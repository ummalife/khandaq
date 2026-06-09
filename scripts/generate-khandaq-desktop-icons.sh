#!/usr/bin/env bash
# Regenerate Khandaq desktop icons (macOS .icns, Windows .ico, Linux hicolor PNGs)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DESKTOP="$ROOT/khandaq-desktop"
SVG="$DESKTOP/img/icons/khandaq.svg"
ICONS="$DESKTOP/img/icons"

if [[ ! -f "$SVG" ]]; then
  echo "Missing $SVG" >&2
  exit 1
fi

command -v rsvg-convert >/dev/null || { echo "rsvg-convert required" >&2; exit 1; }

# Opaque edge-to-edge background — transparent corners become a white dock frame on macOS.
ICON_BG="${KHANDAQ_ICON_BG:-#061B16}"
RSVG_OPTS=(-b "$ICON_BG")

render_png() {
  local size="$1" out="$2"
  rsvg-convert "${RSVG_OPTS[@]}" -w "$size" -h "$size" "$SVG" -o "$out"
  if command -v magick >/dev/null; then
    magick "$out" -background "$ICON_BG" -alpha remove -alpha off "$out"
  fi
}

echo "PNG sizes..."
for size in 14 16 22 24 32 36 48 64 72 96 128 192 256 512; do
  mkdir -p "$ICONS/${size}x${size}"
  render_png "$size" "$ICONS/${size}x${size}/khandaq.png"
done

echo "macOS icns..."
ICONSET=$(mktemp -d)/khandaq.iconset
mkdir -p "$ICONSET"
trap 'rm -rf "$(dirname "$ICONSET")"' EXIT
for spec in "16:icon_16x16.png" "32:icon_16x16@2x.png" "32:icon_32x32.png" "64:icon_32x32@2x.png" \
            "128:icon_128x128.png" "256:icon_128x128@2x.png" "256:icon_256x256.png" "512:icon_256x256@2x.png" \
            "512:icon_512x512.png" "1024:icon_512x512@2x.png"; do
  IFS=: read -r sz fn <<< "$spec"
  render_png "$sz" "$ICONSET/$fn"
done
iconutil -c icns "$ICONSET" -o "$ICONS/khandaq.icns"

echo "Windows ico..."
if command -v magick >/dev/null; then
  magick -background "$ICON_BG" "$SVG" -define icon:auto-resize=256,128,96,64,48,32,24,16 \
    "$DESKTOP/windows/khandaq.ico"
elif command -v convert >/dev/null; then
  convert -background "$ICON_BG" "$SVG" -define icon:auto-resize=256,128,96,64,48,32,24,16 \
    "$DESKTOP/windows/khandaq.ico"
else
  echo "ImageMagick not found, skipping .ico" >&2
fi

echo "Done: $ICONS/khandaq.icns, $DESKTOP/windows/khandaq.ico"
