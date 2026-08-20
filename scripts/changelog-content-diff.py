#!/usr/bin/env python3
"""
Compare the regenerated web/changelog.json against the committed one, by CONTENT.

Why this exists: the freshness gate in .github/workflows/changelog.yml used to be
`git diff --exit-code web/changelog.json` after regenerating the file. That compares BYTES, and two
byte-level differences are guaranteed to be there on every run, neither of which means the changelog
is stale:

  * `generated_at` is `datetime.now()`, so a file regenerated now can never equal one generated
    earlier. The check was therefore comparing the file against itself-at-a-different-second, and
    could not pass on any pull request, whatever the branch contained. It had gone unnoticed because
    the step is gated on `github.event_name == 'pull_request'` and this repository is pushed to
    directly, so it had effectively never run.
  * line endings. The committed blob has CRLF; the generator writes `\n`, and `.json` is not covered
    by .gitattributes, so on a Linux runner the regenerated file differs on every single line.

Both are properties of how the file was produced, not of what it says. So compare the parsed JSON
with `generated_at` removed: that is exactly the question the gate is meant to ask -- "does this
branch change the changelog without regenerating it?" -- and it is immune to the clock, the platform
and the formatter.

  --verify   exit 1 if the content differs (the PR gate)
  (no flag)  exit 0 if the content differs, 1 if it does not -- the "should I commit?" test, shaped
             like `git diff --quiet` so the caller reads the same way round
"""
from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REL = "web/changelog.json"
VOLATILE = ("generated_at",)


def content(payload: dict) -> dict:
    """The payload minus the fields that change without the changelog changing."""
    return {k: v for k, v in payload.items() if k not in VOLATILE}


def committed() -> dict | None:
    """HEAD's version of the file, or None when it is not tracked yet."""
    try:
        raw = subprocess.check_output(
            ["git", "-C", str(ROOT), "show", f"HEAD:{REL}"], stderr=subprocess.DEVNULL)
    except subprocess.CalledProcessError:
        return None
    return json.loads(raw.decode("utf-8"))


def main() -> int:
    verify = "--verify" in sys.argv

    generated = json.loads((ROOT / REL).read_text(encoding="utf-8"))
    old = committed()
    differs = old is None or content(old) != content(generated)

    if verify:
        if differs:
            print(f"::error::{REL} is stale. Run: python3 scripts/generate-changelog.py "
                  "and commit the result.")
            # Show WHICH top-level sections moved, so the failure names the fix instead of
            # printing a 70 KB diff nobody reads.
            if old is not None:
                for key in sorted(set(content(old)) | set(content(generated))):
                    if content(old).get(key) != content(generated).get(key):
                        print(f"  changed section: {key}")
            return 1
        print(f"{REL} is up to date (content matches; generated_at ignored)")
        return 0

    # Non-verify mode: "is there anything worth committing?"
    return 0 if differs else 1


if __name__ == "__main__":
    raise SystemExit(main())
