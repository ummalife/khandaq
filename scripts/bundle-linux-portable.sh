#!/usr/bin/env bash
# Bundle Khandaq Linux binary with shared libraries for distro-neutral tarball.
set -euo pipefail


# KHANDAQ (audit round 3, F-23): the release-blocking waiver gate, on the path it was written for.
#
# scripts/check-bundled-deps-eol.py has had a --release mode since the first audit round: no grace
# period, and a waiver with under 14 days left may not carry a release. It was wired into the ANDROID
# release workflow and into nothing else, so the DESKTOP builds -- the ones that actually bundle
# OpenSSL 1.1.1w and Qt 5.12.12, the two components the waivers are about -- never consulted it. A
# desktop artifact could be produced on an expired waiver, which is precisely what the finding asked
# to be impossible.
#
# Skippable only deliberately, for a local experiment that is not a release:
#   KHANDAQ_SKIP_DEP_GATE=1 ./scripts/<this script>
if [ "${KHANDAQ_SKIP_DEP_GATE:-0}" != "1" ]; then
    _gate_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
    if command -v python3 >/dev/null 2>&1; then
        echo "==> bundled dependency gate (waivers must be live)"
        python3 "${_gate_root}/scripts/check-bundled-deps-eol.py" --release
    else
        echo "ERROR: python3 not found; cannot run the bundled dependency gate." >&2
        echo "       Install python3, or set KHANDAQ_SKIP_DEP_GATE=1 if this is not a release." >&2
        exit 1
    fi
fi

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIST="${1:-$ROOT/dist/linux}"
BIN="$DIST/khandaq"
LIBDIR="$DIST/lib"

if [[ ! -f "$BIN" ]]; then
  echo "Missing binary: $BIN"
  exit 1
fi

if [[ ! -f "$DIST/khandaq.bin" ]]; then
  mv "$BIN" "$DIST/khandaq.bin"
fi
BIN="$DIST/khandaq.bin"

rm -rf "$LIBDIR"
mkdir -p "$LIBDIR"

declare -A SEEN=()

skip_lib() {
  case "$1" in
    ld-linux*|linux-vdso*|libc.so*|libm.so*|libpthread.so*|libdl.so*|librt.so*|libresolv.so*|libutil.so*|libanl.so*|libnss_*|libnsl.so*)
      return 0
      ;;
  esac
  return 1
}

copy_lib() {
  local soname="$1"
  local libpath="$2"
  [[ -z "$libpath" || "$libpath" == "not" || ! -e "$libpath" ]] && return 0
  skip_lib "$soname" && return 0

  local real target base
  real="$(readlink -f "$libpath")"
  base="$(basename "$real")"
  [[ -n "${SEEN[$real]+x}" ]] && {
    [[ -e "$LIBDIR/$soname" ]] || ln -sf "$base" "$LIBDIR/$soname"
    return 0
  }
  SEEN[$real]=1

  if [[ ! -e "$LIBDIR/$base" ]]; then
    cp -L "$real" "$LIBDIR/$base"
  fi
  if [[ "$soname" != "$base" && ! -e "$LIBDIR/$soname" ]]; then
    ln -sf "$base" "$LIBDIR/$soname"
  fi

  local dep_soname dep_path
  while read -r dep_soname dep_path; do
    copy_lib "$dep_soname" "$dep_path"
  done < <(
    LD_LIBRARY_PATH="$LIBDIR${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
      ldd "$LIBDIR/$soname" 2>/dev/null | awk '/=> \// {print $1, $3}'
  )
}

echo "==> Bundling shared libraries into $LIBDIR"
while read -r soname libpath; do
  copy_lib "$soname" "$libpath"
done < <(ldd "$BIN" | awk '/=> \// {print $1, $3}')

LIB_COUNT="$(find "$LIBDIR" -maxdepth 1 \( -type f -o -type l \) | wc -l | tr -d ' ')"
echo "    $LIB_COUNT library entries bundled"

QT_PLUGINS="${QT_PLUGIN_ROOT:-}"
if [[ -z "$QT_PLUGINS" ]]; then
  for candidate in \
    /usr/lib/x86_64-linux-gnu/qt5/plugins \
    /usr/lib/qt5/plugins \
    /usr/lib64/qt5/plugins; do
    if [[ -d "$candidate/platforms" ]]; then
      QT_PLUGINS="$candidate"
      break
    fi
  done
fi

PLUGIN_DST="$DIST/plugins"
rm -rf "$PLUGIN_DST"
if [[ -n "$QT_PLUGINS" && -d "$QT_PLUGINS" ]]; then
  echo "==> Bundling Qt plugins from $QT_PLUGINS"
  mkdir -p "$PLUGIN_DST"
  for sub in platforms imageformats iconengines xcbglintegrations \
    wayland-decoration-client wayland-graphics-integration-client; do
    if [[ -d "$QT_PLUGINS/$sub" ]]; then
      mkdir -p "$PLUGIN_DST/$sub"
      cp -a "$QT_PLUGINS/$sub"/*.so "$PLUGIN_DST/$sub/" 2>/dev/null || true
      for plug in "$PLUGIN_DST/$sub"/*.so; do
        [[ -f "$plug" ]] || continue
        while read -r soname libpath; do
          copy_lib "$soname" "$libpath"
        done < <(LD_LIBRARY_PATH="$LIBDIR" ldd "$plug" 2>/dev/null | awk '/=> \// {print $1, $3}')
      done
    fi
  done
  echo "    Qt plugins bundled"
else
  echo "WARN: Qt plugin directory not found"
fi

cat > "$DIST/khandaq" <<'EOF'
#!/bin/sh
set -eu
PROG="$0"
while [ -L "$PROG" ]; do
  LINK="$(readlink "$PROG")"
  case "$LINK" in
    /*) PROG="$LINK" ;;
    *) PROG="$(dirname "$PROG")/$LINK" ;;
  esac
done
DIR="$(CDPATH= cd -- "$(dirname -- "$PROG")" && pwd)"
export LD_LIBRARY_PATH="$DIR/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
if [ -d "$DIR/plugins" ]; then
  export QT_PLUGIN_PATH="$DIR/plugins"
fi
exec "$DIR/khandaq.bin" "$@"
EOF
chmod +x "$DIST/khandaq" "$DIST/khandaq.bin"

MISSING="$(LD_LIBRARY_PATH="$LIBDIR" ldd "$BIN" 2>/dev/null | grep 'not found' || true)"
if [[ -n "$MISSING" ]]; then
  echo "WARN: unresolved deps after bundle:"
  echo "$MISSING"
  exit 1
fi

echo "Portable bundle ready: $DIST/khandaq (+ khandaq.bin, lib/)"
