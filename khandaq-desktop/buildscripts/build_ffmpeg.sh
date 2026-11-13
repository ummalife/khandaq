#!/bin/bash

# SPDX-License-Identifier: GPL-3.0-or-later AND MIT
#     Copyright (c) 2017-2021 Maxim Biro <nurupo.contributions@gmail.com>
#     Copyright (c) 2021 by The qTox Project Contributors

# KHANDAQ (desktop v0.2.50): FFmpeg 8.1.1 for the Windows build, configured like the macOS build
# (build_ffmpeg in scripts/build-macos-deps.sh) and the Linux build (build_ffmpeg_linux.sh). Until
# v0.2.50 Windows carried FFmpeg 4.4.8 without the MOV demuxer and the AAC decoder, so the app could
# not read a voice message from the phones (src/model/air/airvoicedecoder.cpp, canDecode()) and
# offered an external player instead.
#
# Only what the messenger uses on Windows: the DirectShow and VfW cameras and the GDI screen capture
# (src/video/cameradevice.cpp; gdigrab brings the BMP decoder with it), the MOV demuxer and AAC
# decoder for voice messages, the H.264, MJPEG and raw decoders and the H.264/MJPEG demuxers and
# parsers the 4.4.8 build had, and swscale. No encoders, muxers, network, protocols, filters,
# hardware decoders, IAMF or zlib, and on Windows no library FFmpeg would otherwise pick up from the
# image by itself (--disable-autodetect). configure leaves out a component whose dependency it cannot
# find without failing, so the build checks the result before compiling anything.

set -euo pipefail

readonly SCRIPT_DIR="$(dirname "$(realpath "$0")")"

source "${SCRIPT_DIR}/build_utils.sh"

parse_arch --dep "ffmpeg" --supported "win32 win64 macos" "$@"

if [ "${SCRIPT_ARCH}" == "win64" ]; then
    FFMPEG_ARCH="--arch=x86_64"
    TARGET_OS="--target-os=mingw32"
    CROSS_PREFIX="--cross-prefix=${MINGW_ARCH}-w64-mingw32-"
elif [ "${SCRIPT_ARCH}" == "win32" ]; then
    FFMPEG_ARCH="--arch=x86"
    TARGET_OS="--target-os=mingw32"
    CROSS_PREFIX="--cross-prefix=${MINGW_ARCH}-w64-mingw32-"
else
    FFMPEG_ARCH=""
    TARGET_OS=""
    CROSS_PREFIX=""
fi

if [ "${SCRIPT_ARCH}" == "macos" ]; then
    # The qTox macOS CI path (.ci-scripts/build-osx-deps.sh), which the monorepo does not run; the
    # macOS releases build FFmpeg in scripts/build-macos-deps.sh. AVFoundation is one of the
    # libraries configure detects by itself, so autodetection stays on here.
    PLATFORM_FLAGS=(--enable-pthreads --enable-indev=avfoundation)
    PLATFORM_DEFINES=(HAVE_PTHREADS CONFIG_AVFOUNDATION_INDEV)
else
    # Win32 threads are what configure picks on Windows by itself; naming them makes it fail
    # instead of building without threads.
    PLATFORM_FLAGS=(--disable-autodetect --enable-w32threads --enable-indev=dshow,gdigrab,vfwcap)
    PLATFORM_DEFINES=(HAVE_W32THREADS CONFIG_DSHOW_INDEV CONFIG_GDIGRAB_INDEV CONFIG_VFWCAP_INDEV)
fi

"${SCRIPT_DIR}/download/download_ffmpeg.sh"

# As on macOS and Linux: three advisories against 8.1.1 with no fixed 8.1.x release sit in MOV boxes
# a voice message never carries: CVE-2026-52297 (iacb, IAMF), CVE-2026-96611 (ispe, HEIF image
# items) and CVE-2026-107678 (pssh, DRM init data). IAMF is configured out below; the other two
# boxes leave the demuxer's parse table, so it skips them as it skips any unknown box. A future
# FFmpeg that changes these lines stops the build here.
for entry in "{ MKTAG('p','s','s','h'), mov_read_pssh }," "{ MKTAG('i','s','p','e'), mov_read_ispe },"; do
    if [ "$(grep -cxF "$entry" libavformat/mov.c)" != 1 ]; then
        echo "ERROR: FFmpeg: '$entry' is not in libavformat/mov.c exactly once" >&2
        exit 1
    fi
    grep -vxF "$entry" libavformat/mov.c >libavformat/mov.c.khandaq
    mv libavformat/mov.c.khandaq libavformat/mov.c
done

# --extra-version goes into the version and configuration strings of every library, so a DLL
# without it is some other FFmpeg.
CFLAGS="${CROSS_CFLAG}" \
CPPFLAGS="${CROSS_CPPFLAG}" \
LDFLAGS="${CROSS_LDFLAG}" \
./configure ${FFMPEG_ARCH} \
          --enable-gpl --enable-shared --disable-static \
          "--prefix=${DEP_PREFIX}" ${TARGET_OS} ${CROSS_PREFIX} \
          --pkg-config="pkg-config" --extra-cflags="-O2 -g0" \
          --extra-version=khandaq-mov-hardening \
          --disable-debug --disable-programs --disable-doc --disable-network \
          --disable-iconv --disable-zlib --disable-bzlib --disable-lzma --disable-sdl2 --disable-xlib \
          --disable-swscale-alpha \
          --disable-everything --disable-avfilter --disable-swresample --disable-iamf \
          "${PLATFORM_FLAGS[@]}" \
          --enable-demuxer=h264,mjpeg,mov \
          --enable-parser=aac,h264,mjpeg \
          --enable-decoder=aac,h264,mjpeg,rawvideo

for define in HAVE_THREADS CONFIG_MOV_DEMUXER CONFIG_AAC_DECODER CONFIG_H264_DECODER \
              CONFIG_MJPEG_DECODER CONFIG_RAWVIDEO_DECODER CONFIG_SWSCALE CONFIG_AVDEVICE \
              "${PLATFORM_DEFINES[@]}"; do
    if ! grep -qxF "#define ${define} 1" config.h config_components.h; then
        echo "ERROR: FFmpeg configure left out ${define}, which the app needs" >&2
        exit 1
    fi
done
for define in CONFIG_IAMFDEC CONFIG_NETWORK CONFIG_ZLIB CONFIG_AVFILTER; do
    if ! grep -qxF "#define ${define} 0" config.h config_components.h; then
        echo "ERROR: FFmpeg configure put in ${define}, which this build leaves out" >&2
        exit 1
    fi
done

make -j "${MAKE_JOBS}"
make install
