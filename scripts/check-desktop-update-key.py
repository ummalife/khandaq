#!/usr/bin/env python3
"""KHANDAQ (0.2.65): the desktop app trusts the key the releases are signed with, and nothing else.

The app updates itself only from a manifest the release key has signed (src/net/updatemanifest.cpp),
and that key is compiled into it. If the key in the app and the key that signs the releases ever
part, every app in the field stops updating at once, silently, and the only way back is a download
by hand; if the app trusted any other key, whoever holds that key could install code on every
desktop. So this holds, on every pull request:

  * the key compiled into the app is the release key published in web/downloads
    (KHANDAQ-RELEASE-SIGNING.pub and the releases@khandaq.org line of allowed_signers), and is a
    well-formed Ed25519 key;
  * the throwaway key that signs the test fixtures (test/net/update/) is not the release key and is
    nowhere in the app's sources;
  * the app and the release scripts agree on the manifest's name, schema, signature namespace and
    the file each platform downloads (scripts/desktop_update_manifest.py, sign-desktop-artifacts.sh);
  * every release build script turns the update on and the QA mode off (UPDATE_CHECK=ON,
    UPDATE_CHECK_QA=OFF): a QA build follows another manifest and trusts the test key.

    scripts/check-desktop-update-key.py
    scripts/check-desktop-update-key.py --self-test   # every check must catch the mutant made for it
"""
from __future__ import annotations

import base64
import re
import struct
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import desktop_update_manifest as dum  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
APP = "khandaq-desktop/src/net/updatemanifest.cpp"
APP_HEADER = "khandaq-desktop/src/net/updatemanifest.h"
PUB = "web/downloads/KHANDAQ-RELEASE-SIGNING.pub"
SIGNERS = "web/downloads/allowed_signers"
TEST_KEY = "khandaq-desktop/test/net/update/update-test.pub"
SIGNER = "scripts/sign-desktop-artifacts.sh"
BUILD_SCRIPTS = ("scripts/build-macos.sh", "scripts/build-linux.sh", "scripts/build-linux-docker.sh",
                 "khandaq-desktop/windows/cross-compile/build.sh")
APP_SOURCES = "khandaq-desktop/src"


def constant(text: str, name: str, where: str, errors: list[str]) -> str | None:
    found = re.findall(r"constexpr\s+char\s+" + re.escape(name) + r"\[\]\s*=\s*\"([^\"]*)\";", text)
    if len(found) != 1:
        errors.append(f"{where}: {name} is not defined exactly once ({len(found)} found): the check "
                      f"has drifted from the code, and a vacuous pass is worse than none")
        return None
    return found[0]


def ed25519_blob(blob: str, where: str, errors: list[str]) -> bytes | None:
    try:
        raw = base64.b64decode(blob, validate=True)
    except ValueError:
        errors.append(f"{where}: the key is not base64")
        return None
    try:
        length = struct.unpack_from(">I", raw, 0)[0]
        kind = raw[4:4 + length]
        key_length = struct.unpack_from(">I", raw, 4 + length)[0]
        key = raw[8 + length:8 + length + key_length]
    except struct.error:
        errors.append(f"{where}: not an OpenSSH public key")
        return None
    if kind != b"ssh-ed25519" or key_length != 32 or len(raw) != 8 + length + 32:
        errors.append(f"{where}: not an Ed25519 key, or with bytes after it")
        return None
    return key


def problems(files: dict[str, str]) -> list[str]:
    errors: list[str] = []
    app, header = files[APP], files[APP_HEADER]

    # The key.
    app_key = constant(app, "releaseKeyBlob", APP, errors)
    pub = files[PUB].split()
    pub_key = pub[1] if len(pub) >= 2 and pub[0] == "ssh-ed25519" else None
    if pub_key is None:
        errors.append(f"{PUB}: not an ssh-ed25519 public key line")
    signer_keys = []
    for line in files[SIGNERS].splitlines():
        fields = line.split()
        if fields and fields[0] == dum.IDENTITY:
            signer_keys += [fields[i + 1] for i, f in enumerate(fields[:-1]) if f == "ssh-ed25519"]
    if len(signer_keys) != 1:
        errors.append(f"{SIGNERS}: {dum.IDENTITY} must have exactly one ssh-ed25519 key, has {len(signer_keys)}")
    if app_key is not None:
        ed25519_blob(app_key, APP, errors)
        if app_key != pub_key:
            errors.append(f"{APP}: the app trusts a key that is not {PUB}; every app would refuse the "
                          f"releases signed with it")
        if signer_keys and app_key != signer_keys[0]:
            errors.append(f"{APP}: the app's key is not the one {SIGNERS} gives {dum.IDENTITY}")
    test = files[TEST_KEY].split()
    test_key = test[1] if len(test) >= 2 else None
    if test_key is None or test_key == pub_key:
        errors.append(f"{TEST_KEY}: the test key must be a throwaway key of its own, not the release key")
    elif any(test_key in text for path, text in files.items() if path.startswith(APP_SOURCES + "/")):
        errors.append(f"{APP_SOURCES}: the test key is in the app's sources; only a QA build "
                      f"(UPDATE_CHECK_QA_KEY) may trust a key other than the release key")

    # The names the app and the scripts must agree on.
    url = constant(app, "productionManifest", APP, errors)
    if url is not None and url != f"https://khandaq.org/downloads/{dum.MANIFEST_NAME}":
        errors.append(f"{APP}: the app asks {url}, the release publishes /downloads/{dum.MANIFEST_NAME}")
    for name, want in (("schema", dum.SCHEMA), ("signatureNameSpace", dum.NAMESPACE)):
        got = constant(header, name, APP_HEADER, errors)
        if got is not None and got != want:
            errors.append(f"{APP_HEADER}: {name} is {got!r}, the release scripts use {want!r}")
    signer = files[SIGNER]
    for name, want in (("UPDATE_MANIFEST", dum.MANIFEST_NAME), ("UPDATE_NAMESPACE", dum.NAMESPACE)):
        got = re.findall(r"^" + name + r"=\"([^\"]*)\"$", signer, re.M)
        if got != [want]:
            errors.append(f"{SIGNER}: {name} is {got}, the app reads {want!r}")
    app_files = dict(re.findall(r"\{QStringLiteral\(\"([a-z0-9_-]+)\"\),\s*QStringLiteral\(\"([^\"]+)\"\)\}", app))
    if app_files != dum.PLATFORM_FILES:
        errors.append(f"{APP}: the platforms and files the app knows {app_files} are not the ones the "
                      f"release writes {dum.PLATFORM_FILES}")

    # The release builds.
    for path in BUILD_SCRIPTS:
        text = files[path]
        configures = len(re.findall(r"^\s*-DCMAKE_BUILD_TYPE=", text, re.M))
        if configures == 0:
            errors.append(f"{path}: no cmake configure found: the check has drifted from the script")
        if len(re.findall(r"^\s*-DUPDATE_CHECK=ON\b", text, re.M)) != configures:
            errors.append(f"{path}: every configure must say -DUPDATE_CHECK=ON")
        if len(re.findall(r"^\s*-DUPDATE_CHECK_QA=OFF\b", text, re.M)) != configures:
            errors.append(f"{path}: every configure must say -DUPDATE_CHECK_QA=OFF")
        if re.search(r"UPDATE_CHECK_QA=(?:ON|1|TRUE|YES)|UPDATE_CHECK=OFF", text, re.I):
            errors.append(f"{path}: a release build with the QA mode on, or without the update")
    return errors


def read_files() -> dict[str, str]:
    files = {}
    for path in (APP, APP_HEADER, PUB, SIGNERS, TEST_KEY, SIGNER, *BUILD_SCRIPTS):
        files[path] = (ROOT / path).read_text(encoding="utf-8")
    for source in sorted((ROOT / APP_SOURCES).rglob("*")):
        if source.suffix in (".cpp", ".h", ".mm", ".c") and source.is_file():
            files.setdefault(source.relative_to(ROOT).as_posix(), source.read_text(encoding="utf-8", errors="replace"))
    return files


def self_test() -> int:
    files = read_files()
    if problems(files):
        print("FAIL: check-desktop-update-key.py self-test: the files as they are do not pass:", file=sys.stderr)
        for error in problems(files):
            print(f"  - {error}", file=sys.stderr)
        return 1
    app_key = re.search(r"releaseKeyBlob\[\] = \"([^\"]+)\"", files[APP]).group(1)
    test_key = files[TEST_KEY].split()[1]
    other = "AAAAC3NzaC1lZDI1NTE5AAAAI" + app_key[25:-4] + ("AAAA" if not app_key.endswith("AAAA") else "BBBB")
    mutants = [
        ("another key in the app", APP, app_key, other),
        ("the test key in the app", APP, app_key, test_key),
        ("a broken key in the app", APP, app_key, app_key[:-8]),
        ("another key published", PUB, app_key, other),
        ("another key for the identity", SIGNERS, app_key, other),
        ("the release key as the test key", TEST_KEY, test_key, app_key),
        ("another manifest address", APP, "downloads/khandaq-desktop-update.json", "downloads/update.json"),
        ("another namespace in the app", APP_HEADER, '"khandaq-desktop-update";', '"khandaq-release";'),
        ("another namespace in the signer", SIGNER, 'UPDATE_NAMESPACE="khandaq-desktop-update"',
         'UPDATE_NAMESPACE="khandaq-release"'),
        ("another schema", APP_HEADER, '"khandaq-desktop-update/1"', '"khandaq-desktop-update/2"'),
        ("another file for a platform", APP, 'QStringLiteral("khandaq-macos.zip")', 'QStringLiteral("khandaq.zip")'),
        ("the QA mode in a release build", "scripts/build-macos.sh", "-DUPDATE_CHECK_QA=OFF", "-DUPDATE_CHECK_QA=ON"),
        ("no update in a release build", "khandaq-desktop/windows/cross-compile/build.sh",
         "-DUPDATE_CHECK=ON", "-DUPDATE_CHECK=OFF"),
    ]
    survived = []
    for name, path, old, new in mutants:
        if old not in files[path]:
            survived.append(f"{name}: `{old}` is not in {path} (the self-test has drifted)")
            continue
        mutated = dict(files)
        mutated[path] = files[path].replace(old, new, 1)
        if not problems(mutated):
            survived.append(f"{name}: not caught")
    if survived:
        print("FAIL: check-desktop-update-key.py self-test:", file=sys.stderr)
        for line in survived:
            print(f"  - {line}", file=sys.stderr)
        return 1
    print(f"ok: check-desktop-update-key.py self-test, {len(mutants)} mutants caught")
    return 0


def main() -> int:
    if sys.argv[1:] == ["--self-test"]:
        return self_test()
    if sys.argv[1:]:
        print("usage: check-desktop-update-key.py [--self-test]", file=sys.stderr)
        return 2
    errors = problems(read_files())
    if errors:
        for error in errors:
            print(f"::error::{error}", file=sys.stderr)
        return 1
    print("ok: the desktop app trusts the release key alone, and agrees with the release scripts")
    return 0


if __name__ == "__main__":
    sys.exit(main())
