# Khandaq — Mobile Strategy (CTO)

**Дата:** 2026-06-08  
**Контекст:** Alpha Release, без rebrand mobile, без нового UI

---

## 1. Сравнение клиентов

| Критерий | TRIfA (Android) | aTox (Android) | Antidote (iOS) | qTox (Desktop) |
|----------|-----------------|----------------|----------------|----------------|
| Статус upstream | Активный форк (ZoffCC) | **Unmaintained** (2024+) | Форк Zoxcore, редкие коммиты | Unmaintained |
| Сборка в Khandaq | ✅ release APK | ❌ source на macOS | ⚠️ sim .app (патчи Xcode 26) | ✅ mac/linux/win |
| Лицензия | GPL-2/3 | GPL-3 | **MPL-2.0** | GPL-3 |
| Стек | Java/Kotlin + JNI toxcore | Kotlin + tox4j | Swift/ObjC + CocoaPods | Qt5/C++ |
| AV звонки | ✅ WebRTC audio + toxav | ✅ | ✅ | ✅ |
| Группы | ✅ | ✅ | ✅ | ✅ |
| Файлы / история | ✅ SQLCipher | ✅ Room | ✅ Realm | ✅ SQLCipher |
| Push | FCM + UnifiedPush | Нет native push | FCM + extensions | N/A |
| Bootstrap update | Runtime + hardcoded DB | nodes.json | nodes.json bundle | nodes.json + live |
| Техдолг | Высокий (монолит, JNI) | Средний (archived) | Высокий (Pods, Swift 4, libsodium/vpx) | Средний (Qt5 EOL) |

---

## 2. Функциональные отличия (ключевые)

| Функция | TRIfA | Antidote | Desktop |
|---------|-------|----------|---------|
| Conference / групповые звонки | Частично / эксперимент | Ограничено | ✅ |
| Tox message v3 / push metadata | ✅ Zoxcore extensions | ✅ | Частично |
| LAN discovery bootstrap | ✅ | ❌ | ❌ |
| Custom bootstrap UI | ✅ Maintenance | Settings | Advanced |
| Desktop-level polish | Низкий | Средний | Высокий (после rebrand) |

---

## 3. Активность разработки

| Проект | Оценка | Источник |
|--------|--------|----------|
| TRIfA | **Высокая** (форк с коммитами, сборка актуальна) | `khandaq-android-trifa/` builds OK |
| aTox | **Нет** (archived upstream) | README evilcorpltd/aTox |
| Antidote Zoxcore | **Низкая-средняя** (форк для msgv3/push) | Требует патчей на Xcode 15+ |
| qTox/Khandaq desktop | **Внутренняя** (единственный активный Khandaq fork) | khandaq-desktop |

---

## 4. Технический долг

| Клиент | Долг | Блокеры Alpha |
|--------|------|---------------|
| TRIfA | Монолит, sorma2 ORM, WebRTC native, debug-signed APK | Rebrand, release signing, bootstrap DB sync |
| aTox | tox4j/sbt pipeline, unmaintained | Не кандидат на базу |
| Antidote | MPL license, Pods 2019-era, simulator/device lib mismatch | Rebrand, App Store signing, MPL/GPL strategy |
| Desktop | Qt5, остатки qTox strings (исправлено в A4) | Linux amd64, DMG polish |

---

## 5. CTO-рекомендация

### Официальная база Android: **TRIfA**

**Почему:**
- Единственный Android-клиент с активным форком и успешной сборкой
- Feature parity с desktop (файлы, AV, группы, push, bootstrap refresh)
- aTox исключить из roadmap (archived, нет сборки)

**Действия Alpha:**
1. Rebrand package → `org.khandaq.messenger` (post-Alpha)
2. Синхронизировать `BootstrapNodeEntryDB` с `config/khandaq_bootstrap_nodes.json`
3. Release keystore + Play/F-Droid pipeline

### Официальная база iOS: **Antidote (Zoxcore fork)**

**Почему:**
- Единственный iOS Tox-клиент с toxav + push в кодовой базе
- Совместим с TRIfA extensions (msgv3, push URLs)
- Нет альтернативы с меньшим долгом в экосистеме Tox iOS

**Действия Alpha:**
1. Зафиксировать патчи сборки (libsodium, libvpx, deployment target 12)
2. Юридически отделить MPL iOS от GPL desktop (отдельный продукт в бренде Khandaq)
3. TestFlight pipeline + signed IPA

### Desktop: **Khandaq (qTox fork)** — primary product

Mobile = companion apps на upstream bases до завершения rebrand.

---

## 6. Что НЕ делать в Alpha

- Не начинать новый UI (Scenario B/C из PROJECT_STATUS_REPORT)
- Не поддерживать aTox как второй Android-клиент
- Не смешивать MPL и GPL в одном дистрибутиве без legal review

---

## 7. Долгосрочно (post-Alpha, не в scope)

| Вариант | Когда |
|---------|-------|
| Shared Kotlin Multiplatform core | При rebrand mobile + 2+ full-time mobile dev |
| Единый toxcore wrapper | При унификации TRIfA JNI и Antidote objcTox |
| Новый UI на toxcore | Только если TRIfA/Antidote rebrand заблокирован |
