# Khandaq — response to the second external security review

**Response date:** 11 August 2026
**Responding to:** the follow-up review of `master` — 2 high, 6 medium, 2 low
**Base:** commit `3880e7db` plus the batch described here

---

## Summary

Nothing is disputed — every finding reproduced. Of the ten: **five are fixed outright** (2, 3, 5, 8, 10), **three are partly fixed** with the remaining half named rather than glossed (4, 6, 7), and **two are open** (1, 9). The `.gitattributes` gap you noted separately is also fixed.

Thank you for the two things that mattered most: catching that our release job **re-pinned** dependencies instead of verifying them, and confirming which of our earlier fixes actually landed. Both changed what we did next.

| # | Finding | Status |
|---|---|---|
| 1 | History-sync sender impersonation | **OPEN** — needs a protocol change, design below |
| 2 | Release CI replaces dependency pins | **FIXED** |
| 3 | 1:1 chunk assembly allocation | **FIXED** |
| 4 | Push relay: unauthenticated posture + token in logs | **PARTLY FIXED** — logging and TLS closed; enforcement is a rollout |
| 5 | Cancelled group files requestable after restart | **FIXED** |
| 6 | Plaintext identity export | **PARTLY FIXED** — hardened, encrypted-by-default deferred |
| 7 | Windows runtime dependencies | **PARTLY FIXED** — FFmpeg bumped; Qt/OpenSSL deferred |
| 8 | iOS dependencies unlocked | **FIXED** |
| 9 | Keychain errors vs "not found" | **OPEN** — accepted, small |
| 10 | Push replay protection process-local | **FIXED** |
| — | No `.gitattributes` (CRLF) | **FIXED** |

---

## Fixed

### 2 — Release CI replaced dependency pins (HIGH)

You were right, and it was worse than it looked. The regenerating task ran with its failure ignored, immediately before build, signing and attestation — so the pinning mechanism protected nothing on the release path.

- The release workflow now **verifies** committed pins and fails on mismatch. No regeneration, no ignored failure.
- The regenerating task now **refuses to run on CI** unless explicitly opted in, so no future workflow can silently re-pin.
- Regeneration moved to a separate manual workflow that builds and signs nothing; its output is reviewed and committed by a human.
- After the build, the release job asserts the pin file is unchanged before attestation.

**What we found while fixing it:**

The naive fix would have failed **every** release. The committed pins were generated on macOS, where the SDK jar's witness key begins `Android:` — on a Linux runner the same artifact yields `android:`, and the comparison is case-sensitive, so verification died on the first assertion. This also explains the excuse our own workflow comment gave for regenerating ("CI resolves some dependencies to different checksums"): the artifacts were never different, only the key was. We removed the locally installed Android SDK from witnessing entirely rather than papering over the case — it is provisioned by the runner image rather than fetched by us, and the key does not even encode the platform revision, so it was never a meaningful pin.

Regeneration also emitted entries keyed by a Gradle transform-workspace hash, which moves with the toolchain — a regenerated file would have broken verification at the next AGP bump. Those are excluded now.

**We also closed the gap you would have found next.** 20 real Maven coordinates resolved but were not pinned at all (media3, room, work-runtime, guava, ucrop and friends — this predates your review). They are pinned now.

Taking digests from our own cache is the same pattern you objected to, so we did not stop at `pinChecksums` — that task only proves the artifacts *on this machine* match the pins, and the pins came from that same machine. `app/verify_pins_against_upstream.sh` closes the loop: it re-downloads every pinned artifact straight from Google Maven / Maven Central / JitPack and compares the SHA-256 with the committed pin. It needs nothing but `curl`, writes nothing, and you can run it yourself.

One of the 20 was worth the extra look. `androidx.sqlite:sqlite-framework-android:2.5.2` is cached under the file name `sqlite-framework-release.aar`, which matches no URL on the server — the server only serves `sqlite-framework-android-2.5.2.aar`. Since the file name is part of the pin key, a locally-derived name would have been exactly the machine-dependent trap we had just removed for the SDK jars. It is not: the name is declared in the published Gradle Module Metadata, so it is identical on every machine. Bytes verified separately.

Result after the change: **338 pins verified locally, 0 unresolved, 0 unpinned — and 338/338 re-verified against the upstream repositories, 0 mismatches.**

That upstream run also produced the one correction worth reporting, because it argues against trusting the script blindly: it first reported a mismatch on `org.jetbrains.kotlin:kotlin-stdlib:2.1.20`. The pin was right; the script was wrong. That coordinate resolves to `kotlin-stdlib-2.1.20-all.jar`, and the plain `kotlin-stdlib-2.1.20.jar` also exists upstream as a *different* artifact — the script tried the coordinate form first and compared the wrong file. It now tries the pinned file name first, since that is the name Gradle actually fetched.

While fixing this we found the tool contradicting its own policy: the `noWitness` exclusion list was applied only when *writing* the pin file, so the verify task still hashed the excluded artifacts and then reported 17 Gradle transform outputs as "unpinned dependencies". Both tasks now read the same list, so "unpinned" means only "a real dependency nobody pinned".

### 3 — 1:1 chunk assembly allocation (MEDIUM)

The declared total is now validated against the real maximum message size, and assemblies have a TTL, a per-peer cap and a global cap.

### 5 — Cancelled group files (MEDIUM)

Cancellation is now persisted in the existing key/value table — no schema migration — and enforced before every emitting path: begin, chunk, and the resend/request handler.

Three defects surfaced during review of our own fix, all closed: a race where a stale "not cancelled" cache write could overwrite a fresh cancel; the automatic retry clearing a user cancellation; and a read failure being indistinguishable from "no tombstone", which cached a false negative for the process lifetime. A read failure now fails **closed** — we do not emit data — and is not cached.

### 8 — iOS dependencies unlocked (MEDIUM)

`Podfile.lock` is committed and no longer ignored — with a comment on the `.gitignore` line so it does not quietly come back. We did not narrow the `Podfile` ranges in the same change: committing the lock is the fix, changing ranges is a separate decision.

You asked for **both** lockfiles. The second one did not exist, and while creating it we found the more useful fact: **nothing in CI or in `scripts/` runs `bundle exec`** — `pod` and `fastlane` are invoked directly from the versions installed on the build machine, so the `Gemfile` was decorative and a lockfile generated from a fresh resolve would have documented a toolchain nobody uses. Instead the `Gemfile` is now pinned to the versions the release is actually built with (CocoaPods 1.16.2, fastlane 2.237.0) and `Gemfile.lock` is committed from that — 134 gems, each with a SHA-256. Moving the build itself onto `bundle exec` is the remaining half and is stated as not done, in the file.

### 10 + 4 (logging) — Push relay (MEDIUM / LOW)

- Replay protection moved from a per-process dictionary to the shared store with an atomic claim, so two workers can no longer each accept the same signature. It now fails **closed** and logs loudly instead of silently accepting.
- Token redaction extended beyond the access log: the rate-limit directives no longer write the raw request line into the error log, and the port-80 redirect no longer logs an unredacted URI.
- Deployment now fails if certificate issuance fails, instead of leaving an HTTP-only proxy serving.

### 7 (part) — FFmpeg

Bumped 4.4.5 → **4.4.8**, the current head of the branch. The digest is not copied from a release page: the tarball was downloaded and hashed (`c73848c4…c39c3`), and its detached `.asc` checked — *Good signature* from the FFmpeg release key `FCF986EA15E6E293A5644F10B4322F04D67658D8`.

Qt 5.12 and OpenSSL 1.1.1 are a migration project, not a bump, and are deferred deliberately. Your own note about FFmpeg's exposure here being mostly local camera/video-device decoding is the reason we are comfortable sequencing it that way rather than the reverse.

### `.gitattributes`

Added; shell scripts and build files are pinned to LF.

---

## Open, with reasons

### 1 — History-sync sender impersonation (HIGH)

Confirmed, and it is the same issue we listed as open in our previous response. It needs an original-author signature over `(group, message id, timestamp, content digest)`, carried as an optional field so older clients ignore it, with unsigned history marked unverified during a transition — i.e. a protocol version bump. We are not shipping a rushed version of that.

What we did do in the meantime, and what you can verify: unauthenticated sync packets can no longer write the persistent peer table; synced rows are never re-served, so a forgery cannot launder through an honest client; packets claiming to originate from us are rejected; and **private member-to-member messages are no longer served through history sync at all** — we found that leak ourselves between your two reviews and fixed it.

Your point about storage bounds is taken and not yet done.

### 4 (enforcement) — The relay accepts unsigned requests

Correct, and the cause is on the client: the signing secret is empty in both shipped app builds, so **no released client signs anything**. Enforcing today would silence notifications for every installed user. Order: ship signing clients → confirm coverage from relay telemetry → enforce. The extractable shared secret is a real design limit; per-install capabilities are the right answer and are not in this batch.

### 6 — Plaintext identity export

The export is `0600`, memory is zeroed, staging copies are deleted in `finally`, and stale bundles are wiped before a new export. The file itself is still plaintext, because it is the artefact the user collects and the format is what other clients import. Making the encrypted container the default is a user-facing change we want to do deliberately.

You are right that the format is not the constraint — the encrypted variant is equally interoperable, and this same code already writes it for the local profile.

### 9 — Keychain tri-state (LOW)

Accepted, not yet done.

---

## On your closing observations

**Zero unit tests** — correct, and the sharpest point in your report. Everything in these two rounds was verified by builds, adversarial review and manual device QA. That is why our own fixes needed three correction rounds: nothing catches a regression automatically. The first tests will target packet parsing and the assembly state machines, which is where both of your reviews found real defects.

**Test target could not build offline** — noted; the Kotlin plugin is not vendored.

---

## Verification

What was actually run, not what was intended:

- Android `:app:assembleRelease` (R8) — green, with the pin gate active: 338 verified.
- iOS `xcodebuild` — `BUILD SUCCEEDED`.
- `app/verify_pins_against_upstream.sh` — **338/338 against Google Maven / Maven Central / JitPack, 0 mismatches, 0 unresolved.**
- FFmpeg 4.4.8 tarball: SHA-256 recomputed and GPG signature verified against the FFmpeg release key.
- All six root workflow files parse; `bash -n` over every changed shell script; `app.py` parses.

Not verified, and we would rather you know it: **none of this batch has been exercised on devices.** There is still no fuzzing, no sanitizer coverage and no unit test — see your closing point, which we agree with.
