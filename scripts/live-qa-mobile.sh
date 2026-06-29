#!/usr/bin/env bash
# Live QA smoke for Android device + iOS simulator
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SS="$ROOT/docs/qa-screenshots-v2"
PKG="org.khandaq.messenger"
SIM_ID="${IOS_SIM_ID:-741D68CF-E57E-41C5-8AFD-488051D1E86B}"
IOS_APP="${IOS_APP:-$ROOT/khandaq-ios/build/DerivedData/Build/Products/Release-iphonesimulator/Khandaq.app}"
LOG="$SS/qa-summary.txt"

mkdir -p "$SS"
: > "$LOG"

log() { echo "$1" | tee -a "$LOG"; }

# --- Android ---
log "=== ANDROID QA $(date) ==="
if adb devices 2>/dev/null | grep -qE '\sdevice$'; then
  adb logcat -c
  adb shell am force-stop "$PKG" 2>/dev/null || true
  adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  sleep 5
  PID=$(adb shell pidof "$PKG" 2>/dev/null | tr -d '\r')
  log "launch_pid=${PID:-none}"
  adb exec-out screencap -p > "$SS/android-01-chat-list.png" || true

  # Open Isa chat
  adb shell uiautomator dump /sdcard/ui.xml 2>/dev/null || true
  ISA_Y=$(python3 - <<'PY' 2>/dev/null || echo 629
import re, subprocess
try:
    xml = subprocess.check_output(['adb','shell','cat','/sdcard/ui.xml']).decode('utf-8','replace')
    for m in re.finditer(r'text="Isa"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        x1,y1,x2,y2 = map(int, m.groups())
        print((y1+y2)//2); break
except Exception:
    print(629)
PY
)
  adb shell input tap 570 "$ISA_Y"
  sleep 2
  adb exec-out screencap -p > "$SS/android-02-private-chat.png" || true
  log "chat_activity=$(adb shell dumpsys activity activities 2>/dev/null | rg 'topResumedActivity' | head -1 | tr -d '\r')"

  # Attachment -> preview -> cancel
  adb shell input tap 881 2156
  sleep 2
  adb exec-out screencap -p > "$SS/android-03-file-picker.png" || true
  adb shell input tap 518 794
  sleep 2
  PREVIEW=$(adb shell dumpsys activity activities 2>/dev/null | rg -c 'MediaSendPreviewActivity' || echo 0)
  log "media_preview_opened=$PREVIEW"
  adb exec-out screencap -p > "$SS/android-04-media-preview.png" || true
  adb shell input tap 647 2142
  sleep 1
  adb exec-out screencap -p > "$SS/android-05-after-cancel.png" || true

  FATAL=$(adb logcat -d 2>/dev/null | rg -c 'FATAL EXCEPTION' || echo 0)
  log "fatal_exceptions=$FATAL"
else
  log "android_device=not_connected"
fi

# --- iOS ---
log ""
log "=== iOS QA $(date) ==="
if [[ -d "$IOS_APP" ]]; then
  xcrun simctl boot "$SIM_ID" 2>/dev/null || true
  xcrun simctl install "$SIM_ID" "$IOS_APP" 2>/dev/null || true
  xcrun simctl terminate "$SIM_ID" org.khandaq.messenger 2>/dev/null || true
  xcrun simctl launch "$SIM_ID" org.khandaq.messenger 2>&1 | tee -a "$LOG"
  sleep 4
  xcrun simctl io "$SIM_ID" screenshot "$SS/ios-01-launch.png" 2>/dev/null || true
  log "ios_screenshot=ios-01-launch.png"
else
  log "ios_app_missing=$IOS_APP"
fi

log ""
log "Artifacts: $SS"
