# Khandaq Bootstrap — Monitoring

## Health checks

### 1. systemd

```bash
systemctl is-active khandaq-bootstrap
journalctl -u khandaq-bootstrap -f
```

### 2. TCP port

```bash
nc -vz localhost 33445
```

### 3. Public bootstrap registry (внешний)

После деплоя нода должна появиться в публичном реестре bootstrap-нод в течение 24–48 ч (зависит от сканера). URL — `public_registry` в `config/khandaq_bootstrap_nodes.json`.

Проверка:

```bash
curl -fsSL "$(jq -r .sources.public_registry config/khandaq_bootstrap_nodes.json)" | jq '.nodes[] | select(.public_key=="YOUR_KEY")'
```

### 4. Локальный аудит

```bash
./scripts/audit-bootstrap-nodes.sh
```

## Метрики (минимум Alpha)

| Метрика | Порог | Действие |
|---------|-------|----------|
| systemd active | != active | restart + alert |
| TCP 33445 | closed | check firewall / process |
| В public registry | offline > 7d | investigate VPS |
| Disk /var/lib | > 80% | rotate logs |

## Алерты (пример cron)

```cron
*/15 * * * * root systemctl is-active khandaq-bootstrap || systemctl restart khandaq-bootstrap
0 */6 * * * root /opt/khandaq/scripts/audit-bootstrap-nodes.sh >> /var/log/khandaq-bootstrap-audit.log 2>&1
```

## Docker

```bash
docker compose -f infra/bootstrap/docker-compose.yml ps
docker compose -f infra/bootstrap/docker-compose.yml logs --tail=50
```
