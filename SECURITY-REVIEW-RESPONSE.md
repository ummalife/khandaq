# Khandaq — response to the external security review

**Response date:** 9 August 2026
**Responding to:** `review.md` — 2 critical, 8 high, 5 medium
**Repository state:** master `b24469f5`; the remediation described here is **not yet committed** (see *Verifiability* below)

---

## Verifiability — read this first

The fixes described in this document are in a working tree, not yet pushed. **You cannot verify most of them from the public repository yet.** Where a claim refers to code that is already in `origin/master`, the commit hash is given and you can check it today. Where it refers to this batch, it is marked *(this batch, unpushed)*.

We are sending the document in this state because you asked for a response, not because the work is finished shipping. The commit hash will follow.

A second distinction runs through the whole document: **"fixed in source" is not "fixed for users."** The Android native library is built by CI from a separate upstream clone; desktop binaries are rebuilt and released manually. Where a fix has not reached installed apps, that is stated.

### Status legend

| Status | Meaning |
|---|---|
| **CLOSED** | Attack eliminated |
| **MITIGATED** | Narrowed, not eliminated; the remainder is described |
| **ALREADY CLOSED** | Fixed before your report, in a commit you can date |
| **CONFIRMED, NOT YET FIXED** | You are right; we have not fixed it |
| **MIXED** | A bullet list in your report; each sub-point carries its own status in the table for that finding |
| **ACCEPTED** | Deliberately not fixed; rationale given |

---

## Summary

You filed 15 findings. Our accounting, which should reconcile against the per-finding sections below:

- **6 were already fixed in code before your report** — findings 1, 3, 7 (the write path), 13, 14 and 15 (the file-mode, memory-zeroing and staging-delete hardening), in commits dated 17 June – 7 August 2026. You were reading an older revision; the evidence is below and it is not a criticism of your work.
- **7 were confirmed and are fixed in this batch** — 2, 4 (in part — most of finding 4 also predates you), 5, 8, 9 (in part), 10 (in part), 11.
- **1 is confirmed and remains open by design** — finding 6, history-sync sender authentication, which needs a protocol change.
- **1 we assessed wrongly twice and you were right both times** — finding 12, the Windows credential store. It is undefined behaviour. It is now fixed, in this batch, after we twice recorded it as not-UB.

Three findings carry a correction of something we said before: 7, 12 and 15. In each case your original filing was right and our first internal assessment was wrong. They are marked in place rather than quietly restated.

Findings 9 and 10 are bullet lists in your report and do not reduce to a single status; each sub-point is answered in its own row below, which is why they are labelled **MIXED**.

**Evidence that the review predates our 1–7 August work.** Not the version strings — those are still stale at HEAD and you were right to flag them. The decisive evidence is your own line numbers. They line up, without exception, at commit `4a5e5ad1` (1 August 2026, 17:57:14) — the last commit before our security batch began 24 seconds later with `c3aa59f2`. Spot-checks at that revision: `OCTNgcGroupLiveVideo.m:451` is the plane-size computation and `:467` the `+ 32` decode width (today, after `f630a3af`/`e908dd4a`, lines 455 and 477); `core.cpp:1871` is `NgcIncomingAssembly& asm_ = it.value();`, `:1900` is `ngcIncomingAssemblies.remove(key);` and `:1905` is `QFile in(asm_.outPath);` — precisely the retain / remove / dereference sequence you describe; `KeychainManager.swift:156` is `func setData`; `HelperGroup.java:9755` opens `handle_incoming_sync_group_message`; `GroupMessageListActivity.java:4449` is `int w2 = 480 + 32;`; `credentialstore.cpp:73` is the double-`toStdWString()` expression.

Every fix we mark ALREADY CLOSED below landed after that snapshot — with one exception we should name rather than let you find: the SHA-pinning half of finding 14 (`1a338ff7`, 31 July) was already in the tree you read.

**The most important sentence in this document:** the Android group-video overflow (finding 2) is fixed in source but **has not reached any user**. Installed Android clients are exposed until CI rebuilds the native library and a new Play release ships. Your recommendation was to disable group video in the meantime; we did not, and we explain that decision under *Response to your closing recommendation* rather than leaving it implicit.

---

## Critical findings

### 1 — iOS group-video decode overflows the frame planes — **ALREADY CLOSED** (commits `f630a3af`, `e908dd4a`, 7 Aug 2026)

Correct as filed, including the mechanism and the magnitude. The decode call requested `kOCTNgcVideoWidth + 32` (512) while the buffers were allocated for 480×640, so every decoded frame overran all three planes:

- luma: `640 × 512 = 327 680` bytes into a `307 200`-byte allocation — 20 480 bytes past the end
- each chroma plane: `320 × 256 = 81 920` bytes into `76 800` — 5 120 bytes past the end, twice

Total ≈ 30 720 bytes across three buffers, which matches the ~30 KiB you measured.

Both halves are fixed. `OCTNgcGroupLiveVideo.m:477` passes the true width, so buffer and packing width agree, and `toxav_ngc_video.m:146-151` rejects any frame whose real plane dimensions are smaller than what will be copied:

```objc
if (real_y_w < out_w || real_y_h < out_h) {
    CVPixelBufferUnlockBaseAddress(imageBuffer, kCVPixelBufferLock_ReadOnly);
    return;
}
```

The read side (`OCTNgcGroupLiveVideo.m:271-278`) now indexes by width, never by the decoder's stride.

**Delivery, precisely:** the app has never had a public App Store release — App Store Connect shows only version 1.0 (never submitted) and 1.4.29 (waiting for review, carrying build 142969, which predates these fixes). The fix reached TestFlight in build **142972, uploaded 9 August** — not on the 7th, when the commits landed. So: testers are covered as of 9 August; there are no App Store users to cover yet; and the version currently queued for review does **not** contain the fix. We will not submit it until it does.

**Also addressed since your report:** you asked that the decoder "require the expected dimensions and `YUV420P` format". The dimension guard was in place; the format was not — `VTDecompressionSessionCreate` was called with no `destinationImageBufferAttributes`, so the output format followed the attacker's SPS. That is now pinned to the planar/bi-planar 4:2:0 formats the copy code actually handles *(this batch, unpushed)*.

**Not done:** explicit buffer capacities in the iOS decoder API (Android does check capacities — see finding 2), and ASan/fuzz coverage. Neither exists. See *What remains open*.

### 2 — Android group video: chroma-plane heap overflow — **MITIGATED in source, NOT delivered** *(this batch, unpushed)*

Correct as filed, and it is the most serious item in the report. Android decodes NGC group video through FFmpeg's *software* H.264 decoder (`--disable-mediacodec` on all four ABIs, swscale disabled), created with no pixel-format restriction. The old guard bounded only the luma stride. A High 4:4:4 SPS (`chroma_format_idc = 3`) makes the decoder emit `YUV444P` with chroma strides equal to the luma width, so `(height / 2) × linesize[1] = 320 × 512 = 163 840` bytes are copied into an `81 920`-byte pinned Java array — ~80 KB past the end, twice per frame, from any peer in a public group. The Java-side sanity check runs *after* the native copy, so it never helped.

The guard now requires 4:2:0 and bounds both chroma strides (`toxav.c:3309-3331`):

```c
} else if (((frame->format != AV_PIX_FMT_YUV420P) && (frame->format != AV_PIX_FMT_YUVJ420P))
        || (frame->linesize[1] < 1) || (frame->linesize[2] < 1)
        || (frame->linesize[1] > (width / 2)) || (frame->linesize[2] > (width / 2))) {
    av_frame_free(&frame); continue;
}
```

`YUVJ420P` is accepted because FFmpeg emits it for full-range 8-bit 4:2:0 with identical layout; the real bound is the stride check. Honest senders encode 480×640, the receiver passes 512-wide buffers, and FFmpeg reports `linesize[1] = 256` — exactly the `width / 2` the guard allows.

On your JNI sub-claim: the wrapper now does check Java array capacities before the native call (`jni-c-toxcore.c:8246-8262`, `GetArrayLength` against the required luma and chroma sizes). The quarter-sized U/V allocation in `GroupMessageListActivity` is unchanged — we bounded the copy rather than the allocation.

**Delivery gap.** The shipped `.so` is built by CI from a fresh upstream clone, so an edit in our tree does not reach it. Delivery required a patch script wired into all four clone sites in `circle_scripts/deps.sh`, plus a CI assertion that the marker is present in every patched tree; the script fails the build if its anchors are missing, so an unpatched `.so` cannot ship silently. **None of this has run yet.** Until it does, every installed Android client remains vulnerable.

---

## High-severity findings

### 3 — Desktop use-after-free on transfer completion — **ALREADY CLOSED** (`7ae45ab7`, 3 Aug 2026)

Correct as filed. The needed fields are copied into locals before `remove(key)` invalidates the reference.

Distributed desktop binaries are release tag v0.2.12 (14 July 2026), so this fix — and every other desktop fix in this document — reaches users only on the next desktop release.

### 4 — Unvalidated chunk counts and missing resource limits — **MITIGATED**, mostly before your report

Splitting by date, because most of this predates you:

- **Desktop, before your report:** `totalChunks == ceil(totalSize / chunkPayload)` (`c3aa59f2`, 1 Aug), the max-chunks ceiling and a 16-assembly cap (`76730909`, 3 Aug), the 60 s stale sweep and the `chunkSize <= chunkPayload` bound (`f630a3af`), and `offset + chunkSize <= totalSize` (`e908dd4a`, 7 Aug).
- **Android, before your report:** the `ceil` equality check has been enforced since `460f0baf`, and a 512-chunk-per-key orphan cap existed. Your "no adequate limits" framing was right in aggregate but not for the count check specifically.
- **This batch:** Android gained the max-legitimate-chunks ceiling, an orphan-chunk TTL, a bound on the *number* of orphan keys and a global byte ceiling; iOS gained a concurrent-assembly cap with eviction.

**Remainder (MITIGATED, not CLOSED):** of the four limit classes you named — global, byte-budget, per-sender, TTL — we now have global and TTL on all platforms and a byte budget on Android. There is still **no per-sender quota** anywhere: one member can occupy the whole assembly cap. Worst case per client is now bounded by the cap times the maximum accepted transfer size rather than by your 200 MiB-per-transfer figure, but a per-sender limit is the right fix and is not implemented.

### 5 — Group-file chunks not bound to the sending peer — **MITIGATED** *(this batch, unpushed)*

Confirmed on all three platforms and addressed on all three. An assembly records the originating peer's public key at BEGIN, and once a **real** key is bound, a chunk from anyone else is dropped unconditionally — regardless of idle time. Completion attributes the file to the recorded sender rather than re-resolving a transient peer id.

Two corrections to what we first wrote, because you would find them:

- The unconditional drop applies **once a real key is bound**. If the key lookup failed at BEGIN and the assembly has taken no chunk yet, the first peer to deliver a chunk adopts it — on desktop and Android. That is a real residual, not a theoretical one.
- Re-binding rules differ per platform: desktop re-binds only via BEGIN after the 60 s sweep threshold; Android re-binds after its 8 s progress-stall window plus a stored-row origin check. We did not unify them.

**Not done:** the signed manifest / content digest you recommended. Sender binding is receiver-side only; it authenticates *who delivered* a chunk, not *what* the file is.

### 6 — History sync allows sender impersonation — **CONFIRMED, remains open**

You are right, and this is the finding we cannot close without a protocol change. A group member can cause a fabricated message to be stored and displayed in another member's chat attributed to a third party — in effect bypassing the per-message sender binding toxcore provides for ordinary group messages.

What this batch prevents:

- unauthenticated sync packets can no longer write into the persistent peer table (no phantom peers, no name overwrites);
- synced rows are never re-served by us, so a forgery cannot launder through an honest client and spread second-hand (Android and iOS);
- packets claiming to originate from us are rejected;
- synced rows are flagged in the database **on Android and iOS**, and cannot be edited or retracted as if they were ours. **Desktop has no such flag** — it stores history-synced files with no marker distinguishing them from first-hand ones.

What an attacker in the group can still do, stated fully: deliver a sync packet directly to any member and have a fabricated message stored and displayed attributed to a public key of their choosing; it persists across restart. **On Android it also raises a system notification and increments the unread badge.** On iOS it raises no notification but counts toward unread when the claimed author is a currently-present peer. It does not propagate beyond members contacted directly.

On rate-limiting: **there is none on Android.** We tried a per-syncer limiter in this batch and removed it — it was keyed on an NGC public key, which is re-issued on every rejoin, so an attacker rotated around it while honest senders (which spawn several concurrent push threads) were being throttled. iOS serves a given peer's history request at most once per 20 s per group. We would rather tell you the limiter is absent than describe one that does not work.

A complete fix needs an original-sender signature over `(group, type, message id, timestamp, content digest)`, carried as an optional field older clients ignore, with unsigned history marked unverified in the UI during a transition. That is a protocol version bump and it is not in this batch.

### 7 — iOS profile password in plaintext preferences — **MITIGATED** (write path closed `c9972d33`, 3 Aug 2026)

**Correct as filed.** On the revision you reviewed, `setData` persisted the value to `UserDefaults` whenever the keychain add/update failed. Our first response called this a misread on your part; that was wrong and we withdraw it.

The write path is gone: the only function that ever wrote to `UserDefaults` can now only delete, on both the keychain-success and keychain-failure branches (`KeychainManager.swift:169-175`).

**Why MITIGATED and not CLOSED:** your second observation still holds. `readKeychainData` returns nil for operational keychain errors as well as for "not found", and `getDataForKey` then falls through to a `UserDefaults` **read** which returns any legacy value before migrating it back into the keychain. On a device that still has a legacy value, a transient keychain error therefore still surfaces the plaintext secret. Closing this means distinguishing `errSecItemNotFound` from operational failures, which we have not done.

### 8 — Android PIN bypassable on a cold start — **MITIGATED** *(this batch, unpushed)*

Confirmed, and the exposure was broader than described: because the foreground service keeps the process alive, the common case is a *warm* process, where the exempt startup wrapper consumed the "was backgrounded" flag so the following content screen was never gated. Notification taps and share intents entered the same way.

The gate is now process-scoped state cleared only by a successful unlock, so every activity started while locked re-asserts it — cold start, warm resume, notification tap and share intent.

**Scope, stated plainly:** the gate is raised only for profiles that have a PIN configured. A profile that enabled the lock before PIN support existed (password-only) gets no cold-start gate.

**On your core point, which we did not implement:** you asked that database keys require user-authenticated Keystore access or the actual profile password. They do not — the DB key is Keystore-wrapped and does not derive from the PIN. So the PIN remains an access gate on the UI, not a cryptographic gate on the data, and against an attacker with the device unlocked and the ability to read app-private storage it is friction rather than a boundary. The device lock screen remains the real control. We are recording this as a rejected recommendation rather than presenting the finding as closed.

### 9 — Push relay identifier exposure and weak authentication defaults — **MIXED**

| Sub-point | Status |
|---|---|
| Stats admin key in query string | **ALREADY CLOSED** (`1fab327a`, 5 Aug) — the endpoint and its data collection were removed, not re-gated. The complete route table is now `/health`, `/toxfcm/fcm.php`, `/` |
| Plain HTTP accepted | **MITIGATED** in source, **not deployed** |
| nginx logging the full URI | **MITIGATED** in source, **not deployed** — tokens are still written to the access log on the live relay today |
| Token-hash retention | **MITIGATED** — truncated SHA-256, 24 h purge; purge is write-triggered, so rows outlive the window on an idle relay. Key rotation, which you also recommended, is not implemented |
| Secrets/identifiers in the query string | **MITIGATED** — a header path exists, but no shipped client uses it; moving the token out of the query changes wake URLs already exchanged between contacts |
| Auth enforcement default | **CONFIRMED — worse than you described**, see below |
| Extractable shared client HMAC key | **ACCEPTED** — a shared symmetric secret in a distributed client is anti-abuse, not authentication. Fixing it is a protocol redesign |
| privacy.html mismatch | **CLOSED** in source, **not deployed** |

**Correcting ourselves on the auth state — and disclosing an outage your finding led us to.** We were about to tell you the relay runs in soft (log-only) mode. It did not. While fact-checking this document we probed the live endpoint and found it returning **HTTP 401 to every wake request**: our own hardening commit `9758a44c` (3 August, "close push-relay fail-open; make auth secret mandatory") made the relay fail closed when `PUSH_RELAY_AUTH_SECRET` is empty, and that variable was never added to the relay's `.env`. From the 5 August redeploy until 9 August, **push notifications were dead in production on both platforms** and we had not noticed. The `/health` field reads `auth_mode: "off"`, which sounds like "checks disabled, everything passes" and means the opposite.

Fixed on 9 August by provisioning the secret; the relay now reports `auth_mode: "soft"` and we verified a wake request traversing to FCM end to end.

The underlying weakness you identified is real and remains: `KHANDAQ_PUSH_AUTH_SECRET` is empty for both Debug and Release in the iOS project, so **no App Store build has ever signed a push-wake request**; Android signs only when the variable was set at build time. Enforcement therefore cannot be switched on today without repeating the outage. Sequencing: ship signing clients → watch the soft-mode counter in relay logs fall to ~0 → enforce.

**privacy.html** was factually wrong — it claimed Apple-only push and no third-party components while both platforms use Firebase Cloud Messaging and our wake relay. It now describes FCM on both platforms, the ML Kit barcode scanner used for QR codes, what the relay receives (push token, sender's Tox public key, sender IP — never message content), the 24 h truncated-hash retention, that your push URL is shared with your contacts, and that saved media survives uninstall on Android. `FirebaseDataCollectionDefaultEnabled=NO` was added to the iOS Info.plist, which means the page must be published with or after the next iOS submission — before that, its "diagnostics are off" statement would be false for the live binary.

### 10 — Build and release supply chain — **MIXED**

| Sub-point | Status |
|---|---|
| x264 from a mutable branch | **CLOSED** *(this batch, unpushed)* — pinned to an exact commit with a post-checkout assertion that fails the build on mismatch. This was the one genuinely unverified input |
| Android release script emitting debug-signed APKs | **ALREADY CLOSED** (`c860d5bb`, 31 July) — the debug fallback is now a hard failure |
| OpenSSL 1.1.1w, Qt 5.12.12, FFmpeg 4.4.5 | **ACCEPTED** — you are right that these are end-of-life or behind. For the record they are not *unverified*: each tarball is SHA-256 checked before use (`buildscripts/download/common.sh`). They are old, that is real maintenance debt on the Windows desktop build, and it does not affect the mobile clients |
| docker-compose binary without checksum | **ACCEPTED** — reachable only when every apt path fails during node provisioning |

**Not done:** container digest pinning and SBOM generation. SLSA build provenance does exist (`.github/workflows/release-provenance.yml`, verifiable with `gh attestation verify`); an SBOM does not.

### 14 — changelog workflow permissions — **ALREADY CLOSED** (`1a338ff7`, `470c8449`, `53b662c7`, 31 July – 3 Aug)

Read-only by default with write scoped to the push-triggered job; actions pinned to full commit SHAs.

---

## Medium-severity findings

### 11 — iOS PIN lockout resets after ten attempts — **MITIGATED** *(this batch, unpushed)*

Confirmed. The counter is no longer cleared at the threshold; a persisted deadline with growing backoff (60 s → 5 min → 15 min → 1 h) applies, PIN entry is disabled the moment the deadline is set, and a successful profile-password login clears it so the owner is never stuck.

Limits: friction, not a boundary. No wipe, no absolute cap, and the deadline is wall-clock — an attacker holding an unlocked device can move the system clock forward to skip it. A monotonic clock does not survive process death; we judged wipe-on-failure too dangerous for a messenger whose users may have no backup.

### 12 — Desktop credential store — **macOS ALREADY CLOSED; Windows CLOSED in this batch**

macOS was fixed before your report, in `f630a3af` (7 August): `CFRelease` on the item reference instead of passing it to `SecKeychainItemFreeContent`, which was running `free()` on a live CoreFoundation allocation.

**Windows: you were right and we were wrong, twice.** The expression built a vector from iterators belonging to two *separate* `toStdWString()` temporaries — a mismatched iterator range, undefined behaviour, exactly as you wrote. An internal check called it not-UB, and our first draft of this document repeated that. Both were wrong.

It is fixed *(this batch, unpushed)*: one named `std::wstring`, then the vector built from it, matching the idiom the same function already used two lines above. A sweep of the file found a second instance of the same class — `std::vector<wchar_t> user(L"account", L"account" + 7)`, where the two string literals are not guaranteed to designate the same array object — fixed the same way. The stored-credential format is unchanged, so existing saved passwords still load.

Verified by compiling the Windows arm on this macOS host: the production file is textually included into a forcing translation unit with `Q_OS_WIN` defined and minimal `windows.h`/`wincred.h` stubs, so the real `#elif defined(Q_OS_WIN)` branch is what the compiler sees — clean, zero warnings. A true MSVC build and an on-Windows round-trip of `CredWriteW`/`CredReadW` have not been run; the Windows CI job should confirm.

### 13 — Unsigned bootstrap node updates — **ALREADY CLOSED** (`86cffc71`, 3 Aug 2026)

The runtime fetch was removed; there is no network path left to sign. A user-supplied local `nodes.json` is still honoured — an intentional qTox feature with a local trust boundary.

### 15 — Android exports an unencrypted Tox identity — **MITIGATED**, hardening predates your report

**Correct as filed, and we initially recorded it as not reproducing. That was wrong.** The export calls `export_savedata_file_unsecure`, whose native implementation ignores the passphrase argument entirely and writes raw `tox_get_savedata()` bytes — containing the Tox private key.

Hardening that landed **after your snapshot but before your report** (`bde5a2f0`, 3 August; `460f0baf` for the staging delete): the file is created `0600`, the plaintext buffer is wiped with `sodium_memzero` before free, and the staging copy in the app-private cache is deleted on the success path. To be exact: your revision is 1 August, so you would not have seen the `0600`/zeroing work — it was not done in response to you either.

**Done in this batch, because you asked:** deletion moved into `finally` blocks so the staging copy cannot survive an exception or an early return, on both the export and import paths *(unpushed)*. Reviewing that, we found a second export flow we had not mentioned to you at all — an "export all files" path writing the plaintext key to external storage, where it persisted indefinitely. That is addressed too; *What remains open* states exactly what is left on disk and for how long.

**Correcting something we wrote earlier in this document:** the `.tox` format is *not* plaintext by necessity. The encrypted variant is equally interoperable, and this same JNI already writes and reads it for the local profile. Plaintext export is a default we chose, not a constraint the format imposes. Changing that default is the right fix and is not done.

---

## Issues we found that you did not

From our own hostile-perspective sweep of the same subsystems, run after remediation. Reported because you would rather know.

1. **Any group member could delete another member's media message.** The delete-for-both handler bound text messages to their author but not the file branch — and the hash it matched on travels in the clear in every group file packet. On iOS the text branch was bound to a *display name*, which any member can copy. Fixed on Android and iOS; desktop does not implement the packet. **Residual:** rows carrying no recorded author — everything stored before this build, including all history-synced rows — keep the previous, weaker rule, because refusing them would permanently break legitimate deletion of existing media.
2. **A two-step laundering attack on iOS.** An attacker could send a BEGIN carrying our outgoing message's id, cause the receiver to adopt *our own* row and stamp the attacker's key onto it, then legitimately pass the delete check. Closed at the BEGIN, COMPLETE and single-packet paths. Checked on Android: it does not transfer there — outgoing rows carry an explicit direction flag and our own key, and incoming handlers reject them before any mutation. That guard predates this batch.
3. **Android BEGIN had no maximum-chunk ceiling** — one packet could drive an OOM.
4. **Android orphan-chunk map had no TTL, no key-count bound and no byte ceiling.**

---

## Response to your systemic observations

You wrote that duplicated protocol and toxcore code across platforms has already produced divergent validation and two distinct native video bugs, and that a single versioned protocol spec, a shared hostile-input corpus and sanitizer-enabled native CI would reduce the drift. **We agree, and this batch is evidence for your argument rather than against it.**

Concretely: git carries two independent toxcore checkouts (desktop and iOS), and CI clones a third per Android ABI at build time — four more trees that exist only during a build. The same class of bug required two unrelated patches — `toxav_ngc_video.m` for iOS, `toxav.c` for Android — and delivering the Android half required a Python patch script replicated across four clone sites plus a CI assertion, precisely because the platforms do not share the code. Finding 5 is the same story at the protocol level: three implementations of one wire format, each with different validation, and our own fixes to them still differ in their re-binding rules.

There is no protocol specification document, no shared hostile-input corpus, and no sanitizer-enabled native CI. We are not going to claim otherwise. These are on the plan below with the honest note that they are larger than a remediation batch.

On CI coverage: you are right that nested project workflows do not run as root workflows and there is no active security matrix. On release metadata drift you are also right, and it is still uncorrected at HEAD — the README advertises v0.2.8 and the site says v0.2.12.

---

## Response to your closing recommendation

You recommended three immediate actions. Honestly:

1. **"Disable both group-video implementations."** **We did not do this.** We fixed the guards instead and left the feature enabled. You should know the reasoning and judge it: on iOS the fix reached TestFlight on 9 August, and there are no App Store users yet, so the practical iOS exposure is testers between your report and that build. On Android the fix is real but undelivered, which means our decision leaves installed users exposed for as long as the rebuild-and-release cycle takes — and that is exactly the interval your recommendation was aimed at. A remote kill-switch for NGC video receive does not exist; adding one is itself a shipped-client change, so it would not close the window any faster than the fix. We accept that this is a judgement call weighted toward not breaking a working feature, and that a stricter reading of your advice would have disabled receive in the next Play build regardless.
2. **"Fix the desktop use-after-free."** Done before your report (`7ae45ab7`), though undelivered until a desktop release.
3. **"Suspend unauthenticated group file/history protocols until sender binding and strict resource limits are implemented."** We implemented sender binding and resource limits rather than suspending the protocols. Sender binding is done on all three platforms; resource limits are done except for a per-sender quota. History sync remains unauthenticated at the message level — finding 6 — so on that half your condition is not met and the protocol is still enabled.

---

## What remains open

1. **Delivery of the Android video fix.** Highest priority; installed clients exposed until CI rebuild + Play release.
2. **History-sync sender authentication (finding 6).** Needs a signature and a protocol bump; design sketched above.
3. **Windows credential store UB (finding 12).** Confirmed, unfixed.
4. **iOS keychain read-path fallback (finding 7).** Distinguish `errSecItemNotFound` from operational errors.
5. **Push-wake signing end to end**, then relay enforcement. Currently absent, not merely unenforced.
6. **Relay deploy** — log redaction and HTTPS enforcement are in source only.
7. **Desktop release.** Every desktop fix here is unreleased; distributed build is v0.2.12. This also gates the OpenSSL/Qt/FFmpeg migration.
8. **Per-sender transfer quota**, **signed manifest/content digest** for group files, and **encrypted-by-default profile export**.
11. **The plaintext export bundle itself.** Precisely, so you can reproduce it: after a successful "export all files", `/sdcard/Android/data/com.khandaq.messenger/files/fullexport/` contains `unsecure_export_savedata.tox` — the Tox savedata in the clear, including the long-term private key — alongside `main.db` and `files.db`, which are encrypted. That file *is* the artefact the user collects, so it cannot be deleted on success; it is wiped when the next export starts and on any failed or aborted export, but there is no time bound. On the file mode: the native writer opens it `0600`, but the destination is emulated external storage, where the filesystem synthesises permissions and ignores the mode — so on Android 11+ the directory is inaccessible to other apps, while on Android 5–10 (our `minSdkVersion` is 21) any app holding `READ_EXTERNAL_STORAGE` can read it for as long as it exists, and `adb`/root can read it on any version. Deletion is `unlink`, not a secure erase, and failures are swallowed.
12. **Container digest pinning and SBOM generation** (finding 10), and **push-token hash rotation** (finding 9) — both named in your recommendations and neither done.
13. **Stale-staging pre-delete in two more callers of the same JNI export** (`PasswordBackupHelper`, `DbRekeyHelper`), found while fixing the first one.
9. **Sanitizer-enabled native CI, a shared hostile-input corpus, and a protocol specification** — your systemic recommendation, accepted and not started.
10. **Android group-text attribution (found by us, unfixed).** The incoming group-text handler matches on `(group, message id, text)` without a direction filter and rewrites the stored author when it differs, so a modified client reusing our message id and text can overwrite the displayed author of our own message. It cannot delete or edit it — the retract gate additionally requires the row to be incoming. Deferred because the same matching logic backs duplicate-collapse behaviour.

---

## Verification performed, and its limits

Builds with the batch applied: Android `:app:assembleRelease` (R8 included), iOS `xcodebuild`, desktop `khandaq_static`.

**What those builds do not cover, stated plainly:** none of them compiles the patched native C. `toxav.c`, the amalgamation copy and the JNI guard are built only by the Android CI job and the desktop dependency build, neither of which has run. The single highest-severity fix in this document has therefore been reviewed and syntax-checked, but not compiled in its real toolchain, and not executed.

There is no ASan run and no fuzz corpus — you asked for both as a precondition for re-enabling group video, and neither exists.

Every fix was reviewed adversarially, with the reviewer instructed to prioritise one question: *does this reject traffic live clients actually send, or lose user data?* That caught four regressions in our own remediation before they shipped — a history rate-limit that would have discarded legitimate history, a delete-binding that would have made existing media permanently non-retractable, a desktop binding keyed on peer presence that lapses after 58 s on any flaky link, and an iOS gate that would have silently dropped legitimate retractions. They are described above rather than omitted, because they explain why the batch took six rounds.

Device-level QA on group video, group file transfer and history sync across two phones is scheduled with the release that carries these changes; it has not been done.
