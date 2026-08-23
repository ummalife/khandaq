#!/usr/bin/env python3
"""The bundled inventory must not claim a platform whose versions it does not pin.

KHANDAQ (internal audit 2026-08-22, #20). bundled-deps.json listed 13 components as shipping on
macOS while every pin_file it names is a Windows cross-build download script. The SBOM is generated
from this file, so the claim travelled: a reader — or the next audit — would take `qt@5.12.12` as a
statement about the macOS build, when the macOS build takes Qt from Homebrew at whatever version the
CI image happened to have.

Being wrong about which platform a version describes is worse than saying nothing, because it reads
as coverage. This does not demand that macOS and Linux be pinned — that is real work, recorded as a
gap in platform_sources — it demands that the file not assert what it has not checked.

    scripts/check-bundled-deps-platforms.py
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEPS = ROOT / "khandaq-desktop" / "buildscripts" / "bundled-deps.json"


def main() -> int:
    if not DEPS.is_file():
        print(f"::error::нет {DEPS.relative_to(ROOT)}", file=sys.stderr)
        return 1
    d = json.loads(DEPS.read_text(encoding="utf-8"))

    sources = d.get("platform_sources")
    if not sources:
        print("::error::нет раздела platform_sources — файл не говорит, откуда берутся версии по "
              "платформам, а значит любое поле platforms в нём непроверяемо", file=sys.stderr)
        return 1

    pinned_platforms = {k for k, v in sources.items()
                        if isinstance(v, dict) and v.get("pinned") is True}
    unpinned = {k for k, v in sources.items()
                if isinstance(v, dict) and v.get("pinned") is False}
    for plat in unpinned:
        if not str(sources[plat].get("gap", "")).strip():
            print(f"::error::platform_sources.{plat}: pinned=false и не написано, что нужно сделать",
                  file=sys.stderr)
            return 1

    problems = []
    claims = 0
    for c in d.get("components", []):
        name = c.get("name", "?")
        governs = set(c.get("pin_governs") or [])
        if "pin_governs" not in c:
            problems.append(f"{name}: нет поля pin_governs — неизвестно, что закрепляет его pin_file")
            continue
        for plat in c.get("platforms") or []:
            claims += 1
            if plat in governs:
                continue
            if plat in unpinned:
                continue  # честно объявленный пробел
            problems.append(
                f"{name}: заявлена платформа {plat!r}, но pin_file её не закрепляет и в "
                f"platform_sources она не отмечена как незакреплённая")

    print(f"==> Компонентов {len(d.get('components', []))}, заявлений о платформах {claims}; "
          f"закреплено: {', '.join(sorted(pinned_platforms)) or '—'}; "
          f"честно незакреплено: {', '.join(sorted(unpinned)) or '—'}")
    if problems:
        print(f"\nПРОВАЛЕНО: {len(problems)}", file=sys.stderr)
        for p in problems:
            print(f"::error::  {p}", file=sys.stderr)
        return 1
    print("ВСЁ ЧИСТО: инвентарь не утверждает про платформы больше, чем проверяет")
    return 0


if __name__ == "__main__":
    sys.exit(main())
