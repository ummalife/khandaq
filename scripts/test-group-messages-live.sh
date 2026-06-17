#!/usr/bin/env bash
# Install latest APK, wait for mesh, send cross-messages, print verdict. Keeps emulators alive.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="${APK:-$ROOT/dist/android/khandaq-release.apk}"
PKG="org.khandaq.messenger"
GROUP="${GROUP:-QA-PUBLIC-1781284029}"
LOG="$ROOT/docs/qa-msg-live-$(date +%s)"
mkdir -p "$LOG"
MSG1="liveA-$(date +%H%M%S)"
MSG2="liveB-$(date +%H%M%S)"

log() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$LOG/summary.txt"; }

open_group() {
  local dev=$1
  adb -s "$dev" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || true
  python3 - "$dev" "$GROUP" <<'PY'
import re,subprocess,sys
dev,name=sys.argv[1:3]
try:
 xml=subprocess.check_output(['adb','-s',dev,'shell','cat','/sdcard/ui.xml']).decode('utf-8','replace')
except Exception:
 sys.exit(1)
m=re.search(rf'text="{re.escape(name)}"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',xml)
if m:
 x1,y1,x2,y2=map(int,m.groups())
 subprocess.run(['adb','-s',dev,'shell','input','tap',str((x1+x2)//2),str((y1+y2)//2)])
PY
}

send_msg() {
  local dev=$1 msg=$2
  adb -s "$dev" shell input tap 400 2150
  sleep 0.4
  adb -s "$dev" shell input text "$msg"
  sleep 0.3
  adb -s "$dev" shell input keyevent 66
}

log "LOG=$LOG APK=$(shasum -a 256 "$APK" | awk '{print $1}')"
bash "$ROOT/scripts/start-qa-emulators.sh" 2>&1 | tee -a "$LOG/boot.log"

for dev in emulator-5554 emulator-5556; do
  log "install $dev"
  adb -s "$dev" install -r "$APK" 2>&1 | tee -a "$LOG/install-$dev.log" | tail -2
  adb -s "$dev" shell am force-stop "$PKG" 2>/dev/null || true
  adb -s "$dev" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
done
sleep 5
open_group emulator-5554; sleep 2
open_group emulator-5556; sleep 2

adb -s emulator-5554 logcat -c
adb -s emulator-5556 logcat -c
log "wait 90s for peer mesh (nudge-only maintain)..."
sleep 90

adb -s emulator-5554 logcat -d > "$LOG/peers-5554.txt"
adb -s emulator-5556 logcat -d > "$LOG/peers-5556.txt"
rg "group_peer_join_cb|peers=2|nudge_public" "$LOG/peers-"*.txt | tail -10 | tee -a "$LOG/summary.txt"

log "5554 -> $MSG1"
send_msg emulator-5554 "$MSG1"
sleep 30
log "5556 -> $MSG2"
send_msg emulator-5556 "$MSG2"
sleep 30

adb -s emulator-5554 logcat -d > "$LOG/logcat-5554.txt"
adb -s emulator-5556 logcat -d > "$LOG/logcat-5556.txt"

{
  echo "MSG1=$MSG1 MSG2=$MSG2"
  echo -n "5556_got_A="; rg -c "$MSG1|group_message_cb:recv.*liveA" "$LOG/logcat-5556.txt" || echo 0
  echo -n "5554_got_B="; rg -c "$MSG2|group_message_cb:recv.*liveB" "$LOG/logcat-5554.txt" || echo 0
  rg "send_group_text_message_resilient|sync_targets=" "$LOG/logcat-"*.txt | tail -12
} | tee -a "$LOG/summary.txt"

log "Done. Emulators stay open 10 min — watch windows."
for i in $(seq 1 20); do sleep 30; done
