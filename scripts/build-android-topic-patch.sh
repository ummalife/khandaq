#!/usr/bin/env bash
# Build libjni-c-toxcore-topic-patch.so for ABIs where libjni-c-toxcore.so lacks set_topic JNI.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP="$ROOT/khandaq-android-trifa/android-refimpl-app"
SRC="$ROOT/khandaq-android-trifa/jni-c-toxcore/jni-c-toxcore-topic-patch.c"
NDK="${ANDROID_NDK_HOME:-${ANDROID_HOME:-$HOME/Library/Android/sdk}/ndk/25.1.8937393}"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/darwin-x86_64"
API=21

build_abi() {
  local abi="$1"
  local triple="$2"
  local clang="$TOOLCHAIN/bin/${triple}${API}-clang"
  local out_dir="$APP/app/nativelibs/$abi"
  local base_so="$APP/app/build/intermediates/merged_native_libs/debug/mergeDebugNativeLibs/out/lib/$abi/libjni-c-toxcore.so"

  if [[ ! -f "$base_so" ]]; then
    echo "WARN: missing $base_so — run ./gradlew :app:assembleDebug first"
    return 1
  fi

  if nm -g "$base_so" 2>/dev/null | rg -q "tox_1group_1set_1topic"; then
    echo "==> $abi: libjni-c-toxcore.so already has tox_group_set_topic JNI, skip patch"
    rm -f "$out_dir/libjni-c-toxcore-topic-patch.so"
    return 0
  fi

  mkdir -p "$out_dir"
  echo "==> Building topic patch for $abi ..."
  "$clang" -shared -fPIC -O2 \
    "$SRC" \
    -o "$out_dir/libjni-c-toxcore-topic-patch.so" \
    "$base_so" \
    -Wl,--no-undefined

  nm -g "$out_dir/libjni-c-toxcore-topic-patch.so" | rg "tox_1group_1set_1topic" || {
    echo "ERROR: patch symbol missing for $abi"
    exit 1
  }
  echo "OK: $out_dir/libjni-c-toxcore-topic-patch.so"
}

[[ -x "$TOOLCHAIN/bin/aarch64-linux-android${API}-clang" ]] || {
  echo "NDK clang not found under $NDK"
  exit 1
}

build_abi "arm64-v8a" "aarch64-linux-android"
build_abi "armeabi-v7a" "armv7a-linux-androideabi"

# Ship base libs alongside patch (nativelibs was dummy-only).
for abi in arm64-v8a armeabi-v7a; do
  base="$APP/app/build/intermediates/merged_native_libs/debug/mergeDebugNativeLibs/out/lib/$abi/libjni-c-toxcore.so"
  if [[ -f "$base" ]]; then
    cp -f "$base" "$APP/app/nativelibs/$abi/libjni-c-toxcore.so"
  fi
done

echo "Done. Rebuild APK: cd $APP && ./gradlew :app:assembleDebug"
