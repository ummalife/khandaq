# Khandaq — Mobile Build (Фаза 7–8, as-is)

Пока **без ребрендинга** — собираем upstream Tox-клиенты как есть.

## Android

| Клиент | Источник | Статус upstream | Артефакт |
|--------|----------|-----------------|----------|
| **TRIfA** | `khandaq-android-trifa/` | Active | `dist/android/trifa-release.apk` |
| **aTox** | `khandaq-android/` | Unmaintained | `dist/android/atox-v0.8.0.apk` (release) |

### TRIfA (сборка из исходников)

```bash
git clone --depth 1 https://github.com/zoff99/ToxAndroidRefImpl.git khandaq-android-trifa
./scripts/build-android-trifa.sh
```

Требует: `ANDROID_HOME`, JDK, Gradle wrapper в `android-refimpl-app/`.

### aTox (официальный release APK)

Сборка из исходников на macOS не поддерживается (нет `Darwin.mk`, нужны sbt + tox4j + NDK в Linux).

```bash
git clone --depth 1 https://github.com/evilcorpltd/aTox.git khandaq-android
./scripts/fetch-android-atox.sh
```

## iOS

| Клиент | Источник | Артефакт |
|--------|----------|----------|
| **Antidote** | `khandaq-ios/` (Zoxcore fork) | `dist/ios/Antidote-simulator.zip` |

```bash
git clone --depth 1 -b develop https://github.com/Zoxcore/Antidote.git khandaq-ios
./scripts/build-ios.sh          # simulator .app (может падать на Xcode 15+)
./scripts/fetch-ios-antidote.sh # upstream unsigned IPA — надёжнее «как есть»
```

Артефакт IPA: `dist/ios/antidote-v1.4.28-unsigned.ipa`

Требует: Xcode, CocoaPods для source build. Device install — подписать IPA своим сертификатом.

## Проверка Android

```bash
adb install dist/android/trifa-release.apk
# или
adb install dist/android/atox-v0.8.0.apk
```

## Следующий шаг (ребренд)

- Package id: `org.khandaq.messenger`
- Иконки: `khandaq-desktop/resources/khandaq/`
- Bootstrap: `khandaq-desktop/res/nodes.json`
