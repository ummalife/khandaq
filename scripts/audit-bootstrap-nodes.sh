#!/usr/bin/env bash
# Audit Tox bootstrap nodes — fetch nodes.tox.chat + TCP probe
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${OUT:-$ROOT/docs/bootstrap-audit-latest.json}"
URL="${NODES_URL:-https://nodes.tox.chat/json}"

curl -fsSL "$URL" -o /tmp/nodes_tox_chat_audit.json
python3 - "$OUT" <<'PY'
import json, socket, sys, time

out_path = sys.argv[1]
api = json.load(open('/tmp/nodes_tox_chat_audit.json'))

def tcp_ok(host, port, timeout=3):
    if host in ('-', 'NONE', ''):
        return False
    try:
        s = socket.create_connection((host, port), timeout)
        s.close()
        return True
    except Exception:
        return False

rows = []
for n in api.get('nodes', []):
    host = n['ipv4'] if n['ipv4'] not in ('-', 'NONE') else n.get('ipv6', '-')
    if host in ('-', 'NONE', ''):
        continue
    ports = [n['port']] + list(n.get('tcp_ports') or [])
    live_tcp = any(tcp_ok(host, p) for p in ports)
    rows.append({
        'host': host,
        'maintainer': n.get('maintainer'),
        'location': n.get('location'),
        'api_udp': n.get('status_udp'),
        'api_tcp': n.get('status_tcp'),
        'live_tcp': live_tcp,
        'last_ping': n.get('last_ping'),
        'public_key': n.get('public_key'),
    })

udp_online = [r for r in rows if r['api_udp']]
report = {
    'probed_at': int(time.time()),
    'source': 'https://nodes.tox.chat/json',
    'api_last_scan': api.get('last_scan'),
    'summary': {
        'total': len(rows),
        'api_udp_online': len(udp_online),
        'api_dead': sum(1 for r in rows if not r['api_udp'] and not r['api_tcp']),
        'live_tcp': sum(1 for r in rows if r['live_tcp']),
    },
    'nodes': rows,
}
json.dump(report, open(out_path, 'w'), indent=2)
print(json.dumps(report['summary'], indent=2))
PY

echo "Report: $OUT"
