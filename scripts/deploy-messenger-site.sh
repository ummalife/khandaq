#!/usr/bin/env bash
# Deprecated wrapper — main site is https://khandaq.org/ (see deploy-site.sh)
exec "$(cd "$(dirname "$0")" && pwd)/deploy-site.sh" "$@"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WEB="$ROOT/web/messenger"
DL="$WEB/downloads"
REMOTE="${KHANDAQ_MESSENGER_REMOTE:-Khandaq}"
REMOTE_DIR="${KHANDAQ_MESSENGER_DIR:-/var/www/element/messenger}"
STAMP="$(date -u +%Y%m%d)"

mkdir -p "$DL"

echo "==> Package Android"
APK_SRC="${KHANDAQ_ANDROID_APK:-$ROOT/dist/android/khandaq-release.apk}"
[[ -f "$APK_SRC" ]] || APK_SRC="$ROOT/dist/android/khandaq-debug.apk"
[[ -f "$APK_SRC" ]] || APK_SRC="$ROOT/dist/android/trifa-release.apk"
[[ -f "$APK_SRC" ]] || { echo "Missing Android APK"; exit 1; }
cp -f "$APK_SRC" "$DL/khandaq-android.apk"

echo "==> Package macOS"
[[ -d "$ROOT/dist/macos/khandaq.app" ]] || { echo "Missing dist/macos/khandaq.app"; exit 1; }
rm -f "$DL/khandaq-macos.zip"
ditto -c -k --keepParent "$ROOT/dist/macos/khandaq.app" "$DL/khandaq-macos.zip"

echo "==> Package Linux"
[[ -f "$ROOT/dist/linux/khandaq" ]] || { echo "Missing dist/linux/khandaq"; exit 1; }
rm -f "$DL/khandaq-linux-x86_64.tar.gz"
[[ -f "$ROOT/dist/linux/khandaq.bin" ]] || { echo "Missing dist/linux/khandaq.bin (run bundle-linux-portable)"; exit 1; }
[[ -d "$ROOT/dist/linux/lib" ]] || { echo "Missing dist/linux/lib"; exit 1; }
[[ -d "$ROOT/dist/linux/plugins" ]] || { echo "Missing dist/linux/plugins"; exit 1; }
tar -C "$ROOT/dist/linux" -czf "$DL/khandaq-linux-x86_64.tar.gz" \
  khandaq khandaq.bin lib plugins khandaq.desktop org.khandaq.messenger.appdata.xml INSTALL.txt

echo "==> Package Windows"
WIN_EXE="$ROOT/dist/windows/x86_64/khandaq-windows-installer.exe"
[[ -f "$WIN_EXE" ]] || WIN_EXE="$ROOT/dist/windows/x86_64/Khandaq-installer.exe"
[[ -f "$WIN_EXE" ]] || { echo "Missing Windows installer"; exit 1; }
cp -f "$WIN_EXE" "$DL/khandaq-windows-installer.exe"

echo "==> Checksums"
( cd "$DL" && shasum -a 256 khandaq-* > SHA256SUMS.txt )

echo "==> Upload to $REMOTE:$REMOTE_DIR"
ssh "$REMOTE" "mkdir -p '$REMOTE_DIR/downloads'"
scp -p "$WEB/index.html" "$WEB/style.css" "$REMOTE:$REMOTE_DIR/"
scp -pr "$WEB/assets" "$REMOTE:$REMOTE_DIR/"
scp -p "$DL"/* "$REMOTE:$REMOTE_DIR/downloads/"

echo "==> Fix permissions"
ssh "$REMOTE" "chown -R www-data:www-data '$REMOTE_DIR' && chmod -R a+r '$REMOTE_DIR' && find '$REMOTE_DIR' -type d -exec chmod 755 {} +"

echo "==> Done ($STAMP)"
echo "    https://khandaq.org/messenger/"
ls -lh "$DL"
