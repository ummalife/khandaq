#!/usr/bin/env python3
"""Refuse a workflow that runs a third-party action from a mutable reference.

KHANDAQ (re-review v2 2026-08-22, RR2-10). Most workflows here already pin every action to a commit
SHA; codeql.yml did not, and it is the workflow holding `security-events: write`. A major-version tag
is a pointer somebody else can move: the scanner that decides whether this repository has
vulnerabilities could change without a single line of diff here.

Pinning them once fixes today. This makes it stay fixed, which is the part a person cannot do by
remembering.

    scripts/check-action-pinning.py
    scripts/check-action-pinning.py --all      # include the nested, GitHub-never-reads-these ones

Nested workflows under khandaq-android-trifa/.github/ are NOT scanned by default: GitHub only reads
the repository-root .github/workflows, so those files do not run and pinning them would be theatre.
`--all` exists for the day that changes.
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ROOT_WORKFLOWS = ROOT / ".github" / "workflows"

SHA_RE = re.compile(r"^[0-9a-f]{40}$")
USES_RE = re.compile(r"^\s*(?:-\s*)?uses:\s*['\"]?([^'\"#\s]+)['\"]?")

# Documented exceptions. Empty on purpose: an exception must be argued in a pull request, not
# inherited from a file nobody re-reads. Shape: {"owner/repo": "why this one cannot be pinned"}.
ALLOWED_UNPINNED: dict[str, str] = {}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--all", action="store_true",
                    help="также вложенные .github, которые GitHub не исполняет")
    args = ap.parse_args()

    files = sorted(ROOT_WORKFLOWS.glob("*.yml")) + sorted(ROOT_WORKFLOWS.glob("*.yaml"))
    if args.all:
        files += sorted(p for p in ROOT.glob("*/.github/workflows/*.y*ml"))
    if not files:
        print("::error::не найдено ни одного workflow — проверять нечего, и это само по себе "
              "подозрительно", file=sys.stderr)
        return 1

    problems: list[str] = []
    pinned = local = 0

    for path in files:
        rel = path.relative_to(ROOT)
        for n, line in enumerate(path.read_text(encoding="utf-8", errors="replace").splitlines(), 1):
            m = USES_RE.match(line)
            if not m:
                continue
            ref = m.group(1)
            if ref.startswith("./") or ref.startswith(".github/"):
                local += 1
                continue
            if ref.startswith("docker://"):
                if "@sha256:" not in ref:
                    problems.append(f"{rel}:{n}: образ {ref} не закреплён по digest")
                else:
                    pinned += 1
                continue
            if "@" not in ref:
                problems.append(f"{rel}:{n}: {ref} вообще без ссылки на версию")
                continue
            name, _, version = ref.partition("@")
            if SHA_RE.match(version):
                pinned += 1
                continue
            owner_repo = "/".join(name.split("/")[:2])
            if owner_repo in ALLOWED_UNPINNED:
                print(f"    исключение: {rel}:{n} {ref} — {ALLOWED_UNPINNED[owner_repo]}")
                continue
            problems.append(
                f"{rel}:{n}: {name}@{version} — подвижная ссылка. Тег может быть переставлен "
                f"владельцем репозитория действия, и содержимое шага изменится без единой строки "
                f"диффа здесь. Закрепите по SHA коммита: "
                f"gh api repos/{owner_repo}/git/ref/tags/{version} --jq .object.sha")

    print(f"==> Проверено {len(files)} workflow: {pinned} закреплённых действи(я/й), "
          f"{local} локальных")
    if problems:
        print(f"\nПРОВАЛЕНО: {len(problems)} незакреплённ(ое/ых) действи(е/й)", file=sys.stderr)
        for p in problems:
            print(f"::error::  {p}", file=sys.stderr)
        return 1
    print("ВСЁ ЧИСТО: каждое стороннее действие закреплено по неизменяемому SHA")
    return 0


if __name__ == "__main__":
    sys.exit(main())
