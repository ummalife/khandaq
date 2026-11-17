#!/usr/bin/env python3
"""Every copy of toxcore must look a group up on the DHT by its chat id.

KHANDAQ (0.2.57, group search key). m_create_group_connection() gives a group's onion friend a random
key, and upstream onion_client walks the DHT toward that key. Each member of a group announced to, and
searched, a different part of the DHT, and members met only when they happened to ask the same node on
the way. A group with one always-online member (the Khandaq Community founder) could not be joined by
its chat id: on the public DHT a fresh join never found the founder in 5 minutes. With the chat id as
the search key the same join connected in about 30 seconds.

The change only works when the joiner and the members both carry it, so it has to be in all three
clients and in the founder bot:
- desktop: buildscripts/toxcore/toxcore/onion_client.c and both amalgamations;
- iOS: local_pod_repo/toxcore (the founder bot is built from this copy);
- Android: patches/apply_khandaq_group_search_key.py, run by circle_scripts/deps.sh on the cloned
  toxcore for each of the 4 ABIs.

This check holds the change in every copy, and proves that the Android script turns the pinned upstream
file into exactly the desktop copy (the desktop file without the change IS the pinned upstream file).

    scripts/check-group-search-key.py
"""
from __future__ import annotations

import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

DESKTOP = "khandaq-desktop/buildscripts/toxcore/toxcore/onion_client.c"
COPIES = (
    ("desktop", DESKTOP),
    ("desktop amalgamation", "khandaq-desktop/buildscripts/toxcore/amalgamation/toxcore_amalgamation.c"),
    ("desktop amalgamation (no toxav)",
     "khandaq-desktop/buildscripts/toxcore/amalgamation/toxcore_amalgamation_no_toxav.c"),
    ("iOS", "khandaq-ios/local_pod_repo/toxcore/toxcore/toxcore/onion_client.m"),
)
ANDROID_SCRIPT = "khandaq-android-trifa/patches/apply_khandaq_group_search_key.py"
ANDROID_DEPS = "khandaq-android-trifa/circle_scripts/deps.sh"
ANDROID_ABIS = 4

HELPER_BODY = "    return onion_friend->is_groupchat ? onion_friend->gc_public_key : onion_friend->real_public_key;\n"
HELPER_SIGNATURE = "static const uint8_t *onion_friend_search_key(const Onion_Friend *onion_friend)\n"
REQUEST_NEW = "ping_id, onion_friend_search_key(onion_friend),\n"
REQUEST_OLD = "ping_id, onion_friend->real_public_key,\n"
REFERENCE_NEW = "        reference_id = onion_friend_search_key(&onion_c->friends_list[num - 1]);\n"
REFERENCE_OLD = "        reference_id = onion_c->friends_list[num - 1].real_public_key;\n"


def check_copy(label: str, text: str) -> list[str]:
    problems = []
    if text.count(HELPER_SIGNATURE) != 1 or text.count(HELPER_BODY) != 1:
        problems.append(f"{label}: onion_friend_search_key() is missing or does not prefer the chat id of a group")
    if text.count(REQUEST_NEW) != 2 or REQUEST_OLD in text:
        problems.append(f"{label}: client_send_announce_request() must search by onion_friend_search_key() "
                        f"in both of its requests (found {text.count(REQUEST_NEW)}, "
                        f"{text.count(REQUEST_OLD)} still by the random key)")
    if text.count(REFERENCE_NEW) != 2 or REFERENCE_OLD in text:
        problems.append(f"{label}: client_add_to_list() and client_ping_nodes() must sort the nodes by "
                        f"onion_friend_search_key() (found {text.count(REFERENCE_NEW)}, "
                        f"{text.count(REFERENCE_OLD)} still by the random key)")
    return problems


def without_change(text: str) -> str:
    """The desktop onion_client.c as the pinned upstream has it: helper and call sites reverted."""
    start = text.index("\n/** KHANDAQ (group search key)")
    end = text.index(HELPER_BODY, start) + len(HELPER_BODY) + len("}\n")
    text = text[:start] + text[end:]
    return text.replace(REQUEST_NEW, REQUEST_OLD).replace(REFERENCE_NEW, REFERENCE_OLD)


def check_android(desktop_text: str) -> list[str]:
    problems = []
    deps = (ROOT / ANDROID_DEPS).read_text(encoding="utf-8", errors="replace")
    wired = deps.count("python3 /root/work/patches/apply_khandaq_group_search_key.py toxcore || exit 1")
    if wired != ANDROID_ABIS:
        problems.append(f"Android: {ANDROID_DEPS} runs the group search key patch at {wired} site(s), "
                        f"expected one per ABI ({ANDROID_ABIS})")

    script = ROOT / ANDROID_SCRIPT
    if not script.is_file():
        return problems + [f"Android: {ANDROID_SCRIPT} is missing"]
    with tempfile.TemporaryDirectory() as tmp:
        tree = Path(tmp) / "toxcore"
        tree.mkdir()
        target = tree / "onion_client.c"
        target.write_text(without_change(desktop_text), encoding="utf-8")
        result = subprocess.run([sys.executable, "-I", str(script), str(tree)],
                                capture_output=True, text=True, check=False)
        if result.returncode != 0:
            return problems + [f"Android: {ANDROID_SCRIPT} failed on the pinned upstream file: "
                               f"{result.stderr.strip()}"]
        if target.read_text(encoding="utf-8") != desktop_text:
            problems.append(f"Android: {ANDROID_SCRIPT} does not produce the desktop onion_client.c "
                            "from the pinned upstream file, so Android would search by a different key")
    return problems


def main() -> int:
    problems: list[str] = []
    for label, rel in COPIES:
        path = ROOT / rel
        if not path.is_file():
            problems.append(f"{label}: {rel} is missing")
            continue
        problems += check_copy(label, path.read_text(encoding="utf-8", errors="replace"))

    desktop_text = (ROOT / DESKTOP).read_text(encoding="utf-8", errors="replace")
    if not problems:
        problems += check_android(desktop_text)

    if problems:
        print("A copy of toxcore does not look groups up by their chat id:")
        for problem in problems:
            print(f"  - {problem}")
        return 1
    print(f"group search key: present in {len(COPIES)} toxcore copies and in the Android build "
          f"({ANDROID_ABIS} ABIs)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
