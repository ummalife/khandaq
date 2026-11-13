#!/usr/bin/env python3
"""The Linux bundle must carry the binary of the build that made it.

KHANDAQ (desktop v0.2.51). build-linux-docker.sh installs each new binary as dist/linux/khandaq, and
bundle-linux-portable.sh then moves it to khandaq.bin and writes a launcher script in its place. The
move used to happen only when no khandaq.bin was there yet. dist/ is kept between builds, so a second
build into the same tree kept the khandaq.bin of the build before and wrote the launcher over the new
binary: the first v0.2.51 Linux build came out with the v0.2.50 binary beside the v0.2.51 libraries,
and verify-linux-build.sh passed it. It was found before the release only because the binary names
the commit it was built from.

This runs the real bundle-linux-portable.sh on throwaway trees, with system binaries standing in for
the app (two different ELF files, so a kept old one is told apart from the new one):

  1. a new build over an earlier bundle: khandaq.bin must become the new binary;
  2. the script run again on a finished bundle: khandaq.bin and the launcher must stay as they are;
  3. a first build into an empty tree: the binary must become khandaq.bin;
  4. no binary at all, only a launcher: the script must fail rather than make a bundle.

The script reads library dependencies with ldd, so this runs on Linux only (CI runs it on Ubuntu).
"""
from __future__ import annotations

import os
import platform
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BUNDLE = ROOT / "scripts" / "bundle-linux-portable.sh"
LAUNCHER_START = b"#!/bin/sh\n"


def find_elf(*names: str) -> Path:
    for name in names:
        found = shutil.which(name)
        if found:
            path = Path(found).resolve()
            if path.read_bytes()[:4] == b"\x7fELF":
                return path
    sys.exit(f"ERROR: none of {names} is an ELF binary on this system")


def run_bundle(dist: Path) -> subprocess.CompletedProcess[str]:
    env = dict(os.environ, KHANDAQ_SKIP_DEP_GATE="1")
    return subprocess.run(
        ["bash", str(BUNDLE), str(dist)],
        env=env,
        capture_output=True,
        text=True,
        check=False,
    )


def main() -> int:
    if platform.system() != "Linux":
        print("SKIP: bundle-linux-portable.sh uses ldd; this check runs on Linux (CI)")
        return 0

    new_binary = find_elf("true")
    old_binary = find_elf("false")
    if new_binary.read_bytes() == old_binary.read_bytes():
        sys.exit("ERROR: the two stand-in binaries are identical; the check could not tell them apart")

    failures: list[str] = []

    def expect(condition: bool, message: str) -> None:
        print(("  ok: " if condition else "  FAIL: ") + message)
        if not condition:
            failures.append(message)

    with tempfile.TemporaryDirectory() as tmp:
        # 1. A new build over the bundle an earlier build left.
        dist = Path(tmp) / "rebuild"
        dist.mkdir()
        shutil.copy2(new_binary, dist / "khandaq")
        shutil.copy2(old_binary, dist / "khandaq.bin")
        result = run_bundle(dist)
        expect(result.returncode == 0, f"a new build over an earlier bundle is bundled (exit {result.returncode})")
        expect(
            (dist / "khandaq.bin").read_bytes() == new_binary.read_bytes(),
            "a new build over an earlier bundle replaces khandaq.bin with the new binary",
        )
        expect(
            (dist / "khandaq").read_bytes().startswith(LAUNCHER_START),
            "the launcher takes the new binary's place",
        )

        # 2. The script run again on the bundle it just made.
        result = run_bundle(dist)
        expect(result.returncode == 0, f"bundling a finished bundle again succeeds (exit {result.returncode})")
        expect(
            (dist / "khandaq.bin").read_bytes() == new_binary.read_bytes(),
            "bundling a finished bundle again keeps its khandaq.bin",
        )
        expect(
            (dist / "khandaq").read_bytes().startswith(LAUNCHER_START),
            "bundling a finished bundle again keeps the launcher",
        )

        # 3. A first build into an empty tree.
        dist = Path(tmp) / "first"
        dist.mkdir()
        shutil.copy2(new_binary, dist / "khandaq")
        result = run_bundle(dist)
        expect(result.returncode == 0, f"a first build is bundled (exit {result.returncode})")
        expect(
            (dist / "khandaq.bin").read_bytes() == new_binary.read_bytes(),
            "a first build becomes khandaq.bin",
        )

        # 4. Only a launcher and no binary.
        dist = Path(tmp) / "no-binary"
        dist.mkdir()
        (dist / "khandaq").write_bytes(LAUNCHER_START + b'exec "$DIR/khandaq.bin" "$@"\n')
        result = run_bundle(dist)
        expect(result.returncode != 0, "a launcher without a binary is refused")
        expect(not (dist / "khandaq.bin").exists(), "no khandaq.bin is made from a launcher")

    if failures:
        print(f"ERROR: {len(failures)} check(s) failed; see scripts/bundle-linux-portable.sh", file=sys.stderr)
        return 1
    print("ok: the Linux bundle carries the binary of the build that made it")
    return 0


if __name__ == "__main__":
    sys.exit(main())
