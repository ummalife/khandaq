#!/usr/bin/env python3
"""KHANDAQ (0.2.65): write the signed manifest the desktop app updates from.

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
refused, and so is a QA build (UPDATE_CHECK_QA), the only kind that contains the name of the variable
that points the check elsewhere. The installer's payload is compressed, so its version comes from its
version resource (VIProductVersion in qtox64.nsi) and is believed only when the portable zip of the
same build is given too and agrees.

Only the files named on the command line are read, and deploy-site.sh names only what it has just
staged from dist/, never a file fetched from the server. A platform not named keeps its entry from
the manifest already beside SHA256SUMS.txt (committed with its signature after each release),
provided SHA256SUMS.txt still gives the same bytes for it. A platform never goes back to an older
version: the apps that saw the newer one would not take it, and the rest should not.

    scripts/generate-desktop-update-manifest.py web/downloads/khandaq-macos.zip ...  # write it
    scripts/generate-desktop-update-manifest.py --check      # it agrees with SHA256SUMS.txt
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
import zipfile
from pathlib import Path

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


def windows_zip(path: Path) -> str:
    with zipfile.ZipFile(path) as archive:
        names = [info for info in archive.infolist() if info.filename.lower() == "khandaq.exe"]
        if len(names) != 1:
            raise Refused(f"{path.name}: Khandaq.exe is not at the top of the zip exactly once")
        limited(names[0].file_size, path.name)
        return program_version(archive.read(names[0]), f"{path.name}:{names[0].filename}")


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
    match = re.search(r"^Version:\s*(\S+)\s*$", text, re.M)
    if not match:
        raise Refused(f"{path.name}: its control file has no Version")
    # [epoch:]upstream[-revision]
    package = match.group(1).split(":", 1)[-1].split("-", 1)[0]
    with deb_tar(members, "data.tar", path.name) as data:
        version = program_version(tar_member(data, DEB_PROGRAM, path.name), f"{path.name}:{DEB_PROGRAM}")
    if package != version:
        raise Refused(f"{path.name}: the package says {match.group(1)}, its program {version}")
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


def generate(artifacts: list[Path], manifest_path: Path, sums_path: Path) -> dict:
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
                          "qtox64.nsi; it is believed only beside the zip of the same build, which "
                          "was not given")
        if installer != portable:
            raise Refused(f"khandaq-windows-installer.exe says {installer}, the program in the zip of "
                          f"the same build {portable} (is VERSION in qtox64.nsi bumped?)")

    previous = (load_manifest(manifest_path) or {}).get("platforms", {})
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


def check(manifest_path: Path, sums_path: Path) -> list[str]:
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
        if local.is_file() and (local.stat().st_size != entry["size"] or sha256_of(local) != entry["sha256"]):
            errors.append(f"{platform}: {entry['file']} here is not the file the manifest describes")
    return errors


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


def fake_installer(path: Path, version: tuple[int, int, int, int], decoy: tuple[int, int, int, int]) -> None:
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
    path.write_bytes(bytes(head) + text + resources)


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
    fake_installer(paths["windows-x86_64-installer"], parts, (9, 9, 9, 0))
    fake_macos_zip(paths["macos-zip"], override.get("macos", program), fake_program("v9.9.9"))
    fake_deb(paths["linux-deb-amd64"], override.get("deb", program), version, compression)
    fake_linux_tarball(paths["linux-portable-x86_64"], override.get("linux", program))
    return list(paths.values())


def sums_for(paths: list[Path]) -> str:
    return "".join(f"{sha256_of(p)}  {p.name}\n" for p in sorted(paths, key=lambda p: p.name))


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
            generate(artifacts, manifest, sums or manifest.parent / "SHA256SUMS.txt")
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
        manifest = generate(release, out, out.parent / "SHA256SUMS.txt")
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
        carried = generate([newer[2]], carry, carry.parent / "SHA256SUMS.txt")
        if carried["platforms"]["macos-zip"]["version"] != "0.2.66":
            failures.append("carry over: the staged platform did not change")
        if any(carried["platforms"][p] != manifest["platforms"][p] for p in dum.PLATFORM_FILES if p != "macos-zip"):
            failures.append("carry over: a platform not staged changed")
        if not write(carried, carry) or (carry.parent / (dum.MANIFEST_NAME + ".sig")).exists():
            failures.append("carry over: a changed manifest kept the old signature")
        cases += 1
        signature = carry.parent / (dum.MANIFEST_NAME + ".sig")
        signature.write_bytes(b"signature of the same bytes")
        if write(carried, carry) or not signature.exists():
            failures.append("rewriting the same manifest dropped its signature")

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

        # 5. --check holds the manifest to SHA256SUMS.txt and to its own form.
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
    parser.add_argument("--self-test", action="store_true", help="run the fixtures and exit")
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    sums = args.sums or args.manifest.parent / "SHA256SUMS.txt"
    if args.check:
        if not args.manifest.is_file():
            print(f"ok: no {args.manifest.name} yet, nothing to check")
            return 0
        errors = check(args.manifest, sums)
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
