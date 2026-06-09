#!/usr/bin/env bash
# Create Khandaq Android production release keystore (one-time).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SECRETS="$ROOT/secrets"
KEYSTORE="$SECRETS/khandaq-release.keystore"
ENV_FILE="$SECRETS/android-signing.env"
ALIAS="${KHANDAQ_ANDROID_KEY_ALIAS:-khandaq}"

mkdir -p "$SECRETS"
chmod 700 "$SECRETS"

if [[ -f "$KEYSTORE" ]]; then
  echo "Keystore already exists: $KEYSTORE"
  echo "Delete manually to regenerate."
  exit 1
fi

STORE_PASS="$(openssl rand -base64 24 | tr -d '/+=' | head -c 24)"
KEY_PASS="$STORE_PASS"

echo "==> Generating release keystore..."
keytool -genkeypair -v \
  -keystore "$KEYSTORE" \
  -alias "$ALIAS" \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -storepass "$STORE_PASS" -keypass "$KEY_PASS" \
  -dname "CN=Khandaq Messenger, OU=Engineering, O=Khandaq, L=Unknown, ST=Unknown, C=US"

chmod 600 "$KEYSTORE"

cat > "$ENV_FILE" <<EOF
# Khandaq Android production signing — KEEP OFFLINE BACKUP
KHANDAQ_ANDROID_KEYSTORE=$KEYSTORE
KHANDAQ_ANDROID_KEY_ALIAS=$ALIAS
KHANDAQ_ANDROID_STORE_PASS=$STORE_PASS
KHANDAQ_ANDROID_KEY_PASS=$KEY_PASS
EOF
chmod 600 "$ENV_FILE"

BT="${ANDROID_HOME:-$HOME/Library/Android/sdk}/build-tools"
BT="$(ls -1d "$BT"/* 2>/dev/null | sort -V | tail -1)"

echo ""
echo "==> Keystore created: $KEYSTORE"
echo "==> Env file: $ENV_FILE"
echo ""
echo "==> SHA-256 certificate fingerprint:"
keytool -list -v -keystore "$KEYSTORE" -alias "$ALIAS" -storepass "$STORE_PASS" 2>/dev/null \
  | grep -E 'SHA256:|SHA1:|MD5:' || true

if [[ -n "$BT" && -x "$BT/apksigner" ]]; then
  echo ""
  echo "==> apksigner cert dump:"
  "$BT/apksigner" verify --print-certs "$KEYSTORE" 2>/dev/null || true
fi

echo ""
echo "BACKUP: copy $KEYSTORE and $ENV_FILE to encrypted offline storage."
echo "Never commit these files to git."
