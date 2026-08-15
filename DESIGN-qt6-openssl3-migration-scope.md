# Qt 6 / OpenSSL 3 migration — scope estimate

**Status:** scoping only, nothing implemented. Produced 11 Aug 2026 by a 6-agent parallel audit of
`khandaq-desktop` (5 subsystem surveys + 1 synthesis), in answer to external audit #2 finding 7.
Every claim below is anchored to a file:line an agent actually read.

**The one thing to take away if you read nothing else:** the cost is the Windows cross-toolchain,
not the C++. See §1.

---

# Qt 5.12 → Qt 6 + OpenSSL 1.1.1w → 3: оценка объёма (khandaq-desktop)

## 1. Вердикт

**Месяц, не неделя и не квартал: ~22–34 человеко-дня, из них 8–14 — только Windows-кросс-тулчейн.** Доминирующий фактор — **не C++-код, а `buildscripts/build_qt_windows.sh` + `Dockerfile.windows_builder`**: сегодня Qt собирается qmake-style `./configure` с `-device-option CROSS_COMPILE=` / `-xplatform win32-g++` (`buildscripts/build_qt_windows.sh:20-75`) в один проход (`buildscripts/docker/Dockerfile.windows_builder:70-76`); Qt6 требует CMake-configure, отдельный **host-Qt6** для moc/rcc/uic/lrelease, Ninja (которого в образе нет, `Dockerfile.windows_builder:27-50`) и другой список `-skip` (половина из 33 репозиториев в Qt6 не существует). Это новый скрипт, а не патч, и цикл обратной связи по нему — часы на попытку (`.github/workflows/windows-build.yaml:15` — `timeout-minutes: 350` на **одну** сборку Qt5, а OpenSSL — слой №1 образа, `Dockerfile.windows_builder:62-68`, так что его бамп инвалидирует Qt-слой). **Прикладной код — меньше, чем боялись:** QtMultimedia не используется вообще (0 вхождений, `-skip multimedia` в `build_qt_windows.sh:46`), TLS-кода нет вовсе (0 `QSsl*`, крипта на libsodium — `src/persistence/profile.cpp:29`), нет `QTextCodec`/`QLinkedList`/`QVariant::Type`/`qrand`/`toSet`. Настоящий редизайн ровно один: детект смены сети в `src/core/core.cpp:315-318`. **Сборка — больше, чем боялись.**

## 2. Воркстримы

Колонки **не суммируются по файлам** — области пересекаются; union кодовых файлов с жёсткой поломкой ≈ **40** (32 в `src/` по UI-аудиту ∪ 20 по core-net ∪ 27 по sweep) + ~14 файлов сборки.

| Воркстрим | Файлов | Механика / редизайн | Дней | Что именно |
|---|---|---|---|---|
| **Build system** | ~14 (`CMakeLists.txt`, `cmake/Dependencies.cmake`, `build_qt_windows.sh`, `Dockerfile.windows_builder`, `windows/cross-compile/build.sh`, `download_qt.sh`, `download_openssl.sh`, toolchain, `translations/CMakeLists.txt`, `qtox64.nsi`) | **30 / 70** | **8–14** | `build_qt_windows.sh` переписывается целиком + host-Qt6 стейдж; 10× `find_package(Qt5*)`→`Qt6*` (`Dependencies.cmake:25-34`), 13 таргетов `Qt5::`, `qt5_wrap_ui` (`CMakeLists.txt:164`), `qt5_add_translation` (49 .ts, `translations/CMakeLists.txt:18-69`); C++17 вместо `-std=c++11` (`CMakeLists.txt:116`); `cmake_minimum_required 3.7.2`→≥3.16 (`:23`); переименование DLL везде |
| **Core / net** | 3 (`core.cpp`, `core.h`, `updatecheck.cpp`) | **1 файл / 1 редизайн** | **2–3** | `QNetworkConfigurationManager` удалён (`core.cpp:51,315-318`, `core.h:49,52,312`) → `QNetworkInformation::reachabilityChanged` + собственный поллер `QNetworkInterface` (аналога `configurationChanged` нет); `QDateTime::fromTime_t` (`core.cpp:1252`); зафиксировать, что Qt6 по умолчанию следует редиректам — `updatecheck.cpp:125` политику не задаёт |
| **UI / widgets** | 32 | **27 / 5 поведенческих** | **5–7** | 20× `setMargin` в 12 файлах; 9 инклюдов `QDesktopWidget` (6 — мёртвые) + 3 живых сайта; 17× `Qt::CTRL +` в 7 файлах; `enterEvent(QEnterEvent*)` (`genericchatroomwidget.h:78`); `QMessageBox::setButtonText` ×3 (`messageboxmanager.cpp:165,172,179`). Поведенческие: `style.cpp:71-81`, `appmanager.cpp:176-179`, `screenshotgrabber.cpp:227-232`, `genericchatform.cpp:241-263`, `widget.cpp:420-472` (mac) |
| **Multimedia / AV** | 3 | **100 / 0** | **0.5** | Весь стек — OpenAL (`audio/src/backend/openal.cpp`) + FFmpeg (`src/video/camerasource.cpp`) + `QPainter` (`src/video/videosurface.cpp:197-205`). Ломается ровно: 2 инклюда `QDesktopWidget` (`netcamview.cpp:39`, `cameradevice.cpp:22`) + `netcamview.cpp:133 setMargin(0)` |
| **Deprecated sweep** | 27 (union) | **16 / 11 семантических** | **3–4** | 44 сайта; ядро работы — 11 переписываний `QRegExp` в 7 файлах с реальной сменой семантики + связка `QStringRef`/`capturedRef` (`textformatter.cpp:128,185`) |
| **QA + релиз** | — | — | **3–5** | См. §5; автотестов на AV/сеть/UI нет |
| | | | **22–34** | |

Отдельно: **`Qt5::OpenGL` (`Dependencies.cmake:30,48`) — мёртвая зависимость** (0 вхождений `QOpenGL*`/`QGLWidget`), не тащить `Qt6::OpenGLWidgets`, просто выкинуть. `Qt5Xml` нужен (`smileypack.cpp:206-238`, DOM в Qt6 жив).

## 3. Три главных риска

**R1. Windows-рецепт Qt6 — новый скрипт с циклом обратной связи в часы, и он единственный, чем можно проверить всё остальное.**
- `buildscripts/build_qt_windows.sh:22-24` — `-device-option "CROSS_COMPILE=…" -xplatform win32-g++`: синтаксис qmake-configure, в Qt6 не работает.
- `Dockerfile.windows_builder:70-76` — один проход Qt; Qt6 требует `-qt-host-path <host Qt6>` → Qt собирается **дважды**.
- `Dockerfile.windows_builder:27-50` — нет `ninja-build`; `build_qt_windows.sh:32-67` — `-skip canvas3d/gamepad/script/xmlpatterns/x11extras/macextras/…`, репозиториев нет в Qt6; `:17-18,26` — `OPENSSL_LIBS` и `pkg-config --cflags` как позиционный аргумент Qt6-configure не понимает.
- `.github/workflows/windows-build.yaml:9,15` — `workflow_dispatch`-only, x86_64-only, 350 минут таймаута на текущий один проход Qt5. Двойная сборка Qt6 в этот бюджет может не влезть.
- `Dockerfile.windows_builder:62-68` — OpenSSL строится первым слоем: любой бамп → полный ребилд Qt.

**R2. Молчаливая порча внешнего вида — компилятор не скажет ничего.**
- `src/widget/style.cpp:81` — `QString("%1 %2px \"%3\"").arg(font.weight() * 8)`. В Qt5 шкала весов 0–99 (`Normal=50`, `Bold=75`) → CSS 400/600; в Qt6 шкала уже CSS 1–1000 (`Normal=400`) → `*8` даст **`font: 3200 …`** во **всех** QSS-темах (12 тем, `style.cpp:90-99`). Плюс `style.cpp:71-75` `font.setWeight(int)` — int-перегрузка в Qt6 удалена, т.е. поломка компиляции и поломка поведения в одной функции.
- `src/appmanager.cpp:176-179` — гард `>= 5.6.0` под Qt6 истинен, `AA_EnableHighDpiScaling`/`AA_UseHighDpiPixmaps` компилируются и **ничего не делают**; без `setHighDpiScaleFactorRoundingPolicy()` раскладка на 125/150% уедет относительно Qt5.
- `src/widget/tool/screenshotgrabber.cpp:227-232` — `grabWindow(QApplication::desktop()->winId(), rec.x() * pixRatio, …)`: при всегда включённом HiDPI в Qt6 ручное домножение на `pixRatio` (`:51`) даёт двойное масштабирование, а `grabWindow(0,…)` со «старым» winId тихо снимет не тот surface.

**R3. Сторонние зависимости, у которых может не быть Qt6-версии, и allow-list DLL, который падает от любого переименования.**
- `buildscripts/download/download_snore.sh:20` — `SNORE_VERSION=0.7.0` (KDE snorenotify, релиз 2016); `cmake/Dependencies.cmake:262` — `find_package(LibsnoreQt5 0.7.0 REQUIRED)`; `windows/cross-compile/build.sh:88,99` — `-DDESKTOP_NOTIFICATIONS=ON` захардкожено. **[прогноз]** Qt6-порта snorenotify upstream нет — вопреки утверждению build-аудита; проверить обязательно. Хорошая новость: код изолирован — `src/platform/desktop_notifications/desktopnotify.{h,cpp}` (~100 строк) + 8 гардов `#if DESKTOP_NOTIFICATIONS`, так что замена на `QSystemTrayIcon::showMessage`/WinToast — 1–2 дня, а не блокер релиза.
- `windows/cross-compile/build.sh:191-207` + `:217-225` — жёсткий список runtime-DLL и проверка «нет лишних DLL» с `exit 1`. Под Qt6/OpenSSL3 сразу три несоответствия: `libssl-1_1-x64.dll` → `libssl-3-x64.dll` (`:206`), `libsnore-qt5/…` (`:191`), `platforms/qdirect2d.dll` (`:197`) — плагина в Qt6 нет с 6.0. Плюс `Dockerfile.windows_builder:186-191` экспортирует `Qt5Core.dll`…`Qt5Widgets.dll` поимённо и `qtox64.nsi:265-268` ссылается на `libsnore-qt5`.

## 4. Порядок работ

**Шаг 0 — Qt-нейтральная чистка, шипится на 5.12 сегодня (~2 дня, риск нулевой).**
`CMakeLists.txt:116` → `set(CMAKE_CXX_STANDARD 17)`; `:23` → `cmake_minimum_required(3.16)`; удалить мёртвый `execute_process(brew --prefix qt@5)` (`:91-94`, переменная `QT_PREFIX_PATH` больше нигде не используется); удалить `Qt5OpenGL` (`Dependencies.cmake:30,48`); добавить явный `Qt5::Concurrent` в `add_dependency` (`Dependencies.cmake:44-51` — сейчас затягивается транзитивно); удалить 6 мёртвых `#include <QDesktopWidget>`; удалить 6 мёртвых `#include <QSignalMapper>` (`nexus.cpp:50`, `widget.cpp:44`, `groupinviteform.cpp:37`, `addfriendform.cpp:39`, `groupinvitewidget.cpp:29` + 2 forward-декларации — API нигде не используется, это **не** поломка Qt6, вопреки замечанию AV-аудита); удалить `util/include/util/compatiblerecursivemutex.h`. Не трогать `CMakeLists.txt:106` (`Qt5Widgets_VERSION` пуст до `include(Dependencies)` на `:155`, поэтому `-format-version 1` не добавляется никогда) — «починка» этого бага под Qt6 сломает `rcc`; правильный шаг — **удалить блок `:106-113`**.

**Шаг 1 — поднять Windows-пин Qt 5.12.12 → 5.15.2 (`download_qt.sh:20-23` + hash). Да, это де-рискует прыжок, и существенно.**
Один пин, один ребилд образа, тот же qmake-configure. Что это даёт: (а) versionless CMake — `find_package(QT NAMES Qt6 Qt5)`, `qt_wrap_ui`, `qt_add_translation`, таргеты `Qt::*` — их **нет** в 5.12, поэтому без этого шага придётся руками ветвить `if(QT_VERSION_MAJOR EQUAL 6)`; (б) `Qt::SkipEmptyParts`, `QRegularExpression`-эквиваленты, `horizontalAdvance` — доступны без гардов; (в) главное — **`QT_DISABLE_DEPRECATED_BEFORE=0x050F00` превращает grep-аудит в вывод компилятора**: почти всё, что Qt6 удалил, в 5.15 помечено deprecated. Миграция перестаёт быть «большим взрывом» и становится серией обычных релизов.
**Явная оговорка:** open-source архив `download.qt.io/archive/qt/5.15/` заканчивается на **5.15.2** (ноябрь 2020); 5.15.3+ — коммерческие. Поддержка OpenSSL 3 в ветке Qt5 появилась в 5.15.9 / в KDE-патчсете. То есть 5.15 покупает **API-де-риск, но не бамп OpenSSL** — OpenSSL 3 переезжает вместе с Qt6, как и зафиксировано в вводных.

**Шаг 2 — весь механический + семантический sweep на 5.15 (≈8–10 дней), шипится обычными релизами.**
20× `setMargin`, 3 живых `QDesktopWidget`-сайта, 17× `Qt::CTRL +` → `QKeyCombination`, `SkipEmptyParts`, `fromTime_t`, `setButtonText`, `QLibraryInfo::path`, и — ключевое — **11 переписываний `QRegExp` с тестами**. Сначала написать тесты: `test/chatlog/textformatter_test.cpp` уже покрывает `capturedRef`-сайт, а на `tabcompleter.cpp:69,71,79`, `setpassworddialog.cpp:91-94`, `chatmessage.cpp:306` тестов **нет вообще** — их надо добавить до правки, иначе изменения тихие. `enterEvent(QEnterEvent*)` и `QAction::menu<QMenu*>()` на 5.15 не выражаются — оставить под `#if QT_VERSION`.

**Шаг 3 — спайк сборки Qt6 + OpenSSL 3 на ветке, без кода (5–8 дней).** Цель одна: `.exe` слинковался и стартанул. `download_openssl.sh:25-26` → 3.x + `--libdir=lib` в `build_openssl.sh:30-35`; новый `build_qt_windows.sh` на CMake-configure + `-- -DOPENSSL_ROOT_DIR=/windows`; host-Qt6 стейдж; `ninja-build` в образ; `CMAKE_FIND_ROOT_PATH` += `/windows` в `buildscripts/toolchain/windows-x86_64-toolchain.cmake:7`; все переименования DLL из R3. Решение по snorenotify принимается здесь.

**Шаг 4 — Qt6-only код (2–3 дня):** `QNetworkConfigurationManager` (`core.cpp:315-318`), `enterEvent`, `QMenu::addAction` с shortcut, `QAction::menu<QMenu*>()`, `style.cpp:71-81` (веса шрифтов — с прогоном всех тем), `setHighDpiScaleFactorRoundingPolicy`, `grabWindow(0,…)`.

**Шаг 5 — QA (§5) и релиз.** Обратный порядок (сначала Qt6-сборка, потом код) не работает: до шага 3 нечем компилировать, а до шага 2 в спайке будут тонуть сотни ошибок, не относящихся к тулчейну.

## 5. Что должно быть верно до Windows-релиза

**Сборка**
- [x] `windows-build.yaml` зелёный на x86_64/Release — **сделано 15 авг** (run `31891747608`), но не «просто так»: прогон вскрыл, что сборка не собиралась с коммита `3f337be3` (audit A43 подставил `CRED_PERSIST_CURRENT_USER`, которой нет в Windows API), а после починки артефакт содержал **только zip** — ни установщика, ни контрольных сумм. Установщик собирался всегда, но называется `Khandaq-installer.exe` (`windows/qtox64.nsi:11`), а CI искал upstream-имя `setup-qtox.exe`; `|| true` и `if-no-files-found: warn` превращали это в тишину. Исправлено (`4bbca4f1`): верные имена, `SHA256SUMS.txt` пишется файлом и грузится, отсутствие установщика роняет шаг, `if-no-files-found: error`.
      **Урок для Qt6-миграции:** «зелёный прогон» здесь не равен «артефакт пригоден» — при переходе проверять содержимое артефакта, а не статус джобы.
- [ ] Проверка «нет лишних/недостающих DLL» (`windows/cross-compile/build.sh:217-232`) **проходит с обновлённым списком**, а не отключена.
- [ ] В бандле реально лежат и грузятся `libssl-3-x64.dll` / `libcrypto-3-x64.dll` (проверить `tasklist /m` на живом процессе, не по факту наличия файла).
- [ ] NSIS-инсталлятор ставится и удаляется (`windows/qtox64.nsi:249,265-268` — путь `libsnore-qt5` мёртв, если snore заменён).

**OpenSSL 3 — функциональные доказательства (в приложении TLS-кода нет, поэтому доказательств всего два)**
- [ ] `src/net/updatecheck.cpp:125-153`: запрос к `https://api.github.com/…` возвращает **непустой** `tag_name`. Отдельно зафиксировать, что Qt6 теперь следует редиректам — под Qt5 это молча давало пустой результат.
- [ ] SQLCipher (`download_sqlcipher.sh:20`, 4.5.0, линкуется `-lcrypto`): **открыть профиль и историю, созданные сборкой на 1.1.1w** — паролем и без; создать новый, перезапустить, открыть. Это единственный путь, где смена крипто-бэкенда может съесть пользовательские данные.

**Сеть / Tox**
- [ ] Холодный старт → online за разумное время; bootstrap-burst (`core.cpp:321`) отрабатывает.
- [ ] Выключить/включить сеть; **переключить Wi-Fi → Ethernet при сохранённом «online»** — переподключение работает. Это замена `QNetworkConfigurationManager`, автотестов нет ни одного.

**UI (то, что Qt6 ломает молча)**
- [ ] Все 12 тем (`style.cpp:90-99`) в светлом и тёмном: текст не «весь жирный»/«весь тонкий» — прямая проверка `font.weight() * 8`.
- [ ] Масштаб 100 / 125 / 150 / 175% — сравнение скриншотов с Qt5-сборкой; проверка на двух мониторах с разным DPR.
- [ ] Скриншот-тул: одиночный и мульти-монитор, 100% и 150% (`screenshotgrabber.cpp:227-232`).
- [ ] Tab-completion в группе с кириллическим и **арабским** ником (`tabcompleter.cpp:69` — `\w` в QRegularExpression Unicode-aware, в QRegExp был ASCII); индикатор силы пароля на не-ASCII пароле (`setpassworddialog.cpp:91-94`); цитирование `>` и `＞` (`chatmessage.cpp:306`).
- [ ] Все 4 локали переключаются, 49 `.ts` собраны host-овым `lrelease`; **RTL для ar** визуально цел.
- [ ] Уведомления: работают через замену **или** фича явно выключена и это записано в release notes.

**AV (регрессия только руками — тестов нет)**
- [ ] Энумерация аудио-устройств вход/выход (`avform.cpp:508,527`), 1:1 звонок, групповой звонок, видео через dshow, шаринг экрана (gdigrab), звуки уведомлений (`widget.cpp:1068-1085`).

**Регрессии кода**
- [ ] `test/` (chatlog, core, dbutility, model, net, persistence, platform, widget) зелёный под Qt6 — единственная дешёвая сетка под переписанные регулярки.
- [ ] Смайлпак грузится (`smileypack.cpp:206-238`, `QDomDocument`) — в т.ч. асинхронный путь через QtConcurrent.

## 6. Чего аудиты **не** определили (не догадки — честные пробелы)

1. **Существует ли Qt6-сборка snorenotify.** Build-аудит утверждает «есть пакет `LibsnoreQt6`» — это **не проверено**; пин `download_snore.sh:20` = v0.7.0 (2016), апстрим KDE дремлет. Требуется проверка сети до планирования шага 3.
2. **Факты уровня заголовков Qt6** — `QAction::menu<QMenu*>()` и наличие 5-аргументной `QMenu::addAction(..., QKeySequence)`. UI-аудит сам пометил оба как `[прогноз]`: локально стоит только `qt@5 5.15.19`, Qt6-заголовков ни у кого нет.
3. **Собирается ли SQLCipher 4.5.0 против OpenSSL 3 и читаются ли старые БД.** Не проверялось ни в каком виде; ставим в QA-чеклист как блокер.
4. **Переживает ли `-fno-exceptions` (`CMakeLists.txt:117`) заголовки QtConcurrent из Qt6** — `QtConcurrent::run` используется в 4 файлах (`camerasource.cpp:348`, `smileypack.cpp:113,352`, инклюды в `coreav.cpp:36`, `toxcall.cpp:28`). Отдельно: **`smileypack.cpp:113` и `:352` вызывают `QtConcurrent::run(this, &SmileyPack::load, …)` — перегрузка «объект + указатель на метод» в Qt6 удалена** (нужно `run(&SmileyPack::load, this, …)`). Этого сайта нет ни в одном из пяти аудитов.
5. **Реальное wall-clock образа с двумя сборками Qt6.** Сегодняшний бюджет — 350 минут на один проход Qt5 (`windows-build.yaml:15`). Влезет ли двойная сборка — неизвестно; возможно, потребуется кэш-слой или self-hosted runner.
6. **Соберётся ли Qt 5.15.2 текущим кросс-рецептом.** Ожидаемо да (qmake-configure жив всю ветку 5.15), но не проверено; это первый эксперимент шага 1 и он дешёвый.
7. **Linux и macOS вне оценки.** Живой CI ровно один (`.github/workflows/windows-build.yaml`; `khandaq-desktop/.github/…` в монорепо не запускается), i686 в нём тоже нет. Стоимость AppImage (`appimage/build.sh:79-82`), macOS (`brew --prefix qt@5`) и системных Qt5 в докерфайлах (`Dockerfile.ubuntu_lts:50` тянет `qt5-default`, удалённый из Ubuntu с 21.04 — образ, вероятно, уже сломан) **не измерена**.
8. **Wayland/Linux-специфика:** в Qt6 `grabWindow` на Wayland возвращает null — скриншот-тул там просто перестанет работать. Для Windows-релиза нерелевантно, для Linux — отдельный вопрос без ответа.

---

## ПОПРАВКИ ПОСЛЕ ПРОВЕРКИ РУКАМИ (11 авг 2026)

Синтез выше сделан агентами; ниже — то, что не подтвердилось при собственной проверке. **Читайте это до §4.**

**ОШИБКА В ШАГЕ 0: `util/include/util/compatiblerecursivemutex.h` УДАЛЯТЬ НЕЛЬЗЯ.** План предлагает его удалить — это сломает сборку немедленно. Заголовок живой и используется в: `src/core/core.h:34,305`, `core.cpp:39,359`, `coreav.{h,cpp}`, `corefile.{h,cpp}`, `src/persistence/settings.h:34,713`, `settings.cpp:38,64`, `offlinemsgengine.h:27,68`, `audio/src/backend/{alsink,alsource,openal}.h`. Это шим для Qt < 5.14 (`QRecursiveMutex` появился в 5.14): под 5.15/Qt6 он вырождается в псевдоним и его можно **упростить**, но удаление — только вместе с заменой всех использований.

**Уточнённые счётчики мёртвых инклюдов** (план говорил «6 и 6»):
- `#include <QDesktopWidget>` — 9 файлов, из них **5 мёртвых** (`nexus.cpp`, `video/cameradevice.cpp`, `widget/widget.cpp`, `widget/form/settings/avform.cpp`, `chatlog/content/filetransferwidget.cpp`). Живые: `netcamview.cpp`, `imagepreviewwidget.cpp`, `userinterfaceform.cpp`, `screenshotgrabber.cpp`.
- `#include <QSignalMapper>` — **5 инклюдов + 2 forward-декларации** (`nexus.h:46`, `groupinviteform.h:33`), использований — **ноль**. Все мёртвые, как и говорил синтез.

**Сделано и проверено сборкой (эта часть шага 0 уже в репозитории):** удалены 5 мёртвых `QDesktopWidget`, 5 мёртвых `QSignalMapper` + 2 forward-декларации, мёртвый блок `execute_process(brew --prefix qt@5)` (переменная `QT_PREFIX_PATH` не читалась нигде и блок выполнялся даже там, где brew не существует), и `Qt5OpenGL` (`REQUIRED`, но `QOpenGL*`/`QGLWidget` — ноль вхождений).

**НЕ сделано намеренно, и почему:** `CMAKE_CXX_STANDARD 17` и `cmake_minimum_required 3.16` в шаге 0 помечены «нулевой риск» — это верно только для нативной сборки. Оба влияют на **кросс-сборку под Windows** (mingw/gcc, другой стандарт библиотеки, другой CMake в контейнере), а её здесь проверить нечем: локально доступен только macOS/clang. Их нужно вносить отдельным изменением и подтверждать прогоном `windows-build.yaml`, а не за компанию с чисткой мёртвого кода.

**Общий вывод про план:** оценка и порядок работ выглядят здраво, но каждый пункт «удалить X» нужно проверять `grep` перед исполнением — один из них был бы фатальным.

### Шаг 1 подготовлен: дайджест Qt 5.15.2 проверен (11 авг 2026)

Пин **не тронут** — его правка меняет Windows-образ, а проверить это можно только прогоном
`windows-build.yaml`. Но то, что можно было проверить заранее, проверено, чтобы бамп стал
однострочником с уже сверенным дайджестом, а не слепой правкой:

```
qt-everywhere-src-5.15.2.tar.xz   (586 690 220 байт)
md5    e1447db4f06c841d8947f0a6ce83a7b5   ← совпал с официальным
                                            download.qt.io/archive/qt/5.15/5.15.2/single/md5sums.txt
sha256 3a530d1b243b5dec00bc54937455471aaa3e56849d2593edb8ded07228202240   ← посчитан локально
```

Две независимые проверки: Qt публикует только md5, а `download_verify_extract_tarball` требует
sha256 — поэтому тарбол скачан целиком, md5 сверен с опубликованным, sha256 посчитан из тех же
байт. Имя файла в 5.15 то же, что в 5.12 (`qt-everywhere-src-<ver>.tar.xz`), так что шаблон URL
в `download_qt.sh:27-29` менять не нужно.

Правка, когда будет чем проверить (`buildscripts/download/download_qt.sh:20-23`):

```sh
QT_MINOR=15
QT_PATCH=2
QT_HASH=3a530d1b243b5dec00bc54937455471aaa3e56849d2593edb8ded07228202240
```

**Что проверить в том же прогоне, а не после:** список `-skip` в `build_qt_windows.sh:32-67`
писался под 5.12 — между 5.12 и 5.15 состав модулей менялся, и `-skip` несуществующего модуля
configure не прощает.
