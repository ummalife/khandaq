# Khandaq — Bootstrap Nodes Audit

**Дата:** 2026-06-08 UTC  
**Источник live-скана:** https://nodes.tox.chat/json  
**nodes.tox.chat last_scan:** 1780882539 (2026-06-08 01:35 UTC)  
**Локальная TCP-верификация:** 2026-06-08 01:39 UTC  

---

## 1. Сводка

| Метрика | Значение |
|---------|----------|
| Всего в nodes.tox.chat | 49 |
| Online UDP (API) | 21 |
| Online TCP (API) | 20 |
| UDP+TCP (API) | 19 |
| Мёртвые (API) | 27 |
| Live TCP probe (локально) | 20/49 |

**До обновления:** desktop nodes.json — 34 ноды, last_scan **2020-11-22** (устарело на ~5 лет).  
**После обновления:** 21 UDP-online нод (curated), синхронизировано desktop / iOS / aTox.  

### TRIfA (отдельный формат)

- Встроенные bootstrap: `BootstrapNodeEntryDB.java` (hardcoded, ~40 UDP + TCP relay entries)  
- Обновление из сети: Maintenance → «update Bootstrapnodes from Internet» (nodes.tox.chat)  
- Файла `nodes.json` в assets нет; конвертер: `convert_nodes_file/`  
- **Риск:** дефолты в Java не синхронизированы с curated list — пользователи без internet-update получают старый набор  

---

## 2. Методология

1. Загрузка актуального JSON с nodes.tox.chat  
2. TCP connect probe (порты 33445 / tcp_ports) — timeout 4s  
3. UDP Tox ping — ограниченно (NAT); **статус UDP берём из nodes.tox.chat** как authoritative  
4. Отбор: `status_udp=true` для bootstrap; мёртвые (`both false`) исключены  

---

## 3. Актуальные bootstrap nodes (включены в релиз)

| Host | Loc | UDP | TCP | Maintainer | last_ping |
|------|-----|-----|-----|------------|-----------|
| 144.217.167.73 | CA | True | True | velusip | 1780882539 |
| tox.abilinski.com | CA | True | False | AnthonyBilinski | 1780882539 |
| 139.162.110.188 | CA | True | True | ToxTom | 1780882539 |
| 172.105.109.31 | CA | True | True | amr | 1780882541 |
| 43.198.227.166 | CN | True | True | Hardy | 1780882539 |
| tox1.mf-net.eu | DE | True | True | 2mf | 1780882539 |
| tox2.mf-net.eu | DE | True | True | 2mf | 1780882541 |
| tox4.mf-net.eu | DE | True | True | 2mf | 1780882541 |
| 188.245.84.166 | DE | True | True | Careplus | 1780882539 |
| 91.146.66.26 | EE | True | False | Toxdaemon | 1780882539 |
| 188.214.122.30 | EG | True | True | turambar | 1780882539 |
| 167.17.40.142 | FI | True | True | refan | 1780882539 |
| 86.107.187.54 | NL | True | True | Boca | 1780882539 |
| 95.181.230.108 | RU | True | True | wdwp | 1780882539 |
| 3.0.24.15 | SG | True | True | Hardy | 1780882541 |
| tox3.mf-net.eu | SG | True | True | 2mf | 1780882541 |
| 119.59.101.63 | TH | True | True | Felix | 1780882540 |
| tox.initramfs.io | TW | True | True | initramfs | 1780882539 |
| 205.185.115.131 | US | True | True | GDR! | 1780882539 |
| 172.104.215.182 | US | True | True | zero-one | 1780882539 |
| tox.hidemybits.com | US | True | True | john8675309 | 1780882539 |

---

## 4. Исключённые (мёртвые по API)

- `198.199.98.108` (Cody) — UDP/TCP offline, last_ping=1692090783
- `tox.kurnevsky.net` (kurnevsky) — UDP/TCP offline, last_ping=1775771254
- `45.32.184.23` (dandri) — UDP/TCP offline, last_ping=1780754799
- `tox2.abilinski.com` (AnthonyBilinski) — UDP/TCP offline, last_ping=1746336323
- `46.101.197.175` (kotelnik) — UDP/TCP offline, last_ping=1716531423
- `tox01.ky0uraku.xyz` (ky0uraku) — UDP/TCP offline, last_ping=1691512685
- `tox4.plastiras.org` (Tha_14) — UDP/TCP offline, last_ping=1759177875
- `188.225.9.167` (Nikat) — UDP/TCP offline, last_ping=1780465540
- `122.116.39.151` (miaoski) — UDP/TCP offline, last_ping=1681799519
- `173.232.195.131` (DEADBEEF) — UDP/TCP offline, last_ping=1692574743
- `2607:f130:0:f8::4c85:a645` (Busindre) — UDP/TCP offline, last_ping=1718916783
- `tox3.plastiras.org` (Tha_14) — UDP/TCP offline, last_ping=1758825194
- `104.225.141.59` (Gabe) — UDP/TCP offline, last_ping=1777671816
- `2001:678:888:100:cafe::2` (dandri) — UDP/TCP offline, last_ping=1765269340
- `198.98.49.206` (Cüber) — UDP/TCP offline, last_ping=1685639465
- ... ещё 12 нод

---

## 5. Изменённые файлы

| Файл | Было | Стало |
|------|------|-------|
| `khandaq-desktop/res/nodes.json` | 34 (2020) | 21 online UDP |
| `khandaq-ios/.../nodes.json` | 48 (2023) | 21 online UDP |
| `khandaq-android/atox/.../nodes.json` | 24 (2024) | 21 online UDP |
| `config/khandaq_bootstrap_nodes.json` | — | новый registry + 3 planned Khandaq nodes |

---

## 6. Рекомендации

1. Запустить 3 Khandaq-owned bootstrap (см. `infra/bootstrap/`) и добавить ключи в `config/khandaq_bootstrap_nodes.json`  
2. TRIfA: синхронизировать `BootstrapNodeEntryDB.java` с curated list (отдельный PR)  
3. CI: еженедельный `scripts/audit-bootstrap-nodes.sh` + alert при <10 UDP nodes  
4. Desktop уже тянет live list с nodes.tox.chat при старте (`bootstrapnodeupdater.cpp`) — embedded list = fallback  
