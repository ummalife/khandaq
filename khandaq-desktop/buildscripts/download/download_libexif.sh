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

LIBEXIF_VERSION=0.6.26
LIBEXIF_HASH=0830ed253fceeb60444fb309598bc8a9491d3007dc054aad3a50a347c5597c57

source "$(dirname "$(realpath "$0")")/common.sh"

download_verify_extract_tarball \
    "https://github.com/libexif/libexif/releases/download/v${LIBEXIF_VERSION}/libexif-${LIBEXIF_VERSION}.tar.bz2" \
    "${LIBEXIF_HASH}"
