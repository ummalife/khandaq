# Khandaq — internal security audit, 10 August 2026

**Scope:** the whole repository, with emphasis on code written in the previous 48 hours — the remediation batch answering the external review (`aa828e69`), the group-video fix (`a8d227a6`) and the layout fix (`f775b556`).
**Method:** five parallel reviewers by subsystem, each finding then attacked by an independent skeptic instructed to refute it. Only findings that survived refutation with a traced attacker-input → dangerous-operation path are listed.
**Result:** 13 findings raised, 2 refuted, **11 confirmed**. Two are high; both are fixed. This document is written to be handed to an external auditor alongside the repository.

---

## Why this audit was run

The code under review was written fast, under time pressure, and its own review had already caught four regressions before they shipped. New code is where new defects live, so we audited ourselves before inviting anyone else to.

That judgement paid: the two most serious findings in this document are in code we had written or touched hours earlier.

---

## High

### H-1 — Private group messages were served to the whole group — **FIXED** (`f464d622`)

Khandaq supports private member-to-member messages inside a group. They are stored as rows of the group chat and separated from the public timeline only by a flag.

The history-sync serving path selected messages by chat and time window with **no private filter**. So a private conversation between two members was packed into sync packets and handed to any member that requested history — and in public groups, membership is open to anyone with the chat-id. The receiver stored it as an ordinary group message and rendered it to the whole group.

This is a confidentiality failure, not a spoofing or availability one: real private conversation content reached people who were never party to it.

**Fix:** both history-sync predicates now exclude private messages (and local system lines, which we were also re-broadcasting under our own key). `buildSyncPacketForMessage` refuses them as a second layer, so a future caller cannot leak one either. On receive, synced rows are pinned public — the wire format carries no private bit, so a relaying peer must never be able to promote third-party content into someone's private thread.

**Also fixed alongside:** the dedup helper matched private rows, which gave a remote peer an oracle for *"does this exact text exist in this user's private messages"* — history sync answers a dedup hit with a delivery receipt.

**Android was checked and is not affected** — its query already carries `private_messageEq(0)`.

**Residual, stated plainly:** this stops *us* from leaking. Peers running older builds still hold and still re-serve private messages, and nothing on the wire lets a receiver distinguish such a row. The exposure ends only as installs update.

### H-2 — A friend could rewrite the text of your own sent messages — **FIXED** (`f464d622`)

The 1:1 edit packet was applied to whatever message matched the msgV3 hash, with no check that the sender is the message's author. That hash travels inside the original message, so the recipient knows it.

Consequence: your correspondent could silently change the text of a message **you** sent, in your own history. You would see their words attributed to you.

**Fix:** an incoming edit may only modify a message authored by the friend who sent it. Editing your own message and having the peer apply it continues to work.

---

## Medium — supply chain (two fixed, two open)

### M-1 — Unpinned executable pulled into the build that produces the shipped library — **FIXED** (`f464d622`)

`gas-preprocessor.pl` was fetched from a mutable GitHub branch, without a checksum, and installed executable into the container that builds `libjni-c-toxcore.so` — the toxcore/toxav stack shipped to every Android user. Whoever controls that branch controlled code running inside our build.

Now pinned to an exact commit and hash-verified; a mismatch fails the build.

### M-2 — The toxcore pin fell back to a mutable branch — **FIXED** (`f464d622`)

The native build pinned an exact commit of a third-party fork but **fell back to a mutable branch** if that commit could not be checked out. A force-push upstream — routine on a personal fork — would silently change what we ship, with a green build. The fallback is removed: an unfetchable pin now fails loudly.

### M-3 — Cancelled uploads are still served — **OPEN**

A cancelled group-file upload remains serveable to any group member who asks for it by id. The sender believes they cancelled; the data still goes out.

### M-4 — Dependency checksums are regenerated immediately before signing — **OPEN**

The release path regenerates the pinned Maven checksums just before producing the signed build, which weakens what the pinning is for: a substituted upstream artifact would be re-pinned rather than rejected.

---

## Low — **OPEN**

- **L-1 — Android does not bound a file chunk against the declared payload size or total size.** iOS and desktop enforce both. The peer able to exploit it is the transfer's own sender, so the effect is a stored file that does not match its declared metadata (and >100% progress), not content forgery.
- **L-2 — 1:1 chunked-message reassembly has no cap, no TTL and no per-peer bound.** An accepted friend can allocate reassembly buffers that are never freed.

---

## Repository hygiene, for the auditor's first hour

We checked what a stranger learns from the public repository:

- **No signing keys, service accounts, `.p8`, `.p12` or keystores** — never committed, in any revision.
- **The push-relay secret exists only as a placeholder** in example configuration; no value is in the repository.
- **`BEGIN PRIVATE KEY` matches** are test fixtures vendored from upstream WebRTC, not ours.
- **`google-services.json` with a Firebase Android API key is present in history before 3 August 2026.** We are naming it rather than waiting for it to be found. That key is public by design — it ships inside every APK and Google documents it as non-secret; protection comes from key restrictions and App Check, not secrecy. Removing it from history requires a rewrite, which we have not done.
- Secret scanning runs on every push (new commits) and weekly across the full history. A full-history sweep was run manually for this audit (`gitleaks` 8.18.4, run `31385127130`) and **passed clean** across all 1134 commits.

---

## Known-open items carried from the external review

These are documented in `SECURITY-REVIEW-RESPONSE.md` and remain open; they are not re-listed as new findings:

- history-sync messages carry no original-sender signature (needs a protocol version bump);
- push-wake requests are not signed by shipped clients, so relay authentication cannot be enforced yet;
- the group-video picture does not render (transport-level, pre-existing);
- Windows credential-store rewrite, per-sender transfer quota, encrypted-by-default profile export, SBOM.

---

## What was verified, and what was not

Android `assembleRelease` (including R8), iOS `xcodebuild`, and `bash -n` over the build script all pass with these changes.

Not verified: none of the two high fixes has been exercised on real devices. The meaningful test for H-1 is three devices — A sends B a private message in a group, C joins and requests history, and C must not receive it, while public history still arrives. That test has not been run.

Nor has any of this been fuzzed or run under a sanitizer; the external review asked for that and it still does not exist.
