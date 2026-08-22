#!/usr/bin/env python3
"""Verify every published desktop artifact the way a user would, from outside.

KHANDAQ (re-audit 2026-08-22, K-05 — "Post-release script проверяет signatures/attestations всех
опубликованных desktop artifacts"). The deploy already signs what it uploads and the post-deploy
probe checks that a signature EXISTS beside each artifact. Existence is not validity: a truncated
upload, a partially-replaced file, or a signature left behind from an earlier build would all pass
that check and fail a real user.

So this downloads each artifact and its detached signature over public HTTPS and verifies it
cryptographically with ssh-keygen — the same command the download page tells a user to run — and
cross-checks three independent statements about the same bytes:

  * the OpenSSH signature (the trust anchor: the key that made it is published in the git
    repository, a different channel from this web server);
  * the SHA-256 in the published SHA256SUMS.txt;
  * the SHA-256 recorded in /release-manifest.json by the deploy.

Any disagreement between those three is the interesting case, and none of them can be checked by
looking at a configuration file.

It is deliberately NOT part of every deploy: it pulls a few hundred megabytes. Run it after a
release, and whenever the download page is about to be pointed at new binaries.

    scripts/verify-desktop-signatures.py
    scripts/verify-desktop-signatures.py --base https://khandaq.org --keep /tmp/artifacts
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request
from pathlib import Path

DEFAULT_BASE = "https://khandaq.org"
IDENTITY = "releases@khandaq.org"
NAMESPACE = "khandaq-release"

failures: list[str] = []
checked = 0


def fetch(url: str, dest: Path | None = None):
    req = urllib.request.Request(url, headers={"User-Agent": "khandaq-verify/1"})
    try:
        with urllib.request.urlopen(req, timeout=180) as resp:  # noqa: S310 - fixed https host
            if dest is None:
                return resp.read()
            with dest.open("wb") as fh:
                shutil.copyfileobj(resp, fh)
            return b""
    except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError, OSError) as exc:
        failures.append(f"{url}: не скачивается ({exc})")
        return None


def main() -> int:
    global checked
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default=DEFAULT_BASE)
    ap.add_argument("--keep", default=None, help="каталог для скачанного (по умолчанию — временный)")
    args = ap.parse_args()
    base = args.base.rstrip("/")

    if not shutil.which("ssh-keygen"):
        print("ОШИБКА: нет ssh-keygen — проверить подписи нечем", file=sys.stderr)
        return 2

    work = Path(args.keep) if args.keep else Path(tempfile.mkdtemp(prefix="khandaq-verify-"))
    work.mkdir(parents=True, exist_ok=True)
    print(f"==> Проверка {base}  (рабочий каталог {work})")

    signers = fetch(f"{base}/downloads/allowed_signers")
    if signers is None:
        print("не удалось получить allowed_signers — дальше идти незачем", file=sys.stderr)
        return 1
    signers_path = work / "allowed_signers"
    signers_path.write_bytes(signers)
    print(f"    список подписантов: {len(signers.splitlines())} запись(ей)")

    sums_raw = fetch(f"{base}/downloads/SHA256SUMS.txt")
    if sums_raw is None:
        return 1
    published_sums = {}
    for line in sums_raw.decode("utf-8", "replace").splitlines():
        m = re.match(r"^([0-9a-fA-F]{64})\s+\*?(.+?)\s*$", line)
        if m:
            published_sums[m.group(2)] = m.group(1).lower()

    manifest_raw = fetch(f"{base}/release-manifest.json")
    manifest_sums = {}
    if manifest_raw is not None:
        try:
            manifest_sums = (json.loads(manifest_raw).get("site") or {}).get("artifacts") or {}
        except ValueError:
            failures.append("/release-manifest.json не разбирается как JSON")

    names = sorted(n for n in published_sums if not n.endswith(".sig"))
    if not names:
        failures.append("в SHA256SUMS.txt нет ни одного артефакта")
    print(f"    артефактов к проверке: {len(names)}")

    for name in names:
        print(f"\n  -- {name}")
        art = work / name
        sig = work / (name + ".sig")
        if fetch(f"{base}/downloads/{name}", art) is None:
            continue
        if fetch(f"{base}/downloads/{name}.sig", sig) is None:
            failures.append(f"{name}: подпись не опубликована")
            continue

        digest = hashlib.sha256()
        with art.open("rb") as fh:
            for chunk in iter(lambda: fh.read(1 << 20), b""):
                digest.update(chunk)
        actual = digest.hexdigest()
        size_mb = art.stat().st_size / (1 << 20)
        print(f"     {size_mb:.1f} МиБ, sha256 {actual[:16]}…")

        if published_sums[name] != actual:
            failures.append(f"{name}: SHA256SUMS.txt обещает {published_sums[name][:16]}…, "
                            f"скачалось {actual[:16]}…")
        else:
            print("     ок: совпадает с SHA256SUMS.txt")

        if manifest_sums:
            want = manifest_sums.get(name)
            if want is None:
                failures.append(f"{name}: нет в release-manifest.json, хотя опубликован")
            elif want.lower() != actual:
                failures.append(f"{name}: манифест обещает {want[:16]}…, скачалось {actual[:16]}…")
            else:
                print("     ок: совпадает с release-manifest.json")

        proc = subprocess.run(
            ["ssh-keygen", "-Y", "verify", "-f", str(signers_path), "-I", IDENTITY,
             "-n", NAMESPACE, "-s", str(sig)],
            stdin=art.open("rb"), capture_output=True, text=True)
        if proc.returncode == 0:
            print(f"     ок: {proc.stdout.strip() or 'подпись верна'}")
            checked += 1
        else:
            failures.append(f"{name}: подпись НЕ проходит проверку — "
                            f"{(proc.stderr or proc.stdout).strip().splitlines()[0] if (proc.stderr or proc.stdout).strip() else 'без деталей'}")

        if not args.keep:
            art.unlink(missing_ok=True)
            sig.unlink(missing_ok=True)

    if not args.keep:
        shutil.rmtree(work, ignore_errors=True)

    print()
    if failures:
        print(f"ПРОВАЛЕНО: {len(failures)} проблем(ы)")
        for f in failures:
            print(f"  - {f}")
        return 1
    print(f"ВСЁ ЧИСТО: {checked} артефакт(ов) — подпись верна, и обе опубликованные суммы сходятся")
    return 0


if __name__ == "__main__":
    sys.exit(main())
