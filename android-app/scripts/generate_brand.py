#!/usr/bin/env python3
"""Generate native/vector brand variants from one SVG master; no app runtime dependency."""

from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
BRAND = ROOT / "design/brand"
INK = "#111111"
MINT = "#5CE1C6"
SVG = "http://www.w3.org/2000/svg"
master = ET.parse(BRAND / "librevision-mark.svg").getroot()
paths = []
shapes = []
for element in master:
    tag = element.tag.rsplit("}", 1)[-1]
    if tag == "path":
        paths.append(element.attrib["d"])
        shapes.append(
            f'<path fill="currentColor" stroke="none" d="{element.attrib["d"]}"/>'
        )
    elif tag == "circle":
        x, y, r = (float(element.attrib[k]) for k in ["cx", "cy", "r"])
        paths.append(
            f"M{x - r:g},{y:g}a{r:g},{r:g} 0,1 0 {2 * r:g},0a{r:g},{r:g} 0,1 0 {-2 * r:g},0"
        )
        shapes.append(
            f'<circle fill="currentColor" stroke="none" cx="{x:g}" cy="{y:g}" r="{r:g}"/>'
        )
inner = "\n".join(shapes)


def write(path, text):
    path = ROOT / path
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text + "\n")


def vector(size, content):
    return f'<?xml version="1.0" encoding="utf-8"?>\n<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="{size}dp" android:height="{size}dp" android:viewportWidth="{size}" android:viewportHeight="{size}">\n{content}\n</vector>'


def native_paths(color):
    return "\n".join(
        f'<path android:fillColor="{color}" android:pathData="{path}"/>'
        for path in paths
    )


write(Path("app/src/main/res/drawable/lv_brand.xml"), vector(32, native_paths(INK)))
foreground = (
    '<group android:scaleX="1.8" android:scaleY="1.8" android:translateX="23.4" android:translateY="27">\n'
    + native_paths(INK)
    + "\n</group>"
)
write(
    Path("app/src/main/res/drawable/librevision_launcher_foreground.xml"),
    vector(108, foreground),
)
background = f'<path android:fillColor="{MINT}" android:pathData="M24,0H84Q108,0 108,24V84Q108,108 84,108H24Q0,108 0,84V24Q0,0 24,0Z"/>'
write(
    Path("app/src/main/res/drawable/ic_librevision.xml"),
    vector(108, background + "\n" + foreground),
)
for version in ["v26", "v33"]:
    for suffix in ["", "_round"]:
        mono = (
            '\n<monochrome android:drawable="@drawable/librevision_launcher_foreground"/>'
            if version == "v33"
            else ""
        )
        write(
            Path(f"app/src/main/res/mipmap-anydpi-{version}/ic_launcher{suffix}.xml"),
            f'<?xml version="1.0" encoding="utf-8"?>\n<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n<background android:drawable="@color/librevision_launcher_background"/>\n<foreground android:drawable="@drawable/librevision_launcher_foreground"/>{mono}\n</adaptive-icon>',
        )
write(
    Path("app/src/main/res/values/brand.xml"),
    f'<?xml version="1.0" encoding="utf-8"?>\n<resources><color name="librevision_launcher_background">{MINT}</color></resources>',
)
write(
    Path("app/src/main/res/values/themes.xml"),
    """<?xml version="1.0" encoding="utf-8"?>
<resources>
<style name="Theme.LibreVision.Base" parent="@android:style/Theme.Material.NoActionBar">
<item name="android:windowBackground">#000000</item>
</style>
<style name="Theme.LibreVision" parent="Theme.LibreVision.Base"/>
</resources>""",
)
write(
    Path("app/src/main/res/values-v31/themes.xml"),
    """<?xml version="1.0" encoding="utf-8"?>
<resources>
<style name="Theme.LibreVision" parent="Theme.LibreVision.Base">
<item name="android:windowSplashScreenBackground">#000000</item>
<item name="android:windowSplashScreenAnimatedIcon">@drawable/ic_librevision</item>
</style>
</resources>""",
)
write(
    Path("design/brand/librevision-icon.svg"),
    f'<svg xmlns="{SVG}" width="512" height="512" viewBox="0 0 108 108" role="img" aria-label="LibreVision app icon"><rect width="108" height="108" rx="24" fill="{MINT}"/><g transform="translate(23.4 27) scale(1.8)" color="{INK}">{inner}</g></svg>',
)
for name, color in [
    ("librevision-wordmark", INK),
    ("librevision-wordmark-light", "#F4F7FC"),
]:
    write(
        Path(f"design/brand/{name}.svg"),
        f'<svg xmlns="{SVG}" width="232" height="48" viewBox="0 0 232 48" role="img" aria-label="LibreVision" color="{color}"><g transform="translate(0 4) scale(1.25)">{inner}</g><text x="51" y="34" font-family="DejaVu Sans,sans-serif" font-size="28" font-weight="600" letter-spacing="-1.2" fill="currentColor">LibreVision</text></svg>',
    )
html_file = ROOT / "design/LibreVision_Industrial_Prototype.html"
html = html_file.read_text()
html = re.sub(
    r'<symbol id="i-brand" viewBox="0 0 32 32">.*?</symbol>',
    f'<symbol id="i-brand" viewBox="0 0 32 32">{inner}</symbol>',
    html,
    count=1,
    flags=re.S,
)
if 'rel="icon"' not in html:
    html = html.replace(
        "<title>LibreVision — Interface Study 01</title>",
        '<title>LibreVision — Interface Study 01</title>\n<link rel="icon" href="brand/librevision-icon.svg" type="image/svg+xml"/>',
    )
html_file.write_text(html)
print(
    "Generated SVG, Android launcher, themed icon, splash and design-reference assets."
)
