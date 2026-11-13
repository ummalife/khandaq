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

# KHANDAQ (security): bumped 4.4.1 -> 4.4.5 -> 4.4.8 (the head of the 4.4.x branch, with
# its accumulated decoder CVE fixes; audit #2 finding 7) -> 8.1.1 in desktop v0.2.50, the
# release and digest scripts/build-macos-deps.sh and build_ffmpeg_linux.sh pin, so the three
# desktop builds read voice messages with the same FFmpeg. build_ffmpeg.sh says what is built.
FFMPEG_VERSION=8.1.1
FFMPEG_HASH=b6863adde98898f42602017462871b5f6333e65aec803fdd7a6308639c52edf3

source "$(dirname "$(realpath "$0")")/common.sh"

download_verify_extract_tarball \
    "https://www.ffmpeg.org/releases/ffmpeg-${FFMPEG_VERSION}.tar.xz" \
    "${FFMPEG_HASH}"
