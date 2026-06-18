#!/usr/bin/env bash
# Visible dual-emulator live QA — keeps emulators alive for the whole run.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
APK="${APK:-$ROOT/dist/android/khandaq-release.apk}"
PKG="org.khandaq.messenger"
TS="$(date +%s)"
LOG="$ROOT/docs/qa-live-visible-$TS"
mkdir -p "$LOG"

log() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$LOG/summary.txt"; }

wait_emu() {
  local serial="$1" timeout="${2:-180}"
  local i=0
  while (( i < timeout )); do
    if adb -s "$serial" get-state 2>/dev/null | grep -q device; then
      if [[ "$(adb -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; then
        return 0
      fi
    fi
    sleep 2
    ((i += 2))
  done
  return 1
}

start_emu() {
  local avd="$1" port="$2" gpu="$3"
  local serial="emulator-$port"
  if adb devices | grep -q "$serial.*device"; then
    log "$serial already up"
    return 0
  fi
  log "start $serial avd=$avd gpu=$gpu (visible window)"
  nohup "$ANDROID_HOME/emulator/emulator" \
    -avd "$avd" -port "$port" -no-boot-anim -no-audio -gpu "$gpu" -memory 2048 \
    >>"$LOG/emu-$port.log" 2>&1 </dev/null &
  disown || true
  wait_emu "$serial" 180
}

onboarding() {
  local dev="$1"
  adb -s "$dev" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  sleep 2
  python3 - "$dev" <<'PY'
import re,subprocess,sys,time
dev=sys.argv[1]
def dump():
 subprocess.run(['adb','-s',dev,'shell','uiautomator','dump','/sdcard/ui.xml'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
 return subprocess.check_output(['adb','-s',dev,'shell','cat','/sdcard/ui.xml']).decode('utf-8','replace')
def tap(needle):
 xml=dump()
 m=re.search(rf'text="{re.escape(needle)}"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml, re.I)
 if m:
  x1,y1,x2,y2=map(int,m.groups()); subprocess.run(['adb','-s',dev,'shell','input','tap',str((x1+x2)//2),str((y1+y2)//2)]); return True
 return False
for _ in range(25):
 xml=dump()
 if 'More options' in xml: print('READY'); break
 for l in ['SKIP','Continue','Allow','While using the app','Next','Done','OK']:
  if l in xml and tap(l): time.sleep(2); break
 else:
  if 'EditText' in xml:
   subprocess.run(['adb','-s',dev,'shell','input','tap','540','600'])
   subprocess.run(['adb','-s',dev,'shell','input','text','QAUser'])
   subprocess.run(['adb','-s',dev,'shell','input','keyevent','66']); time.sleep(2)
  else: time.sleep(1)
PY
}

log "=== Khandaq visible live QA ==="
log "LOG=$LOG"
log "APK=$(shasum -a 256 "$APK" | awk '{print $1}')"

adb kill-server 2>/dev/null || true
adb start-server

# sequential boot — emu1 host GPU, emu2 software GPU
start_emu Pixel_6a 5554 host
sleep 8
start_emu Pixel_6a_2 5556 swiftshader_indirect
adb devices -l | tee -a "$LOG/summary.txt"

for dev in emulator-5554 emulator-5556; do
  log "[$dev] fresh install"
  adb -s "$dev" shell am force-stop "$PKG" 2>/dev/null || true
  adb -s "$dev" uninstall "$PKG" 2>/dev/null || true
  adb -s "$dev" install "$APK" 2>&1 | tee "$LOG/install-$dev.log"
done

log "onboarding..."
onboarding emulator-5554 | tee -a "$LOG/onboard-5554.log"
onboarding emulator-5556 | tee -a "$LOG/onboard-5556.log"

log "=== NGC QA (watch emulator windows) ==="
OUT="$LOG" QA_SINGLE_PUBLIC="${QA_SINGLE_PUBLIC:-0}" WAIT_SYNC="${WAIT_SYNC:-120}" \
  bash "$ROOT/scripts/live-qa-ngc-groups.sh" 2>&1 | tee "$LOG/qa-run.log"

log "=== QA finished. Emulators stay open 15 min for manual inspection ==="
log "Artifacts: $LOG"
for i in $(seq 1 30); do
  if ! adb devices | grep -qE 'emulator-555[46].*device'; then
    log "WARN: emulator went offline at minute $((i/2))"
  fi
  sleep 30
done
log "Done."
