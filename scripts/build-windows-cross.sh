#!/usr/bin/env bash
# Cross-compile Khandaq for Windows (x86_64) via Docker
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/khandaq-desktop"
ARCH="${KHANDAQ_WINDOWS_ARCH:-x86_64}"
BUILD_TYPE="${KHANDAQ_WINDOWS_BUILD_TYPE:-Release}"
IMAGE="${KHANDAQ_WINDOWS_IMAGE:-khandaq-windows-builder:${ARCH}}"
DOCKERFILE="$SRC/buildscripts/docker/Dockerfile.windows_builder"
DIST="$ROOT/dist/windows/${ARCH}"

if [[ "$ARCH" == "x86_64" ]]; then
  WINEARCH=win64
elif [[ "$ARCH" == "i686" ]]; then
  WINEARCH=win32
else
  echo "Unsupported ARCH=$ARCH (use x86_64 or i686)"
  exit 1
fi

if [[ "${SKIP_IMAGE_BUILD:-0}" != "1" ]]; then
  echo "==> Building Windows cross-compile image: $IMAGE"
  echo "    (first run: 1–3 h — Qt, FFmpeg, toxcore inside Docker)"
  docker build \
    -f "$DOCKERFILE" \
    --build-arg "ARCH=${ARCH}" \
    --build-arg "WINEARCH=${WINEARCH}" \
    -t "$IMAGE" \
    "$SRC/buildscripts"
else
  echo "==> Skipping image build (SKIP_IMAGE_BUILD=1)"
fi

mkdir -p "$DIST"

echo "==> Cross-compiling Khandaq ($ARCH $BUILD_TYPE)..."
docker run --rm \
  -v "$SRC:/khandaq:ro" \
  -v "$DIST:/dist" \
  -v "khandaq-windows-build-cache-${ARCH}:/build" \
  "$IMAGE" \
  bash -ec "
    set -euo pipefail
    rm -rf /build/src
    mkdir -p /build/src
    tar -C /khandaq --exclude=build --exclude=local-deps --exclude=.git -cf - . | tar -C /build/src -xf -
    cd /build/src
    if [[ -x scripts/generate-khandaq-icons.sh ]]; then
      ./scripts/generate-khandaq-icons.sh || true
    fi
    mkdir -p /build/out && cd /build/out
    /build/src/windows/cross-compile/build.sh \
      --src-dir /build/src \
      --arch ${ARCH} \
      --build-type ${BUILD_TYPE}
    cp -a install-prefix/khandaq-${ARCH}-${BUILD_TYPE}.zip /dist/
    if [[ -d package-prefix ]]; then
      cp -a package-prefix/*.exe /dist/ 2>/dev/null || true
    fi
    if [[ -f /dist/Khandaq-installer.exe ]]; then
      cp -f /dist/Khandaq-installer.exe /dist/khandaq-windows-installer.exe
    fi
    sha256sum /dist/* > /dist/sha256sums.txt 2>/dev/null || true
    ls -la /dist/
    echo 'Windows cross-compile OK'
  "

echo ""
echo "Artifacts in $DIST:"
ls -la "$DIST"
