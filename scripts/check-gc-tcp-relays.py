#!/usr/bin/env python3
"""Every copy of toxcore must keep TCP relays of its own for each group.

KHANDAQ (0.2.63, F9, group TCP relays). QA live of 0.2.62: members of a group behind the same NAT never saw each
other, a member who joined later stayed out of step with the others for good, and an invite from a desktop founder
could die without a word. A group's TCP connections run under the group's own key and take their relays from the
messenger's, and four things kept a group without any:
- it took only the relays the messenger was connected to, and on a client that reaches all of its friends over UDP
  those relays sleep;
- it took them again only when the number of its own connected relays changed, so a group that started with none
  (every group loaded at tox_new(), every group made before the first relay was up) never got any;
- an invite confirm carried only the group's connected relays: with none, and no UDP address for the friend, the
  founder dropped the invite;
- a relay a group shares sleeps while every peer on it is direct, so a peer that can reach us only over TCP (one
  behind our NAT, which cannot hairpin to our public address) sent its first handshake to a relay we had left.

A copy of toxcore now tops each group up to GC_MIN_CONNECTED_TCP_RELAYS relays, from the messenger's connected relays
and then its sleeping ones; confirms an invite with the group's relays, connected or asleep, and then the
messenger's; keeps every TCP connection of a group awake for a while after a sync request; and takes back the
sleepers a connection counted on its relays when it wakes (as TokTok PR #3081). Nothing changes on the wire.

Every client is in groups, so every client has to carry the change:
- desktop: buildscripts/toxcore/toxcore/{group_chats.c,TCP_connection.c} and both amalgamations;
- iOS: local_pod_repo/toxcore (group_chats.m, TCP_connection.m);
- Android: patches/apply_khandaq_gc_tcp_relays.py, run by circle_scripts/deps.sh on the cloned toxcore for each of
  the 4 ABIs, after the roaming patch.

This check holds the text of the change, as the Android script carries it, in every copy, and the lines that make it
work; proves that the Android script turns the desktop files without the change into exactly the desktop files; and
keeps the toxcore test that runs it on local toxes (auto_tests/group_tcp_relays_test.c) in the desktop build.

    scripts/check-gc-tcp-relays.py
    scripts/check-gc-tcp-relays.py --self-test    # every check must catch the mutant made for it (CI)
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
DESKTOP_FILES = {name: f"{DESKTOP_DIR}/{name}" for name in ("group_chats.c", "TCP_connection.c")}
COPIES = (
    ("desktop", tuple(DESKTOP_FILES.values())),
    ("desktop amalgamation", (f"{AMALGAMATION_DIR}/toxcore_amalgamation.c",)),
    ("desktop amalgamation (no toxav)", (f"{AMALGAMATION_DIR}/toxcore_amalgamation_no_toxav.c",)),
    ("iOS", (f"{IOS_DIR}/group_chats.m", f"{IOS_DIR}/TCP_connection.m")),
)

RELAYS_SCRIPT = "khandaq-android-trifa/patches/apply_khandaq_gc_tcp_relays.py"
RELAYS_MARKER = "KHANDAQ (F9, group TCP relays)"
ANDROID_DEPS = "khandaq-android-trifa/circle_scripts/deps.sh"
ANDROID_ABIS = 4
RELAYS_RUN = "python3 /root/work/patches/apply_khandaq_gc_tcp_relays.py toxcore || exit 1"
ROAMING_RUN = "python3 /root/work/patches/apply_khandaq_gc_roaming.py toxcore || exit 1"

TEST_DIR = "khandaq-desktop/buildscripts/toxcore/auto_tests"
TEST_LIST = f"{TEST_DIR}/CMakeLists.txt"
TEST = (f"{TEST_DIR}/group_tcp_relays_test.c", "auto_test(group_tcp_relays)")

# The edits of the Android script, by file: (what, constant of the original text, constant of the changed text).
EDITS = (
    ("group_chats.c", "the relay minimum and the awake window", "ORIG_CONSTANTS", "REPL_CONSTANTS"),
    ("group_chats.c", "the peer loop and the relay check of do_gc_tcp", "ORIG_DO_GC_TCP", "REPL_DO_GC_TCP"),
    ("group_chats.c", "the relays a group takes from the messenger", "ORIG_SEEDING", "REPL_SEEDING"),
    ("group_chats.c", "the relays of an invite confirm", "ORIG_CONFIRM_RELAYS", "REPL_CONFIRM_RELAYS"),
    ("group_chats.c", "the length of the join data", "ORIG_CONFIRM_LENGTH", "REPL_CONFIRM_LENGTH"),
    ("TCP_connection.c", "the sleepers a waking connection takes back", "ORIG_WAKE", "REPL_WAKE"),
)

# The lines the change works by, each once in every copy: were the Android script ever cut from a desktop file
# without one of them, the text check below would hold the copies to that.
ESSENTIALS = (
    ("a group short of relays is topped up every check, not only when its count changes",
     "                || tcp_connected_relays_count(chat->tcp_conn) < GC_MIN_CONNECTED_TCP_RELAYS)) {\n"),
    ("a group takes the messenger's sleeping relays too",
     "    append_tcp_relays_with_status(m_tcp_c, TCP_CONN_SLEEPING, tcp_relays, &num_copied, num_relays);\n"),
    ("an invite confirm carries the relays a group can share, not only its connected ones",
     "    const uint32_t num_tcp_relays = copy_gc_shareable_tcp_relays(c, chat, tcp_relays, "
     "GCC_MAX_TCP_SHARED_RELAYS);\n"),
    ("every TCP connection of a group stays awake while it re-syncs",
     "        const bool tcp_set = !direct || resyncing;\n"),
    ("a connection that wakes takes back the sleepers it counted",
     "                if (con_to->connections[i].status == TCP_CONNECTIONS_STATUS_ONLINE "
     "&& tcp_con->sleep_count > 0) {\n"
     "                    --tcp_con->sleep_count;\n"),
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
    text = read(RELAYS_SCRIPT)
    if text is None:
        raise ValueError(f"{RELAYS_SCRIPT} is missing")
    names = tuple(name for _file, _what, orig, repl in EDITS for name in (orig, repl))
    constants = script_constants(text, names, RELAYS_SCRIPT)
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
    sites = deps.count(RELAYS_RUN)
    if sites != ANDROID_ABIS:
        problems.append(f"Android: {ANDROID_DEPS} runs the TCP relays patch at {sites} site(s), expected one per ABI "
                        f"({ANDROID_ABIS})")
    # After the roaming patch of the same ABI, as the desktop files were made.
    roams = [i for i in range(len(deps)) if deps.startswith(ROAMING_RUN, i)]
    relays = [i for i in range(len(deps)) if deps.startswith(RELAYS_RUN, i)]
    if len(roams) == len(relays) and any(not (roam < relay and (k + 1 == len(roams) or relay < roams[k + 1]))
                                         for k, (roam, relay) in enumerate(zip(roams, relays))):
        problems.append(f"Android: {ANDROID_DEPS} runs the TCP relays patch before the roaming patch of its ABI")
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
            if RELAYS_MARKER in text:
                return [f"Android: cannot take the change out of the desktop {name} to compare"]
            (tree / name).write_text(text, encoding="utf-8")
        result = subprocess.run([sys.executable, "-I", str(ROOT / RELAYS_SCRIPT), str(tree)], capture_output=True,
                                text=True, check=False)
        if result.returncode != 0:
            return [f"Android: {RELAYS_SCRIPT} failed on the desktop files without the change: "
                    f"{result.stderr.strip()}"]
        return [f"Android: {RELAYS_SCRIPT} does not produce the desktop {name}, so Android groups would keep their "
                "relays otherwise"
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
        print("FAIL: check-gc-tcp-relays.py self-test: the files as they are do not pass:", file=sys.stderr)
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
        """deps.sh with its first TCP relays run moved before the roaming run of its ABI."""
        original = read(rel) or ""
        without = original.replace(RELAYS_RUN, "true", 1)
        changed = without.replace(ROAMING_RUN, RELAYS_RUN + " ; " + ROAMING_RUN, 1)
        return lambda path: changed if path == rel else read(path)

    mutants = (
        ("iOS tops a group up only when its relay count changes",
         mutant(f"{IOS_DIR}/group_chats.m",
                "            && (tcp_connected_relays_count(chat->tcp_conn) != chat->connected_tcp_relays\n"
                "                || tcp_connected_relays_count(chat->tcp_conn) < GC_MIN_CONNECTED_TCP_RELAYS)) {\n",
                "            && tcp_connected_relays_count(chat->tcp_conn) != chat->connected_tcp_relays) {\n"),
         "iOS:"),
        ("iOS leaves a phantom sleeper on a relay",
         mutant(f"{IOS_DIR}/TCP_connection.m",
                "&& tcp_con->sleep_count > 0) {\n                    --tcp_con->sleep_count;\n",
                "&& tcp_con->sleep_count > 0) {\n"),
         "iOS:"),
        ("the amalgamation seeds a group from the messenger's connected relays alone",
         mutant(f"{AMALGAMATION_DIR}/toxcore_amalgamation.c",
                "    append_tcp_relays_with_status(m_tcp_c, TCP_CONN_SLEEPING, tcp_relays, &num_copied, num_relays);\n",
                ""),
         "desktop amalgamation:"),
        ("the amalgamation without toxav confirms an invite with the connected group relays alone",
         mutant(f"{AMALGAMATION_DIR}/toxcore_amalgamation_no_toxav.c",
                "copy_gc_shareable_tcp_relays(c, chat, tcp_relays, GCC_MAX_TCP_SHARED_RELAYS);",
                "tcp_copy_connected_relays(chat->tcp_conn, tcp_relays, GCC_MAX_TCP_SHARED_RELAYS);"),
         "desktop amalgamation (no toxav):"),
        ("the desktop lets the connections of direct peers sleep while it re-syncs",
         mutant(DESKTOP_FILES["group_chats.c"], "        const bool tcp_set = !direct || resyncing;\n",
                "        const bool tcp_set = !direct;\n"),
         "desktop:"),
        ("Android runs the TCP relays patch for 3 ABIs",
         mutant(ANDROID_DEPS, RELAYS_RUN, "true", ANDROID_ABIS), "Android:"),
        ("Android runs the TCP relays patch before the roaming patch",
         moved_run(ANDROID_DEPS), "Android:"),
        ("the desktop build drops the TCP relays test",
         mutant(TEST_LIST, TEST[1], ""), "toxcore test:"),
    )
    failed = []
    for name, mutant_read, expected in mutants:
        found, _edits, _ok = problems_of(mutant_read)
        if not any(problem.startswith(expected) for problem in found):
            failed.append(f"{name}: not caught (got {found or 'no problem'})")

    if failed:
        print("FAIL: check-gc-tcp-relays.py self-test:", file=sys.stderr)
        for line in failed:
            print(f"  - {line}", file=sys.stderr)
        return 1
    print(f"ok: check-gc-tcp-relays.py self-test, {len(mutants)} mutants caught")
    return 0


def main() -> int:
    problems, edits, desktop_ok = problems_of(read_from(ROOT))
    if desktop_ok:
        problems += check_android_script(edits)

    if problems:
        print("A copy of toxcore does not keep TCP relays of its own for its groups:")
        for problem in problems:
            print(f"  - {problem}")
        return 1
    print(f"gc tcp relays: {len(COPIES)} toxcore copies keep relays for their groups, hand them out in an invite "
          f"confirm and stay on them while they re-sync, as does the Android build ({ANDROID_ABIS} ABIs); the "
          "toxcore test is in")
    return 0


if __name__ == "__main__":
    if sys.argv[1:] == ["--self-test"]:
        sys.exit(self_test())
    if sys.argv[1:]:
        print("usage: check-gc-tcp-relays.py [--self-test]", file=sys.stderr)
        sys.exit(2)
    sys.exit(main())
