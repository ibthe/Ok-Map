#!/usr/bin/env python3
"""Builds a raster satellite imagery MBTiles (sat.mbtiles) for the area of map.mbtiles from an XYZ tile server.

Usage: build_satellite.py <map.mbtiles> <sat.mbtiles> <url_template> [max_zoom=12] [min_zoom=0] [detail ...]
  url_template: an XYZ tile URL such as https://example.org/tiles/{z}/{x}/{y}.jpg   ({z} {x} {y}; file:// works too)
                or a WMS GetMap URL with {bbox} (EPSG:3857 tile box), e.g.
                https://host/wms?SERVICE=WMS&VERSION=1.1.1&REQUEST=GetMap&LAYERS=orthophoto&SRS=EPSG:3857&BBOX={bbox}&WIDTH=256&HEIGHT=256&FORMAT=image/jpeg
  detail:       "west,south,east,north,zoom" - extra high-zoom areas (a city, a route) on top of the whole-country max_zoom

Use only a tile source whose licence allows offline copies (open imagery such as Sentinel-2 mosaics, your own
orthophotos, ...). Google / Bing / Apple imagery may not be downloaded and stored.
PNG tiles are converted to JPEG (quality 82). Size grows about 4x with every zoom level: z10 ~ 10 MB, z11 ~ 35 MB, z12 ~ 130 MB for a country the size of Georgia.
"""
import io
import math
import sqlite3
import sys
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor


def tile_range(z, w, s, e, n):
    def tx(lon):
        return int((lon + 180.0) / 360.0 * (1 << z))

    def ty(lat):
        la = math.radians(max(-85.05, min(85.05, lat)))
        return int((1 - math.asinh(math.tan(la)) / math.pi) / 2 * (1 << z))
    return tx(w), tx(e), ty(n), ty(s)


def tile_bbox_3857(z, x, y):
    """EPSG:3857 box of an XYZ tile: minx,miny,maxx,maxy"""
    size = 40075016.6855784 / (1 << z)
    minx = -20037508.3427892 + x * size
    maxy = 20037508.3427892 - y * size
    return f"{minx},{maxy - size},{minx + size},{maxy}"


def tile_url(template, z, x, y):
    return template.format(z=z, x=x, y=y, bbox=tile_bbox_3857(z, x, y))


MIN_BYTES = 600          # smaller answers are blank / transparent tiles (outside the coverage of the source)


def recompress(data, quality=82):
    """PNG (often 100+ KB at high zoom) -> JPEG, about 5-8x smaller; transparent areas become dark grey"""
    if data[:4] != b"\x89PNG":
        return data, "jpg" if data[:2] == b"\xff\xd8" else "png"
    try:
        from PIL import Image
        im = Image.open(io.BytesIO(data))
        if im.mode in ("RGBA", "LA", "P"):
            im = im.convert("RGBA")
            bg = Image.new("RGB", im.size, (48, 48, 48))
            bg.paste(im, mask=im.split()[-1])
            im = bg
        else:
            im = im.convert("RGB")
        out = io.BytesIO()
        im.save(out, "JPEG", quality=quality, optimize=True)
        return out.getvalue(), "jpg"
    except Exception:
        return data, "png"


def fetch(url):
    for attempt in range(3):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "OkMap-build/1.0 (offline map builder)"})
            with urllib.request.urlopen(req, timeout=30) as r:
                return r.read()
        except Exception:
            time.sleep(1 + attempt)
    return None


def main(map_path, out_path, template, max_zoom=12, min_zoom=0, details=()):
    meta = dict(sqlite3.connect(map_path).execute("select name, value from metadata").fetchall())
    w, s, e, n = (float(v) for v in meta["bounds"].split(","))
    jobs = []
    for z in range(min_zoom, max_zoom + 1):
        x0, x1, y0, y1 = tile_range(z, w, s, e, n)
        jobs += [(z, x, y) for x in range(x0, x1 + 1) for y in range(y0, y1 + 1)]
    for d in details:                                       # "w,s,e,n,z": higher zoom only inside this box
        dw, ds, de, dn, dz = (float(v) for v in d.split(","))
        for z in range(max_zoom + 1, int(dz) + 1):
            x0, x1, y0, y1 = tile_range(z, dw, ds, de, dn)
            jobs += [(z, x, y) for x in range(x0, x1 + 1) for y in range(y0, y1 + 1)]
    jobs = sorted(set(jobs))
    top = max(j[0] for j in jobs)
    print(f"{len(jobs)} tiles, zoom {min_zoom}-{top}")
    db = sqlite3.connect(out_path)
    db.executescript("""drop table if exists tiles; drop table if exists metadata;
        create table tiles (zoom_level integer, tile_column integer, tile_row integer, tile_data blob);
        create unique index tile_index on tiles (zoom_level, tile_column, tile_row);
        create table metadata (name text, value text);""")
    ok = size = 0
    fmt = "jpg"
    with ThreadPoolExecutor(8) as pool:
        for (z, x, y), data in zip(jobs, pool.map(lambda j: fetch(tile_url(template, *j)), jobs)):
            if not data or len(data) < MIN_BYTES:
                continue
            data, f = recompress(data)
            if f == "png":
                fmt = "png"
            db.execute("insert or replace into tiles values (?,?,?,?)", (z, x, (1 << z) - 1 - y, data))
            ok += 1
            size += len(data)
            if ok % 2000 == 0:
                db.commit()
                print(f"  {ok} tiles, {size / 1e6:.0f} MB")
    for k, v in {"name": "satellite", "format": fmt, "minzoom": str(min_zoom), "maxzoom": str(top),
                 "bounds": f"{w},{s},{e},{n}", "type": "baselayer",
                 "attribution": "© National Agency of Public Registry of Georgia (NAPR)"}.items():
        db.execute("insert into metadata values (?,?)", (k, v))
    db.commit()
    db.close()
    print(f"imagery: {ok}/{len(jobs)} tiles, {size / 1e6:.1f} MB")
    # a source that covers only the country (and not the whole bounding box) is normal; nothing at all is an error
    if ok == 0 or ok < len(jobs) * 0.05:
        sys.exit("almost no tiles could be downloaded - check the URL template")


if __name__ == "__main__":
    if len(sys.argv) < 4:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4]) if len(sys.argv) > 4 else 12,
         int(sys.argv[5]) if len(sys.argv) > 5 else 0, sys.argv[6:])
