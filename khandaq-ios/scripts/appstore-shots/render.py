#!/usr/bin/env python3
"""Composes the App Store screenshots: brand background, caption and a simulator capture in a phone frame.

Usage:
  python3 render.py --raw <dir> --out <dir> [--locales en-US,ru,ar-SA] [--chrome <path>]

Raw captures are 1320x2868 PNGs from an iPhone 17 Pro Max simulator (`xcrun simctl io <udid> screenshot`),
named after the "file" entries of captions.json. <raw>/<locale>/<file> is used when it exists, else
<raw>/en-US/<file> (the same capture with a localized caption). The frames are written to
<out>/<locale>/NN-<name>.png at 1320x2868 (APP_IPHONE_67), the layout `prepare-appstore.py --shots <out>` uploads.
"""
import argparse
import base64
import html
import json
import re
import struct
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
FONTS_CSS = HERE.parents[2] / "web" / "assets" / "fonts" / "fonts.css"
SIZE = (1320, 2868)
DEFAULT_CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
DEFAULT_LOCALES = ("en-US", "ru", "ar-SA")
RTL_LOCALES = {"ar-SA"}
# Sora has neither Cyrillic nor Arabic glyphs: those captions use Inter or the system Arabic face.
HEADLINE_FONTS = {"ru": "'Inter', sans-serif", "ar-SA": "'SF Arabic', 'Geeza Pro', sans-serif"}
BODY_FONTS = {"ar-SA": "'SF Arabic', 'Geeza Pro', sans-serif"}
DEFAULT_HEADLINE_FONT = "'Sora', 'Inter', sans-serif"
DEFAULT_BODY_FONT = "'Inter', sans-serif"


def png_size(path):
    with open(path, "rb") as f:
        head = f.read(24)
    if head[:8] != b"\x89PNG\r\n\x1a\n":
        raise SystemExit(f"{path}: not a PNG")
    return struct.unpack(">II", head[16:24])


def inline_fonts(css_path):
    """The site's fonts.css with each url() turned into a data URI: Chrome refuses file:// fonts across files."""
    css = css_path.read_text(encoding="utf-8")

    def to_data_uri(match):
        font = css_path.parent / match.group(1).rsplit("/", 1)[-1]
        return "url(data:font/woff2;base64," + base64.b64encode(font.read_bytes()).decode("ascii") + ")"

    return re.sub(r"url\(([^)]+\.woff2)\)", to_data_uri, css)


def raw_capture(raw_dir, locale, name):
    for candidate in (raw_dir / locale / name, raw_dir / "en-US" / name):
        if candidate.is_file():
            if png_size(candidate) != SIZE:
                raise SystemExit(f"{candidate}: expected {SIZE[0]}x{SIZE[1]}, got {png_size(candidate)}")
            return candidate
    raise SystemExit(f"no capture {name} for {locale} in {raw_dir}")


def render_page(template, fonts, locale, caption, capture):
    values = {
        "lang": locale.split("-")[0],
        "dir": "rtl" if locale in RTL_LOCALES else "ltr",
        "fonts": fonts,
        "headline_font": HEADLINE_FONTS.get(locale, DEFAULT_HEADLINE_FONT),
        "body_font": BODY_FONTS.get(locale, DEFAULT_BODY_FONT),
        "title": html.escape(caption["title"]),
        "subtitle": html.escape(caption["subtitle"]),
        "shot": html.escape(capture.as_uri()),
    }
    return re.sub(r"\{\{(\w+)\}\}", lambda m: values[m.group(1)], template)


def screenshot(chrome, page, out_png, profile_dir):
    subprocess.run(
        [chrome, "--headless=new", "--disable-gpu", "--hide-scrollbars", "--force-device-scale-factor=1",
         f"--window-size={SIZE[0]},{SIZE[1]}", "--virtual-time-budget=3000",
         f"--user-data-dir={profile_dir}", f"--screenshot={out_png}", page.as_uri()],
        check=True, capture_output=True,
    )
    if png_size(out_png) != SIZE:
        raise SystemExit(f"{out_png}: rendered {png_size(out_png)}, expected {SIZE}")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--raw", required=True, type=Path, help="folder with <locale>/<file> captures")
    parser.add_argument("--out", required=True, type=Path, help="folder for <locale>/NN-<name>.png frames")
    parser.add_argument("--locales", default=",".join(DEFAULT_LOCALES))
    parser.add_argument("--chrome", default=DEFAULT_CHROME)
    args = parser.parse_args()

    frames = json.loads((HERE / "captions.json").read_text(encoding="utf-8"))["frames"]
    template = (HERE / "frame.html").read_text(encoding="utf-8")
    fonts = inline_fonts(FONTS_CSS)
    locales = [loc.strip() for loc in args.locales.split(",") if loc.strip()]

    with tempfile.TemporaryDirectory(prefix="khandaq-shots-") as tmp:
        tmp_dir = Path(tmp)
        for locale in locales:
            out_dir = args.out / locale
            out_dir.mkdir(parents=True, exist_ok=True)
            for index, frame in enumerate(frames, start=1):
                caption = frame["captions"].get(locale)
                if caption is None:
                    raise SystemExit(f"frame {frame['name']}: no caption for {locale}")
                capture = raw_capture(args.raw.resolve(), locale, frame["file"])
                page = tmp_dir / f"{locale}-{index:02d}.html"
                page.write_text(render_page(template, fonts, locale, caption, capture), encoding="utf-8")
                out_png = out_dir / f"{index:02d}-{frame['name']}.png"
                screenshot(args.chrome, page, out_png.resolve(), tmp_dir / "chrome-profile")
                print(f"{locale}: {out_png.name} <- {capture}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
