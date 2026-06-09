#!/usr/bin/env bash
set -euo pipefail

# Upload Khandaq iOS to TestFlight (App Store Connect API key).
# Targeted testers: TESTERS=email@example.com ./scripts/upload-testflight.sh

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CONFIG="${HOME}/.appstoreconnect/config"
SCHEME="Antidote"
WORKSPACE="${ROOT}/Antidote.xcworkspace"
ARCHIVE_PATH="${ROOT}/build/Khandaq.xcarchive"
EXPORT_PATH="${ROOT}/build/export"
IPA_PATH="${EXPORT_PATH}/Khandaq.ipa"

if [[ ! -f "$CONFIG" ]]; then
  echo "Missing $CONFIG"
  exit 1
fi
# shellcheck disable=SC1090
source "$CONFIG"

: "${ASC_KEY_ID:?}"
: "${ASC_ISSUER_ID:?}"
: "${ASC_TEAM_ID:?}"
: "${ASC_KEY_PATH:?}"

command -v xcodebuild >/dev/null || { echo "Xcode required"; exit 1; }
[[ -d "$WORKSPACE" ]] || { echo "Run pod install first"; exit 1; }

mkdir -p "${ROOT}/build"

EXISTING_IPA="$(find "$EXPORT_PATH" -maxdepth 1 -name '*.ipa' 2>/dev/null | head -1 || true)"
if [[ "${SKIP_ARCHIVE:-0}" == "1" && -n "$EXISTING_IPA" ]]; then
  IPA_PATH="$EXISTING_IPA"
  echo "==> SKIP_ARCHIVE=1 — using existing $IPA_PATH"
else
echo "==> Bump build number"
/usr/libexec/PlistBuddy -c "Print CFBundleVersion" "${ROOT}/Antidote/Antidote-Info.plist"
agvtool next-version -all >/dev/null || true

AUTH_ARGS=(
  -authenticationKeyPath "$ASC_KEY_PATH"
  -authenticationKeyID "$ASC_KEY_ID"
  -authenticationKeyIssuerID "$ASC_ISSUER_ID"
  -allowProvisioningUpdates
)

echo "==> Archive Release (generic iOS device)"
xcodebuild \
  -workspace "$WORKSPACE" \
  -scheme "$SCHEME" \
  -configuration Release \
  -archivePath "$ARCHIVE_PATH" \
  -destination "generic/platform=iOS" \
  DEVELOPMENT_TEAM="$ASC_TEAM_ID" \
  CODE_SIGN_STYLE=Automatic \
  "${AUTH_ARGS[@]}" \
  archive

echo "==> Export IPA"
xcodebuild \
  -exportArchive \
  -archivePath "$ARCHIVE_PATH" \
  -exportPath "$EXPORT_PATH" \
  -exportOptionsPlist "${ROOT}/scripts/ExportOptions.plist" \
  "${AUTH_ARGS[@]}"

# Xcode names IPA after scheme/product
if [[ ! -f "$IPA_PATH" ]]; then
  IPA_PATH="$(find "$EXPORT_PATH" -maxdepth 1 -name '*.ipa' | head -1)"
fi
[[ -f "$IPA_PATH" ]] || { echo "IPA not found in $EXPORT_PATH"; exit 1; }
fi

if [[ ! -f "$IPA_PATH" ]]; then
  IPA_PATH="$(find "$EXPORT_PATH" -maxdepth 1 -name '*.ipa' | head -1)"
fi
[[ -f "$IPA_PATH" ]] || { echo "IPA not found"; exit 1; }

echo "==> Upload to TestFlight ($IPA_PATH)"
UPLOAD_LOG="${ROOT}/build/testflight-upload-latest.log"
xcrun altool \
  --upload-app \
  --type ios \
  --file "$IPA_PATH" \
  --apiKey "$ASC_KEY_ID" \
  --apiIssuer "$ASC_ISSUER_ID" \
  2>&1 | tee "$UPLOAD_LOG"

DELIVERY_UUID="$(sed -n 's/^Delivery UUID: //p' "$UPLOAD_LOG" | tail -1)"
if [[ -z "$DELIVERY_UUID" ]]; then
  echo "Upload finished but Delivery UUID missing — check App Store Connect manually"
  exit 0
fi

echo "==> Distribute to targeted testers"
DIST_ARGS=()
if [[ -n "${TESTERS:-}" ]]; then
  DIST_ARGS=(--testers "$TESTERS")
  echo "Target testers: $TESTERS"
else
  echo "TESTERS not set — assign build to all beta groups"
fi
python3 "${ROOT}/scripts/distribute-testflight.py" "$DELIVERY_UUID" "${DIST_ARGS[@]}"

echo "Done."
