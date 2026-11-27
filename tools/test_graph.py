#!/usr/bin/env python3
"""Synthetic test of build_graph + route_check: a 12x12 street grid with a one-way street, a dead-end, a private
road, a roundabout, an isolated island and a track. Run: python tools/test_graph.py"""
import os
import struct
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import build_graph as bg
import route_check as rc

ways, nid = [], {}


def node(i, j):
    nid.setdefault((i, j), len(nid) + 1)
    return (nid[(i, j)], 41.70 + i * 0.002, 44.80 + j * 0.002)


for i in range(12):   # horizontal streets
    tags = {"highway": "residential", "name": f"Улица {i}"}
    if i == 5:
        tags["oneway"] = "yes"
    ways.append((tags, [node(i, j) for j in range(12)]))
for j in range(12):   # vertical streets
    tags = {"highway": "secondary", "name": f"Проспект {j}", "maxspeed": "60"}
    if j == 11:
        tags["access"] = "private"
    ways.append((tags, [node(i, j) for i in range(12)]))
ways.append(({"highway": "residential"}, [node(100, 100), node(100, 101)]))  # island, dropped (too small)
g = bg.build(ways, min_component=10)
assert g["A"].size > 100, g["A"].size
p = os.path.join(tempfile.mkdtemp(), "g.bin")
bg.write(g, p)
G = rc.Graph(p)
s, e = G.snap(41.70, 44.80), G.snap(41.70 + 11 * 0.002, 44.80 + 10 * 0.002)
t, legs = G.route(s, e)
d = G.dist(legs)
exp = 11 * 0.002 * 111320 + 10 * 0.002 * 111320 * 0.7478
assert abs(d - exp) < 0.06 * exp, (d, exp)   # Manhattan distance (first and last segment are counted whole)
# against the one-way street 5: crossing it must still work, driving along it backwards must not
s2, e2 = G.snap(41.70 + 5 * 0.002, 44.80 + 8 * 0.002), G.snap(41.70 + 5 * 0.002, 44.80 + 2 * 0.002)
t2, legs2 = G.route(s2, e2)
assert G.dist(legs2) > 6 * 0.002 * 111320 * 0.7478 * 1.2, "went against the one-way street"
# 12 streets x 11 blocks + 11 public avenues x 11 blocks; the private avenue and the tiny island are dropped
assert G.S == 12 * 11 + 11 * 11, G.S
print("graph tests passed:", round(d), "m,", round(t), "s")

# Via-node turn restrictions: the fastest west->north movement is prohibited, so the
# router must retain the incoming directed arc and take the east-side bypass.
def restriction_graph(kind, with_bypass=True):
    ids = {}
    def pt(name, lat, lon):
        ids[name] = len(ids) + 1000
        return (ids[name], lat, lon)
    west = pt("west", 41.70, 44.80)
    center = pt("center", 41.70, 44.81)
    north = pt("north", 41.71, 44.81)
    east = pt("east", 41.70, 44.82)
    ways = [
        ({"highway": "residential"}, [west, center], 10),
        ({"highway": "residential"}, [center, north], 20),
    ]
    if with_bypass:
        ways.extend([
            ({"highway": "residential"}, [center, east], 30),
            ({"highway": "residential"}, [east, north], 40),
        ])
    g = bg.build(ways, min_component=1,
                 restrictions=[(10, ids["center"], 20, kind)])
    path = os.path.join(tempfile.mkdtemp(), "turns.bin")
    bg.write(g, path)
    graph = rc.Graph(path)
    start = graph.snap(41.70, 44.805)
    target = graph.snap(41.705, 44.81)
    return graph, start, target, g


no_graph, no_start, no_target, no_data = restriction_graph("no_left_turn", True)
assert no_data["turn_restrictions"].shape[0] == 1, no_data["turn_restrictions"]
no_route = no_graph.route(no_start, no_target)
assert no_route is not None, "no_left_turn should use the available bypass"
assert len(no_route[1]) >= 3, ("router ignored no_left_turn", no_route[1])

only_graph, only_start, only_target, only_data = restriction_graph("only_straight_on", True)
assert only_data["turn_restrictions"].shape[0] >= 1
only_route = only_graph.route(only_start, only_target)
assert only_route is not None, "only_* should preserve the allowed target way"
# Directly leaving toward east is forbidden; the route must use the permitted north branch first.
only_east = only_graph.snap(41.70, 44.815)
only_detour = only_graph.route(only_start, only_east)
assert only_detour is not None and len(only_detour[1]) >= 3, ("only_* turn was not enforced", only_detour)

blocked_graph, blocked_start, blocked_target, _ = restriction_graph("no_left_turn", False)
assert blocked_graph.route(blocked_start, blocked_target) is None, "route should fail when the only turn is prohibited"
print("via-node turn restriction tests passed")


# A no_u_turn relation may use the same OSM way as both from and to. The via node
# must split that way, and only the reverse arc must be prohibited (not straight-on travel).
uturn_nodes = [(2001, 41.72, 44.80), (2002, 41.72, 44.81), (2003, 41.72, 44.82)]
uturn_data = bg.build([({"highway": "residential"}, uturn_nodes, 77)], min_component=1,
                     restrictions=[(77, 2002, 77, "no_u_turn")])
assert uturn_data["A"].size == 2, uturn_data["A"].size
assert uturn_data["turn_restrictions"].shape[0] == 2, uturn_data["turn_restrictions"]
uturn_path = os.path.join(tempfile.mkdtemp(), "uturn.bin")
bg.write(uturn_data, uturn_path)
uturn_graph = rc.Graph(uturn_path)
uturn_start = uturn_graph.snap(41.72, 44.805)
uturn_target = uturn_graph.snap(41.72, 44.815)
uturn_route = uturn_graph.route(uturn_start, uturn_target)
assert uturn_route is not None and len(uturn_route[1]) == 2, ("no_u_turn blocked straight travel", uturn_route)
print("same-way no_u_turn test passed")


# OSM relation applicability: car-specific values override generic values, while
# explicit car exemptions must not become restrictions in the car graph.
assert bg.car_restriction_kind({"type": "restriction", "restriction": "no_left_turn"}) == "no_left_turn"
assert bg.car_restriction_kind({"type": "restriction", "restriction": "no_left_turn",
                                "restriction:motorcar": "no_right_turn"}) == "no_right_turn"
assert bg.car_restriction_kind({"type": "restriction", "restriction": "no_left_turn",
                                "except": "psv; motorcar"}) is None
assert bg.car_restriction_kind({"type": "restriction", "restriction": "only_straight_on",
                                "except": "vehicle"}) is None
assert bg.car_restriction_kind({"type": "restriction", "restriction": "no_entry"}) is None

# The Python reader accepts legacy v1/v2 graphs, but rejects malformed v2 tails.
legacy_raw = bytearray(open(p, "rb").read())
assert struct.unpack_from("<i", legacy_raw, 4)[0] == 3
struct.pack_into("<i", legacy_raw, 4, 1)
legacy_path = os.path.join(tempfile.mkdtemp(), "legacy-v1.bin")
open(legacy_path, "wb").write(legacy_raw[:-8])  # omit v2 turn and v3 sequence count sections
legacy_graph = rc.Graph(legacy_path)
assert legacy_graph.version == 1 and not legacy_graph.turns

bad_raw = bytearray(open(p, "rb").read()[:-4])
struct.pack_into("<i", bad_raw, 4, 2)
struct.pack_into("<i", bad_raw, len(bad_raw) - 4, -1)
bad_path = os.path.join(tempfile.mkdtemp(), "bad-v2.bin")
open(bad_path, "wb").write(bad_raw)
try:
    rc.Graph(bad_path)
    raise AssertionError("malformed v2 restriction section was accepted")
except ValueError:
    pass
print("OSM car-exception and graph-format validation tests passed")


# Via-way relation parsing preserves the ordered via-way chain and rejects ambiguous
# mixed via-node/via-way relations rather than silently degrading them to via-node.
assert bg.parse_via_way_members([
    ("from", "w", 100), ("via", "w", 200), ("via", "w", 300), ("to", "w", 400)
]) == (100, (200, 300), 400)
assert bg.parse_via_way_members([
    ("from", "w", 100), ("via", "n", 250), ("via", "w", 300), ("to", "w", 400)
]) is None
assert bg.parse_via_way_members([
    ("from", "w", 100), ("via", "w", 200), ("to", "w", 400), ("to", "w", 401)
]) is None
assert bg.parse_via_way_members([
    ("from", "w", 100), ("to", "w", 400)
]) is None
print("ordered via-way relation parsing tests passed")


# Graph v3: ordered via-way restrictions are resolved to exact directed arc chains
def via_way_graph(kind, with_bypass):
    pts = {
        "a": (3001, 41.7300, 44.8000),
        "b": (3002, 41.7300, 44.8100),
        "c": (3003, 41.7350, 44.8200),
        "d": (3004, 41.7400, 44.8300),
        "e": (3005, 41.7400, 44.8400),
        "x": (3006, 41.7500, 44.8200),
        "f": (3007, 41.7310, 44.8150),
    }
    ways = [
        ({"highway": "residential"}, [pts["a"], pts["b"]], 10),
        ({"highway": "residential", "oneway": "yes"}, [pts["b"], pts["c"], pts["d"]], 20),
        ({"highway": "residential"}, [pts["d"], pts["e"]], 30),
        ({"highway": "residential", "oneway": "yes"}, [pts["c"], pts["x"]], 60),
    ]
    if with_bypass:
        ways.extend([
            ({"highway": "residential"}, [pts["b"], pts["f"]], 40),
            ({"highway": "residential"}, [pts["f"], pts["e"]], 50),
        ])
    data = bg.build(ways, min_component=1,
                    via_way_restrictions=[(10, (20,), 30, kind)])
    path = os.path.join(tempfile.mkdtemp(), "via-way.bin")
    bg.write(data, path)
    return rc.Graph(path), data, path

no_via_graph, no_via_data, no_via_path = via_way_graph("no_straight_on", False)
assert len(no_via_data["sequence_restrictions"]) >= 1
no_via_start = no_via_graph.snap(41.7300, 44.8050)
no_via_end = no_via_graph.snap(41.7400, 44.8350)
no_via_route = no_via_graph.route(no_via_start, no_via_end)
assert no_via_route is None, "no-via-way restriction was ignored"

no_detour_graph, _, _ = via_way_graph("no_straight_on", True)
no_detour = no_detour_graph.route(no_detour_graph.snap(41.7300, 44.8050),
                                  no_detour_graph.snap(41.7400, 44.8350))
assert no_detour is not None and not ({1, 2} <= {k for k, _ in no_detour[1]}), (
    "no-via-way restriction failed to select the available bypass", no_detour)

only_via_graph, only_via_data, only_via_path = via_way_graph("only_straight_on", True)
assert len(only_via_data["sequence_restrictions"]) >= 3, only_via_data["sequence_restrictions"]
only_via_route = only_via_graph.route(only_via_graph.snap(41.7300, 44.8050),
                                      only_via_graph.snap(41.7400, 44.8350))
assert only_via_route is not None
assert 1 in [k for k, _ in only_via_route[1]] and 2 in [k for k, _ in only_via_route[1]], (
    "only-via-way restriction did not keep the route on the ordered via way", only_via_route[1])
print("graph v3 ordered via-way no_/only_ routing tests passed")

# v2 remains readable after the v3 writer adds its sequence section.
v2_raw = bytearray(open(p, "rb").read())
struct.pack_into("<i", v2_raw, 4, 2)
v2_path = os.path.join(tempfile.mkdtemp(), "legacy-v2.bin")
open(v2_path, "wb").write(v2_raw[:-4])
v2_graph = rc.Graph(v2_path)
assert v2_graph.version == 2 and not v2_graph.ac_bad[0]

# v3 rejects malformed sequence records.
bad_seq = bytearray(open(no_via_path, "rb").read())
bad_seq[-4:] = struct.pack("<i", -1)
bad_seq_path = os.path.join(tempfile.mkdtemp(), "bad-sequence-v3.bin")
open(bad_seq_path, "wb").write(bad_seq)
try:
    rc.Graph(bad_seq_path)
    raise AssertionError("negative v3 sequence count was accepted")
except ValueError:
    pass
print("graph v1/v2/v3 compatibility and malformed v3 validation tests passed")


# A relation with multiple ordered via-way members must preserve member order.
multi_pts = {
    "a": (4001, 41.7600, 44.8000), "b": (4002, 41.7600, 44.8100),
    "c": (4003, 41.7650, 44.8200), "d": (4004, 41.7700, 44.8300),
    "e": (4005, 41.7700, 44.8400),
}
multi_ways = [
    ({"highway": "residential"}, [multi_pts["a"], multi_pts["b"]], 110),
    ({"highway": "residential", "oneway": "yes"}, [multi_pts["b"], multi_pts["c"]], 120),
    ({"highway": "residential", "oneway": "yes"}, [multi_pts["c"], multi_pts["d"]], 130),
    ({"highway": "residential"}, [multi_pts["d"], multi_pts["e"]], 140),
]
multi_data = bg.build(multi_ways, min_component=1,
                      via_way_restrictions=[(110, (120, 130), 140, "no_straight_on")])
multi_path = os.path.join(tempfile.mkdtemp(), "multi-via.bin")
bg.write(multi_data, multi_path)
multi_graph = rc.Graph(multi_path)
assert multi_data["sequence_restrictions"], "multiple ordered via ways were not resolved"
assert multi_graph.route(multi_graph.snap(41.7600, 44.8050),
                         multi_graph.snap(41.7700, 44.8350)) is None, (
    "multi-member via-way restriction was not enforced")
# Reversing via member order must not be silently normalized into a valid chain.
reversed_data = bg.build(multi_ways, min_component=1,
                         via_way_restrictions=[(110, (130, 120), 140, "no_straight_on")])
assert not reversed_data["sequence_restrictions"], "via-way member order was ignored"
print("multiple ordered via-way members and order-sensitive resolution tests passed")
