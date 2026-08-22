#!/usr/bin/env python3
"""CI gate for web/.well-known/security.txt (RFC 9116).

KHANDAQ (re-audit 2026-08-22, W-03). An expired security.txt is worse than none: a researcher who
finds one reads the Expires date as a promise that the contact is still monitored. The date is
therefore checked here, in CI, with a month of warning — not discovered by the researcher.

The runtime half (published, 200, text/plain, not expired) lives in scripts/verify-site-deploy.py;
this is the part that must fail before the deploy rather than after it.
"""
from __future__ import annotations

import datetime as dt
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SECTXT = ROOT / "web" / ".well-known" / "security.txt"
WARN_DAYS = 30

REQUIRED = ("Contact", "Expires", "Canonical", "Policy", "Preferred-Languages")


def main() -> int:
    if not SECTXT.is_file():
        print(f"::error::{SECTXT.relative_to(ROOT)} отсутствует", file=sys.stderr)
        return 1
    text = SECTXT.read_text(encoding="utf-8")
    problems = []

    for field in REQUIRED:
        if not re.search(rf"^{field}:\s*\S+", text, re.M):
            problems.append(f"нет обязательного поля {field}")

    m = re.search(r"^Expires:\s*(\S+)\s*$", text, re.M)
    if m:
        raw = m.group(1)
        try:
            exp = dt.datetime.fromisoformat(raw.replace("Z", "+00:00"))
            if exp.tzinfo is None:
                problems.append(f"Expires без часового пояса: {raw!r}")
            else:
                left = (exp - dt.datetime.now(dt.timezone.utc)).days
                if left <= 0:
                    problems.append(f"security.txt просрочен ({raw}) — обновите Expires")
                elif left < WARN_DAYS:
                    problems.append(f"security.txt истекает через {left} дн. ({raw}) — обновите заранее")
                else:
                    print(f"ок: Expires {raw} (ещё {left} дн.)")
        except ValueError:
            problems.append(f"Expires не является датой ISO 8601: {raw!r}")
    # Multiple Expires fields is a spec violation, and the one that would be honoured is ambiguous.
    if len(re.findall(r"^Expires:", text, re.M)) > 1:
        problems.append("несколько полей Expires — RFC 9116 допускает ровно одно")

    canonical = re.search(r"^Canonical:\s*(\S+)", text, re.M)
    if canonical and not canonical.group(1).startswith("https://"):
        problems.append(f"Canonical должен быть https://, а не {canonical.group(1)!r}")
    for contact in re.findall(r"^Contact:\s*(\S+)", text, re.M):
        if not (contact.startswith("https://") or contact.startswith("mailto:") or contact.startswith("tel:")):
            problems.append(f"Contact должен быть https:/mailto:/tel:, а не {contact!r}")

    if problems:
        for p in problems:
            print(f"::error::{p}", file=sys.stderr)
        return 1
    print(f"ок: {SECTXT.relative_to(ROOT)} валиден")
    return 0


if __name__ == "__main__":
    sys.exit(main())
