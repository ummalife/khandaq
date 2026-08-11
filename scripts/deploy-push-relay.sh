#!/usr/bin/env bash
# Deploy Khandaq push relay to khandaq.org VPS
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REMOTE="${KHANDAQ_PUSH_REMOTE:-Khandaq}"
REMOTE_DIR="${KHANDAQ_PUSH_DIR:-/opt/khandaq-push}"
NGINX_CONF="$ROOT/infra/push/nginx-push.conf"

echo "==> Upload push relay"
ssh "$REMOTE" "mkdir -p '$REMOTE_DIR/secrets' && rm -rf '$REMOTE_DIR/firebase-service-account.json'"
scp -pr "$ROOT/infra/push/"* "$REMOTE:$REMOTE_DIR/"

echo "==> Build and start"
ssh "$REMOTE" bash -s <<REMOTE
set -euo pipefail
cd '$REMOTE_DIR'
if [[ ! -f .env ]]; then
  echo "WARNING: .env missing — copy secrets/push-relay.env.example and add firebase-service-account.json"
  touch .env
fi
docker compose build
docker compose up -d
docker compose ps
REMOTE

echo "==> Nginx vhost"
scp -p "$NGINX_CONF" "$REMOTE:/tmp/khandaq-push.nginx.conf"
ssh "$REMOTE" bash -s <<'REMOTE'
set -euo pipefail
CONF="/etc/nginx/sites-available/push.khandaq.org"
ENABLED="/etc/nginx/sites-enabled/push.khandaq.org"
BOOTSTRAP="/etc/nginx/sites-available/push.khandaq.org.http"
LIVE="/etc/letsencrypt/live/push.khandaq.org/fullchain.pem"
if [[ ! -f "$LIVE" ]]; then
  # KHANDAQ (audit2 #4): the port-80 bootstrap vhost serves ONLY the ACME webroot. It must never
  # proxy the relay, because wake URLs carry the FCM registration token (+ auth/ts) in the query.
  mkdir -p /var/www/push.khandaq.org
  cat > "$BOOTSTRAP" << 'EOF'
server {
    listen 80;
    listen [::]:80;
    server_name push.khandaq.org;
    location /.well-known/acme-challenge/ { root /var/www/push.khandaq.org; }
    # KHANDAQ (audit3 #2): this bootstrap vhost is live BEFORE the real conf, so the redacted
    # log_format does not exist yet (referencing it here would fail nginx -t and break the deploy).
    # Turn the access log off for the catch-all instead: a stray wake to http:// must not write its
    # FCM token into the global combined log. The ACME location above keeps logging (path only).
    location / { access_log off; return 404; }
}
EOF
  ln -sf "$BOOTSTRAP" "$ENABLED"
  nginx -t && systemctl reload nginx
  certbot certonly --webroot -w /var/www/push.khandaq.org -d push.khandaq.org \
    --non-interactive --agree-tos --register-unsafely-without-email || true
fi
# KHANDAQ (audit2 #4): no certificate -> FAIL the deploy. The old fallback installed an HTTP-only
# proxy vhost, so a failed issuance silently left the relay answering wakes in cleartext forever.
if [[ ! -f "$LIVE" ]]; then
  rm -f "$ENABLED" "$BOOTSTRAP" /etc/nginx/sites-enabled/push.khandaq.org.http
  systemctl reload nginx || true
  echo "FATAL: no TLS certificate for push.khandaq.org — refusing to publish the relay over HTTP." >&2
  echo "       Point DNS at this host, then re-run. The relay container is up but NOT exposed." >&2
  exit 1
fi
cp /tmp/khandaq-push.nginx.conf "$CONF"
rm -f "$BOOTSTRAP" /etc/nginx/sites-enabled/push.khandaq.org.http
ln -sf "$CONF" "$ENABLED"
nginx -t && systemctl reload nginx
REMOTE

echo "==> Verify"
# KHANDAQ (audit2 #4): HTTPS only — never fall back to probing (or blessing) a cleartext endpoint.
curl -fsSL "https://push.khandaq.org/health"
echo
echo "Done"
