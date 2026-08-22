#!/usr/bin/env python3
"""iOS push capabilities must live in the Keychain, and both halves must agree where.

KHANDAQ (re-review 2026-08-22, KQ-08). A per-contact capability is a bearer secret: hold one and you
can make the relay wake that device for that relationship until it is revoked. They were in
UserDefaults — app-private under normal sandboxing, and still a preferences store that lands in
backups and in anything that can read the app container.

Two halves have to stay in step, and they are in different languages and different targets:
KhandaqPush.swift writes them, and the objcTox pod reads them when it publishes a wake URL. A
service string changed on one side only would not fail to build; it would silently publish URLs
with no capability, which looks exactly like a device that has not registered yet.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SWIFT = ROOT / "khandaq-ios/Antidote/KhandaqPush.swift"
POD = ROOT / "khandaq-ios/local_pod_repo/objcTox/Classes/Private/Manager/Submanagers/OCTPushUrlValidator.m"

problems: list[str] = []


def strip_comments(text: str) -> str:
    """Remove // and /* */ comments; string literals are left intact, since they are what is matched."""
    out, i, n = [], 0, len(text)
    while i < n:
        if text.startswith("//", i):
            j = text.find("\n", i)
            i = n if j < 0 else j
        elif text.startswith("/*", i):
            j = text.find("*/", i + 2)
            i = n if j < 0 else j + 2
        elif text[i] in "\"'":
            q = text[i]
            j = i + 1
            while j < n and text[j] != q:
                j += 2 if text[j] == "\\" else 1
            out.append(text[i:j + 1])
            i = j + 1
        else:
            out.append(text[i])
            i += 1
    return "".join(out)


def require(cond: bool, msg: str) -> None:
    if not cond:
        problems.append(msg)


def main() -> int:
    for p in (SWIFT, POD):
        if not p.is_file():
            print(f"::error::{p.relative_to(ROOT)} отсутствует", file=sys.stderr)
            return 1
    swift = SWIFT.read_text(encoding="utf-8")
    pod = POD.read_text(encoding="utf-8")

    sw_service = re.search(r'capKeychainService\s*=\s*"([^"]+)"', swift)
    pod_service = re.search(r'kCapabilityKeychainService\s*=\s*@"([^"]+)"', pod)
    require(sw_service is not None, "в KhandaqPush.swift нет имени сервиса Keychain")
    require(pod_service is not None, "в OCTPushUrlValidator.m нет имени сервиса Keychain")
    if sw_service and pod_service:
        require(sw_service.group(1) == pod_service.group(1),
                f"имена сервиса Keychain разошлись: Swift {sw_service.group(1)!r} vs "
                f"под {pod_service.group(1)!r} — под перестанет находить capability, и URL будут "
                f"публиковаться без неё, что неотличимо от незарегистрированного устройства")

    require("kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly" in swift,
            "класс доступности не AfterFirstUnlockThisDeviceOnly: пуш приходит в фоне, в том числе "
            "до первой разблокировки после перезагрузки, а ThisDeviceOnly держит секрет вне бэкапов")

    # The trap that once logged this app out on every launch: kSecAttrAccessible inside a SEARCH
    # dictionary is a predicate, not an attribute, so it stops matching items stored otherwise.
    #
    # Comments are stripped first. The first version of this check read the prose EXPLAINING the trap
    # and reported it as the trap — a gate that fails on its own documentation is a gate that gets
    # deleted rather than fixed.
    for name, text in (("Swift", strip_comments(swift)), ("под", strip_comments(pod))):
        for m in re.finditer(r"kSecReturnData", text):
            window = text[max(0, m.start() - 400):m.start() + 200]
            require("kSecAttrAccessible" not in window,
                    f"{name}: kSecAttrAccessible внутри поискового запроса — там это предикат, "
                    f"и он перестанет находить ранее сохранённые элементы")

    # Writing a capability back into UserDefaults would undo the whole finding. Removing one is the
    # migration and must stay allowed.
    for m in re.finditer(r"UserDefaults\.standard\.set\(", swift):
        line = swift[:m.start()].count("\n") + 1
        problems.append(f"KhandaqPush.swift:{line}: capability снова пишется в UserDefaults")

    require("migrateFromDefaultsIfNeeded" in swift,
            "нет одноразовой миграции из UserDefaults — на устройствах с прошлой сборкой "
            "capability просто исчезнут, и пуши от этих контактов прекратятся")
    require("removeObject(forKey: account)" in swift,
            "миграция не удаляет старое значение из настроек, а значит секрет остаётся там же, где и был")

    if problems:
        for p in problems:
            print(f"::error::{p}", file=sys.stderr)
        return 1
    print("ок: capability в Keychain (device-only), обе половины смотрят в один сервис, "
          "миграция на месте")
    return 0


if __name__ == "__main__":
    sys.exit(main())
