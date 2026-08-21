#!/usr/bin/env bash
# Deploy Khandaq push relay to khandaq.org VPS
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REMOTE="${KHANDAQ_PUSH_REMOTE:-Khandaq}"
REMOTE_DIR="${KHANDAQ_PUSH_DIR:-/opt/khandaq-push}"
NGINX_CONF="$ROOT/infra/push/nginx-push.conf"

# KHANDAQ (re-audit 2026-08-21, R-02): the authentication mode must be CHOSEN, not inherited.
#
# The relay ran soft because a variable was missing, not because anyone decided it should. That is
# the whole finding: "authentication present but not enforced" was the default state of a fresh
# deployment or a rollback, and nothing on the way to production ever asked.
#
# Soft is still correct TODAY — no shipped client signs, the push secret is empty in every release
# build, so enforcing would 401 every wake in the field. So this does not change the mode. It makes
# the mode a decision with a name on it, and then checks the running relay agrees.
MODE="${KHANDAQ_PUSH_AUTH_MODE:-}"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --enforce) MODE=enforce ;;
    --soft)    MODE=soft ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
  shift
done
case "$MODE" in
  enforce) COMPOSE_MODE_FILE=docker-compose.prod.yml;     EXPECT_MODE=enforce; ENFORCE_VALUE=1 ;;
  soft)    COMPOSE_MODE_FILE=docker-compose.softmode.yml; EXPECT_MODE=soft;    ENFORCE_VALUE=0 ;;
  *)
    cat >&2 <<'USAGE'
No push-auth mode chosen. Pick one deliberately:

  scripts/deploy-push-relay.sh --soft      accept unsigned requests (needs PUSH_AUTH_ENFORCE_BY set
                                           in the relay .env: soft is a dated exception, not a default)
  scripts/deploy-push-relay.sh --enforce   reject unsigned/invalid requests with 401

As of the 2026-08-21 re-audit --soft is the only one that works: no shipped client signs, so
--enforce would silence every push notification in the field. See PUSH-AUTH-SECRET-PROVISIONING.md.
USAGE
    exit 2
    ;;
esac
echo "==> push auth mode: $MODE ($COMPOSE_MODE_FILE)"

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
# The chosen overlay is symlinked to docker-compose.override.yml, which compose picks up on its own.
# That makes the mode STICKY: a bare \`docker compose up -d\` typed by hand six months from now cannot
# quietly fall back to whatever .env happens to say.
ln -sfn '$COMPOSE_MODE_FILE' docker-compose.override.yml
export PUSH_AUTH_ENFORCE='$ENFORCE_VALUE'
docker compose build
docker compose up -d
docker compose ps

# KHANDAQ (audit 2026-08-21, K-05): the closure criteria for the container hardening are "runs
# non-root" and "starts successfully with only the explicitly required writable paths". Assert both
# here rather than assuming the compose file was applied — a stale container that compose decided not
# to recreate would otherwise keep running as root with a writable root filesystem, and nothing would
# say so.
# KHANDAQ (production deploy 2026-08-21): every command below redirects stdin from /dev/null, and
# that is not cosmetic - without it NONE OF THESE CHECKS RUN.
#
# This whole block is fed to `ssh bash -s` from a heredoc, so the remote shell's stdin IS the rest of
# this script. `docker compose exec -T` reads stdin. The first exec therefore swallowed every
# remaining line, the remote bash reached end-of-input and exited 0 - silently, with `set -euo
# pipefail` in force, because running out of script is not an error.
#
# So the deploy printed "==> Verify the hardening actually took effect" and then verified nothing:
# not non-root, not the read-only root filesystem, not /data, not the auth mode. It had never
# verified them in any deploy since the checks were written. That all four happened to be true was
# established by checking the host by hand, afterwards.
#
# The lesson outlasts the redirect: an assertion that cannot fail looks exactly like one that passes.
# Make a new check here fail once on purpose before trusting it.
echo "==> Verify the hardening actually took effect"
uid=\$(docker compose exec -T push-relay id -u </dev/null | tr -d '\r')
if [[ "\$uid" == "0" ]]; then
  echo "ERROR: the relay is running as root — the container was not recreated with the new image" >&2
  exit 1
fi
echo "    running as uid \$uid (non-root)"

if docker compose exec -T push-relay sh -c 'touch /app/.writetest 2>/dev/null' </dev/null; then
  echo "ERROR: / is writable inside the container — read_only did not take effect" >&2
  exit 1
fi
echo "    root filesystem is read-only"

# /data must be writable, or the relay cannot keep its replay store, rate window or adoption counters.
docker compose exec -T push-relay sh -c 'touch /data/.writetest && rm -f /data/.writetest' </dev/null \
  || { echo "ERROR: /data is not writable by uid \$uid — check the push-relay-data volume" >&2; exit 1; }
echo "    /data is writable"

# KHANDAQ (re-audit 2026-08-21, R-02): assert the RUNNING relay is in the mode that was asked for.
# Same reasoning as the non-root and read-only assertions above: a container compose decided not to
# recreate can keep serving in the old mode, and nothing else would ever say so.
health=\$(docker compose exec -T push-relay python -c   "import urllib.request;print(urllib.request.urlopen('http://127.0.0.1:8080/health',timeout=5).read().decode())" </dev/null 2>/dev/null || true)
case "\$health" in
  *'"auth_mode": "$EXPECT_MODE"'*|*'"auth_mode":"$EXPECT_MODE"'*)
    echo "    /health reports auth_mode=$EXPECT_MODE" ;;
  *)
    echo "ERROR: asked for $EXPECT_MODE but /health does not report it:" >&2
    echo "\$health" >&2
    exit 1 ;;
esac

# KHANDAQ (production deploy 2026-08-21): a relay that starts with an ERROR in its log has something
# wrong that none of the assertions above are shaped to see. The first real deploy of the hardened
# image logged "Control server error: [Errno 30] Read-only file system: '/.gunicorn'" on every boot,
# and every check above still passed - non-root, read-only, /data writable, auth_mode soft.
#
# Every ERROR this relay emits is a deploy-stopping condition by construction: no auth secret
# configured, a service account it cannot read, soft mode past its own cutoff. So treat any of them
# as a failed deploy rather than as log colour.
echo "==> The relay must start clean"
sleep 2
errs=\$(docker compose logs --no-color --since 3m </dev/null 2>/dev/null | grep -F '[ERROR]' || true)
if [[ -n "\$errs" ]]; then
  echo "ERROR: the relay logged errors at startup:" >&2
  echo "\$errs" >&2
  exit 1
fi
echo "    no errors in the startup log"
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
