#!/usr/bin/env bash
# Live QA: Tox profile export/import on a connected Android device (Khandaq/TRIfA).
set -euo pipefail

PKG="com.khandaq.messenger"
ACTIVITY="$PKG/com.zoffcc.applications.trifa.StartMainActivityWrapper"
MAINT="$PKG/com.zoffcc.applications.trifa.MaintenanceActivity"
EXPORT_DIR="/sdcard/Android/data/$PKG/files/vfs_export"
EXPORT_FILE="$EXPORT_DIR/unsecure_export_savedata.tox"
SAVEDATA_REMOTE="files/savedata.tox"

PASS=0
FAIL=0
WARN=0

log() { printf '[QA] %s\n' "$*"; }
pass() { PASS=$((PASS + 1)); log "PASS: $*"; }
fail() { FAIL=$((FAIL + 1)); log "FAIL: $*"; }
warn() { WARN=$((WARN + 1)); log "WARN: $*"; }

adb_check() {
  if ! adb devices | awk 'NR>1 && $2=="device"{found=1} END{exit !found}'; then
    echo "No adb device connected" >&2
    exit 1
  fi
}

tap_text() {
  local text="$1"
  adb shell uiautomator dump /sdcard/qa_uidump.xml >/dev/null 2>&1
  local bounds
  bounds=$(adb shell cat /sdcard/qa_uidump.xml | tr '>' '\n' | grep "text=\"$text\"" | head -1 | sed -n 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\].*/\1 \2 \3 \4/p')
  if [[ -z "$bounds" ]]; then
    return 1
  fi
  local x1 y1 x2 y2 cx cy
  read -r x1 y1 x2 y2 <<<"$bounds"
  cx=$(( (x1 + x2) / 2 ))
  cy=$(( (y1 + y2) / 2 ))
  adb shell input tap "$cx" "$cy"
  return 0
}

tap_contains() {
  local needle="$1"
  adb shell uiautomator dump /sdcard/qa_uidump.xml >/dev/null 2>&1
  local line bounds
  line=$(adb shell cat /sdcard/qa_uidump.xml | tr '>' '\n' | grep "$needle" | head -1)
  bounds=$(echo "$line" | sed -n 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\].*/\1 \2 \3 \4/p')
  if [[ -z "$bounds" ]]; then
    return 1
  fi
  local x1 y1 x2 y2 cx cy
  read -r x1 y1 x2 y2 <<<"$bounds"
  cx=$(( (x1 + x2) / 2 ))
  cy=$(( (y1 + y2) / 2 ))
  adb shell input tap "$cx" "$cy"
  return 0
}

dialog_yes() {
  sleep 1
  tap_text "Yes, I want to export" || tap_text "Да, я хочу экспортировать" || \
    tap_contains 'button1' || tap_text "Choose file" || tap_text "Выбрать файл" || \
    tap_text "Yes, I want to wipe all data and import" || true
}

file_size() {
  adb shell "stat -c %s '$1' 2>/dev/null || wc -c < '$1' 2>/dev/null" | tr -d '\r'
}

savedata_size() {
  adb shell "run-as $PKG stat -c %s $SAVEDATA_REMOTE 2>/dev/null" | tr -d '\r'
}

launch_app() {
  adb shell am force-stop "$PKG" || true
  sleep 1
  adb shell am start -n "$ACTIVITY" >/dev/null
  sleep 5
}

open_settings_tab() {
  # Bottom nav: Contacts, Chats, Settings, Profile — Settings is 3rd tab (~75% width)
  adb shell wm size | grep -q Physical || true
  tap_contains 'content-desc="Настройки"' && return 0
  tap_contains 'content-desc="Settings"' && return 0
  # fallback coordinate for 1080x2400 class devices
  adb shell input tap 810 2120
  sleep 2
}

scroll_settings_list() {
  adb shell input swipe 540 1800 540 900 350
  sleep 1
}

qa_export_via_settings_footer() {
  log "=== QA-1: Export via Settings → Backup → Export ==="
  launch_app
  open_settings_tab
  scroll_settings_list

  if ! tap_text "Экспорт" && ! tap_text "Export"; then
    fail "Could not find Export button in Settings backup footer"
    return 1
  fi
  sleep 1
  dialog_yes
  sleep 3

  local size before after
  size=$(file_size "$EXPORT_FILE" || echo 0)
  if [[ "${size:-0}" -gt 100 ]]; then
    pass "Export file exists ($EXPORT_FILE, ${size} bytes)"
  else
    fail "Export file missing or too small (${size:-0} bytes)"
    return 1
  fi

  before=$(savedata_size || echo 0)
  sleep 1
  # trigger export again and compare mtime/size stable
  tap_text "Экспорт" || tap_text "Export" || true
  dialog_yes
  sleep 2
  after=$(file_size "$EXPORT_FILE" || echo 0)
  if [[ "${after:-0}" -ge "${before:-0}" ]] && [[ "${after:-0}" -gt 100 ]]; then
    pass "Re-export updated or preserved file (${after} bytes)"
  else
    warn "Re-export size unexpected before=${before} after=${after}"
  fi
}

qa_export_via_maintenance() {
  log "=== QA-2: Export via Maintenance (legacy path) ==="
  adb shell am start -n "$MAINT" >/dev/null
  sleep 3
  # scroll maintenance to export button
  adb shell input swipe 540 1900 540 400 400
  sleep 1
  tap_contains 'button_export_savedata' || tap_text "export tox savedata" || tap_text "экспортировать" || true
  sleep 1
  dialog_yes
  sleep 3
  local size
  size=$(file_size "$EXPORT_FILE" || echo 0)
  if [[ "${size:-0}" -gt 100 ]]; then
    pass "Maintenance export file OK (${size} bytes)"
  else
    fail "Maintenance export failed"
  fi
  adb shell input keyevent KEYCODE_BACK
  sleep 1
}

qa_import_file_staging() {
  log "=== QA-3: Import staging file (legacy I_WANT_TO_IMPORT path via Maintenance) ==="
  local export_size
  export_size=$(file_size "$EXPORT_FILE" || echo 0)
  if [[ "${export_size:-0}" -lt 100 ]]; then
    warn "Skip import QA — no export file to copy"
    return 0
  fi

  adb shell "mkdir -p '$EXPORT_DIR'" >/dev/null 2>&1 || true
  adb shell "cp '$EXPORT_FILE' '$EXPORT_DIR/I_WANT_TO_IMPORT_savedata.tox'" >/dev/null 2>&1 || true

  local staged
  staged=$(file_size "$EXPORT_DIR/I_WANT_TO_IMPORT_savedata.tox" || echo 0)
  if [[ "${staged:-0}" -lt 100 ]]; then
    fail "Could not stage import file on device"
    return 1
  fi
  pass "Staged import file (${staged} bytes)"

  # Note: current UI import uses SAF picker — Maintenance import button opens picker, not legacy file.
  # Verify picker flow opens (cannot fully automate SAF without user).
  adb shell am start -n "$MAINT" >/dev/null
  sleep 2
  adb shell input swipe 540 1900 540 300 500
  sleep 1
  tap_contains 'button_import_savedata' || tap_text "IM-port" || tap_text "Импорт" || true
  sleep 2
  if tap_text "Choose file" || tap_text "Выбрать файл"; then
    pass "Import dialog shows file picker (SAF) — manual pick required for full round-trip"
    adb shell input keyevent KEYCODE_BACK
  else
    warn "Import dialog/picker not confirmed via UI dump — may need scroll or different locale"
    adb shell input keyevent KEYCODE_BACK
  fi
}

qa_first_launch_import_button() {
  log "=== QA-4: First-launch import entry (requires cleared app data — skipped destructive) ==="
  warn "Skipped destructive test (clear app data). SetPassword import button exists in layout — verify manually after clear-data."
}

print_summary() {
  log "========================================"
  log "Live QA summary: PASS=$PASS FAIL=$FAIL WARN=$WARN"
  if [[ "$FAIL" -gt 0 ]]; then
    exit 1
  fi
}

main() {
  adb_check
  log "Device: $(adb shell getprop ro.product.model | tr -d '\r')"
  log "App: $(adb shell dumpsys package $PKG | grep versionName | head -1 | tr -d '\r')"

  qa_export_via_settings_footer || true
  qa_export_via_maintenance || true
  qa_import_file_staging || true
  qa_first_launch_import_button || true
  print_summary
}

main "$@"
