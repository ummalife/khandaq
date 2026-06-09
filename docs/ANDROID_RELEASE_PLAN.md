# Android Release Plan — Khandaq Messenger

**Дата:** 2026-06-08  
**Package:** `org.khandaq.messenger`  
**Статус:** Подготовка (не публиковать до Alpha sign-off)

---

## Build variants

| Variant | Gradle task | Output | Назначение |
|---------|-------------|--------|------------|
| **debug** | `assembleDebug` | `app/build/outputs/apk/debug/app-debug.apk` | Dev, smoke tests, adb install |
| **release** | `assembleRelease` | `app/build/outputs/apk/release/app-release-unsigned.apk` | Alpha/RC, store submission |

**Сборка:**
```bash
cd khandaq-android-trifa/android-refimpl-app
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew assembleDebug    # или assembleRelease
```

**Khandaq dist copy (debug):** `dist/android/khandaq-debug.apk`

---

## Debug

- Debug keystore (Android default `~/.android/debug.keystore`)
- `BuildConfig.BUILD_TYPE` = `debug`
- Native libs: prebuilt `libjni-c-toxcore.so` из `pkgs_ToxAndroidRefImpl`
- ProGuard/minify: **off**
- Подходит для Alpha internal testing

---

## Release

- Требуется **release keystore** (ещё не создан для Khandaq)
- `assembleRelease` → unsigned APK → `apksigner sign`
- Рекомендуется:
  - `minifyEnabled false` (как upstream TRIfA — tox JNI surface)
  - `v1 + v2 + v3` signing
  - zipalign перед sign

**Signing requirements:**
1. Создать keystore: `keytool -genkey -v -keystore khandaq-release.keystore -alias khandaq -keyalg RSA -keysize 4096 -validity 10000`
2. Хранить в CI secrets / offline backup
3. `apksigner sign --ks khandaq-release.keystore --out khandaq-release.apk app-release-unsigned.apk`
4. **Не** коммитить keystore в git

---

## Play Store requirements (будущее)

| Требование | Статус |
|------------|--------|
| `applicationId` уникальный | ✅ `org.khandaq.messenger` |
| Target SDK ≥ 34 (2025 policy) | ⚠️ сейчас 33 — bump перед production |
| Privacy policy URL | ⬜ `https://khandaq.org/privacy` |
| Data safety form | ⬜ Tox P2P, no cloud |
| App label / icons Khandaq | ✅ |
| GPL source offer | ⬜ Play + website link to source |
| 64-bit native libs | ✅ arm64-v8a |
| Content rating questionnaire | ⬜ |

---

## F-Droid requirements

| Требование | Статус |
|------------|--------|
| GPL-2.0+ compliance | ✅ headers preserved |
| Reproducible build (опционально) | ⬜ JNI prebuilt — документировать |
| `org.khandaq.messenger` | ✅ |
| Anti-features (Network, Microphone, …) | ⬜ metadata |
| Fastlane / metadata Khandaq | ⬜ обновить fastlane |
| No proprietary deps blocking | ⚠️ `zoff99` Maven JNI blobs — F-Droid может потребовать build from source |
| Bootstrap nodes documented | ✅ `config/khandaq_bootstrap_nodes.json` |

---

## Pre-release checklist

- [ ] Bump `targetSdk` to 34+
- [ ] Release keystore + signed APK
- [ ] Runtime matrix on physical device (B8)
- [ ] Store screenshots Khandaq branding
- [ ] Update `versionName` / `versionCode` for Alpha tag
- [ ] JNI rebuild pipeline (optional, для полного `org.khandaq.messenger` Java package)

---

*Не публиковать до GO из `KHANDAQ_ANDROID_REBRAND_REPORT.md`.*
