#!/usr/bin/env python3
"""Vendor the site's webfonts so no page reaches out to a third party.

KHANDAQ (re-audit 2026-08-22, W-01). The site linked fonts.googleapis.com and pulled the actual
font files from fonts.gstatic.com. Two consequences, and the second is the one that matters:

  * every visitor's IP and User-Agent went to a third party purely to render text, on a site whose
    whole pitch is that it does not hand your traffic to anyone; and
  * the Content-Security-Policy had to allow a foreign origin for both style-src and font-src,
    which is precisely the hole a strict CSP exists to close.

So the fonts are committed and served from the site's own origin. `style-src` and `font-src` then
reduce to 'self' with no exceptions.

KHANDAQ (redesign E23, 2026-11-11). The site now sets IBM Plex Sans and IBM Plex Mono, the faces of
the design system, so the files no longer come from Google at all: design/system/fonts holds the
TTFs, and this script converts the five weights the site uses into WOFF2 next to the pages. Nothing
is fetched. The OFL reserves the name "Plex", and its FAQ allows a web format under that name only
when the font data is unchanged apart from the compression, so the fonts are converted whole,
without subsetting, and the licence travels with them.

Idempotent: a file is rewritten only when its bytes change, and the run prints what moved.

    scripts/vendor-webfonts.py            # convert design/system/fonts into web/assets/fonts/
                                          # (needs fontTools and brotli: pip install fonttools brotli)
    scripts/vendor-webfonts.py --check    # verify the set against style.css: stdlib only, no network
"""
from __future__ import annotations

import argparse
import io
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
WEB = ROOT / "web"
FONT_DIR = WEB / "assets" / "fonts"
SOURCE_DIR = ROOT / "design" / "system" / "fonts"
STYLESHEET = WEB / "style.css"
LICENCE_SOURCE = SOURCE_DIR / "OFL.txt"
LICENCE = "IBM-Plex-OFL.txt"

# Source TTF in design/system/fonts -> the WOFF2 file style.css asks for. These are the weights the
# site sets: Sans 400/500/600 for text, Mono 400/500 for IDs and keys.
FONTS = {
    "IBMPlexSans-Regular.ttf": "ibm-plex-sans-400.woff2",
    "IBMPlexSans-Medium.ttf": "ibm-plex-sans-500.woff2",
    "IBMPlexSans-SemiBold.ttf": "ibm-plex-sans-600.woff2",
    "IBMPlexMono-Regular.ttf": "ibm-plex-mono-400.woff2",
    "IBMPlexMono-Medium.ttf": "ibm-plex-mono-500.woff2",
}

# Font services a page could be pointed back at. Matched as URLs (after "//"), so prose that names
# one of them is not a failure; a link, an @import or a script that loads from one is.
FONT_HOSTS = re.compile(
    r"//(?:fonts\.googleapis\.com|fonts\.gstatic\.com|use\.typekit\.net|p\.typekit\.net|"
    r"fonts\.bunny\.net|fast\.fonts\.net|cloud\.typography\.com)"
)


def rel(path: Path) -> str:
    return path.relative_to(ROOT).as_posix()


def check() -> int:
    failures: list[str] = []
    vendored = set(FONTS.values())

    # 1. Every face the stylesheet declares loads a vendored WOFF2 from web/assets/fonts/.
    css = STYLESHEET.read_text(encoding="utf-8")
    urls: list[str] = []
    for face in re.findall(r"@font-face\s*\{[^}]*\}", css):
        urls += re.findall(r"url\(\s*[\"']?([^\"')]+?)[\"']?\s*\)", face)
    if not urls:
        failures.append("style.css declares no @font-face with a url(): the stylesheet changed shape, "
                        "and this check would pass vacuously")
    used: set[str] = set()
    for url in urls:
        if "//" in url or url.startswith("data:"):
            failures.append(f"style.css loads a font from {url}: fonts must be files in web/assets/fonts/")
            continue
        # style.css is served from the site root, so a relative url() resolves against the root.
        target = WEB / url.split("?", 1)[0].split("#", 1)[0].lstrip("/")
        if target.parent != FONT_DIR:
            failures.append(f"style.css loads {url}, which is not in web/assets/fonts/")
            continue
        if target.name not in vendored:
            failures.append(f"style.css loads {url}, which this script does not vendor: add it to FONTS")
        if not target.is_file():
            failures.append(f"style.css loads {url}, which does not exist")
            continue
        if target.read_bytes()[:4] != b"wOF2":
            failures.append(f"{rel(target)} is not a WOFF2 file")
        used.add(target.name)

    # 2. The folder holds the vendored set, its licence and nothing else.
    for name in sorted(vendored - used):
        failures.append(f"web/assets/fonts/{name} is vendored, but no @font-face in style.css uses it")
    for path in sorted(FONT_DIR.iterdir()) if FONT_DIR.is_dir() else []:
        if path.name != LICENCE and path.name not in vendored:
            failures.append(f"{rel(path)} is not part of the vendored set and nothing loads it: remove it")
    if not (FONT_DIR / LICENCE).is_file():
        failures.append(f"web/assets/fonts/{LICENCE} is missing: the OFL must travel with the fonts")

    # 3. A font preload must name a file a face uses, or the browser downloads it for nothing.
    pages = sorted(WEB.rglob("*.html"))
    for page in pages:
        for tag in re.findall(r"<link\b[^>]*>", page.read_text(encoding="utf-8")):
            if not (re.search(r"\brel=[\"']preload[\"']", tag) and re.search(r"\bas=[\"']font[\"']", tag)):
                continue
            href = re.search(r"\bhref=[\"']([^\"']+)[\"']", tag)
            path = href.group(1).split("?", 1)[0] if href else ""
            if not path.startswith("/assets/fonts/") or path.rsplit("/", 1)[-1] not in used:
                failures.append(f"{rel(page)}: preloads {path or 'a font without href'}, which no "
                                f"@font-face in style.css uses")

    # 4. Nothing the browser runs or renders points back at a font service.
    for path in pages + sorted(WEB.rglob("*.css")) + sorted(WEB.rglob("*.js")):
        for m in FONT_HOSTS.finditer(path.read_text(encoding="utf-8", errors="replace")):
            failures.append(f"{rel(path)} still loads from {m.group(0)[2:]}")

    if failures:
        print("FAIL: the site's webfonts are not self-contained:", file=sys.stderr)
        for line in failures:
            print(f"  - {line}", file=sys.stderr)
        return 1
    print(f"ok: {len(used)} self-hosted WOFF2 files used by style.css, the OFL beside them, "
          f"no other font files and no font service")
    return 0


def vendor() -> int:
    try:
        from fontTools.ttLib import TTFont
        import brotli  # noqa: F401  fontTools needs it to write WOFF2
    except ImportError as err:
        print(f"{err.name} is needed to write WOFF2: pip install fonttools brotli", file=sys.stderr)
        return 1

    FONT_DIR.mkdir(parents=True, exist_ok=True)
    written = unchanged = 0
    outputs = [(SOURCE_DIR / source, FONT_DIR / name, None) for source, name in FONTS.items()]
    outputs.append((LICENCE_SOURCE, FONT_DIR / LICENCE, LICENCE_SOURCE.read_bytes()))
    for source, dest, data in outputs:
        if data is None:
            # As read: fontTools would otherwise stamp head.modified with the time of this run and
            # recompute bounding boxes, so every run would differ and the data would not be the source's.
            font = TTFont(source, recalcBBoxes=False, recalcTimestamp=False)
            font.flavor = "woff2"
            buffer = io.BytesIO()
            font.save(buffer)
            data = buffer.getvalue()
        if dest.is_file() and dest.read_bytes() == data:
            unchanged += 1
            continue
        dest.write_bytes(data)
        written += 1
        print(f"written: {rel(dest)} ({len(data) // 1024} KiB, from {rel(source)})")
    print(f"vendored {len(outputs)} files: {written} written, {unchanged} unchanged")
    return check()


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    ap.add_argument("--check", action="store_true",
                    help="verify the vendored set against style.css without writing (no network, stdlib only)")
    args = ap.parse_args()
    return check() if args.check else vendor()


if __name__ == "__main__":
    sys.exit(main())
