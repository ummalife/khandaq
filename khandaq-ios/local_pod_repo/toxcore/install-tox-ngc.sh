#!/bin/bash
# Sync NGC toxcore from khandaq-desktop/buildscripts/toxcore into the CocoaPods tree.
set -euo pipefail

_HOME2_=$(dirname "$0")
_HOME_=$(cd "$_HOME2_" && pwd)

DESKTOP_TOXCORE="${DESKTOP_TOXCORE:-${_HOME_}/../../../khandaq-desktop/buildscripts/toxcore}"
OUTPUT="${_HOME_}/toxcore"
TOXCORE_SRC="${OUTPUT}/toxcore"

if [[ ! -d "${DESKTOP_TOXCORE}/toxcore" ]]; then
    echo "Desktop toxcore not found at ${DESKTOP_TOXCORE}/toxcore"
    exit 1
fi

echo "Source: ${DESKTOP_TOXCORE}"
echo "Output: ${OUTPUT}"

mkdir -p "${TOXCORE_SRC}"

echo "Copying toxcore sources..."
rsync -a --delete \
    --exclude='*_test.cc' \
    --exclude='*_test.cpp' \
    --exclude='*_fuzz_test.cc' \
    --exclude='*.bazel' \
    --exclude='*.api.h' \
    "${DESKTOP_TOXCORE}/toxcore/" "${TOXCORE_SRC}/"

echo "Copying toxutil..."
mkdir -p "${OUTPUT}/toxutil"
rsync -a --delete \
    "${DESKTOP_TOXCORE}/toxutil/" "${OUTPUT}/toxutil/"

# KHANDAQ (audit round 3, F-01) — toxav/ IS DELIBERATELY NOT SYNCED, AND THAT HAS A PRICE.
#
# This script has always copied toxcore/ and toxutil/ and never toxav/. Nobody wrote down why, so it
# read like an oversight for long enough that the iOS A/V stack drifted a whole generation behind the
# desktop one: as of 2026-08-21 the desktop toxav/ is rtp.c 1290 lines / video.c 1085 / toxav.c 3566
# against the pod's 957 / 446 / 1572, and the two use different logging and Tox-handle APIs
# (LOGGER_API_*(tox, ...) vs LOGGER_*(log, ...)). rsync'ing toxav/ in would not compile.
#
# What that silence cost: the desktop toxav gained three bounds checks in fill_data_into_slot() and
# the iOS copy never got them, so any accepted contact in a video call could write attacker-chosen
# bytes at an attacker-chosen offset past a heap allocation. Someone even dropped the fixed file in
# beside the broken one as rtp.m.bak — where the podspec glob 'toxcore/toxav/*.{m,h}' cannot see it —
# and the divergence stayed invisible anyway.
#
# So it stays manual, and the obligation is stated here instead of being inferred:
#
#   AFTER RUNNING THIS SCRIPT, DIFF toxav/ BY HAND against ${DESKTOP_TOXCORE}/toxav/ and port any
#   security-relevant change across. scripts/check-ios-rtp-reassembly.py holds the line for the
#   reassembler specifically — it compiles the real function and attacks it — but it covers ONE
#   function. video.c, toxav.c, audio.c and bwcontroller.c are not covered by anything.
echo
echo "NOTE: toxav/ is not synced (different API generation - see the comment above)."
echo "      Diff it against ${DESKTOP_TOXCORE}/toxav/ by hand and port security fixes across."

echo "Changing .c files to .m (Xcode / CocoaPods)"
find "${TOXCORE_SRC}" "${OUTPUT}/toxutil" -name '*.c' -print0 | while IFS= read -r -d '' file; do
    mv -v "$file" "${file%.c}.m"
done

patch_includes() {
    local root="$1"
    while IFS= read -r -d '' file; do
        sed -i '' 's/#include <sodium.h>/#include "sodium.h"/g' "$file"
        sed -i '' 's/#include <opus.h>/#include "opus.h"/g' "$file"
        sed -i '' 's|#include "../third_party/cmp/cmp.h"|#include "cmp.h"|g' "$file"
    done < <(find "$root" \( -name '*.m' -o -name '*.h' \) -print0)
}

echo "Patching includes..."
patch_includes "${TOXCORE_SRC}"
patch_includes "${OUTPUT}/toxutil"

echo "Done. tox_group_new present:"
grep -c 'tox_group_new' "${TOXCORE_SRC}/tox.h" || true
