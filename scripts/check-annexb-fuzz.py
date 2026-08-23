#!/usr/bin/env python3
"""Fuzz the NGC Annex-B to AVCC converter — every byte comes from a group participant.

KHANDAQ (2026-08-23). Core::onLosslessPacket -> CoreExt::onLosslessPacket ->
toxext_handle_lossless_custom_packet -> parse_messages_packet. No call is required, only an accepted
contact, and what the parser reports back becomes a QString in the chat window
(CoreExt::onExtendedMessageReceived) — so reading past the packet is memory disclosure, not a crash.

The defect this was written against: MESSAGE_START checked `it + 8 > end` and the other three
message types did not, while all four read the same eight bytes. A one-byte packet read eight bytes
of adjacent heap.

The parser is lifted verbatim from the shipped file, same as the RTP harnesses, so this cannot drift
into testing a copy.

    scripts/check-annexb-fuzz.py
    scripts/check-annexb-fuzz.py --libfuzzer --seconds 45
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
HARNESS = os.path.join(HERE, "annexb_fuzz_harness.c.in")
SRC = os.path.join(ROOT, "khandaq-ios/local_pod_repo/toxcore/toxcore/toxav/toxav_ngc_video.m")


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
    m = re.search(r"^static\s+bool\s+%s\s*\(" % re.escape(name), src, re.M)
    if not m:
        die("%s: cannot find `static bool %s(` — the file changed shape and this check would have become "
            "vacuous. Fix the extractor rather than deleting the check." % (where, name))
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
    ap.add_argument("--libfuzzer", action="store_true")
    ap.add_argument("--seconds", type=int, default=30)
    args = ap.parse_args()

    cc = shutil.which("clang") or shutil.which("cc")
    if not cc:
        die("нет компилятора C")
    if not os.path.isfile(SRC):
        die("не найден toxav_ngc_video.m — проверять нечего, а пустой прогон выглядел бы "
            "как успех")

    where = os.path.relpath(SRC, ROOT)
    fn = extract_function(read(SRC), "ngc_annexb_to_avcc", where)

    harness = read(HARNESS)
    if "@@FUNCTION@@" not in harness:
        die("%s: нет маркера @@FUNCTION@@" % HARNESS)
    harness = harness.replace("@@FUNCTION@@", fn)

    tmp = tempfile.mkdtemp(prefix="khandaq-toxextfuzz-")
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
        use_lf = subprocess.run([cc, "-fsanitize=fuzzer,address", "-o", os.path.join(tmp, "probe"),
                                 probe], capture_output=True, text=True).returncode == 0
        if not use_lf:
            print("    libFuzzer недоступен — детерминированный корпус вместо него")

    # gnu11: toxext_read_from_buf is a statement-expression, a GNU extension the shipped code uses.
    flags = [cc, "-std=gnu11", "-g", "-O1", "-fno-omit-frame-pointer",
             "-fsanitize=address,undefined", "-fno-sanitize-recover=all"]
    if use_lf:
        flags += ["-fsanitize=fuzzer", "-DKHANDAQ_LIBFUZZER"]
    flags += ["-o", binary, csrc]

    build = subprocess.run(flags, capture_output=True, text=True)
    if build.returncode != 0:
        print(build.stderr[-2500:], file=sys.stderr)
        die("харнесс не собрался — почините его, а не удаляйте проверку")

    print("==> Собрано с ASan+UBSan из %s" % where)
    if use_lf:
        cmd = [binary, "-max_total_time=%d" % args.seconds, "-print_final_stats=1", "-rss_limit_mb=2048"]
        print("    libFuzzer, бюджет %d с" % args.seconds)
    else:
        cmd = [binary, str(args.iterations)]
        print("    детерминированный корпус, кадров: %d" % args.iterations)

    env = dict(os.environ)
    env["ASAN_OPTIONS"] = "detect_leaks=0,abort_on_error=0"
    run = subprocess.run(cmd, capture_output=True, text=True, env=env, timeout=1800)
    out = (run.stdout or "") + (run.stderr or "")

    if ("AddressSanitizer" in out or "runtime error" in out or "INVARIANT" in out
            or run.returncode != 0):
        print("\nПРОВАЛЕНО: санитайзер или инвариант сработали", file=sys.stderr)
        for line in out.splitlines()[:40]:
            print("::error::  %s" % line, file=sys.stderr)
        return 1

    tail = [l for l in out.splitlines() if l.strip()][-1:] or ["(без вывода)"]
    print("    %s" % tail[0])
    print("\nВСЁ ЧИСТО: сгенерированные кадры не вывели конвертер за пределы буфера")
    return 0


if __name__ == "__main__":
    sys.exit(main())
