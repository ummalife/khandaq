#!/bin/bash

#    Copyright © 2021 by The qTox Project Contributors
#
#    Khandaq: NGC-capable toxcore fork (same as Android TRIfA / zoxcore).

set -euo pipefail

# zoff99/c-toxcore @ zoff99/zoxcore_local_fork — must match Android jni-c-toxcore.
TOXCORE_GIT_URL="https://github.com/zoff99/c-toxcore.git"
TOXCORE_GIT_REF="2e7a0675c5fadc6c362e67cb66cc8925bdff8816"

source "$(dirname "$(realpath "$0")")/common.sh"

if [ "${FORCE_TOXCORE_DOWNLOAD:-0}" != "1" ] && [ -f CMakeLists.txt ] && [ -d toxcore ]; then
    echo "toxcore source already present, skipping download (set FORCE_TOXCORE_DOWNLOAD=1 to refresh)"
    exit 0
fi

TEMPDIR=$(mktemp -d -p . 2>/dev/null || mktemp -d -t qtox_download)
TEMPDIR=$(cd "$TEMPDIR" && pwd -P)

git clone --depth 1 "$TOXCORE_GIT_URL" "$TEMPDIR/src"
git -C "$TEMPDIR/src" fetch --depth 1 origin "$TOXCORE_GIT_REF"
git -C "$TEMPDIR/src" checkout "$TOXCORE_GIT_REF"

ACTUAL=$(git -C "$TEMPDIR/src" rev-parse HEAD)
if [ "$ACTUAL" != "$TOXCORE_GIT_REF" ]; then
    echo "Warning: toxcore HEAD $ACTUAL != expected $TOXCORE_GIT_REF"
fi

cp -R "$TEMPDIR/src"/. .
rm -rf "$TEMPDIR"
echo "NGC toxcore $TOXCORE_GIT_REF installed"
