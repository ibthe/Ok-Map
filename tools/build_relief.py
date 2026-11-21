#!/usr/bin/env python3
"""Builds relief.mbtiles (terrain tiles, Terrarium encoding) for Georgia from the public AWS Terrain Tiles
dataset. Used for hillshading and for the elevation readout. Usage: build_relief.py <out.mbtiles>"""
import concurrent.futures as cf
import math
import os
import sqlite3
import sys
import time
import urllib.request

OUT = sys.argv[1]
MAXZ = 11
BBOX = (39.8, 40.9, 47.0, 43.7)  # W S E N
URL = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png"


def xy(z, lat, lon):
    n = 2 ** z
    x = int((lon + 180) / 360 * n)
    y = int((1 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2 * n)
    return x, y


jobs = []
for z in range(0, MAXZ + 1):
    x0, y1 = xy(z, BBOX[1], BBOX[0])
    x1, y0 = xy(z, BBOX[3], BBOX[2])
    for x in range(x0, x1 + 1):
        for y in range(y0, y1 + 1):
            jobs.append((z, x, y))
print("tiles to download:", len(jobs))


def get(job):
    z, x, y = job
    for i in range(4):
        try:
            with urllib.request.urlopen(URL.format(z=z, x=x, y=y), timeout=60) as r:
                return job, r.read()
        except Exception:
            time.sleep(1.5 * (i + 1))
    return job, None


tmp = OUT + ".tmp"
if os.path.exists(tmp):
    os.remove(tmp)
db = sqlite3.connect(tmp)
db.executescript("""
create table metadata(name text, value text);
create table tiles(zoom_level integer, tile_column integer, tile_row integer, tile_data blob);
create unique index tile_index on tiles(zoom_level, tile_column, tile_row);
""")
ok = fail = 0
with cf.ThreadPoolExecutor(16) as ex:
    for (z, x, y), data in ex.map(get, jobs):
        if data is None:
            fail += 1
            continue
        db.execute("insert into tiles values (?,?,?,?)", (z, x, (1 << z) - 1 - y, data))
        ok += 1
meta = {"name": "relief", "format": "png", "minzoom": "0", "maxzoom": str(MAXZ),
        "bounds": ",".join(map(str, BBOX)), "type": "baselayer", "version": "1",
        "description": "Terrarium elevation tiles"}
db.executemany("insert into metadata values (?,?)", meta.items())
db.commit()
db.close()
print("ok:", ok, "failed:", fail)
if fail > len(jobs) * 0.02 or ok == 0:
    os.remove(tmp)
    sys.exit("ERROR: too many relief tiles failed")
os.replace(tmp, OUT)
