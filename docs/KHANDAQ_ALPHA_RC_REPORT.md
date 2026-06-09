# Khandaq Alpha Release Candidate Report

**Версия:** 0.1.0-alpha RC1  
**Дата:** 2026-06-08  
**Scope:** Phases A1–A6 (стабилизация, без нового функционала)

---

## Executive Summary

| Платформа | Готовность Alpha | Вердикт |
|-----------|------------------|---------|
| **Desktop** | 75% | RC — можно ограниченный internal alpha |
| **Android (TRIfA)** | 40% | Не публичный alpha (upstream as-is) |
| **iOS (Antidote)** | 35% | Simulator only; не публичный alpha |
| **Network** | 70% | Bootstrap обновлены; Khandaq nodes — planned |

---

## A1 — Network Stabilization ✅

- Аудит: `docs/BOOTSTRAP_AUDIT.md`
- Источник: nodes.tox.chat (49 nodes → **21 UDP-online** curated)
- Обновлены: `khandaq-desktop/res/nodes.json`, iOS `nodes.json`, aTox `nodes.json`
- Registry: `config/khandaq_bootstrap_nodes.json`
- Скрипт: `scripts/audit-bootstrap-nodes.sh`

**Риск:** TRIfA hardcoded bootstrap в Java не обновлён (отдельная задача post-A1).

---

## A2 — Bootstrap Infrastructure ✅ (docs)

- `infra/bootstrap/`: install.sh, docker-compose, systemd, FIREWALL.md, MONITORING.md
- 3 planned Khandaq nodes в config (ключи TBD до деплоя)

**Блокер публичного alpha:** нет live Khandaq-owned bootstrap nodes.

---

## A3 — Mobile Consolidation ✅

- `docs/MOBILE_STRATEGY.md`
- **Android base:** TRIfA
- **iOS base:** Antidote (Zoxcore)
- aTox — исключён

---

## A4 — Alpha Release Readiness (Desktop rebrand strings) ✅

Исправлены пользовательские упоминания qTox → Khandaq:

| Файл | Изменение |
|------|-----------|
| `src/persistence/settings.cpp` | tr() error messages |
| `src/widget/tool/messageboxmanager.cpp` | executable warning |
| `src/widget/form/chatform.cpp` | file/screenshot errors |
| `src/persistence/profile.cpp` | default status, chat logs errors |
| `src/persistence/db/upgrades/dbupgrader.cpp` | DB version message |
| `src/platform/desktop_notifications/desktopnotify.cpp` | Snore app name |
| `src/net/updatecheck.cpp` | log strings |
| `src/appmanager.cpp` | single-instance message |
| `windows/qtox.nsi`, `qtox64.nsi` | installer function/temp paths |
| `translations/*.ts` (54 files) | qTox → Khandaq in translations |

**Сохранено намеренно:** GPL блок в `aboutsettings.ui` (upstream attribution).

---

## A5 — License Compliance ✅

| Артефакт | Путь |
|----------|------|
| NOTICE | `/NOTICE` |
| THIRD_PARTY_LICENSES | `/THIRD_PARTY_LICENSES.md` |
| Release compliance guide | `docs/RELEASE_COMPLIANCE.md` |
| Source package script | `scripts/package-release-source.sh` |

---

## Риски

| # | Риск | Severity | Mitigation |
|---|------|----------|------------|
| 1 | GPL source не опубликован с бинарниками | High | Run package-release-source.sh at release |
| 2 | Mobile MPL + Desktop GPL в одном бренде | High | Separate products until legal review |
| 3 | TRIfA debug-signed APK | Medium | Release keystore before public alpha |
| 4 | iOS unsigned IPA / Xcode 26 patches | Medium | TestFlight pipeline |
| 5 | nodes.tox.chat dependency | Medium | Own bootstrap (A2) + weekly audit |
| 6 | Qt5 EOL | Low (post-alpha) | Qt6 migration roadmap |

---

## Блокеры первого публичного Alpha

1. **Не деплоены** Khandaq bootstrap nodes (3× VPS)
2. **Mobile не ребрендированы** — нельзя выдавать за Khandaq
3. **Нет подписанных** mobile release builds
4. **Нет опубликованного** source tarball на релизе desktop
5. **TRIfA bootstrap DB** не синхронизирован с curated list
6. **Runtime QA** на устройствах не формализован (manual only)

---

## Что нужно для публичного Alpha (минимум)

### Desktop (можно раньше mobile)

- [ ] Tag `v0.1.0-alpha` + source tarball
- [ ] GitHub Release: macOS, Linux aarch64, Windows x86_64
- [ ] README с install + source link
- [ ] Удалить legacy `io.github.qtox.qTox.*` из release bundle

### Android

- [ ] Rebrand TRIfA → Khandaq
- [ ] Release signing
- [ ] Sync BootstrapNodeEntryDB
- [ ] F-Droid / APK metadata

### iOS

- [ ] Apple Developer signing
- [ ] Зафиксировать build patches в repo
- [ ] TestFlight beta

### Network

- [ ] Deploy 3× `infra/bootstrap`
- [ ] Update `khandaq_owned_nodes` with real keys

---

## Готовность по компонентам

```
Desktop   ████████░░  75%  — RC internal
Android   ████░░░░░░  40%  — dev builds only
iOS       ███░░░░░░░  35%  — simulator only
Network   ███████░░░  70%  — public nodes OK, own nodes pending
Legal     ███████░░░  70%  — docs ready, publish at release
```

---

## Вердикт CTO

**Internal Alpha (desktop-only): GO** — при публикации source + NOTICE.  
**Public Alpha (full platform): NO-GO** — до mobile rebrand, signing, own bootstrap, QA matrix.

Следующий milestone: **v0.1.0-alpha.1** = desktop public + network infra live.
