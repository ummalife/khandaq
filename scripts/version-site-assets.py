#!/usr/bin/env python3
"""Stamp the site's long-cached files with a version taken from their content, and check the stamps.

KHANDAQ (redesign E23, 2026-11-11). nginx serves /assets/ with max-age=604800 and /style.css with
max-age=3600 (infra/nginx/khandaq-static-site.locations.conf). A browser that cached site.js or
tokens.css keeps using that copy for up to a week, so a deploy that changes them would pair new
pages with old code and old colours, and nothing would report it. Every page therefore links these
files as /path?v=<first 10 hex digits of the file's SHA-256>: new content, new address, no stale copy.

The stamp is derived, never typed. web/assets/tokens.css is written by design/system/build.py, so a
rebuild of the tokens changes its stamp too; this check then fails until the pages are restamped.

KHANDAQ (SEO, 2026-11-13). The server does not send these files as they are in web/: it sends
their minified form (scripts/site_minify.py: comments and layout whitespace removed, nothing else),
so the stamp is the hash of the minified bytes, and this script is also what writes those bytes for
deploy-site.sh. The pages are stamped with exactly what gets published, and a change to the
minifier alone moves the stamps as well. A page that links a stylesheet or script of this site that
is not listed below fails the check: it would be published unminified and without a stamp.

    scripts/version-site-assets.py            # rewrite the stamps in every page to match the files
    scripts/version-site-assets.py --check    # fail when a stamp is missing or stale (CI, deploy-site.sh)
    scripts/version-site-assets.py --out DIR  # the same check, then write the bytes to publish to DIR
"""
from __future__ import annotations

import argparse
import hashlib
import re
import sys
from pathlib import Path
from urllib.parse import urljoin, urlsplit

# Sibling module, imported the way verify-site-deploy.py imports tls_probe.
sys.path.insert(0, str(Path(__file__).resolve().parent))
import site_minify  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
WEB = ROOT / "web"
SITE_HOST = "khandaq.org"
STAMP_LENGTH = 10

# The address a page links -> the file behind it. These outlive a deploy in the browser's cache, and
# each is published minified.
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


def stamp_of(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()[:STAMP_LENGTH]


def write_published(out: Path, published: dict[str, bytes]) -> None:
    """Lay the published bytes out under their addresses, readable by the web server."""
    for address, data in published.items():
        target = out / address.lstrip("/")
        target.parent.mkdir(parents=True, exist_ok=True)
        for folder in target.relative_to(out).parents:
            (out / folder).chmod(0o755)
        target.write_bytes(data)
        target.chmod(0o644)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    ap.add_argument("--check", action="store_true",
                    help="report missing or stale stamps without writing; exit 1 if there are any")
    ap.add_argument("--out", type=Path, metavar="DIR",
                    help="check as --check does, then write the minified files to publish into DIR")
    args = ap.parse_args()
    checking = args.check or args.out is not None
    if args.out is not None:
        out = args.out.resolve()
        # Never into the checkout: written over web/, the minified bytes would replace the sources.
        if out == ROOT or ROOT in out.parents:
            print(f"FAIL: --out {args.out} is inside the repository; give a directory outside it, "
                  f"such as one from mktemp -d", file=sys.stderr)
            return 1

    missing = [path for path in VERSIONED.values() if not path.is_file()]
    if missing:
        for path in missing:
            print(f"FAIL: {path.relative_to(ROOT)} does not exist", file=sys.stderr)
        return 1
    try:
        minified = {address: site_minify.minify_file(path) for address, path in VERSIONED.items()}
    except site_minify.MinifyError as exc:
        print(f"FAIL: cannot minify {exc}", file=sys.stderr)
        return 1
    published = {address: result.text.encode("utf-8") for address, result in minified.items()}
    stamps = {address: stamp_of(data) for address, data in published.items()}

    problems: list[str] = []
    unlisted: list[str] = []
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
                if address.endswith((".css", ".js")):
                    line = text.count("\n", 0, m.start()) + 1
                    unlisted.append(f"{name}:{line}: {address} is not in VERSIONED, so it would be "
                                    f"published unminified and without a stamp")
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
            if not checking:
                page.write_text(new_text, encoding="utf-8")

    if unlisted:
        print("FAIL: pages link site stylesheets or scripts that are not versioned:", file=sys.stderr)
        for line in unlisted:
            print(f"  - {line}", file=sys.stderr)
        print("Add each to VERSIONED in scripts/version-site-assets.py.", file=sys.stderr)
        return 1

    # A file no page links any more means the pattern stopped matching (a rename, a new way of
    # linking), and a check that sees nothing passes vacuously. That is a failure, not a pass.
    for address, count in links.items():
        if count == 0:
            problems.append(f"no page under web/ links {address}: the check would pass vacuously")

    summary = ", ".join(f"{address}?v={stamps[address]}" for address in VERSIONED)
    total = sum(links.values())
    if checking:
        # What pr-cy reported, held as a gate: Lighthouse's rule applied to the published bytes.
        unminified = []
        for address, result in minified.items():
            share, wasted, flagged = site_minify.audit(VERSIONED[address].suffix, result)
            if flagged:
                unminified.append(f"{address}: {share:.1%} of it is still whitespace, {wasted} B of "
                                  f"transfer; the minifier no longer does its job")
        if problems or unminified:
            if problems:
                print("FAIL: long-cached files linked without their current version:", file=sys.stderr)
                for line in problems:
                    print(f"  - {line}", file=sys.stderr)
                print("Run scripts/version-site-assets.py to restamp the pages.", file=sys.stderr)
            if unminified:
                print("FAIL: published files a minification audit would still report:", file=sys.stderr)
                for line in unminified:
                    print(f"  - {line}", file=sys.stderr)
            return 1
        sizes = ", ".join(f"{address} {VERSIONED[address].stat().st_size} -> {len(data)} B"
                          for address, data in published.items())
        print(f"ok: {total} links in {len(pages)} pages carry the current version ({summary}); "
              f"published minified: {sizes}")
        if args.out is not None:
            write_published(args.out, published)
            print(f"wrote {len(published)} files to publish into {args.out}")
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
