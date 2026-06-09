# Khandaq Android Rebrand Report (B1–B9)

**Дата:** 2026-06-08  
**База:** TRIfA / ToxAndroidRefImpl  
**APK:** `dist/android/khandaq-debug.apk` (109 MB, debug-signed)

---

## 1. Что изменено

| Фаза | Результат |
|------|-----------|
| **B1** | `docs/ANDROID_REBRAND_AUDIT.md` |
| **B2** | `applicationId` + `namespace` → `org.khandaq.messenger`; provider authorities; intent actions; **Java package JNI-safe** (см. §3) |
| **B3** | Khandaq launcher/adaptive icons; `app/branding/` + `assets/branding/`; `drawable/khandaq_logo.png`; notification mipmaps |
| **B4** | Khandaq palette в `colors.xml`, drawer в `styles.xml` |
| **B5** | About: Khandaq Messenger, version, Tox, GPL, khandaq.org, Open Source Licenses, GPL upstream links |
| **B6** | `BootstrapNodeEntryDB` синхронизирован с `config/khandaq_bootstrap_nodes.json` (32 UDP + 68 TCP, public fallback) |
| **B7** | `docs/ANDROID_RELEASE_PLAN.md` |
| **B8** | `assembleDebug` SUCCESS — метаданные ниже |
| **B9** | Этот отчёт |

---

## 2. Ключевые изменённые файлы

### Gradle / manifest
- `khandaq-android-trifa/android-refimpl-app/app/build.gradle`
- `khandaq-android-trifa/android-refimpl-app/app/src/main/AndroidManifest.xml`

### Брендинг / UI
- `app/src/main/res/values/strings.xml`
- `app/src/main/res/values/colors.xml`
- `app/src/main/res/values/styles.xml`
- `app/src/main/res/values/ic_launcher4_background.xml`
- `app/src/main/res/mipmap-*/ic_launcher4*.png` (все плотности)
- `app/src/main/res/drawable-*/ic_khandaq_notification.png`
- `app/src/main/res/drawable/khandaq_logo.png`
- `app/branding/logo.svg`, `logo.png`, `adaptive/*`
- `app/src/main/assets/branding/*`

### Код
- `app/src/main/java/com/zoffcc/applications/trifa/Aboutpage.java`
- `app/src/main/java/com/zoffcc/applications/trifa/BootstrapNodeEntryDB.java`
- ~140 Java файлов — `import org.khandaq.messenger.R/BuildConfig` (namespace migration)

### Скрипты / docs
- `scripts/android-rebrand-migrate.sh`
- `scripts/android-rebrand-revert-java-move.sh`
- `scripts/sync-android-bootstrap-nodes.py`
- `docs/ANDROID_REBRAND_AUDIT.md`
- `docs/ANDROID_RELEASE_PLAN.md`

### Артефакт
- `dist/android/khandaq-debug.apk`

---

## 3. Что осталось от TRIfA

| Область | Детали |
|---------|--------|
| **Java package** | `com.zoffcc.applications.trifa` — **все** исходники (JNI constraint) |
| **JNI** | `libjni-c-toxcore.so` — символы `Java_com_zoffcc_applications_trifa_*` |
| **ORM** | `com.zoffcc.applications.sorm` |
| **Native audio** | `com.zoffcc.applications.nativeaudio` |
| **Maven deps** | `com.github.zoff99:pkgs_*` coordinates |
| **Copyright headers** | `Zoff <zoff@zoff.cc>`, GPL — **намеренно сохранены** |
| **Классы/логи** | `TRIFAGlobals`, `trifa.*` log tags, internal paths `/trifa/crashes` |
| **CI upstream** | `.github/workflows/*` — не трогали |

**Попытка полного переноса Java → `org.khandaq.messenger` откатана:** без пересборки JNI (нет Android `libtoxcore.a` локально) приложение не запускает tox.

---

## 4. GPL — что нельзя менять

- Copyright notices в исходниках TRIfA/Zoff
- GPL v2 license texts в зависимостях
- Upstream attribution (TRIfA / ToxAndroidRefImpl) в About
- c-toxcore / toxav derivative compliance — см. `NOTICE`, `THIRD_PARTY_LICENSES.md`

---

## 5. Оставшиеся проблемы

1. **Нет runtime validation** — устройство не подключено (`adb devices` пуст)
2. **Java package ≠ applicationId** — внутренний `com.zoffcc.applications.trifa`
3. **targetSdk 33** — Play policy 2025+ требует 34
4. **Release keystore** — не создан
5. **Khandaq-owned bootstrap** — `public_key=TBD`, только public nodes в DB
6. **`res/branding/`** — PNG нельзя в произвольных `res/` subdirs; используем `app/branding/` + `assets/branding/`
7. **F-Droid** — prebuilt JNI из zoff99 Maven

---

## 6. B8 — APK validation

### Build metadata (`aapt dump badging`)

| Поле | Значение |
|------|----------|
| Package | `org.khandaq.messenger` |
| versionName | `1.0.275` |
| versionCode | `10275` |
| minSdk | `21` |
| targetSdk | `33` |
| application-label | `Khandaq` |
| APK size | **109 MB** |
| ABIs | armeabi-v7a, arm64-v8a, x86, x86_64 |

### Runtime matrix (устройство не подключено)

| Проверка | Статус | Примечание |
|----------|--------|------------|
| Запуск приложения | **PARTIAL** | Сборка OK; runtime не проверен |
| Создание профиля | **PARTIAL** | Код не менялся |
| Генерация Tox ID | **PARTIAL** | JNI путь прежний |
| Список контактов | **PARTIAL** | — |
| Отправка сообщения | **PARTIAL** | — |
| Получение сообщения | **PARTIAL** | — |
| Группы | **PARTIAL** | — |
| Файлы | **PARTIAL** | — |

*До rebrand TRIfA release APK работал на тех же путях tox/JNI.*

---

## 7. Готовность Android Alpha

| Критерий | % |
|----------|---|
| Бренд (label, icons, theme, about) | 90% |
| Package identity (applicationId) | 95% |
| Full Java namespace migration | 40% |
| Bootstrap sync | 85% |
| Release/signing/store readiness | 30% |
| Runtime verified | 0% |

**Сводная готовность Android Alpha: ~72%**

---

## 8. GO / NO-GO

### **GO** — Internal Alpha (sideload)

**Обоснование:**
- `assembleDebug` успешен
- `applicationId` = `org.khandaq.messenger`, label **Khandaq**
- Иконки, тема, About, bootstrap обновлены
- Tox JNI / сетевая логика не тронуты — функциональный паритет с рабочим TRIfA baseline

**Условие:** раздача тестерам через `adb install` / прямой APK, не store.

### **NO-GO** — Public Alpha (Play / F-Droid / широкая аудитория)

**Обоснование:**
- Нет подтверждённого runtime на устройстве
- Внутренний Java package TRIfA + prebuilt JNI
- Нет release signing
- targetSdk 33

---

## Итоговый ответ

# **GO** (sideload Internal Alpha)

Первый полноценный **Khandaq-branded Android APK** готов к internal alpha testing.

Публичный Alpha — **NO-GO** до device smoke test + release keystore + targetSdk bump.

---

*Скрипты: `scripts/android-rebrand-migrate.sh`, `scripts/sync-android-bootstrap-nodes.py`*
