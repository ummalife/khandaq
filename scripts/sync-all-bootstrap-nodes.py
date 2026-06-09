#!/usr/bin/env python3
"""Merge Khandaq-owned bootstrap nodes into all client bundles (additive)."""
from __future__ import annotations

import json
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CONFIG = ROOT / "config" / "khandaq_bootstrap_nodes.json"

NODES_JSON_TARGETS = [
    ROOT / "khandaq-desktop/res/nodes.json",
    ROOT / "khandaq-ios/local_pod_repo/objcTox/Classes/Public/Manager/nodes.json",
    ROOT / "khandaq-android/atox/src/main/res/raw/nodes.json",
]


def is_valid_key(key: str) -> bool:
    return bool(key) and key != "TBD" and len(key) == 64


def node_host(n: dict) -> str:
    ipv4 = n.get("ipv4", "")
    if ipv4 and ipv4 not in ("-", "TBD"):
        return ipv4
    return n.get("host", "")


def khandaq_to_nodes_json_entry(n: dict, ts: int) -> dict:
    ipv6 = n.get("ipv6", "-")
    if ipv6 in ("-", "TBD", None, ""):
        ipv6 = "-"
    tcp_ports = sorted({int(p) for p in (n.get("tcp_ports") or []) if p})
    port = int(n.get("port", 33445))
    if port not in tcp_ports:
        tcp_ports = sorted({port, *tcp_ports})
    return {
        "ipv4": node_host(n),
        "ipv6": ipv6,
        "port": port,
        "tcp_ports": tcp_ports,
        "public_key": n["public_key"].upper(),
        "maintainer": "Khandaq",
        "location": n.get("location", "??"),
        "status_udp": bool(n.get("status_udp", True)),
        "status_tcp": bool(n.get("status_tcp", True)),
        "version": "1000002024",
        "motd": n.get("motd", "Khandaq bootstrap — https://khandaq.org"),
        "last_ping": ts,
    }


def merge_nodes_json(path: Path, khandaq_nodes: list[dict], ts: int) -> int:
    data = json.loads(path.read_text())
    existing = data.get("nodes", [])
    merged: list[dict] = []
    seen: set[str] = set()

    for n in khandaq_nodes:
        entry = khandaq_to_nodes_json_entry(n, ts)
        pk = entry["public_key"].upper()
        if pk in seen:
            continue
        merged.append(entry)
        seen.add(pk)

    for n in existing:
        pk = str(n.get("public_key", "")).upper()
        if not pk or pk in seen:
            continue
        merged.append(n)
        seen.add(pk)

    data["nodes"] = merged
    data["last_scan"] = ts
    data["last_refresh"] = ts
    path.write_text(json.dumps(data, indent=2) + "\n")
    return len(khandaq_nodes)


def main() -> int:
    data = json.loads(CONFIG.read_text())
    khandaq = [
        n for n in data.get("khandaq_owned_nodes", [])
        if n.get("status", "active") == "active" and is_valid_key(n.get("public_key", ""))
    ]
    if not khandaq:
        print("No active Khandaq-owned nodes in registry", file=sys.stderr)
        return 1

    ts = int(time.time())
    print(f"Khandaq nodes ({len(khandaq)}) — additive merge, public fallbacks kept")

    for path in NODES_JSON_TARGETS:
        if not path.exists():
            print(f"SKIP missing: {path}", file=sys.stderr)
            continue
        added = merge_nodes_json(path, khandaq, ts)
        print(f"  {path.relative_to(ROOT)}: prepended {added}, total nodes in file")

    android_sync = ROOT / "scripts/sync-android-bootstrap-nodes.py"
    print("Running Android TRIfA BootstrapNodeEntryDB sync…")
    subprocess.run([sys.executable, str(android_sync)], check=True)

    print("Done.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
