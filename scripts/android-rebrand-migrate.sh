#!/usr/bin/env bash
# Khandaq Android package migration (JNI-safe: MainActivity + TrifaToxService stay in com.zoffcc.applications.trifa)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP="$ROOT/khandaq-android-trifa/android-refimpl-app"
JAVA="$APP/app/src/main/java"
OLD_PKG="com.zoffcc.applications.trifa"
NEW_PKG="org.khandaq.messenger"
OLD_PATH="com/zoffcc/applications/trifa"
NEW_PATH="org/khandaq/messenger"

JNI_KEEP=(
  "MainActivity.java"
  "TrifaToxService.java"
)

echo "==> Creating target package directory"
mkdir -p "$JAVA/$NEW_PATH"

echo "==> Moving Java/Kotlin sources (except JNI-bound classes)"
for f in "$JAVA/$OLD_PATH"/*; do
  base=$(basename "$f")
  skip=0
  for k in "${JNI_KEEP[@]}"; do
    [[ "$base" == "$k" ]] && skip=1 && break
  done
  [[ $skip -eq 1 ]] && continue
  mv "$f" "$JAVA/$NEW_PATH/"
done

echo "==> Bulk replace package references in app module (skip JNI-bound sources)"
while IFS= read -r -d '' file; do
  case "$file" in
    *"/$OLD_PATH/MainActivity.java"|*"/$OLD_PATH/TrifaToxService.java") continue ;;
  esac
  if grep -q "$OLD_PKG" "$file" 2>/dev/null || grep -q "$OLD_PATH" "$file" 2>/dev/null; then
    sed -i '' \
      -e "s/${OLD_PKG//./\\.}/${NEW_PKG//./\\.}/g" \
      -e "s|${OLD_PATH}|${NEW_PATH}|g" \
      "$file"
  fi
done < <(find "$APP/app" -type f \( -name '*.java' -o -name '*.kt' -o -name '*.xml' -o -name '*.gradle' \) -print0)

echo "==> Creating launcher MainActivity subclass in $NEW_PKG"
cat > "$JAVA/$NEW_PATH/MainActivity.java" <<'EOF'
package org.khandaq.messenger;

/**
 * Public launcher activity for Khandaq Messenger.
 * JNI native methods remain on com.zoffcc.applications.trifa.MainActivity (prebuilt libjni-c-toxcore.so).
 */
public class MainActivity extends com.zoffcc.applications.trifa.MainActivity
{
}
EOF

echo "==> Update build.gradle applicationId and namespace"
sed -i '' \
  -e 's/applicationId "com.zoffcc.applications.trifa"/applicationId "org.khandaq.messenger"/' \
  -e "s/namespace 'com.zoffcc.applications.trifa'/namespace 'org.khandaq.messenger'/" \
  -e "s|exclude 'com/zoffcc/applications/trifa/VideoFrameAnalyser.java'|exclude 'org/khandaq/messenger/VideoFrameAnalyser.java'|g" \
  "$APP/app/build.gradle"

echo "==> Fix AndroidManifest JNI service FQCN"
sed -i '' \
  -e 's/android:label="TRIfA"/android:label="@string\/app_name"/' \
  -e 's/android:name="\.TrifaToxService"/android:name="com.zoffcc.applications.trifa.TrifaToxService"/' \
  "$APP/app/src/main/AndroidManifest.xml"

# Provider authorities and intent actions
sed -i '' \
  -e 's/com\.zoffcc\.applications\.trifa\.std_fileprovider/org.khandaq.messenger.std_fileprovider/g' \
  -e 's/com\.zoffcc\.applications\.trifa\.ext2_provider/org.khandaq.messenger.ext2_provider/g' \
  -e 's/com\.zoffcc\.applications\.trifa\.ext1_fileprovider/org.khandaq.messenger.ext1_fileprovider/g' \
  -e 's/com\.zoffcc\.applications\.trifa\.TOXSERVICE_ALARM/org.khandaq.messenger.TOXSERVICE_ALARM/g' \
  -e 's/com\.zoffcc\.applications\.trifa\.EXTERN_RECV/org.khandaq.messenger.EXTERN_RECV/g' \
  -e 's/com\.zoffcc\.applications\.trifa\.TOKEN_CHANGED/org.khandaq.messenger.TOKEN_CHANGED/g' \
  "$APP/app/src/main/AndroidManifest.xml"

echo "==> Migration script done"
