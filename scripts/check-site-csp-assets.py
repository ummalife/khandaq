#!/usr/bin/env python3
"""KHANDAQ gate — every asset the pages reference must be one the site's own CSP allows.

The download cards carried their platform glyphs as inline `data:` URIs. Tightening the policy to
`img-src 'self'` (W-01/W-03) made the browser drop every one of them, and the cards rendered as five
empty coloured squares. Nothing failed: the deploy succeeded, the header check passed — it verifies
the headers, not whether the pages survive them — and the external probe checks headers and TLS. The
page was simply wrong in every browser, quietly, until someone looked at it.

Two things are asserted here:
  1. no `data:` image is referenced while img-src lacks `data:`;
  2. every local asset URL in the CSS actually exists in the tree, and is one deploy-site.sh copies.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
WEB = ROOT / "web"
CSS = WEB / "style.css"
HEADERS = ROOT / "infra" / "nginx" / "khandaq-security-headers.conf"
# deploy-site.sh sends a named list of files plus the assets/ directory. Anything outside that never
# reaches the server, however correct it looks locally.
COPIED_DIRS = ("assets/",)

failures = []
css = CSS.read_text(encoding="utf-8")
headers = HEADERS.read_text(encoding="utf-8") if HEADERS.is_file() else ""

m = re.search(r"img-src([^;\"]*)", headers)
img_src = m.group(1) if m else ""
data_allowed = "data:" in img_src

sources = [(CSS.name, css)]
for html in sorted(WEB.glob("*.html")):
    sources.append((html.name, html.read_text(encoding="utf-8", errors="replace")))

for name, text in sources:
    if not data_allowed and "data:image" in text:
        for lineno, line in enumerate(text.splitlines(), 1):
            if "data:image" in line:
                failures.append(f"{name}:{lineno}: references a data: image, but img-src is "
                                f"'{img_src.strip()}' — the browser will drop it and show nothing")

for url in sorted(set(re.findall(r'url\("([^"]+)"\)', css))):
    if url.startswith(("http://", "https://", "data:", "//")):
        continue
    target = WEB / url
    if not target.is_file():
        failures.append(f"style.css references {url}, which does not exist in web/")
        continue
    if not any(url.startswith(d) for d in COPIED_DIRS):
        failures.append(f"style.css references {url}, which deploy-site.sh does not copy to the "
                        f"server — it exists locally and 404s in production")

if failures:
    print("FAIL — the pages reference assets that will not render in production:\n")
    for f in failures:
        print(f"  - {f}")
    sys.exit(1)

print(f"ok — no blocked data: images, and every local asset in style.css exists and is deployed")
