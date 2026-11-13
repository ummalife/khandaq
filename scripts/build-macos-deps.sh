#!/usr/bin/env bash
# KHANDAQ: build the third-party libraries of the macOS bundle from pinned sources, for the macOS
# version the app declares (LSMinimumSystemVersion in khandaq-desktop/osx/info.plist).
#
# Why this exists. v0.2.47 took its libraries from Homebrew. Homebrew bottles are compiled for the
# macOS they were bottled on, so 35 of the bundled libraries carried LC_BUILD_VERSION minos 26.0
# while Info.plist promised 11.0. dyld refuses to load a library built for a newer system than the
# one it runs on, so the app could not start on macOS 11-15 at all. Everything the bundle ships
# besides Qt itself (Homebrew's Qt frameworks are built for 11.0) is built here instead, with the
# deployment target set and -Werror=unguarded-availability-new, so a call to an API newer than the
# target is a build error rather than a crash on an older Mac. scripts/build-macos.sh refuses to
# produce a bundle in which any Mach-O file requires a newer macOS than the target.
#
# Every archive is pinned by SHA-256 (the values Homebrew verified for the same releases, or the
# value in khandaq-desktop/buildscripts/download/ where that script pins it); x264 has no release
# tarballs and is pinned by git commit, like toxcore in download_toxcore.sh.
#
# Usage:  scripts/build-macos-deps.sh [library ...]     (default: all, in dependency order)
#         KHANDAQ_MACOS_DEPS=/some/prefix scripts/build-macos-deps.sh
# A library is skipped when its stamp (name, version, target) exists, so re-running is cheap;
# delete the prefix to start over.
set -euo pipefail

# An x86_64 bash (Intel Homebrew's /usr/local/bin/bash, first in PATH on some Macs) runs under
# Rosetta, and configure scripts then take an Apple Silicon Mac for an Intel one. Run natively.
if [ "$(sysctl -n sysctl.proc_translated 2>/dev/null || echo 0)" = "1" ]; then
    exec /usr/bin/arch -arm64 /bin/bash "$0" "$@"
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TARGET="${KHANDAQ_MACOS_TARGET:-11.0}"
PREFIX="${KHANDAQ_MACOS_DEPS:-$ROOT/khandaq-desktop/macos-deps}"
CACHE="$PREFIX/src"
WORK="$PREFIX/build"
STAMPS="$PREFIX/.stamps"
JOBS="$(sysctl -n hw.ncpu)"
ARCH="$(uname -m)"

if [ "$(uname -s)" != "Darwin" ]; then
    echo "ERROR: $(basename "$0") builds macOS libraries and runs on macOS only." >&2
    exit 1
fi

PLIST_TARGET="$(/usr/libexec/PlistBuddy -c "Print :LSMinimumSystemVersion" "$ROOT/khandaq-desktop/osx/info.plist")"
if [ "$PLIST_TARGET" != "$TARGET" ]; then
    echo "ERROR: building for macOS $TARGET, but osx/info.plist declares $PLIST_TARGET." >&2
    exit 1
fi

mkdir -p "$CACHE" "$WORK" "$STAMPS" "$PREFIX/lib/pkgconfig"

# Only this prefix may be visible to the builds: a Homebrew header or library picked up by
# accident would bring its minimum macOS back into the bundle.
unset CPATH C_INCLUDE_PATH CPLUS_INCLUDE_PATH OBJC_INCLUDE_PATH LIBRARY_PATH PKG_CONFIG_PATH \
    DYLD_LIBRARY_PATH DYLD_FALLBACK_LIBRARY_PATH
export PKG_CONFIG_LIBDIR="$PREFIX/lib/pkgconfig"
export MACOSX_DEPLOYMENT_TARGET="$TARGET"
SDK="$(xcrun --sdk macosx --show-sdk-path)"
export SDKROOT="$SDK"
BASE_CFLAGS="-O2 -mmacosx-version-min=$TARGET -Werror=unguarded-availability-new"
export CFLAGS="$BASE_CFLAGS"
export CXXFLAGS="$BASE_CFLAGS"
export OBJCFLAGS="$BASE_CFLAGS"
export CPPFLAGS="-I$PREFIX/include"
export LDFLAGS="-mmacosx-version-min=$TARGET -L$PREFIX/lib -Wl,-headerpad_max_install_names"
# CMake's own search of PATH stays off (PATH has /opt/homebrew/bin, and CMake would look in
# /opt/homebrew/lib next to it), so the tools it would have found there are named instead.
PKG_CONFIG="$(command -v pkg-config || true)"
if [ -z "$PKG_CONFIG" ]; then
    echo "ERROR: pkg-config is needed (brew install pkgconf)." >&2
    exit 1
fi
CMAKE_COMMON=(
    -DCMAKE_BUILD_TYPE=Release
    -DCMAKE_MAKE_PROGRAM=/usr/bin/make
    -DCMAKE_C_COMPILER=/usr/bin/cc
    -DCMAKE_CXX_COMPILER=/usr/bin/c++
    -DPKG_CONFIG_EXECUTABLE="$PKG_CONFIG"
    -DCMAKE_INSTALL_PREFIX="$PREFIX"
    -DCMAKE_INSTALL_LIBDIR=lib
    -DCMAKE_INSTALL_NAME_DIR="$PREFIX/lib"
    -DCMAKE_OSX_DEPLOYMENT_TARGET="$TARGET"
    -DCMAKE_PREFIX_PATH="$PREFIX"
    -DCMAKE_FIND_USE_SYSTEM_ENVIRONMENT_PATH=OFF
    -DCMAKE_IGNORE_PREFIX_PATH="/opt/homebrew;/usr/local"
)

# zlib is part of macOS but ships no pkg-config file, and libpng.pc requires one (FreeType asks
# pkg-config for libpng). Describe the SDK's zlib, as Homebrew does for its builds.
ZLIB_VERSION="$(sed -n 's/^#define ZLIB_VERSION "\(.*\)"$/\1/p' "$SDK/usr/include/zlib.h")"
cat >"$PREFIX/lib/pkgconfig/zlib.pc" <<EOF
Name: zlib
Description: zlib from the macOS SDK
Version: ${ZLIB_VERSION:?no ZLIB_VERSION in the SDK zlib.h}
Libs: -lz
Cflags:
EOF

# fetch URL SHA256 [FILE] -> path of the verified archive in the cache
fetch() {
    local url="$1" sha="$2" file="$CACHE/${3:-$(basename "$1")}"
    if [ -f "$file" ] && echo "$sha  $file" | shasum -a 256 -c --status; then
        printf '%s\n' "$file"
        return 0
    fi
    rm -f "$file" "$file.part"
    echo "==> download $url" >&2
    curl -fL --retry 3 --proto '=https' --proto-redir '=https' --tlsv1.2 -o "$file.part" "$url" >&2
    if ! echo "$sha  $file.part" | shasum -a 256 -c --status; then
        echo "ERROR: SHA-256 of $url is $(shasum -a 256 "$file.part" | cut -d' ' -f1), expected $sha" >&2
        rm -f "$file.part"
        exit 1
    fi
    mv "$file.part" "$file"
    printf '%s\n' "$file"
}

# prepare NAME URL SHA256 [FILE] -> verified, freshly unpacked sources in $SRC_DIR
SRC_DIR=""
prepare() {
    local archive
    archive="$(fetch "$2" "$3" "${4:-}")"
    SRC_DIR="$WORK/$1"
    rm -rf "$SRC_DIR"
    mkdir -p "$SRC_DIR"
    tar -xf "$archive" -C "$SRC_DIR" --strip-components 1
}

stamp_of() { printf '%s\n' "$STAMPS/$1-$2-macos$TARGET"; }

# sdk_newer_than_target NAME... -> the NAMEs the SDK declares only for a macOS newer than $TARGET.
# Configure-time function checks link a dummy prototype against the SDK, which lists every symbol
# of its own macOS, so such a function is "found" and then called without an availability check.
sdk_newer_than_target() {
    local name out probe="$WORK/availability-probe.c"
    for name in "$@"; do
        cat >"$probe" <<EOF
#include <crt_externs.h>
#include <fcntl.h>
#include <fstab.h>
#include <grp.h>
#include <ifaddrs.h>
#include <locale.h>
#include <netdb.h>
#include <poll.h>
#include <pwd.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/event.h>
#include <sys/mman.h>
#include <sys/mount.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/statvfs.h>
#include <sys/sysctl.h>
#include <sys/time.h>
#include <time.h>
#include <unistd.h>
#include <wchar.h>
#include <xlocale.h>
void *khandaq_probe(void) { return (void *)&$name; }
EOF
        if ! out="$(cc -isysroot "$SDK" -mmacosx-version-min="$TARGET" \
            -Werror=unguarded-availability-new -fsyntax-only "$probe" 2>&1)"; then
            case "$out" in
                *"is only available on macOS"*) printf '%s\n' "$name" ;;
            esac
        fi
    done
    rm -f "$probe"
}

# --- Qt's own dependencies (Homebrew's QtCore/QtGui link them) ---------------------------------

build_pcre2() {
    local v=10.49 d
    prepare pcre2 "https://github.com/PCRE2Project/pcre2/releases/download/pcre2-$v/pcre2-$v.tar.bz2" 53c156e1ba416a20da8e65395daa132da0d80e76910424caca3fcdae7831d384
    d="$SRC_DIR"
    (cd "$d" && ./configure --prefix="$PREFIX" --disable-static --enable-shared --enable-pcre2-16 \
        --enable-jit --disable-dependency-tracking && make -j"$JOBS" && make install)
}

build_zstd() {
    local v=1.5.7 d
    prepare zstd "https://github.com/facebook/zstd/archive/refs/tags/v$v.tar.gz" 37d7284556b20954e56e1ca85b80226768902e2edabd3b649e9e72c0c9012ee3 "zstd-$v.tar.gz"
    d="$SRC_DIR"
    make -C "$d/lib" -j"$JOBS" libzstd PREFIX="$PREFIX"
    make -C "$d/lib" install-shared install-includes install-pc PREFIX="$PREFIX"
}

build_gettext() {
    # Only libintl: glib links it, and Homebrew's QtCore names it as a load command.
    local v=1.0 d
    prepare gettext "https://ftp.gnu.org/gnu/gettext/gettext-$v.tar.gz" 85d99b79c981a404874c02e0342176cf75c7698e2b51fe41031cf6526d974f1a
    d="$SRC_DIR"
    (cd "$d/gettext-runtime" && am_cv_func_iconv_works=yes ./configure --prefix="$PREFIX" \
        --disable-static --enable-shared --with-included-gettext --disable-java --disable-csharp \
        --disable-libasprintf --without-emacs --without-git --without-cvs --without-xz --without-bzip2 \
        --disable-dependency-tracking && make -j"$JOBS" -C intl && make -C intl install)
}

MESON=()
setup_meson() {
    local v=1.12.1 d
    prepare meson "https://github.com/mesonbuild/meson/releases/download/$v/meson-$v.tar.gz" ab0a6ca09f8ef70c564c8241fb5a23957886a0b53fb58412b5e07eaf07dba743
    d="$SRC_DIR"
    MESON=(python3 "$d/meson.py")
}

build_glib() {
    local v=2.88.2 d
    [ "${#MESON[@]}" -gt 0 ] || setup_meson
    prepare glib "https://download.gnome.org/sources/glib/2.88/glib-$v.tar.xz" cf3f215a640c8a4257f14317586b8f1fdd25a10a93cb4bdda147c0f9ad88e74f
    d="$SRC_DIR"
    # glib checks its list of functions without their headers, so one the SDK has only for a
    # newer macOS (pipe2: macOS 27.0) would be used unguarded and fail -Werror. Leave those out;
    # glib then uses its fallback, as it does when built with an older SDK.
    local fn
    for fn in $(sdk_newer_than_target \
        $(sed -n "/^functions = \[/,/^\]/s/^  '\([A-Za-z0-9_]*\)',\$/\1/p" "$d/meson.build")); do
        echo "    glib: without $fn (the SDK has it only for a macOS newer than $TARGET)"
        sed -i '' "/^functions = \[/,/^\]/{/^  '$fn',\$/d;}" "$d/meson.build"
    done
    # libffi is part of macOS (the SDK carries ffi/ffi.h and libffi.tbd). Describe it for
    # pkg-config so meson uses it instead of looking for a subproject.
    cat >"$PREFIX/lib/pkgconfig/libffi.pc" <<EOF
Name: libffi
Description: libffi from the macOS SDK
Version: 3.4.0
Cflags: -I$SDK/usr/include/ffi
Libs: -lffi
EOF
    "${MESON[@]}" setup "$d/_build" "$d" --prefix="$PREFIX" --libdir=lib --buildtype=release \
        --wrap-mode=nodownload -Ddefault_library=shared -Dtests=false -Dintrospection=disabled \
        -Ddtrace=false -Dbsymbolic_functions=false -Ddocumentation=false -Dman-pages=disabled
    "${MESON[@]}" compile -C "$d/_build"
    "${MESON[@]}" install -C "$d/_build"
}

build_libpng() {
    local v=1.6.59 d
    prepare libpng "https://downloads.sourceforge.net/project/libpng/libpng16/$v/libpng-$v.tar.xz" d80dd2a38a37f803cb9b6ac7b14bd6e74ddc3b654780a8380bdf93523fdb4389
    d="$SRC_DIR"
    (cd "$d" && ./configure --prefix="$PREFIX" --disable-static --enable-shared \
        --disable-dependency-tracking && make -j"$JOBS" && make install)
}

build_md4c() {
    local v=0.5.3 d
    prepare md4c "https://github.com/mity/md4c/archive/refs/tags/release-$v.tar.gz" 353c346f376b87c954a13f3415ede2d51264cc61dc5abcd38ff1d2aa0d059b9e "md4c-$v.tar.gz"
    d="$SRC_DIR"
    cmake -S "$d" -B "$d/_build" "${CMAKE_COMMON[@]}" -DBUILD_SHARED_LIBS=ON
    cmake --build "$d/_build" -j"$JOBS"
    cmake --install "$d/_build"
}

# --- the messenger's own dependencies -----------------------------------------------------------

# --- What Homebrew's Qt plugins link (macdeployqt copies these too) -----------------------------
# platforms/libqcocoa links FreeType; the JPEG, TIFF and WebP image plugins link libjpeg, libtiff
# (with liblzma and zstd) and libwebp. Versions and hashes are the ones Homebrew ships, and each is
# configured like Homebrew's build, so the library names and compatibility versions the plugins
# were linked against stay valid.

build_xz() {
    local v=5.8.4 d
    prepare xz "https://github.com/tukaani-project/xz/releases/download/v$v/xz-$v.tar.gz" 0014c7886930454fe8bd4228665b51af55eeae560ea135c9c4cd33f55b2591d9
    d="$SRC_DIR"
    (cd "$d" && ./configure --prefix="$PREFIX" --disable-static --enable-shared --disable-xz \
        --disable-xzdec --disable-lzmadec --disable-lzmainfo --disable-scripts --disable-doc \
        --disable-nls --disable-dependency-tracking && make -j"$JOBS" && make install)
}

build_jpeg() {
    # libjpeg-turbo with the libjpeg 8 API, as Homebrew builds it: Qt's JPEG plugin links libjpeg.8.
    local v=3.2.1 d
    prepare libjpeg-turbo "https://github.com/libjpeg-turbo/libjpeg-turbo/releases/download/$v/libjpeg-turbo-$v.tar.gz" c27ad72a68da151177929aa969ae773a2da5ac2af9ce946079314fdac8cece7f
    d="$SRC_DIR"
    cmake -S "$d" -B "$d/_build" "${CMAKE_COMMON[@]}" -DCMAKE_INSTALL_DEFAULT_LIBDIR=lib \
        -DWITH_JPEG8=1 -DENABLE_STATIC=OFF -DENABLE_SHARED=ON -DWITH_TURBOJPEG=OFF -DWITH_TOOLS=OFF \
        -DWITH_TESTS=OFF
    cmake --build "$d/_build" -j"$JOBS"
    cmake --install "$d/_build"
}

build_tiff() {
    # The codecs Homebrew's libtiff.6 links: zlib (the system's), libjpeg, liblzma and zstd.
    local v=4.7.2 d
    prepare tiff "https://download.osgeo.org/libtiff/tiff-$v.tar.gz" 672bd7d10aee4606171afb864f3570b83340f6a33e2c186dc0512f7145ffdf6a
    d="$SRC_DIR"
    (cd "$d" && ./configure --prefix="$PREFIX" --disable-static --enable-shared --disable-cxx \
        --enable-zlib --enable-jpeg --enable-lzma --enable-zstd --disable-webp --disable-lerc \
        --disable-jbig --disable-libdeflate --disable-tools --disable-tests --disable-contrib \
        --disable-docs --disable-dependency-tracking \
        --with-jpeg-include-dir="$PREFIX/include" --with-jpeg-lib-dir="$PREFIX/lib" \
        && make -j"$JOBS" && make install)
}

build_webp() {
    local v=1.6.0 d
    prepare libwebp "https://storage.googleapis.com/downloads.webmproject.org/releases/webp/libwebp-$v.tar.gz" e4ab7009bf0629fd11982d4c2aa83964cf244cffba7347ecd39019a9e38c4564
    d="$SRC_DIR"
    cmake -S "$d" -B "$d/_build" "${CMAKE_COMMON[@]}" -DBUILD_SHARED_LIBS=ON \
        -DWEBP_BUILD_ANIM_UTILS=OFF -DWEBP_BUILD_CWEBP=OFF -DWEBP_BUILD_DWEBP=OFF \
        -DWEBP_BUILD_GIF2WEBP=OFF -DWEBP_BUILD_IMG2WEBP=OFF -DWEBP_BUILD_VWEBP=OFF \
        -DWEBP_BUILD_WEBPINFO=OFF -DWEBP_BUILD_WEBPMUX=OFF -DWEBP_BUILD_EXTRAS=OFF
    cmake --build "$d/_build" -j"$JOBS"
    cmake --install "$d/_build"
}

build_freetype() {
    # zlib, bzip2 (both the system's) and libpng, as in the libfreetype.6 Qt's Cocoa plugin links.
    local v=2.15.0 d
    prepare freetype "https://downloads.sourceforge.net/project/freetype/freetype2/$v/freetype-$v.tar.xz" 93cd47eb719c325107f2d3cf087d06299e944c3b48a40b55537302304dea1dd5
    d="$SRC_DIR"
    (cd "$d" && ./configure --prefix="$PREFIX" --disable-static --enable-shared --with-zlib=yes \
        --with-bzip2=yes --with-png=yes --with-harfbuzz=no --with-brotli=no \
        && make -j"$JOBS" && make install)
}

build_sodium() {
    local v=1.0.22 d
    prepare libsodium "https://download.libsodium.org/libsodium/releases/libsodium-$v.tar.gz" adbdd8f16149e81ac6078a03aca6fc03b592b89ef7b5ed83841c086191be3349
    d="$SRC_DIR"
    (cd "$d" && ./configure --prefix="$PREFIX" --disable-static --enable-shared \
        --disable-dependency-tracking && make -j"$JOBS" && make install)
}

build_opus() {
    local v=1.6.1 d
    prepare opus "https://downloads.xiph.org/releases/opus/opus-$v.tar.gz" 6ffcb593207be92584df15b32466ed64bbec99109f007c82205f0194572411a1
    d="$SRC_DIR"
    (cd "$d" && ./configure --prefix="$PREFIX" --disable-static --enable-shared --disable-doc \
        --disable-extra-programs --disable-dependency-tracking && make -j"$JOBS" && make install)
}

build_vpx() {
    local v=1.16.0 d
    prepare libvpx "https://github.com/webmproject/libvpx/archive/refs/tags/v$v.tar.gz" 7a479a3c66b9f5d5542a4c6a1b7d3768a983b1e5c14c60a9396edc9b649e015c "libvpx-$v.tar.gz"
    d="$SRC_DIR"
    # darwin20 is macOS 11: libvpx derives -mmacosx-version-min from the target triple.
    mkdir -p "$d/_build"
    (cd "$d/_build" && ../configure --prefix="$PREFIX" --target=arm64-darwin20-gcc --enable-shared \
        --disable-static --disable-examples --disable-tools --disable-docs --disable-unit-tests \
        --enable-pic --enable-runtime-cpu-detect --enable-vp9-highbitdepth \
        && make -j"$JOBS" && make install)
    # libvpx names itself just "libvpx.N.dylib", so whatever links it records a name dyld cannot
    # resolve. Give it its full path, as every other library here has.
    local lib
    for lib in "$PREFIX"/lib/libvpx.*.dylib; do
        [ -L "$lib" ] || install_name_tool -id "$lib" "$lib"
    done
}

build_x264() {
    local rev=b35605ace3ddf7c1a5d67a2eb553f034aef41d55 d="$WORK/x264"
    rm -rf "$d"
    git init -q "$d"
    git -C "$d" fetch -q --depth 1 https://code.videolan.org/videolan/x264.git "$rev"
    git -C "$d" checkout -q FETCH_HEAD
    if [ "$(git -C "$d" rev-parse HEAD)" != "$rev" ]; then
        echo "ERROR: x264 checkout is not $rev" >&2
        exit 1
    fi
    (cd "$d" && ./configure --prefix="$PREFIX" --enable-shared --disable-static --disable-cli \
        --disable-lsmash --disable-swscale --disable-ffms --extra-cflags="$CFLAGS" \
        --extra-ldflags="$LDFLAGS" && make -j"$JOBS" && make install)
}

build_ffmpeg() {
    # Only what the messenger uses: the AVFoundation camera, the mov demuxer and AAC decoder for
    # voice messages from the phones (src/model/air/airvoicedecoder.cpp), the H.264/H.265 decoders
    # and the libx264 encoder toxav uses for video calls, MJPEG/raw camera frames and swscale.
    # No network, no filters, no hardware codecs. Homebrew's build had all of FFmpeg plus x265,
    # SVT-AV1, dav1d, vmaf, LAME and OpenSSL, none of which the app calls.
    local v=8.1.3 d
    prepare ffmpeg "https://ffmpeg.org/releases/ffmpeg-$v.tar.xz" 7138d28c96d9d3e3af4ee3d8cad72741f8ffb40da90c1112235dea3ecd3178a3
    d="$SRC_DIR"
    # The MOV demuxer is here for voice messages (AAC in MP4). Three advisories against 8.1.3 with no
    # fixed 8.1.x release sit in boxes a voice message never carries: CVE-2026-52297 (iacb, IAMF),
    # CVE-2026-96611 (ispe, HEIF image items) and CVE-2026-107678 (pssh, DRM init data). IAMF is
    # configured out below; the other two boxes leave the demuxer's parse table, so it skips them as
    # it skips any unknown box (HEIF grids then fail as "Non uniform HEIF tiles", with no arithmetic
    # on attacker-chosen sizes). A future FFmpeg that changes these lines stops the build here.
    local entry
    for entry in "{ MKTAG('p','s','s','h'), mov_read_pssh }," "{ MKTAG('i','s','p','e'), mov_read_ispe },"; do
        if [ "$(grep -cxF "$entry" "$d/libavformat/mov.c")" != 1 ]; then
            echo "ERROR: FFmpeg $v: '$entry' is not in libavformat/mov.c exactly once" >&2
            exit 1
        fi
        grep -vxF "$entry" "$d/libavformat/mov.c" >"$d/libavformat/mov.c.khandaq"
        mv "$d/libavformat/mov.c.khandaq" "$d/libavformat/mov.c"
    done
    (cd "$d" && ./configure --prefix="$PREFIX" --cc=clang --enable-shared --disable-static \
        --enable-gpl --enable-libx264 --enable-pthreads \
        --disable-programs --disable-doc --disable-debug --disable-network \
        --disable-videotoolbox --disable-audiotoolbox --disable-securetransport \
        --disable-coreimage --disable-metal --disable-sdl2 --disable-iconv --disable-lzma \
        --disable-bzlib --disable-xlib --disable-libxcb --enable-zlib \
        --disable-everything --disable-avfilter --disable-swresample --disable-iamf \
        --enable-indev=avfoundation --enable-demuxer=mov \
        --enable-decoder=aac,h264,hevc,mjpeg,rawvideo --enable-parser=aac,h264,hevc,mjpeg \
        --enable-encoder=libx264 \
        --extra-cflags="$CFLAGS $CPPFLAGS" --extra-ldflags="$LDFLAGS" \
        && make -j"$JOBS" && make install)
}

build_openal() {
    local v=1.25.2 d
    prepare openal-soft "https://openal-soft.org/openal-releases/openal-soft-$v.tar.bz2" 1dbaac44e7579d5bc8847ca8db4b2e8b9fd3961041f35ee20def4958301e1089
    d="$SRC_DIR"
    # The macOS 27 SDK marks the Core Audio render callback type nonblocking, and OpenAL Soft 1.25.2
    # passes plain lambdas there; it builds with -Werror=function-effects whenever the compiler knows
    # the flag. The annotation changes no generated code, so the check is answered "no" up front.
    cmake -S "$d" -B "$d/_build" "${CMAKE_COMMON[@]}" -DALSOFT_EXAMPLES=OFF -DALSOFT_UTILS=OFF \
        -DALSOFT_INSTALL_EXAMPLES=OFF -DALSOFT_INSTALL_UTILS=OFF -DALSOFT_BACKEND_PORTAUDIO=OFF \
        -DALSOFT_BACKEND_PULSEAUDIO=OFF -DALSOFT_BACKEND_SDL2=OFF -DALSOFT_BACKEND_SDL3=OFF \
        -DALSOFT_BACKEND_JACK=OFF -DALSOFT_REQUIRE_COREAUDIO=ON -DHAVE_WFUNCTION_EFFECTS=OFF
    cmake --build "$d/_build" -j"$JOBS"
    cmake --install "$d/_build"
}

build_sqlcipher() {
    # CommonCrypto instead of OpenSSL: it is part of macOS, so the bundle needs no OpenSSL at all
    # (QtNetwork already uses Secure Transport). The database format does not depend on the crypto
    # provider: same cipher, KDF and HMAC, so profiles written by v0.2.47 open unchanged.
    local v=4.16.0 d
    prepare sqlcipher "https://github.com/sqlcipher/sqlcipher/archive/refs/tags/v$v.tar.gz" d687bf981199ac019c6c87b11f92a5900aec777855e7ba5b30e5e1192933ce8a "sqlcipher-$v.tar.gz"
    d="$SRC_DIR"
    (cd "$d" && ./configure --prefix="$PREFIX" --disable-tcl --dll-basename=libsqlcipher \
        --includedir="$PREFIX/include/sqlcipher" --with-tempstore=yes \
        CFLAGS="$CFLAGS -DSQLITE_HAS_CODEC -DSQLCIPHER_CRYPTO_CC -DSQLITE_ENABLE_JSON1 \
-DSQLITE_ENABLE_FTS3 -DSQLITE_ENABLE_FTS3_PARENTHESIS -DSQLITE_ENABLE_FTS5 \
-DSQLITE_ENABLE_COLUMN_METADATA -DSQLITE_EXTRA_INIT=sqlcipher_extra_init \
-DSQLITE_EXTRA_SHUTDOWN=sqlcipher_extra_shutdown" \
        LDFLAGS="$LDFLAGS -framework Security -framework CoreFoundation" \
        && make -j"$JOBS" && make install)
    # The same names Homebrew's formula gives it, which is what the desktop CMake looks for.
    mv -f "$PREFIX/bin/sqlite3" "$PREFIX/bin/sqlcipher"
    sed 's/-lsqlite3/-lsqlcipher/' "$PREFIX/lib/pkgconfig/sqlite3.pc" >"$PREFIX/lib/pkgconfig/sqlcipher.pc"
    rm -f "$PREFIX/lib/pkgconfig/sqlite3.pc" "$PREFIX/lib/libsqlite3.a"
}

build_qrencode() {
    # Version and hash as pinned in khandaq-desktop/buildscripts/download/download_qrencode.sh.
    local v=4.1.1 d
    prepare qrencode "https://github.com/fukuchi/libqrencode/archive/refs/tags/v$v.tar.gz" 5385bc1b8c2f20f3b91d258bf8ccc8cf62023935df2d2676b5b67049f31a049c "qrencode-$v.tar.gz"
    d="$SRC_DIR"
    cmake -S "$d" -B "$d/_build" "${CMAKE_COMMON[@]}" -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
        -DBUILD_SHARED_LIBS=ON -DWITH_TOOLS=OFF \
        -DWITH_TESTS=OFF
    cmake --build "$d/_build" -j"$JOBS"
    cmake --install "$d/_build"
}

build_libexif() {
    # Version and hash as pinned in khandaq-desktop/buildscripts/download/download_libexif.sh.
    local v=0.6.26 d
    prepare libexif "https://github.com/libexif/libexif/releases/download/v$v/libexif-$v.tar.bz2" 0830ed253fceeb60444fb309598bc8a9491d3007dc054aad3a50a347c5597c57
    d="$SRC_DIR"
    (cd "$d" && ./configure --prefix="$PREFIX" --disable-static --enable-shared --disable-docs \
        --disable-nls --disable-dependency-tracking && make -j"$JOBS" && make install)
}

# --- toxcore and the extensions, from the sources committed under buildscripts/ ------------------

build_toxcore() {
    local s="$ROOT/khandaq-desktop/buildscripts/toxcore"
    cmake -S "$s" -B "$WORK/toxcore" "${CMAKE_COMMON[@]}" -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
        -DBOOTSTRAP_DAEMON=OFF -DENABLE_STATIC=OFF -DENABLE_SHARED=ON -DMUST_BUILD_TOXAV=ON
    cmake --build "$WORK/toxcore" -j"$JOBS"
    cmake --install "$WORK/toxcore"
}

build_toxext() {
    local s="$ROOT/khandaq-desktop/buildscripts/toxext"
    cmake -S "$s" -B "$WORK/toxext" "${CMAKE_COMMON[@]}" -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
        -DBUILD_TESTING=OFF
    cmake --build "$WORK/toxext" -j"$JOBS"
    cmake --install "$WORK/toxext"
}

build_toxext_messages() {
    local s="$ROOT/khandaq-desktop/buildscripts/toxext_messages"
    cmake -S "$s" -B "$WORK/toxext_messages" "${CMAKE_COMMON[@]}" -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
        -DBUILD_TESTING=OFF
    cmake --build "$WORK/toxext_messages" -j"$JOBS"
    cmake --install "$WORK/toxext_messages"
}

# Name, version (for the stamp) and builder, in dependency order.
ALL=(
    "pcre2 10.49" "zstd 1.5.7" "gettext 1.0" "glib 2.88.2" "libpng 1.6.59" "md4c 0.5.3"
    "xz 5.8.4" "jpeg 3.2.1" "tiff 4.7.2" "webp 1.6.0" "freetype 2.15.0"
    "sodium 1.0.22" "opus 1.6.1" "vpx 1.16.0" "x264 r3222" "ffmpeg 8.1.3+mov-hardening"
    "openal 1.25.2" "sqlcipher 4.16.0" "qrencode 4.1.1" "libexif 0.6.26"
    "toxcore 2e7a0675" "toxext 0.0.3" "toxext_messages 0.0.3"
)

# wanted NAME REQUESTED... -> whether NAME is among the requested libraries
wanted() {
    local want
    for want in "${@:2}"; do [ "$want" = "$1" ] && return 0; done
    return 1
}

for entry in "${ALL[@]}"; do
    name="${entry%% *}"
    version="${entry#* }"
    if [ "$#" -gt 0 ] && ! wanted "$name" "$@"; then
        continue
    fi
    stamp="$(stamp_of "$name" "$version")"
    if [ -f "$stamp" ]; then
        echo "==> $name $version: already built for macOS $TARGET"
        continue
    fi
    echo "==> $name $version (macOS $TARGET)"
    "build_$name"
    touch "$stamp"
done

# Every library in the prefix must load on the target macOS.
fail=0
while IFS= read -r -d '' lib; do
    case " $(lipo -archs "$lib") " in
        *" $ARCH "*) ;;
        *) echo "ERROR: $lib is not built for $ARCH" >&2; fail=1 ;;
    esac
    # Every reference must be a path dyld can resolve: absolute, or @rpath (the bundler maps those).
    bare="$(otool -L "$lib" | tail -n +2 | awk '{ print $1 }' | grep -vE '^(/|@rpath/)' || true)"
    if [ -n "$bare" ]; then
        echo "ERROR: $lib refers to $(echo "$bare" | tr '\n' ' ')without a path" >&2
        fail=1
    fi
    # An rpath outside the prefix (Homebrew's, say) means it was linked against a library that is
    # not in it.
    while IFS= read -r rp; do
        case "$rp" in
            @*|"$PREFIX"/*) ;;
            *) echo "ERROR: $lib has rpath $rp, outside $PREFIX" >&2; fail=1 ;;
        esac
    done < <(otool -l "$lib" | awk '$1 == "cmd" { r = ($2 == "LC_RPATH") } r && $1 == "path" { print $2 }')
    minos="$(otool -l "$lib" | awk '/LC_BUILD_VERSION/ { b = 1 } b && $1 == "minos" { print $2; exit }')"
    if [ -z "$minos" ]; then
        echo "ERROR: $lib has no LC_BUILD_VERSION" >&2
        fail=1
    elif [ "$(printf '%s\n%s\n' "$minos" "$TARGET" | sort -V | tail -1)" != "$TARGET" ]; then
        echo "ERROR: $lib requires macOS $minos, newer than $TARGET" >&2
        fail=1
    fi
done < <(find "$PREFIX/lib" -name '*.dylib' -type f -print0)
if [ "$fail" -ne 0 ]; then
    exit 1
fi
echo "==> $PREFIX: every library is $ARCH and loads on macOS $TARGET"
