#!/usr/bin/env python3
"""Composes the store screenshots: caption and a device capture in a phone, on the app's own ground.

Usage:
  python3 render.py --raw <dir> --out <dir> [--profile appstore|play] [--captions <json>]
                    [--locales en-US,ru,ar-SA] [--chrome <path>]

--profile appstore (default): raw captures are 1320x2868 PNGs from an iPhone 17 Pro Max simulator
(`xcrun simctl io <udid> screenshot`); frames are 1320x2868 (APP_IPHONE_67), the layout
`prepare-appstore.py --shots <out>` uploads.
--profile play: raw captures are 1080x2400 PNGs from the Android emulator (`adb exec-out screencap -p`);
frames are 1080x2160, the tallest phone screenshot Google Play accepts (no side over twice the other).
Run it with `--captions captions-play.json --locales en-US,ru-RU`, the Play listing's languages.

Raw captures are named after the "file" entries of the captions file. <raw>/<locale>/<file> is used
when it exists, else <raw>/en-US/<file> (the same capture with a localized caption). The frames are
written to <out>/<locale>/NN-<name>.png.

Colours and type come from the design system (design/system): the frame ground is the `bg` token of the
theme named by the frame's "theme" in captions.json ("light" when absent, so it matches a light capture),
and the captions are set in IBM Plex Sans, or IBM Plex Sans Arabic for Arabic.
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
DESIGN_SYSTEM = HERE.parents[2] / "design" / "system"
TOKENS_CSS = DESIGN_SYSTEM / "dist" / "tokens.css"
FONT_DIR = DESIGN_SYSTEM / "fonts"
# (family, weight, file) for every face a caption can use; the files are the OFL copies in design/system/fonts.
FONT_FACES = (
    ("IBM Plex Sans", 400, "IBMPlexSans-Regular.ttf"),
    ("IBM Plex Sans", 500, "IBMPlexSans-Medium.ttf"),
    ("IBM Plex Sans", 600, "IBMPlexSans-SemiBold.ttf"),
    ("IBM Plex Sans Arabic", 400, "IBMPlexSansArabic-Regular.ttf"),
    ("IBM Plex Sans Arabic", 500, "IBMPlexSansArabic-Medium.ttf"),
    ("IBM Plex Sans Arabic", 600, "IBMPlexSansArabic-SemiBold.ttf"),
)
# Per store: the capture it takes, the frame it outputs, and the frame's geometry in CSS pixels.
# The screen inside the phone keeps the capture's aspect ratio: (phone - 2 * bezel) matches the capture.
PROFILES = {
    "appstore": {
        "capture": (1320, 2868),
        "canvas": (1320, 2868),
        "css": {
            "copy-h": 680, "copy-inline": 112, "copy-gap": 32, "h1-size": 104, "p-size": 46, "p-max": 1040,
            "rtl-h1-size": 96, "rtl-p-size": 44,
            "phone-top": 712, "phone-left": 160, "phone-w": 1000, "phone-h": 2126,
            "phone-radius": 150, "bezel": 20, "screen-radius": 130,
        },
    },
    "play": {
        "capture": (1080, 2400),
        "canvas": (1080, 2160),
        "css": {
            "copy-h": 540, "copy-inline": 88, "copy-gap": 22, "h1-size": 72, "p-size": 34, "p-max": 880,
            "rtl-h1-size": 66, "rtl-p-size": 32,
            "phone-top": 566, "phone-left": 184, "phone-w": 712, "phone-h": 1543,
            "phone-radius": 76, "bezel": 16, "screen-radius": 60,
        },
    },
}
DEFAULT_CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
DEFAULT_LOCALES = ("en-US", "ru", "ar-SA")
RTL_LOCALES = {"ar-SA"}
THEMES = ("light", "dark")
FONT_STACKS = {"ar-SA": "'IBM Plex Sans Arabic', 'IBM Plex Sans', sans-serif"}
DEFAULT_FONT_STACK = "'IBM Plex Sans', sans-serif"
# Russian typesetting: a one- or two-letter preposition, conjunction or particle is tied to the word after it
# with a no-break space, and a dash to the word before it, so a balanced caption never ends a line on a short
# word or starts one with a dash.
RU_SHORT_WORD = re.compile(r"(?<![\w-])(в|во|и|а|с|со|к|ко|о|об|у|по|до|на|не|ни|за|из|от|но|же|ли)\s+(?=\w)",
                           re.IGNORECASE)
NO_BREAK_SPACE = " "


def tie_short_words(text, locale):
    if not locale.startswith("ru"):
        return text
    text = text.replace(" \u2014 ", NO_BREAK_SPACE + "\u2014 ")
    return RU_SHORT_WORD.sub(lambda m: m.group(1) + NO_BREAK_SPACE, text)


def png_size(path):
    with open(path, "rb") as f:
        head = f.read(24)
    if head[:8] != b"\x89PNG\r\n\x1a\n":
        raise SystemExit(f"{path}: not a PNG")
    return struct.unpack(">II", head[16:24])


def inline_fonts():
    """@font-face rules with the fonts as data URIs: Chrome refuses file:// fonts across files."""
    rules = []
    for family, weight, name in FONT_FACES:
        data = base64.b64encode((FONT_DIR / name).read_bytes()).decode("ascii")
        rules.append(
            f"@font-face {{ font-family: '{family}'; font-weight: {weight}; font-style: normal; "
            f"src: url(data:font/ttf;base64,{data}) format('truetype'); }}"
        )
    return "\n".join(rules)


def raw_capture(raw_dir, locale, name, size):
    for candidate in (raw_dir / locale / name, raw_dir / "en-US" / name):
        if candidate.is_file():
            if png_size(candidate) != size:
                raise SystemExit(f"{candidate}: expected {size[0]}x{size[1]}, got {png_size(candidate)}")
            return candidate
    raise SystemExit(f"no capture {name} for {locale} in {raw_dir}")


def geometry(profile):
    width, height = profile["canvas"]
    props = [f"--canvas-w: {width}px;", f"--canvas-h: {height}px;"]
    props += [f"--{key}: {value}px;" for key, value in profile["css"].items()]
    return ":root {\n  " + "\n  ".join(props) + "\n}"


def render_page(template, fonts, tokens, frame_geometry, locale, theme, caption, capture):
    values = {
        "lang": locale.split("-")[0],
        "dir": "rtl" if locale in RTL_LOCALES else "ltr",
        "theme": theme,
        "fonts": fonts,
        "tokens": tokens,
        "geometry": frame_geometry,
        "font_stack": FONT_STACKS.get(locale, DEFAULT_FONT_STACK),
        "title": html.escape(tie_short_words(caption["title"], locale)),
        "subtitle": html.escape(tie_short_words(caption["subtitle"], locale)),
        "shot": html.escape(capture.as_uri()),
    }
    return re.sub(r"\{\{(\w+)\}\}", lambda m: values[m.group(1)], template)


def screenshot(chrome, page, out_png, profile_dir, size):
    subprocess.run(
        [chrome, "--headless=new", "--disable-gpu", "--hide-scrollbars", "--force-device-scale-factor=1",
         f"--window-size={size[0]},{size[1]}", "--virtual-time-budget=3000",
         f"--user-data-dir={profile_dir}", f"--screenshot={out_png}", page.as_uri()],
        check=True, capture_output=True,
    )
    if png_size(out_png) != size:
        raise SystemExit(f"{out_png}: rendered {png_size(out_png)}, expected {size}")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--raw", required=True, type=Path, help="folder with <locale>/<file> captures")
    parser.add_argument("--out", required=True, type=Path, help="folder for <locale>/NN-<name>.png frames")
    parser.add_argument("--locales", default=",".join(DEFAULT_LOCALES))
    parser.add_argument("--chrome", default=DEFAULT_CHROME)
    parser.add_argument("--profile", choices=sorted(PROFILES), default="appstore")
    parser.add_argument("--captions", type=Path, default=HERE / "captions.json",
                        help="frames and their captions (default: captions.json next to this script)")
    args = parser.parse_args()

    frames = json.loads(args.captions.read_text(encoding="utf-8"))["frames"]
    template = (HERE / "frame.html").read_text(encoding="utf-8")
    fonts = inline_fonts()
    tokens = TOKENS_CSS.read_text(encoding="utf-8")
    profile = PROFILES[args.profile]
    frame_geometry = geometry(profile)
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
                theme = frame.get("theme", "light")
                if theme not in THEMES:
                    raise SystemExit(f"frame {frame['name']}: theme must be one of {', '.join(THEMES)}, not {theme!r}")
                capture = raw_capture(args.raw.resolve(), locale, frame["file"], profile["capture"])
                page = tmp_dir / f"{locale}-{index:02d}.html"
                page.write_text(
                    render_page(template, fonts, tokens, frame_geometry, locale, theme, caption, capture),
                    encoding="utf-8",
                )
                out_png = out_dir / f"{index:02d}-{frame['name']}.png"
                screenshot(args.chrome, page, out_png.resolve(), tmp_dir / "chrome-profile", profile["canvas"])
                print(f"{locale}: {out_png.name} <- {capture}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
