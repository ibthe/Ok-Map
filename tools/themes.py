#!/usr/bin/env python3
"""Single source of truth for the 5 colour themes (UI + map), each with a day and a night variant.

Design rules (long sessions outdoors / in the car):
  * no pure white / pure black surfaces: softly tinted neutrals reduce glare and eye strain;
  * body text >= 7:1 contrast, secondary text >= 4.5:1, button label on accent >= 4.5:1 (checked below);
  * one calm accent per theme; saturation is lowered for large areas (map land, water), raised only for small signals;
  * the night variants keep the same hue family, lower luminance and avoid blue-heavy light (warmer neutrals).
"""
import colorsys
import sys

ORDER = ["forest", "ocean", "sand", "dusk", "graphite", "sunset", "mint", "contrast"]

# ui colours: surface (screen), panel (floating glass, drawn with alpha), dialog, text, text2, hint, icon,
#             accent, on_accent, container, on_container, crosshair
THEMES = {
    "forest": {
        "title": "Тайга", "about": "спокойный зелёный, как лес: меньше напряжения для глаз",
        "light": dict(surface="#F3F5F0", panel="#F9FBF6", dialog="#FBFCF8", text="#1B2A22", text2="#46594D",
                      hint="#7B8C81", icon="#24372C", accent="#2A7553", on_accent="#FFFFFF",
                      container="#D6EBDD", on_container="#0E3A26", crosshair="#C93A53"),
        "dark": dict(surface="#0F1512", panel="#151D19", dialog="#1B2520", text="#E3ECE6", text2="#A3B5AA",
                     hint="#74857A", icon="#D8E5DC", accent="#7FD1A8", on_accent="#06281A",
                     container="#1F4A37", on_container="#D6EBDD", crosshair="#FF7088"),
        "map": dict(nh=95, ns=.10, nsd=.14, gh=128, gs=1.0, wh=200, ws=.9, os=.9, ph=285),
    },
    "ocean": {
        "title": "Лагуна", "about": "прохладная сине-бирюзовая гамма: ясность и доверие",
        "light": dict(surface="#F1F5F8", panel="#F8FBFD", dialog="#FBFDFE", text="#14222E", text2="#445869",
                      hint="#788A99", icon="#1E3140", accent="#1F6A98", on_accent="#FFFFFF",
                      container="#D3E8F5", on_container="#0A3550", crosshair="#C93A53"),
        "dark": dict(surface="#0D141A", panel="#131C24", dialog="#19232D", text="#E1EBF3", text2="#9FB2C2",
                     hint="#71859A", icon="#D5E3EE", accent="#7CC0EA", on_accent="#052A42",
                     container="#1B4B69", on_container="#D3E8F5", crosshair="#FF7088"),
        "map": dict(nh=205, ns=.16, nsd=.22, gh=150, gs=.8, wh=208, ws=1.15, os=.9, ph=250),
    },
    "sand": {
        "title": "Песок", "about": "тёплая «бумажная» гамма: мягко для чтения и вечером",
        "light": dict(surface="#F6F2EA", panel="#FBF8F2", dialog="#FDFBF6", text="#2A2118", text2="#65564A",
                      hint="#968777", icon="#3A2E22", accent="#A84D29", on_accent="#FFFFFF",
                      container="#F4DDD0", on_container="#4A1C0A", crosshair="#C0392B"),
        "dark": dict(surface="#11141B", panel="#181C25", dialog="#1F242E", text="#EEE8DC", text2="#BDB5A5",
                     hint="#8B8576", icon="#E8E0D0", accent="#E8B96A", on_accent="#2E1F00",
                     container="#5B4518", on_container="#F5E3BD", crosshair="#FF8070"),
        # night desert: the dark map is deep blue-grey with olive land and teal water, the sand colour stays in the UI accent
        "map_dark": dict(nh=225, ns=.0, nsd=.22, gh=95, gs=.55, wh=200, ws=.9, os=1.0, ph=35),
        "map": dict(nh=34, ns=.34, nsd=.24, gh=72, gs=.8, wh=188, ws=.6, os=1.05, ph=15),
    },
    "dusk": {
        "title": "Сумерки", "about": "мягкий лавандовый: расслабляет, хорош для вечера",
        "light": dict(surface="#F4F2F8", panel="#FAF9FD", dialog="#FCFBFE", text="#1E1A2B", text2="#524D67",
                      hint="#868199", icon="#2B2640", accent="#6450AE", on_accent="#FFFFFF",
                      container="#E3DDF6", on_container="#261A5C", crosshair="#C93A53"),
        "dark": dict(surface="#121019", panel="#181624", dialog="#1F1C2D", text="#E8E5F3", text2="#ADA8C2",
                     hint="#7E7996", icon="#DEDAEE", accent="#B5A6F0", on_accent="#231552",
                     container="#473A85", on_container="#E3DDF6", crosshair="#FF7088"),
        "map": dict(nh=262, ns=.12, nsd=.20, gh=145, gs=.65, wh=222, ws=.85, os=.85, ph=270),
    },
    "graphite": {
        "title": "Графит", "about": "строгая чёрно-белая гамма без цвета: максимум фокуса",
        "light": dict(surface="#F1F1F1", panel="#FAFAFA", dialog="#FCFCFC", text="#17181A", text2="#505358",
                      hint="#84878C", icon="#222427", accent="#25272B", on_accent="#FFFFFF",
                      container="#E3E4E6", on_container="#1B1C1F", crosshair="#C0392B"),
        "dark": dict(surface="#101112", panel="#17181A", dialog="#1E1F21", text="#ECEDEE", text2="#A8ABAF",
                     hint="#7B7E83", icon="#E2E3E5", accent="#E4E6E9", on_accent="#141517",
                     container="#34363A", on_container="#E3E4E6", crosshair="#FF8070"),
        "map": dict(nh=220, ns=.015, nsd=.02, gh=120, gs=.10, wh=215, ws=.14, os=.10, ph=220, k=1.08),
    },
    "sunset": {
        "title": "Закат", "about": "нежный розово-коралловый, тёплый и мягкий",
        "light": dict(surface="#F8F1F2", panel="#FDF8F9", dialog="#FEFAFA", text="#2C1A1F", text2="#664A52",
                      hint="#957C84", icon="#3A2229", accent="#B23A5E", on_accent="#FFFFFF",
                      container="#F6DCE3", on_container="#4E0E24", crosshair="#0F6FB0"),
        "dark": dict(surface="#171012", panel="#1F1619", dialog="#271C20", text="#F1E4E7", text2="#BFA6AD",
                     hint="#8F767E", icon="#EBD9DE", accent="#F29BB5", on_accent="#45091F",
                     container="#6E2540", on_container="#F6DCE3", crosshair="#6FC3FF"),
        "map": dict(nh=345, ns=.12, nsd=.14, gh=100, gs=.6, wh=215, ws=.75, os=.9, ph=330),
    },
    "mint": {
        "title": "Мята", "about": "свежая бирюзово-мятная, бодрая и чистая",
        "light": dict(surface="#EFF6F5", panel="#F7FBFA", dialog="#FAFDFC", text="#112624", text2="#3E5855",
                      hint="#728C89", icon="#1B3330", accent="#0A7C7F", on_accent="#FFFFFF",
                      container="#CDEDEA", on_container="#053B3D", crosshair="#C93A53"),
        "dark": dict(surface="#0B1514", panel="#111D1C", dialog="#172625", text="#DDF0EE", text2="#9DB8B5",
                     hint="#6F8D8A", icon="#D0E8E5", accent="#6FD6D2", on_accent="#032B2C",
                     container="#17504F", on_container="#CDEDEA", crosshair="#FF7088"),
        "map": dict(nh=172, ns=.12, nsd=.18, gh=152, gs=.95, wh=188, ws=1.1, os=.8, ph=200),
    },
    "contrast": {
        "title": "Контраст", "about": "максимальная читаемость на ярком солнце",
        "light": dict(surface="#FAFAFA", panel="#FFFFFF", dialog="#FFFFFF", text="#000000", text2="#2E2E2E",
                      hint="#595959", icon="#000000", accent="#0B3FBF", on_accent="#FFFFFF",
                      container="#DCE6FF", on_container="#00123D", crosshair="#D00020"),
        "dark": dict(surface="#000000", panel="#0C0C0C", dialog="#141414", text="#FFFFFF", text2="#D6D6D6",
                     hint="#A0A0A0", icon="#FFFFFF", accent="#FFD400", on_accent="#000000",
                     container="#3A3200", on_container="#FFE766", crosshair="#FF5C7A"),
        "map": dict(nh=215, ns=.04, nsd=.05, gh=125, gs=1.15, wh=210, ws=1.2, os=1.15, ph=270, k=1.15),
    },
}


def mix(a, b, t):
    """a moved towards b by t (0..1); both '#RRGGBB'"""
    x, y = hex2rgb(a), hex2rgb(b)
    return "#%02X%02X%02X" % tuple(round(x[i] + (y[i] - x[i]) * t) for i in range(3))


def dim_palette(t):
    """"Evening": the night palette with lifted surfaces - a soft grey-tinted middle between day and night."""
    d, l = t["dark"], t["light"]
    p = dict(d)
    for k in ("surface", "panel", "dialog"):
        p[k] = mix(d[k], l[k], .22)
    # text has to be brighter than at night, otherwise it loses contrast on the lifted surfaces
    p["text"] = mix(d["text"], "#FFFFFF", .85)
    p["text2"] = mix(d["text2"], "#FFFFFF", .60)
    p["hint"] = mix(d["hint"], "#FFFFFF", .45)
    p["icon"] = mix(d["icon"], "#FFFFFF", .6)
    p["container"] = d["container"]
    p["on_container"] = mix(d["on_container"], "#FFFFFF", .6)
    return p


# colours of the stylised satellite map (not a user-selectable theme)
SAT_MAP = dict(nh=100, ns=.10, nsd=.10, gh=108, gs=2.4, wh=205, ws=2.2, os=1.35, ph=30, k=1.0)


def hex2rgb(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


for _t in THEMES.values():
    _t["dim"] = dim_palette(_t)


def lum(h):
    def f(v):
        v /= 255
        return v / 12.92 if v <= .03928 else ((v + .055) / 1.055) ** 2.4
    r, g, b = hex2rgb(h)
    return .2126 * f(r) + .7152 * f(g) + .0722 * f(b)


def contrast(a, b):
    la, lb = lum(a), lum(b)
    if la < lb:
        la, lb = lb, la
    return (la + .05) / (lb + .05)


def check():
    bad = []
    for name, t in THEMES.items():
        for mode in ("light", "dark", "dim"):
            p = t[mode]
            rules = [("text/surface", p["text"], p["surface"], 7), ("text/panel", p["text"], p["panel"], 7),
                     ("text/dialog", p["text"], p["dialog"], 7), ("text2/surface", p["text2"], p["surface"], 4.5),
                     ("text2/panel", p["text2"], p["panel"], 4.5), ("on_accent/accent", p["on_accent"], p["accent"], 4.5),
                     ("on_container/container", p["on_container"], p["container"], 7),
                     ("accent/panel", p["accent"], p["panel"], 3), ("hint/surface", p["hint"], p["surface"], 3)]
            for label, a, b, need in rules:
                r = contrast(a, b)
                if r < need:
                    bad.append(f"{name}/{mode}: {label} = {r:.2f} < {need}")
    return bad


# ---------------------------------------------------------------- map colour transform (used by gen_style.c)
def _parse(color):
    if color.startswith("#"):
        r, g, b = hex2rgb(color)
        return r, g, b, None
    inner = color[color.index("(") + 1:color.rindex(")")].split(",")
    return int(inner[0]), int(inner[1]), int(inner[2]), float(inner[3])


def map_color(color, m, dark, mode=None, darken=False):
    """Re-tints one base colour of the style for a theme: neutrals take the theme tint, greens / blues /
    purples move to the theme's hues, saturation is scaled. Lightness is kept, so contrast stays intact."""
    r, g, b, a = _parse(color)
    h, l, s = colorsys.rgb_to_hls(r / 255, g / 255, b / 255)
    hd = h * 360
    chroma = (max(r, g, b) - min(r, g, b)) / 255
    if chroma < .06 or l > .96 or l < .04:                  # neutrals: backgrounds, roads, labels
        h2, s2 = m["nh"], (m["nsd"] if dark else m["ns"]) * (1 if l > .04 else .5)
        if l > .985:
            s2 = 0
    elif 60 <= hd < 175:                                     # greens / yellow-greens
        h2, s2 = m["gh"] + (hd - 110) * .45, min(1, s * m["gs"])
    elif 175 <= hd < 255:                                    # blues / cyans (water)
        h2, s2 = m["wh"] + (hd - 210) * .45, min(1, s * m["ws"])
    elif 255 <= hd < 320:                                    # purples (boundaries, aerialways)
        h2, s2 = m["ph"] + (hd - 275) * .3, min(1, s * m["os"])
    else:                                                    # browns, oranges, reds (relief, rock, warnings)
        h2, s2 = hd, min(1, s * m["os"])
    k = m.get("k", 1.0)                                     # optional contrast stretch around mid-grey
    if k != 1.0:
        l = max(0.0, min(1.0, .5 + (l - .5) * k))
    if mode == "dim":                                       # evening: night colours lifted towards the middle
        l = .12 + .76 * l
    elif mode == "sat":                                     # satellite look: natural mid-dark tones
        l = .10 + .72 * l
    if darken:
        l *= .72
    r2, g2, b2 = colorsys.hls_to_rgb((h2 % 360) / 360, l, max(0, min(1, s2)))
    R, G, B = (int(round(v * 255)) for v in (r2, g2, b2))
    if a is None:
        return "#%02x%02x%02x" % (R, G, B)
    return "rgba(%d,%d,%d,%s)" % (R, G, B, a)


if __name__ == "__main__":
    if "--list" in sys.argv:
        print(" ".join(ORDER))
        sys.exit(0)
    problems = check()
    print("\n".join(problems) or "contrast OK")
