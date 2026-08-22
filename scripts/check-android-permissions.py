#!/usr/bin/env python3
"""The manifest's permissions and docs/ANDROID-PERMISSIONS.md must be the same set.

KHANDAQ (re-audit 2026-08-22, M-01). "Automated manifest snapshot diff", asked for by the audit and
worth more than a snapshot: a hash tells you SOMETHING changed, this tells you what, and it fails in
the direction that matters. A permission cannot be added without a line saying what feature needs
it, and a documented one cannot quietly stop being requested.

Also checks the upper API bounds, because those are the part that rots silently — a permission whose
maxSdkVersion is missing keeps being requested on versions where the platform stopped honouring it,
which is exactly the "wider than necessary" the finding describes.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MANIFEST = ROOT / "khandaq-android-trifa/android-refimpl-app/app/src/main/AndroidManifest.xml"
DOC = ROOT / "docs/ANDROID-PERMISSIONS.md"

# Permissions that MUST carry an upper bound, and what it must be. Everything else must not be
# bounded unless the table says so — an accidental maxSdkVersion is a feature that stops working.
REQUIRED_BOUNDS = {
    "BLUETOOTH": 30,
    "WRITE_EXTERNAL_STORAGE": 28,
    "READ_EXTERNAL_STORAGE": 32,
}

# Never again, each for a stated reason (see the doc). Re-adding one has to be a deliberate act that
# updates this list, not a merge that slips it back in.
FORBIDDEN = {
    "SYSTEM_ALERT_WINDOW": "рисование поверх других приложений; входящие звонки идут через full-screen intent",
    "DISABLE_KEYGUARD": "ничего не снимает экран блокировки",
    "RAISED_THREAD_PRIORITY": "не существует в AOSP — вендорная строка, которая ничего не давала",
    "MANAGE_EXTERNAL_STORAGE": "полный доступ к хранилищу; Play требует отдельного обоснования, а приложению он не нужен",
}


def main() -> int:
    problems: list[str] = []
    if not MANIFEST.is_file() or not DOC.is_file():
        print("::error::манифест или docs/ANDROID-PERMISSIONS.md отсутствует", file=sys.stderr)
        return 1

    xml = MANIFEST.read_text(encoding="utf-8")
    # Commented-out declarations must not count: several are parked in the manifest on purpose.
    xml_live = re.sub(r"<!--.*?-->", "", xml, flags=re.S)

    declared: dict[str, int | None] = {}
    for m in re.finditer(r"<uses-permission\s+([^>]*?)/?>", xml_live, re.S):
        attrs = m.group(1)
        name = re.search(r'android:name="android\.permission\.([A-Z_0-9]+)"', attrs)
        if not name:
            continue
        bound = re.search(r'android:maxSdkVersion="(\d+)"', attrs)
        declared[name.group(1)] = int(bound.group(1)) if bound else None

    doc = DOC.read_text(encoding="utf-8")
    documented = set(re.findall(r"^\|\s*`([A-Z_0-9]+)`\s*\|", doc, re.M))
    # The "Removed" table lists them in a different column shape; those must NOT be documented as live.
    removed = set(re.findall(r"^\|\s*`([A-Z_0-9]+)`\s*\|\s*(?:No |Nothing |Not )", doc, re.M))
    documented -= removed

    for name in sorted(set(declared) - documented):
        problems.append(f"{name} запрошен в манифесте, но не описан в docs/ANDROID-PERMISSIONS.md")
    for name in sorted(documented - set(declared)):
        problems.append(f"{name} описан в матрице, но манифест его больше не запрашивает")

    for name, reason in FORBIDDEN.items():
        if name in declared:
            problems.append(f"{name} снова запрошен — он был убран: {reason}")

    for name, want in REQUIRED_BOUNDS.items():
        if name not in declared:
            continue
        got = declared[name]
        if got != want:
            problems.append(
                f"{name}: maxSdkVersion={got!r}, ожидалось {want} — без верхней границы разрешение "
                f"продолжает запрашиваться там, где платформа его уже не учитывает")

    if problems:
        for p in problems:
            print(f"::error::{p}", file=sys.stderr)
        return 1
    print(f"ок: {len(declared)} разрешений, все описаны и с верными границами API")
    return 0


if __name__ == "__main__":
    sys.exit(main())
