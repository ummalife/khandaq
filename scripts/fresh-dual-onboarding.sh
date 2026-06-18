#!/usr/bin/env bash
# Full uninstall + install + onboarding on one or more Android devices.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PKG="org.khandaq.messenger"
APK="${APK:-$ROOT/khandaq-android-trifa/android-refimpl-app/app/build/outputs/apk/debug/app-debug.apk}"
DEVICES=("$@")
if ((${#DEVICES[@]} == 0)); then
  DEVICES=(emulator-5554 emulator-5556)
fi

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export PATH="$ANDROID_HOME/platform-tools:$PATH"

log() { echo "[$(date +%H:%M:%S)] $*"; }

adb_dev() { adb -s "$1" "${@:2}"; }

wipe_and_install() {
  local dev="$1"
  log "[$dev] force-stop + uninstall"
  adb_dev "$dev" shell am force-stop "$PKG" 2>/dev/null || true
  adb_dev "$dev" uninstall "$PKG" 2>/dev/null || true
  # leftover prefs/db dirs (uninstall usually enough; belt-and-suspenders)
  adb_dev "$dev" shell rm -rf "/data/data/$PKG" 2>/dev/null || true
  log "[$dev] install APK"
  if ! adb_dev "$dev" install -r "$APK"; then
    log "[$dev] install failed"
    return 1
  fi
}

onboarding() {
  local dev="$1"
  log "[$dev] launch app"
  adb_dev "$dev" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  sleep 3
  python3 - "$dev" <<'PY'
import re, subprocess, sys, time

dev = sys.argv[1]

def dump():
    subprocess.run(['adb', '-s', dev, 'shell', 'uiautomator', 'dump', '/sdcard/ui.xml'],
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return subprocess.check_output(['adb', '-s', dev, 'shell', 'cat', '/sdcard/ui.xml']).decode('utf-8', 'replace')

def tap_text(needle):
    xml = dump()
    for pat in [
        rf'text="{re.escape(needle)}"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',
        rf'content-desc="{re.escape(needle)}"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',
    ]:
        m = re.search(pat, xml, re.I)
        if m:
            x1, y1, x2, y2 = map(int, m.groups())
            subprocess.run(['adb', '-s', dev, 'shell', 'input', 'tap',
                            str((x1 + x2) // 2), str((y1 + y2) // 2)])
            return True
    return False

def tap_skip():
    xml = dump()
    m = re.search(r'resource-id="[^"]*skip_button[^"]*"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
    if m:
        x1, y1, x2, y2 = map(int, m.groups())
        subprocess.run(['adb', '-s', dev, 'shell', 'input', 'tap',
                        str((x1 + x2) // 2), str((y1 + y2) // 2)])
        return True
    for label in ['skip', 'SKIP', 'Skip', 'Пропустить']:
        if tap_text(label):
            return True
    return False

labels = [
    'NO', 'No', 'no',
    'skip', 'SKIP', 'Continue', 'Allow', 'While using the app', 'Only this time',
    'Next', 'Done', 'OK', 'GOT IT', 'Accept', 'Agree', 'Not now', 'Later',
]
for step in range(40):
    xml = dump()
    if 'More options' in xml or 'main_profile_bar' in xml or ('text="Khandaq"' in xml and 'Search' in xml):
        print(f'READY step={step}')
        sys.exit(0)
    if 'Batteryoptimization' in xml or 'Battery optimization' in xml:
        if tap_text('NO') or tap_text('No'):
            print(f'battery_opt_no step={step}')
            time.sleep(2)
            continue
    if 'Set Password' in xml or 'set_password' in xml or 'Password again' in xml or 'Password' in xml:
        if tap_skip():
            print(f'skip_password step={step}')
            time.sleep(4)
            continue
    tapped = False
    for label in labels:
        if label.lower() in xml.lower() and tap_text(label):
            print(f'tap {label} step={step}')
            time.sleep(2)
            tapped = True
            break
    if tapped:
        continue
    if 'EditText' in xml and 'Password' in xml:
        if tap_skip():
            print(f'skip_via_button step={step}')
            time.sleep(4)
            continue
    time.sleep(1.5)

print('TIMEOUT')
sys.exit(1)
PY
}

wait_tox() {
  local dev="$1" timeout="${2:-180}"
  log "[$dev] wait tox online (max ${timeout}s)"
  for ((i=0; i<timeout; i+=5)); do
    if adb_dev "$dev" logcat -d -t 600 2>/dev/null | rg -q 'is_tox_started=true|tox_thread_start_fg'; then
      log "[$dev] tox ready t=${i}s"
      return 0
    fi
    if (( i > 0 && i % 30 == 0 )); then
      adb_dev "$dev" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
    fi
    sleep 5
  done
  log "[$dev] WARN tox not confirmed"
  return 1
}

for dev in "${DEVICES[@]}"; do
  if ! adb_dev "$dev" get-state >/dev/null 2>&1; then
    log "ERROR: $dev offline"
    exit 1
  fi
done

for dev in "${DEVICES[@]}"; do
  wipe_and_install "$dev" || exit 1
done

for dev in "${DEVICES[@]}"; do
  onboarding "$dev" || exit 2
  adb_dev "$dev" logcat -c 2>/dev/null || true
  wait_tox "$dev" 180 || true
done

log "Done — both devices fresh + onboarded"
