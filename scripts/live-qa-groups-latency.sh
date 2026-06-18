#!/usr/bin/env bash
# Live QA: 2 emulators — create public group, join, measure mesh + message latency.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PKG="org.khandaq.messenger"
APK="${APK:-$ROOT/khandaq-android-trifa/android-refimpl-app/app/build/outputs/apk/debug/app-debug.apk}"
OUT="${OUT:-$ROOT/docs/qa-groups-latency-$(date +%Y%m%d-%H%M%S)}"
CREATOR="${CREATOR:-emulator-5554}"
JOINER="${JOINER:-emulator-5556}"
AVD2="${AVD2:-Pixel_6a_2}"
TS="$(date +%s)"
PUB_NAME="QA_LAT_$TS"
MSG_C="qa_c_$TS"
MSG_J="qa_j_$TS"
WAIT_MESH="${WAIT_MESH:-120}"
WAIT_MSG="${WAIT_MSG:-60}"
USE_BROADCAST="${USE_BROADCAST:-1}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"

mkdir -p "$OUT"
SUMMARY="$OUT/summary.txt"
: > "$SUMMARY"
log() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$SUMMARY"; }

adb_dev() { adb -s "$1" "${@:2}"; }

qa_broadcast() {
  local dev="$1" action="$2"
  shift 2
  adb_dev "$dev" shell am broadcast -a "$action" -p "$PKG" "$@" >/dev/null 2>&1
}

wait_tox_ready() {
  local dev="$1" timeout="${2:-180}"
  for ((i=0; i<timeout; i+=5)); do
    if adb_dev "$dev" logcat -d -t 800 2>/dev/null | rg -q 'is_tox_started=true|tox_thread_start_fg|create_new_group:ok'; then
      log "[$dev] tox ready t=${i}s"
      return 0
    fi
    if (( i > 0 && i % 30 == 0 )); then
      adb_dev "$dev" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
      qa_broadcast "$dev" "com.zoffcc.applications.trifa.EXTERN_RECV" 2>/dev/null || true
    fi
    sleep 5
  done
  log "[$dev] tox NOT ready after ${timeout}s"
  return 1
}

wait_log_match() {
  local dev="$1" pattern="$2" timeout="$3"
  for _ in $(seq 1 "$timeout"); do
    if adb_dev "$dev" logcat -d -t 400 2>/dev/null | rg -q "$pattern"; then
      return 0
    fi
    sleep 1
  done
  return 1
}

broadcast_create_public() {
  local dev="$1" name="$2"
  wait_tox_ready "$dev" 60 || true
  qa_broadcast "$dev" "org.khandaq.qa.CREATE_PUBLIC_GROUP" --es name "$name"
  wait_log_match "$dev" 'create_new_group:ok num=.* id=' 120
}

broadcast_join_public() {
  local dev="$1" gid="$2"
  qa_broadcast "$dev" "org.khandaq.qa.JOIN_PUBLIC_GROUP" --es group_id "$gid"
  wait_log_match "$dev" 'finish_public_group_join:gn=' 60 \
    || wait_log_match "$dev" 'join_group:already_in_tox' 10
}

broadcast_send_msg() {
  local dev="$1" gid="$2" msg="$3"
  qa_broadcast "$dev" "org.khandaq.qa.SEND_GROUP_MESSAGE" --es group_id "$gid" --es text "$msg"
  wait_log_match "$dev" "group_msg_send:.*text=$msg" 30
}

install_dev() {
  local dev="$1"
  if ! adb_dev "$dev" install -r "$APK" >>"$OUT/install-$dev.log" 2>&1; then
    log "[$dev] reinstall after uninstall"
    adb_dev "$dev" uninstall "$PKG" >>"$OUT/install-$dev.log" 2>&1 || true
    adb_dev "$dev" install -r "$APK" >>"$OUT/install-$dev.log" 2>&1 || return 1
  fi
}

ensure_joiner_emulator() {
  if adb_dev "$JOINER" get-state >/dev/null 2>&1; then
    log "joiner $JOINER already up"
    return 0
  fi
  log "starting second emulator AVD=$AVD2 -> $JOINER"
  nohup emulator -avd "$AVD2" -no-snapshot-load -partition-size 8192 -no-audio -no-boot-anim \
    -gpu swiftshader_indirect > "$OUT/emulator-$JOINER.log" 2>&1 &
  for _ in $(seq 1 90); do
    if adb_dev "$JOINER" get-state >/dev/null 2>&1; then
      boot=$(adb_dev "$JOINER" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
      if [[ "$boot" == "1" ]]; then
        log "joiner booted: $JOINER"
        return 0
      fi
    fi
    sleep 3
  done
  log "ERROR: joiner $JOINER did not boot"
  return 1
}

ui_dump() {
  local dev="$1" tag="$2"
  adb_dev "$dev" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || true
  adb_dev "$dev" shell cat /sdcard/ui.xml > "$OUT/ui-$dev-$tag.xml" 2>/dev/null || true
  cp "$OUT/ui-$dev-$tag.xml" "$OUT/ui-$dev-latest.xml" 2>/dev/null || true
}

tap_text() {
  local dev="$1" text="$2"
  python3 - "$dev" "$text" "$OUT" <<'PY'
import re, sys
dev, needle, out = sys.argv[1:4]
try:
    xml = open(f"{out}/ui-{dev}-latest.xml", encoding='utf-8', errors='replace').read()
except FileNotFoundError:
    sys.exit(1)
for pat in [
    rf'text="{re.escape(needle)}"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',
    rf'content-desc="{re.escape(needle)}"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',
]:
    m = re.search(pat, xml)
    if m:
        x1,y1,x2,y2 = map(int, m.groups())
        print((x1+x2)//2, (y1+y2)//2)
        sys.exit(0)
sys.exit(1)
PY
}

tap_xy() { adb_dev "$1" shell input tap "$2" "$3"; }

open_menu() {
  local dev="$1"
  ui_dump "$dev" "menu"
  if coords=$(tap_text "$dev" "More options" 2>/dev/null); then
    read -r x y <<< "$coords"
  else
    x=1020; y=180
  fi
  tap_xy "$dev" "$x" "$y"
  sleep 1
}

menu_pick() {
  local dev="$1"; shift
  open_menu "$dev"
  ui_dump "$dev" "menu-open"
  for label in "$@"; do
    if coords=$(tap_text "$dev" "$label" 2>/dev/null); then
      read -r x y <<< "$coords"
      tap_xy "$dev" "$x" "$y"
      sleep 2
      return 0
    fi
  done
  return 1
}

input_text() {
  local dev="$1" text="$2"
  adb_dev "$dev" shell cmd clipboard set-text "$text" >/dev/null 2>&1 \
    || adb_dev "$dev" shell am broadcast -a clipper.set -e text "$text" >/dev/null 2>&1 \
    || true
  sleep 0.2
  adb_dev "$dev" shell input keyevent 279 2>/dev/null || adb_dev "$dev" shell input text "$text"
}

create_public() {
  local dev="$1" name="$2"
  adb_dev "$dev" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  sleep 2
  ui_dump "$dev" "pre-create"
  if ! rg -q 'Create Public Group|group_new_group_name' "$OUT/ui-$dev-pre-create.xml" 2>/dev/null; then
    menu_pick "$dev" "Create new Public Group" "Создать новую открытую группу" || return 1
  fi
  sleep 1
  ui_dump "$dev" "create-form"
  tap_xy "$dev" 540 393
  sleep 0.3
  adb_dev "$dev" shell input keyevent 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67
  sleep 0.3
  input_text "$dev" "$name"
  sleep 1
  ui_dump "$dev" "create-filled"
  adb_dev "$dev" shell input keyevent 111
  sleep 0.5
  if coords=$(tap_text "$dev" "CREATE GROUP" 2>/dev/null); then
    read -r x y <<< "$coords"; tap_xy "$dev" "$x" "$y"
  else
    tap_xy "$dev" 810 1454
  fi
  sleep 4
  for _ in 1 2 3 4 5; do
    if adb_dev "$dev" logcat -d 2>/dev/null | rg -q "create_new_group:ok num=.* id="; then
      return 0
    fi
    sleep 2
  done
  return 1
}

join_public() {
  local dev="$1" gid="$2"
  menu_pick "$dev" "Join a Public Group" "Присоединиться к открытой группе" || return 1
  tap_xy "$dev" 540 450; sleep 0.5
  input_text "$dev" "$gid"
  sleep 1
  for label in "Join Group" "JOIN GROUP" "Join" "Присоединиться" "Вступить"; do
    if coords=$(tap_text "$dev" "$label" 2>/dev/null); then
      read -r x y <<< "$coords"; tap_xy "$dev" "$x" "$y"; sleep 4
      adb_dev "$dev" shell input keyevent 4; sleep 2
      return 0
    fi
  done
  tap_xy "$dev" 810 2200; sleep 4
  adb_dev "$dev" shell input keyevent 4; sleep 2
}

open_chat() {
  local dev="$1" name="$2"
  adb_dev "$dev" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  sleep 2
  ui_dump "$dev" "list"
  if coords=$(tap_text "$dev" "$name" 2>/dev/null); then
    read -r x y <<< "$coords"; tap_xy "$dev" "$x" "$y"; sleep 2
    return 0
  fi
  tap_xy "$dev" 540 600; sleep 2
}

send_msg() {
  local dev="$1" msg="$2"
  tap_xy "$dev" 400 2150; sleep 0.3
  input_text "$dev" "$msg"
  sleep 0.3
  adb_dev "$dev" shell input keyevent 66; sleep 2
}

launch() {
  local dev="$1"
  adb_dev "$dev" shell am force-stop "$PKG" 2>/dev/null || true
  adb_dev "$dev" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >>"$OUT/launch-$dev.log" 2>&1
  sleep 20
  adb_dev "$dev" logcat -c 2>/dev/null || true
}

# --- main ---
log "=== GROUP LATENCY QA === OUT=$OUT"
log "CREATOR=$CREATOR JOINER=$JOINER"

ensure_joiner_emulator || exit 1

for dev in "$CREATOR" "$JOINER"; do
  install_dev "$dev" || log "WARN install failed $dev"
done

launch "$CREATOR"
launch "$JOINER"
wait_tox_ready "$CREATOR" 180 || log "WARN creator tox slow"
wait_tox_ready "$JOINER" 180 || log "WARN joiner tox slow"
sleep 3

if [[ "$USE_BROADCAST" == "1" ]]; then
  log "using QA broadcast (USE_BROADCAST=1)"
  broadcast_create_public "$CREATOR" "$PUB_NAME" || { log "FAIL create public (broadcast)"; exit 2; }
else
  create_public "$CREATOR" "$PUB_NAME" || { log "FAIL create public (ui)"; exit 2; }
fi
PUB_ID="$(adb_dev "$CREATOR" logcat -d 2>/dev/null | rg 'create_new_group:ok num=.* id=([0-9a-fA-F]{64})' -o --replace '$1' | tail -1)"
log "PUBLIC id=$PUB_ID name=$PUB_NAME"
[[ ${#PUB_ID} -eq 64 ]] || { log "FAIL no group id"; exit 2; }

if [[ "$USE_BROADCAST" == "1" ]]; then
  broadcast_join_public "$JOINER" "$PUB_ID" || { log "FAIL join public (broadcast)"; exit 2; }
else
  join_public "$JOINER" "$PUB_ID" || { log "FAIL join public (ui)"; exit 2; }
fi

log "wait mesh (max ${WAIT_MESH}s)..."
mesh_ok=0
for ((i=0; i<WAIT_MESH; i+=5)); do
  cj=$(adb_dev "$CREATOR" logcat -d 2>/dev/null | rg -c 'group_peer_join_cb' || true)
  jj=$(adb_dev "$JOINER" logcat -d 2>/dev/null | rg -c 'group_peer_join_cb' || true)
  cf=$(adb_dev "$CREATOR" logcat -d 2>/dev/null | rg 'group_peer_mesh:summary' | tail -1 || true)
  jf=$(adb_dev "$JOINER" logcat -d 2>/dev/null | rg 'group_peer_mesh:summary' | tail -1 || true)
  log "mesh t=${i}s creator_joins=$cj joiner_joins=$jj"
  log "  creator: ${cf:-none}"
  log "  joiner:  ${jf:-none}"
  if [[ "$cf" == *"fanout=1"* || "$cf" == *"fanout=2"* ]] && [[ "$jf" == *"fanout=1"* ]]; then
    mesh_ok=1
    break
  fi
  if (( cj >= 1 && jj >= 1 )); then
    mesh_ok=1
    break
  fi
  sleep 5
done

if [[ "$USE_BROADCAST" == "1" ]]; then
  broadcast_send_msg "$CREATOR" "$PUB_ID" "$MSG_C" || log "WARN send creator (broadcast)"
  broadcast_send_msg "$JOINER" "$PUB_ID" "$MSG_J" || log "WARN send joiner (broadcast)"
else
  open_chat "$CREATOR" "$PUB_NAME"
  send_msg "$CREATOR" "$MSG_C"
  open_chat "$JOINER" "$PUB_NAME"
  send_msg "$JOINER" "$MSG_J"
fi
SEND_TS=$(date +%s%3N 2>/dev/null || python3 -c 'import time; print(int(time.time()*1000))')

log "wait delivery (max ${WAIT_MSG}s)..."
del_c=0 del_j=0
for ((i=0; i<WAIT_MSG; i+=3)); do
  del_c=$(adb_dev "$CREATOR" logcat -d 2>/dev/null | rg -c "group_msg_recv:.*text=$MSG_J" || true)
  del_j=$(adb_dev "$JOINER" logcat -d 2>/dev/null | rg -c "group_msg_recv:.*text=$MSG_C" || true)
  if (( del_c >= 1 && del_j >= 1 )); then
    break
  fi
  sleep 3
done

adb_dev "$CREATOR" logcat -d > "$OUT/logcat-creator.txt"
adb_dev "$JOINER" logcat -d > "$OUT/logcat-joiner.txt"

python3 - "$OUT" "$MSG_C" "$MSG_J" "$mesh_ok" "$del_c" "$del_j" <<'PY'
import re, sys, pathlib

out, msg_c, msg_j, mesh_ok, del_c, del_j = sys.argv[1:7]
mesh_ok = mesh_ok == "1"
del_c = int(del_c)
del_j = int(del_j)

def latency(sender_log, receiver_log, token):
    stext = pathlib.Path(sender_log).read_text(errors='replace')
    rtext = pathlib.Path(receiver_log).read_text(errors='replace')
    send_ts = recv_ts = None
    for line in stext.splitlines():
        if 'group_msg_send:ts=' in line and f'text={token}' in line:
            m = re.search(r'ts=(\d+)', line)
            if m: send_ts = int(m.group(1))
    for line in rtext.splitlines():
        if 'group_msg_recv:ts=' in line and f'text={token}' in line:
            m = re.search(r'ts=(\d+)', line)
            if m: recv_ts = int(m.group(1))
    if send_ts and recv_ts and recv_ts >= send_ts:
        return recv_ts - send_ts
    return None

lat_c_on_j = latency(f"{out}/logcat-creator.txt", f"{out}/logcat-joiner.txt", msg_c)
lat_j_on_c = latency(f"{out}/logcat-joiner.txt", f"{out}/logcat-creator.txt", msg_j)

def last_mesh(logpath):
    lines = [l for l in pathlib.Path(logpath).read_text(errors='replace').splitlines()
             if 'group_peer_mesh:summary' in l]
    return lines[-1] if lines else "none"

lines = []
lines.append(f"MESH_OK={mesh_ok}")
lines.append(f"DELIVERY creator_got_joiner={del_c >= 1} joiner_got_creator={del_j >= 1}")
lines.append(f"LATENCY_MS joiner->creator={lat_j_on_c}")
lines.append(f"LATENCY_MS creator->joiner={lat_c_on_j}")
lines.append(f"MESH creator: {last_mesh(f'{out}/logcat-creator.txt')}")
lines.append(f"MESH joiner:  {last_mesh(f'{out}/logcat-joiner.txt')}")

pass_ok = mesh_ok and del_c >= 1 and del_j >= 1
if lat_c_on_j is not None and lat_j_on_c is not None:
    pass_ok = pass_ok and lat_c_on_j <= 2000 and lat_j_on_c <= 2000
lines.append(f"VERDICT pass={pass_ok}")

pathlib.Path(f"{out}/verdict.txt").write_text("\n".join(lines) + "\n")
print("\n".join(lines))
sys.exit(0 if pass_ok else 3)
PY
rc=$?

log "Done. Artifacts: $OUT"
exit $rc
