#!/usr/bin/env python3
"""Generate nodes.tox.chat-compatible bootstrap registry from Khandaq config."""
from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CONFIG = ROOT / "config" / "khandaq_bootstrap_nodes.json"
DEFAULT_OUTPUT = ROOT / "dist" / "bootstrap" / "nodes.json"


def owned_to_node(entry: dict, now: int) -> dict:
    host = entry.get("ipv4") or entry.get("host", "")
    tcp_ports = list(entry.get("tcp_ports") or [])
    if entry.get("port") and entry["port"] not in tcp_ports:
        tcp_ports.insert(0, int(entry["port"]))
    return {
        "ipv4": host,
        "ipv6": entry.get("ipv6", "-"),
        "port": int(entry.get("port", 33445)),
        "tcp_ports": tcp_ports,
        "public_key": entry["public_key"],
        "maintainer": "Khandaq",
        "location": entry.get("location", "?"),
        "status_udp": bool(entry.get("status_udp", True)),
        "status_tcp": bool(entry.get("status_tcp", True)),
        "version": "1000002024",
        "motd": entry.get("motd", "Khandaq bootstrap — https://khandaq.org"),
        "last_ping": now,
    }


def dedupe_nodes(nodes: list[dict]) -> list[dict]:
    seen: set[str] = set()
    out: list[dict] = []
    for node in nodes:
        key = node.get("public_key", "")
        if not key or key in seen:
            continue
        seen.add(key)
        out.append(node)
    return out


def generate(config_path: Path, output_path: Path) -> dict:
    data = json.loads(config_path.read_text(encoding="utf-8"))
    now = int(time.time())

    owned = [owned_to_node(n, now) for n in data.get("khandaq_owned_nodes", [])]
    public = list(data.get("public_bootstrap_nodes", []))
    for node in public:
        node.setdefault("last_ping", now)

    registry = {
        "last_scan": now,
        "last_refresh": now,
        "nodes": dedupe_nodes(owned + public),
        "meta": {
            "source": "https://bootstrap.khandaq.org/nodes.json",
            "schema": "nodes.tox.chat-compatible",
            "schema_version": data.get("schema_version", 1),
            "updated_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(now)),
            "khandaq_owned_count": len(owned),
            "total_count": len(owned) + len(public),
        },
    }

    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(
        json.dumps(registry, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    return registry


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "-c",
        "--config",
        type=Path,
        default=DEFAULT_CONFIG,
        help=f"Input config (default: {DEFAULT_CONFIG})",
    )
    parser.add_argument(
        "-o",
        "--output",
        type=Path,
        default=DEFAULT_OUTPUT,
        help=f"Output nodes.json (default: {DEFAULT_OUTPUT})",
    )
    args = parser.parse_args()

    if not args.config.is_file():
        print(f"Config not found: {args.config}", file=sys.stderr)
        return 1

    registry = generate(args.config, args.output)
    print(
        f"Wrote {args.output} — {registry['meta']['khandaq_owned_count']} Khandaq + "
        f"{registry['meta']['total_count'] - registry['meta']['khandaq_owned_count']} public "
        f"= {len(registry['nodes'])} unique nodes"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
