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
if [[ ! -f /etc/letsencrypt/live/push.khandaq.org/fullchain.pem ]]; then
  mkdir -p /var/www/push.khandaq.org
  cat > /etc/nginx/sites-available/push.khandaq.org.http << 'EOF'
server {
    listen 80;
    listen [::]:80;
    server_name push.khandaq.org;
    location / { proxy_pass http://127.0.0.1:8088; }
}
EOF
  ln -sf /etc/nginx/sites-available/push.khandaq.org.http /etc/nginx/sites-enabled/push.khandaq.org
  nginx -t && systemctl reload nginx
  certbot certonly --webroot -w /var/www/push.khandaq.org -d push.khandaq.org \
    --non-interactive --agree-tos --register-unsafely-without-email || true
fi
if [[ -f /etc/letsencrypt/live/push.khandaq.org/fullchain.pem ]]; then
  cp /tmp/khandaq-push.nginx.conf "$CONF"
  rm -f /etc/nginx/sites-enabled/push.khandaq.org.http
else
  echo "SSL cert missing — keeping HTTP-only vhost (add DNS push.khandaq.org first)"
  cat > "$CONF" << 'EOF'
server {
    listen 80;
    listen [::]:80;
    server_name push.khandaq.org;
    location / { proxy_pass http://127.0.0.1:8088; }
}
EOF
fi
ln -sf "$CONF" "$ENABLED"
nginx -t && systemctl reload nginx
REMOTE

echo "==> Verify"
curl -fsSL "https://push.khandaq.org/health" 2>/dev/null || curl -fsSL "http://push.khandaq.org/health" || echo "DNS/cert pending"
echo "Done"
