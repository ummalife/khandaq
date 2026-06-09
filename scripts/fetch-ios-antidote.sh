#!/usr/bin/env bash
# Fetch upstream Antidote unsigned IPA (source build may fail on Xcode 15+)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIST="$ROOT/dist/ios"
VERSION="${ANTIDOTE_VERSION:-v1.4.28}"
URL="https://github.com/Zoxcore/Antidote/releases/download/${VERSION}/Antidote_unsigned.ipa"
OUT="$DIST/antidote-${VERSION}-unsigned.ipa"

mkdir -p "$DIST"
echo "==> Downloading Antidote $VERSION..."
curl -fsSL -o "$OUT" "$URL"
shasum -a 256 "$OUT" | tee -a "$DIST/sha256sums.txt"
ls -la "$OUT"
echo "Antidote fetch OK"
