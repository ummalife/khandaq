# Building Khandaq from Source

## Prerequisites

| Platform | Requirements |
|----------|----------------|
| Linux desktop | Docker (recommended) or Qt 5.15+, CMake, FFmpeg dev libs |
| Windows | MinGW cross-toolchain (see `khandaq-desktop/windows/cross-compile/`) |
| macOS | Xcode, Qt via Homebrew, CMake |
| Android | JDK 17, Android SDK, NDK (see `scripts/build-android-trifa.sh`) |
| iOS | macOS, Xcode 15+, CocoaPods |

## Desktop — Linux (Docker, recommended)

```bash
./scripts/build-linux-docker.sh
```

Output: portable bundle under `dist/linux/` (gitignored; use for releases).

## Desktop — Windows (cross-compile)

```bash
./scripts/build-windows-cross.sh
```

Output: `dist/windows/x86_64/khandaq-x86_64-Release.zip`, installer `.exe`.

## Desktop — macOS

```bash
./scripts/build-macos.sh
```

Output: `dist/macos/khandaq.app`, `khandaq-macos.zip`.

## Android (TRIfA)

```bash
./scripts/build-android-trifa.sh
```

Release signing: copy `secrets/android-signing.env.example` → `secrets/android-signing.env` (never commit).

Output: `dist/android/khandaq-release.apk`.

## iOS (Antidote fork)

```bash
cd khandaq-ios
pod install
open Antidote.xcworkspace
```

Build with scheme **Antidote**, configuration **Release**.

TestFlight upload (maintainers only): `khandaq-ios/scripts/upload-testflight.sh` — requires App Store Connect API key in `~/.appstoreconnect/config` (not in repo).

## Verify Linux portable bundle

```bash
./scripts/verify-linux-build.sh
```

## Reproducibility notes

- `khandaq-ios/Pods/` is not committed — run `pod install` after clone.
- Android and desktop builds download dependencies on first compile.
- Exact compiler versions are pinned in Docker scripts where possible.
