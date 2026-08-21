#!/usr/bin/env bash
# Cross-compile Khandaq for Windows (x86_64) via Docker
set -euo pipefail


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
    if [[ \"\${REFRESH_TOXCORE:-1}\" != \"0\" ]]; then
      echo \"==> Building x264 (required for toxav in NGC toxcore)...\"
      rm -rf /build/x264-refresh
      mkdir -p /build/x264-refresh && cd /build/x264-refresh
      /build/src/buildscripts/build_x264.sh --arch ${WINEARCH}
      echo \"==> Refreshing NGC toxcore (zoff fork) into /windows prefix...\"
      rm -rf /build/toxcore-refresh
      mkdir -p /build/toxcore-refresh && cd /build/toxcore-refresh
      FORCE_TOXCORE_DOWNLOAD=1 /build/src/buildscripts/build_toxcore.sh --arch ${WINEARCH}
      cp -f /windows/bin/libtoxcore.dll /export/libtoxcore.dll 2>/dev/null || true
      cp -f /windows/bin/libx264-*.dll /export/ 2>/dev/null || true
      cd /build/src
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
