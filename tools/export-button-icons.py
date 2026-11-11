#!/usr/bin/env python3
"""Export the Android vector icons referenced by the Buttons and Gestures guides as SVG.

Run from any directory: python3 tools/export-button-icons.py
No third-party packages are needed. Geometry comes from the app resources; a
white tile and dark ink keep the documentation readable in light and dark themes.
"""

from pathlib import Path
import re
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app/src/main/res"
OUT = ROOT / "docs/img/buttons"
ANDROID = "{http://schemas.android.com/apk/res/android}"
SVG = "http://www.w3.org/2000/svg"
ET.register_namespace("", SVG)


def attr(element, name, default=None):
    return element.get(ANDROID + name, default)


def export(name, strings):
    vector = ET.parse(RES / "drawable" / f"{name}.xml").getroot()
    if vector.tag != "vector":
        raise ValueError(f"{name}: expected a vector drawable")
    width = float(attr(vector, "viewportWidth"))
    height = float(attr(vector, "viewportHeight"))
    svg = ET.Element(f"{{{SVG}}}svg", {
        "width": "32", "height": "32",
        "viewBox": f"-4 -4 {width + 8:g} {height + 8:g}",
        "role": "img", "aria-labelledby": "title",
    })
    ET.SubElement(svg, f"{{{SVG}}}title", {"id": "title"}).text = name
    ET.SubElement(svg, f"{{{SVG}}}rect", {
        "x": "-4", "y": "-4", "width": f"{width + 8:g}",
        "height": f"{height + 8:g}", "rx": "4", "fill": "#ffffff",
    })

    def color(value):
        # Documentation uses one ink colour; preserve transparency/alpha.
        if value in (None, "@android:color/transparent", "#00000000"):
            return "none", None
        if value.startswith("#") and len(value) == 9:
            alpha = int(value[1:3], 16) / 255
            return "#263238", f"{alpha:g}"
        if value.startswith("#") or value in (
            "@android:color/white", "@android:color/black", "@color/white",
        ):
            return "#263238", None
        raise ValueError(f"{name}: unsupported colour {value}")

    def children(source, target):
        for child in source:
            if child.tag == "group":
                px, py = attr(child, "pivotX", "0"), attr(child, "pivotY", "0")
                transform = (
                    f"translate({attr(child, 'translateX', '0')} {attr(child, 'translateY', '0')}) "
                    f"translate({px} {py}) rotate({attr(child, 'rotation', '0')}) "
                    f"scale({attr(child, 'scaleX', '1')} {attr(child, 'scaleY', '1')}) "
                    f"translate({-float(px):g} {-float(py):g})"
                )
                children(child, ET.SubElement(target, f"{{{SVG}}}g", {"transform": transform}))
            elif child.tag == "path":
                if any(key.startswith(ANDROID + "trimPath") for key in child.attrib):
                    raise ValueError(f"{name}: trimmed paths need explicit conversion")
                data = attr(child, "pathData")
                if data.startswith("@string/"):
                    data = strings[data.removeprefix("@string/")]
                path = ET.SubElement(target, f"{{{SVG}}}path", {"d": data})
                for paint in ("fill", "stroke"):
                    ink, alpha = color(attr(child, paint + "Color"))
                    path.set(paint, ink)
                    opacity = float(attr(child, paint + "Alpha", "1")) * float(alpha or "1")
                    if opacity != 1:
                        path.set(paint + "-opacity", f"{opacity:g}")
                for android, svg_attr in (
                    ("strokeWidth", "stroke-width"), ("strokeLineCap", "stroke-linecap"),
                    ("strokeLineJoin", "stroke-linejoin"), ("strokeMiterLimit", "stroke-miterlimit"),
                ):
                    value = attr(child, android)
                    if value is not None:
                        path.set(svg_attr, value)
                path.set("fill-rule", "evenodd" if attr(child, "fillType") == "evenOdd" else "nonzero")
            else:
                raise ValueError(f"{name}: unsupported vector element {child.tag}")

    children(vector, svg)
    ET.indent(svg, space="  ")
    return '<!-- Generated from app/src/main/res/drawable/' + name + '.xml; run tools/export-button-icons.py. -->\n' + ET.tostring(svg, encoding="unicode") + "\n"


def main():
    strings = {}
    for resource in sorted((RES / "values").glob("*.xml")):
        for element in ET.parse(resource).getroot().findall("string"):
            strings[element.get("name")] = "".join(element.itertext())
    guides = sorted((ROOT / "docs").glob("buttons-and-gestures*.md"))
    icons = sorted(set(re.findall(
        r"img/buttons/(ic_[a-z0-9_]+)\.svg", "\n".join(p.read_text() for p in guides),
    )))
    if not icons:
        raise ValueError("No button icons found in docs/buttons-and-gestures*.md")
    # Convert everything before writing so unsupported resources cannot leave a partial export.
    converted = {name: export(name, strings) for name in icons}
    OUT.mkdir(parents=True, exist_ok=True)
    for name, svg in converted.items():
        (OUT / f"{name}.svg").write_text(svg)
    print(f"Exported {len(icons)} icons to {OUT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
