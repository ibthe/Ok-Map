#!/usr/bin/env python3
"""Writes the Android theme resources (attrs, 5 colour themes x day/night, type scale, dimens) from tools/themes.py.
Run from the repo root:  python tools/gen_ui_themes.py"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import themes as T

bad = T.check()
if bad:
    sys.exit("contrast problems:\n" + "\n".join(bad))

RES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "res")
ATTRS = ["gSurface", "gPanel", "gStroke", "gFab", "gDialog", "gTextPrimary", "gTextSecondary", "gHint", "gIcon",
         "gAccent", "gOnAccent", "gAccentContainer", "gOnAccentContainer", "gCrosshair", "gCrosshairHalo"]
CAP = {"forest": "Forest", "ocean": "Ocean", "sand": "Sand", "dusk": "Dusk", "graphite": "Graphite"}


def w(path, text):
    path = os.path.join(RES, path)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)


def a(alpha, h):
    return "#%02X%s" % (alpha, h.lstrip("#").upper())


def values(p, dark):
    stroke = a(0x24, "#FFFFFF") if dark else a(0x1A, p["text"])
    return {
        "gSurface": p["surface"].upper(), "gPanel": a(0xEB, p["panel"]), "gStroke": stroke,
        "gFab": a(0xF5, p["panel"]), "gDialog": p["dialog"].upper(), "gTextPrimary": p["text"].upper(),
        "gTextSecondary": p["text2"].upper(), "gHint": p["hint"].upper(), "gIcon": p["icon"].upper(),
        "gAccent": p["accent"].upper(), "gOnAccent": p["on_accent"].upper(),
        "gAccentContainer": p["container"].upper(), "gOnAccentContainer": p["on_container"].upper(),
        "gCrosshair": p["crosshair"].upper(), "gCrosshairHalo": a(0xCC, p["surface"]),
    }


def style(name, mode_dark):
    p = T.THEMES[name]["dark" if mode_dark else "light"]
    v = values(p, mode_dark)
    items = "\n".join(f'        <item name="{k}">{val}</item>' for k, val in v.items())
    return f'''    <style name="Theme.OkMap.{CAP[name]}" parent="Base.Theme.OkMap">
{items}
        <item name="colorPrimary">{v["gAccent"]}</item>
        <item name="colorOnPrimary">{v["gOnAccent"]}</item>
        <item name="colorPrimaryContainer">{v["gAccentContainer"]}</item>
        <item name="colorOnPrimaryContainer">{v["gOnAccentContainer"]}</item>
        <item name="colorSecondaryContainer">{v["gAccentContainer"]}</item>
        <item name="colorOnSecondaryContainer">{v["gOnAccentContainer"]}</item>
        <item name="colorSurface">{v["gSurface"]}</item>
        <item name="colorOnSurface">{v["gTextPrimary"]}</item>
        <item name="colorOnSurfaceVariant">{v["gTextSecondary"]}</item>
        <item name="colorSurfaceContainer">{v["gDialog"]}</item>
        <item name="colorSurfaceContainerHigh">{v["gDialog"]}</item>
        <item name="colorOutline">{v["gHint"]}</item>
        <item name="android:statusBarColor">{v["gSurface"]}</item>
        <item name="android:navigationBarColor">{v["gSurface"]}</item>
        <item name="android:windowBackground">{v["gSurface"]}</item>
        <item name="android:windowLightStatusBar">@bool/light_bars</item>
        <item name="android:windowLightNavigationBar" tools:targetApi="27">@bool/light_bars</item>
    </style>
'''


w("values/attrs.xml", "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n" +
  "\n".join(f'    <attr name="{x}" format="color" />' for x in ATTRS) + "\n</resources>\n")

w("values/dimens.xml", '''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- 4 dp base grid; 8 dp rhythm between blocks, 16 dp screen margin -->
    <dimen name="space_1">4dp</dimen>
    <dimen name="space_2">8dp</dimen>
    <dimen name="space_3">12dp</dimen>
    <dimen name="space_4">16dp</dimen>
    <dimen name="space_6">24dp</dimen>
    <dimen name="screen_margin">16dp</dimen>
    <dimen name="control_sm">48dp</dimen>
    <dimen name="control_lg">56dp</dimen>
    <dimen name="radius_chip">12dp</dimen>
    <dimen name="radius_card">20dp</dimen>
    <dimen name="radius_pill">28dp</dimen>
    <dimen name="icon">24dp</dimen>
</resources>
''')

base = '''    <!-- type scale (sp): 12 caption / 14 label / 16 body / 20 title; line heights on the 4 dp grid -->
    <style name="TextAppearance.G.Title" parent="TextAppearance.Material3.TitleLarge">
        <item name="android:fontFamily">sans-serif-medium</item>
        <item name="android:textSize">20sp</item>
        <item name="lineHeight">28sp</item>
        <item name="android:letterSpacing">0</item>
    </style>
    <style name="TextAppearance.G.Body" parent="TextAppearance.Material3.BodyLarge">
        <item name="android:fontFamily">sans-serif</item>
        <item name="android:textSize">16sp</item>
        <item name="lineHeight">24sp</item>
        <item name="android:letterSpacing">0.01</item>
    </style>
    <style name="TextAppearance.G.BodySmall" parent="TextAppearance.Material3.BodyMedium">
        <item name="android:fontFamily">sans-serif</item>
        <item name="android:textSize">14sp</item>
        <item name="lineHeight">20sp</item>
        <item name="android:letterSpacing">0.015</item>
    </style>
    <style name="TextAppearance.G.Label" parent="TextAppearance.Material3.LabelLarge">
        <item name="android:fontFamily">sans-serif-medium</item>
        <item name="android:textSize">14sp</item>
        <item name="lineHeight">20sp</item>
        <item name="android:letterSpacing">0.02</item>
    </style>
    <style name="TextAppearance.G.Caption" parent="TextAppearance.Material3.LabelMedium">
        <item name="android:fontFamily">sans-serif-medium</item>
        <item name="android:textSize">12sp</item>
        <item name="lineHeight">16sp</item>
        <item name="android:letterSpacing">0.02</item>
    </style>

    <style name="ShapeAppearance.G.Dialog" parent="ShapeAppearance.Material3.LargeComponent">
        <item name="cornerFamily">rounded</item>
        <item name="cornerSize">28dp</item>
    </style>
    <style name="ThemeOverlay.G.Dialog" parent="ThemeOverlay.Material3.MaterialAlertDialog">
        <item name="shapeAppearanceOverlay">@style/ShapeAppearance.G.Dialog</item>
        <item name="materialAlertDialogTitleTextStyle">@style/MaterialAlertDialog.G.Title</item>
        <item name="materialAlertDialogBodyTextStyle">@style/MaterialAlertDialog.G.Body</item>
        <item name="android:textAppearanceListItem">@style/TextAppearance.G.Body</item>
        <item name="android:textAppearanceListItemSmall">@style/TextAppearance.G.Body</item>
    </style>
    <style name="MaterialAlertDialog.G.Title" parent="MaterialAlertDialog.Material3.Title.Text">
        <item name="android:textAppearance">@style/TextAppearance.G.Title</item>
    </style>
    <style name="MaterialAlertDialog.G.Body" parent="MaterialAlertDialog.Material3.Body.Text">
        <item name="android:textAppearance">@style/TextAppearance.G.BodySmall</item>
    </style>

    <style name="Base.Theme.OkMap" parent="Theme.Material3.DayNight.NoActionBar">
        <item name="textAppearanceHeadlineSmall">@style/TextAppearance.G.Title</item>
        <item name="textAppearanceBodyLarge">@style/TextAppearance.G.Body</item>
        <item name="textAppearanceBodyMedium">@style/TextAppearance.G.BodySmall</item>
        <item name="textAppearanceLabelLarge">@style/TextAppearance.G.Label</item>
        <item name="textAppearanceLabelMedium">@style/TextAppearance.G.Caption</item>
        <item name="materialAlertDialogTheme">@style/ThemeOverlay.G.Dialog</item>
        <item name="android:fontFamily">sans-serif</item>
    </style>
'''
w("values/themes.xml", '<?xml version="1.0" encoding="utf-8"?>\n<resources xmlns:tools="http://schemas.android.com/tools">\n' + base + "\n" +
  "".join(style(n, False) for n in T.ORDER) +
  '    <!-- default (manifest) theme -->\n    <style name="Theme.OkMap" parent="Theme.OkMap.Forest" />\n</resources>\n')
w("values-night/themes.xml", '<?xml version="1.0" encoding="utf-8"?>\n<resources xmlns:tools="http://schemas.android.com/tools">\n' +
  "".join(style(n, True) for n in T.ORDER) + "</resources>\n")

for f in ("values/colors.xml", "values-night/colors.xml", "values-v27/themes.xml", "values-v27/nav.xml"):
    p = os.path.join(RES, f)
    if os.path.exists(p):
        os.remove(p)
print("ui themes written")
