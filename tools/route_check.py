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
        (_, _, self.N, self.S, self.P, self.Aj, self.M, self.NB, self.maxkmh,
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
        assert pos[0] + self.CD * 4 - self.CD * 4 <= len(raw)

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
            if best is not None and best[0] < r * self.cell * 1e-6 * 111320 * 0.9:
                break
        return best

    def cost(self, k, meters):
        mult = PEN.get((self.FLG[k] >> 2) & 15, 1.0) * (UNPAVED_PEN if self.FLG[k] & 128 else 1.0)
        return meters / (self.KMH[k] / 3.6) * mult

    def route(self, s, e):
        """s, e = snap results (d, seg, offset_m). Returns (time_s, [(seg, forward)]) or None"""
        _, ks, os_ = s
        _, ke, oe = e
        el, eo = self.nlat, self.nlon
        tgt = (self.nlat[self.A[ke]], self.nlon[self.A[ke]])
        elat, elon = None, None
        pts = self.shape(ke)
        # end point coordinates for the heuristic
        acc, cur = 0.0, pts[0]
        for p, q in zip(pts, pts[1:]):
            L = hav(p[0], p[1], q[0], q[1])
            if acc + L >= oe or q is pts[-1]:
                t = 0 if L == 0 else max(0.0, min(1.0, (oe - acc) / L))
                elat, elon = p[0] + (q[0] - p[0]) * t, p[1] + (q[1] - p[1]) * t
                break
            acc += L
        vmax = self.maxkmh / 3.6
        INF = float("inf")
        g = {}
        prev = {}
        heap = []
        Lk = self.LEN[ks] / 10.0
        fl = self.FLG[ks]
        starts = []
        if fl & 2:
            starts.append((self.A[ks], self.cost(ks, os_), (ks, False, os_, 0.0)))
        if fl & 1:
            starts.append((self.B[ks], self.cost(ks, Lk - os_), (ks, True, os_, Lk)))
        best, best_end = INF, None
        if ks == ke:
            if oe >= os_ and fl & 1:
                best, best_end = self.cost(ks, oe - os_), ("direct",)
            elif oe < os_ and fl & 2:
                best, best_end = self.cost(ks, os_ - oe), ("direct",)

        def h(n):
            return hav(self.nlat[n] / 1e6, self.nlon[n] / 1e6, elat, elon) / vmax
        for n, c, via in starts:
            g[int(n)] = c
            prev[int(n)] = (None, via)
            heapq.heappush(heap, (c + h(n), int(n)))
        Le = self.LEN[ke] / 10.0
        fe = self.FLG[ke]
        ends = {}
        if fe & 1:
            ends[int(self.A[ke])] = self.cost(ke, oe)
        if fe & 2:
            ends[int(self.B[ke])] = self.cost(ke, Le - oe)
        done = set()
        while heap:
            f, u = heapq.heappop(heap)
            if u in done:
                continue
            if f >= best:
                break
            done.add(u)
            if u in ends and g[u] + ends[u] < best:
                best, best_end = g[u] + ends[u], u
            for k in self.adj[self.adj_start[u]:self.adj_start[u + 1]]:
                fl = self.FLG[k]
                if self.A[k] == u and fl & 1:
                    v, fwd = int(self.B[k]), True
                elif self.B[k] == u and fl & 2:
                    v, fwd = int(self.A[k]), False
                else:
                    continue
                c = g[u] + self.cost(k, self.LEN[k] / 10.0)
                if c < g.get(v, INF):
                    g[v] = c
                    prev[v] = (u, (int(k), fwd, 0.0, 0.0))
                    heapq.heappush(heap, (c + h(v), v))
        if best_end is None:
            return None
        legs = []
        if best_end == ("direct",):
            return best, [(ks, oe >= os_)]
        u = best_end
        legs.append((ke, u == int(self.A[ke])))
        while u is not None:
            p, via = prev[u]
            legs.append((via[0], via[1]))
            u = p
        legs.reverse()
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
