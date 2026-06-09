#!/usr/bin/env bash
# Configure Uptime Kuma monitors on khandaq.org server (Socket.IO API).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PY="${KUMA_SETUP_PY:-$SCRIPT_DIR/setup-uptime-kuma.py}"
VENV="${KUMA_VENV:-/opt/khandaq-monitoring/venv}"

export KUMA_URL="${KUMA_URL:-http://127.0.0.1:3001}"
export KUMA_ADMIN_USER="${KUMA_ADMIN_USER:-khandaq-ops}"
export KUMA_ADMIN_PASS="${KUMA_ADMIN_PASS:-$(openssl rand -base64 18 | tr -d '/+=' | head -c 20)}"
export KUMA_TELEGRAM_TOKEN="${KUMA_TELEGRAM_TOKEN:-}"
export KUMA_TELEGRAM_CHAT="${KUMA_TELEGRAM_CHAT:-}"
export KUMA_SMTP_HOST="${KUMA_SMTP_HOST:-}"
export KUMA_SMTP_PORT="${KUMA_SMTP_PORT:-587}"
export KUMA_SMTP_USER="${KUMA_SMTP_USER:-}"
export KUMA_SMTP_PASS="${KUMA_SMTP_PASS:-}"
export KUMA_SMTP_TO="${KUMA_SMTP_TO:-}"
export KUMA_CREDS_FILE="${KUMA_CREDS_FILE:-/opt/khandaq-monitoring/kuma-admin.env}"

if [[ ! -x "$VENV/bin/python" ]]; then
  python3 -m venv "$VENV"
  "$VENV/bin/pip" install -q uptime-kuma-api
fi

exec "$VENV/bin/python" "$PY"
