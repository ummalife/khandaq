#!/usr/bin/env python3
"""Prove the release key cannot be made to vouch for bytes that came from the download server.

KHANDAQ (internal audit 2026-08-22, H-01). The deploy fetches already-published artifacts from the
production web server when the checkout does not hold them, and then signed everything whose
signature did not verify. A missing or wrong signature was the TRIGGER to sign — so an attacker who
could write to the downloads directory got a valid release signature over their binary on the next
ordinary deploy, and after a second deploy from the same tree the checksums and the manifest agreed
with it too. Every published statement about those bytes would then be self-consistent and wrong.

The fix is that the signer refuses in both of the cases that used to end in a signature. This proves
it, on a scratch directory with a throwaway key, because "refuses" is a claim about behaviour and the
only way to hold it is to keep trying it.

    scripts/check-signing-refuses-untrusted.py
"""
from __future__ import annotations

import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SIGNER = ROOT / "scripts" / "sign-desktop-artifacts.sh"

failures: list[str] = []


def run(dl: Path, keydir: Path, fetched: str = "") -> subprocess.CompletedProcess:
    env = dict(os.environ)
    env["KHANDAQ_DOWNLOADS_DIR"] = str(dl)
    env["KHANDAQ_SIGNING_DIR"] = str(keydir)
    env["FETCHED_ARTIFACTS"] = fetched
    return subprocess.run(["bash", str(SIGNER)], capture_output=True, text=True, env=env, timeout=180)


def fresh(tmp: Path, name: str) -> tuple[Path, Path]:
    dl = tmp / name / "downloads"
    keydir = tmp / name / "key"
    dl.mkdir(parents=True)
    keydir.mkdir(parents=True)
    (dl / "khandaq-windows-installer.exe").write_bytes(b"genuine installer bytes\n" * 64)
    return dl, keydir


def main() -> int:
    if not shutil.which("ssh-keygen"):
        print("ОШИБКА: нет ssh-keygen", file=sys.stderr)
        return 2
    if not SIGNER.is_file():
        print(f"ОШИБКА: нет {SIGNER}", file=sys.stderr)
        return 2

    tmp = Path(tempfile.mkdtemp(prefix="khandaq-signtest-"))
    try:
        # --- 1. Базовый случай: локально собранный артефакт подписывается.
        dl, keydir = fresh(tmp, "clean")
        r = run(dl, keydir)
        art = dl / "khandaq-windows-installer.exe"
        if r.returncode != 0:
            failures.append(f"обычная подпись сломалась: {(r.stderr or r.stdout).strip()[:300]}")
        elif not (dl / "khandaq-windows-installer.exe.sig").is_file():
            failures.append("обычная подпись не создала .sig — тест ниже ничего не доказывал бы")
        else:
            print("  ок: локально собранный артефакт подписывается")

        # --- 2. Байты пришли с сервера раздачи: подписывать нельзя.
        dl2, keydir2 = fresh(tmp, "fetched")
        shutil.copy2(keydir / "khandaq-release-ed25519", keydir2 / "khandaq-release-ed25519")
        shutil.copy2(keydir / "khandaq-release-ed25519.pub", keydir2 / "khandaq-release-ed25519.pub")
        r = run(dl2, keydir2, fetched=" khandaq-windows-installer.exe ")
        if r.returncode == 0:
            failures.append("артефакт, ДОСТАВЛЕННЫЙ С СЕРВЕРА, был подписан релизным ключом — это "
                            "ровно тот путь, которым подменённый бинарник получает подпись")
        elif (dl2 / "khandaq-windows-installer.exe.sig").is_file():
            failures.append("скрипт вышел с ошибкой, но подпись всё равно оставил на диске")
        else:
            print("  ок: байты с сервера раздачи подписаны НЕ были")

        # --- 3. Локальный файл с несходящейся подписью: это инцидент, а не повод переподписать.
        dl3, keydir3 = fresh(tmp, "tampered")
        shutil.copy2(keydir / "khandaq-release-ed25519", keydir3 / "khandaq-release-ed25519")
        shutil.copy2(keydir / "khandaq-release-ed25519.pub", keydir3 / "khandaq-release-ed25519.pub")
        # Подпись от ДРУГИХ байтов — как если бы файл подменили после подписания.
        shutil.copy2(art.with_suffix(".exe.sig"), dl3 / "khandaq-windows-installer.exe.sig")
        (dl3 / "khandaq-windows-installer.exe").write_bytes(b"tampered installer bytes\n" * 64)
        before = (dl3 / "khandaq-windows-installer.exe.sig").read_bytes()
        r = run(dl3, keydir3)
        after = (dl3 / "khandaq-windows-installer.exe.sig").read_bytes()
        if r.returncode == 0:
            failures.append("файл, не сходящийся со своей подписью, был переподписан — расхождение "
                            "должно останавливать выкат, а не запускать подпись")
        elif after != before:
            failures.append("подпись была перезаписана, хотя скрипт вышел с ошибкой")
        else:
            failures_before = len(failures)
            if "инцидент" not in (r.stderr or ""):
                failures.append("отказ не объясняет, что это инцидент — оператор переподпишет вручную")
            if len(failures) == failures_before:
                print("  ок: расхождение с подписью остановило выкат")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    print()
    if failures:
        print(f"ПРОВАЛЕНО: {len(failures)}", file=sys.stderr)
        for f in failures:
            print(f"::error::  {f}", file=sys.stderr)
        return 1
    print("ВСЁ ЧИСТО: релизным ключом подписывается только то, что собрано локально и не менялось")
    return 0


if __name__ == "__main__":
    sys.exit(main())
