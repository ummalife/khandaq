# Third-Party Licenses — Khandaq

**Product:** Khandaq Messenger (Alpha)  
**See also:** `NOTICE`, `docs/RELEASE_COMPLIANCE.md`

---

## Desktop (khandaq-desktop)

> **Versions live in `khandaq-desktop/buildscripts/bundled-deps.json`, not here.** This table has no
> version column on purpose: a hand-maintained one drifts (the header of `windows-build.yaml` claimed
> libvpx 1.14.0 while the build pinned 1.14.1). The inventory is checked against the real
> `download_*.sh` pins by `scripts/check-bundled-deps-eol.py`, which also emits the CycloneDX SBOM
> shipped with releases. `python3 scripts/check-bundled-deps-eol.py --print-markdown` prints the
> current versioned table.

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
| OpenSSL | Apache-2.0 | Built directly by `buildscripts/build_openssl.sh` for Windows and macOS — not "via toxcore" |
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

## Fonts (all clients and the site)

| Component | License | Copies |
|-----------|---------|--------|
| IBM Plex Sans, IBM Plex Sans Arabic, IBM Plex Mono (Copyright 2017 IBM Corp., Reserved Font Name "Plex") | SIL Open Font License 1.1 | `design/system/fonts/`, `khandaq-desktop/res/font/plex/`, Android `app/src/main/res/font/ibm_plex_*.ttf`, iOS `khandaq-ios/Antidote/Fonts/`, site `web/assets/fonts/ibm-plex-*.woff2` |

The license text ships next to the fonts in `design/system/fonts/OFL.txt`,
`khandaq-desktop/res/font/plex/OFL.txt` and `web/assets/fonts/IBM-Plex-OFL.txt`, and the iOS app shows
it in `antidote-acknowledgements.html`. Every font file also carries the copyright and the license in
its `name` table (IDs 0, 13 and 14); that is what travels with the Android copies, because `res/font/`
accepts font files only.

---

## Source offers (GPL)

Binary distributions of GPL components must include or offer corresponding source:

- Khandaq desktop: tag-matched source tarball (`scripts/package-release-source.sh`)
- TRIfA APK: source at TRIfA upstream + Khandaq fork tag
- Antidote IPA: MPL source offer per MPL-2.0 section 3
