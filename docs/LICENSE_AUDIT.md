# Khandaq — GPL / License Audit

**Дата:** 2026-06-08  
**Статус:** аудит только, без изменений

---

## 1. Лицензии по компонентам

| Компонент | Лицензия | Файл | Примечание |
|-----------|----------|------|------------|
| khandaq-desktop | GPL-3.0-or-later | `khandaq-desktop/LICENSE` | Форк qTox, GPL headers сохранены |
| khandaq-desktop (SPDX в cmake) | GPL-3.0-or-later + MIT | `khandaq-desktop/LICENSES/` | `GPL-3.0-or-later.txt`, `MIT.txt` |
| c-toxcore (dep) | GPL-3.0 | `buildscripts/toxcore/LICENSE` | Статическая/динамическая линковка |
| toxext / toxext_messages | GPL-3.0 | `buildscripts/toxext*/LICENSE` | Расширения сообщений |
| Smileys (EmojiOne, Universe) | Отдельные | `smileys/*/LICENSE*` | Не GPL — проверить совместимость при дистрибуции |
| DejaVu Sans font | Bitstream Vera / Arev | `res/font/LICENSE` | Встроен в бинарник |
| khandaq-android (aTox) | GPL-3.0 | `khandaq-android/LICENSE` | Upstream evilcorpltd/aTox |
| khandaq-android-trifa (TRIfA) | GPL-2 / GPL-3 | `LICENSE-GPLv3`, `LICENSE-zzGPLv2` | Dual licensing в репо |
| khandaq-ios (Antidote) | MPL-2.0 | `khandaq-ios/LICENSE` | **Не GPL** — отдельная правовая модель |
| WebRTC (в TRIfA native-audio) | BSD-style | `LICENSE_THIRD_PARTY` | Транзитивная зависимость |

---

## 2. GPL compliance — desktop (Khandaq)

### Соблюдается

- GPL-3.0 текст в `LICENSE`
- Copyright blocks qTox Project Contributors в исходниках (~200+ файлов)
- About → GPL notice + attribution qTox (`aboutsettings.ui`)
- `res/org.khandaq.messenger.appdata.xml` → «Based on qTox»

### Не соблюдается / неполно для публичного релиза

| Проблема | Severity | Действие перед релизом |
|----------|----------|------------------------|
| Нет единого `NOTICE` / `THIRD_PARTY_LICENSES` в корне репо | High | Сгенерировать из deps (Qt, FFmpeg, OpenAL, SQLCipher, toxcore, smileys) |
| Нет SPDX в modified files (только upstream headers) | Medium | Добавить блок «Modified by Khandaq Project» в changed files или `COPYING.md` |
| Старый `io.github.qtox.qTox.appdata.xml` + `.desktop` не удалены | Medium | Удалить или пометить deprecated; не публиковать в релизе |
| Windows zip содержит GPL DLL без accompanying source offer | High | README + link to source + offer written; или installer с license page |
| `dist/*` артефакты без source tarball | High | Публиковать `khandaq-desktop` source tag вместе с бинарниками |
| Smileys EmojiOne — отдельная лицензия | Medium | Включить в NOTICE; проверить redistribution |
| macOS `.app` — нет GPL notice в GUI beyond About | Low | Достаточно если About + website source link |

---

## 3. GPL compliance — mobile (as-is upstream)

### Android TRIfA / aTox

- GPL-2/3 — при дистрибуции APK обязателен source offer
- Текущие APK **не брендированы Khandaq** — юридически это TRIfA/aTox, не производная Khandaq
- TRIfA release APK подписан debug keystore (не для production)

### iOS Antidote

- **MPL-2.0**, не GPL — смешивание с GPL desktop в одном «продукте Khandaq» требует юридической оценки
- Firebase Messaging (Google) — отдельные ToS
- Unsigned IPA — не для App Store без signing

---

## 4. NOTICE файлы — текущее состояние

| Путь | Есть |
|------|------|
| `khandaq-desktop/LICENSE` | ✅ |
| `khandaq-desktop/LICENSES/GPL-3.0-or-later.txt` | ✅ |
| `khandaq-desktop/LICENSES/MIT.txt` | ✅ |
| Root `LICENSES/` | ❌ |
| Root `NOTICE` | ❌ |
| `THIRD_PARTY_LICENSES.md` | ❌ |
| Per-dep licenses в dist | ❌ |

---

## 5. Чеклист перед публичным релизом

1. Опубликовать git tag с полным source `khandaq-desktop`
2. Создать `NOTICE` + `THIRD_PARTY_LICENSES.md` (toxcore, Qt, FFmpeg, OpenAL, SQLCipher, qrencode, libvpx, smileys)
3. Удалить/исключить из release: `io.github.qtox.qTox.desktop`, `io.github.qtox.qTox.appdata.xml`
4. Windows installer: страница лицензии + ссылка на source
5. macOS: `Info.plist` — убрать `qtox_profile` UTI или документировать совместимость
6. Переводы: обновить `lupdate` после смены source strings (GPL не требует, но UX)
7. Mobile: не выдавать upstream APK/IPA за «Khandaq» без rebrand + отдельного license review (MPL vs GPL)
8. CI: SBOM или `license-check` step

---

## 6. Риски

- **GPL violation** при публикации бинарников без source — критично
- **MPL + GPL** в едином бренде Khandaq — нужна стратегия (отдельные репо/продукты)
- **Upstream unmaintained qTox** — security fixes не upstreamятся в ваш fork автоматически
