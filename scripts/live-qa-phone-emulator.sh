#!/usr/bin/env bash
# Live NGC QA: phone + emulator.
# MODE=emu-create  — группа на эмуляторе, телефон вступает по ID (по умолчанию)
# MODE=phone-create — группа на телефоне, эмулятор вступает по ID
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PKG="com.khandaq.messenger"
JOIN="com.zoffcc.applications.trifa.JoinPublicGroupActivity"
CHAT="com.zoffcc.applications.trifa.GroupMessageListActivity"
APK="${APK:-$ROOT/dist/android/khandaq-release.apk}"
OUT="${OUT:-$ROOT/docs/qa-phone-emu-$(date +%Y%m%d-%H%M%S)}"
EMULATOR="${EMULATOR:-emulator-5554}"
PHONE="${PHONE:-}"
MODE="${MODE:-emu-create}"
WAIT_SYNC="${WAIT_SYNC:-150}"
TS="$(date +%s)"
PUB_NAME="QA-PHONE-PUBLIC-$TS"

mkdir -p "$OUT"
log() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$OUT/summary.txt"; }
adb_dev() { adb -s "$1" "${@:2}"; }

detect_phone() {
  [[ -n "$PHONE" ]] && { echo "$PHONE"; return; }
  adb devices | awk '/device$/ && !/emulator-/ {print $1; exit}'
}

start_emulator_if_needed() {
  if adb_dev "$EMULATOR" get-state 2>/dev/null | grep -q device; then
    log "$EMULATOR online"; return 0
  fi
  log "boot $EMULATOR..."
  "$ROOT/scripts/start-qa-emulators.sh" >>"$OUT/emu-boot.log" 2>&1 || true
  sleep 8
}

install_and_launch() {
  local dev="$1"
  log "[$dev] install"
  adb_dev "$dev" install -r "$APK" >>"$OUT/install-$dev.log" 2>&1
  adb_dev "$dev" logcat -c
  adb_dev "$dev" shell am force-stop "$PKG" 2>/dev/null || true
  adb_dev "$dev" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >>"$OUT/launch-$dev.log" 2>&1
  sleep 5
}

# Только разрешения — НИКОГДА не трогаем поле Search на главном экране
dismiss_permissions() {
  local dev="$1"
  log "[$dev] dismiss permissions (no search typing)"
  python3 - "$dev" "$OUT" <<'PY'
import re,subprocess,sys,time
dev,out=sys.argv[1:3]
LABELS=['SKIP','Continue','Allow','While using the app','Next','Done','OK','GOT IT',
        'Разрешить','Продолжить','ПРОПУСТИТЬ','Далее','Готово']
def dump(i):
 subprocess.run(['adb','-s',dev,'shell','uiautomator','dump',f'/sdcard/ob{i}.xml'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
 xml=subprocess.check_output(['adb','-s',dev,'shell','cat',f'/sdcard/ob{i}.xml']).decode('utf-8','replace')
 open(f'{out}/ui-{dev}-ob{i}.xml','w').write(xml)
 return xml
def tap_text(xml, t):
 m=re.search(rf'text="{re.escape(t)}"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
 if m:
  x1,y1,x2,y2=map(int,m.groups())
  subprocess.run(['adb','-s',dev,'shell','input','tap',str((x1+x2)//2),str((y1+y2)//2)])
  return True
 return False
for i in range(20):
 xml=dump(i)
 if 'More options' in xml:
  print('READY'); break
 hit=False
 for l in LABELS:
  if l in xml and tap_text(xml,l):
   time.sleep(1.5); hit=True; break
 if not hit:
  time.sleep(1)
PY
}

ui_dump() {
  local dev="$1" tag="$2"
  adb_dev "$dev" shell uiautomator dump "/sdcard/ui-$tag.xml" >/dev/null 2>&1 || true
  adb_dev "$dev" shell cat "/sdcard/ui-$tag.xml" > "$OUT/ui-$dev-$tag.xml" 2>/dev/null || true
  cp "$OUT/ui-$dev-$tag.xml" "$OUT/ui-$dev-latest.xml" 2>/dev/null || true
  adb_dev "$dev" exec-out screencap -p > "$OUT/screen-$dev-$tag.png" 2>/dev/null || true
}

tap_text_in() {
  local dev="$1" text="$2" xml="$3"
  python3 - "$dev" "$text" "$xml" <<'PY'
import re,subprocess,sys
dev,needle,path=sys.argv[1:4]
xml=open(path,encoding='utf-8',errors='replace').read()
for pat in [rf'text="{re.escape(needle)}"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',
          rf'content-desc="{re.escape(needle)}"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"']:
 m=re.search(pat, xml)
 if m:
  x1,y1,x2,y2=map(int,m.groups())
  subprocess.run(['adb','-s',dev,'shell','input','tap',str((x1+x2)//2),str((y1+y2)//2)])
  print('ok'); sys.exit(0)
sys.exit(1)
PY
}

tap_rid() {
  local dev="$1" rid="$2" xml="$3"
  python3 - "$dev" "$rid" "$xml" <<'PY'
import re,subprocess,sys
dev,rid,path=sys.argv[1:4]
xml=open(path,encoding='utf-8',errors='replace').read()
m=re.search(rf'resource-id="{re.escape(rid)}"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
if m:
 x1,y1,x2,y2=map(int,m.groups())
 subprocess.run(['adb','-s',dev,'shell','input','tap',str((x1+x2)//2),str((y1+y2)//2)])
 print('ok'); sys.exit(0)
sys.exit(1)
PY
}

clear_search_focus() {
  local dev="$1"
  adb_dev "$dev" shell input keyevent 4 2>/dev/null || true
  sleep 0.5
}

open_overflow_menu() {
  local dev="$1"
  clear_search_focus "$dev"
  adb_dev "$dev" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  sleep 2
  ui_dump "$dev" "menu-before"
  for label in "Ещё" "More options" "More"; do
    if tap_text_in "$dev" "$label" "$OUT/ui-$dev-latest.xml" 2>/dev/null; then return 0; fi
  done
  python3 - "$dev" "$OUT/ui-$dev-latest.xml" <<'PY'
import re,subprocess,sys
dev,path=sys.argv[1:3]
xml=open(path,encoding='utf-8',errors='replace').read()
m=re.search(r'content-desc="(Ещё|More options)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
if m:
 x1,y1,x2,y2=map(int,m.groups()[1:])
 subprocess.run(['adb','-s',dev,'shell','input','tap',str((x1+x2)//2),str((y1+y2)//2)])
 sys.exit(0)
sys.exit(1)
PY
}

create_public_group_ui() {
  local dev="$1" name="$2"
  log "[$dev] Меню → Create new Public Group → имя=$name"
  adb_dev "$dev" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  sleep 2
  open_overflow_menu "$dev"
  sleep 1
  ui_dump "$dev" "menu-open"
  if ! tap_text_in "$dev" "Create new Public Group" "$OUT/ui-$dev-latest.xml" 2>/dev/null; then
    tap_text_in "$dev" "Создать новую открытую группу" "$OUT/ui-$dev-latest.xml" 2>/dev/null \
      || adb_dev "$dev" shell input tap 792 331
  fi
  sleep 2
  ui_dump "$dev" "pub-form"
  # поле имени группы (НЕ search)
  if ! tap_rid "$dev" "org.khandaq.messenger:id/group_new_group_name" "$OUT/ui-$dev-latest.xml" 2>/dev/null; then
    adb_dev "$dev" shell input tap 540 450
  fi
  sleep 0.4
  adb_dev "$dev" shell input text "$name"
  sleep 1
  ui_dump "$dev" "pub-filled"
  if ! tap_text_in "$dev" "CREATE GROUP" "$OUT/ui-$dev-latest.xml" 2>/dev/null; then
    tap_text_in "$dev" "Create Group" "$OUT/ui-$dev-latest.xml" 2>/dev/null \
      || tap_text_in "$dev" "Создать группу" "$OUT/ui-$dev-latest.xml" 2>/dev/null \
      || adb_dev "$dev" shell input tap 810 2200
  fi
  sleep 4
}

current_activity() {
  adb_dev "$1" shell dumpsys window 2>/dev/null | rg -m1 'mCurrentFocus' | sed 's/.*}//' || true
}

wait_join_ok() {
  local dev="$1" gid="$2" timeout="${3:-90}"
  log "[$dev] ждём join OK (max ${timeout}s)"
  local i=0
  while (( i < timeout )); do
    if adb_dev "$dev" logcat -d 2>/dev/null | rg -q "join_group:new groupnum|finish_public_group_join|group_self_join_cb"; then
      log "[$dev] join OK в logcat"
      return 0
    fi
    if adb_dev "$dev" logcat -d 2>/dev/null | rg -qi "join_group:group_id:.*${gid:0:16}"; then
      sleep 3
      if adb_dev "$dev" logcat -d 2>/dev/null | rg -q "join_group:attempt=.*groupnum=[0-9]"; then
        log "[$dev] join attempt seen"
      fi
    fi
    sleep 3
    ((i+=3))
  done
  log "[$dev] FAIL: join не подтверждён"
  ui_dump "$dev" "join-fail"
  return 1
}

assert_on_join_screen() {
  local dev="$1" tag="$2"
  if ! rg -q "group_join_group_id" "$OUT/ui-$dev-$tag.xml" 2>/dev/null; then
    log "[$dev] FAIL: экран Join не открыт (нет group_join_group_id). Не трогаем Search!"
    ui_dump "$dev" "wrong-screen-$tag"
    return 1
  fi
  if rg -q 'main_chat_search' "$OUT/ui-$dev-$tag.xml" 2>/dev/null; then
    log "[$dev] FAIL: всё ещё главный экран со Search"
    return 1
  fi
  return 0
}

type_into_join_id_field() {
  local dev="$1" gid="$2"
  tap_rid "$dev" "org.khandaq.messenger:id/group_join_group_id" "$OUT/ui-$dev-latest.xml" || return 1
  sleep 0.4
  # 64 hex посимвольно чанками — только когда поле join активно
  local i=0 len=${#gid}
  while (( i < len )); do
    adb_dev "$dev" shell input text "${gid:i:16}"
    ((i+=16))
    sleep 0.15
  done
}

join_public_group_by_id() {
  local dev="$1" gid="$2"
  log "[$dev] Меню → Join public group → ID ${gid:0:16}..."
  adb_dev "$dev" logcat -c 2>/dev/null || true
  clear_search_focus "$dev"

  open_overflow_menu "$dev"
  sleep 1
  ui_dump "$dev" "join-menu"
  for label in "Join a Public Group" "Присоединиться к открытой группе" "Join Public Group"; do
    if tap_text_in "$dev" "$label" "$OUT/ui-$dev-latest.xml" 2>/dev/null; then break; fi
  done
  sleep 2
  ui_dump "$dev" "join-screen"
  assert_on_join_screen "$dev" "join-screen" || return 1

  type_into_join_id_field "$dev" "$gid"
  sleep 1
  ui_dump "$dev" "join-id-filled"
  assert_on_join_screen "$dev" "join-id-filled" || return 1

  for _ in 1 2 3 4 5 6; do
    if python3 - "$OUT/ui-$dev-latest.xml" <<'PY'
import re,sys
xml=open(sys.argv[1],encoding='utf-8',errors='replace').read()
m=re.search(r'resource-id="org\.khandaq\.messenger:id/friend_joingroup"[^>]*enabled="true"', xml)
sys.exit(0 if m else 1)
PY
    then break; fi
    sleep 1
    ui_dump "$dev" "join-wait-btn"
  done

  tap_rid "$dev" "org.khandaq.messenger:id/friend_joingroup" "$OUT/ui-$dev-latest.xml" 2>/dev/null \
    || tap_text_in "$dev" "JOIN GROUP" "$OUT/ui-$dev-latest.xml" 2>/dev/null \
    || tap_text_in "$dev" "Присоединиться к группе" "$OUT/ui-$dev-latest.xml" 2>/dev/null

  wait_join_ok "$dev" "$gid" 90 || return 1
  clear_search_focus "$dev"
  log "[$dev] join OK"
}

# Открыть чат тапом по строке группы в списке (activity не exported — am start нельзя)
open_group_chat_by_name() {
  local dev="$1" name="$2"
  log "[$dev] открыть группу в списке: $name"
  clear_search_focus "$dev"
  adb_dev "$dev" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  sleep 2
  ui_dump "$dev" "chat-list"
  if tap_text_in "$dev" "$name" "$OUT/ui-$dev-latest.xml" 2>/dev/null; then
    sleep 3
  else
    log "[$dev] WARN: имя не найдено, ищем QA-* в списке"
    python3 - "$dev" "$OUT/ui-$dev-latest.xml" <<'PY'
import re,subprocess,sys
dev,path=sys.argv[1:3]
xml=open(path,encoding='utf-8',errors='replace').read()
for m in re.finditer(r'text="(QA-[^"]+)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
 x1,y1,x2,y2=map(int,m.groups()[1:])
 subprocess.run(['adb','-s',dev,'shell','input','tap',str((x1+x2)//2),str((y1+y2)//2)])
 print(m.group(1)); break
PY
    sleep 3
  fi
  ui_dump "$dev" "in-group-chat"
  if ! rg -q "ml_new_message" "$OUT/ui-$dev-latest.xml"; then
    log "[$dev] FAIL: нет поля чата ml_new_message — возможно не вошли в группу"
    return 1
  fi
  if rg -q 'main_chat_search.*focused="true"' "$OUT/ui-$dev-latest.xml" 2>/dev/null; then
    log "[$dev] FAIL: фокус в Search, не в чате"
    return 1
  fi
  return 0
}

send_group_message() {
  local dev="$1" msg="$2" name="$3"
  log "[$dev] отправка в чат (только ml_new_message): $msg"

  if ! open_group_chat_by_name "$dev" "$name"; then
    log "[$dev] abort send — чат группы не открыт"
    return 1
  fi

  python3 - "$dev" "$msg" "$OUT/ui-$dev-latest.xml" <<'PY'
import re,subprocess,sys
dev,msg,path=sys.argv[1:4]
xml=open(path,encoding='utf-8',errors='replace').read()
if 'main_chat_search' in xml and 'ml_new_message' not in xml:
 print('ON_MAIN_LIST_NOT_CHAT'); sys.exit(2)
RID='org.khandaq.messenger:id/ml_new_message'
m=re.search(rf'resource-id="{re.escape(RID)}"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
if not m:
 print('NO_CHAT_FIELD'); sys.exit(1)
x1,y1,x2,y2=map(int,m.groups())
subprocess.run(['adb','-s',dev,'shell','input','tap',str((x1+x2)//2),str((y1+y2)//2)])
# adb input text: только буквы/цифры/_
safe=''.join(c if c.isalnum() or c=='_' else '_' for c in msg)
subprocess.run(['adb','-s',dev,'shell','input','text',safe])
subprocess.run(['adb','-s',dev,'shell','input','keyevent','66'])
print('SENT',safe)
PY
  sleep 2
}

wait_mesh() {
  local a="$1" b="$2" t="${3:-$WAIT_SYNC}"
  log "wait mesh ${t}s"
  local i=0
  while (( i < t )); do
    local ca cj
    ca=$(adb_dev "$a" logcat -d 2>/dev/null | rg -c "group_peer_join" || true)
    cj=$(adb_dev "$b" logcat -d 2>/dev/null | rg -c "group_peer_join" || true)
    if (( ca >= 1 && cj >= 1 )); then log "mesh OK"; return 0; fi
    sleep 5; ((i+=5))
  done
  log "WARN: mesh timeout"
  return 1
}

extract_group_id() {
  local dev="$1"
  adb_dev "$dev" logcat -d 2>/dev/null | rg 'create_new_group:ok num=.* id=([0-9a-fA-F]{64})' -o --replace '$1' | tail -1
}

# --- main ---
PHONE_DEV="$(detect_phone)"
[[ -n "$PHONE_DEV" ]] || { log "ERROR: телефон не найден (PHONE=serial)"; exit 1; }

if [[ "$MODE" == "phone-create" ]]; then
  CREATOR="$PHONE_DEV"
  JOINER="$EMULATOR"
else
  CREATOR="$EMULATOR"
  JOINER="$PHONE_DEV"
fi

log "=== Phone + Emulator QA mode=$MODE ==="
log "CREATOR=$CREATOR  JOINER=$JOINER"
log "OUT=$OUT"

start_emulator_if_needed
for d in "$CREATOR" "$JOINER"; do
  adb_dev "$d" get-state 2>/dev/null | grep -q device || { log "ERROR: $d offline"; exit 1; }
done

install_and_launch "$CREATOR"
install_and_launch "$JOINER"
dismiss_permissions "$CREATOR"
dismiss_permissions "$JOINER"
sleep 5

create_public_group_ui "$CREATOR" "$PUB_NAME"
PUB_ID="$(extract_group_id "$CREATOR")"
log "GROUP id=$PUB_ID name=$PUB_NAME"
[[ ${#PUB_ID} -eq 64 ]] || { log "FAIL: нет group id"; exit 2; }

if ! join_public_group_by_id "$JOINER" "$PUB_ID"; then
  log "FAIL: телефон/джойнер не вступил в группу — стоп"
  exit 3
fi
wait_mesh "$CREATOR" "$JOINER" "$WAIT_SYNC" || true
sleep 15

MSG_C="from_creator_${TS}"
MSG_J="from_joiner_${TS}"

send_group_message "$CREATOR" "$MSG_C" "$PUB_NAME"
sleep 10
send_group_message "$JOINER" "$MSG_J" "$PUB_NAME"
sleep 40

adb_dev "$CREATOR" logcat -d > "$OUT/logcat-creator.txt"
adb_dev "$JOINER" logcat -d > "$OUT/logcat-joiner.txt"
adb_dev "$CREATOR" exec-out screencap -p > "$OUT/screen-creator.png" 2>/dev/null || true
adb_dev "$JOINER" exec-out screencap -p > "$OUT/screen-joiner.png" 2>/dev/null || true

python3 - "$OUT" "$PUB_ID" "$MSG_C" "$MSG_J" "$MODE" <<'PY'
import pathlib,sys
out,pub,mc,mj,mode=sys.argv[1:6]
c=pathlib.Path(f'{out}/logcat-creator.txt').read_text(errors='replace')
j=pathlib.Path(f'{out}/logcat-joiner.txt').read_text(errors='replace')
ps='group_peer_join' in c and 'group_peer_join' in j
cr=mj in c; jr=mc in j
lines=[f'MODE={mode}',f'PUBLIC_ID={pub}',f'peer_sync={ps}',
       f'creator_got_joiner={cr}',f'joiner_got_creator={jr}',f'msg_cross={cr and jr}']
pathlib.Path(f'{out}/verdict.txt').write_text('\n'.join(lines)+'\n')
print('\n'.join(lines))
PY

log "Готово: $OUT"
