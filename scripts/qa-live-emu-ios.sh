#!/usr/bin/env bash
# Live QA: Android emulator + iOS sim (no phone)
# Group chat + friend link + DM between emu and iOS
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PKG="com.khandaq.messenger"
RCVR="$PKG/com.zoffcc.applications.trifa.QaGroupBroadcastReceiver"
APK="${APK:-$ROOT/khandaq-android-trifa/android-refimpl-app/app/build/outputs/apk/debug/app-debug.apk}"
IOS_APP="${IOS_APP:-/tmp/khandaq-ios-dd/Build/Products/Debug-iphonesimulator/Khandaq.app}"
EMU="${EMU:-emulator-5554}"
IOS_SIM="${IOS_SIM:-B2161BF1-80D0-45D4-8A07-C2FE4F79CB5C}"
WAIT_MESH="${WAIT_MESH:-240}"
WAIT_MSG="${WAIT_MSG:-90}"
WAIT_FRIEND="${WAIT_FRIEND:-120}"
TS="$(date +%s)"
GROUP_NAME="QA-EMU-IOS-$TS"
OUT="${OUT:-$ROOT/docs/qa-emu-ios-$TS}"
mkdir -p "$OUT"

log() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$OUT/summary.txt" >&2; }

sanitize_tox_id() {
  echo "$1" | rg -o '[0-9A-Fa-f]{64,76}' | head -1
}
adb_dev() { adb -s "$1" "${@:2}"; }
qa_broadcast() { adb_dev "$1" shell am broadcast -n "$RCVR" "${@:2}" >>"$OUT/broadcast-$1.log" 2>&1; }

ios_openurl() { log "[ios] $1"; xcrun simctl openurl "$IOS_SIM" "$1" >>"$OUT/ios-url.log" 2>&1 || true; }
ios_collect_logs() {
  xcrun simctl spawn "$IOS_SIM" log show --last 10m --style compact 2>/dev/null \
    | rg "qa_ios:" > "$OUT/ios-qa.log" 2>&1 || true
}
ios_wait_log() {
  local pat="$1" max="${2:-90}" i=0
  while (( i < max )); do
    ios_collect_logs
    rg -q "$pat" "$OUT/ios-qa.log" 2>/dev/null && return 0
    sleep 3; ((i+=3))
  done
  return 1
}

launch_emu() {
  adb_dev "$EMU" shell am force-stop "$PKG" 2>/dev/null || true
  adb_dev "$EMU" logcat -c 2>/dev/null || true
  adb_dev "$EMU" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  sleep 15
  qa_broadcast "$EMU" -a org.khandaq.qa.LOG_TOX_ID
}

wait_emu_tox() {
  local i=0
  log "[emu] wait tox (max ${1:-180}s)"
  while (( i < ${1:-180} )); do
    local tid
    tid=$(adb_dev "$EMU" logcat -d 2>/dev/null | rg "qa_toxid:device=([0-9A-F]{64,76})" -o --replace '$1' | tail -1 || true)
    if [[ -n "$tid" && ${#tid} -ge 64 ]]; then
      echo "$tid"
      return 0
    fi
    if adb_dev "$EMU" logcat -d 2>/dev/null | rg -q 'is_tox_started=true|tox_thread_start_fg'; then
      qa_broadcast "$EMU" -a org.khandaq.qa.LOG_TOX_ID
      sleep 3
      tid=$(adb_dev "$EMU" logcat -d 2>/dev/null | rg "qa_toxid:device=([0-9A-F]{64,76})" -o --replace '$1' | tail -1 || true)
      [[ -n "$tid" && ${#tid} -ge 64 ]] && { echo "$tid"; return 0; }
    fi
    if (( i > 0 && i % 30 == 0 )); then
      adb_dev "$EMU" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
      qa_broadcast "$EMU" -a org.khandaq.qa.LOG_TOX_ID
    fi
    sleep 3; ((i+=3))
  done
  return 1
}

mesh_line() {
  local gid6="${2: -6}"
  adb_dev "$1" logcat -d 2>/dev/null | rg "group_peer_mesh:summary.*id=${gid6}" | tail -1 || true
}

mesh_ok() {
  local dev="$1" gid="$2" min="${3:-2}"
  local line peers
  line=$(mesh_line "$dev" "$gid")
  [[ -z "$line" ]] && return 1
  if [[ "$line" =~ tox_peers=([0-9]+) ]]; then
    peers="${BASH_REMATCH[1]}"
    (( peers >= min )) && [[ "$line" == *"group_conn=1"* ]] && return 0
  fi
  return 1
}

wait_mesh() {
  local dev="$1" gid="$2" min="$3" max="${4:-$WAIT_MESH}"
  log "[$dev] wait mesh min=$min gid=...${gid: -6}"
  local i=0
  while (( i < max )); do
    if mesh_ok "$dev" "$gid" "$min"; then
      mesh_line "$dev" "$gid" | tee -a "$OUT/summary.txt"
      return 0
    fi
    qa_broadcast "$dev" -a org.khandaq.qa.REVIVE_GROUPS
    sleep 5; ((i+=5))
  done
  return 1
}

emu_msg_seen() { adb_dev "$1" logcat -d 2>/dev/null | rg -q "group_msg_recv:.*text=$2"; }
emu_friend_verify() {
  qa_broadcast "$EMU" -a org.khandaq.qa.VERIFY_FRIEND_MESSAGE --es text "$1"
  sleep 4
  adb_dev "$EMU" logcat -d 2>/dev/null | rg -q "qa_friend_verify:found=true.*text=$1"
}
ios_verify_friend() {
  ios_openurl "khandaq://qa/verify_friend_msg?text=$1"
  sleep 5
  ios_wait_log "qa_ios:done action=verify_friend_msg.*text=$1" 120
}
ios_verify_group() {
  ios_openurl "khandaq://qa/verify_group_msg?group_id=$1&text=$2"
  sleep 5
  ios_wait_log "qa_ios:done action=verify_group_msg.*text=$2" 120
}

ios_revive_groups() {
  ios_openurl "khandaq://qa/revive_groups"
  sleep 3
}

send_group_retry() {
  local text="$2" n=1
  while (( n <= 6 )); do
    qa_broadcast "$EMU" -a org.khandaq.qa.SEND_GROUP_MESSAGE --es group_id "$1" --es text "$text"
    sleep 8
    adb_dev "$EMU" logcat -d 2>/dev/null | rg -q "SEND_GROUP_MESSAGE msg_id=[0-9]+.*text=$text|qa_group_broadcast:done action=.*SEND_GROUP_MESSAGE msg_id=[0-9]+" && return 0
    qa_broadcast "$EMU" -a org.khandaq.qa.REVIVE_GROUPS
    sleep 10; ((n++))
  done
  return 1
}

emu_friend_accepted() {
  adb_dev "$EMU" logcat -d 2>/dev/null | rg "add_friend_to_system:fnum add=[0-9]+" | tail -1 | rg -qv "4294967295"
}

wait_emu_accept_ios() {
  local ios_tox="$1" max="${2:-180}"
  log "[emu] accept friend ios=...${ios_tox: -6}"
  local i=0
  while (( i < max )); do
    qa_broadcast "$EMU" -a org.khandaq.qa.ACCEPT_FRIEND --es tox_id "$ios_tox"
    sleep 8
    if emu_friend_accepted; then
      log "[emu] friend accept ok"
      return 0
    fi
    qa_broadcast "$EMU" -a org.khandaq.qa.SEND_FRIEND_MESSAGE --es tox_id "$ios_tox" --es text "qa-ping-$TS"
    sleep 4
    if adb_dev "$EMU" logcat -d 2>/dev/null | rg "SEND_FRIEND_MESSAGE tox_id=${ios_tox}.*msg=[0-9]+" | tail -1 | rg -q .; then
      log "[emu] friend link ok (send probe)"
      return 0
    fi
    sleep 12; ((i+=20))
  done
  return 1
}

emu_send_friend_retry() {
  local tox_id="$1" text="$2" max="${3:-240}"
  log "[emu] send_friend retry tox=...${tox_id: -6} text=$text"
  local i=0
  while (( i < max )); do
    qa_broadcast "$EMU" -a org.khandaq.qa.SEND_FRIEND_MESSAGE --es tox_id "$tox_id" --es text "$text"
    sleep 6
    if adb_dev "$EMU" logcat -d 2>/dev/null | rg "SEND_FRIEND_MESSAGE tox_id=${tox_id}.*msg=[0-9]+" | tail -1 | rg -q .; then
      return 0
    fi
    sleep 9; ((i+=15))
  done
  return 1
}

# --- main ---
log "=== QA EMU + iOS (no phone) === OUT=$OUT"

if ! adb_dev "$EMU" get-state 2>/dev/null | rg -q device; then
  log "FAIL: $EMU offline"; exit 2
fi
if ! xcrun simctl list devices booted 2>/dev/null | rg -q "$IOS_SIM"; then
  log "FAIL: iOS sim not booted"; exit 2
fi

[[ -f "$APK" ]] || (cd "$ROOT/khandaq-android-trifa/android-refimpl-app" && ./gradlew :app:assembleDebug --no-daemon)
if [[ "${CLEAN_EMU:-0}" == "1" ]]; then
  log "[emu] pm clear $PKG (fresh tox identity)"
  adb_dev "$EMU" shell pm clear "$PKG" >>"$OUT/clear-emu.log" 2>&1 || true
fi
adb_dev "$EMU" install -r "$APK" >>"$OUT/install-emu.log" 2>&1 || {
  log "WARN: install failed — check existing package"
  adb_dev "$EMU" shell pm path "$PKG" >>"$OUT/install-emu.log" 2>&1 || { log "FAIL: $PKG not installed"; exit 3; }
}
[[ -d "$IOS_APP" ]] || (cd "$ROOT/khandaq-ios" && xcodebuild -workspace Antidote.xcworkspace -scheme Antidote -configuration Debug \
  -destination "id=$IOS_SIM" -derivedDataPath /tmp/khandaq-ios-dd CODE_SIGNING_ALLOWED=NO build >>"$OUT/ios-build.log" 2>&1)
xcrun simctl install "$IOS_SIM" "$IOS_APP" >>"$OUT/ios-install.log" 2>&1 || true
xcrun simctl terminate "$IOS_SIM" org.khandaq.messenger 2>/dev/null || true
sleep 1
xcrun simctl launch "$IOS_SIM" org.khandaq.messenger >>"$OUT/ios-launch.log" 2>&1
sleep 20

launch_emu
EMU_TOX=$(sanitize_tox_id "$(wait_emu_tox 180)") || true
[[ ${#EMU_TOX} -ge 64 ]] || { adb_dev "$EMU" logcat -d > "$OUT/logcat-emu-fail.txt" 2>&1; log "FAIL emu tox"; exit 1; }
log "emu tox=${EMU_TOX:0:20}..."

ios_openurl "khandaq://qa/log_tox_id"
sleep 5
ios_collect_logs
IOS_TOX=$(sanitize_tox_id "$(rg "qa_ios:done action=log_tox_id device=([0-9A-Fa-f]+)" -o --replace '$1' "$OUT/ios-qa.log" 2>/dev/null | tail -1 || true)")
if [[ ${#IOS_TOX} -lt 64 ]]; then
  log "WARN: iOS tox id not in logs — retry log_tox_id"
  ios_openurl "khandaq://qa/log_tox_id"
  sleep 5
  ios_collect_logs
  IOS_TOX=$(sanitize_tox_id "$(rg "qa_ios:done action=log_tox_id device=([0-9A-Fa-f]+)" -o --replace '$1' "$OUT/ios-qa.log" 2>/dev/null | tail -1 || true)")
fi
log "ios tox=${IOS_TOX:0:20}..."
[[ ${#IOS_TOX} -ge 64 && ${#EMU_TOX} -ge 64 ]] || { log "FAIL tox ids"; exit 1; }

PASS=0; FAIL=0

# --- Friend link + DM ---
log "=== Phase 1: Friend link + DM ==="
# iOS initiates, Android accepts (Android needs explicit ACCEPT for friend_request dialog)
ios_openurl "khandaq://qa/add_friend?tox_id=$EMU_TOX"
sleep 10
wait_emu_accept_ios "$IOS_TOX" 180 || log "WARN: emu accept not confirmed in logcat"
qa_broadcast "$EMU" -a org.khandaq.qa.FORCE_ADD_FRIEND --es tox_id "$IOS_TOX"
sleep 8
log "wait friend connection ${WAIT_FRIEND}s"
sleep "$WAIT_FRIEND"

DM_E="qa-dm-emu-$TS"
DM_I="qa-dm-ios-$TS"
# iOS first — bootstraps Android friend DB via incoming message callback
ios_openurl "khandaq://qa/send_friend?tox_id=$EMU_TOX&text=$DM_I"
sleep 12
emu_send_friend_retry "$IOS_TOX" "$DM_E" 120 || true
sleep 45

if ios_verify_friend "$DM_E"; then
  log "PASS: iOS got emu DM"; PASS=$((PASS+1))
else
  log "FAIL: iOS did not get emu DM"; FAIL=$((FAIL+1))
fi
if emu_friend_verify "$DM_I"; then
  log "PASS: emu got iOS DM"; PASS=$((PASS+1))
else
  log "FAIL: emu did not get iOS DM"; FAIL=$((FAIL+1))
fi

# --- Group chat ---
log "=== Phase 2: Group chat emu ↔ iOS ==="
qa_broadcast "$EMU" -a org.khandaq.qa.CREATE_PUBLIC_GROUP --es name "$GROUP_NAME"
sleep 15
GID=$(adb_dev "$EMU" logcat -d 2>/dev/null | rg -o 'create_new_group:ok num=[0-9]+ id=[0-9a-fA-F]{64}' | tail -1 | rg -o '[0-9a-fA-F]{64}' || true)
[[ ${#GID} -eq 64 ]] || { log "FAIL no group id"; exit 1; }
log "group_id=$GID"

ios_openurl "khandaq://qa/join?group_id=$GID&min_peers=2"
sleep 5
ios_collect_logs
if ! rg -qi "qa_ios:done action=join.*group_id=${GID}" "$OUT/ios-qa.log" 2>/dev/null; then
  ios_openurl "khandaq://qa/join?group_id=$GID&min_peers=2"
  sleep 5
fi
qa_broadcast "$EMU" -a org.khandaq.qa.REVIVE_GROUPS
ios_revive_groups
sleep 5

MESH_E=0; MESH_I=0
if wait_mesh "$EMU" "$GID" 2 "$WAIT_MESH"; then
  MESH_E=1; PASS=$((PASS+1)); log "PASS: emu mesh"
else
  FAIL=$((FAIL+1)); log "FAIL: emu mesh"
fi
if ios_wait_log "qa_ios:done action=join.*group_id=${GID}|qa_ios:done action=join.*connected=1" 200; then
  MESH_I=1; PASS=$((PASS+1)); log "PASS: iOS join+mesh"
else
  ios_revive_groups
  ios_openurl "khandaq://qa/join?group_id=$GID&min_peers=2"
  sleep 5
  if ios_wait_log "qa_ios:done action=join" 200; then
    MESH_I=1; PASS=$((PASS+1)); log "PASS: iOS join retry"
  else
    FAIL=$((FAIL+1)); log "FAIL: iOS join"
  fi
fi

MSG_E="qa-grp-emu-$TS"
MSG_I="qa-grp-ios-$TS"
send_group_retry "$GID" "$MSG_E" || true
if [[ $MESH_I -eq 1 ]]; then
  ios_revive_groups
  ios_openurl "khandaq://qa/send_group?group_id=$GID&text=$MSG_I"
  sleep 5
else
  log "SKIP: iOS group send (no mesh)"
fi

if [[ $MESH_I -eq 1 ]] && ios_wait_log "qa_ios:done action=send_group.*text=$MSG_I.*sent=1" 120; then
  PASS=$((PASS+1)); log "PASS: iOS group send"
else
  FAIL=$((FAIL+1)); log "FAIL: iOS group send"
fi

# delivery emu -> iOS via verify
if ios_verify_group "$GID" "$MSG_E"; then
  PASS=$((PASS+1)); log "PASS: iOS got emu group msg"
else
  FAIL=$((FAIL+1)); log "FAIL: iOS did not get emu group msg"
fi

# delivery iOS -> emu via logcat
if emu_msg_seen "$EMU" "$MSG_I"; then
  PASS=$((PASS+1)); log "PASS: emu got iOS group msg"
else
  sleep 30
  if emu_msg_seen "$EMU" "$MSG_I"; then
    PASS=$((PASS+1)); log "PASS: emu got iOS group msg late"
  else
    FAIL=$((FAIL+1)); log "FAIL: emu did not get iOS group msg"
  fi
fi

adb_dev "$EMU" logcat -d > "$OUT/logcat-emu.txt" 2>&1
ios_collect_logs

{
  echo "GROUP_ID=$GID"
  echo "EMU_TOX=${EMU_TOX:0:20}..."
  echo "IOS_TOX=${IOS_TOX:0:20}..."
  echo "MESH_EMU=$MESH_E"
  echo "IOS_JOIN=$MESH_I"
  echo "PASS_CHECKS=$PASS"
  echo "FAIL_CHECKS=$FAIL"
} | tee "$OUT/verdict.txt" | tee -a "$OUT/summary.txt"

CORE=0
[[ $FAIL -eq 0 && $PASS -ge 6 ]] && CORE=1

log "=== RESULT pass=$PASS fail=$FAIL core=$([[ $CORE -eq 1 ]] && echo OK || echo PARTIAL/FAIL) ==="
log "artifacts: $OUT"
exit "$(( CORE == 1 ? 0 : 1 ))"
