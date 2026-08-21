#!/usr/bin/env python3
"""KHANDAQ — prose inside an unquoted heredoc is code, and it will run on the wrong machine.

This exists because it happened, during a production deploy, in a comment explaining a different bug.

`scripts/deploy-push-relay.sh` sends a block to the server with

    ssh "$REMOTE" bash -s <<REMOTE
    ...
    REMOTE

The delimiter is UNQUOTED on purpose — the block interpolates the chosen compose overlay and the
remote directory before it is sent. That also means every backtick in that block is command
substitution performed by the LOCAL shell, and every unescaped `$` is a local variable. A comment
written to explain the previous fix contained ``ssh bash -s`` and ``docker compose exec -T`` in
backticks, so the deploying workstation dutifully ran them:

    You must specify a subsystem to invoke.
    scripts/deploy-push-relay.sh: line 52: docker: command not found

It is the same shape as the compose file whose `${VAR:?...}` message contained a colon: text written
for a human, parsed by a machine that was never told it was prose. `bash -n` accepts both, because
both are syntactically valid — they just mean something other than what was written.

So: inside an unquoted heredoc, a backtick must be escaped. That is the whole rule.

    python3 scripts/check-shell-heredocs.py

Quoted delimiters (<<'EOF', <<"EOF") are left alone: nothing inside them is expanded, which is
exactly why quoting is the better default when the block needs no interpolation.

Stdlib only, offline. Never passes vacuously: if it stops finding heredocs it fails.
"""
import os
import re
import sys

ROOT = os.environ.get("KHANDAQ_ROOT") or os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SCRIPTS = os.path.join(ROOT, "scripts")

# An unquoted heredoc opener: <<WORD or <<-WORD, where WORD is bare (no quotes).
OPEN_UNQUOTED = re.compile(r"<<-?\s*([A-Za-z_][A-Za-z0-9_]*)\s*$")
# A quoted one, tracked only so the closing delimiter is matched correctly and its body skipped.
OPEN_QUOTED = re.compile(r"""<<-?\s*(?:'([A-Za-z_][A-Za-z0-9_]*)'|"([A-Za-z_][A-Za-z0-9_]*)")\s*$""")

problems = []
heredocs_seen = 0


def die(msg):
    print("::error::" + msg, file=sys.stderr)
    sys.exit(1)


def unescaped_backticks(line):
    """Column of each backtick not preceded by a backslash."""
    out = []
    for i, ch in enumerate(line):
        if ch != "`":
            continue
        backslashes = 0
        j = i - 1
        while j >= 0 and line[j] == "\\":
            backslashes += 1
            j -= 1
        if backslashes % 2 == 0:
            out.append(i + 1)
    return out


def check_file(path):
    global heredocs_seen
    rel = os.path.relpath(path, ROOT).replace("\\", "/")
    with open(path, encoding="utf-8", errors="replace") as fh:
        lines = fh.read().splitlines()

    delimiter = None
    expand = False
    for n, raw in enumerate(lines, 1):
        line = raw.rstrip("\r")

        if delimiter is not None:
            if line.strip() == delimiter:
                delimiter = None
                expand = False
                continue
            if not expand:
                continue
            for col in unescaped_backticks(line):
                problems.append((rel, n, col, line.strip()))
            continue

        mq = OPEN_QUOTED.search(line)
        if mq:
            delimiter = mq.group(1) or mq.group(2)
            expand = False
            heredocs_seen += 1
            continue

        mu = OPEN_UNQUOTED.search(line)
        if mu:
            delimiter = mu.group(1)
            expand = True
            heredocs_seen += 1


def main():
    if not os.path.isdir(SCRIPTS):
        die("scripts/ does not exist")

    files = sorted(n for n in os.listdir(SCRIPTS) if n.endswith(".sh"))
    if not files:
        die("no shell scripts under scripts/ — this check would pass vacuously")

    for name in files:
        check_file(os.path.join(SCRIPTS, name))

    print("shell heredocs: %d across %d script(s)" % (heredocs_seen, len(files)))
    if heredocs_seen == 0:
        die("no heredocs found at all — the parser has drifted from the scripts")

    if problems:
        for rel, n, col, text in problems:
            print("::error file=%s,line=%d,col=%d::unescaped backtick inside an UNQUOTED heredoc: the "
                  "local shell runs this as a command before the block is ever sent"
                  % (rel, n, col), file=sys.stderr)
            print("    %s" % text, file=sys.stderr)
        print("::error::fix: escape it as \\` , or quote the heredoc delimiter (<<'EOF') if the block "
              "needs no interpolation.", file=sys.stderr)
        die("%d unescaped backtick(s) in unquoted heredocs" % len(problems))

    print("no unescaped backticks in any unquoted heredoc")
    return 0


if __name__ == "__main__":
    sys.exit(main())
