#!/bin/bash

set -euo pipefail

readonly SCRIPT_DIR="$(dirname "$(realpath "$0")")"

source "${SCRIPT_DIR}/build_utils.sh"

parse_arch --dep "x264" --supported "win32 win64 macos" "$@"

build_x264() {
    mkdir -p x264
    pushd x264 >/dev/null || exit 1

    "${SCRIPT_DIR}/download/download_x264.sh"

    if [[ "${SCRIPT_ARCH}" == "win32" ]] || [[ "${SCRIPT_ARCH}" == "win64" ]]; then
        CROSS_ARG="${MINGW_ARCH}-w64-mingw32-"
        HOST="${MINGW_ARCH}-w64-mingw32"
    else
        CROSS_ARG=""
        HOST=""
    fi

    pushd x264 >/dev/null || exit 1

    if [[ -n "${HOST}" ]]; then
        ./configure \
            --host="${HOST}" \
            --cross-prefix="${CROSS_ARG}" \
            "--prefix=${DEP_PREFIX}" \
            --enable-shared \
            --disable-cli \
            --disable-asm
    else
        ./configure \
            "--prefix=${DEP_PREFIX}" \
            --enable-shared \
            --disable-cli
    fi

    make -j"${MAKE_JOBS}"
    make install

    popd >/dev/null
    popd >/dev/null
}

build_x264
