#!/usr/bin/env python3
"""Generate web/changelog.json from git history and changelog/releases/*.json."""

from __future__ import annotations

import json
import re
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RELEASES_DIR = ROOT / "changelog" / "releases"
OUT = ROOT / "web" / "changelog.json"

REPO = "ummalife/khandaq"
GITHUB_BASE = f"https://github.com/{REPO}/commit"

SEVERITIES = ("critical", "important", "medium", "low")
SUBJECT_RE = re.compile(
    r"^\[(critical|important|medium|low)\]\s*(.+)$",
    re.IGNORECASE,
)
BODY_SEVERITY_RE = re.compile(
    r"^severity:\s*(critical|important|medium|low)\s*$",
    re.IGNORECASE | re.MULTILINE,
)


def run_git(*args: str) -> str:
    return subprocess.check_output(
        ["git", "-C", str(ROOT), *args],
        text=True,
        stderr=subprocess.DEVNULL,
    )


def parse_commit_entry(sha: str, date_iso: str, subject: str, body: str) -> dict | None:
    severity = None
    title = subject.strip()

    m = SUBJECT_RE.match(subject.strip())
    if m:
        severity = m.group(1).lower()
        title = m.group(2).strip()
    else:
        bm = BODY_SEVERITY_RE.search(body or "")
        if bm:
            severity = bm.group(1).lower()

    if not severity:
        return None

    date = date_iso[:10]
    return {
        "severity": severity,
        "title": title,
        "description": (body or "").strip() or None,
        "date": date,
        "commits": [
            {
                "hash": sha[:7],
                "full": sha,
                "url": f"{GITHUB_BASE}/{sha}",
            }
        ],
    }


def load_git_commits(limit: int = 500) -> list[dict]:
    raw = run_git(
        "log",
        f"-{limit}",
        "--format=%H%x1f%aI%x1f%s%x1f%b%x1e",
    )
    entries: list[dict] = []
    for record in raw.split("\x1e"):
        record = record.strip()
        if not record:
            continue
        parts = record.split("\x1f", 3)
        if len(parts) < 3:
            continue
        sha, date_iso, subject = parts[0], parts[1], parts[2]
        body = parts[3] if len(parts) > 3 else ""
        item = parse_commit_entry(sha, date_iso, subject, body)
        if item:
            entries.append(item)
    return entries


def group_by_date(commits: list[dict]) -> list[dict]:
    by_date: dict[str, list[dict]] = {}
    for c in commits:
        by_date.setdefault(c["date"], []).append(c)

    days = []
    for date in sorted(by_date.keys(), reverse=True):
        changes = sorted(
            by_date[date],
            key=lambda x: SEVERITIES.index(x["severity"]),
        )
        for ch in changes:
            ch.pop("date", None)
        days.append({"date": date, "changes": changes})
    return days


def load_releases() -> list[dict]:
    releases = []
    if not RELEASES_DIR.is_dir():
        return releases

    for path in sorted(RELEASES_DIR.glob("*.json"), reverse=True):
        data = json.loads(path.read_text(encoding="utf-8"))
        commits = []
        for h in data.get("commits", []):
            full = h if len(h) == 40 else h
            try:
                short = run_git("rev-parse", "--short", full).strip()
                full_sha = run_git("rev-parse", full).strip()
            except subprocess.CalledProcessError:
                short = h[:7]
                full_sha = h
            commits.append(
                {
                    "hash": short,
                    "full": full_sha,
                    "url": f"{GITHUB_BASE}/{full_sha}",
                }
            )

        changes = []
        for ch in data.get("changes", []):
            sev = ch.get("severity", "medium").lower()
            if sev not in SEVERITIES:
                sev = "medium"
            changes.append(
                {
                    "severity": sev,
                    "title": ch["title"],
                    "description": ch.get("description"),
                    "platforms": ch.get("platforms", []),
                }
            )
        changes.sort(key=lambda x: SEVERITIES.index(x["severity"]))

        releases.append(
            {
                "version": data.get("version"),
                "date": data.get("date"),
                "title": data.get("title", ""),
                "summary": data.get("summary"),
                "commits": commits,
                "changes": changes,
            }
        )

    releases.sort(key=lambda r: r.get("date", ""), reverse=True)
    return releases


def main() -> int:
    try:
        run_git("rev-parse", "HEAD")
    except subprocess.CalledProcessError:
        print("Not a git repository", file=sys.stderr)
        return 1

    releases = load_releases()
    tagged = load_git_commits()
    days = group_by_date(tagged)

    payload = {
        "generated_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "repo": REPO,
        "github_url": f"https://github.com/{REPO}",
        "severities": [
            {
                "id": "critical",
                "label": "Критический",
                "description": "Уязвимость, краш, утечка данных, RCE",
            },
            {
                "id": "important",
                "label": "Важный",
                "description": "Серьёзный баг или слабое место без немедленной эксплуатации",
            },
            {
                "id": "medium",
                "label": "Средний",
                "description": "Стабильность, UX, hardening",
            },
            {
                "id": "low",
                "label": "Низкий",
                "description": "Мелкие правки, документация, косметика",
            },
        ],
        "releases": releases,
        "days": days,
    }

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(f"Wrote {OUT} ({len(releases)} releases, {len(tagged)} tagged commits, {len(days)} days)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
