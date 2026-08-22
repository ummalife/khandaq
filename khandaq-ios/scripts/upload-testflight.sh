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
[[ -d "$WORKSPACE" ]] || { echo "Run 'bundle exec pod install' first"; exit 1; }

mkdir -p "${ROOT}/build"

# KHANDAQ (audit 2026-08-21, K-07): the release path VERIFIES the dependency pins; it never installs
# or resolves anything. This mirrors what release-provenance.yml already does for the Android witness
# checksums, and for the same reason: an artifact about to be signed and uploaded must have been
# built from the pins a human reviewed, not from whatever this Mac happened to resolve.
#
# `bundle check`, deliberately, not `bundle install` — on the release path a missing gem must stop
# the release, not silently be fetched.
export BUNDLE_GEMFILE="${ROOT}/Gemfile"
command -v bundle >/dev/null || { echo "Bundler required: gem install bundler"; exit 1; }
bundle check >/dev/null || {
  echo "ERROR: installed gems do not match Gemfile.lock — run 'bundle install' in khandaq-ios/ and re-check the diff" >&2
  exit 1; }
git -C "$ROOT" diff --quiet -- Gemfile.lock Podfile.lock || {
  echo "ERROR: Gemfile.lock/Podfile.lock are modified before the release even starts. Commit or revert them —" >&2
  echo "       a release must be built from reviewed pins, and an uncommitted lock is by definition unreviewed." >&2
  exit 1; }

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
# KHANDAQ (re-review 2026-08-22, KQ-01): provision the push auth secret at BUILD time, the same way
# the Android build reads KHANDAQ_PUSH_AUTH_SECRET from the environment. It is a build setting, never
# a committed value — Antidote-Info.plist holds $(KHANDAQ_PUSH_AUTH_SECRET) and the project defaults
# it to empty, which the client treats as "signing dormant" rather than "sign with an empty key".
#
# Without this the two platforms diverge silently: an Android release could ship signing wake
# requests while every iOS build stayed dormant no matter what the environment said, and the relay's
# adoption numbers would read as an iOS problem instead of a build-path one.
PUSH_SECRET_ARGS=()
if [[ -n "${KHANDAQ_PUSH_AUTH_SECRET:-}" ]]; then
  PUSH_SECRET_ARGS+=(KHANDAQ_PUSH_AUTH_SECRET="$KHANDAQ_PUSH_AUTH_SECRET")
  echo "==> push auth secret: provisioned (${#KHANDAQ_PUSH_AUTH_SECRET} chars, value never printed)"
else
  echo "==> push auth secret: not set - this build ships with wake signing dormant"
fi

xcodebuild \
  -workspace "$WORKSPACE" \
  -scheme "$SCHEME" \
  -configuration Release \
  -archivePath "$ARCHIVE_PATH" \
  -destination "generic/platform=iOS" \
  DEVELOPMENT_TEAM="$ASC_TEAM_ID" \
  CODE_SIGN_STYLE=Automatic \
  "${AUTH_ARGS[@]}" \
  ${PUSH_SECRET_ARGS[@]+"${PUSH_SECRET_ARGS[@]}"} \
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

# KHANDAQ (audit 2026-08-21, K-07): belt and braces, the same shape as the post-build pin assertion
# in release-provenance.yml. If anything in this run rewrote a lockfile, the build that was just
# uploaded was not built from the reviewed pins — say so loudly rather than letting the next release
# inherit a silently changed lock.
git -C "$ROOT" diff --exit-code -- Gemfile.lock Podfile.lock || {
  echo "ERROR: a dependency lock changed during the release. The uploaded build was NOT built from" >&2
  echo "       the reviewed pins — investigate before promoting it out of TestFlight." >&2
  exit 1; }

echo "Done."
