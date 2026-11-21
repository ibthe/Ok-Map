#!/usr/bin/env python3
"""Builds the offline search index (places.db) from the points inside the vector tiles.
Usage: build_places.py <map.mbtiles> <places.db>"""
import json
import math
import os
import sqlite3
import sys

import mvt

src, dst = sys.argv[1], sys.argv[2]
db = sqlite3.connect(src)
meta = dict(db.execute("select name, value from metadata"))
Z = int(meta.get("maxzoom") or 14)
print("reading zoom", Z)

WANT = {"place", "poi", "mountain_peak", "aerodrome_label", "water_name"}
PRIO = {"city": 0, "town": 1, "village": 2, "suburb": 3, "quarter": 3, "hamlet": 3, "neighbourhood": 3,
        "isolated_dwelling": 3, "island": 3, "state": 3, "province": 3}
# transport stays easy to find: stations and airports rank with towns, stops rank below them
TRANSPORT_PRIO = {"station": 3, "subway": 3, "bus_station": 3, "ferry_terminal": 3, "harbor": 4,
                  "halt": 4, "tram_stop": 5, "bus_stop": 5}
found = {}
tiles = 0
for zoom, x, row, blob in db.execute("select zoom_level, tile_column, tile_row, tile_data from tiles "
                                     "where zoom_level=?", (Z,)):
    tiles += 1
    y = (1 << zoom) - 1 - row
    raw = mvt.unzip(blob)
    for lname, lb in mvt.layers(raw):
        if lname not in WANT:
            continue
        feats, extent = mvt.point_features(lb)
        for props, px, py in feats:
            name = props.get("name")
            if not name or not isinstance(name, str):
                continue
            ka = props.get("name:ka") or name
            ru = props.get("name:ru") or ""
            en = props.get("name:en") or props.get("name_en") or props.get("name:latin") or ""
            cls = props.get("class") or ""
            sub = props.get("subclass") or ""
            if lname == "place":
                if cls in ("continent", "ocean", "sea", "country"):
                    continue
                kind, prio = cls, PRIO.get(cls, 4)
            elif lname == "mountain_peak":
                kind, prio = "peak", 4
            elif lname == "aerodrome_label":
                kind, prio = "aerodrome", 2
            elif lname == "water_name":
                kind, prio = "water", 4
            else:
                kind, prio = (sub or cls or "poi"), 6
                if kind in TRANSPORT_PRIO:
                    prio = TRANSPORT_PRIO[kind]
            lat, lon = mvt.tile_to_lonlat(zoom, x, y, px, py, extent)
            ele = props.get("ele")
            try:
                ele = int(ele) if ele not in (None, "") else None
            except (TypeError, ValueError):
                ele = None
            key = (ka, ru, en, round(lat, 3), round(lon, 3))
            if key not in found:
                found[key] = (ka, ru, en, kind, cls, sub, prio, ele, lat, lon)
    if tiles % 5000 == 0:
        print("  tiles:", tiles, "places:", len(found))

def dist_m(la1, lo1, la2, lo2):
    dx = (lo2 - lo1) * 111320 * math.cos(math.radians(la1))
    dy = (la2 - la1) * 110574
    return math.hypot(dx, dy)


# Cross-check against reference points (verified against Wikipedia/Wikivoyage/OSM-based sources).
# A reference object missing from the OSM data (nothing of the same group within 2 km) is added to the index.
GROUPS = {"rail": {"station", "halt", "subway"}, "air": {"aerodrome"}}
# reference points count only inside the extent of this map (bounds metadata: west,south,east,north)
try:
    BW, BS, BE, BN = (float(v) for v in str(meta.get("bounds")).split(","))
except (TypeError, ValueError):
    BW, BS, BE, BN = -180, -90, 180, 90
ref_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "reference_places.json")
added = 0
if os.path.exists(ref_path):
    with open(ref_path, encoding="utf-8") as f:
        for r in json.load(f):
            if not (BW <= r["lon"] <= BE and BS <= r["lat"] <= BN):
                continue
            kinds = GROUPS.get(r["group"], {r["kind"]})
            near = [v for v in found.values() if v[3] in kinds and dist_m(r["lat"], r["lon"], v[8], v[9]) < 2000]
            if near:
                print("  reference OK:", r["en"], "->", near[0][2] or near[0][0])
                continue
            print("  reference MISSING in OSM data, adding:", r["en"])
            found[(r["ka"], r["ru"], r["en"], round(r["lat"], 3), round(r["lon"], 3))] = (
                r["ka"], r["ru"], r["en"], r["kind"], "", r["kind"],
                TRANSPORT_PRIO.get(r["kind"], 2), None, r["lat"], r["lon"])
            added += 1
print("reference objects added:", added)

out = sqlite3.connect(dst)
out.executescript("""
drop table if exists places;
create table places(id integer primary key, ka text, ru text, en text, s text,
                    kind text, cls text, sub text, prio integer, ele integer, lat real, lon real);
""")
rows = []
for (ka, ru, en, kind, cls, sub, prio, ele, lat, lon) in found.values():
    s = "\n".join(t.lower() for t in (ka, ru, en) if t)
    rows.append((ka, ru, en, s, kind, cls, sub, prio, ele, lat, lon))
out.executemany("insert into places(ka,ru,en,s,kind,cls,sub,prio,ele,lat,lon) values (?,?,?,?,?,?,?,?,?,?,?)", rows)
out.commit()
out.execute("vacuum")
print("tiles scanned:", tiles, "| places indexed:", len(rows))
if len(rows) < 100:
    sys.exit("ERROR: search index is almost empty")
