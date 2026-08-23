#!/usr/bin/env python3
"""Every App Group the iOS code uses must be declared by the targets that use it.

KHANDAQ (internal audit 2026-08-22). ShareInboxStorage reads and writes
`group.org.khandaq.messenger`, and no entitlements file declares it. `containerURL` therefore returns
nil on every device and every build, `savePendingShare` returns false, and "Share to Khandaq" ends in
"Could not share to Khandaq" — a 100% failure with no log, no crash and nothing to notice. The same
nil makes the `khandaq://share` deep link a no-op.

Fixing it needs the App Group created in the Apple Developer portal and enabled on both App IDs;
adding the entitlement without that makes signing fail outright (verified: "Provisioning profile
doesn't include the App Groups capability"). That is an owner action, so the gap is RECORDED here
with an expiry rather than left to be rediscovered by the next audit — and any NEW mismatch fails.

    scripts/check-ios-app-groups.py
"""
from __future__ import annotations

import datetime as dt
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
IOS = ROOT / "khandaq-ios"

# Известные пробелы: группа используется кодом, но не объявлена, и закрыть это может только владелец
# в портале разработчика. Дата — чтобы пробел не стал вечным.
KNOWN_GAPS = {
    "group.org.khandaq.messenger": {
        "why": ("App Group не заведён в портале Apple и не включён у обоих App ID. Добавление "
                "entitlement без этого ломает подпись целиком (проверено сборкой), поэтому код "
                "оставлен как есть: расширение «Поделиться» не работает, но релизы собираются. "
                "Действие владельца: Developer portal -> Identifiers -> App Groups -> создать "
                "group.org.khandaq.messenger и включить у org.khandaq.messenger и "
                "org.khandaq.messenger.shareextension, затем вернуть entitlement обоим таргетам."),
        "expires": "2026-11-20",
    },
}


def main() -> int:
    if not IOS.is_dir():
        print("::error::нет khandaq-ios", file=sys.stderr)
        return 1

    used: dict[str, list[str]] = {}
    for path in list(IOS.rglob("*.swift")) + list(IOS.rglob("*.m")):
        s = str(path)
        # Вендоренный C из toxcore пишет #include "group.h" — это не идентификатор App Group.
        if "/build/" in s or "/Pods/" in s or "/local_pod_repo/toxcore/" in s:
            continue
        text = path.read_text(encoding="utf-8", errors="replace")
        # App Group — обратный DNS: group.<tld>.<org>.<app>. Требуем минимум три части после
        # префикса, иначе в выборку попадают заголовки вида "group.h".
        for m in re.finditer(r'"(group\.[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+){2,})"', text):
            used.setdefault(m.group(1), []).append(str(path.relative_to(ROOT)))

    declared: set[str] = set()
    ent_files = [p for p in IOS.rglob("*.entitlements") if "/build/" not in str(p)]
    for path in ent_files:
        text = path.read_text(encoding="utf-8", errors="replace")
        if "com.apple.security.application-groups" in text:
            declared.update(re.findall(r"<string>(group\.[^<]+)</string>", text))

    print(f"==> App Group: используется в коде {len(used)}, объявлено в {len(ent_files)} "
          f"entitlements {len(declared)}")

    problems, tolerated = [], []
    today = dt.date.today()
    for group, where in sorted(used.items()):
        if group in declared:
            print(f"    ок: {group} объявлен")
            continue
        gap = KNOWN_GAPS.get(group)
        if gap is None:
            problems.append(
                f"{group} используется в {where[0]} (и ещё {len(where) - 1} местах), но не объявлен "
                f"ни в одном entitlements. Контейнер не создастся, обращения к нему вернут nil, и "
                f"функция будет молча не работать на всех устройствах.")
            continue
        try:
            expires = dt.date.fromisoformat(gap["expires"])
        except ValueError:
            problems.append(f"{group}: KNOWN_GAPS.expires не дата ISO")
            continue
        if expires < today:
            problems.append(f"{group}: срок известного пробела истёк {gap['expires']} — заведите "
                            f"группу в портале или удалите код, который её использует")
            continue
        tolerated.append((group, gap, (expires - today).days))

    for group, gap, left in tolerated:
        print(f"    ИЗВЕСТНЫЙ ПРОБЕЛ: {group} — до {gap['expires']} (осталось {left} дн.)")
        print(f"      {gap['why']}")

    print()
    if problems:
        print(f"ПРОВАЛЕНО: {len(problems)}", file=sys.stderr)
        for p in problems:
            print(f"::error::  {p}", file=sys.stderr)
        return 1
    if tolerated:
        print(f"ок: новых расхождений нет; известных пробелов {len(tolerated)}, все с датой")
        return 0
    print("ВСЁ ЧИСТО: каждая используемая App Group объявлена")
    return 0


if __name__ == "__main__":
    sys.exit(main())
