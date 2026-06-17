#!/usr/bin/env bash
# Live QA: create public + private NGC groups on one Android device/emulator,
# verify tox online + per-group connection status (logcat + UI subtitle).
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PKG="org.khandaq.messenger"
DEV="${DEV:-emulator-5554}"
APK="${APK:-$ROOT/khandaq-android-trifa/android-refimpl-app/app/build/outputs/apk/debug/app-debug.apk}"
OUT="${OUT:-$ROOT/docs/qa-groups-online-$(date +%Y%m%d-%H%M%S)}"
TS="$(date +%s)"
PUB_NAME="QA-PUB-$TS"
PRIV_NAME="QA-PRIV-$TS"
WAIT_GROUPS="${WAIT_GROUPS:-45}"
TOX_WAIT="${TOX_WAIT:-90}"

mkdir -p "$OUT"
SUMMARY="$OUT/summary.txt"
: > "$SUMMARY"

log() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$SUMMARY"; }

adb_dev() { adb -s "$DEV" "${@:2}"; }

ui_dump() {
  local tag="$1"
  adb_dev "$DEV" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || true
  adb_dev "$DEV" shell cat /sdcard/ui.xml > "$OUT/ui-$tag.xml" 2>/dev/null || true
  cp "$OUT/ui-$tag.xml" "$OUT/ui-latest.xml" 2>/dev/null || true
  adb_dev "$DEV" exec-out screencap -p > "$OUT/screen-$tag.png" 2>/dev/null || true
}

tap_text() {
  local text="$1"
  python3 - "$text" "$OUT" <<'PY'
import re, sys
needle, out = sys.argv[1:3]
try:
    xml = open(f"{out}/ui-latest.xml", encoding='utf-8', errors='replace').read()
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

tap_xy() { adb_dev "$DEV" shell input tap "$1" "$2"; }

open_main_menu() {
  ui_dump "menu-before"
  if coords=$(tap_text "More options" 2>/dev/null); then
    read -r x y <<< "$coords"
  else
    x=1020; y=180
  fi
  tap_xy "$x" "$y"
  sleep 1
}

menu_tap() {
  local -a labels=("$@")
  open_main_menu
  ui_dump "menu-open"
  for label in "${labels[@]}"; do
    if coords=$(tap_text "$label" 2>/dev/null); then
      read -r x y <<< "$coords"
      tap_xy "$x" "$y"
      sleep 2
      return 0
    fi
  done
  return 1
}

create_group() {
  local kind="$1" name="$2" tag="$3"
  log "create $kind group: $name"
  if [[ "$kind" == "public" ]]; then
    menu_tap "Create new Public Group" "Создать новую открытую группу" "Create Public Group" \
      || { log "FAIL: menu public"; return 1; }
  else
    menu_tap "Create new Private Group" "Создать новую частную группу" \
      || { log "FAIL: menu private"; return 1; }
  fi
  sleep 1
  ui_dump "$tag-before"
  tap_xy 540 450
  sleep 0.5
  adb_dev "$DEV" shell input text "$name"
  sleep 1
  ui_dump "$tag-filled"
  for label in "Create Group" "CREATE GROUP" "Создать группу" "Add" "Добавить"; do
    if coords=$(tap_text "$label" 2>/dev/null); then
      read -r x y <<< "$coords"
      tap_xy "$x" "$y"
      sleep 4
      return 0
    fi
  done
  tap_xy 810 2200
  sleep 4
  adb_dev "$DEV" shell input keyevent 4
  sleep 1
  adb_dev "$DEV" shell input keyevent 4
  sleep 2
}

wait_tox_online() {
  log "wait tox online (max ${TOX_WAIT}s)"
  local i=0
  while (( i < TOX_WAIT )); do
    if adb_dev "$DEV" logcat -d 2>/dev/null | rg -q "self_connection_status.*(CONNECTED|TCP|UDP)|connection_status.*TOX_CONNECTION"; then
      log "tox online signal in logcat"
      return 0
    fi
    if adb_dev "$DEV" logcat -d 2>/dev/null | rg -qi "bootstrap.*ok|connected to bootstrap"; then
      log "bootstrap ok in logcat"
      return 0
    fi
    sleep 3
    ((i+=3))
  done
  log "WARN: tox online not confirmed"
  return 0
}

extract_group_ids() {
  adb_dev "$DEV" logcat -d 2>/dev/null | rg 'create_new_group:ok num=.* id=([0-9a-fA-F]{64})' -o --replace '$1'
}

parse_group_status_from_log() {
  local gid="$1"
  local short="${gid:0:8}"
  adb_dev "$DEV" logcat -d 2>/dev/null | rg -i "${short}|${gid}|group_connection_status|schedule_group_auto_reconnect|maintain_private_group|peers=|conn=" | tail -30
}

ui_subtitle_for_group() {
  local name="$1"
  python3 - "$name" "$OUT" <<'PY'
import re, sys
name, out = sys.argv[1:3]
try:
    xml = open(f"{out}/ui-latest.xml", encoding='utf-8', errors='replace').read()
except FileNotFoundError:
    print("UI: no dump")
    sys.exit(0)
# find row containing group name, then nearby subtitle on next node or same parent area
idx = xml.find(f'text="{name}"')
if idx < 0:
    print(f"UI: group name '{name}' not found")
    sys.exit(0)
chunk = xml[max(0, idx-500):idx+1200]
subs = re.findall(r'text="([^"]{1,120})"', chunk)
# filter: subtitle is not the name itself
candidates = [s for s in subs if s != name and (
    'участник' in s.lower() or 'member' in s.lower() or
    'подключ' in s.lower() or 'connect' in s.lower() or
    'попытка' in s.lower() or 'attempt' in s.lower()
)]
if candidates:
    print("UI subtitle:", candidates[0])
else:
    print("UI: no status subtitle near name (found texts:", subs[:6], ")")
PY
}

# --- main ---
log "=== GROUP ONLINE QA ==="
log "DEV=$DEV OUT=$OUT APK=$APK"

if ! adb_dev "$DEV" get-state >/dev/null 2>&1; then
  log "ERROR: device $DEV not connected"
  exit 1
fi

if [[ -f "$APK" ]]; then
  log "install APK"
  adb_dev "$DEV" install -r "$APK" >>"$OUT/install.log" 2>&1 || log "WARN: install failed"
fi

adb_dev "$DEV" logcat -c
adb_dev "$DEV" shell am force-stop "$PKG" 2>/dev/null || true
adb_dev "$DEV" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >>"$OUT/launch.log" 2>&1
sleep 8
wait_tox_online
sleep 5

create_group "public" "$PUB_NAME" "pub" || exit 2
sleep 3
create_group "private" "$PRIV_NAME" "priv" || exit 2

mapfile -t ALL_IDS < <(extract_group_ids)
PUB_ID="${ALL_IDS[0]:-}"
PRIV_ID="${ALL_IDS[1]:-}"
if [[ ${#ALL_IDS[@]} -ge 2 ]]; then
  PUB_ID="${ALL_IDS[-2]}"
  PRIV_ID="${ALL_IDS[-1]}"
fi

log "PUBLIC id=${PUB_ID:-missing} name=$PUB_NAME"
log "PRIVATE id=${PRIV_ID:-missing} name=$PRIV_NAME"

log "wait ${WAIT_GROUPS}s for group connection settle..."
sleep "$WAIT_GROUPS"

ui_dump "final-list"
ui_subtitle_for_group "$PUB_NAME" | tee -a "$SUMMARY"
ui_subtitle_for_group "$PRIV_NAME" | tee -a "$SUMMARY"

adb_dev "$DEV" logcat -d > "$OUT/logcat-full.txt"

python3 - "$OUT" "$PUB_ID" "$PRIV_ID" "$PUB_NAME" "$PRIV_NAME" <<'PY'
import re, sys, pathlib

out, pub_id, priv_id, pub_name, priv_name = sys.argv[1:6]
text = pathlib.Path(f"{out}/logcat-full.txt").read_text(errors='replace')

def group_conn_verdict(gid, gnum, label):
    if not gid or len(gid) != 64:
        return {"label": label, "online": False, "reason": "no group id"}
    # Lines mentioning this group's tox group number or hex id
    hits = []
    for line in text.splitlines():
        ll = line.lower()
        if gid.lower() in ll or (gnum is not None and re.search(rf'\bgn={gnum}\b', line)):
            hits.append(line)
    conn_vals = []
    peer_vals = []
    for line in hits:
        for m in re.finditer(r'conn=(-?\d+)', line):
            conn_vals.append(m.group(1))
        for m in re.finditer(r'peers=(\d+)', line):
            peer_vals.append(m.group(1))
    status_cb = re.findall(rf'group_connection_status_cb:group_number={gnum} status=(-?\d+)', text) if gnum else []
    last_conn = (status_cb or conn_vals)[-1] if (status_cb or conn_vals) else None
    last_peers = peer_vals[-1] if peer_vals else None
    online = str(last_conn) == '1'
    state = { '-1': 'ERROR/disconnected', '0': 'CONNECTING', '1': 'CONNECTED', '3': 'CONNECTED(legacy)' }.get(str(last_conn), 'unknown')
    if str(last_conn) == '3':
        online = True
    return {
        "label": label,
        "gid": gid,
        "gnum": gnum,
        "online": online,
        "conn": last_conn,
        "state": state,
        "peers": last_peers,
        "reason": f"conn={last_conn} ({state}), peers={last_peers}",
    }

creates = re.findall(r'create_new_group:ok num=(\d+) id=([0-9a-fA-F]{64})', text)
pub_num, priv_num = None, None
if len(creates) >= 1:
    pub_num = creates[0][0]
if len(creates) >= 2:
    priv_num = creates[1][0]

pub = group_conn_verdict(pub_id, pub_num, f"PUBLIC ({pub_name})")
priv = group_conn_verdict(priv_id, priv_num, f"PRIVATE ({priv_name})")

# fallback: last global connection cb
if pub["conn"] is None:
    m = re.findall(r'group_connection_status_cb:group_number=\d+ status=(-?\d+)', text)
    if m:
        pub["conn"] = m[0]
        pub["state"] = { '-1': 'ERROR', '0': 'CONNECTING', '1': 'CONNECTED' }.get(m[0], '?')
        pub["online"] = m[0] == '1'
        pub["reason"] += " (fallback first status_cb)"

lines = []
lines.append(f"PUBLIC_ID={pub_id}")
lines.append(f"PRIVATE_ID={priv_id}")
for g in (pub, priv):
    lines.append(f"{g['label']}: online={g['online']} {g['reason']}")

ui = pathlib.Path(f"{out}/ui-latest.xml")
if ui.exists():
    ux = ui.read_text(errors='replace')
    for name in (pub_name, priv_name):
        if f'text="{name}"' not in ux:
            lines.append(f"UI: {name} NOT in friend list")
        elif 'Подключение' in ux or 'Connecting' in ux:
            if name in ux:
                chunk = ux[ux.find(name):ux.find(name)+800] if name in ux else ''
                if 'попытка' in chunk.lower() or 'attempt' in chunk.lower():
                    lines.append(f"UI: {name} shows CONNECTING spinner text")
                elif 'участник' in chunk.lower() or 'member' in chunk.lower():
                    lines.append(f"UI: {name} shows member count (treated as settled)")

all_ok = pub["online"] and priv["online"]
lines.append(f"VERDICT: public_online={pub['online']} private_online={priv['online']} pass={all_ok}")
pathlib.Path(f"{out}/verdict.txt").write_text("\n".join(lines) + "\n")
print("\n".join(lines))
sys.exit(0 if all_ok else 3)
PY
rc=$?

log "log excerpts PUBLIC:"
parse_group_status_from_log "${PUB_ID:-}" | tee -a "$SUMMARY" || true
log "log excerpts PRIVATE:"
parse_group_status_from_log "${PRIV_ID:-}" | tee -a "$SUMMARY" || true

log "Done. Artifacts: $OUT"
exit $rc
