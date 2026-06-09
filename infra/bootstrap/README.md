# Khandaq Bootstrap Infrastructure

Minimal infrastructure for **Khandaq-owned** Tox bootstrap nodes.

**Important:** The public Tox network is not replaced. Khandaq nodes are **added** on top of existing bootstrap entries from `nodes.tox.chat` (see `config/khandaq_bootstrap_nodes.json`).

## Target topology

| ID | Region | Hostname |
|----|--------|----------|
| `khandaq-bootstrap-eu-1` | EU | `bootstrap1.khandaq.org` |
| `khandaq-bootstrap-us-1` | US | `bootstrap2.khandaq.org` |
| `khandaq-bootstrap-de-1` | DE | `bootstrap3.khandaq.org` |

## Production deploy (Docker Compose)

On each VPS (SSH key auth recommended):

```bash
# Copy infra/bootstrap/ to the server, then:
cd /opt/khandaq-bootstrap
docker compose up -d
```

Files in this directory:

| File | Purpose |
|------|---------|
| `Dockerfile` | tox-bootstrapd image from c-toxcore |
| `docker-compose.yml` | Container orchestration |
| `bootstrap-daemon.conf` | Daemon configuration |
| `deploy-node.sh` | First-time host setup (UFW, directories) |
| `nginx-bootstrap.conf` | HTTPS registry front-end template |

## Registry publication

After nodes are live, maintainers run from a dev machine:

```bash
./scripts/deploy-bootstrap-registry.sh
```

Publishes `https://bootstrap.khandaq.org/nodes.json`.

## Ports

- **33445/UDP** — Tox bootstrap (required)
- **33445/TCP** — Tox TCP relay (optional but recommended)
- **443/TCP** — HTTPS registry (nginx on main VPS)

## Security

- Use SSH keys, not passwords, for server access.
- Do not commit VPS credentials or internal IP audit reports to the public repository.
