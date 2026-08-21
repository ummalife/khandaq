#!/usr/bin/env python3
"""KHANDAQ (re-audit 2026-08-21, R-07) — one generated source of truth for published identity.

The website, the README and the release notes each stated the package name, the version and the iOS
build number by hand. Predictably they disagreed: the iOS build number appeared as three different
values in three places (two of them on the SAME page), the README's release tag was thirty minor
versions stale, and the site's own download cards mixed two release tags. None of that is a
vulnerability, but provenance checking is exactly the thing it breaks — a user comparing the package
and version before installing sees numbers that do not match what was signed.

So the numbers are derived here, from the build files, and everything published is checked against
them by scripts/check-release-metadata.py.

ONE DISTINCTION MATTERS MORE THAN THE REST, and getting it wrong would break every desktop download
link: the Android `versionName` (0.2.38 — what Google Play shows) and the GitHub RELEASE TAG
(v0.2.12 — what the .zip/.deb URLs are built from) are different things that both look like a
version. There is no v0.2.38 tag. `desktop.releaseTag` is therefore hand-maintained, but it exists
in exactly one place instead of nine.

    python3 scripts/generate-release-manifest.py        # rewrite web/release-manifest.json
    python3 scripts/generate-release-manifest.py --print # show it without writing
"""
import json
import os
import re
import sys

ROOT = os.environ.get("KHANDAQ_ROOT") or os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
GRADLE = os.path.join(ROOT, "khandaq-android-trifa", "android-refimpl-app", "app", "build.gradle")
PBX = os.path.join(ROOT, "khandaq-ios", "Antidote.xcodeproj", "project.pbxproj")
MANIFEST = os.path.join(ROOT, "web", "release-manifest.json")

# API level -> the marketing "Android N+" a page is allowed to claim from it.
API_TO_ANDROID = {21: "5", 22: "5", 23: "6", 24: "7", 25: "7", 26: "8", 27: "8",
                  28: "9", 29: "10", 30: "11", 31: "12", 32: "12", 33: "13", 34: "14", 35: "15", 36: "16"}


def die(msg):
    print("::error::" + msg, file=sys.stderr)
    sys.exit(1)


def read(path):
    try:
        with open(path, encoding="utf-8", errors="replace") as fh:
            return fh.read()
    except OSError as exc:
        die("cannot read %s (%s)" % (path, exc))


def one(pattern, text, what, where):
    """Exactly one distinct value, or a hard failure — never a silent first-match."""
    found = re.findall(pattern, text, re.M)
    if not found:
        die("no %s in %s — the parser has drifted from the file, and a vacuous pass is worse than "
            "no check at all" % (what, where))
    if len(set(found)) != 1:
        die("%s is written %d different ways in %s: %s"
            % (what, len(set(found)), where, sorted(set(found))))
    return found[0]


def build_truth():
    g, p = read(GRADLE), read(PBX)
    min_sdk = one(r"^\s*minSdkVersion\s+(\d+)", g, "minSdkVersion", "build.gradle")
    if int(min_sdk) not in API_TO_ANDROID:
        die("minSdkVersion %s is not in API_TO_ANDROID — add it rather than skipping the check" % min_sdk)
    return {
        "android": {
            "applicationId": one(r'^\s*applicationId\s+"([^"]+)"', g, "applicationId", "build.gradle"),
            # NOT the published identity: R/BuildConfig only. Recorded so the difference is explicit,
            # because it is exactly what the re-audit mistook for the package name.
            "namespace": one(r"^\s*namespace\s+'([^']+)'", g, "namespace", "build.gradle"),
            "versionName": one(r'^\s*versionName\s+"([^"]+)"', g, "versionName", "build.gradle"),
            "versionCode": int(one(r"^\s*versionCode\s+(\d+)", g, "versionCode", "build.gradle")),
            "minSdk": int(min_sdk),
            "minAndroidRelease": API_TO_ANDROID[int(min_sdk)],
            "targetSdk": int(one(r"^\s*targetSdkVersion\s+(\d+)", g, "targetSdkVersion", "build.gradle")),
        },
        "ios": {
            "bundleId": one(r"^\s*PRODUCT_BUNDLE_IDENTIFIER = (org\.khandaq\.messenger);", p,
                            "PRODUCT_BUNDLE_IDENTIFIER", "project.pbxproj"),
            "marketingVersion": one(r"^\s*MARKETING_VERSION = ([0-9.]+);", p,
                                    "MARKETING_VERSION", "project.pbxproj"),
            "buildNumber": one(r"^\s*CURRENT_PROJECT_VERSION = (\d+);", p,
                               "CURRENT_PROJECT_VERSION", "project.pbxproj"),
        },
    }


# Hand-maintained, and deliberately so — nothing in the build files knows these.
HAND_MAINTAINED = {
    "desktop": {
        "releaseTag": "v0.2.12",
        "_comment": "The GitHub release the desktop .zip/.deb URLs are built from. NOT the Android "
                    "versionName: there is no v0.2.38 tag, and aligning them breaks every download link.",
    },
    "site": {
        "minAndroidReleaseClaimed": "8",
        "_comment": "The site says 'Android 8+' while minSdkVersion is 21 (Android 5). Deliberate for "
                    "now: the app has never been QA'd below 8, and the database-key path was crashing "
                    "outright on 5.x until the re-audit fix. Raising minSdkVersion to 23+ would let "
                    "this field go away — a product decision about which users to drop, so it is "
                    "recorded here rather than silently taken.",
    },
}


def build_manifest():
    m = build_truth()
    m.update(HAND_MAINTAINED)
    m["_generated_by"] = "scripts/generate-release-manifest.py"
    m["_checked_by"] = "scripts/check-release-metadata.py"
    return m


def main():
    m = build_manifest()
    text = json.dumps(m, indent=2, ensure_ascii=False, sort_keys=True) + "\n"
    if "--print" in sys.argv:
        sys.stdout.write(text)
        return 0
    os.makedirs(os.path.dirname(MANIFEST), exist_ok=True)
    with open(MANIFEST, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(text)
    print("wrote %s" % os.path.relpath(MANIFEST, ROOT))
    sys.stdout.write(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
