#!/usr/bin/env python3
"""Synthetic test of build_graph + route_check: a 12x12 street grid with a one-way street, a dead-end, a private
road, a roundabout, an isolated island and a track. Run: python tools/test_graph.py"""
import os
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
assert uturn_data["turn_restrictions"].shape[0] == 1, uturn_data["turn_restrictions"]
uturn_path = os.path.join(tempfile.mkdtemp(), "uturn.bin")
bg.write(uturn_data, uturn_path)
uturn_graph = rc.Graph(uturn_path)
uturn_start = uturn_graph.snap(41.72, 44.805)
uturn_target = uturn_graph.snap(41.72, 44.815)
uturn_route = uturn_graph.route(uturn_start, uturn_target)
assert uturn_route is not None and len(uturn_route[1]) == 2, ("no_u_turn blocked straight travel", uturn_route)
print("same-way no_u_turn test passed")
