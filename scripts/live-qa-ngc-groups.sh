#!/usr/bin/env bash
# Live NGC group QA on two Android emulators
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PKG="org.khandaq.messenger"
MAIN="com.zoffcc.applications.trifa.MainActivity"
ADD_PUB="com.zoffcc.applications.trifa.AddPublicGroupActivity"
ADD_PRIV="com.zoffcc.applications.trifa.AddPrivateGroupActivity"
JOIN="com.zoffcc.applications.trifa.JoinPublicGroupActivity"
APK="${APK:-$ROOT/dist/android/khandaq-release.apk}"
OUT="${OUT:-$ROOT/docs/qa-ngc-$(date +%Y%m%d-%H%M%S)}"
CREATOR="${CREATOR:-emulator-5554}"
JOINER="${JOINER:-emulator-5556}"
TS="$(date +%s)"
PUB_NAME="QA-PUBLIC-$TS"
PRIV_NAME="QA-PRIVATE-$TS"
WAIT_SYNC="${WAIT_SYNC:-120}"
QA_SINGLE_PUBLIC="${QA_SINGLE_PUBLIC:-0}"

mkdir -p "$OUT"

log() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$OUT/summary.txt"; }

adb_dev() { adb -s "$1" "${@:2}"; }

install_apk() {
  local dev="$1"
  log "[$dev] install APK"
  adb_dev "$dev" install -r "$APK" >>"$OUT/install-$dev.log" 2>&1 || return 1
}

launch_main() {
  local dev="$1"
  adb_dev "$dev" logcat -c
  adb_dev "$dev" shell am force-stop "$PKG" 2>/dev/null || true
  adb_dev "$dev" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 \
    >>"$OUT/launch-$dev.log" 2>&1
  sleep 6
}

open_main_menu() {
  local dev="$1"
  ui_dump "$dev" "menu-before"
  cp "$OUT/ui-$dev-menu-before.xml" "$OUT/ui-$dev-latest.xml"
  # overflow / more options (top-right)
  if coords=$(tap_text "$dev" "More options" 2>/dev/null); then
    read -r x y <<< "$coords"
    tap_xy "$dev" "$x" "$y"
  else
    tap_xy "$dev" 1020 180
  fi
  sleep 1
}

menu_create_public() {
  local dev="$1"
  open_main_menu "$dev"
  ui_dump "$dev" "menu-open"
  cp "$OUT/ui-$dev-menu-open.xml" "$OUT/ui-$dev-latest.xml"
  for label in "Create new Public Group" "Создать новую открытую группу" "Create Public Group"; do
    if coords=$(tap_text "$dev" "$label" 2>/dev/null); then
      read -r x y <<< "$coords"
      tap_xy "$dev" "$x" "$y"
      sleep 2
      return 0
    fi
  done
  return 1
}

menu_create_private() {
  local dev="$1"
  open_main_menu "$dev"
  ui_dump "$dev" "menu-priv"
  cp "$OUT/ui-$dev-menu-priv.xml" "$OUT/ui-$dev-latest.xml"
  for label in "Create new Private Group" "Создать новую частную группу"; do
    if coords=$(tap_text "$dev" "$label" 2>/dev/null); then
      read -r x y <<< "$coords"
      tap_xy "$dev" "$x" "$y"
      sleep 2
      return 0
    fi
  done
  return 1
}

menu_join_public() {
  local dev="$1"
  open_main_menu "$dev"
  ui_dump "$dev" "menu-join"
  cp "$OUT/ui-$dev-menu-join.xml" "$OUT/ui-$dev-latest.xml"
  for label in "Join a Public Group" "Присоединиться к открытой группе" "Join Public Group"; do
    if coords=$(tap_text "$dev" "$label" 2>/dev/null); then
      read -r x y <<< "$coords"
      tap_xy "$dev" "$x" "$y"
      sleep 2
      return 0
    fi
  done
  return 1
}

wait_tox_online() {
  local dev="$1" timeout="${2:-90}"
  log "[$dev] wait tox online (max ${timeout}s)"
  local i=0
  while (( i < timeout )); do
    if adb_dev "$dev" logcat -d 2>/dev/null | rg -q "self_connection_status.*(CONNECTED|TCP|UDP)"; then
      log "[$dev] tox appears online"
      return 0
    fi
    if adb_dev "$dev" logcat -d 2>/dev/null | rg -qi "connection_status.*1|TOX_CONNECTION"; then
      sleep 3
      log "[$dev] tox connection signal seen"
      return 0
    fi
    sleep 2
    ((i+=2))
  done
  log "[$dev] WARN: tox online not confirmed in logcat"
  return 0
}

ui_dump() {
  local dev="$1" tag="$2"
  adb_dev "$dev" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || true
  adb_dev "$dev" shell cat /sdcard/ui.xml > "$OUT/ui-$dev-$tag.xml" 2>/dev/null || true
  adb_dev "$dev" exec-out screencap -p > "$OUT/screen-$dev-$tag.png" 2>/dev/null || true
}

tap_text() {
  local dev="$1" text="$2"
  python3 - "$dev" "$text" "$OUT" <<'PY'
import re, subprocess, sys
dev, needle, out = sys.argv[1:4]
xml_path = f"{out}/ui-{dev}-latest.xml"
try:
    xml = open(xml_path, encoding='utf-8', errors='replace').read()
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

tap_xy() {
  local dev="$1" x="$2" y="$3"
  adb_dev "$dev" shell input tap "$x" "$y"
}

create_group_via_ui() {
  local dev="$1" kind="$2" name="$3" tag="$4" # kind=public|private
  log "[$dev] create $kind group name=$name"
  adb_dev "$dev" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  sleep 3
  if [[ "$kind" == "public" ]]; then
    menu_create_public "$dev" || { log "[$dev] FAIL menu_create_public"; return 1; }
  else
    menu_create_private "$dev" || { log "[$dev] FAIL menu_create_private"; return 1; }
  fi
  sleep 1
  ui_dump "$dev" "$tag-before"
  cp "$OUT/ui-$dev-$tag-before.xml" "$OUT/ui-$dev-latest.xml"
  tap_xy "$dev" 540 450
  sleep 0.5
  adb_dev "$dev" shell input text "$name"
  sleep 1
  ui_dump "$dev" "$tag-filled"
  cp "$OUT/ui-$dev-$tag-filled.xml" "$OUT/ui-$dev-latest.xml"
  for label in "Create Group" "CREATE GROUP" "Создать группу" "Add" "Добавить"; do
    if coords=$(tap_text "$dev" "$label" 2>/dev/null); then
      read -r x y <<< "$coords"
      tap_xy "$dev" "$x" "$y"
      log "[$dev] tapped '$label' at $x,$y"
      sleep 4
      return 0
    fi
  done
  tap_xy "$dev" 810 2200
  sleep 4
}

extract_group_id_from_logs() {
  local dev="$1" name="$2"
  adb_dev "$dev" logcat -d 2>/dev/null | rg "create_new_group:ok.*name=$name|create_new_group:ok num=.* id=" | tail -5
  adb_dev "$dev" logcat -d 2>/dev/null | rg "create_new_group:ok num=.* id=[0-9a-fA-F]{64}" | tail -3
}

get_latest_group_id() {
  local dev="$1" privacy="$2" # 1=public 0=private
  adb_dev "$dev" logcat -d 2>/dev/null | rg "create_new_group:ok num=.* id=([0-9a-fA-F]{64})" -o --replace '$1' | tail -1
}

join_group_via_ui() {
  local dev="$1" gid="$2"
  log "[$dev] join group id=${gid:0:16}..."
  adb_dev "$dev" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  sleep 3
  menu_join_public "$dev" || { log "[$dev] FAIL menu_join_public"; return 1; }
  sleep 1
  tap_xy "$dev" 540 450
  sleep 0.5
  adb_dev "$dev" shell input text "$gid"
  sleep 1
  ui_dump "$dev" "join-filled"
  cp "$OUT/ui-$dev-join-filled.xml" "$OUT/ui-$dev-latest.xml"
  for label in "Join Group" "JOIN GROUP" "Join" "Присоединиться" "Вступить"; do
    if coords=$(tap_text "$dev" "$label" 2>/dev/null); then
      read -r x y <<< "$coords"
      tap_xy "$dev" "$x" "$y"
      log "[$dev] tapped join '$label'"
      sleep 5
      return 0
    fi
  done
  tap_xy "$dev" 810 2200
  sleep 5
}

open_group_chat() {
  local dev="$1" name="$2"
  ui_dump "$dev" "list"
  cp "$OUT/ui-$dev-list.xml" "$OUT/ui-$dev-latest.xml"
  if coords=$(tap_text "$dev" "$name" 2>/dev/null); then
    read -r x y <<< "$coords"
    tap_xy "$dev" "$x" "$y"
    sleep 2
    return 0
  fi
  return 1
}

send_chat_message() {
  local dev="$1" msg="$2"
  tap_xy "$dev" 400 2150
  sleep 0.3
  adb_dev "$dev" shell input text "$msg"
  sleep 0.3
  adb_dev "$dev" shell input keyevent 66
  sleep 2
}

wait_peer_mesh() {
  local creator="$1" joiner="$2" gid="$3" timeout="${4:-180}"
  log "wait_peer_mesh: same group id=${gid:0:16}... max ${timeout}s"
  local i=0
  while (( i < timeout )); do
    local cj jj
    cj=$(adb_dev "$creator" logcat -d 2>/dev/null | rg -c "group_peer_join" || true)
    jj=$(adb_dev "$joiner" logcat -d 2>/dev/null | rg -c "group_peer_join" || true)
    if (( cj >= 1 && jj >= 1 )); then
      log "peer_mesh: group_peer_join on both (creator=$cj joiner=$jj)"
      return 0
    fi
    if adb_dev "$creator" logcat -d 2>/dev/null | rg -q "peers=2|peer_count=2|2 members"; then
      if adb_dev "$joiner" logcat -d 2>/dev/null | rg -q "peers=2|peer_count=2|2 members"; then
        log "peer_mesh: peers=2 seen in logcat"
        return 0
      fi
    fi
    sleep 5
    ((i+=5))
  done
  log "WARN: peer_mesh not confirmed in ${timeout}s"
  return 1
}

collect_metrics() {
  local dev="$1" tag="$2"
  local file="$OUT/metrics-$dev-$tag.txt"
  {
    echo "=== $dev $tag $(date) ==="
    adb_dev "$dev" logcat -d 2>/dev/null | rg -i "create_new_group|join_group|group_self_join|group_peer_join|reconnect_group|maintain_all_groups|kickstart_public|peer_count|group_connection_status|handle_group_join_fail" | tail -80
  } > "$file"
  log "[$dev] metrics -> $file"
}

# --- main ---
log "NGC QA OUT=$OUT"
log "CREATOR=$CREATOR JOINER=$JOINER WAIT_SYNC=${WAIT_SYNC}s"

for dev in "$CREATOR" "$JOINER"; do
  if ! adb_dev "$dev" get-state >/dev/null 2>&1; then
    log "ERROR: $dev not connected"
    exit 1
  fi
  install_apk "$dev" || log "WARN: install failed on $dev"
done

launch_main "$CREATOR"
launch_main "$JOINER"
sleep 8
wait_tox_online "$CREATOR" "${TOX_WAIT:-15}"
wait_tox_online "$JOINER" "${TOX_WAIT:-15}"
sleep 5

# Create public then private on creator
create_group_via_ui "$CREATOR" "public" "$PUB_NAME" "pub"
collect_metrics "$CREATOR" "after-pub-create"
PUB_ID="$(adb_dev "$CREATOR" logcat -d 2>/dev/null | rg 'create_new_group:ok num=.* id=([0-9a-fA-F]{64})' -o --replace '$1' | tail -1)"
log "PUBLIC group id=$PUB_ID name=$PUB_NAME"

PRIV_ID=""
if [[ "$QA_SINGLE_PUBLIC" != "1" ]]; then
  sleep 3
  create_group_via_ui "$CREATOR" "private" "$PRIV_NAME" "priv"
  collect_metrics "$CREATOR" "after-priv-create"
  PRIV_ID="$(adb_dev "$CREATOR" logcat -d 2>/dev/null | rg 'create_new_group:ok num=.* id=([0-9a-fA-F]{64})' -o --replace '$1' | tail -1)"
  log "PRIVATE group id=$PRIV_ID name=$PRIV_NAME"
else
  log "QA_SINGLE_PUBLIC=1 — skip private group"
fi

if [[ -z "${PUB_ID:-}" || ${#PUB_ID} -ne 64 ]]; then
  log "FAIL: could not extract public group ID from creator logs"
  adb_dev "$CREATOR" logcat -d > "$OUT/logcat-creator-full.txt"
  exit 2
fi

# Join public from joiner
join_group_via_ui "$JOINER" "$PUB_ID"
collect_metrics "$JOINER" "after-pub-join"

# Real-user flow: write into the group right after join, measure delivery latency
log "Opening chats and sending cross-messages IMMEDIATELY after join..."
open_group_chat "$CREATOR" "$PUB_NAME" || tap_xy "$CREATOR" 540 600
sleep 2
send_chat_message "$CREATOR" "hello-from-creator-$TS"
open_group_chat "$JOINER" "$PUB_NAME" || tap_xy "$JOINER" 540 600
sleep 2
send_chat_message "$JOINER" "hello-from-joiner-$TS"
SEND_TS=$(date +%s)

log "Polling delivery (max ${WAIT_SYNC}s)..."
DELIVERY_SEC=""
i=0
while (( i < WAIT_SYNC )); do
  c_got=$(adb_dev "$CREATOR" logcat -d 2>/dev/null | rg -c "group_message_cb.*hello-from-joiner-$TS" || true)
  j_got=$(adb_dev "$JOINER" logcat -d 2>/dev/null | rg -c "group_message_cb.*hello-from-creator-$TS" || true)
  if (( c_got >= 1 && j_got >= 1 )); then
    DELIVERY_SEC=$(( $(date +%s) - SEND_TS ))
    log "DELIVERY OK: both sides received in ${DELIVERY_SEC}s"
    break
  fi
  sleep 3
  ((i+=3))
done
if [[ -z "$DELIVERY_SEC" ]]; then
  log "WARN: cross-delivery not confirmed in ${WAIT_SYNC}s (creator_got=$c_got joiner_got=$j_got)"
fi
echo "DELIVERY_SEC=${DELIVERY_SEC:-timeout}" >> "$OUT/summary.txt"

collect_metrics "$CREATOR" "sync-wait-creator"
collect_metrics "$JOINER" "sync-wait-joiner"

# Try join private by ID (expected: isolated session / no peer sync)
if [[ -n "${PRIV_ID:-}" && ${#PRIV_ID} -eq 64 ]]; then
  join_group_via_ui "$JOINER" "$PRIV_ID"
  collect_metrics "$JOINER" "after-priv-join"
  sleep 30
  collect_metrics "$JOINER" "after-priv-join-wait"
fi

# short tail wait so ngch/ack logs land before final collection
sleep 10

collect_metrics "$CREATOR" "final"
collect_metrics "$JOINER" "final"
adb_dev "$CREATOR" logcat -d > "$OUT/logcat-creator-full.txt"
adb_dev "$JOINER" logcat -d > "$OUT/logcat-joiner-full.txt"

# Parse results
python3 - "$OUT" "$PUB_ID" "$PRIV_ID" "$PUB_NAME" <<'PY'
import re, sys, pathlib
out, pub_id, priv_id, pub_name = sys.argv[1:5]
report = []

def scan(path, label):
    p = pathlib.Path(path)
    if not p.exists():
        return {"label": label, "peer_joins": 0, "self_joins": 0, "reconnects": 0, "peer_count_lines": []}
    text = p.read_text(errors='replace')
    return {
        "label": label,
        "peer_joins": len(re.findall(r'group_peer_join', text)),
        "self_joins": len(re.findall(r'group_self_join', text)),
        "reconnects": len(re.findall(r'reconnect_group_if_disconnected', text)),
        "kickstarts": len(re.findall(r'kickstart_public_group_dht|maintain_all_groups', text)),
        "join_attempts": re.findall(r'join_group:attempt=\d+ groupnum=(-?\d+)', text),
        "create_ok": re.findall(r'create_new_group:ok num=\d+ id=([0-9a-fA-F]{64})', text),
        "messages": re.findall(r'hello-from-(creator|joiner)-\d+', text),
        "msg_recv": re.findall(r'group_message_cb:recv[^\\n]*hello-from-(creator|joiner)', text),
        "delivery_ack": len(re.findall(r'group_delivery_ack:', text)),
        "ngch_sync": len(re.findall(r'send_ngch_syncmsg:result=', text)),
        "ngch_in": len(re.findall(r'group_custom_private_packet_cb: got ngch_syncmsg|group_message_add_from_sync', text)),
    }

c = scan(f"{out}/logcat-creator-full.txt", "creator")
j = scan(f"{out}/logcat-joiner-full.txt", "joiner")

lines = []
lines.append(f"PUBLIC_ID={pub_id}")
lines.append(f"PRIVATE_ID={priv_id}")
lines.append(f"PUBLIC_NAME={pub_name}")
for d in (c, j):
    lines.append(f"{d['label']}: peer_joins={d['peer_joins']} self_joins={d['self_joins']} reconnects={d['reconnects']} kickstarts={d.get('kickstarts',0)} join_attempts={d.get('join_attempts',[])} creates={d.get('create_ok',[])} msgs={d.get('messages',[])} msg_recv={d.get('msg_recv',[])} ngch_sync={d.get('ngch_sync',0)} ngch_in={d.get('ngch_in',0)}")
pub_join_ok = any(int(x) >= 0 for x in j.get('join_attempts', ['-1']))
peer_sync = c['peer_joins'] >= 1 or j['peer_joins'] >= 1
creator_got_joiner = 'joiner' in c.get('msg_recv', [])
joiner_got_creator = 'creator' in j.get('msg_recv', [])
msg_cross = creator_got_joiner and joiner_got_creator
lines.append(f"VERDICT: pub_join_ok={pub_join_ok} peer_sync={peer_sync} msg_cross={msg_cross} creator_got_joiner={creator_got_joiner} joiner_got_creator={joiner_got_creator}")
pathlib.Path(f"{out}/verdict.txt").write_text("\n".join(lines) + "\n")
print("\n".join(lines))
PY

log "Done. Artifacts: $OUT"
