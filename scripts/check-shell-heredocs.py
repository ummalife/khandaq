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

# The tree holds five unquoted heredocs today. The floor exists so that losing the ability to see
# them is a failure rather than a quieter number.
MIN_UNQUOTED_HEREDOCS = 4

# KHANDAQ (audit round 3, F-21): both openers used to be anchored with `\s*$`, which is not how
# heredocs are written half the time. `cat <<EOF > /tmp/out` and `cat <<EOF2 | tee /tmp/x` were
# invisible, so their bodies were never scanned and the checker still printed a reassuring total.
# The delimiter word may be followed by anything -- a redirect, a pipe, another command.
OPEN_UNQUOTED = re.compile(r"<<-?\s*([A-Za-z_][A-Za-z0-9_]*)\s*(?![\w'\"])")
OPEN_QUOTED = re.compile(r"""<<-?\s*(?:'([A-Za-z_][A-Za-z0-9_]*)'|"([A-Za-z_][A-Za-z0-9_]*)")""")

problems = []
heredocs_seen = 0
# Counted separately: a quoted heredoc expands nothing, so it can never carry this defect. Only the
# unquoted ones are coverage. The old guard compared the COMBINED total against zero, and the tree
# holds ~35 quoted against ~5 unquoted -- so the parser could stop recognising unquoted openers
# entirely and the check would still report a healthy-looking total and exit 0.
unquoted_seen = 0


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


def _strip_single_quoted(line):
    """Remove '...' spans so their double quotes do not confuse the quote counter."""
    return re.sub(r"'[^']*'", "", line)


def unescaped_double_quotes(line):
    n = 0
    for i, ch in enumerate(line):
        if ch != '"':
            continue
        backslashes = 0
        j = i - 1
        while j >= 0 and line[j] == "\\":
            backslashes += 1
            j -= 1
        if backslashes % 2 == 0:
            n += 1
    return n


def check_multiline_double_quoted(path, lines, rel):
    """
    KHANDAQ (audit round 3, F-21) — the shape the heredoc scan classifies as SAFE and is not.

    scripts/deploy-site.sh sends a block as

        ssh "$REMOTE" "python3 <<'PY'
        ...
        PY
        nginx -t"

    The local shell never sees a heredoc here. It sees one multi-line argument in DOUBLE quotes, and
    inside double quotes a backtick is still command substitution performed locally — the quotes
    around 'PY' are decorative, as the deliberate local interpolation of $NGINX_SITE a few lines down
    confirms. De-anchoring the heredoc regex does not help: there is no heredoc to find. So this is a
    second, separate rule, and it is what stops that file from being scored as clean by accident.

    Heuristic by necessity, and deliberately narrow: only strings that actually span lines, which is
    the only case where a whole block of prose ends up inside quotes.
    """
    inside = False
    started_at = 0
    for n, raw in enumerate(lines, 1):
        line = raw.rstrip("\r")
        if not inside and line.lstrip().startswith("#"):
            continue

        if inside:
            for col in unescaped_backticks(line):
                problems.append((rel, n, col, line.strip(),
                                 "unescaped backtick inside a multi-line DOUBLE-QUOTED argument "
                                 "(opened at line %d): the local shell substitutes it before the "
                                 "argument is ever sent" % started_at))

        if unescaped_double_quotes(_strip_single_quoted(line)) % 2 == 1:
            inside = not inside
            if inside:
                started_at = n


def check_file(path):
    global heredocs_seen, unquoted_seen
    rel = os.path.relpath(path, ROOT).replace("\\", "/")
    with open(path, encoding="utf-8", errors="replace") as fh:
        lines = fh.read().splitlines()

    check_multiline_double_quoted(path, lines, rel)

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
                problems.append((rel, n, col, line.strip(),
                                 "unescaped backtick inside an UNQUOTED heredoc: the local "
                                 "shell runs this as a command before the block is ever sent"))
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
            unquoted_seen += 1


def main():
    if not os.path.isdir(SCRIPTS):
        die("scripts/ does not exist")

    files = sorted(n for n in os.listdir(SCRIPTS) if n.endswith(".sh"))
    if not files:
        die("no shell scripts under scripts/ — this check would pass vacuously")

    for name in files:
        check_file(os.path.join(SCRIPTS, name))

    print("shell heredocs: %d across %d script(s) (%d unquoted, which are the ones that expand)"
          % (heredocs_seen, len(files), unquoted_seen))
    if heredocs_seen == 0:
        die("no heredocs found at all — the parser has drifted from the scripts")
    if problems:
        for rel, n, col, text, why in problems:
            print("::error file=%s,line=%d,col=%d::%s" % (rel, n, col, why), file=sys.stderr)
            print("    %s" % text, file=sys.stderr)
        print("::error::fix: escape it as \\` , or quote the heredoc delimiter (<<'EOF') if the block "
              "needs no interpolation. For a multi-line double-quoted argument, escape the backtick "
              "or move the block into a quoted heredoc.", file=sys.stderr)
        die("%d unescaped backtick(s) in a place the LOCAL shell would run them" % len(problems))

    if unquoted_seen < MIN_UNQUOTED_HEREDOCS:
        die("only %d unquoted heredoc(s) recognised, expected at least %d. Quoted heredocs expand "
            "nothing and cannot carry this defect, so they are not coverage — if the parser stops "
            "recognising the unquoted ones, this check silently protects nothing. Either the scripts "
            "genuinely changed (lower the floor in a reviewed commit) or the parser drifted."
            % (unquoted_seen, MIN_UNQUOTED_HEREDOCS))

    print("no unescaped backticks where the local shell would run them")
    return 0


if __name__ == "__main__":
    sys.exit(main())
