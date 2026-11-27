#!/usr/bin/env python3
"""Generates the map style (OpenMapTiles schema, fully offline).
Usage: gen_style.py <out.json> [--dark | --dim] [--theme <id>]   or   gen_style.py <out.json> --sat [--dark]
(--dim = evening brightness between day and night; --sat = stylised satellite look, with optional real imagery)"""
import json, sys

import themes

NIGHT = "--dark" in sys.argv
DIM = "--dim" in sys.argv
SAT = "--sat" in sys.argv
DARK = NIGHT or DIM or SAT          # all three are drawn from the dark base colours
THEME = next((a.split("=", 1)[1] for a in sys.argv if a.startswith("--theme=")), "forest")
if "--theme" in sys.argv:
    THEME = sys.argv[sys.argv.index("--theme") + 1]
if THEME not in themes.THEMES:
    sys.exit("unknown theme " + THEME)
MAPT = themes.SAT_MAP if SAT else (themes.THEMES[THEME].get("map_dark") if NIGHT and themes.THEMES[THEME].get("map_dark") else themes.THEMES[THEME]["map"])
args = [a for a in sys.argv[1:] if not a.startswith("--") and a != THEME]
SRC = "openmaptiles"
FONT = ["NotoSans"]
L = []


def c(light, dark):
    """base colour of the day / night style, re-tinted for the selected colour theme"""
    return themes.map_color(dark if DARK else light, MAPT, DARK, "sat" if SAT else ("dim" if DIM else None), SAT and NIGHT)


def zi(*stops, base=1.4):
    out = ["interpolate", ["exponential", base], ["zoom"]]
    for z, v in stops:
        out += [z, v]
    return out


def cls(*names):
    return ["match", ["get", "class"], list(names), True, False]


def anyc(*names):
    """class OR subclass is one of names (OpenMapTiles poi: class=railway subclass=station, class=bus subclass=bus_stop)"""
    return ["any", cls(*names), ["match", ["get", "subclass"], list(names), True, False]]


# transport POIs are drawn by their own layers (tr-*, lbl-station ...) and excluded from the generic POI layers
TRANSPORT = anyc("station", "halt", "subway", "tram_stop", "bus_station", "bus_stop", "ferry_terminal", "harbor")


def layer(id, type, sl=None, filter=None, minzoom=None, maxzoom=None, paint=None, layout=None, source=None):
    d = {"id": id, "type": type}
    if type != "background":
        d["source"] = source or SRC
    if sl:
        d["source-layer"] = sl
    if filter is not None:
        d["filter"] = filter
    if minzoom is not None:
        d["minzoom"] = minzoom
    if maxzoom is not None:
        d["maxzoom"] = maxzoom
    if layout:
        d["layout"] = layout
    if paint:
        d["paint"] = paint
    L.append(d)


# ------------------------------------------------------------------ base
layer("background", "background", paint={"background-color": c("#eef0ea", "#14181d")})

# landcover: class first, then subclass details (vineyards, orchards, scrub, scree ...)
LC = {"grass": ("#dfeacd", "#1f2b24"), "wood": ("#c4dcb0", "#1a2a21"), "farmland": ("#ecefd6", "#232a25"),
      "ice": ("#f4f9fd", "#33404e"), "rock": ("#dcd8d0", "#2d2f31"), "sand": ("#f1e8c8", "#34322a"),
      "wetland": ("#d3e6dc", "#1d2e2b")}
for k, (lt, dk) in LC.items():
    layer(f"landcover-{k}", "fill", "landcover", ["==", ["get", "class"], k],
          paint={"fill-color": c(lt, dk), "fill-opacity": 0.92, "fill-antialias": False})


def sub(id, names, light, dark, minzoom=10, opacity=0.95):
    layer(id, "fill", "landcover", ["match", ["get", "subclass"], list(names), True, False], minzoom=minzoom,
          paint={"fill-color": c(light, dark), "fill-opacity": opacity})


sub("landcover-meadow", ["meadow", "grassland", "grass"], "#d8e8c0", "#22302a")
sub("landcover-scrub", ["scrub", "heath", "shrubbery", "fell", "tundra"], "#cddcb0", "#1f2c24")
sub("landcover-vineyard", ["vineyard"], "#dfe3b4", "#2b3022", 11)
sub("landcover-orchard", ["orchard", "plant_nursery", "garden"], "#d4e4b0", "#232f22", 11)
sub("landcover-bare-rock", ["bare_rock", "scree", "rock", "cliff", "stone"], "#d6d1c7", "#34363a", 9)
sub("landcover-glacier", ["glacier", "ice"], "#f6fbff", "#3a4858", 6)

layer("landuse-residential", "fill", "landuse", cls("residential", "suburb", "neighbourhood"), minzoom=6,
      paint={"fill-color": c("#e6e3dc", "#1f242a"), "fill-opacity": zi((6, 0.4), (12, 0.95))})
layer("landuse-commercial", "fill", "landuse", cls("commercial", "retail"), minzoom=10,
      paint={"fill-color": c("#efe0d6", "#2a2523")})
layer("landuse-industrial", "fill", "landuse", cls("industrial", "railway", "quarry", "dam"), minzoom=10,
      paint={"fill-color": c("#e1dfda", "#25282b")})
layer("landuse-military", "fill", "landuse", cls("military"), minzoom=9,
      paint={"fill-color": c("#e6dcd9", "#2b2626"), "fill-opacity": 0.8})
layer("landuse-green", "fill", "landuse",
      cls("cemetery", "pitch", "playground", "stadium", "garden", "track", "theme_park", "zoo"),
      minzoom=11, paint={"fill-color": c("#d2e5c2", "#22322a")})
layer("landuse-institution", "fill", "landuse", cls("school", "university", "college", "kindergarten", "library"),
      minzoom=12, paint={"fill-color": c("#ebe3d3", "#292925")})
layer("landuse-hospital", "fill", "landuse", cls("hospital"), minzoom=12,
      paint={"fill-color": c("#f3dcdc", "#33262a")})
layer("landuse-station", "fill", "landuse", cls("bus_station"), minzoom=13,
      paint={"fill-color": c("#f0e2cc", "#33291f")})
layer("landuse-station-outline", "line", "landuse", cls("bus_station"), minzoom=13,
      paint={"line-color": c("#c98a3a", "#e0a060"), "line-width": 1.4, "line-dasharray": [3, 2]})

layer("park", "fill", "park", minzoom=5,
      paint={"fill-color": c("#bfdca6", "#25382b"), "fill-opacity": zi((5, 0.22), (10, 0.42), base=1)})
layer("park-outline", "line", "park", minzoom=8, layout={"line-join": "round"},
      paint={"line-color": c("#86b872", "#4f7a58"), "line-width": zi((8, 0.6), (14, 1.4)),
             "line-dasharray": [4, 2]})

# relief shading (raster-dem built from terrain tiles); removed at runtime if relief.mbtiles is absent.
# Softer than before: low exaggeration at close zoom so the noise of the DEM stays out of the picture.
layer("relief-hillshade", "hillshade", source="relief",
      paint={"hillshade-exaggeration": zi((5, 0.34), (9, 0.26), (12, 0.14), (14, 0.08), base=1),
             "hillshade-illumination-direction": 315,
             "hillshade-shadow-color": c("#6f6a5c", "#000000"),
             "hillshade-highlight-color": c("#ffffff", "#3a4350"),
             "hillshade-accent-color": c("#8d8774", "#000000")})

# ---- water. Adjacent OSM water polygons (reservoirs, fish ponds, split lakes) leave hairline gaps; the
# fill is drawn without anti-aliasing and a same-coloured stroke on top closes every seam, so a pond
# complex reads as one body of water instead of a chequerboard.
WATER = c("#a6cbf0", "#10253d")
layer("water", "fill", "water", paint={"fill-color": WATER, "fill-antialias": False})
layer("water-seam", "line", "water", minzoom=7, layout={"line-join": "round", "line-cap": "round"},
      paint={"line-color": WATER, "line-width": zi((7, 0.8), (11, 2.2), (13, 3.2), (15, 4.5), (18, 8), base=1.3)})
layer("waterway-river", "line", "waterway", ["==", ["get", "class"], "river"], minzoom=8,
      layout={"line-cap": "round", "line-join": "round"},
      paint={"line-color": WATER, "line-width": zi((8, 0.6), (12, 1.8), (14, 3.2), (18, 14))})
layer("waterway-minor", "line", "waterway", cls("stream", "canal", "drain", "ditch"), minzoom=11,
      layout={"line-cap": "round", "line-join": "round"},
      paint={"line-color": WATER, "line-width": zi((11, 0.4), (14, 1.2), (16, 2.2), (18, 4.5))})

layer("aeroway-area", "fill", "aeroway", ["==", ["geometry-type"], "Polygon"], minzoom=9,
      paint={"fill-color": c("#e2dff0", "#2a2838"), "fill-opacity": 0.85})
layer("aeroway-outline", "line", "aeroway", ["==", ["geometry-type"], "Polygon"], minzoom=9,
      layout={"line-join": "round"},
      paint={"line-color": c("#7f6fb8", "#a898d0"), "line-width": zi((9, 1.0), (13, 1.8), (16, 2.6)),
             "line-dasharray": [3, 2]})
layer("aeroway-runway", "line", "aeroway", ["all", ["==", ["geometry-type"], "LineString"], cls("runway")],
      minzoom=10, paint={"line-color": c("#cfcbc3", "#3a3e44"), "line-width": zi((10, 1), (14, 6), (18, 40))})
layer("aeroway-taxiway", "line", "aeroway", ["all", ["==", ["geometry-type"], "LineString"], cls("taxiway")],
      minzoom=12, paint={"line-color": c("#d6d2ca", "#33373c"), "line-width": zi((12, 0.5), (16, 3), (18, 10))})

layer("building", "fill", "building", minzoom=13,
      paint={"fill-color": c("#d9d3c7", "#2a2f36"), "fill-opacity": zi((13, 0), (14.5, 1), base=1)})
layer("building-outline", "line", "building", minzoom=15,
      paint={"line-color": c("#c4bdaf", "#3b414a"), "line-width": 0.5})

# ------------------------------------------------------------------ roads
LINE = {"line-cap": "round", "line-join": "round"}
BUTT = {"line-cap": "butt", "line-join": "round"}
NOTUNNEL = ["!=", ["get", "brunnel"], "tunnel"]
BRIDGE = ["==", ["get", "brunnel"], "bridge"]
TUNNEL = ["==", ["get", "brunnel"], "tunnel"]
RAMP = ["==", ["get", "ramp"], 1]


def rd(cl, extra=None):
    f = cls(*cl)
    return ["all", f, extra] if extra is not None else f


ROADS = [  # name, classes, minzoom, fill(light,dark), casing(light,dark), width stops
    ("service", ["service"], 13, ("#ffffff", "#3b414a"), ("#d3cdc1", "#12151a"), [(13, 0.8), (16, 3), (18, 9)]),
    ("minor", ["minor"], 11, ("#ffffff", "#464d57"), ("#d0cabe", "#12151a"), [(11, 0.6), (14, 2.5), (16, 7), (18, 22)]),
    ("tertiary", ["tertiary"], 9, ("#ffffff", "#58606b"), ("#cbc4b7", "#12151a"),
     [(9, 0.6), (12, 1.8), (14, 4), (16, 10), (18, 28)]),
    ("secondary", ["secondary"], 7, ("#fff0b5", "#80703f"), ("#d6c98f", "#251f10"),
     [(7, 0.6), (12, 2.2), (14, 5), (16, 12), (18, 32)]),
    ("primary", ["primary"], 5, ("#fdd98a", "#a2843f"), ("#d4b46c", "#251f10"),
     [(5, 0.5), (10, 1.6), (12, 3), (14, 6), (16, 13), (18, 34)]),
    ("trunk", ["trunk"], 4, ("#fbc577", "#b98640"), ("#d19f50", "#251f10"),
     [(4, 0.5), (10, 1.8), (12, 3.2), (14, 6.5), (16, 14), (18, 36)]),
    ("motorway", ["motorway"], 4, ("#f4a56b", "#c4753d"), ("#c97c3d", "#241407"),
     [(4, 0.6), (10, 2), (12, 3.5), (14, 7), (16, 15), (18, 38)]),
]

# tunnels first (under everything): pale dashed casing so they read as "underground"
for name, classes, mz, fill, casing, st in ROADS:
    layer(f"road-{name}-tunnel", "line", "transportation", rd(classes, TUNNEL), minzoom=mz, layout=BUTT,
          paint={"line-color": c(*casing), "line-opacity": 0.7, "line-dasharray": [2, 1.5],
                 "line-width": zi(*[(z, round(w * 1.1 + 0.4, 2)) for z, w in st])})
for name, classes, mz, fill, casing, st in ROADS:
    layer(f"road-{name}-casing", "line", "transportation", rd(classes, NOTUNNEL), minzoom=mz, layout=LINE,
          paint={"line-color": c(*casing),
                 "line-width": zi(*[(z, round(w * 1.35 + 0.7, 2)) for z, w in st])})
# bridges: dark outer casing, drawn over the normal casing
for name, classes, mz, fill, casing, st in ROADS:
    if mz > 14:
        continue
    layer(f"road-{name}-bridge", "line", "transportation", rd(classes, BRIDGE), minzoom=max(mz, 12), layout=BUTT,
          paint={"line-color": c("#8d877b", "#05070a"),
                 "line-width": zi(*[(z, round(w * 1.35 + 2.2, 2)) for z, w in st])})

# unpaved/track/path with surface hint
UNPAVED = ["match", ["get", "surface"], ["unpaved", "gravel", "dirt", "ground", "sand", "grass", "mud"], True, False]
layer("road-track", "line", "transportation", cls("track"), minzoom=11, layout=LINE,
      paint={"line-color": c("#a98b58", "#90805a"), "line-dasharray": [3, 1.5],
             "line-width": zi((11, 0.5), (14, 1.2), (16, 1.8), (18, 3.2))})
layer("road-path", "line", "transportation", cls("path"), minzoom=12, layout=LINE,
      paint={"line-color": c("#b0603a", "#c98a62"), "line-dasharray": [1.2, 1.4],
             "line-width": zi((12, 0.6), (15, 1.3), (18, 2.6))})
layer("road-pedestrian", "line", "transportation", cls("pedestrian"), minzoom=13, layout=LINE,
      paint={"line-color": c("#ffffff", "#4a515b"), "line-width": zi((13, 0.8), (16, 3.5), (18, 9))})
for name, classes, mz, fill, casing, st in ROADS:
    layer(f"road-{name}", "line", "transportation", rd(classes, NOTUNNEL), minzoom=mz, layout=LINE,
          paint={"line-color": c(*fill), "line-width": zi(*st)})
# unpaved tertiary/minor roads get a dashed lighter centre so gravel roads are distinguishable
layer("road-unpaved", "line", "transportation",
      ["all", cls("tertiary", "minor", "service", "secondary"), UNPAVED, NOTUNNEL], minzoom=12, layout=BUTT,
      paint={"line-color": c("#c9bfa8", "#2b3036"), "line-dasharray": [2.5, 2.5], "line-opacity": 0.9,
             "line-width": zi((12, 0.5), (15, 1.6), (18, 5))})
layer("road-oneway", "symbol", "transportation",
      ["all", ["==", ["get", "oneway"], 1], cls("minor", "tertiary", "secondary", "primary", "service")],
      minzoom=16, source="openmaptiles-labels",
      layout={"symbol-placement": "line", "symbol-spacing": 150, "text-field": ">",
              "text-font": FONT, "text-size": 12, "text-keep-upright": False, "text-rotation-alignment": "map"},
      paint={"text-color": c("#8a8a92", "#9aa0aa")})
layer("rail", "line", "transportation", cls("rail"), minzoom=8, layout={"line-join": "round"},
      paint={"line-color": c("#8a8a98", "#6f7280"), "line-dasharray": [4, 3],
             "line-width": zi((8, 0.6), (14, 1.2), (18, 2))})
layer("transit", "line", "transportation", cls("transit"), minzoom=11,
      paint={"line-color": c("#9a8fb5", "#7d7396"), "line-dasharray": [3, 2], "line-width": 1.1})
layer("aerialway", "line", "transportation", cls("aerialway", "gondola", "cable_car", "chair_lift", "drag_lift",
                                                  "mixed_lift", "t-bar", "j-bar", "platter", "rope_tow", "magic_carpet"),
      minzoom=10, layout=LINE,
      paint={"line-color": c("#6b5b95", "#a898d0"), "line-dasharray": [1, 2.2],
             "line-width": zi((10, 0.8), (14, 1.4), (18, 2.4))})
layer("pier", "line", "transportation", cls("pier"), minzoom=13,
      paint={"line-color": c("#d6d0c3", "#3a3f46"), "line-width": zi((13, 0.8), (18, 6))})
layer("ferry", "line", "transportation", cls("ferry"), minzoom=6,
      paint={"line-color": c("#5f93c9", "#4f80b4"), "line-dasharray": [3, 3], "line-width": 1.2})

# ------------------------------------------------------------------ boundaries
NOMARITIME = ["!=", ["get", "maritime"], 1]
B2 = c("#7a6e92", "#a99cc6")
layer("boundary-2", "line", "boundary",
      ["all", ["==", ["get", "admin_level"], 2], NOMARITIME, ["!=", ["get", "disputed"], 1]],
      layout={"line-join": "round"},
      paint={"line-color": B2, "line-width": zi((2, 0.6), (6, 1.4), (10, 2.4))})
layer("boundary-2-disputed", "line", "boundary",
      ["all", ["==", ["get", "admin_level"], 2], NOMARITIME, ["==", ["get", "disputed"], 1]],
      paint={"line-color": B2, "line-dasharray": [3, 2], "line-width": zi((2, 0.6), (6, 1.4), (10, 2.4))})
layer("boundary-4", "line", "boundary", ["all", ["==", ["get", "admin_level"], 4], NOMARITIME], minzoom=5,
      paint={"line-color": c("#a898bf", "#7d7096"), "line-dasharray": [3, 2],
             "line-width": zi((5, 0.6), (10, 1.6))})
layer("boundary-6", "line", "boundary", ["all", ["==", ["get", "admin_level"], 6], NOMARITIME], minzoom=9,
      paint={"line-color": c("#c3b9d2", "#5d5470"), "line-dasharray": [2, 2], "line-width": 0.9})

# ------------------------------------------------------------------ dots
# peaks / spot heights are shown only in the elevation map type: hidden here, the app turns them on (visibility)
layer("peak-dot", "symbol", "mountain_peak", ["has", "ele"], minzoom=9, source="openmaptiles-labels",
      layout={"icon-image": "ic-peak", "icon-size": zi((9, 0.5), (14, 0.7)), "icon-allow-overlap": True,
              "visibility": "none"})
NOT_TR = ["!", TRANSPORT]
layer("poi-dot", "circle", "poi", ["all", ["has", "name"], NOT_TR], minzoom=15,
      paint={"circle-radius": 3, "circle-color": c("#8793a5", "#9aa7bb"), "circle-stroke-width": 1,
             "circle-stroke-color": c("#ffffff", "#1c1f24")})

# transport: railway stations, metro, bus stations, stops, ferry terminals, airports.
# Drawn as badge icons ("ic-train", "ic-bus", "ic-stop", "ic-ferry", "ic-plane"): the app renders them and adds them to the style.
def icon(id, sl, filt, image, minzoom, sizes):
    layer(id, "symbol", sl, filt, minzoom=minzoom, source="openmaptiles-labels",
          layout={"icon-image": image, "icon-size": zi(*sizes), "icon-allow-overlap": True,
                  "icon-ignore-placement": True, "icon-anchor": "center"})


icon("tr-stop-dot", "poi", anyc("bus_stop", "tram_stop"), "ic-stop", 15, [(15, 0.5), (18, 0.75)])
icon("tr-bus-dot", "poi", anyc("bus_station"), "ic-bus", 12, [(12, 0.6), (16, 0.95)])
icon("tr-ferry-dot", "poi", anyc("ferry_terminal", "harbor"), "ic-ferry", 11, [(11, 0.6), (16, 0.95)])
icon("tr-station-dot", "poi", anyc("station", "halt", "subway"), "ic-train", 10, [(10, 0.55), (14, 0.8), (17, 1.05)])
icon("tr-aerodrome-dot", "aerodrome_label", ["!=", ["get", "class"], "private"], "ic-plane", 7, [(7, 0.6), (12, 0.95)])

# ------------------------------------------------------------------ labels
# Symbol layers whose id starts with "lbl-" get their text-field replaced at runtime (LabelExpr.kt).
HALO = c("rgba(255,255,255,0.58)", "rgba(16,19,24,0.58)")
WHALO = c("rgba(196,222,246,0.6)", "rgba(10,26,44,0.6)")


def label(id, sl, filter, minzoom, size, color, halo=None, maxzoom=None, line=False, anchor=None,
          offset=None, sort=None, width=8, halo_w=1.0, spacing=None):
    lo = {"text-field": ["to-string", ["get", "name"]], "text-font": FONT, "text-size": size,
          "text-max-width": width, "text-padding": 2}
    if line:
        lo.update({"symbol-placement": "line", "symbol-spacing": 280, "text-max-angle": 35})
    if anchor:
        lo["text-anchor"] = anchor
    if offset:
        lo["text-offset"] = offset
    if sort is not None:
        lo["symbol-sort-key"] = sort
    if spacing:
        lo["text-letter-spacing"] = spacing
    layer(id, "symbol", sl, filter, minzoom, maxzoom, layout=lo, source="openmaptiles-labels",
          paint={"text-color": color, "text-halo-color": halo or HALO, "text-halo-width": halo_w, "text-halo-blur": 1.1})


RANK = ["to-number", ["get", "rank"], 99]
REF = ["any", ["has", "name"], ["has", "ref"]]
BLUE = c("#35739f", "#7db4e6")

label("lbl-water", "water_name", ["has", "name"], 0, zi((0, 10), (8, 12), (14, 14)), BLUE, halo=WHALO)
label("lbl-waterway", "waterway", ["all", ["has", "name"], cls("river")], 10, zi((10, 10), (16, 13)), BLUE,
      halo=WHALO, line=True)
label("lbl-road-major", "transportation_name", ["all", REF, cls("motorway", "trunk", "primary", "secondary")], 9,
      zi((9, 10), (16, 13)), c("#4a4a4a", "#d0d0d0"), line=True)
label("lbl-road-minor", "transportation_name",
      ["all", REF, cls("tertiary", "minor", "service", "track", "path")], 13,
      zi((13, 10), (17, 12)), c("#555555", "#bbbbbb"), line=True)
label("lbl-peak", "mountain_peak", ["has", "ele"], 9, zi((9, 10), (14, 12)), c("#7a5a3a", "#d3a574"),
      anchor="top", offset=[0, 0.7], sort=["-", 0, ["to-number", ["get", "ele"], 0]], width=7)
for _l in L:
    if _l["id"] == "lbl-peak":
        _l["layout"]["visibility"] = "none"
label("lbl-poi", "poi", ["all", ["has", "name"], NOT_TR], 15, 11, c("#5b6574", "#aab6c8"), anchor="top", offset=[0, 0.6], width=7)
label("lbl-aerodrome", "aerodrome_label", ["has", "name"], 8, 11, c("#556070", "#a0acc0"), anchor="top",
      offset=[0, 0.6])
TRC = c("#1e5fb8", "#8fc0f5")
label("lbl-station", "poi", ["all", ["has", "name"], anyc("station", "halt", "subway")], 11, zi((11, 10), (16, 12)),
      TRC, anchor="top", offset=[0, 0.7], width=7, sort=RANK)
label("lbl-busstation", "poi", ["all", ["has", "name"], anyc("bus_station")], 13, 11,
      c("#a8631a", "#e0a060"), anchor="top", offset=[0, 0.7], width=7)
label("lbl-ferry", "poi", ["all", ["has", "name"], anyc("ferry_terminal", "harbor")], 12, 11,
      c("#1f7a70", "#4cc3b3"), anchor="top", offset=[0, 0.7], width=7)
label("lbl-stop", "poi", ["all", ["has", "name"], anyc("bus_stop", "tram_stop")], 16, 10,
      c("#a8631a", "#e0a060"), anchor="top", offset=[0, 0.6], width=7)
label("lbl-hamlet", "place", cls("hamlet", "neighbourhood", "isolated_dwelling"), 13, zi((13, 10), (16, 13)),
      c("#666666", "#b5b5b5"), sort=RANK, width=7)
label("lbl-suburb", "place", cls("suburb", "quarter"), 11, zi((11, 10), (15, 14)), c("#555555", "#c0c0c0"),
      sort=RANK)
label("lbl-village", "place", cls("village"), 10, zi((10, 10), (14, 13)), c("#444444", "#cfcfcf"), sort=RANK,
      width=7)
label("lbl-town", "place", cls("town"), 8, zi((8, 11), (12, 15)), c("#333333", "#dedede"), sort=RANK)
label("lbl-city", "place", cls("city"), 4, zi((4, 12), (8, 16), (12, 20)), c("#222222", "#f0f0f0"), sort=RANK)
label("lbl-island", "place", cls("island"), 9, zi((9, 10), (13, 13)), c("#5a6b4a", "#a3b894"), sort=RANK)
label("lbl-state", "place", cls("state", "province"), 4, zi((4, 10), (8, 14)), c("#7b6f93", "#b3a6cf"),
      maxzoom=10, sort=RANK, width=7, spacing=0.08)
label("lbl-country", "place", cls("country"), 2, zi((2, 11), (5, 15), (8, 22)), c("#4a4458", "#d6cfe6"),
      maxzoom=9, sort=RANK, width=7, spacing=0.12)

if SAT:
    for l in L:
        if l["id"] == "relief-hillshade":
            l["paint"] = {"hillshade-exaggeration": zi((5, 0.62), (9, 0.52), (12, 0.34), (14, 0.22), base=1),
                          "hillshade-illumination-direction": 315, "hillshade-shadow-color": "#000000",
                          "hillshade-highlight-color": "#ffffff", "hillshade-accent-color": "#1c261c"}
    # Put every base fill below the raster and every cartographic overlay above it. Missing
    # imagery tiles then reveal the vector map, while roads, labels and hillshade stay legible.
    base = [layer for layer in L if layer["type"] in ("background", "fill")]
    overlays = [layer for layer in L if layer["type"] not in ("background", "fill")]
    L = base + [{"id": "sat-imagery", "type": "raster", "source": "sat",
                 "paint": {"raster-fade-duration": 0}}] + overlays

style = {
    "version": 8,
    "name": "Ok Map " + ("Satellite" if SAT else THEME) + (" Dark" if NIGHT else " Dim" if DIM else ""),
    "sources": {
        SRC: {"type": "vector", "url": "__MBTILES_URL__",
              "attribution": "© OpenStreetMap contributors, © OpenMapTiles, © Natural Earth"},
        # same tiles, second source: symbol layers wait for glyphs; keeping them apart means a glyph
        # problem can never hide the roads/water/landcover of a tile
        "openmaptiles-labels": {"type": "vector", "url": "__MBTILES_URL__"},
        **({"sat": {"type": "raster", "url": "__SAT_URL__", "tileSize": 256,
                    "attribution": "Orthophoto: © National Agency of Public Registry of Georgia (NAPR)"}} if SAT else {}),
        "relief": {"type": "raster-dem", "url": "__RELIEF_URL__", "tileSize": 256, "encoding": "terrarium",
                   "attribution": "Terrain: Mapzen/AWS Terrain Tiles (SRTM, ASTER, others)"},
    },
    "glyphs": "asset://glyphs/{fontstack}/{range}.pbf",
    "layers": L,
}

out = args[0] if args else "app/src/main/assets/style.json"
with open(out, "w", encoding="utf-8") as f:
    json.dump(style, f, ensure_ascii=False, indent=1)
print("wrote", out, len(L), "layers", "(sat)" if SAT else "", "(dark)" if NIGHT else "(dim)" if DIM else "")
