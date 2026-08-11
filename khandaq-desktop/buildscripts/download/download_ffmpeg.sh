#!/bin/bash

#    Copyright © 2021 by The qTox Project Contributors
#
#    This program is libre software: you can redistribute it and/or modify
#    it under the terms of the GNU General Public License as published by
#    the Free Software Foundation, either version 3 of the License, or
#    (at your option) any later version.
#
#    This program is distributed in the hope that it will be useful,
#    but WITHOUT ANY WARRANTY; without even the implied warranty of
#    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
#    GNU General Public License for more details.
#
#    You should have received a copy of the GNU General Public License
#    along with this program.  If not, see <http://www.gnu.org/licenses/>.

set -euo pipefail

# KHANDAQ (security): bumped 4.4.1 -> 4.4.5 -> 4.4.8, the current head of the 4.4.x
# branch, picking up its accumulated decoder CVE fixes (audit #2 finding 7: six issues
# were fixed in 4.4.6 alone). Same 4.4 ABI, no risk to the cross-compile.
# Hash is from the real tarball; its detached .asc verifies against FFmpeg's release
# signing key FCF986EA15E6E293A5644F10B4322F04D67658D8.
# TODO: move the Windows build to a modern FFmpeg (Linux uses n8.x) — needs a Windows
# CI build to verify avcodec API changes, so out of scope for this drop-in bump.
FFMPEG_VERSION=4.4.8
FFMPEG_HASH=c73848c4ae283d9eaee7be3b276affbc3543380483555500d0dd2c9b7e1c39c3

source "$(dirname "$(realpath "$0")")/common.sh"

download_verify_extract_tarball \
    "https://www.ffmpeg.org/releases/ffmpeg-${FFMPEG_VERSION}.tar.xz" \
    "${FFMPEG_HASH}"
