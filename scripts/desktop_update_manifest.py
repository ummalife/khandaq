"""KHANDAQ (0.2.65): the desktop update manifest, as the release scripts and the app both read it.

The desktop app (khandaq-desktop/src/net/updatemanifest.cpp) learns from
https://khandaq.org/downloads/khandaq-desktop-update.json whether a newer release is out, and believes
it only once the release key's signature over it verifies, in a namespace of its own. These are the
app's rules, kept in one place for the scripts that write the manifest
(generate-desktop-update-manifest.py), sign it (sign-desktop-artifacts.sh), deploy it
(deploy-site.sh, verify-site-deploy.py, verify-desktop-signatures.py) and hold the app to them
(check-desktop-update-key.py). A manifest this module accepts is one the app accepts; it is stricter
than the app only where the scripts are the writer: no field and no platform the writer does not know.
"""
from __future__ import annotations

import json
import re
import subprocess
from pathlib import Path
from urllib.parse import urlsplit

MANIFEST_NAME = "khandaq-desktop-update.json"
SIGNATURE_NAME = MANIFEST_NAME + ".sig"
SCHEMA = "khandaq-desktop-update/1"
# The app verifies exactly this namespace, so no signature over a download (khandaq-release) can pass
# for a signature over the manifest, nor the other way round.
NAMESPACE = "khandaq-desktop-update"
IDENTITY = "releases@khandaq.org"
NOTES_URL = {"en": "https://khandaq.org/changelog", "ru": "https://khandaq.org/ru/changelog.html"}
# Each platform the app knows, with the one file its entry may name: the downloads on khandaq.org and
# in the GitHub release.
PLATFORM_FILES = {
    "windows-x86_64-installer": "khandaq-windows-installer.exe",
    "windows-x86_64-zip": "khandaq-windows-x86_64.zip",
    "macos-zip": "khandaq-macos.zip",
    "linux-deb-amd64": "khandaq-messenger_amd64.deb",
    "linux-portable-x86_64": "khandaq-linux-x86_64-portable.tar.gz",
}
# The largest download the app takes (UpdateManifest::artifactLimit).
ARTIFACT_LIMIT = 1 << 30
# \A and \Z: Python's $ would let a final newline through, as PCRE's does in the app.
VERSION = re.compile(r"\A(0|[1-9][0-9]{0,8})\.(0|[1-9][0-9]{0,8})\.(0|[1-9][0-9]{0,8})\Z")
SHA256 = re.compile(r"\A[0-9a-f]{64}\Z")
SUMS_LINE = re.compile(r"^([0-9a-fA-F]{64})\s+\*?(.+?)\s*$")
ENTRY_FIELDS = {"file", "sha256", "size", "version"}
TOP_FIELDS = {"notesUrl", "platforms", "schema"}


def version_key(text: str) -> tuple[int, int, int]:
    match = VERSION.match(text)
    if not match:
        raise ValueError(f"not a version: {text!r}")
    return int(match.group(1)), int(match.group(2)), int(match.group(3))


def platform_of(file_name: str) -> str | None:
    for platform, name in PLATFORM_FILES.items():
        if name == file_name:
            return platform
    return None


def serialize(manifest: dict) -> bytes:
    """The one form the manifest is published in, so that writing it again changes no byte."""
    return (json.dumps(manifest, indent=2, sort_keys=True, ensure_ascii=True) + "\n").encode("ascii")


def _notes_ok(value) -> bool:
    if not isinstance(value, str):
        return False
    parts = urlsplit(value)
    return (parts.scheme == "https" and parts.hostname == "khandaq.org" and parts.port is None
            and parts.username is None and parts.password is None)


def validate(manifest) -> list[str]:
    """Why the app, or the writer, would refuse this manifest; empty when neither would."""
    if not isinstance(manifest, dict):
        return ["the manifest is not a JSON object"]
    errors = []
    unknown = sorted(set(manifest) - TOP_FIELDS)
    if unknown:
        errors.append(f"fields the writer does not know: {', '.join(unknown)}")
    if manifest.get("schema") != SCHEMA:
        errors.append(f"schema is {manifest.get('schema')!r}, not {SCHEMA!r}")
    notes = manifest.get("notesUrl")
    if not isinstance(notes, dict) or not all(_notes_ok(notes.get(lang)) for lang in ("en", "ru")):
        errors.append("notesUrl must give en and ru pages of https://khandaq.org/")
    elif notes != NOTES_URL:
        errors.append(f"notesUrl is {notes!r}, not the changelog pages {NOTES_URL!r}")
    platforms = manifest.get("platforms")
    if not isinstance(platforms, dict):
        errors.append("platforms is not an object")
        return errors
    for platform in sorted(platforms):
        entry = platforms[platform]
        where = f"platforms.{platform}"
        if platform not in PLATFORM_FILES:
            errors.append(f"{where}: a platform the app does not know")
            continue
        if not isinstance(entry, dict):
            errors.append(f"{where}: not an object")
            continue
        unknown = sorted(set(entry) - ENTRY_FIELDS)
        if unknown:
            errors.append(f"{where}: fields the writer does not know: {', '.join(unknown)}")
        version = entry.get("version")
        if not isinstance(version, str) or not VERSION.match(version):
            errors.append(f"{where}: version {version!r} is not three plain numbers (no v)")
        if entry.get("file") != PLATFORM_FILES[platform]:
            errors.append(f"{where}: file {entry.get('file')!r} is not {PLATFORM_FILES[platform]!r}")
        size = entry.get("size")
        if isinstance(size, bool) or not isinstance(size, int) or not 1 <= size <= ARTIFACT_LIMIT:
            errors.append(f"{where}: size {size!r} is not a whole number of bytes up to 1 GiB")
        sha256 = entry.get("sha256")
        if not isinstance(sha256, str) or not SHA256.match(sha256):
            errors.append(f"{where}: sha256 is not 64 lowercase hex digits")
    return errors


def parse_sums(text: str) -> dict[str, str]:
    """SHA256SUMS.txt as file -> lowercase hex, in either tool's format ("HASH  name", "HASH *name")."""
    sums = {}
    for line in text.splitlines():
        match = SUMS_LINE.match(line)
        if match:
            sums[match.group(2)] = match.group(1).lower()
    return sums


def against_sums(manifest: dict, sums: dict[str, str]) -> list[str]:
    """Every SHA-256 the manifest gives must be the one SHA256SUMS.txt publishes for that file."""
    errors = []
    for platform, entry in sorted((manifest.get("platforms") or {}).items()):
        name = entry.get("file")
        published = sums.get(name)
        if published is None:
            errors.append(f"{platform}: {name} is not in SHA256SUMS.txt")
        elif published != entry.get("sha256"):
            errors.append(f"{platform}: the manifest gives {str(entry.get('sha256'))[:16]}... for "
                          f"{name}, SHA256SUMS.txt {published[:16]}...")
    return errors


def verify_signature(allowed_signers: Path, signature: Path, data: bytes) -> tuple[bool, str]:
    """ssh-keygen -Y verify of data, for the release identity in the manifest's own namespace."""
    proc = subprocess.run(
        ["ssh-keygen", "-Y", "verify", "-f", str(allowed_signers), "-I", IDENTITY, "-n", NAMESPACE,
         "-s", str(signature)],
        input=data, capture_output=True, timeout=60)
    detail = (proc.stdout or proc.stderr).decode("utf-8", "replace").strip()
    return proc.returncode == 0, detail.splitlines()[0] if detail else ""
