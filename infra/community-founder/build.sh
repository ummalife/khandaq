#!/usr/bin/env bash
# Build the Khandaq Community founder (khandaq-community), its probe (community-probe), the local
# admin test client (community-admin-test) and the unit test of the shared helpers
# (community-unit-test), then run the unit test.
#
# All of them link the toxcore the iOS client ships, taken from khandaq-ios/local_pod_repo/toxcore,
# for the reason infra/demo-contact/build.sh gives: NGC moderation, the topic lock and msgV3 must
# behave here exactly as they do between the clients. The bot needs no audio or video, so toxav is
# left out, and with it opus and vpx; toxencryptsave contributes one header that tox.c includes.
#
# The pod keeps its C sources under .m names (CocoaPods compiles them as Objective-C); they are plain
# C and are copied back to .c.
#
# The copy gets one change, toxcore-founder-connected.patch, which says why: a founder's group is
# connected as soon as it is restored, instead of waiting for a sync that may never come while its
# members drop it. The build stops if the patch no longer applies exactly, so that a toxcore update
# in the pod gets the patch reviewed instead of silently skipped.
#
# Requires a C compiler, pkg-config, patch and libsodium development files.
#   Ubuntu:  apt install build-essential patch pkg-config libsodium-dev
#   macOS:   brew install pkg-config libsodium
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
command -v patch >/dev/null || { echo "patch is required" >&2; exit 1; }
pkg-config --exists libsodium || { echo "missing development files for libsodium (pkg-config libsodium)" >&2; exit 1; }

rm -rf "$SRC" "$OBJ"
mkdir -p "$SRC/toxcore/events" "$SRC/toxencryptsave" "$SRC/third_party" "$OBJ"

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
cp "$POD/toxencryptsave/defines.h" "$SRC/toxencryptsave/"
cp "$CMP/cmp.c" "$CMP/cmp.h" "$SRC/third_party/"

# No fuzz: the patch applies to exactly the code it was written against, or the build stops.
if ! patch -s -t -N -F0 -p1 -d "$SRC" <"$HERE/toxcore-founder-connected.patch"; then
  echo "toxcore-founder-connected.patch does not apply to $POD; review it against the new toxcore" >&2
  exit 1
fi

SODIUM_INC="$(pkg-config --variable=includedir libsodium)"
CFLAGS_COMMON=(-O2 -g -std=gnu11 -pthread -fstack-protector-strong -D_FORTIFY_SOURCE=2
  -Wno-unused-parameter -Wno-deprecated-declarations
  -I"$SRC" -I"$SRC/third_party" -I"$SODIUM_INC/sodium")
read -r -a PKG_CFLAGS <<<"$(pkg-config --cflags libsodium)"
read -r -a PKG_LIBS <<<"$(pkg-config --libs libsodium)"

jobs="$( (command -v nproc >/dev/null && nproc) || sysctl -n hw.ncpu 2>/dev/null || echo 2)"

# One object per source, at most $jobs compilers at a time; the first failing compile stops the build.
# toxcore is not ours to fix, so its warnings are silenced (-w). Written for bash 3.2 as well (macOS):
# no mapfile, and empty arrays are expanded with the +-guard.
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

# Our own code builds without warnings. WERROR=1 turns any warning into a failed build; it is left
# off by default so that a newer compiler on a server cannot block a deploy over a new warning.
OWN_WARNINGS=(-Wall -Wextra -Wshadow -Wno-unused-parameter)
if [[ "${WERROR:-0}" == 1 ]]; then
  OWN_WARNINGS+=(-Werror)
fi
link_tool() {
  local name="$1" source="$2"
  "$CC" "${CFLAGS_COMMON[@]}" "${PKG_CFLAGS[@]}" "${OWN_WARNINGS[@]}" -I"$HERE" \
    -o "$OUT/$name" "$HERE/$source" "$OBJ"/*.o "${PKG_LIBS[@]}" -lm
}
link_tool khandaq-community khandaq_community.c
link_tool community-probe probe.c
link_tool community-admin-test community_admin_test.c
link_tool community-unit-test community_unit_test.c

"$OUT/community-unit-test"
echo "built $OUT/khandaq-community, $OUT/community-probe and $OUT/community-admin-test"
