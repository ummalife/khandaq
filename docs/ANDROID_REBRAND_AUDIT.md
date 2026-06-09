# Android Rebrand Audit — Khandaq Messenger (Phase B1)

**Дата:** 2026-06-08  
**База:** `khandaq-android-trifa/` (TRIfA / ToxAndroidRefImpl)  
**Статус:** Аудит до изменений. ~375 файлов содержат TRIfA/zoffcc-ссылки (~4977 вхождений).

---

## Текущая идентичность

| Параметр | Значение |
|----------|----------|
| Application label | `TRIfA` |
| `applicationId` | `com.zoffcc.applications.trifa` |
| `namespace` | `com.zoffcc.applications.trifa` |
| Java package (app) | `com.zoffcc.applications.trifa` (~144 файла) |
| `versionName` | `1.0.275` |
| `versionCode` | `10275` |
| `minSdk` | 21 |
| `targetSdk` | 33 |
| `compileSdk` | 34 |

---

## 1. Пользовательские (user-facing)

### App label и строки
- `app/src/main/res/values/strings.xml` — `app_name` = **TRIfA**, десятки строк с «TRIfA» (уведомления, батарея, share intent, About).
- `AndroidManifest.xml` — `android:label="TRIfA"`.
- Launcher icons: `ic_launcher4*` (TRIfA-иконка, mipmap mdpi–xxxhdpi).

### About / support
- `Aboutpage.java` — github `zoff99/ToxAndroidRefImpl`, ссылка `https://tox.zoff.cc`.
- `strings.xml` — `Aboutpage_5a` = «TRIfA a Tox Client for Android».

### Store metadata (не в APK, но бренд)
- `khandaq-android-trifa/fastlane/metadata/android/` — TRIfA titles/descriptions (если присутствует в форке).

### Email / URL (в коде и строках)
- `zoff@zoff.cc` — copyright headers (все Java-файлы).
- `https://github.com/zoff99/ToxAndroidRefImpl` — About, README, CI.
- `https://tox.zoff.cc` — About FAQ link.
- `nodes.tox.chat` — bootstrap (публичная сеть, оставить).

---

## 2. Технические

### Gradle
- `android-refimpl-app/app/build.gradle` — `applicationId`, `namespace`, exclude paths `com/zoffcc/applications/trifa/`.

### AndroidManifest
- Provider authorities:
  - `com.zoffcc.applications.trifa.std_fileprovider`
  - `com.zoffcc.applications.trifa.ext2_provider`
  - `com.zoffcc.applications.trifa.ext1_fileprovider`
- Custom intent actions:
  - `com.zoffcc.applications.trifa.TOXSERVICE_ALARM`
  - `com.zoffcc.applications.trifa.EXTERN_RECV`
  - `com.zoffcc.applications.trifa.TOKEN_CHANGED`
- Activities/services — относительные имена `.MainActivity`, `.TrifaToxService` (резолвятся через namespace).

### Java / Kotlin
- `app/src/main/java/com/zoffcc/applications/trifa/` — 144 файла.
- Смежные пакеты (не трогать без необходимости):
  - `com.zoffcc.applications.sorm` — ORM (sorma2 generated)
  - `com.zoffcc.applications.nativeaudio` — native-audio-jni module
  - `com.zoffcc.applications.logging` / `loggingstdout`
  - `me.jagar.*`, `org.secuso.*` — vendored libs с import trifa

### JNI — критический блокер полной миграции пакета
- `jni-c-toxcore/jni-c-toxcore.c` — ~191 символов `Java_com_zoffcc_applications_trifa_*`.
- `FindClass`: `com/zoffcc/applications/trifa/TrifaToxService`, `com/zoffcc/applications/trifa/MainActivity`.
- `libjni-c-toxcore.so` — prebuilt в `com.github.zoff99:pkgs_ToxAndroidRefImpl:1.0.175` (AAR).
- **Пересборка JNI** требует Android NDK toolchain + static libs (`libtoxcore.a`, …) — отсутствуют локально.

**План B2 (JNI-safe):** оставить `MainActivity.java` + `TrifaToxService.java` в `com.zoffcc.applications.trifa`, публичный launcher — `org.khandaq.messenger.MainActivity extends …`.

### Layouts / XML
- 15+ layout-файлов с FQCN `com.zoffcc.applications.trifa.*` custom views.
- `pref_headers.xml`, `file_paths.xml` — provider paths.

### Dependencies (Maven coordinates, не package)
- `com.github.zoff99:pkgs_ToxAndroidRefImpl:1.0.175`
- `com.github.zoff99:pkgs_guardianprojectIOCipher`
- `com.github.zoff99:pkgs_zoffccAndroidJDBC`
- `com.github.zoff99:pkgs_hotchemiPermissionsdispatcher`

### Bootstrap
- `BootstrapNodeEntryDB.java` — hardcoded UDP/TCP nodes (устаревший список vs `config/khandaq_bootstrap_nodes.json`).
- `TRIFAGlobals.TOX_NODELIST_HOST` — remote nodelist.

### Firebase
- `google-services.json` — **отсутствует** (push не настроен).

### Theme / colors
- `colors.xml` — Material Indigo/Pink (`#3F51B5`, `#FF4081`), не Khandaq palette.

### Логотип Khandaq (в монорепо)
- `khandaq-desktop/img/icons/khandaq.svg` — primary `#0F3D2E`, accent `#C9A227`, bg `#0B0F0E`.

---

## 3. Лицензионные

### GPL-2.0 headers (обязательно сохранить)
- Все Java/C файлы TRIfA: `Copyright (C) 2017–2022 Zoff <zoff@zoff.cc>`, GPL v2.
- **Нельзя удалять** copyright notices при rebrand (GPL §1).

### Upstream attribution
- TRIfA / ToxAndroidRefImpl — derivative work.
- c-toxcore, toxav — GPL.
- `NOTICE`, `THIRD_PARTY_LICENSES.md` в корне Khandaq уже покрывают desktop; Android должен ссылаться в About.

### Товарные знаки
- «TRIfA», «ZoffCC» — upstream branding; заменить в user-facing, оставить в copyright headers.

### F-Droid / Play compliance
- GPL source offer — через `https://khandaq.org` + repo.
- Anti-feature: зависимости на `zoff99` GitHub packages — технические, не user-facing.

---

## Матрица миграции (B2+)

| Элемент | Целевое значение | Риск |
|---------|------------------|------|
| `applicationId` | `org.khandaq.messenger` | Низкий |
| `namespace` | `org.khandaq.messenger` | Средний (R/BuildConfig imports) |
| Java package (bulk) | `org.khandaq.messenger` | Средний |
| JNI MainActivity/Service | Оставить `com.zoffcc.applications.trifa` | **Обязательно** без JNI rebuild |
| Provider authorities | `org.khandaq.messenger.*` | Низкий |
| App label / strings | Khandaq | Низкий |
| Icons / theme | Khandaq palette | Низкий |
| Bootstrap DB | Sync с `khandaq_bootstrap_nodes.json` | Низкий (additive) |

---

## Файлы с наибольшей концентрацией бренда

```
android-refimpl-app/app/src/main/res/values/strings.xml
android-refimpl-app/app/src/main/AndroidManifest.xml
android-refimpl-app/app/build.gradle
android-refimpl-app/app/src/main/java/com/zoffcc/applications/trifa/Aboutpage.java
android-refimpl-app/app/src/main/java/com/zoffcc/applications/trifa/MainActivity.java
jni-c-toxcore/jni-c-toxcore.c
khandaq-android-trifa/README.md
khandaq-android-trifa/.github/workflows/*.yml
```

---

*Phase B1 complete — no source changes in this document.*
