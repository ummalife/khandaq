#!/usr/bin/env bash
# Build Antidote iOS (Zoxcore fork) — upstream as-is, simulator .app
# Note: Xcode 15+ may fail on Pods/libsodium alignment — use fetch-ios-antidote.sh for IPA
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="${KHANDAQ_IOS_SRC:-$ROOT/khandaq-ios}"
DIST="$ROOT/dist/ios"
SCHEME="${IOS_SCHEME:-Antidote}"

command -v xcodebuild >/dev/null || { echo "Xcode required"; exit 1; }
command -v pod >/dev/null || { echo "CocoaPods required: gem install cocoapods"; exit 1; }
[[ -d "$SRC" ]] || { echo "Missing $SRC"; exit 1; }

mkdir -p "$DIST"
cd "$SRC"

if [[ ! -f Podfile.lock ]]; then
  echo "==> pod install..."
  pod install
fi

[[ -d Antidote.xcworkspace ]] || { echo "Antidote.xcworkspace missing after pod install"; exit 1; }

SIM_ID=$(xcrun simctl list devices available -j 2>/dev/null | python3 -c "
import json,sys
data=json.load(sys.stdin)
for r in data.get('devices',{}).values():
  for d in r:
    if d.get('isAvailable') and 'iPhone' in d.get('name',''):
      print(d['udid']); raise SystemExit
" 2>/dev/null || true)
DEST="${IOS_DESTINATION:-platform=iOS Simulator,id=${SIM_ID}}"
echo "==> xcodebuild ($SCHEME, $DEST)..."
xcodebuild \
  -workspace Antidote.xcworkspace \
  -scheme "$SCHEME" \
  -configuration Release \
  -destination "$DEST" \
  -derivedDataPath "$SRC/build/DerivedData" \
  CODE_SIGNING_ALLOWED=NO \
  build 2>&1 | tee "$DIST/xcodebuild.log"

APP=$(find "$SRC/build/DerivedData" -name '*.app' -path '*Release-iphonesimulator*' | head -1)
if [[ -n "$APP" && -d "$APP" ]]; then
  rm -rf "$DIST/Antidote.app"
  cp -a "$APP" "$DIST/Antidote.app"
  ( cd "$DIST" && zip -qr Antidote-simulator.zip Antidote.app )
  echo "iOS simulator build OK: $DIST/Antidote-simulator.zip"
else
  echo "Build finished but .app not found — see $DIST/xcodebuild.log"
  exit 1
fi
