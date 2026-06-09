# Third-Party Licenses — Khandaq

**Product:** Khandaq Messenger (Alpha)  
**See also:** `NOTICE`, `docs/RELEASE_COMPLIANCE.md`

---

## Desktop (khandaq-desktop)

| Component | License | Notes |
|-----------|---------|-------|
| qTox (base) | GPL-3.0-or-later | `khandaq-desktop/LICENSE` |
| c-toxcore | GPL-3.0 | Static/dynamic link |
| Qt 5 | LGPL-3.0 / GPL-3.0 | Dynamic on Linux/Windows; macOS deployment |
| FFmpeg | LGPL-2.1+ / GPL-2+ | Video decode/encode |
| OpenAL Soft | LGPL-2.0+ | Audio playback |
| SQLCipher | BSD-style (Zetetic) | Encrypted chat DB |
| libvpx | BSD-3-Clause | VP8/VP9 |
| libopus | BSD-3-Clause | Audio codec |
| libsodium | ISC | Via toxcore |
| OpenSSL | Apache-2.0 | Via toxcore build |
| libqrencode | LGPL-2.1+ | QR codes |
| libexif | LGPL-2.1+ | EXIF metadata |
| Hunspell | LGPL-2.1+ / GPL-2+ | Optional spell check |
| SnoreToast / libsnore | LGPL-2.1+ | Windows notifications (optional) |
| EmojiOne / smiley packs | Custom | See `smileys/*/LICENSE*` |
| DejaVu Sans | Bitstream Vera / Arev | `res/font/LICENSE` |
| toxext, tox_extension_messages | GPL-3.0 | Message extensions |

Full SPDX: `khandaq-desktop/LICENSES/`

---

## Android TRIfA (khandaq-android-trifa)

| Component | License |
|-----------|---------|
| TRIfA | GPL-2.0 / GPL-3.0 (`LICENSE-GPLv3`, `LICENSE-zzGPLv2`) |
| c-toxcore (JNI) | GPL-3.0 |
| SQLCipher | BSD-style |
| WebRTC (native-audio) | BSD-style | `LICENSE_THIRD_PARTY` |

---

## Android aTox (khandaq-android) — upstream snapshot, not primary

| Component | License |
|-----------|---------|
| aTox | GPL-3.0 |
| tox4j | GPL-3.0 |

---

## iOS Antidote (khandaq-ios)

| Component | License |
|-----------|---------|
| Antidote | MPL-2.0 |
| objcTox / toxcore pods | GPL-3.0 (transitive) |
| Realm | Apache-2.0 |
| Firebase | Google ToS (optional dep) |
| CocoaPods dependencies | Various — see `Pods/` and `antidote-acknowledgements.html` |

**Note:** MPL-2.0 iOS client is a separate legal product from GPL desktop.

---

## Bootstrap / Infrastructure

| Component | License |
|-----------|---------|
| tox-bootstrapd | GPL-3.0 (c-toxcore) |
| Docker base images | Per image license |

---

## Source offers (GPL)

Binary distributions of GPL components must include or offer corresponding source:

- Khandaq desktop: tag-matched source tarball (`scripts/package-release-source.sh`)
- TRIfA APK: source at TRIfA upstream + Khandaq fork tag
- Antidote IPA: MPL source offer per MPL-2.0 section 3
