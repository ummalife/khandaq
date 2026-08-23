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

# KHANDAQ (deep review 2026-08-23, RR3-08): one legitimate case where a mismatch is NOT an incident.
#
# Rotating the release key invalidates every existing signature at once, so docs/DESKTOP-SIGNING.md's
# rotation procedure walks straight into the refusal below and cannot be carried out. --rotate is
# that procedure, and nothing else: it re-signs artifacts whose signature no longer verifies. It does
# NOT relax the H-01 rule — bytes fetched from the distribution channel stay unsignable in every mode,
# because that rule keys on where the bytes came from, not on whether a signature matches.
ROTATE=0
for arg in "$@"; do
  [ "$arg" = "--rotate" ] && ROTATE=1
done

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# KHANDAQ (internal audit 2026-08-22, H-01): the directory is overridable so the refusal behaviour
# below can be exercised on a scratch copy. Production passes nothing and gets web/downloads, and the
# public key and allowed_signers follow the directory rather than being pinned to it separately —
# otherwise a test would verify against the real trust anchor while signing somewhere else.
DL="${KHANDAQ_DOWNLOADS_DIR:-$ROOT/web/downloads}"
KEY_DIR="${KHANDAQ_SIGNING_DIR:-$HOME/.khandaq/release-signing}"
KEY="$KEY_DIR/khandaq-release-ed25519"
PUB_IN_REPO="$DL/KHANDAQ-RELEASE-SIGNING.pub"
ALLOWED="$DL/allowed_signers"
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
  base="$(basename "$f")"
  if [ -f "$f.sig" ] && ssh-keygen -Y verify -f "$ALLOWED" -I "$IDENTITY" -n "$NAMESPACE" \
                                   -s "$f.sig" < "$f" >/dev/null 2>&1; then
    echo "  уже подписан: $base"
    continue
  fi

  # KHANDAQ (internal audit 2026-08-22, H-01). Everything below this line used to be "re-sign it".
  # Two cases reach here, and neither of them may end in a signature being made.
  #
  # 1. The bytes came from the web server (deploy-site.sh fetched them because this checkout does not
  #    hold them). Signing those would mean the release key vouches for whatever the distribution
  #    channel happened to be serving — which is precisely the thing the signature exists to rule
  #    out. An attacker with write access to the downloads directory would get a valid signature over
  #    their file on the next ordinary deploy.
  #
  # 2. A local file carries a signature that does not verify. That is not "the file changed": every
  #    path that stages a freshly built artifact into web/downloads REMOVES the old signature beside
  #    it (deploy-site.sh, drop_stale_sig). It did not, until the deep review pointed out that the
  #    committed .sig files are exactly what a rebuild leaves stranded — so this refusal used to stop
  #    legitimate rebuilds. With staging fixed, a mismatch here means the bytes or the signature were
  #    altered after signing, and the correct response to that is to stop. Key rotation is the one
  #    other way to reach this state, and it has its own flag rather than a hole in the rule.
  case " ${FETCHED_ARTIFACTS:-} " in
    *" $base "*)
      echo "ОШИБКА: $base доставлен с боевого сервера, а его опубликованная подпись не проходит" >&2
      echo "       проверку (или её там нет). Подписать эти байты нельзя: ключ поручился бы за то," >&2
      echo "       что раздаёт сам сервер — ровно за то, от чего подпись и защищает." >&2
      echo "       Соберите артефакт локально в dist/ и выложите заново, а если он там не менялся —" >&2
      echo "       это инцидент: на сервере лежит не то, что было подписано." >&2
      exit 1
      ;;
  esac
  if [ -f "$f.sig" ] && [ "$ROTATE" = "1" ]; then
    echo "  ротация: переподписываю $base (старая подпись не проходит — это и есть смена ключа)"
  elif [ -f "$f.sig" ]; then
    echo "ОШИБКА: $base не сходится со своей подписью $base.sig." >&2
    echo "       Пересборка кладёт новый файл БЕЗ старой подписи рядом, поэтому расхождение здесь" >&2
    echo "       означает, что после подписания изменили байты или подпись. Это инцидент, а не" >&2
    echo "       повод переподписать." >&2
    exit 1
  fi

  # KHANDAQ (deep review 2026-08-23, RR3-08 follow-up): three things had to change here, and the
  # first two are defects that predate the refusal logic above.
  #
  # `ssh-keygen -Y sign` does NOT overwrite an existing <file>.sig — it PROMPTS ("Overwrite (y/n)?")
  # and then exits 0 whatever happens. With stdout and stderr sent to /dev/null and the status
  # unchecked, that meant: the command blocked on a prompt nobody could see (a signing run hung for
  # three minutes before this was found), and when it did return it printed "подписан" for a file it
  # had not signed. Only the self-check at the end noticed, and by then the message had lied.
  #
  # So: remove the stale signature first, close stdin so a prompt can never block, and verify that a
  # signature actually appeared and that it verifies. A signing step that cannot fail is decoration.
  rm -f "$f.sig"
  if ! ssh-keygen -Y sign -f "$KEY" -n "$NAMESPACE" "$f" </dev/null >/dev/null 2>&1; then
    echo "ОШИБКА: ssh-keygen не смог подписать $base" >&2
    exit 1
  fi
  if [ ! -f "$f.sig" ] || ! ssh-keygen -Y verify -f "$ALLOWED" -I "$IDENTITY" -n "$NAMESPACE" \
                                        -s "$f.sig" < "$f" >/dev/null 2>&1; then
    echo "ОШИБКА: подпись для $base не создана или не проходит собственную проверку" >&2
    exit 1
  fi
  # ssh-keygen writes <file>.sig next to the input, which is exactly where it is published.
  echo "  подписан: $base"
  signed=$((signed + 1))
done
echo "подписано файлов: $signed"

echo "==> Самопроверка"
verify_all
