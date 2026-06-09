# Khandaq — Technical Project Status Report

**Дата аудита:** 2026-06-08  
**Версия:** 0.1.0-alpha (desktop Phases 2–5, mobile as-is)  
**Метод:** статический анализ кодовой базы + проверка артефактов сборки. Runtime-тесты на устройствах **не проводились** в рамках аудита (статусы mobile/desktop features помечены соответственно).

---

# 1. Общая сводка проекта

## 1.1 Структура

```
Khandaq/
├── khandaq-desktop/      # Qt5/C++ fork qTox → Khandaq (основной продукт)
├── khandaq-android/      # aTox clone (upstream, без rebrand)
├── khandaq-android-trifa/ # TRIfA clone (upstream, без rebrand)
├── khandaq-ios/          # Antidote Zoxcore fork (upstream, без rebrand)
├── docs/                 # Планы, build guides, этот отчёт
├── scripts/              # build-macos/linux/windows/mobile
└── dist/                 # Артефакты сборки
    ├── linux/
    ├── macos/
    ├── windows/x86_64/
    ├── android/
    └── ios/
```

## 1.2 Дерево (depth 2)

```
.
├── dist/          {android, ios, linux, macos, windows}
├── docs/
├── scripts/
├── khandaq-desktop/   {src, res, themes, translations, windows, osx, buildscripts, ...}
├── khandaq-android/   {atox, core, domain, gradle, scripts}
├── khandaq-android-trifa/ {android-refimpl-app, jni-c-toxcore, ...}
└── khandaq-ios/       {Antidote, Pods, local_pod_repo, ...}
```

**Директорий (depth≤2):** 67  
**Файлов (всего, incl. build/Pods/cache):** ~17 599  
**Исходников (.cpp/.h/.kt/.java/.swift/.m/.mm, excl. build/Pods):** ~1 825

## 1.3 Размер

| Путь | Размер |
|------|--------|
| Весь workspace | **2.5 GB** |
| khandaq-desktop | 248 MB |
| khandaq-android-trifa | 1.0 GB |
| khandaq-ios | 960 MB |
| khandaq-android | 4.2 MB |
| dist/ | 368 MB |

## 1.4 Основные технологии

| Слой | Стек |
|------|------|
| Desktop | Qt 5.7+, C++11, CMake, c-toxcore, FFmpeg, OpenAL Soft, SQLCipher, libvpx, qrencode |
| Desktop packaging | NSIS (Win), .app bundle (macOS), .desktop/appdata (Linux) |
| Android TRIfA | Kotlin/Java, Gradle AGP 8.6, JNI, c-toxcore, SQLCipher, WebRTC audio |
| Android aTox | Kotlin, Dagger, Room, Navigation, tox4j |
| iOS Antidote | Swift 4 / ObjC, CocoaPods, Realm, Firebase Messaging, objcTox |
| Build/CI | Docker (Linux/Windows cross), Homebrew (macOS), Gradle, Xcode |

## 1.5 Зависимости (desktop runtime)

| Пакет | Назначение |
|-------|------------|
| Qt5 (Core, Gui, Widgets, Network, Svg, Xml, Concurrent) | UI |
| c-toxcore ≥0.2.10 | P2P протокол Tox |
| toxext, tox_extension_messages | Расширения сообщений |
| FFmpeg ≥2.6 | Видео |
| OpenAL Soft | Аудио |
| SQLCipher | История чатов (encrypted DB) |
| libvpx, libopus | Кодеки AV |
| libsodium, OpenSSL | Crypto (через toxcore) |
| libqrencode, libexif | QR, EXIF |
| SnoreToast / libsnore (Win) | Уведомления (optional) |
| Hunspell (optional) | Spell check |

Полный список: `khandaq-desktop/INSTALL.md`, `buildscripts/docker/`.

---

# 2. Ребрендинг аудит

## 2.1 Сводка по терминам

| Термин | Оценка количества | Категория |
|--------|-------------------|-----------|
| **qTox/qtox** | ~500+ в desktop (вкл. GPL headers ×~5 строк/файл) | См. разбивку ниже |
| **Tox/tox** | Повсеместно | Большинство — **протокол** (не трогать) |
| **toxcore** | API, labels, deps | **Техническое** |
| **TokTok** | buildscripts, nodes motd, docs | **Техническое** (upstream fork) |

## 2.2 Уже ребрендировано (Khandaq) ✅

| Область | Значение |
|---------|----------|
| `applicationName` | Khandaq |
| `organizationName` | Khandaq |
| `organizationDomain` | khandaq.org |
| Desktop ID | `org.khandaq.messenger` |
| Binary | `khandaq` / `Khandaq.exe` |
| Config | `khandaq.ini` |
| Profile dir | `~/.config/khandaq/` (Linux) |
| Logs | `khandaq.log` |
| Window title | Khandaq |
| Icons | `khandaq.svg`, theme QSS |
| About (частично) | «Khandaq Messenger version» |
| IPC prefix | `khandaq-` |

## 2.3 Пользовательские упоминания qTox — **нужно заменить**

| Файл | Строка / контекст |
|------|-------------------|
| `src/persistence/settings.cpp:126,500` | `tr("...Cannot start qTox.")` |
| `src/widget/tool/messageboxmanager.cpp:143` | `tr("You have asked qTox to open...")` |
| `src/widget/form/chatform.cpp:302,564,646` | `tr("qTox wasn't able to...")` |
| `src/persistence/profile.cpp:281` | `tr("Toxing on qTox")` default status |
| `src/persistence/profile.cpp:636,648` | `tr("qTox couldn't open your chat logs...")` |
| `src/persistence/db/upgrades/dbupgrader.cpp:239` | `tr("...upgrade qTox.")` |
| `src/platform/desktop_notifications/desktopnotify.cpp:39` | `Snore::Application("qTox", ...)` — видно в уведомлениях Win |
| `translations/*.ts` (40+ файлов) | Все `source` обновлены частично; **переводы** всё ещё «qTox» (см. `ru.ts`) |
| `osx/info.plist:15,77` | UTI `qtox_profile` |
| `windows/qtox.nsi`, `qtox64.nsi` | Функции `Launch_qTox_without_Admin`, temp `qTox-install-...` |
| `io.github.qtox.qTox.desktop` | Полностью qTox (legacy, не используется) |
| `res/io.github.qtox.qTox.appdata.xml` | Полностью qTox (legacy) |

## 2.4 Технические / GPL — **не заменять** (или только в legal)

| Область | Примеры |
|---------|---------|
| GPL file headers | `This file is part of qTox` во всех `src/**` |
| About GPL block | `aboutsettings.ui:301-304` — upstream copyright |
| `appdata.xml` | «Based on qTox» — корректно для GPL |
| `tox:` URI, `.tox` files | Протокол Tox |
| `toxcore`, `tox_*()` API | c-toxcore |
| `res/nodes.json` motd | Текст операторов нод (не UI) |
| Thread names | `"qTox Core"`, `"qTox Database"` — debug only |
| Smileys metadata | `smileypack.cpp` — upstream pack names |

## 2.5 Tox / toxcore / TokTok — классификация

| Контекст | Заменять? |
|----------|-----------|
| UI: «Tox ID», «toxcore version» в About | Нет — описание протокола |
| `src/core/tox*.cpp` | Нет — API |
| `buildscripts/download/download_toxcore.sh` → TokTok/c-toxcore | Нет — dep URL |
| Default status «Toxing on qTox» | **Да** → «Toxing on Khandaq» |
| `nodes.json` встроенные bootstrap | Нет — сеть Tox |

## 2.6 Mobile — ребрендинг **не начат**

| Клиент | Package / Bundle | Бренд |
|--------|------------------|-------|
| TRIfA | `com.zoffcc.applications.trifa` | TRIfA |
| aTox | `ltd.evilcorp.atox` | aTox |
| Antidote | `org.zoxcore.Antidote` | Antidote |

---

# 3. Android аудит

> APK собраны в workspace. Runtime на устройстве **не тестировался** — статусы по upstream + code review.

## 3.1 TRIfA (основная source-сборка)

| Параметр | Значение |
|----------|----------|
| Собирается APK | **Да** (`assembleRelease` успешен, 2026-06-08) |
| Артефакт | `dist/android/trifa-release.apk` (~101 MB, debug-signed) |
| Unsigned | `trifa-release-unsigned.apk` (~101 MB) |
| applicationId | `com.zoffcc.applications.trifa` |
| minSdk | 21 |
| targetSdk | 33 |
| compileSdk | 34 |
| namespace | `com.zoffcc.applications.trifa` |

## 3.2 aTox (upstream release)

| Параметр | Значение |
|----------|----------|
| Source build на macOS | **Нет** (нет `Darwin.mk`, нужен sbt+tox4j+NDK Linux) |
| APK | `dist/android/atox-v0.8.0.apk` (~16 MB, upstream release) |
| applicationId | `ltd.evilcorp.atox` |
| minSdk / targetSdk | 21 / 35 (из `gradle/libs.versions.toml`) |
| Upstream status | **Unmaintained** (README 2024+) |

## 3.3 Функциональность (TRIfA — наиболее полный клиент)

| Функция | Статус | Комментарий |
|---------|--------|-------------|
| Создание профиля | **WORKING** | Upstream stable, F-Droid |
| Генерация Tox ID | **WORKING** | toxcore JNI |
| Сохранение профиля | **WORKING** | Encrypted export supported upstream |
| Добавление контакта | **WORKING** | |
| Отправка сообщений | **WORKING** | |
| Получение сообщений | **WORKING** | |
| Передача файлов | **WORKING** | |
| Групповые чаты | **WORKING** | incl. persistent groups |
| Уведомления | **WORKING** | Android notifications; FCM не у TRIfA |
| Аудио/видео звонки | **WORKING** | TRIfA strength |
| Аудио (aTox) | **PARTIAL** | Нет audio calls в aTox |
| Видео (aTox) | **BROKEN** | Не поддерживается |

## 3.4 Риски Android

- APK подписан debug keystore — не для production
- Khandaq branding отсутствует
- TRIfA — большая codebase (1 GB), сложный JNI
- aTox unmaintained — не рекомендуется как долгосрочная база

---

# 4. iOS аудит

## 4.1 Сборка

| Параметр | Значение |
|----------|----------|
| `pod install` | **Да** (успешно) |
| `xcodebuild` Simulator | **Нет** — падает на Xcode 26: `libsodium` alignment в Pods |
| IPA артефакт | `dist/ios/antidote-v1.4.28-unsigned.ipa` (~70 MB, upstream) |
| bundle identifier | `org.zoxcore.Antidote` |
| deployment target | iOS 11.0 |
| Swift version | 4.0 |
| Лицензия | **MPL-2.0** (не GPL) |

## 4.2 Зависимости (CocoaPods)

- objcTox, toxcore (local_pod_repo, TokTok/c-toxcore)
- cmp, Yaml
- Realm (через objcTox chain)
- Firebase/Messaging 8.15
- SnapKit, SDCAlertView, LNNotificationsUI, JGProgressHUD

## 4.3 Функциональность (Antidote upstream)

| Функция | Статус | Комментарий |
|---------|--------|-------------|
| Запуск приложения | **PARTIAL** | IPA unsigned; source build broken на Xcode 26 |
| Создание профиля | **WORKING** | upstream feature set |
| Tox ID | **WORKING** | |
| Добавление контакта | **WORKING** | |
| Сообщения | **WORKING** | |
| Файлы | **WORKING** | |
| Группы | **PARTIAL** | Ограниченнее TRIfA |
| Уведомления | **PARTIAL** | Firebase push в fork; требует настройки |
| Аудио/видео | **WORKING** | Antidote historically strong |

---

# 5. Desktop аудит

## 5.1 Linux

| Параметр | Значение |
|----------|----------|
| Собирается | **Да** (Docker Ubuntu 24.04) |
| Бинарник | `dist/linux/khandaq` — **13.2 MB**, ELF **aarch64** |
| Архитектура | ⚠️ Apple Silicon Docker — не amd64 release |
| Известные проблемы | aarch64 only; нужен x86_64 builder для релиза; GUI не тестировался в аудите |
| Отсутствует | AppImage/Flatpak Khandaq; deb/rpm; amd64 binary |

## 5.2 Windows

| Параметр | Значение |
|----------|----------|
| Собирается | **Да** (cross-compile Docker x86_64) |
| Артефакт | `khandaq-x86_64-Release.zip` (~23 MB), `Khandaq-installer.exe` (~16 MB) |
| Khandaq.exe | ~12.2 MB (в zip) |
| Известные проблемы | DLL check пропущен на aarch64 Docker host; dead download URLs починены вручную (Qt, qrencode, libsodium); updater не собирается |
| Отсутствует | Code signing; auto-update; 32-bit i686 build не запускался |

## 5.3 macOS

| Параметр | Значение |
|----------|----------|
| Собирается | **Да** (native Homebrew) |
| Бинарник | `Khandaq.app` — binary **13.1 MB** |
| Архитектура | arm64 (Apple Silicon host) |
| Известные проблемы | `qtox_profile` UTI в plist; DMG не собран; notarization нет |
| Отсутствует | Universal binary; DMG polish (Phase 6); Sparkle/update |

## 5.4 Desktop features (Khandaq fork — code-based)

| Область | Статус |
|-------|--------|
| Профили / Tox ID | Реализовано (upstream qTox) |
| E2E сообщения | toxcore |
| Файлы / AV | FFmpeg + toxcore AV |
| Группы | Да |
| Spell check | Optional (OFF в Docker/Linux build) |
| Desktop notifications | OFF в Linux Docker; ON в Windows |
| Update check | **OFF** (правильно для rebrand) |
| Tox URI `tox:` | Сохранён |

---

# 6. GPL аудит

Полный отчёт: **[LICENSE_AUDIT.md](LICENSE_AUDIT.md)**

Кратко: GPL-3.0 headers сохранены; **нет** root NOTICE/THIRD_PARTY_LICENSES; legacy qTox appdata/desktop файлы остались; mobile MPL (iOS) vs GPL (desktop) — несовместимая смесь для единого продукта без стратегии.

---

# 7. Безопасность

## 7.1 Desktop (Khandaq)

| Аспект | Расположение / поведение |
|--------|--------------------------|
| Профили `.tox` | `~/.config/khandaq/` (Linux), `%APPDATA%/khandaq/` (Win), `~/Library/Application Support/Khandaq` (macOS) |
| Настройки | `~/.config/Khandaq/khandaq.ini` (через QSettings + org name) |
| Логи | cache dir / `khandaq.log` |
| Ключи Tox | Внутри `.tox` save (toxcore); в памяти при работе |
| Шифрование профиля | **Опционально** — пароль при создании (`ToxEncrypt`, `profile.cpp`) |
| Шифрование истории | SQLCipher (`history.cpp`, encrypted DB) |
| Экспорт | `.tox` export (стандарт Tox) |
| Импорт | `ProfileImporter`, `.tox` handler |
| Пароль профиля | Да — login screen |
| IPC | Shared memory `khandaq-{version}-{user}` |

## 7.2 Риски

| Риск | Severity |
|------|----------|
| Unmaintained qTox base — нет security patches upstream | **High** |
| `nodes.json` last_scan 2020 — устаревшие/мертвые ноды | **Medium** |
| Профиль path изменён — нет миграции с qTox | **Low** |
| Windows Snore app id «qTox» | **Low** (metadata leak) |
| Mobile unsigned APK/IPA | **High** для distribution |
| Antidote Firebase — third-party push metadata | **Medium** |
| Нет threat model / security audit | **High** для production messenger |

---

# 8. Сетевой аудит

## 8.1 Bootstrap nodes

| Источник | Путь |
|----------|------|
| Desktop embedded | `khandaq-desktop/res/nodes.json` |
| aTox | `khandaq-android/atox/src/main/res/raw/nodes.json` |
| Antidote | `khandaq-ios/local_pod_repo/objcTox/.../nodes.json` |
| TRIfA | Собственная логика + convert_nodes_file |

## 8.2 Характеристики

- **Публичные Tox DHT bootstrap nodes** — да, community-operated
- `last_scan`: **1606049991** (~2020-11-22) — данные **устарели**
- Часть нод `status_udp/tcp: false` — мёртвые записи в JSON
- motd некоторых нод содержит «qTox best Tox» — не UI, данные оператора

## 8.3 Собственные bootstrap nodes

- **Можно** — toxcore поддерживает custom bootstrap (`bootstrapnodeupdater.cpp`, settings)
- Конфигурация: `res/nodes.json` (embedded), user `bootstrapNodes.json` в settings dir
- Фаза 9 (не реализована): собственная infra

## 8.4 Сеть

- P2P DHT Tox — без центрального сервера сообщений
- Proxy: SOCKS5/HTTP в settings
- LAN discovery: опционально
- IPv6/UDP: CLI flags в `appmanager.cpp`

---

# 9. UI аудит

> Скриншоты в рамках аудита **не снимались** (нет GUI session). Оценка по `.ui`, QSS, themes.

## 9.1 Устаревший / qTox 2015 look

| Экран | Проблема |
|-------|----------|
| Login screen | Qt Widgets, фиксированный layout, legacy login logo area |
| Main window | Трёхколоночный splitter qTox, circle widget |
| Settings | Tabbed dialogs, `.ui` forms — типичный Qt 2014 |
| Chat form | QTextEdit + emoji picker — functional, не modern |
| About | HTML в QLabel, старый GPL block |
| Friend list | Custom widgets, не Material/HIG |

## 9.2 Частично обновлено (Phase 3)

- Dark palette `#0B0F0E` / `#0F3D2E` / `#C9A227`
- `theme.qss` brand overlay
- Khandaq icons / login logo SVG

## 9.3 Требует полного редизайна

1. **Login / onboarding** — первое впечатление
2. **Chat bubble UI** — конкуренты 2020+ стандарт
3. **Settings** — слишком много вкладок, advanced scary
4. **Profile / QR** — функционально, визуально старо
5. **Mobile** — отдельные UI (TRIfA/aTox/Antidote), не Khandaq

## 9.4 Скриншоты

Недоступны в headless audit. Рекомендуется: `docs/screenshots/` после ручного прогона на Linux/macOS/Win.

---

# 10. Кодовая база — оценка модулей (1–10)

Оценка **khandaq-desktop** (основной fork):

| Модуль | Архитектура | Тех. долг | Поддержка | Развитие | Итого |
|--------|-------------|-----------|-----------|----------|-------|
| `src/core/` | 7 | 5 | 6 | 7 (toxcore bound) | **6/10** |
| `src/persistence/` | 6 | 4 | 5 | 5 | **5/10** |
| `src/widget/` | 5 | 3 | 4 | 4 | **4/10** |
| `src/model/` | 7 | 6 | 6 | 6 | **6/10** |
| `src/net/` | 6 | 5 | 5 | 5 | **5/10** |
| `src/video/` + `audio/` | 6 | 4 | 4 | 5 | **5/10** |
| `translations/` | 4 | 2 | 3 | 3 | **3/10** |
| `buildscripts/` | 6 | 3 | 4 | 4 | **4/10** |
| `test/` | 6 | 6 | 5 | 5 | **6/10** |
| Mobile (3 repos) | 5–7 | 3–5 | 3–6 | 4–6 | **4–6/10** |

**Среднее desktop:** ~5/10 — рабочий fork, тяжёлый Qt Widgets UI, мёртвый upstream.

---

# 11. Roadmap — три сценария

## Сценарий A: Минимальный релиз текущего форка

**Цель:** Khandaq desktop + упаковка mobile as-is под одним брендом (marketing only).

| | |
|--|--|
| Сложность | **Средняя** |
| Сроки | **2–4 месяца** (1–2 dev) |
| Объём кода | ~5–15k LOC изменений |
| Работы | Дочистить qTox strings + lupdate; NOTICE/licenses; amd64 Linux; sign Win/macOS; минимальный mobile rebrand (icons, name); CI |
| Риски | Unmaintained base; security; mobile три разных кодовые базы |

## Сценарий B: Tox Core + новые UI

**Цель:** Сохранить toxcore/toxav; переписать UI (Qt6/QML или Electron/Tauri + native core).

| | |
|--|--|
| Сложность | **Высокая** |
| Сроки | **9–18 месяцев** (2–4 dev) |
| Объём кода | ~80–150k LOC нового UI + bindings |
| Работы | Core abstraction layer; новый design system; единый mobile (Flutter/RN + toxcore FFI); desktop QML |
| Риски | FFI complexity; AV на всех платформах; потеря feature parity |

## Сценарий C: Modern Khandaq + сервисы

**Цель:** Tox E2E + push relay, custom bootstrap, profile discovery service.

| | |
|--|--|
| Сложность | **Очень высокая** |
| Сроки | **18–36 месяцев** (4–8 dev) |
| Объём кода | ~200–400k LOC + infra |
| Работы | Bootstrap fleet; Tox push proxy (не ломая E2E msg); optional profile directory; relay для NAT; новые клиенты |
| Риски | Архитектурное отклонение от «чистого Tox»; юридические/доверие; operational cost |

---

# 12. Финальный вывод (CTO perspective)

## Вердикт

**Краткосрочно (3–6 мес):** продолжать **текущую кодовую базу desktop** для alpha/beta релиза — **да**, сценарий A оправдан: rebrand уже на 70–80%, сборки работают, Tox protocol intact.

**Среднесрочно (12+ мес):** **не** ставить на долгосрочное развитие Qt Widgets fork qTox как единственную платформу. Upstream мёртв, UI debt критичен, mobile — три чужих приложения без единой архитектуры.

**Рекомендация:** гибрид

1. **Сейчас:** Scenario A — довести desktop Khandaq до подписанного релиза + GPL compliance
2. **Параллельно:** выбрать **один** mobile base (TRIfA — наиболее feature-complete) и начать rebrand + fork maintenance
3. **Через 6–9 мес:** решение Scenario B vs C на основе метрик пользователей и capacity команды

## Технические аргументы

| За продолжение fork | Против |
|---------------------|--------|
| Работающий toxcore integration | qTox/qTox unmaintained |
| 80% rebrand done | 40+ translations stale |
| Builds on 3 desktop OS | Linux aarch64-only artifact |
| GPL path clear (with fixes) | iOS MPL ≠ GPL product unity |
| Low cost to ship alpha | Qt Widgets — тупик для modern UX |
| | nodes.json 2020 — connectivity risk |
| | Нет единой mobile codebase |

**Если бы я был CTO Khandaq:** я бы **не** инвестировал в глубокую модернизацию текущего Qt Widgets UI. Я бы **использовал** текущий fork как **временный ship vehicle** (Scenario A), одновременно закладывая **Scenario B** (новый UI на toxcore) или **C** (если нужны push/bootstrap как продуктовое преимущество). Полный rewrite «с нуля» без toxcore — нет; полное сохранение qTox UI — тоже нет.

---

## Приложение A: Артефакты сборки (2026-06-08)

| Платформа | Файл | Размер |
|-----------|------|--------|
| Linux | `dist/linux/khandaq` | 13.2 MB (aarch64) |
| macOS | `dist/macos/khandaq.app/.../khandaq` | 13.1 MB |
| Windows | `dist/windows/x86_64/khandaq-x86_64-Release.zip` | 23.6 MB |
| Windows | `dist/windows/x86_64/Khandaq-installer.exe` | 16.5 MB |
| Android | `dist/android/trifa-release.apk` | 101 MB |
| Android | `dist/android/atox-v0.8.0.apk` | 16.1 MB |
| iOS | `dist/ios/antidote-v1.4.28-unsigned.ipa` | 70 MB |

## Приложение B: Документация проекта

- `docs/KHANDAQ_REBRAND_PLAN.md`
- `docs/KHANDAQ_BUILD.md`, `LINUX_BUILD.md`, `WINDOWS_BUILD.md`, `MOBILE_BUILD.md`
- `docs/KHANDAQ_CHANGELOG.md`
- `docs/LICENSE_AUDIT.md`

---

*Отчёт подготовлен без изменений в коде. Следующий шаг по запросу: приоритизированный backlog исправлений.*
