#!/usr/bin/env python3
"""Vendor the site's webfonts so no page reaches out to a third party.

KHANDAQ (re-audit 2026-08-22, W-01). The site linked fonts.googleapis.com and pulled the actual
font files from fonts.gstatic.com. Two consequences, and the second is the one that matters:

  * every visitor's IP and User-Agent went to a third party purely to render text, on a site whose
    whole pitch is that it does not hand your traffic to anyone; and
  * the Content-Security-Policy had to allow a foreign origin for both style-src and font-src,
    which is precisely the hole a strict CSP exists to close.

So the fonts are fetched once, committed, and served from the site's own origin. `style-src` and
`font-src` then reduce to 'self' with no exceptions.

Idempotent: re-running downloads nothing new unless upstream changed, and prints what moved.

    scripts/vendor-webfonts.py            # fetch/refresh into web/assets/fonts/
    scripts/vendor-webfonts.py --check    # verify the committed files match fonts.css, no network
"""
from __future__ import annotations

import argparse
import hashlib
import re
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
FONT_DIR = ROOT / "web" / "assets" / "fonts"
CSS_OUT = FONT_DIR / "fonts.css"

# The exact families the pages ask for, together. One request keeps the @font-face ordering stable,
# which keeps the generated CSS diff-free between runs.
CSS_URL = (
    "https://fonts.googleapis.com/css2"
    "?family=Inter:wght@400;500;600;700"
    "&family=JetBrains+Mono:wght@400;500"
    "&family=Sora:wght@600;700"
    "&display=swap"
)

# Google serves a different stylesheet per browser. Without a modern UA it returns .ttf instead of
# .woff2 — three times the bytes, and a different set of files every time the fetcher changes.
UA = (
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
)

# Latin plus Cyrillic: the site is English with Russian content, and shipping every subset Google
# offers (greek, vietnamese, ...) would triple the committed bytes for glyphs no page renders.
KEEP_SUBSETS = {"latin", "latin-ext", "cyrillic", "cyrillic-ext"}


def fetch(url: str) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=60) as resp:  # noqa: S310 - fixed https host
        return resp.read()


def parse_blocks(css: str) -> list[tuple[str, str]]:
    """Return (subset-comment, @font-face block) pairs, in source order."""
    out = []
    for m in re.finditer(r"/\*\s*([a-z-]+)\s*\*/\s*(@font-face\s*\{[^}]*\})", css):
        out.append((m.group(1), m.group(2)))
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true",
                    help="verify committed files against fonts.css without touching the network")
    args = ap.parse_args()

    if args.check:
        if not CSS_OUT.is_file():
            print(f"ОШИБКА: {CSS_OUT.relative_to(ROOT)} отсутствует", file=sys.stderr)
            return 1
        css = CSS_OUT.read_text(encoding="utf-8")
        refs = re.findall(r"url\(([^)]+)\)", css)
        missing = []
        for ref in refs:
            name = ref.strip("'\"").rsplit("/", 1)[-1]
            if not (FONT_DIR / name).is_file():
                missing.append(name)
        if missing:
            print("ОШИБКА: fonts.css ссылается на файлы, которых нет в репозитории:", file=sys.stderr)
            for name in missing:
                print(f"  {name}", file=sys.stderr)
            return 1
        # Nothing may point outside the origin: that is the whole reason this file exists.
        external = [r for r in refs if "//" in r]
        if external:
            print("ОШИБКА: fonts.css всё ещё ссылается наружу:", file=sys.stderr)
            for r in external:
                print(f"  {r}", file=sys.stderr)
            return 1
        print(f"ок: {len(refs)} локальных файлов шрифтов, внешних ссылок нет")
        return 0

    FONT_DIR.mkdir(parents=True, exist_ok=True)
    css = fetch(CSS_URL).decode("utf-8")
    blocks = parse_blocks(css)
    if not blocks:
        print("ОШИБКА: в ответе Google нет ни одного @font-face — формат изменился", file=sys.stderr)
        return 1

    kept, out_blocks, downloaded, unchanged = 0, [], 0, 0
    for subset, block in blocks:
        if subset not in KEEP_SUBSETS:
            continue
        kept += 1
        m = re.search(r"url\((https://fonts\.gstatic\.com/[^)]+)\)", block)
        if not m:
            print(f"ОШИБКА: в блоке {subset} нет ссылки на gstatic", file=sys.stderr)
            return 1
        url = m.group(1)
        family = re.search(r"font-family:\s*'([^']+)'", block).group(1).replace(" ", "-").lower()
        weight = re.search(r"font-weight:\s*(\d+)", block).group(1)
        data = fetch(url)
        # The name is derived, not taken from Google's opaque hashed filename: upstream rotates
        # those on every font revision, which would otherwise churn the repo on every refresh.
        name = f"{family}-{weight}-{subset}.woff2"
        dest = FONT_DIR / name
        if dest.is_file() and hashlib.sha256(dest.read_bytes()).digest() == hashlib.sha256(data).digest():
            unchanged += 1
        else:
            dest.write_bytes(data)
            downloaded += 1
        out_blocks.append(f"/* {subset} */\n" + block.replace(url, f"/assets/fonts/{name}"))

    header = (
        "/* Generated by scripts/vendor-webfonts.py — do not edit by hand.\n"
        "   Self-hosted so the site makes no third-party request to render text, and so the CSP\n"
        "   can keep style-src/font-src at 'self'. Re-run the script to refresh. */\n"
    )
    CSS_OUT.write_text(header + "\n".join(out_blocks) + "\n", encoding="utf-8")

    total = sum(f.stat().st_size for f in FONT_DIR.glob("*.woff2"))
    print(f"блоков оставлено: {kept} (из {len(blocks)}); скачано: {downloaded}, без изменений: {unchanged}")
    print(f"итого шрифтов: {len(list(FONT_DIR.glob('*.woff2')))} файлов, {total // 1024} КиБ")
    print(f"написано: {CSS_OUT.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
