# Security Policy

## Supported versions

| Version | Supported |
|---------|-----------|
| Public beta (current) | Yes |
| Earlier alpha builds | No |

## Reporting a vulnerability

Please report security issues **privately** before public disclosure:

1. Open a [GitHub Security Advisory](https://github.com/ummalife/khandaq/security/advisories/new) on this repository, **or**
2. Contact the Khandaq team via https://khandaq.org/

Include:

- Affected platform (desktop / Android / iOS)
- Steps to reproduce
- Impact assessment (confidentiality, integrity, availability)
- Proof-of-concept if available

We aim to acknowledge reports within **72 hours**.

## Scope

**In scope:**

- Khandaq client source in this repository
- Documented public endpoints (`push.khandaq.org` wake relay; clients bootstrap from the public Tox DHT — the self-hosted `bootstrap*.khandaq.org` nodes have been retired)
- Build and release integrity (checksums, supply chain)

**Out of scope:**

- Vulnerabilities in upstream Tox protocol design (report to the c-toxcore maintainers)
- Social engineering, physical device access
- Denial-of-service against public Tox bootstrap nodes not operated by Khandaq

## What we do not have access to

- Your Tox message plaintext (E2E encrypted)
- Your Tox private keys (stored locally, encrypted with your password)
- Message content on `push.khandaq.org` (wake notifications only)

## Push relay transparency

`push.khandaq.org` is a **wake-only** relay. It does not receive message bodies.

It may receive:

- FCM/APNs device token. Clients from Android 0.2.42 / iOS build 142986 send it in the body
  of `POST /wake`, with the signature in a header; older installations still use the legacy
  `GET/POST /toxfcm/fcm.php?id=…` form, where it is a URL query parameter and therefore
  reaches the web-server access log. Retirement schedule:
  [docs/PUSH-COMPATIBILITY.md](docs/PUSH-COMPATIBILITY.md).
- Optional sender **public key** (64 hex chars) — not a full Tox ID — so the mobile app can
  route the notification to the correct chat. Same two carriers as the token.
- A per-contact capability, when the sending device has registered one. The shared build-time
  HMAC is replay and rate-abuse hardening, **not** client identity: it is a fleet-wide key
  embedded in public binaries, so possession of it proves nothing about who is calling.

It does **not** receive receiver Tox ID or message plaintext. See `config/khandaq_push.json` and [docs/PUSH_RELAY.md](docs/PUSH_RELAY.md).

## Firebase client keys (Android)

`google-services.json` contains a Firebase **client** API key. This is expected for Android builds (the same key is embedded in the APK). Restrict the key in [Firebase Console](https://console.firebase.google.com/) → Project settings → API restrictions (Android app + Firebase APIs only). iOS `GoogleService-Info.plist` is **not** committed (see `khandaq-ios/.gitignore`).

## Bundled cryptographic runtimes (desktop)

The desktop client bundles its own TLS/crypto stack rather than using the system one. Today that is
**Qt 5.12.12** and **OpenSSL 1.1.1w**. OpenSSL 1.1.1 has received no public security fixes since
**11 September 2023**, and Qt 5.12's extended LTS ended **5 December 2021**. This is lifecycle risk,
not a known remote exploit in Khandaq — and it is not closable by a version bump, because Qt 5.12
cannot link against OpenSSL 3. The exit is the migration scoped in
`DESIGN-qt6-openssl3-migration-scope.md`.

**Policy.** No new desktop release ships a cryptographic or TLS runtime past its published
end-of-life date, or below a recorded security floor, unless that specific component carries a waiver
in `khandaq-desktop/buildscripts/bundled-deps.json` containing:

- a **reason** — why it cannot simply be bumped;
- a named **owner**;
- a **tracking** document;
- an **expiry**, at most 90 days out.

`scripts/check-bundled-deps-eol.py --release` enforces it, and it runs as the **first** step of the
Windows build — before the ~350-minute toolchain build, not after. A waiver lets a release proceed
with an accepted, dated, owned risk; an expired or incomplete one fails the build until it is
re-argued. The same script also verifies the inventory against the actual `download_*.sh` pins, so
the version list cannot drift from the build the way the prose comment in `windows-build.yaml` had,
and emits the CycloneDX SBOM that ships with the release artifacts.

Currently waived, both expiring 2026-11-19: **OpenSSL 1.1.1w** and **Qt 5.12.12**, blocked on the
Qt 6 migration.

The third waiver is gone. **libexpat** was bumped 2.4.1 → 2.8.3 on 2026-08-21 in response to the
re-audit, clearing the CVE-2024-8176 floor unwaived. Nothing structural had blocked it: expat is its
own Docker layer built immediately before gdb and consumed by nothing else, so it serves the bundled
debugger's XML target descriptions and the messenger never parses untrusted XML with it. The bump was
checked before it was made — the tarball digest was computed from the downloaded bytes and agrees
with the digest GitHub publishes, and 2.8.3 configures and builds with this repository's exact flags
for `x86_64-w64-mingw32`, producing a static `libexpat.a` that still exports every symbol gdb 11.1
needs. A full Windows container build still has to confirm it, because there is no Docker on the
machine this was done on.

## External audits

Both 2026-08-21 rounds are answered finding by finding, including what was measured and where the
measurement stops:

- `SECURITY-AUDIT-2026-08-21-RESPONSE.md` — first round, K-01…K-09, all nine closed.
- `SECURITY-REAUDIT-2026-08-21-RESPONSE.md` — re-audit, R-01…R-07: five closed; R-01 (per-install
  push capabilities) and R-04 (Qt 6 / OpenSSL 3 migration) remain open as architectural work, with
  the non-architectural half of each closed.

## Recommended verification

- Compare release `SHA256SUMS.txt` with downloaded binaries
- Build clients from source and compare behaviour
- Review `config/khandaq_push.json` and bootstrap registry for network transparency
