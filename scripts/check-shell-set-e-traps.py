#!/usr/bin/env python3
"""Two shapes that end a `set -e` script without saying anything.

KHANDAQ (re-audit 2026-08-22, found while closing W-02). deploy-site.sh could not complete from a
clean checkout, and had not been able to for some time. It exited 1 partway through, before
uploading a single file, and printed nothing about it — so the published site quietly fell a release
behind and the audit found the drift from the outside.

The cause was one line:

    DEB="$(ls -1t "$ROOT"/dist/*.deb 2>/dev/null | head -1)"

dist/ is gitignored, so on a clean checkout the glob matches nothing, `ls` exits 1, `pipefail`
carries that through `| head -1`, and the exit status of `VAR=$(pipeline)` IS the pipeline's status.
`set -e` then terminates the script. The `2>/dev/null` makes it worse: it hides the only clue.

A neighbouring line looked like the same bug and is NOT one:

    [[ -f "$file" ]] && cp "$file" "$dest"

`set -e` does not fire there: a command that is part of an `&&` list and is not the one after the
final `&&` is exempt. Checked in a real shell rather than reasoned about, after this gate first
reported it — a linter that cries wolf about correct code is a linter somebody deletes.

Scope: scripts that run unattended — deploy, ci and signing. Those are where a silent exit means
"it did not happen and nobody was told", which is exactly what W-02 turned out to be. Build scripts
are reported as notes, not failures: they are run by a person watching the terminal, where an early
exit is visible, and rewriting them blind is a bigger risk than the one being closed.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SCRIPTS = sorted(ROOT.glob("scripts/*.sh")) + sorted(ROOT.glob("khandaq-*/**/*.sh"))

SET_E = re.compile(r"^\s*set\s+-[a-z]*e", re.M)
# VAR=$( ... | ... )  with no `|| VAR=...` fallback on the same line.
ASSIGN_PIPE = re.compile(r'^\s*(?:local\s+|export\s+)?[A-Za-z_][A-Za-z0-9_]*="?\$\([^)]*\|[^)]*\)"?\s*$')
# Unattended paths: a silent exit here means the deploy did not happen and said nothing.
ENFORCED = ("deploy-", "ci-", "sign-", "lock-")


def main() -> int:
    problems: list[str] = []
    notes: list[str] = []
    scanned = 0
    for path in SCRIPTS:
        try:
            text = path.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        if not SET_E.search(text):
            continue
        scanned += 1
        rel = path.relative_to(ROOT)
        for n, line in enumerate(text.splitlines(), 1):
            stripped = line.strip()
            if stripped.startswith("#"):
                continue
            if ASSIGN_PIPE.match(line) and "||" not in line:
                msg = (f"{rel}:{n}: присваивание из конвейера без запасного варианта — при pipefail "
                       f"падение любой части конвейера завершает скрипт молча. "
                       f'Добавьте `|| VAR=""`.\n      {stripped}')
                if path.name.startswith(ENFORCED):
                    problems.append(msg)
                else:
                    notes.append(msg)

    for note in notes:
        print(f"::notice::{note}")
    if problems:
        for p in problems:
            print(f"::error::{p}", file=sys.stderr)
        return 1
    print(f"ок: {scanned} скрипт(ов) с set -e; ловушек нет в автоматических путях "
          f"(deploy/ci/sign), {len(notes)} замечани(я/й) в сборочных скриптах")
    return 0


if __name__ == "__main__":
    sys.exit(main())
