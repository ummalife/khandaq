#!/usr/bin/env python3
"""KHANDAQ gate — a filename that arrived from a peer must never steer the write path.

Every incoming file carries a name chosen by whoever sent it. On the way to disk that name is put
through get_incoming_filetransfer_local_filename(), which strips path separators and neutralises
"." / ".." (security D-3). Without it, a contact naming their file ../../databases/x could write
outside the per-peer directory.

The sanitiser is correct today. What is missing is anything that keeps it that way: a seventh
receive path added later, or a regex quietly relaxed, breaks this silently and no test notices.
CodeQL flags these sites as java/path-injection and cannot see the sanitiser, so its alerts are
false positives — this gate is what actually holds the property, and why those alerts can be
dismissed rather than chased.

Three things are asserted:
  1. the sanitiser still removes both separators and dot-references;
  2. every place that accepts an incoming transfer routes the peer's name through it;
  3. no Filetransfer.file_name is assigned a raw peer-supplied name.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "khandaq-android-trifa/android-refimpl-app/app/src/main/java/com/zoffcc/applications/trifa"
SANITISER = "get_incoming_filetransfer_local_filename"

failures = []


def fail(msg):
    failures.append(msg)


# ---- 1. the sanitiser itself -------------------------------------------------------------------
pattern_file = SRC / "TrifaSetPatternActivity.java"
body = pattern_file.read_text(encoding="utf-8", errors="replace")
m = re.search(r"filter_out_specials_from_filepath\(String \w+\)\s*\{(.*?)\n    \}", body, re.S)
if not m:
    fail("filter_out_specials_from_filepath is gone or reshaped — the sanitiser cannot be checked")
else:
    impl = m.group(1)
    # Separators must be replaced, not merely inspected.
    if not re.search(r"replaceAll\(\s*\"\[\\\\\\\\/", impl):
        fail("the sanitiser no longer replaces path separators (/ and \\) — traversal becomes possible")
    if '".."' not in impl:
        fail('the sanitiser no longer neutralises ".." — a parent-directory reference can survive')
    if "startsWith(\".\")" not in impl:
        fail("the sanitiser no longer strips leading dots — a peer can create hidden files")

# ---- 2. every receive path sanitises ------------------------------------------------------------
# A file arriving from a peer enters through one of these; each must call the sanitiser.
RECEIVE_SITES = {
    "MainActivity.java": 2,          # 1:1 transfers: FTV2 and legacy
    "HelperGroup.java": 2,           # NGC group transfers
    "NgcGroupFileTransfer.java": 2,  # NGC chunked transfers
}
for name, expected in RECEIVE_SITES.items():
    text = (SRC / name).read_text(encoding="utf-8", errors="replace")
    calls = len(re.findall(rf"\b{SANITISER}\(", text))
    if calls < expected:
        fail(f"{name}: {calls} sanitiser call(s), expected at least {expected} — "
             f"a receive path may write a peer-chosen name straight to disk")

# ---- 3. nothing stores a raw peer name as the on-disk name --------------------------------------
# Two record types reach the filesystem. Filetransfer, where only incoming rows matter (an outgoing
# name comes from the local file the user picked). And GroupMessage, whose file_name is joined with
# path_name by send_ngch_syncfile — which sends those bytes to a peer — and by the export action.
RAW_NAMES = ("filename", "displayFilename", "displayName", "file_name_incoming")
BLOCK = re.compile(r"new Filetransfer\(\)(.*?)insert_into_filetransfer_db", re.S)
for java in SRC.glob("*.java"):
    text = java.read_text(encoding="utf-8", errors="replace")
    for block in BLOCK.finditer(text):
        chunk = block.group(1)
        if "TRIFA_FT_DIRECTION_INCOMING" not in chunk:
            continue
        for line in chunk.splitlines():
            stripped = line.strip()
            if stripped.startswith("//") or stripped.startswith("*"):
                continue
            assign = re.search(r"\.file_name\s*=\s*(\w+)\s*;", stripped)
            if assign and assign.group(1) in RAW_NAMES:
                lineno = text[:block.start()].count("\n") + 1
                fail(f"{java.name}:~{lineno}: an incoming transfer stores the raw peer-supplied "
                     f"'{assign.group(1)}' as its on-disk name")

group_text = (SRC / "HelperGroup.java").read_text(encoding="utf-8", errors="replace")
for lineno, line in enumerate(group_text.splitlines(), 1):
    stripped = line.strip()
    if stripped.startswith("//") or stripped.startswith("*"):
        continue
    assign = re.search(r"\.file_name\s*=\s*(\w+)\s*;", stripped)
    if assign and assign.group(1) in RAW_NAMES:
        fail(f"HelperGroup.java:{lineno}: a group file row stores the raw peer-supplied "
             f"'{assign.group(1)}'; send_ngch_syncfile joins it with path_name and sends the bytes out")

# ---- 4. the two paths that hand a file's contents onward check the join -------------------------
GUARDED = {
    "HelperGroup.java": ("send_ngch_syncfile", "reads a file and sends it to a peer"),
    "HelperGeneric.java": ("export_vfs_file_to_real_file", "copies a file out of the encrypted store"),
}
for name, (func, why) in GUARDED.items():
    text = (SRC / name).read_text(encoding="utf-8", errors="replace")
    # the DEFINITION, not the first call site
    m_def = re.search(rf"static\s+\w+\s+{func}\s*\(", text)
    idx = m_def.start() if m_def else -1
    if idx < 0:
        fail(f"{name}: {func} is gone — the path guard cannot be checked")
        continue
    window = text[idx:idx + 4000]
    if "path_inside_dir_or_null" not in window:
        fail(f"{name}: {func} no longer checks that the path stays inside its directory, and it {why}")

if failures:
    print("FAIL — incoming filenames can reach the write path unsanitised:\n")
    for f in failures:
        print(f"  - {f}")
    sys.exit(1)

print("ok — peer-supplied filenames are sanitised on every incoming path")
