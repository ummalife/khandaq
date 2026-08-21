#!/usr/bin/env python3
"""KHANDAQ (audit round 3, F-01) — the iOS video-RTP reassembler, compiled and attacked.

WHY THIS EXISTS, AND WHY IT IS NOT A GREP

`khandaq-ios/local_pod_repo/toxcore/` is a vendored copy of the desktop toxcore, synced by
`install-tox-ngc.sh`. That script copies `toxcore/` and `toxutil/` — and NOT `toxav/`. It never has.
So while the desktop toxcore gained three bounds checks in `fill_data_into_slot`, the iOS copy sat
frozen at an older generation and kept none of them, and the fixed file was even dropped into the
same directory as `rtp.m.bak` (byte-identical to the desktop `rtp.c`) where the podspec glob
`toxcore/toxav/*.{m,h}` cannot see it. The bug was reachable by any accepted contact in a video call:

  packet A: sequnum=S timestamp=T data_length_full=200        offset_full=0          -> slot of 200 bytes
  packet B: sequnum=S timestamp=T data_length_full=0x00100000 offset_full=0x000FFF00 -> same slot

`get_slot()` matches on sequnum+timestamp alone and never compares `data_length_full`, so B lands in
A's 200-byte slot; the `assert` compared B's offset against B's OWN inflated length and agreed; and
`memcpy(slot->buf->data + offset_full, ...)` wrote roughly a megabyte past the allocation, at an
offset the attacker picks anywhere in a 4 GiB window, with attacker-chosen contents.

A grep for the guard text would pass the moment someone reworded a log line. This compiles the REAL
function out of the REAL file and runs the attack against it, so what is verified is behaviour.

    python3 scripts/check-ios-rtp-reassembly.py

Requires a C compiler (cc/gcc/clang). Exits 2 — not 0 — if there is none, because a check that skips
itself silently is the thing this whole audit round kept finding.
"""
import os
import re
import shutil
import subprocess
import sys
import tempfile

ROOT = os.environ.get("KHANDAQ_ROOT") or os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
IOS_RTP = os.path.join(ROOT, "khandaq-ios", "local_pod_repo", "toxcore", "toxcore", "toxav", "rtp.m")
DESKTOP_RTP = os.path.join(ROOT, "khandaq-desktop", "buildscripts", "toxcore", "toxav", "rtp.c")


def die(msg):
    print("::error::" + msg, file=sys.stderr)
    sys.exit(1)


def read(path):
    try:
        with open(path, encoding="utf-8", errors="replace") as fh:
            return fh.read()
    except OSError as exc:
        die("cannot read %s (%s)" % (path, exc))


def extract_function(src, name, where):
    """Pull one C function out by brace matching. A failure here is a hard error, never a skip."""
    m = re.search(r"^static\s+bool\s+%s\s*\(" % re.escape(name), src, re.M)
    if not m:
        die("%s: cannot find `static bool %s(` — the file changed shape and this check would have "
            "become vacuous. Fix the extractor rather than deleting the check." % (where, name))
    start = m.start()
    i = src.index("{", m.end() - 1)
    depth = 0
    for j in range(i, len(src)):
        if src[j] == "{":
            depth += 1
        elif src[j] == "}":
            depth -= 1
            if depth == 0:
                return src[start:j + 1]
    die("%s: unbalanced braces while extracting %s" % (where, name))


HARNESS_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                            "rtp_reassembly_harness.c.in")


def find_compiler():
    for cc in ("cc", "gcc", "clang"):
        p = shutil.which(cc)
        if p:
            return p
    for p in (r"C:\Users\Administrator\scoop\apps\mingw\current\bin\gcc.exe",):
        if os.path.isfile(p):
            return p
    return None


def main():
    ios = read(IOS_RTP)
    desktop = read(DESKTOP_RTP)

    # The desktop copy is the reference. If IT ever loses a guard, this check must not quietly keep
    # passing on the iOS side alone.
    for needle, what in (("MAX_RTP_FRAME_SIZE", "the frame-size ceiling"),
                         ("data_length_full != ", "the per-frame length agreement check"),
                         ("data_length_full - ", "the memcpy bound")):
        if needle not in desktop:
            die("the desktop reference %s no longer contains %s (%s) — the two implementations are "
                "being compared against a moving target" % (DESKTOP_RTP, needle, what))

    fn = extract_function(ios, "fill_data_into_slot", "khandaq-ios .../toxav/rtp.m")

    m = re.search(r"^#define\s+MAX_RTP_FRAME_SIZE\s+(.+)$", ios, re.M)
    defines = ("#define MAX_RTP_FRAME_SIZE " + m.group(1).strip()) if m else \
              "#define MAX_RTP_FRAME_SIZE MAX_RTP_FRAME_SIZE_FALLBACK"
    if not m:
        print("::warning::khandaq-ios rtp.m does not define MAX_RTP_FRAME_SIZE; the harness will use "
              "a fallback and case 3 will exercise the fallback ceiling instead", file=sys.stderr)

    cc = find_compiler()
    if cc is None:
        die("no C compiler found (cc/gcc/clang). This check does not skip itself — install one or "
            "run it where one exists.")

    harness = read(HARNESS_FILE)
    for marker in ("@@DEFINES@@", "@@FUNCTION@@"):
        if marker not in harness:
            die("%s no longer contains %s — the harness and this script have drifted apart"
                % (HARNESS_FILE, marker))
    src = harness.replace("@@DEFINES@@", defines).replace("@@FUNCTION@@", fn)
    tmp = tempfile.mkdtemp(prefix="khandaq-rtp-")
    cpath = os.path.join(tmp, "rtp_reassembly_test.c")
    epath = os.path.join(tmp, "rtp_reassembly_test.exe")
    with open(cpath, "w", encoding="utf-8") as fh:
        fh.write(src)

    # -DNDEBUG on purpose: this models the RELEASE pod, which is the artifact users run and the only
    # configuration in which the bug is memory corruption. With asserts live, the assert on
    # `offset_full < data_length_full` fires first and the process aborts — a crash, not a write. The
    # guards have to hold when that assert is gone, so that is what is tested.
    print("compiling the real fill_data_into_slot out of khandaq-ios .../toxav/rtp.m (%d bytes of "
          "function) with %s, -DNDEBUG (release semantics)" % (len(fn), os.path.basename(cc)))
    build = subprocess.run([cc, "-std=c11", "-O1", "-Wall", "-DNDEBUG", "-o", epath, cpath],
                           capture_output=True, text=True)
    if build.returncode != 0:
        print(build.stdout, file=sys.stderr)
        print(build.stderr, file=sys.stderr)
        die("the extracted function does not compile — see above")

    run = subprocess.run([epath], capture_output=True, text=True)
    sys.stdout.write(run.stdout)
    if run.stderr.strip():
        sys.stderr.write(run.stderr)
    if run.returncode != 0:
        die("the iOS RTP reassembler accepted a packet it must refuse — F-01 has regressed")

    print("iOS video-RTP reassembly refuses the F-01 attack and still reassembles a valid frame")
    return 0


if __name__ == "__main__":
    sys.exit(main())
