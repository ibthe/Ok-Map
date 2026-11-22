#!/usr/bin/env python3
"""Builds the offline road graph (graph.bin) from an OSM .osm.pbf for the on-device car router.

Usage: build_graph.py <region.osm.pbf> <graph.bin>        (needs: pip install osmium numpy)

Model: nodes = road junctions and dead ends; segments = road pieces between two junctions with their shape,
length, driving speed, direction rules, class and name.

File "OKG2" (little endian): 20 int32 header (magic, version, N nodes, S segments, P shape points, A adjacency size,
M names, name bytes, max km/h, grid lat0, lon0, cell, W, H, cell data size), then N lat, N lon (micro-degrees),
adjacency CSR (N+1 starts, A segment ids), per segment: A node, B node, length (dm), name id, shape start (int32 each),
km/h (u8), flags (u8: bit0 A->B allowed, bit1 B->A allowed, bits2-5 road class, bit6 roundabout, bit7 unpaved),
shape count (u16), then shape lat / lon (int32), name offsets + UTF-8 bytes, and the snapping grid (cell starts + segment ids).
Read by app/.../Graph.kt and tools/route_check.py.
"""
import math
import re
import struct
import sys

import numpy as np

MAGIC = 0x3247_4B4F  # "OKG2"
VERSION = 1

# class id (stored in flags bits 2..5), default speed km/h
CLASSES = {
    "motorway": (1, 90), "motorway_link": (1, 50),
    "trunk": (2, 80), "trunk_link": (2, 50),
    "primary": (3, 70), "primary_link": (3, 45),
    "secondary": (4, 60), "secondary_link": (4, 40),
    "tertiary": (5, 50), "tertiary_link": (5, 35),
    "unclassified": (6, 40), "residential": (7, 30), "living_street": (8, 10),
    "service": (9, 20), "track": (10, 12),
}
F_FWD, F_BACK, F_ROUNDABOUT, F_UNPAVED = 1, 2, 64, 128   # class id occupies bits 2..5
UNPAVED = {"unpaved", "gravel", "dirt", "ground", "sand", "grass", "mud", "compacted", "fine_gravel", "earth", "pebblestone"}
DENY = {"no", "private", "customers", "agricultural", "forestry", "military"}


def haversine(la1, lo1, la2, lo2):
    p = math.pi / 180
    a = math.sin((la2 - la1) * p / 2) ** 2 + math.cos(la1 * p) * math.cos(la2 * p) * math.sin((lo2 - lo1) * p / 2) ** 2
    return 12742000.0 * math.asin(min(1.0, math.sqrt(a)))


def parse_speed(v):
    if not v:
        return None
    m = re.match(r"^\s*(\d+(?:\.\d+)?)\s*(mph)?", v)
    if not m:
        return None
    s = float(m.group(1)) * (1.609 if m.group(2) else 1.0)
    return int(s) if 5 <= s <= 140 else None


def way_rules(tags):
    """-> (class_id, speed, flags) or None when a car can not use the way"""
    hw = tags.get("highway")
    if hw not in CLASSES:
        return None
    if tags.get("area") == "yes":
        return None
    if tags.get("access") in DENY and tags.get("motor_vehicle") not in ("yes", "permissive", "destination"):
        return None
    if tags.get("motor_vehicle") in ("no", "private") or tags.get("vehicle") in ("no", "private") or tags.get("motorcar") == "no":
        return None
    if hw == "service" and tags.get("service") in ("parking_aisle", "driveway", "emergency_access", "drive-through"):
        return None
    cid, speed = CLASSES[hw]
    ms = parse_speed(tags.get("maxspeed"))
    if ms:
        speed = ms
    flags = cid << 2
    surf = tags.get("surface", "")
    if surf in UNPAVED or hw == "track" or tags.get("tracktype") in ("grade3", "grade4", "grade5"):
        flags |= F_UNPAVED
        if not ms:
            speed = min(speed, 25 if hw != "track" else 12)
    junction = tags.get("junction")
    if junction in ("roundabout", "circular"):
        flags |= F_ROUNDABOUT
    ow = tags.get("oneway", "")
    if ow in ("yes", "true", "1") or junction == "roundabout" or (hw in ("motorway", "motorway_link") and ow != "no"):
        flags |= F_FWD
    elif ow == "-1":
        flags |= F_BACK
    else:
        flags |= F_FWD | F_BACK
    return cid, max(5, min(130, speed)), flags


def way_name(tags):
    n = tags.get("name:ru") or tags.get("name") or ""
    ref = tags.get("ref", "")
    if n and ref:
        return f"{n} ({ref})"
    return n or ref


def build(ways, min_component=40, cell=5000):
    """ways: iterable of (tags: dict, nodes: list[(id, lat, lon)]) -> dict of numpy arrays + names"""
    use = {}
    kept = []
    for tags, nodes in ways:
        r = way_rules(tags)
        if not r or len(nodes) < 2:
            continue
        kept.append((r, way_name(tags), nodes))
        seen = set()
        for nid, _, _ in nodes:
            use[nid] = use.get(nid, 0) + (2 if nid in seen else 1)
            seen.add(nid)

    node_index, nlat, nlon = {}, [], []
    names, name_idx = [], {}
    seg = []   # (a, b, len_dm, name, shape_start, shape_cnt, kmh, flags)
    shp_lat, shp_lon = [], []

    def nidx(nid, la, lo):
        i = node_index.get(nid)
        if i is None:
            i = len(nlat)
            node_index[nid] = i
            nlat.append(round(la * 1e6))
            nlon.append(round(lo * 1e6))
        return i

    for (cid, speed, flags), name, nodes in kept:
        cuts = [i for i, (nid, _, _) in enumerate(nodes) if i in (0, len(nodes) - 1) or use[nid] >= 2]
        ni = name_idx.get(name, -1)
        if name and ni < 0:
            ni = name_idx[name] = len(names)
            names.append(name)
        for c0, c1 in zip(cuts, cuts[1:]):
            pts = nodes[c0:c1 + 1]
            a = nidx(pts[0][0], pts[0][1], pts[0][2])
            b = nidx(pts[-1][0], pts[-1][1], pts[-1][2])
            if a == b:
                continue
            length = sum(haversine(p[1], p[2], q[1], q[2]) for p, q in zip(pts, pts[1:]))
            mid = pts[1:-1]
            seg.append((a, b, int(round(length * 10)), ni, len(shp_lat), len(mid), speed, flags))
            for _, la, lo in mid:
                shp_lat.append(round(la * 1e6))
                shp_lon.append(round(lo * 1e6))

    n = len(nlat)
    # keep only big connected components (drops isolated private roads that would break snapping)
    parent = list(range(n))

    def find(x):
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    for s in seg:
        ra, rb = find(s[0]), find(s[1])
        if ra != rb:
            parent[ra] = rb
    size = {}
    for s in seg:
        r = find(s[0])
        size[r] = size.get(r, 0) + 1
    seg = [s for s in seg if size[find(s[0])] >= min_component]

    # re-index nodes that are still used
    used = sorted({s[0] for s in seg} | {s[1] for s in seg})
    remap = {old: i for i, old in enumerate(used)}
    nlat = [nlat[o] for o in used]
    nlon = [nlon[o] for o in used]
    n = len(used)
    S = len(seg)
    A = np.array([remap[s[0]] for s in seg], dtype="<i4")
    B = np.array([remap[s[1]] for s in seg], dtype="<i4")
    LEN = np.array([s[2] for s in seg], dtype="<i4")
    NAME = np.array([s[3] for s in seg], dtype="<i4")
    SST = np.array([s[4] for s in seg], dtype="<i4")
    SCNT = np.array([s[5] for s in seg], dtype="<u2")
    KMH = np.array([s[6] for s in seg], dtype="u1")
    FLG = np.array([s[7] for s in seg], dtype="u1")

    # adjacency (CSR): every segment is listed under both of its nodes
    deg = np.bincount(np.concatenate([A, B]), minlength=n).astype("<i4")
    adj_start = np.zeros(n + 1, dtype="<i4")
    np.cumsum(deg, out=adj_start[1:])
    adj = np.zeros(int(adj_start[-1]), dtype="<i4")
    fill = adj_start[:-1].copy()
    for k in range(S):
        for node in (A[k], B[k]):
            adj[fill[node]] = k
            fill[node] += 1

    # grid index over segment shapes (for snapping the user's point to the road)
    nlat_a, nlon_a = np.array(nlat, dtype="<i4"), np.array(nlon, dtype="<i4")
    lat0, lon0 = int(nlat_a.min()) - cell, int(nlon_a.min()) - cell
    W = (int(nlon_a.max()) - lon0) // cell + 2
    H = (int(nlat_a.max()) - lat0) // cell + 2
    cells = {}
    shp_lat_a, shp_lon_a = np.array(shp_lat, dtype="<i4"), np.array(shp_lon, dtype="<i4")
    for k in range(S):
        la = [int(nlat_a[A[k]])] + [int(v) for v in shp_lat_a[SST[k]:SST[k] + SCNT[k]]] + [int(nlat_a[B[k]])]
        lo = [int(nlon_a[A[k]])] + [int(v) for v in shp_lon_a[SST[k]:SST[k] + SCNT[k]]] + [int(nlon_a[B[k]])]
        touched = set()
        for i in range(len(la) - 1):
            # walk the sub-segment in steps of half a cell so that no cell on the way is missed
            steps = max(1, int(max(abs(la[i + 1] - la[i]), abs(lo[i + 1] - lo[i])) / (cell / 2)) + 1)
            for t in range(steps + 1):
                f = t / steps
                cy = (int(la[i] + (la[i + 1] - la[i]) * f) - lat0) // cell
                cx = (int(lo[i] + (lo[i + 1] - lo[i]) * f) - lon0) // cell
                touched.add(cy * W + cx)
        for c in touched:
            cells.setdefault(c, []).append(k)
    cell_start = np.zeros(W * H + 1, dtype="<i4")
    counts = np.zeros(W * H, dtype="<i4")
    for c, lst in cells.items():
        counts[c] = len(lst)
    np.cumsum(counts, out=cell_start[1:])
    cell_data = np.zeros(int(cell_start[-1]), dtype="<i4")
    for c, lst in cells.items():
        cell_data[cell_start[c]:cell_start[c] + len(lst)] = lst

    name_bytes = [s.encode("utf-8") for s in names]
    name_off = np.zeros(len(names) + 1, dtype="<i4")
    np.cumsum([len(b) for b in name_bytes], out=name_off[1:]) if names else None
    return dict(nlat=nlat_a, nlon=nlon_a, adj_start=adj_start, adj=adj, A=A, B=B, LEN=LEN, NAME=NAME, SST=SST,
                KMH=KMH, FLG=FLG, SCNT=SCNT, shp_lat=shp_lat_a, shp_lon=shp_lon_a, name_off=name_off,
                name_bytes=b"".join(name_bytes), names=len(names), lat0=lat0, lon0=lon0, cell=cell, W=W, H=H,
                cell_start=cell_start, cell_data=cell_data)


def pad4(b):
    return b + b"\0" * (-len(b) % 4)


def write(g, path):
    S, N, P = len(g["A"]), len(g["nlat"]), len(g["shp_lat"])
    header = [MAGIC, VERSION, N, S, P, len(g["adj"]), g["names"], len(g["name_bytes"]), int(g["KMH"].max()),
              g["lat0"], g["lon0"], g["cell"], g["W"], g["H"], len(g["cell_data"])]
    header += [0] * (20 - len(header))
    with open(path, "wb") as f:
        f.write(struct.pack("<20i", *header))
        for key in ("nlat", "nlon", "adj_start", "adj", "A", "B", "LEN", "NAME", "SST"):
            f.write(g[key].tobytes())
        f.write(pad4(g["KMH"].tobytes()))
        f.write(pad4(g["FLG"].tobytes()))
        f.write(pad4(g["SCNT"].tobytes()))
        for key in ("shp_lat", "shp_lon", "name_off"):
            f.write(g[key].tobytes())
        f.write(pad4(g["name_bytes"]))
        f.write(g["cell_start"].tobytes())
        f.write(g["cell_data"].tobytes())
    print(f"graph: {N} nodes, {S} segments, {P} shape points, {g['names']} names, grid {g['W']}x{g['H']}")


def read_pbf(path):
    import osmium

    class H(osmium.SimpleHandler):
        def __init__(self):
            super().__init__()
            self.ways = []

        def way(self, w):
            if "highway" not in w.tags:
                return
            tags = {t.k: t.v for t in w.tags if t.k in (
                "highway", "area", "access", "motor_vehicle", "motorcar", "vehicle", "service", "maxspeed", "surface",
                "tracktype", "junction", "oneway", "name", "name:ru", "ref")}
            if way_rules(tags) is None:
                return
            try:
                nodes = [(n.ref, n.lat, n.lon) for n in w.nodes]
            except osmium.InvalidLocationError:
                return
            self.ways.append((tags, nodes))

    h = H()
    h.apply_file(path, locations=True)
    print("routable ways:", len(h.ways))
    return h.ways


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    write(build(read_pbf(sys.argv[1])), sys.argv[2])
