#!/usr/bin/env bash
# Full QA: Android emulator + iOS simulator (no physical phone)
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PKG="com.khandaq.messenger"
RCVR="$PKG/com.zoffcc.applications.trifa.QaGroupBroadcastReceiver"
APK="${APK:-$ROOT/khandaq-android-trifa/android-refimpl-app/app/build/outputs/apk/debug/app-debug.apk}"
IOS_APP="${IOS_APP:-$ROOT/khandaq-ios/build/simulator/Build/Products/Debug-iphonesimulator/Khandaq.app}"
EMU="${EMU:-emulator-5554}"
IOS_SIM="${IOS_SIM:-B2161BF1-80D0-45D4-8A07-C2FE4F79CB5C}"
TS="$(date +%s)"
GROUP_NAME="QA-FULL-$TS"
OUT="${OUT:-$ROOT/docs/qa-full-$TS}"
mkdir -p "$OUT"

log() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$OUT/summary.txt"; }
adb_dev() { adb -s "$EMU" "$@"; }
qa() { adb_dev shell am broadcast -n "$RCVR" "$@" >>"$OUT/broadcast.log" 2>&1; }
ios_url() { log "[ios] $1"; xcrun simctl openurl "$IOS_SIM" "$1" >>"$OUT/ios-url.log" 2>&1; }
ios_logs() {
  xcrun simctl spawn "$IOS_SIM" log show --last 10m --style compact 2>/dev/null \
    | rg "qa_ios:" > "$OUT/ios-qa.log" 2>&1 || true
}
ios_wait() {
  local pat="$1" max="${2:-90}" i=0
  while (( i < max )); do
    ios_logs
    rg -q "$pat" "$OUT/ios-qa.log" 2>/dev/null && return 0
    sleep 2; ((i+=2))
  done
  return 1
}
pass() { log "PASS: $*"; echo "PASS: $*" >> "$OUT/results.txt"; }
fail() { log "FAIL: $*"; echo "FAIL: $*" >> "$OUT/results.txt"; }

onboard_android() {
  python3 - "$EMU" <<'PY'
import re, subprocess, sys, time
dev = sys.argv[1]
def dump():
    subprocess.run(['adb','-s',dev,'shell','uiautomator','dump','/sdcard/ui.xml'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return subprocess.check_output(['adb','-s',dev,'shell','cat','/sdcard/ui.xml']).decode('utf-8','replace')
def tap_bounds(m):
    x1,y1,x2,y2=map(int,m.groups())
    subprocess.run(['adb','-s',dev,'shell','input','tap',str((x1+x2)//2),str((y1+y2)//2)])
for step in range(40):
    xml = dump()
    if 'Search' in xml and ('Chats' in xml or 'Contacts' in xml):
        sys.exit(0)
    if 'skip_button' in xml:
        m = re.search(r'resource-id="[^"]*skip_button[^"]*"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
        if m: tap_bounds(m); time.sleep(2); continue
    for label in ['Allow','NO','While using the app','Continue','OK']:
        m = re.search(rf'text="{label}"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
        if m: tap_bounds(m); time.sleep(2); break
    else:
        time.sleep(1.5)
sys.exit(1)
PY
}

wait_android_tox() {
  local i=0
  while (( i < 180 )); do
    qa -a org.khandaq.qa.LOG_TOX_ID
    sleep 2
    local tid
    tid=$(adb_dev logcat -d 2>/dev/null | rg "qa_toxid:device=([0-9A-F]{64,76})" -o --replace '$1' | tail -1 || true)
    if [[ -n "$tid" && ${#tid} -ge 64 ]]; then
      echo "$tid" > "$OUT/android_toxid.txt"
      return 0
    fi
    sleep 3; ((i+=3))
  done
  return 1
}

wait_android_friend_msg() {
  local text="$1" max="${2:-90}" i=0
  while (( i < max )); do
    qa -a org.khandaq.qa.VERIFY_FRIEND_MESSAGE --es text "$text"
    sleep 2
    if adb_dev logcat -d 2>/dev/null | rg -q "qa_friend_verify:found=true.*text=$text"; then
      return 0
    fi
    sleep 3; ((i+=3))
  done
  return 1
}

mesh_ok() {
  local gid="$1" min="${2:-2}"
  local gid6="${gid: -6}"
  local line
  line=$(adb_dev logcat -d 2>/dev/null | rg "group_peer_mesh:summary.*id=${gid6}" | tail -1 || true)
  [[ -n "$line" && "$line" == *"group_conn=1"* ]] || return 1
  [[ "$line" =~ tox_peers=([0-9]+) ]] && (( BASH_REMATCH[1] >= min ))
}

wait_mesh() {
  local gid="$1" min="${2:-2}" max="${3:-240}" i=0
  while (( i < max )); do
    mesh_ok "$gid" "$min" && return 0
    qa -a org.khandaq.qa.REVIVE_GROUPS
    ios_url "khandaq://qa/revive_groups"
    sleep 5; ((i+=5))
  done
  return 1
}

group_msg_seen_android() {
  local text="$1"
  adb_dev logcat -d 2>/dev/null | rg -q "group_msg_recv:.*text=$text"
}

log "=== QA FULL emulator+iOS OUT=$OUT ==="

[[ -f "$APK" ]] || { log "building android..."; (cd "$ROOT/khandaq-android-trifa/android-refimpl-app" && ./gradlew :app:assembleDebug --no-daemon) >>"$OUT/build.log" 2>&1; }
[[ -d "$IOS_APP" ]] || { log "building ios..."; (cd "$ROOT/khandaq-ios" && xcodebuild -workspace Antidote.xcworkspace -scheme Antidote -configuration Debug -destination "id=$IOS_SIM" -derivedDataPath build/simulator build) >>"$OUT/ios-build.log" 2>&1; }

adb_dev install -r "$APK" >>"$OUT/install-android.log" 2>&1
xcrun simctl install "$IOS_SIM" "$IOS_APP" >>"$OUT/install-ios.log" 2>&1
adb_dev shell am force-stop "$PKG" 2>/dev/null || true
adb_dev logcat -c 2>/dev/null || true
adb_dev shell am start -n "$PKG/com.zoffcc.applications.trifa.StartMainActivityWrapper" >/dev/null 2>&1
sleep 3
onboard_android || log "WARN: android onboard timeout"
sleep 5
xcrun simctl launch "$IOS_SIM" org.khandaq.messenger >>"$OUT/ios-launch.log" 2>&1
sleep 15

# --- Tox IDs ---
if wait_android_tox; then
  pass "Android tox ready"
else
  fail "Android tox not ready"
fi
ANDROID_TID=$(cat "$OUT/android_toxid.txt" 2>/dev/null || true)
ios_url "khandaq://qa/log_tox_id"
sleep 4
ios_logs
IOS_TID=$(rg "qa_ios:done action=log_tox_id.*device=([0-9A-F]+)" -o --replace '$1' "$OUT/ios-qa.log" 2>/dev/null | tail -1 || true)
if [[ -n "$IOS_TID" && ${#IOS_TID} -ge 64 ]]; then
  pass "iOS tox ready (${#IOS_TID} chars)"
  echo "$IOS_TID" > "$OUT/ios_toxid.txt"
else
  fail "iOS tox id not in logs"
fi

# --- Theme ---
ios_url "khandaq://qa/log_theme"
sleep 2
ios_url "khandaq://qa/toggle_theme"
sleep 2
ios_url "khandaq://qa/log_theme"
sleep 2
ios_logs
if rg -q "qa_ios:done action=toggle_theme" "$OUT/ios-qa.log" 2>/dev/null; then
  pass "iOS theme toggle"
else
  fail "iOS theme toggle"
fi

# --- Friend add (iOS adds Android) ---
if [[ -n "$ANDROID_TID" ]]; then
  ios_url "khandaq://qa/add_friend?tox_id=$ANDROID_TID"
  sleep 8
  ios_logs
  if ios_wait "qa_ios:done action=add_friend" 30; then
    pass "iOS add_friend to Android (no crash)"
  else
    fail "iOS add_friend"
  fi
  qa -a org.khandaq.qa.ACCEPT_FRIEND --es tox_id "$IOS_TID"
  sleep 5
fi

# --- DM cross-platform ---
DM_A="qa-dm-a2i-$TS"
DM_I="qa-dm-i2a-$TS"
if [[ -n "$IOS_TID" ]]; then
  qa -a org.khandaq.qa.SEND_FRIEND_MESSAGE --es tox_id "$IOS_TID" --es text "$DM_A"
  sleep 8
  ios_url "khandaq://qa/verify_friend_msg?text=$DM_A"
  sleep 6
  ios_logs
  if ios_wait "qa_ios:done action=verify_friend_msg.*text=$DM_A" 60; then
    pass "DM Android→iOS ($DM_A)"
  else
    fail "DM Android→iOS ($DM_A)"
  fi

  ios_url "khandaq://qa/send_friend?tox_id=$ANDROID_TID&text=$DM_I"
  sleep 8
  if wait_android_friend_msg "$DM_I" 90; then
    pass "DM iOS→Android ($DM_I)"
  else
    fail "DM iOS→Android ($DM_I)"
  fi
fi

# --- Group cross-platform ---
qa -a org.khandaq.qa.CREATE_PUBLIC_GROUP --es name "$GROUP_NAME"
sleep 12
GID=$(adb_dev logcat -d 2>/dev/null | rg -o 'create_new_group:ok num=[0-9]+ id=[0-9a-fA-F]{64}' | tail -1 | rg -o '[0-9a-fA-F]{64}' || true)
if [[ -z "$GID" ]]; then
  fail "Android group create (no gid)"
else
  pass "Android group create gid=${GID:0:16}..."
  echo "$GID" > "$OUT/group_id.txt"
  ios_url "khandaq://qa/join?group_id=$GID&min_peers=2"
  sleep 10
  if wait_mesh "$GID" 2 240; then
    pass "Group mesh >=2 peers"
  else
    fail "Group mesh timeout"
  fi

  GM_A="qa-grp-a-$TS"
  GM_I="qa-grp-i-$TS"
  qa -a org.khandaq.qa.SEND_GROUP_MESSAGE --es group_id "$GID" --es text "$GM_A"
  sleep 8
  ios_url "khandaq://qa/verify_group_msg?group_id=$GID&text=$GM_A"
  sleep 6
  if ios_wait "qa_ios:done action=verify_group_msg.*text=$GM_A" 90; then
    pass "Group Android→iOS ($GM_A)"
  else
    fail "Group Android→iOS ($GM_A)"
  fi

  ios_url "khandaq://qa/send_group?group_id=$GID&text=$GM_I"
  sleep 12
  if ios_wait "qa_ios:done action=send_group.*text=$GM_I" 60; then
    pass "Group iOS send ($GM_I)"
  else
    fail "Group iOS send ($GM_I)"
  fi
  sleep 5
  if group_msg_seen_android "$GM_I"; then
    pass "Group iOS→Android ($GM_I)"
  else
    fail "Group iOS→Android ($GM_I)"
  fi
fi

# --- Crashes ---
adb_dev logcat -d > "$OUT/logcat-android.txt" 2>&1
ios_logs
if adb_dev logcat -d 2>/dev/null | rg -qi "FATAL EXCEPTION|AndroidRuntime.*crash"; then
  fail "Android crash in logcat"
else
  pass "No Android FATAL in logcat"
fi
if xcrun simctl spawn "$IOS_SIM" log show --last 5m 2>/dev/null | rg -qi "Khandaq.*(SIGABRT|SIGSEGV|Fatal|terminated due to)"; then
  fail "iOS crash in logs"
else
  pass "No iOS crash in logs"
fi

PASS_N=$(rg -c '^PASS:' "$OUT/results.txt" 2>/dev/null || echo 0)
FAIL_N=$(rg -c '^FAIL:' "$OUT/results.txt" 2>/dev/null || echo 0)
log "=== DONE pass=$PASS_N fail=$FAIL_N artifacts=$OUT ==="
exit "$(( FAIL_N == 0 ? 0 : 1 ))"
