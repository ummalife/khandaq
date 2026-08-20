#!/usr/bin/env bash
# Deploy Khandaq main site to https://khandaq.org/
# - Backs up legacy Element/Matrix static UI
# - Updates nginx static locations (Matrix API proxies unchanged)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WEB="$ROOT/web"
DL="$WEB/downloads"
REMOTE="${KHANDAQ_SITE_REMOTE:-Khandaq}"
REMOTE_SITE_DIR="${KHANDAQ_SITE_DIR:-/var/www/khandaq-site}"
REMOTE_BACKUP_ROOT="${KHANDAQ_BACKUP_DIR:-/var/www/backups}"
NGINX_SITE="/etc/nginx/sites-enabled/khandaq.org"
STAMP="$(date -u +%Y%m%d-%H%M%S)"

mkdir -p "$DL"

echo "==> Generate changelog from git"
python3 "$ROOT/scripts/generate-changelog.py"

# KHANDAQ: Android is now distributed via Google Play (official), so the website no longer hosts a
# sideload/debug APK. (This also closes KHQ-01: a debug-signed APK could previously be published here
# via the build-script fallback.) The site links to the Play listing instead. The tester APK still goes
# to the GitHub release for internal testers only — never to the public site.
echo "==> Android: skipping APK hosting (site links to Google Play)"

echo "==> Package desktop/mobile mirrors (optional — skip missing)"
if [[ -d "$ROOT/dist/macos/khandaq.app" ]]; then
  rm -f "$DL/khandaq-macos.zip"
  ditto -c -k --keepParent "$ROOT/dist/macos/khandaq.app" "$DL/khandaq-macos.zip"
fi
if [[ -f "$ROOT/dist/linux/khandaq" && -f "$ROOT/dist/linux/khandaq.bin" && -d "$ROOT/dist/linux/lib" ]]; then
  # Only ship the -portable name; the bare khandaq-linux-x86_64.tar.gz was an
  # unreferenced byte-identical duplicate (removed from the mirror).
  rm -f "$DL/khandaq-linux-x86_64.tar.gz" "$DL/khandaq-linux-x86_64-portable.tar.gz"
  tar -C "$ROOT/dist/linux" -czf "$DL/khandaq-linux-x86_64-portable.tar.gz" \
    khandaq khandaq.bin lib plugins khandaq.desktop org.khandaq.messenger.appdata.xml INSTALL.txt 2>/dev/null \
    || tar -C "$ROOT/dist/linux" -czf "$DL/khandaq-linux-x86_64-portable.tar.gz" khandaq khandaq.bin lib INSTALL.txt
fi
DEB="$(ls -1t "$ROOT"/dist/linux/khandaq-messenger_*_amd64.deb 2>/dev/null | head -1)"
if [[ -n "$DEB" && -f "$DEB" ]]; then
  DEB_VER="$(basename "$DEB" | sed -n 's/khandaq-messenger_\(.*\)_amd64\.deb/\1/p')"
  cp -f "$DEB" "$DL/khandaq-messenger_amd64.deb"
  if [[ -n "$DEB_VER" ]]; then
    cp -f "$DEB" "$DL/khandaq-messenger_${DEB_VER}_amd64.deb"
  fi
fi
WIN_EXE="$ROOT/dist/windows/x86_64/khandaq-windows-installer.exe"
[[ -f "$WIN_EXE" ]] || WIN_EXE="$ROOT/dist/windows/x86_64/Khandaq-installer.exe"
[[ -f "$WIN_EXE" ]] && cp -f "$WIN_EXE" "$DL/khandaq-windows-installer.exe"

# KHANDAQ (audit 2026-08-20): MERGE the checksum list, never blindly overwrite it.
#
# This used to be a plain `shasum -a 256 khandaq-* > SHA256SUMS.txt`, computed over whatever
# happened to be in the downloads directory at that moment. Every staging step above is conditional
# on $ROOT/dist/... existing, and dist/ is gitignored — so deploying a copy change from a clean
# checkout stages nothing, the glob matches only the one .deb that is committed, and the published
# SHA256SUMS.txt is silently truncated to a single line.
#
# The binaries themselves stay on the server and stay downloadable. So the file the site tells users
# to verify with ("shasum -a 256 -c SHA256SUMS.txt", web/index.html) simply stops containing them,
# and the one integrity control offered to users disappears without any error — which is worse than
# never offering it, because a missing entry reads as "nothing to check" rather than as a failure.
#
# Entries computed now win; entries for artifacts not staged this run are carried over.
( cd "$DL" && {
    fresh="$(mktemp)"
    shasum -a 256 khandaq-* 2>/dev/null | sort -u > "$fresh" || true

    if [[ ! -s "$fresh" && -s SHA256SUMS.txt ]]; then
      echo "    WARNING: no artifacts staged; leaving the existing SHA256SUMS.txt untouched" >&2
    else
      if [[ -s SHA256SUMS.txt ]]; then
        # Carry over any entry whose FILE is not being republished in this run.
        #
        # The filename is extracted by stripping the hash rather than taken as $2, because the two
        # tools that produce these lines disagree about the separator: macOS `shasum` writes
        # "HASH  name" while GNU `sha256sum` in binary mode writes "HASH *name". Keying on $2 works
        # under one and silently duplicates every entry under the other — which is exactly the kind
        # of difference that shows up only on someone else's machine.
        awk '
          function fname(s) { sub(/^[0-9a-fA-F]+[ \t]+\*?/, "", s); return s }
          NR == FNR { republished[fname($0)] = 1; next }
          !(fname($0) in republished)
        ' "$fresh" SHA256SUMS.txt >> "$fresh" || true
      fi
      # Sorted by filename for a readable published file; correctness comes from the dedup above.
      awk '{ n = $0; sub(/^[0-9a-fA-F]+[ \t]+\*?/, "", n); print n "\t" $0 }' "$fresh" \
        | sort -f | cut -f2- > SHA256SUMS.txt
    fi
    rm -f "$fresh"
  } )
echo "    SHA256SUMS.txt: $(wc -l < "$DL/SHA256SUMS.txt" 2>/dev/null || echo 0) entries"

echo "==> Backup Element/Matrix static site on server"
ssh "$REMOTE" "mkdir -p '$REMOTE_BACKUP_ROOT' && \
  if [[ -d /var/www/element ]]; then \
    cp -a /var/www/element '$REMOTE_BACKUP_ROOT/element-matrix-$STAMP' && \
    cp '$NGINX_SITE' '$REMOTE_BACKUP_ROOT/khandaq.org.nginx.$STAMP.bak'; \
    echo 'Backup: $REMOTE_BACKUP_ROOT/element-matrix-$STAMP'; \
  fi"

echo "==> Upload Khandaq site to $REMOTE:$REMOTE_SITE_DIR"
ssh "$REMOTE" "mkdir -p '$REMOTE_SITE_DIR/downloads'"
# KHANDAQ (KHQ-01): purge any previously-published sideload APK from the live server so it can't be
# fetched by direct URL after the site switched to Google Play.
ssh "$REMOTE" "rm -f '$REMOTE_SITE_DIR/downloads/khandaq-android.apk' '$REMOTE_SITE_DIR/downloads/khandaq-release.apk' '$REMOTE_SITE_DIR/downloads/SHA256SUMS.android.txt' 2>/dev/null || true"
scp -p "$WEB/index.html" "$WEB/changelog.html" "$WEB/changelog.json" "$WEB/style.css" "$WEB/robots.txt" "$WEB/sitemap.xml" "$REMOTE:$REMOTE_SITE_DIR/"
scp -pr "$WEB/assets" "$REMOTE:$REMOTE_SITE_DIR/"
scp -p "$DL"/* "$REMOTE:$REMOTE_SITE_DIR/downloads/" 2>/dev/null || true
scp -p "$ROOT/infra/nginx/khandaq-static-site.locations.conf" "$REMOTE:/tmp/khandaq-static-site.locations.conf"

echo "==> Patch nginx (static UI only; Matrix API unchanged)"
ssh "$REMOTE" "python3 <<'PY'
from pathlib import Path
import sys

nginx = Path('$NGINX_SITE')
snippet = Path('/tmp/khandaq-static-site.locations.conf').read_text()
if not snippet.endswith('\n'):
    snippet += '\n'
lines = nginx.read_text().splitlines(keepends=True)

start = next((i for i, l in enumerate(lines) if 'location = /messenger' in l), None)
if start is None:
    start = next((i for i, l in enumerate(lines) if 'Настройки для Element Web' in l), None)
if start is None:
    start = next((i for i, l in enumerate(lines) if 'location = /index.html' in l), None)
idx = next((i for i, l in enumerate(lines) if l.strip() == 'location / {' and i > (start or 0)), None)
if start is None or idx is None:
    print('ERROR: static site block not found', file=sys.stderr)
    sys.exit(1)

depth = 0
end = None
for j in range(idx, len(lines)):
    depth += lines[j].count('{') - lines[j].count('}')
    if j > idx and depth <= 0:
        end = j
        break
if end is None:
    print('ERROR: end of location / not found', file=sys.stderr)
    sys.exit(1)

new_lines = lines[:start] + [snippet] + lines[end + 1:]
nginx.write_text(''.join(new_lines))
print(f'Patched {nginx}: lines {start + 1}-{end + 1}')
PY
nginx -t && systemctl reload nginx"

echo "==> Permissions"
ssh "$REMOTE" "chown -R www-data:www-data '$REMOTE_SITE_DIR' && chmod -R a+r '$REMOTE_SITE_DIR' && find '$REMOTE_SITE_DIR' -type d -exec chmod 755 {} +"

echo "==> Done"
echo "    https://khandaq.org/"
echo "    Backup: $REMOTE_BACKUP_ROOT/element-matrix-$STAMP"
ls -lh "$DL" 2>/dev/null || true
