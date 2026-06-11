#!/usr/bin/env bash
# Build TRIfA (ToxAndroidRefImpl) Android APK — Khandaq release
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="${KHANDAQ_TRIFA_SRC:-$ROOT/khandaq-android-trifa}"
APP="$SRC/android-refimpl-app"
DIST="$ROOT/dist/android"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
SIGNING_ENV="$ROOT/secrets/android-signing.env"

[[ -d "$APP" ]] || { echo "Missing $APP — clone ToxAndroidRefImpl first"; exit 1; }
[[ -d "$ANDROID_HOME" ]] || { echo "ANDROID_HOME not found: $ANDROID_HOME"; exit 1; }

export ANDROID_HOME
mkdir -p "$DIST"

if [[ -f "$SIGNING_ENV" ]]; then
  # shellcheck disable=SC1090
  source "$SIGNING_ENV"
fi

echo "==> Gradle assembleRelease (TRIfA)..."
cd "$APP"
./gradlew assembleRelease --no-daemon

UNSIGNED="$APP/app/build/outputs/apk/release/app-release-unsigned.apk"
[[ -f "$UNSIGNED" ]] || { echo "APK not found: $UNSIGNED"; exit 1; }

cp -f "$UNSIGNED" "$DIST/trifa-release-unsigned.apk"

BT="$(ls -1d "$ANDROID_HOME/build-tools"/* 2>/dev/null | sort -V | tail -1)"
RELEASE_KS="${KHANDAQ_ANDROID_KEYSTORE:-}"
RELEASE_ALIAS="${KHANDAQ_ANDROID_KEY_ALIAS:-khandaq}"
RELEASE_STORE_PASS="${KHANDAQ_ANDROID_STORE_PASS:-}"
RELEASE_KEY_PASS="${KHANDAQ_ANDROID_KEY_PASS:-$RELEASE_STORE_PASS}"

if [[ -f "${RELEASE_KS:-}" && -n "$RELEASE_STORE_PASS" && -n "$BT" && -x "$BT/apksigner" ]]; then
  OUT="$DIST/khandaq-release.apk"
  "$BT/apksigner" sign \
    --ks "$RELEASE_KS" \
    --ks-key-alias "$RELEASE_ALIAS" \
    --ks-pass "pass:$RELEASE_STORE_PASS" \
    --key-pass "pass:$RELEASE_KEY_PASS" \
    --out "$OUT" "$UNSIGNED"
  cp -f "$OUT" "$DIST/trifa-release.apk"
  echo "Signed with PRODUCTION keystore -> $OUT"
  echo "Share with team as: $OUT (or khandaq-release.apk from dist/android/)"
  SIGN_MODE="production"
elif [[ -f "${ANDROID_DEBUG_KEYSTORE:-$HOME/.android/debug.keystore}" && -n "$BT" && -x "$BT/apksigner" ]]; then
  KS="${ANDROID_DEBUG_KEYSTORE:-$HOME/.android/debug.keystore}"
  OUT="$DIST/trifa-release.apk"
  "$BT/apksigner" sign \
    --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --out "$OUT" "$UNSIGNED"
  echo "WARNING: Signed with DEBUG keystore -> $OUT"
  SIGN_MODE="debug"
else
  cp -f "$UNSIGNED" "$DIST/trifa-release.apk"
  echo "No keystore — unsigned APK at $DIST/trifa-release.apk"
  SIGN_MODE="unsigned"
fi

( cd "$DIST" && shasum -a 256 khandaq-release.apk trifa-release*.apk 2>/dev/null | sort -u | tee sha256sums.txt )
echo "TRIfA build OK (signing: $SIGN_MODE)"
