#!/usr/bin/env bash
# Live QA for iOS fixes: theme toggle + group live audio/video (no crash, sane errors)
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PKG="com.khandaq.messenger"
RCVR="$PKG/com.zoffcc.applications.trifa.QaGroupBroadcastReceiver"
APK="${APK:-$ROOT/khandaq-android-trifa/android-refimpl-app/app/build/outputs/apk/debug/app-debug.apk}"
IOS_APP="${IOS_APP:-/tmp/khandaq-ios-dd/Build/Products/Debug-iphonesimulator/Khandaq.app}"
EMU="${EMU:-emulator-5554}"
ANDROID_DEV="${ANDROID_DEV:-$EMU}"
# Prefer physical phone when emulator tox is flaky (override: ANDROID_DEV=emulator-5554)
if [[ "${ANDROID_DEV}" == "emulator-5554" ]] && adb devices | rg -q "ce75a71db51e[[:space:]]*device"; then
  ANDROID_DEV="ce75a71db51e"
fi
IOS_SIM="${IOS_SIM:-B2161BF1-80D0-45D4-8A07-C2FE4F79CB5C}"
TS="$(date +%s)"
GROUP_NAME="QA-FIXES-$TS"
OUT="${OUT:-$ROOT/docs/qa-ios-fixes-$TS}"
mkdir -p "$OUT"

log() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$OUT/summary.txt" >&2; }

adb_dev() { adb -s "$ANDROID_DEV" "$@"; }
qa_broadcast() { adb_dev shell am broadcast -n "$RCVR" "$@" >>"$OUT/broadcast-$ANDROID_DEV.log" 2>&1; }
ios_openurl() { log "[ios] $1"; xcrun simctl openurl "$IOS_SIM" "$1" >>"$OUT/ios-url.log" 2>&1 || true; }
ios_collect_logs() {
  xcrun simctl spawn "$IOS_SIM" log show --last 15m --style compact 2>/dev/null \
    | rg "qa_ios:" > "$OUT/ios-qa.log" 2>&1 || true
}
ios_wait_log() {
  local pat="$1" max="${2:-60}" i=0
  while (( i < max )); do
    ios_collect_logs
    rg -q "$pat" "$OUT/ios-qa.log" 2>/dev/null && return 0
    sleep 2; ((i+=2))
  done
  return 1
}
ios_last_theme_dark() {
  rg "qa_ios:done action=(log_theme|toggle_theme).*dark=([01])" "$OUT/ios-qa.log" 2>/dev/null \
    | tail -1 | rg -o "dark=[01]" | rg -o "[01]" || echo ""
}
launch_emu() {
  adb_dev shell am force-stop "$PKG" 2>/dev/null || true
  adb_dev logcat -c 2>/dev/null || true
  adb_dev shell am start -n "$PKG/com.zoffcc.applications.trifa.StartMainActivityWrapper" >/dev/null 2>&1 \
    || adb_dev shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  sleep 18
  qa_broadcast -a org.khandaq.qa.LOG_TOX_ID
}
wait_emu_tox() {
  local i=0
  while (( i < ${1:-180} )); do
    local tid
    tid=$(adb_dev logcat -d 2>/dev/null | rg "qa_toxid:device=([0-9A-F]{64,76})" -o --replace '$1' | tail -1 || true)
    if [[ -n "$tid" && ${#tid} -ge 64 ]]; then
      echo "$tid"
      return 0
    fi
    if adb_dev logcat -d 2>/dev/null | rg -q 'is_tox_started=true|tox_thread_start_fg'; then
      qa_broadcast -a org.khandaq.qa.LOG_TOX_ID
      sleep 3
      tid=$(adb_dev logcat -d 2>/dev/null | rg "qa_toxid:device=([0-9A-F]{64,76})" -o --replace '$1' | tail -1 || true)
      [[ -n "$tid" && ${#tid} -ge 64 ]] && { echo "$tid"; return 0; }
    fi
    if (( i > 0 && i % 30 == 0 )); then
      adb_dev shell am start -n "$PKG/com.zoffcc.applications.trifa.StartMainActivityWrapper" >/dev/null 2>&1 || true
      qa_broadcast -a org.khandaq.qa.LOG_TOX_ID
    fi
    sleep 3; ((i+=3))
  done
  return 1
}
ios_app_running() {
  xcrun simctl spawn "$IOS_SIM" launchctl list 2>/dev/null | rg -q "org.khandaq.messenger" \
    || pgrep -f "Khandaq.app/Khandaq" >/dev/null 2>&1
}
ios_grant_media_privacy() {
  xcrun simctl privacy "$IOS_SIM" grant microphone org.khandaq.messenger 2>/dev/null || true
  xcrun simctl privacy "$IOS_SIM" grant camera org.khandaq.messenger 2>/dev/null || true
}
ios_ensure_foreground() {
  ios_grant_media_privacy
  if ! ios_app_running; then
    xcrun simctl launch "$IOS_SIM" org.khandaq.messenger >>"$OUT/ios-launch.log" 2>&1 || true
    sleep 8
  fi
}
ios_recent_crash() {
  xcrun simctl spawn "$IOS_SIM" log show --last 3m --style compact 2>/dev/null \
    | rg -qi "Khandaq.*(SIGABRT|SIGSEGV|Fatal|terminated due to|crash)" && return 0
  return 1
}

mesh_ok() {
  local gid="$1" min="${2:-2}"
  local gid6="${gid: -6}"
  local line peers
  line=$(adb_dev logcat -d 2>/dev/null | rg "group_peer_mesh:summary.*id=${gid6}" | tail -1 || true)
  [[ -z "$line" ]] && return 1
  if [[ "$line" =~ tox_peers=([0-9]+) ]]; then
    peers="${BASH_REMATCH[1]}"
    (( peers >= min )) && [[ "$line" == *"group_conn=1"* ]] && return 0
  fi
  return 1
}

wait_mesh() {
  local gid="$1" max="${2:-180}"
  local gid_upper
  gid_upper=$(echo "$gid" | tr '[:lower:]' '[:upper:]')
  local i=0
  while (( i < max )); do
    mesh_ok "$gid" 2 && return 0
    ios_collect_logs
    if rg -q "qa_ios:done action=join.*group_id=${gid_upper}.*peers=[2-9]" "$OUT/ios-qa.log" 2>/dev/null; then
      return 0
    fi
    qa_broadcast -a org.khandaq.qa.REVIVE_GROUPS
    ios_openurl "khandaq://qa/revive_groups"
    sleep 5; ((i+=5))
  done
  return 1
}

PASS=0
FAIL=0
pass() { log "PASS: $*"; PASS=$((PASS+1)); }
fail() { log "FAIL: $*"; FAIL=$((FAIL+1)); }

log "=== QA iOS fixes (theme + live media) android=$ANDROID_DEV OUT=$OUT"

if ! adb_dev get-state 2>/dev/null | rg -q device; then
  log "FAIL: $ANDROID_DEV offline"; exit 2
fi
if ! xcrun simctl list devices booted 2>/dev/null | rg -q "$IOS_SIM"; then
  xcrun simctl boot "$IOS_SIM" 2>/dev/null || true
  sleep 5
fi

log "[build] iOS"
cd "$ROOT/khandaq-ios" && xcodebuild -scheme Antidote \
  -destination "platform=iOS Simulator,id=$IOS_SIM" \
  -derivedDataPath /tmp/khandaq-ios-dd CODE_SIGNING_ALLOWED=NO build \
  >>"$OUT/ios-build.log" 2>&1 || { log "FAIL ios build"; exit 3; }

[[ -f "$APK" ]] || (cd "$ROOT/khandaq-android-trifa/android-refimpl-app" && ./gradlew :app:assembleDebug --no-daemon >>"$OUT/android-build.log" 2>&1)

adb_dev install -r "$APK" >>"$OUT/install-android.log" 2>&1 || true
xcrun simctl install "$IOS_SIM" "$IOS_APP" >>"$OUT/ios-install.log" 2>&1

xcrun simctl terminate "$IOS_SIM" org.khandaq.messenger 2>/dev/null || true
adb_dev shell am force-stop "$PKG" 2>/dev/null || true
sleep 1
ios_grant_media_privacy
xcrun simctl launch "$IOS_SIM" org.khandaq.messenger >>"$OUT/ios-launch.log" 2>&1
launch_emu
EMU_TOX=$(wait_emu_tox 180) || true
[[ ${#EMU_TOX} -ge 64 ]] || { log "FAIL: emu tox not ready"; exit 1; }
log "emu tox=${EMU_TOX:0:20}..."
sleep 5

ios_openurl "khandaq://qa/log_tox_id"
sleep 5
ios_collect_logs
ios_wait_log "qa_ios:done action=log_tox_id" 60 || fail "iOS app not responding to QA"

# --- Theme ---
log "=== Phase 1: Theme toggle ==="
ios_openurl "khandaq://qa/log_theme"
sleep 2
ios_collect_logs
BEFORE=$(ios_last_theme_dark)
[[ -n "$BEFORE" ]] || { fail "theme log missing"; BEFORE=0; }
log "theme before toggle: dark=$BEFORE"

ios_openurl "khandaq://qa/toggle_theme"
sleep 2
ios_wait_log "qa_ios:done action=toggle_theme" 30 || fail "toggle_theme log missing"
ios_collect_logs
AFTER=$(ios_last_theme_dark)
if [[ "$BEFORE" == "0" && "$AFTER" == "1" ]] || [[ "$BEFORE" == "1" && "$AFTER" == "0" ]]; then
  pass "theme toggle flipped dark $BEFORE -> $AFTER"
else
  fail "theme toggle did not flip (before=$BEFORE after=$AFTER)"
fi
if ios_app_running && ! ios_recent_crash; then
  pass "theme toggle — app alive (no crash)"
else
  fail "theme toggle — crash detected"
fi

ios_openurl "khandaq://qa/toggle_theme"
sleep 2
ios_wait_log "qa_ios:done action=toggle_theme" 30 || fail "toggle_theme back log missing"
ios_collect_logs
RESTORED=$(ios_last_theme_dark)
if [[ "$RESTORED" == "$BEFORE" ]]; then
  pass "theme toggle restored dark=$RESTORED"
else
  fail "theme restore expected dark=$BEFORE got $RESTORED"
fi
if ios_app_running && ! ios_recent_crash; then
  pass "theme restore — app alive"
else
  fail "theme restore — crash detected"
fi

# --- Group mesh for live media ---
log "=== Phase 2: Group mesh + live media probes ==="
qa_broadcast -a org.khandaq.qa.CREATE_PUBLIC_GROUP --es name "$GROUP_NAME"
sleep 18
GID=$(adb_dev logcat -d 2>/dev/null | rg -o 'create_new_group:ok num=[0-9]+ id=[0-9a-fA-F]{64}' | tail -1 | rg -o '[0-9a-fA-F]{64}' || true)
if [[ ${#GID} -ne 64 ]]; then
  adb_dev shell am start -n "$PKG/com.zoffcc.applications.trifa.StartMainActivityWrapper" >/dev/null 2>&1 || true
  sleep 5
  qa_broadcast -a org.khandaq.qa.CREATE_PUBLIC_GROUP --es name "$GROUP_NAME"
  sleep 18
  GID=$(adb_dev logcat -d 2>/dev/null | rg -o 'create_new_group:ok num=[0-9]+ id=[0-9a-fA-F]{64}' | tail -1 | rg -o '[0-9a-fA-F]{64}' || true)
fi
[[ ${#GID} -eq 64 ]] || { fail "no group id"; log "VERDICT: FAIL ($FAIL)"; exit 1; }
log "group_id=$GID"

ios_openurl "khandaq://qa/join?group_id=$GID&min_peers=2"
sleep 5
ios_openurl "khandaq://qa/join?group_id=$GID&min_peers=2"
if wait_mesh "$GID" 180; then
  pass "group mesh >=2 peers"
else
  fail "group mesh timeout"
fi

ios_grant_media_privacy

ios_openurl "khandaq://qa/probe_group_live_audio?group_id=$GID"
if ios_wait_log "qa_ios:done action=probe_group_live_audio.*crashed=0" 45; then
  if ios_app_running && ! ios_recent_crash; then
    pass "live audio probe — no crash"
    rg "qa_ios:done action=probe_group_live_audio" "$OUT/ios-qa.log" | tail -1 | tee -a "$OUT/summary.txt"
    line=$(rg "qa_ios:done action=probe_group_live_audio" "$OUT/ios-qa.log" | tail -1 || true)
    if echo "$line" | rg -q "started=1"; then
      pass "live audio started"
    elif echo "$line" | rg -q "started=0.*code="; then
      pass "live audio graceful error (not crash)"
    elif echo "$line" | rg -q "skipped=not_connected"; then
      fail "live audio skipped — group not connected after retry"
    fi
  else
    fail "live audio probe — app crashed"
  fi
else
  ios_collect_logs
  if ios_app_running && ! ios_recent_crash; then
    fail "live audio probe — missing done log"
  else
    fail "live audio probe — app crashed"
  fi
fi

ios_openurl "khandaq://qa/probe_group_live_video?group_id=$GID"
ios_wait_log "qa_ios:done action=probe_group_live_video.*crashed=0" 45 || true
ios_collect_logs
if ios_app_running && ! ios_recent_crash; then
  if rg -q "qa_ios:done action=probe_group_live_video.*crashed=0" "$OUT/ios-qa.log" 2>/dev/null; then
    pass "live video probe — no crash"
    line=$(rg "qa_ios:done action=probe_group_live_video" "$OUT/ios-qa.log" | tail -1 || true)
    echo "$line" | tee -a "$OUT/summary.txt"
    if echo "$line" | rg -q "domain=OCTNgcGroupLiveVideo.*code=1"; then
      pass "live video sim — expected simulator error (not Internal error)"
    elif echo "$line" | rg -q "started=1"; then
      pass "live video started on sim"
    elif echo "$line" | rg -q "started=0.*code="; then
      pass "live video graceful error (not crash)"
    else
      fail "live video unexpected result: $line"
    fi
  else
    fail "live video probe — missing done log"
  fi
else
  fail "live video probe — app crashed"
fi

log "=== VERDICT: PASS=$PASS FAIL=$FAIL ==="
[[ "$FAIL" -eq 0 ]]
