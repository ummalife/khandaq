#!/usr/bin/env bash
# Deploy the Khandaq Community founder to khandaq.org (ssh alias "Khandaq"; override with HOST=...).
#
# Builds on the server from the sources in this checkout, installs the bot and its probe, refreshes
# the systemd unit and restarts the service. The founder key lives in /var/lib/khandaq-community;
# the chat id every client has compiled in is derived from it, so this script never deletes or
# rewrites anything there. Before each restart it copies the state into
# /var/backups/khandaq-community (the ten newest copies are kept).
#
# The first deploy founds the group. The script then waits for group.txt, prints the chat id that
# the clients must compile in, and pins it in /etc/default/khandaq-community (--expect-group): from
# then on a state directory that lost or swapped its group stops the bot instead of founding a
# second group. It prints the bot's Tox ID for the admins, never the pairing code; read that with
#   ssh Khandaq cat /var/lib/khandaq-community/admin-code.txt
# Like deploy-demo-contact.sh, it expects the ssh login to be root.
#
# After the restart it runs the probe on the server itself as a smoke test (SKIP_PROBE=1 skips it).
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
HOST="${HOST:-Khandaq}"
REMOTE=/opt/khandaq-community

command -v rsync >/dev/null || { echo "rsync is required" >&2; exit 1; }
for path in "$HERE/assets/nodes.txt" "$HERE/toxcore-founder-connected.patch" "$HERE/toxcore-founder-announce.patch" \
            "$ROOT/khandaq-ios/local_pod_repo/toxcore/toxcore/toxcore/tox.h" \
            "$ROOT/khandaq-desktop/buildscripts/toxcore/third_party/cmp/cmp.c"; do
  [[ -f "$path" ]] || { echo "missing $path" >&2; exit 1; }
done

echo "==> sources -> $HOST:$REMOTE/src"
ssh "$HOST" "mkdir -p $REMOTE/src/community-founder $REMOTE/src/pod-toxcore $REMOTE/src/cmp"
rsync -a --delete --exclude build/ "$HERE/" "$HOST:$REMOTE/src/community-founder/"
rsync -a --delete --exclude '*.bak' "$ROOT/khandaq-ios/local_pod_repo/toxcore/toxcore/" "$HOST:$REMOTE/src/pod-toxcore/"
rsync -a --delete "$ROOT/khandaq-desktop/buildscripts/toxcore/third_party/cmp/" "$HOST:$REMOTE/src/cmp/"

echo "==> build, back up, install and restart on $HOST"
ssh "$HOST" bash -s <<'REMOTE_SCRIPT'
set -euo pipefail
R=/opt/khandaq-community
STATE=/var/lib/khandaq-community
BACKUPS=/var/backups/khandaq-community
DEFAULTS=/etc/default/khandaq-community

id khandaq-community >/dev/null 2>&1 ||
  useradd --system --home-dir "$STATE" --shell /usr/sbin/nologin khandaq-community
OUT="$R/build" POD_TOXCORE="$R/src/pod-toxcore" CMP_DIR="$R/src/cmp" bash "$R/src/community-founder/build.sh"
install -d -m 0755 "$R/bin" "$R/assets"
install -m 0755 "$R/build/khandaq-community" "$R/bin/khandaq-community.new"
install -m 0755 "$R/build/community-probe" "$R/bin/community-probe"
rsync -a --delete "$R/src/community-founder/assets/" "$R/assets/"

# The state as it was before this restart. The pairing code is left out: it only pairs new admins,
# and the bot makes a new one if it is ever lost.
if [[ -f "$STATE/khandaq-community.tox" ]]; then
  install -d -m 0700 "$BACKUPS"
  stamp="$(date -u +%Y%m%dT%H%M%SZ)"
  tar -C "$STATE" --exclude admin-code.txt -cf "$BACKUPS/state-$stamp.tar" .
  chmod 0600 "$BACKUPS/state-$stamp.tar"
  ls -1t "$BACKUPS"/state-*.tar | tail -n +11 | xargs -r rm -f
  echo "state backed up to $BACKUPS/state-$stamp.tar"
fi
# A group.txt that names a different chat id than the pinned one: stop here, touch nothing.
if [[ -f "$DEFAULTS" && -s "$STATE/group.txt" ]] &&
   ! grep -q -- "--expect-group $(head -c 64 "$STATE/group.txt")" "$DEFAULTS"; then
  echo "$STATE/group.txt and $DEFAULTS name different chat ids; not restarting" >&2
  rm -f "$R/bin/khandaq-community.new"
  exit 1
fi

mv -f "$R/bin/khandaq-community.new" "$R/bin/khandaq-community"
install -m 0644 "$R/src/community-founder/khandaq-community.service" /etc/systemd/system/khandaq-community.service
systemctl daemon-reload
systemctl enable khandaq-community.service >/dev/null 2>&1
systemctl restart khandaq-community.service

for _ in $(seq 1 60); do
  [[ -s "$STATE/group.txt" && -s "$STATE/toxid.txt" ]] && break
  sleep 1
done
systemctl is-active --quiet khandaq-community.service && [[ -s "$STATE/group.txt" ]] ||
  { journalctl -u khandaq-community -n 40 --no-pager; exit 1; }
chat_id="$(head -c 64 "$STATE/group.txt")"
if [[ ! -f "$DEFAULTS" ]]; then
  printf 'COMMUNITY_ARGS="--expect-group %s"\n' "$chat_id" >"$DEFAULTS"
  chmod 0644 "$DEFAULTS"
  echo "pinned the chat id in $DEFAULTS"
fi
echo "Chat id (compiled into the clients): $chat_id"
echo "Bot Tox ID (admins add it with the pairing code): $(cat "$STATE/toxid.txt")"
REMOTE_SCRIPT

if [[ "${SKIP_PROBE:-0}" != "1" ]]; then
  echo "==> smoke test: probe on $HOST (join, founder, settings, topic, a 75 s stay)"
  ssh "$HOST" 'cd /opt/khandaq-community && S=/var/lib/khandaq-community &&
    bin/community-probe --chat-id "$(head -c 64 $S/group.txt)" --nodes assets/nodes.txt \
      --expect-limit "$(cat $S/limit.txt)" --expect-topic "$(cat $S/topic.txt)"'
fi
