# Khandaq Desktop — сборка

Версия: **0.1.0-alpha**  
База: qTox → Khandaq (`khandaq-desktop/`)

## Зависимости (Linux Ubuntu 22.04/24.04)

```bash
sudo apt update
sudo apt install -y \
  build-essential cmake extra-cmake-modules git pkg-config \
  qtbase5-dev qttools5-dev-tools libqt5svg5-dev libqt5opengl5-dev \
  libavcodec-dev libavdevice-dev libavfilter-dev libavutil-dev \
  libswscale-dev libswresample-dev libopenal-dev libqrencode-dev \
  libsqlcipher-dev libsodium-dev libopus-dev libvpx-dev \
  libkf5sonnet-dev libxss-dev patchelf
```

Собрать из исходников: [c-toxcore](https://github.com/TokTok/c-toxcore), [toxext](https://github.com/toxext/toxext), [tox_extension_messages](https://github.com/toxext/tox_extension_messages).

## Иконки (Фаза 3)

```bash
cd khandaq-desktop
./scripts/generate-khandaq-icons.sh
```

Генерирует: `img/icons/*/khandaq.png`, `khandaq.icns`, `windows/khandaq.ico`.

## Linux (Фаза 4)

Подробно: [LINUX_BUILD.md](LINUX_BUILD.md)

```bash
# Reproducible (Docker, Ubuntu 24.04)
./scripts/build-linux-docker.sh

# Native Ubuntu
./scripts/build-linux.sh --install-deps
./scripts/build-linux.sh --build-deps   # при необходимости
./scripts/build-linux.sh

# Проверка
./scripts/verify-linux-build.sh dist/linux/khandaq
```

## Быстрая сборка

```bash
cd khandaq-desktop
../scripts/build-linux.sh
```

Или вручную:

```bash
cd khandaq-desktop
mkdir -p build && cd build
cmake .. -DCMAKE_BUILD_TYPE=Release -DUPDATE_CHECK=OFF -DSPELL_CHECK=OFF
make -j$(nproc)
cp khandaq ../../dist/linux/
```

## macOS

```bash
brew bundle --file osx/Brewfile
# собрать toxcore (buildscripts/build_toxcore_linux.sh или вручную)
mkdir -p _build && cd _build
cmake .. -DCMAKE_PREFIX_PATH=$(brew --prefix qt@5) -DUPDATE_CHECK=OFF
make -j$(sysctl -n hw.ncpu)
```

Результат: `khandaq.app`

## Windows (cross-compile)

См. `docs/WINDOWS_BUILD.md` и `scripts/build-windows-cross.sh`.

## Проверка после сборки

```bash
./khandaq --help
# Профиль: ~/.config/khandaq/
# Настройки: ~/.config/Khandaq/khandaq.ini (или AppConfigLocation)
# Логи: khandaq.log в cache dir
```

## CMake flags

| Flag | Default | Описание |
|------|---------|----------|
| `UPDATE_CHECK` | OFF | Проверка обновлений |
| `SPELL_CHECK` | ON | Sonnet spell check |
| `DESKTOP_NOTIFICATIONS` | OFF | Snorenotify |
