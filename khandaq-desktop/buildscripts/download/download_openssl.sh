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

# KHANDAQ (security): 1.1.1l (Aug 2021) was missing ~2 years of CVE fixes incl.
# CVE-2022-0778 (DoS) and CVE-2023-0286 (X.400 type confusion). Bumped to the final
# 1.1.1 release (1.1.1w, Sep 2023) — same API/build, no risk to the cross-compile.
# TODO: migrate the Windows build to OpenSSL 3.x (Linux already uses 3.0.x) — needs a
# Windows CI build to verify, so out of scope for this drop-in security bump.
OPENSSL_VERSION=1.1.1w
OPENSSL_HASH=cf3098950cb4d853ad95c0841f1f9c6d3dc102dccfcacd521d93925208b76ac8

source "$(dirname "$(realpath "$0")")/common.sh"

download_verify_extract_tarball \
    "https://www.openssl.org/source/openssl-$OPENSSL_VERSION.tar.gz" \
    "$OPENSSL_HASH"
