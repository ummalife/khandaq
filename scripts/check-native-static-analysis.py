#!/usr/bin/env python3
"""Broad static analysis over the desktop's own C++, with a baseline.

KHANDAQ (re-review v2 2026-08-22, RR2-12). The targeted harnesses in this repository are good at what
they are for — each pins a defect somebody already understood. None of them is a general analyser,
and the desktop is the largest memory-unsafe surface in the product: it parses peer-supplied media,
files and protocol packets. CodeQL cannot cover it here because c-cpp needs to observe a real
compilation and the runner has no Qt toolchain.

cppcheck needs no build, which is why it is the part that exists today. It runs over
khandaq-desktop/src — the code this project writes, not the vendored tree — and compares against a
committed baseline: inherited findings do not block, anything NEW does.

    scripts/check-native-static-analysis.py
    scripts/check-native-static-analysis.py --update   # переписать базовую линию (осознанно!)

NOT the whole answer to RR2-12. A CodeQL c-cpp build and sanitizer-backed fuzzing of the packet and
media parsers are still open; this is the part that could be made to work without a Qt toolchain in
CI, and it is honest about being that.
"""
from __future__ import annotations

import argparse
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TARGET = ROOT / "khandaq-desktop" / "src"
BASELINE = ROOT / "security" / "cppcheck-baseline.txt"

# Qt macros and the headers cppcheck cannot resolve without a build produce noise that says nothing
# about the code; the ids below are that noise and nothing else.
SUPPRESS = ("missingInclude", "missingIncludeSystem", "unknownMacro", "syntaxError",
            "preprocessorErrorDirective")


def run_cppcheck() -> list[str]:
    cmd = [shutil.which("cppcheck") or "cppcheck",
           "--enable=warning,portability", "--inline-suppr", "--quiet",
           # -j 1 on purpose: with the default parallelism cppcheck's whole-program checks land
           # differently between runs, and a baseline compared against a moving target reports
           # phantom "fixed" and phantom "new" findings. Verified: two runs at -j 1 are identical,
           # two runs at the default are not.
           "-j", "1",
           *(f"--suppress={s}" for s in SUPPRESS),
           "--template={severity}|{id}|{file}|{message}", str(TARGET)]
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=1800)
    # Пути — относительно корня репозитория: иначе базовая линия привязана к каталогу, в котором её
    # сняли, и в CI не совпадёт ни одной строкой.
    root = str(ROOT) + "/"
    lines = sorted({ln.strip().replace(root, "") for ln in (r.stdout + r.stderr).splitlines()
                    if ln.count("|") >= 3})
    return lines


def load_baseline() -> set[str]:
    if not BASELINE.is_file():
        return set()
    return {ln.strip() for ln in BASELINE.read_text(encoding="utf-8").splitlines()
            if ln.strip() and not ln.startswith("#")}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--update", action="store_true")
    args = ap.parse_args()

    if not shutil.which("cppcheck"):
        print("::error::cppcheck не установлен. В CI он ставится шагом выше; "
              "локально: brew install cppcheck / apt install cppcheck", file=sys.stderr)
        return 1
    if not TARGET.is_dir():
        print(f"::error::нет {TARGET.relative_to(ROOT)}", file=sys.stderr)
        return 1

    found = run_cppcheck()
    baseline = load_baseline()

    if args.update:
        header = [ln for ln in BASELINE.read_text(encoding="utf-8").splitlines()
                  if ln.startswith("#")] if BASELINE.is_file() else []
        BASELINE.write_text("\n".join(header + found) + "\n", encoding="utf-8")
        print(f"базовая линия переписана: {len(found)} находок")
        return 0

    new = [f for f in found if f not in baseline]
    fixed = [b for b in baseline if b not in found]

    print(f"==> cppcheck по {TARGET.relative_to(ROOT)}: найдено {len(found)}, "
          f"в базовой линии {len(baseline)}")
    for f in fixed:
        print(f"    ПОЧИНЕНО (уберите из базовой линии): {f}")
    if not found and not baseline:
        print("::error::анализатор не вернул НИЧЕГО и базовая линия пуста — так не бывает; "
              "скорее всего он не разобрал ни одного файла", file=sys.stderr)
        return 1

    if new:
        print(f"\n::error::{len(new)} новая(ые) находка(и) статического анализа", file=sys.stderr)
        for f in new:
            sev, ident, path, msg = f.split("|", 3)
            print(f"::error::  [{sev}] {ident}  {path}  {msg}", file=sys.stderr)
        print("::error::Почините их или, если находка ложная, добавьте инлайн "
              "// cppcheck-suppress <id> с объяснением. Базовая линия расширяется только "
              "осознанно и с обоснованием в PR.", file=sys.stderr)
        return 1
    print("ВСЁ ЧИСТО: новых находок нет")
    return 0


if __name__ == "__main__":
    sys.exit(main())
