#!/usr/bin/env bash
# Generate and deploy bootstrap registry to bootstrap.khandaq.org
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REMOTE="${KHANDAQ_BOOTSTRAP_REMOTE:-Khandaq}"
REMOTE_DIR="${KHANDAQ_BOOTSTRAP_DIR:-/var/www/bootstrap.khandaq.org}"
NGINX_CONF="${KHANDAQ_BOOTSTRAP_NGINX:-$ROOT/infra/bootstrap/nginx-bootstrap.conf}"

echo "==> Generate nodes.json"
python3 "$ROOT/scripts/generate-bootstrap-registry.py"

OUT="$ROOT/dist/bootstrap/nodes.json"
[[ -f "$OUT" ]] || { echo "Missing $OUT"; exit 1; }

echo "==> Upload to $REMOTE:$REMOTE_DIR"
ssh "$REMOTE" "mkdir -p '$REMOTE_DIR'"
scp -p "$OUT" "$REMOTE:$REMOTE_DIR/nodes.json"
scp -p "$NGINX_CONF" "$REMOTE:/tmp/khandaq-bootstrap.nginx.conf"

echo "==> Install nginx vhost (if missing)"
ssh "$REMOTE" bash -s <<'REMOTE'
set -euo pipefail
CONF="/etc/nginx/sites-available/bootstrap.khandaq.org"
ENABLED="/etc/nginx/sites-enabled/bootstrap.khandaq.org"
cp /tmp/khandaq-bootstrap.nginx.conf "$CONF"
ln -sf "$CONF" "$ENABLED"
nginx -t
systemctl reload nginx
REMOTE

echo "==> Fix permissions"
ssh "$REMOTE" "chown -R www-data:www-data '$REMOTE_DIR' && chmod 644 '$REMOTE_DIR/nodes.json'"

echo "==> Verify"
curl -fsSL "https://bootstrap.khandaq.org/nodes.json" | python3 -c \
  "import json,sys; d=json.load(sys.stdin); print('OK:', len(d['nodes']), 'nodes, updated', d['meta']['updated_at'])"

echo "Done: https://bootstrap.khandaq.org/nodes.json"
