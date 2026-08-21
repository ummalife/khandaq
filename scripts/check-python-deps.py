#!/usr/bin/env python3
"""KHANDAQ (audit 2026-08-21, K-05) — fail CI when a pinned relay dependency has a known advisory.

Pinning is what makes a build reproducible; it is also what makes it go stale. `requests==2.32.3`
was pinned and correct on the day it was written and had picked up two advisories by the time it was
audited. Nothing in the repository changed in between, so nothing on a push-triggered gate could
have noticed — which is why this lives in the scheduled workflow beside the CA-anchor check.

Reads infra/push/relay/requirements.txt (the hash-locked closure — DIRECT and transitive, because
transitive is where this bites) and queries api.osv.dev for each pinned version.

    python3 scripts/check-python-deps.py

  KHANDAQ_DEPS_ALLOW   comma-separated advisory IDs to accept, each with a reason in the source
                       below. Empty by default: an unreviewed advisory must fail.
  KHANDAQ_DEPS_OFFLINE set to 1 to skip the network query (still validates the lock file's shape),
                       so the check degrades to "cannot confirm" rather than to a false pass.
"""
import json
import os
import re
import sys
import urllib.error
import urllib.request

ROOT = os.environ.get("KHANDAQ_ROOT") or os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REQ = os.path.join(ROOT, "infra", "push", "relay", "requirements.txt")

# Advisories accepted after review. Every entry needs an ID and a reason; an empty dict is the
# correct steady state. Do NOT add one to make a red build green — add it when the advisory has been
# read and does not apply, and say why.
ALLOWED = {}
for _id in (a.strip() for a in os.environ.get("KHANDAQ_DEPS_ALLOW", "").split(",") if a.strip()):
    ALLOWED.setdefault(_id, "accepted via KHANDAQ_DEPS_ALLOW")


def die(msg):
    print("::error::" + msg, file=sys.stderr)
    sys.exit(1)


def parse_lock(path):
    """Every `name==version` line, with a hash count so a truncated lock cannot pass vacuously."""
    try:
        with open(path, encoding="utf-8") as fh:
            text = fh.read()
    except OSError as exc:
        die("cannot read %s (%s)" % (path, exc))

    pins, current = {}, None
    for line in text.splitlines():
        line = line.strip()
        if line.startswith("#") or not line:
            continue
        m = re.match(r"^([A-Za-z0-9_.\-]+)==([^\s\\]+)", line)
        if m:
            current = (m.group(1).lower(), m.group(2))
            pins[current] = 0
        elif line.startswith("--hash=") and current:
            pins[current] += 1
    if not pins:
        die("%s pins nothing — refusing to pass vacuously" % path)
    unhashed = [n for n, c in pins.items() if c == 0]
    if unhashed:
        die("these pins carry no --hash and would be installed unverified: %s"
            % ", ".join("%s==%s" % n for n in unhashed))
    return pins


def query_osv(name, version):
    body = json.dumps({"package": {"name": name, "ecosystem": "PyPI"}, "version": version}).encode()
    req = urllib.request.Request("https://api.osv.dev/v1/query", data=body,
                                 headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            return json.load(resp).get("vulns", []) or []
    except urllib.error.URLError as exc:
        # Fail rather than pass: "the vulnerability database was unreachable" is not "there are no
        # vulnerabilities", and a check that goes green on a network blip is worse than no check.
        die("OSV query for %s==%s failed (%s). Set KHANDAQ_DEPS_OFFLINE=1 to skip deliberately."
            % (name, version, exc))


def main():
    pins = parse_lock(REQ)
    print("%s pins %d packages, %d hashes" % (os.path.relpath(REQ, ROOT), len(pins), sum(pins.values())))

    if os.environ.get("KHANDAQ_DEPS_OFFLINE") == "1":
        print("KHANDAQ_DEPS_OFFLINE=1 — lock file validated, advisories NOT checked")
        return

    findings = []
    for name, version in sorted(pins):
        vulns = query_osv(name, version)
        unreviewed = []
        for v in vulns:
            ids = {v["id"]} | set(v.get("aliases", []))
            if ids & set(ALLOWED):
                continue
            unreviewed.append(v)
        if unreviewed:
            for v in unreviewed:
                fixed = sorted({e["fixed"]
                                for a in v.get("affected", [])
                                for r in a.get("ranges", [])
                                for e in r.get("events", []) if "fixed" in e})
                findings.append((name, version, v["id"], v.get("aliases", []), fixed,
                                 (v.get("summary") or "").strip()))
        else:
            print("  ok  %s==%s" % (name, version))

    if findings:
        for name, version, vid, aliases, fixed, summary in findings:
            print("::error::%s==%s is affected by %s%s%s — %s"
                  % (name, version, vid,
                     (" (%s)" % ", ".join(aliases)) if aliases else "",
                     (" fixed in %s" % ", ".join(fixed)) if fixed else " (no fixed version published)",
                     summary), file=sys.stderr)
        die("%d pinned relay dependenc%s carry known advisories. Bump requirements.in, regenerate "
            "with scripts/lock-relay-requirements.py, and commit the new lock."
            % (len(findings), "y" if len(findings) == 1 else "ies"))
    print("no known advisories against any pinned version")


if __name__ == "__main__":
    main()
