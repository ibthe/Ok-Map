#!/usr/bin/env python3
"""Fails the build when the tileset is empty or lacks essential layers / language names."""
import sqlite3
import sys

import mvt

path = sys.argv[1]
db = sqlite3.connect(path)
print("tiles per zoom:", db.execute("select zoom_level, count(*) from tiles group by 1 order by 1").fetchall())
meta = dict(db.execute("select name, value from metadata"))
print("metadata:", {k: meta.get(k) for k in ("name", "minzoom", "maxzoom", "bounds", "format")})

SPOTS = {"Tbilisi": (41.7151, 44.8271), "Batumi": (41.6168, 41.6367), "Kazbegi": (42.66, 44.64)}
totals = {}
keys = {b"name:ka": 0, b"name:ru": 0, b"name:en": 0, b"name_en": 0}
for city, (lat, lon) in SPOTS.items():
    for z in (6, 8, 10, 12, 14):
        x, y = mvt.lonlat_to_tile(z, lat, lon)
        row = (1 << z) - 1 - y
        r = db.execute("select tile_data from tiles where zoom_level=? and tile_column=? and tile_row=?",
                       (z, x, row)).fetchone()
        if not r:
            print(f"  {city} z{z}: no tile")
            continue
        raw = mvt.unzip(r[0])
        st = mvt.layer_stats(raw)
        print(f"  {city} z{z}: {st}")
        for k, v in st.items():
            totals[k] = totals.get(k, 0) + v
        for k in keys:
            if k in raw:
                keys[k] += 1

print("layer totals:", totals)
print("name keys:", {k.decode(): v for k, v in keys.items()})
missing = [l for l in ("water", "transportation", "landcover", "place", "boundary") if not totals.get(l)]
if missing:
    sys.exit("ERROR: tiles contain no features in layers: %s. The OSM extract was not processed "
             "correctly (see Planetiler log above)." % missing)
if not totals.get("building") or not totals.get("poi"):
    print("WARNING: no buildings/POI found in the sampled z12-14 tiles")
if keys[b"name:ka"] == 0 or keys[b"name:ru"] == 0:
    sys.exit("ERROR: tiles have no name:ka / name:ru. Add a language option to the Planetiler call "
             "(see tools/README.md).")
if keys[b"name:en"] == 0 and keys[b"name_en"] == 0:
    sys.exit("ERROR: tiles have no English names")
print("OK")
