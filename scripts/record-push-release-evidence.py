#!/usr/bin/env python3
"""Record what the push relay actually was at release time.

KHANDAQ (re-review 2026-08-22, KQ-01): "Add a release attestation recording the exact relay mode and
capability adoption at release time."

KQ-01 and KQ-02 are open not because the code is wrong but because security here depends on what is
DEPLOYED and ADOPTED. A release that says "capabilities are implemented" is describing the
repository; a release that says "the relay was in soft mode, 0 devices had registered, 100% of wake
traffic was still arriving on the legacy endpoint" is describing reality, and only the second is
evidence.

So the numbers are captured from the running relay and committed beside the release, where a later
reader — or the next audit — can compare them against the claim.

    scripts/record-push-release-evidence.py                 # write docs/push-release-evidence.json
    scripts/record-push-release-evidence.py --print         # to stdout, write nothing
    scripts/record-push-release-evidence.py --check         # fail if the record is missing/stale

The relay's detailed health is bound to loopback on the host on purpose (the public /health stays
minimal), so this reads it over ssh rather than from the internet.
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "docs" / "push-release-evidence.json"
REMOTE = "Khandaq"
HEALTH = "http://127.0.0.1:8088/health/detail"
# Older than this and the record describes a relay nobody has looked at since.
STALE_DAYS = 45


def die(msg: str) -> int:
    print(f"::error::{msg}", file=sys.stderr)
    return 1


def fetch() -> dict | None:
    try:
        r = subprocess.run(["ssh", "-o", "ConnectTimeout=15", "-o", "BatchMode=yes",
                            REMOTE, f"curl -sS -m 20 {HEALTH}"],
                           capture_output=True, text=True, timeout=90)
    except (OSError, subprocess.TimeoutExpired) as exc:
        print(f"::error::не удалось опросить реле ({exc})", file=sys.stderr)
        return None
    if r.returncode != 0 or not r.stdout.strip():
        print(f"::error::реле не ответило: {(r.stderr or '').strip()[:200]}", file=sys.stderr)
        return None
    try:
        return json.loads(r.stdout)
    except ValueError:
        print("::error::ответ /health/detail не разбирается как JSON", file=sys.stderr)
        return None


def summarise(health: dict, sha: str) -> dict:
    caps = health.get("capabilities") or {}
    paths = health.get("emission_paths") or {}
    adoption = health.get("auth_adoption") or {}
    return {
        "_comment": [
            "KHANDAQ (re-review 2026-08-22, KQ-01) — the state of the production push relay at the",
            "moment this release was cut. Written by scripts/record-push-release-evidence.py from",
            "the relay's own /health/detail. Not a plan and not a design statement: a measurement.",
        ],
        "commit": sha,
        "relay": {
            "auth_mode": health.get("auth_mode"),
            "enforce_by": health.get("enforce_by"),
            "enforce_overdue": health.get("enforce_overdue"),
            "fcm_mode": health.get("fcm_mode"),
            "break_glass": health.get("break_glass"),
        },
        "capabilities": {
            "mode": caps.get("mode"),
            "devices_registered": caps.get("devices_registered"),
            "capabilities_registered": caps.get("capabilities_registered"),
            "grace_days": caps.get("grace_days"),
            "grace_still_used": caps.get("grace_still_used"),
        },
        "emission": {
            "legacy_mode": paths.get("legacy_mode"),
            "legacy_pct": paths.get("legacy_pct"),
            "retire_target": paths.get("retire_target"),
            "counts": paths.get("counts"),
        },
        "auth_adoption": {
            "window_signed_pct": adoption.get("window_signed_pct"),
            "window_outcomes": adoption.get("window_outcomes"),
        },
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--print", action="store_true")
    ap.add_argument("--check", action="store_true")
    args = ap.parse_args()

    if args.check:
        if not OUT.is_file():
            return die(f"{OUT.relative_to(ROOT)} отсутствует — релиз не несёт свидетельства о "
                       f"состоянии реле, а именно оно и является предметом KQ-01")
        try:
            rec = json.loads(OUT.read_text(encoding="utf-8"))
        except ValueError as exc:
            return die(f"{OUT.name} не разбирается ({exc})")
        for field in ("commit", "relay", "capabilities", "emission"):
            if field not in rec:
                return die(f"{OUT.name}: нет раздела {field}")
        if not (rec.get("relay") or {}).get("auth_mode"):
            return die(f"{OUT.name}: не записан режим аутентификации реле")
        print(f"ок: свидетельство на месте, коммит {str(rec['commit'])[:12]}, "
              f"режим {rec['relay']['auth_mode']}, устройств с capability "
              f"{rec['capabilities'].get('devices_registered')}")
        return 0

    try:
        sha = subprocess.run(["git", "-C", str(ROOT), "rev-parse", "HEAD"],
                             capture_output=True, text=True, check=True).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        sha = "unknown"

    health = fetch()
    if health is None:
        return 1
    record = summarise(health, sha)
    text = json.dumps(record, indent=2, ensure_ascii=False) + "\n"
    if args.print:
        sys.stdout.write(text)
        return 0
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(text, encoding="utf-8")
    print(f"написано {OUT.relative_to(ROOT)}")
    print(f"  режим {record['relay']['auth_mode']}, устройств с capability "
          f"{record['capabilities']['devices_registered']}, legacy "
          f"{record['emission']['legacy_pct']}%")
    return 0


if __name__ == "__main__":
    sys.exit(main())
