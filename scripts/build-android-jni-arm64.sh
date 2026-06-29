#!/usr/bin/env bash
# Build libjni-c-toxcore.so for Android (arm64-v8a) with UTF-8 JNI fixes.
# Requires Docker. Full build takes ~30-60 min (toxcore + deps).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TRIFA="$ROOT/khandaq-android-trifa"
OUT="$TRIFA/android-refimpl-app/app/nativelibs/arm64-v8a"
IMAGE="${KHANDAQ_JNI_BUILD_IMAGE:-ubuntu:24.04}"

if ! command -v docker >/dev/null; then
  echo "Docker required for native JNI build"
  exit 1
fi

mkdir -p "$OUT"

echo "==> Building libjni-c-toxcore.so (arm64-v8a) in Docker ($IMAGE)..."
docker run --rm \
  -v "$TRIFA:/src:ro" \
  -v "$OUT:/out" \
  "$IMAGE" bash -c '
    set -euo pipefail
    export DEBIAN_FRONTEND=noninteractive
    apt-get update -qq
    apt-get install -y -qq git curl wget unzip build-essential python3 file binutils > /dev/null
    cd /tmp
    # Minimal path: run upstream circle deps (arm64 section only) if present
    if [[ -x /src/circle_scripts/deps.sh ]]; then
      echo "NOTE: full deps.sh build not auto-run (too heavy)."
      echo "Run manually inside container or on CI:"
      echo "  cd /src/circle_scripts && bash deps.sh"
      echo "Then copy artefacts/android/libs/arm64-v8a/libjni-c-toxcore.so to nativelibs/"
      exit 2
    fi
    exit 3
  ' || {
    echo ""
    echo "Manual JNI rebuild steps:"
    echo "  1. Run CircleCI deps.sh on Linux (see khandaq-android-trifa/circle_scripts/deps.sh)"
    echo "  2. cp artefacts/android/libs/arm64-v8a/libjni-c-toxcore.so \\"
    echo "       khandaq-android-trifa/android-refimpl-app/app/nativelibs/arm64-v8a/"
    echo "  3. ./scripts/build-android-trifa.sh"
    exit 1
  }

echo "JNI build OK -> $OUT/libjni-c-toxcore.so"
