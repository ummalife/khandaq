#!/usr/bin/env python3
"""Every copy of toxcore must keep, take and acknowledge what a group peer sends out of order.

KHANDAQ (0.2.59, group files). QA live of 0.2.57 and 0.2.58: a 6 MiB file to a group never reached a phone that
lost its network for 10 s while the file went out; its sender timed it out ("Send array stuck") about a minute
later, after sending it 46.8 MB. The receiving side of toxcore was why: a fragment that came out of order was
dropped, a fragment taken was not acknowledged, a packet kept for later was handed on at one per tox_iterate and
acknowledged as message 0, and the copy of a kept packet that then came in order stayed in the receive array. The
sending side is #201: the oldest packet a peer has not acknowledged goes again every second.

Every client receives group files, so every client has to carry both changes:
- desktop: buildscripts/toxcore/toxcore/group_{chats,connection}.c and both amalgamations;
- iOS: local_pod_repo/toxcore (group_chats.m, group_connection.m);
- Android: patches/apply_khandaq_media_resend.py (#201) and patches/apply_khandaq_gc_recv_gap.py, run by
  circle_scripts/deps.sh on the cloned toxcore for each of the 4 ABIs.

This check holds the text of both changes, as the Android scripts carry it, in every copy, and the lines that make
them work; proves that the two Android scripts turn the desktop files without the changes into exactly the desktop
files; and keeps the toxcore test that runs them on two local toxes (auto_tests/group_recv_gap_test.c) in the
desktop build.

    scripts/check-gc-recv-gap.py
    scripts/check-gc-recv-gap.py --self-test    # every check must catch the mutant made for it (CI)
"""
from __future__ import annotations

import ast
import subprocess
import sys
import tempfile
from collections.abc import Callable
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

DESKTOP_DIR = "khandaq-desktop/buildscripts/toxcore/toxcore"
IOS_DIR = "khandaq-ios/local_pod_repo/toxcore/toxcore/toxcore"
AMALGAMATION_DIR = "khandaq-desktop/buildscripts/toxcore/amalgamation"
DESKTOP_CONNECTION = f"{DESKTOP_DIR}/group_connection.c"
DESKTOP_CHATS = f"{DESKTOP_DIR}/group_chats.c"
COPIES = (
    ("desktop", (DESKTOP_CONNECTION, DESKTOP_CHATS)),
    ("desktop amalgamation", (f"{AMALGAMATION_DIR}/toxcore_amalgamation.c",)),
    ("desktop amalgamation (no toxav)", (f"{AMALGAMATION_DIR}/toxcore_amalgamation_no_toxav.c",)),
    ("iOS", (f"{IOS_DIR}/group_connection.m", f"{IOS_DIR}/group_chats.m")),
)

ANDROID_PATCHES = "khandaq-android-trifa/patches"
RECV_GAP_SCRIPT = f"{ANDROID_PATCHES}/apply_khandaq_gc_recv_gap.py"
RESEND_SCRIPT = f"{ANDROID_PATCHES}/apply_khandaq_media_resend.py"
RECV_GAP_MARKER = "KHANDAQ (0.2.59, group files)"
RESEND_MARKER = "Khandaq fix (#201)"
ANDROID_DEPS = "khandaq-android-trifa/circle_scripts/deps.sh"
ANDROID_ABIS = 4
ANDROID_RUNS = (
    ("the receive gap patch", "python3 /root/work/patches/apply_khandaq_gc_recv_gap.py toxcore || exit 1"),
    ("the #201 resend patch",
     "python3 /root/work/patches/apply_khandaq_media_resend.py toxcore/group_connection.c || exit 1"),
)

TEST = "khandaq-desktop/buildscripts/toxcore/auto_tests/group_recv_gap_test.c"
TEST_LIST = "khandaq-desktop/buildscripts/toxcore/auto_tests/CMakeLists.txt"
TEST_ENTRY = "auto_test(group_recv_gap)"

# The lines the change works by, each once in every copy: were the Android scripts ever cut from a desktop file
# without one of them, the text check below would hold the copies to that.
ESSENTIALS = (
    ("a packet handed on from the receive array is acknowledged by its own id",
     "    const uint64_t message_id = array_entry->message_id;\n\n"
     "    const bool ret = handle_gc_lossless_helper("),
    ("a fragment kept for its sequence is acknowledged", "    if (frag_ret == 0 || frag_ret == 1) {\n"),
    ("what the receive array keeps is taken in one go", "#define GCC_RECV_ARRAY_TAKE_MAX 512\n"),
    ("the oldest packet not acknowledged goes again every second",
     "        if ((khq_is_head && delta >= 1) || (delta > 1 && is_power_of_2(delta))) {\n"),
)

Read = Callable[[str], "str | None"]


def script_constants(text: str, names: tuple[str, ...], rel: str) -> dict[str, str]:
    """The string constants `names` of an Android patch script, without running it."""
    found = {}
    for node in ast.parse(text).body:
        if isinstance(node, ast.Assign) and len(node.targets) == 1 and isinstance(node.targets[0], ast.Name):
            if node.targets[0].id in names:
                value = ast.literal_eval(node.value)
                if isinstance(value, str):
                    found[node.targets[0].id] = value
    missing = [name for name in names if name not in found]
    if missing:
        raise ValueError(f"{rel} has no {', '.join(missing)}")
    return found


def changes(read: Read) -> list[tuple[str, str, str]]:
    """(what, original, changed) of each edit the two Android scripts make, in the order they make them."""
    resend_text = read(RESEND_SCRIPT)
    gap_text = read(RECV_GAP_SCRIPT)
    if resend_text is None or gap_text is None:
        raise ValueError(f"{RESEND_SCRIPT} or {RECV_GAP_SCRIPT} is missing")
    resend = script_constants(resend_text, ("ORIG", "REPL"), RESEND_SCRIPT)
    gap = script_constants(gap_text, ("ORIG_RECEIVE", "REPL_RECEIVE", "ORIG_FRAGMENT_ACK", "REPL_FRAGMENT_ACK",
                                      "ORIG_KEPT_ACK", "REPL_KEPT_ACK"), RECV_GAP_SCRIPT)
    return [
        ("the oldest packet resent every second (#201)", resend["ORIG"], resend["REPL"]),
        ("the receive side", gap["ORIG_RECEIVE"], gap["REPL_RECEIVE"]),
        ("the acknowledgement of a fragment kept for its sequence", gap["ORIG_FRAGMENT_ACK"],
         gap["REPL_FRAGMENT_ACK"]),
        ("the acknowledgement of a fragment kept out of order", gap["ORIG_KEPT_ACK"], gap["REPL_KEPT_ACK"]),
    ]


def check_copy(label: str, text: str, edits: list[tuple[str, str, str]]) -> list[str]:
    problems = []
    for what, line in ESSENTIALS:
        if text.count(line) != 1:
            problems.append(f"{label}: {what}: expected once, found {text.count(line)} time(s)")
    for what, original, changed in edits:
        if text.count(changed) != 1:
            problems.append(f"{label}: {what} differs from the Android scripts (found {text.count(changed)} copies of "
                            "their text)")
        if original in text:
            problems.append(f"{label}: {what} is still as toxcore had it")
    return problems


def check_copies(read: Read, edits: list[tuple[str, str, str]]) -> tuple[list[str], bool]:
    """The problems of the toxcore copies, and whether the desktop one is good."""
    problems: list[str] = []
    desktop_ok = False
    for label, rels in COPIES:
        texts = [read(rel) for rel in rels]
        missing = [rel for rel, text in zip(rels, texts) if text is None]
        if missing:
            problems += [f"{label}: {rel} is missing" for rel in missing]
            continue
        found = check_copy(label, "".join(text or "" for text in texts), edits)
        problems += found
        if label == "desktop":
            desktop_ok = not found
    return problems, desktop_ok


def check_wiring(read: Read) -> list[str]:
    deps = read(ANDROID_DEPS)
    if deps is None:
        return [f"Android: {ANDROID_DEPS} is missing"]
    problems = []
    for what, run in ANDROID_RUNS:
        sites = deps.count(run)
        if sites != ANDROID_ABIS:
            problems.append(f"Android: {ANDROID_DEPS} runs {what} at {sites} site(s), expected one per ABI "
                            f"({ANDROID_ABIS})")
    return problems


def check_test(read: Read) -> list[str]:
    problems = []
    if read(TEST) is None:
        problems.append(f"toxcore test: {TEST} is missing")
    listed = (read(TEST_LIST) or "").splitlines()
    if TEST_ENTRY not in (line.strip() for line in listed):
        problems.append(f"toxcore test: {TEST_LIST} does not build and run {TEST_ENTRY}")
    return problems


def check_android_scripts(edits: list[tuple[str, str, str]]) -> list[str]:
    """The two Android scripts, run as deps.sh runs them, turn the desktop files without the changes into them."""
    desktop = {"group_connection.c": (ROOT / DESKTOP_CONNECTION).read_text(encoding="utf-8"),
               "group_chats.c": (ROOT / DESKTOP_CHATS).read_text(encoding="utf-8")}
    with tempfile.TemporaryDirectory() as tmp:
        tree = Path(tmp) / "toxcore"
        tree.mkdir()
        for name, text in desktop.items():
            for _what, original, changed in edits:
                text = text.replace(changed, original)
            if RECV_GAP_MARKER in text or RESEND_MARKER in text:
                return [f"Android: cannot take the changes out of the desktop {name} to compare"]
            (tree / name).write_text(text, encoding="utf-8")
        for script, target in ((RESEND_SCRIPT, tree / "group_connection.c"), (RECV_GAP_SCRIPT, tree)):
            result = subprocess.run([sys.executable, "-I", str(ROOT / script), str(target)], capture_output=True,
                                    text=True, check=False)
            if result.returncode != 0:
                return [f"Android: {script} failed on the desktop files without the change: {result.stderr.strip()}"]
        return [f"Android: {RESEND_SCRIPT} and {RECV_GAP_SCRIPT} do not produce the desktop {name}, so Android would "
                "catch up with a group peer otherwise"
                for name, text in desktop.items() if (tree / name).read_text(encoding="utf-8") != text]


def read_from(root: Path) -> Read:
    def read(rel: str) -> str | None:
        path = root / rel
        return path.read_text(encoding="utf-8", errors="replace") if path.is_file() else None
    return read


def problems_of(read: Read) -> tuple[list[str], list[tuple[str, str, str]], bool]:
    edits = changes(read)
    problems, desktop_ok = check_copies(read, edits)
    return problems + check_wiring(read) + check_test(read), edits, desktop_ok


def self_test() -> int:
    """Each check must catch the mutant made for it, and the files as they are must pass."""
    read = read_from(ROOT)
    problems, _edits, _desktop_ok = problems_of(read)
    if problems:
        print("FAIL: check-gc-recv-gap.py self-test: the files as they are do not pass:", file=sys.stderr)
        for problem in problems:
            print(f"  - {problem}", file=sys.stderr)
        return 1

    def mutant(rel: str, old: str, new: str, occurrences: int = 1) -> Read:
        """`rel` with the first of the `occurrences` of `old` in it replaced by `new`."""
        original = read(rel)
        if original is None or original.count(old) != occurrences:
            raise AssertionError(f"self-test: `{old.strip()}` is not {occurrences} time(s) in {rel}")
        changed = original.replace(old, new, 1)
        return lambda path: changed if path == rel else read(path)

    gap_run = ANDROID_RUNS[0][1]
    mutants = (
        ("iOS acknowledges a fragment kept for its sequence no more",
         mutant(f"{IOS_DIR}/group_chats.m", "if (frag_ret == 0 || frag_ret == 1) {", "if (frag_ret == 0) {"),
         "iOS:"),
        ("the amalgamation acknowledges a packet from the receive array as message 0 again",
         mutant(f"{AMALGAMATION_DIR}/toxcore_amalgamation.c", "gc_send_message_ack(chat, gconn, message_id, GR_ACK_RECV);"
                "\n\n    gcc_set_recv_message_id(gconn, gconn->received_message_id + 1);",
                "gc_send_message_ack(chat, gconn, array_entry->message_id, GR_ACK_RECV);"
                "\n\n    gcc_set_recv_message_id(gconn, gconn->received_message_id + 1);"),
         "desktop amalgamation:"),
        ("the amalgamation without toxav takes one kept packet per iteration",
         mutant(f"{AMALGAMATION_DIR}/toxcore_amalgamation_no_toxav.c", "#define GCC_RECV_ARRAY_TAKE_MAX 512",
                "#define GCC_RECV_ARRAY_TAKE_MAX 1"),
         "desktop amalgamation (no toxav):"),
        ("the desktop resends the oldest packet at the resend times only",
         mutant(DESKTOP_CONNECTION, "if ((khq_is_head && delta >= 1) || (delta > 1 && is_power_of_2(delta))) {",
                "if (delta > 1 && is_power_of_2(delta)) {"),
         "desktop:"),
        ("Android runs the receive gap patch for 3 ABIs",
         mutant(ANDROID_DEPS, gap_run, "true", ANDROID_ABIS), "Android:"),
        ("the desktop build drops the toxcore test",
         mutant(TEST_LIST, TEST_ENTRY, ""), "toxcore test:"),
    )
    failed = []
    for name, mutant_read, expected in mutants:
        found, _edits, _ok = problems_of(mutant_read)
        if not any(problem.startswith(expected) for problem in found):
            failed.append(f"{name}: not caught (got {found or 'no problem'})")

    if failed:
        print("FAIL: check-gc-recv-gap.py self-test:", file=sys.stderr)
        for line in failed:
            print(f"  - {line}", file=sys.stderr)
        return 1
    print(f"ok: check-gc-recv-gap.py self-test, {len(mutants)} mutants caught")
    return 0


def main() -> int:
    problems, edits, desktop_ok = problems_of(read_from(ROOT))
    if desktop_ok:
        problems += check_android_scripts(edits)

    if problems:
        print("A copy of toxcore does not keep, take or acknowledge what a group peer sends out of order, or does not "
              "resend the oldest packet every second:")
        for problem in problems:
            print(f"  - {problem}")
        return 1
    print(f"gc receive gap: {len(COPIES)} toxcore copies keep, take and acknowledge what a group peer sends out of "
          f"order and resend the oldest packet every second, as does the Android build ({ANDROID_ABIS} ABIs); the "
          "toxcore test is in")
    return 0


if __name__ == "__main__":
    if sys.argv[1:] == ["--self-test"]:
        sys.exit(self_test())
    if sys.argv[1:]:
        print("usage: check-gc-recv-gap.py [--self-test]", file=sys.stderr)
        sys.exit(2)
    sys.exit(main())
