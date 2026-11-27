#!/usr/bin/env python3
"""Reference implementation of the on-device router (same file format and algorithm as Graph.kt).
Used by CI as a smoke test of graph.bin and by tools/test_graph.py.
Usage: route_check.py graph.bin  lat1 lon1 lat2 lon2 [min_km max_km]"""
import heapq
import math
import struct
import sys

import numpy as np

PEN = {10: 2.5}          # class id -> time multiplier (tracks are a last resort)
UNPAVED_PEN = 1.3


def hav(la1, lo1, la2, lo2):
    p = math.pi / 180
    a = math.sin((la2 - la1) * p / 2) ** 2 + math.cos(la1 * p) * math.cos(la2 * p) * math.sin((lo2 - lo1) * p / 2) ** 2
    return 12742000.0 * math.asin(min(1.0, math.sqrt(a)))


class Graph:
    def __init__(self, path):
        raw = open(path, "rb").read()
        h = struct.unpack_from("<20i", raw, 0)
        (_, self.version, self.N, self.S, self.P, self.Aj, self.M, self.NB, self.maxkmh,
         self.lat0, self.lon0, self.cell, self.W, self.H, self.CD) = h[:15]
        pos = [80]

        def take(dtype, n, pad=False):
            sz = np.dtype(dtype).itemsize * n
            a = np.frombuffer(raw, dtype=dtype, count=n, offset=pos[0])
            pos[0] += sz + ((-sz) % 4 if pad else 0)
            return a
        N, S, P = self.N, self.S, self.P
        self.nlat, self.nlon = take("<i4", N), take("<i4", N)
        self.adj_start, self.adj = take("<i4", N + 1), take("<i4", self.Aj)
        self.A, self.B, self.LEN, self.NAME, self.SST = (take("<i4", S) for _ in range(5))
        self.KMH, self.FLG, self.SCNT = take("u1", S, True), take("u1", S, True), take("<u2", S, True)
        self.slat, self.slon = take("<i4", P), take("<i4", P)
        self.name_off = take("<i4", self.M + 1)
        self.name_bytes = raw[pos[0]:pos[0] + self.NB]
        pos[0] += self.NB + (-self.NB) % 4
        self.cell_start, self.cell_data = take("<i4", self.W * self.H + 1), take("<i4", self.CD)
        if self.version not in (1, 2, 3):
            raise ValueError(f"unsupported graph format version {self.version}")
        self.turns = set()
        if self.version in (2, 3):
            if pos[0] + 4 > len(raw):
                raise ValueError("missing turn-restriction section")
            count = struct.unpack_from("<i", raw, pos[0])[0]
            pos[0] += 4
            if count < 0 or pos[0] + count * 8 > len(raw):
                raise ValueError("invalid turn-restriction section size")
            pairs = np.frombuffer(raw, dtype="<i4", count=count * 2, offset=pos[0]).reshape((-1, 2))
            if pairs.size and (np.any(pairs < 0) or np.any(pairs >= self.S * 2)):
                raise ValueError("turn-restriction arc id out of range")
            self.turns = {(int(a), int(b)) for a, b in pairs}
            pos[0] += count * 8
        self.ac_edges, self.ac_fail, self.ac_bad, self.ac_only = [{}], [0], [False], [None]
        if self.version == 3:
            if pos[0] + 4 > len(raw):
                raise ValueError("missing sequence-restriction section")
            nseq, = struct.unpack_from("<i", raw, pos[0]); pos[0] += 4
            if nseq < 0: raise ValueError("invalid sequence-restriction count")
            self.ac_edges, self.ac_fail, self.ac_bad, self.ac_only = [{}], [0], [False], [None]
            local_only = [[]]
            for _ in range(nseq):
                if pos[0] + 12 > len(raw): raise ValueError("truncated sequence restriction")
                typ, plen, alen = struct.unpack_from("<iii", raw, pos[0]); pos[0] += 12
                if typ not in (1, 2) or plen < 1 or alen < 0 or (typ == 1 and alen != 0) or (typ == 2 and alen < 1):
                    raise ValueError("invalid sequence restriction header")
                n = plen + alen
                if pos[0] + 4 * n > len(raw): raise ValueError("truncated sequence restriction arcs")
                arcs = list(struct.unpack_from("<" + "i" * n, raw, pos[0])); pos[0] += 4 * n
                if any(a < 0 or a >= self.S * 2 for a in arcs): raise ValueError("sequence restriction arc out of range")
                state = 0
                for arc in arcs[:plen]:
                    nxt = self.ac_edges[state].get(arc)
                    if nxt is None:
                        nxt = len(self.ac_edges); self.ac_edges[state][arc] = nxt
                        self.ac_edges.append({}); self.ac_fail.append(0); self.ac_bad.append(False); self.ac_only.append(None); local_only.append([])
                    state = nxt
                if typ == 1: self.ac_bad[state] = True
                else: local_only[state].append(frozenset(arcs[plen:]))
            from collections import deque
            q = deque(self.ac_edges[0].values())
            while q:
                state = q.popleft()
                f = self.ac_fail[state]
                self.ac_bad[state] = self.ac_bad[state] or self.ac_bad[f]
                inherited, sets = self.ac_only[f], local_only[state]
                if inherited is not None or sets:
                    allowed = set(inherited) if inherited is not None else None
                    for item in sets:
                        allowed = set(item) if allowed is None else allowed.intersection(item)
                    self.ac_only[state] = frozenset(allowed)
                for arc, child in self.ac_edges[state].items():
                    q.append(child)
                    f = self.ac_fail[state]
                    while f and arc not in self.ac_edges[f]: f = self.ac_fail[f]
                    self.ac_fail[child] = self.ac_edges[f].get(arc, 0)
            if pos[0] != len(raw): raise ValueError("unexpected trailing sequence-restriction data")
        elif pos[0] != len(raw):
            raise ValueError("unexpected trailing data in graph format v1")

    def shape(self, k):
        s, c = self.SST[k], self.SCNT[k]
        pts = [(self.nlat[self.A[k]] / 1e6, self.nlon[self.A[k]] / 1e6)]
        pts += [(self.slat[s + i] / 1e6, self.slon[s + i] / 1e6) for i in range(c)]
        pts.append((self.nlat[self.B[k]] / 1e6, self.nlon[self.B[k]] / 1e6))
        return pts

    def snap(self, lat, lon, max_cells=12):
        cy, cx = (int(lat * 1e6) - self.lat0) // self.cell, (int(lon * 1e6) - self.lon0) // self.cell
        best = None
        k = math.cos(math.radians(lat))
        for r in range(max_cells):
            for y in range(cy - r, cy + r + 1):
                for x in range(cx - r, cx + r + 1):
                    if max(abs(y - cy), abs(x - cx)) != r or not (0 <= y < self.H and 0 <= x < self.W):
                        continue
                    c = y * self.W + x
                    for kseg in self.cell_data[self.cell_start[c]:self.cell_start[c + 1]]:
                        pts, off = self.shape(kseg), 0.0
                        for p, q in zip(pts, pts[1:]):
                            ax, ay, bx, by = (p[1] - lon) * k, p[0] - lat, (q[1] - lon) * k, q[0] - lat
                            dx, dy = bx - ax, by - ay
                            L2 = dx * dx + dy * dy
                            t = 0 if L2 == 0 else max(0.0, min(1.0, -(ax * dx + ay * dy) / L2))
                            d = math.hypot(ax + t * dx, ay + t * dy) * 111320
                            seg_len = hav(p[0], p[1], q[0], q[1])
                            if best is None or d < best[0]:
                                best = (d, int(kseg), off + t * seg_len)
                            off += seg_len
            if best is not None and best[0] <= r * self.cell * 1e-6 * 111320 * min(1.0, k):
                break
        return best

    def cost(self, k, meters):
        mult = PEN.get((self.FLG[k] >> 2) & 15, 1.0) * (UNPAVED_PEN if self.FLG[k] & 128 else 1.0)
        return meters / (self.KMH[k] / 3.6) * mult

    def _ac_next(self, state, arc):
        while state and arc not in self.ac_edges[state]:
            state = self.ac_fail[state]
        return self.ac_edges[state].get(arc, 0)

    def _ac_blocked(self, state, arc):
        allowed = self.ac_only[state]
        if allowed is not None and arc not in allowed:
            return True
        return self.ac_bad[self._ac_next(state, arc)]

    def route(self, s, e):
        """A*: state is (incoming directed arc, via-way restriction automaton state)."""
        _, ks, os_ = s
        _, ke, oe = e
        Lk, Le = self.LEN[ks] / 10.0, self.LEN[ke] / 10.0
        INF = float("inf")
        if ks == ke:
            if oe >= os_ and self.FLG[ks] & 1:
                return self.cost(ks, oe - os_), [(ks, True)]
            if oe < os_ and self.FLG[ks] & 2:
                return self.cost(ks, os_ - oe), [(ks, False)]

        def arc_end(arc):
            k = arc // 2
            return int(self.B[k] if arc % 2 == 0 else self.A[k])

        pts = self.shape(ke)
        acc, elat, elon = 0.0, pts[0][0], pts[0][1]
        for p, q in zip(pts, pts[1:]):
            seg_len = hav(p[0], p[1], q[0], q[1])
            if acc + seg_len >= oe or q == pts[-1]:
                t = 0.0 if seg_len == 0 else max(0.0, min(1.0, (oe - acc) / seg_len))
                elat = p[0] + (q[0] - p[0]) * t
                elon = p[1] + (q[1] - p[1]) * t
                break
            acc += seg_len
        vmax = max(5, self.maxkmh) / 3.6

        def heuristic(node):
            return hav(self.nlat[node] / 1e6, self.nlon[node] / 1e6, elat, elon) / vmax

        dist, prev, heap = {}, {}, []
        starts = []
        start_at_node = os_ <= 1.0 or Lk - os_ <= 1.0
        if start_at_node:
            start_node = int(self.A[ks] if os_ <= 1.0 else self.B[ks])
            for k in self.adj[self.adj_start[start_node]:self.adj_start[start_node + 1]]:
                if self.A[k] == start_node and self.FLG[k] & 1:
                    starts.append((2 * int(k), self.cost(int(k), self.LEN[k] / 10.0)))
                elif self.B[k] == start_node and self.FLG[k] & 2:
                    starts.append((2 * int(k) + 1, self.cost(int(k), self.LEN[k] / 10.0)))
        else:
            if self.FLG[ks] & 2:
                starts.append((2 * ks + 1, self.cost(ks, os_)))
            if self.FLG[ks] & 1:
                starts.append((2 * ks, self.cost(ks, Lk - os_)))
        for arc, cost in starts:
            ac = self._ac_next(0, arc)
            if self.ac_bad[ac]:
                continue
            state = (arc, ac)
            if cost < dist.get(state, INF):
                dist[state] = cost
                prev[state] = None
                heapq.heappush(heap, (cost + heuristic(arc_end(arc)), state))

        best, best_state, best_end_arc = INF, None, None
        done = set()
        while heap:
            estimate, state = heapq.heappop(heap)
            if estimate >= best:
                break
            if state in done:
                continue
            done.add(state)
            in_arc, ac_state = state
            node = arc_end(in_arc)
            end_options = []
            if node == int(self.A[ke]) and self.FLG[ke] & 1:
                end_options.append((2 * ke, self.cost(ke, oe)))
            if node == int(self.B[ke]) and self.FLG[ke] & 2:
                end_options.append((2 * ke + 1, self.cost(ke, Le - oe)))
            for end_arc, tail_cost in end_options:
                if ((in_arc, end_arc) not in self.turns and
                    not self._ac_blocked(ac_state, end_arc) and
                    dist[state] + tail_cost < best):
                    best, best_state, best_end_arc = dist[state] + tail_cost, state, end_arc

            for k in self.adj[self.adj_start[node]:self.adj_start[node + 1]]:
                if self.A[k] == node and self.FLG[k] & 1:
                    out_arc, nxt = 2 * int(k), int(self.B[k])
                elif self.B[k] == node and self.FLG[k] & 2:
                    out_arc, nxt = 2 * int(k) + 1, int(self.A[k])
                else:
                    continue
                if (in_arc, out_arc) in self.turns or self._ac_blocked(ac_state, out_arc):
                    continue
                ac_next = self._ac_next(ac_state, out_arc)
                next_state = (out_arc, ac_next)
                if next_state in done:
                    continue
                candidate = dist[state] + self.cost(int(k), self.LEN[k] / 10.0)
                if candidate < dist.get(next_state, INF):
                    dist[next_state] = candidate
                    prev[next_state] = state
                    heapq.heappush(heap, (candidate + heuristic(nxt), next_state))

        if best_state is None:
            return None
        arcs, state = [], best_state
        while state is not None:
            arcs.append(state[0])
            state = prev[state]
        arcs.reverse()
        legs = [(arc // 2, arc % 2 == 0) for arc in arcs]
        legs.append((best_end_arc // 2, best_end_arc % 2 == 0))
        return best, legs
    def dist(self, legs):
        return sum(self.LEN[k] / 10.0 for k, _ in legs)


if __name__ == "__main__":
    g = Graph(sys.argv[1])
    la1, lo1, la2, lo2 = map(float, sys.argv[2:6])
    s, e = g.snap(la1, lo1), g.snap(la2, lo2)
    print("snap:", s, e)
    r = g.route(s, e)
    if r is None:
        sys.exit("NO ROUTE")
    t, legs = r
    km = g.dist(legs) / 1000
    print(f"route: {km:.1f} km, {t / 60:.0f} min, {len(legs)} segments")
    if len(sys.argv) >= 8:
        lo, hi = float(sys.argv[6]), float(sys.argv[7])
        if not lo <= km <= hi:
            sys.exit(f"route length {km:.1f} km outside {lo}..{hi}")
