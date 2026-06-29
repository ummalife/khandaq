#!/usr/bin/env bash
# Live QA: Android phone + emulator + iOS sim (khandaq://qa)
# ANDROID_ONLY=1  — skip iOS
# INSTALL_APK=1   — reinstall debug APK on Android
# INSTALL_IOS=1   — reinstall iOS sim app
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PKG="org.khandaq.messenger"
RCVR="$PKG/com.zoffcc.applications.trifa.QaGroupBroadcastReceiver"
APK="${APK:-$ROOT/khandaq-android-trifa/android-refimpl-app/app/build/outputs/apk/debug/app-debug.apk}"
IOS_APP="${IOS_APP:-/tmp/khandaq-ios-dd/Build/Products/Debug-iphonesimulator/Khandaq.app}"
PHONE="${PHONE:-ce75a71db51e}"
EMU="${EMU:-emulator-5554}"
IOS_SIM="${IOS_SIM:-B2161BF1-80D0-45D4-8A07-C2FE4F79CB5C}"
ANDROID_ONLY="${ANDROID_ONLY:-0}"
INSTALL_APK="${INSTALL_APK:-1}"
INSTALL_IOS="${INSTALL_IOS:-1}"
WAIT_MESH="${WAIT_MESH:-240}"
WAIT_MESH3="${WAIT_MESH3:-180}"
WAIT_MSG="${WAIT_MSG:-90}"
TS="$(date +%s)"
GROUP_NAME="QA-LIVE-$TS"
OUT="${OUT:-$ROOT/docs/qa-live-$TS}"
mkdir -p "$OUT"

log() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$OUT/summary.txt"; }

adb_dev() { adb -s "$1" "${@:2}"; }

require_device() {
  local dev="$1"
  if ! adb_dev "$dev" get-state 2>/dev/null | rg -q device; then
    log "FAIL: device $dev not online"
    exit 2
  fi
}

build_and_install_android() {
  local dev="$1"
  if [[ "$INSTALL_APK" != "1" ]]; then
    return 0
  fi
  if [[ ! -f "$APK" ]] || [[ "$INSTALL_APK" == "force" ]]; then
    log "gradle assembleDebug..."
    (cd "$ROOT/khandaq-android-trifa/android-refimpl-app" && ./gradlew :app:assembleDebug --no-daemon) \
      >>"$OUT/gradle-build.log" 2>&1 || { log "FAIL: gradle build"; exit 3; }
  fi
  log "[$dev] install $APK"
  adb_dev "$dev" install -r "$APK" >>"$OUT/install-$dev.log" 2>&1 || { log "FAIL: install on $dev"; exit 3; }
}

ios_booted() {
  xcrun simctl list devices booted 2>/dev/null | rg -q "$IOS_SIM"
}

ios_install_launch() {
  if [[ "$ANDROID_ONLY" == "1" ]]; then
    return 1
  fi
  if ! ios_booted; then
    log "FAIL: iOS sim $IOS_SIM not booted"
    return 1
  fi
  if [[ "$INSTALL_IOS" == "1" && -d "$IOS_APP" ]]; then
    log "[ios] install $IOS_APP"
    xcrun simctl install "$IOS_SIM" "$IOS_APP" >>"$OUT/ios-install.log" 2>&1 || true
  fi
  log "[ios] launch org.khandaq.messenger"
  xcrun simctl terminate "$IOS_SIM" org.khandaq.messenger 2>/dev/null || true
  sleep 1
  xcrun simctl launch "$IOS_SIM" org.khandaq.messenger >>"$OUT/ios-launch.log" 2>&1 || true
  sleep 20
  return 0
}

ios_openurl() {
  local url="$1"
  log "[ios] openurl $url"
  xcrun simctl openurl "$IOS_SIM" "$url" >>"$OUT/ios-url.log" 2>&1 || true
}

ios_collect_logs() {
  xcrun simctl spawn "$IOS_SIM" log show --last 5m 2>/dev/null \
    | rg -i "qa_ios|groupMessageCallback|Khandaq:" > "$OUT/ios-qa.log" 2>&1 || true
}

ios_log_has() {
  local pattern="$1"
  ios_collect_logs
  rg -q "$pattern" "$OUT/ios-qa.log" 2>/dev/null
}

ios_wait_log() {
  local pattern="$1" max="${2:-90}"
  local i=0
  while (( i < max )); do
    if ios_log_has "$pattern"; then
      return 0
    fi
    sleep 3
    ((i+=3))
  done
  return 1
}

ios_verify_group_msg() {
  local gid="$1" text="$2"
  ios_openurl "khandaq://qa/verify_group_msg?group_id=$gid&text=$(python3 -c "import urllib.parse; print(urllib.parse.quote('''$text'''))")"
  sleep 6
  ios_wait_log "qa_ios:done action=verify_group_msg.*text=$text" 120
}

ios_revive_groups() {
  ios_openurl "khandaq://qa/revive_groups"
  sleep 3
}

ios_group_mesh_peers() {
  local gid="$1"
  ios_openurl "khandaq://qa/log_group_mesh?group_id=$gid"
  sleep 4
  ios_collect_logs
  rg "qa_ios:done action=log_group_mesh.*group_id=${gid}.*peers=([0-9]+)" -o --replace '$1' "$OUT/ios-qa.log" 2>/dev/null | tail -1 || true
}

ios_wait_group_mesh() {
  local gid="$1" min_peers="${2:-2}" max="${3:-$WAIT_MESH3}"
  log "[ios] wait group mesh min_peers=$min_peers (${max}s)"
  local i=0
  while (( i < max )); do
    ios_revive_groups
    qa_broadcast "$PHONE" -a org.khandaq.qa.REVIVE_GROUPS 2>/dev/null || true
    qa_broadcast "$EMU" -a org.khandaq.qa.REVIVE_GROUPS 2>/dev/null || true
    local peers
    peers=$(ios_group_mesh_peers "$gid")
    if [[ -n "$peers" && "$peers" =~ ^[0-9]+$ && "$peers" -ge "$min_peers" ]]; then
      log "[ios] group mesh OK peers=$peers"
      return 0
    fi
    if wait_group_mesh "$PHONE" "$gid" "$min_peers" 10; then
      log "[ios] phone sees >=$min_peers peers (iOS peers=${peers:-?})"
      return 0
    fi
    sleep 5
    ((i+=5))
  done
  log "[ios] FAIL group mesh timeout"
  return 1
}

qa_broadcast() {
  local dev="$1"; shift
  adb_dev "$dev" shell am broadcast -n "$RCVR" "$@" >>"$OUT/broadcast-$dev.log" 2>&1
}

wait_tox_ready() {
  local dev="$1" max="${2:-120}"
  log "[$dev] wait tox ready (${max}s)"
  local i=0
  while (( i < max )); do
    local tid
    tid=$(adb_dev "$dev" logcat -d 2>/dev/null | rg "qa_toxid:device=([0-9A-F]{64,76})" -o --replace '$1' | tail -1 || true)
    if [[ -n "$tid" && ${#tid} -ge 64 ]]; then
      log "[$dev] tox ready tox_id=${tid:0:20}..."
      echo "$tid" > "$OUT/toxid-$dev.txt"
      return 0
    fi
    sleep 3
    ((i+=3))
  done
  log "[$dev] FAIL tox ready timeout"
  return 1
}

launch_app() {
  local dev="$1"
  adb_dev "$dev" shell am force-stop "$PKG" 2>/dev/null || true
  adb_dev "$dev" logcat -c 2>/dev/null || true
  adb_dev "$dev" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  sleep 12
}

mesh_line_for_gid() {
  local dev="$1" gid="$2"
  local gid6="${gid: -6}" gid16="${gid:0:16}"
  adb_dev "$dev" logcat -d 2>/dev/null \
    | rg "group_peer_mesh:summary.*id=${gid6}|group_peer_mesh:summary.*id=${gid16}" \
    | tail -1 || true
}

mesh_peers_count() {
  local line="$1"
  if [[ "$line" =~ tox_peers=([0-9]+) ]]; then
    echo "${BASH_REMATCH[1]}"
    return 0
  fi
  echo "0"
}

group_mesh_ok_min() {
  local dev="$1" gid="$2" min_peers="${3:-2}"
  local line peers
  line=$(mesh_line_for_gid "$dev" "$gid")
  if [[ -z "$line" ]]; then
    return 1
  fi
  peers=$(mesh_peers_count "$line")
  if (( peers >= min_peers )) && [[ "$line" == *"group_conn=1"* ]]; then
    return 0
  fi
  return 1
}

wait_group_mesh() {
  local dev="$1" gid="$2" min_peers="${3:-2}" max="${4:-$WAIT_MESH}"
  local gid6="${gid: -6}"
  log "[$dev] wait group mesh gid=...${gid6} min_peers=$min_peers (${max}s)"
  local i=0
  while (( i < max )); do
    if group_mesh_ok_min "$dev" "$gid" "$min_peers"; then
      local line
      line=$(mesh_line_for_gid "$dev" "$gid")
      log "[$dev] group mesh OK (gid ...${gid6}) ${line:-}"
      return 0
    fi
    qa_broadcast "$dev" -a org.khandaq.qa.REVIVE_GROUPS 2>/dev/null || true
    if (( i > 0 && i % 30 == 0 )); then
      qa_broadcast "$dev" -a org.khandaq.qa.DUMP_MESH --es group_id "$gid" 2>/dev/null || true
    fi
    sleep 5
    ((i+=5))
  done
  log "[$dev] FAIL group mesh timeout for ...${gid6}"
  mesh_line_for_gid "$dev" "$gid" | tee -a "$OUT/summary.txt" || true
  return 1
}

send_group_message_retry() {
  local dev="$1" gid="$2" text="$3" attempts="${4:-6}"
  local n=1
  while (( n <= attempts )); do
    log "[$dev] send attempt $n/$attempts: $text"
    qa_broadcast "$dev" -a org.khandaq.qa.SEND_GROUP_MESSAGE --es group_id "$gid" --es text "$text"
    sleep 8
    if adb_dev "$dev" logcat -d 2>/dev/null | rg -q "SEND_GROUP_MESSAGE msg_id=-98.*text=$text|qa_group_broadcast:done action=.*SEND_GROUP_MESSAGE msg_id=-98"; then
      log "[$dev] send queued/failed (-98), revive + retry..."
      qa_broadcast "$dev" -a org.khandaq.qa.REVIVE_GROUPS 2>/dev/null || true
      sleep 12
    elif adb_dev "$dev" logcat -d 2>/dev/null | rg -q "SEND_GROUP_MESSAGE msg_id=[0-9]+.*text=$text|qa_group_broadcast:done action=.*SEND_GROUP_MESSAGE msg_id=[0-9]+.*text=$text|group_msg_send:.*text=$text"; then
      log "[$dev] send OK"
      return 0
    fi
    ((n++))
  done
  log "[$dev] FAIL send after $attempts attempts"
  return 1
}

message_seen() {
  local dev="$1" text="$2"
  adb_dev "$dev" logcat -d 2>/dev/null | rg -q "group_msg_recv:.*text=$text"
}

wait_message_delivery() {
  local recv_dev="$1" text="$2" max="${3:-$WAIT_MSG}"
  local i=0
  while (( i < max )); do
    if message_seen "$recv_dev" "$text"; then
      return 0
    fi
    sleep 3
    ((i+=3))
  done
  return 1
}

extract_group_id() {
  local dev="$1" name="$2"
  adb_dev "$dev" logcat -d 2>/dev/null \
    | rg "create_new_group:ok.*name=$name|create_new_group:ok num=.* id=([0-9a-fA-F]{64})" -o \
    | rg -o '[0-9a-fA-F]{64}' | tail -1
}

extract_tox_id() {
  local dev="$1"
  adb_dev "$dev" logcat -d 2>/dev/null | rg "qa_toxid:device=([0-9A-F]+)" -o --replace '$1' | tail -1
}

# --- main ---
IOS_ENABLED=0
if [[ "$ANDROID_ONLY" != "1" ]] && ios_booted; then
  IOS_ENABLED=1
fi

log "=== QA LIVE START (3-device) ==="
log "phone=$PHONE emu=$EMU ios=$IOS_SIM ios_enabled=$IOS_ENABLED group=$GROUP_NAME"

require_device "$PHONE"
require_device "$EMU"

for dev in "$PHONE" "$EMU"; do
  build_and_install_android "$dev"
done

if [[ $IOS_ENABLED -eq 1 ]]; then
  if [[ ! -d "$IOS_APP" ]]; then
    log "building iOS debug..."
    (cd "$ROOT/khandaq-ios" && xcodebuild -workspace Antidote.xcworkspace -scheme Antidote -configuration Debug \
      -destination "id=$IOS_SIM" -derivedDataPath /tmp/khandaq-ios-dd CODE_SIGNING_ALLOWED=NO build) \
      >>"$OUT/ios-build.log" 2>&1 || log "WARN: iOS build failed"
  fi
  ios_install_launch || IOS_ENABLED=0
fi

FAIL=0
PASS=0
MESH_PHONE=0
MESH_EMU=0
IOS_JOIN=0
IOS_SEND=0

for dev in "$PHONE" "$EMU"; do
  launch_app "$dev"
  qa_broadcast "$dev" -a org.khandaq.qa.LOG_TOX_ID
  if ! wait_tox_ready "$dev" 120; then
    FAIL=$((FAIL+1))
  fi
  tid=$(extract_tox_id "$dev")
  log "[$dev] tox_id=${tid:0:20}..."
done

if [[ $IOS_ENABLED -eq 1 ]]; then
  ios_openurl "khandaq://qa/log_tox_id"
  sleep 5
fi

log "[$PHONE] create public group"
qa_broadcast "$PHONE" -a org.khandaq.qa.CREATE_PUBLIC_GROUP --es name "$GROUP_NAME"
sleep 15

GID=$(extract_group_id "$PHONE" "$GROUP_NAME")
if [[ -z "$GID" ]]; then
  GID=$(adb_dev "$PHONE" logcat -d 2>/dev/null | rg -o 'create_new_group:ok num=[0-9]+ id=[0-9a-fA-F]{64}' | tail -1 | rg -o '[0-9a-fA-F]{64}' || true)
fi
if [[ -z "$GID" ]]; then
  log "FAIL: no group id from phone"
  exit 1
fi
log "group_id=$GID"
echo "$GID" > "$OUT/group_id.txt"

log "[$EMU] join group"
adb_dev "$EMU" logcat -c 2>/dev/null || true
qa_broadcast "$EMU" -a org.khandaq.qa.JOIN_PUBLIC_GROUP --es group_id "$GID"
sleep 10

if [[ $IOS_ENABLED -eq 1 ]]; then
  log "[ios] join group"
  ios_openurl "khandaq://qa/join?group_id=$GID&min_peers=2"
  sleep 5
fi

qa_broadcast "$PHONE" -a org.khandaq.qa.REVIVE_GROUPS
qa_broadcast "$EMU" -a org.khandaq.qa.REVIVE_GROUPS
sleep 5

if wait_group_mesh "$PHONE" "$GID" 2 "$WAIT_MESH"; then
  MESH_PHONE=1
  PASS=$((PASS+1))
  log "PASS: phone mesh (>=2 peers)"
else
  log "FAIL: phone mesh"
  FAIL=$((FAIL+1))
fi

if wait_group_mesh "$EMU" "$GID" 2 "$WAIT_MESH"; then
  MESH_EMU=1
  PASS=$((PASS+1))
  log "PASS: emulator mesh (>=2 peers)"
else
  log "FAIL: emulator mesh"
  FAIL=$((FAIL+1))
fi

if [[ $IOS_ENABLED -eq 1 ]]; then
  if ios_wait_log "qa_ios:done action=join.*group_id=$GID|qa_ios:done action=join.*gn=" 200; then
    IOS_JOIN=1
    PASS=$((PASS+1))
    log "PASS: iOS join confirmed"
  else
    log "WARN: iOS join not confirmed in logs — retry join"
    ios_openurl "khandaq://qa/join?group_id=$GID&min_peers=2"
    sleep 5
    if ios_wait_log "qa_ios:done action=join" 200; then
      IOS_JOIN=1
      PASS=$((PASS+1))
      log "PASS: iOS join confirmed (retry)"
    else
      ios_collect_logs
      log "FAIL: iOS join"
      FAIL=$((FAIL+1))
    fi
  fi

  IOS_MESH=0
  if [[ $MESH_PHONE -eq 1 ]]; then
    log "wait 3-device group mesh (${WAIT_MESH3}s max)"
    if ios_wait_group_mesh "$GID" 3 "$WAIT_MESH3" && wait_group_mesh "$PHONE" "$GID" 3 30; then
      IOS_MESH=1
      PASS=$((PASS+1))
      log "PASS: 3-peer mesh (phone + iOS)"
    else
      log "FAIL: 3-peer mesh not confirmed — skipping iOS send/verify"
      FAIL=$((FAIL+1))
      IOS_JOIN=0
    fi
  fi
fi

qa_broadcast "$PHONE" -a org.khandaq.qa.DUMP_MESH --es group_id "$GID"
qa_broadcast "$EMU" -a org.khandaq.qa.DUMP_MESH --es group_id "$GID"
sleep 3

MSG1="qa-phone-$TS"
MSG2="qa-emu-$TS"
MSG3="qa-ios-$TS"

send_group_message_retry "$PHONE" "$GID" "$MSG1" 6 || true
sleep 3
send_group_message_retry "$EMU" "$GID" "$MSG2" 6 || true

if [[ $IOS_ENABLED -eq 1 && $IOS_JOIN -eq 1 && ${IOS_MESH:-0} -eq 1 ]]; then
  log "[ios] send group message"
  ios_openurl "khandaq://qa/send_group?group_id=$GID&text=$MSG3"
  sleep 12
  if ios_wait_log "qa_ios:done action=send_group.*text=$MSG3" 60; then
    IOS_SEND=1
    PASS=$((PASS+1))
    log "PASS: iOS send confirmed"
  else
    log "FAIL: iOS send not confirmed"
    FAIL=$((FAIL+1))
  fi
fi

log "wait Android delivery (${WAIT_MSG}s each)"
DELIVERY_EMU_PHONE=0
DELIVERY_PHONE_EMU=0
DELIVERY_PHONE_IOS=0
DELIVERY_EMU_IOS=0

if wait_message_delivery "$EMU" "$MSG1" "$WAIT_MSG"; then
  DELIVERY_EMU_PHONE=1
  PASS=$((PASS+1))
  log "PASS: emulator saw phone ($MSG1)"
else
  log "FAIL: emulator did not see phone ($MSG1)"
  FAIL=$((FAIL+1))
fi

if wait_message_delivery "$PHONE" "$MSG2" "$WAIT_MSG"; then
  DELIVERY_PHONE_EMU=1
  PASS=$((PASS+1))
  log "PASS: phone saw emulator ($MSG2)"
else
  log "FAIL: phone did not see emulator ($MSG2)"
  FAIL=$((FAIL+1))
fi

if [[ $IOS_ENABLED -eq 1 && $IOS_SEND -eq 1 ]]; then
  if wait_message_delivery "$PHONE" "$MSG3" "$WAIT_MSG"; then
    DELIVERY_PHONE_IOS=1
    PASS=$((PASS+1))
    log "PASS: phone saw iOS ($MSG3)"
  else
    log "FAIL: phone did not see iOS ($MSG3)"
    FAIL=$((FAIL+1))
  fi
  if wait_message_delivery "$EMU" "$MSG3" "$WAIT_MSG"; then
    DELIVERY_EMU_IOS=1
    PASS=$((PASS+1))
    log "PASS: emulator saw iOS ($MSG3)"
  else
    log "FAIL: emulator did not see iOS ($MSG3)"
    FAIL=$((FAIL+1))
  fi
fi

if [[ $IOS_ENABLED -eq 1 && $IOS_JOIN -eq 1 ]]; then
  log "[ios] verify received Android messages"
  IOS_RECV_PHONE=0
  IOS_RECV_EMU=0
  if ios_verify_group_msg "$GID" "$MSG1"; then
    IOS_RECV_PHONE=1
    PASS=$((PASS+1))
    log "PASS: iOS saw phone ($MSG1)"
  else
    log "FAIL: iOS did not see phone ($MSG1)"
    FAIL=$((FAIL+1))
  fi
  if ios_verify_group_msg "$GID" "$MSG2"; then
    IOS_RECV_EMU=1
    PASS=$((PASS+1))
    log "PASS: iOS saw emulator ($MSG2)"
  else
    log "FAIL: iOS did not see emulator ($MSG2)"
    FAIL=$((FAIL+1))
  fi
else
  IOS_RECV_PHONE=0
  IOS_RECV_EMU=0
fi

log "collect logcat"
adb_dev "$PHONE" logcat -d > "$OUT/logcat-phone.txt" 2>&1
adb_dev "$EMU" logcat -d > "$OUT/logcat-emu.txt" 2>&1
ios_collect_logs

ANDROID_CORE=0
[[ $MESH_PHONE -eq 1 && $MESH_EMU -eq 1 && $DELIVERY_EMU_PHONE -eq 1 && $DELIVERY_PHONE_EMU -eq 1 ]] && ANDROID_CORE=1

IOS_CORE=0
if [[ $IOS_ENABLED -eq 1 ]]; then
  [[ $IOS_JOIN -eq 1 && ${IOS_MESH:-0} -eq 1 && $IOS_SEND -eq 1 && $DELIVERY_PHONE_IOS -eq 1 && $DELIVERY_EMU_IOS -eq 1 && $IOS_RECV_PHONE -eq 1 && $IOS_RECV_EMU -eq 1 ]] && IOS_CORE=1
fi

FULL_CORE=0
[[ $ANDROID_CORE -eq 1 && ( $IOS_ENABLED -eq 0 || $IOS_CORE -eq 1 ) ]] && FULL_CORE=1

{
  echo "GROUP_ID=$GID"
  echo "IOS_ENABLED=$IOS_ENABLED"
  echo "MESH_PHONE=$MESH_PHONE"
  echo "MESH_EMU=$MESH_EMU"
  echo "IOS_MESH=${IOS_MESH:-0}"
  echo "IOS_JOIN=$IOS_JOIN"
  echo "IOS_SEND=$IOS_SEND"
  echo "DELIVERY_EMU_GOT_PHONE=$DELIVERY_EMU_PHONE"
  echo "DELIVERY_PHONE_GOT_EMU=$DELIVERY_PHONE_EMU"
  echo "DELIVERY_PHONE_GOT_IOS=$DELIVERY_PHONE_IOS"
  echo "DELIVERY_EMU_GOT_IOS=$DELIVERY_EMU_IOS"
  echo "IOS_GOT_PHONE=$IOS_RECV_PHONE"
  echo "IOS_GOT_EMU=$IOS_RECV_EMU"
  echo "PASS_CHECKS=$PASS"
  echo "FAIL_CHECKS=$FAIL"
  echo "ANDROID_CORE_PASS=$ANDROID_CORE"
  echo "IOS_CORE_PASS=$IOS_CORE"
  echo "FULL_CORE_PASS=$FULL_CORE"
} | tee "$OUT/verdict.txt" | tee -a "$OUT/summary.txt"

log "=== RESULT android=$([[ $ANDROID_CORE -eq 1 ]] && echo OK || echo FAIL) ios=$([[ $IOS_CORE -eq 1 ]] && echo OK || echo SKIP/FAIL) full=$([[ $FULL_CORE -eq 1 ]] && echo OK || echo FAIL) pass=$PASS fail=$FAIL ==="
log "artifacts: $OUT"

exit "$(( FULL_CORE == 1 ? 0 : 1 ))"
