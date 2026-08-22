#!/usr/bin/env python3
"""Every Semgrep rule must fire on its fixture, and none may fire on the real tree.

KHANDAQ (re-review 2026-08-22, KQ-10). The review asks for exactly this: "Add small intentionally
vulnerable fixtures or mutation tests for one query per language and verify the scan detects them."

A ruleset nobody has watched fire is not coverage. A pattern with a typo, a language tag that does
not match the file extension, a `pattern-not` that swallows the whole rule — all of them produce a
green scan that means nothing, and all of them are invisible without a fixture that MUST be caught.

Two assertions, and the second matters as much as the first:

  * each rule fires on security/sast-fixtures/ — the rule works;
  * no rule fires on the shipped tree — the ruleset is clean today, so the next finding is real.

    scripts/check-sast-fixtures.py
"""
from __future__ import annotations

import json
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RULES = ROOT / "security" / "semgrep-khandaq.yml"
FIXTURES = ROOT / "security" / "sast-fixtures"

# Where the real code lives. Deliberately narrow: scanning the whole checkout would drag in vendored
# third-party sources whose findings are not ours to fix and would bury the ones that are.
REAL_PATHS = [
    "khandaq-android-trifa/android-refimpl-app/app/src/main",
    "khandaq-ios/Antidote",
    "khandaq-ios/local_pod_repo/objcTox/Classes",
    "infra/push/relay",
    "scripts",
]


def semgrep(paths: list[str]) -> dict:
    exe = shutil.which("semgrep")
    if exe is None:
        print("::error::semgrep не установлен. В CI он ставится шагом выше; локально: "
              "python3 -m pip install semgrep", file=sys.stderr)
        sys.exit(2)
    cmd = [exe, "--config", str(RULES), "--json", "--quiet", "--no-git-ignore", "--metrics=off", *paths]
    r = subprocess.run(cmd, capture_output=True, text=True, cwd=ROOT)
    if r.returncode not in (0, 1):   # 1 = findings, which is a normal outcome here
        print(r.stderr[-3000:], file=sys.stderr)
        print("::error::semgrep завершился с ошибкой", file=sys.stderr)
        sys.exit(2)
    try:
        return json.loads(r.stdout)
    except ValueError:
        print(r.stdout[-2000:] + r.stderr[-2000:], file=sys.stderr)
        print("::error::semgrep вернул неразбираемый JSON", file=sys.stderr)
        sys.exit(2)


def rule_ids() -> list[str]:
    return re.findall(r"^  - id: (\S+)", RULES.read_text(encoding="utf-8"), re.M)


def main() -> int:
    if not RULES.is_file() or not FIXTURES.is_dir():
        print("::error::нет security/semgrep-khandaq.yml или security/sast-fixtures/", file=sys.stderr)
        return 1

    expected = rule_ids()
    if not expected:
        print("::error::в наборе правил нет ни одного id", file=sys.stderr)
        return 1

    print(f"==> Фикстуры: правил {len(expected)}")
    found = {f["check_id"].split(".")[-1] for f in semgrep([str(FIXTURES)]).get("results", [])}
    silent = [r for r in expected if r not in found]
    for r in expected:
        print(f"  {'ок  ' if r in found else 'МОЛЧИТ'} {r}")
    if silent:
        print(f"::error::эти правила не сработали ни на одной фикстуре: {', '.join(silent)}. "
              f"Правило, которое никогда не срабатывало, читается как покрытие, но им не является.",
              file=sys.stderr)
        return 1

    print("\n==> Настоящее дерево")
    real = [p for p in REAL_PATHS if (ROOT / p).exists()]
    results = semgrep(real).get("results", [])
    if results:
        print(f"::error::{len(results)} находок в рабочем коде:", file=sys.stderr)
        for f in results[:40]:
            loc = f.get("path", "?")
            line = (f.get("start") or {}).get("line", "?")
            print(f"::error::  {f['check_id'].split('.')[-1]}  {loc}:{line}", file=sys.stderr)
        return 1

    print(f"  чисто: {len(real)} каталог(ов), находок нет")
    print(f"\nВСЁ ЧИСТО: {len(expected)} правил(о) срабатывают на фикстурах и молчат на рабочем коде")
    return 0


if __name__ == "__main__":
    sys.exit(main())
