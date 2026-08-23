#!/usr/bin/env python3
"""No client may ship a DHT anchor the registry has retired.

KHANDAQ (deep review 2026-08-23, RR3-11). The bootstrap lists are trust anchors: they are the first
peers a client talks to, and a client that trusts a name with no infrastructure behind it is the
shape an eclipse attack is built from. `config/khandaq_bootstrap_nodes.json` records which
Khandaq-owned nodes are still active — and nothing checked that the four shipped lists agreed with
it. Three retired anchors (bootstrap1..3.khandaq.org, which no longer resolve) were still in the aTox
client's list, and `sync-android-bootstrap-nodes.py` put them back into the TRIfA client on every run
because it filtered on key validity and never looked at status.

Neither is visible by reading any one file. That is what this is for. The lists change through
scripts that no workflow calls, so this is the only gate they pass at all.

    scripts/check-bootstrap-nodes.py
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
REGISTRY = ROOT / "config" / "khandaq_bootstrap_nodes.json"

# The four lists that actually reach users, one per client line.
CLIENTS = (
    ("TRIfA (Android)", "khandaq-android-trifa/android-refimpl-app/app/src/main/java/"
                        "com/zoffcc/applications/trifa/BootstrapNodeEntryDB.java"),
    ("aTox (Android)", "khandaq-android/atox/src/main/res/raw/nodes.json"),
    ("desktop", "khandaq-desktop/res/nodes.json"),
    ("iOS", "khandaq-ios/local_pod_repo/objcTox/Classes/Public/Manager/nodes.json"),
)

KEY_RE = re.compile(r"\b([0-9A-Fa-f]{64})\b")


def main() -> int:
    if not REGISTRY.is_file():
        print(f"::error::нет {REGISTRY.relative_to(ROOT)} — не с чем сверять якоря", file=sys.stderr)
        return 1
    reg = json.loads(REGISTRY.read_text(encoding="utf-8"))
    owned = reg.get("khandaq_owned_nodes", [])
    if not owned:
        print("::error::в реестре нет ни одной ноды Khandaq — проверка выродилась бы в пустую",
              file=sys.stderr)
        return 1

    retired = {n["public_key"].upper(): n.get("host", "?")
               for n in owned if n.get("status", "active") != "active"}
    active = {n["public_key"].upper(): n.get("host", "?")
              for n in owned if n.get("status", "active") == "active"}
    print(f"==> Реестр: активных нод Khandaq {len(active)}, отозванных {len(retired)}")

    problems: list[str] = []
    for label, rel in CLIENTS:
        path = ROOT / rel
        if not path.is_file():
            problems.append(f"{label}: нет файла {rel} — список якорей пропал из сборки?")
            continue
        text = path.read_text(encoding="utf-8", errors="replace")
        keys = {k.upper() for k in KEY_RE.findall(text)}
        if not keys:
            problems.append(f"{label}: в {rel} не найдено ни одного 64-символьного ключа — "
                            f"формат изменился, и проверка перестала что-либо проверять")
            continue
        bad = sorted(keys & set(retired))
        for k in bad:
            problems.append(f"{label}: ключ отозванной ноды {retired[k]} ({k[:16]}…) всё ещё в "
                            f"{rel}. Имя не резолвится, а клиент ходит к нему первым.")
        mine = keys & (set(retired) | set(active))
        print(f"    {label}: ключей {len(keys)}, из них наших {len(mine)}, отозванных {len(bad)}")

    print()
    if problems:
        print(f"ПРОВАЛЕНО: {len(problems)}", file=sys.stderr)
        for p in problems:
            print(f"::error::  {p}", file=sys.stderr)
        print("::error::Уберите отозванные якоря из списка клиента и перезапустите "
              "scripts/sync-all-bootstrap-nodes.py / sync-android-bootstrap-nodes.py.",
              file=sys.stderr)
        return 1
    print("ВСЁ ЧИСТО: ни один клиент не несёт отозванных якорей DHT")
    return 0


if __name__ == "__main__":
    sys.exit(main())
