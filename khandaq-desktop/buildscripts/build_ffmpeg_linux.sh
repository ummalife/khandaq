#!/bin/bash

# SPDX-License-Identifier: GPL-3.0-or-later
#     Copyright (c) 2026 by The Khandaq Project

# KHANDAQ (desktop v0.2.49): the FFmpeg of the Linux build, from source, as the macOS build has had
# it since v0.2.48 (build_ffmpeg in scripts/build-macos-deps.sh). Until v0.2.49 the Linux image took
# Ubuntu 24.04's FFmpeg 6.1.1, with every codec, demuxer, filter and protocol Ubuntu enables, and the
# Ubuntu security tracker still lists CVE-2026-107678 (a crafted MP4 exhausting the stack) against it
# as "Needs evaluation". A voice message reaches the MOV demuxer when its play button is pressed
# (src/model/air/airvoicedecoder.cpp), so the fix could not wait for Ubuntu.
#
# Only what the messenger uses on Linux: the V4L2 camera and the X11 screen capture
# (src/video/cameradevice.cpp), the mov demuxer and AAC decoder for voice messages from the phones,
# the H.264/H.265 decoders toxav uses for video calls, MJPEG/raw camera frames and swscale; the
# libx264 encoder as on macOS. No network, no protocols, no filters, no hardware codecs, and no
# library FFmpeg would otherwise pick up from the image by itself (--disable-autodetect).
#
# Run in an empty directory: the pinned tarball is downloaded there and checked against its SHA-256
# (download/common.sh), then built and installed into /usr/local, where toxcore and Khandaq find it.

set -euo pipefail

readonly SCRIPT_DIR="$(dirname "$(realpath "$0")")"

# The release and digest scripts/build-macos-deps.sh pins for the macOS build.
readonly FFMPEG_VERSION=8.1.1
readonly FFMPEG_HASH=b6863adde98898f42602017462871b5f6333e65aec803fdd7a6308639c52edf3

# Goes into the version and configuration strings of every library, where
# scripts/verify-linux-build.sh looks for it: a bundle without it carries some other FFmpeg.
readonly FFMPEG_EXTRA_VERSION=khandaq-mov-hardening

source "${SCRIPT_DIR}/download/common.sh"

download_verify_extract_tarball \
    "https://ffmpeg.org/releases/ffmpeg-${FFMPEG_VERSION}.tar.xz" \
    "${FFMPEG_HASH}"

# As on macOS: three advisories against 8.1.1 with no fixed 8.1.x release sit in MOV boxes a voice
# message never carries: CVE-2026-52297 (iacb, IAMF), CVE-2026-96611 (ispe, HEIF image items) and
# CVE-2026-107678 (pssh, DRM init data). IAMF is configured out below; the other two boxes leave the
# demuxer's parse table, so it skips them as it skips any unknown box. A future FFmpeg that changes
# these lines stops the build here.
for entry in "{ MKTAG('p','s','s','h'), mov_read_pssh }," "{ MKTAG('i','s','p','e'), mov_read_ispe },"; do
    if [ "$(grep -cxF "$entry" libavformat/mov.c)" != 1 ]; then
        echo "ERROR: FFmpeg ${FFMPEG_VERSION}: '$entry' is not in libavformat/mov.c exactly once" >&2
        exit 1
    fi
    grep -vxF "$entry" libavformat/mov.c >libavformat/mov.c.khandaq
    mv libavformat/mov.c.khandaq libavformat/mov.c
done

# --disable-autodetect still probes the iconv of the C library unless iconv is disabled by name;
# nothing here converts subtitle character sets, so it goes, as on macOS.
./configure --prefix=/usr/local --enable-shared --disable-static \
    --extra-version="${FFMPEG_EXTRA_VERSION}" \
    --enable-gpl --enable-libx264 --enable-pthreads \
    --disable-programs --disable-doc --disable-debug --disable-network \
    --disable-autodetect --disable-iconv --enable-zlib \
    --enable-libxcb --enable-libxcb-shm --enable-libxcb-xfixes --enable-libxcb-shape \
    --disable-everything --disable-avfilter --disable-swresample --disable-iamf \
    --enable-indev=v4l2,xcbgrab --enable-demuxer=mov \
    --enable-decoder=aac,h264,hevc,mjpeg,rawvideo --enable-parser=aac,h264,hevc,mjpeg \
    --enable-encoder=libx264

make -j"$(nproc)"
make install
ldconfig
