#!/usr/bin/env bash
# Deploy Uptime Kuma on khandaq.org main server (localhost:3001, SSH tunnel for UI)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REMOTE="${KHANDAQ_MONITOR_REMOTE:-Khandaq}"
REMOTE_DIR="${KHANDAQ_MONITOR_DIR:-/opt/khandaq-monitoring}"

echo "==> Upload monitoring stack"
ssh "$REMOTE" "mkdir -p '$REMOTE_DIR'"
scp -p "$ROOT/infra/monitoring/docker-compose.yml" "$REMOTE:$REMOTE_DIR/"
scp -p "$ROOT/scripts/collect-bootstrap-metrics.sh" "$REMOTE:$REMOTE_DIR/" 2>/dev/null || true

echo "==> Start Uptime Kuma"
ssh "$REMOTE" bash -s <<REMOTE
set -euo pipefail
cd '$REMOTE_DIR'
if ! command -v docker >/dev/null; then
  echo "Docker not installed on server — install docker.io first" >&2
  exit 1
fi
docker compose up -d
docker compose ps
REMOTE

echo "==> Done"
echo "    UI: ssh -L 3001:127.0.0.1:3001 $REMOTE  →  http://localhost:3001"
echo "    Add monitors: TCP bootstrap1/2/3.khandaq.org:33445 + ping"
echo "    Host metrics: cron collect-bootstrap-metrics.sh (see docs/MONITORING_REPORT.md)"
