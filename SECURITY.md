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

- FCM/APNs device token (URL query `id=`)
- Optional sender **public key** (64 hex chars, `&from=`) — not a full Tox ID — so the mobile app can route the notification to the correct chat

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

Currently waived, all expiring 2026-11-19: OpenSSL 1.1.1w and Qt 5.12.12 (blocked on the Qt 6
migration) and libexpat 2.4.1 (below the CVE-2024-8176 floor of 2.7.0 — nothing structural blocks
that bump; it is waived only because no desktop build could be produced and smoke-tested when the
finding was raised, and it is the cheapest of the three to clear).

## Recommended verification

- Compare release `SHA256SUMS.txt` with downloaded binaries
- Build clients from source and compare behaviour
- Review `config/khandaq_push.json` and bootstrap registry for network transparency
