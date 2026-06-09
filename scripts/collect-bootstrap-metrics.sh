#!/usr/bin/env bash
# Collect CPU/RAM/load from bootstrap VPS hosts (run on khandaq.org or locally with SSH keys)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REGISTRY="${REGISTRY:-$ROOT/config/khandaq_bootstrap_nodes.json}"
OUT="${OUT:-/var/log/khandaq/bootstrap-metrics.jsonl}"
TIMEOUT="${TIMEOUT:-10}"

mkdir -p "$(dirname "$OUT")"

python3 - "$REGISTRY" "$OUT" "$TIMEOUT" <<'PY'
import json, subprocess, sys, time

registry_path, out_path, timeout = sys.argv[1], sys.argv[2], int(sys.argv[3])
hosts = {}
for n in json.load(open(registry_path)).get("khandaq_owned_nodes", []):
    hosts[n["id"]] = n.get("ipv4") or n["host"]

ts = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
for node_id, host in hosts.items():
    row = {"ts": ts, "node_id": node_id, "host": host}
    try:
        cmd = [
            "ssh", "-o", "BatchMode=yes", "-o", f"ConnectTimeout={timeout}",
            f"root@{host}",
            "bash -lc 'echo CPU_LOAD=$(cat /proc/loadavg); "
            "echo MEM_TOTAL_KB=$(grep MemTotal /proc/meminfo | awk \"{print \\$2}\"); "
            "echo MEM_AVAIL_KB=$(grep MemAvailable /proc/meminfo | awk \"{print \\$2}\"); "
            "echo UPTIME_SEC=$(cut -d. -f1 /proc/uptime)'",
        ]
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout + 5)
        if r.returncode != 0:
            row["error"] = (r.stderr or r.stdout or "ssh failed").strip()[:200]
        else:
            for line in r.stdout.splitlines():
                if "=" in line:
                    k, v = line.split("=", 1)
                    row[k.lower()] = v.strip()
    except Exception as e:
        row["error"] = str(e)[:200]
    with open(out_path, "a", encoding="utf-8") as f:
        f.write(json.dumps(row) + "\n")
    status = "OK" if "error" not in row else "FAIL"
    print(f"{node_id:28} {host:22} [{status}]")
PY
