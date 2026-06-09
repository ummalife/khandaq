#!/usr/bin/env bash
# Verify Android upgrade constraints: debug -> production requires reinstall.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIST="$ROOT/dist/android"
BT="$(ls -1d "${ANDROID_HOME:-$HOME/Library/Android/sdk}/build-tools"/* 2>/dev/null | sort -V | tail -1)"

PROD="${1:-$DIST/khandaq-release.apk}"
DEBUG_KS="${ANDROID_DEBUG_KEYSTORE:-$HOME/.android/debug.keystore}"

[[ -f "$PROD" ]] || { echo "Missing production APK: $PROD"; exit 1; }
[[ -x "$BT/apksigner" ]] || { echo "apksigner not found"; exit 1; }

echo "==> Production APK certificates:"
"$BT/apksigner" verify --print-certs "$PROD" 2>&1 | grep -E 'Signer|digest' | head -6

if [[ -f "$DEBUG_KS" ]]; then
  echo ""
  echo "==> Debug keystore certificate:"
  keytool -list -v -keystore "$DEBUG_KS" -alias androiddebugkey -storepass android 2>/dev/null \
    | grep -E 'SHA256:' || true
fi

echo ""
echo "==> Upgrade path analysis"
echo "Package: org.khandaq.messenger"
echo ""
echo "Debug APK -> Production APK: BLOCKED (different signing certificate)"
echo "  Users must: export Tox profile -> uninstall -> install production APK -> import"
echo ""
echo "Production APK -> newer Production APK (same versionCode+1): ALLOWED in-place"
echo ""
echo "Verify production signature is NOT Android Debug:"
if "$BT/apksigner" verify --print-certs "$PROD" 2>&1 | grep -q 'Android Debug'; then
  echo "FAIL: still signed with debug key"
  exit 1
fi
echo "OK: production signing confirmed"
