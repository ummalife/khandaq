#!/usr/bin/env python3
"""Every copy of toxcore must leave an FTV2 offer alone until the receiver accepts it.

KHANDAQ (0.2.57, FTV2 stale accept). do_reqchunk_filecb() sends SEEK + ACCEPT to the sender of a receiving
FTV2 transfer that has had no data for STALE_CYCLES_ON_FTV2_RECEIVER iterations, in case the receiver's
ACCEPT was lost while the sender was offline. Upstream did that for every receiving FTV2 transfer. An offer
still waiting for the person was started by it: the sender sent the whole file, and the receiver dropped
every chunk of a transfer it had not accepted, once a stale period (about a minute on Android) for as long
as the offer waited. QA run 7, Android to Android: a file the person had refused went out twice, 20 MB
each time. A transfer the receiver had paused was resumed by it.

The step now runs only for a transfer the receiver accepted and has not paused, see
ftv2_receiver_expects_data(). The stale ACCEPT comes from the receiver, so a person is protected by the
toxcore of their own client, whatever the sender runs, and every client has to carry the change:
- desktop: buildscripts/toxcore/toxcore/Messenger.c and both amalgamations;
- iOS: local_pod_repo/toxcore;
- Android: patches/apply_khandaq_ftv2_stale_accept.py, run by circle_scripts/deps.sh on the cloned
  toxcore for each of the 4 ABIs.

This check holds the change in every copy, keeps the toxcore test that runs it on two local toxes
(auto_tests/ftv2_stale_accept_test.c) in the desktop build, and proves that the Android script turns the
pinned upstream file into exactly the desktop copy (the desktop file without the change IS the pinned
upstream file).

    scripts/check-ftv2-stale-accept.py
"""
from __future__ import annotations

import re
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

DESKTOP = "khandaq-desktop/buildscripts/toxcore/toxcore/Messenger.c"
COPIES = (
    ("desktop", DESKTOP),
    ("desktop amalgamation", "khandaq-desktop/buildscripts/toxcore/amalgamation/toxcore_amalgamation.c"),
    ("desktop amalgamation (no toxav)",
     "khandaq-desktop/buildscripts/toxcore/amalgamation/toxcore_amalgamation_no_toxav.c"),
    ("iOS", "khandaq-ios/local_pod_repo/toxcore/toxcore/toxcore/Messenger.m"),
)
ANDROID_SCRIPT = "khandaq-android-trifa/patches/apply_khandaq_ftv2_stale_accept.py"
ANDROID_DEPS = "khandaq-android-trifa/circle_scripts/deps.sh"
ANDROID_ABIS = 4
TEST = "khandaq-desktop/buildscripts/toxcore/auto_tests/ftv2_stale_accept_test.c"
TEST_LIST = "khandaq-desktop/buildscripts/toxcore/auto_tests/CMakeLists.txt"
TEST_ENTRY = "auto_test(ftv2_stale_accept)"

HELPER_START = "/** KHANDAQ (FTV2 stale accept)"
HELPER_SIGNATURE = "static bool ftv2_receiver_expects_data(const struct File_Transfers *ft)\n"
HELPER_BODY = "    return ft->status == FILESTATUS_TRANSFERRING && (ft->paused & FILE_PAUSE_US) == 0;\n"
STALE_TEST = ("ft->file_receiver_last_received_chunk_this_many_iterations_ago"
              " > (uint32_t)STALE_CYCLES_ON_FTV2_RECEIVER) {\n")
STALE_OLD = "                    if (" + STALE_TEST
# The guarded step: a transfer owed no data restarts its count, and only the others can go stale.
GUARDED = re.compile(
    r"                    if \(!ftv2_receiver_expects_data\(ft\)\) \{\n"
    r"(?:[ \t]*//[^\n]*\n)*"
    r"[ \t]*ft->file_receiver_last_received_chunk_this_many_iterations_ago = 0;\n"
    r"                    \} else if \(" + re.escape(STALE_TEST))


def check_copy(label: str, text: str) -> list[str]:
    problems = []
    if text.count(HELPER_SIGNATURE) != 1 or text.count(HELPER_BODY) != 1:
        problems.append(f"{label}: ftv2_receiver_expects_data() is missing, or no longer asks for a transfer "
                        "we accepted and have not paused")
    guarded = len(GUARDED.findall(text))
    if guarded != 1 or STALE_OLD in text:
        problems.append(f"{label}: do_reqchunk_filecb() must run its stale step only when "
                        f"ftv2_receiver_expects_data() (found {guarded} guarded step(s), "
                        f"{text.count(STALE_OLD)} unguarded)")
    return problems


def without_change(text: str) -> str:
    """The desktop Messenger.c as the pinned upstream has it: helper and guard reverted."""
    start = text.index(HELPER_START)
    end = text.index(HELPER_BODY, start) + len(HELPER_BODY) + len("}\n\n")
    text = text[:start] + text[end:]
    return GUARDED.sub(lambda _match: STALE_OLD, text)


def check_test() -> list[str]:
    problems = []
    if not (ROOT / TEST).is_file():
        problems.append(f"toxcore test: {TEST} is missing")
    listed = (ROOT / TEST_LIST).read_text(encoding="utf-8", errors="replace").splitlines()
    if TEST_ENTRY not in (line.strip() for line in listed):
        problems.append(f"toxcore test: {TEST_LIST} does not build and run {TEST_ENTRY}")
    return problems


def check_android(desktop_text: str) -> list[str]:
    problems = []
    deps = (ROOT / ANDROID_DEPS).read_text(encoding="utf-8", errors="replace")
    wired = deps.count("python3 /root/work/patches/apply_khandaq_ftv2_stale_accept.py toxcore || exit 1")
    if wired != ANDROID_ABIS:
        problems.append(f"Android: {ANDROID_DEPS} runs the FTV2 stale accept patch at {wired} site(s), "
                        f"expected one per ABI ({ANDROID_ABIS})")

    script = ROOT / ANDROID_SCRIPT
    if not script.is_file():
        return problems + [f"Android: {ANDROID_SCRIPT} is missing"]
    with tempfile.TemporaryDirectory() as tmp:
        tree = Path(tmp) / "toxcore"
        tree.mkdir()
        target = tree / "Messenger.c"
        target.write_text(without_change(desktop_text), encoding="utf-8")
        result = subprocess.run([sys.executable, "-I", str(script), str(tree)],
                                capture_output=True, text=True, check=False)
        if result.returncode != 0:
            return problems + [f"Android: {ANDROID_SCRIPT} failed on the pinned upstream file: "
                               f"{result.stderr.strip()}"]
        if target.read_text(encoding="utf-8") != desktop_text:
            problems.append(f"Android: {ANDROID_SCRIPT} does not produce the desktop Messenger.c from the "
                            "pinned upstream file, so Android would treat a waiting offer differently")
    return problems


def main() -> int:
    problems: list[str] = []
    desktop_ok = False
    for label, rel in COPIES:
        path = ROOT / rel
        if not path.is_file():
            problems.append(f"{label}: {rel} is missing")
            continue
        found = check_copy(label, path.read_text(encoding="utf-8", errors="replace"))
        problems += found
        if rel == DESKTOP:
            desktop_ok = not found

    problems += check_test()

    if desktop_ok:
        desktop_text = (ROOT / DESKTOP).read_text(encoding="utf-8", errors="replace")
        problems += check_android(desktop_text)

    if problems:
        print("A copy of toxcore starts an FTV2 offer nobody accepted, or resumes a paused one:")
        for problem in problems:
            print(f"  - {problem}")
        return 1
    print(f"FTV2 stale accept: present in {len(COPIES)} toxcore copies, in the Android build "
          f"({ANDROID_ABIS} ABIs) and in the toxcore test")
    return 0


if __name__ == "__main__":
    sys.exit(main())
