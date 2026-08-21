#!/usr/bin/env python3
"""KHANDAQ (audit round 3, F-03) — the file half of history sync must be gated too, on both clients.

The anti-downgrade rule (K-01) refuses an UNSIGNED history record claiming an author whose signing
key this device has learned from a live announcement in that group. It was enforced on the text
packet (0x01/0x02) and not on the file packet (0x03) — on either platform. So the exact claim that
was refused outright as text was accepted without question as a file: a row inserted under that
author's pubkey, up to ~36 KiB written into the encrypted store, and a notification raised. The
forged file bubble was visually identical to a real one.

A rule enforced on one of two packet types is not a rule, and a rule enforced on one of two clients
is not a rule either. This asserts the call sites exist, on both, and that each sits BEFORE the code
that writes — a gate next to the insert would stop the record while leaving its side effects behind,
which is the reasoning the text gates already carry in their comments.

    python3 scripts/check-history-file-gate.py

Stdlib only, offline. Never passes vacuously: if a handler or its insert call cannot be found, that
is a failure, because a check that cannot locate what it guards is not guarding it.
"""
import os
import re
import sys

ROOT = os.environ.get("KHANDAQ_ROOT") or os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

ANDROID = os.path.join(ROOT, "khandaq-android-trifa", "android-refimpl-app", "app", "src", "main",
                       "java", "com", "zoffcc", "applications", "trifa", "HelperGroup.java")
IOS = os.path.join(ROOT, "khandaq-ios", "local_pod_repo", "objcTox", "Classes", "Private", "Manager",
                   "Groups", "OCTNgcGroupHistSync.m")

# (label, file, handler signature, the gate that must appear, the write that must come after it)
CASES = [
    ("Android text", ANDROID,
     r"static void handle_incoming_sync_group_message\(",
     "NgcHistoryDowngradePolicy.decide(", "group_message_add_from_sync("),
    ("Android file", ANDROID,
     r"static void handle_incoming_sync_group_file\(",
     "NgcHistoryDowngradePolicy.decide(", "group_file_add_from_sync("),
    ("iOS text", IOS,
     r"- \(void\)handleIncomingSyncMessageWithGroupNumber:",
     "downgradeDecisionForGroupNumber:", "senderPeerId ="),
    ("iOS file", IOS,
     r"- \(void\)handleIncomingSyncFileWithGroupNumber:",
     "downgradeDecisionForUnsignableRecordInGroupNumber:", "fileData ="),
]


def die(msg):
    print("::error::" + msg, file=sys.stderr)
    sys.exit(1)


def read(path):
    try:
        with open(path, encoding="utf-8", errors="replace") as fh:
            return fh.read()
    except OSError as exc:
        die("cannot read %s (%s)" % (path, exc))


def strip_comments(src):
    """
    KHANDAQ: comments out, before anything is searched for.

    Found by mutating this very check. Disabling the gate call left it green, because the COMMENT
    above the gate names `NgcHistoryDowngradePolicy.decide` while explaining what it is for — so the
    check was reading prose and calling it a call site. That is the same mistake, in miniature, that
    this audit round kept finding elsewhere: text written for a human, parsed by a machine.

    Blank the comments and keep the line count, so reported line numbers stay true.
    """
    out = []
    in_block = False
    for line in src.split("\n"):
        if in_block:
            end = line.find("*/")
            line = line[end + 2:] if end >= 0 else ""
            in_block = end < 0
        start = line.find("/*")
        while start >= 0:
            end = line.find("*/", start + 2)
            if end < 0:
                line = line[:start]
                in_block = True
                break
            line = line[:start] + " " + line[end + 2:]
            start = line.find("/*")
        slash = line.find("//")
        if slash >= 0:
            line = line[:slash]
        out.append(line)
    return "\n".join(out)


def handler_body(text, signature, label):
    """From the handler signature to the start of the next top-level declaration."""
    m = re.search(signature, text)
    if not m:
        die("%s: cannot find the handler matching %r — the file changed shape and this check would "
            "have passed vacuously. Fix the pattern rather than deleting the check."
            % (label, signature))
    start = m.start()
    # The next handler at the same nesting, or end of file.
    nxt = re.search(r"\n    (?:static |- \(|public |private )", text[m.end():])
    end = m.end() + nxt.start() if nxt else len(text)
    return strip_comments(text[start:end]), text[:start].count("\n") + 1


def main():
    checked = 0
    for label, path, signature, gate, write in CASES:
        text = read(path)
        body, line = handler_body(text, signature, label)

        gate_at = body.find(gate)
        if gate_at < 0:
            die("%s (%s:%d): the anti-downgrade gate %r is NOT called in this handler. An unsigned "
                "record claiming an author known to sign would be accepted here — which is exactly "
                "what the text path refuses." % (label, os.path.relpath(path, ROOT), line, gate))

        write_at = body.find(write)
        if write_at < 0:
            die("%s (%s:%d): cannot find %r, the write this gate is supposed to precede. Either the "
                "handler was restructured or this check has drifted; both need a human."
                % (label, os.path.relpath(path, ROOT), line, write))

        if gate_at > write_at:
            die("%s (%s:%d): the gate appears AFTER %r. It has to come first — everything below can "
                "write a row, touch storage or raise a notification, and a gate next to the insert "
                "stops the record while leaving its side effects behind."
                % (label, os.path.relpath(path, ROOT), line, write))

        print("  ok  %-13s %s:%d — gate before %s" % (label, os.path.relpath(path, ROOT).replace("\\", "/"),
                                                      line, write))
        checked += 1

    if checked != len(CASES):
        die("only %d of %d handlers checked" % (checked, len(CASES)))
    print("both packet types are gated on both clients (%d handlers)" % checked)
    return 0


if __name__ == "__main__":
    sys.exit(main())
