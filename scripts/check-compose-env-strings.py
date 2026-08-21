#!/usr/bin/env python3
"""KHANDAQ — a compose file that cannot be parsed is an outage waiting for the next deploy.

This exists because it happened. `infra/push/docker-compose.yml` carried:

    - PUSH_AUTH_ENFORCE=${PUSH_AUTH_ENFORCE:?choose a mode explicitly: deploy with ... --soft}

and docker compose refused the whole file with

    services.push-relay.environment.[4]: unexpected type map[string]interface {}

An unquoted YAML scalar inside a block sequence splits on the first ": " — colon, space — so the
entry parsed as a one-key MAPPING whose key was everything up to "explicitly" and whose value was the
rest. The variable itself was fine. The prose written for the operator was what broke it, and the
`:?` guard is precisely the feature that invites prose into that position.

What that costs is more than a failed deploy. `docker compose` refuses to load the file at all, so on
a host where it has already been uploaded there is no `logs`, no `ps`, no `restart` and no rollback
by compose until someone edits YAML on a production box. It is the deploy-time equivalent of a syntax
error in the fire alarm.

The rule: any environment entry containing ": " must be quoted. This is a targeted lint rather than a
full YAML parse on purpose — stdlib only, no PyYAML, so it runs anywhere the other checks run, and it
encodes the one thing that actually went wrong instead of approximating a parser.

    python3 scripts/check-compose-env-strings.py

Never passes vacuously: if it stops finding environment blocks it fails instead of reporting success.
"""
import os
import re
import sys

ROOT = os.environ.get("KHANDAQ_ROOT") or os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# Where compose files live. Add directories here rather than walking the tree: a check that silently
# widens its own scope is a check whose green result means something different every month.
SEARCH_DIRS = [os.path.join(ROOT, "infra", "push")]

# A compose file with no environment block at all is suspicious enough to fail on.
MIN_ENTRIES_TOTAL = 8

problems = []
entries_seen = 0
files_seen = []


def die(msg):
    print("::error::" + msg, file=sys.stderr)
    sys.exit(1)


def check_file(path):
    """Walk the file tracking the `environment:` blocks by indentation."""
    global entries_seen
    rel = os.path.relpath(path, ROOT)
    with open(path, encoding="utf-8", errors="replace") as fh:
        lines = fh.read().splitlines()

    env_indent = None          # indentation of the `environment:` key, or None when outside one
    found_block = False

    for n, raw in enumerate(lines, 1):
        line = raw.rstrip("\r")
        if not line.strip() or line.lstrip().startswith("#"):
            continue

        indent = len(line) - len(line.lstrip())

        m = re.match(r"^(\s*)environment:\s*$", line)
        if m:
            env_indent = len(m.group(1))
            found_block = True
            continue

        if env_indent is None:
            continue

        # A key at or above the environment key's indentation ends the block.
        if indent <= env_indent and not line.lstrip().startswith("- "):
            env_indent = None
            continue

        item = re.match(r"^\s*-\s+(.*)$", line)
        if not item:
            continue

        value = item.group(1).strip()
        entries_seen += 1

        # Already quoted end to end: YAML takes it literally, nothing to split on.
        if (value.startswith('"') and value.endswith('"')) or \
           (value.startswith("'") and value.endswith("'")):
            continue

        if ": " in value:
            problems.append(
                (rel, n, value,
                 "contains ': ' unquoted, so YAML parses this entry as a mapping and docker compose "
                 "rejects the file with \"unexpected type map[string]interface {}\""))
        elif value.endswith(":"):
            problems.append(
                (rel, n, value,
                 "ends with ':', which YAML reads as a mapping key with an empty value"))

    if not found_block:
        die("%s: no `environment:` block found — the file changed shape and this check would have "
            "passed vacuously. Fix the check rather than deleting it." % rel)


def main():
    for directory in SEARCH_DIRS:
        if not os.path.isdir(directory):
            die("%s does not exist — SEARCH_DIRS is stale" % os.path.relpath(directory, ROOT))
        for name in sorted(os.listdir(directory)):
            if re.match(r"^docker-compose.*\.ya?ml$", name):
                path = os.path.join(directory, name)
                files_seen.append(os.path.relpath(path, ROOT))
                check_file(path)

    if not files_seen:
        die("no docker-compose files found under %s" % ", ".join(
            os.path.relpath(d, ROOT) for d in SEARCH_DIRS))

    print("compose environment entries: %d across %d file(s)" % (entries_seen, len(files_seen)))
    for f in files_seen:
        print("  %s" % f)

    if entries_seen < MIN_ENTRIES_TOTAL:
        die("only %d environment entries seen, expected at least %d — the parser has drifted from "
            "the files" % (entries_seen, MIN_ENTRIES_TOTAL))

    if problems:
        for rel, n, value, why in problems:
            print("::error file=%s,line=%d::%s" % (rel, n, why), file=sys.stderr)
            print("    %s" % value, file=sys.stderr)
            print("    fix: wrap the whole entry in double quotes, or take the colon out of the "
                  "message.", file=sys.stderr)
        die("%d compose environment entr(y/ies) will not parse" % len(problems))

    print("every entry is a plain string — docker compose can load these files")
    return 0


if __name__ == "__main__":
    sys.exit(main())
