#!/usr/bin/env python3
"""Validate a raster MBTiles database before it replaces offline satellite imagery."""
import io
import sqlite3
import sys


def validate(path):
    try:
        db = sqlite3.connect("file:" + path + "?mode=ro", uri=True)
    except sqlite3.Error as exc:
        raise ValueError("not a readable SQLite database: " + str(exc)) from exc
    try:
        check = db.execute("PRAGMA quick_check").fetchone()
        if not check or check[0] != "ok":
            raise ValueError("MBTiles database integrity check failed")
        tables = {row[0] for row in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        if not {"metadata", "tiles"}.issubset(tables):
            raise ValueError("MBTiles must contain metadata and tiles tables")
        columns = {row[1] for row in db.execute("PRAGMA table_info(tiles)")}
        if not {"zoom_level", "tile_column", "tile_row", "tile_data"}.issubset(columns):
            raise ValueError("MBTiles tiles table has an invalid schema")
        metadata = dict(db.execute("SELECT name, value FROM metadata"))
        image_format = str(metadata.get("format", "")).lower()
        expected_format = {"jpg": "JPEG", "jpeg": "JPEG", "png": "PNG", "webp": "WEBP"}.get(image_format)
        min_zoom = int(metadata.get("minzoom", -1))
        max_zoom = int(metadata.get("maxzoom", -1))
        if expected_format is None or not (0 <= min_zoom <= max_zoom <= 22):
            raise ValueError("MBTiles must declare a supported raster format and valid minzoom/maxzoom")
        count = db.execute("SELECT count(*) FROM tiles WHERE tile_data IS NOT NULL").fetchone()[0]
        if count < 1:
            raise ValueError("MBTiles contains no raster tiles")
        samples = db.execute("SELECT tile_data FROM tiles WHERE tile_data IS NOT NULL LIMIT 16").fetchall()
    except sqlite3.Error as exc:
        raise ValueError("invalid MBTiles schema or data: " + str(exc)) from exc
    finally:
        db.close()

    try:
        from PIL import Image
    except ImportError as exc:
        raise ValueError("Pillow is required to validate raster tiles") from exc

    valid = 0
    for (payload,) in samples:
        if not payload:
            continue
        try:
            image = Image.open(io.BytesIO(payload))
            if image.format != expected_format:
                continue
            image.verify()
            valid += 1
        except Exception:
            continue
    if valid == 0:
        raise ValueError("MBTiles contains no valid JPEG, PNG, or WebP image tiles")
    return count


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit("Usage: validate_mbtiles.py <raster.mbtiles>")
    try:
        print("validated raster MBTiles:", validate(sys.argv[1]), "tiles")
    except ValueError as exc:
        sys.exit(str(exc))
