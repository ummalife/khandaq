#!/usr/bin/env python3
"""Hold the Android native library to the digests that were reviewed.

KHANDAQ (internal audit 2026-08-22). libjni-c-toxcore.so is not in the repository — the release
downloads it as an artifact of the android-native-so workflow, and the run was chosen as "latest
successful, any branch". Anyone with write access could therefore build a library from a patched
circle_scripts/deps.sh on their own branch and have a tag cut from clean master pick it up, signed
and attested as if it came from reviewed source.

The release now takes the run from this release's own history AND checks these digests. This script
is the same check for a working copy, so a local build that quietly picks up a different library
fails here rather than in a shipped APK.

    scripts/check-native-lib-pins.py
"""
from __future__ import annotations

import hashlib
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
NL = ROOT / "khandaq-android-trifa/android-refimpl-app/app/nativelibs"
SUMS = NL / "SHA256SUMS.txt"
# Only these reach a user's device; the others exist so the app runs on an emulator.
SHIPPED = ("arm64-v8a", "armeabi-v7a")


def main() -> int:
    if not SUMS.is_file():
        print(f"::error::нет {SUMS.relative_to(ROOT)} — нативная библиотека ничем не закреплена, "
              f"а в репозитории её нет: релиз взял бы её из скачанного артефакта без сверки",
              file=sys.stderr)
        return 1

    expected: dict[str, str] = {}
    for line in SUMS.read_text(encoding="utf-8").splitlines():
        m = re.match(r"^([0-9a-f]{64})\s+\*?(\S+)\s*$", line.strip())
        if m:
            expected[m.group(2)] = m.group(1)
    if not expected:
        print(f"::error::{SUMS.name} не содержит ни одной суммы", file=sys.stderr)
        return 1

    missing_shipped = [a for a in SHIPPED if f"{a}/libjni-c-toxcore.so" not in expected]
    if missing_shipped:
        print(f"::error::в закреплении нет поставляемых ABI: {', '.join(missing_shipped)}",
              file=sys.stderr)
        return 1

    problems, checked, absent = [], 0, 0
    for rel, want in sorted(expected.items()):
        path = NL / rel
        if not path.is_file():
            absent += 1
            continue
        digest = hashlib.sha256()
        with path.open("rb") as fh:
            for chunk in iter(lambda: fh.read(1 << 20), b""):
                digest.update(chunk)
        got = digest.hexdigest()
        if got != want:
            ships = rel.split("/")[0] in SHIPPED
            problems.append(
                f"{rel}: закреплено {want[:16]}…, на диске {got[:16]}…"
                + (" — И ЭТОТ СЛАЙС УЕЗЖАЕТ ПОЛЬЗОВАТЕЛЮ" if ships else " (только эмулятор)"))
        else:
            checked += 1

    print(f"==> Закреплено слайсов: {len(expected)}; проверено на диске {checked}, "
          f"отсутствует локально {absent}")
    if problems:
        print(f"\nПРОВАЛЕНО: {len(problems)}", file=sys.stderr)
        for p in problems:
            print(f"::error::  {p}", file=sys.stderr)
        print("::error::Либо нативный стек пересобрали — тогда обновите SHA256SUMS.txt в том же PR, "
              "что и circle_scripts/deps.sh, — либо это не те байты, которые ревьюили.",
              file=sys.stderr)
        return 1
    if checked == 0:
        print("нет ни одного слайса локально — проверять нечего (это нормально на чистом checkout)")
        return 0
    print("ВСЁ ЧИСТО: нативные слайсы совпадают с закреплёнными дайджестами")
    return 0


if __name__ == "__main__":
    sys.exit(main())
