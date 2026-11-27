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
assert bg.car_restriction_kind({"type": "restriction", "restriction": "no_entry"}) == "no_entry"
assert bg.car_restriction_kind({"type": "restriction", "restriction": "no_exit"}) == "no_exit"

# Conditional restrictions: supported weekday ranges and same-day time windows.
assert bg.parse_conditional_restrictions(
    "no_left_turn @ (Mo-Fr 07:00-09:00)"
) == [("no_left_turn", 0b0011111, 420, 540)]
assert bg.parse_conditional_restrictions(
    "no_entry @ (Sa-Su); no_exit @ (07:30-08:45)"
) == [("no_entry", 0b1100000, 0, 1440), ("no_exit", 0b1111111, 450, 525)]
assert bg.parse_conditional_restrictions("no_left_turn @ (PH 07:00-09:00)") == []
assert bg.parse_conditional_restrictions("no_left_turn @ (Mo-Fr 22:00-02:00)") == []

# The Python reader accepts legacy v1/v2 graphs, but rejects malformed v2 tails.
legacy_raw = bytearray(open(p, "rb").read())
assert struct.unpack_from("<i", legacy_raw, 4)[0] == 4
struct.pack_into("<i", legacy_raw, 4, 1)
legacy_path = os.path.join(tempfile.mkdtemp(), "legacy-v1.bin")
open(legacy_path, "wb").write(legacy_raw[:-12])  # omit turn, sequence, and conditional sections
legacy_graph = rc.Graph(legacy_path)
assert legacy_graph.version == 1 and not legacy_graph.turns

bad_raw = bytearray(open(p, "rb").read()[:-8])  # keep only the v2 turn section
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
    ("from", "w", 100), ("via", "r", 250), ("to", "w", 400)
]) is None
assert bg.parse_via_way_members([
    ("from", "w", 100), ("via", "w", 200), ("to", "w", 400), ("to", "w", 401)
]) is None
assert bg.parse_via_way_members([
    ("from", "w", 100), ("to", "w", 400)
]) is None
assert bg.parse_via_way_members([
    ("from", "n", 99), ("from", "w", 100), ("via", "w", 200), ("to", "w", 400)
]) is None
assert bg.parse_via_way_members([
    ("from", "w", 100), ("via", "w", 200), ("to", "w", 400), ("to", "n", 401)
]) is None
assert bg.parse_via_node_members([
    ("from", "w", 100), ("via", "n", 250), ("to", "w", 400)
]) == (100, 250, 400)
assert bg.parse_via_node_members([
    ("from", "w", 100), ("via", "n", 250), ("via", "r", 251), ("to", "w", 400)
]) is None

# OSM no_entry has one from-way and multiple to-ways; no_exit has multiple
# from-ways and one to-way. Expand them into exact pairs, never broaden malformed roles.
assert bg.parse_via_node_member_pairs([
    ("from", "w", 100), ("via", "n", 250),
    ("to", "w", 400), ("to", "w", 401)
], "no_entry") == [(100, 250, 400), (100, 250, 401)]
assert bg.parse_via_node_member_pairs([
    ("from", "w", 100), ("from", "w", 101),
    ("via", "n", 250), ("to", "w", 400)
], "no_exit") == [(100, 250, 400), (101, 250, 400)]
assert bg.parse_via_node_member_pairs([
    ("from", "w", 100), ("from", "w", 101),
    ("via", "n", 250), ("to", "w", 400)
], "no_entry") == []
assert bg.parse_via_node_member_pairs([
    ("from", "w", 100), ("via", "n", 250),
    ("to", "w", 400), ("to", "w", 401)
], "no_exit") == []

# Expanded no_entry/no_exit pairs are enforced by the same directed-turn table.
multi_nodes = {
    "west": (501, 41.73, 44.80), "center": (502, 41.73, 44.81),
    "north": (503, 41.74, 44.81), "south": (504, 41.72, 44.81),
    "east": (505, 41.73, 44.82), "west2": (506, 41.735, 44.80)
}
multi_ways = [
    ({"highway": "residential"}, [multi_nodes["west"], multi_nodes["center"]], 10),
    ({"highway": "residential"}, [multi_nodes["center"], multi_nodes["north"]], 20),
    ({"highway": "residential"}, [multi_nodes["center"], multi_nodes["south"]], 30),
    ({"highway": "residential"}, [multi_nodes["center"], multi_nodes["east"]], 40),
    ({"highway": "residential"}, [multi_nodes["west2"], multi_nodes["center"]], 11)
]
entry_pairs = bg.parse_via_node_member_pairs([
    ("from", "w", 10), ("via", "n", 502), ("to", "w", 20), ("to", "w", 30)
], "no_entry")
entry_data = bg.build(multi_ways, min_component=1,
                      restrictions=[(*pair, "no_entry") for pair in entry_pairs])
assert entry_data["turn_restrictions"].shape[0] == 2, entry_data["turn_restrictions"]
exit_pairs = bg.parse_via_node_member_pairs([
    ("from", "w", 10), ("from", "w", 11), ("via", "n", 502), ("to", "w", 20)
], "no_exit")
exit_data = bg.build(multi_ways, min_component=1,
                     restrictions=[(*pair, "no_exit") for pair in exit_pairs])
assert exit_data["turn_restrictions"].shape[0] == 2, exit_data["turn_restrictions"]

conditional_data = bg.build(
    multi_ways, min_component=1,
    conditional_restrictions=[(10, 502, 20, "no_left_turn", 0b0011111, 420, 540)]
)
assert conditional_data["conditional_turn_restrictions"].shape == (1, 5), conditional_data["conditional_turn_restrictions"]
assert set(conditional_data["conditional_turn_restrictions"][:, 2]) == {0b0011111}
conditional_only_data = bg.build(
    multi_ways, min_component=1,
    conditional_restrictions=[(10, 502, 20, "only_straight_on", 0b0011111, 420, 540)]
)
assert conditional_only_data["conditional_turn_restrictions"].shape == (4, 5), conditional_only_data["conditional_turn_restrictions"]
conditional_path = os.path.join(tempfile.mkdtemp(), "conditional-v4.bin")
bg.write(conditional_data, conditional_path)
conditional_reference = rc.Graph(conditional_path)
assert len(conditional_reference.conditional_turns) == 1
# Unknown roles must invalidate the whole relation instead of being silently discarded.
assert bg.parse_via_way_members([
    ("from", "w", 100), ("via", "w", 200), ("to", "w", 400), ("location_hint", "n", 999)
]) is None
assert bg.parse_via_node_members([
    ("from", "w", 100), ("via", "n", 250), ("to", "w", 400), ("label", "n", 999)
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
open(v2_path, "wb").write(v2_raw[:-8])  # omit sequence and conditional sections
v2_graph = rc.Graph(v2_path)
assert v2_graph.version == 2 and not v2_graph.ac_bad[0]

# v3 rejects malformed sequence records.
bad_seq = bytearray(open(no_via_path, "rb").read()[:-4])  # strip v4 conditional count to form v3
struct.pack_into("<i", bad_seq, 4, 3)
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


# Overlapping via-way prefixes: after traversing A->B->C, the suffix B->C
# must still activate the second restriction and force the reverse-side bypass.
overlap_pts = {
    "a": (8001, 41.8400, 44.8000),
    "b": (8002, 41.8400, 44.8100),
    "c": (8003, 41.8450, 44.8200),
    "d": (8004, 41.8500, 44.8300),
    "e": (8005, 41.8500, 44.8400),
    "f": (8006, 41.8550, 44.8350),
}
overlap_ways = [
    ({"highway": "residential"}, [overlap_pts["a"], overlap_pts["b"]], 810),
    ({"highway": "residential", "oneway": "yes"}, [overlap_pts["b"], overlap_pts["c"]], 820),
    ({"highway": "residential", "oneway": "yes"}, [overlap_pts["c"], overlap_pts["d"]], 830),
    ({"highway": "residential"}, [overlap_pts["d"], overlap_pts["e"]], 840),
    ({"highway": "residential", "oneway": "yes"}, [overlap_pts["d"], overlap_pts["f"]], 850),
    ({"highway": "residential", "oneway": "yes"}, [overlap_pts["f"], overlap_pts["e"]], 860),
]
overlap_data = bg.build(overlap_ways, min_component=1, via_way_restrictions=[
    (810, (820,), 830, "only_straight_on"),
    (820, (830,), 840, "no_straight_on"),
])
overlap_path = os.path.join(tempfile.mkdtemp(), "overlap-via-way.bin")
bg.write(overlap_data, overlap_path)
overlap_graph = rc.Graph(overlap_path)
overlap_route = overlap_graph.route(
    overlap_graph.snap(41.8400, 44.8050),
    overlap_graph.snap(41.8500, 44.8350))
assert overlap_route is not None, "overlapping restriction prefixes should preserve the bypass"
overlap_legs = overlap_route[1]
assert (3, True) not in overlap_legs, ("suffix via-way restriction was lost by automaton fallback", overlap_legs)
assert {(4, True), (5, True), (3, False)}.issubset(set(overlap_legs)), (
    "overlapping restriction did not select the bypass and reverse approach", overlap_legs)
print("overlapping via-way prefix automaton test passed")


# High-latitude grid search: longitude cells shrink with cos(latitude), so a
# height-only stopping radius can miss a closer road two columns away.
snap_ways = [
    ({"highway": "residential"}, [(5001, 41.7324, 44.7990), (5002, 41.7326, 44.7990)], 501),
    ({"highway": "residential"}, [(5003, 41.7324, 44.8101), (5004, 41.7326, 44.8101)], 502),
    # This remote component shifts the grid origin without becoming a nearby candidate.
    ({"highway": "residential"}, [(5005, 42.0000, 44.7900), (5006, 42.0010, 44.7900)], 503),
]
snap_data = bg.build(snap_ways, min_component=1, cell=5000)
snap_path = os.path.join(tempfile.mkdtemp(), "latitude-snap.bin")
bg.write(snap_data, snap_path)
snap_graph = rc.Graph(snap_path)
latitude_snap = snap_graph.snap(41.7325, 44.8049)
assert latitude_snap[1] == 1, ("latitude-aware grid search missed the closer road", latitude_snap)
print("latitude-aware road snapping test passed")


# Starting exactly at a junction must not invent an incoming from-way arc.
junction_pts = {
    "a": (6001, 41.8000, 44.8000),
    "b": (6002, 41.8000, 44.8100),
    "c": (6003, 41.8100, 44.8100),
    "d": (6004, 41.8000, 44.8200),
    "f": (6005, 41.8000, 44.8300),
    "e": (6006, 41.8005, 44.8150),
}
junction_ways = [
    ({"highway": "residential"}, [junction_pts["a"], junction_pts["b"]], 610),
    ({"highway": "residential", "oneway": "yes"}, [junction_pts["b"], junction_pts["c"]], 620),
    ({"highway": "residential"}, [junction_pts["c"], junction_pts["d"]], 630),
    ({"highway": "residential"}, [junction_pts["d"], junction_pts["f"]], 660),
    ({"highway": "residential"}, [junction_pts["b"], junction_pts["e"]], 640),
    ({"highway": "residential"}, [junction_pts["e"], junction_pts["d"]], 650),
]
junction_data = bg.build(junction_ways, min_component=1,
                         via_way_restrictions=[(610, (620,), 630, "only_straight_on")])
junction_path = os.path.join(tempfile.mkdtemp(), "junction-start.bin")
bg.write(junction_data, junction_path)
junction_graph = rc.Graph(junction_path)
junction_target = junction_graph.snap(41.8000, 44.8250)
assert junction_target[1] == 3, junction_target
start_at_b = (0.0, 0, float(junction_graph.LEN[0]) / 10.0)
from_junction = junction_graph.route(start_at_b, junction_target)
assert from_junction is not None and {3, 4, 5}.issubset({k for k, _ in from_junction[1]}) and 1 not in {
    k for k, _ in from_junction[1]}, ("junction start was incorrectly treated as incoming from-way", from_junction)

start_at_a = (0.0, 0, 0.0)
from_upstream = junction_graph.route(start_at_a, junction_target)
assert from_upstream is not None and 1 in {k for k, _ in from_upstream[1]}, (
    "via-way only restriction stopped applying after actually traversing the from-way", from_upstream)
print("junction-start routing and via-way context tests passed")


# If the destination lies on a road leaving the exact start junction, route directly
# along that partial segment rather than traversing it fully and looping back.
direct_pts = [
    (7001, 41.8200, 44.8000),
    (7002, 41.8200, 44.8100),
    (7003, 41.8210, 44.8150),
]
direct_data = bg.build([
    ({"highway": "residential"}, direct_pts[:2], 710),
    ({"highway": "residential"}, direct_pts[1:], 720),
], min_component=1)
direct_path = os.path.join(tempfile.mkdtemp(), "direct-junction.bin")
bg.write(direct_data, direct_path)
direct_graph = rc.Graph(direct_path)
direct_start = (0.0, 0, float(direct_graph.LEN[0]) / 10.0)
direct_end = direct_graph.snap(41.8205, 44.8125)
direct_route = direct_graph.route(direct_start, direct_end)
assert direct_end[1] == 1 and direct_route is not None and direct_route[1] == [(1, True)], (
    "route failed to use the direct partial segment from its start junction", direct_end, direct_route)
print("direct destination from junction test passed")


# OSM car access hierarchy and explicit reverse one-way overrides.
assert bg.way_rules({"highway": "residential", "access": "private"}) is None
assert bg.way_rules({"highway": "residential", "access": "private", "motorcar": "yes"}) is not None
assert bg.way_rules({"highway": "residential", "access": "yes", "motorcar": "private"}) is None
assert bg.way_rules({"highway": "residential", "access": "private", "motor_vehicle": "destination"}) is not None
assert bg.way_rules({"highway": "residential", "access": "private", "vehicle": "yes"}) is not None

motorway_reverse = bg.way_rules({"highway": "motorway", "oneway": "-1"})[2]
assert motorway_reverse & bg.F_BACK and not motorway_reverse & bg.F_FWD, motorway_reverse
roundabout_reverse = bg.way_rules({"highway": "residential", "junction": "roundabout", "oneway": "-1"})[2]
assert roundabout_reverse & bg.F_BACK and not roundabout_reverse & bg.F_FWD, roundabout_reverse
roundabout_two_way = bg.way_rules({"highway": "residential", "junction": "roundabout", "oneway": "no"})[2]
assert roundabout_two_way & bg.F_BACK and roundabout_two_way & bg.F_FWD, roundabout_two_way
print("car access hierarchy and one-way override tests passed")


# Avoiding unpaved roads must compare a same-segment track with a faster paved detour.
route_pts = {
    "start": (9001, 41.7000, 44.8000),
    "end": (9002, 41.7000, 44.8100),
    "north": (9003, 41.7100, 44.8050),
}
route_data = bg.build([
    ({"highway": "track", "maxspeed": "50"}, [route_pts["start"], route_pts["end"]], 901),
    ({"highway": "residential"}, [route_pts["start"], route_pts["north"], route_pts["end"]], 902),
], min_component=1)
route_path = os.path.join(tempfile.mkdtemp(), "unpaved-detour.bin")
bg.write(route_data, route_path)
route_graph = rc.Graph(route_path)
route_start = route_graph.snap(route_pts["start"][1], route_pts["start"][2])
route_end = route_graph.snap(route_pts["end"][1], route_pts["end"][2])
assert route_start[1] == route_end[1] == 0, (route_start, route_end)
avoid_route = route_graph.route(route_start, route_end, avoid_unpaved=True)
allow_route = route_graph.route(route_start, route_end, avoid_unpaved=False)
assert avoid_route is not None and allow_route is not None
track_seconds = route_graph.cost(0, route_graph.LEN[0] / 10.0, avoid_unpaved=True)
assert avoid_route[0] < track_seconds - 1.0, ("avoid_unpaved kept the slower direct track", avoid_route, track_seconds)
assert 1 in {k for k, _ in avoid_route[1]}, ("paved detour was not selected", avoid_route)
assert abs(allow_route[0] - route_graph.cost(0, route_graph.LEN[0] / 10.0, avoid_unpaved=False)) < 1.0, (allow_route, route_graph.LEN[0])
assert allow_route[1][0] == (0, True) and all(k == 0 for k, _ in allow_route[1]), allow_route
far_snap = route_graph.snap(43.0, 46.0)
assert far_snap is not None, "reference snap must use the same whole-network fallback as Kotlin"
print("same-segment paved detour, surface penalty and snap fallback tests passed")
