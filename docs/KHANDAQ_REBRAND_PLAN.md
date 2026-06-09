# Khandaq Rebrand Plan — Фаза 1 (анализ qTox)

**Дата:** 2026-06-08  
**База:** `khandaq-desktop/` — клон `https://github.com/qTox/qTox`  
**Commit:** `2b9cbdc` (describe: `nightly`)  
**Статус:** только анализ, код не менялся

---

## 0. Критическое замечание по базе

Официальный `qTox/qTox` **помечен как unmaintained**. В `README.md` рекомендуется активный форк [TokTok/qTox](https://github.com/TokTok/qTox).

| Вариант | Плюсы | Минусы |
|---------|-------|--------|
| **qTox/qTox** (текущий клон) | Соответствует ТЗ, стабильная структура, много документации | Нет активной разработки, successor message в README |
| **TokTok/qTox** | Активные фиксы, совместимость с TokTok/c-toxcore | Потребует пересверки плана, возможны расхождения в путях/фичах |

**Рекомендация:** продолжить ребрендинг на текущем клоне для Фазы 2, но перед production-релизом сравнить diff с TokTok/qTox и cherry-pick критичных патчей.

---

## 1. Структура проекта

```
khandaq-desktop/
├── CMakeLists.txt          # project(qtox), add_executable(qtox)
├── cmake/
│   ├── Dependencies.cmake  # GIT_VERSION, зависимости
│   └── Installation.cmake  # desktop, icons, macOS bundle
├── src/                    # ~200+ исходников Qt/C++
│   ├── appmanager.cpp      # setApplicationName, логи, CLI
│   ├── persistence/        # paths, settings, profile, toxsave
│   ├── widget/             # UI, about, settings forms
│   ├── core/               # toxcore wrapper (НЕ ТРОГАТЬ API)
│   ├── net/                # bootstrap nodes, update check, tox URI
│   └── platform/           # autorun, notifications, camera
├── res/
│   ├── nodes.json          # встроенные bootstrap nodes
│   └── io.github.qtox.qTox.appdata.xml
├── img/
│   ├── icons/              # qtox.svg, qtox.icns, */qtox.png
│   └── login_logo.svg
├── themes/                 # default/ + dark/ (QSS, palette.ini)
├── translations/           # 40+ .ts файлов
├── windows/                # NSIS installer, .rc, cross-compile
├── osx/                    # info.plist, DMG scripts
├── flatpak/                # io.github.qtox.qTox.json
├── io.github.qtox.qTox.desktop
├── res.qrc                 # Qt resource bundle
├── INSTALL.md              # инструкции сборки
└── buildscripts/docker/    # Ubuntu/Fedora/Windows builder images
```

**CMake target:** `project(qtox)` → бинарник `qtox` (Linux/macOS), `qtox.exe` (Windows), `qtox.app` (macOS bundle).

---

## 2. Инвентарь брендинга

### 2.1. Имя приложения и UI (критично для Фазы 2)

| Что | Текущее значение | Файл |
|-----|------------------|------|
| Application name | `"qTox"` | `src/appmanager.cpp:192` |
| Desktop file name | `"io.github.qtox.qTox"` | `src/appmanager.cpp:194` |
| CLI description | `"qTox, version: …"` | `src/appmanager.cpp:213` |
| Main window title | `"qTox"` / `"{chat} - qTox"` | `src/mainwindow.ui:20`, `src/widget/widget.cpp:2758-2760` |
| Login screen title | `"qTox"` | `src/loginscreen.ui:26` |
| Window icon | `:/img/icons/qtox.svg` | `src/mainwindow.ui`, `src/loginscreen.ui`, `src/widget/widget.cpp:1892` |
| Tray icon theme | `qtox`, `qtox-{status}` | `src/widget/widget.cpp:179,591-592` |
| About: version string | `"You are using qTox version %1."` | `src/widget/form/settings/aboutform.cpp:101` |
| About: update status | `"qTox is up to date ✓"` | `src/widget/form/settings/aboutsettings.ui:206` |
| About: unstable warning | `"...unstable version of qTox"` | `aboutsettings.ui:245` |
| About: license HTML | qTox copyright/GPL text | `aboutsettings.ui:300-303` |
| About: bug tracker links | `github.com/qTox/qTox/...` | `aboutform.cpp:115-160` |
| Settings UI strings | ~15 строк с "qTox" | `generalsettings.ui`, `userinterfacesettings.ui`, `advancedsettings.ui` |
| Screenshot grabber | `"hide/show qTox window"` | `screenshotgrabber.cpp:172,183` |
| Desktop notifications icon | `:/img/icons/qtox.svg` | `desktopnotify.cpp:32` |

**Organization name:** `setOrganizationName()` **не вызывается**. Qt использует только `applicationName` для `QStandardPaths::AppDataLocation`.

### 2.2. Пути данных и конфигурации

| Что | Текущее | Файл |
|-----|---------|------|
| Global settings file | `qtox.ini` | `src/persistence/paths.cpp:35`, `settings.cpp:63` |
| Log file | `qtox.log`, `qtox.log.1` | `src/appmanager.cpp:256-275`, `advancedform.cpp:112,131` |
| IPC shared memory key | `qtox-{version}-{user}` | `src/ipc.cpp:55` |
| App data (Qt) | `~/.local/share/qTox/` (Linux, без org) | через `QStandardPaths::AppDataLocation` |
| Tox profile dir (TCS) | `~/.config/tox/` (Linux) | `paths.cpp:218-222` |
| Tox profile dir (Win) | `%APPDATA%/tox/` | `paths.cpp:206-211` |
| Tox profile dir (macOS) | `~/Library/Application Support/Tox/` | `paths.cpp:212-216` |
| Portable mode marker | `qtox.ini` рядом с бинарником | `paths.cpp:72-76` |
| Bootstrap nodes (user) | `bootstrapNodes.json` в settings dir | `paths.cpp:378-383` |
| Autorun (macOS) | `chat.tox.qtox.autorun` | `autorun_osx.cpp:32,42` |
| Autorun (Linux XDG) | `.desktop` с Exec=qtox | `autorun_xdg.cpp` |

**Флаг `PATHS_VERSION_TCS_COMPLIANT = 0`** (`paths.h:26`) — используется legacy API путей; профили `.tox` лежат в `~/.config/tox/`, а не в app-specific dir.

### 2.3. Desktop / packaging metadata

| Платформа | Файл | Ключевые значения |
|-----------|------|-------------------|
| Linux desktop | `io.github.qtox.qTox.desktop` | Name=qTox, Exec=qtox, Icon=qtox |
| AppStream | `res/io.github.qtox.qTox.appdata.xml` | id=io.github.qtox.qTox, name=qTox |
| Flatpak | `flatpak/io.github.qtox.qTox.json` | app-id, command=qtox, rename-icon=qtox |
| Windows NSIS x86 | `windows/qtox.nsi` | APP_NAME=qTox, setup-qtox.exe, qtox.exe |
| Windows NSIS x64 | `windows/qtox64.nsi` | то же |
| Windows RC | `windows/qtox.rc` | qtox.ico |
| macOS plist | `osx/info.plist` | CFBundleName=qTox, CFBundleIdentifier=chat.tox.qtox, CFBundleExecutable=qtox |
| macOS icons | `img/icons/qtox.icns`, `qtox_profile.icns` | bundle + profile type icon |
| CMake install | `cmake/Installation.cmake` | desktop, appdata, hicolor icons `qtox.png` |
| AppArmor | `security/apparmor/*/usr.bin.qtox` | profile name `qtox` |

### 2.4. Иконки и визуальные ассеты

```
img/icons/qtox.svg              # основная SVG (res.qrc, window, tray)
img/icons/qtox.icns               # macOS app icon
img/icons/qtox_profile.icns       # macOS .tox file icon
img/icons/{14,16,22,24,32,36,48,64,72,96,128,192,256,512}x{size}/qtox.png
img/login_logo.svg                # логотип на экране входа
windows/qtox.ico                  # генерируется из qtox.svg (generate-icon.sh)
osx/background-DMG/qTox-DMG-bak.tiff
```

### 2.5. Переводы (40+ языков)

Все файлы `translations/*.ts` содержат source strings с "qTox" (22–51 вхождение на файл).  
Пример: `translations/ru.ts` — 49 вхождений.

**Стратегия Фазы 2:** менять source strings в `.ui` и `.cpp` (`tr("...")`), затем `lupdate` для обновления `.ts`. Полный перевод "Khandaq" на все языки — отдельная задача; на старте достаточно английского source + ru.

### 2.6. Update check и внешние ссылки

| Компонент | URL / значение | Файл |
|-----------|----------------|------|
| Auto-update API | `api.github.com/repos/qTox/qTox/releases/latest` | `src/net/updatecheck.cpp:33` |
| Homepage | `https://qtox.github.io` | appdata.xml, NSIS |
| GitHub repo | `https://github.com/qTox/qTox` | aboutform, NSIS, themes (комментарий) |

Для Khandaq: отключить (`-DUPDATE_CHECK=OFF`) или перенаправить на свой release endpoint.

### 2.7. GPL copyright headers (НЕ удалять)

~500+ файлов содержат стандартный header:
```
Copyright © … by The qTox Project Contributors
This file is part of qTox, a Qt-based graphical interface for Tox.
```

**Действие:** добавить Khandaq modification notice, **не удалять** оригинальный copyright (GPL compliance).

### 2.8. Упоминания "Tox" (протокол — оставить)

Эти строки **не ребрендить**, они описывают протокол:

| Контекст | Примеры | Файлы |
|----------|---------|-------|
| Tox URI scheme | `tox:`, `x-scheme-handler/tox` | `appmanager.cpp`, `toxuri.cpp`, `.desktop` MimeType |
| Tox ID / profile | `.tox` extension, `Tox profile` | `info.plist`, NSIS SupportedTypes |
| toxcore API | `#include <tox/tox.h>`, `tox_version_*()` | `core/`, `aboutform.cpp` |
| Bootstrap | `res/nodes.json`, `bootstrapNodes.json` | `res/`, `paths.cpp` |
| Настройки сети | UDP, IPv6, LAN discovery, proxy | `appmanager.cpp` CLI options |
| Аудио | `ToxIncomingCall.wav` | `audio/original/` (внутренние имена, не UI) |

---

## 3. Целевые значения Khandaq (Фаза 2)

| Параметр | qTox (сейчас) | Khandaq (цель) |
|----------|---------------|----------------|
| Application name | qTox | Khandaq |
| Window title | qTox | Khandaq |
| About dialog title | (в settings) | Khandaq Messenger |
| Executable | qtox | khandaq |
| Desktop entry | io.github.qtox.qTox.desktop | khandaq.desktop |
| App ID | io.github.qtox.qTox | org.khandaq.messenger |
| Organization | (не задано) | Khandaq |
| Config file | qtox.ini | khandaq.ini |
| Profile/app data dir | ~/.local/share/qTox + ~/.config/tox | ~/.local/share/Khandaq/khandaq + ~/.config/khandaq |
| Logs | qtox.log | khandaq.log |
| IPC key prefix | qtox- | khandaq- |
| macOS bundle | qtox.app | Khandaq.app |
| macOS bundle ID | chat.tox.qtox | org.khandaq.messenger |
| Windows binary | qtox.exe | Khandaq.exe |
| Installer | setup-qtox.exe | Khandaq-installer.exe |

### 3.1. Решение по путям профиля

**Вариант A (TCS-совместимый):** оставить `~/.config/tox/` для `.tox` файлов → профили видны другим Tox-клиентам.  
**Вариант B (изолированный Khandaq):** заменить `tox` → `khandaq` в `paths.cpp` (все платформы) → чистый профиль, без автомиграции с qTox.

**Выбор по ТЗ:** Вариант B. Формат `.tox` и Tox ID не меняются; меняется только директория хранения. Импорт профиля вручную остаётся возможным.

---

## 4. Файлы для изменения по фазам

### Фаза 2 — Ребрендинг (обязательные)

#### CMake / build system
- [ ] `CMakeLists.txt` — `project(khandaq)`, target name, `SVG_SRC` path
- [ ] `cmake/Installation.cmake` — desktop, appdata, icon paths, bundle name
- [ ] `cmake/warnings/CMakeLists.txt` — project name (косметика)

#### Runtime / paths
- [ ] `src/appmanager.cpp` — applicationName, organizationName, organizationDomain, desktopFileName, logs, CLI
- [ ] `src/persistence/paths.cpp` — `khandaq.ini`, `khandaq` profile dirs
- [ ] `src/persistence/paths.h` — комментарии
- [ ] `src/persistence/settings.cpp` — `globalSettingsFile`
- [ ] `src/ipc.cpp` — IPC key prefix
- [ ] `src/widget/widget.cpp` — window title, icon paths, tray theme names
- [ ] `src/widget/form/settings/aboutform.cpp` — version strings, links → khandaq.org/github
- [ ] `src/widget/form/settings/advancedform.cpp` — log path, warning strings
- [ ] `src/platform/autorun_*.cpp` — desktop entry name, macOS label
- [ ] `src/platform/desktop_notifications/desktopnotify.cpp` — icon
- [ ] `src/net/updatecheck.cpp` — disable or retarget

#### UI files
- [ ] `src/mainwindow.ui` — windowTitle, windowIcon
- [ ] `src/loginscreen.ui` — windowTitle, windowIcon
- [ ] `src/widget/form/settings/aboutsettings.ui` — strings, license HTML (добавить Khandaq notice, сохранить GPL)
- [ ] `src/widget/form/settings/generalsettings.ui`
- [ ] `src/widget/form/settings/userinterfacesettings.ui`
- [ ] `src/widget/form/settings/advancedsettings.ui` (если есть qTox strings)

#### Packaging
- [ ] `io.github.qtox.qTox.desktop` → `khandaq.desktop` (переименовать + содержимое)
- [ ] `res/io.github.qtox.qTox.appdata.xml` → `org.khandaq.messenger.appdata.xml`
- [ ] `flatpak/io.github.qtox.qTox.json` → обновить (низкий приоритет)
- [ ] `windows/qtox.nsi` → `khandaq.nsi` (или параметризовать)
- [ ] `windows/qtox64.nsi` → `khandaq64.nsi`
- [ ] `windows/qtox.rc` → `khandaq.rc`
- [ ] `windows/cross-compile/build.sh` — output name
- [ ] `osx/info.plist` — все CFBundle* поля
- [ ] `osx/makedist.sh` — DMG name

#### Tests (обновить ожидания)
- [ ] `test/persistence/paths_test.cpp` — `khandaq.ini`, path assertions

### Фаза 3 — Иконки и тема

- [ ] Создать `resources/khandaq/` (icon.svg, icon.png, splash.png, logo.svg, theme.qss)
- [ ] Скопировать/сгенерировать PNG размеры из SVG (или symlink на placeholder)
- [ ] `res.qrc` — добавить khandaq assets, сохранить старые как fallback
- [ ] `img/login_logo.svg` — заменить на Khandaq logo
- [ ] `themes/dark/palette.ini` — primary `#0F3D2E`, accent `#C9A227`, bg `#0B0F0E`, text `#F5F5F0`
- [ ] `windows/generate-icon.sh` — khandaq.ico
- [ ] `img/icons/khandaq.icns` — macOS placeholder

**Источник логотипа:** https://khandaq.org/themes/element/img/logos/element-logo.svg

### Фазы 4–6 — Build scripts (создать новые)
- [ ] `scripts/build-linux.sh`
- [ ] `scripts/build-windows-cross.sh`
- [ ] `scripts/build-macos.sh`
- [ ] `docs/KHANDAQ_BUILD.md`, `WINDOWS_BUILD.md`, `MACOS_BUILD.md`

### Низкий приоритет / не Фаза 2
- `translations/*.ts` — массовое обновление после смены source strings
- `security/apparmor/` — новый профиль `usr.bin.khandaq`
- `.ci-scripts/`, `.github/workflows/` — CI под Khandaq
- `tools/update-versions.sh` — appdata path
- GPL headers во всех файлах — добавить modification block, не переписывать массово

---

## 5. Что НЕЛЬЗЯ менять (протокол Tox)

| Область | Почему |
|---------|--------|
| `src/core/*` — вызовы toxcore API | Ломает совместимость с сетью Tox |
| `#include <tox/tox.h>`, `tox_*()` функции | API c-toxcore |
| URI scheme `tox:` | Стандарт Tox deep linking |
| MIME `x-scheme-handler/tox`, `application/x-tox` | OS integration для Tox links |
| Расширение файла `.tox` | Tox Client Standard export format |
| `res/nodes.json` — публичные bootstrap nodes | Нужны для входа в общую сеть (добавление своих — Фаза 9, не замена) |
| SQLCipher schema / `dbupgrade/` | Ломает существующие базы истории |
| `toxext`, `tox_extension_messages` протокол | Расширения сообщений |
| Packet format, crypto, DHT логика | Внутри toxcore, не в qTox |

**Можно менять безопасно:** UI-лейблы "Tox ID", "Tox URI", "toxcore version" — это описание протокола, не идентичность приложения.

---

## 6. Зависимости

### 6.1. Обязательные (из INSTALL.md)

| Пакет | Мин. версия | Назначение |
|-------|-------------|------------|
| Qt5 | ≥ 5.7.1 | core, gui, widgets, network, svg, opengl, xml, concurrent |
| GCC / MinGW | ≥ 4.8 (C++11) | компилятор |
| CMake | ≥ 3.7.2 | build system |
| c-toxcore | ≥ 0.2.10 | протокол Tox (core, av) |
| FFmpeg | ≥ 2.6.0 | видео |
| OpenAL Soft | ≥ 1.16.0 | аудио |
| qrencode | ≥ 3.0.3 | QR для Tox ID |
| sqlcipher | ≥ 3.2.0 | шифрование БД истории |
| pkg-config | ≥ 0.28 | поиск библиотек |
| toxext | ≥ 0.0.3 | расширения |
| tox_extension_messages | ≥ 0.0.3 | расширенные сообщения |

### 6.2. Опциональные

| Пакет | CMake flag | Назначение |
|-------|------------|------------|
| sonnet (KF5) | `-DSPELL_CHECK=OFF` | spell check |
| libXScrnSaver + libX11 | auto | auto-away (Linux) |
| snorenotify | `-DDESKTOP_NOTIFICATIONS=ON` | desktop notifications |
| Check | dev only | unit tests |

### 6.3. Ubuntu 22.04/24.04 (пакеты)

```bash
sudo apt update
sudo apt install -y \
  build-essential cmake extra-cmake-modules git pkg-config \
  qtbase5-dev qttools5-dev-tools libqt5svg5-dev libqt5opengl5-dev \
  libavcodec-dev libavdevice-dev libavfilter-dev libavutil-dev \
  libswscale-dev libswresample-dev libopenal-dev libqrencode-dev \
  libsqlcipher-dev libsodium-dev libopus-dev libvpx-dev \
  libkf5sonnet-dev libxss-dev patchelf
```

Затем собрать из исходников: **c-toxcore**, **toxext**, **tox_extension_messages** (см. INSTALL.md § Compile dependencies).

---

## 7. Команды сборки

### 7.1. Linux (Ubuntu 22.04/24.04)

```bash
cd khandaq-desktop
mkdir -p build && cd build
cmake .. -DCMAKE_BUILD_TYPE=Release
make -j$(nproc)
# Результат: ./qtox  (после Фазы 2: ./khandaq)
./qtox --help
```

**С зависимостями через Docker (как в upstream):**
```bash
docker compose run --rm ubuntu_lts
# внутри контейнера — build toxcore + cmake + make
```

### 7.2. macOS (≥ 10.15)

```bash
brew bundle --file osx/Brewfile
# собрать toxcore, toxext, tox_extension_messages
mkdir -p _build && cd _build
cmake .. -DCMAKE_PREFIX_PATH=$(brew --prefix qt@5)
make -j$(sysctl -n hw.ncpu)
make install
# Результат: qtox.app + qTox.dmg (после Фазы 2: Khandaq.app)
```

### 7.3. Windows (cross-compile from Linux)

```bash
docker compose run windows_builder
mkdir build-windows && cd build-windows
/qtox/windows/cross-compile/build.sh \
  --src-dir /qtox --arch x86_64 --build-type Release
# Результат: qtox.exe + setup-qtox.exe
```

См. `windows/cross-compile/README.md`.

### 7.4. Полезные CMake flags для Khandaq

```bash
cmake .. \
  -DCMAKE_BUILD_TYPE=Release \
  -DUPDATE_CHECK=OFF \          # отключить проверку qTox releases
  -DSPELL_CHECK=OFF \           # если нет sonnet
  -DDESKTOP_NOTIFICATIONS=OFF   # если нет snorenotify
```

---

## 8. Проверки после ребрендинга (Фаза 4+)

- [ ] `./khandaq` запускается без ошибок
- [ ] Window title = "Khandaq"
- [ ] Профиль создаётся в `~/.config/khandaq/` (или `~/.local/share/Khandaq/`)
- [ ] `khandaq.ini` создаётся в config dir
- [ ] `khandaq.log` пишется в cache dir
- [ ] Tox ID генерируется
- [ ] Настройки открываются
- [ ] About → "Khandaq Messenger", toxcore version отображается
- [ ] Нет "qTox" в UI (кроме legal notices об upstream)
- [ ] `tox:` URI handler работает
- [ ] Подключение к публичным bootstrap nodes

---

## 9. Риски

| Риск | Severity | Митигация |
|------|----------|-----------|
| Unmaintained upstream qTox | High | Мониторить TokTok/qTox, cherry-pick fixes |
| Смена profile path ломает TCS interop | Medium | Документировать; ручной import/export `.tox` |
| 40+ translation files устареют | Low | `lupdate` + постепенный перевод |
| GPL compliance | High | Сохранить copyright, публиковать source (Фаза 13) |
| Update check тянет qTox releases | Medium | `-DUPDATE_CHECK=OFF` на Фазе 2 |
| IPC key change → два клиента параллельно | Low | Ожидаемо: qTox и Khandaq не конфликтуют |
| XDG icon cache (qtox → khandaq) | Low | `gtk-update-icon-cache` после install |
| AppArmor profile mismatch | Low | Новый профиль или отключение |

---

## 10. Предлагаемая структура репозитория Khandaq (Фаза 14)

```
khandaq/
├── desktop/          # ← переименовать khandaq-desktop после Фазы 2
├── android/          # Фаза 7-8
├── infra/            # Фаза 10
├── docs/
│   └── KHANDAQ_REBRAND_PLAN.md  (этот файл)
├── scripts/          # Фазы 4-6
├── dist/             # артефакты сборки
├── LICENSES/         # Фаза 13
├── config/           # bootstrap_nodes.json (Фаза 9)
├── resources/khandaq/ # Фаза 3
├── README.md
└── RELEASE_CHECKLIST.md
```

---

## 11. Отчёт Фазы 1

### Сделано
- Клонирован `qTox` → `khandaq-desktop/`
- Проанализирована структура: CMake, src/, res/, themes/, translations/, windows/, osx/
- Составлен полный инвентарь брендинга (UI, paths, packaging, icons, translations)
- Определены safe/unsafe зоны для протокола Tox
- Задокументированы зависимости и команды сборки Linux/macOS/Windows
- Код **не изменялся**

### Затронутые файлы
- Создан: `docs/KHANDAQ_REBRAND_PLAN.md`
- Клон: `khandaq-desktop/` (read-only анализ)

### Сборка
На macOS (текущая среда) полная сборка не выполнялась — требуются Qt5, toxcore и ~15 зависимостей. Команды указаны в §7.

### Следующий шаг
**Фаза 2:** ребрендинг Desktop по таблице §4, начиная с `appmanager.cpp`, `paths.cpp`, `CMakeLists.txt`, desktop/appdata metadata.
