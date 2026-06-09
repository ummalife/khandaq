#!/usr/bin/env bash
# Khandaq Android Smoke Test C1+C2 (emulator)
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="$ROOT/dist/android/khandaq-debug.apk"
SS="$ROOT/docs/smoke-test-screenshots"
LOG="$ROOT/docs/smoke-test-logcat.txt"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"

mkdir -p "$SS"

boot_emulator() {
  if adb devices 2>/dev/null | grep -qE 'emulator-[0-9]+\s+device'; then
    return 0
  fi
  nohup "$ANDROID_HOME/emulator/emulator" \
    -avd Pixel_6a -wipe-data -partition-size 8192 \
    -no-snapshot-save -no-audio -no-boot-anim \
    -gpu swiftshader_indirect >> /tmp/khandaq-smoke-emu.log 2>&1 &
  for _ in $(seq 1 90); do
    adb devices 2>/dev/null | grep -qE 'emulator-[0-9]+\s+device' && break
    sleep 2
  done
  for _ in $(seq 1 50); do
    [[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]] && return 0
    sleep 2
  done
  return 1
}

boot_emulator
adb devices -l | tee "$SS/c1-devices.log"

adb install -r "$APK" 2>&1 | tee "$SS/c1-install.log"
adb logcat -c
adb shell monkey -p org.khandaq.messenger -c android.intent.category.LAUNCHER 1 2>&1 | tee "$SS/c1-monkey.log"
sleep 6
adb shell pidof org.khandaq.messenger 2>&1 | tee "$SS/c1-pid.txt" || true
adb exec-out screencap -p > "$SS/c1-launch.png" || true
adb logcat -d -v time > "$SS/full-logcat.txt"
rg -i -E 'khandaq|trifa|tox|jni|crash|fatal|exception|UnsatisfiedLink|ClassNotFound|Provider|jni-c-toxcore|TrifaToxService|AndroidRuntime' \
  "$SS/full-logcat.txt" > "$LOG" || true

{
  echo "pid=$(cat "$SS/c1-pid.txt" 2>/dev/null || echo none)"
  echo "filtered_lines=$(wc -l < "$LOG" 2>/dev/null || echo 0)"
  echo "=== FATAL ==="
  rg -i 'FATAL EXCEPTION|UnsatisfiedLinkError|ClassNotFoundException' "$LOG" 2>/dev/null | head -20 || true
  echo "=== JNI ==="
  rg -i 'jni-c-toxcore|loadLibrary|successfully loaded|TrifaToxService' "$LOG" 2>/dev/null | head -25 || true
} | tee "$SS/c2-summary.txt"

echo "Smoke test artifacts in $SS"
