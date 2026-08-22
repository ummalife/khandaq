#!/usr/bin/env bash
# Sign every published desktop artifact with a key that is not the download channel.
#
# KHANDAQ (re-audit 2026-08-22, K-05). The site published unsigned Windows and macOS builds and then
# taught people to click past the warnings — "More info → Run anyway", `xattr -cr`, add an antivirus
# exception. Those are exactly the defences that would catch a substituted binary, and a user trained
# to dismiss them has no defence left. A SHA-256 list beside the download does not replace them:
# whoever can replace the binary can replace the checksum, because both come down the same pipe.
#
# Authenticode and Developer ID close this properly and both need certificates only the owner can
# obtain (see docs/DESKTOP-SIGNING.md). This is the part that needs no certificate authority at all
# and therefore exists today: a detached Ed25519 signature over each artifact, made with a key that
# lives on the release machine and never in this repository, with the PUBLIC half committed here —
# so the signature and the thing it vouches for travel by different routes. Whoever compromises the
# web server still cannot produce a signature that verifies.
#
# OpenSSH rather than minisign or gpg on purpose: `ssh-keygen -Y verify` is present on macOS, on
# every Linux, and in Git for Windows, so a user can check a download with nothing installed.
#
#   scripts/sign-desktop-artifacts.sh            # sign web/downloads/*
#   scripts/sign-desktop-artifacts.sh --verify   # verify what is already there
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DL="$ROOT/web/downloads"
KEY_DIR="${KHANDAQ_SIGNING_DIR:-$HOME/.khandaq/release-signing}"
KEY="$KEY_DIR/khandaq-release-ed25519"
PUB_IN_REPO="$ROOT/web/downloads/KHANDAQ-RELEASE-SIGNING.pub"
ALLOWED="$ROOT/web/downloads/allowed_signers"
# The identity the signature is bound to. Not an email that must exist — a stable label, which is
# what allowed_signers matches on.
IDENTITY="releases@khandaq.org"
NAMESPACE="khandaq-release"

command -v ssh-keygen >/dev/null 2>&1 || {
  echo "ОШИБКА: нет ssh-keygen — без него ни подписать, ни проверить" >&2
  exit 1
}

verify_all() {
  local n=0 bad=0
  [ -f "$ALLOWED" ] || { echo "ОШИБКА: нет $ALLOWED — проверять нечем" >&2; exit 1; }
  for f in "$DL"/*; do
    case "$f" in
      *.sig|*allowed_signers|*.pub|*SHA256SUMS.txt|*/index.html) continue ;;
    esac
    [ -f "$f" ] || continue
    if [ ! -f "$f.sig" ]; then
      echo "  НЕ ПОДПИСАН: $(basename "$f")"
      bad=$((bad + 1))
      continue
    fi
    if ssh-keygen -Y verify -f "$ALLOWED" -I "$IDENTITY" -n "$NAMESPACE" \
                  -s "$f.sig" < "$f" >/dev/null 2>&1; then
      echo "  ок: $(basename "$f")"
      n=$((n + 1))
    else
      echo "  ПОДПИСЬ НЕ СХОДИТСЯ: $(basename "$f")"
      bad=$((bad + 1))
    fi
  done
  echo "проверено: $n, проблем: $bad"
  [ "$bad" -eq 0 ]
}

if [ "${1:-}" = "--verify" ]; then
  echo "==> Проверка подписей в web/downloads"
  verify_all
  exit $?
fi

if [ ! -f "$KEY" ]; then
  # Created here rather than left as a manual step, because a signing scheme nobody has set up is a
  # signing scheme that does not exist. It lives OUTSIDE the repository and is never committed.
  mkdir -p "$KEY_DIR"
  chmod 700 "$KEY_DIR"
  echo "==> Ключа подписи нет — создаю $KEY"
  echo "    Приватная половина остаётся здесь и НИКОГДА не попадает в репозиторий."
  echo "    Сделайте её резервную копию: потеря ключа означает смену публичного ключа у всех."
  ssh-keygen -t ed25519 -f "$KEY" -N "" -C "khandaq-release-signing" >/dev/null
  chmod 600 "$KEY"
fi

# Publish the public half in the repository, which is a different distribution channel from the
# website. That is the whole point: an attacker who owns the web server cannot also rewrite git.
cp -f "$KEY.pub" "$PUB_IN_REPO"
printf '%s %s\n' "$IDENTITY" "$(cat "$KEY.pub")" > "$ALLOWED"

echo "==> Подпись артефактов в $DL"
signed=0
for f in "$DL"/*; do
  case "$f" in
    *.sig|*allowed_signers|*.pub|*SHA256SUMS.txt|*/index.html) continue ;;
  esac
  [ -f "$f" ] || continue
  # Skip what is already signed AND still verifies. Signing reads the whole file, and the artifacts
  # come to about 400 MB — re-signing unchanged bytes on every deploy costs minutes and proves
  # nothing. The verify is the check: a file that changed, or a signature that does not match it,
  # fails here and gets re-signed.
  if [ -f "$f.sig" ] && ssh-keygen -Y verify -f "$ALLOWED" -I "$IDENTITY" -n "$NAMESPACE" \
                                   -s "$f.sig" < "$f" >/dev/null 2>&1; then
    echo "  уже подписан: $(basename "$f")"
    continue
  fi
  ssh-keygen -Y sign -f "$KEY" -n "$NAMESPACE" "$f" >/dev/null 2>&1
  # ssh-keygen writes <file>.sig next to the input, which is exactly where it is published.
  echo "  подписан: $(basename "$f")"
  signed=$((signed + 1))
done
echo "подписано файлов: $signed"

echo "==> Самопроверка"
verify_all
