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

# KHANDAQ (security): 1.11.0 was vulnerable to CVE-2023-5217 (VP8 heap overflow,
# exploited in the wild; fixed in 1.13.1). Bumped to 1.14.0 to match the Linux build.
VPX_VERSION=1.14.0
VPX_HASH=5f21d2db27071c8a46f1725928a10227ae45c5cd1cad3727e4aafbe476e321fa

source "$(dirname "$(realpath "$0")")/common.sh"

download_verify_extract_tarball \
    "https://github.com/webmproject/libvpx/archive/v$VPX_VERSION.tar.gz" \
    "${VPX_HASH}"
