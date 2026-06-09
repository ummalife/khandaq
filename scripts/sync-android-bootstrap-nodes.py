#!/usr/bin/env python3
"""Sync BootstrapNodeEntryDB.java from config/khandaq_bootstrap_nodes.json."""
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CONFIG = ROOT / "config" / "khandaq_bootstrap_nodes.json"
TARGET = ROOT / "khandaq-android-trifa/android-refimpl-app/app/src/main/java/com/zoffcc/applications/trifa/BootstrapNodeEntryDB.java"


def is_valid_key(key: str) -> bool:
    return key and key != "TBD" and len(key) == 64


def node_host(n: dict) -> str:
    ipv4 = n.get("ipv4", "")
    if ipv4 and ipv4 not in ("-", "TBD"):
        return ipv4
    return n.get("host", "")


def gen_udp_lines(nodes: list) -> list[str]:
    lines = []
    num = 0
    for n in nodes:
        host = node_host(n)
        key = n.get("public_key", "")
        port = n.get("port", 33445)
        if not host or not is_valid_key(key):
            continue
        lines.append(
            f'        n = BootstrapNodeEntryDB_(true, num_, "{host}",{port},"{key}");insert_node_into_db_real(n);num_++;'
        )
        num += 1
        ipv6 = n.get("ipv6", "")
        if ipv6 and ipv6 not in ("-", "TBD"):
            lines.append(
                f'        n = BootstrapNodeEntryDB_(true, num_, "{ipv6}",{port},"{key}");insert_node_into_db_real(n);num_++;'
            )
            num += 1
    return lines


def gen_tcp_lines(nodes: list) -> list[str]:
    lines = []
    num = 0
    for n in nodes:
        host = node_host(n)
        key = n.get("public_key", "")
        if not host or not is_valid_key(key):
            continue
        ports = set()
        if n.get("port"):
            ports.add(int(n["port"]))
        for p in n.get("tcp_ports") or []:
            ports.add(int(p))
        for port in sorted(ports):
            lines.append(
                f'        n = BootstrapNodeEntryDB_(false, num_, "{host}",{port},"{key}");insert_node_into_db_real(n);num_++;'
            )
            num += 1
        ipv6 = n.get("ipv6", "")
        if ipv6 and ipv6 not in ("-", "TBD"):
            for port in sorted(ports):
                lines.append(
                    f'        n = BootstrapNodeEntryDB_(false, num_, "{ipv6}",{port},"{key}");insert_node_into_db_real(n);num_++;'
                )
                num += 1
    return lines


def patch_method(src: str, method: str, body_lines: list[str]) -> str:
    pattern = rf"(public static void {method}\(\)\s*\{{.*?// @formatter:off\n)(.*?)(\n\s*// @formatter:on)"
    repl = r"\1" + "\n".join(body_lines) + r"\3"
    new_src, n = re.subn(pattern, repl, src, count=1, flags=re.DOTALL)
    if n != 1:
        raise SystemExit(f"Failed to patch {method}")
    return new_src


def main():
    data = json.loads(CONFIG.read_text())
    khandaq = [n for n in data.get("khandaq_owned_nodes", []) if n.get("status") != "planned" or is_valid_key(n.get("public_key", ""))]
    khandaq_valid = [n for n in data.get("khandaq_owned_nodes", []) if is_valid_key(n.get("public_key", ""))]
    public = data.get("public_bootstrap_nodes", [])

    # Order: Khandaq owned first, then public fallback
    ordered = khandaq_valid + public

    src = TARGET.read_text()
    src = patch_method(src, "insert_default_udp_nodes_into_db", gen_udp_lines(ordered))
    src = patch_method(src, "insert_default_tcprelay_nodes_into_db", gen_tcp_lines(ordered))
    TARGET.write_text(src)
    print(f"Synced {len(gen_udp_lines(ordered))} UDP + {len(gen_tcp_lines(ordered))} TCP relay entries")


if __name__ == "__main__":
    main()
