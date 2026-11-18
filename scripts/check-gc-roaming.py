#!/usr/bin/env python3
"""Every copy of toxcore must send to a group peer at the address its packets come from now.

KHANDAQ (0.2.60, group roaming). QA live of 0.2.59: the network of a phone went away for 10 s while a group file went
out to it, and came back with another address. The file stood still for a minute, and the phone caught up with it
about two and a half minutes after its network came back. The phone kept its group session and went on sending from
its new address; each of its packets counted as a direct one, so its connection stayed direct and the sender sent it
everything over UDP to the old address alone. The phone heard nothing of the sender until it timed it out (58 s) and
came back through a new handshake.

A copy of toxcore now takes the address a packet of a peer came from over UDP as the peer's when the packet is a
lossless message it has not had yet, or when what it sends the peer goes unanswered; and a peer it has heard nothing of
for a ping and a half is sent a peer info request with the next ping, a lossless packet that moves the peer to where
we are now. Nothing changes on the wire.

Every client is in groups, so every client has to carry the change:
- desktop: buildscripts/toxcore/toxcore/group_{chats.c,connection.c,connection.h} and both amalgamations;
- iOS: local_pod_repo/toxcore (group_chats.m, group_connection.m, group_connection.h);
- Android: patches/apply_khandaq_gc_roaming.py, run by circle_scripts/deps.sh on the cloned toxcore for each of the 4
  ABIs.

This check holds the text of the change, as the Android script carries it, in every copy, and the lines that make it
work; proves that the Android script turns the desktop files without the change into exactly the desktop files; and
keeps the toxcore test that runs it on two local toxes whose addresses move (auto_tests/group_roaming_test.c) in the
desktop build.

    scripts/check-gc-roaming.py
    scripts/check-gc-roaming.py --self-test    # every check must catch the mutant made for it (CI)
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

# The files of each copy, by the name of the file the Android script patches.
DESKTOP_FILES = {name: f"{DESKTOP_DIR}/{name}" for name in ("group_connection.h", "group_connection.c",
                                                             "group_chats.c")}
COPIES = (
    ("desktop", tuple(DESKTOP_FILES.values())),
    ("desktop amalgamation", (f"{AMALGAMATION_DIR}/toxcore_amalgamation.c",)),
    ("desktop amalgamation (no toxav)", (f"{AMALGAMATION_DIR}/toxcore_amalgamation_no_toxav.c",)),
    ("iOS", (f"{IOS_DIR}/group_connection.h", f"{IOS_DIR}/group_connection.m", f"{IOS_DIR}/group_chats.m")),
)

ROAMING_SCRIPT = "khandaq-android-trifa/patches/apply_khandaq_gc_roaming.py"
ROAMING_MARKER = "KHANDAQ (0.2.60, group roaming)"
ANDROID_DEPS = "khandaq-android-trifa/circle_scripts/deps.sh"
ANDROID_ABIS = 4
ROAMING_RUN = "python3 /root/work/patches/apply_khandaq_gc_roaming.py toxcore || exit 1"
RECV_GAP_RUN = "python3 /root/work/patches/apply_khandaq_gc_recv_gap.py toxcore || exit 1"

TEST_DIR = "khandaq-desktop/buildscripts/toxcore/auto_tests"
TEST_LIST = f"{TEST_DIR}/CMakeLists.txt"
TEST = (f"{TEST_DIR}/group_roaming_test.c", "auto_test(group_roaming)")

# The edits of the Android script, by file: (what, constant of the original text, constant of the changed text).
EDITS = (
    ("group_connection.h", "the declarations of the two checks", "ORIG_DECLARATIONS", "REPL_DECLARATIONS"),
    ("group_connection.c", "the time an unacknowledged message may wait", "ORIG_UNANSWERED_TIMEOUT",
     "REPL_UNANSWERED_TIMEOUT"),
    ("group_connection.c", "the two checks", "ORIG_CHECKS", "REPL_CHECKS"),
    ("group_chats.c", "follow_moved_gc_peer and the lossless handler's address", "ORIG_LOSSLESS_HEAD",
     "REPL_LOSSLESS_HEAD"),
    ("group_chats.c", "a new lossless message from another address", "ORIG_LOSSLESS_FOLLOW", "REPL_LOSSLESS_FOLLOW"),
    ("group_chats.c", "the lossy handler's address", "ORIG_LOSSY_HEAD", "REPL_LOSSY_HEAD"),
    ("group_chats.c", "a lossy packet from another address", "ORIG_LOSSY_FOLLOW", "REPL_LOSSY_FOLLOW"),
    ("group_chats.c", "a lossless packet over TCP", "ORIG_TCP_LOSSLESS", "REPL_TCP_LOSSLESS"),
    ("group_chats.c", "a lossy packet over TCP", "ORIG_TCP_LOSSY", "REPL_TCP_LOSSY"),
    ("group_chats.c", "a lossless packet over UDP", "ORIG_UDP_LOSSLESS", "REPL_UDP_LOSSLESS"),
    ("group_chats.c", "a lossy packet over UDP", "ORIG_UDP_LOSSY", "REPL_UDP_LOSSY"),
    ("group_chats.c", "the silence after which a peer is asked for its info", "ORIG_PROBE_TIMEOUT",
     "REPL_PROBE_TIMEOUT"),
    ("group_chats.c", "the peer info request to a silent peer", "ORIG_PROBE", "REPL_PROBE"),
)

# The lines the change works by, each once in every copy: were the Android script ever cut from a desktop file
# without one of them, the text check below would hold the copies to that.
ESSENTIALS = (
    ("a new lossless message from another address moves us to it",
     "        follow_moved_gc_peer(chat, gconn, ipp, gcc_message_is_new(gconn, message_id));\n"),
    ("a lossy packet from another address moves us when what we send goes unanswered",
     "        follow_moved_gc_peer(chat, gconn, ipp, false);\n"),
    ("a lossless packet over UDP says where it came from",
     "            ret = handle_gc_lossless_packet(c, chat, sender_pk, payload, payload_len, true, ipp, userdata);\n"),
    ("a lossy packet over UDP says where it came from",
     "            ret = handle_gc_lossy_packet(c, chat, sender_pk, payload, payload_len, true, ipp, userdata);\n"),
    ("what we send a peer goes unanswered after 3 s", "#define GCC_UNANSWERED_TIMEOUT 3\n"),
    ("a peer silent for a ping and a half is sent a peer info request",
     "            if (mono_time_is_timeout(chat->mono_time, gconn->last_received_packet_time, "
     "GC_SILENT_PEER_PROBE_TIMEOUT)) {\n"
     "                send_gc_peer_info_request(chat, gconn);\n"),
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


def changes(read: Read) -> list[tuple[str, str, str, str]]:
    """(file, what, original, changed) of each edit the Android script makes, in the order it makes them."""
    text = read(ROAMING_SCRIPT)
    if text is None:
        raise ValueError(f"{ROAMING_SCRIPT} is missing")
    names = tuple(name for _file, _what, orig, repl in EDITS for name in (orig, repl))
    constants = script_constants(text, names, ROAMING_SCRIPT)
    return [(name, what, constants[orig], constants[repl]) for name, what, orig, repl in EDITS]


def check_copy(label: str, text: str, edits: list[tuple[str, str, str, str]]) -> list[str]:
    problems = []
    for what, line in ESSENTIALS:
        if text.count(line) != 1:
            problems.append(f"{label}: {what}: expected once, found {text.count(line)} time(s)")
    for _name, what, original, changed in edits:
        if text.count(changed) != 1:
            problems.append(f"{label}: {what} differs from the Android script (found {text.count(changed)} copies of "
                            "its text)")
        # Most edits add lines around what toxcore had, so the original text is inside the changed one.
        if original in text.replace(changed, ""):
            problems.append(f"{label}: {what} is still as toxcore had it")
    return problems


def check_copies(read: Read, edits: list[tuple[str, str, str, str]]) -> tuple[list[str], bool]:
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
    sites = deps.count(ROAMING_RUN)
    if sites != ANDROID_ABIS:
        problems.append(f"Android: {ANDROID_DEPS} runs the roaming patch at {sites} site(s), expected one per ABI "
                        f"({ANDROID_ABIS})")
    # After the receive gap patch of the same ABI, as the desktop files were made.
    gaps = [i for i in range(len(deps)) if deps.startswith(RECV_GAP_RUN, i)]
    roams = [i for i in range(len(deps)) if deps.startswith(ROAMING_RUN, i)]
    if len(gaps) == len(roams) and any(not (gap < roam and (k + 1 == len(gaps) or roam < gaps[k + 1]))
                                       for k, (gap, roam) in enumerate(zip(gaps, roams))):
        problems.append(f"Android: {ANDROID_DEPS} runs the roaming patch before the receive gap patch of its ABI")
    return problems


def check_test(read: Read) -> list[str]:
    problems = []
    test, entry = TEST
    if read(test) is None:
        problems.append(f"toxcore test: {test} is missing")
    if entry not in [line.strip() for line in (read(TEST_LIST) or "").splitlines()]:
        problems.append(f"toxcore test: {TEST_LIST} does not build and run {entry}")
    return problems


def check_android_script(edits: list[tuple[str, str, str, str]]) -> list[str]:
    """The Android script, run as deps.sh runs it, turns the desktop files without the change into them."""
    desktop = {name: (ROOT / rel).read_text(encoding="utf-8") for name, rel in DESKTOP_FILES.items()}
    with tempfile.TemporaryDirectory() as tmp:
        tree = Path(tmp) / "toxcore"
        tree.mkdir()
        for name, text in desktop.items():
            for edit_name, _what, original, changed in edits:
                if edit_name == name:
                    text = text.replace(changed, original)
            if ROAMING_MARKER in text:
                return [f"Android: cannot take the change out of the desktop {name} to compare"]
            (tree / name).write_text(text, encoding="utf-8")
        result = subprocess.run([sys.executable, "-I", str(ROOT / ROAMING_SCRIPT), str(tree)], capture_output=True,
                                text=True, check=False)
        if result.returncode != 0:
            return [f"Android: {ROAMING_SCRIPT} failed on the desktop files without the change: "
                    f"{result.stderr.strip()}"]
        return [f"Android: {ROAMING_SCRIPT} does not produce the desktop {name}, so Android would follow a group peer "
                "that moves otherwise"
                for name, text in desktop.items() if (tree / name).read_text(encoding="utf-8") != text]


def read_from(root: Path) -> Read:
    def read(rel: str) -> str | None:
        path = root / rel
        return path.read_text(encoding="utf-8", errors="replace") if path.is_file() else None
    return read


def problems_of(read: Read) -> tuple[list[str], list[tuple[str, str, str, str]], bool]:
    edits = changes(read)
    problems, desktop_ok = check_copies(read, edits)
    return problems + check_wiring(read) + check_test(read), edits, desktop_ok


def self_test() -> int:
    """Each check must catch the mutant made for it, and the files as they are must pass."""
    read = read_from(ROOT)
    problems, _edits, _desktop_ok = problems_of(read)
    if problems:
        print("FAIL: check-gc-roaming.py self-test: the files as they are do not pass:", file=sys.stderr)
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

    def moved_run(rel: str) -> Read:
        """deps.sh with its first roaming run moved before the receive gap run of its ABI."""
        original = read(rel) or ""
        without = original.replace(ROAMING_RUN, "true", 1)
        changed = without.replace(RECV_GAP_RUN, ROAMING_RUN + " ; " + RECV_GAP_RUN, 1)
        return lambda path: changed if path == rel else read(path)

    mutants = (
        ("iOS follows a peer on a lossy packet no more",
         mutant(f"{IOS_DIR}/group_chats.m", "        follow_moved_gc_peer(chat, gconn, ipp, false);\n", ""),
         "iOS:"),
        ("iOS asks a silent peer for its info no more",
         mutant(f"{IOS_DIR}/group_chats.m", "                send_gc_peer_info_request(chat, gconn);\n"
                "            }\n        }\n", "            }\n        }\n"),
         "iOS:"),
        ("the amalgamation takes a new lossless message for an old one",
         mutant(f"{AMALGAMATION_DIR}/toxcore_amalgamation.c", "gcc_message_is_new(gconn, message_id));", "false);"),
         "desktop amalgamation:"),
        ("the amalgamation without toxav says no address for a lossless packet over UDP",
         mutant(f"{AMALGAMATION_DIR}/toxcore_amalgamation_no_toxav.c",
                "payload, payload_len, true, ipp, userdata);\n            break;\n        }\n\n        case "
                "NET_PACKET_GC_LOSSY",
                "payload, payload_len, true, nullptr, userdata);\n            break;\n        }\n\n        case "
                "NET_PACKET_GC_LOSSY"),
         "desktop amalgamation (no toxav):"),
        ("the desktop waits 30 s for an acknowledgement",
         mutant(DESKTOP_FILES["group_connection.c"], "#define GCC_UNANSWERED_TIMEOUT 3\n",
                "#define GCC_UNANSWERED_TIMEOUT 30\n"),
         "desktop:"),
        ("Android runs the roaming patch for 3 ABIs",
         mutant(ANDROID_DEPS, ROAMING_RUN, "true", ANDROID_ABIS), "Android:"),
        ("Android runs the roaming patch before the receive gap patch",
         moved_run(ANDROID_DEPS), "Android:"),
        ("the desktop build drops the roaming test",
         mutant(TEST_LIST, TEST[1], ""), "toxcore test:"),
    )
    failed = []
    for name, mutant_read, expected in mutants:
        found, _edits, _ok = problems_of(mutant_read)
        if not any(problem.startswith(expected) for problem in found):
            failed.append(f"{name}: not caught (got {found or 'no problem'})")

    if failed:
        print("FAIL: check-gc-roaming.py self-test:", file=sys.stderr)
        for line in failed:
            print(f"  - {line}", file=sys.stderr)
        return 1
    print(f"ok: check-gc-roaming.py self-test, {len(mutants)} mutants caught")
    return 0


def main() -> int:
    problems, edits, desktop_ok = problems_of(read_from(ROOT))
    if desktop_ok:
        problems += check_android_script(edits)

    if problems:
        print("A copy of toxcore does not send to a group peer at the address its packets come from now:")
        for problem in problems:
            print(f"  - {problem}")
        return 1
    print(f"gc roaming: {len(COPIES)} toxcore copies follow a group peer whose address changes and ask a silent peer "
          f"for its info, as does the Android build ({ANDROID_ABIS} ABIs); the toxcore test is in")
    return 0


if __name__ == "__main__":
    if sys.argv[1:] == ["--self-test"]:
        sys.exit(self_test())
    if sys.argv[1:]:
        print("usage: check-gc-roaming.py [--self-test]", file=sys.stderr)
        sys.exit(2)
    sys.exit(main())
