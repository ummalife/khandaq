#!/usr/bin/env bash
#
# Build libjni-c-toxcore.so for Android from source, with the Khandaq NGC pure-TCP
# announce patch applied (khandaq-android-trifa/patches/khandaq-ngc-tcp-announce.patch).
#
# This reuses the upstream TRIfA native build (circle_scripts/deps.sh): it downloads
# android-ndk-r13b and builds FFmpeg/x264/vpx/opus/libsodium + c-toxcore + the JNI lib.
#
# IMPORTANT: this is a heavy legacy build. It is designed for x86_64 Linux / CI. On an
# Apple Silicon (arm64) Mac it runs under x86_64 emulation and is VERY slow / fragile.
# Prefer running on an x86_64 Linux runner.
#
# Output .so files land in khandaq-android-trifa/.../artefacts and are copied into
# android-refimpl-app/app/nativelibs/<abi>/ by the upstream scripts.
set -uo pipefail

TRIFA_DIR="$(cd "$(dirname "$0")/../khandaq-android-trifa" && pwd)"
PATCH_REL="patches/khandaq-ngc-tcp-announce.patch"

echo "TRIFA_DIR=$TRIFA_DIR"
[ -f "$TRIFA_DIR/$PATCH_REL" ] || { echo "patch not found: $TRIFA_DIR/$PATCH_REL"; exit 1; }
[ -f "$TRIFA_DIR/circle_scripts/deps.sh" ] || { echo "deps.sh not found"; exit 1; }

docker run --rm --platform linux/amd64 \
  -v "$TRIFA_DIR":/root/work \
  ubuntu:20.04 /bin/bash -c '
set -uo pipefail
export DEBIAN_FRONTEND=noninteractive
echo "[1/4] apt install build prerequisites ..."
apt-get update -qq
apt-get install -y --no-install-recommends \
  clang cmake libconfig-dev libgtest-dev ninja-build pkg-config \
  zip grep file ca-certificates autotools-dev autoconf automake \
  git bc wget rsync make libtool ssh gzip tar coreutils \
  python3 python-is-python3 openjdk-11-jdk-headless libncurses5 unzip xz-utils \
  || { echo "apt install FAILED"; exit 1; }

echo "[2/4] verify Khandaq NGC pure-TCP announce patch is wired into each toxcore checkout ..."
# KHANDAQ (audit): this used to sed-inject the patcher after the "git checkout zoff99/zoxcore_local_fork"
# fallback line. That fallback is gone (a failed checkout of the pinned commit must fail the build, not
# silently build a mutable branch), and deps.sh now calls the patcher inline at all 4 ABI sites with
# "|| exit 1". So assert the wiring instead of injecting it -- a missing call must still be a hard stop,
# so we never silently ship an UNPATCHED .so.
patch_sites=$(grep -c "apply_khandaq_patch.py toxcore/Messenger.c" /root/work/circle_scripts/deps.sh)
echo "Messenger.c patcher wired at $patch_sites site(s)"
if [ "$patch_sites" -ne 4 ]; then
  echo "ERROR: expected the Messenger.c patcher at 4 ABI sites in deps.sh, found $patch_sites"; exit 1
fi

# media/NdkMediaFormat.h (FFmpeg --enable-mediacodec) requires NDK API >= 21. The 32-bit ABI
# toolchains are built with --api 16 which lacks the media NDK headers -> build aborts. Bump to 21.
sed -i "s/--api 16 /--api 21 /g" /root/work/circle_scripts/deps.sh
echo "toolchains at api: $(grep -oE -- "--api [0-9]+" /root/work/circle_scripts/deps.sh | sort | uniq -c)"

# FFmpeg n6.0 mediacodec_wrapper.c uses AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG (NDK API 28+), absent
# from NDK r13b. jni-c-toxcore.c makes no direct av_mediacodec_* calls (only av_jni_set_java_vm via
# --enable-jni), so disabling FFmpeg HW mediacodec is link-safe. The test .so loses HW h264 decode
# (software fallback) but tox/groups/messaging - what we are verifying - are unaffected.
sed -i "s/--enable-mediacodec/--disable-mediacodec/g; s/--enable-decoder=h264_mediacodec/--disable-decoder=h264_mediacodec/g" /root/work/circle_scripts/deps.sh
echo "mediacodec now: $(grep -oE -- "--(enable|disable)-mediacodec" /root/work/circle_scripts/deps.sh | sort | uniq -c)"

# libsodium 1.0.19 emits ARMv8 crypto intrinsics (llvm.aarch64.crypto.aesmc / aegis) that NDK r13b
# clang 3.8 cannot select for arm64 -> "Cannot select intrinsic". Build portable C crypto instead
# (--disable-asm). Perf-only; correctness/compatibility unaffected. (32-bit armeabi already builds.)
sed -i "s#libsodium/configure #libsodium/configure --disable-asm #g" /root/work/circle_scripts/deps.sh
echo "libsodium configure now: $(grep -c -- "libsodium/configure --disable-asm" /root/work/circle_scripts/deps.sh) site(s)"

# libsodium 1.0.19 added aegis128l/aegis256 with ARMv8-crypto intrinsics that NDK r13b clang 3.8
# cannot select on aarch64 (32-bit armeabi already builds fine). Downgrade to 1.0.18 (pre-aegis),
# which compiles on clang 3.8. Force a fresh clone so a cached 1.0.19 checkout is not reused.
sed -i "s/_LIBSODIUM_VERSION_=\"1.0.19\"/_LIBSODIUM_VERSION_=\"1.0.18\"/g" /root/work/circle_scripts/deps.sh
sed -i "s#git clone --depth=1 --branch=\"\$_LIBSODIUM_VERSION_\" https://github.com/jedisct1/libsodium.git#rm -Rf libsodium ; git clone --depth=1 --branch=\"\$_LIBSODIUM_VERSION_\" https://github.com/jedisct1/libsodium.git#g" /root/work/circle_scripts/deps.sh
echo "libsodium version now: $(grep -oE "_LIBSODIUM_VERSION_=\"[0-9.]+\"" /root/work/circle_scripts/deps.sh | sort | uniq -c)"

mkdir -p /root/.android && touch /root/.android/repositories.cfg

echo "[3/4] run deps.sh (cache mode if workspace exists) ..."
cd /root/work
if [ -d workspace ] && [ -n "$(ls -A workspace 2>/dev/null)" ]; then oo="cache"; else oo=""; fi
echo "deps.sh mode: \"$oo\""
mkdir -p build_dir && cd build_dir
bash ../circle_scripts/deps.sh "$oo" 2>&1
rc=$?

echo "[4/4] locate built .so artefacts ..."
find /root/work -name "libjni-c-toxcore.so" -exec ls -la {} \; 2>/dev/null | head
exit $rc
'
