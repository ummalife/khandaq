#!/usr/bin/env bash
# Capture Khandaq UI screenshots for design handoff (Android emulator + macOS desktop).
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/docs/design-handoff/screenshots"
APK="${KHANDAQ_SCREENSHOT_APK:-$ROOT/khandaq-android-trifa/android-refimpl-app/app/build/outputs/apk/debug/app-debug.apk}"
PKG="org.khandaq.messenger"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
AVD="${KHANDAQ_SCREENSHOT_AVD:-Khandaq_medium}"
EMU_LOG="/tmp/khandaq-screenshot-emu.log"

mkdir -p "$OUT/android" "$OUT/desktop" "$OUT/ios" "$OUT/meta"

log() { echo "[capture] $*"; }

boot_emulator() {
  if adb devices 2>/dev/null | grep -qE '(emulator-[0-9]+|[0-9a-f]+)\s+device'; then
    log "device already connected"
    return 0
  fi
  log "starting emulator AVD=$AVD ..."
  nohup "$ANDROID_HOME/emulator/emulator" \
    -avd "$AVD" \
    -no-snapshot-save -no-audio -no-boot-anim \
    -gpu swiftshader_indirect \
    >> "$EMU_LOG" 2>&1 &
  for _ in $(seq 1 120); do
    adb devices 2>/dev/null | grep -qE 'emulator-[0-9]+\s+device' && break
    sleep 2
  done
  for _ in $(seq 1 60); do
    [[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]] && return 0
    sleep 2
  done
  log "ERROR: emulator boot timeout"
  return 1
}

android_shot() {
  local name="$1"
  shift
  adb shell am force-stop "$PKG" 2>/dev/null || true
  sleep 0.4
  adb shell "$@" 2>/dev/null || true
  sleep 2.5
  adb exec-out screencap -p > "$OUT/android/${name}.png" || true
  log "android: $name"
}

grant_perms() {
  local perms=(
    android.permission.POST_NOTIFICATIONS
    android.permission.CAMERA
    android.permission.RECORD_AUDIO
    android.permission.READ_EXTERNAL_STORAGE
    android.permission.WRITE_EXTERNAL_STORAGE
  )
  for p in "${perms[@]}"; do
    adb shell pm grant "$PKG" "$p" 2>/dev/null || true
  done
}

capture_android() {
  [[ -f "$APK" ]] || { log "missing APK: $APK"; return 1; }
  boot_emulator || return 1
  adb devices -l | tee "$OUT/meta/android-devices.log"
  adb install -r "$APK" 2>&1 | tee "$OUT/meta/android-install.log"
  grant_perms

  # Launcher / onboarding / main
  adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
  sleep 5
  adb exec-out screencap -p > "$OUT/android/00-launcher-flow.png"

  local activities=(
    "01-main|MainActivity"
    "02-set-password|SetPasswordActivity"
    "03-check-password|CheckPasswordActivity"
    "04-wrong-credentials|WrongCredentials"
    "05-add-friend|AddFriendActivity"
    "06-qr-scan|QrScanActivity"
    "07-profile|ProfileActivity"
    "08-settings|SettingsActivity"
    "09-select-language|SelectLanguageActivity"
    "10-network-diagnostics|NetworkDiagnosticsActivity"
    "11-maintenance|MaintenanceActivity"
    "12-export|ExportActivity"
    "13-about|Aboutpage"
    "14-io-browser|IOBrowser"
    "15-add-private-group|AddPrivateGroupActivity"
    "16-add-public-group|AddPublicGroupActivity"
    "17-join-public-group|JoinPublicGroupActivity"
    "18-friend-info|FriendInfoActivity"
    "19-friend-select|FriendSelectSingleActivity"
    "20-message-list|MessageListActivity"
    "21-group-message-list|GroupMessageListActivity"
    "22-group-info|GroupInfoActivity"
    "23-group-peer-info|GroupPeerInfoActivity"
    "24-conference-message-list|ConferenceMessageListActivity"
    "25-conference-info|ConferenceInfoActivity"
    "26-conference-peer-info|ConferencePeerInfoActivity"
    "27-conference-audio|ConferenceAudioActivity"
    "28-calling|CallingActivity"
    "29-calling-waiting|CallingWaitingActivity"
    "30-media-viewer|MediaViewerActivity"
    "31-media-send-preview|MediaSendPreviewActivity"
    "32-image-viewer|ImageviewerActivity"
    "33-crash|CrashActivity"
    "34-audio-roundtrip|AudioRoundtripActivity"
    "35-conf-audio-player|ConfGroupAudioPlayer"
  )

  for entry in "${activities[@]}"; do
    local file="${entry%%|*}"
    local act="${entry##*|}"
    android_shot "$file" am start -n "$PKG/com.zoffcc.applications.trifa.$act"
  done

  # Activities that need extras (shell layout even if empty data)
  android_shot "36-friend-info-extra" am start -n "$PKG/com.zoffcc.applications.trifa.FriendInfoActivity" --el friendnum 0
  android_shot "37-message-list-extra" am start -n "$PKG/com.zoffcc.applications.trifa.MessageListActivity" --el friendnum 0
  android_shot "38-group-chat-extra" am start -n "$PKG/com.zoffcc.applications.trifa.GroupMessageListActivity" --es group_id "0000000000000000000000000000000000000000000000000000000000000000"

  log "android done: $(ls -1 "$OUT/android"/*.png 2>/dev/null | wc -l | tr -d ' ') files"
}

capture_desktop_macos() {
  local app="$ROOT/dist/macos/khandaq.app"
  [[ -d "$app" ]] || { log "skip desktop: no $app"; return 0; }

  log "launching macOS Khandaq ..."
  open -a "$app" 2>/dev/null || open "$app"
  sleep 8

  # Full screen capture (app should be visible)
  screencapture -x "$OUT/desktop/01-app-launched.png" 2>/dev/null || true

  # Try window capture via AppleScript
  local wid
  wid=$(osascript <<'APPLESCRIPT' 2>/dev/null || true
tell application "System Events"
  tell process "khandaq"
    if (count of windows) > 0 then
      return id of front window
    end if
  end tell
end tell
return ""
APPLESCRIPT
)
  if [[ -n "$wid" ]]; then
    screencapture -l "$wid" -x "$OUT/desktop/02-main-window.png" 2>/dev/null || true
  fi

  log "desktop done: $(ls -1 "$OUT/desktop"/*.png 2>/dev/null | wc -l | tr -d ' ') files"
}

capture_ios_note() {
  cat > "$OUT/ios/README.txt" <<'EOF'
iOS (Antidote) screenshots require building for Simulator:
  cd khandaq-ios && xcodebuild -scheme Antidote -destination 'platform=iOS Simulator,name=iPhone 15 Pro' build
Then run UI tests: khandaq-ios/ScreenshotsUITests/ScreenshotsUITests.swift

Reference images already in repo:
  khandaq-ios/docs/app001.png .. app004.png
  khandaq-ios/AntidoteTests/ReferenceImages_64/
EOF
}

write_index() {
  cat > "$OUT/README.md" <<EOF
# Khandaq Design Handoff Screenshots

Generated: $(date -u +"%Y-%m-%d %H:%M UTC")

## Android (\`android/\`)
Activity launches via \`adb am start\`. Some screens may show empty/error state without test data — layout/chrome still valid for redesign.

APK: \`$APK\`
AVD: \`$AVD\`

## Desktop macOS (\`desktop/\`)
Qt Khandaq from \`dist/macos/khandaq.app\`. Manual navigation screenshots may be needed for settings/chat panels.

## iOS
See \`ios/README.txt\` — build simulator app separately.

## Designer TZ
See conversation / \`docs/DESIGN_HANDOFF_TZ.md\` if created.
EOF
}

main() {
  log "output -> $OUT"
  capture_android || log "android capture had errors"
  capture_desktop_macos || log "desktop capture had errors"
  capture_ios_note
  write_index
  log "all done"
}

main "$@"
