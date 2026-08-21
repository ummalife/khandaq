# Response — Khandaq security re-audit, 21 Aug 2026

Seven findings, R-01 through R-07. **Five are closed**, two remain open and are the two the re-audit
itself classified as architectural. Everything below states what was changed, what was measured, and
where the measurement stops — a claim without a check behind it is marked as such rather than left to
read like one.

The first-round response is `SECURITY-AUDIT-2026-08-21-RESPONSE.md` (findings K-01…K-09, all closed,
merged as `86dc9d0e`). This document covers only the re-audit.

| # | Finding | Severity | Status |
|---|---|---|---|
| R-01 | Shared push-auth secret remains extractable | MEDIUM | **Open** — architectural; two rollout assumptions verified and pinned |
| R-02 | Push relay defaults to soft/monitor authentication | MEDIUM | **Closed** |
| R-03 | Bundled libexpat 2.4.1 below the security floor | MEDIUM | **Closed** |
| R-04 | Desktop TLS stack on EOL Qt/OpenSSL | MEDIUM | **Open** — migration outstanding; the release-blocking half is closed |
| R-05 | Android release lint is non-blocking | LOW | **Closed** |
| R-06 | Raw plaintext identity export remains available | LOW | **Closed** |
| R-07 | Release/distribution metadata drift | LOW | **Closed** |

A finding the re-audit did not raise was found while closing R-06 and is fixed here too: on Android
5.x the app **crashed** on the database-key path. See the last section.

---

## R-02 — Push relay defaults to soft/monitor authentication · CLOSED

`PUSH_AUTH_ENFORCE` defaulted to `0` in `docker-compose.yml`, so an operator who deployed the
documented way got monitor mode and no signal that they had. The variable is now **required** —
`${PUSH_AUTH_ENFORCE:?...}` — so a deploy that has not made the choice fails to start instead of
quietly choosing the weaker option.

Two compose overlays make the choice explicit: `docker-compose.prod.yml` (literal `=1`) and
`docker-compose.softmode.yml` (`=0`, and additionally requires `PUSH_AUTH_ENFORCE_BY` — a named human
and a date, because soft mode is a temporary decision someone owns). `scripts/deploy-push-relay.sh`
takes `--enforce` or `--soft`, symlinks the chosen overlay and asserts the running mode against
`/health` afterwards.

**The constraint that governs when `=1` can actually be switched on, stated plainly because it
decides the sequencing:** no shipped client signs push requests today. `KHANDAQ_PUSH_AUTH_SECRET` is
empty in both iOS build configurations, defaults empty in `app/build.gradle`, and no workflow sets
it. Turning enforcement on right now would return 401 to every wake — that is, every notification on
every device. The mechanism is in place and correct; the order is ship signing clients → watch the
soft-mode counter fall to ~0 → enforce. `docker-compose.yml` carries that note where the operator
will read it.

---

## R-03 — Bundled libexpat below the CVE-2024-8176 floor · CLOSED

Bumped **2.4.1 → 2.8.3**. The waiver is deleted, not renewed; `check-bundled-deps-eol.py` now passes
with libexpat unwaived.

The waiver had said "nothing structural blocks this bump", which was true, so the bump was the right
answer. Scope, because it changes how much the finding mattered: expat here is a **gdb dependency**,
not an app-facing parser. `Dockerfile.windows_builder` builds it in its own layer immediately before
gdb, `build_libexpat_windows.sh` is its only consumer, and nothing in the messenger parses XML with
it — the CVEs are reachable only by a developer feeding the bundled debugger a hostile XML target
description. The inventory's `purpose` field previously implied otherwise and has been corrected.

Checked before the pin was changed rather than after:

- the tarball digest was **recomputed from the downloaded bytes**, not copied from the release page,
  and agrees with what GitHub publishes for `R_2_8_3`;
- the URL template `R_${EXPAT_VERSION//./_}` still resolves at 2.8.3;
- 2.8.3 was **configured and built here** for `x86_64-w64-mingw32` with this repository's exact
  flags — `configure` and `make` both returned 0 — and the resulting static `libexpat.a` still
  exports every symbol gdb 11.1 links against.

**Not verified:** a full Windows container build. There is no Docker on the machine this was done on,
so the CI Windows job is the first place it is exercised end to end. That is recorded in
`bundled-deps.json` rather than left implicit.

---

## R-05 — Android release lint is non-blocking · CLOSED

`lintOptions { }` (the deprecated DSL, silently ignored by AGP 8.6) became `lint { }` with
`checkReleaseBuilds = true` and `abortOnError = true`. `abortOnError` matters more than it looks:
with it false, `AndroidLintTextOutputTask` returns early and the gate cannot fail regardless of what
lint found.

The gate is deliberately **narrow rather than maximal**: `lint.xml` sets everything to `warning` and
raises the `Security` category to `fatal`. A release lint that fails on the accumulated warnings of a
large upstream codebase gets switched off within a week; one that fails only on security issues
survives. `lintVitalRelease` runs in `release-provenance.yml` before the APK is built.

One `HardwareIds` finding is suppressed by path with a reason, in `DbSecretKeyStorage.java`.

**Checked in both directions.** Green on master. Injecting `android:debuggable="true"` into the
manifest turns the release build red — the gate bites. (`allowBackup="true"` does *not* trip it,
because the manifest declares `fullBackupContent`; that is worth knowing before relying on it as a
test.)

---

## R-06 — Raw plaintext identity export · CLOSED

The re-audit described the raw export as "reachable behind an explicit warning". It was reachable
behind rather less than that, and in a more exposed place than the report identified.

**What was actually there.** `Settings → Export profile` — the only export a normal user ever meets —
was one tap plus one confirm dialog from writing the Tox **private key**, unencrypted and
unrevocable, into whatever folder the system picker last used: Downloads, a synced Drive folder,
wherever. The dialog said *"Only Tox ID and contacts are included"*, which describes what is in the
file but not what the file **is**. Whoever holds a copy is that account: they can sign in as the
user, receive what is sent to them, and write as them. There is no password on it and nothing to
revoke. Two further paths in Maintenance had the same shape — the chat export runs
`sqlcipher_export` with `KEY ''`, i.e. the entire message history decrypted onto shared storage, and
"reveal passwords" prints the database key on the display.

**What was not done, and why.** The raw paths are not deleted. Other Tox clients import a plain
savedata file and nothing else, so removing it strands anyone migrating away — a previous review
round already recorded that reasoning and it still holds. The re-audit's remediation allows either
removal *or* a deliberate advanced mode plus re-authentication; this is the second branch, taken
because the first costs users their only interoperable exit.

**What changed:**

1. `Settings → Export profile` now asks which format first, with the **password-encrypted `.kbk`
   container as the default action** and the plain `.tox` as the labelled alternative "for another
   Tox app". The re-audit asked for the encrypted container to be what normal migration and backup
   workflows produce; this row is where that decision actually gets made. The encrypted route already
   existed (`PasswordBackupHelper`) but was offered only from `ExportActivity` and Maintenance,
   neither of which a normal user opens.
2. A warning that states what the file is, replacing the old wording.
3. **Device re-authentication** — lock-screen PIN, pattern, password or biometric, via
   `KeyguardManager.createConfirmDeviceCredentialIntent`. A phone left unlocked on a table no longer
   exports an identity. Devices with no screen lock have nothing to authenticate against; there the
   user types a confirmation word instead. That is weaker on purpose, and the class comment says so:
   on such a device the holder already has the app open, so refusing outright would cost the
   migration path and buy nothing.
4. The part that matters most: `handleExportDestination` — the only method that actually writes the
   key out — **requires a live, single-use authorisation and refuses without one**. A dialog at a
   call site is advice that the fourth entry point forgets to ask for. This cannot be forgotten,
   because skipping it produces a refusal rather than a file.

**Measured** on the emulator (`khandaq-qa-1`, API 34): 6 tests, 0 failures, 0 skipped. A grant is
single-use; an expired grant is refused; a revoked grant is refused; an unauthorised export leaves
the staging file untouched — it never reaches the delete that begins a real export — and writes no
destination; an authorised one passes the guard and spends the grant. **Mutation-checked:** with the
guard replaced by `if (false)` the suite goes red. The test observes the staging file rather than the
Tox layer, so it holds without a profile open.

That test also caught a defect in the first version of this change: the refusal called
`Toast.makeText` on whatever thread it was on, and off the main thread that throws `NullPointerException` —
turning a clean "no" into a crash a caller might catch and retry. The refusal is now posted to the
main looper and cannot throw; the log line is unconditional.

**Not verified:** `StartExportImportTest`, a legacy UI flow that taps the same button, was taught to
answer the gate but was not executed — no workflow runs it, and it needs a full first-run plus picker
interaction. Said in its comment rather than implied by silence.

---

## R-07 — Release/distribution metadata drift · CLOSED

The published numbers were derived by hand in nine places and had drifted: the iOS build number
appeared as three different values in three places (two on the same page), the README's release tag
was thirty minor versions stale, and the site's own download cards carried two different tags.

`scripts/generate-release-manifest.py` derives them from `build.gradle` and `project.pbxproj` into
`web/release-manifest.json`; `scripts/check-release-metadata.py` checks all 21 published claims
against it, and the manifest against the build files, so neither can drift alone. A pattern that
stops matching is a **failure**, not a skip — the check cannot pass vacuously.

Two values are hand-maintained on purpose, in one place instead of nine, each with the reason
recorded next to it:

- `desktop.releaseTag` = `v0.2.12`. This is **not** the Android `versionName` (`0.2.38`). There is no
  `v0.2.38` tag, and aligning them would break every desktop download URL. The two look alike and
  that is exactly how the drift started.
- `site.minAndroidReleaseClaimed` = `8` while `minSdkVersion` is 21 (Android 5) — deliberate: the app
  has never been QA'd below 8, and the database-key path was crashing outright on 5.x until the fix
  below. Raising `minSdkVersion` would remove the field, but that is a product decision about which
  users to drop, so it is recorded rather than silently taken.

---

## R-01 — Shared push-auth secret · OPEN (architectural)

Not implemented. The remediation is the per-install capability model in
`DESIGN-push-per-install-capabilities.md`, which is a multi-release project across the relay and both
mobile clients, and §5 of that document requires it to follow the signed-history rollout rather than
run alongside it.

What was done is the part that could be settled without shipping behaviour — because both items were
assumptions a two-store rollout was going to be bet on, and an assumption that fails at step 3 is a
flag day.

- **Step 1 of the rollout is already true, and is now pinned.** `/wake` reads `token` and `sender`
  and ignores the rest of the body, so a client sending `cap` today is served exactly as one that
  does not. The risk was never that it did not work — it was that nothing recorded the dependency,
  and "reject unknown fields" is a plausible hardening for someone to add later, which would break
  the rollout silently and only after clients had begun emitting `cap`. Pinned along with the
  inertness (6 hostile values, including non-strings and 8 KiB of junk) and with the failure that
  would hurt more: **`cap` must stay out of the HMAC pre-image**, or every shipped client stops
  verifying at once.
- **The "fortunate accident" §3 depends on is real.** Verified on a device against the actual
  `android.net.Uri` rather than by re-reading the validator: `PushUrlValidator` accepts the wake URL
  with `cap=` in either parameter order and on the publish side too, and a capability rescues neither
  a disallowed host nor a missing `id`. (7 tests.) `KhandaqPush.swift` matches by inspection — it
  looks up the `id` query item and ignores the rest — but **no iOS device ran here**, so that half is
  read, not proven.
- **A constraint the design did not state,** found by testing it: the host-confusion defence rejects
  any URL containing `@`, a backslash or whitespace *anywhere*, not only in the authority. Base64url
  capability values are safe; an encoding that can emit `@` would produce URLs every shipped client
  refuses.
- **A defect in the design itself.** Step 2 authenticates `POST /register` with the existing HMAC —
  which is the fleet secret this design exists to remove, so every installation holds it and anyone
  can register a capability against anyone else's token. Harmless while nothing reads the table; at
  step 4, when the relay begins *requiring* a capability for tokens that have one, a forged
  registration silently kills that device's notifications. That inverts the design's own requirement
  5, "fail toward delivery" — the one failure a user cannot see. The document now says so, and
  `test_there_is_no_registration_endpoint_yet` fails if `/register` appears before it is resolved.

---

## R-04 — Desktop TLS stack on EOL Qt/OpenSSL · OPEN (migration outstanding)

The Qt 6 / OpenSSL 3 migration is not done. It is scoped in
`DESIGN-qt6-openssl3-migration-scope.md`, and its retest — build all desktop targets, verify runtime
TLS, calls, media and database compatibility, regenerate SBOM and provenance — needs a Windows build
environment that does not exist on this machine.

The re-audit's remediation has a second half: *"block release when a waiver expires"*. **That half is
now closed, and it was not merely missing — it was written but never wired.** `--release` mode (no
grace period; a waiver with under 14 days left may not carry a release) had existed since the first
round and nothing invoked it. The only invocation anywhere was the advisory `--warn-days 180` form on
pull requests, where failing does not stop a release from being cut. So shipping a desktop build on
an expired waiver was reachable — and would have become reachable in practice on **2026-11-19**, when
both current waivers lapse.

Both release gates now run in `release-provenance.yml`, *before* the build: a release that must not
go out should cost seconds, not a full NDK build. Checked in both directions — as master stands both
pass (two waived risks, each with owner and expiry; 21 published claims matching); with qt's waiver
backdated the gate fails with *"the waiver EXPIRED 20 day(s) ago"* and exit 1.

> **Correction, round-3 audit (F-23).** The paragraph above was accurate about the Android release
> workflow and, as first written, implied more coverage than existed. `release-provenance.yml` builds
> the *Android* artifact. The waivers are about **OpenSSL 1.1.1w and Qt 5.12.12**, which only the
> **desktop** builds bundle — and none of `build-linux.sh`, `build-macos.sh`,
> `build-windows-cross.sh`, `build-linux-deb.sh` or `bundle-linux-portable.sh` consulted the gate at
> all. So a desktop artifact could still be produced on an expired waiver: the gate was on a path,
> just not the one the finding was about. All five desktop scripts now run
> `check-bundled-deps-eol.py --release` before doing any work, escapable only by setting
> `KHANDAQ_SKIP_DEP_GATE=1` on purpose.

Two waivers remain, both expiring 2026-11-19 and both owned: **OpenSSL 1.1.1w** and **Qt 5.12.12**.
The third — libexpat — is gone, bumped rather than renewed (R-03).

---

## Found while closing R-06 — Android 5.x crash · FIXED

Not in the re-audit. `DbSecretKeyStorage` called `KeyStore.getInstance("AndroidKeyStore")` for AES
keys, which requires API 23; `minSdkVersion` is 21. On Android 5.0/5.1 the database-key path threw
and the failure escaped both callers, which caught `Exception` while the real throw was an `Error`.
The guard is now explicit — a clear `UnsupportedOperationException` naming the API level — and both
callers catch `Throwable`.

This is also why `site.minAndroidReleaseClaimed` stays at 8 rather than being lowered to match
`minSdkVersion`: the app is *built* for Android 5 and has never been QA'd there.

---

## How to re-verify

```bash
# the six security checks, offline except the OSV one
python3 scripts/check-push-ca-anchors.py
python3 scripts/check-python-deps.py
python3 scripts/check-ios-bundler.py
python3 scripts/check-bundled-deps-eol.py --warn-days 180
python3 scripts/check-release-metadata.py
python3 scripts/check-ios-downgrade-policy.py

# the release gates, as release-provenance.yml runs them
python3 scripts/check-bundled-deps-eol.py --release
python3 scripts/check-release-metadata.py

# relay
cd infra/push/relay && python3 -m pytest test_app.py -q          # 66 tests

# android
cd khandaq-android-trifa/android-refimpl-app
./gradlew :app:testDebugUnitTest                                  # JVM
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.zoffcc.applications.trifa.PlaintextExportGateDeviceTest
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.zoffcc.applications.trifa.PushUrlCapabilityCompatTest
./gradlew :app:lintVitalRelease                                   # the R-05 gate
```
