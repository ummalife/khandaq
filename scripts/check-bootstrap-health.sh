#!/usr/bin/env bash
# Khandaq bootstrap health check (local or remote nodes from registry)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REGISTRY="${REGISTRY:-$ROOT/config/khandaq_bootstrap_nodes.json}"
TIMEOUT="${TIMEOUT:-5}"

if [[ ! -f "$REGISTRY" ]]; then
  echo "Registry not found: $REGISTRY" >&2
  exit 1
fi

python3 - "$REGISTRY" "$TIMEOUT" <<'PY'
import json, socket, subprocess, sys, time

registry_path, timeout_s = sys.argv[1], int(sys.argv[2])
data = json.load(open(registry_path))
nodes = data.get("khandaq_owned_nodes", [])
if not nodes:
    print("No khandaq_owned_nodes in registry")
    sys.exit(1)

def tcp_ok(host, port, timeout):
    try:
        s = socket.create_connection((host, port), timeout)
        s.close()
        return True
    except Exception:
        return False

def udp_probe(host, port):
    try:
        r = subprocess.run(
            ["bash", "-c", f"nc -z -u -w{timeout_s} {host} {port} 2>/dev/null"],
            capture_output=True, timeout=timeout_s + 2,
        )
        return r.returncode == 0
    except Exception:
        return False

print(f"Khandaq bootstrap health — {time.strftime('%Y-%m-%d %H:%M:%S UTC', time.gmtime())}")
print("-" * 72)
fail = 0
for n in nodes:
    host = n.get("ipv4") or n.get("host")
    port = int(n.get("port", 33445))
    pk = n.get("public_key", "?")[:16]
    tcp = tcp_ok(host, port, timeout_s)
    udp = udp_probe(host, port)
    status = "OK" if tcp else "FAIL"
    if not tcp:
        fail += 1
    print(f"{n.get('id','?'):28} {host:18} :{port}  TCP={'yes' if tcp else 'no '} UDP={'yes' if udp else 'no '}  key={pk}…  [{status}]")

print("-" * 72)
if fail:
    print(f"RESULT: FAIL ({fail} node(s) unreachable on TCP)")
    sys.exit(1)
print("RESULT: OK")
PY
