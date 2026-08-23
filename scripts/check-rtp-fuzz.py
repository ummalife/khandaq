#!/usr/bin/env python3
"""Drive the real RTP reassembly with generated packet sequences under AddressSanitizer.

KHANDAQ (RR2-12, 2026-08-23). The review asks for "sanitizer-backed fuzzing of the packet and media
parsers", and names the reason: the targeted harnesses in this repository each pin a defect somebody
already understood, which is exactly what a case list cannot do for the next one.

This is the smallest honest version of that. It lifts fill_data_into_slot verbatim out of the shipped
khandaq-ios/.../toxav/rtp.m — the same extractor the sibling check uses, so it can never drift into
testing a copy — builds it with ASan, and drives it with sequences of packets nobody wrote down.

It finds both memory defects the 2026-08-23 review found by hand, in seconds:
  * a packet rejected AFTER its slot was opened left next_free_entry incremented, so repeats walked
    an int8_t counter off a three-entry array;
  * a zero-length payload reached the same state without failing any check.

Front ends: libFuzzer where the toolchain has it (Linux clang), otherwise a deterministic PRNG
corpus. Apple clang ships no libFuzzer runtime, and "no libFuzzer" must not mean "no fuzzing" — the
standalone mode gives the same answer on every machine and every run.

    scripts/check-rtp-fuzz.py
    scripts/check-rtp-fuzz.py --iterations 1000000
    scripts/check-rtp-fuzz.py --libfuzzer --seconds 60      # coverage-guided, where available
"""
from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
HARNESS = os.path.join(HERE, "rtp_fuzz_harness.c.in")
IOS_RTP = os.path.join(ROOT, "khandaq-ios/local_pod_repo/objcTox/../toxcore/toxcore/toxav/rtp.m")
IOS_RTP_ALT = os.path.join(ROOT, "khandaq-ios/local_pod_repo/toxcore/toxcore/toxav/rtp.m")


def die(msg):
    print("::error::%s" % msg, file=sys.stderr)
    sys.exit(1)


def read(path):
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as fh:
            return fh.read()
    except OSError as exc:
        die("cannot read %s (%s)" % (path, exc))


def extract_function(src, name, where):
    """Same brace-matching extractor as check-ios-rtp-reassembly.py. A failure is an error, not a skip."""
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


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--iterations", type=int, default=200000)
    ap.add_argument("--libfuzzer", action="store_true", help="использовать libFuzzer, если он есть")
    ap.add_argument("--seconds", type=int, default=30, help="бюджет времени libFuzzer")
    args = ap.parse_args()

    cc = shutil.which("clang") or shutil.which("cc")
    if not cc:
        die("нет компилятора C — фаззить нечем")

    src_path = IOS_RTP_ALT if os.path.isfile(IOS_RTP_ALT) else IOS_RTP
    if not os.path.isfile(src_path):
        die("не найден rtp.m — проверять нечего, а пустой прогон выглядел бы как успех")
    ios = read(src_path)
    fn = extract_function(ios, "fill_data_into_slot", os.path.relpath(src_path, ROOT))

    defines = ""
    m = re.search(r"^#define\s+MAX_RTP_FRAME_SIZE\s+.*$", ios, re.M)
    defines = m.group(0) if m else "#define MAX_RTP_FRAME_SIZE MAX_RTP_FRAME_SIZE_FALLBACK"

    harness = read(HARNESS)
    for marker in ("@@DEFINES@@", "@@FUNCTION@@"):
        if marker not in harness:
            die("%s: нет маркера %s" % (HARNESS, marker))
    harness = harness.replace("@@DEFINES@@", defines).replace("@@FUNCTION@@", fn)

    tmp = tempfile.mkdtemp(prefix="khandaq-rtpfuzz-")
    csrc = os.path.join(tmp, "fuzz.c")
    with open(csrc, "w", encoding="utf-8") as fh:
        fh.write(harness)
    binary = os.path.join(tmp, "fuzz")

    use_lf = False
    if args.libfuzzer:
        probe = os.path.join(tmp, "probe.c")
        with open(probe, "w", encoding="utf-8") as fh:
            fh.write("#include <stdint.h>\n#include <stddef.h>\n"
                     "int LLVMFuzzerTestOneInput(const uint8_t *d, size_t n){(void)d;(void)n;return 0;}\n")
        r = subprocess.run([cc, "-fsanitize=fuzzer,address", "-o", os.path.join(tmp, "probe"), probe],
                           capture_output=True, text=True)
        use_lf = r.returncode == 0
        if not use_lf:
            print("    libFuzzer недоступен в этом clang — детерминированный корпус вместо него")

    flags = [cc, "-std=c11", "-g", "-O1", "-fno-omit-frame-pointer",
             "-fsanitize=address,undefined", "-fno-sanitize-recover=all"]
    if use_lf:
        flags += ["-fsanitize=fuzzer", "-DKHANDAQ_LIBFUZZER"]
    flags += ["-o", binary, csrc]

    build = subprocess.run(flags, capture_output=True, text=True)
    if build.returncode != 0:
        print(build.stderr[-2500:], file=sys.stderr)
        die("харнесс не собрался. Функция изменилась так, что вокруг неё не компилируется — "
            "почините харнесс, а не удаляйте проверку.")

    print("==> Собрано с ASan+UBSan из %s" % os.path.relpath(src_path, ROOT))
    if use_lf:
        cmd = [binary, "-max_total_time=%d" % args.seconds, "-print_final_stats=1", "-rss_limit_mb=2048"]
        print("    libFuzzer, бюджет %d с" % args.seconds)
    else:
        cmd = [binary, str(args.iterations)]
        print("    детерминированный корпус, последовательностей: %d" % args.iterations)

    env = dict(os.environ)
    env["ASAN_OPTIONS"] = "detect_leaks=0,abort_on_error=0"
    run = subprocess.run(cmd, capture_output=True, text=True, env=env, timeout=1800)
    out = (run.stdout or "") + (run.stderr or "")

    bad = ("AddressSanitizer" in out or "runtime error" in out or "INVARIANT" in out
           or run.returncode != 0)
    if bad:
        print("\nПРОВАЛЕНО: санитайзер или инвариант сработали", file=sys.stderr)
        for line in out.splitlines()[:40]:
            print("::error::  %s" % line, file=sys.stderr)
        return 1

    tail = [l for l in out.splitlines() if l.strip()][-1:] or ["(без вывода)"]
    print("    %s" % tail[0])
    print("\nВСЁ ЧИСТО: сгенерированные последовательности не сломали пересборку RTP")
    return 0


if __name__ == "__main__":
    sys.exit(main())
