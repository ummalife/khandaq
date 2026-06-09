#!/usr/bin/env bash
# Reproducible Khandaq Linux build via Docker (Ubuntu 24.04)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/khandaq-desktop"
PLATFORM="${KHANDAQ_LINUX_PLATFORM:-linux/amd64}"
IMAGE="${KHANDAQ_LINUX_IMAGE:-khandaq-builder:ubuntu24.04-amd64}"
DOCKERFILE="$SRC/buildscripts/docker/Dockerfile.khandaq_ubuntu2404"
CACHE_VOL="${KHANDAQ_LINUX_CACHE:-khandaq-linux-build-cache-amd64}"

if [[ "${SKIP_IMAGE_BUILD:-0}" != "1" ]]; then
  echo "==> Building Docker image: $IMAGE ($PLATFORM)"
  docker build --platform "$PLATFORM" -f "$DOCKERFILE" -t "$IMAGE" "$SRC/buildscripts"
else
  echo "==> Skipping image build (SKIP_IMAGE_BUILD=1)"
fi

echo "==> Compiling Khandaq inside container ($PLATFORM)..."
docker run --rm --platform "$PLATFORM" \
  -v "$SRC:/khandaq:ro" \
  -v "$ROOT/scripts:/scripts:ro" \
  -v "$ROOT/dist/linux:/dist" \
  -v "$CACHE_VOL:/build" \
  "$IMAGE" \
  bash -ec '
    set -euo pipefail
    export PKG_CONFIG_PATH="/usr/local/lib/pkgconfig:${PKG_CONFIG_PATH:-}"
    rm -rf /build/src
    mkdir -p /build/src
    tar -C /khandaq --exclude=build --exclude=local-deps --exclude=.git -cf - . | tar -C /build/src -xf -
    cd /build/src
    if [[ -x scripts/generate-khandaq-icons.sh ]]; then
      ./scripts/generate-khandaq-icons.sh || true
    fi
    mkdir -p /build/out && cd /build/out
    cmake /build/src \
      -DCMAKE_BUILD_TYPE=Release \
      -DUPDATE_CHECK=OFF \
      -DSPELL_CHECK=OFF \
      -DDESKTOP_NOTIFICATIONS=OFF
    make -j"$(nproc)" khandaq
    install -m 755 khandaq /dist/khandaq
    cp -f /build/src/khandaq.desktop /dist/
    cp -f /build/src/res/org.khandaq.messenger.appdata.xml /dist/
    bash /scripts/bundle-linux-portable.sh /dist
    sha256sum /dist/khandaq.bin | tee /dist/khandaq.sha256
    file /dist/khandaq.bin
    echo "Docker build OK"
  '

echo "Artifact: $ROOT/dist/linux/khandaq"
