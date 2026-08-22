#!/usr/bin/env bash
# Release audit QA: NGC public groups on 2+ Android devices/emulators.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PKG="com.khandaq.messenger"
APK="${APK:-$ROOT/dist/android/khandaq-release.apk}"
OUT="${OUT:-$ROOT/docs/qa-release-audit-$(date +%Y%m%d-%H%M%S)}"
CREATOR="${CREATOR:-emulator-5554}"
JOINER="${JOINER:-emulator-5556}"
THIRD="${THIRD:-}"
WAIT_SYNC="${WAIT_SYNC:-150}"
TS="$(date +%s)"
PUB_NAME="QA-AUDIT-PUBLIC-$TS"

mkdir -p "$OUT"
log() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$OUT/summary.txt"; }
adb_dev() { adb -s "$1" "${@:2}"; }

require_device() {
  local dev="$1"
  if ! adb_dev "$dev" get-state 2>/dev/null | grep -q device; then
    log "ERROR: $dev not connected"
    exit 1
  fi
}

install_fresh() {
  local dev="$1"
  log "[$dev] fresh install"
  adb_dev "$dev" shell am force-stop "$PKG" 2>/dev/null || true
  adb_dev "$dev" install -r "$APK" >>"$OUT/install-$dev.log" 2>&1
  adb_dev "$dev" logcat -c
  adb_dev "$dev" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >>"$OUT/launch-$dev.log" 2>&1
  sleep 5
}

onboarding() {
  local dev="$1"
  log "[$dev] onboarding"
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
 for l in ['SKIP','Continue','Allow','While using the app','Next','Done','OK','GOT IT']:
  if l in xml and tap(l): time.sleep(2); break
 else:
  if 'EditText' in xml:
   subprocess.run(['adb','-s',dev,'shell','input','tap','540','600'])
   subprocess.run(['adb','-s',dev,'shell','input','text','QAUser'])
   subprocess.run(['adb','-s',dev,'shell','input','keyevent','66']); time.sleep(2)
  else: time.sleep(1)
PY
}

ui_dump() {
  local dev="$1" tag="$2"
  adb_dev "$dev" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || true
  adb_dev "$dev" shell cat /sdcard/ui.xml > "$OUT/ui-$dev-$tag.xml" 2>/dev/null || true
  adb_dev "$dev" exec-out screencap -p > "$OUT/screen-$dev-$tag.png" 2>/dev/null || true
  cp "$OUT/ui-$dev-$tag.xml" "$OUT/ui-$dev-latest.xml" 2>/dev/null || true
}

tap_xy() { adb_dev "$1" shell input tap "$2" "$3"; }

tap_text() {
  local dev="$1" text="$2"
  python3 - "$dev" "$text" "$OUT" <<'PY'
import re, sys
dev, needle, out = sys.argv[1:4]
try:
    xml = open(f"{out}/ui-{dev}-latest.xml", encoding='utf-8', errors='replace').read()
except FileNotFoundError:
    sys.exit(1)
for pat in [
    rf'text="{re.escape(needle)}"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',
    rf'content-desc="{re.escape(needle)}"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',
]:
    m = re.search(pat, xml)
    if m:
        x1,y1,x2,y2 = map(int, m.groups())
        print((x1+x2)//2, (y1+y2)//2)
        sys.exit(0)
sys.exit(1)
PY
}

open_menu() {
  local dev="$1"
  ui_dump "$dev" "menu"
  if coords=$(tap_text "$dev" "More options" 2>/dev/null); then
    read -r x y <<< "$coords"; tap_xy "$dev" "$x" "$y"
  else
    tap_xy "$dev" 1020 180
  fi
  sleep 1
}

create_public_group() {
  local dev="$1" name="$2"
  adb_dev "$dev" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  sleep 2
  open_menu "$dev"
  ui_dump "$dev" "menu-open"
  local tapped=0
  for label in "Create new Public Group" "Создать новую открытую группу"; do
    if coords=$(tap_text "$dev" "$label" 2>/dev/null); then
      read -r x y <<< "$coords"; tap_xy "$dev" "$x" "$y"; tapped=1; sleep 2; break
    fi
  done
  if [[ "$tapped" -eq 0 ]]; then
    tap_xy "$dev" 792 331
    sleep 2
  fi
  ui_dump "$dev" "pub-before"
  tap_xy "$dev" 540 450; sleep 0.5
  adb_dev "$dev" shell input text "$name"; sleep 1
  ui_dump "$dev" "pub-filled"
  for label in "Create Group" "CREATE GROUP" "Создать группу" "Add" "Добавить"; do
    if coords=$(tap_text "$dev" "$label" 2>/dev/null); then
      read -r x y <<< "$coords"; tap_xy "$dev" "$x" "$y"; log "[$dev] tapped '$label'"; sleep 4; return 0
    fi
  done
  tap_xy "$dev" 810 2200; sleep 4
}

join_public_group() {
  local dev="$1" gid="$2"
  open_menu "$dev"
  ui_dump "$dev" "menu-join"
  for label in "Join a Public Group" "Присоединиться к открытой группе"; do
    if coords=$(tap_text "$dev" "$label" 2>/dev/null); then
      read -r x y <<< "$coords"; tap_xy "$dev" "$x" "$y"; sleep 2; break
    fi
  done
  tap_xy "$dev" 540 450; sleep 0.5
  adb_dev "$dev" shell input text "$gid"; sleep 1
  for label in "Join Group" "JOIN GROUP" "Присоединиться"; do
    if coords=$(tap_text "$dev" "$label" 2>/dev/null); then
      read -r x y <<< "$coords"; tap_xy "$dev" "$x" "$y"; sleep 5; return 0
    fi
  done
  tap_xy "$dev" 810 2200; sleep 5
}

open_group_by_name() {
  local dev="$1" name="$2"
  ui_dump "$dev" "list"
  if coords=$(tap_text "$dev" "$name" 2>/dev/null); then
    read -r x y <<< "$coords"; tap_xy "$dev" "$x" "$y"; sleep 2; return 0
  fi
  tap_xy "$dev" 540 700; sleep 2
}

send_message() {
  local dev="$1" msg="$2"
  tap_xy "$dev" 400 2150; sleep 0.3
  adb_dev "$dev" shell input text "$msg"; sleep 0.3
  adb_dev "$dev" shell input keyevent 66; sleep 2
}

wait_two_members() {
  local dev="$1" timeout="${2:-120}"
  local i=0
  while (( i < timeout )); do
    if adb_dev "$dev" logcat -d 2>/dev/null | rg -q "2 members|peers=2|peer_count=2"; then
      return 0
    fi
    if adb_dev "$dev" shell dumpsys activity activities 2>/dev/null | rg -q "2 members · 2 online"; then
      return 0
    fi
    sleep 5; ((i+=5))
  done
  return 1
}

wifi_flap_test() {
  local dev="$1"
  log "[$dev] WiFi flap test"
  adb_dev "$dev" shell svc wifi disable 2>/dev/null || true
  sleep 8
  adb_dev "$dev" shell svc wifi enable 2>/dev/null || true
  sleep 20
  adb_dev "$dev" logcat -d 2>/dev/null | rg -i "group_connection_status|schedule_group_auto_reconnect|reconnect_group" | tail -15 \
    > "$OUT/wifi-flap-$dev.log" || true
}

# --- main ---
log "RELEASE AUDIT OUT=$OUT"
log "APK=$(shasum -a 256 "$APK" 2>/dev/null | awk '{print $1}')"
require_device "$CREATOR"
require_device "$JOINER"

for dev in "$CREATOR" "$JOINER"; do install_fresh "$dev"; onboarding "$dev"; done
sleep 10

create_public_group "$CREATOR" "$PUB_NAME" || { ui_dump "$CREATOR" "create-fail"; log "FAIL: create_public_group UI"; exit 2; }
PUB_ID="$(adb_dev "$CREATOR" logcat -d 2>/dev/null | rg 'create_new_group:ok num=.* id=([0-9a-fA-F]{64})' -o --replace '$1' | tail -1)"
log "GROUP id=$PUB_ID name=$PUB_NAME"
[[ ${#PUB_ID} -eq 64 ]] || { log "FAIL: no group id"; exit 2; }

join_public_group "$JOINER" "$PUB_ID"
log "wait mesh ${WAIT_SYNC}s"
sleep "$WAIT_SYNC"

wait_two_members "$CREATOR" 60 || log "WARN: creator 2 members not confirmed"
wait_two_members "$JOINER" 60 || log "WARN: joiner 2 members not confirmed"

MSG_A="audit-creator-$TS"
MSG_B="audit-joiner-$TS"
open_group_by_name "$CREATOR" "$PUB_NAME"
send_message "$CREATOR" "$MSG_A"
sleep 10
open_group_by_name "$JOINER" "$PUB_NAME"
send_message "$JOINER" "$MSG_B"
sleep 30

wifi_flap_test "$JOINER"

adb_dev "$CREATOR" logcat -d > "$OUT/logcat-creator.txt"
adb_dev "$JOINER" logcat -d > "$OUT/logcat-joiner.txt"

python3 - "$OUT" "$PUB_ID" "$MSG_A" "$MSG_B" <<'PY'
import pathlib, re, sys
out, pub_id, msg_a, msg_b = sys.argv[1:5]
c = pathlib.Path(f"{out}/logcat-creator.txt").read_text(errors='replace')
j = pathlib.Path(f"{out}/logcat-joiner.txt").read_text(errors='replace')

def m(text, needle):
    return needle in text

peer_sync = 'group_peer_join' in c and 'group_peer_join' in j
creator_recv = m(j, msg_a) and ('group_message_cb:recv' in c or msg_a in c)
joiner_recv = m(c, msg_b) and ('group_message_cb:recv' in j or msg_b in j)
auto_reconnect = 'schedule_group_auto_reconnect' in c or 'schedule_group_auto_reconnect' in j
delivery_ack = 'group_delivery_ack' in c or 'group_delivery_ack' in j

lines = [
    f"PUBLIC_ID={pub_id}",
    f"peer_sync={peer_sync}",
    f"creator_got_joiner_msg={creator_recv}",
    f"joiner_got_creator_msg={joiner_recv}",
    f"msg_cross={creator_recv and joiner_recv}",
    f"auto_reconnect_seen={auto_reconnect}",
    f"delivery_ack_seen={delivery_ack}",
]
pathlib.Path(f"{out}/verdict.txt").write_text("\n".join(lines) + "\n")
print("\n".join(lines))
PY

if [[ -n "$THIRD" ]] && adb_dev "$THIRD" get-state 2>/dev/null | grep -q device; then
  log "THIRD device $THIRD — install + join"
  install_fresh "$THIRD"
  join_public_group "$THIRD" "$PUB_ID"
  sleep 60
  open_group_by_name "$THIRD" "$PUB_NAME"
  send_message "$THIRD" "audit-third-$TS"
  adb_dev "$THIRD" logcat -d > "$OUT/logcat-third.txt"
else
  log "THIRD device not set/online — skip 3-peer test (set THIRD=serial)"
fi

log "Done. Artifacts: $OUT"
