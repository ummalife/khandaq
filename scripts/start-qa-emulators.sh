#!/usr/bin/env bash
# Start two visible Android emulators for Khandaq QA (Mac-stable).
# Emulator 1: host GPU. Emulator 2: software GPU (avoids Metal crash with dual host).
set -euo pipefail

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
EMULATOR="$ANDROID_HOME/emulator/emulator"
ADB="$ANDROID_HOME/platform-tools/adb"
LOG_DIR="${LOG_DIR:-$PWD/docs/qa-emulators-$(date +%Y%m%d-%H%M%S)}"
AVD1="${AVD1:-Pixel_6a}"
AVD2="${AVD2:-Pixel_6a_2}"
PORT1="${PORT1:-5554}"
PORT2="${PORT2:-5556}"

mkdir -p "$LOG_DIR"

log() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$LOG_DIR/boot.log"; }

wait_emu() {
  local serial="$1" timeout="${2:-180}"
  log "wait $serial (max ${timeout}s)"
  local i=0
  while (( i < timeout )); do
    if "$ADB" -s "$serial" get-state 2>/dev/null | grep -q device; then
      local boot
      boot="$("$ADB" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
      if [[ "$boot" == "1" ]]; then
        log "$serial boot_completed"
        return 0
      fi
    fi
    sleep 2
    ((i += 2))
  done
  log "WARN: $serial not fully booted in ${timeout}s"
  return 1
}

# Do not kill running emulators unless FORCE=1
if [[ "${FORCE:-0}" == "1" ]]; then
  pkill -f "emulator.*-port $PORT1" 2>/dev/null || true
  pkill -f "emulator.*-port $PORT2" 2>/dev/null || true
  sleep 2
fi

"$ADB" kill-server 2>/dev/null || true
"$ADB" start-server

if ! "$ADB" devices | grep -q "emulator-$PORT1.*device"; then
  log "Starting emulator-$PORT1 ($AVD1) gpu=host window=ON"
  nohup "$EMULATOR" \
    -avd "$AVD1" \
    -port "$PORT1" \
    -no-boot-anim \
    -no-audio \
    -gpu host \
    -memory 2048 \
    >>"$LOG_DIR/emu-$PORT1.log" 2>&1 &
  disown || true
  wait_emu "emulator-$PORT1" 180 || true
else
  log "emulator-$PORT1 already running"
fi

sleep 5

if ! "$ADB" devices | grep -q "emulator-$PORT2.*device"; then
  log "Starting emulator-$PORT2 ($AVD2) gpu=swiftshader window=ON"
  nohup "$EMULATOR" \
    -avd "$AVD2" \
    -port "$PORT2" \
    -no-boot-anim \
    -no-audio \
    -gpu swiftshader_indirect \
    -memory 2048 \
    >>"$LOG_DIR/emu-$PORT2.log" 2>&1 &
  disown || true
  wait_emu "emulator-$PORT2" 180 || true
else
  log "emulator-$PORT2 already running"
fi

"$ADB" devices -l | tee -a "$LOG_DIR/boot.log"
log "LOG_DIR=$LOG_DIR"
log "Done. Keep these windows open during QA."
