#!/usr/bin/env bash
# Post-build checks for Khandaq Linux binary
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BIN="${1:-$ROOT/dist/linux/khandaq}"
REAL_BIN="$ROOT/dist/linux/khandaq.bin"
LIB_DIR="$ROOT/dist/linux/lib"
DOCKER_IMAGE="${KHANDAQ_LINUX_IMAGE:-khandaq-builder:ubuntu24.04-amd64}"

fail=0
ok() { echo "  OK: $1"; }
bad() { echo "  FAIL: $1"; fail=1; }

strings_target() {
  [[ -f "$REAL_BIN" ]] && echo "$REAL_BIN" || echo "$BIN"
}

strings_pipe() {
  local target
  target="$(strings_target)"
  if strings "$target" 2>/dev/null | head -c 1 | grep -q .; then
    strings "$target" 2>/dev/null | tr '\0' '\n'
    return
  fi
  if command -v docker >/dev/null && docker info >/dev/null 2>&1; then
    docker run --rm --platform linux/amd64 -v "$(dirname "$target"):/dist:ro" "$DOCKER_IMAGE" \
      strings "/dist/$(basename "$target")" 2>/dev/null | tr '\0' '\n' || true
  fi
}

has_string() {
  strings_pipe | grep -Fq "$1"
}

count_string() {
  strings_pipe | grep -c "$1" || true
}

echo "==> Verifying $BIN"

[[ -f "$BIN" ]] && ok "launcher exists" || bad "launcher missing"
[[ -x "$BIN" ]] && ok "launcher executable" || bad "launcher not executable"
[[ -f "$REAL_BIN" ]] && ok "khandaq.bin exists" || bad "khandaq.bin missing"
[[ -d "$LIB_DIR" ]] && ok "lib/ bundled ($(find "$LIB_DIR" -maxdepth 1 \( -type f -o -type l \) | wc -l | tr -d ' ') entries)" || bad "lib/ missing"
[[ -f "$ROOT/dist/linux/plugins/platforms/libqxcb.so" ]] && ok "Qt xcb plugin bundled" || bad "Qt plugins missing"

# KHANDAQ (desktop v0.2.49): the bundle carries the FFmpeg khandaq-desktop/buildscripts/
# build_ffmpeg_linux.sh builds (8.1.1 without the MOV boxes of the open advisories; its
# --extra-version is in every library's configuration string), never Ubuntu's 6.1.1. libavfilter and
# libswresample are configured out of it, so finding one means some other FFmpeg came along.
FFMPEG_MARK="khandaq-mov-hardening"
for name in libavcodec libavdevice libavformat libavutil libswscale; do
  found=""
  for lib in "$LIB_DIR/$name".so.*; do
    [[ -f "$lib" && ! -L "$lib" ]] || continue
    found="$lib"
    if LC_ALL=C grep -aqF -- "--extra-version=$FFMPEG_MARK" "$lib"; then
      ok "$(basename "$lib") is the FFmpeg built from source"
    else
      bad "$(basename "$lib") is not the FFmpeg of build_ffmpeg_linux.sh (no $FFMPEG_MARK)"
    fi
  done
  [[ -n "$found" ]] || bad "$name missing from lib/"
done
for name in libavfilter libswresample libpostproc; do
  if compgen -G "$LIB_DIR/$name.so*" >/dev/null; then
    bad "$name bundled: build_ffmpeg_linux.sh configures it out"
  fi
done

FILE_INFO="$(file -b "$(strings_target)" 2>/dev/null || echo unknown)"
echo "  info: $FILE_INFO"

has_string "Khandaq Messenger" && ok "contains 'Khandaq Messenger'" || bad "missing 'Khandaq Messenger' string"
has_string "khandaq.ini" && ok "config file khandaq.ini referenced" || bad "khandaq.ini not found in binary"
has_string "khandaq.log" && ok "log file khandaq.log referenced" || bad "khandaq.log not found in binary"
has_string "org.khandaq.messenger" && ok "app id org.khandaq.messenger" || bad "app id missing"

QTox_UI="$(count_string "qTox")"
if [[ "$QTox_UI" -le 600 ]]; then
  ok "qTox mentions present ($QTox_UI, mostly GPL/smileys — expected)"
else
  bad "unexpected qTox string count ($QTox_UI)"
fi

if [[ "$FILE_INFO" == *"ELF"* ]] && [[ "$(uname -s)" != "Linux" ]]; then
  if command -v docker >/dev/null && docker info >/dev/null 2>&1; then
    HELP_OUT=$(docker run --rm --platform linux/amd64 \
      -v "$(dirname "$BIN"):/dist:ro" \
      -e QT_QPA_PLATFORM=offscreen \
      "$DOCKER_IMAGE" \
      bash -ec "/dist/khandaq --help 2>&1 | head -5" || true)
    if echo "$HELP_OUT" | grep -qi "Khandaq"; then
      ok "--help shows Khandaq (via Docker)"
    else
      bad "--help check via Docker failed"
      echo "$HELP_OUT" | sed 's/^/    /'
    fi
  else
    echo "  SKIP: --help (ELF binary, run on Linux or with Docker)"
  fi
elif QT_QPA_PLATFORM=offscreen "$BIN" --help 2>&1 | grep -qi "Khandaq"; then
  ok "--help shows Khandaq"
else
  bad "--help missing Khandaq branding"
fi

echo ""
if [[ "$fail" -eq 0 ]]; then
  echo "All automated checks passed."
  echo "Manual (Linux + display):"
  echo "  ./khandaq"
  echo "  - window title: Khandaq"
  echo "  - profile dir: ~/.config/khandaq/"
  echo "  - settings: ~/.config/Khandaq/khandaq.ini"
  exit 0
fi
echo "Some checks failed."
exit 1
