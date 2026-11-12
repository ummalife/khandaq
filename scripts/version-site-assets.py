#!/usr/bin/env python3
"""Stamp the site's long-cached files with a version taken from their content, and check the stamps.

KHANDAQ (redesign E23, 2026-11-11). nginx serves /assets/ with max-age=604800 and /style.css with
max-age=3600 (infra/nginx/khandaq-static-site.locations.conf). A browser that cached site.js or
tokens.css keeps using that copy for up to a week, so a deploy that changes them would pair new
pages with old code and old colours, and nothing would report it. Every page therefore links these
files as /path?v=<first 10 hex digits of the file's SHA-256>: new content, new address, no stale copy.

The stamp is derived, never typed. web/assets/tokens.css is written by design/system/build.py, so a
rebuild of the tokens changes its stamp too; this check then fails until the pages are restamped.

    scripts/version-site-assets.py           # rewrite the stamps in every page to match the files
    scripts/version-site-assets.py --check   # fail when a stamp is missing or stale (CI, deploy-site.sh)
"""
from __future__ import annotations

import argparse
import hashlib
import re
import sys
from pathlib import Path
from urllib.parse import urljoin, urlsplit

ROOT = Path(__file__).resolve().parent.parent
WEB = ROOT / "web"
SITE_HOST = "khandaq.org"
STAMP_LENGTH = 10

# The address a page links -> the file behind it. These outlive a deploy in the browser's cache.
VERSIONED = {
    "/assets/site.js": WEB / "assets" / "site.js",
    "/assets/tokens.css": WEB / "assets" / "tokens.css",
    "/style.css": WEB / "style.css",
}

# src="..." and href="..." in either quote style. The leading whitespace keeps data-src and
# srcset out.
ATTRIBUTE = re.compile(r"(\s(?:src|href)=)([\"'])(.*?)\2", re.S)


def page_address(page: Path) -> str:
    """The path a page is served at, so a relative link resolves the way the browser resolves it."""
    path = "/" + page.relative_to(WEB).as_posix()
    return path[: -len("index.html")] if path.endswith("/index.html") else path


def stamp_of(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()[:STAMP_LENGTH]


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    ap.add_argument("--check", action="store_true",
                    help="report missing or stale stamps without writing; exit 1 if there are any")
    args = ap.parse_args()

    missing = [path for path in VERSIONED.values() if not path.is_file()]
    if missing:
        for path in missing:
            print(f"FAIL: {path.relative_to(ROOT)} does not exist", file=sys.stderr)
        return 1
    stamps = {address: stamp_of(path) for address, path in VERSIONED.items()}

    problems: list[str] = []
    links = {address: 0 for address in VERSIONED}
    pages = sorted(WEB.rglob("*.html"))
    rewritten: list[str] = []
    for page in pages:
        text = page.read_text(encoding="utf-8")
        name = page.relative_to(ROOT).as_posix()
        base = page_address(page)

        def restamp(m: re.Match[str]) -> str:
            value = m.group(3)
            parts = urlsplit(value)
            if parts.scheme not in ("", "http", "https") or parts.netloc not in ("", SITE_HOST):
                return m.group(0)
            address = urlsplit(urljoin(base, parts.path)).path if not parts.netloc else parts.path
            if address not in VERSIONED:
                return m.group(0)
            links[address] += 1
            want = f"v={stamps[address]}"
            bare = value.split("?", 1)[0].split("#", 1)[0]
            if parts.query != want:
                line = text.count("\n", 0, m.start()) + 1
                found = f"carries ?{parts.query}" if parts.query else "carries no version"
                problems.append(f"{name}:{line}: {bare} {found}; the file is now ?{want}")
            fixed = bare + "?" + want
            if parts.fragment:
                fixed += "#" + parts.fragment
            return m.group(1) + m.group(2) + fixed + m.group(2)

        new_text = ATTRIBUTE.sub(restamp, text)
        if new_text != text:
            rewritten.append(name)
            if not args.check:
                page.write_text(new_text, encoding="utf-8")

    # A file no page links any more means the pattern stopped matching (a rename, a new way of
    # linking), and a check that sees nothing passes vacuously. That is a failure, not a pass.
    for address, count in links.items():
        if count == 0:
            problems.append(f"no page under web/ links {address}: the check would pass vacuously")

    summary = ", ".join(f"{address}?v={stamps[address]}" for address in VERSIONED)
    total = sum(links.values())
    if args.check:
        if problems:
            print("FAIL: long-cached files linked without their current version:", file=sys.stderr)
            for line in problems:
                print(f"  - {line}", file=sys.stderr)
            print("Run scripts/version-site-assets.py to restamp the pages.", file=sys.stderr)
            return 1
        print(f"ok: {total} links in {len(pages)} pages carry the current version ({summary})")
        return 0

    vacuous = [line for line in problems if line.startswith("no page")]
    if vacuous:
        for line in vacuous:
            print(f"FAIL: {line}", file=sys.stderr)
        return 1
    if rewritten:
        print(f"restamped {len(rewritten)} pages: {', '.join(rewritten)}")
    print(f"{total} links in {len(pages)} pages carry the current version ({summary})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
