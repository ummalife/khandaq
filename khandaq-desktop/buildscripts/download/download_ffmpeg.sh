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

# KHANDAQ (security): bumped 4.4.1 -> 4.4.5, the last 4.4.x release, picking up the
# branch's accumulated decoder CVE fixes. Same 4.4 ABI, no risk to the cross-compile.
# TODO: move the Windows build to a modern FFmpeg (Linux uses n8.x) — needs a Windows
# CI build to verify avcodec API changes, so out of scope for this drop-in bump.
FFMPEG_VERSION=4.4.5
FFMPEG_HASH=f9514e0d3515aee5a271283df71636e1d1ff7274b15853bcd84e144be416ab07

source "$(dirname "$(realpath "$0")")/common.sh"

download_verify_extract_tarball \
    "https://www.ffmpeg.org/releases/ffmpeg-${FFMPEG_VERSION}.tar.xz" \
    "${FFMPEG_HASH}"
