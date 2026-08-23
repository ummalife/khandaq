#!/usr/bin/env python3
"""Do not let security fixes pile up on master while the published version stands still.

KHANDAQ (re-review 2026-08-23, KQ-R1). The review's finding was not a bug in the code — it was that
the code was FIXED and the users were not: out-of-bounds reads reachable from an accepted contact
were closed on master while 0.2.42 stayed in the stores. "The repository is green" and "users are
protected" had quietly become different statements, and nothing measured the gap.

This measures it. It counts security-labelled commits landed since the release version last moved.

    scripts/check-release-freshness.py              # report; warn past the threshold
    scripts/check-release-freshness.py --release    # hard failure if ANY are outstanding

The soft/hard split is deliberate. Failing every pull request the moment one security fix merges
would block the second fix — the gate would punish exactly the work it exists to encourage. So
day-to-day it warns and counts, and the release path refuses to cut a build that leaves known fixes
behind.
"""
from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
GRADLE = "khandaq-android-trifa/android-refimpl-app/app/build.gradle"

# A commit counts as a security fix if it says so. Subject prefixes used in this repository, plus
# the audit identifiers, so a "close RR3-05" style message is not missed.
SECURITY_RE = re.compile(
    r"^(security|fix\(security\)|CI: let CodeQL)|(RR\d|KQ-|K-\d|W-\d|S-\d|M-\d)", re.I)

# Above this many outstanding fixes, an ordinary run starts warning loudly rather than just counting.
WARN_AT = 3


def git(*args: str) -> str:
    r = subprocess.run(["git", "-C", str(ROOT), *args], capture_output=True, text=True)
    return r.stdout.strip() if r.returncode == 0 else ""


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--release", action="store_true",
                    help="релизный режим: любой невыпущенный security-фикс — отказ")
    args = ap.parse_args()

    if not git("rev-parse", "--git-dir"):
        print("не git-репозиторий — проверять нечего")
        return 0

    # The commit where versionCode last changed IS the release boundary: everything after it is in
    # the repository and not in anybody's hands.
    # -G, not -S. `-S versionCode` counts OCCURRENCES of the string, and the line is always
    # present — so it matched only the commit that first introduced it, 1440 commits ago, and the
    # gate reported 61 outstanding fixes on a tree that had just been released. Found by reading the
    # number rather than the exit status. -G matches commits whose diff touches a line where the
    # value itself changes.
    boundary = git("log", "-1", "--format=%H", "-G", r"versionCode [0-9]", "--", GRADLE)
    if not boundary:
        print("::warning::не удалось найти коммит последнего изменения versionCode — "
              "проверка свежести релиза не выполнена", file=sys.stderr)
        return 0

    version = ""
    m = re.search(r'versionName\s+"([^"]+)"', (ROOT / GRADLE).read_text(encoding="utf-8"))
    if m:
        version = m.group(1)

    log = git("log", "--format=%H%x1f%s", f"{boundary}..HEAD")
    outstanding = []
    for line in log.splitlines():
        if "\x1f" not in line:
            continue
        sha, subject = line.split("\x1f", 1)
        if subject.startswith("Merge "):
            continue
        if SECURITY_RE.search(subject):
            outstanding.append((sha[:12], subject))

    total = len(git("log", "--format=%H", f"{boundary}..HEAD").splitlines())
    print(f"==> Версия {version or '?'} закреплена коммитом {boundary[:12]}")
    print(f"    коммитов после неё: {total}, из них помеченных как безопасность: {len(outstanding)}")
    for sha, subject in outstanding:
        print(f"      {sha}  {subject[:96]}")

    print()
    if not outstanding:
        print("ВСЁ ЧИСТО: невыпущенных исправлений безопасности нет")
        return 0

    if args.release:
        print(f"::error::релиз оставил бы позади {len(outstanding)} исправлени(е/я/й) "
              f"безопасности. Поднимите версию и соберите из коммита, который их содержит — "
              f"иначе «репозиторий зелёный» и «пользователи защищены» это разные утверждения.",
              file=sys.stderr)
        return 1

    if len(outstanding) >= WARN_AT:
        print(f"::warning::{len(outstanding)} исправлени(е/я/й) безопасности ждут выпуска. "
              f"Это не ошибка сборки, но это долг: код починен, а установленные копии — нет.")
    else:
        print(f"невыпущенных исправлений безопасности: {len(outstanding)} (порог "
              f"предупреждения — {WARN_AT})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
