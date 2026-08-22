#!/usr/bin/env python3
"""Material Symbols SVG -> Android VectorDrawable, so both apps ship the same glyphs.

Material Symbols use viewBox="0 -960 960 960" (y grows upward from -960). VectorDrawable has no
negative viewport origin, so the paths are wrapped in a group translated by +960 on Y.
"""
import io
import os
import re
import sys

TEMPLATE = '''<?xml version="1.0" encoding="utf-8"?>
<!-- Generated from Material Symbols Outlined (Apache 2.0) so Android and iOS draw the same glyph. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="960"
    android:viewportHeight="960"
    android:tint="{tint}">
    <group android:translateY="960">
{paths}
    </group>
</vector>
'''


def convert(src, tint="?attr/colorControlNormal"):
    svg = io.open(src, encoding="utf-8").read()
    paths = re.findall(r'<path[^>]*\bd="([^"]+)"', svg)
    if not paths:
        raise SystemExit("no paths in %s" % src)
    body = "\n".join(
        '        <path\n            android:fillColor="@android:color/white"\n'
        '            android:pathData="%s" />' % d for d in paths)
    return TEMPLATE.format(paths=body, tint=tint)


if __name__ == "__main__":
    out_dir = sys.argv[-1]
    for src in sys.argv[1:-1]:
        name = os.path.splitext(os.path.basename(src))[0]
        out = os.path.join(out_dir, name + ".xml")
        io.open(out, "w", encoding="utf-8").write(convert(src))
        print(out)
