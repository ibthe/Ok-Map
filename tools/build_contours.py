#!/usr/bin/env python3
"""Contour lines (isohypses) as vector tiles, computed from the relief DEM (Terrarium PNG tiles of relief.mbtiles).

Usage: build_contours.py <relief.mbtiles> <contours.mbtiles>      (needs: pip install numpy pillow scipy contourpy shapely)

Layer "contour": LineString features with  ele (metres)  and  idx (1 = index line every 5th level, 0 = ordinary).
Level step per zoom (metres): z9-10: 200, z11: 100, z12: 50 (higher zooms reuse the z12 tiles).
"""
import gzip
import io
import math
import sqlite3
import struct
import sys

import numpy as np

STEP = {9: 200, 10: 200, 11: 100, 12: 50}
EXTENT = 4096
SRC_Z = 11            # zoom of the DEM tiles we read


# ---------------------------------------------------------------- minimal MVT writer
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


def zigzag(n):
    return (n << 1) ^ (n >> 31)


def field(num, wire, payload):
    return varint(num << 3 | wire) + payload


def ldelim(num, data):
    return field(num, 2, varint(len(data)) + data)


def encode_lines(lines):
    """lines: list of (ele, idx, [(x, y) int tile units]) -> one Tile message with the layer "contour" """
    keys = ["ele", "idx"]
    values, vidx = [], {}

    def val(v):
        if v not in vidx:
            vidx[v] = len(values)
            values.append(v)
        return vidx[v]

    feats = b""
    for ele, idx, pts in lines:
        geom = [(1 << 3) | 1]
        x0, y0 = pts[0]
        geom += [zigzag(x0), zigzag(y0)]
        geom.append(((len(pts) - 1) << 3) | 2)
        px, py = x0, y0
        for x, y in pts[1:]:
            geom += [zigzag(x - px), zigzag(y - py)]
            px, py = x, y
        tags = [0, val(("i", ele)), 1, val(("i", idx))]
        feats += ldelim(2, field(2, 2, varint(len(b"".join(varint(t) for t in tags))) + b"".join(varint(t) for t in tags))
                        + field(3, 0, varint(2))
                        + field(4, 2, varint(len(b"".join(varint(g) for g in geom))) + b"".join(varint(g) for g in geom)))
    layer = field(15, 0, varint(2)) + ldelim(1, b"contour") + feats
    for k in keys:
        layer += ldelim(3, k.encode())
    for kind, v in values:
        layer += ldelim(4, field(4, 0, varint(v)))
    layer += field(5, 0, varint(EXTENT))
    return ldelim(3, layer)


# ---------------------------------------------------------------- DEM
def load_dem(path):
    from PIL import Image
    db = sqlite3.connect(path)
    rows = db.execute("select tile_column, tile_row, tile_data from tiles where zoom_level=?", (SRC_Z,)).fetchall()
    if not rows:
        sys.exit(f"no z{SRC_Z} tiles in {path}")
    xs = [r[0] for r in rows]
    ys = [(1 << SRC_Z) - 1 - r[1] for r in rows]          # TMS -> XYZ
    x0, x1, y0, y1 = min(xs), max(xs), min(ys), max(ys)
    dem = np.zeros(((y1 - y0 + 1) * 256, (x1 - x0 + 1) * 256), dtype=np.float32)
    for (x, tms_y, data), y in zip(rows, ys):
        a = np.asarray(Image.open(io.BytesIO(data)).convert("RGB"), dtype=np.float32)
        h = a[:, :, 0] * 256.0 + a[:, :, 1] + a[:, :, 2] / 256.0 - 32768.0
        dem[(y - y0) * 256:(y - y0 + 1) * 256, (x - x0) * 256:(x - x0 + 1) * 256] = h
    return dem, x0, y0


def simplify(xy, tol):
    """Douglas-Peucker (shapely) when available, otherwise plain distance decimation"""
    try:
        from shapely.geometry import LineString
        return np.asarray(LineString(xy).simplify(tol, preserve_topology=False).coords)
    except ImportError:
        keep = [0]
        for i in range(1, len(xy) - 1):
            if math.hypot(*(xy[i] - xy[keep[-1]])) >= tol:
                keep.append(i)
        keep.append(len(xy) - 1)
        return xy[keep]


def contour_lines(dem, levels):
    import contourpy
    gen = contourpy.contour_generator(z=dem, line_type=contourpy.LineType.Separate)
    res = {}
    for lv in levels:
        res[lv] = [np.asarray(l) for l in gen.lines(float(lv)) if len(l) >= 3]
    return res


def build(dem, x0, y0, out_path, sigma=1.0):
    from scipy.ndimage import gaussian_filter
    dem = gaussian_filter(dem, sigma)
    hi = float(dem.max())
    base = 50
    levels = [lv for lv in range(base, int(hi) + 1, base)]
    print(f"DEM {dem.shape[1]}x{dem.shape[0]} px, max {hi:.0f} m, {len(levels)} levels")
    lines = contour_lines(dem, levels)
    tiles = {}                                              # (z, x, y) -> list of lines
    for z, step in STEP.items():
        scale = 2.0 ** (z - SRC_Z)                           # z11 tile units -> tile units of zoom z
        for lv, polys in lines.items():
            if lv % step:
                continue
            idx = 1 if lv % (step * 5) == 0 else 0
            for pl in polys:
                tx = (pl[:, 0] + 0.5) / 256.0 + x0           # position in z11 tile units
                ty = (pl[:, 1] + 0.5) / 256.0 + y0
                tx *= scale
                ty *= scale
                pts = simplify(np.column_stack([tx, ty]), 0.3 / 256.0)    # ~0.3 px of the rendered tile
                if len(pts) < 2:
                    continue
                ix, iy = np.floor(pts[:, 0]).astype(int), np.floor(pts[:, 1]).astype(int)
                cut = np.nonzero((ix[1:] != ix[:-1]) | (iy[1:] != iy[:-1]))[0] + 1
                for seg in np.split(np.arange(len(pts)), cut):
                    a, b = max(0, seg[0] - 1), min(len(pts), seg[-1] + 2)       # one vertex of overlap each side
                    run = pts[a:b]
                    key = (z, int(ix[seg[0]]), int(iy[seg[0]]))
                    q = np.round((run - np.array([key[1], key[2]])) * EXTENT).astype(int)
                    keep = np.ones(len(q), bool)
                    keep[1:] = np.any(q[1:] != q[:-1], axis=1)
                    q = q[keep]
                    if len(q) >= 2:
                        tiles.setdefault(key, []).append((lv, idx, [tuple(p) for p in q]))
    db = sqlite3.connect(out_path)
    db.executescript("""drop table if exists tiles; drop table if exists metadata;
        create table tiles (zoom_level integer, tile_column integer, tile_row integer, tile_data blob);
        create unique index tile_index on tiles (zoom_level, tile_column, tile_row);
        create table metadata (name text, value text);""")
    total = 0
    for (z, x, y), ln in tiles.items():
        data = gzip.compress(encode_lines(ln), 6)
        total += len(data)
        db.execute("insert into tiles values (?,?,?,?)", (z, x, (1 << z) - 1 - y, data))
    meta = {"name": "contours", "format": "pbf", "minzoom": "9", "maxzoom": "12", "type": "overlay",
            "json": '{"vector_layers":[{"id":"contour","fields":{"ele":"Number","idx":"Number"},"minzoom":9,"maxzoom":12}]}'}
    db.executemany("insert into metadata values (?,?)", meta.items())
    db.commit()
    db.close()
    print(f"contours: {len(tiles)} tiles, {total / 1e6:.1f} MB")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    d, x0, y0 = load_dem(sys.argv[1])
    build(d, x0, y0, sys.argv[2])
