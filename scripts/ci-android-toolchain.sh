#!/usr/bin/env bash
# KHANDAQ (audit 2026-08-21, K-06) — provision the Android build toolchain, fail closed, prove the
# exact revisions ended up on disk, and prove nothing else was pulled in behind our back.
#
# What was there before: two lines in release-provenance.yml,
#     yes | sudo "$SDKMGR" --install "ndk;21.0.6113669" ... || true
#     yes | sudo "$SDKMGR" --install "platforms;android-36" ... || true
# and the same shape in witness-checksums-update.yml. Three separate defects sat in them, and each
# one hid the next:
#
#  1. `|| true`. A failed install was ignored, so the build continued against whatever the runner
#     image happened to carry. The attestation still came out valid — provenance attests what the
#     workflow produced, not that the intended toolchain produced it.
#
#  2. `yes | ... ` under `set -o pipefail`. sdkmanager exits first, `yes` then dies of SIGPIPE (141),
#     and the PIPELINE reports 141 even on a completely successful install. So `|| true` was not just
#     sloppy, it was load-bearing: without it the step would have failed every single time. Which
#     means we cannot know these installs ever succeeded — they may have been failing all along.
#
#  3. The version was wrong anyway. It installed `ndk;21.0.6113669`, but the two modules that
#     actually compile C into the shipped APK — :native-audio-jni and :loggingstdout, both
#     `implementation project(...)` dependencies of :app — declare `ndkVersion "23.2.8568313"`.
#     `build-tools` was never installed at all, and that is aapt2/d8/zipalign/apksigner, i.e. the
#     tools that emit the APK bytes. Releases nevertheless succeeded, which is the proof of what is
#     really happening: AGP's `android.builder.sdkDownload` defaults to true, so Gradle quietly
#     fetched whatever it needed from dl.google.com at task time. The pinned-toolchain step was
#     decorative.
#
# Hence: read every version out of the build files (so this script cannot drift from them), install
# fail-closed, verify against each package's own source.properties (the file Gradle reads — not
# sdkmanager's report of its own work), and snapshot the installed set before the build so an
# auto-download during the build can be caught before anything is attested.
#
# Usage (paths are relative to the repo root):
#   ci-android-toolchain.sh parse            <gradle-project-dir>
#   ci-android-toolchain.sh install          <gradle-project-dir>
#   ci-android-toolchain.sh verify           <gradle-project-dir>
#   ci-android-toolchain.sh snapshot         <outfile>
#   ci-android-toolchain.sh assert-unchanged <before> <after>
#   ci-android-toolchain.sh record           <gradle-project-dir> <outfile>
set -euo pipefail

die() { echo "::error::$*" >&2; exit 1; }

# Read one declaration out of a build.gradle. Anchored and `head -1` on purpose: app/build.gradle
# nests a second `android { }` block, so a loose match can pick the wrong occurrence.
gradle_str() { sed -n "s/^[[:space:]]*$1[[:space:]]*\"\([^\"]*\)\".*/\1/p" "$2" | head -1; }
gradle_num() { sed -n "s/^[[:space:]]*$1[[:space:]]*\([0-9][0-9]*\).*/\1/p" "$2" | head -1; }

parse_gradle() {
    PROJ=$1
    [ -d "$PROJ" ] || die "gradle project dir not found: $PROJ"
    local app="$PROJ/app/build.gradle"
    [ -f "$app" ] || die "not a gradle project: $app is missing"

    COMPILE_SDK=$(gradle_num compileSdkVersion "$app")
    BUILD_TOOLS=$(gradle_str buildToolsVersion "$app")
    [ -n "$COMPILE_SDK" ] || die "could not read compileSdkVersion out of $app"
    [ -n "$BUILD_TOOLS" ] || die "could not read buildToolsVersion out of $app"

    # EVERY module's ndkVersion, not just :app's. :app declares one but compiles no native code
    # itself; the modules it depends on declare another and do. Installing only :app's would repeat
    # the original defect in a new place.
    NDK_VERSIONS=$(
        find "$PROJ" -name build.gradle -not -path '*/build/*' -print0 \
        | xargs -0 -r sed -n 's/^[[:space:]]*ndkVersion[[:space:]]*"\([^"]*\)".*/\1/p' \
        | sort -u | tr '\n' ' '
    )
    NDK_VERSIONS=${NDK_VERSIONS% }
    [ -n "$NDK_VERSIONS" ] || die "no ndkVersion declared anywhere under $PROJ"
}

# One installed package, one assertion, against the property Gradle itself keys on.
assert_prop() {
    local file=$1 key=$2 want=$3 got
    [ -f "$file" ] || die "$file is missing — the package was not installed (this used to be swallowed by '|| true')"
    got=$(sed -n "s/^${key}=//p" "$file" | head -1 | tr -d '\r')
    [ "$got" = "$want" ] || die "$file: $key is '$got', expected '$want'"
    echo "    ok: $key=$got  ($file)"
}

verify_installed() {
    local root=${ANDROID_SDK_ROOT:-}
    [ -n "$root" ] || die "ANDROID_SDK_ROOT is not set"
    echo "==> verifying the toolchain on disk"
    local v
    for v in $NDK_VERSIONS; do
        assert_prop "$root/ndk/$v/source.properties"                        "Pkg.Revision"            "$v"
    done
    assert_prop "$root/platforms/android-$COMPILE_SDK/source.properties"    "AndroidVersion.ApiLevel" "$COMPILE_SDK"
    assert_prop "$root/build-tools/$BUILD_TOOLS/source.properties"          "Pkg.Revision"            "$BUILD_TOOLS"
}

sdkmgr() {
    local m="${ANDROID_SDK_ROOT:-}/cmdline-tools/latest/bin/sdkmanager"
    [ -x "$m" ] || die "sdkmanager not found at $m"
    echo "$m"
}

do_snapshot() {
    local out=${1:?usage: snapshot <outfile>}
    # Sorted so the before/after comparison is about CONTENT, not about the order sdkmanager happens
    # to print in.
    "$(sdkmgr)" --sdk_root="$ANDROID_SDK_ROOT" --list_installed | sed 's/[[:space:]]*$//' | sort > "$out"
    echo "==> toolchain snapshot written to $out ($(wc -l < "$out") lines)"
}

cmd=${1:-}
case "$cmd" in
    parse)
        parse_gradle "${2:?usage: parse <gradle-project-dir>}"
        echo "NDK_VERSIONS=$NDK_VERSIONS"
        echo "COMPILE_SDK=$COMPILE_SDK"
        echo "BUILD_TOOLS=$BUILD_TOOLS"
        ;;
    install)
        parse_gradle "${2:?usage: install <gradle-project-dir>}"
        [ -n "${ANDROID_SDK_ROOT:-}" ] || die "ANDROID_SDK_ROOT is not set"
        M=$(sdkmgr)
        echo "build files ask for: ndk=[$NDK_VERSIONS] compileSdk=$COMPILE_SDK buildTools=$BUILD_TOOLS"

        PKGS=""
        for v in $NDK_VERSIONS; do PKGS="$PKGS ndk;$v"; done
        PKGS="$PKGS platforms;android-$COMPILE_SDK build-tools;$BUILD_TOOLS"

        # One invocation, and the pipeline status read explicitly. `yes |` stays because sdkmanager
        # prompts for licences on a cold SDK, but its SIGPIPE death must not be mistaken for the
        # installer failing (defect 2 in the header) — and, just as importantly, the installer
        # failing must no longer be mistaken for success.
        echo "==> sdkmanager --install$PKGS"
        set +o pipefail
        # shellcheck disable=SC2086
        yes | sudo "$M" --sdk_root="$ANDROID_SDK_ROOT" --install $PKGS
        rc=${PIPESTATUS[1]}
        set -o pipefail
        [ "$rc" -eq 0 ] || die "sdkmanager failed to install one of:$PKGS (exit $rc)"

        # sdkmanager ran under sudo, so everything it wrote is root-owned; Gradle then needs to write
        # into the same tree (licences, .knownPackages, NDK caches) and would fail on permissions.
        # Previously invisible, because the installs were failing anyway.
        sudo chown -R "$(id -u):$(id -g)" "$ANDROID_SDK_ROOT"

        verify_installed
        ;;
    verify)
        parse_gradle "${2:?usage: verify <gradle-project-dir>}"
        verify_installed
        ;;
    snapshot)
        do_snapshot "${2:?usage: snapshot <outfile>}"
        ;;
    assert-unchanged)
        before=${2:?usage: assert-unchanged <before> <after>}
        after=${3:?usage: assert-unchanged <before> <after>}
        [ -f "$before" ] || die "missing snapshot $before"
        [ -f "$after" ]  || die "missing snapshot $after"
        if ! diff -u "$before" "$after"; then
            # AGP downloads missing SDK components at task time by default, so an incomplete pin list
            # does not fail the build — it silently repairs itself from dl.google.com and the artifact
            # is then built by components nobody reviewed. Catching it HERE, before the attestation
            # step, is what makes the pinning real.
            die "the Android SDK changed during the build — a component was auto-downloaded and is therefore unpinned. Add every package listed as added above to the declared toolchain (an ndkVersion/buildToolsVersion in the build files, or the install list in scripts/ci-android-toolchain.sh) and re-run. Nothing has been attested."
        fi
        echo "==> SDK unchanged during the build: nothing was auto-downloaded"
        ;;
    record)
        # The toolchain inventory that ships beside the artifact and is attested with it. Provenance
        # says "this workflow, this commit"; this says WITH WHAT — the half K-06 found missing.
        parse_gradle "${2:?usage: record <gradle-project-dir> <outfile>}"
        out=${3:?usage: record <gradle-project-dir> <outfile>}
        {
            echo "# Khandaq Android build toolchain (scripts/ci-android-toolchain.sh)"
            echo "commit=${GITHUB_SHA:-unknown}"
            echo "runner_label=${KHANDAQ_RUNNER_LABEL:-unknown}"
            echo "runner_image=${ImageOS:-unknown} ${ImageVersion:-unknown}"
            echo "ndk=$NDK_VERSIONS"
            echo "compile_sdk=$COMPILE_SDK"
            echo "build_tools=$BUILD_TOOLS"
            echo "java=$(java -version 2>&1 | head -1)"
            echo "gradle_wrapper=$(sed -n 's/^distributionUrl=.*\/\(gradle-[0-9.]*\)-.*$/\1/p' \
                "$PROJ/gradle/wrapper/gradle-wrapper.properties" 2>/dev/null || echo unknown)"
            echo "# --- sdkmanager --list_installed ---"
            cat "${KHANDAQ_SDK_SNAPSHOT:-/dev/null}" 2>/dev/null || true
        } > "$out"
        cat "$out"
        ;;
    *)
        die "usage: $0 {parse|install|verify|snapshot|assert-unchanged|record} ..."
        ;;
esac
