# Khandaq Android Smoke Test Report (C1–C6)

**Дата:** 2026-06-08  
**APK:** `dist/android/khandaq-debug.apk` (после manifest fix)  
**Устройство:** Emulator `Pixel_6a` (API 33, arm64-v8a) — физическое устройство не подключено  
**Логотип:** обновлён на `https://khandaq.org/themes/element/img/logos/element-logo.svg`

---

## Критический crash (обнаружен в C1, исправлен до повторного прогона)

### Симптом
Приложение падало сразу после `monkey` / launch. `pidof` пустой.

### Stacktrace
```
E/AndroidRuntime: FATAL EXCEPTION: main
E/AndroidRuntime: Process: org.khandaq.messenger, PID: 3642
E/AndroidRuntime: java.lang.RuntimeException: Unable to instantiate application org.khandaq.messenger.MainApplication
E/AndroidRuntime: Caused by: java.lang.ClassNotFoundException: Didn't find class "org.khandaq.messenger.MainApplication"
```

### Причина
`namespace` = `org.khandaq.messenger`, но Java-классы остались в `com.zoffcc.applications.trifa`.  
Относительные имена в `AndroidManifest.xml` (`android:name=".MainApplication"`) резолвились в несуществующий `org.khandaq.messenger.*`.

### Исправление (package migration, не архитектура)
Все `android:name=".*"` → `android:name="com.zoffcc.applications.trifa.*"` (57 компонентов).  
Файл: `app/src/main/AndroidManifest.xml`

### Повторный C1 после fix
- Install: **Success**
- Launch: **Success** (`pid=3662`)
- Экран: `SetPasswordActivity` (первый запуск)
- FATAL: **нет**

---

## Результаты по фазам

| Test | Result | Logs / notes | Screenshot |
|------|--------|--------------|------------|
| **C1** adb devices | **PASS** | `emulator-5554 device` | — |
| **C1** adb install -r | **PASS** | `Performing Streamed Install Success` | `docs/smoke-test-screenshots/c1-install.log` |
| **C1** monkey launch | **PASS** (после fix) | `Events injected: 1`, pid=3662 | `docs/smoke-test-screenshots/c1-monkey.log` |
| **C1** Иконка Khandaq на launcher | **PARTIAL** | Иконки перегенерированы из element-logo.svg; launcher cache на эмуляторе может показывать старую — нужен скрин home | — |
| **C1** Запуск без crash | **FAIL→PASS** | FAIL до manifest fix; PASS после | `docs/smoke-test-screenshots/c1-launch.png` |
| **C2** libjni-c-toxcore.so загружается | **PASS** | `successfully loaded jni-c-toxcore library` | `docs/smoke-test-logcat.txt` |
| **C2** native-audio-jni | **PASS** | `successfully loaded native-audio-jni library` | — |
| **C2** TrifaToxService стартует | **PARTIAL** | Сервис не стартовал на экране SetPassword (ожидаемо до завершения onboarding) | — |
| **C2** ClassNotFoundException | **PASS** (после fix) | Только в прогоне до manifest fix | `full-logcat.txt:1026-1049` |
| **C2** UnsatisfiedLinkError | **PASS** | Не обнаружено | — |
| **C2** Provider authority conflicts | **PASS** | Нет ошибок Provider | — |
| **C2** Crash loop | **PASS** | Нет повторных FATAL после fix | — |
| **C3** Создать профиль | **PARTIAL** | Достигнут `SetPasswordActivity`; SKIP не автоматизирован (неверные coords) | `c3-after-skip.png` |
| **C3** Tox ID / копирование | **NOT TESTED** | Требует завершить onboarding вручную | — |
| **C3** Перезапуск + сохранение профиля | **NOT TESTED** | — | — |
| **C3** Settings | **NOT TESTED** | — | — |
| **C3** About → Khandaq | **NOT TESTED** | — | — |
| **C4** Messaging Android ↔ Desktop | **NOT TESTED** | Нет второго Tox-клиента в среде теста | — |
| **C5** Files / groups | **NOT TESTED** | Зависит от C3/C4 | — |

---

## C2 — ключевые строки logcat (после fix)

```
I/trifa.MainApplication: MainApplication:onCreate
I/trifa.MainActivity: successfully loaded jni-c-toxcore library
I/trifa.MainActivity: successfully loaded native-audio-jni library
I/ActivityTaskManager: START ... SetPasswordActivity
```

Полный logcat: `docs/smoke-test-screenshots/full-logcat.txt` (4419 строк)  
Фильтр: `docs/smoke-test-logcat.txt` (257 строк)

---

## Логотип element-logo.svg

| Путь | Статус |
|------|--------|
| `app/branding/logo.svg` | ✅ скачан с khandaq.org |
| `app/src/main/assets/branding/logo.svg` | ✅ |
| `mipmap-*/ic_launcher4*.png` | ✅ перегенерированы |
| `drawable/khandaq_logo.png` | ✅ |
| `activity_set_password.xml` | ✅ обновлён на `khandaq_logo` (после smoke) |
| `activity_check_password.xml` | ✅ обновлён на `khandaq_logo` |

Старый `khandaq-desktop/img/icons/khandaq.svg` больше не используется для иконок.

---

## Скрипты

- `scripts/android-smoke-test.sh` — C1+C2
- `scripts/android-smoke-test-c3.sh` — частичный C3

---

## Вердикт: Android Internal Alpha

# **GO (conditional)**

**Обоснование GO:**
- После исправления manifest APK **устанавливается и запускается**
- **JNI загружается** — tox stack инициализируется на уровне native lib
- Нет ClassNotFound / UnsatisfiedLinkError после fix
- Бренд APK: `org.khandaq.messenger`, label **Khandaq**

**Условия (блокеры для полного GO):**
1. **Обязательно** включить manifest FQCN fix в сборку (уже в дереве)
2. **Ручной C3–C5** на физическом устройстве (профиль, Tox ID, About, messaging)
3. Пересобрать APK после правок password-screen logo (`assembleDebug`)
4. Подтвердить иконку launcher на реальном home screen

**NO-GO для расширенного alpha** без:
- Успешного C3 (Tox ID + persistence)
- C4 messaging smoke с Desktop
- Release-signed build

---

*Первый прогон C1: **NO-GO** (ClassNotFound MainApplication). Второй прогон: **GO conditional**.*
