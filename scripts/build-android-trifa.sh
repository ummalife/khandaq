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
else
  # KHANDAQ (security KHQ-01/VULN-01): NEVER fall back to the Android debug keystore (public
  # password "android") or ship an unsigned APK as a "release". A debug-signed APK published to the
  # site would let anyone holding ~/.android/debug.keystore sign fake "updates" (TOFU sideload
  # trust). Fail hard instead so a misconfigured build can't silently produce a distributable APK.
  echo "ERROR: production keystore not found or signing vars unset." >&2
  echo "       Set KHANDAQ_ANDROID_KEYSTORE + KHANDAQ_ANDROID_STORE_PASS (see secrets/android-signing.env)." >&2
  exit 1
fi

# KHANDAQ (security KHQ-13): run shasum from inside $DIST on BASENAMES only, so the published
# SHA256SUMS contains "<hash>  khandaq-release.apk" (matching the downloaded filename) and never an
# absolute build-machine path (which leaked the username/dir layout and broke `sha256sum -c`).
( cd "$DIST" && shasum -a 256 khandaq-release.apk trifa-release.apk 2>/dev/null | sort -u | tee sha256sums.txt )
echo "TRIfA build OK (signing: $SIGN_MODE)"
