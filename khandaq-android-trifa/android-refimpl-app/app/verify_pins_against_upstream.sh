#!/usr/bin/env bash
#
# KHANDAQ (audit2 #2) — verify app/witness.gradle against the UPSTREAM repositories.
#
# Why this exists: `gradlew :app:pinChecksums` proves that the artifacts *on this machine* match the
# committed pins. It cannot prove the pins themselves were honest, because they were generated from
# that same local cache. This script closes that loop: it re-downloads every pinned artifact straight
# from Google Maven / Maven Central / JitPack and compares the SHA-256 with the committed pin.
#
# Run it after any dependency bump, i.e. whenever app/witness.gradle changes:
#
#     ./app/verify_pins_against_upstream.sh
#
# Exit status is non-zero if any pin mismatches or cannot be resolved upstream. It needs network
# access and nothing else (curl + shasum/sha256sum), and it never writes to the repository.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WITNESS="${1:-$SCRIPT_DIR/witness.gradle}"
WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT

if [ ! -f "$WITNESS" ]; then
    echo "no witness file at $WITNESS" >&2
    exit 2
fi

if command -v sha256sum >/dev/null 2>&1; then
    sha256() { sha256sum "$1" | cut -d' ' -f1; }
else
    sha256() { shasum -a 256 "$1" | cut -d' ' -f1; }
fi

GOOGLE="https://dl.google.com/dl/android/maven2"
CENTRAL="https://repo1.maven.org/maven2"
JITPACK="https://jitpack.io"

# Repositories to try, most likely first. Mirrors the repository list in build.gradle.
repos_for() {
    case "$1" in
        androidx.*|com.android.*|com.google.android.*|com.google.testing.*)
            echo "$GOOGLE $CENTRAL" ;;
        com.github.*)
            echo "$CENTRAL $JITPACK" ;;
        *)
            echo "$CENTRAL $GOOGLE $JITPACK" ;;
    esac
}

ok=0; bad=0; unresolved=0; total=0

while IFS= read -r line; do
    # 'group:name:version:file:sha256',
    body="${line#*\'}"; body="${body%\',}"
    hash="${body##*:}"
    rest="${body%:*}"
    file="${rest##*:}"
    rest="${rest%:*}"
    version="${rest##*:}"
    rest="${rest%:*}"
    name="${rest##*:}"
    group="${rest%:*}"

    [ -n "$group" ] && [ -n "$name" ] && [ -n "$version" ] && [ -n "$file" ] || continue
    total=$((total + 1))

    ext="${file##*.}"
    gpath="${group//./\/}"
    out="$WORKDIR/artifact"
    got=""

    for base in $(repos_for "$group"); do
        # Try the PINNED file name first. It is what gradle actually fetched, so it is the only name
        # that distinguishes classified artifacts: org.jetbrains.kotlin:kotlin-stdlib:2.1.20 resolves to
        # kotlin-stdlib-2.1.20-all.jar, and the plain kotlin-stdlib-2.1.20.jar also exists upstream as a
        # DIFFERENT artifact — trying the coordinate form first "verified" the wrong file and reported a
        # false mismatch. Fall back to <name>-<version>.<ext> for Kotlin-Multiplatform artifacts (e.g.
        # androidx.sqlite), whose cached name comes from their Gradle Module Metadata and matches no URL.
        for candidate in "$file" "${name}-${version}.${ext}"; do
            rm -f "$out"
            if curl -fsS --max-time 120 -o "$out" "${base}/${gpath}/${name}/${version}/${candidate}" 2>/dev/null; then
                got="$(sha256 "$out")"
                break 2
            fi
        done
    done

    if [ -z "$got" ]; then
        echo "UNRESOLVED  ${group}:${name}:${version} (${file})"
        unresolved=$((unresolved + 1))
    elif [ "$got" = "$hash" ]; then
        ok=$((ok + 1))
    else
        echo "MISMATCH    ${group}:${name}:${version} (${file})"
        echo "            pinned: $hash"
        echo "            remote: $got"
        bad=$((bad + 1))
    fi
done < <(grep -E "^[[:space:]]*'[^']+:[0-9a-f]{64}'," "$WITNESS")

echo "----"
echo "pins: $total   verified-against-upstream: $ok   mismatched: $bad   unresolved: $unresolved"

if [ "$bad" -ne 0 ] || [ "$unresolved" -ne 0 ] || [ "$total" -eq 0 ]; then
    exit 1
fi
