#!/usr/bin/env python3
"""KHANDAQ (audit 2026-08-21, K-07) — no CocoaPods/fastlane invocation may escape Bundler.

khandaq-ios/Gemfile pins CocoaPods 1.16.2 and fastlane 2.237.0 and Gemfile.lock is committed, but a
lockfile only binds the build if the build is actually run through Bundler. It was not: `pod install`
and `fastlane` were invoked bare, so the versions that shipped were whatever Homebrew had put on the
release Mac, and the committed lock described a toolchain nobody used.

The invocations are fixed; this keeps them fixed. It is a text check on purpose — no CI job in this
repository builds iOS (every root workflow is `runs-on: ubuntu-latest`), so without something that
runs on Linux there is nothing at all holding the property, and a single re-introduced bare `pod`
would silently unpin the release again.

    python3 scripts/check-ios-bundler.py
"""
import os
import re
import sys

ROOT = os.environ.get("KHANDAQ_ROOT") or os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# Anything that runs `pod` or `fastlane` (or the fastlane actions that resolve gems) as a command.
INVOCATION = re.compile(
    r"""(?:^|[;&|]\s*|\$\(\s*|`\s*)      # start of a command, not the middle of a word
        (?P<prefix>[A-Za-z_][A-Za-z0-9_]*=\S+\s+)*   # leading VAR=value assignments
        (?P<cmd>pod|fastlane|gym|match)\b""",
    re.VERBOSE)

# Where an unguarded invocation matters. Vendored upstream code is excluded — it is not our build.
SEARCH_DIRS = ["scripts", "khandaq-ios/scripts", "khandaq-ios/fastlane", "docs",
               ".github/workflows", "khandaq-ios/.github/workflows"]
SEARCH_FILES = ["khandaq-ios/README.md", "README.md", "docs/BUILDING.md"]
EXTENSIONS = (".sh", ".yml", ".yaml", ".md", ".bash")

# The opt-out marker, and it is deliberately wordy: `# khandaq-bundler-ok: <reason>`. A bare pragma
# would get copied around; one that has to state a reason gets read.
EXEMPT = re.compile(r"#\s*khandaq-bundler-ok:\s*\S")

EXCLUDED = (
    # Vendored upstream pod, with its own (unlocked) Gemfile. Not part of the Khandaq build.
    "khandaq-ios/local_pod_repo/",
    # Disabled upstream fastlane tree, kept for reference only.
    "khandaq-ios/XOLDX_",
)


def die(msg):
    print("::error::" + msg, file=sys.stderr)
    sys.exit(1)


def candidate_files():
    seen = []
    for rel in SEARCH_DIRS:
        base = os.path.join(ROOT, rel.replace("/", os.sep))
        if not os.path.isdir(base):
            continue
        for dirpath, _dirnames, filenames in os.walk(base):
            for fn in filenames:
                if fn.endswith(EXTENSIONS):
                    seen.append(os.path.join(dirpath, fn))
    for rel in SEARCH_FILES:
        p = os.path.join(ROOT, rel.replace("/", os.sep))
        if os.path.isfile(p):
            seen.append(p)
    out = []
    for p in sorted(set(seen)):
        rel = os.path.relpath(p, ROOT).replace(os.sep, "/")
        if not any(rel.startswith(x) for x in EXCLUDED):
            out.append((rel, p))
    return out


def offending_lines(text, markdown=False, exemptions=None):
    """
    Lines that run pod/fastlane without Bundler.

    In markdown only FENCED CODE is inspected. Prose mentions the commands constantly — including
    the sentence in docs/BUILDING.md explaining why a bare `pod` is wrong — and a checker that flags
    its own documentation is a checker somebody switches off.
    """
    bad = []
    exemptions = exemptions if exemptions is not None else []
    in_fence = False
    for n, raw in enumerate(text.splitlines(), 1):
        line = raw.strip()
        # An explicit, reasoned opt-out for the rare line that runs `pod` on purpose WITHOUT wanting
        # the pinned one — reporting the host's global version for contrast, for instance. It has to
        # carry a reason, and the run prints every exemption, so this cannot quietly become the way
        # the rule is avoided.
        if EXEMPT.search(line):
            exemptions.append((n, line))
            continue
        if markdown:
            if line.startswith("```") or line.startswith("~~~"):
                in_fence = not in_fence
                continue
            if not in_fence:
                continue
        if not line or line.startswith("#") or line.lstrip("#").strip().startswith("KHANDAQ"):
            continue
        # A comment anywhere on the line: only inspect the part before it. Crude but right here —
        # these files quote commands in prose, and a false positive in a gate is how gates get
        # switched off.
        code = re.split(r"\s#", line)[0]
        for m in INVOCATION.finditer(code):
            before = code[:m.start()]
            if "bundle exec" in before:
                continue
            # `bundle exec pod` may also be reached through a variable, e.g. $POD.
            if re.search(r"bundle\s+exec\s*$", before.rstrip()):
                continue
            bad.append((n, raw.rstrip(), m.group("cmd")))
    return bad


def main():
    files = candidate_files()
    if not files:
        die("found no files to scan — the search paths are wrong, and a vacuous pass is worse than "
            "no check")

    findings, exempted = [], []
    for rel, path in files:
        with open(path, encoding="utf-8", errors="replace") as fh:
            text = fh.read()
        exempt = []
        for lineno, line, cmd in offending_lines(text, markdown=rel.endswith('.md'),
                                                 exemptions=exempt):
            findings.append((rel, lineno, cmd, line))
        for lineno, line in exempt:
            exempted.append((rel, lineno, line))

    print("scanned %d files for un-Bundlered pod/fastlane invocations" % len(files))
    for rel, lineno, line in exempted:
        print("  exempt  %s:%d  %s" % (rel, lineno, line.strip()))
    if findings:
        for rel, lineno, cmd, line in findings:
            print("::error file=%s,line=%d::`%s` is invoked without `bundle exec` — it will use "
                  "whatever version the machine has, not khandaq-ios/Gemfile.lock: %s"
                  % (rel, lineno, cmd, line.strip()), file=sys.stderr)
        die("%d un-Bundlered CocoaPods/fastlane invocation(s). Prefix with `bundle exec` and set "
            "BUNDLE_GEMFILE to khandaq-ios/Gemfile (objcTox has its own Gemfile pinning a far older "
            "CocoaPods, so the working directory is not a safe default)." % len(findings))
    print("every pod/fastlane invocation goes through bundle exec")


if __name__ == "__main__":
    main()
