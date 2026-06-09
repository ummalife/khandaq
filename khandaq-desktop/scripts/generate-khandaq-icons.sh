#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ICON_SVG="$ROOT/resources/khandaq/icon.svg"
RSVG="$(command -v rsvg-convert || echo /opt/homebrew/bin/rsvg-convert)"

if [ ! -f "$ICON_SVG" ]; then
  echo "Missing $ICON_SVG"
  exit 1
fi

echo "==> Sync SVG to img/icons/"
cp "$ICON_SVG" "$ROOT/img/icons/khandaq.svg"
cp "$ROOT/resources/khandaq/logo.svg" "$ROOT/img/login_logo.svg"

ICON_BG="${KHANDAQ_ICON_BG:-#061B16}"
render_png() {
  local size="$1" out="$2"
  "$RSVG" -b "$ICON_BG" -w "$size" -h "$size" "$ICON_SVG" -o "$out"
  if command -v magick >/dev/null; then
    magick "$out" -background "$ICON_BG" -alpha remove -alpha off "$out"
  fi
}

echo "==> Generate PNG sizes"
SIZES=(14 16 22 24 32 36 48 64 72 96 128 192 256 512)
for size in "${SIZES[@]}"; do
  dir="$ROOT/img/icons/${size}x${size}"
  mkdir -p "$dir"
  render_png "$size" "$dir/khandaq.png"
done

render_png 512 "$ROOT/resources/khandaq/icon.png"
"$RSVG" -w 800 -h 400 "$ROOT/resources/khandaq/logo.svg" -o "$ROOT/resources/khandaq/splash.png"

if [[ "$(uname -s)" == "Darwin" ]] && command -v iconutil >/dev/null; then
  echo "==> Generate macOS icns"
  ICONSET="$ROOT/build/khandaq.iconset"
  rm -rf "$ICONSET"
  mkdir -p "$ICONSET"
  for size in 16 32 128 256 512; do
    render_png "$size" "$ICONSET/icon_${size}x${size}.png"
    dbl=$((size * 2))
    render_png "$dbl" "$ICONSET/icon_${size}x${size}@2x.png"
  done
  iconutil -c icns "$ICONSET" -o "$ROOT/img/icons/khandaq.icns"
else
  echo "==> Skip macOS icns (not on macOS)"
fi

echo "==> Generate Windows ico"
if command -v magick >/dev/null; then
  magick -background "$ICON_BG" "$ROOT/img/icons/256x256/khandaq.png" \
    -define icon:auto-resize=256,128,64,48,32,16 \
    "$ROOT/windows/khandaq.ico"
else
  echo "Skip .ico — install imagemagick"
fi

echo "Done."
