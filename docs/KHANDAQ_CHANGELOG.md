# Khandaq Changelog

## 0.1.0-alpha (2026-06-08) — Phase 4

### Added
- `scripts/build-linux.sh` — native Ubuntu build with dep checks
- `scripts/build-linux-docker.sh` — reproducible Docker build (Ubuntu 24.04)
- `scripts/verify-linux-build.sh` — post-build string/branding checks
- `buildscripts/docker/Dockerfile.khandaq_ubuntu2404`
- `docs/LINUX_BUILD.md`

### Artifacts
- `dist/linux/khandaq` (ELF aarch64 in Apple Silicon Docker; use x86_64 host for amd64)
- `dist/linux/khandaq.sha256`, desktop, appdata

---

## 0.1.0-alpha (2026-06-08) — Phase 3

### Added
- Khandaq brand assets: `resources/khandaq/{icon,logo,splash}.svg|png`, `theme.qss`
- `scripts/generate-khandaq-icons.sh` — PNG/icns/ico generation
- Brand overlay QSS applied via `Style::resolve()`

### Changed
- App/window/tray icon: `khandaq.svg` (+ all hicolor PNG sizes)
- Login logo: Khandaq shield + wordmark
- macOS icon: `khandaq.icns`
- Windows icon: `khandaq.ico`
- Dark palette: `#0B0F0E` / `#0F3D2E` / `#C9A227` / `#F5F5F0`
- Login screen and tab styling aligned to brand colors

---

## 0.1.0-alpha (2026-06-08) — Phase 2

### Added
- Khandaq Messenger desktop client based on qTox
- Rebrand plan (`docs/KHANDAQ_REBRAND_PLAN.md`)
- Build documentation (`docs/KHANDAQ_BUILD.md`)
- Brand resources placeholder (`resources/khandaq/`)

### Fixed
- FFmpeg 6+ compatibility in `cameradevice.cpp` (`priv_data_size` removed)

### Changed
- Application name: qTox → Khandaq
- Executable: `qtox` → `khandaq`
- App ID: `org.khandaq.messenger`
- Config: `qtox.ini` → `khandaq.ini`
- Profile path: `~/.config/tox/` → `~/.config/khandaq/`
- Logs: `qtox.log` → `khandaq.log`
- Desktop entry: `khandaq.desktop`
- Windows installer metadata → Khandaq
- macOS bundle: `Khandaq.app`, `org.khandaq.messenger`
- Update check disabled by default

### Preserved
- Tox protocol compatibility (toxcore API, `tox:` URI, `.tox` profiles)
- GPL license notices for qTox upstream
- Public bootstrap nodes in `res/nodes.json`

### Not included
- Custom icons (Phase 3)
- Android client (Phase 7-8)
- Bootstrap node infrastructure (Phase 9-10)
