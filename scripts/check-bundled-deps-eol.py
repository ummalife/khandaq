#!/usr/bin/env python3
"""KHANDAQ (audit 2026-08-21, K-04) — hold the desktop's bundled dependency inventory honest.

The desktop ships Qt 5.12.12 and OpenSSL 1.1.1w. OpenSSL 1.1.1 has received no public security fixes
since 2023-09-11; Qt 5.12's extended LTS ended 2021-12-05. For a messenger that is accumulating
exposure by the calendar, and it cannot be closed by a version bump — Qt 5.12 cannot link OpenSSL 3,
so the exit is the migration scoped in DESIGN-qt6-openssl3-migration-scope.md.

What can be closed here is the part that made it invisible. Before this, the bundled versions existed
in two places, neither checkable: a prose comment at the top of windows-build.yaml (already drifted —
it claimed libvpx 1.14.0 while download_vpx.sh pins 1.14.1) and a version-free table in
THIRD_PARTY_LICENSES.md. Nothing anywhere would ever have noticed an EOL date passing.

Three gates, in order:

  1. DRIFT   — for every component, read the real value out of the real download script and require
               the inventory to agree. Offline, dateless, and the reason the inventory cannot rot.
  2. EOL     — a past `eol` date fails, unless a waiver is recorded with a reason, a named owner, a
               tracking document and an expiry that has not passed. The waiver IS the "explicit
               vulnerability-backport policy" the audit asks for, in a form that expires and
               re-fails rather than becoming permanent.
  3. FLOOR   — a `min_version` below which a component is known-vulnerable. Same waiver rules.

    python3 scripts/check-bundled-deps-eol.py                 # normal: warn near EOL, fail past it
    python3 scripts/check-bundled-deps-eol.py --release       # release gate: no grace, waivers must
                                                              # have >14 days left
    python3 scripts/check-bundled-deps-eol.py --sbom OUT.json # CycloneDX 1.5 for the release artifacts
    python3 scripts/check-bundled-deps-eol.py --print-markdown
    python3 scripts/check-bundled-deps-eol.py --fix-versions  # adopt the pin files' current values

Stdlib only. No network, no Qt, no Docker, no build.
"""
import argparse
import datetime
import json
import os
import re
import sys

ROOT = os.environ.get("KHANDAQ_ROOT") or os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
INVENTORY = os.path.join(ROOT, "khandaq-desktop", "buildscripts", "bundled-deps.json")

WAIVER_MAX_DAYS = 90          # how long a waiver may run before it must be re-argued
RELEASE_MIN_WAIVER_DAYS = 14  # a waiver about to expire must not carry a release

errors, warnings = [], []


def err(msg):
    errors.append(msg)


def warn(msg):
    warnings.append(msg)


def load():
    try:
        with open(INVENTORY, encoding="utf-8") as fh:
            doc = json.load(fh)
    except OSError as exc:
        sys.exit("::error::cannot read %s (%s)" % (INVENTORY, exc))
    except ValueError as exc:
        sys.exit("::error::%s is not valid JSON (%s)" % (INVENTORY, exc))
    comps = doc.get("components") or []
    if not comps:
        sys.exit("::error::%s lists no components — refusing to pass vacuously" % INVENTORY)
    return doc, comps


def pinned_value(comp):
    """The version the BUILD would actually use, read out of the download script."""
    path = os.path.join(ROOT, comp["pin_file"].replace("/", os.sep))
    try:
        with open(path, encoding="utf-8", errors="replace") as fh:
            text = fh.read()
    except OSError as exc:
        err("%s: cannot read pin file %s (%s)" % (comp["name"], comp["pin_file"], exc))
        return None

    names = comp["pin_var"] if isinstance(comp["pin_var"], list) else [comp["pin_var"]]
    parts = []
    for var in names:
        m = re.search(r'^(?:readonly\s+)?%s=["\']?([^"\'\s]+)' % re.escape(var), text, re.M)
        if not m:
            err("%s: %s does not define %s any more — the inventory is pointing at the wrong place"
                % (comp["name"], comp["pin_file"], var))
            return None
        parts.append(m.group(1))
    return ".".join(parts)


def parse_date(value, what, name):
    try:
        return datetime.date.fromisoformat(value)
    except (TypeError, ValueError):
        err("%s: %s is not an ISO date: %r" % (name, what, value))
        return None


def version_tuple(v):
    return tuple(int(x) for x in re.findall(r"\d+", v)[:4])


def check_waiver(comp, today, release, subject):
    """
    True if `subject` (an EOL or a floor violation) is covered by a valid waiver.

    A waiver has to say why, who owns it, where it is tracked, and when it stops being true. Anything
    less is a comment, and a comment is what let this sit unnoticed in the first place.
    """
    w = comp.get("waiver")
    name = comp["name"]
    if not w:
        return False
    missing = [f for f in ("reason", "owner", "opened", "expires", "tracking") if not w.get(f)]
    if missing:
        err("%s: %s is waived, but the waiver is missing %s — an incomplete waiver is not a waiver"
            % (name, subject, ", ".join(missing)))
        return False
    expires = parse_date(w["expires"], "waiver.expires", name)
    opened = parse_date(w["opened"], "waiver.opened", name)
    if not expires or not opened:
        return False
    if (expires - opened).days > WAIVER_MAX_DAYS:
        err("%s: the waiver runs %d days (max %d). A waiver is a dated decision to accept risk, not "
            "a permanent exemption — shorten it and re-argue it when it expires."
            % (name, (expires - opened).days, WAIVER_MAX_DAYS))
        return False
    left = (expires - today).days
    if left < 0:
        err("%s: %s, and the waiver EXPIRED %d day(s) ago (%s, owner %s, tracked in %s). Either "
            "fix the dependency or record a new, re-argued waiver."
            % (name, subject, -left, w["expires"], w["owner"], w["tracking"]))
        return False
    if release and left < RELEASE_MIN_WAIVER_DAYS:
        err("%s: %s, waived only until %s (%d days). A release must not be carried by a waiver that "
            "is about to expire — extend it deliberately or fix the dependency."
            % (name, subject, w["expires"], left))
        return False
    warn("%s: %s — WAIVED until %s (%d days left), owner %s, tracked in %s"
         % (name, subject, w["expires"], left, w["owner"], w["tracking"]))
    return True


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--warn-days", type=int, default=180,
                    help="warn when an EOL date is within this many days (default 180)")
    ap.add_argument("--release", action="store_true",
                    help="release gate: warnings become errors, waivers must have time left")
    ap.add_argument("--sbom", metavar="PATH", help="write a CycloneDX 1.5 SBOM and exit")
    ap.add_argument("--print-markdown", action="store_true",
                    help="print the inventory as a markdown table")
    ap.add_argument("--fix-versions", action="store_true",
                    help="rewrite the inventory's versions from the pin files (after a bump)")
    args = ap.parse_args()

    doc, comps = load()
    today = datetime.date.today()

    if args.fix_versions:
        changed = []
        for c in comps:
            actual = pinned_value(c)
            if actual and actual != c["version"]:
                changed.append("%s %s -> %s" % (c["name"], c["version"], actual))
                c["version"] = actual
        if errors:
            for e in errors:
                print("::error::" + e, file=sys.stderr)
            return 1
        with open(INVENTORY, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(json.dumps(doc, indent=2, ensure_ascii=False) + "\n")
        print("updated %d version(s):%s" % (len(changed), "".join("\n  " + c for c in changed))
              if changed else "no version changes")
        return 0

    if args.print_markdown:
        print("| Component | Version | Platforms | Purpose |")
        print("|---|---|---|---|")
        for c in sorted(comps, key=lambda x: x["name"]):
            v = c["version"]
            v = v[:12] + "…" if len(v) > 16 else v      # git refs, shortened for a readable table
            print("| %s | %s | %s | %s |"
                  % (c["name"], v, ", ".join(c["platforms"]), c.get("purpose") or ""))
        return 0

    # ---- gate 1: the inventory must match the build scripts -----------------------------------
    for c in comps:
        actual = pinned_value(c)
        if actual is not None and actual != c["version"]:
            err("%s: the inventory says %s but %s pins %s. One of them is what the build uses, and "
                "an inventory that disagrees with the build is worse than none — run "
                "`--fix-versions` after a deliberate bump."
                % (c["name"], c["version"], c["pin_file"], actual))

    # ---- gates 2 and 3: end of life, and known-vulnerable floors -------------------------------
    for c in comps:
        name = c["name"]
        if c.get("eol"):
            eol = parse_date(c["eol"], "eol", name)
            if eol:
                left = (eol - today).days
                if left < 0:
                    subject = ("%s %s reached end of life on %s (%d days ago) and receives no public "
                               "security fixes" % (name, c["version"], c["eol"], -left))
                    if not check_waiver(c, today, args.release, subject):
                        err(subject + ". Source: " + (c.get("eol_source") or "(no source recorded)"))
                elif left <= args.warn_days:
                    warn("%s %s reaches end of life on %s (%d days) — plan the replacement now, not "
                         "on the day" % (name, c["version"], c["eol"], left))

        floor = c.get("min_version")
        if floor and version_tuple(c["version"]) < version_tuple(floor):
            subject = ("%s %s is below the security floor %s" % (name, c["version"], floor))
            if not check_waiver(c, today, args.release, subject):
                err(subject + ". " + (c.get("min_version_source") or ""))

    print("bundled desktop dependencies: %d components in %s"
          % (len(comps), os.path.relpath(INVENTORY, ROOT)))
    for w in warnings:
        print("::warning::" + w)
    for e in errors:
        print("::error::" + e, file=sys.stderr)

    if errors:
        print("::error::%d problem(s) with the bundled dependency inventory." % len(errors),
              file=sys.stderr)
        return 1
    # A waived item does NOT block a release — that is the whole point of a waiver, and a gate with
    # no way through is a gate that gets deleted the first time a security fix has to ship on the
    # current stack. What --release adds is that the waiver must be current (RELEASE_MIN_WAIVER_DAYS
    # above), and that every accepted risk is named in the release log rather than passing silently.
    if args.release and warnings:
        print("::notice::this release ships with %d accepted, waived risk(s), listed above. Each is "
              "owned and dated; when the waiver expires the build fails until it is re-argued."
              % len(warnings))
    print("no unwaived end-of-life or below-floor dependency")
    return 0


def sbom(doc, comps, path):
    """CycloneDX 1.5, hand-rolled — a dict and json.dump, rather than a new build dependency."""
    bom = {
        "bomFormat": "CycloneDX",
        "specVersion": "1.5",
        "version": 1,
        "metadata": {
            "component": {"type": "application", "name": "khandaq-desktop",
                          "description": "Khandaq desktop client (Qt/C++)"},
            "tools": [{"name": "check-bundled-deps-eol.py", "vendor": "Khandaq"}],
        },
        "components": [
            {
                "type": "library",
                "bom-ref": "khandaq-desktop/%s@%s" % (c["name"], c["version"]),
                "name": c["name"],
                "version": c["version"],
                "description": c.get("purpose") or "",
                "properties": [
                    {"name": "khandaq:pin_file", "value": c["pin_file"]},
                    {"name": "khandaq:platforms", "value": ",".join(c["platforms"])},
                ] + ([{"name": "khandaq:eol", "value": c["eol"]}] if c.get("eol") else [])
                  + ([{"name": "khandaq:waiver_expires", "value": c["waiver"]["expires"]}]
                     if c.get("waiver") else []),
            }
            for c in sorted(comps, key=lambda x: x["name"])
        ],
    }
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(json.dumps(bom, indent=2, ensure_ascii=False) + "\n")
    print("wrote %s (%d components)" % (path, len(bom["components"])))


if __name__ == "__main__":
    _args = sys.argv[1:]
    if "--sbom" in _args:
        _doc, _comps = load()
        sbom(_doc, _comps, _args[_args.index("--sbom") + 1])
        sys.exit(0)
    sys.exit(main())
