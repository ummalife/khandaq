# Khandaq Messenger

Open-source, decentralized messenger built on the [Tox](https://tox.chat/) protocol. Khandaq adds branded clients, optional Khandaq-owned bootstrap nodes, and a privacy-preserving push wake relay — without replacing the public Tox network.

**Repository:** https://github.com/ummalife/khandaq  
**Website:** https://khandaq.org  
**Downloads:** [Latest release](https://github.com/ummalife/khandaq/releases/latest)

---

## What is in this repository

| Path | Description |
|------|-------------|
| [`khandaq-desktop/`](khandaq-desktop/) | Desktop client (Qt/C++), forked from [qTox](https://github.com/TokTok/qTox) |
| [`khandaq-ios/`](khandaq-ios/) | iOS client (Swift), forked from [Antidote](https://github.com/Antidote-for-Tox/Antidote) |
| [`khandaq-android-trifa/`](khandaq-android-trifa/) | Android client (Java/Kotlin), forked from [TRIfA](https://github.com/zoff99/ToxAndroidRefImpl) |
| [`khandaq-android/`](khandaq-android/) | Legacy Android reference (aTox-based) |
| [`config/`](config/) | Public network config (bootstrap registry, push relay URLs) |
| [`infra/`](infra/) | Bootstrap node & push relay deployment templates (Docker, nginx) |
| [`scripts/`](scripts/) | Build, bundle, and deploy automation |
| [`web/messenger/`](web/messenger/) | Static download landing page |
| [`docs/`](docs/) | Public documentation (English) |

Message content is **end-to-end encrypted** by Tox. Khandaq infrastructure only provides bootstrap discovery and opaque push wake notifications (no message body on the relay).

---

## Download pre-built clients

All binaries are hosted on **GitHub Releases** (not in git):

| Platform | Asset |
|----------|--------|
| Android | `khandaq-android.apk` |
| Windows | `khandaq-windows-installer.exe` |
| macOS | `khandaq-macos.zip` |
| Linux x86_64 | `khandaq-linux-x86_64-portable.tar.gz` |
| iOS | [TestFlight](https://testflight.apple.com/join/4ppS8ZN5) (not distributed as IPA here) |

Verify downloads with `SHA256SUMS.txt` attached to each release.

---

## Public network endpoints (auditable)

These URLs are embedded in client builds and configuration files:

| Service | URL | Purpose |
|---------|-----|---------|
| Bootstrap registry | https://bootstrap.khandaq.org/nodes.json | Khandaq + public Tox bootstrap list |
| Push wake relay | https://push.khandaq.org/toxfcm/fcm.php | FCM/APNs wake only (no message payload) |
| Main site | https://khandaq.org | Project website |

Source of truth: [`config/khandaq_bootstrap_nodes.json`](config/khandaq_bootstrap_nodes.json), [`config/khandaq_push.json`](config/khandaq_push.json).

See [docs/NETWORK.md](docs/NETWORK.md) and [docs/BOOTSTRAP.md](docs/BOOTSTRAP.md).

---

## Build from source

See [docs/BUILDING.md](docs/BUILDING.md) for platform-specific instructions.

Quick overview:

```bash
# Desktop (Linux host or Docker)
./scripts/build-linux-docker.sh

# Windows (cross-compile from macOS/Linux)
./scripts/build-windows-cross.sh

# macOS
./scripts/build-macos.sh

# Android
./scripts/build-android-trifa.sh

# iOS (macOS + Xcode)
cd khandaq-ios && pod install
# Open Antidote.xcworkspace in Xcode
```

---

## Security & transparency

- **No message content** is stored on Khandaq servers.
- Push relay accepts only device tokens; see [docs/PUSH_RELAY.md](docs/PUSH_RELAY.md).
- Report vulnerabilities: [SECURITY.md](SECURITY.md).
- We welcome code review and reproducible builds.

---

## Upstream projects

Khandaq is a fork/rebrand. Core protocol and libraries:

- [TokTok/c-toxcore](https://github.com/TokTok/c-toxcore) — Tox protocol implementation
- [TokTok/qTox](https://github.com/TokTok/qTox) — desktop base
- [Antidote-for-Tox/Antidote](https://github.com/Antidote-for-Tox/Antidote) — iOS base
- [zoff99/ToxAndroidRefImpl](https://github.com/zoff99/ToxAndroidRefImpl) — Android (TRIfA) base

Khandaq maintains **Tox wire compatibility** — clients can talk to standard Tox peers.

---

## License

This repository contains multiple upstream codebases with different licenses:

| Component | License |
|-----------|---------|
| `khandaq-desktop/` | GNU GPL v3+ |
| `khandaq-android-trifa/` | GNU GPL v3+ |
| `khandaq-ios/` (Antidote fork) | Mozilla Public License 2.0 |

See `LICENSE` and per-directory license files for details.

---

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).
