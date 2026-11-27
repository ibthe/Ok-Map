#!/usr/bin/env python3
"""Offline tests for satellite tile validation and atomic MBTiles publication."""
import io
import os
import sqlite3
import sys
import tempfile

from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import build_satellite as bs


def image_bytes():
    image = Image.effect_noise((256, 256), 90).convert("RGB")
    out = io.BytesIO()
    image.save(out, "JPEG", quality=92)
    return out.getvalue()


def map_file(path):
    db = sqlite3.connect(path)
    db.execute("create table metadata (name text, value text)")
    db.execute("insert into metadata values ('bounds', '44.0,41.0,44.1,41.1')")
    db.commit()
    db.close()


def run():
    root = tempfile.mkdtemp()
    source = os.path.join(root, "map.mbtiles")
    output = os.path.join(root, "sat.mbtiles")
    map_file(source)
    valid = image_bytes()

    original_fetch = bs.fetch
    try:
        bs.fetch = lambda url: valid
        bs.main(source, output, "https://tiles.invalid/{z}/{x}/{y}.jpg", 0, 0)
        db = sqlite3.connect(output)
        count = db.execute("select count(*) from tiles").fetchone()[0]
        metadata = dict(db.execute("select name, value from metadata"))
        tile = db.execute("select tile_data from tiles limit 1").fetchone()[0]
        db.close()
        assert count == 1, count
        assert metadata["format"] == "jpg", metadata
        assert Image.open(io.BytesIO(tile)).format == "JPEG"
        print("satellite valid-image build test passed")

        # A failed/invalid download must leave the previous destination untouched.
        previous = open(output, "rb").read()
        bs.fetch = lambda url: b"<html>" * 300
        try:
            bs.main(source, output, "https://tiles.invalid/{z}/{x}/{y}.jpg", 0, 0)
            raise AssertionError("invalid image payload unexpectedly built an MBTiles file")
        except RuntimeError:
            pass
        assert open(output, "rb").read() == previous, "failed build replaced the previous MBTiles"
        assert not os.path.exists(output + ".tmp"), "temporary MBTiles was not cleaned up"
        print("satellite invalid-response atomicity test passed")
    finally:
        bs.fetch = original_fetch


if __name__ == "__main__":
    run()
