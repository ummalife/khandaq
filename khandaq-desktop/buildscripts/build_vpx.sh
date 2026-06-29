#!/bin/bash

# SPDX-License-Identifier: GPL-3.0-or-later AND MIT
#     Copyright (c) 2017-2021 Maxim Biro <nurupo.contributions@gmail.com>
#     Copyright (c) 2021 by The qTox Project Contributors

set -euo pipefail

readonly SCRIPT_DIR="$(dirname "$(realpath "$0")")"

source "${SCRIPT_DIR}/build_utils.sh"

parse_arch --dep "vpx" --supported "win32 win64 macos" "$@"

if [[ "$SCRIPT_ARCH" == "win64" ]]; then
# There is a bug in gcc that breaks avx512 on 64-bit Windows https://gcc.gnu.org/bugzilla/show_bug.cgi?id=54412
# VPX fails to build due to it.
# This is a workaround as suggested in https://stackoverflow.com/questions/43152633
    ARCH_FLAGS="-fno-asynchronous-unwind-tables"
    CROSS_ARG="${MINGW_ARCH}-w64-mingw32-"
    TARGET_ARG="--target=x86_64-win64-gcc"
elif [[ "$SCRIPT_ARCH" == "win32" ]]; then
    ARCH_FLAGS=""
    CROSS_ARG="${MINGW_ARCH}-w64-mingw32-"
    TARGET_ARG="--target=x86-win32-gcc"
elif [ "${SCRIPT_ARCH}" == "macos" ]; then \
    ARCH_FLAGS=""
    CROSS_ARG=""
    TARGET_ARG=""
else
    exit 1
fi

"${SCRIPT_DIR}/download/download_vpx.sh"

if [ "${SCRIPT_ARCH}" == "macos" ]; then
    patch -Np1 < "${SCRIPT_DIR}/patches/vpx-macos.patch"
else
    patch -Np1 < "${SCRIPT_DIR}/patches/vpx-windows.patch"
    # KHANDAQ (security D-7/8/9): libvpx 1.13+/1.14 hardcodes `NO_UNDEFINED := -Wl,-z,defs`
    # in build/make/Makefile. `-z defs` is an ELF-only linker option; mingw's PE/COFF ld
    # rejects it ("unrecognized option '-z'") and the libvpx.dll link fails. 1.11.0 used the
    # mingw-friendly --no-undefined; restore that equivalent so the DLL links under mingw.
    sed -i 's/^NO_UNDEFINED := -Wl,-z,defs/NO_UNDEFINED := -Wl,--no-undefined/' build/make/Makefile
fi

CFLAGS="${ARCH_FLAGS} ${CROSS_CFLAG}" \
CPPFLAGS="${CROSS_CPPFLAG}" \
LDFLAGS="${CROSS_LDFLAG}" \
CROSS="${CROSS_ARG}" \
    ./configure \
        ${TARGET_ARG} \
        "--prefix=${DEP_PREFIX}" \
        --enable-shared \
        --disable-static \
        --enable-runtime-cpu-detect \
        --disable-examples \
        --disable-tools \
        --disable-docs \
        --disable-unit-tests

make -j "${MAKE_JOBS}"
make install

if [ "${SCRIPT_ARCH}" == "macos" ]; then
    install_name_tool -id '@rpath/libvpx.dylib' ${DEP_PREFIX}/lib/libvpx.dylib
fi
