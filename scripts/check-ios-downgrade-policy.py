#!/usr/bin/env python3
"""KHANDAQ (audit 2026-08-21, K-01) — the iOS anti-downgrade policy must agree with Android's.

`NgcHistoryDowngradePolicy` (Java) and `OCTNgcHistoryDowngradePolicy` (Objective-C) implement one
security rule twice: an unsigned history record claiming an author whose signing key we have recently
seen is refused. A rule enforced on one client and not the other is not a rule, and the two are in
different languages with different integer semantics — Java's `long` is signed, ObjC's `uint64_t`
underflows — so "they look the same" is not evidence.

The Java side is covered by NgcHistoryDowngradePolicyTest. This covers the ObjC side, and it runs on
Linux with no Xcode: the BODY of OCTNgcHistoryDowngradeDecide is extracted from the real .m file and
compiled as C after a small, fixed and declared set of textual substitutions (property syntax ->
struct members, nil -> NULL, BOOL -> int). Branch order, comparisons and constants therefore come
verbatim from the file that ships; only the type syntax is rewritten. It catches exactly the mistakes
that happen here: an inverted comparison, a wrong boundary, a missing guard, unsigned wraparound.

The truth table is the same one the JUnit test asserts, case for case.

    python3 scripts/check-ios-downgrade-policy.py

Needs a C compiler (`cc`). Set KHANDAQ_ROOT to run it from outside the repo.
"""
import os
import re
import shutil
import subprocess
import sys
import tempfile

ROOT = os.environ.get("KHANDAQ_ROOT") or os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
GROUPS = os.path.join(ROOT, "khandaq-ios", "local_pod_repo", "objcTox", "Classes", "Private",
                      "Manager", "Groups")
M = os.path.join(GROUPS, "OCTNgcHistoryDowngradePolicy.m")
HSK = os.path.join(GROUPS, "OCTNgcHskDirectory.m")


def die(msg):
    print("::error::" + msg, file=sys.stderr)
    sys.exit(1)


try:
    src = open(M, encoding="utf-8").read()
except OSError as exc:
    die("cannot read %s (%s)" % (M, exc))
# Non-greedy: with a greedy `(.*)` this swallowed everything up to the LAST closing brace in the
# file, so adding a second function to the .m silently pulled its body into decide()'s. It compiled
# as a confusing error about an undeclared `NO` rather than as "your regex is wrong", which is the
# worst kind of failure — the message points away from the cause.
m = re.search(r"OCTNgcDowngradeDecision OCTNgcHistoryDowngradeDecide\([^)]*\)\s*\{(.*?)\n\}",
              src, re.S)
if not m:
    sys.exit("could not find OCTNgcHistoryDowngradeDecide in the .m file")
body = m.group(1)

# KHANDAQ (re-review 2026-08-22, KQ-03): the attribution rule is a SECOND function that must match
# Android, and it decides whether a kept row may carry the author's name. Extracted the same way,
# for the same reason: two implementations of one security rule in two languages is not evidence
# that they agree.
ma = re.search(r"BOOL OCTNgcHistoryDowngradeRendersAsClaimedAuthor\([^)]*\)\s*\{(.*?)\n\}",
               src, re.S)
if not ma:
    die("could not find OCTNgcHistoryDowngradeRendersAsClaimedAuthor in the .m file — the KQ-03 "
        "attribution rule is missing from the iOS client, so the two platforms disagree")
attrib_body = ma.group(1).replace("NO", "0").replace("YES", "1")

grace = re.search(r"\+ \(uint64_t\)replaceGraceMs\s*\{\s*return ([^;]+);",
                  open(HSK, encoding="utf-8").read())
if not grace:
    die("could not read +replaceGraceMs from %s" % HSK)
GRACE_EXPR = grace.group(1).strip()

SUBS = [
    ("authorHsk == nil", "authorHsk == NULL"),
    ("authorHsk->hskPub.length", "authorHsk->hskPubLength"),   # (not present, kept for safety)
    ("authorHsk.hskPub.length", "authorHsk->hskPubLength"),
    ("authorHsk.lastSeenMs", "authorHsk->lastSeenMs"),
    ("OCTNgcHistoryDowngradeKeyStaleMs()", "KEY_STALE_MS"),
]
for a, b in SUBS:
    body = body.replace(a, b)
if "authorHsk." in body or "nil" in body:
    sys.exit("unhandled Objective-C syntax survived the translation:\n" + body)

harness = """
#include <stdio.h>
#include <stdint.h>

typedef enum {
    OCTNgcDowngradeDecisionAcceptVerified,
    OCTNgcDowngradeDecisionAcceptLegacy,
    OCTNgcDowngradeDecisionAcceptKeyStale,
    OCTNgcDowngradeDecisionReject,
} OCTNgcDowngradeDecision;

typedef struct { uint64_t hskPubLength; uint64_t firstSeenMs; uint64_t lastSeenMs; } Record;
#define BOOL int
#define KEY_STALE_MS (%s)

static OCTNgcDowngradeDecision decide(Record *authorHsk, BOOL verdictMatchesThisRow, uint64_t nowMs)
{
%s
}

static BOOL rendersAsClaimedAuthor(OCTNgcDowngradeDecision decision, BOOL syncerIsAuthor)
{
%s
}

#define NOW 1700000000000ULL
static int fails = 0;
static void check(const char *name, OCTNgcDowngradeDecision got, OCTNgcDowngradeDecision want)
{
    if (got != want) { printf("  FAIL %%-46s got %%d want %%d\\n", name, got, want); fails++; }
    else             { printf("  ok   %%s\\n", name); }
}
static void checkb(const char *name, BOOL got, BOOL want)
{
    if (got != want) { printf("  FAIL %%-46s got %%d want %%d\\n", name, got, want); fails++; }
    else             { printf("  ok   %%s\\n", name); }
}

int main(void)
{
    Record fresh = { 32, NOW - 2000, NOW - 60000 };
    Record atGrace = { 32, 0, NOW - KEY_STALE_MS };
    Record pastGrace = { 32, 0, NOW - KEY_STALE_MS - 1 };
    Record insideGrace = { 32, 0, NOW - KEY_STALE_MS + 1 };
    Record future = { 32, 0, NOW + 60000 };
    Record noKey = { 0, 0, NOW };

    check("verified row is accepted", decide(&fresh, 1, NOW), OCTNgcDowngradeDecisionAcceptVerified);
    check("unsigned row from a signing author is refused", decide(&fresh, 0, NOW), OCTNgcDowngradeDecisionReject);
    check("author who never announced is unaffected", decide(0, 0, NOW), OCTNgcDowngradeDecisionAcceptLegacy);
    check("record without a key is treated as unknown", decide(&noKey, 0, NOW), OCTNgcDowngradeDecisionAcceptLegacy);
    check("key exactly at the grace boundary is stale", decide(&atGrace, 0, NOW), OCTNgcDowngradeDecisionAcceptKeyStale);
    check("key past the grace boundary is stale", decide(&pastGrace, 0, NOW), OCTNgcDowngradeDecisionAcceptKeyStale);
    check("one ms inside the window still rejects", decide(&insideGrace, 0, NOW), OCTNgcDowngradeDecisionReject);
    check("backwards clock accepts rather than rejects", decide(&future, 0, NOW), OCTNgcDowngradeDecisionAcceptKeyStale);
    check("a verdict wins over staleness", decide(&pastGrace, 1, NOW), OCTNgcDowngradeDecisionAcceptVerified);
    check("a verdict wins over an unknown author", decide(0, 1, NOW), OCTNgcDowngradeDecisionAcceptVerified);

    /* The case the explicit lastSeenMs > nowMs guard exists for, and the only one where it changes
       the answer. With an ordinary clock a future lastSeenMs underflows to an enormous value and
       lands on "stale" by accident; with a clock still near the epoch -- a device that has not yet
       synced time -- and a corrupt stored value near UINT64_MAX, the same underflow produces a SMALL
       number, which without the guard reads as "seen moments ago" and REJECTS the peer's history. */
    Record corrupt = { 32, 0, 0xFFFFFFFFFFFFFC18ULL };   /* UINT64_MAX - 999 */
    check("unset clock + corrupt lastSeen must not reject", decide(&corrupt, 0, 1000ULL),
          OCTNgcDowngradeDecisionAcceptKeyStale);

    /* KQ-03: the attribution rule, same truth table as StaleAuthorAttributionTest on Android. */
    checkb("stale key relayed by a third party must NOT attribute",
           rendersAsClaimedAuthor(OCTNgcDowngradeDecisionAcceptKeyStale, 0), 0);
    checkb("stale key relayed by the author itself keeps the name",
           rendersAsClaimedAuthor(OCTNgcDowngradeDecisionAcceptKeyStale, 1), 1);
    checkb("a verified row always attributes",
           rendersAsClaimedAuthor(OCTNgcDowngradeDecisionAcceptVerified, 0), 1);
    checkb("an author that never signed is unaffected",
           rendersAsClaimedAuthor(OCTNgcDowngradeDecisionAcceptLegacy, 0), 1);
    /* KHANDAQ (internal audit 2026-08-22): a rejected row DOES now reach rendering — the file path
       keeps it instead of dropping it, because a file record can never be signed and dropping every
       one of them refused the feature rather than an attack. So the answer flipped: relayed by a
       third party it must not carry the claimed name, relayed by the author it still may. */
    checkb("a rejected row relayed by a third party must NOT attribute",
           rendersAsClaimedAuthor(OCTNgcDowngradeDecisionReject, 0), 0);
    checkb("a rejected row relayed by the author itself keeps the name",
           rendersAsClaimedAuthor(OCTNgcDowngradeDecisionReject, 1), 1);

    printf("KEY_STALE_MS = %%llu ms (%%llu h)\\n",
           (unsigned long long)KEY_STALE_MS, (unsigned long long)(KEY_STALE_MS / 3600000ULL));
    if (KEY_STALE_MS != 24ULL * 60ULL * 60ULL * 1000ULL) {
        printf("  FAIL the staleness window is not the 24h directory replace-grace\\n"); fails++;
    }
    printf(fails ? "\\n%%d FAILED\\n" : "\\nall checks passed\\n", fails);
    return fails ? 1 : 0;
}
""" % (GRACE_EXPR, body, attrib_body)

work = tempfile.mkdtemp(prefix="khandaq-ios-policy-")
c = os.path.join(work, "check.c")
exe = os.path.join(work, "check.exe")
open(c, "w", encoding="utf-8", newline="\n").write(harness)
cc = shutil.which("cc") or shutil.which("gcc") or shutil.which("clang")
if cc is None:
    die("no C compiler found (cc/gcc/clang)")
r = subprocess.run([cc, "-std=c11", "-Wall", "-Wextra", "-Werror", "-O1", c, "-o", exe],
                   capture_output=True, text=True)
if r.returncode:
    print(r.stdout + r.stderr)
    die("the extracted policy body does not compile as C — see the output above")
rc = subprocess.run([exe]).returncode
shutil.rmtree(work, ignore_errors=True)
if rc:
    die("the iOS anti-downgrade policy does not match the Android truth table")
sys.exit(0)
