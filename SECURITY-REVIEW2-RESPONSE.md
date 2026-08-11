# Khandaq — response to the second external security review

**Response date:** 11 August 2026
**Responding to:** the follow-up review of `master` — 2 high, 6 medium, 2 low
**Base:** commit `3880e7db` plus the batch described here

---

## Summary

Nothing is disputed — every finding reproduced. Of the ten: **seven are fixed outright** (2, 3, 5, 6, 8, 9, 10) and **three are partly fixed** (1, 4, 7), with the remaining half of each named rather than glossed. **Nothing is left untouched.** The `.gitattributes` gap you noted separately is fixed, and the "zero unit tests" observation you closed on is answered with 45 of them, gating the release.

Thank you for the two things that mattered most: catching that our release job **re-pinned** dependencies instead of verifying them, and confirming which of our earlier fixes actually landed. Both changed what we did next.

| # | Finding | Status |
|---|---|---|
| 1 | History-sync sender impersonation | **PARTLY FIXED** — storage/notification bounds done; the author signature needs a protocol version |
| 2 | Release CI replaces dependency pins | **FIXED** |
| 3 | 1:1 chunk assembly allocation | **FIXED** |
| 4 | Push relay: unauthenticated posture + token in logs | **PARTLY FIXED** — logging and TLS closed; enforcement is a rollout |
| 5 | Cancelled group files requestable after restart | **FIXED** — device-verified across an app restart |
| 6 | Plaintext identity export | **FIXED** — encrypted backup is now the default; raw kept behind an explicit, honest warning |
| 7 | Windows runtime dependencies | **PARTLY FIXED** — FFmpeg bumped; Qt/OpenSSL deferred |
| 8 | iOS dependencies unlocked | **FIXED** |
| 9 | Keychain errors vs "not found" | **FIXED** |
| 10 | Push replay protection process-local | **FIXED** |
| — | No `.gitattributes` (CRLF) | **FIXED** |
| — | Zero JVM unit tests | **FIXED** — 45 tests, mutation-checked, gating the release |

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

Qt 5.12 and OpenSSL 1.1.1 are deferred, and the reason is more specific than "it is a big job":

**There is no OpenSSL bump available that is independent of Qt.** 1.1.1w is the *final* release of the 1.1.1 branch, so there is nothing newer to move to inside it, and Qt 5.12 cannot be built against OpenSSL 3 — so the only way off an unsupported OpenSSL is the Qt migration itself. Sequencing them as one project is not a preference, it is the dependency.

We should also correct something we nearly told you: this is **not** blocked on the absence of a Windows CI. There is one — `.github/workflows/windows-build.yaml`, manual trigger, producing the installer, the zip and their SHA-256. So the migration can be verified when it is done; what it needs is the work and a real Windows QA pass, not infrastructure.

Your own note that FFmpeg's exposure here is mostly local camera/video-device decoding is why we were comfortable doing that bump first and this second, rather than the reverse.

### `.gitattributes`

Added; shell scripts and build files are pinned to LF.

---

## Open, with reasons

### 1 — History-sync sender impersonation (HIGH)

Confirmed, and it is the same issue we listed as open in our previous response. It needs an original-author signature over `(group, message id, timestamp, content digest)`, carried as an optional field so older clients ignore it, with unsigned history marked unverified during a transition — i.e. a protocol version bump. We are not shipping a rushed version of that.

What we did do in the meantime, and what you can verify: unauthenticated sync packets can no longer write the persistent peer table; synced rows are never re-served, so a forgery cannot launder through an honest client; packets claiming to originate from us are rejected; and **private member-to-member messages are no longer served through history sync at all** — we found that leak ourselves between your two reviews and fixed it.

**The second half of your finding — "the same path explicitly lacks rate/storage bounds, enabling database and notification flooding... impose per-group row/byte limits" — is done.** None of it needs the wire format to change, so there was no reason to hold it behind the signature work. `NgcHistorySyncBudget` applies three budgets per group, separated because they fail differently:

- **Rows per group.** Counted against *synced* rows only, so a group's own live traffic is never rejected however busy the group is. Seeded once per run from the database; a failed count seeds as 0 rather than being skipped, because an unseeded counter would mean no row budget at all — which is the hole itself.
- **Bytes per group per app run.** Deliberately not persisted. It bounds how much a single run can be made to ingest, which is the flooding case, without inventing a lifetime quota that would eventually refuse a legitimately long-lived group.
- **Notifications per group per window.** Rate-limited, not suppressed: a message that genuinely arrived while the user was offline still deserves to notify, it just cannot notify a hundred times. Over-limit rows are still stored and still update the unread badge — only the notification is dropped.

15 unit tests cover the decision logic, and they were mutation-checked the same way as the rest: dropping the row cap, making the seed non-idempotent, removing the backwards-clock guard, and making the group key case-sensitive each turned the suite red (7 failures from 4 mutants).

What remains open on this finding is therefore only the part that genuinely needs a protocol version: the original-author signature.

### 4 (enforcement) — The relay accepts unsigned requests

Correct, and the cause is on the client: the signing secret is empty in both shipped app builds, so **no released client signs anything**. Enforcing today would silence notifications for every installed user. Order: ship signing clients → confirm coverage → enforce.

**We said "confirm coverage from relay telemetry", then checked, and there was no such telemetry** — only a log line per unsigned request. That made the middle step a log-grep, for a number that decides whether flipping enforcement silences real users. So it is now counted: the relay tallies signed vs unsigned wake requests per UTC day and `/health` reports `auth_adoption` with the trailing-window percentage. No token, no IP, no request detail is stored — two integers per day, which is all the decision needs.

Two deliberate properties, both verified: the percentage is `null` rather than 100 when there has been no traffic, so an idle relay cannot read as "fully adopted"; and unlike the replay store this counter fails **open** and silently, because a broken counter must never start dropping pushes.

The extractable shared secret is still a real design limit. Per-install capabilities are the right answer and are not in this batch.

### 6 — Plaintext identity export — encrypted is now the default

You asked us to "make the existing password-encrypted backup format the default and deprecate raw identity export". Done, in that order.

The encrypted container already existed (`PasswordBackupHelper` / `BackupHelper`, the AES-GCM path you listed under positive controls) and it carries the same three things the raw bundle does — Tox identity, database key, database and VFS. So the export screen now offers it as its **default action**, and the raw bundle is the deliberate, explicitly-labelled alternative behind the same dialog.

We kept the raw path rather than removing it: a plain savedata file is what other Tox clients import, and silently taking that away would strand users mid-migration. "Deprecate", not "delete".

**The dialog was also lying, and that is arguably the worse half of this finding.** Its text read *"Encrypted files will be exported to: …"* — while the file next to them holds the Tox private key in the clear. A user reading that had no way to know what they were writing to shared storage. It now states plainly that the identity key is written **unencrypted**, names the folder, and says what someone who reads that folder can do with it. Corrected in all four shipped locales (en, ru, ar, zh-CN).

**Then device QA turned up something that changes this finding's severity, and we would rather you hear it from us.** Walking the shipped UI to look at the new dialog, we could not reach it — and tracing why, the screen is not reachable at all:

- `ExportActivity` is launched from exactly one place: the "reveal passwords" button in `MaintenanceActivity`.
- `MaintenanceActivity` is started only by two static helpers in its own file, and nothing calls them. The new settings screen calls `ToxProfileImportHelper.promptExportSavedata` directly instead.
- The one remaining route, the `MaintenanceActivity` header in `pref_headers.xml`, is never rendered: those headers are loaded by `SettingsActivity`, and the settings tab pushes `SettingsActivity.GeneralPreferenceFragment` directly without ever starting `SettingsActivity`.

So in the shipped app **no user action reaches the plaintext bundle, or the screen that displays the database password.** The code is live, the path is not. That lowers the practical severity of what you found, and we are not treating it as a reason to relax: dead code that writes a private key in the clear is one wiring change away from being live again, which is precisely why the hardening and the encrypted default above stay in.

The pre-existing hardening stands: `0600`, memory zeroed, staging copies deleted in `finally`, stale bundles wiped before a new export.

Not done: the raw file is still plaintext when the user explicitly chooses it. Making the *format itself* encrypted would break import on other Tox clients, which is the reason that path exists at all.

### 9 — Keychain tri-state (LOW) — now fixed

Done, and tracing it confirmed your reading exactly. `readKeychainData` now returns `.found` / `.notFound` / `.failed(OSStatus)`, and the two consequences you predicted are both closed:

- **Migration no longer runs on a failure.** Only `.notFound` triggers the legacy read. On `.failed` — a locked keychain before first unlock, an ACL/entitlement problem, a corrupt item — we serve the legacy value and leave it exactly where it is. Migration can be retried on any later launch; the profile password cannot be un-lost.
- **The legacy copy is purged only on a confirmed write.** `setData` now returns success and the purge is conditional on it (or on the caller deleting the value, where dropping a plaintext copy is always right). Previously the purge ran unconditionally, so a failed migration write destroyed the only remaining copy — this was the concrete data-loss path in your finding.

Two smaller things fell out of the same trace: an item that exists but comes back as the wrong type is now `.failed` rather than `.notFound`, so it cannot trigger migrate-and-purge either; and deleting is no longer skipped when the preceding read failed, so wiping an account is not silently a no-op. `SecItemDelete` returning `errSecItemNotFound` is now treated as success, which it is.

---

## On your closing observations

**Zero unit tests** — the sharpest point in your report, and no longer true. There are now 45, and they target exactly what you would expect: the 1:1 chunk-header admission rule, the reassembly state machine, the NGC group-file chunk bounds, and the new history-sync budgets — the places where your two reviews and our own internal one found real defects.

To make them testable at all, the two admission rules were lifted out of their packet handlers into pure predicates (`MessageChunker.isValidChunkHeader`, `NgcGroupFileTransfer.isValidFileChunkHeader`) — same conditions, same order, no behaviour change. They are plain JUnit with no Robolectric: a test that needs an Android runtime to check an integer bound is a test that ends up skipped.

**We then checked that the tests actually bite**, because a suite that passes against broken code is worse than none. Four bounds were deliberately reverted one at a time — the chunk-count ceiling, the duplicate-sequence guard, the "chunk larger than the declared payload" bound, and the long arithmetic in the offset calculation — and each mutation was caught (6 failures from 4 mutations; the two extra are second tests covering the same bound). Sources restored, all green.

That last mutation is worth singling out because it is the kind of thing review misses: with `int` instead of `long`, `index * payload` overflows to a large negative offset, which then compares as comfortably inside the file. The test constructs exactly that case.

`testDebugUnitTest` now runs in the release workflow, before anything is built or signed, and the step fails if the suite reports zero executed tests — the same vacuous-pass hole we had just closed on the pin gate.

**Test target could not build offline** — noted; the Kotlin plugin is not vendored. It builds and runs online, which is where CI runs it.

---

## Verification

What was actually run, not what was intended:

- Android `:app:assembleRelease` (R8) — green, with the pin gate active: 338 verified.
- Android `:app:testDebugUnitTest` — **45 executed, 0 failures, 0 skipped**, plus the mutation runs described above (9 mutants, all caught).
- iOS `xcodebuild` — `BUILD SUCCEEDED`.
- `app/verify_pins_against_upstream.sh` — **338/338 against Google Maven / Maven Central / JitPack, 0 mismatches, 0 unresolved.**
- FFmpeg 4.4.8 tarball: SHA-256 recomputed and GPG signature verified against the FFmpeg release key.
- All six root workflow files parse; `bash -n` over every changed shell script; `app.py` parses.

**Device QA (Samsung SM-A075F, Android 16, the release APK from this batch):** app installs over the previous build, opens its SQLCipher database, bootstraps, goes online (`self connection status=2`), restores pending delivery, crash buffer empty. Chats, groups and the chat screen render correctly; attaching a 3.15 MB file to a group creates the outgoing row on the chunked path as expected. This is also how we found that the plaintext export screen is unreachable (finding 6).

**The cancelled-transfer-survives-restart test (finding 5) passes on devices.** Getting there took two detours worth reporting, because both are product bugs of ours.

The first attempt could not start a transfer at all. The two phones were on different networks (one on carrier LTE behind CGNAT, one on Wi-Fi); on the same LAN the group peer link then came up one-way, and that cleared only after restarting the app. Even then the already-queued transfer **did not resume**, and in that state the row offers **neither a cancel nor a retry control** — a dead end for the user. Both of those are pre-existing, outside this batch, and now tracked separately.

Sending a *fresh* file while the peer was online worked immediately, which is how the test finally ran:

1. 5.24 MB group file, chunked path, reached 26% and the cancel control appeared.
2. Cancelled. The row moved to the failed/retry state, and the log shows the tombstone actually written: `set_g_opts:(INSERT):key=kqngccancel_<group>|<msg_hash>`.
3. Force-stopped the app and relaunched.
4. **The cancelled row came back still cancelled** — failed/retry, not sending. The other queued transfer in the same chat was untouched, so the tombstone is per-message and not a blanket stop.

What we did **not** observe directly: a `FILE_REQUEST` from the peer being refused for that message. The gate is in the code and unit-tested, and the persisted tombstone it reads is confirmed present, but the remote-request path itself was not exercised.

Not verified, and we would rather you know it:

- The unverified-sender marker (finding 1, step 2) is regression-checked only: it does not appear on any live row, but its positive case needs an incoming history-synced row, which we could not produce. Its rule is covered by unit tests instead.

- There is still no fuzzing and no sanitizer coverage.
- Nothing in this batch has been exercised on iOS hardware; iOS verification is the build only. The 45 tests cover the parsing bounds, the reassembly state machines and the history-sync budgets; everything above them — the persistence, the transport, the UI states — is still only covered by builds, adversarial review and manual QA.
