# Khandaq — Linux Build (Фаза 4)

Целевые дистрибутивы: **Ubuntu 22.04 / 24.04**, Debian 12+.

## Reproducible build (Docker, рекомендуется)

Работает с macOS/Windows/Linux — единый образ Ubuntu 24.04:

```bash
./scripts/build-linux-docker.sh
```

Результат:
```
dist/linux/khandaq
dist/linux/khandaq.desktop
dist/linux/org.khandaq.messenger.appdata.xml
dist/linux/khandaq.sha256
```

## Native build (Ubuntu)

```bash
# 1. Системные пакеты
./scripts/build-linux.sh --install-deps

# 2. toxcore (если нет в репозитории)
./scripts/build-linux.sh --build-deps

# 3. Сборка
./scripts/build-linux.sh
```

## Проверка

```bash
./scripts/verify-linux-build.sh dist/linux/khandaq
```

Ручная проверка на Linux с GUI:

```bash
./dist/linux/khandaq --help
./dist/linux/khandaq
```

| Check | Ожидание |
|-------|----------|
| Window title | Khandaq |
| Profile path | `~/.config/khandaq/` |
| Config | `~/.config/Khandaq/khandaq.ini` |
| Logs | `khandaq.log` в cache dir |
| Tox ID | генерируется при создании профиля |
| UI | нет qTox (кроме GPL/legal) |

## Установка desktop entry

```bash
sudo install -m 755 dist/linux/khandaq /usr/local/bin/
sudo install -m 644 dist/linux/khandaq.desktop /usr/share/applications/
sudo install -m 644 dist/linux/org.khandaq.messenger.appdata.xml /usr/share/metainfo/
sudo gtk-update-icon-cache /usr/share/icons/hicolor/ 2>/dev/null || true
```

## CMake flags

```bash
cmake .. \
  -DCMAKE_BUILD_TYPE=Release \
  -DUPDATE_CHECK=OFF \
  -DSPELL_CHECK=OFF \
  -DDESKTOP_NOTIFICATIONS=OFF
```

## Известные проблемы

- `docker compose` в upstream qTox может падать (cycle) — используйте `build-linux-docker.sh`
- Ubuntu 24.04: пакет `qt5-default` удалён → `qtbase5-dev`
- FFmpeg 6+: патч в `cameradevice.cpp` уже применён
