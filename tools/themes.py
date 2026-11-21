#!/usr/bin/env python3
"""Single source of truth for the 5 colour themes (UI + map), each with a day and a night variant.

Design rules (long sessions outdoors / in the car):
  * no pure white / pure black surfaces: softly tinted neutrals reduce glare and eye strain;
  * body text >= 7:1 contrast, secondary text >= 4.5:1, button label on accent >= 4.5:1 (checked below);
  * one calm accent per theme; saturation is lowered for large areas (map land, water), raised only for small signals;
  * the night variants keep the same hue family, lower luminance and avoid blue-heavy light (warmer neutrals).
"""
import colorsys

ORDER = ["forest", "ocean", "sand", "dusk", "graphite"]

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
        "dark": dict(surface="#16120E", panel="#1D1812", dialog="#241E17", text="#EFE6DA", text2="#BBAA97",
                     hint="#8C7D6C", icon="#E8DCCB", accent="#E8996F", on_accent="#3A1505",
                     container="#6B3418", on_container="#F4DDD0", crosshair="#FF8070"),
        "map": dict(nh=38, ns=.26, nsd=.20, gh=82, gs=.75, wh=195, ws=.7, os=1.0, ph=15),
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
        "title": "Графит", "about": "нейтральная серая гамма с тёплым акцентом: минимум отвлечения",
        "light": dict(surface="#F2F2F1", panel="#F9F9F8", dialog="#FCFCFB", text="#1D1F21", text2="#53575C",
                      hint="#82878C", icon="#2A2D30", accent="#A8531A", on_accent="#FFFFFF",
                      container="#F6E0CD", on_container="#4A2308", crosshair="#C0392B"),
        "dark": dict(surface="#111213", panel="#181A1C", dialog="#1F2123", text="#E8E9EA", text2="#A9ADB2",
                     hint="#7A7F85", icon="#E0E2E4", accent="#F0A868", on_accent="#3A1C04",
                     container="#6A3A12", on_container="#F6E0CD", crosshair="#FF8070"),
        "map": dict(nh=210, ns=.03, nsd=.04, gh=120, gs=.3, wh=210, ws=.35, os=.3, ph=220),
    },
}


def hex2rgb(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


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
        for mode in ("light", "dark"):
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


def map_color(color, m, dark):
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
    r2, g2, b2 = colorsys.hls_to_rgb((h2 % 360) / 360, l, max(0, min(1, s2)))
    R, G, B = (int(round(v * 255)) for v in (r2, g2, b2))
    if a is None:
        return "#%02x%02x%02x" % (R, G, B)
    return "rgba(%d,%d,%d,%s)" % (R, G, B, a)


if __name__ == "__main__":
    problems = check()
    print("\n".join(problems) or "contrast OK")
