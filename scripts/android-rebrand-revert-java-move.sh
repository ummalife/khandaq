#!/usr/bin/env bash
# Revert Java package split — keep applicationId/namespace org.khandaq.messenger, Java stays com.zoffcc.applications.trifa (JNI)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVA="$ROOT/khandaq-android-trifa/android-refimpl-app/app/src/main/java"
OLD_PATH="com/zoffcc/applications/trifa"
NEW_PATH="org/khandaq/messenger"
OLD_PKG="com.zoffcc.applications.trifa"
NEW_PKG="org.khandaq.messenger"

mkdir -p "$JAVA/$OLD_PATH"

# Remove stub MainActivity
rm -f "$JAVA/$NEW_PATH/MainActivity.java"

# Move everything back
for f in "$JAVA/$NEW_PATH"/*; do
  [[ -e "$f" ]] || continue
  mv "$f" "$JAVA/$OLD_PATH/"
done
rmdir "$JAVA/$NEW_PATH" 2>/dev/null || true
rmdir "$JAVA/org/khandaq" 2>/dev/null || true
rmdir "$JAVA/org" 2>/dev/null || true

# Revert package declarations in moved-back sources
find "$JAVA/$OLD_PATH" -name '*.java' -o -name '*.kt' | while read -r f; do
  sed -i '' "s/^package ${NEW_PKG//./\\.};/package ${OLD_PKG//./\\.};/" "$f"
  sed -i '' "s/${NEW_PKG//./\\.}/${OLD_PKG//./\\.}/g" "$f"
done

# Revert XML layout FQCNs in app
find "$ROOT/khandaq-android-trifa/android-refimpl-app/app" -name '*.xml' -exec grep -l "$NEW_PKG" {} \; 2>/dev/null | while read -r f; do
  sed -i '' "s/${NEW_PKG//./\\.}/${OLD_PKG//./\\.}/g" "$f"
done

# Add R/BuildConfig imports where missing (namespace is org.khandaq.messenger)
for f in "$JAVA/$OLD_PATH"/*.java; do
  [[ -f "$f" ]] || continue
  if grep -q '\bR\.' "$f" && ! grep -q 'import org.khandaq.messenger.R;' "$f"; then
    sed -i '' "/^package /a\\
\\
import org.khandaq.messenger.R;
" "$f"
  fi
  if grep -q 'BuildConfig' "$f" && ! grep -q 'import org.khandaq.messenger.BuildConfig;' "$f"; then
    sed -i '' "/^package /a\\
\\
import org.khandaq.messenger.BuildConfig;
" "$f"
  fi
done

# Manifest: relative names OK with namespace; restore TrifaToxService to relative
sed -i '' \
  -e 's/android:name="com.zoffcc.applications.trifa.TrifaToxService"/android:name=".TrifaToxService"/' \
  "$ROOT/khandaq-android-trifa/android-refimpl-app/app/src/main/AndroidManifest.xml"

echo "Java package reverted to com.zoffcc.applications.trifa"
