#!/usr/bin/env python3
"""Planetiler may write `tiles` as a VIEW over deduplicated tables. Turn it into a plain MBTiles table
so every reader (MapLibre, sqlite3, other tools) sees the standard layout."""
import sqlite3
import sys

db = sqlite3.connect(sys.argv[1])
row = db.execute("select type from sqlite_master where name='tiles'").fetchone()
print("tiles object type:", row)
if row and row[0] == "view":
    db.executescript("""
        create table tiles_plain(zoom_level integer, tile_column integer, tile_row integer, tile_data blob);
        insert into tiles_plain select zoom_level, tile_column, tile_row, tile_data from tiles;
        drop view tiles;
        drop table if exists tiles_shallow;
        drop table if exists tiles_data;
        drop table if exists map;
        drop table if exists images;
        alter table tiles_plain rename to tiles;
        create unique index tile_index on tiles(zoom_level, tile_column, tile_row);
    """)
    db.commit()
    db.execute("vacuum")
    print("converted view -> table")
n = db.execute("select count(*) from tiles").fetchone()[0]
print("tiles:", n)
