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
