#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/khandaq-desktop"
BUILD="$SRC/build"
DEPS="$SRC/local-deps"
DIST="$ROOT/dist/macos"

export PKG_CONFIG_PATH="${DEPS}/lib/pkgconfig:/opt/homebrew/opt/openal-soft/lib/pkgconfig:${PKG_CONFIG_PATH:-}"
export LIBRARY_PATH="/opt/homebrew/lib:${LIBRARY_PATH:-}"

echo "==> Checking tools..."
command -v cmake >/dev/null || { echo "Missing cmake"; exit 1; }
command -v brew >/dev/null || { echo "Missing homebrew"; exit 1; }

if [ ! -f "${DEPS}/lib/pkgconfig/toxcore.pc" ]; then
  echo "==> Building toxcore deps..."
  mkdir -p "$SRC/local-deps" "$SRC/buildscripts/toxcore" "$SRC/buildscripts/toxext" "$SRC/buildscripts/toxext_messages"
  brew bundle --file "$SRC/osx/Brewfile"
  # toxcore
  (cd "$SRC/buildscripts/toxcore" && \
    ../download/download_toxcore.sh && \
    cmake -DCMAKE_POLICY_VERSION_MINIMUM=3.5 -DCMAKE_INSTALL_PREFIX="$DEPS" \
      -DBOOTSTRAP_DAEMON=OFF -DCMAKE_BUILD_TYPE=Release -DENABLE_STATIC=OFF -DENABLE_SHARED=ON \
      -DCMAKE_OSX_DEPLOYMENT_TARGET=10.15 . && \
    cmake --build . -- -j"$(sysctl -n hw.ncpu)" && cmake --build . --target install)
  # toxext
  (cd "$SRC/buildscripts/toxext" && \
    ../download/download_toxext.sh && \
    cmake -DCMAKE_POLICY_VERSION_MINIMUM=3.5 -DCMAKE_INSTALL_PREFIX="$DEPS" \
      -DCMAKE_BUILD_TYPE=Release -DBUILD_TESTING=OFF -DCMAKE_OSX_DEPLOYMENT_TARGET=10.15 . && \
    cmake --build . -- -j"$(sysctl -n hw.ncpu)" && cmake --build . --target install)
  # toxext_messages
  (cd "$SRC/buildscripts/toxext_messages" && \
    ../download/download_toxext_messages.sh && \
    cmake -DCMAKE_POLICY_VERSION_MINIMUM=3.5 -DCMAKE_INSTALL_PREFIX="$DEPS" \
      -DCMAKE_BUILD_TYPE=Release -DBUILD_TESTING=OFF -DCMAKE_OSX_DEPLOYMENT_TARGET=10.15 . && \
    cmake --build . -- -j"$(sysctl -n hw.ncpu)" && cmake --build . --target install)
fi

mkdir -p "$BUILD" "$DIST"
cd "$BUILD"

echo "==> Configuring..."
cmake "$SRC" \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_OSX_DEPLOYMENT_TARGET=11.0 \
  -DUPDATE_CHECK=OFF \
  -DSPELL_CHECK=OFF \
  -DCMAKE_PREFIX_PATH="/opt/homebrew/opt/qt@5;${DEPS}"

echo "==> Building + bundling (macdeployqt)..."
make khandaq install -j"$(sysctl -n hw.ncpu)"

APP="$BUILD/khandaq.app"
BIN="$APP/Contents/MacOS/khandaq"
FW="$APP/Contents/Frameworks"

echo "==> Bundling NGC libtoxcore..."
TOXCORE_DYLIB="$DEPS/lib/libtoxcore.2.19.0.dylib"
if [ ! -f "$TOXCORE_DYLIB" ]; then
    TOXCORE_DYLIB="$(readlink -f "$DEPS/lib/libtoxcore.2.dylib" 2>/dev/null || realpath "$DEPS/lib/libtoxcore.2.dylib")"
fi
cp -f "$TOXCORE_DYLIB" "$FW/libtoxcore.2.dylib"
install_name_tool -id "@executable_path/../Frameworks/libtoxcore.2.dylib" "$FW/libtoxcore.2.dylib"
install_name_tool -change @rpath/libtoxcore.2.dylib "@executable_path/../Frameworks/libtoxcore.2.dylib" "$BIN" 2>/dev/null || true

echo "==> Fixing @rpath references..."
if otool -L "$BIN" | grep -q '@rpath/libtoxcore'; then
  install_name_tool -change @rpath/libtoxcore.2.dylib \
    @executable_path/../Frameworks/libtoxcore.2.dylib "$BIN"
fi

echo "==> Ad-hoc codesign..."
codesign --force --deep --sign - "$APP"

echo "==> Verifying bundle..."
if otool -L "$BIN" | grep -q '/opt/homebrew'; then
  echo "ERROR: binary still links to Homebrew — bundle incomplete"
  exit 1
fi

echo "==> Copying artifact..."
rm -rf "$DIST/khandaq.app"
rm -f "$DIST/khandaq-macos.dmg"
cp -R "$APP" "$DIST/"
( cd "$DIST" && rm -f khandaq-macos.zip && zip -qr khandaq-macos.zip khandaq.app )
( cd "$DIST" && shasum -a 256 khandaq.app/Contents/MacOS/khandaq khandaq-macos.zip 2>/dev/null | tee sha256sums.txt || true )
echo "Done: $DIST/khandaq.app ($(du -sh "$DIST/khandaq.app" | cut -f1))"
echo "      $DIST/khandaq-macos.zip"
