#!/usr/bin/env python3
"""KHANDAQ (audit 2026-08-21, K-05) — regenerate infra/push/relay/requirements.txt.

Reads the DIRECT dependencies from requirements.in, resolves the full transitive closure for
CPython 3.12 on linux/amd64 with pip, then writes every artifact's PyPI-published SHA-256 into a
hash-locked requirements file.

Two properties this deliberately has:

  * The closure comes from pip's own resolver, not from a human reading `requires_dist`. Dependency
    metadata moves — `google-auth` dropped `cachetools` and `rsa` from its core requirements, and
    `gunicorn` moved `packaging` into an extra — and a hand-maintained list is wrong the moment it
    is written.
  * Several hashes are recorded per package: the py3-none-any wheel, the cp312/abi3 x86_64 manylinux
    wheels, and the sdist. Which wheel pip picks depends on the glibc of the base image, so a
    superset is recorded rather than a guess. pip accepts extra hashes; it rejects a missing one.

Needs network (PyPI). Run it, review the diff, commit it — the same shape as the Android
witness-checksum flow, where regeneration is explicit and verification is what the release path does.

    python3 scripts/lock-relay-requirements.py
"""
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.request

ROOT = os.environ.get("KHANDAQ_ROOT") or os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RELAY = os.path.join(ROOT, "infra", "push", "relay")
REQ_IN = os.path.join(RELAY, "requirements.in")
REQ_TXT = os.path.join(RELAY, "requirements.txt")

# The interpreter/platform the relay image actually runs. Keep in step with the Dockerfile's base.
PY_VERSION = "312"
PLATFORMS = ["manylinux_2_17_x86_64", "manylinux2014_x86_64",
             "manylinux_2_28_x86_64", "manylinux_2_34_x86_64", "any"]

HEADER = """# KHANDAQ (audit 2026-08-21, K-05) — hash-locked dependency closure for the push relay.
#
# The Dockerfile used to `pip install flask==... requests==... gunicorn==... google-auth==...`:
# four DIRECT versions pinned, every transitive dependency free to be whatever PyPI served that
# minute, and not one byte checked. A compromised or simply changed transitive package entered a
# production relay build with nothing to review — and the relay holds the mounted Firebase service
# account, so "a relay code compromise" means the FCM project.
#
# This file pins the FULL closure, resolved for CPython 3.12 on linux/amd64, and hashes every
# artifact. Installed with `--require-hashes --only-binary=:all:`, so a byte that does not match is
# a build failure rather than a surprise, and no sdist can execute a setup.py during the build.
#
# Several hashes per package on purpose: the wheel pip picks depends on the glibc the base image
# happens to have, so the py3-none-any wheel, the cp312/abi3 x86_64 manylinux wheels and the sdist
# are all listed. A superset is legal; guessing which one the image will choose is not.
#
# Regenerate (do NOT hand-edit):  python3 scripts/lock-relay-requirements.py
# Every version below is checked against api.osv.dev by scripts/check-python-deps.py in CI, because
# "no known advisories" decays without a commit.
#
# Direct dependencies are declared in requirements.in — this file is derived from it.
"""


def die(msg):
    print("ERROR: " + msg, file=sys.stderr)
    sys.exit(1)


def resolve_closure(direct, into):
    cmd = [sys.executable, "-m", "pip", "download", "-q", "-d", into,
           "--only-binary=:all:", "--python-version", PY_VERSION,
           "--implementation", "cp", "--abi", "cp312", "--abi", "abi3", "--abi", "none"]
    for p in PLATFORMS:
        cmd += ["--platform", p]
    cmd += direct
    subprocess.run(cmd, check=True)

    closure, downloaded = {}, {}
    for fn in os.listdir(into):
        m = re.match(r"^([A-Za-z0-9_.\-]+?)-(\d[^-]*)-", fn)
        if not m:
            continue
        name = m.group(1).replace("_", "-").lower()
        closure[name] = m.group(2)
        with open(os.path.join(into, fn), "rb") as fh:
            downloaded[fn] = hashlib.sha256(fh.read()).hexdigest()
    # pip evaluates environment markers against the MACHINE IT RUNS ON, not against --platform. On a
    # Windows checkout that pulls in colorama (click's `platform_system == "Windows"` extra), which
    # the Linux image will never install. Dropping it keeps the lock a description of the image.
    closure.pop("colorama", None)
    return closure, downloaded


def relevant(filename):
    if filename.endswith(".tar.gz"):
        return True
    if filename.endswith(("-py3-none-any.whl", "-py2.py3-none-any.whl")):
        return True
    return (filename.endswith(".whl") and "x86_64" in filename and "musllinux" not in filename
            and ("cp312" in filename or "abi3" in filename))


def main():
    if not os.path.isfile(REQ_IN):
        die("missing %s" % REQ_IN)
    direct = [ln.strip() for ln in open(REQ_IN, encoding="utf-8")
              if ln.strip() and not ln.startswith("#")]
    if not direct:
        die("%s declares no dependencies" % REQ_IN)

    work = tempfile.mkdtemp(prefix="khandaq-relay-lock-")
    try:
        closure, downloaded = resolve_closure(direct, work)
        print("resolved closure: %d packages" % len(closure))

        entries, checked = [], 0
        for name in sorted(closure):
            version = closure[name]
            with urllib.request.urlopen(
                    "https://pypi.org/pypi/%s/%s/json" % (name, version), timeout=60) as resp:
                meta = json.load(resp)
            files = [u for u in meta["urls"] if relevant(u["filename"]) and not u.get("yanked")]
            if not files:
                die("no usable artifact for %s==%s" % (name, version))
            entries.append((name, version, sorted({u["digests"]["sha256"] for u in files})))

        # Cross-check: everything pip actually downloaded must hash to a digest PyPI publishes for
        # that release. This is what turns "the JSON API said so" into "and the bytes agree".
        published = {n: set(h) for n, _, h in entries}
        for fn, digest in downloaded.items():
            name = re.match(r"^([A-Za-z0-9_.\-]+?)-", fn).group(1).replace("_", "-").lower()
            if name not in published:
                continue
            if digest not in published[name]:
                die("%s hashes to %s, which PyPI does not publish for that release" % (fn, digest))
            checked += 1
        print("recomputed %d downloaded artifacts against the published digests: all match" % checked)

        lines = [HEADER]
        for name, version, hashes in entries:
            lines.append("%s==%s \\" % (name, version))
            for i, h in enumerate(hashes):
                lines.append("    --hash=sha256:%s%s" % (h, "" if i == len(hashes) - 1 else " \\"))
        with open(REQ_TXT, "w", encoding="utf-8", newline="\n") as fh:
            fh.write("\n".join(lines) + "\n")
        print("wrote %s (%d packages, %d hashes)"
              % (REQ_TXT, len(entries), sum(len(h) for _, _, h in entries)))
    finally:
        shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    main()
