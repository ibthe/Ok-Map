#!/usr/bin/env python3
"""Synthetic test: a 3000 m cone must give nested closed contour rings with the right ele values and index lines."""
import gzip
import io
import os
import sqlite3
import sys
import tempfile

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import build_contours as bc


def terrarium(h):
    v = h + 32768.0
    r = np.floor(v / 256.0)
    g = np.floor(v - r * 256.0)
    b = np.floor((v - np.floor(v)) * 256.0)
    return np.dstack([r, g, b]).astype("uint8")


def read_varint(b, i):
    n = s = 0
    while True:
        c = b[i]; i += 1
        n |= (c & 0x7F) << s; s += 7
        if not c & 0x80:
            return n, i


def decode(tile):
    """-> list of (ele, idx, npts) from a gzip MVT with layer 'contour'"""
    b = gzip.decompress(tile)
    i = 0
    out = []
    n, i = read_varint(b, i)            # tag of the layer field
    ln, i = read_varint(b, i)
    layer, k = b[i:i + ln], 0
    keys, vals, feats = [], [], []
    while k < len(layer):
        tag, k = read_varint(layer, k)
        f, w = tag >> 3, tag & 7
        if w == 0:
            _, k = read_varint(layer, k)
        else:
            l, k = read_varint(layer, k)
            body = layer[k:k + l]; k += l
            if f == 3: keys.append(body.decode())
            elif f == 4: vals.append(read_varint(body, 1)[0])
            elif f == 2: feats.append(body)
    for fb in feats:
        k, tags, geom = 0, [], []
        while k < len(fb):
            tag, k = read_varint(fb, k)
            f, w = tag >> 3, tag & 7
            if w == 0:
                _, k = read_varint(fb, k)
            else:
                l, k = read_varint(fb, k)
                body = fb[k:k + l]; k += l
                j = 0
                arr = []
                while j < len(body):
                    v, j = read_varint(body, j); arr.append(v)
                if f == 2: tags = arr
                elif f == 4: geom = arr
        props = {keys[tags[m]]: vals[tags[m + 1]] for m in range(0, len(tags), 2)}
        out.append((props["ele"], props["idx"], (len(geom) - 3) // 2 + 1))
    return out


tmp = tempfile.mkdtemp()
src = os.path.join(tmp, "relief.mbtiles")
db = sqlite3.connect(src)
db.execute("create table tiles (zoom_level integer, tile_column integer, tile_row integer, tile_data blob)")
yy, xx = np.mgrid[0:512, 0:512]
h = 3000.0 * np.exp(-(((xx - 256) ** 2 + (yy - 256) ** 2) / (2 * 90.0 ** 2)))
Z = bc.SRC_Z
for ty in range(2):
    for tx in range(2):
        t = terrarium(h[ty * 256:(ty + 1) * 256, tx * 256:(tx + 1) * 256])
        buf = io.BytesIO(); Image.fromarray(t).save(buf, "PNG")
        db.execute("insert into tiles values (?,?,?,?)", (Z, 1000 + tx, (1 << Z) - 1 - (700 + ty), buf.getvalue()))
db.commit(); db.close()
out = os.path.join(tmp, "contours.mbtiles")
dem, x0, y0 = bc.load_dem(src)
assert dem.shape == (512, 512) and abs(dem.max() - 3000) < 5, dem.max()
bc.build(dem, x0, y0, out)
o = sqlite3.connect(out)
z12 = o.execute("select zoom_level, tile_column, tile_row, tile_data from tiles where zoom_level=12").fetchall()
assert z12, "no z12 tiles"
eles, idxs = set(), set()
for _, _, _, d in z12:
    for ele, idx, n in decode(d):
        eles.add(ele); idxs.add((ele, idx))
assert eles >= {500, 1000, 2000, 2950}, sorted(eles)           # 50 m steps at z12
assert (300, 0) in idxs and (500, 1) in idxs and (1000, 1) in idxs, "index line every 5th level (250 m at z12)"
z9 = o.execute("select tile_data from tiles where zoom_level=9").fetchall()
e9 = {e for (d,) in z9 for e, _, _ in decode(d)}
assert e9 and all(e % 200 == 0 for e in e9) and 2950 not in e9, sorted(e9)
print("contour tests passed:", len(z12), "z12 tiles,", len(eles), "levels at z12, z9 levels", sorted(e9)[:5], "...")
