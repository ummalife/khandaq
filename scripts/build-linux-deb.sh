#!/usr/bin/env bash
# Build khandaq-messenger .deb from the portable Linux bundle (dist/linux).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/dist/linux"
OUT="${KHANDAQ_DEB_OUT:-$ROOT/dist/linux}"
if [[ -z "${KHANDAQ_VERSION:-}" && -f "$ROOT/VERSION" ]]; then
  KHANDAQ_VERSION="$(tr -d '[:space:]' < "$ROOT/VERSION")"
fi
VERSION="${KHANDAQ_VERSION:-0.2.6}"
PKG="khandaq-messenger_${VERSION}_amd64"
ICONS="$ROOT/khandaq-desktop/img/icons"
IMAGE="${KHANDAQ_DEB_IMAGE:-ubuntu:24.04}"

for f in khandaq khandaq.bin lib; do
  [[ -e "$SRC/$f" ]] || { echo "Missing $SRC/$f — run build-linux-docker.sh first"; exit 1; }
done

STAGING="$(mktemp -d)"
trap 'rm -rf "$STAGING"' EXIT

PKGROOT="$STAGING/$PKG"
mkdir -p "$PKGROOT/DEBIAN"
mkdir -p "$PKGROOT/opt/khandaq"
mkdir -p "$PKGROOT/usr/bin"
mkdir -p "$PKGROOT/usr/share/applications"
mkdir -p "$PKGROOT/usr/share/metainfo"
mkdir -p "$PKGROOT/usr/share/doc/khandaq-messenger"

echo "==> Stage application files"
cp -a "$SRC/khandaq" "$SRC/khandaq.bin" "$PKGROOT/opt/khandaq/"
cp -a "$SRC/lib" "$PKGROOT/opt/khandaq/"
[[ -d "$SRC/plugins" ]] && cp -a "$SRC/plugins" "$PKGROOT/opt/khandaq/"
ln -sf /opt/khandaq/khandaq "$PKGROOT/usr/bin/khandaq"

sed 's|^Exec=.*|Exec=khandaq %u|' "$SRC/khandaq.desktop" > "$PKGROOT/usr/share/applications/khandaq.desktop"
cp -f "$SRC/org.khandaq.messenger.appdata.xml" "$PKGROOT/usr/share/metainfo/"
[[ -f "$SRC/INSTALL.txt" ]] && cp -f "$SRC/INSTALL.txt" "$PKGROOT/usr/share/doc/khandaq-messenger/README"

if [[ -d "$ICONS" ]]; then
  for size in 16 22 24 32 48 64 72 96 128 192 256 512; do
    icon="$ICONS/${size}x${size}/khandaq.png"
    if [[ -f "$icon" ]]; then
      mkdir -p "$PKGROOT/usr/share/icons/hicolor/${size}x${size}/apps"
      cp -f "$icon" "$PKGROOT/usr/share/icons/hicolor/${size}x${size}/apps/khandaq.png"
    fi
  done
fi

sed "s/@VERSION@/$VERSION/" "$ROOT/packaging/debian/control.in" > "$PKGROOT/DEBIAN/control"

cat > "$PKGROOT/DEBIAN/postinst" <<'EOF'
#!/bin/sh
set -e
if command -v update-desktop-database >/dev/null 2>&1; then
  update-desktop-database /usr/share/applications 2>/dev/null || true
fi
if command -v gtk-update-icon-cache >/dev/null 2>&1; then
  gtk-update-icon-cache -f -t /usr/share/icons/hicolor 2>/dev/null || true
fi
exit 0
EOF
chmod 755 "$PKGROOT/DEBIAN/postinst"

echo "==> Build .deb inside Docker ($IMAGE)"
mkdir -p "$OUT"
docker run --rm --platform linux/amd64 \
  -v "$STAGING:/work:ro" \
  -v "$OUT:/out" \
  "$IMAGE" \
  bash -ec "
    set -euo pipefail
    apt-get update -qq
    apt-get install -y -qq dpkg-dev >/dev/null
    dpkg-deb --build /work/$PKG /out/${PKG}.deb
    dpkg-deb -I /out/${PKG}.deb
    ls -lh /out/${PKG}.deb
  "

echo "==> SHA256"
shasum -a 256 "$OUT/${PKG}.deb" | tee "$OUT/${PKG}.deb.sha256"
echo "Done: $OUT/${PKG}.deb"
