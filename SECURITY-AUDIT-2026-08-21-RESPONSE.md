# Response — Security Audit, 21 August 2026

Branch `fix/audit-2026-08-21`. Nine findings, K-01 … K-09. All nine are addressed; three carry an
owner decision or a build that cannot be produced on the machine this work was done on, and those are
named below rather than folded into a "done" column.

Every claim of the audit was re-verified against the tree before anything was changed. Where the
audit was right, it is credited; where it was imprecise or where the recommended fix would have
caused harm, that is stated with the evidence, because a remediation that quietly implements a wrong
instruction is worse than one that argues with it.

---

## Summary

| ID | Severity | Finding | State | Commit |
|---|---|---|---|---|
| K-01 | HIGH | Unsigned/legacy NGC history is an attribution downgrade path | **Fixed**, needs device QA | `b145bd9` |
| K-02 | MEDIUM | Relay serves unsigned/invalid requests in soft mode | **Telemetry + truth fixed**; the flip is operational | `a83ac71` |
| K-03 | MEDIUM | Global push HMAC secret is extractable | **Rotation fixed**; the credential design is scoped, not built | `681bb1a` |
| K-04 | MEDIUM | Desktop on EOL Qt 5.12 / OpenSSL 1.1.1 | **Gated and dated**; the migration itself is blocked | `185328f` |
| K-05 | MEDIUM | Relay image/dependencies unpinned and unhardened | **Fixed** | `b21789d` |
| K-06 | MEDIUM | Android release toolchain not pinned, install failures ignored | **Fixed** | `8971fe3` |
| K-07 | MEDIUM | iOS lockfile not enforced by the release commands | **Fixed** | `ca9f587` |
| K-08 | LOW | CA-anchor pinning expires and fails open with nothing watching | **Fixed** | `2fcde36` |
| K-09 | LOW | Per-IP rate limiting is process-local | **Fixed** | `1c8d47c` |

---

## What the audit did not have

Nine things were found while verifying its claims. Four of them are worse than the finding that led
to them.

1. **The Android release workflow installed the wrong NDK.** It provisioned `ndk;21.0.6113669`; the
   two modules that actually compile C into the shipped APK declare `ndkVersion "23.2.8568313"`, and
   `build-tools` was never installed at all. Releases nevertheless succeeded, which is the proof of
   what was really happening: AGP downloads missing SDK components at task time. The pinned-toolchain
   step was decorative, and fixing only `|| true` would have changed nothing observable. (K-06)

2. **`yes | sudo sdkmanager` under `set -o pipefail` always fails.** `yes` dies of SIGPIPE and the
   pipeline reports 141 even on success — so `|| true` was load-bearing, not sloppy, and we cannot
   know those installs ever worked. (K-06)

3. **The documented push-auth ROLLBACK was an outage.** The runbook told the operator to clear
   `PUSH_RELAY_AUTH_SECRET` for "fully off … clients without it keep working". Both halves have been
   false since the fail-closed change: every request 401s, and compose's `:?` means the container
   will not start. That advice fires precisely when someone is undoing an enforcement mistake. (K-02)

4. **The adoption counters did not survive the runbook.** `/data` had no volume, so
   `docker compose up -d --build` — step 2 of that same runbook — wiped the numbers step 4 tells you
   to read. A relay rebuilt minutes ago reported 100% adoption off two signed requests. (K-02)

5. **A second, cheaper forgery channel on Android.** History also arrives through the toxproxy relay,
   where the NGC author is 64 characters taken from a *friend's* message body — no magic, no version
   byte, and no signed form that could ever exist. It bypassed the group receive path entirely. A gate
   scoped to the 0x01 packet alone would have left it open. (K-01)

6. **`requests` and `flask` both carried advisories, not just `requests`.** The audit named
   CVE-2024-47081 (fixed in 2.32.4). `requests==2.32.3` also carries CVE-2026-25645, fixed in
   **2.33.0** — so the suggested 2.32.4 would still have been vulnerable — and `flask==3.0.3` carries
   CVE-2026-27205, unmentioned. (K-05)

7. **libexpat 2.4.1** is bundled into the Windows desktop build: below the CVE-2024-8176 floor and
   predating the entire Feb–Mar 2022 expat CVE wave, with upstream at 2.8.3. Unlike Qt and OpenSSL,
   nothing structural blocks that bump. (K-04)

8. **A third expiry-drift surface in the CA pinning.** The date is written out twice in incompatible
   syntax — `expiration="2027-08-01"` in XML and `c.year/c.month/c.day` in Objective-C — with nothing
   linking them. Editing one and shipping is a silent one-platform fail-open, and it is the easiest
   mistake to make during exactly the rotation the finding asks for. (K-08)

9. **The iOS app cannot be linked from a clean checkout, and nothing said so.** `.gitignore:18` is a
   blanket `*.a`, which silently excludes
   `libopus.xcframework/{ios-arm64,ios-arm64-simulator}/libopus.a` — headers and `Info.plist` are
   committed, the libraries are not. libopus is a `vendored_frameworks` entry, so `pod install`
   succeeds and the failure appears only at link time: `ld: library 'opus' not found`. Builds had
   always been done on a machine where `scripts/build-ios-native-deps.sh` had been run at some point,
   so nobody hit it, and `docs/BUILDING.md` still said "pod install, open Xcode". Found by the first
   run of the new macOS workflow, which is exactly what it is for. (K-07 adjacent)

   A second, quieter one alongside it: `vpx.framework`'s binaries *are* committed (no `.a` extension,
   so the ignore rule missed them) — but `vpx` is byte-identical to `vpx-simulator`, so a **device**
   build from a clean checkout silently links the simulator slice until `vpx-device` is copied over
   it.

10. **A rate-limited request burned its signature.** Auth ran before the rate check and validating a
   signature *consumes* it, so a signed request refused for rate had already spent its single-use
   signature and the client's retry read as a replay. In the other direction, requests with bad or
   missing auth were never charged to the bucket at all — the relay's limiter did not limit
   unauthenticated floods, only well-behaved callers. (K-09)

---

## Corrections to the audit

- **K-01's recommended fix, implemented literally, destroys history.** The 0x02 handler deliberately
  inserts nothing — the unsigned packet creates the row and the signed one records a verdict about
  it. "Reject unsigned history claiming that author" therefore drops every message from exactly the
  authors doing the right thing. It only works once the signed twin is guaranteed to arrive first,
  which is why the emit order was reversed on both mobile clients. See `DESIGN-ngc-signed-history-sync.md` §10.
- **K-01's desktop clause is about files, not text.** Desktop parses four packet ids and synced text
  is not among them; the only synced form it accepts is `0x03` files. It also cannot verify anything —
  version 0x02 dies at the parser — so every synced row it holds is unverifiable by construction.
- **K-08's premise about the iOS side is wrong.** iOS embeds whole base64 DER *certificates*, not SPKI
  hashes. The obvious implementation of the requested check — diff the base64 in both files — would
  report divergence forever. The two only become comparable after hashing each certificate's
  SubjectPublicKeyInfo.
- **K-08's "the two sets should be identical" is also wrong.** Android additionally pins the LE YE1
  intermediate, and should: an Android `<pin-set>` matches any certificate in the chain, while iOS
  uses `SecTrustSetAnchorCertificatesOnly(true)`, where an intermediate anchor adds nothing over Root
  YE. The asymmetry is deliberate and is now encoded, with a guard so the allowlist cannot rot.
- **K-09 is not exploitable as described.** nginx already applies `limit_req` at 30r/m burst=10 on the
  only vhost that proxies to the relay — shared across nginx workers, four times stricter than the
  relay's own default — and the container port is bound to 127.0.0.1. The doubling was unreachable
  through the front door. Fixed because a backstop that lies about its ceiling is worse than none.
- **K-02's closure criteria were already three-quarters met.** Under enforcement the
  missing/stale/replayed/bad-HMAC cases all 401 (driven through both endpoints), `/health` reports the
  mode, and "exactly once across both workers" is genuinely guaranteed — two processes claiming one
  signature against one DB produced exactly one winner, 8 of 8. What was missing was resolution and
  truthfulness, not mechanism.
- **K-07 is a self-declared TODO, and the release script invokes neither `pod` nor `fastlane`.** The
  Gemfile header states the gap in plain English. The bare invocations were in a simulator build and a
  QA harness; the release-critical one was a line in the documentation, i.e. it ran on a human's Mac
  before the release script started.

---

## Regression checklist (audit §8)

| Check | State |
|---|---|
| No private group message emitted through history sync | Pre-existing, unchanged |
| A peer cannot edit/retract another author's message | Pre-existing, unchanged |
| Known HSK author + unsigned history ⇒ reject/quarantine | **Now rejected** (K-01); needs the three-client test |
| Bad signed-history signature ⇒ no verdict, no notification | Pre-existing; notification gate added (K-01) |
| History/file request floods stay within budgets | Pre-existing, unchanged |
| Cancelled transfer cannot be resurrected | Pre-existing, unchanged |
| Push unsigned/bad/replayed/stale ⇒ 401 in enforce mode | Verified by test; per-outcome counters added (K-02) |
| Rate limiting uses the real client IP only from a trusted hop | Pre-existing; now also shared across workers (K-09) |
| Android exported components accept only intended actions | Pre-existing, unchanged |
| Decrypted media / crash logs / backups off shared storage | Pre-existing, unchanged |
| Every release pin verified; CI cannot regenerate pins on the signing path | Pre-existing; toolchain now covered too (K-06) |
| Build toolchain versions recorded; provisioning failures stop the build | **Fixed** (K-06) — TOOLCHAIN.txt is attested |
| No release uses an EOL crypto runtime without a documented backport source | **Gated** (K-04) — waivers, dated and owned |
| Gitleaks full-history scan green; keys outside git | Pre-existing, unchanged |

---

## New CI gates

`.github/workflows/security-checks.yml` — push, pull request, dispatch and a Monday cron. All four
checks are offline-capable, need no SDK, no Xcode and no Qt, and each is red under mutation:

| Check | Catches |
|---|---|
| `check-push-ca-anchors.py` | anchor expiry within 120 days, Android/iOS divergence, the two expiry dates disagreeing |
| `check-python-deps.py` | a new advisory against any pinned relay dependency; an unhashed or empty lock |
| `check-ios-bundler.py` | a `pod`/`fastlane` invocation that escapes Bundler |
| `check-bundled-deps-eol.py` | an EOL or below-floor desktop dependency without a current waiver; inventory drift from the build scripts |
| `check-ios-downgrade-policy.py` | the Objective-C anti-downgrade rule diverging from the Android truth table |

The last one exists because the rule is implemented twice, in languages with different integer
semantics, and no CI job here has Xcode. It extracts the real policy body, compiles it as C and runs
the same table the JUnit test asserts — including a case ordinary inputs cannot reach, where uint64
underflow would read as "seen moments ago" and refuse a peer's history.

---

## What is NOT done, and who has to do it

**Android is now built and tested on a real device image; iOS and desktop are not.**

An Android SDK and a headless emulator were set up on the build host (Ryzen 9 3900, Windows Server
2022). Acceleration came from Google's AMD hypervisor driver — `extras;google;Android_Emulator_Hypervisor_Driver`
— rather than Windows Hypervisor Platform, so it needed no Hyper-V and no reboot on a machine that is
also serving traffic. `scripts/qa-android-emulator.sh` reproduces the whole setup.

What ran:

| | |
|---|---|
| `./gradlew :app:testDebugUnitTest` | **172 tests, 0 failures**, 16 classes — the real Gradle task, not a hand-rolled javac harness |
| `./gradlew :app:connectedDebugAndroidTest` | **21 tests, 0 failures, 0 skipped** on an Android 14 x86_64 emulator |
| relay | 57 pytest cases |
| CI checks | all five, each proven red under mutation |
| ObjC policy | extracted, compiled as C, run against the Android truth table |
| rate limiter | four-process contention run |

The device suite is worth spelling out, because two thirds of it had never executed:

- `NgcHistSigNativeTest` (8) — the Ed25519 JNI, including that the pre-image this device signs matches
  the frozen cross-platform vector. This is the foundation K-01's verdict path stands on.
- `NgcHistoryDowngradeDeviceTest` (7) — **new**: the anti-downgrade gate against the real SQLCipher
  store, covering the case-convention mismatch between the write path and the gate, a verdict that
  must not vouch for different text under the same message id, the stale-key anti-lockout, and
  multi-byte UTF-8 surviving the round trip.
- `NgcHskStoreDeviceTest` (6) — these had SKIPPED on every run since they were written. A fresh
  install lands on `OnboardingActivity` and waits for a human, so no profile ever opened. The new
  `KhandaqFirstRun` helper walks first-run through the accessibility tree, and they now actually run.

What still did not run: a pod build, a desktop build, and every workflow change — the workflows execute
for the first time in CI. `.github/workflows/ios-build.yml` is the iOS half: a macOS runner is the only
lawful way to reach Xcode and the iOS Simulator, since the Simulator ships only inside Xcode, Xcode is
macOS-only, and macOS on non-Apple hardware breaks Apple's licence.

Required before release:

1. **K-01 three-client QA.** The device test above proves the gate against the real database, but not
   the audit's literal criterion, which is a three-client scenario over the live Tox network: an
   attacker sends unsigned forged history claiming a victim whose HSK is known, and the updated
   receiver must not store or render it. That needs a purpose-built attacker binary and NGC group
   connectivity held for several minutes. Plus a mixed-version group, to confirm non-signing authors
   are unaffected.
2. **K-01 availability decision.** Once an author's key is known, their history reaches a peer only
   from them — a newcomer joining while they are offline gets nothing from them. Inherent to the rule;
   the single tunable is `KEY_STALE_MS`. Accept it, or say so before shipping.
3. **K-02 operational flip.** `PUSH_AUTH_ENFORCE=1` remains an operator decision and is still blocked
   on the real prerequisite: no signed clients exist in the field yet, so the adoption percentage is
   structurally 0 and no relay code moves it. Deploy the current relay first; the counters only start
   surviving redeploys now that `/data` is a volume.
4. **K-04 waivers expire 2026-11-19.** OpenSSL and Qt are blocked on the Qt 6 migration. **libexpat is
   not** — it is the cheapest of the three to clear and only needs one build.
5. **K-03 per-install capabilities** — designed in `DESIGN-push-per-install-capabilities.md`, not
   built. Recommended after the signed-history rollout finishes, because both change the same live
   emission paths on two mobile clients.
6. **K-05/K-06 first runs.** The relay image build and its post-deploy assertions, and the Android
   toolchain gate, all execute for the first time in CI / on the VPS. The one calibration risk is that
   the drift check may name a component AGP auto-downloads (most likely a CMake package); its error
   message says exactly what to add.
