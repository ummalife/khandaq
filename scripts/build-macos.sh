#!/usr/bin/env bash
set -euo pipefail

# An x86_64 bash (Intel Homebrew's /usr/local/bin/bash, first in PATH on some Macs) runs under
# Rosetta, and configure scripts then take an Apple Silicon Mac for an Intel one. Run natively.
if [ "$(sysctl -n sysctl.proc_translated 2>/dev/null || echo 0)" = "1" ]; then
    exec /usr/bin/arch -arm64 /bin/bash "$0" "$@"
fi


# KHANDAQ (audit round 3, F-23): the release-blocking waiver gate, on the path it was written for.
#
# scripts/check-bundled-deps-eol.py has had a --release mode since the first audit round: no grace
# period, and a waiver with under 14 days left may not carry a release. It was wired into the ANDROID
# release workflow and into nothing else, so the DESKTOP builds -- the ones that actually bundle
# OpenSSL 1.1.1w and Qt 5.12.12, the two components the waivers are about -- never consulted it. A
# desktop artifact could be produced on an expired waiver, which is precisely what the finding asked
# to be impossible.
#
# Skippable only deliberately, for a local experiment that is not a release:
#   KHANDAQ_SKIP_DEP_GATE=1 ./scripts/<this script>
if [ "${KHANDAQ_SKIP_DEP_GATE:-0}" != "1" ]; then
    _gate_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
    if command -v python3 >/dev/null 2>&1; then
        echo "==> bundled dependency gate (waivers must be live)"
        python3 "${_gate_root}/scripts/check-bundled-deps-eol.py" --release
    else
        echo "ERROR: python3 not found; cannot run the bundled dependency gate." >&2
        echo "       Install python3, or set KHANDAQ_SKIP_DEP_GATE=1 if this is not a release." >&2
        exit 1
    fi
fi

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/khandaq-desktop"
BUILD="$SRC/build"
DIST="$ROOT/dist/macos"
# The macOS the bundle promises (LSMinimumSystemVersion) is the one everything in it is built for.
TARGET="$(/usr/libexec/PlistBuddy -c "Print :LSMinimumSystemVersion" "$SRC/osx/info.plist")"
MACOS_DEPS="${KHANDAQ_MACOS_DEPS:-$SRC/macos-deps}"
ARCH="$(uname -m)"

echo "==> Checking tools..."
command -v cmake >/dev/null || { echo "Missing cmake"; exit 1; }
command -v pkg-config >/dev/null || { echo "Missing pkg-config"; exit 1; }
command -v brew >/dev/null || { echo "Missing homebrew (Qt 5 comes from qt@5)"; exit 1; }
QT_PREFIX="${KHANDAQ_QT_PREFIX:-$(brew --prefix qt@5)}"
[ -x "$QT_PREFIX/bin/macdeployqt" ] || { echo "Missing macdeployqt in $QT_PREFIX/bin"; exit 1; }

# KHANDAQ: v0.2.47 linked FFmpeg, OpenSSL, SQLCipher, Opus, libvpx, OpenAL and the libraries
# Homebrew's Qt uses straight from Homebrew, whose bottles require the macOS they were built on
# (26.0), so the app did not start on macOS 11-15. Everything except Qt itself now comes from
# scripts/build-macos-deps.sh, built for $TARGET, and the bundle is checked before it is signed.
echo "==> Third-party libraries for macOS $TARGET..."
KHANDAQ_MACOS_DEPS="$MACOS_DEPS" KHANDAQ_MACOS_TARGET="$TARGET" "$ROOT/scripts/build-macos-deps.sh"

# Nothing from Homebrew but Qt may reach the build.
unset CPATH C_INCLUDE_PATH CPLUS_INCLUDE_PATH OBJC_INCLUDE_PATH LIBRARY_PATH PKG_CONFIG_PATH \
    DYLD_LIBRARY_PATH DYLD_FALLBACK_LIBRARY_PATH
export PKG_CONFIG_LIBDIR="$MACOS_DEPS/lib/pkgconfig"
export MACOSX_DEPLOYMENT_TARGET="$TARGET"

mkdir -p "$BUILD" "$DIST"
cd "$BUILD"

echo "==> Configuring..."
# pkg-config results live in the cache and are not looked up again, so a cache from an earlier
# configure would keep whatever it found then.
rm -f "$BUILD/CMakeCache.txt"
cmake "$SRC" \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_OSX_DEPLOYMENT_TARGET="$TARGET" \
  -DUPDATE_CHECK=OFF \
  -DSPELL_CHECK=OFF \
  -DKHANDAQ_HOMEBREW_PATHS=OFF \
  -DCMAKE_PREFIX_PATH="$QT_PREFIX;$MACOS_DEPS" \
  -DCMAKE_IGNORE_PREFIX_PATH="/opt/homebrew;/usr/local" \
  -DCMAKE_FIND_USE_SYSTEM_ENVIRONMENT_PATH=OFF \
  -DCMAKE_MAKE_PROGRAM=/usr/bin/make \
  -DCMAKE_C_COMPILER=/usr/bin/cc \
  -DCMAKE_CXX_COMPILER=/usr/bin/c++ \
  -DPKG_CONFIG_EXECUTABLE="$(command -v pkg-config)" \
  -DMACDEPLOYQT_PATH="$QT_PREFIX/bin"

# Homebrew supplies Qt and nothing else the app is compiled or linked against.
QT_REAL="$(cd "$QT_PREFIX" && pwd -P)"
leaks="$(find "$BUILD/CMakeFiles" \( -name flags.make -o -name link.txt \) -exec cat {} + \
    | grep -oE '(/opt/homebrew|/usr/local)/[^[:space:];"]*' \
    | awk -v a="$QT_PREFIX/" -v b="$QT_REAL/" 'index($0, a) != 1 && index($0, b) != 1' \
    | sort -u || true)"
if [ -n "$leaks" ]; then
    echo "ERROR: the build takes more than Qt from Homebrew:" >&2
    echo "$leaks" | sed 's/^/  /' >&2
    exit 1
fi

echo "==> Building + bundling (macdeployqt)..."
# A bundle left by an earlier run already has libraries swapped and references rewritten;
# macdeployqt starts from a fresh one so every run produces the same bundle.
rm -rf "$BUILD/khandaq.app"
make khandaq install -j"$(sysctl -n hw.ncpu)"

APP="$BUILD/khandaq.app"
FW="$APP/Contents/Frameworks"
mkdir -p "$FW"

# macdeployqt copied the libraries Homebrew's QtCore and QtGui link (glib, gthread, libintl, PCRE2,
# zstd, libpng, md4c) and those its plugins link (FreeType, libjpeg, libtiff, liblzma, libwebp)
# from Homebrew. Swap each one, and anything else in Frameworks that the prefix also has, for the
# copy built for $TARGET; file names stay, so the references stay valid.
echo "==> Replacing Homebrew libraries with the ones built for macOS $TARGET..."
for lib in "$FW"/*.dylib; do
    name="$(basename "$lib")"
    if [ -e "$MACOS_DEPS/lib/$name" ]; then
        cp -f "$(realpath "$MACOS_DEPS/lib/$name")" "$lib"
        chmod u+w "$lib"
        install_name_tool -id "@executable_path/../Frameworks/$name" "$lib"
    fi
done

# Every reference to the prefix, to Homebrew or through @rpath now points into Frameworks, copying
# a library that is not there yet; repeat until nothing changes, since a copied library brings its
# own references. A library's own install name gets the same treatment (Qt's plugins keep their
# Homebrew one), though dyld does not use it for a plugin that is opened by path.
is_macho() { file -b "$1" | grep -q '^Mach-O'; }
# install_id FILE -> FILE's own install name; empty for an executable
install_id() { otool -arch "$ARCH" -D "$1" | tail -n +2; }
# deps_of FILE -> what FILE loads; otool -L lists a library's own install name among them.
# (awk does the filtering: a shell builtin writing into a process substitution from a subshell
# that has children can lose a line to EINTR, and a lost line here is a missed dependency.)
deps_of() {
    otool -arch "$ARCH" -L "$1" | tail -n +2 | awk -v id="$(install_id "$1")" '$1 != id { print $1 }'
}
outside_ref() {
    case "$1" in
        "$MACOS_DEPS"/*|/opt/homebrew/*|/usr/local/*|@rpath/*) return 0 ;;
    esac
    return 1
}
# rpaths_of FILE -> FILE's LC_RPATH entries
rpaths_of() {
    otool -arch "$ARCH" -l "$1" | awk '$1 == "cmd" { r = ($2 == "LC_RPATH") } r && $1 == "path" { print $2 }'
}
changed=1
while [ "$changed" -eq 1 ]; do
    changed=0
    while IFS= read -r -d '' f; do
        is_macho "$f" || continue
        if outside_ref "$(install_id "$f")"; then
            install_name_tool -id "@executable_path/../${f#"$APP"/Contents/}" "$f"
            changed=1
        fi
        while IFS= read -r dep; do
            outside_ref "$dep" || continue
            framework="$(sed -n 's|^.*/\([^/]*\.framework/.*\)$|\1|p' <<<"$dep")"
            if [ -n "$framework" ]; then
                # Qt itself: macdeployqt has put the framework in the bundle already.
                if [ ! -e "$FW/$framework" ]; then
                    echo "ERROR: ${f#"$APP"/} needs $dep, which macdeployqt did not bundle" >&2
                    exit 1
                fi
                install_name_tool -change "$dep" "@executable_path/../Frameworks/$framework" "$f"
                changed=1
                continue
            fi
            base="$(basename "$dep")"
            if [ ! -e "$FW/$base" ]; then
                if [ -e "$MACOS_DEPS/lib/$base" ]; then
                    cp -f "$(realpath "$MACOS_DEPS/lib/$base")" "$FW/$base"
                    chmod u+w "$FW/$base"
                    install_name_tool -id "@executable_path/../Frameworks/$base" "$FW/$base"
                else
                    echo "ERROR: ${f#"$APP"/} needs $dep, which is neither bundled nor in $MACOS_DEPS" >&2
                    exit 1
                fi
            fi
            install_name_tool -change "$dep" "@executable_path/../Frameworks/$base" "$f"
            changed=1
        done < <(deps_of "$f")
    done < <(find "$APP/Contents" -type f -print0)
done

# The linker leaves an rpath for every directory a library was linked from (the prefix, build trees,
# Qt's lib directory). Nothing in the bundle loads through them any more, and on another Mac they
# would only send dyld looking outside the bundle.
while IFS= read -r -d '' f; do
    is_macho "$f" || continue
    while IFS= read -r rp; do
        case "$rp" in
            @*) ;;
            *) install_name_tool -delete_rpath "$rp" "$f" ;;
        esac
    done < <(rpaths_of "$f")
done < <(find "$APP/Contents" -type f -print0)

# Nothing in the bundle may need a newer macOS than Info.plist promises, or a library outside the
# bundle and the system: either one is an app that does not start on somebody's Mac.
echo "==> Checking the bundle against macOS $TARGET..."
BUNDLE_TARGET="$(/usr/libexec/PlistBuddy -c "Print :LSMinimumSystemVersion" "$APP/Contents/Info.plist")"
if [ "$BUNDLE_TARGET" != "$TARGET" ]; then
    echo "ERROR: the bundle's Info.plist declares macOS $BUNDLE_TARGET, expected $TARGET" >&2
    exit 1
fi
fail=0
checked=0
while IFS= read -r -d '' f; do
    is_macho "$f" || continue
    checked=$((checked + 1))
    case " $(lipo -archs "$f") " in
        *" $ARCH "*) ;;
        *) echo "  ${f#"$APP"/}: not built for $ARCH" >&2; fail=1 ;;
    esac
    minos="$(otool -arch "$ARCH" -l "$f" | awk '/LC_BUILD_VERSION/ { b = 1 } b && $1 == "minos" { print $2; exit }')"
    if [ -z "$minos" ]; then
        minos="$(otool -arch "$ARCH" -l "$f" | awk '/LC_VERSION_MIN_MACOSX/ { v = 1 } v && $1 == "version" { print $2; exit }')"
    fi
    if [ -z "$minos" ] || [ "$(printf '%s\n%s\n' "$minos" "$TARGET" | sort -V | tail -1)" != "$TARGET" ]; then
        echo "  ${f#"$APP"/}: requires macOS ${minos:-unknown}" >&2
        fail=1
    fi
    outside="$(deps_of "$f" | grep -vE '^(/usr/lib/|/System/Library/|@executable_path/|@loader_path/)' || true)"
    if [ -n "$outside" ]; then
        echo "  ${f#"$APP"/}: links $(echo "$outside" | tr '\n' ' ')" >&2
        fail=1
    fi
    case "$(install_id "$f")" in
        /*) echo "  ${f#"$APP"/}: is named $(install_id "$f")" >&2; fail=1 ;;
    esac
    while IFS= read -r rp; do
        case "$rp" in
            @*) ;;
            *) echo "  ${f#"$APP"/}: has rpath $rp" >&2; fail=1 ;;
        esac
    done < <(rpaths_of "$f")
    # Each bundled library it loads must be there, with at least the compatibility version it was
    # linked against: dyld refuses an older one. The replacements built for $TARGET must keep the
    # versions Homebrew's builds had.
    id="$(install_id "$f")"
    while IFS= read -r line; do
        dep="${line%% (compatibility version*}"
        [ "$dep" != "$id" ] || continue
        case "$dep" in
            @executable_path/../Frameworks/*) ;;
            *) continue ;;
        esac
        target="$FW/${dep#@executable_path/../Frameworks/}"
        if [ ! -e "$target" ]; then
            echo "  ${f#"$APP"/}: loads $dep, which is not in the bundle" >&2
            fail=1
            continue
        fi
        case "$dep" in
            *.framework/*) continue ;; # Qt's own frameworks come from one Qt build
        esac
        need="$(sed -n 's/.*(compatibility version \([0-9.]*\),.*/\1/p' <<<"$line")"
        have="$(otool -arch "$ARCH" -L "$target" | sed -n '2s/.*(compatibility version \([0-9.]*\),.*/\1/p')"
        if [ -z "$need" ] || [ -z "$have" ] \
            || [ "$(printf '%s\n%s\n' "$need" "$have" | sort -V | tail -1)" != "$have" ]; then
            echo "  ${f#"$APP"/}: needs $(basename "$dep") ${need:-?}, the bundle has ${have:-?}" >&2
            fail=1
        fi
    done < <(otool -arch "$ARCH" -L "$f" | tail -n +2 | sed 's/^[[:space:]]*//')
done < <(find "$APP/Contents" -type f -print0)
if [ "$fail" -ne 0 ]; then
    echo "ERROR: the bundle would not start on macOS $TARGET (see above)" >&2
    exit 1
fi
echo "    $checked Mach-O files, all $ARCH, for macOS $TARGET or older, no outside libraries"

echo "==> Ad-hoc codesign..."
codesign --force --deep --sign - "$APP"

echo "==> Copying artifact..."
rm -rf "$DIST/khandaq.app"
rm -f "$DIST/khandaq-macos.dmg"
cp -R "$APP" "$DIST/"
( cd "$DIST" && rm -f khandaq-macos.zip && zip -qr khandaq-macos.zip khandaq.app )
( cd "$DIST" && shasum -a 256 khandaq.app/Contents/MacOS/khandaq khandaq-macos.zip 2>/dev/null | tee sha256sums.txt || true )
echo "Done: $DIST/khandaq.app ($(du -sh "$DIST/khandaq.app" | cut -f1))"
echo "      $DIST/khandaq-macos.zip"
