#!/usr/bin/env bash
# Quick desktop UI smoke: launch Khandaq, capture screenshots (macOS host).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${KHANDAQ_UI_TEST_OUT:-$ROOT/docs/ui-test-screenshots}"
MAC_APP="${KHANDAQ_MAC_APP:-$ROOT/dist/macos/khandaq.app}"
TS="$(date +%Y%m%d-%H%M%S)"

mkdir -p "$OUT"

if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "macOS screenshot test only runs on Darwin (use rebuild + manual VM check on Windows)."
  exit 0
fi

[[ -d "$MAC_APP" ]] || { echo "Missing $MAC_APP — run scripts/build-macos.sh first"; exit 1; }

echo "==> Launch Khandaq (macOS)"
killall khandaq 2>/dev/null || true
sleep 1
open -n "$MAC_APP"
sleep 5

capture() {
  local name="$1"
  local path="$OUT/mac-${TS}-${name}.png"
  if screencapture -x "$path" 2>/dev/null; then
    echo "  saved $path"
    return 0
  fi
  # fallback: whole screen
  screencapture -x "$path" || true
  echo "  saved (fullscreen fallback) $path"
}

echo "==> Capture login screen"
capture "login"

echo "==> Done. Review PNGs in $OUT"
