#!/usr/bin/env python3
"""KHANDAQ (0.2.66): write the signed manifest the desktop app updates from.

The desktop app asks https://khandaq.org/downloads/khandaq-desktop-update.json once a day whether a
newer release is out (khandaq-desktop/src/net/updatecheck.cpp) and believes the answer only when the
release key's signature over it verifies, in the namespace khandaq-desktop-update that
sign-desktop-artifacts.sh signs it in. For each kind of desktop download the manifest names the
version inside it, its file, its size and its SHA-256; the app downloads the file only when the
person presses Update, and keeps it only when both match.

The version of each entry is read from the artifact itself: from the GIT_DESCRIBE string the build
compiled into the program, which Qt stores as UTF-16 (QStringLiteral). Never from the command line or
from VERSION, because an app must never be told a version its download does not carry. A program
not built from an exact release tag ("v0.2.64-3-gabc1234"; "Nightly" when there was no git) is
refused, and so are one built from a tree with changes nobody committed ("v0.2.64-dirty") and a
QA build (UPDATE_CHECK_QA), the only kind that contains the name of the variable that points the
check elsewhere. The installer's payload is compressed: 7-Zip unpacks bin/Khandaq.exe from it, which
must be byte for byte the Khandaq.exe of the portable zip given with it, so that both come from one
build, and its version resource (VIProductVersion in qtox64.nsi) must agree too.

Only the files named on the command line are read, and deploy-site.sh names only what it has just
staged from dist/, never a file fetched from the server. A platform not named keeps its entry from
the manifest already beside SHA256SUMS.txt (committed with its signature after each release),
provided SHA256SUMS.txt still gives the same bytes for it. A platform never goes back to an older
version: the apps that saw the newer one would not take it, and the rest should not.

Every manifest carries a serial, the UTC time it was written in seconds, and always above the serial
of the one before; the app refuses a manifest with a lower serial than the highest it has verified,
so that an old signed manifest served again cannot hold back a newer release. Written again with
nothing else changed, the manifest keeps its serial, its bytes and its signature.

    scripts/generate-desktop-update-manifest.py web/downloads/khandaq-macos.zip ...  # write it
    scripts/generate-desktop-update-manifest.py --check      # it agrees with SHA256SUMS.txt
    scripts/generate-desktop-update-manifest.py --check --with-downloads  # and each download is here
    scripts/generate-desktop-update-manifest.py --against-live LIVE.json  # takes nothing back
    scripts/generate-desktop-update-manifest.py --self-test  # the fixtures below (CI)
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import plistlib
import re
import shutil
import struct
import subprocess
import sys
import tarfile
import tempfile
import time
import zipfile
from pathlib import Path
from typing import Callable

sys.path.insert(0, str(Path(__file__).resolve().parent))
import desktop_update_manifest as dum  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_MANIFEST = ROOT / "web" / "downloads" / dum.MANIFEST_NAME

# GIT_DESCRIBE as the program holds it: "v0.2.64" in UTF-16, NUL-terminated, or with the
# "-3-gabc1234" of a build made after the tag.
DESCRIBE_UTF16 = re.compile(
    rb"(?<![A-Za-z0-9]\x00)v\x00((?:[0-9]\x00)+\.\x00(?:[0-9]\x00)+\.\x00(?:[0-9]\x00)+)"
    rb"((?:[-+.~A-Za-z0-9]\x00)*)\x00\x00")
# The name UpdateEndpoint::qaUrlVariable gives the QA override. Only a QA build contains it.
QA_MARKER = b"KHANDAQ_UPDATE_MANIFEST_URL"
APP_PLIST = re.compile(r"\A([^/]+\.app)/Contents/Info\.plist\Z")
DEB_PROGRAM = "opt/khandaq/khandaq.bin"
# The program in the installer, as 7-Zip names it ($INSTDIR\bin\Khandaq.exe, qtox64.nsi).
NSIS_PROGRAM = "bin/Khandaq.exe"
# 7-Zip as Homebrew's sevenzip and Debian's 7zip install it, then p7zip. Not 7za, which reads no NSIS.
SEVEN_ZIP = ("7zz", "7z")


class Refused(Exception):
    """An artifact or a manifest that must not be published."""


def program_version(data: bytes, where: str) -> str:
    if QA_MARKER in data or QA_MARKER.decode("ascii").encode("utf-16-le") in data:
        raise Refused(f"{where}: a QA build (UPDATE_CHECK_QA), which follows another manifest and "
                      f"trusts a test key, and is never released")
    found = set()
    for match in DESCRIBE_UTF16.finditer(data):
        version = match.group(1).decode("utf-16-le")
        suffix = match.group(2).decode("utf-16-le")
        if suffix.endswith("-dirty"):
            raise Refused(f"{where}: built from a tree with changes nobody committed (v{version}{suffix}); "
                          f"build the release from a clean checkout of the tag")
        if suffix:
            raise Refused(f"{where}: built after the tag, not from it (v{version}{suffix}); only a "
                          f"build of the exact release tag may be offered")
        found.add(version)
    if not found:
        raise Refused(f"{where}: no release version in the program (a Nightly build, or not Khandaq)")
    if len(found) > 1:
        raise Refused(f"{where}: more than one version in the program: {', '.join(sorted(found))}")
    version = found.pop()
    if not dum.VERSION.match(version):
        raise Refused(f"{where}: {version!r} is not a version the app reads")
    return version


def limited(size: int, where: str) -> None:
    if size > dum.ARTIFACT_LIMIT:
        raise Refused(f"{where}: {size} bytes is more than any program of a release")


def windows_zip_program(path: Path) -> tuple[str, bytes]:
    with zipfile.ZipFile(path) as archive:
        names = [info for info in archive.infolist() if info.filename.lower() == "khandaq.exe"]
        if len(names) != 1:
            raise Refused(f"{path.name}: Khandaq.exe is not at the top of the zip exactly once")
        limited(names[0].file_size, path.name)
        return names[0].filename, archive.read(names[0])


def windows_zip(path: Path) -> str:
    member, data = windows_zip_program(path)
    return program_version(data, f"{path.name}:{member}")


def macos_zip(path: Path) -> str:
    with zipfile.ZipFile(path) as archive:
        plists = [name for name in archive.namelist() if APP_PLIST.match(name)]
        if len(plists) != 1:
            raise Refused(f"{path.name}: not exactly one .app bundle at the top of the zip")
        bundle = APP_PLIST.match(plists[0]).group(1)
        try:
            info = plistlib.loads(archive.read(plists[0]))
        except (plistlib.InvalidFileException, ValueError) as exc:
            raise Refused(f"{path.name}: {plists[0]} does not parse ({exc})") from exc
        program = info.get("CFBundleExecutable") if isinstance(info, dict) else None
        if not isinstance(program, str) or not program or "/" in program:
            raise Refused(f"{path.name}: {plists[0]} names no program (CFBundleExecutable)")
        member = f"{bundle}/Contents/MacOS/{program}"
        try:
            limited(archive.getinfo(member).file_size, path.name)
            data = archive.read(member)
        except KeyError as exc:
            raise Refused(f"{path.name}: {member} is not in the zip") from exc
        return program_version(data, f"{path.name}:{member}")


def tar_member(archive: tarfile.TarFile, wanted: str, where: str) -> bytes:
    for member in archive.getmembers():
        name = member.name[2:] if member.name.startswith("./") else member.name
        if name == wanted:
            if not member.isfile():
                raise Refused(f"{where}: {wanted} is not a file")
            limited(member.size, where)
            return archive.extractfile(member).read()
    raise Refused(f"{where}: {wanted} is not in it")


def linux_tarball(path: Path) -> str:
    with tarfile.open(path, "r:gz") as archive:
        return program_version(tar_member(archive, "khandaq.bin", path.name), f"{path.name}:khandaq.bin")


def ar_members(data: bytes, where: str) -> dict[str, bytes]:
    if not data.startswith(b"!<arch>\n"):
        raise Refused(f"{where}: not a Debian package (no ar header)")
    members = {}
    pos = 8
    while pos < len(data):
        header = data[pos:pos + 60]
        if len(header) < 60 or header[58:60] != b"`\n":
            raise Refused(f"{where}: a broken ar header at byte {pos}")
        name = header[:16].decode("ascii", "replace").strip().rstrip("/")
        try:
            size = int(header[48:58].decode("ascii").strip())
        except ValueError as exc:
            raise Refused(f"{where}: a broken ar member size at byte {pos}") from exc
        members[name] = data[pos + 60:pos + 60 + size]
        pos += 60 + size + (size & 1)
    return members


def unzstd(data: bytes, where: str) -> bytes:
    tool = shutil.which("zstd")
    if tool:
        proc = subprocess.run([tool, "-dcq"], input=data, capture_output=True)
        if proc.returncode != 0:
            raise Refused(f"{where}: zstd could not read it ({proc.stderr.decode(errors='replace').strip()})")
        return proc.stdout
    try:
        from compression import zstd  # Python 3.14 and later
    except ImportError as exc:
        raise Refused(f"{where}: is zstd-compressed and this machine has no zstd "
                      f"(brew install zstd, or apt install zstd)") from exc
    return zstd.decompress(data)


def deb_tar(members: dict[str, bytes], prefix: str, where: str) -> tarfile.TarFile:
    names = [name for name in members if name == prefix or name.startswith(prefix + ".")]
    if len(names) != 1:
        raise Refused(f"{where}: not exactly one {prefix}.* in the package")
    name, body = names[0], members[names[0]]
    if name.endswith(".zst"):
        return tarfile.open(fileobj=io.BytesIO(unzstd(body, f"{where}:{name}")), mode="r:")
    if name.endswith((".gz", ".xz", ".bz2")) or name == prefix:
        return tarfile.open(fileobj=io.BytesIO(body), mode="r:*")
    raise Refused(f"{where}: {name} is compressed in a way dpkg does not write")


def linux_deb(path: Path) -> str:
    members = ar_members(path.read_bytes(), path.name)
    with deb_tar(members, "control.tar", path.name) as control:
        text = tar_member(control, "control", path.name).decode("utf-8", "replace")
    found = re.search(r"^Version:\s*(\S+)\s*$", text, re.M)
    if not found:
        raise Refused(f"{path.name}: its control file has no Version")
    # [epoch:]upstream[-revision]
    package = found.group(1).split(":", 1)[-1].split("-", 1)[0]
    with deb_tar(members, "data.tar", path.name) as data:
        version = program_version(tar_member(data, DEB_PROGRAM, path.name), f"{path.name}:{DEB_PROGRAM}")
    if package != version:
        raise Refused(f"{path.name}: the package says {found.group(1)}, its program {version}")
    return version


def pe_section(data: bytes, wanted: bytes, where: str) -> bytes:
    try:
        if data[:2] != b"MZ":
            raise Refused(f"{where}: not a Windows program")
        pe = struct.unpack_from("<I", data, 0x3C)[0]
        if data[pe:pe + 4] != b"PE\0\0":
            raise Refused(f"{where}: not a Windows program (no PE header)")
        count = struct.unpack_from("<H", data, pe + 6)[0]
        optional = struct.unpack_from("<H", data, pe + 20)[0]
        table = pe + 24 + optional
        for index in range(count):
            entry = table + 40 * index
            name = data[entry:entry + 8].rstrip(b"\0")
            raw_size, raw_pointer = struct.unpack_from("<II", data, entry + 16)
            if name == wanted:
                return data[raw_pointer:raw_pointer + raw_size]
    except struct.error as exc:
        raise Refused(f"{where}: a broken PE header") from exc
    raise Refused(f"{where}: no {wanted.decode()} section")


def installer_version(path: Path) -> str:
    resources = pe_section(path.read_bytes(), b".rsrc", path.name)
    key = "VS_VERSION_INFO\0".encode("utf-16-le")
    at = resources.find(key)
    if at < 0 or resources.find(key, at + 1) >= 0:
        raise Refused(f"{path.name}: not exactly one version resource")
    signature = resources.find(struct.pack("<I", 0xFEEF04BD), at + len(key), at + len(key) + 8)
    if signature < 0:
        raise Refused(f"{path.name}: the version resource has no fixed file info")
    product_ms, product_ls = struct.unpack_from("<II", resources, signature + 16)
    parts = (product_ms >> 16, product_ms & 0xFFFF, product_ls >> 16, product_ls & 0xFFFF)
    if parts[3] != 0:
        raise Refused(f"{path.name}: product version {'.'.join(map(str, parts))} is not X.Y.Z.0")
    return "%d.%d.%d" % parts[:3]


def seven_zip(where: str, which: Callable[[str], str | None] = shutil.which) -> str:
    for name in SEVEN_ZIP:
        tool = which(name)
        if tool:
            return tool
    raise Refused(f"{where}: the program inside the installer is read with 7-Zip, and this machine has "
                  f"none (brew install sevenzip, or apt install 7zip)")


def nsis_entries(listing: str, where: str) -> list[dict[str, str]]:
    """The files of `7z l -slt` of an installer, provided 7-Zip read it as NSIS."""
    head, separator, body = listing.replace("\r\n", "\n").partition("\n----------\n")
    if not separator:
        raise Refused(f"{where}: 7-Zip lists no files in it")
    archive = dict(line.split(" = ", 1) for line in head.splitlines() if " = " in line)
    if archive.get("Type") != "Nsis":
        raise Refused(f"{where}: not an NSIS installer (7-Zip reads it as {archive.get('Type')!r})")
    entries = []
    for block in body.split("\n\n"):
        fields = dict(line.split(" = ", 1) for line in block.splitlines() if " = " in line)
        if "Path" in fields:
            entries.append(fields)
    return entries


def nsis_program_size(entries: list[dict[str, str]], where: str) -> int:
    programs = [entry["Path"] for entry in entries
                if entry["Path"].replace("\\", "/").rsplit("/", 1)[-1].lower() == "khandaq.exe"]
    if programs != [NSIS_PROGRAM]:
        raise Refused(f"{where}: not exactly one program, {NSIS_PROGRAM}, in it "
                      f"({', '.join(programs) or 'none'})")
    size = next(entry.get("Size", "") for entry in entries if entry["Path"] == NSIS_PROGRAM)
    if not size.isdigit():
        raise Refused(f"{where}: 7-Zip gives no size for {NSIS_PROGRAM}")
    limited(int(size), where)
    return int(size)


def installer_program(path: Path) -> bytes:
    """bin/Khandaq.exe as the installer would install it, unpacked by 7-Zip as NSIS and nothing else."""
    tool = seven_zip(path.name)

    def seven(command: list[str]) -> subprocess.CompletedProcess:
        try:
            return subprocess.run([tool, *command], stdin=subprocess.DEVNULL, capture_output=True, timeout=600)
        except (OSError, subprocess.SubprocessError) as exc:
            raise Refused(f"{path.name}: 7-Zip did not run ({exc})") from exc

    def why(proc: subprocess.CompletedProcess) -> str:
        lines = (proc.stderr or proc.stdout).decode("utf-8", "replace").strip().splitlines()
        return lines[-1] if lines else f"exit {proc.returncode}"

    listing = seven(["l", "-slt", "-tNsis", "--", str(path)])
    if listing.returncode != 0:
        raise Refused(f"{path.name}: 7-Zip cannot read it as an NSIS installer ({why(listing)})")
    entries = nsis_entries(listing.stdout.decode("utf-8", "replace"), path.name)
    size = nsis_program_size(entries, path.name)
    unpacked = seven(["x", "-so", "-y", "-tNsis", "--", str(path), NSIS_PROGRAM])
    if unpacked.returncode != 0 or len(unpacked.stdout) != size:
        raise Refused(f"{path.name}: 7-Zip did not unpack the {size} bytes of {NSIS_PROGRAM} "
                      f"({len(unpacked.stdout)} bytes, {why(unpacked)})")
    return unpacked.stdout


READERS = {
    "windows-x86_64-zip": windows_zip,
    "windows-x86_64-installer": installer_version,
    "macos-zip": macos_zip,
    "linux-deb-amd64": linux_deb,
    "linux-portable-x86_64": linux_tarball,
}


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_manifest(path: Path) -> dict | None:
    if not path.is_file():
        return None
    try:
        manifest = json.loads(path.read_bytes())
    except ValueError as exc:
        raise Refused(f"{path}: does not parse ({exc})") from exc
    errors = dum.validate(manifest)
    if errors:
        raise Refused(f"{path}: " + "; ".join(errors))
    return manifest


def load_sums(path: Path) -> dict[str, str]:
    return dum.parse_sums(path.read_text(encoding="utf-8", errors="replace")) if path.is_file() else {}


def next_serial(previous: dict | None, manifest: dict, clock: Callable[[], float]) -> int:
    """The serial of the manifest before when nothing else changed, so that writing it again changes
    no byte and keeps its signature; otherwise the UTC time in seconds, and above the serial before
    whatever the clock of this machine says."""
    if previous is None:
        return max(int(clock()), 1)
    if {key: value for key, value in previous.items() if key != "serial"} == manifest:
        return previous["serial"]
    return max(int(clock()), previous["serial"] + 1)


def generate(artifacts: list[Path], manifest_path: Path, sums_path: Path,
             clock: Callable[[], float] = time.time,
             unpack_installer: Callable[[Path], bytes] = installer_program) -> dict:
    staged: dict[str, Path] = {}
    for path in artifacts:
        platform = dum.platform_of(path.name)
        if platform is None:
            raise Refused(f"{path.name}: not a download the app updates from "
                          f"({', '.join(dum.PLATFORM_FILES.values())})")
        if platform in staged:
            raise Refused(f"{path.name}: given twice")
        if not path.is_file():
            raise Refused(f"{path}: no such file")
        staged[platform] = path
    versions = {platform: READERS[platform](path) for platform, path in staged.items()}

    if "windows-x86_64-installer" in versions:
        installer = versions["windows-x86_64-installer"]
        portable = versions.get("windows-x86_64-zip")
        if portable is None:
            raise Refused("khandaq-windows-installer.exe: its version resource is written by hand in "
                          "qtox64.nsi, and the program it installs is held to the zip's; it is "
                          "believed only beside the zip of the same build, which was not given")
        if installer != portable:
            raise Refused(f"khandaq-windows-installer.exe says {installer}, the program in the zip of "
                          f"the same build {portable} (is VERSION in qtox64.nsi bumped?)")
        # The version resource is one line of qtox64.nsi: an installer of another build, or of none
        # the zip came from, says the same. The program it installs must be the zip's, byte for byte.
        inside = unpack_installer(staged["windows-x86_64-installer"])
        member, beside = windows_zip_program(staged["windows-x86_64-zip"])
        if inside != beside:
            raise Refused(f"khandaq-windows-installer.exe: its {NSIS_PROGRAM} (sha256 "
                          f"{hashlib.sha256(inside).hexdigest()[:16]}...) is not the {member} of the zip "
                          f"given with it ({hashlib.sha256(beside).hexdigest()[:16]}...); give the two "
                          f"from one build")

    previous_manifest = load_manifest(manifest_path)
    previous = (previous_manifest or {}).get("platforms", {})
    sums = load_sums(sums_path)
    platforms = {}
    for platform in dum.PLATFORM_FILES:
        old = previous.get(platform)
        if platform in staged:
            path = staged[platform]
            entry = {"file": path.name, "sha256": sha256_of(path), "size": path.stat().st_size,
                     "version": versions[platform]}
            if old and dum.version_key(old["version"]) > dum.version_key(entry["version"]):
                raise Refused(f"{path.name}: {entry['version']} is older than the {old['version']} "
                              f"already offered; apps that saw {old['version']} would not take it. "
                              f"Publish a newer version instead")
            platforms[platform] = entry
        elif old:
            if sums.get(old["file"]) != old["sha256"]:
                raise Refused(f"{old['file']}: the manifest offers {old['version']} with bytes that "
                              f"{sums_path.name} no longer lists; stage the artifact again so that "
                              f"its entry is made from it")
            platforms[platform] = old
    manifest = {"notesUrl": dict(dum.NOTES_URL), "platforms": platforms, "schema": dum.SCHEMA}
    manifest["serial"] = next_serial(previous_manifest, manifest, clock)
    errors = dum.validate(manifest)
    if errors:
        raise Refused("; ".join(errors))
    return manifest


def write(manifest: dict, manifest_path: Path) -> bool:
    """True when the manifest changed. A changed manifest loses the signature beside it, as every
    restaged artifact does (deploy-site.sh, drop_stale_sig): the signer stops on a signature that
    does not match, and must."""
    data = dum.serialize(manifest)
    if manifest_path.is_file() and manifest_path.read_bytes() == data:
        return False
    temporary = manifest_path.with_name(manifest_path.name + ".tmp")
    temporary.write_bytes(data)
    os.replace(temporary, manifest_path)
    signature = manifest_path.with_name(manifest_path.name + ".sig")
    if signature.exists():
        signature.unlink()
    return True


def check(manifest_path: Path, sums_path: Path, with_downloads: bool = False) -> list[str]:
    """With with_downloads, each download the manifest names must be beside it: deploy-site.sh asks
    once it has staged what dist/ holds and fetched the rest from the server, so that the manifest
    never goes up naming bytes that do not."""
    if not manifest_path.is_file():
        return []
    raw = manifest_path.read_bytes()
    try:
        manifest = json.loads(raw)
    except ValueError as exc:
        return [f"{manifest_path.name} does not parse ({exc})"]
    errors = dum.validate(manifest)
    if errors:
        return errors
    if raw != dum.serialize(manifest):
        errors.append(f"{manifest_path.name} is not in the form the generator writes (edited by hand?)")
    errors += dum.against_sums(manifest, load_sums(sums_path))
    for platform, entry in sorted(manifest["platforms"].items()):
        local = manifest_path.parent / entry["file"]
        if not local.is_file():
            if with_downloads:
                errors.append(f"{platform}: {entry['file']} is neither staged nor published")
            continue
        if local.stat().st_size != entry["size"] or sha256_of(local) != entry["sha256"]:
            errors.append(f"{platform}: {entry['file']} here is not the file the manifest describes")
    return errors


def check_live(manifest_path: Path, live_path: Path) -> list[str]:
    """The manifest about to be published against the live one, copied from the server for this and
    for nothing else: an empty copy means there is none yet."""
    if not live_path.is_file():
        return [f"{live_path}: no copy of the live manifest (an empty file stands for none)"]
    data = live_path.read_bytes()
    if not data:
        return []
    try:
        live = json.loads(data)
    except ValueError as exc:
        return [f"the live manifest does not parse ({exc})"]
    errors = dum.validate(live)
    if errors:
        return ["the live manifest is not one these scripts write (is this checkout behind?): " + "; ".join(errors)]
    manifest = load_manifest(manifest_path)
    if manifest is None:
        return [f"there is no {manifest_path.name} here to publish"]
    return dum.against_live(manifest, live)


# ---- self-test ------------------------------------------------------------------------------------


def fake_program(*describes: str, extra: bytes = b"") -> bytes:
    """Filler with each GIT_DESCRIBE as QStringLiteral lays it out: a header, then UTF-16 and a NUL."""
    out = bytearray(b"\x7fELF" + bytes(range(256)) * 4)
    for describe in describes:
        # QArrayData: ref -1 (static), size, alloc, padding, offset 24 to the characters.
        out += struct.pack("<iiIIq", -1, len(describe), 0, 0, 24)
        out += (describe + "\0").encode("utf-16-le") + b"\xcc" * 6
    out += "Qt 5.12.12 and Copyright".encode("utf-16-le") + b"\0\0" + extra + b"tail" * 64
    return bytes(out)


def fake_windows_zip(path: Path, program: bytes) -> None:
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("Khandaq.exe", program)
        archive.writestr("Qt5Core.dll", b"MZ library")
        archive.writestr("platforms/qwindows.dll", b"MZ plugin")


# Where a fake installer keeps the program it would install, in place of NSIS's compressed data.
FAKE_NSIS = b"\0fake NSIS data\0"
# The clock of the self-test: a release written on 2026-11-20.
CLOCK = 1795145410


def fake_installer(path: Path, version: tuple[int, int, int, int], decoy: tuple[int, int, int, int],
                   program: bytes = b"") -> None:
    def version_info(parts: tuple[int, int, int, int]) -> bytes:
        ms, ls = (parts[0] << 16) | parts[1], (parts[2] << 16) | parts[3]
        fixed = struct.pack("<13I", 0xFEEF04BD, 0x10000, ms, ls, ms, ls, 0x3F, 0, 0x40004, 1, 0, 0, 0)
        body = "VS_VERSION_INFO\0".encode("utf-16-le") + b"\0\0" + fixed
        return struct.pack("<HHH", 6 + len(body), len(fixed), 0) + body

    optional = 0xF0
    table = 0x40 + 24 + optional
    text = (b"\x90" * 64 + version_info(decoy)).ljust(0x200, b"\0")
    resources = (b"\0" * 16 + version_info(version)).ljust(0x200, b"\0")
    text_at = (table + 80 + 0x1FF) // 0x200 * 0x200
    resources_at = text_at + len(text)
    head = bytearray(text_at)
    head[0:2] = b"MZ"
    struct.pack_into("<I", head, 0x3C, 0x40)
    head[0x40:0x44] = b"PE\0\0"
    struct.pack_into("<HHIIIHH", head, 0x44, 0x14C, 2, 0, 0, 0, optional, 0x102)
    struct.pack_into("<8sIIIIIIHHI", head, table, b".text", len(text), 0x1000, len(text), text_at, 0, 0, 0, 0, 0)
    struct.pack_into("<8sIIIIIIHHI", head, table + 40, b".rsrc", len(resources), 0x2000, len(resources),
                     resources_at, 0, 0, 0, 0, 0)
    path.write_bytes(bytes(head) + text + resources + FAKE_NSIS + program)


def fake_unpack(path: Path) -> bytes:
    """installer_program for the fake installers: 7-Zip reads no NSIS that makensis did not write."""
    data = path.read_bytes()
    at = data.find(FAKE_NSIS)
    if at < 0:
        raise Refused(f"{path.name}: not an NSIS installer")
    return data[at + len(FAKE_NSIS):]


def fake_macos_zip(path: Path, program: bytes, apple_double: bytes) -> None:
    plist = plistlib.dumps({"CFBundleExecutable": "khandaq", "CFBundleShortVersionString": "0.2.65"})
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("khandaq.app/", b"")
        archive.writestr("khandaq.app/Contents/Info.plist", plist)
        archive.writestr("khandaq.app/Contents/MacOS/khandaq", program)
        archive.writestr("khandaq.app/Contents/MacOS/._khandaq", apple_double)
        archive.writestr("khandaq.app/Contents/Frameworks/QtCore.framework/QtCore", b"\xcf\xfa\xed\xfe")


def tar_bytes(files: dict[str, bytes], mode: str) -> bytes:
    buffer = io.BytesIO()
    with tarfile.open(fileobj=buffer, mode=mode) as archive:
        for name, data in files.items():
            info = tarfile.TarInfo(name)
            info.size = len(data)
            archive.addfile(info, io.BytesIO(data))
    return buffer.getvalue()


def fake_deb(path: Path, program: bytes, package_version: str, compression: str) -> None:
    control = f"Package: khandaq-messenger\nVersion: {package_version}\nArchitecture: amd64\n".encode()
    data_files = {"./opt/khandaq/khandaq": b"#!/bin/sh\n", "./opt/khandaq/khandaq.bin": program}
    if compression == "zst":
        plain = tar_bytes(data_files, "w")
        data = subprocess.run(["zstd", "-q", "-c"], input=plain, capture_output=True, check=True).stdout
    else:
        data = tar_bytes(data_files, f"w:{compression}")
    members = [("debian-binary", b"2.0\n"), ("control.tar.xz", tar_bytes({"./control": control}, "w:xz")),
               (f"data.tar.{compression}", data)]
    out = bytearray(b"!<arch>\n")
    for name, body in members:
        out += f"{name:<16}{0:<12}{0:<6}{0:<6}{100644:<8}{len(body):<10}`\n".encode("ascii") + body
        if len(body) & 1:
            out += b"\n"
    path.write_bytes(bytes(out))


def fake_linux_tarball(path: Path, program: bytes) -> None:
    path.write_bytes(tar_bytes({"khandaq": b"#!/bin/sh\n", "khandaq.bin": program,
                                "lib/libQt5Core.so.5": b"\x7fELF"}, "w:gz"))


def fake_release(folder: Path, version: str = "0.2.65", compression: str = "xz",
                 **override: bytes) -> list[Path]:
    folder.mkdir(parents=True, exist_ok=True)
    program = fake_program("v" + version)
    parts = tuple(int(p) for p in version.split(".")) + (0,)
    paths = {platform: folder / name for platform, name in dum.PLATFORM_FILES.items()}
    fake_windows_zip(paths["windows-x86_64-zip"], override.get("windows", program))
    fake_installer(paths["windows-x86_64-installer"], parts, (9, 9, 9, 0), override.get("windows", program))
    fake_macos_zip(paths["macos-zip"], override.get("macos", program), fake_program("v9.9.9"))
    fake_deb(paths["linux-deb-amd64"], override.get("deb", program), version, compression)
    fake_linux_tarball(paths["linux-portable-x86_64"], override.get("linux", program))
    return list(paths.values())


def sums_for(paths: list[Path]) -> str:
    return "".join(f"{sha256_of(p)}  {p.name}\n" for p in sorted(paths, key=lambda p: p.name))


def run_generate(artifacts: list[Path], manifest: Path, sums: Path, clock: int = CLOCK) -> dict:
    return generate(artifacts, manifest, sums, clock=lambda: clock, unpack_installer=fake_unpack)


# `7z l -slt` of the 0.2.61 installer, cut down to what nsis_entries reads.
NSIS_LISTING = """
7-Zip [64] 17.05 : Copyright (c) 1999-2021 Igor Pavlov : 2017-08-28

Listing archive: khandaq-windows-installer.exe

--
Path = khandaq-windows-installer.exe
Type = Nsis
Physical Size = 17945155
SubType = NSIS-3 Unicode

----------
Path = $PLUGINSDIR/System.dll
Size = 30208
Modified = 

Path = bin/Khandaq.exe
Size = 16520704
Modified = 2026-11-19 02:03:04

Path = bin/platforms/qwindows.dll
Size = 1453056

Path = uninstall.exe
Size = 50310
"""


def self_test() -> int:
    failures: list[str] = []
    cases = 0
    work = Path(tempfile.mkdtemp(prefix="khandaq-update-manifest-"))

    def expect_refusal(name: str, artifacts: list[Path], needle: str, manifest: Path | None = None,
                       sums: Path | None = None) -> None:
        nonlocal cases
        cases += 1
        manifest = manifest or work / name / "out" / dum.MANIFEST_NAME
        manifest.parent.mkdir(parents=True, exist_ok=True)
        try:
            run_generate(artifacts, manifest, sums or manifest.parent / "SHA256SUMS.txt")
        except Refused as exc:
            if needle not in str(exc):
                failures.append(f"{name}: refused for another reason: {exc}")
            return
        failures.append(f"{name}: was not refused")

    try:
        # 1. Five downloads of one build.
        cases += 1
        release = fake_release(work / "release")
        out = work / "release-out" / dum.MANIFEST_NAME
        out.parent.mkdir()
        manifest = run_generate(release, out, out.parent / "SHA256SUMS.txt")
        write(manifest, out)
        if sorted(manifest["platforms"]) != sorted(dum.PLATFORM_FILES):
            failures.append(f"one build: platforms {sorted(manifest['platforms'])}")
        for platform, entry in manifest["platforms"].items():
            path = work / "release" / dum.PLATFORM_FILES[platform]
            if entry != {"file": path.name, "sha256": sha256_of(path), "size": path.stat().st_size,
                         "version": "0.2.65"}:
                failures.append(f"one build: {platform} is {entry}")
        if out.read_bytes() != dum.serialize(manifest) or not out.read_bytes().endswith(b"}\n"):
            failures.append("one build: not written in the canonical form")
        if manifest["serial"] != CLOCK:
            failures.append(f"one build: serial {manifest['serial']}, not the time it was written")
        (out.parent / "SHA256SUMS.txt").write_text(sums_for(release))
        if check(out, out.parent / "SHA256SUMS.txt"):
            failures.append(f"one build: --check refuses it: {check(out, out.parent / 'SHA256SUMS.txt')}")

        # 2. The deb, compressed as each dpkg writes it.
        for compression in ("gz", "zst"):
            if compression == "zst" and not shutil.which("zstd"):
                print("  skipped: data.tar.zst (no zstd on this machine)")
                continue
            cases += 1
            deb = fake_release(work / f"deb-{compression}", compression=compression)
            try:
                if linux_deb(deb[3]) != "0.2.65":
                    failures.append(f"data.tar.{compression}: wrong version")
            except Refused as exc:
                failures.append(f"data.tar.{compression}: refused: {exc}")

        # 3. What must never be offered.
        refusals = [
            ("after the tag", {"windows": fake_program("v0.2.65-3-gabc1234")}, "built after the tag"),
            ("changes not committed", {"linux": fake_program("v0.2.65-dirty")}, "changes nobody committed"),
            ("after the tag, changed", {"deb": fake_program("v0.2.65-3-gabc1234-dirty")},
             "changes nobody committed"),
            ("Nightly", {"linux": fake_program("Nightly")}, "no release version"),
            ("two versions", {"macos": fake_program("v0.2.65", "v0.2.64")}, "more than one version"),
            ("QA build", {"macos": fake_program("v0.2.65", extra=QA_MARKER + b"\0")}, "a QA build"),
            ("leading zero", {"deb": fake_program("v0.2.065")}, "not a version the app reads"),
        ]
        for name, override, needle in refusals:
            artifacts = fake_release(work / name.replace(" ", "-"), **override)
            expect_refusal(name, artifacts, needle)

        installer_only = [p for p in fake_release(work / "installer-alone") if p.name.endswith(".exe")]
        expect_refusal("installer without its zip", installer_only, "beside the zip of the same build")
        mixed = fake_release(work / "mixed")
        fake_installer(mixed[0], (0, 2, 64, 0), (9, 9, 9, 0))
        expect_refusal("installer of another version", mixed, "says 0.2.64")
        # The version resource is right, the program it installs is not the zip's: another build.
        other = fake_release(work / "other-build")
        fake_installer(other[0], (0, 2, 65, 0), (9, 9, 9, 0), fake_program("v0.2.65", extra=b"another run"))
        expect_refusal("installer of another build", other, "is not the Khandaq.exe of the zip")
        package = fake_release(work / "package")
        fake_deb(package[3], fake_program("v0.2.65"), "0.2.64-1", "xz")
        expect_refusal("deb version", package, "the package says 0.2.64-1")
        odd = work / "odd" / "khandaq-messenger_0.2.65_amd64.deb"
        odd.parent.mkdir()
        odd.write_bytes(b"!<arch>\n")
        expect_refusal("unknown name", [odd], "not a download the app updates from")
        expect_refusal("given twice", [release[2], release[2]], "given twice")

        # 4. Platforms not staged keep their entry, while SHA256SUMS.txt still gives their bytes.
        cases += 1
        newer = fake_release(work / "newer", version="0.2.66")
        carry = work / "carry" / dum.MANIFEST_NAME
        carry.parent.mkdir()
        shutil.copy2(out, carry)
        (carry.parent / "SHA256SUMS.txt").write_text(sums_for(release))
        (carry.parent / (dum.MANIFEST_NAME + ".sig")).write_bytes(b"old signature")
        # A clock behind the manifest before: the serial still goes up.
        carried = run_generate([newer[2]], carry, carry.parent / "SHA256SUMS.txt", clock=1000)
        if carried["platforms"]["macos-zip"]["version"] != "0.2.66":
            failures.append("carry over: the staged platform did not change")
        if any(carried["platforms"][p] != manifest["platforms"][p] for p in dum.PLATFORM_FILES if p != "macos-zip"):
            failures.append("carry over: a platform not staged changed")
        if carried["serial"] != CLOCK + 1:
            failures.append(f"carry over: serial {carried['serial']}, not one above the {CLOCK} before")
        if not write(carried, carry) or (carry.parent / (dum.MANIFEST_NAME + ".sig")).exists():
            failures.append("carry over: a changed manifest kept the old signature")
        cases += 1
        signature = carry.parent / (dum.MANIFEST_NAME + ".sig")
        signature.write_bytes(b"signature of the same bytes")
        if write(carried, carry) or not signature.exists():
            failures.append("rewriting the same manifest dropped its signature")
        # Written again from the same download a day later: the same serial, bytes and signature.
        cases += 1
        again = run_generate([newer[2]], carry, carry.parent / "SHA256SUMS.txt", clock=CLOCK + 86400)
        if again != carried or write(again, carry) or not signature.exists():
            failures.append(f"the same manifest written again changed (serial {again['serial']})")
        # Changed, with the clock ahead: the time it was written.
        cases += 1
        (carry.parent / "SHA256SUMS.txt").write_text(sums_for(release + [newer[2], newer[4]]))
        later = run_generate([newer[4]], carry, carry.parent / "SHA256SUMS.txt", clock=CLOCK + 86400)
        if later["serial"] != CLOCK + 86400:
            failures.append(f"a changed manifest: serial {later['serial']}, not the time it was written")

        stale = work / "stale" / dum.MANIFEST_NAME
        stale.parent.mkdir()
        shutil.copy2(out, stale)
        (stale.parent / "SHA256SUMS.txt").write_text(sums_for(newer))
        expect_refusal("carry over against other sums", [newer[2]], "no longer lists", manifest=stale)

        downgrade = work / "downgrade" / dum.MANIFEST_NAME
        downgrade.parent.mkdir()
        shutil.copy2(carry, downgrade)
        (downgrade.parent / "SHA256SUMS.txt").write_text(sums_for(release + [newer[2]]))
        expect_refusal("downgrade", [release[2]], "is older than the 0.2.66 already offered", manifest=downgrade)

        # 5. A serial the app would refuse.
        for serial in (None, 0, -1, True, 1.5, "1795145410", dum.SERIAL_LIMIT + 1):
            cases += 1
            wrong = json.loads(dum.serialize(manifest))
            if serial is None:
                del wrong["serial"]
            else:
                wrong["serial"] = serial
            if not any(e.startswith("serial ") for e in dum.validate(wrong)):
                failures.append(f"a manifest with serial {serial!r} passed")

        # 6. The live manifest: nothing it offers may be taken back.
        def live_errors(name: str, live: dict | None, mine: dict | None) -> list[str]:
            folder = work / "live" / name.replace(" ", "-")
            folder.mkdir(parents=True)
            copy = folder / "live.json"
            copy.write_bytes(dum.serialize(live) if live is not None else b"")
            local = out
            if mine is not None:
                local = folder / dum.MANIFEST_NAME
                local.write_bytes(dum.serialize(mine))
            return check_live(local, copy)

        def changed(serial: int = CLOCK, schema: str = dum.SCHEMA, **platforms: dict) -> dict:
            other = json.loads(dum.serialize(manifest))
            other["serial"], other["schema"] = serial, schema
            for key, entry in platforms.items():
                other["platforms"][key.replace("_", "-")] = entry
            return other

        mac = manifest["platforms"]["macos-zip"]
        without_linux = json.loads(dum.serialize(manifest))
        del without_linux["platforms"]["linux-portable-x86_64"]
        live_cases = [
            ("none live yet", None, None, None),
            ("the same manifest", changed(), None, None),
            ("an older live one", changed(CLOCK - 1, macos_zip=dict(mac, version="0.2.64")), None, None),
            ("a newer live one", changed(CLOCK + 1), None, "is below the live"),
            ("the same serial, other content", changed(macos_zip=dict(mac, size=1)), None, "with other content"),
            ("a platform ahead", changed(CLOCK - 1, macos_zip=dict(mac, version="0.2.66")), None,
             "macos-zip: 0.2.65 is below the live 0.2.66"),
            ("a platform dropped", changed(CLOCK - 1), without_linux, "and this manifest drops it"),
            ("another schema", changed(schema="khandaq-desktop-update/2"), None, "is this checkout behind?"),
        ]
        for name, live, mine, needle in live_cases:
            cases += 1
            errors = live_errors(name, live, mine)
            if needle is None and errors:
                failures.append(f"live, {name}: refused: {errors}")
            elif needle is not None and not any(needle in e for e in errors):
                failures.append(f"live, {name}: not refused for it: {errors}")
        cases += 1
        if not check_live(out, work / "live" / "absent.json"):
            failures.append("live: a missing copy of the live manifest passed for none")

        # 7. --check holds the manifest to SHA256SUMS.txt and to its own form.
        cases += 1
        (out.parent / "SHA256SUMS.txt").write_text(sums_for(newer))
        if not any("SHA256SUMS.txt" in e for e in check(out, out.parent / "SHA256SUMS.txt")):
            failures.append("--check accepted sums of other bytes")
        cases += 1
        (out.parent / "SHA256SUMS.txt").write_text(sums_for(release))
        out.write_bytes(json.dumps(manifest).encode())
        if not any("canonical" in e or "form" in e for e in check(out, out.parent / "SHA256SUMS.txt")):
            failures.append("--check accepted a manifest edited by hand")
        cases += 1
        broken = json.loads(dum.serialize(manifest))
        broken["platforms"]["macos-zip"]["file"] = "khandaq-macos-old.zip"
        out.write_bytes(dum.serialize(broken))
        if not any("is not 'khandaq-macos.zip'" in e for e in check(out, out.parent / "SHA256SUMS.txt")):
            failures.append("--check accepted a file name the app refuses")

        # 8. --with-downloads: every download the manifest names is beside it, as deploy-site.sh
        # leaves them once it has fetched what it did not stage.
        cases += 1
        beside = work / "release" / dum.MANIFEST_NAME
        beside.write_bytes(dum.serialize(manifest))
        (beside.parent / "SHA256SUMS.txt").write_text(sums_for(release))
        if check(beside, beside.parent / "SHA256SUMS.txt", with_downloads=True):
            failures.append(f"--with-downloads refused the downloads beside it: "
                            f"{check(beside, beside.parent / 'SHA256SUMS.txt', with_downloads=True)}")
        cases += 1
        (work / "release" / "khandaq-macos.zip").unlink()
        errors = check(beside, beside.parent / "SHA256SUMS.txt", with_downloads=True)
        if errors != ["macos-zip: khandaq-macos.zip is neither staged nor published"]:
            failures.append(f"--with-downloads, a download missing: {errors}")
        if check(beside, beside.parent / "SHA256SUMS.txt"):
            failures.append("--check without --with-downloads asked for the downloads")

        # 9. The installer as 7-Zip lists it.
        cases += 1
        if nsis_program_size(nsis_entries(NSIS_LISTING, "listing"), "listing") != 16520704:
            failures.append("7-Zip listing: not the size of bin/Khandaq.exe")
        listings = [
            ("another format", NSIS_LISTING.replace("Type = Nsis", "Type = PE"), "not an NSIS installer"),
            ("no files", NSIS_LISTING.split("----------")[0], "lists no files"),
            ("two programs", NSIS_LISTING + "\nPath = Khandaq.exe\nSize = 10\n", "not exactly one program"),
            ("another folder", NSIS_LISTING.replace("Path = bin/Khandaq.exe", "Path = app/Khandaq.exe"),
             "not exactly one program"),
            ("no size", NSIS_LISTING.replace("Size = 16520704", "Size = "), "gives no size"),
            ("a size that is no number", NSIS_LISTING.replace("Size = 16520704", "Size = 16520704 B"),
             "gives no size"),
            ("too large", NSIS_LISTING.replace("Size = 16520704", f"Size = {dum.ARTIFACT_LIMIT + 1}"),
             "more than any program"),
        ]
        for name, listing, needle in listings:
            cases += 1
            try:
                nsis_program_size(nsis_entries(listing, "listing"), "listing")
                failures.append(f"7-Zip listing, {name}: not refused")
            except Refused as exc:
                if needle not in str(exc):
                    failures.append(f"7-Zip listing, {name}: refused for another reason: {exc}")
        cases += 1
        try:
            seven_zip("khandaq-windows-installer.exe", which=lambda name: None)
            failures.append("no 7-Zip: not refused")
        except Refused as exc:
            if "this machine has none" not in str(exc):
                failures.append(f"no 7-Zip: refused for another reason: {exc}")
        if any(shutil.which(name) for name in SEVEN_ZIP):
            # A Windows program and a zip, which 7-Zip reads in formats of their own unless held to NSIS.
            for name, path in (("a program", mixed[0]), ("a zip", mixed[1])):
                cases += 1
                try:
                    installer_program(path)
                    failures.append(f"7-Zip unpacked a program from {name}, which is no NSIS installer")
                except Refused as exc:
                    if "cannot read it as an NSIS installer" not in str(exc):
                        failures.append(f"7-Zip on {name}: refused for another reason: {exc}")
        else:
            print("  skipped: 7-Zip on a file that is no installer (no 7-Zip on this machine)")
    except Refused as exc:
        failures.append(f"refused where it must not be: {exc}")
    finally:
        shutil.rmtree(work, ignore_errors=True)

    if failures:
        print("FAIL: generate-desktop-update-manifest.py self-test:", file=sys.stderr)
        for failure in failures:
            print(f"  - {failure}", file=sys.stderr)
        return 1
    print(f"ok: generate-desktop-update-manifest.py self-test, {cases} cases")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("artifacts", nargs="*", type=Path, help="the downloads staged from dist/")
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST)
    parser.add_argument("--sums", type=Path, default=None, help="default: SHA256SUMS.txt beside it")
    parser.add_argument("--check", action="store_true", help="check the manifest, write nothing")
    parser.add_argument("--with-downloads", action="store_true",
                        help="with --check: each download the manifest names must be beside it")
    parser.add_argument("--against-live", type=Path, metavar="LIVE",
                        help="compare the manifest with LIVE, the live one copied from the server "
                             "(empty for none); write nothing")
    parser.add_argument("--self-test", action="store_true", help="run the fixtures and exit")
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    sums = args.sums or args.manifest.parent / "SHA256SUMS.txt"
    if args.against_live:
        try:
            errors = check_live(args.manifest, args.against_live)
        except Refused as exc:
            errors = [str(exc)]
        for error in errors:
            print(f"::error::{args.manifest.name} against the live one: {error}", file=sys.stderr)
        if errors:
            return 1
        if args.against_live.stat().st_size:
            print(f"ok: {args.manifest.name} takes nothing back from the live one")
        else:
            print("ok: no update manifest is live yet, nothing to compare with")
        return 0
    if args.check:
        if not args.manifest.is_file():
            print(f"ok: no {args.manifest.name} yet, nothing to check")
            return 0
        errors = check(args.manifest, sums, with_downloads=args.with_downloads)
        for error in errors:
            print(f"::error::{args.manifest.name}: {error}", file=sys.stderr)
        if not errors:
            print(f"ok: {args.manifest.name} agrees with {sums.name}")
        return 1 if errors else 0
    if not args.artifacts:
        parser.error("name the staged downloads, or give --check or --self-test")
    try:
        manifest = generate(args.artifacts, args.manifest, sums)
    except Refused as exc:
        print(f"::error::{dum.MANIFEST_NAME} not written: {exc}", file=sys.stderr)
        return 1
    changed = write(manifest, args.manifest)
    for platform, entry in sorted(manifest["platforms"].items()):
        print(f"    {platform}: {entry['version']} {entry['file']} ({entry['size']} bytes)")
    print(f"    {args.manifest.name}: {'written; to be signed' if changed else 'unchanged'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
