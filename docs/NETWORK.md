# Khandaq Network Architecture

Khandaq clients connect to the **public Tox DHT**. The apps talk to two Khandaq-operated servers,
both documented and auditable in this repository: a wake-only push relay, and, from the desktop app
only, the website khandaq.org, which tells it of a new release and serves the download when the
person asks for it (see [Desktop update check](#desktop-update-check)).

## Components

```
┌─────────────┐     Tox P2P (E2E)      ┌─────────────┐
│  Client A   │◄──────────────────────►│  Client B   │
└──────┬──────┘                        └──────┬──────┘
       │                                        │
       │ bootstrap / DHT                        │
       ▼                                        ▼
┌──────────────────────────────────────────────────────┐
│  Public Tox DHT bootstrap nodes (nodes.tox.chat)      │
└──────────────────────────────────────────────────────┘

       │ wake only (no message body)
       ▼
┌──────────────────────┐
│ push.khandaq.org     │  ──► FCM / APNs ──► offline device
└──────────────────────┘
```

## Bootstrap

Both clients bootstrap from the **proven public Tox DHT** — the same nodes the wider Tox
network uses (e.g. `tox.abilinski.com`, `tox1.mf-net.eu`, `tox2.mf-net.eu`, `tox.initramfs.io`,
sourced from `nodes.tox.chat`). The self-hosted `bootstrap{1,2,3}.khandaq.org` nodes that earlier
builds added have been **retired**: relying on the public DHT means joining no longer depends on
Khandaq's own infrastructure being reachable.

## Public endpoints

| Endpoint | Config file | Client usage |
|----------|-------------|--------------|
| `https://push.khandaq.org/toxfcm/fcm.php` | `config/khandaq_push.json` | Push wake relay (FCM/APNs) |
| `https://khandaq.org` | `web/` | Website & download links |
| `https://khandaq.org/downloads/khandaq-desktop-update.json` (and `.sig`) | `khandaq-desktop/src/net/updatemanifest.cpp` | Desktop update check: the signed manifest of the latest release, and the downloads it names |

## What Khandaq servers see

The push relay and khandaq.org are the Khandaq-operated servers the apps talk to; the desktop app
talks to khandaq.org only for its update check. Bootstrap is the public Tox DHT, operated by
the community — Khandaq runs none of those nodes.

| Data | Push relay | khandaq.org (desktop update check) |
|------|------------|------------------------------------|
| Tox public keys | Optional sender key only (`&from=`, 64 hex) | No |
| IP addresses | Yes (HTTP access logs) | Yes, as for any visit to the site (the proxy's, if one is set in the app) |
| Message content | **No** | **No** |
| FCM/APNs device token | Yes (query parameter `id=`) | No |

## Desktop update check

From the first desktop release built with `UPDATE_CHECK=ON` (the release after 0.2.65). Android
and iOS are updated by their stores. A release build on Windows, macOS or Linux (deb or portable
folder) asks khandaq.org whether a newer release is out:

- **When:** 1 to 3 minutes after it starts, at random, then whenever a day has passed since the last
  check that went through (it looks hourly). Check now in Settings, About asks at once.
- **What:** `GET https://khandaq.org/downloads/khandaq-desktop-update.json` and its `.sig`, at most
  64 KiB and 4 KiB, 30 s each. `User-Agent: Khandaq` without a version, `Accept-Language: *`,
  `Accept-Encoding: identity`; no cookies, no identifiers, no redirect followed. Through the proxy
  set in the profile; a proxy without an address or port means no request at all.
- **Trust:** the manifest counts only once the release key's signature (SSHSIG, namespace
  `khandaq-desktop-update`) verifies with the key compiled into the app; see
  [DESKTOP-SIGNING.md](DESKTOP-SIGNING.md#5-the-desktop-update-manifest).
- **Download:** only when the person presses Update: the one file the manifest names for that
  platform, from the same folder, refused unless its size and SHA-256 are the signed ones.
- **Off:** the Check for updates switch in Settings, About. Development and nightly builds never
  check; Flatpak, Snap and AppImage copies are left to what installed them.

## Auditing

1. Inspect `config/khandaq_push.json`.
2. Search client code for the hardcoded push URL (`push.khandaq.org`) — there is no Khandaq
   bootstrap URL anymore; the bootstrap node lists are the public Tox DHT.
3. Build from source and verify the same endpoint is used.
4. Search the desktop code for `khandaq-desktop-update.json` (`src/net/updatemanifest.cpp`): the
   update check's one address. Downloads are resolved beside it.

Raw VPS IPs and internal ops logs are **not** published in this repository.
