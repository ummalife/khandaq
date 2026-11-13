#!/usr/bin/env python3
"""Hold khandaq.org's visit statistics to the consent the privacy policy promises.

KHANDAQ (visit statistics, 2026-11-13). The site counts visits with Yandex Metrica and Google
Analytics only after the visitor accepts: web/assets/visit-stats.js asks, and it alone loads the
counters (privacy policy, "This website"). That promise breaks quietly, with nothing failing:

  1. a counter loaded any other way, such as the snippet either service hands out pasted into a
     page, runs before anyone is asked. So no page and no other file of the site names the counters'
     hosts; only the loader does.
  2. every content page loads the loader, so the notice is there to answer and to withdraw from,
     and both privacy policies carry the section the notice links to and the button that brings the
     notice back. The utility pages stay without it, on purpose: the 404 page (served at any unknown
     address, mostly to scanners), the downloads mirror (a plain list of signed files) and the search
     engines' ownership files, which must stay byte for byte as issued.
  3. the advertising cookie matching stays closed: the header policy allows no frames and nothing
     from yandex.ru (scripts/generate-security-headers.py).

    scripts/check-visit-stats.py
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
WEB = ROOT / "web"
LOADER = WEB / "assets" / "visit-stats.js"
HEADERS = ROOT / "infra" / "nginx" / "khandaq-security-headers.conf"

CONTENT_PAGES = ("index.html", "ru/index.html", "changelog.html", "ru/changelog.html",
                 "privacy.html", "ru/privacy.html")
POLICIES = {"privacy.html": "/privacy.html#website", "ru/privacy.html": "/ru/privacy.html#website"}

COUNTER_HOSTS = re.compile(r"mc\.yandex\.|yandex\.ru/an/|googletagmanager\.com|google-analytics\.com|"
                           r"analytics\.google\.com", re.I)
LOADER_TAG = re.compile(r'<script\s+src="/assets/visit-stats\.js(?:\?v=[0-9a-f]+)?"\s+defer></script>')
TEXT_SUFFIXES = (".html", ".js", ".css", ".json", ".txt", ".xml")


def main() -> int:
    failures: list[str] = []
    if not LOADER.is_file():
        print(f"FAIL: {LOADER.relative_to(ROOT)} does not exist", file=sys.stderr)
        return 1
    loader = LOADER.read_text(encoding="utf-8")

    # 1. Only the loader names the counters' hosts.
    scanned = 0
    for path in sorted(WEB.rglob("*")):
        if path == LOADER or not path.is_file() or path.suffix not in TEXT_SUFFIXES:
            continue
        scanned += 1
        text = path.read_text(encoding="utf-8", errors="replace")
        for m in COUNTER_HOSTS.finditer(text):
            line = text.count("\n", 0, m.start()) + 1
            failures.append(f"{path.relative_to(ROOT)}:{line}: names {m.group(0)}; a counter loaded from "
                            f"here would run before the visitor is asked. Only visit-stats.js loads them.")
    if not COUNTER_HOSTS.search(loader):
        failures.append(f"{LOADER.relative_to(ROOT)} names none of the counters' hosts: it changed shape, and "
                        f"this check would pass vacuously")

    # 2. The content pages ask, the utility pages do not, and the policies answer.
    pages = sorted(WEB.rglob("*.html"))
    for page in pages:
        name = page.relative_to(WEB).as_posix()
        html = page.read_text(encoding="utf-8")
        linked = len(LOADER_TAG.findall(html))
        mentioned = "visit-stats.js" in html
        if name in CONTENT_PAGES:
            if linked != 1:
                failures.append(f"web/{name} must load /assets/visit-stats.js exactly once, with defer "
                                f"(found {linked})")
        elif mentioned:
            failures.append(f"web/{name} loads visit-stats.js, but it is a utility page that stays "
                            f"without statistics (see this script's docstring)")
    missing = [name for name in CONTENT_PAGES if not (WEB / name).is_file()]
    if missing:
        failures.append(f"content pages missing from web/: {', '.join(missing)}")

    for name, href in POLICIES.items():
        page = WEB / name
        html = page.read_text(encoding="utf-8") if page.is_file() else ""
        if 'id="website"' not in html:
            failures.append(f"web/{name} has no section id=\"website\", which the notice links to")
        if not re.search(r"<button\b[^>]*\bdata-visit-stats-open\b[^>]*\bhidden\b", html):
            failures.append(f"web/{name} has no hidden button marked data-visit-stats-open, so a "
                            f"visitor could not bring the notice back to withdraw")
        if f"'{href}'" not in loader:
            failures.append(f"visit-stats.js does not link the notice to {href}")

    # 3. No frames, nothing from yandex.ru.
    headers = HEADERS.read_text(encoding="utf-8") if HEADERS.is_file() else ""
    csp = re.search(r'add_header Content-Security-Policy "([^"]*)"', headers)
    if not csp:
        failures.append(f"{HEADERS.relative_to(ROOT)} carries no Content-Security-Policy")
    else:
        policy = csp.group(1)
        for directive in ("frame-src", "child-src"):
            if re.search(rf"(?:^|;)\s*{directive}\b", policy):
                failures.append(f"the CSP sets {directive}: Metrica's advertising frame would be allowed")
        if re.search(r"https://(?:\*\.)?yandex\.ru\b", policy):
            failures.append("the CSP allows yandex.ru: Metrica's advertising cookie matching would run")

    if failures:
        print("FAIL: the visit statistics no longer match what the privacy policy promises:", file=sys.stderr)
        for line in failures:
            print(f"  - {line}", file=sys.stderr)
        return 1
    print(f"ok: only visit-stats.js names the counters ({scanned} other files scanned); "
          f"{len(CONTENT_PAGES)} content pages load it, {len(pages) - len(CONTENT_PAGES)} utility pages do "
          f"not; both policies carry #website and the button; no frames and no yandex.ru in the CSP")
    return 0


if __name__ == "__main__":
    sys.exit(main())
