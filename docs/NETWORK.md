# Khandaq Network Architecture

Khandaq clients connect to the **public Tox DHT**. The only Khandaq-operated piece of
infrastructure is a wake-only push relay; it is fully documented and auditable in this repository.

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

## What Khandaq servers see

The push relay is the only Khandaq-operated server. Bootstrap is the public Tox DHT, operated by
the community — Khandaq runs none of those nodes.

| Data | Push relay |
|------|------------|
| Tox public keys | Optional sender key only (`&from=`, 64 hex) |
| IP addresses | Yes (HTTP access logs) |
| Message content | **No** |
| FCM/APNs device token | Yes (query parameter `id=`) |

## Auditing

1. Inspect `config/khandaq_push.json`.
2. Search client code for the hardcoded push URL (`push.khandaq.org`) — there is no Khandaq
   bootstrap URL anymore; the bootstrap node lists are the public Tox DHT.
3. Build from source and verify the same endpoint is used.

Raw VPS IPs and internal ops logs are **not** published in this repository.
