# Khandaq Network Architecture

Khandaq clients connect to the **public Tox DHT**. Khandaq adds infrastructure that is fully documented and auditable in this repository.

## Components

```
┌─────────────┐     Tox P2P (E2E)      ┌─────────────┐
│  Client A   │◄──────────────────────►│  Client B   │
└──────┬──────┘                        └──────┬──────┘
       │                                        │
       │ bootstrap / DHT                        │
       ▼                                        ▼
┌──────────────────────────────────────────────────────┐
│  Public Tox bootstrap nodes (community registry)      │
│  + Khandaq-owned nodes (bootstrap1/2/3.khandaq.org)  │
└──────────────────────────────────────────────────────┘

       │ wake only (no message body)
       ▼
┌──────────────────────┐
│ push.khandaq.org     │  ──► FCM / APNs ──► offline device
└──────────────────────┘
```

## Public endpoints

| Endpoint | Config file | Client usage |
|----------|-------------|--------------|
| `https://bootstrap.khandaq.org/nodes.json` | `config/khandaq_bootstrap_nodes.json` | Bootstrap node list (additive) |
| `https://push.khandaq.org/toxfcm/fcm.php` | `config/khandaq_push.json` | Push wake relay |
| `https://khandaq.org` | `web/messenger/` | Website & download links |

## What Khandaq servers see

| Data | Bootstrap nodes | Push relay |
|------|-----------------|------------|
| Tox public keys | Yes (required for DHT) | Optional sender key only (`&from=`, 64 hex) |
| IP addresses | Yes (network traffic) | Yes (HTTP access logs) |
| Message content | **No** | **No** |
| FCM/APNs device token | No | Yes (query parameter `id=`) |

## Khandaq-owned bootstrap nodes

Hostnames (public keys in `config/khandaq_bootstrap_nodes.json`):

- `bootstrap1.khandaq.org` (EU)
- `bootstrap2.khandaq.org` (US)
- `bootstrap3.khandaq.org` (DE)

Policy: **additive** — Khandaq nodes are added alongside public community bootstrap entries, not replacing the global Tox network.

## Auditing

1. Inspect `config/khandaq_bootstrap_nodes.json` and `config/khandaq_push.json`.
2. Search client code for hardcoded URLs (`push.khandaq.org`, `bootstrap.khandaq.org`).
3. Compare live registry: `curl -s https://bootstrap.khandaq.org/nodes.json | jq .`
4. Build from source and verify the same endpoints are used.

Raw VPS IPs and internal ops logs are **not** published in this repository.
