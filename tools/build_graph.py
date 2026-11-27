#!/usr/bin/env python3
"""Builds the offline road graph (graph.bin) from an OSM .osm.pbf for the on-device car router.

Usage: build_graph.py <region.osm.pbf> <graph.bin>        (needs: pip install osmium numpy)

Model: nodes = road junctions and dead ends; segments = road pieces between two junctions with their shape,
length, driving speed, direction rules, class and name.

File "OKG2" v4 (little endian): 20 int32 header (magic, version, N nodes, S segments, P shape points, A adjacency size,
M names, name bytes, max km/h, grid lat0, lon0, cell, W, H, cell data size), then N lat, N lon (micro-degrees),
adjacency CSR (N+1 starts, A segment ids), per segment: A node, B node, length (dm), name id, shape start (int32 each),
km/h (u8), flags (u8: bit0 A->B allowed, bit1 B->A allowed, bits2-5 road class, bit6 roundabout, bit7 unpaved),
shape count (u16), then shape lat / lon (int32), name offsets + UTF-8 bytes, and the snapping grid (cell starts + segment ids).
Version 4 appends weekday/time-window turn records after the via-way sequence section. Read by app/.../Graph.kt and tools/route_check.py.
"""
import math
import re
import struct
import sys

import numpy as np

MAGIC = 0x3247_4B4F  # "OKG2"
VERSION = 4

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
DENY = {"no", "private", "customers", "agricultural", "forestry", "military", "delivery", "permit", "restricted"}
NO_TURN_RESTRICTIONS = {"no_left_turn", "no_right_turn", "no_u_turn", "no_straight_on"}
ONLY_TURN_RESTRICTIONS = {"only_left_turn", "only_right_turn", "only_u_turn", "only_straight_on"}
MULTI_MEMBER_TURN_RESTRICTIONS = {"no_entry", "no_exit"}


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
    return kind if kind in NO_TURN_RESTRICTIONS or kind in ONLY_TURN_RESTRICTIONS or kind in MULTI_MEMBER_TURN_RESTRICTIONS else None


def parse_via_way_members(members):
    """Parse an ordered OSM restriction with exactly one from/to way and one or more via-ways."""
    # Do not silently discard unexpected relation members: interpreting a malformed
    # restriction after dropping a role can prohibit a broader set of turns than OSM intended.
    if any(role not in ("from", "via", "to") for role, _, _ in members):
        return None
    from_members = [(typ, int(ref)) for role, typ, ref in members if role == "from"]
    to_members = [(typ, int(ref)) for role, typ, ref in members if role == "to"]
    via_members = [(typ, int(ref)) for role, typ, ref in members if role == "via"]
    if (len(from_members) != 1 or from_members[0][0] != "w" or
            len(to_members) != 1 or to_members[0][0] != "w" or not via_members):
        return None
    if any(typ != "w" for typ, _ in via_members):
        return None
    # A relation may list multiple via ways. Their order is semantically significant.
    return (from_members[0][1], tuple(ref for _, ref in via_members), to_members[0][1])


def parse_via_node_member_pairs(members, kind):
    """Expand via-node no_entry/no_exit relations into exact directed way pairs."""
    if any(role not in ("from", "via", "to") for role, _, _ in members):
        return []
    from_members = [(typ, int(ref)) for role, typ, ref in members if role == "from"]
    via_members = [(typ, int(ref)) for role, typ, ref in members if role == "via"]
    to_members = [(typ, int(ref)) for role, typ, ref in members if role == "to"]
    if len(via_members) != 1 or via_members[0][0] != "n":
        return []
    via_node = via_members[0][1]

    if kind == "no_entry":
        if (len(from_members) != 1 or from_members[0][0] != "w" or not to_members or
                any(typ != "w" for typ, _ in to_members)):
            return []
        return [(from_members[0][1], via_node, to_ref) for _, to_ref in to_members]

    if kind == "no_exit":
        if (len(to_members) != 1 or to_members[0][0] != "w" or not from_members or
                any(typ != "w" for typ, _ in from_members)):
            return []
        return [(from_ref, via_node, to_members[0][1]) for _, from_ref in from_members]

    if (len(from_members) != 1 or from_members[0][0] != "w" or
            len(to_members) != 1 or to_members[0][0] != "w"):
        return []
    return [(from_members[0][1], via_node, to_members[0][1])]


def parse_via_node_members(members):
    """Backward-compatible parser for a single from/via/to relation."""
    pairs = parse_via_node_member_pairs(members, "no_left_turn")
    return pairs[0] if len(pairs) == 1 else None


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
    # OSM access tags are hierarchical: the most specific car tag overrides broader tags.
    effective_access = next((tags.get(key) for key in ("motorcar", "motor_vehicle", "vehicle", "access")
                             if tags.get(key)), "")
    if effective_access in DENY:
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
    # Explicit reverse direction overrides implicit motorway / roundabout one-way rules.
    if ow == "-1":
        flags |= F_BACK
    elif ow in ("yes", "true", "1") or (junction == "roundabout" and ow != "no") or (hw in ("motorway", "motorway_link") and ow != "no"):
        flags |= F_FWD
    else:
        flags |= F_FWD | F_BACK
    return cid, max(5, min(130, speed)), flags


def way_name(tags):
    n = tags.get("name:ru") or tags.get("name") or ""
    ref = tags.get("ref", "")
    if n and ref:
        return f"{n} ({ref})"
    return n or ref


def build(ways, min_component=40, cell=5000, restrictions=(), via_way_restrictions=(), conditional_restrictions=()):
    """Build graph; ways may be (tags,nodes) or (tags,nodes,osm_way_id).

    restrictions contains (from_way_id, via_node_id, to_way_id, restriction_tag) via-node relations.
    """
    restrictions = list(restrictions)
    via_way_restrictions = list(via_way_restrictions)
    conditional_restrictions = list(conditional_restrictions)
    restriction_via_nodes = ({int(r[1]) for r in restrictions} |
                             {int(r[1]) for r in conditional_restrictions})
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

    # Conditional via-node restrictions are stored separately and activated by the
    # Android router only during their supported weekday/time window.
    conditional_blocked = set()
    for from_way, via_node, to_way, kind, day_mask, start_minute, end_minute in conditional_restrictions:
        incoming = by_way_node_in.get((int(from_way), int(via_node)), ())
        if not incoming:
            continue
        if int(from_way) == int(to_way) and kind == "no_u_turn":
            pairs = ((ia, ia ^ 1) for ia in incoming)
            conditional_blocked.update((ia, oa, int(day_mask), int(start_minute), int(end_minute)) for ia, oa in pairs)
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
                conditional_blocked.update(
                    (ia, oa, int(day_mask), int(start_minute), int(end_minute))
                    for oa in outs if oa not in allowed
                )
        elif str(kind).startswith("no_"):
            for ia in incoming:
                conditional_blocked.update(
                    (ia, oa, int(day_mask), int(start_minute), int(end_minute))
                    for oa in allowed
                )
    conditional_turn_restrictions = (
        np.asarray(sorted(conditional_blocked), dtype="<i4").reshape((-1, 5))
        if conditional_blocked else np.empty((0, 5), dtype="<i4")
    )

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

    arcs_by_way = {}
    for arc, way_id in arc_way.items():
        arcs_by_way.setdefault(way_id, []).append(arc)

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
        for arc in arcs_by_way.get(from_way, ()):
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
                for i in range(len(full_path) - 1):
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
    shp_lat_a, shp_lon_a = np.array(shp_lat, dtype="<i4"), np.array(shp_lon, dtype="<i4")
    # Intermediate geometry points can extend beyond the endpoint/junction bounds
    # (for example, a curved road between two junctions). Include them in the grid extent.
    all_lat = np.concatenate((nlat_a, shp_lat_a))
    all_lon = np.concatenate((nlon_a, shp_lon_a))
    lat0, lon0 = int(all_lat.min()) - cell, int(all_lon.min()) - cell
    W = (int(all_lon.max()) - lon0) // cell + 2
    H = (int(all_lat.max()) - lat0) // cell + 2
    cells = {}
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
                cell_start=cell_start, cell_data=cell_data, turn_restrictions=turn_restrictions,
                sequence_restrictions=sequence_restrictions,
                conditional_turn_restrictions=conditional_turn_restrictions)


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
        f.write(turns.astype("<i4", copy=False).tobytes())
        seqs = g.get("sequence_restrictions", [])
        f.write(struct.pack("<i", len(seqs)))
        for rtype, prefix, allowed in seqs:
            f.write(struct.pack("<iii", int(rtype), len(prefix), len(allowed)))
            f.write(struct.pack("<" + "i" * (len(prefix) + len(allowed)), *(prefix + allowed)))
        conditional = g.get("conditional_turn_restrictions", np.empty((0, 5), dtype="<i4"))
        f.write(struct.pack("<i", len(conditional)))
        if len(conditional):
            f.write(conditional.astype("<i4", copy=False).tobytes())
    print(f"graph: {N} nodes, {S} segments, {P} shape points, {g['names']} names, grid {g['W']}x{g['H']}")


DAY_INDEX = {"Mo": 0, "Tu": 1, "We": 2, "Th": 3, "Fr": 4, "Sa": 5, "Su": 6}


def parse_conditional_restrictions(value):
    """Parse only complete weekday/time rule sets; unsupported clauses reject the relation."""
    if not value:
        return []
    out = []
    clauses = str(value).split(";")
    for clause in clauses:
        if not clause.strip():
            return []
        match = re.fullmatch(r"\s*([a-z_]+)\s*@\s*\(([^)]+)\)\s*", clause)
        if not match:
            return []
        kind, condition = match.group(1), match.group(2).strip()
        if kind not in NO_TURN_RESTRICTIONS | ONLY_TURN_RESTRICTIONS | MULTI_MEMBER_TURN_RESTRICTIONS:
            return []

        parts = condition.split()
        day_expr, time_expr = None, None
        if len(parts) == 1:
            if ":" in parts[0]:
                time_expr = parts[0]
            else:
                day_expr = parts[0]
        elif len(parts) == 2:
            day_expr, time_expr = parts
        else:
            return []

        day_mask = 0
        if day_expr is None:
            day_mask = 0x7f
        else:
            for item in day_expr.split(","):
                endpoints = item.split("-")
                if len(endpoints) == 1 and endpoints[0] in DAY_INDEX:
                    day_mask |= 1 << DAY_INDEX[endpoints[0]]
                elif len(endpoints) == 2 and all(x in DAY_INDEX for x in endpoints):
                    day = DAY_INDEX[endpoints[0]]
                    end = DAY_INDEX[endpoints[1]]
                    while True:
                        day_mask |= 1 << day
                        if day == end:
                            break
                        day = (day + 1) % 7
                else:
                    return []
            if not day_mask:
                return []

        start_minute, end_minute = 0, 1440
        if time_expr is not None:
            times = time_expr.split("-")
            if len(times) != 2:
                return []
            def minute(text, allow_24=False):
                m = re.fullmatch(r"(\d{2}):(\d{2})", text)
                if not m:
                    return None
                hour, minute_value = int(m.group(1)), int(m.group(2))
                if minute_value > 59 or hour > (24 if allow_24 else 23) or (hour == 24 and (minute_value != 0 or not allow_24)):
                    return None
                return hour * 60 + minute_value
            start_minute = minute(times[0])
            end_minute = minute(times[1], allow_24=True)
            if start_minute is None or end_minute is None or start_minute >= end_minute:
                return []
        out.append((kind, day_mask, start_minute, end_minute))
    return out

def read_pbf(path):
    import osmium

    class H(osmium.SimpleHandler):
        def __init__(self):
            super().__init__()
            self.ways = []
            self.restrictions = []
            self.via_way_restrictions = []
            self.conditional_restrictions = []
            self.unsupported_conditional = 0

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
            conditional_value = (tags.get("restriction:motorcar:conditional") or
                                 tags.get("restriction:motor_vehicle:conditional") or
                                 tags.get("restriction:conditional"))
            kind = car_restriction_kind(tags)
            exceptions = {x.strip().lower() for x in re.split(r"[;,]", tags.get("except", "")) if x.strip()}
            if exceptions & {"motorcar", "motor_vehicle", "vehicle"}:
                return
            # Mixed static + conditional relations keep the static restriction; the conditional
            # override is deliberately not guessed because its default-rule semantics vary.
            if conditional_value and kind is not None:
                self.unsupported_conditional += 1
            conditional_rules = parse_conditional_restrictions(conditional_value) if kind is None else []
            if kind is None and not conditional_rules:
                if conditional_value:
                    self.unsupported_conditional += 1
                return
            # Keep unsupported member types so malformed relations are rejected, not shortened.
            ordered = [(m.role, m.type, int(m.ref)) for m in r.members]
            if conditional_rules:
                expanded = []
                for conditional_kind, day_mask, start_minute, end_minute in conditional_rules:
                    parsed_nodes = parse_via_node_member_pairs(ordered, conditional_kind)
                    if not parsed_nodes:
                        self.unsupported_conditional += 1
                        return
                    expanded.extend(
                        (*pair, conditional_kind, day_mask, start_minute, end_minute) for pair in parsed_nodes
                    )
                self.conditional_restrictions.extend(expanded)
                return
            parsed_via = parse_via_way_members(ordered)
            if parsed_via is not None:
                self.via_way_restrictions.append((*parsed_via, kind))
                return
            parsed_nodes = parse_via_node_member_pairs(ordered, kind)
            if parsed_nodes:
                self.restrictions.extend((*parsed_node, kind) for parsed_node in parsed_nodes)

    h = H()
    h.apply_file(path, locations=True)
    print("routable ways:", len(h.ways), "via-node restrictions:", len(h.restrictions),
          "via-way restrictions detected:", len(h.via_way_restrictions),
          "conditional via-node restrictions:", len(h.conditional_restrictions),
          "unsupported conditional relations:", h.unsupported_conditional)
    return h.ways, h.restrictions, h.via_way_restrictions, h.conditional_restrictions


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    ways, restrictions, via_way_restrictions, conditional_restrictions = read_pbf(sys.argv[1])
    graph = build(ways, restrictions=restrictions, via_way_restrictions=via_way_restrictions,
                  conditional_restrictions=conditional_restrictions)
    print("via-way sequence restriction records:", len(graph["sequence_restrictions"]))
    write(graph, sys.argv[2])
