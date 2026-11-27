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
VERSION = 3

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
NO_TURN_RESTRICTIONS = {"no_left_turn", "no_right_turn", "no_u_turn", "no_straight_on"}
ONLY_TURN_RESTRICTIONS = {"only_left_turn", "only_right_turn", "only_u_turn", "only_straight_on"}


def car_restriction_kind(tags):
    """Return a supported restriction applying to cars, or None.

    OSM's 'except' list can explicitly exempt motorcars / motor vehicles.
    A generic restriction is used only when no car-specific value overrides it.
    """
    if tags.get("type") != "restriction":
        return None
    except_modes = {v.strip().lower() for v in re.split(r"[;,]", tags.get("except", "")) if v.strip()}
    if except_modes.intersection({"motorcar", "motor_vehicle", "vehicle"}):
        return None
    kind = (tags.get("restriction:motorcar")
            or tags.get("restriction:motor_vehicle")
            or tags.get("restriction", ""))
    return kind if kind in NO_TURN_RESTRICTIONS or kind in ONLY_TURN_RESTRICTIONS else None


def parse_via_way_members(members):
    """Parse an ordered OSM restriction member list containing one or more via-ways.

    members is an iterable of (role, member_type, ref) triples in relation order.
    Mixed via-node/via-way relations are deliberately rejected until their topology
    can be resolved without guessing.
    """
    from_ways = [int(ref) for role, typ, ref in members if role == "from" and typ == "w"]
    to_ways = [int(ref) for role, typ, ref in members if role == "to" and typ == "w"]
    via_members = [(typ, int(ref)) for role, typ, ref in members if role == "via"]
    if len(from_ways) != 1 or len(to_ways) != 1 or not via_members:
        return None
    if any(typ != "w" for typ, _ in via_members):
        return None
    # A relation may list multiple via ways. Their order is semantically significant.
    return (from_ways[0], tuple(ref for _, ref in via_members), to_ways[0])


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


def build(ways, min_component=40, cell=5000, restrictions=(), via_way_restrictions=()):
    """Build graph; ways may be (tags,nodes) or (tags,nodes,osm_way_id).

    restrictions contains (from_way_id, via_node_id, to_way_id, restriction_tag) via-node relations.
    """
    restrictions = list(restrictions)
    via_way_restrictions = list(via_way_restrictions)
    restriction_via_nodes = {int(r[1]) for r in restrictions}
    use = {}
    kept = []
    for item in ways:
        tags, nodes = item[0], item[1]
        way_id = int(item[2]) if len(item) > 2 else -1
        r = way_rules(tags)
        if not r or len(nodes) < 2:
            continue
        kept.append((r, way_name(tags), nodes, way_id))
        seen = set()
        for nid, _, _ in nodes:
            use[nid] = use.get(nid, 0) + (2 if nid in seen else 1)
            seen.add(nid)

    node_index, nlat, nlon = {}, [], []
    names, name_idx = [], {}
    seg = []   # (..., flags, OSM way id, start OSM node id, end OSM node id)
    shp_lat, shp_lon = [], []

    def nidx(nid, la, lo):
        i = node_index.get(nid)
        if i is None:
            i = len(nlat)
            node_index[nid] = i
            nlat.append(round(la * 1e6))
            nlon.append(round(lo * 1e6))
        return i

    for (cid, speed, flags), name, nodes, way_id in kept:
        cuts = [i for i, (nid, _, _) in enumerate(nodes)
                if i in (0, len(nodes) - 1) or use[nid] >= 2 or nid in restriction_via_nodes]
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
            seg.append((a, b, int(round(length * 10)), ni, len(shp_lat), len(mid), speed, flags,
                        way_id, int(pts[0][0]), int(pts[-1][0])))
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

    # Resolve via-node restrictions to pairs of directed arcs. Arc 2*k is A->B,
    # arc 2*k+1 is B->A. The router checks the incoming/outgoing arc pair.
    by_way_node_in, by_way_node_out, outgoing_at = {}, {}, {}
    for k, s in enumerate(seg):
        way_id, na, nb, flags = s[8], s[9], s[10], s[7]
        a, b = int(A[k]), int(B[k])
        if way_id < 0:
            continue
        if flags & F_FWD:
            by_way_node_in.setdefault((way_id, nb), []).append(2 * k)
            by_way_node_out.setdefault((way_id, na), []).append(2 * k)
            outgoing_at.setdefault(na, []).append(2 * k)
        if flags & F_BACK:
            by_way_node_in.setdefault((way_id, na), []).append(2 * k + 1)
            by_way_node_out.setdefault((way_id, nb), []).append(2 * k + 1)
            outgoing_at.setdefault(nb, []).append(2 * k + 1)
    blocked = set()
    for from_way, via_node, to_way, kind in restrictions:
        incoming = by_way_node_in.get((int(from_way), int(via_node)), ())
        if not incoming:
            continue
        # A same-way U-turn relation refers to the reverse of the incoming segment,
        # not every outgoing segment that happens to share the same OSM way id.
        if int(from_way) == int(to_way) and kind == "no_u_turn":
            blocked.update((ia, ia ^ 1) for ia in incoming)
            continue
        if int(from_way) == int(to_way) and kind == "only_u_turn":
            allowed = {ia ^ 1 for ia in incoming}
        else:
            allowed = set(by_way_node_out.get((int(to_way), int(via_node)), ()))
        if not allowed:
            continue
        if str(kind).startswith("only_"):
            outs = outgoing_at.get(int(via_node), ())
            for ia in incoming:
                blocked.update((ia, oa) for oa in outs if oa not in allowed)
        elif str(kind).startswith("no_"):
            for ia in incoming:
                blocked.update((ia, oa) for oa in allowed)
    turn_restrictions = np.asarray(sorted(blocked), dtype="<i4").reshape((-1, 2)) if blocked else np.empty((0, 2), dtype="<i4")

    # Resolve ordered via-way relations to exact directed-arc sequences. Ambiguous
    # topology is ignored rather than producing a restriction broader than OSM intended.
    outgoing_osm = {}
    arc_way, arc_start, arc_end = {}, {}, {}
    for k, item in enumerate(seg):
        way_id, na, nb, flags = int(item[8]), int(item[9]), int(item[10]), int(item[7])
        if way_id < 0:
            continue
        if flags & F_FWD:
            arc = 2 * k
            arc_way[arc], arc_start[arc], arc_end[arc] = way_id, na, nb
            outgoing_osm.setdefault(na, []).append(arc)
        if flags & F_BACK:
            arc = 2 * k + 1
            arc_way[arc], arc_start[arc], arc_end[arc] = way_id, nb, na
            outgoing_osm.setdefault(nb, []).append(arc)

    sequence_restrictions = []
    for from_way, via_ways, to_way, kind in via_way_restrictions:
        via_ways = tuple(int(v) for v in via_ways)
        from_way, to_way = int(from_way), int(to_way)
        if not via_ways or not kind.startswith(("no_", "only_")):
            continue
        resolved = []
        ambiguous = False

        def walk_way(node, idx, previous_arc, prefix, visited):
            nonlocal ambiguous
            if ambiguous:
                return
            next_way = via_ways[idx + 1] if idx + 1 < len(via_ways) else to_way
            exits = [a for a in outgoing_osm.get(node, ()) if arc_way[a] == next_way]
            if exits:
                if idx + 1 == len(via_ways):
                    resolved.append((tuple(prefix), tuple(sorted(exits))))
                    if len(resolved) > 1:
                        ambiguous = True
                        return
                else:
                    walk_way(node, idx + 1, previous_arc, prefix, visited)
                    if ambiguous:
                        return
            for arc in outgoing_osm.get(node, ()):
                if arc_way[arc] != via_ways[idx] or arc == (previous_arc ^ 1):
                    continue
                end_node = arc_end[arc]
                if end_node in visited:
                    continue
                walk_way(end_node, idx, arc, prefix + [arc], visited | {end_node})
                if ambiguous:
                    return

        starts = []
        for arc, wid in arc_way.items():
            if wid == from_way:
                node = arc_end[arc]
                if any(arc_way.get(a) == via_ways[0] for a in outgoing_osm.get(node, ())):
                    starts.append((arc, node))
        for from_arc, node in starts:
            resolved.clear()
            ambiguous = False
            for via_arc in outgoing_osm.get(node, ()):
                if arc_way[via_arc] != via_ways[0]:
                    continue
                walk_way(arc_end[via_arc], 0, via_arc, [via_arc], {node, arc_end[via_arc]})
                if ambiguous:
                    break
            if ambiguous or len(resolved) != 1:
                continue
            via_prefix, allowed_to = resolved[0]
            prefix = [from_arc] + list(via_prefix)
            if kind.startswith("only_"):
                full_path = prefix
                for i, arc in enumerate(full_path):
                    sequence_restrictions.append((2, full_path[:i + 1], [full_path[i + 1]]))
                sequence_restrictions.append((2, full_path, list(allowed_to)))
            else:
                for to_arc in allowed_to:
                    sequence_restrictions.append((1, prefix + [to_arc], []))

    unique_sequences = {}
    for rtype, prefix, allowed in sequence_restrictions:
        unique_sequences[(rtype, tuple(prefix), tuple(allowed))] = None
    sequence_restrictions = [(t, list(p), list(a)) for t, p, a in unique_sequences]

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
                cell_start=cell_start, cell_data=cell_data, turn_restrictions=turn_restrictions,\n                sequence_restrictions=sequence_restrictions)


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
        turns = g.get("turn_restrictions", np.empty((0, 2), dtype="<i4"))
        f.write(struct.pack("<i", len(turns)))
        f.write(turns.astype("<i4", copy=False).tobytes())\n        seqs = g.get("sequence_restrictions", [])\n        f.write(struct.pack("<i", len(seqs)))\n        for rtype, prefix, allowed in seqs:\n            f.write(struct.pack("<iii", int(rtype), len(prefix), len(allowed)))\n            f.write(struct.pack("<" + "i" * (len(prefix) + len(allowed)), *(prefix + allowed)))
    print(f"graph: {N} nodes, {S} segments, {P} shape points, {g['names']} names, grid {g['W']}x{g['H']}")


def read_pbf(path):
    import osmium

    class H(osmium.SimpleHandler):
        def __init__(self):
            super().__init__()
            self.ways = []
            self.restrictions = []
            self.via_way_restrictions = []

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
            self.ways.append((tags, nodes, int(w.id)))

        def relation(self, r):
            tags = {t.k: t.v for t in r.tags}
            kind = car_restriction_kind(tags)
            if kind is None:
                return
            ordered = [(m.role, m.type, int(m.ref)) for m in r.members
                       if m.role in ("from", "via", "to") and m.type in ("w", "n")]
            parsed_via = parse_via_way_members(ordered)
            if parsed_via is not None:
                self.via_way_restrictions.append((*parsed_via, kind))
                return
            # Existing via-node relations remain supported. Do not reinterpret mixed
            # or malformed via-way relations as node restrictions.
            via_nodes = [ref for role, typ, ref in ordered if role == "via" and typ == "n"]
            from_ways = [ref for role, typ, ref in ordered if role == "from" and typ == "w"]
            to_ways = [ref for role, typ, ref in ordered if role == "to" and typ == "w"]
            if len(from_ways) == 1 and len(via_nodes) == 1 and len(to_ways) == 1:
                if not any(role == "via" and typ == "w" for role, typ, _ in ordered):
                    self.restrictions.append((from_ways[0], via_nodes[0], to_ways[0], kind))

    h = H()
    h.apply_file(path, locations=True)
    print("routable ways:", len(h.ways), "via-node restrictions:", len(h.restrictions),
          "via-way restrictions detected (not yet encoded):", len(h.via_way_restrictions))
    return h.ways, h.restrictions, h.via_way_restrictions


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    ways, restrictions = read_pbf(sys.argv[1])
    write(build(ways, restrictions=restrictions), sys.argv[2])
