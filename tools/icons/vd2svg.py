#!/usr/bin/env python3
"""Android VectorDrawable -> SVG, so iOS can ship the exact same icon geometry as Android.

Only the subset Khandaq's drawables use: <path android:pathData/fillColor/fillType> inside a
<vector viewportWidth/viewportHeight>. Groups with rotation/scale are reported, not silently dropped.
"""
import sys
import os
import xml.etree.ElementTree as ET

A = "{http://schemas.android.com/apk/res/android}"


def convert(src):
    root = ET.parse(src).getroot()
    vw = root.get(A + "viewportWidth", "24")
    vh = root.get(A + "viewportHeight", "24")

    if root.findall(".//{*}group"):
        print("WARN: %s has <group> (rotation/scale) — check the result" % os.path.basename(src),
              file=sys.stderr)

    paths = []
    for p in root.iter():
        if not p.tag.endswith("path"):
            continue
        d = p.get(A + "pathData")
        if not d:
            continue
        rule = p.get(A + "fillType", "nonZero")
        rule = "evenodd" if rule.lower() == "evenodd" else "nonzero"

        # Khandaq draws some icons as outlines (fillColor transparent + strokeColor/strokeWidth).
        # Dropping the stroke turned a hollow gear into a solid one — carry both over.
        fill_color = (p.get(A + "fillColor") or "").lower()
        fill = "none" if (not fill_color or "transparent" in fill_color) else "currentColor"

        attrs = ['d="%s"' % d, 'fill="%s"' % fill, 'fill-rule="%s"' % rule]
        if p.get(A + "strokeColor"):
            attrs.append('stroke="currentColor"')
            attrs.append('stroke-width="%s"' % (p.get(A + "strokeWidth") or "1"))
            attrs.append('stroke-linecap="%s"' % (p.get(A + "strokeLineCap") or "round"))
            attrs.append('stroke-linejoin="%s"' % (p.get(A + "strokeLineJoin") or "round"))
        paths.append("  <path %s/>" % " ".join(attrs))

    return ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 %s %s" width="%s" height="%s">\n'
            % (vw, vh, vw, vh)) + "\n".join(paths) + "\n</svg>\n"


if __name__ == "__main__":
    for src in sys.argv[1:-1]:
        out = os.path.join(sys.argv[-1],
                           os.path.splitext(os.path.basename(src))[0] + ".svg")
        with open(out, "w") as fh:
            fh.write(convert(src))
        print(out)
