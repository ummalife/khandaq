#!/usr/bin/env bash
# Apply Khandaq_Messenger_Brand_Asset_Pack to all platform targets.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ZIP="${KHANDAQ_BRAND_ZIP:-$HOME/Downloads/Khandaq_Messenger_Brand_Asset_Pack.zip}"
EXTRACT="$ROOT/brand/_pack"
ANDROID_RES="$ROOT/khandaq-android-trifa/android-refimpl-app/app/src/main/res"
IOS_ICON="$ROOT/khandaq-ios/Antidote/Images.xcassets/AppIcon.appiconset"
DESKTOP_ICONS="$ROOT/khandaq-desktop/img/icons"
WEB="$ROOT/web/messenger"

[[ -f "$ZIP" ]] || { echo "Missing brand zip: $ZIP"; exit 1; }

echo "==> Extract brand pack"
rm -rf "$EXTRACT"
mkdir -p "$EXTRACT"
unzip -qo "$ZIP" -d "$EXTRACT"

B="$EXTRACT/Brand"

echo "==> Android launcher icons"
for density in mdpi hdpi xhdpi xxhdpi xxxhdpi; do
  src_dir="$B/Android/mipmap-$density"
  dst_dir="$ANDROID_RES/mipmap-$density"
  [[ -d "$src_dir" ]] || continue
  mkdir -p "$dst_dir"
  cp -f "$src_dir/ic_launcher.png" "$dst_dir/ic_launcher4.png"
  cp -f "$src_dir/ic_launcher.png" "$dst_dir/ic_launcher4_round.png"
  cp -f "$src_dir/ic_launcher_foreground.png" "$dst_dir/ic_launcher4_foreground.png"
  cp -f "$src_dir/ic_launcher.png" "$dst_dir/ic_launcher.png"
  cp -f "$src_dir/ic_launcher.png" "$dst_dir/ic_launcher_round.png"
done

echo "==> iOS AppIcon"
mkdir -p "$IOS_ICON"
cp -f "$B/iOS/AppIcon.appiconset/"*.png "$IOS_ICON/"
# Keep iphone/ipad Contents.json in repo (brand zip universal idiom fails ASC validation).
if command -v sips >/dev/null; then
  sips -z 20 20 "$IOS_ICON/AppIcon-40.png" --out "$IOS_ICON/AppIcon-20.png" >/dev/null
  sips -z 29 29 "$IOS_ICON/AppIcon-58.png" --out "$IOS_ICON/AppIcon-29.png" >/dev/null
fi

echo "==> Desktop icons (macOS / Linux / Windows) — from brand pack, no regeneration"
cp -f "$B/Logo/khandaq-icon-editable.svg" "$DESKTOP_ICONS/khandaq.svg"
cp -f "$B/Logo/khandaq-icon-editable.svg" "$ROOT/khandaq-desktop/resources/khandaq/icon.svg"
cp -f "$B/MacOS/khandaq.icns" "$DESKTOP_ICONS/khandaq.icns"
cp -f "$B/Windows/khandaq.ico" "$ROOT/khandaq-desktop/windows/khandaq.ico"

# Drop legacy Antidote Icon-App-* entries so Xcode cannot pick stale assets.
rm -f "$IOS_ICON"/Icon-App-*.png "$IOS_ICON"/ItunesArtwork@2x.png

copy_linux_size() {
  local size="$1"
  local src="$B/Linux/khandaq-${size}.png"
  local dst="$DESKTOP_ICONS/${size}x${size}/khandaq.png"
  mkdir -p "$(dirname "$dst")"
  if [[ -f "$src" ]]; then
    cp -f "$src" "$dst"
  elif command -v sips >/dev/null 2>&1 && [[ -f "$B/Linux/khandaq-512.png" ]]; then
    sips -z "$size" "$size" "$B/Linux/khandaq-512.png" --out "$dst" >/dev/null
  fi
}

for size in 14 16 22 24 32 36 48 64 72 96 128 192 256 512; do
  copy_linux_size "$size"
done

echo "==> Website logo + favicons"
mkdir -p "$WEB/assets"
cp -f "$B/Logo/khandaq-icon-editable.svg" "$WEB/assets/logo.svg"
cp -f "$B/Favicons/favicon-16.png" "$WEB/assets/favicon-16.png"
cp -f "$B/Favicons/favicon-32.png" "$WEB/assets/favicon-32.png"
cp -f "$B/Favicons/apple-touch-icon-180.png" "$WEB/assets/apple-touch-icon.png"
cp -f "$B/PWA/pwa-192.png" "$WEB/assets/pwa-192.png"
cp -f "$B/PWA/pwa-512.png" "$WEB/assets/pwa-512.png"

if ! grep -q 'favicon-32.png' "$WEB/index.html" 2>/dev/null; then
  perl -0pi -e 's|<link rel="icon" href="assets/logo.svg" type="image/svg\+xml">|<link rel="icon" href="assets/favicon-32.png" sizes="32x32">\n  <link rel="icon" href="assets/logo.svg" type="image/svg+xml">\n  <link rel="apple-touch-icon" href="assets/apple-touch-icon.png">|' "$WEB/index.html"
fi

echo "==> Store assets (reference)"
mkdir -p "$ROOT/brand/store"
cp -f "$B/Store/GooglePlay/"*.png "$ROOT/brand/store/" 2>/dev/null || true
cp -f "$B/Website/"*.png "$ROOT/brand/website/" 2>/dev/null || mkdir -p "$ROOT/brand/website" && cp -f "$B/Website/"*.png "$ROOT/brand/website/" 2>/dev/null || true

echo "Done. Rebuild clients to pick up binary icon changes."
