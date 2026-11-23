#!/usr/bin/env python3
"""Sanity check of the bearing formula used by Compass.kt (Qibla = initial great-circle bearing to the Kaaba)."""
import math

KAABA = (21.422487, 39.826206)


def bearing(la1, lo1, la2, lo2):
    p1, p2, dl = math.radians(la1), math.radians(la2), math.radians(lo2 - lo1)
    y = math.sin(dl) * math.cos(p2)
    x = math.cos(p1) * math.sin(p2) - math.sin(p1) * math.cos(p2) * math.cos(dl)
    return (math.degrees(math.atan2(y, x)) + 360) % 360


# well-known Qibla directions (degrees from true north)
CASES = {"Moscow": (55.7558, 37.6173, 176.4), "New York": (40.7128, -74.006, 58.5),
         "London": (51.5074, -0.1278, 118.99), "Jakarta": (-6.2088, 106.8456, 295.1), "Tbilisi": (41.7151, 44.8271, None)}
for name, (la, lo, exp) in CASES.items():
    b = bearing(la, lo, *KAABA)
    print(f"{name:9s} qibla {b:6.1f}")
    if exp is not None:
        assert abs(b - exp) < 1.5, (name, b, exp)
assert abs(bearing(0, 0, 10, 0)) < 1e-9 and abs(bearing(0, 0, 0, 10) - 90) < 1e-9
print("compass formulas OK")
