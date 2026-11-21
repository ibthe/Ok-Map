# tools/

| file | what it does |
|---|---|
| `themes.py` | the 5 colour themes (UI + map, day/night) and the contrast check |
| `gen_ui_themes.py` | writes Android theme resources (attrs, themes, dimens, type scale) from `themes.py` |
| `gen_style.py` | writes `style_<theme>[_dark].json` (colors, roads, labels): `--theme forest|ocean|sand|dusk|graphite [--dark]` |
| `build_glyphs.py` | builds the label font (SDF `.pbf`) for Latin + Cyrillic + Georgian: tries GitHub glyph repos / TTF fonts / system fonts, judges every candidate by its number of real glyphs (not by file size) and fails the build if `თბილისი Тбилиси Tbilisi` cannot be drawn |
| `check_tiles.py` | fails the build if the tiles have no `name:ka` / `name:ru` / English names |

If `check_tiles.py` complains about missing names, add a language list to the Planetiler
call in `.github/workflows/build.yml` (see the Planetiler OpenMapTiles docs for the exact
option name in your version), e.g. `--languages=ka,ru,en`.

Wikidata translations (more names in ru/en for villages and POIs) can be enabled with
Planetiler's `--fetch-wikidata` option; this makes the tile build much slower.

## Fonts (labels)

`build_glyphs.py` never trusts file sizes: a font host may return a tiny placeholder for every range.
Each source is scored by the number of glyphs with ink in the Latin / Cyrillic / Georgian ranges.
To force your own font, drop `NotoSans-Regular.ttf` and `NotoSansGeorgian-Regular.ttf` (any TTF/OTF that
covers the scripts) into `tools/fonts/` - they are used before anything is downloaded.
In the app: Menu -> Diagnostics shows the glyph count per script (a mark means OK, a cross means placeholder).
