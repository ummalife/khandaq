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

# KHANDAQ (re-audit 2026-08-22, W-02): snapshot before, and a way back.
#
# The audit asks for a rollback that "restores previous internally-consistent manifest/artifacts".
# There was none: the deploy overwrote the live site in place, so a bad publish could only be undone
# by fixing forward — which is the worst moment to be editing. Every deploy now leaves a hardlinked
# snapshot of the whole served tree (pages, manifest, checksums, signatures and artifacts together,
# because a manifest without its artifacts is not a consistent state), and --rollback puts the most
# recent one back and re-runs the same external verification.
SNAP_ROOT="${KHANDAQ_SITE_SNAPSHOTS:-/var/www/backups/site-snapshots}"

if [[ "${1:-}" == "--rollback" ]]; then
  echo "==> Rolling back $REMOTE:$REMOTE_SITE_DIR"
  PREV="$(ssh "$REMOTE" "ls -1d '$SNAP_ROOT'/* 2>/dev/null | sort | tail -1")" || PREV=""
  if [[ -z "$PREV" ]]; then
    echo "нет ни одного снимка в $SNAP_ROOT — откатывать не к чему" >&2
    exit 1
  fi
  echo "    снимок: $PREV"
  ssh "$REMOTE" "set -e
    rm -rf '$REMOTE_SITE_DIR.rollback-tmp'
    cp -al '$PREV' '$REMOTE_SITE_DIR.rollback-tmp' 2>/dev/null || cp -a '$PREV' '$REMOTE_SITE_DIR.rollback-tmp'
    # KHANDAQ (deep review 2026-08-23, RR3-12): drop the snapshot's nginx copy BEFORE the tree is
    # served, not after.
    #
    # The snapshot keeps the CSP snippet and the vhost inside itself so a rollback can restore them
    # together with the content. Removing them after the swap left them reachable over HTTP for the
    # seconds in between — and the chmod -R a+r on the way past made them world-readable first. They
    # are read from $PREV/_nginx below, so the served tree never needs to contain them at all.
    rm -rf '$REMOTE_SITE_DIR.rollback-tmp/_nginx'
    rm -rf '$REMOTE_SITE_DIR.superseded'
    mv '$REMOTE_SITE_DIR' '$REMOTE_SITE_DIR.superseded'
    mv '$REMOTE_SITE_DIR.rollback-tmp' '$REMOTE_SITE_DIR'
    rm -rf '$REMOTE_SITE_DIR.superseded'
    chown -R www-data:www-data '$REMOTE_SITE_DIR'
    chmod -R a+r '$REMOTE_SITE_DIR'
    find '$REMOTE_SITE_DIR' -type d -exec chmod 755 {} +
    # Put the CSP back with the content it belongs to, and only then reload.
    if [ -f '$PREV/_nginx/khandaq-security-headers.conf' ]; then
      cp -a '$PREV/_nginx/khandaq-security-headers.conf' /etc/nginx/snippets/khandaq-security-headers.conf
      echo '    restored: khandaq-security-headers.conf'
    else
      echo '    WARNING: snapshot predates nginx snapshotting - CSP hashes NOT rolled back' >&2
    fi
    if [ -f '$PREV/_nginx/site.conf' ]; then
      cp -a '$PREV/_nginx/site.conf' '$NGINX_SITE'
      echo '    restored: nginx site'
    fi
    # Belt to the braces above: if a future change reintroduces _nginx into the served tree, this
    # still takes it out. It is not the control — the control is that it was never copied in.
    rm -rf '$REMOTE_SITE_DIR/_nginx'
    # Explicit rather than 'nginx -t && reload': in an AND-list a failing first command does not trip
    # set -e, so a bad config would have been followed by a silent non-reload and a green rollback.
    if ! nginx -t; then
      echo 'ОШИБКА: восстановленный конфиг nginx не проходит проверку — reload не делаю' >&2
      exit 1
    fi
    systemctl reload nginx"
  echo "==> Verifying the rolled-back site"
  RB_SHA="$(curl -fsS https://khandaq.org/release-manifest.json | python3 -c 'import sys,json;print(json.load(sys.stdin)["site"]["gitSha"])')" || RB_SHA=""
  if [[ -z "$RB_SHA" ]]; then
    echo "не удалось прочитать gitSha восстановленного манифеста" >&2
    exit 1
  fi
  echo "    восстановленный сайт собран из $RB_SHA"
  # KHANDAQ (0.2.65): the desktop update manifest served now is the snapshot's, not this checkout's.
  # Apps that verified a newer one refuse it (its serial is lower) until a release publishes a
  # manifest with a higher serial; the rest are offered what the snapshot offers.
  echo "    desktop update manifest: the snapshot's; apps that saw a newer one wait for the next release"
  python3 "$ROOT/scripts/verify-site-deploy.py" --sha "$RB_SHA" --kept-update-manifest
  exit $?
fi

mkdir -p "$DL"

echo "==> Generate changelog from git"
python3 "$ROOT/scripts/generate-changelog.py"

# KHANDAQ (re-audit 2026-08-21, R-07): regenerate the release manifest and REFUSE to publish if any
# page still contradicts it. The site is the last place a user can check what they are installing
# against what was signed, so shipping numbers that disagree with the build is worse than shipping
# nothing — it teaches people the comparison is pointless.
echo "==> Release manifest + published-metadata check"
python3 "$ROOT/scripts/generate-release-manifest.py" >/dev/null
python3 "$ROOT/scripts/check-release-metadata.py"

# KHANDAQ (re-audit 2026-08-22, W-01/W-03): the header baseline is generated from the pages, so an
# edit to any inline <script> changes its CSP hash. Regenerating here and refusing to continue when
# the committed snippet is stale is the difference between "the deploy fails" and "the deploy
# succeeds and the page silently stops working in every browser".
echo "==> Security headers + security.txt"
python3 "$ROOT/scripts/generate-security-headers.py"
python3 "$ROOT/scripts/generate-security-headers.py" --check
python3 "$ROOT/scripts/check-security-txt.py"
python3 "$ROOT/scripts/vendor-webfonts.py" --check
# KHANDAQ (redesign E23, 2026-11-11): /assets/ is cached for a week and /style.css for an hour. A page
# whose ?v= stamp does not match the file it links would meet the visitor's cached copy, so the deploy
# refuses instead of restamping on its own; scripts/version-site-assets.py restamps on purpose.
python3 "$ROOT/scripts/version-site-assets.py" --check

# KHANDAQ: Android is now distributed via Google Play (official), so the website no longer hosts a
# sideload/debug APK. (This also closes KHQ-01: a debug-signed APK could previously be published here
# via the build-script fallback.) The site links to the Play listing instead. The tester APK still goes
# to the GitHub release for internal testers only — never to the public site.
echo "==> Android: skipping APK hosting (site links to Google Play)"

# KHANDAQ (deep review 2026-08-23, RR3-08): staging a freshly built artifact must take its stale
# signature with it.
#
# The signer treats "file present, signature present, does not verify" as an incident and stops the
# deploy — correct, and it rests on the claim that a rebuild never leaves an old .sig beside the new
# bytes. That claim was false HERE: every line below copies into web/downloads over the previous
# file, the .sig files are committed (the binaries are gitignored, so a clean checkout has the
# signature and not the artifact), and nothing removed them. The first desktop rebuild would have
# hit the incident path and refused to publish a legitimate build.
drop_stale_sig() {
  for name in "$@"; do
    rm -f "$DL/$name.sig"
  done
}

# KHANDAQ (0.2.65): the downloads the desktop app updates from that this run staged from dist/. The
# update manifest is written from these files and nothing else, and above all never from a file
# fetched from the server further down: the manifest tells every app which bytes to install.
UPDATE_STAGED=()

echo "==> Package desktop/mobile mirrors (optional — skip missing)"
if [[ -d "$ROOT/dist/macos/khandaq.app" ]]; then
  rm -f "$DL/khandaq-macos.zip"
  ditto -c -k --keepParent "$ROOT/dist/macos/khandaq.app" "$DL/khandaq-macos.zip"
  drop_stale_sig khandaq-macos.zip
  UPDATE_STAGED+=("$DL/khandaq-macos.zip")
fi
if [[ -f "$ROOT/dist/linux/khandaq" && -f "$ROOT/dist/linux/khandaq.bin" && -d "$ROOT/dist/linux/lib" ]]; then
  # Only ship the -portable name; the bare khandaq-linux-x86_64.tar.gz was an
  # unreferenced byte-identical duplicate (removed from the mirror).
  rm -f "$DL/khandaq-linux-x86_64.tar.gz" "$DL/khandaq-linux-x86_64-portable.tar.gz"
  tar -C "$ROOT/dist/linux" -czf "$DL/khandaq-linux-x86_64-portable.tar.gz" \
    khandaq khandaq.bin lib plugins khandaq.desktop org.khandaq.messenger.appdata.xml INSTALL.txt 2>/dev/null \
    || tar -C "$ROOT/dist/linux" -czf "$DL/khandaq-linux-x86_64-portable.tar.gz" khandaq khandaq.bin lib INSTALL.txt
  drop_stale_sig khandaq-linux-x86_64-portable.tar.gz khandaq-linux-x86_64.tar.gz
  UPDATE_STAGED+=("$DL/khandaq-linux-x86_64-portable.tar.gz")
fi
# KHANDAQ (re-audit 2026-08-22, W-02 — root cause): this line used to end the deploy.
#
# The script runs under `set -euo pipefail`. dist/ is gitignored, so on a clean checkout the glob
# matches nothing, `ls` exits 1, pipefail propagates that through `| head -1`, and because the exit
# status of `VAR=$(pipeline)` IS the pipeline's status, `set -e` terminated the script — here, before
# a single file had been uploaded, with no error message and a bare exit code nobody was reading.
#
# That is the drift the audit observed. The site was not stale because somebody forgot to deploy; it
# was stale because deploying from a clean checkout could not reach the upload step at all, and said
# nothing about it. Reproduced deliberately before fixing: exit 1, output stopping mid-way.
DEB="$(ls -1t "$ROOT"/dist/linux/khandaq-messenger_*_amd64.deb 2>/dev/null | head -1)" || DEB=""
if [[ -n "$DEB" && -f "$DEB" ]]; then
  DEB_VER="$(basename "$DEB" | sed -n 's/khandaq-messenger_\(.*\)_amd64\.deb/\1/p')"
  cp -f "$DEB" "$DL/khandaq-messenger_amd64.deb"
  drop_stale_sig khandaq-messenger_amd64.deb
  UPDATE_STAGED+=("$DL/khandaq-messenger_amd64.deb")
  if [[ -n "$DEB_VER" ]]; then
    cp -f "$DEB" "$DL/khandaq-messenger_${DEB_VER}_amd64.deb"
    drop_stale_sig "khandaq-messenger_${DEB_VER}_amd64.deb"
  fi
fi
WIN_EXE="$ROOT/dist/windows/x86_64/khandaq-windows-installer.exe"
[[ -f "$WIN_EXE" ]] || WIN_EXE="$ROOT/dist/windows/x86_64/Khandaq-installer.exe"
if [[ -f "$WIN_EXE" ]]; then
  cp -f "$WIN_EXE" "$DL/khandaq-windows-installer.exe"
  drop_stale_sig khandaq-windows-installer.exe
  UPDATE_STAGED+=("$DL/khandaq-windows-installer.exe")
fi
# KHANDAQ (desktop release v0.2.47): the portable zip is the Windows download the site leads with, and
# nothing staged it: every deploy carried over the zip it found on the server, so a new Windows build
# would have gone out as a new installer beside the old zip. CI names it khandaq-<arch>-Release.zip
# (khandaq-desktop/windows/cross-compile/build.sh); the release asset is khandaq-windows-x86_64.zip.
WIN_ZIP="$ROOT/dist/windows/x86_64/khandaq-windows-x86_64.zip"
[[ -f "$WIN_ZIP" ]] || WIN_ZIP="$ROOT/dist/windows/x86_64/khandaq-x86_64-Release.zip"
if [[ -f "$WIN_ZIP" ]]; then
  cp -f "$WIN_ZIP" "$DL/khandaq-windows-x86_64.zip"
  drop_stale_sig khandaq-windows-x86_64.zip
  UPDATE_STAGED+=("$DL/khandaq-windows-x86_64.zip")
fi

# KHANDAQ (0.2.65): the manifest the desktop app updates from, made from what was staged above. The
# generator reads each version from the program inside the artifact and refuses a build that is not
# the exact release tag, a QA build, and a platform going back to an older version. Platforms not
# staged in this run keep their entry while SHA256SUMS.txt still lists the same bytes for them. A
# manifest that changes gets a higher serial and loses its old signature, so the signer below signs
# it in its own namespace. Only a run that wrote it here publishes it (below, last).
if [[ ${#UPDATE_STAGED[@]} -gt 0 ]]; then
  echo "==> Desktop update manifest"
  python3 "$ROOT/scripts/generate-desktop-update-manifest.py" "${UPDATE_STAGED[@]}"
fi

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
    # KHANDAQ (0.2.65): the update manifest is not a download and has its own signature namespace;
    # the checksum list and verify-desktop-signatures.py treat every line here as a release artifact.
    for name in khandaq-*; do
      case "$name" in
        khandaq-desktop-update.json|khandaq-desktop-update.json.sig) continue ;;
      esac
      [[ -f "$name" ]] && shasum -a 256 "$name"
    done 2>/dev/null | sort -u > "$fresh" || true

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
          fname($0) ~ /^khandaq-desktop-update\.json/ { next }
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

# KHANDAQ (re-audit 2026-08-22, K-05): bring down anything already published that this checkout does
# not hold, so it is signed too.
#
# The desktop artifacts are built locally and live only on the server; dist/ is gitignored and a
# clean checkout stages none of them. Signing only what happens to be staged would therefore leave
# the Windows, macOS and Linux downloads — the three the audit is actually about — permanently
# unsigned, while the page told users to verify a signature. Fetching them means the signature is
# computed over the exact bytes being served, which is the only version of this that means anything.
echo "==> Fetch already-published artifacts that are not staged locally"
REMOTE_FILES="$(ssh "$REMOTE" "ls -1 '$REMOTE_SITE_DIR/downloads' 2>/dev/null" | tr -d '\r')" || REMOTE_FILES=""
for name in $REMOTE_FILES; do
  # Only release artifacts. The server's downloads directory has picked up strays over the years
  # (a copy of changelog.json among them); fetching and signing those would put files in this
  # checkout that are not releases and do not belong in the repository.
  case "$name" in
    khandaq-*) ;;
    *) continue ;;
  esac
  case "$name" in
    *.sig|*.pub) continue ;;
    # KHANDAQ (0.2.65): the update manifest is committed with its signature and written only from
    # artifacts built here; a copy from the server is never taken.
    khandaq-desktop-update.json) continue ;;
  esac
  if [[ ! -f "$DL/$name" ]]; then
    echo "    fetching $name"
    scp -q "$REMOTE:$REMOTE_SITE_DIR/downloads/$name" "$DL/$name"
    # KHANDAQ (internal audit 2026-08-22, H-01): bring the PUBLISHED signature down with the bytes,
    # and remember that these bytes came from the distribution channel.
    #
    # Without this the file arrived unsigned, the signing step saw a missing or wrong signature and
    # treated that as "changed, re-sign" - so a binary swapped on the web server was signed with the
    # release key on the next ordinary deploy. The one event that had to stop everything was the
    # trigger for blessing it. These names are refused by the signer below; the only thing that may
    # happen to them is verification against the signature they arrived with.
    scp -q "$REMOTE:$REMOTE_SITE_DIR/downloads/$name.sig" "$DL/$name.sig" 2>/dev/null || true
    FETCHED_ARTIFACTS="${FETCHED_ARTIFACTS:-} $name"
  fi
done
export FETCHED_ARTIFACTS="${FETCHED_ARTIFACTS:-}"
if [[ -n "${FETCHED_ARTIFACTS// /}" ]]; then
  echo "    доставлено с сервера (подписывать их нельзя):${FETCHED_ARTIFACTS}"
fi

# KHANDAQ (0.2.65, review of the desktop update): the update manifest goes up only from a run that
# staged a desktop download and wrote the manifest from it. Otherwise the live one stays as it is: the
# copy committed in this checkout can be older than the one live, and putting it back would hold the
# apps on an older release. Before it goes up, every SHA-256 it gives must be the one SHA256SUMS.txt
# publishes, and every download it names must be here, staged from dist/ or fetched just above, as
# those bytes: nothing else is uploaded. Then it must take nothing back from the live manifest, read
# from the server for this comparison and nothing else: not the serial, below which every app that
# verified the live one refuses to go, and not the version of any platform.
if [[ ${#UPDATE_STAGED[@]} -gt 0 ]]; then
  echo "==> Desktop update manifest against the downloads here and the live one"
  python3 "$ROOT/scripts/generate-desktop-update-manifest.py" --check --with-downloads
  LIVE_UPDATE="$(mktemp -d "${TMPDIR:-/tmp}/khandaq-live-update.XXXXXX")"
  trap 'rm -rf "$LIVE_UPDATE"' EXIT
  # Empty when none is live yet; a copy that fails stops the deploy.
  ssh "$REMOTE" "if [ -e '$REMOTE_SITE_DIR/downloads/khandaq-desktop-update.json' ]; then cat '$REMOTE_SITE_DIR/downloads/khandaq-desktop-update.json'; fi" > "$LIVE_UPDATE/live.json"
  python3 "$ROOT/scripts/generate-desktop-update-manifest.py" --against-live "$LIVE_UPDATE/live.json"
  rm -rf "$LIVE_UPDATE"
  trap - EXIT
else
  echo "==> Desktop update manifest: nothing staged from dist/, the live one stays as it is"
fi

# KHANDAQ (re-audit 2026-08-22, K-05): sign what is about to be published, with a key that is not
# this web server. A checksum list beside a download is an integrity check, not a trust anchor —
# whoever can replace the binary can replace the list, because both come down the same pipe. The
# detached signatures below are made with a key held on the release machine whose PUBLIC half is
# committed to the repository, so an attacker who owns the site still cannot produce one that
# verifies. Authenticode and Developer ID close the rest; see docs/DESKTOP-SIGNING.md.
echo "==> Sign desktop artifacts"
bash "$ROOT/scripts/sign-desktop-artifacts.sh"

echo "==> Backup Element/Matrix static site on server"
ssh "$REMOTE" "mkdir -p '$REMOTE_BACKUP_ROOT' && \
  if [[ -d /var/www/element ]]; then \
    cp -a /var/www/element '$REMOTE_BACKUP_ROOT/element-matrix-$STAMP' && \
    cp '$NGINX_SITE' '$REMOTE_BACKUP_ROOT/khandaq.org.nginx.$STAMP.bak'; \
    echo 'Backup: $REMOTE_BACKUP_ROOT/element-matrix-$STAMP'; \
  fi"

# Hardlinked, so a snapshot of ~400 MB of artifacts costs inodes rather than disk. Kept to the last
# five: enough to step back past a bad publish, not enough to fill the volume.
echo "==> Snapshot the currently-served site"
ssh "$REMOTE" "set -e
  mkdir -p '$SNAP_ROOT'
  if [ -d '$REMOTE_SITE_DIR' ] && [ -n \"\$(ls -A '$REMOTE_SITE_DIR' 2>/dev/null)\" ]; then
    cp -al '$REMOTE_SITE_DIR' '$SNAP_ROOT/$STAMP' 2>/dev/null || cp -a '$REMOTE_SITE_DIR' '$SNAP_ROOT/$STAMP'
    # KHANDAQ (internal audit 2026-08-22): the CSP travels with the content, or the rollback breaks
    # the page it just restored.
    #
    # The headers snippet carries sha256 hashes of the inline scripts. Restoring old HTML while
    # leaving the new hashes in nginx means the browser blocks the very scripts the restored page
    # needs — and a static site has no server-side error for that, so it looks like the rollback
    # worked and the page is simply broken.
    mkdir -p '$SNAP_ROOT/$STAMP/_nginx'
    cp -a /etc/nginx/snippets/khandaq-security-headers.conf '$SNAP_ROOT/$STAMP/_nginx/' 2>/dev/null || true
    cp -a '$NGINX_SITE' '$SNAP_ROOT/$STAMP/_nginx/site.conf' 2>/dev/null || true
    echo \"    snapshot: $SNAP_ROOT/$STAMP (+ nginx)\"
  else
    echo '    nothing served yet - no snapshot taken'
  fi
  ls -1d '$SNAP_ROOT'/* 2>/dev/null | sort | head -n -5 | xargs -r rm -rf"

echo "==> Upload Khandaq site to $REMOTE:$REMOTE_SITE_DIR"
ssh "$REMOTE" "mkdir -p '$REMOTE_SITE_DIR/downloads'"
# KHANDAQ (KHQ-01): purge any previously-published sideload APK from the live server so it can't be
# fetched by direct URL after the site switched to Google Play.
ssh "$REMOTE" "rm -f '$REMOTE_SITE_DIR/downloads/khandaq-android.apk' '$REMOTE_SITE_DIR/downloads/khandaq-release.apk' '$REMOTE_SITE_DIR/downloads/SHA256SUMS.android.txt' 2>/dev/null || true"
# KHANDAQ (18.08): privacy.html was missing from this list, so every edit to it silently stayed
# local while the other pages shipped - the page drifted behind for as long as that lasted.
# KHANDAQ (audit round 3, R-07): release-manifest.json is the generated source of truth the
# published version/package claims are checked against, so it has to reach the server too.
# KHANDAQ (re-audit 2026-08-22, W-02): the manifest that reaches the server carries the commit it
# was deployed from and the SHA-256 of every published artifact. The committed copy cannot carry the
# SHA (recording it would change the commit), so it is stamped into a temporary copy here — and
# verify-site-deploy.py refuses a published manifest whose gitSha is null or does not match.
STAMPED="$(mktemp -t khandaq-manifest)"
trap 'rm -f "$STAMPED"' EXIT
# KHANDAQ (SEO, 2026-11-13): the same idea for the stylesheets and the script. pr-cy, after
# Lighthouse, reported them as unminified. web/ keeps the readable sources; what gets published is
# their minified form (scripts/site_minify.py), written here by the same script that stamps the
# pages, so the ?v= every page carries is the hash of exactly these bytes. It refuses to write
# them if any stamp is stale, and verify-site-deploy.py compares the served files with the stamps.
PUBLISHED="$(mktemp -d "${TMPDIR:-/tmp}/khandaq-site-assets.XXXXXX")"
# KHANDAQ (SEO, 2026-11-16): the sitemap pages as served before anything below replaces them, so that
# the pages this deploy changes, and only those, go to IndexNow once the site is verified. It also
# stops the deploy here when web/ lost its IndexNow key file (scripts/indexnow-submit.py).
INDEXNOW_BEFORE="$(mktemp -t khandaq-indexnow)"
trap 'rm -rf "$STAMPED" "$PUBLISHED" "$INDEXNOW_BEFORE"' EXIT
python3 "$ROOT/scripts/generate-release-manifest.py" --stamp "$STAMPED"
python3 "$ROOT/scripts/version-site-assets.py" --out "$PUBLISHED"
python3 "$ROOT/scripts/indexnow-submit.py" snapshot --out "$INDEXNOW_BEFORE"
INDEXNOW_KEY="$(python3 "$ROOT/scripts/indexnow-submit.py" key)"

# KHANDAQ (re-audit 2026-08-22, W-02): upload to a staging directory, then replace.
#
# scp opens the destination with O_TRUNC and writes THROUGH the existing inode. That is invisible
# normally and fatal to the snapshot above: a hardlinked snapshot shares those inodes, so every
# "previous" copy silently gained the new content and the rollback restored nothing. Found by
# testing the rollback rather than by trusting it — the first attempt rolled back to a snapshot that
# already contained the change it was supposed to undo.
#
# Staging plus `cp --remove-destination` unlinks each destination before writing, which breaks the
# hardlink the right way round: the snapshot keeps the old bytes. It also means a half-transferred
# file is never served.
INCOMING="$REMOTE_SITE_DIR.incoming"
ssh "$REMOTE" "rm -rf '$INCOMING' && mkdir -p '$INCOMING/downloads' '$INCOMING/.well-known'"
scp -p "$WEB/index.html" "$WEB/changelog.html" "$WEB/changelog.json" "$WEB/404.html" "$WEB/privacy.html" "$WEB/robots.txt" "$WEB/llms.txt" "$WEB/sitemap.xml" "$REMOTE:$INCOMING/"
# KHANDAQ (SEO, 2026-11-13): the files that prove ownership of the site to Google Search Console and
# Yandex Webmaster. Both services fetch them from the site root again from time to time and drop the
# verification once they are gone, so they stay for good, byte for byte as the services issued them.
# verify-site-deploy.py checks that each answers 200, text/html, with no redirect and these bytes.
scp -p "$WEB/googleed8b77188642d163.html" "$WEB/yandex_3641f7482369653c.html" "$REMOTE:$INCOMING/"
# KHANDAQ (SEO, 2026-11-16): the IndexNow key file. The engines fetch it to check every submission,
# so it stays for good too; verify-site-deploy.py checks it is 200, text/plain, the committed bytes.
scp -p "$WEB/$INDEXNOW_KEY.txt" "$REMOTE:$INCOMING/"
scp -p "$STAMPED" "$REMOTE:$INCOMING/release-manifest.json"
scp -pr "$WEB/assets" "$REMOTE:$INCOMING/"
# style.css, assets/site.js and assets/tokens.css as published: minified, over the readable copies
# of assets/ that the line above has just sent. Order matters.
scp -pr "$PUBLISHED"/* "$REMOTE:$INCOMING/"
# KHANDAQ (redesign 2026-11): the Russian site is its own folder (/ru/), so it needs its own copy;
# verify-site-deploy.py checks the /ru/ pages and fails the deploy if this line goes missing.
scp -pr "$WEB/ru" "$REMOTE:$INCOMING/"
scp -p "$WEB/.well-known/security.txt" "$REMOTE:$INCOMING/.well-known/security.txt"
# KHANDAQ (0.2.65, review of the desktop update): a download that fails to go up stops the deploy;
# this line used to end in 2>/dev/null || true, and a manifest naming bytes that never arrived would
# have gone live. The update manifest and its signature are not among these: they go up last, below.
DOWNLOADS=()
for f in "$DL"/*; do
  [[ -f "$f" ]] || continue
  case "$(basename "$f")" in
    khandaq-desktop-update.json|khandaq-desktop-update.json.sig) continue ;;
  esac
  DOWNLOADS+=("$f")
done
if [[ ${#DOWNLOADS[@]} -gt 0 ]]; then
  scp -p "${DOWNLOADS[@]}" "$REMOTE:$INCOMING/downloads/"
fi
ssh "$REMOTE" "set -e
  mkdir -p '$REMOTE_SITE_DIR/downloads' '$REMOTE_SITE_DIR/.well-known'
  cp -a --remove-destination '$INCOMING/.' '$REMOTE_SITE_DIR/'
  rm -rf '$INCOMING'"
# KHANDAQ (0.2.65, review of the desktop update): the update manifest last, once every download it
# names is in place, so that an app that reads it finds those bytes. The signature first, then the
# manifest, each renamed into place: a reader gets the old file or the new one, never half of one.
# Between the two renames the old manifest meets the new signature, and an app checking just then
# refuses it and tries again within the hour, which is the safe way to be wrong.
if [[ ${#UPDATE_STAGED[@]} -gt 0 ]]; then
  echo "==> Desktop update manifest, last"
  UPDATE_INCOMING="$REMOTE_SITE_DIR.incoming-update"
  ssh "$REMOTE" "rm -rf '$UPDATE_INCOMING' && mkdir -p '$UPDATE_INCOMING'"
  scp -p "$DL/khandaq-desktop-update.json.sig" "$DL/khandaq-desktop-update.json" "$REMOTE:$UPDATE_INCOMING/"
  ssh "$REMOTE" "set -e
    mv -f '$UPDATE_INCOMING/khandaq-desktop-update.json.sig' '$REMOTE_SITE_DIR/downloads/khandaq-desktop-update.json.sig'
    mv -f '$UPDATE_INCOMING/khandaq-desktop-update.json' '$REMOTE_SITE_DIR/downloads/khandaq-desktop-update.json'
    rm -rf '$UPDATE_INCOMING'"
fi
scp -p "$ROOT/infra/nginx/khandaq-static-site.locations.conf" "$REMOTE:/tmp/khandaq-static-site.locations.conf"

# Every location block includes this file, so it must exist BEFORE nginx -t runs or the reload
# fails closed and the site keeps serving the previous config.
ssh "$REMOTE" "mkdir -p /etc/nginx/snippets"
scp -p "$ROOT/infra/nginx/khandaq-security-headers.conf" "$REMOTE:/etc/nginx/snippets/khandaq-security-headers.conf"

echo "==> Patch nginx (static UI only; Matrix API unchanged)"
ssh "$REMOTE" "python3 <<'PY'
from pathlib import Path
import sys

nginx = Path('$NGINX_SITE')
snippet = Path('/tmp/khandaq-static-site.locations.conf').read_text()
if not snippet.endswith('\n'):
    snippet += '\n'
lines = nginx.read_text().splitlines(keepends=True)

# KHANDAQ (re-audit 2026-08-22): replace between explicit markers when they are there.
#
# NOTE ON THE PROSE: no backticks anywhere below. This whole python program is an argument to ssh
# inside DOUBLE quotes, so the local shell would run anything in backticks before the argument
# was ever sent. That is precisely what scripts/check-shell-heredocs.py exists to catch, and it
# caught it here — in a comment added while fixing something else.
#
# The heuristic further down walks to the close of the bare location block and treats that as
# the end of the managed region, which silently assumed that block was last. The moment another
# one was added after it, the old copy survived the patch and nginx refused the config as a
# duplicate location. The markers remove the guess; the heuristic stays for the first run on a
# server that has never been patched, and that run installs the markers.
begin = next((i for i, l in enumerate(lines) if 'KHANDAQ-MANAGED-BEGIN' in l), None)
finish = next((i for i, l in enumerate(lines) if 'KHANDAQ-MANAGED-END' in l), None)
if begin is not None and finish is not None and finish > begin:
    nginx.write_text(''.join(lines[:begin] + [snippet] + lines[finish + 1:]))
    print(f'Patched {nginx}: managed region lines {begin + 1}-{finish + 1}')
    raise SystemExit(0)

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

# KHANDAQ (re-audit 2026-08-22, W-01/W-02/W-03): prove it from OUTSIDE. Everything above is a
# statement about what we uploaded; this is the only step that says anything about what the public
# actually receives. It runs from this machine, not over ssh on the host, precisely so that it goes
# through the same path a user's browser does.
echo "==> Post-deploy verification over public HTTPS"
# A run that staged no desktop download left the live update manifest as it was, which need not be
# the copy in this checkout.
VERIFY=(python3 "$ROOT/scripts/verify-site-deploy.py")
if [[ ${#UPDATE_STAGED[@]} -eq 0 ]]; then
  VERIFY+=(--kept-update-manifest)
fi
if ! "${VERIFY[@]}"; then
  echo "" >&2
  echo "DEPLOY FAILED VERIFICATION — the site is live but does not match this checkout." >&2
  echo "Fix the failures above and re-run; do not announce the release until this passes." >&2
  exit 1
fi

# KHANDAQ (SEO, 2026-11-16): the site is live and verified, so tell IndexNow (Bing, Yandex and the
# others) which pages changed. A failure here leaves a correct site that the engines recrawl on their
# own schedule, so it warns instead of failing the deploy.
echo "==> IndexNow: the pages this deploy changed"
if ! python3 "$ROOT/scripts/indexnow-submit.py" submit --since "$INDEXNOW_BEFORE"; then
  echo "    WARNING: the IndexNow submission failed; the site itself is deployed and verified" >&2
fi

echo "==> Done"
echo "    https://khandaq.org/"
echo "    Backup: $REMOTE_BACKUP_ROOT/element-matrix-$STAMP"
ls -lh "$DL" 2>/dev/null || true
