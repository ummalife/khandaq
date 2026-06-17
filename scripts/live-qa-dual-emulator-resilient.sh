#!/usr/bin/env bash
# Resilient dual-emulator NGC QA (no phone). Restarts 5556 if it dies mid-run.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PKG="org.khandaq.messenger"
APK="${APK:-$ROOT/khandaq-android-trifa/android-refimpl-app/app/build/outputs/apk/debug/app-debug.apk}"
OUT="${OUT:-$ROOT/docs/qa-dual-emu-$(date +%Y%m%d-%H%M%S)}"
CREATOR="${CREATOR:-emulator-5554}"
JOINER="${JOINER:-emulator-5556}"
AVD2="${AVD2:-Pixel_6a_2}"
TS="$(date +%s)"
PUB_NAME="QA_LAT_$TS"
MSG_C="qa_c_$TS"
MSG_J="qa_j_$TS"
WAIT_MESH="${WAIT_MESH:-300}"
SKIP_WIPE="${SKIP_WIPE:-0}"
WAIT_MSG="${WAIT_MSG:-90}"

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"

mkdir -p "$OUT"
SUMMARY="$OUT/summary.txt"
: > "$SUMMARY"
log() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$SUMMARY"; }

adb_dev() { adb -s "$1" "${@:2}"; }

dev_alive() { adb_dev "$1" get-state >/dev/null 2>&1; }

qa_broadcast() {
  local dev="$1" action="$2"
  shift 2
  adb_dev "$dev" shell am broadcast -a "$action" -p "$PKG" "$@" 2>&1 | tail -1
}

ensure_joiner_emulator() {
  if dev_alive "$JOINER"; then
    log "joiner $JOINER up"
    return 0
  fi
  log "starting $JOINER (AVD=$AVD2)"
  adb_dev "$JOINER" emu kill 2>/dev/null || true
  sleep 2
  nohup emulator -avd "$AVD2" -port 5556 -no-snapshot-load -partition-size 8192 \
    -no-audio -no-boot-anim -gpu swiftshader_indirect -memory 4096 \
    > "$OUT/emulator-5556.log" 2>&1 &
  for _ in $(seq 1 120); do
    if dev_alive "$JOINER"; then
      boot=$(adb_dev "$JOINER" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
      if [[ "$boot" == "1" ]]; then
        log "joiner booted"
        sleep 8
        return 0
      fi
    fi
    sleep 3
  done
  log "ERROR: joiner did not boot"
  return 1
}

install_dev() {
  local dev="$1"
  if ! dev_alive "$dev"; then return 1; fi
  if adb_dev "$dev" install -r "$APK" >>"$OUT/install-$dev.log" 2>&1; then
    return 0
  fi
  adb_dev "$dev" uninstall "$PKG" >>"$OUT/install-$dev.log" 2>&1 || true
  adb_dev "$dev" install -r "$APK" >>"$OUT/install-$dev.log" 2>&1
}

onboard_dev() {
  local dev="$1"
  log "[$dev] onboarding"
  adb_dev "$dev" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  sleep 3
  python3 - "$dev" <<'PY'
import re, subprocess, sys, time
dev = sys.argv[1]

def dump():
    subprocess.run(['adb','-s',dev,'shell','uiautomator','dump','/sdcard/ui.xml'],
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return subprocess.check_output(['adb','-s',dev,'shell','cat','/sdcard/ui.xml']).decode('utf-8','replace')

def tap(needle):
    xml = dump()
    m = re.search(rf'text="{re.escape(needle)}"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml, re.I)
    if m:
        x1,y1,x2,y2=map(int,m.groups())
        subprocess.run(['adb','-s',dev,'shell','input','tap',str((x1+x2)//2),str((y1+y2)//2)])
        return True
    m = re.search(r'resource-id="[^"]*skip_button[^"]*"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
    if m and needle.lower()=='skip':
        x1,y1,x2,y2=map(int,m.groups())
        subprocess.run(['adb','-s',dev,'shell','input','tap',str((x1+x2)//2),str((y1+y2)//2)])
        return True
    return False

labels = ['skip','SKIP','While using the app','Allow','NO','Continue','OK','Next','Done']
for step in range(45):
    xml = dump()
    if 'More options' in xml or ('text="Khandaq"' in xml and 'Search' in xml):
        print(f'READY {step}'); sys.exit(0)
    if 'Batteryoptimization' in xml or 'Battery optimization' in xml:
        tap('NO'); time.sleep(2); continue
    if 'Password' in xml or 'skip_button' in xml:
        tap('skip'); time.sleep(3); continue
    hit = False
    for l in labels:
        if l in xml and tap(l):
            time.sleep(2); hit = True; break
    if not hit:
        time.sleep(1.5)
print('TIMEOUT'); sys.exit(1)
PY
}

wait_tox() {
  local dev="$1" timeout="${2:-240}"
  log "[$dev] wait tox (max ${timeout}s)"
  for ((i=0; i<timeout; i+=5)); do
    if ! dev_alive "$dev"; then
      log "[$dev] OFFLINE during tox wait"
      return 1
    fi
    if adb_dev "$dev" logcat -d -t 600 2>/dev/null | rg -q 'is_tox_started=true|tox_thread_start_fg'; then
      log "[$dev] tox OK t=${i}s"
      return 0
    fi
    if (( i > 0 && i % 25 == 0 )); then
      adb_dev "$dev" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
      qa_broadcast "$dev" "com.zoffcc.applications.trifa.EXTERN_RECV" >/dev/null || true
    fi
    sleep 5
  done
  log "[$dev] tox timeout"
  return 1
}

nudge_app() {
  local dev="$1"
  dev_alive "$dev" || return 1
  adb_dev "$dev" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
  qa_broadcast "$dev" "com.zoffcc.applications.trifa.EXTERN_RECV" >/dev/null || true
}

# --- main ---
log "=== RESILIENT DUAL EMULATOR QA === OUT=$OUT"

if [[ ! -f "$APK" ]]; then
  log "building APK..."
  (cd "$ROOT/khandaq-android-trifa/android-refimpl-app" && ./gradlew assembleDebug -q)
fi

dev_alive "$CREATOR" || { log "ERROR: $CREATOR offline"; exit 1; }
ensure_joiner_emulator || exit 1

for dev in "$CREATOR" "$JOINER"; do
  if [[ "$SKIP_WIPE" == "1" ]] && adb_dev "$dev" shell pm path "$PKG" >/dev/null 2>&1; then
    log "[$dev] skip wipe (SKIP_WIPE=1)"
    continue
  fi
  log "[$dev] fresh install"
  adb_dev "$dev" shell am force-stop "$PKG" 2>/dev/null || true
  adb_dev "$dev" uninstall "$PKG" 2>/dev/null || true
  install_dev "$dev" || { log "FAIL install $dev"; exit 1; }
  onboard_dev "$dev" || log "WARN onboard timeout $dev (continuing)"
done

for dev in "$CREATOR" "$JOINER"; do
  log "[$dev] restart app for tox init"
  adb_dev "$dev" shell am force-stop "$PKG" 2>/dev/null || true
  adb_dev "$dev" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
done
sleep 15
# do NOT logcat -c here — would erase is_tox_started marker we poll for
wait_tox "$CREATOR" 240 || exit 2
wait_tox "$JOINER" 240 || {
  log "joiner tox slow — restart joiner emulator once"
  ensure_joiner_emulator
  [[ "$SKIP_WIPE" != "1" ]] && install_dev "$JOINER"
  onboard_dev "$JOINER" || true
  nudge_app "$JOINER"
  wait_tox "$JOINER" 240 || log "WARN joiner tox still not confirmed"
}

# Cross-link emulators as tox friends (DHT-only discovery is too slow/unreliable in dual-emu QA)
log "cross-link tox friends"
qa_broadcast "$CREATOR" "org.khandaq.qa.LOG_TOX_ID" >/dev/null || true
qa_broadcast "$JOINER" "org.khandaq.qa.LOG_TOX_ID" >/dev/null || true
sleep 2
CREATOR_TOX=$(adb_dev "$CREATOR" logcat -d -t 200 2>/dev/null | rg 'qa_toxid:device=([0-9A-Fa-f]{76})' -o --replace '$1' | tail -1)
JOINER_TOX=$(adb_dev "$JOINER" logcat -d -t 200 2>/dev/null | rg 'qa_toxid:device=([0-9A-Fa-f]{76})' -o --replace '$1' | tail -1)
log "creator_tox=${CREATOR_TOX:0:12}... joiner_tox=${JOINER_TOX:0:12}..."
if [[ ${#CREATOR_TOX} -eq 76 && ${#JOINER_TOX} -eq 76 ]]; then
  qa_broadcast "$CREATOR" "org.khandaq.qa.ADD_FRIEND" --es tox_id "$JOINER_TOX"
  qa_broadcast "$JOINER" "org.khandaq.qa.ADD_FRIEND" --es tox_id "$CREATOR_TOX"
  log "wait 120s for friend connection..."
  sleep 120
  for dev in "$CREATOR" "$JOINER"; do
    online=$(adb_dev "$dev" logcat -d -t 400 2>/dev/null | rg -c 'bootstrap_single:res=0' || true)
    log "[$dev] bootstrap_hits=$online (proxy for network up)"
  done
else
  log "WARN could not read tox ids — continuing without friend link"
fi

# CREATE on creator (keep prior logcat — friend-link logs must survive)
log "create public on $CREATOR"
qa_broadcast "$CREATOR" "org.khandaq.qa.CREATE_PUBLIC_GROUP" --es name "$PUB_NAME"
PUB_ID=""
for _ in $(seq 1 90); do
  PUB_ID=$(adb_dev "$CREATOR" logcat -d -t 300 2>/dev/null | rg 'create_new_group:ok num=.* id=([0-9a-fA-F]{64})' -o --replace '$1' | tail -1)
  [[ ${#PUB_ID} -eq 64 ]] && break
  sleep 2
done
log "PUBLIC id=$PUB_ID name=$PUB_NAME"
[[ ${#PUB_ID} -eq 64 ]] || { log "FAIL no group id"; exit 2; }

# JOIN on joiner (with joiner health retry)
join_ok=0
for attempt in 1 2 3; do
  if ! dev_alive "$JOINER"; then
    log "join attempt $attempt: joiner dead — restart"
    ensure_joiner_emulator
    install_dev "$JOINER"
    onboard_dev "$JOINER" || true
    wait_tox "$JOINER" 180 || true
  fi
  nudge_app "$JOINER"
  sleep 5
  log "join attempt $attempt on $JOINER"
  qa_broadcast "$JOINER" "org.khandaq.qa.JOIN_PUBLIC_GROUP" --es group_id "$PUB_ID"
  for _ in $(seq 1 60); do
    if adb_dev "$JOINER" logcat -d -t 400 2>/dev/null | rg -q 'finish_public_group_join:|join_group:already_in_tox|join_group:attempt=.*groupnum=[0-9]'; then
      join_ok=1
      log "join signal on joiner"
      break
    fi
    sleep 3
  done
  (( join_ok )) && break
done
(( join_ok )) || log "WARN join not confirmed in logcat"

# MESH wait
log "wait mesh max ${WAIT_MESH}s"
mesh_ok=0
for ((i=0; i<WAIT_MESH; i+=5)); do
  dev_alive "$CREATOR" || { log "creator died"; break; }
  dev_alive "$JOINER" || { log "joiner died at mesh t=$i — nudge"; ensure_joiner_emulator; nudge_app "$JOINER"; continue; }
  cf=$(adb_dev "$CREATOR" logcat -d -t 250 2>/dev/null | rg 'group_peer_mesh:summary.*gn=0' | tail -1 || true)
  jf=$(adb_dev "$JOINER" logcat -d -t 250 2>/dev/null | rg 'group_peer_mesh:summary' | tail -1 || true)
  cj=$(adb_dev "$CREATOR" logcat -d -t 250 2>/dev/null | rg -c 'group_peer_join_cb' || true)
  jj=$(adb_dev "$JOINER" logcat -d -t 250 2>/dev/null | rg -c 'group_peer_join_cb' || true)
  log "mesh t=${i}s c_joins=$cj j_joins=$jj"
  log "  C: ${cf:-none}"
  log "  J: ${jf:-none}"
  if [[ "$cf" == *"fanout=1"* || "$cf" == *"fanout=2"* ]] && [[ "$jf" == *"fanout=1"* ]]; then
    mesh_ok=1; break
  fi
  if (( cj >= 1 && jj >= 1 )); then
    mesh_ok=1; break
  fi
  if (( i > 0 && i % 60 == 0 )); then
    nudge_app "$CREATOR"; nudge_app "$JOINER"
  fi
  sleep 5
done

# MESSAGES
log "send test messages"
qa_broadcast "$CREATOR" "org.khandaq.qa.SEND_GROUP_MESSAGE" --es group_id "$PUB_ID" --es text "$MSG_C"
sleep 3
qa_broadcast "$JOINER" "org.khandaq.qa.SEND_GROUP_MESSAGE" --es group_id "$PUB_ID" --es text "$MSG_J"

del_c=0 del_j=0
for ((i=0; i<WAIT_MSG; i+=3)); do
  del_c=$(adb_dev "$CREATOR" logcat -d -t 300 2>/dev/null | rg -c "group_msg_recv:.*text=$MSG_J" || true)
  del_j=$(adb_dev "$JOINER" logcat -d -t 300 2>/dev/null | rg -c "group_msg_recv:.*text=$MSG_C" || true)
  (( del_c >= 1 && del_j >= 1 )) && break
  sleep 3
done

adb_dev "$CREATOR" logcat -d > "$OUT/logcat-creator.txt" 2>/dev/null || true
adb_dev "$JOINER" logcat -d > "$OUT/logcat-joiner.txt" 2>/dev/null || true

pass=0
[[ $mesh_ok -eq 1 && $del_c -ge 1 && $del_j -ge 1 ]] && pass=1

{
  echo "MESH_OK=$mesh_ok"
  echo "JOIN_OK=$join_ok"
  echo "DELIVERY creator_got_joiner=$([[ $del_c -ge 1 ]] && echo true || echo false)"
  echo "DELIVERY joiner_got_creator=$([[ $del_j -ge 1 ]] && echo true || echo false)"
  echo "PUBLIC_ID=$PUB_ID"
  echo "VERDICT pass=$pass"
} | tee "$OUT/verdict.txt" | tee -a "$SUMMARY"

log "Artifacts: $OUT"
exit $(( pass ? 0 : 3 ))
