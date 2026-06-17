#!/bin/bash

set -euo pipefail

readonly SCRIPT_DIR="$(dirname "$(realpath "$0")")"
source "${SCRIPT_DIR}/common.sh"

readonly X264_DIR="x264"

if [ -d "${X264_DIR}/.git" ]; then
    pushd "${X264_DIR}" >/dev/null
    git fetch --depth 1 origin stable
    git checkout stable
    popd >/dev/null
else
    rm -rf "${X264_DIR}"
    git clone --depth 1 --branch stable https://code.videolan.org/videolan/x264.git "${X264_DIR}"
fi
