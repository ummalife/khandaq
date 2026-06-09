#!/usr/bin/env bash
# Fetch upstream aTox release APK (source build needs Linux + sbt + tox4j on Mac)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIST="$ROOT/dist/android"
VERSION="${ATOX_VERSION:-v0.8.0}"
URL="https://github.com/evilcorpltd/aTox/releases/download/${VERSION}/atox.apk"
OUT="$DIST/atox-${VERSION}.apk"

mkdir -p "$DIST"
echo "==> Downloading aTox $VERSION..."
curl -fsSL -o "$OUT" "$URL"
shasum -a 256 "$OUT" | tee -a "$DIST/sha256sums.txt"
ls -la "$OUT"
echo "aTox fetch OK"
