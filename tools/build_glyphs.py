#!/usr/bin/env python3
"""
Builds the label font (SDF glyph .pbf files, Mapbox glyphs.proto) for Latin + Cyrillic + Georgian
at build time, so the finished APK needs no network.

Why this is not a plain "download and trust" script: some font hosts answer with a tiny placeholder
instead of a real font (every range came back as the same 3092 bytes). File size proves nothing, so
every candidate is judged by the number of REAL glyphs (glyphs that have ink) it contains in the
Latin, Cyrillic and Georgian ranges, and the result is verified at the end against a list of
characters that must be drawable ("თბილისი Тбилиси Tbilisi").

Sources, in this order (first one that really covers a script wins that script):
  1. your own fonts in tools/fonts/*.ttf|otf
  2. ready-made glyph files from GitHub repositories (downloaded as-is)
  3. TTF fonts from GitHub repositories (Noto Sans, Noto Sans Georgian) -> converted to SDF here
  4. fonts installed on the build machine (apt: fonts-noto-core, DejaVu)

Needs for step 3/4:  pip install pillow numpy scipy fonttools
Usage: build_glyphs.py <output dir>      e.g. app/src/main/assets/glyphs/NotoSans
"""
import concurrent.futures as cf
import glob
import gzip
import os
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

FONT_NAME = "NotoSans"
RANGES = [(i, i + 255) for i in range(0, 65536, 256)]

# script -> (glyph file, minimum number of glyphs WITH INK that a real font has there)
NEED = {
    "latin": ("0-255", 150),
    "cyrillic": ("1024-1279", 200),
    "georgian": ("4096-4351", 45),
}
MUST_DRAW = "Tbilisi თბილისი Тбилиси რუსთაველი ქუთაისი Москва"
SHOULD_DRAW = "–—’‘“”№°′″"  # only a warning if missing

TOOLS = os.path.dirname(os.path.abspath(__file__))

PBF_SOURCES = [  # ready-made SDF glyphs, {r} = "0-255"
    ("protomaps/basemaps-assets: Noto Sans Regular",
     "https://raw.githubusercontent.com/protomaps/basemaps-assets/main/fonts/Noto%20Sans%20Regular/{r}.pbf"),
    ("maplibre/demotiles: Noto Sans Regular",
     "https://raw.githubusercontent.com/maplibre/demotiles/gh-pages/font/Noto%20Sans%20Regular/{r}.pbf"),
    ("maplibre/demotiles: Open Sans + Arial Unicode",
     "https://raw.githubusercontent.com/maplibre/demotiles/gh-pages/font/"
     "Open%20Sans%20Regular%2CArial%20Unicode%20MS%20Regular/{r}.pbf"),
    ("klokantech/klokantech-gl-fonts: KlokanTech Noto Sans Regular",
     "https://raw.githubusercontent.com/klokantech/klokantech-gl-fonts/master/KlokanTech%20Noto%20Sans%20Regular/{r}.pbf"),
    ("openmaptiles/fonts (gh-pages): Noto Sans Regular",
     "https://raw.githubusercontent.com/openmaptiles/fonts/gh-pages/Noto%20Sans%20Regular/{r}.pbf"),
]

TTF_SOURCES = [  # (label, url)
    ("notofonts: Noto Sans Regular",
     "https://raw.githubusercontent.com/notofonts/notofonts.github.io/main/fonts/NotoSans/hinted/ttf/NotoSans-Regular.ttf"),
    ("notofonts: Noto Sans Georgian Regular",
     "https://raw.githubusercontent.com/notofonts/notofonts.github.io/main/fonts/NotoSansGeorgian/hinted/ttf/NotoSansGeorgian-Regular.ttf"),
    ("notofonts/latin-greek-cyrillic: Noto Sans Regular",
     "https://raw.githubusercontent.com/notofonts/latin-greek-cyrillic/main/fonts/NotoSans/hinted/ttf/NotoSans-Regular.ttf"),
    ("notofonts/georgian: Noto Sans Georgian Regular",
     "https://raw.githubusercontent.com/notofonts/georgian/main/fonts/NotoSansGeorgian/hinted/ttf/NotoSansGeorgian-Regular.ttf"),
    ("google/fonts: Noto Sans (variable)",
     "https://raw.githubusercontent.com/google/fonts/main/ofl/notosans/NotoSans%5Bwdth%2Cwght%5D.ttf"),
    ("google/fonts: Noto Sans Georgian (variable)",
     "https://raw.githubusercontent.com/google/fonts/main/ofl/notosansgeorgian/NotoSansGeorgian%5Bwdth%2Cwght%5D.ttf"),
]

SYSTEM_FONTS = [
    "/usr/share/fonts/truetype/noto/NotoSans-Regular.ttf",
    "/usr/share/fonts/truetype/noto/NotoSansGeorgian-Regular.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",  # covers Latin + Cyrillic + Georgian
]


# ======================================================================== glyphs.proto helpers
def read_varint(buf, pos):
    shift = result = 0
    while True:
        b = buf[pos]
        pos += 1
        result |= (b & 0x7F) << shift
        if not b & 0x80:
            return result, pos
        shift += 7


def varint(n):
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def fields(buf):
    pos = 0
    while pos < len(buf):
        key, pos = read_varint(buf, pos)
        num, wt = key >> 3, key & 7
        if wt == 0:
            v, pos = read_varint(buf, pos)
        elif wt == 2:
            ln, pos = read_varint(buf, pos)
            v = buf[pos:pos + ln]
            pos += ln
        elif wt == 1:
            v = buf[pos:pos + 8]
            pos += 8
        elif wt == 5:
            v = buf[pos:pos + 4]
            pos += 4
        else:
            raise ValueError("bad wire type %d" % wt)
        yield num, wt, v


def ld(num, data):
    return varint((num << 3) | 2) + varint(len(data)) + data


def vf(num, value):
    return varint(num << 3) + varint(value)


def zigzag(n):
    return (n << 1) ^ (n >> 63)


def encode_glyph(gid, bitmap, width, height, left, top, advance):
    out = vf(1, gid)
    if bitmap:
        out += ld(2, bitmap)
    return out + vf(3, width) + vf(4, height) + vf(5, zigzag(left)) + vf(6, zigzag(top)) + vf(7, advance)


def parse(buf):
    """-> {glyph id: raw glyph bytes}; raises on a broken file"""
    glyphs = {}
    for num, wt, stack in fields(buf):
        if num != 1 or wt != 2:
            continue
        for n2, w2, g in fields(stack):
            if n2 == 3 and w2 == 2:
                for n3, w3, val in fields(g):
                    if n3 == 1 and w3 == 0:
                        glyphs[val] = g
                        break
    return glyphs


def has_ink(raw):
    width = 0
    bitmap = False
    for n, wt, v in fields(raw):
        if n == 3 and wt == 0:
            width = v
        elif n == 2 and wt == 2 and len(v) > 0:
            bitmap = True
    return width > 0 and bitmap


def real_count(glyphs):
    n = 0
    for raw in glyphs.values():
        try:
            n += has_ink(raw)
        except Exception:  # noqa
            pass
    return n


def build_file(glyph_dict, rng):
    stack = ld(1, FONT_NAME.encode()) + ld(2, rng.encode())
    for gid in sorted(glyph_dict):
        stack += ld(3, glyph_dict[gid])
    return ld(1, stack)


# ======================================================================== download
def fetch(url, tries=3):
    for i in range(tries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "ok-map-build"})
            with urllib.request.urlopen(req, timeout=60) as r:
                data = r.read()
            if data[:2] == b"\x1f\x8b":
                data = gzip.decompress(data)
            return data
        except urllib.error.HTTPError as e:
            if e.code in (404, 403, 410):
                return None
            time.sleep(1.5 * (i + 1))
        except Exception:  # noqa
            time.sleep(1.5 * (i + 1))
    return None


# ======================================================================== providers
class PbfProvider:
    """Ready-made glyph files from a URL template."""

    def __init__(self, label, template):
        self.label = label
        self.template = template
        self.cache = {}

    def get(self, rng):
        if rng not in self.cache:
            data = fetch(self.template.format(r=rng))
            glyphs = {}
            if data:
                try:
                    glyphs = parse(data)
                except Exception:  # noqa
                    glyphs = {}
            self.cache[rng] = glyphs
        return self.cache[rng]


SS = 4          # supersampling for the distance field
SIZE = 24       # em size in pixels (same as Mapbox/fontnik)
BUF = 3         # border around each glyph
RADIUS = 8
CUTOFF = 0.25


class TtfProvider:
    """Converts a TrueType font to SDF glyphs (same format and metrics as fontnik)."""

    def __init__(self, label, path):
        from fontTools.ttLib import TTFont
        self.label = label
        self.path = path
        font = TTFont(path, lazy=True, fontNumber=0)
        self.cmap = {cp: n for cp, n in font.getBestCmap().items() if cp < 0x10000 and cp >= 32
                     and not 0x7F <= cp <= 0x9F}
        font.close()
        self.cache = {}
        self.lock = threading.Lock()
        self._r = None

    def _renderer(self):
        if self._r is None:
            from PIL import ImageFont
            layout = getattr(getattr(ImageFont, "Layout", None), "BASIC", None)
            kw = {"layout_engine": layout} if layout is not None else {}
            self.big = ImageFont.truetype(self.path, SIZE * SS, **kw)
            small = ImageFont.truetype(self.path, SIZE, **kw)
            self.ascender = int(small.getmetrics()[0])
            self._r = True
        return self._r

    def _glyph(self, cp):
        import numpy as np
        from PIL import Image, ImageDraw
        from scipy.ndimage import distance_transform_edt

        ch = chr(cp)
        em = SIZE * SS
        advance = int(round(self.big.getlength(ch) / SS))
        canvas = Image.new("L", (3 * em, 3 * em), 0)
        ox, oy = em, 2 * em  # origin on the baseline
        ImageDraw.Draw(canvas).text((ox, oy), ch, font=self.big, fill=255, anchor="ls")
        bb = canvas.getbbox()
        if not bb:
            return encode_glyph(cp, b"", 0, 0, 0, 0, advance)

        x0, y0, x1, y1 = bb
        left_hi, top_hi = x0 - ox, oy - y0
        right_hi, bottom_hi = x1 - ox, oy - y1
        left = left_hi // SS
        right = -((-right_hi) // SS)
        top = -((-top_hi) // SS)
        bottom = bottom_hi // SS
        w, h = right - left, top - bottom
        if w <= 0 or h <= 0:
            return encode_glyph(cp, b"", 0, 0, 0, 0, advance)

        box = (ox + (left - BUF) * SS, oy - (top + BUF) * SS,
               ox + (right + BUF) * SS, oy - (bottom - BUF) * SS)
        arr = np.asarray(canvas.crop(box), dtype=np.uint8) >= 128
        d_out = distance_transform_edt(~arr)
        d_in = distance_transform_edt(arr)
        signed = np.where(arr, -(d_in - 0.5), d_out - 0.5) / SS          # >0 outside, in output pixels
        hh, ww = signed.shape
        signed = signed.reshape(hh // SS, SS, ww // SS, SS).mean(axis=(1, 3))
        value = 255.0 * (1.0 - (signed / RADIUS + CUTOFF))
        bitmap = np.clip(np.rint(value), 0, 255).astype(np.uint8).tobytes()
        return encode_glyph(cp, bitmap, w, h, left, top - self.ascender, advance)

    def get(self, rng):
        if rng in self.cache:
            return self.cache[rng]
        lo, hi = (int(x) for x in rng.split("-"))
        cps = [cp for cp in range(lo, hi + 1) if cp in self.cmap]
        glyphs = {}
        if cps:
            with self.lock:
                self._renderer()
                for cp in cps:
                    try:
                        glyphs[cp] = self._glyph(cp)
                    except Exception as e:  # noqa
                        print("    glyph U+%04X skipped: %s" % (cp, e))
        self.cache[rng] = glyphs
        return glyphs


def font_ok(path):
    try:
        with open(path, "rb") as f:
            magic = f.read(4)
        return magic in (b"\x00\x01\x00\x00", b"OTTO", b"true", b"ttcf")
    except OSError:
        return False


def candidates():
    # 1. fonts the user put into tools/fonts
    for p in sorted(glob.glob(os.path.join(TOOLS, "fonts", "*.[to]tf"))):
        if font_ok(p):
            yield lambda p=p: TtfProvider("local " + os.path.basename(p), p)
    # 2. ready-made glyph files from GitHub
    for label, tpl in PBF_SOURCES:
        yield lambda label=label, tpl=tpl: PbfProvider(label, tpl)
    # 3. TTF from GitHub
    tmp = tempfile.mkdtemp(prefix="fonts-")
    for i, (label, url) in enumerate(TTF_SOURCES):
        def make(label=label, url=url, i=i):
            data = fetch(url)
            if not data:
                return None
            path = os.path.join(tmp, "f%d.ttf" % i)
            with open(path, "wb") as f:
                f.write(data)
            return TtfProvider(label, path) if font_ok(path) else None
        yield make
    # 4. fonts installed on this machine
    for p in SYSTEM_FONTS:
        if os.path.exists(p) and font_ok(p):
            yield lambda p=p: TtfProvider("system " + os.path.basename(p), p)


# ======================================================================== main
def main(out_dir):
    chosen, covered = [], set()
    print("Looking for a font that really contains Latin, Cyrillic and Georgian glyphs...")
    for make in candidates():
        try:
            prov = make()
        except Exception as e:  # noqa
            print("  (candidate failed: %s)" % e)
            continue
        if prov is None:
            continue
        cov = {s: real_count(prov.get(rng)) for s, (rng, _) in NEED.items()}
        add = [s for s, (_, need) in NEED.items() if cov[s] >= need and s not in covered]
        print("  %-62s latin=%-4d cyrillic=%-4d georgian=%-4d %s" % (
            prov.label, cov["latin"], cov["cyrillic"], cov["georgian"],
            ("-> USED for " + ",".join(add)) if add else "-> rejected"))
        if add:
            chosen.append(prov)
            covered.update(add)
        if len(covered) == len(NEED):
            break

    missing = [s for s in NEED if s not in covered]
    if missing:
        sys.exit("ERROR: no usable font found for: %s.\n"
                 "Put a TTF that covers it (e.g. NotoSans-Regular.ttf, NotoSansGeorgian-Regular.ttf) "
                 "into tools/fonts/ and run the build again." % ", ".join(missing))

    os.makedirs(out_dir, exist_ok=True)
    for old in glob.glob(os.path.join(out_dir, "*.pbf")):
        os.remove(old)

    def work(r):
        rng = "%d-%d" % r
        merged = {}
        for prov in chosen:
            for gid, raw in prov.get(rng).items():
                merged.setdefault(gid, raw)
        data = build_file(merged, rng)
        with open(os.path.join(out_dir, rng + ".pbf"), "wb") as f:
            f.write(data)
        return len(data)

    # network-backed providers are fetched in parallel; TTF rendering is serialized by a lock
    with cf.ThreadPoolExecutor(8) as ex:
        total = sum(ex.map(work, RANGES))
    print("written: %d files, %.1f MB" % (len(RANGES), total / 1e6))

    # ---- verification: the characters of the real labels must be drawable
    ink = set()
    for rng_start, rng_end in RANGES:
        path = os.path.join(out_dir, "%d-%d.pbf" % (rng_start, rng_end))
        with open(path, "rb") as f:
            for gid, raw in parse(f.read()).items():
                if has_ink(raw):
                    ink.add(gid)
    for s, (rng, need) in NEED.items():
        lo, hi = (int(x) for x in rng.split("-"))
        print("  %-9s %d glyphs with ink in %s" % (s, sum(lo <= g <= hi for g in ink), rng))
    absent = sorted({c for c in MUST_DRAW if c != " " and ord(c) not in ink})
    if absent:
        sys.exit("ERROR: these characters cannot be drawn by the chosen fonts: " + " ".join(absent))
    soft = sorted({c for c in SHOULD_DRAW if ord(c) not in ink})
    if soft:
        print("warning: no glyph for punctuation:", " ".join(soft))
    print("OK: labels in Georgian, Russian and English can be drawn.")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "app/src/main/assets/glyphs/NotoSans")
