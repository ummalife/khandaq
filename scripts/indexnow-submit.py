#!/usr/bin/env python3
"""Tell IndexNow which khandaq.org pages a deploy changed.

KHANDAQ (SEO, 2026-11-16). Ahrefs reported "Changed pages not submitted to IndexNow": Bing, Yandex
and the other engines that share IndexNow learn about an edited page only when they happen to crawl
it again. The protocol lets the site tell them at once: a POST that lists the changed addresses,
proven by a key file that the site serves at its root.

The key is both the name and the content of web/<key>.txt (32 hex digits) and is kept nowhere else,
so that file is the single source of truth. The engines fetch it again to check every submission, so
it stays for good, like the ownership files beside it; scripts/verify-site-deploy.py checks that it
is served.

scripts/deploy-site.sh runs this twice:

    scripts/indexnow-submit.py snapshot --out FILE   # before the upload: each sitemap page as served
    scripts/indexnow-submit.py submit --since FILE   # after the verification: the pages that changed

By hand:

    scripts/indexnow-submit.py key                   # print the key
    scripts/indexnow-submit.py submit --all          # every page of web/sitemap.xml
    scripts/indexnow-submit.py submit --all --dry-run

A page counts as changed when its status or its bytes, fetched over public HTTPS, differ from the
snapshot. That compares what visitors actually receive, so it holds whatever the deploy does to a file
on its way to the server.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
WEB = ROOT / "web"
BASE = "https://khandaq.org"
HOST = urllib.parse.urlsplit(BASE).netloc
ENDPOINT = "https://api.indexnow.org/indexnow"
KEY_FILE = re.compile(r"[0-9a-f]{32}\.txt")
SITEMAP_NS = {"sm": "http://www.sitemaps.org/schemas/sitemap/0.9"}
USER_AGENT = "khandaq-indexnow/1"
# What the IndexNow documentation gives as the meaning of each refusal.
REFUSALS = {
    400: "bad request: the body is not valid",
    403: "the key is not valid: the key file is missing or does not match",
    422: "the URLs do not belong to the host, or the key does not match the protocol",
    429: "too many requests",
}


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):  # noqa: D102
        return None


def die(msg: str) -> None:
    print(f"ERROR: {msg}", file=sys.stderr)
    raise SystemExit(1)


def read_key() -> str:
    """The one key file in web/: its name, less .txt, is its content."""
    files = sorted(p for p in WEB.iterdir() if KEY_FILE.fullmatch(p.name))
    if len(files) != 1:
        die(f"web/ must hold exactly one IndexNow key file (<32 hex digits>.txt), it holds {len(files)}")
    key = files[0].stem
    if files[0].read_text(encoding="utf-8").strip() != key:
        die(f"web/{files[0].name} must contain its own name without .txt")
    return key


def sitemap_urls() -> list[str]:
    root = ET.parse(WEB / "sitemap.xml").getroot()
    urls = [loc.text.strip() for loc in root.findall("sm:url/sm:loc", SITEMAP_NS) if loc.text and loc.text.strip()]
    if not urls:
        die("web/sitemap.xml lists no pages")
    foreign = [url for url in urls if urllib.parse.urlsplit(url).netloc != HOST]
    if foreign:
        die(f"web/sitemap.xml lists pages of another host, which IndexNow refuses: {', '.join(foreign)}")
    return urls


def fetch(url: str) -> tuple[int | None, bytes]:
    """Return (status, body) without following redirects; status None if the request failed."""
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    try:
        with urllib.request.build_opener(NoRedirect).open(req, timeout=30) as resp:
            return resp.status, resp.read()
    except urllib.error.HTTPError as exc:
        return exc.code, exc.read()
    except (urllib.error.URLError, TimeoutError, OSError) as exc:
        print(f"  WARNING: {url}: {exc}", file=sys.stderr)
        return None, b""


def served(url: str) -> dict:
    status, body = fetch(url)
    return {"status": status, "sha256": hashlib.sha256(body).hexdigest() if status is not None else None}


def snapshot(out: Path) -> int:
    read_key()  # a checkout without its key file stops here, before anything is uploaded
    state = {url: served(url) for url in sitemap_urls()}
    out.write_text(json.dumps(state, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"  IndexNow: {len(state)} sitemap pages recorded as served before the upload")
    return 0


def changed_since(before_file: Path) -> list[str]:
    try:
        before = json.loads(before_file.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        die(f"cannot read the snapshot {before_file}: {exc}")
    changed = []
    for url in sitemap_urls():
        now = served(url)
        if now["status"] != 200:
            print(f"  WARNING: {url} answers {now['status']} after the deploy, so it is not submitted",
                  file=sys.stderr)
            continue
        if before.get(url) != now:
            changed.append(url)
    return changed


def submit(urls: list[str], dry_run: bool) -> int:
    key = read_key()
    if not urls:
        print("  IndexNow: no sitemap page changed, nothing to submit")
        return 0
    if dry_run:
        print(f"  IndexNow (dry run): would submit {len(urls)} page(s): {', '.join(urls)}")
        return 0
    key_location = f"{BASE}/{key}.txt"
    status, body = fetch(key_location)
    if status != 200 or body.strip() != key.encode("ascii"):
        print(f"ERROR: {key_location} answers {status} without the key, so every engine would refuse "
              "the submission; deploy the key file first", file=sys.stderr)
        return 1
    payload = json.dumps({"host": HOST, "key": key, "keyLocation": key_location, "urlList": urls})
    req = urllib.request.Request(ENDPOINT, data=payload.encode("utf-8"), method="POST", headers={
        "Content-Type": "application/json; charset=utf-8",
        "User-Agent": USER_AGENT,
    })
    detail = ""
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            code = resp.status
    except urllib.error.HTTPError as exc:
        code = exc.code
        detail = exc.read()[:300].decode("utf-8", "replace").strip()
    except (urllib.error.URLError, TimeoutError, OSError) as exc:
        print(f"ERROR: {ENDPOINT}: {exc}", file=sys.stderr)
        return 1
    # 200 is "received", 202 is "received, the key is being checked": both are a success.
    if code in (200, 202):
        print(f"  IndexNow: HTTP {code}, submitted {len(urls)} page(s): {', '.join(urls)}")
        return 0
    print(f"ERROR: IndexNow answered HTTP {code} ({REFUSALS.get(code, 'unexpected answer')}) {detail}".rstrip(),
          file=sys.stderr)
    return 1


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    sub = ap.add_subparsers(dest="command", required=True)
    sub.add_parser("key", help="print the IndexNow key")
    snap = sub.add_parser("snapshot", help="record every sitemap page as served now")
    snap.add_argument("--out", type=Path, required=True)
    send = sub.add_parser("submit", help="send changed pages to IndexNow")
    which = send.add_mutually_exclusive_group(required=True)
    which.add_argument("--since", type=Path, metavar="SNAPSHOT", help="the pages that differ from this snapshot")
    which.add_argument("--all", action="store_true", help="every page of web/sitemap.xml")
    send.add_argument("--dry-run", action="store_true", help="print the pages instead of sending them")
    args = ap.parse_args()

    if args.command == "key":
        print(read_key())
        return 0
    if args.command == "snapshot":
        return snapshot(args.out)
    urls = sitemap_urls() if args.all else changed_since(args.since)
    return submit(urls, args.dry_run)


if __name__ == "__main__":
    sys.exit(main())
