#!/usr/bin/env bash
# Rebuild Khandaq iOS native deps: libopus (xcframework) + vpx device slice.
# Run from repo root. Requires Xcode CLI tools.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
IOS_DIR="$ROOT/khandaq-ios"
OPUS_POD="$IOS_DIR/local_pod_repo/libopus-static"
VPX_FW="$IOS_DIR/local_pod_repo/toxcore/ios/vpx.framework"
WORK=/tmp/khandaq-ios-native-deps
CLANG=/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/clang
IOS_SDK=$(xcrun --sdk iphoneos --show-sdk-path)
SIM_SDK=$(xcrun --sdk iphonesimulator --show-sdk-path)
MIN=12.0

echo "==> opus 1.3.1"
mkdir -p "$WORK"
if [[ ! -d "$WORK/opus-1.3.1" ]]; then
  curl -fsSL -o "$WORK/opus-1.3.1.tar.gz" https://ftp.osuosl.org/pub/xiph/releases/opus/opus-1.3.1.tar.gz
  tar -C "$WORK" -xzf "$WORK/opus-1.3.1.tar.gz"
fi
SRC="$WORK/opus-1.3.1"

build_opus() {
  local name=$1 sdk=$2 minflag=$3 out=$4
  rm -rf "$SRC/build-$name"
  mkdir -p "$SRC/build-$name" && cd "$SRC/build-$name"
  "$SRC/configure" --host=arm-apple-darwin --disable-shared --enable-static --with-pic \
    CC="$CLANG -arch arm64 -isysroot $sdk $minflag" CFLAGS="-O2" LDFLAGS="-arch arm64" >/dev/null
  make -j"$(sysctl -n hw.ncpu)" >/dev/null
  cp .libs/libopus.a "$out"
}

build_opus device "$IOS_SDK" "-miphoneos-version-min=$MIN" "$WORK/libopus-device.a"
build_opus sim "$SIM_SDK" "-mios-simulator-version-min=$MIN" "$WORK/libopus-sim.a"

OPUS_XCF_WORK="$WORK/opus-xcf"
rm -rf "$OPUS_XCF_WORK" "$OPUS_POD/libopus.xcframework"
mkdir -p "$OPUS_XCF_WORK/ios-arm64/Headers" "$OPUS_XCF_WORK/ios-arm64-simulator/Headers"
cp "$WORK/libopus-device.a" "$OPUS_XCF_WORK/ios-arm64/libopus.a"
cp "$WORK/libopus-sim.a" "$OPUS_XCF_WORK/ios-arm64-simulator/libopus.a"
cp -R "$OPUS_POD/libopus/." "$OPUS_XCF_WORK/ios-arm64/Headers/"
cp -R "$OPUS_POD/libopus/." "$OPUS_XCF_WORK/ios-arm64-simulator/Headers/"
xcodebuild -create-xcframework \
  -library "$OPUS_XCF_WORK/ios-arm64/libopus.a" -headers "$OPUS_XCF_WORK/ios-arm64/Headers" \
  -library "$OPUS_XCF_WORK/ios-arm64-simulator/libopus.a" -headers "$OPUS_XCF_WORK/ios-arm64-simulator/Headers" \
  -output "$OPUS_POD/libopus.xcframework"

echo "==> libvpx 1.4.0 device (arm64-darwin-gcc)"
if [[ ! -d "$WORK/libvpx-device-1.4.0" ]]; then
  curl -fsSL -o "$WORK/v1.4.0.tar.gz" https://github.com/webmproject/libvpx/archive/refs/tags/v1.4.0.tar.gz
  tar -C "$WORK" -xzf "$WORK/v1.4.0.tar.gz"
  mv "$WORK/libvpx-1.4.0" "$WORK/libvpx-device-1.4.0"
  patch -d "$WORK/libvpx-device-1.4.0" -p1 < "$IOS_DIR/local_pod_repo/toxcore/vpx-ios.diff"
fi
cd "$WORK/libvpx-device-1.4.0"
./build/make/iosbuild.sh --targets "arm64-darwin-gcc" --jobs "$(sysctl -n hw.ncpu)" >/dev/null
cp "$WORK/libvpx-device-1.4.0/vpx.framework/vpx" "$VPX_FW/vpx-device"

echo "==> libvpx 1.4.0 simulator (arm64-darwin-gcc + iphonesimulator SDK)"
if [[ ! -d "$WORK/libvpx-sim-1.4.0" ]]; then
  tar -C "$WORK" -xzf "$WORK/v1.4.0.tar.gz"
  mv "$WORK/libvpx-1.4.0" "$WORK/libvpx-sim-1.4.0"
  cd "$WORK/libvpx-sim-1.4.0"
  sed -i '' 's/FRAMEWORK_DIR="VPX.framework"/FRAMEWORK_DIR="vpx.framework"/g; s/${FRAMEWORK_DIR}\/VPX/${FRAMEWORK_DIR}\/vpx/g' build/make/iosbuild.sh
  sed -i '' 's/show_darwin_sdk_path macosx/show_darwin_sdk_path iphonesimulator/g' build/make/configure.sh
  sed -i '' 's/alt_libc="$(show_darwin_sdk_path iphoneos)"/alt_libc="$(show_darwin_sdk_path iphonesimulator)"/g' build/make/configure.sh
  sed -i '' 's/add_ldflags -miphoneos-version-min="${IOS_VERSION_MIN}"/add_ldflags -mios-simulator-version-min=12.0/g' build/make/configure.sh
  sed -i '' 's/add_ldflags -ios_version_min "${IOS_VERSION_MIN}"/add_ldflags -mios-simulator-version-min=12.0/g' build/make/configure.sh
fi
cd "$WORK/libvpx-sim-1.4.0"
./build/make/iosbuild.sh --targets "arm64-darwin-gcc" --jobs "$(sysctl -n hw.ncpu)" >/dev/null
cp "$WORK/libvpx-sim-1.4.0/vpx.framework/vpx" "$VPX_FW/vpx-simulator"
cp "$VPX_FW/vpx-simulator" "$VPX_FW/vpx"

echo "OK: libopus.xcframework + vpx-device updated"
