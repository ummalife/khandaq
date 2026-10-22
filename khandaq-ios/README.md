# Khandaq for iOS

The iOS client of [Khandaq Messenger](https://khandaq.org): private, peer-to-peer messaging on the open
Tox network. There are no accounts, phone numbers or message servers — the identity is a key pair
created on the device, and contacts are added by Tox ID or QR code.

## Built on Antidote

This client started from [Antidote](https://github.com/Zoxcore/Antidote), the open-source Tox client for
iOS, and keeps its Mozilla Public License 2.0. Since June 2026 Khandaq has added:

- group chats on Tox NGC — member list, @mentions, private messages to a member, group voice messages,
  inviting several contacts at once;
- replies with quotes, edit and delete for both sides and reactions — Khandaq extensions to the Tox
  message protocol, shared with Khandaq for Android;
- pinned messages, forwarding and Saved Messages;
- voice messages, a media gallery, captions and a photo editor before sending;
- a share extension for sending photos, files and text from other apps;
- signed group history sync;
- a call audio engine with echo cancellation and automatic gain control;
- the Khandaq push wake relay, which carries no message content;
- a delivery watchdog, reconnect back-off, network diagnostics and launch recovery;
- a new design for every screen and a first-launch onboarding.

Notable changes per release are in the project changelog: [khandaq.org/changelog.html](https://khandaq.org/changelog.html).

## Usage


#### Manual Installation

Clone repo, build the vendored native libraries, install pods, then open `Antidote.xcworkspace`
with Xcode 12+.

`libopus.xcframework/*/libopus.a` is not in git (`.gitignore` excludes `*.a`), so a clean checkout
links with `ld: library 'opus' not found` until the script below has been run once. vpx's binaries
are committed, but `vpx` is the simulator slice — a device build needs `vpx-device` copied over it,
which the script also handles.

```
./scripts/build-ios-native-deps.sh
cd khandaq-ios
bundle install
bundle exec pod install
open Antidote.xcworkspace
```

#### Compile on the Commandline
Clone repo, install pods and install Xcode 12+

```
cd khandaq-ios
bundle install
bundle exec pod install
env NSUnbufferedIO=YES xcodebuild -workspace ./Antidote.xcworkspace -scheme Antidote -destination 'platform=iOS Simulator,id=EAB9614F-3485-4A6D-8EFB-FC2B5EFB0243'
```

The Xcode project and module keep Antidote's names (`Antidote.xcworkspace`, scheme `Antidote`) so that
upstream fixes can still be merged; the app itself is Khandaq.

## Reporting problems

Write to support@khandaq.org. If you attach a crash report
([how to get one](https://developer.apple.com/documentation/xcode/acquiring-crash-reports-and-diagnostic-logs)),
check first that it contains no private data.

## License

Khandaq for iOS is available under the Mozilla Public License 2.0, like Antidote, on which it is based.
See the [LICENSE](LICENSE) file for more info. The history of the upstream project up to Antidote 1.4.25
is kept in [CHANGELOG.md](CHANGELOG.md).

## Links

- [icons8](http://icons8.com/) - icons used in app
- new icon https://icons8.com/icon/jQvC2IpxYkR6/key
- [Material Symbols](https://github.com/google/material-design-icons) (Apache 2.0) - chat header, tab bar and message action icons
