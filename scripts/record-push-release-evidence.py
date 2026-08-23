#!/usr/bin/env python3
"""Record what the push relay actually was at release time — one immutable file per release.

KHANDAQ (re-review v2 2026-08-22, RR2-01/RR2-02). The first version of this wrote a single mutable
docs/push-release-evidence.json. That file described commit 62f2c6f while the tree was at 0a0d5559,
and nothing failed — which is the whole defect: a mutable record that can silently describe the
PREVIOUS release is indistinguishable from one nobody updated, and it is used to decide when to turn
on push enforcement.

So evidence is now written once per release, named for the release it describes, and never rewritten:

    docs/release-evidence/push-0.2.42-0a0d55596a1d.json

and `--check` refuses a release whose manifest does not have matching evidence. The chicken-and-egg
in "the evidence must name the release commit" is resolved by keying on the RELEASE (versionName,
versionCode, iOS build) rather than on a SHA that cannot exist until after the file is committed:
the recorded commit must merely be an ancestor of HEAD, while the release block must match the
manifest exactly. A stale file therefore fails on the version, not on a hash race.

    scripts/record-push-release-evidence.py            # write evidence for the current release
    scripts/record-push-release-evidence.py --print    # to stdout, write nothing
    scripts/record-push-release-evidence.py --check    # fail if the release has no matching evidence

The relay's detailed health is bound to loopback on the host on purpose (the public /health stays
minimal), so this reads it over ssh rather than from the internet.
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT_DIR = ROOT / "docs" / "release-evidence"
MANIFEST = ROOT / "web" / "release-manifest.json"
REMOTE = "Khandaq"
HEALTH = "http://127.0.0.1:8088/health/detail"
# Older than this and the record describes a relay nobody has looked at since.
STALE_DAYS = 45


def die(msg: str) -> int:
    print(f"::error::{msg}", file=sys.stderr)
    return 1


def git(*args: str) -> str | None:
    try:
        r = subprocess.run(["git", "-C", str(ROOT), *args], capture_output=True, text=True)
    except OSError:
        return None
    return r.stdout.strip() if r.returncode == 0 else None


def release_identity() -> dict:
    """The release this evidence is about, taken from the manifest the site publishes."""
    m = json.loads(MANIFEST.read_text(encoding="utf-8"))
    return {
        "android": {
            "versionName": m["android"]["versionName"],
            "versionCode": m["android"]["versionCode"],
        },
        "ios": {
            "marketingVersion": m["ios"]["marketingVersion"],
            "buildNumber": m["ios"]["buildNumber"],
        },
    }


def evidence_path(rel: dict, sha: str) -> Path:
    return OUT_DIR / f"push-{rel['android']['versionName']}-{sha[:12]}.json"


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


def summarise(health: dict, sha: str, rel: dict) -> dict:
    caps = health.get("capabilities") or {}
    paths = health.get("emission_paths") or {}
    adoption = health.get("auth_adoption") or {}
    return {
        "_comment": [
            "KHANDAQ (re-review v2 2026-08-22, RR2-01/RR2-02) — the state of the production push",
            "relay at the moment this release was cut. Written by",
            "scripts/record-push-release-evidence.py from the relay's own /health/detail.",
            "Not a plan and not a design statement: a measurement. Immutable once committed —",
            "a later release gets a new file, it never edits this one.",
        ],
        "measured_at": dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat(),
        "commit": sha,
        "release": rel,
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


def check() -> int:
    if not MANIFEST.is_file():
        return die("нет web/release-manifest.json — не с чем сверять свидетельство")
    rel = release_identity()
    want_name = rel["android"]["versionName"]

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    candidates = sorted(OUT_DIR.glob(f"push-{want_name}-*.json"))
    if not candidates:
        have = ", ".join(p.name for p in sorted(OUT_DIR.glob("push-*.json"))) or "ни одного"
        return die(
            f"для релиза {want_name} (versionCode {rel['android']['versionCode']}, "
            f"iOS build {rel['ios']['buildNumber']}) нет свидетельства о состоянии реле. "
            f"Есть: {have}. Запустите scripts/record-push-release-evidence.py — релиз, который "
            f"не несёт измерения реле, не даёт оснований ни включать enforce, ни оставлять soft.")

    problems: list[str] = []
    matched = None
    for path in candidates:
        try:
            rec = json.loads(path.read_text(encoding="utf-8"))
        except ValueError as exc:
            problems.append(f"{path.name}: не разбирается ({exc})")
            continue
        for field in ("measured_at", "commit", "release", "relay", "capabilities", "emission"):
            if field not in rec:
                problems.append(f"{path.name}: нет раздела {field}")
                break
        else:
            if rec["release"] != rel:
                problems.append(
                    f"{path.name}: описывает релиз {json.dumps(rec['release'], ensure_ascii=False)}, "
                    f"а публикуется {json.dumps(rel, ensure_ascii=False)}")
                continue
            if not (rec.get("relay") or {}).get("auth_mode"):
                problems.append(f"{path.name}: не записан режим аутентификации реле")
                continue
            matched = (path, rec)
            break

    if matched is None:
        for p in problems:
            print(f"::error::{p}", file=sys.stderr)
        return die(f"ни одно свидетельство не описывает публикуемый релиз {want_name}")

    path, rec = matched

    # Записанный коммит должен быть предком HEAD: иначе свидетельство снято с ветки, которой в
    # истории релиза нет, и измерение относится не к тому дереву.
    sha = rec["commit"]
    if re.fullmatch(r"[0-9a-f]{7,40}", str(sha)):
        if git("rev-parse", "--verify", f"{sha}^{{commit}}") is None:
            print(f"::warning::коммит {sha[:12]} из {path.name} не найден локально "
                  f"(мелкий клон?) — проверка предка пропущена", file=sys.stderr)
        elif git("merge-base", "--is-ancestor", sha, "HEAD") is None:
            return die(f"{path.name}: коммит {sha[:12]} не является предком HEAD — свидетельство "
                       f"снято с дерева, которого нет в истории этого релиза")
    elif sha != "unknown":
        return die(f"{path.name}: поле commit не похоже на SHA ({sha!r})")

    try:
        measured = dt.datetime.fromisoformat(rec["measured_at"])
    except (TypeError, ValueError):
        return die(f"{path.name}: measured_at не разбирается как дата ISO")
    if measured.tzinfo is None:
        measured = measured.replace(tzinfo=dt.timezone.utc)
    age = (dt.datetime.now(dt.timezone.utc) - measured).days
    if age > STALE_DAYS:
        return die(f"{path.name}: измерению {age} дней (порог {STALE_DAYS}) — переснимите, "
                   f"прежде чем принимать по нему решения о enforce")

    print(f"ок: {path.name} — релиз {want_name} / iOS {rel['ios']['buildNumber']}, "
          f"коммит {str(sha)[:12]}, измерено {rec['measured_at']} ({age} дн. назад)")
    print(f"    режим {rec['relay']['auth_mode']}, устройств с capability "
          f"{rec['capabilities'].get('devices_registered')}, legacy "
          f"{(rec.get('emission') or {}).get('legacy_pct')}%")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--print", action="store_true")
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--force", action="store_true",
                    help="перезаписать существующее свидетельство (по умолчанию запрещено)")
    args = ap.parse_args()

    if args.check:
        return check()

    if not MANIFEST.is_file():
        return die("нет web/release-manifest.json — неизвестно, о каком релизе свидетельство")
    rel = release_identity()
    sha = git("rev-parse", "HEAD") or "unknown"

    health = fetch()
    if health is None:
        return 1
    record = summarise(health, sha, rel)
    text = json.dumps(record, indent=2, ensure_ascii=False) + "\n"
    if args.print:
        sys.stdout.write(text)
        return 0

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    out = evidence_path(rel, sha)
    if out.exists() and not args.force:
        return die(f"{out.relative_to(ROOT)} уже существует. Свидетельство неизменяемо: новый "
                   f"замер — новый релиз или новый коммит. --force, только если понимаете зачем.")
    out.write_text(text, encoding="utf-8")
    print(f"написано {out.relative_to(ROOT)}")
    print(f"  релиз {rel['android']['versionName']} ({rel['android']['versionCode']}) / "
          f"iOS {rel['ios']['buildNumber']}")
    print(f"  режим {record['relay']['auth_mode']}, устройств с capability "
          f"{record['capabilities']['devices_registered']}, legacy "
          f"{record['emission']['legacy_pct']}%")
    return 0


if __name__ == "__main__":
    sys.exit(main())
