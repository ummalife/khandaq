#!/usr/bin/env python3
"""KHANDAQ (re-audit 2026-08-21, R-07) — everything published must match what is built.

The package name, the version and the iOS build number were each written by hand in the website, the
README and the release notes. They drifted, as hand-copied numbers do: the iOS build number appeared
as three different values in three places — two of them on the same page — the README's release tag
was thirty minor versions stale, and the site's own download cards carried two different tags.

That is not a vulnerability. It is worse in a specific, narrow way: provenance checking is precisely
the thing it defeats. A user who does the right thing and compares the package and version against
what was signed sees numbers that disagree, learns the comparison is meaningless, and stops doing it.

This compares every published claim against web/release-manifest.json, and the manifest itself
against the build files, so neither can drift alone.

    python3 scripts/check-release-metadata.py

Stdlib only, offline. Never passes vacuously: a pattern that stops matching is a failure, not a skip.
"""
import json
import os
import re
import sys

ROOT = os.environ.get("KHANDAQ_ROOT") or os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

MANIFEST = os.path.join(ROOT, "web", "release-manifest.json")
SITE = os.path.join(ROOT, "web", "index.html")
DOWNLOADS = os.path.join(ROOT, "web", "downloads", "index.html")
README = os.path.join(ROOT, "README.md")


def die(msg):
    print("::error::" + msg, file=sys.stderr)
    sys.exit(1)


def read(path, required=True):
    try:
        with open(path, encoding="utf-8", errors="replace") as fh:
            return fh.read()
    except OSError as exc:
        if required:
            die("cannot read %s (%s)" % (path, exc))
        return None


def load_manifest():
    try:
        with open(MANIFEST, encoding="utf-8") as fh:
            return json.load(fh)
    except OSError:
        die("web/release-manifest.json is missing — run scripts/generate-release-manifest.py")
    except ValueError as exc:
        die("web/release-manifest.json is not valid JSON (%s)" % exc)


def check_manifest_is_current(manifest):
    """
    The manifest is generated, so it can go stale exactly like the pages it governs. Rebuild the
    build-derived half in memory and require a match; the hand-maintained half is left alone.
    """
    try:
        import importlib.util
        spec = importlib.util.spec_from_file_location(
            "khandaq_relgen", os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                           "generate-release-manifest.py"))
        gen = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(gen)
    except Exception as exc:
        die("cannot load scripts/generate-release-manifest.py (%s)" % exc)

    fresh = gen.build_truth()
    for section in ("android", "ios"):
        if manifest.get(section) != fresh[section]:
            print("::error::web/release-manifest.json is stale in section %r." % section, file=sys.stderr)
            print("  committed: %s" % json.dumps(manifest.get(section), sort_keys=True), file=sys.stderr)
            print("  build says: %s" % json.dumps(fresh[section], sort_keys=True), file=sys.stderr)
            die("regenerate it: python3 scripts/generate-release-manifest.py")


CLAIMS = []


def claim(rel, text, pattern, what, expected):
    """Record every published statement of a fact. A pattern that matches nothing is a failure."""
    hits = []
    for n, line in enumerate(text.splitlines(), 1):
        for m in re.finditer(pattern, line):
            hits.append((n, m.group(1), line.strip()))
    if not hits:
        die("%s: no %s claim matched any more — the page changed shape and this check would have "
            "passed vacuously. Fix the pattern rather than deleting it." % (rel, what))
    for n, got, line in hits:
        CLAIMS.append((rel, n, what, got, expected, line))


def main():
    manifest = load_manifest()
    check_manifest_is_current(manifest)

    android, ios = manifest["android"], manifest["ios"]
    tag = manifest["desktop"]["releaseTag"]
    # KHANDAQ (re-review 2026-08-22, KQ-07): derived, not declared. While the package installed on
    # Android 5 and the site said 8, the claim had to be written down separately and reconciled by
    # hand. minSdkVersion is 26 now, so the site is checked against what the build files actually
    # produce — if someone lowers minSdkVersion, the site claim becomes wrong and this fails.
    # KHANDAQ (re-review 2026-08-22, KQ-07): a hard floor, not just consistency.
    #
    # Tying the site claim to minSdkVersion means the two can never disagree — but they could agree
    # at a LOWER number if somebody lowered both. Android 5, 6 and 7 receive no platform security
    # fixes, so shipping onto them is a decision that must be argued in a diff to this line, not made
    # by editing a build file.
    SUPPORTED_ANDROID_API_FLOOR = 26
    if int(android["minSdk"]) < SUPPORTED_ANDROID_API_FLOOR:
        die("minSdkVersion is %s, below the supported security floor of API %d (Android 8). Android "
            "5-7 no longer receive platform security fixes, so the app would be installing onto "
            "systems whose kernel, WebView and TLS stack it cannot patch. Lowering this is a product "
            "decision that belongs in a reviewed change to scripts/check-release-metadata.py."
            % (android["minSdk"], SUPPORTED_ANDROID_API_FLOOR))

    min_android = manifest["android"]["minAndroidRelease"]

    site = read(SITE)
    # KHANDAQ (release 0.2.40): the "Recent beta improvements" list is a HISTORY of past builds --
    # "voice notes fixed ... TestFlight build 142980" is a true statement about when that fix landed,
    # and rewriting it on every version bump would turn the page into a lie to satisfy a checker.
    # Only CURRENT claims are checked, so that block is excluded before scanning. Everything outside
    # it -- the download cards, the Play link, the README table -- is still required to match, and the
    # per-claim "matched nothing" failure below still guarantees this cannot silently cover the page.
    site_current = re.sub(r'<ul class="highlights">.*?</ul>', "", site, flags=re.S)
    # KHANDAQ (audit round 3, F-24): required, not optional.
    #
    # This was `required=False` with an `if downloads:` guard below, so moving or renaming the
    # downloads page turned its checks into a silent skip — the exact vacuous pass this script's own
    # docstring promises never to allow, and the re-audit response describes the check as
    # unconditional. If the page is genuinely gone, deleting the claim here is a reviewed change;
    # having it evaporate because a path moved is not.
    downloads = read(DOWNLOADS)
    readme = read(README)

    # --- the website -----------------------------------------------------------------------
    claim("web/index.html", site_current, r"Package <code>([a-z0-9_.]+)</code>",
          "Android package", android["applicationId"])
    claim("web/index.html", site_current, r"play\.google\.com/store/apps/details\?id=([a-z0-9_.]+)",
          "Play listing id", android["applicationId"])
    claim("web/index.html", site_current, r"Android (\d+)\+",
          "minimum Android release", min_android)
    claim("web/index.html", site_current, r"TestFlight build (\d+)",
          "iOS build number", ios["buildNumber"])
    claim("web/index.html", site_current, r"var release = '(v[0-9.]+)'",
          "desktop release tag", tag)
    # The three desktop cards each label which release their download came from. They had drifted a
    # tag behind the links right next to them, which is the most confusing possible combination.
    claim("web/index.html", site_current, r"(v[0-9.]+) release ", "desktop card release tag", tag)

    # The Android card states the PLAY version, which is versionName — not the desktop release tag.
    m = re.search(r"<h3>Android</h3>.*?<span class=\"meta\">v([0-9.]+)", site_current, re.S)
    if not m:
        die("web/index.html: the Android card's version line no longer matches — refusing to pass "
            "vacuously on the one number Play users compare against")
    CLAIMS.append(("web/index.html", site_current[:m.start(1)].count("\n") + 1, "Android card version",
                   m.group(1), android["versionName"], "<span class=\"meta\">v%s ..." % m.group(1)))

    claim("web/downloads/index.html", downloads,
          r"play\.google\.com/store/apps/details\?id=([a-z0-9_.]+)",
          "Play listing id", android["applicationId"])

    # --- the README ------------------------------------------------------------------------
    claim("README.md", readme, r"releases/tag/(v[0-9.]+)", "release tag", tag)
    claim("README.md", readme, r"releases/download/(v[0-9.]+)/", "release download tag", tag)
    claim("README.md", readme, r"TestFlight\]\([^)]*\) \(build (\d+)", "iOS build number", ios["buildNumber"])

    bad = [c for c in CLAIMS if c[3] != c[4]]
    print("checked %d published identity/version claims against web/release-manifest.json" % len(CLAIMS))
    print("  android.applicationId  %s" % android["applicationId"])
    print("  android.versionName    %s   (Play; NOT the release tag)" % android["versionName"])
    print("  desktop.releaseTag     %s   (download URLs are built from this)" % tag)
    print("  ios.buildNumber        %s" % ios["buildNumber"])
    print("  site claims Android    %s+  (minSdkVersion is %s = Android %s)"
          % (min_android, android["minSdk"], android["minAndroidRelease"]))
    if bad:
        for rel, n, what, got, expected, line in bad:
            print("::error file=%s,line=%d::%s says %r, the manifest says %r: %s"
                  % (rel, n, what, got, expected, line), file=sys.stderr)
        die("%d published claim(s) drifted from the release manifest" % len(bad))
    print("every published claim matches")
    return 0


if __name__ == "__main__":
    sys.exit(main())
