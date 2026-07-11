#!/usr/bin/env bash
# Build the APK we hand out to testers in Telegram.
#
# Always signed with the SAME production keystore (secrets/khandaq-release.keystore),
# so it installs over any previous tester APK without "conflicts with another
# package". It can NOT install over the Google Play build (Play re-signs with
# Google's key) — Play testers must update via Play (internal/alpha track).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
APP_DIR="$REPO_ROOT/khandaq-android-trifa/android-refimpl-app"
ENV_FILE="$REPO_ROOT/secrets/android-signing.env"
OUT_DIR="$REPO_ROOT/dist/android"

# shellcheck disable=SC1090
source "$ENV_FILE"

BT="$(ls -d "$HOME"/Library/Android/sdk/build-tools/* | sort -V | tail -1)"

cd "$APP_DIR"
./gradlew :app:assembleRelease -x lint

REL_DIR="$APP_DIR/app/build/outputs/apk/release"
UNSIGNED_OR_UPLOAD="$REL_DIR/app-release.apk"

VN="$("$BT/aapt2" dump badging "$UNSIGNED_OR_UPLOAD" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p" | head -1)"
VC="$("$BT/aapt2" dump badging "$UNSIGNED_OR_UPLOAD" | sed -n "s/.*versionCode='\([^']*\)'.*/\1/p" | head -1)"

mkdir -p "$OUT_DIR"
OUT_APK="$OUT_DIR/khandaq-$VN-$VC.apk"

"$BT/apksigner" sign \
    --ks "$KHANDAQ_ANDROID_KEYSTORE" \
    --ks-pass "pass:$KHANDAQ_ANDROID_STORE_PASS" \
    --ks-key-alias "$KHANDAQ_ANDROID_KEY_ALIAS" \
    --key-pass "pass:$KHANDAQ_ANDROID_KEY_PASS" \
    --out "$OUT_APK" \
    "$UNSIGNED_OR_UPLOAD"

"$BT/apksigner" verify --print-certs "$OUT_APK" | head -2
echo
echo "✅ tester APK: $OUT_APK"
echo "   (installs over previous tester APKs; Play installs update via Play only)"
