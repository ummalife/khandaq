#!/usr/bin/env bash
# C3 partial: skip password, reach main UI, open About
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SS="$ROOT/docs/smoke-test-screenshots"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"

boot_emulator() {
  if adb devices 2>/dev/null | grep -qE 'emulator-[0-9]+\s+device'; then return 0; fi
  nohup "$ANDROID_HOME/emulator/emulator" -avd Pixel_6a -partition-size 8192 \
    -no-snapshot-save -no-audio -no-boot-anim -gpu swiftshader_indirect >> /tmp/khandaq-smoke-emu.log 2>&1 &
  for _ in $(seq 1 90); do adb devices | grep -q emulator && break; sleep 2; done
  for _ in $(seq 1 50); do [[ "$(adb shell getprop sys.boot_completed 2>/dev/null|tr -d '\r')" == "1" ]] && break; sleep 2; done
}

boot_emulator
adb install -r "$ROOT/dist/android/khandaq-debug.apk" 2>&1 | tail -1
adb shell am start -n org.khandaq.messenger/com.zoffcc.applications.trifa.StartMainActivityWrapper
sleep 4
# Tap SKIP (approx coords for Pixel 6a 1080x2400)
adb shell input tap 820 1180
sleep 15
adb exec-out screencap -p > "$SS/c3-after-skip.png"
adb logcat -d -v time > "$SS/c3-logcat.txt"
adb shell pidof org.khandaq.messenger | tee "$SS/c3-pid.txt"
grep -iE 'get_my_toxid|tox_self|TrifaToxService|bootstrap|FATAL EXCEPTION' "$SS/c3-logcat.txt" | tail -30 | tee "$SS/c3-tox-grep.txt"
