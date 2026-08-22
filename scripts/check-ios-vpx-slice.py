#!/usr/bin/env python3
"""The tracked vpx binary must be the SIMULATOR slice.

KHANDAQ (re-audit 2026-08-22). This has now dirtied the tree twice, and each time it looked like a
deliberate change in a diff: khandaq-ios/local_pod_repo/toxcore/ios/vpx.framework/vpx is a tracked
file that a copy phase in project.pbxproj OVERWRITES with vpx-device on every device build, and with
vpx-simulator on every simulator build. Nobody edits it; the build does, silently, and `git add -A`
then sweeps it into whatever commit is being made.

Shipping the wrong slice does not fail to build — it fails to LINK, on somebody else's machine,
against an architecture that is not there. So the committed value is pinned here: it is the
simulator slice, byte for byte, and a device build that leaves its own slice behind fails this
check instead of reaching a reviewer as an innocuous "Bin 1673160 -> 1671368 bytes".
"""
from __future__ import annotations

import hashlib
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
FW = ROOT / "khandaq-ios/local_pod_repo/toxcore/ios/vpx.framework"


def sha(p: Path) -> str:
    return hashlib.sha256(p.read_bytes()).hexdigest()


def main() -> int:
    tracked, sim, dev = FW / "vpx", FW / "vpx-simulator", FW / "vpx-device"
    for p in (tracked, sim, dev):
        if not p.is_file():
            print(f"::error::{p.relative_to(ROOT)} отсутствует", file=sys.stderr)
            return 1
    t, s, d = sha(tracked), sha(sim), sha(dev)
    if t == s:
        print(f"ок: отслеживаемый vpx — это симуляторный слайс ({t[:16]}…)")
        return 0
    which = "слайс для УСТРОЙСТВА" if t == d else "неизвестный слайс"
    print(f"::error::vpx.framework/vpx сейчас {which} ({t[:16]}…), а должен совпадать с "
          f"vpx-simulator ({s[:16]}…). Это перезаписала сборка, а не человек — верните файл: "
          f"git checkout -- khandaq-ios/local_pod_repo/toxcore/ios/vpx.framework/vpx",
          file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
