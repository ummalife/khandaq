#!/usr/bin/env bash
# Reproducible Khandaq Linux build via Docker (Ubuntu 24.04)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/khandaq-desktop"
PLATFORM="${KHANDAQ_LINUX_PLATFORM:-linux/amd64}"
IMAGE="${KHANDAQ_LINUX_IMAGE:-khandaq-builder:ubuntu24.04-amd64}"
DOCKERFILE="$SRC/buildscripts/docker/Dockerfile.khandaq_ubuntu2404"
CACHE_VOL="${KHANDAQ_LINUX_CACHE:-khandaq-linux-build-cache-amd64}"

# KHANDAQ (desktop release v0.2.47): the release-blocking waiver gate (audit round 3, F-23) runs here,
# on the host, before anything is built. bundle-linux-portable.sh carries the same gate, but in the
# container below it sees only khandaq-desktop/ and scripts/, so its check could not find
# khandaq-desktop/buildscripts/bundled-deps.json and the build stopped at its last step after a
# complete compile. The container is told the gate already ran (KHANDAQ_SKIP_DEP_GATE=1 on that one
# command).
# Skippable only deliberately, for a local experiment that is not a release:
#   KHANDAQ_SKIP_DEP_GATE=1 ./scripts/build-linux-docker.sh
if [ "${KHANDAQ_SKIP_DEP_GATE:-0}" != "1" ]; then
    if command -v python3 >/dev/null 2>&1; then
        echo "==> bundled dependency gate (waivers must be live)"
        python3 "$ROOT/scripts/check-bundled-deps-eol.py" --release
    else
        echo "ERROR: python3 not found; cannot run the bundled dependency gate." >&2
        echo "       Install python3, or set KHANDAQ_SKIP_DEP_GATE=1 if this is not a release." >&2
        exit 1
    fi
fi

if [[ "${SKIP_IMAGE_BUILD:-0}" != "1" ]]; then
  echo "==> Building Docker image: $IMAGE ($PLATFORM)"
  docker build --platform "$PLATFORM" -f "$DOCKERFILE" -t "$IMAGE" "$SRC/buildscripts"
else
  echo "==> Skipping image build (SKIP_IMAGE_BUILD=1)"
fi

# KHANDAQ (desktop release v0.2.47): the container gets a copy of khandaq-desktop/ without the .git of
# the monorepo, so CMake's own `git describe` found nothing and Settings > About said "Nightly", with
# "build without git" for the commit. What git says here goes in instead.
GIT_VERSION="$(git -C "$ROOT" rev-parse HEAD 2>/dev/null || true)"
GIT_DESCRIBE="$(git -C "$ROOT" describe --tags 2>/dev/null || true)"
GIT_DESCRIBE_EXACT="$(git -C "$ROOT" describe --exact-match --tags HEAD 2>/dev/null || true)"
for value in "$GIT_VERSION" "$GIT_DESCRIBE" "$GIT_DESCRIBE_EXACT"; do
  if [[ -n "$value" && ! "$value" =~ ^[A-Za-z0-9._-]+$ ]]; then
    echo "unexpected output of git: $value" >&2
    exit 1
  fi
done
echo "==> Settings > About will say ${GIT_DESCRIBE:-Nightly}, commit ${GIT_VERSION:-build without git}"

echo "==> Compiling Khandaq inside container ($PLATFORM)..."
docker run --rm --platform "$PLATFORM" \
  -e KHANDAQ_GIT_VERSION="$GIT_VERSION" \
  -e KHANDAQ_GIT_DESCRIBE="$GIT_DESCRIBE" \
  -e KHANDAQ_GIT_DESCRIBE_EXACT="$GIT_DESCRIBE_EXACT" \
  -v "$SRC:/khandaq:ro" \
  -v "$ROOT/scripts:/scripts:ro" \
  -v "$ROOT/dist/linux:/dist" \
  -v "$CACHE_VOL:/build" \
  "$IMAGE" \
  bash -ec '
    set -euo pipefail
    export PKG_CONFIG_PATH="/usr/local/lib/pkgconfig:${PKG_CONFIG_PATH:-}"
    # FFmpeg/toxav .pc files list optional GPU/OpenCL libs as Libs.private — not needed to link khandaq.
    for pc in /usr/local/lib/pkgconfig/*.pc /usr/lib/x86_64-linux-gnu/pkgconfig/libav*.pc; do
      [[ -f "$pc" ]] || continue
      sed -i "/^Libs.private:/d" "$pc" || true
    done
    rm -rf /build/src
    mkdir -p /build/src
    tar -C /khandaq --exclude=build --exclude=local-deps --exclude=.git -cf - . | tar -C /build/src -xf -
    cd /build/src
    if [[ -x scripts/generate-khandaq-icons.sh ]]; then
      ./scripts/generate-khandaq-icons.sh || true
    fi
    mkdir -p /build/out && cd /build/out
    # /build/out is kept between runs and a value given with -D stays in its CMakeCache.txt, so an
    # empty one removes the entry (-U) instead of building with the version of an earlier run.
    version_args=()
    for name in GIT_DESCRIBE GIT_DESCRIBE_EXACT GIT_VERSION; do
      value_name="KHANDAQ_${name}"
      if [[ -n "${!value_name:-}" ]]; then version_args+=("-D${name}=${!value_name}"); else version_args+=("-U${name}"); fi
    done
    cmake /build/src \
      -DCMAKE_BUILD_TYPE=Release \
      -DUPDATE_CHECK=OFF \
      -DSPELL_CHECK=OFF \
      -DDESKTOP_NOTIFICATIONS=OFF \
      "${version_args[@]}"
    make -j"$(nproc)" khandaq
    install -m 755 khandaq /dist/khandaq
    cp -f /build/src/khandaq.desktop /dist/
    cp -f /build/src/res/org.khandaq.messenger.appdata.xml /dist/
    # The waiver gate ran on the host at the top of build-linux-docker.sh.
    KHANDAQ_SKIP_DEP_GATE=1 bash /scripts/bundle-linux-portable.sh /dist
    sha256sum /dist/khandaq.bin | tee /dist/khandaq.sha256
    file /dist/khandaq.bin
    echo "Docker build OK"
  '

# KHANDAQ (desktop release v0.2.47): INSTALL.txt goes into the portable archive (deploy-site.sh packs
# it) and into the .deb as its README (build-linux-deb.sh), and the site tells people to read it,
# but no script made it: v0.2.12 carried a copy written by hand. It lives in the repository now.
cp -f "$ROOT/packaging/linux/INSTALL.txt" "$ROOT/dist/linux/INSTALL.txt"

echo "Artifact: $ROOT/dist/linux/khandaq"
