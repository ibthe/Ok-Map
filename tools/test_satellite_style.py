#!/usr/bin/env python3
"""Regression test: satellite tiles must fall back to the offline vector map when absent."""
import json
import os
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
with tempfile.TemporaryDirectory() as tmp:
    output = os.path.join(tmp, "style_sat.json")
    subprocess.run([sys.executable, os.path.join(ROOT, "tools", "gen_style.py"), output, "--sat"],
                   check=True, cwd=ROOT, stdout=subprocess.DEVNULL)
    with open(output, encoding="utf-8") as f:
        layers = json.load(f)["layers"]

    ids = [layer["id"] for layer in layers]
    assert ids.count("sat-imagery") == 1, "satellite raster layer is missing or duplicated"
    sat = ids.index("sat-imagery")
    fills = [i for i, layer in enumerate(layers) if layer["type"] in ("background", "fill")]
    overlays = [i for i, layer in enumerate(layers) if layer["type"] in ("line", "symbol", "circle", "fill-extrusion")]
    assert fills and sat > max(fills), "satellite raster must cover vector base fills"
    assert overlays and sat < min(overlays), "roads and labels must remain above satellite imagery"

print("satellite style fallback test passed")
