#!/usr/bin/env bash
# Deploy the Khandaq demo contact to khandaq.org (ssh alias "Khandaq"; override with HOST=...).
#
# Builds on the server from the sources in this checkout, installs the binary, the probe and the
# media, refreshes the systemd unit and restarts the service. The identity lives in
# /var/lib/khandaq-demo and survives every redeploy. App Review is given that Tox ID, so this script
# never touches the state directory; recreating it would silently invalidate the review notes.
#
# After the restart it runs the probe on the server itself as a smoke test (--quick: no calls).
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
HOST="${HOST:-Khandaq}"
REMOTE=/opt/khandaq-demo

command -v rsync >/dev/null || { echo "rsync is required" >&2; exit 1; }
for path in "$HERE/assets/nodes.txt" "$ROOT/khandaq-ios/local_pod_repo/toxcore/toxcore/toxcore/tox.h" \
            "$ROOT/khandaq-desktop/buildscripts/toxcore/third_party/cmp/cmp.c"; do
  [[ -f "$path" ]] || { echo "missing $path" >&2; exit 1; }
done

echo "==> sources -> $HOST:$REMOTE/src"
ssh "$HOST" "mkdir -p $REMOTE/src/demo-contact $REMOTE/src/pod-toxcore $REMOTE/src/cmp"
rsync -a --delete --exclude build/ "$HERE/" "$HOST:$REMOTE/src/demo-contact/"
rsync -a --delete --exclude '*.bak' "$ROOT/khandaq-ios/local_pod_repo/toxcore/toxcore/" "$HOST:$REMOTE/src/pod-toxcore/"
rsync -a --delete "$ROOT/khandaq-desktop/buildscripts/toxcore/third_party/cmp/" "$HOST:$REMOTE/src/cmp/"

echo "==> build, install and restart on $HOST"
ssh "$HOST" bash -s <<'REMOTE_SCRIPT'
set -euo pipefail
R=/opt/khandaq-demo
id khandaq-demo >/dev/null 2>&1 || useradd --system --home-dir /var/lib/khandaq-demo --shell /usr/sbin/nologin khandaq-demo
OUT="$R/build" POD_TOXCORE="$R/src/pod-toxcore" CMP_DIR="$R/src/cmp" bash "$R/src/demo-contact/build.sh"
install -d -m 0755 "$R/bin" "$R/assets"
install -m 0755 "$R/build/khandaq-demo" "$R/bin/khandaq-demo.new"
install -m 0755 "$R/build/khandaq-demo-probe" "$R/bin/khandaq-demo-probe"
mv -f "$R/bin/khandaq-demo.new" "$R/bin/khandaq-demo"
rsync -a --delete "$R/src/demo-contact/assets/" "$R/assets/"
install -m 0644 "$R/src/demo-contact/khandaq-demo.service" /etc/systemd/system/khandaq-demo.service
systemctl daemon-reload
systemctl enable khandaq-demo.service >/dev/null 2>&1
systemctl restart khandaq-demo.service
sleep 5
systemctl is-active --quiet khandaq-demo.service || { journalctl -u khandaq-demo -n 30 --no-pager; exit 1; }
echo "Tox ID: $(cat /var/lib/khandaq-demo/toxid.txt)"
REMOTE_SCRIPT

if [[ "${SKIP_PROBE:-0}" != "1" ]]; then
  echo "==> smoke test: probe on $HOST (welcome pack, messaging, files, group; no calls)"
  ssh "$HOST" 'cd /opt/khandaq-demo && bin/khandaq-demo-probe --quick --nodes assets/nodes.txt --bot "$(cat /var/lib/khandaq-demo/toxid.txt)"'
fi
