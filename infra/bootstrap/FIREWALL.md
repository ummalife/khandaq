# Khandaq Bootstrap — Firewall

## Минимально необходимые порты

| Протокол | Порт | Назначение |
|----------|------|------------|
| UDP | 33445 | Tox DHT bootstrap (обязательно) |
| TCP | 33445 | TCP relay (рекомендуется) |
| TCP | 22 | SSH admin (ограничить по IP) |

## UFW (Ubuntu)

```bash
ufw default deny incoming
ufw default allow outgoing
ufw allow 22/tcp
ufw allow 33445/udp
ufw allow 33445/tcp
ufw enable
ufw status verbose
```

## iptables (generic)

```bash
iptables -A INPUT -p udp --dport 33445 -j ACCEPT
iptables -A INPUT -p tcp --dport 33445 -j ACCEPT
```

## Проверка снаружи

```bash
nc -vz YOUR_IP 33445          # TCP
# UDP: см. public bootstrap registry или scripts/audit-bootstrap-nodes.sh
```

## Не открывать

- Панели управления на публичных портах
- Tox TCP relay на нестандартных портах без необходимости
