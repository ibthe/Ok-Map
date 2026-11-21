"""Minimal Mapbox Vector Tile reader (enough for point features and layer statistics)."""
import gzip
import math
import struct

from build_glyphs import fields, read_varint


def unzip(blob):
    return gzip.decompress(blob) if blob[:2] == b"\x1f\x8b" else blob


def zigzag(n):
    return (n >> 1) ^ -(n & 1)


def _value(b):
    for num, wt, v in fields(b):
        if num == 1:
            return v.decode("utf-8", "replace")
        if num == 2:
            return struct.unpack("<f", v)[0]
        if num == 3:
            return struct.unpack("<d", v)[0]
        if num in (4, 5):
            return v
        if num == 6:
            return zigzag(v)
        if num == 7:
            return bool(v)
    return None


def layers(tile):
    """Yield (name, raw layer bytes) for every layer of a decompressed tile."""
    for num, wt, lb in fields(tile):
        if num == 3 and wt == 2:
            name = ""
            for n2, w2, v2 in fields(lb):
                if n2 == 1:
                    name = v2.decode("utf-8", "replace")
                    break
            yield name, lb


def layer_stats(tile):
    out = {}
    for name, lb in layers(tile):
        out[name] = sum(1 for n, w, _ in fields(lb) if n == 2)
    return out


def point_features(lb):
    """-> list of (props dict, x, y) in tile units, plus extent."""
    keys, values, feats, extent = [], [], [], 4096
    for num, wt, v in fields(lb):
        if num == 2:
            feats.append(v)
        elif num == 3:
            keys.append(v.decode("utf-8", "replace"))
        elif num == 4:
            values.append(_value(v))
        elif num == 5:
            extent = v
    out = []
    for fb in feats:
        tags, gtype, geom = [], 0, b""
        for num, wt, v in fields(fb):
            if num == 2:
                p = 0
                while p < len(v):
                    t, p = read_varint(v, p)
                    tags.append(t)
            elif num == 3:
                gtype = v
            elif num == 4:
                geom = v
        if gtype != 1:
            continue
        props = {}
        for i in range(0, len(tags) - 1, 2):
            if tags[i] < len(keys) and tags[i + 1] < len(values):
                props[keys[tags[i]]] = values[tags[i + 1]]
        p, x, y = 0, 0, 0
        while p < len(geom):
            cmd, p = read_varint(geom, p)
            if cmd & 7 != 1:
                break
            for _ in range(cmd >> 3):
                dx, p = read_varint(geom, p)
                dy, p = read_varint(geom, p)
                x += zigzag(dx)
                y += zigzag(dy)
                out.append((props, x, y))
    return out, extent


def tile_to_lonlat(z, x, y, px, py, extent):
    n = 2.0 ** z
    lon = (x + px / extent) / n * 360.0 - 180.0
    lat = math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * (y + py / extent) / n))))
    return lat, lon


def lonlat_to_tile(z, lat, lon):
    n = 2 ** z
    x = int((lon + 180.0) / 360.0 * n)
    y = int((1.0 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2.0 * n)
    return x, y
