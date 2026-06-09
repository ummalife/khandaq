#!/usr/bin/env bash
# Create GPL source release archive for khandaq-desktop
# Usage: ./scripts/package-release-source.sh [VERSION]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERSION="${1:-0.1.0-alpha}"
OUT_DIR="$ROOT/dist/releases"
ARCHIVE="khandaq-desktop-${VERSION}-source.tar.gz"

mkdir -p "$OUT_DIR"

tar -czf "$OUT_DIR/$ARCHIVE" \
  --exclude='.git' \
  --exclude='khandaq-desktop/build' \
  --exclude='khandaq-desktop/.cache' \
  -C "$ROOT" \
  NOTICE \
  THIRD_PARTY_LICENSES.md \
  khandaq-desktop/LICENSE \
  khandaq-desktop/LICENSES \
  khandaq-desktop/CMakeLists.txt \
  khandaq-desktop/src \
  khandaq-desktop/res \
  khandaq-desktop/translations \
  khandaq-desktop/themes \
  khandaq-desktop/windows \
  khandaq-desktop/osx \
  khandaq-desktop/cmake \
  khandaq-desktop/buildscripts \
  khandaq-desktop/smileys \
  khandaq-desktop/tools \
  khandaq-desktop/INSTALL.md \
  khandaq-desktop/README.md \
  khandaq-desktop/CHANGELOG.md \
  khandaq-desktop/CONTRIBUTING.md \
  config/khandaq_bootstrap_nodes.json

shasum -a 256 "$OUT_DIR/$ARCHIVE" | tee "$OUT_DIR/${ARCHIVE}.sha256"
ls -la "$OUT_DIR/$ARCHIVE"
echo "Source package OK: $OUT_DIR/$ARCHIVE"
