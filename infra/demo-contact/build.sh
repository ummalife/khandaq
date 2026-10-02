#!/usr/bin/env bash
# Build the Khandaq demo contact (khandaq-demo) and its probe (khandaq-demo-probe).
#
# Both link the toxcore the iOS client ships, taken from khandaq-ios/local_pod_repo/toxcore. That is
# the point of building it this way rather than against a distribution libtoxcore: msgV3 framing,
# the toxcore capability handshake, NGC groups and the older toxav generation the iPhone runs all
# behave here exactly as they do between two phones. A stock libtoxcore would interoperate on the
# basics and drift on precisely the features a reviewer is asked to try.
#
# The pod keeps its C sources under .m names (CocoaPods compiles them as Objective-C); they are plain
# C and are copied back to .c. toxav_ngc_video is left out: it is the VideoToolbox H.264 path for
# group video, exists only on Apple platforms, and nothing inside toxcore calls it.
#
# Requires a C compiler, pkg-config, libsodium, opus and vpx development files.
#   Ubuntu:  apt install build-essential pkg-config libsodium-dev libopus-dev libvpx-dev
#   macOS:   brew install pkg-config libsodium opus libvpx
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
POD="${POD_TOXCORE:-$ROOT/khandaq-ios/local_pod_repo/toxcore/toxcore}"
CMP="${CMP_DIR:-$ROOT/khandaq-desktop/buildscripts/toxcore/third_party/cmp}"
OUT="${OUT:-$HERE/build}"
SRC="$OUT/src"
OBJ="$OUT/obj"
CC="${CC:-cc}"

[[ -f "$POD/toxcore/tox.h" ]] || { echo "toxcore sources not found at $POD" >&2; exit 1; }
[[ -f "$CMP/cmp.c" ]] || { echo "cmp sources not found at $CMP" >&2; exit 1; }
for lib in libsodium opus vpx; do
  pkg-config --exists "$lib" || { echo "missing development files for $lib (pkg-config $lib)" >&2; exit 1; }
done

rm -rf "$SRC" "$OBJ"
mkdir -p "$SRC/toxcore/events" "$SRC/toxav" "$SRC/toxencryptsave" "$SRC/third_party" "$OBJ"

# Copy one pod directory, turning foo.m into foo.c. Backup files (*.m.bak) are not sources.
copy_dir() {
  local from="$1" to="$2" path base
  for path in "$from"/*.m "$from"/*.h; do
    [[ -e "$path" ]] || continue
    base="$(basename "$path")"
    case "$base" in
      *.m) base="${base%.m}.c" ;;
    esac
    cp "$path" "$to/$base"
  done
}

copy_dir "$POD/toxcore" "$SRC/toxcore"
copy_dir "$POD/toxcore/events" "$SRC/toxcore/events"
copy_dir "$POD/toxav" "$SRC/toxav"
copy_dir "$POD/toxencryptsave" "$SRC/toxencryptsave"
rm -f "$SRC/toxav/toxav_ngc_video.c"
cp "$CMP/cmp.c" "$CMP/cmp.h" "$SRC/third_party/"

SODIUM_INC="$(pkg-config --variable=includedir libsodium)"
CFLAGS_COMMON=(-O2 -g -std=gnu11 -pthread -fstack-protector-strong -D_FORTIFY_SOURCE=2
  -Wno-unused-parameter -Wno-deprecated-declarations
  -I"$SRC" -I"$SRC/third_party" -I"$SODIUM_INC/sodium")
read -r -a PKG_CFLAGS <<<"$(pkg-config --cflags libsodium opus vpx)"
read -r -a PKG_LIBS <<<"$(pkg-config --libs libsodium opus vpx)"

jobs="$( (command -v nproc >/dev/null && nproc) || sysctl -n hw.ncpu 2>/dev/null || echo 2)"

# One object per source, at most $jobs compilers at a time; the first failing compile stops the build.
# Written for bash 3.2 as well (macOS): no mapfile, and empty arrays are expanded with the +-guard.
pids=()
while IFS= read -r -d '' src; do
  rel="${src#"$SRC"/}"
  "$CC" "${CFLAGS_COMMON[@]}" "${PKG_CFLAGS[@]}" -w -c "$src" -o "$OBJ/${rel//\//_}.o" &
  pids+=("$!")
  if (( ${#pids[@]} >= jobs )); then
    wait "${pids[0]}" || exit 1
    pids=(${pids[@]+"${pids[@]:1}"})
  fi
done < <(find "$SRC" -name '*.c' -print0)
for pid in ${pids[@]+"${pids[@]}"}; do
  wait "$pid" || exit 1
done

"$CC" "${CFLAGS_COMMON[@]}" "${PKG_CFLAGS[@]}" -Wall -Wextra -Wno-unused-parameter \
  -o "$OUT/khandaq-demo" "$HERE/khandaq_demo.c" "$OBJ"/*.o "${PKG_LIBS[@]}" -lm
"$CC" "${CFLAGS_COMMON[@]}" "${PKG_CFLAGS[@]}" -Wall -Wextra -Wno-unused-parameter \
  -o "$OUT/khandaq-demo-probe" "$HERE/probe.c" "$OBJ"/*.o "${PKG_LIBS[@]}" -lm

echo "built $OUT/khandaq-demo and $OUT/khandaq-demo-probe"
