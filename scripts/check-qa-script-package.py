#!/usr/bin/env python3
"""KHANDAQ (release 0.2.40) — a QA script aimed at the wrong package tests nothing, and reports green.

The Android applicationId is com.khandaq.messenger while the namespace stayed org.khandaq.messenger,
and fifteen QA and smoke scripts hardcoded the namespace. Every one of them would run to completion
against a package that is not installed: `am start` finds nothing, `pm clear` finds nothing, and the
script prints whatever its closing echo says. A QA run that never touched the app is worse than no QA
run at all, because somebody reads the summary and believes it.

That is this project's recurring failure seen from yet another angle: a check that cannot fail looks
exactly like a check that passes.

The rule: any script hardcoding a Khandaq package must agree with the applicationId in build.gradle.

    python3 scripts/check-qa-script-package.py

Stdlib only, offline. Never passes vacuously — if it stops finding package declarations, or cannot
read the applicationId, it fails rather than reporting success.
"""
import os
import re
import sys

ROOT = os.environ.get("KHANDAQ_ROOT") or os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SCRIPTS = os.path.join(ROOT, "scripts")
GRADLE = os.path.join(ROOT, "khandaq-android-trifa", "android-refimpl-app", "app", "build.gradle")

# The rebrand scripts RECORD the move that created the org.* namespace — their package is history,
# not a live target. Exempted by name, with the reason, rather than by a pattern that could widen.
EXEMPT = {
    "android-rebrand-migrate.sh": "performed the original rebrand; its package is what it renamed TO",
    "android-rebrand-revert-java-move.sh": "the paired revert of that same rebrand",
}

DECL = re.compile(r'^\s*(?:PKG|APP_ID|PACKAGE)\s*=\s*"([A-Za-z][A-Za-z0-9_.]*)"', re.M)

# The tree has 16 today. The floor is here so that losing the ability to SEE them fails loudly.
MIN_SCRIPTS_WITH_PKG = 10


def die(msg):
    print("::error::" + msg, file=sys.stderr)
    sys.exit(1)


def main():
    try:
        with open(GRADLE, encoding="utf-8", errors="replace") as fh:
            gradle = fh.read()
    except OSError as exc:
        die("cannot read %s (%s)" % (GRADLE, exc))

    m = re.search(r'^\s*applicationId\s+"([^"]+)"', gradle, re.M)
    if not m:
        die("no applicationId in app/build.gradle — the parser has drifted from the file, and a "
            "vacuous pass is worse than no check at all")
    app_id = m.group(1)

    if not os.path.isdir(SCRIPTS):
        die("scripts/ does not exist")

    bad, seen, exempted = [], 0, []
    for name in sorted(os.listdir(SCRIPTS)):
        if not name.endswith(".sh"):
            continue
        with open(os.path.join(SCRIPTS, name), encoding="utf-8", errors="replace") as fh:
            text = fh.read()
        for n, line in enumerate(text.splitlines(), 1):
            d = DECL.match(line)
            if not d:
                continue
            declared = d.group(1)
            if not declared.endswith("khandaq.messenger"):
                continue                      # some other package; not ours to police
            seen += 1
            if name in EXEMPT:
                exempted.append((name, EXEMPT[name]))
                continue
            if declared != app_id:
                bad.append((name, n, declared, line.strip()))

    print("scripts declaring a Khandaq package: %d (applicationId is %s)" % (seen, app_id))
    for name, why in exempted:
        print("  exempt  %s — %s" % (name, why))

    if seen < MIN_SCRIPTS_WITH_PKG:
        die("only %d script(s) declare a package, expected at least %d — either the scripts changed "
            "shape or this check stopped recognising the declaration. Fix the pattern; do not lower "
            "the floor without looking." % (seen, MIN_SCRIPTS_WITH_PKG))

    if bad:
        for name, n, declared, line in bad:
            print("::error file=scripts/%s,line=%d::declares %s, but the app installs as %s. This "
                  "script would drive a package that is not on the device and finish reporting "
                  "success without ever touching the app." % (name, n, declared, app_id),
                  file=sys.stderr)
            print("    %s" % line, file=sys.stderr)
        die("%d QA script(s) target the wrong package" % len(bad))

    print("every QA script targets the package the app actually installs as")
    return 0


if __name__ == "__main__":
    sys.exit(main())
