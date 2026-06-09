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
- Documented public endpoints (`bootstrap.khandaq.org`, `push.khandaq.org`)
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

## Recommended verification

- Compare release `SHA256SUMS.txt` with downloaded binaries
- Build clients from source and compare behaviour
- Review `config/khandaq_push.json` and bootstrap registry for network transparency
