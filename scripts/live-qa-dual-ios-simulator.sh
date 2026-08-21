#!/usr/bin/env bash
# Dual-tox NGC smoke on iOS Simulator (two OCTTox instances in iOSDemoTests).
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OBJCTOX="$ROOT/khandaq-ios/local_pod_repo/objcTox"
OUT="${OUT:-$ROOT/docs/qa-ios-dual-$(date +%Y%m%d-%H%M%S)}"
SIM_NAME="${SIM_NAME:-iPhone 16}"
WAIT_MESH="${WAIT_MESH:-300}"
WAIT_MSG="${WAIT_MSG:-60}"
SKIP_NGC_NETWORK_TESTS="${SKIP_NGC_NETWORK_TESTS:-0}"

mkdir -p "$OUT"
SUMMARY="$OUT/summary.txt"
: > "$SUMMARY"
log() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$SUMMARY"; }

pick_simulator_id() {
  if [[ -n "${SIM_ID:-}" ]]; then
    echo "$SIM_ID"
    return 0
  fi
  xcrun simctl list devices available | awk -v name="$SIM_NAME" -F '[()]' '
    $0 ~ name && $0 !~ /unavailable/ { print $2; exit }
  '
}

SIM_ID="$(pick_simulator_id)"
if [[ -z "$SIM_ID" ]]; then
  log "ERROR: simulator not found (SIM_NAME=$SIM_NAME)"
  exit 1
fi

log "simulator $SIM_NAME id=$SIM_ID"
log "output $OUT"

if ! xcrun simctl boot "$SIM_ID" 2>/dev/null; then
  log "simulator already booted or boot issued"
fi
open -a Simulator --args -CurrentDeviceUDID "$SIM_ID" >/dev/null 2>&1 || true

VPX_FW="$ROOT/khandaq-ios/local_pod_repo/toxcore/ios/vpx.framework"
if [[ -f "$VPX_FW/vpx-simulator" ]]; then
  cp "$VPX_FW/vpx-simulator" "$VPX_FW/vpx"
  log "vpx: using simulator slice"
fi

log "pod install (objcTox + local NGC toxcore)"
(
  cd "$OBJCTOX"
  # KHANDAQ (K-07): BUNDLE_GEMFILE is mandatory HERE in particular — the working directory is
  # objcTox, which has its own Gemfile pinning cocoapods '~> 1.0.1' and no lockfile, so a bare
  # `bundle exec` would resolve a fifteen-minor-versions-older CocoaPods.
  BUNDLE_GEMFILE="$ROOT/khandaq-ios/Gemfile" bundle exec pod install
) >>"$OUT/pod-install.log" 2>&1 || {
  log "ERROR: pod install failed, see $OUT/pod-install.log"
  exit 1
}

TEST_FILTER="${TEST_FILTER:-iOSDemoTests/OCTToxGroupDualIntegrationTests/testDualToxPublicGroupMeshAndMessage}"
log "xcodebuild test filter=$TEST_FILTER"

export SKIP_NGC_NETWORK_TESTS

set +e
xcodebuild test \
  -workspace "$OBJCTOX/objcTox.xcworkspace" \
  -scheme iOSDemo \
  -destination "platform=iOS Simulator,id=$SIM_ID" \
  -only-testing:"$TEST_FILTER" \
  ARCHS=arm64 \
  EXCLUDED_ARCHS=x86_64 \
  ONLY_ACTIVE_ARCH=YES \
  CODE_SIGN_IDENTITY=- \
  CODE_SIGNING_ALLOWED=NO \
  2>&1 | tee "$OUT/xcodebuild.log"
XC_RESULT=${PIPESTATUS[0]}
set -e

if rg -q "Test Suite 'Selected tests' passed" "$OUT/xcodebuild.log"; then
  log "RESULT: PASS (xcodebuild test succeeded)"
elif rg -q "NGC_QA PASS" "$OUT/xcodebuild.log" && rg -q "TEST SUCCEEDED" "$OUT/xcodebuild.log"; then
  log "RESULT: PASS (group message delivered)"
else
  log "RESULT: FAIL (xcodebuild exit=$XC_RESULT)"
  rg "NGC_QA|error:|failed|TEST FAILED" "$OUT/xcodebuild.log" | tail -30 | tee -a "$SUMMARY" || true
  exit "$XC_RESULT"
fi

log "logs: $OUT"
