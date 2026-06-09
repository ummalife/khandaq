# Bootstrap Registry

Khandaq publishes a machine-readable list of Tox bootstrap nodes.

**Live URL:** https://bootstrap.khandaq.org/nodes.json

## Source of truth

```
config/khandaq_bootstrap_nodes.json
        │
        ▼
scripts/generate-bootstrap-registry.py
        │
        ▼
dist/bootstrap/nodes.json   (generated, not in git)
        │
        ▼
scripts/deploy-bootstrap-registry.sh   (maintainers)
```

## Khandaq-owned nodes

| ID | Host | Region | Tox public key (prefix) |
|----|------|--------|-------------------------|
| `khandaq-bootstrap-eu-1` | bootstrap1.khandaq.org | NL | `74AE9E62…` |
| `khandaq-bootstrap-us-1` | bootstrap2.khandaq.org | US | `5C6F3903…` |
| `khandaq-bootstrap-de-1` | bootstrap3.khandaq.org | DE | `A181DD1F…` |

Full keys are in `config/khandaq_bootstrap_nodes.json`. These are **public** — Tox clients need them to bootstrap into the DHT.

## Public nodes

The registry also includes curated entries from the public Tox network (`nodes.tox.chat`). Khandaq does not operate those nodes.

## Client integration

Bundled node lists are synced via:

```bash
python3 scripts/sync-all-bootstrap-nodes.py
```

Desktop, iOS, and Android builds embed the result in their respective `nodes.json` or equivalent resources.

## Health check (local)

```bash
./scripts/check-bootstrap-health.sh
```

## Infrastructure templates

Docker and nginx configs for bootstrap hosting: `infra/bootstrap/` (see English README there).
