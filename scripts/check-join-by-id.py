#!/usr/bin/env python3
"""Every copy of toxcore must let a friend's invite finish a join by group ID.

KHANDAQ (join by ID). tox_group_join() adds the group at once, before any peer has answered. When the DHT
announce never completes (a founder behind a NAT with no TCP relay in its announce), the join just sits there.
The clients ask every online friend for an invite (lossless packet 184), and a member answers with an invite, but
Messenger dropped each invite because the group was "already added", and gc_accept_invite() left the chat joined
by ID ahead of the new one, so the inviter's confirmation reached the old chat and was dropped.

Android (patches/apply_khandaq_join_by_id.py) and iOS (local_pod_repo/toxcore) carried the change; the desktop
copy did not, so on the desktop the friend-assisted join of 0.2.57 (Core::autoAcceptRequestedGroupInvite) never
got an invite. QA live of 0.2.59: a desktop that joined a group founded on an iPhone by its key stayed "not
connected to the group" while the iPhone sent it an invite every 30 s. Since 0.2.59 every copy carries it:
- desktop: buildscripts/toxcore/toxcore/{Messenger.c,group_chats.h,group_chats.c} and both amalgamations, with
  the text of the Android script;
- iOS: local_pod_repo/toxcore (Messenger.m, group_chats.h, group_chats.m), the same code with comments of its own;
- Android: patches/apply_khandaq_join_by_id.py, run by circle_scripts/deps.sh on the cloned toxcore for each of
  the 4 ABIs.

This check holds the text of the Android script in the desktop copies and the lines the change works by in every
copy, and proves that the Android script turns the desktop files without the change into exactly the desktop files.

    scripts/check-join-by-id.py
    scripts/check-join-by-id.py --self-test    # every check must catch the mutant made for it (CI)
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
DESKTOP_FILES = ("Messenger.c", "group_chats.h", "group_chats.c")
DESKTOP = tuple(f"{DESKTOP_DIR}/{name}" for name in DESKTOP_FILES)
IOS = (f"{IOS_DIR}/Messenger.m", f"{IOS_DIR}/group_chats.h", f"{IOS_DIR}/group_chats.m")
AMALGAMATION = f"{AMALGAMATION_DIR}/toxcore_amalgamation.c"
AMALGAMATION_NO_TOXAV = f"{AMALGAMATION_DIR}/toxcore_amalgamation_no_toxav.c"
# (label, files, whether the text of the Android script is held there too)
COPIES = (
    ("desktop", DESKTOP, True),
    ("desktop amalgamation", (AMALGAMATION,), True),
    ("desktop amalgamation (no toxav)", (AMALGAMATION_NO_TOXAV,), True),
    ("iOS", IOS, False),
)

ANDROID_SCRIPT = "khandaq-android-trifa/patches/apply_khandaq_join_by_id.py"
MARKER = "KHANDAQ (join by ID)"
ANDROID_DEPS = "khandaq-android-trifa/circle_scripts/deps.sh"
ANDROID_ABIS = 4
ANDROID_RUN = "python3 /root/work/patches/apply_khandaq_join_by_id.py toxcore || exit 1"
HUNKS = ("FILTER", "DECL", "HELPER", "ACCEPT")

# The lines the change works by, each once in every copy.
ESSENTIALS = (
    ("Messenger passes on an invite to a group known only by its ID",
     "|| gc_group_known_only_by_id(m->group_handler, join_data, join_data_len)) {\n"),
    ("the helper is declared", "bool gc_group_known_only_by_id(const GC_Session *c, const uint8_t *chat_id, "
     "uint32_t length);\n"),
    ("the helper matches only a group with no shared state and no founder",
     "        return chat->shared_state.version == 0\n"
     "               && memeq(chat->shared_state.founder_public_key, EXT_PUBLIC_KEY_SIZE, no_founder, "
     "EXT_PUBLIC_KEY_SIZE);\n"),
    ("the accepted invite deletes the chat it replaces",
     "    if (stale_chat != nullptr && (stale_chat->flag_exit || !gc_group_is_valid(stale_chat))) {\n"
     "        group_delete(c, stale_chat);\n"
     "    }\n"),
)

Read = Callable[[str], "str | None"]


def script_hunks(text: str) -> list[tuple[str, str, str]]:
    """(hunk, original, changed) of the Android script, read without running it."""
    values: dict[str, str] = {}

    def value(node: ast.expr) -> str:
        if isinstance(node, ast.Constant) and isinstance(node.value, str):
            return node.value
        if isinstance(node, ast.Name) and node.id in values:
            return values[node.id]
        if isinstance(node, ast.BinOp) and isinstance(node.op, ast.Add):
            return value(node.left) + value(node.right)
        raise ValueError(f"{ANDROID_SCRIPT}: unexpected constant {ast.dump(node)}")

    for node in ast.parse(text).body:
        if isinstance(node, ast.Assign) and len(node.targets) == 1 and isinstance(node.targets[0], ast.Name):
            name = node.targets[0].id
            if name.startswith(("ORIG_", "REPL_")):
                values[name] = value(node.value)
    missing = [f"{kind}_{hunk}" for hunk in HUNKS for kind in ("ORIG", "REPL") if f"{kind}_{hunk}" not in values]
    if missing:
        raise ValueError(f"{ANDROID_SCRIPT} has no {', '.join(missing)}")
    return [(hunk, values[f"ORIG_{hunk}"], values[f"REPL_{hunk}"]) for hunk in HUNKS]


def check_copy(label: str, text: str, hunks: list[tuple[str, str, str]], held: bool) -> list[str]:
    problems = []
    for what, line in ESSENTIALS:
        if text.count(line) != 1:
            problems.append(f"{label}: {what}: expected once, found {text.count(line)} time(s)")
    if held:
        for hunk, original, changed in hunks:
            if text.count(changed) != 1:
                problems.append(f"{label}: the {hunk} change differs from the Android script (found "
                                f"{text.count(changed)} copies of its text)")
            if original not in changed and original in text:
                problems.append(f"{label}: the {hunk} change is still as toxcore had it")
    return problems


def check_copies(read: Read, hunks: list[tuple[str, str, str]]) -> tuple[list[str], bool]:
    """The problems of the toxcore copies, and whether the desktop one is good."""
    problems: list[str] = []
    desktop_ok = False
    for label, rels, held in COPIES:
        texts = [read(rel) for rel in rels]
        missing = [rel for rel, text in zip(rels, texts) if text is None]
        if missing:
            problems += [f"{label}: {rel} is missing" for rel in missing]
            continue
        found = check_copy(label, "".join(text or "" for text in texts), hunks, held)
        problems += found
        if label == "desktop":
            desktop_ok = not found
    return problems, desktop_ok


def check_wiring(read: Read) -> list[str]:
    deps = read(ANDROID_DEPS)
    if deps is None:
        return [f"Android: {ANDROID_DEPS} is missing"]
    sites = deps.count(ANDROID_RUN)
    if sites != ANDROID_ABIS:
        return [f"Android: {ANDROID_DEPS} runs the join by ID patch at {sites} site(s), expected one per ABI "
                f"({ANDROID_ABIS})"]
    return []


def check_android_script(hunks: list[tuple[str, str, str]]) -> list[str]:
    """The Android script, run as deps.sh runs it, turns the desktop files without the change into them."""
    desktop = {name: (ROOT / rel).read_text(encoding="utf-8", errors="surrogateescape")
               for name, rel in zip(DESKTOP_FILES, DESKTOP)}
    with tempfile.TemporaryDirectory() as tmp:
        tree = Path(tmp) / "toxcore"
        tree.mkdir()
        for name, text in desktop.items():
            for _hunk, original, changed in hunks:
                text = text.replace(changed, original)
            if MARKER in text:
                return [f"Android: cannot take the change out of the desktop {name} to compare"]
            (tree / name).write_text(text, encoding="utf-8", errors="surrogateescape")
        result = subprocess.run([sys.executable, "-I", str(ROOT / ANDROID_SCRIPT), str(tree)], capture_output=True,
                                text=True, check=False)
        if result.returncode != 0:
            return [f"Android: {ANDROID_SCRIPT} failed on the desktop files without the change: "
                    f"{result.stderr.strip()}"]
        return [f"Android: {ANDROID_SCRIPT} does not produce the desktop {name}, so Android would join by ID "
                "otherwise"
                for name, text in desktop.items()
                if (tree / name).read_text(encoding="utf-8", errors="surrogateescape") != text]


def read_from(root: Path) -> Read:
    def read(rel: str) -> str | None:
        path = root / rel
        return path.read_text(encoding="utf-8", errors="surrogateescape") if path.is_file() else None
    return read


def problems_of(read: Read) -> tuple[list[str], list[tuple[str, str, str]], bool]:
    script = read(ANDROID_SCRIPT)
    if script is None:
        return [f"Android: {ANDROID_SCRIPT} is missing"], [], False
    hunks = script_hunks(script)
    problems, desktop_ok = check_copies(read, hunks)
    return problems + check_wiring(read), hunks, desktop_ok


def self_test() -> int:
    """Each check must catch the mutant made for it, and the files as they are must pass."""
    read = read_from(ROOT)
    problems, _hunks, _desktop_ok = problems_of(read)
    if problems:
        print("FAIL: check-join-by-id.py self-test: the files as they are do not pass:", file=sys.stderr)
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

    mutants = (
        ("the desktop drops an invite to a group joined by its ID again",
         mutant(DESKTOP[0], "                if (group_not_added(m->group_handler, join_data, join_data_len)\n"
                "                        || gc_group_known_only_by_id(m->group_handler, join_data, join_data_len)) {\n",
                "                if (group_not_added(m->group_handler, join_data, join_data_len)) {\n"),
         "desktop:"),
        ("the amalgamation keeps the chat joined by ID ahead of the invited one",
         mutant(AMALGAMATION, "        group_delete(c, stale_chat);\n", ""),
         "desktop amalgamation:"),
        ("the amalgamation without toxav matches a group with shared state",
         mutant(AMALGAMATION_NO_TOXAV, "        return chat->shared_state.version == 0\n               && ",
                "        return "),
         "desktop amalgamation (no toxav):"),
        ("iOS drops an invite to a group joined by its ID again",
         mutant(IOS[0], "|| gc_group_known_only_by_id(m->group_handler, join_data, join_data_len)) {",
                ") {"),
         "iOS:"),
        ("Android runs the join by ID patch for 3 ABIs",
         mutant(ANDROID_DEPS, ANDROID_RUN, "true", ANDROID_ABIS), "Android:"),
    )
    failed = []
    for name, mutant_read, expected in mutants:
        found, _hunks, _ok = problems_of(mutant_read)
        if not any(problem.startswith(expected) for problem in found):
            failed.append(f"{name}: not caught (got {found or 'no problem'})")

    if failed:
        print("FAIL: check-join-by-id.py self-test:", file=sys.stderr)
        for line in failed:
            print(f"  - {line}", file=sys.stderr)
        return 1
    print(f"ok: check-join-by-id.py self-test, {len(mutants)} mutants caught")
    return 0


def main() -> int:
    problems, hunks, desktop_ok = problems_of(read_from(ROOT))
    if desktop_ok:
        problems += check_android_script(hunks)

    if problems:
        print("A copy of toxcore does not let a friend's invite finish a join by group ID:")
        for problem in problems:
            print(f"  - {problem}")
        return 1
    print(f"join by ID: {len(COPIES)} toxcore copies let a friend's invite finish a join by group ID, as does the "
          f"Android build ({ANDROID_ABIS} ABIs)")
    return 0


if __name__ == "__main__":
    if sys.argv[1:] == ["--self-test"]:
        sys.exit(self_test())
    if sys.argv[1:]:
        print("usage: check-join-by-id.py [--self-test]", file=sys.stderr)
        sys.exit(2)
    sys.exit(main())
