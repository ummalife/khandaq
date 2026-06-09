# Khandaq — Windows Build (Фаза 5)

Кросс-компиляция с Linux (Docker) → **Windows x86_64** portable zip + NSIS installer.

## Быстрый старт

```bash
./scripts/build-windows-cross.sh
```

Результат:
```
dist/windows/x86_64/khandaq-x86_64-Release.zip
dist/windows/x86_64/Khandaq-installer.exe   # если NSIS прошёл
dist/windows/x86_64/sha256sums.txt
```

Повторная сборка (образ уже есть):
```bash
SKIP_IMAGE_BUILD=1 ./scripts/build-windows-cross.sh
```

## 32-bit (i686)

```bash
KHANDAQ_WINDOWS_ARCH=i686 ./scripts/build-windows-cross.sh
```

## Ручной запуск в контейнере

```bash
docker build -f khandaq-desktop/buildscripts/docker/Dockerfile.windows_builder \
  --build-arg ARCH=x86_64 --build-arg WINEARCH=win64 \
  -t khandaq-windows-builder:x86_64 khandaq-desktop/buildscripts

docker run --rm -it -v "$(pwd)/khandaq-desktop:/khandaq" \
  khandaq-windows-builder:x86_64 bash

# внутри:
mkdir -p /build && cd /build
/khandaq/windows/cross-compile/build.sh \
  --src-dir /khandaq --arch x86_64 --build-type Release
```

## CMake flags (в build.sh)

- `UPDATE_CHECK=OFF` — нет upstream qTox update server
- `SPELL_CHECK=OFF` — hunspell на Windows не собирается в CI
- `DESKTOP_NOTIFICATIONS=ON` — SnoreToast

## Проверка на Windows

1. Распаковать zip или установить через `Khandaq-installer.exe`
2. Запустить `bin\Khandaq.exe`
3. Window title: **Khandaq**
4. Config: `%APPDATA%\Khandaq\khandaq.ini`
5. Profile: `%APPDATA%\khandaq\`

## Известные проблемы

- **Первый docker build** — долгий (Qt 5 + FFmpeg + OpenSSL + toxcore)
- `docker compose` в upstream может падать — используйте `build-windows-cross.sh`
- Образ на Apple Silicon: mingw/wine через qemu — медленнее, но работает
- Updater не собирается (как в upstream qTox)
