#!/usr/bin/env python3
"""The published site must state the release that is actually being shipped.

KHANDAQ (re-review v2 2026-08-22, RR2-03). The external monitor asserted that the commit the site
declares EXISTS — which a site a release behind satisfies perfectly. On 22 August khandaq.org still
advertised Android 0.2.40 and iOS build 142983 while the repository, the stores and the security
changes had moved on twice. The download page is where a user checks what they are installing
against what was published; a stale one quietly undermines the provenance story it exists to support.

So this is a freshness SLO rather than an existence check: the published manifest must equal the
repository's, and it is allowed to lag only for as long as a deploy reasonably takes.

    scripts/check-site-freshness.py
    scripts/check-site-freshness.py --grace-minutes 60 --base https://khandaq.org

The grace is measured from the commit that last touched web/release-manifest.json, so a release
merged a minute ago does not fail the monitor while the deploy is still running, and one merged
yesterday does.
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LOCAL = ROOT / "web" / "release-manifest.json"
DEFAULT_BASE = "https://khandaq.org"
# Deploy plus CDN/browser caches. Longer than a deploy takes, short enough that "we forgot" fails.
DEFAULT_GRACE_MIN = 60

WATCHED = (
    ("android", "versionName"),
    ("android", "versionCode"),
    ("ios", "buildNumber"),
    ("ios", "marketingVersion"),
)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default=DEFAULT_BASE)
    ap.add_argument("--grace-minutes", type=int, default=DEFAULT_GRACE_MIN)
    args = ap.parse_args()

    if not LOCAL.is_file():
        print("::error::нет web/release-manifest.json", file=sys.stderr)
        return 1
    local = json.loads(LOCAL.read_text(encoding="utf-8"))

    url = args.base.rstrip("/") + "/release-manifest.json"
    req = urllib.request.Request(url, headers={"User-Agent": "khandaq-freshness/1"})
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:  # noqa: S310 - fixed https host
            published = json.loads(resp.read())
    except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError, OSError, ValueError) as exc:
        print(f"::error::{url} не читается ({exc}) — сайт не отдаёт манифест релиза",
              file=sys.stderr)
        return 1

    drift = []
    for section, key in WATCHED:
        want = (local.get(section) or {}).get(key)
        got = (published.get(section) or {}).get(key)
        if str(want) != str(got):
            drift.append(f"{section}.{key}: в репозитории {want!r}, на сайте {got!r}")

    if not drift:
        print(f"ок: сайт заявляет тот же релиз — Android "
              f"{local['android']['versionName']} ({local['android']['versionCode']}), "
              f"iOS build {local['ios']['buildNumber']}")
        return 0

    # Сколько времени прошло с коммита, который последним трогал манифест.
    age_min = None
    try:
        r = subprocess.run(["git", "-C", str(ROOT), "log", "-1", "--format=%ct", "--",
                            str(LOCAL.relative_to(ROOT))], capture_output=True, text=True, timeout=60)
        if r.returncode == 0 and r.stdout.strip():
            age_min = int((time.time() - int(r.stdout.strip())) / 60)
    except (OSError, ValueError, subprocess.TimeoutExpired):
        age_min = None

    for d in drift:
        print(f"  расхождение: {d}")

    if age_min is not None and age_min <= args.grace_minutes:
        print(f"\nВНИМАНИЕ: релиз объявлен {age_min} мин. назад, окно на выкат "
              f"{args.grace_minutes} мин. — сайт ещё имеет право отставать.")
        return 0

    when = f"{age_min} мин. назад" if age_min is not None else "неизвестно когда"
    print(f"\n::error::сайт отстаёт от объявленного релиза, а объявлен он {when} "
          f"(окно {args.grace_minutes} мин.). Выкатите сайт: scripts/deploy-site.sh. "
          f"Страница загрузок — это то, по чему пользователь сверяет, что он ставит.",
          file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
