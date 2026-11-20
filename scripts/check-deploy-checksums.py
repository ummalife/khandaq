#!/usr/bin/env python3
"""The checksum list deploy-site.sh publishes must match what it publishes, signatures included.

KHANDAQ (0.2.66, review of the first deploy from dist/). deploy-site.sh made SHA256SUMS.txt before it
signed the downloads it had staged. Staging a download drops its old signature (drop_stale_sig), so
the list carried over the line of that old signature and had none for a new one: v0.2.66 went out
with five lines that did not match and one missing, and `shasum -a 256 -c SHA256SUMS.txt`, which the
site tells people to run, failed. The deploy now makes the list again once the signatures are made
and holds it to the bytes before anything is uploaded.

This runs the deploy's own merge_checksums, taken from the script, through that sequence in a
scratch directory, and checks the order of the steps in the script.
"""
from __future__ import annotations

import hashlib
import re
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEPLOY = ROOT / "scripts" / "deploy-site.sh"
LINE = re.compile(r"^([0-9a-f]{64})[ \t]+\*?(.+)$")


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def merge_function(script: str) -> str:
    match = re.search(r"^merge_checksums\(\) \{\n.*?^\}\n", script, re.S | re.M)
    if not match:
        raise SystemExit("FAIL: scripts/deploy-site.sh has no merge_checksums function")
    return match.group(0)


def merge(function: str, downloads: Path) -> None:
    program = f'set -euo pipefail\nDL="$1"\n{function}\nmerge_checksums >/dev/null\n'
    subprocess.run(["bash", "-c", program, "merge_checksums", str(downloads)], check=True)


def listed(path: Path) -> dict[str, str]:
    lines = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        match = LINE.match(line)
        if match:
            lines[match.group(2)] = match.group(1)
    return lines


def check_sequence(function: str, failures: list[str]) -> None:
    published = {
        "khandaq-app.zip": b"app, last release",
        "khandaq-app.zip.sig": b"signature of the last release",
        "khandaq-kept.deb": b"kept",
        "khandaq-kept.deb.sig": b"signature of kept",
        "khandaq-server-only.deb": b"only on the server",
        "khandaq-server-only.deb.sig": b"signature only on the server",
    }
    with tempfile.TemporaryDirectory() as scratch:
        downloads = Path(scratch)
        (downloads / "SHA256SUMS.txt").write_text(
            "".join(f"{sha(data)}  {name}\n" for name, data in sorted(published.items())), encoding="utf-8")
        # As the deploy has them before the list is made: a download kept with its committed
        # signature; the staged one whose old signature drop_stale_sig removed; a new versioned
        # download with no signature yet; the update manifest, which is never listed.
        (downloads / "khandaq-kept.deb").write_bytes(published["khandaq-kept.deb"])
        (downloads / "khandaq-kept.deb.sig").write_bytes(published["khandaq-kept.deb.sig"])
        (downloads / "khandaq-app.zip").write_bytes(b"app, this release")
        (downloads / "khandaq-app_2.zip").write_bytes(b"app, this release")
        (downloads / "khandaq-desktop-update.json").write_bytes(b"{}")
        merge(function, downloads)
        before_signing = listed(downloads / "SHA256SUMS.txt")

        # The signer writes the new signatures beside the staged downloads, and the manifest's.
        made = {
            "khandaq-app.zip.sig": b"signature of this release",
            "khandaq-app_2.zip.sig": b"signature of this release, versioned",
            "khandaq-desktop-update.json.sig": b"signature of the manifest",
        }
        for name, data in made.items():
            (downloads / name).write_bytes(data)

        # What one merge before signing publishes: the state of v0.2.66.
        if (before_signing.get("khandaq-app.zip.sig") != sha(published["khandaq-app.zip.sig"])
                or "khandaq-app_2.zip.sig" in before_signing):
            failures.append("the list made before signing no longer shows what the second merge is for; "
                            "update this check with the deploy")

        merge(function, downloads)
        final = listed(downloads / "SHA256SUMS.txt")
        expected = {
            "khandaq-app.zip": sha(b"app, this release"),
            "khandaq-app.zip.sig": sha(made["khandaq-app.zip.sig"]),
            "khandaq-app_2.zip": sha(b"app, this release"),
            "khandaq-app_2.zip.sig": sha(made["khandaq-app_2.zip.sig"]),
            "khandaq-kept.deb": sha(published["khandaq-kept.deb"]),
            "khandaq-kept.deb.sig": sha(published["khandaq-kept.deb.sig"]),
            "khandaq-server-only.deb": sha(published["khandaq-server-only.deb"]),
            "khandaq-server-only.deb.sig": sha(published["khandaq-server-only.deb.sig"]),
        }
        for name in sorted(set(expected) | set(final)):
            if final.get(name) != expected.get(name):
                failures.append(f"after signing, the list gives {name}: {final.get(name)!r}, "
                                f"expected {expected.get(name)!r}")


def check_order(script: str, failures: list[str]) -> None:
    sign = script.find('bash "$ROOT/scripts/sign-desktop-artifacts.sh"')
    calls = [match.start() for match in re.finditer(r"^merge_checksums$", script, re.M)]
    held = script.find("shasum -a 256 -c --quiet SHA256SUMS.txt")
    upload = script.find('echo "==> Upload Khandaq site')
    if min(sign, held, upload) < 0 or len(calls) != 2:
        failures.append("deploy-site.sh: the signer, two calls of merge_checksums, the shasum -c of the "
                        "list and the upload must all be there")
    elif not calls[0] < sign < calls[1] < held < upload:
        failures.append("deploy-site.sh must make the list, sign, make the list again and check it with "
                        "shasum -c, in that order, before the upload")


def main() -> int:
    script = DEPLOY.read_text(encoding="utf-8")
    failures: list[str] = []
    check_sequence(merge_function(script), failures)
    check_order(script, failures)
    for failure in failures:
        print(f"FAIL: {failure}")
    if failures:
        return 1
    print("ok: the deploy lists every signature with the hash of the signature it made, and checks the "
          "list against the bytes before the upload")
    return 0


if __name__ == "__main__":
    sys.exit(main())
