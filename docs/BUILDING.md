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

**Build the vendored native libraries first.** `.gitignore` excludes `*.a`, so
`libopus.xcframework/*/libopus.a` is NOT in the repository — only its headers and `Info.plist` are.
libopus is a `vendored_frameworks` entry, so `pod install` succeeds and the omission surfaces only at
link time as `ld: library 'opus' not found`. Anyone who has built before has it lying around from an
earlier run; a clean checkout does not (audit 2026-08-21, found by the first run of
`.github/workflows/ios-build.yml`).

`ios/vpx.framework`'s binaries *are* committed — but note that `vpx` is byte-identical to
`vpx-simulator`, so a **device** build from a clean checkout links the simulator slice until
`vpx-device` is copied over it. The script below rebuilds both and leaves the simulator slice active.

```bash
./scripts/build-ios-native-deps.sh     # libopus + libvpx; needs Xcode CLI tools. Once per checkout.
cd khandaq-ios
bundle install
bundle exec pod install
open Antidote.xcworkspace
```

Build with scheme **Antidote**, configuration **Release**.

TestFlight upload (maintainers only): `khandaq-ios/scripts/upload-testflight.sh` — requires App Store Connect API key in `~/.appstoreconnect/config` (not in repo).

## Verify Linux portable bundle

```bash
./scripts/verify-linux-build.sh
```

## Reproducibility notes

- `khandaq-ios/Pods/` is not committed — run `bundle exec pod install` after clone.
  Always go through Bundler: the committed `Gemfile.lock` pins CocoaPods 1.16.2 and fastlane
  2.237.0, and a bare `pod` uses whatever Homebrew put on the machine instead (audit K-07).
  Needs Ruby >= 3.1, which is what `Gemfile.lock`'s activesupport 7.2.x requires.
- Android and desktop builds download dependencies on first compile.
- Exact compiler versions are pinned in Docker scripts where possible.
