#!/usr/bin/env bash
# Deploy Khandaq bootstrap node via Docker Compose (unified method for all VPS)
# Usage: sudo KHANDAQ_NODE_ID=khandaq-bootstrap-eu-1 KHANDAQ_NODE_HOST=bootstrap1.khandaq.org ./deploy-node.sh
set -euo pipefail

NODE_ID="${KHANDAQ_NODE_ID:?KHANDAQ_NODE_ID required}"
NODE_HOST="${KHANDAQ_NODE_HOST:-$NODE_ID}"
MOTD="Khandaq ${NODE_ID} — https://khandaq.org"
REMOTE_DIR="${REMOTE_DIR:-/opt/khandaq-bootstrap}"
LOG_FILE="${LOG_FILE:-/var/log/khandaq-bootstrap/deploy-$(date -u +%Y%m%dT%H%M%SZ).log}"

mkdir -p "$(dirname "$LOG_FILE")"
exec > >(tee -a "$LOG_FILE") 2>&1

echo "=== Khandaq bootstrap deploy (docker compose): $NODE_ID ==="
date -u

if [[ "${EUID:-$(id -u)}" -ne 0 ]]; then
  echo "Run as root" >&2
  exit 1
fi

export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq curl ca-certificates ufw unattended-upgrades python3 || true
apt-get install -y -qq fail2ban 2>/dev/null || true
if ! command -v docker >/dev/null; then
  apt-get install -y -qq docker.io || true
fi
if ! docker compose version >/dev/null 2>&1; then
  apt-get install -y -qq docker-compose-plugin 2>/dev/null \
    || apt-get install -y -qq docker-compose-v2 2>/dev/null \
    || apt-get install -y -qq docker-compose 2>/dev/null \
    || true
fi
systemctl enable docker >/dev/null 2>&1 || true
systemctl start docker 2>/dev/null || true

if docker compose version >/dev/null 2>&1; then
  COMPOSE="docker compose"
elif command -v docker-compose >/dev/null; then
  COMPOSE="docker-compose"
else
  echo "Installing docker compose standalone..." >&2
  curl -fsSL "https://github.com/docker/compose/releases/download/v2.39.1/docker-compose-$(uname -s)-$(uname -m)" \
    -o /usr/local/bin/docker-compose
  chmod +x /usr/local/bin/docker-compose
  COMPOSE="docker-compose"
fi

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
WORKDIR="${REMOTE_DIR}"
mkdir -p "$WORKDIR"
if [[ "$SCRIPT_DIR" != "$WORKDIR" ]]; then
  cp "$SCRIPT_DIR/Dockerfile" "$SCRIPT_DIR/docker-compose.yml" "$SCRIPT_DIR/bootstrap-daemon.conf" "$WORKDIR/"
fi

# Per-node MOTD in config
sed -i "s|^motd = .*|motd = \"${MOTD}\"|" "$WORKDIR/bootstrap-daemon.conf"

cd "$WORKDIR"
$COMPOSE build --no-cache
$COMPOSE up -d
sleep 5

if command -v ufw >/dev/null; then
  ufw allow 22/tcp comment 'SSH' >/dev/null 2>&1 || true
  ufw allow 33445/udp comment 'Khandaq Tox bootstrap UDP' >/dev/null 2>&1 || true
  ufw allow 33445/tcp comment 'Khandaq Tox bootstrap TCP' >/dev/null 2>&1 || true
  ufw allow 443/tcp comment 'Tox TCP relay' >/dev/null 2>&1 || true
  ufw allow 3389/tcp comment 'Tox TCP relay' >/dev/null 2>&1 || true
  ufw --force enable >/dev/null 2>&1 || true
fi

SSHD=/etc/ssh/sshd_config.d/99-khandaq-hardening.conf
if [[ ! -f "$SSHD" ]]; then
  cat >"$SSHD" <<'SSHD_EOF'
MaxAuthTries 5
LoginGraceTime 60
ClientAliveInterval 300
ClientAliveCountMax 2
X11Forwarding no
SSHD_EOF
  systemctl reload sshd 2>/dev/null || systemctl reload ssh 2>/dev/null || true
fi

systemctl enable fail2ban >/dev/null 2>&1 || true
systemctl restart fail2ban >/dev/null 2>&1 || true

cat >/etc/apt/apt.conf.d/20auto-upgrades <<'EOF'
APT::Periodic::Update-Package-Lists "1";
APT::Periodic::Unattended-Upgrade "1";
EOF

PUBLIC_KEY=$(docker logs khandaq-bootstrap 2>&1 | sed -n 's/.*Public Key: \([0-9A-F]*\).*/\1/p' | tail -1)
if [[ -z "$PUBLIC_KEY" ]]; then
  VOL=$(docker volume inspect khandaq-bootstrap_khandaq-bootstrap-keys -f '{{.Mountpoint}}' 2>/dev/null || true)
  if [[ -n "$VOL" && -f "$VOL/keys" ]]; then
    PUBLIC_KEY=$(od -An -tx1 -N32 "$VOL/keys" | tr -d ' \n' | tr '[:lower:]' '[:upper:]')
  fi
fi

echo "=== DEPLOY RESULT ==="
echo "node_id=$NODE_ID"
echo "hostname=$(hostname -f 2>/dev/null || hostname)"
echo "host=$NODE_HOST"
echo "ipv4=$(curl -4 -s --max-time 5 ifconfig.me || true)"
echo "ipv6=$(curl -6 -s --max-time 5 ifconfig.me || true)"
echo "port=33445"
echo "public_key=$PUBLIC_KEY"
echo "container_status=$(docker inspect -f '{{.State.Status}}' khandaq-bootstrap 2>/dev/null || echo unknown)"
ss -ulnp | grep ':33445' || true
ss -tlnp | grep -E ':(33445|443|3389)' || true
docker logs khandaq-bootstrap 2>&1 | tail -15
