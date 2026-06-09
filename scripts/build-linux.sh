#!/usr/bin/env bash
# Khandaq Desktop — native Linux build (Ubuntu 22.04/24.04, Debian)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/khandaq-desktop"
BUILD="${BUILD_DIR:-$SRC/build-linux}"
DIST="$ROOT/dist/linux"
INSTALL_DEPS=0
BUILD_DEPS=0

usage() {
  cat <<EOF
Usage: $0 [options]

Options:
  --install-deps   apt install build dependencies (Ubuntu/Debian, requires sudo)
  --build-deps     compile toxcore/toxext into /usr/local
  --clean          remove build directory before configure
  -h, --help       show this help

Env:
  BUILD_DIR        override build directory (default: khandaq-desktop/build-linux)
  JOBS             parallel make jobs (default: nproc)
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --install-deps) INSTALL_DEPS=1; shift ;;
    --build-deps) BUILD_DEPS=1; shift ;;
    --clean) rm -rf "$BUILD"; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown option: $1"; usage; exit 1 ;;
  esac
done

apt_packages=(
  build-essential cmake extra-cmake-modules git pkg-config
  qtbase5-dev qttools5-dev-tools libqt5svg5-dev libqt5opengl5-dev
  libavcodec-dev libavdevice-dev libavfilter-dev libavutil-dev
  libswscale-dev libswresample-dev libopenal-dev libqrencode-dev
  libsqlcipher-dev libsodium-dev libopus-dev libvpx-dev
  libxss-dev librsvg2-bin file
)

install_apt_deps() {
  echo "==> Installing apt dependencies..."
  sudo apt-get update
  sudo apt-get install -y --no-install-recommends "${apt_packages[@]}"
}

check_tools() {
  echo "==> Checking build tools..."
  for cmd in cmake make pkg-config; do
    command -v "$cmd" >/dev/null || { echo "Missing: $cmd"; exit 1; }
  done
}

check_pkg_deps() {
  echo "==> Checking pkg-config dependencies..."
  export PKG_CONFIG_PATH="/usr/local/lib/pkgconfig:/usr/local/lib64/pkgconfig:${PKG_CONFIG_PATH:-}"
  MISSING=()
  for pkg in Qt5Widgets libavcodec toxcore libsqlcipher; do
    pkg-config --exists "$pkg" 2>/dev/null || MISSING+=("$pkg")
  done
  if [[ ${#MISSING[@]} -gt 0 ]]; then
    echo "Missing packages: ${MISSING[*]}"
    echo "Run: $0 --install-deps   (system packages)"
    echo "Run: $0 --build-deps     (toxcore from source)"
    exit 1
  fi
}

build_tox_deps() {
  echo "==> Building toxcore stack..."
  pushd "$SRC/buildscripts" >/dev/null
  mkdir -p toxcore toxext toxext_messages
  export PKG_CONFIG_PATH="/usr/local/lib/pkgconfig:${PKG_CONFIG_PATH:-}"

  (cd toxcore && ../download/download_toxcore.sh && \
    cmake -DCMAKE_POLICY_VERSION_MINIMUM=3.5 -DBOOTSTRAP_DAEMON=OFF -DCMAKE_BUILD_TYPE=Release . && \
    cmake --build . -- -j"${JOBS:-$(nproc)}" && sudo cmake --build . --target install)

  (cd toxext && ../download/download_toxext.sh && \
    cmake -DCMAKE_POLICY_VERSION_MINIMUM=3.5 -DCMAKE_BUILD_TYPE=Release -DBUILD_TESTING=OFF . && \
    cmake --build . -- -j"${JOBS:-$(nproc)}" && sudo cmake --build . --target install)

  (cd toxext_messages && ../download/download_toxext_messages.sh && \
    cmake -DCMAKE_POLICY_VERSION_MINIMUM=3.5 -DCMAKE_BUILD_TYPE=Release -DBUILD_TESTING=OFF . && \
    cmake --build . -- -j"${JOBS:-$(nproc)}" && sudo cmake --build . --target install)

  sudo ldconfig
  popd >/dev/null
}

[[ "$INSTALL_DEPS" -eq 1 ]] && install_apt_deps
[[ "$BUILD_DEPS" -eq 1 ]] && build_tox_deps

check_tools
check_pkg_deps

# Brand icons (idempotent)
if [[ -x "$SRC/scripts/generate-khandaq-icons.sh" ]]; then
  "$SRC/scripts/generate-khandaq-icons.sh"
fi

mkdir -p "$BUILD" "$DIST"
cd "$BUILD"

export PKG_CONFIG_PATH="/usr/local/lib/pkgconfig:/usr/local/lib64/pkgconfig:${PKG_CONFIG_PATH:-}"

echo "==> Configuring (Release)..."
cmake "$SRC" \
  -DCMAKE_BUILD_TYPE=Release \
  -DUPDATE_CHECK=OFF \
  -DSPELL_CHECK=OFF \
  -DDESKTOP_NOTIFICATIONS=OFF

echo "==> Building khandaq..."
make -j"${JOBS:-$(nproc)}" khandaq

echo "==> Installing artifact to dist/linux/"
install -m 755 "$BUILD/khandaq" "$DIST/khandaq"
cp -f "$SRC/khandaq.desktop" "$DIST/"
cp -f "$SRC/res/org.khandaq.messenger.appdata.xml" "$DIST/"

if command -v sha256sum >/dev/null; then
  sha256sum "$DIST/khandaq" | tee "$DIST/khandaq.sha256"
elif command -v shasum >/dev/null; then
  shasum -a 256 "$DIST/khandaq" | tee "$DIST/khandaq.sha256"
fi

file "$DIST/khandaq"
echo "Done: $DIST/khandaq"
echo "Verify: $ROOT/scripts/verify-linux-build.sh"
