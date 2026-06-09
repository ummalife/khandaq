# Khandaq — Release Compliance

**Версия:** 0.1.0-alpha  
**Дата:** 2026-06-08

---

## 1. Артефакты compliance

| Артефакт | Путь | Статус |
|----------|------|--------|
| NOTICE | `/NOTICE` | ✅ |
| Third-party licenses | `/THIRD_PARTY_LICENSES.md` | ✅ |
| Desktop GPL | `khandaq-desktop/LICENSE` | ✅ |
| SPDX bundle | `khandaq-desktop/LICENSES/` | ✅ |
| Source package script | `scripts/package-release-source.sh` | ✅ |
| License audit (baseline) | `docs/LICENSE_AUDIT.md` | ✅ (pre-A5) |

---

## 2. GPL-3.0 (Desktop)

### Требования

- [x] GPL text included (`LICENSE`)
- [x] Attribution to qTox in About + appdata
- [x] `NOTICE` at repo root
- [x] `THIRD_PARTY_LICENSES.md`
- [ ] Source tarball published **with each binary release** (run script at release time)
- [ ] Windows installer license page (NSIS — metadata Khandaq, GPL in About)

### Source offer

```bash
./scripts/package-release-source.sh 0.1.0-alpha
# → dist/releases/khandaq-desktop-0.1.0-alpha-source.tar.gz
```

Публиковать рядом с бинарниками URL или архив.

---

## 3. Mobile

| Client | License | Alpha status |
|--------|---------|--------------|
| TRIfA APK | GPL-2/3 | Debug-signed; **не публиковать как Khandaq** без rebrand |
| aTox APK | GPL-3 | Upstream snapshot only |
| Antidote IPA | MPL-2.0 | Unsigned; отдельный legal track |

**Правило Alpha:** mobile binaries = upstream as-is; бренд Khandaq только desktop.

---

## 4. Исключить из публичного релиза

- `io.github.qtox.qTox.desktop`
- `io.github.qtox.qTox.appdata.xml`
- `dist/` build artifacts без matching source tag
- `.env`, secrets, debug keystores

---

## 5. Pre-release checklist

1. `git tag v0.1.0-alpha`
2. `./scripts/package-release-source.sh v0.1.0-alpha`
3. Attach source tarball to GitHub Release
4. Verify `NOTICE` + `THIRD_PARTY_LICENSES.md` in release notes
5. Windows: installer shows Khandaq + link to source
6. Linux: ship `org.khandaq.messenger.appdata.xml`
7. Do **not** ship mobile as "Khandaq" until rebrand + license review

---

## 6. CI (рекомендация post-Alpha)

- `license-check` step for new deps
- SBOM export from CMake/Gradle
- Fail if `dist/` committed without version tag
