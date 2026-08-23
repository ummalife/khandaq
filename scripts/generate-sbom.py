#!/usr/bin/env python3
"""One CycloneDX SBOM for the whole product, across every ecosystem it ships.

KHANDAQ (re-review 2026-08-22, KQ-06). The repository pins well: Maven artifacts are hash-locked in
witness.gradle, pods and gems have lockfiles, the relay's Python closure is hash-locked, and the
desktop's bundled C libraries are inventoried with lifecycle dates. What none of that answers is the
question the review asks — "has a correctly pinned version become vulnerable since we pinned it?"
Pinning stops silent substitution; it says nothing about tomorrow's advisory.

Answering it needs a single list of everything, in one format, that a scanner can read. That is this
file. It is deliberately derived from the LOCK/PIN files rather than from the build's declarations:
the lock is what actually ships.

  Maven      khandaq-android-trifa/.../app/witness.gradle      (338 artifacts, each with its sha256)
  CocoaPods  khandaq-ios/Podfile.lock
  RubyGems   khandaq-ios/Gemfile.lock
  PyPI       infra/push/relay/requirements.txt
  generic    khandaq-desktop/buildscripts/bundled-deps.json    (bundled C libraries)

    scripts/generate-sbom.py                 # write sbom/khandaq-sbom.json
    scripts/generate-sbom.py --print         # to stdout
    scripts/generate-sbom.py --check         # fail if the committed SBOM is stale
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "sbom" / "khandaq-sbom.json"

WITNESS = ROOT / "khandaq-android-trifa/android-refimpl-app/app/witness.gradle"
# KHANDAQ (re-review 2026-08-22, KQ-06): "verify the release SBOM exactly describes the SHIPPED
# binary set, not only build files". witness.gradle pins every configuration — annotation
# processors, lint, test runners — so scanning it wholesale reports advisories in tooling as though
# they were in the app. The first run of the scanner said 54 vulnerabilities; every one of them was
# build-time, and the guava that actually ships (33.0.0-android) had none. That distinction is the
# difference between a useful gate and one people learn to ignore.
#
# This file is the resolved releaseRuntimeClasspath, captured by `--refresh-runtime` (which needs
# Gradle and the Android SDK) and committed so ordinary runs and CI need neither.
RUNTIME_LIST = ROOT / "sbom" / "android-release-runtime.txt"
ANDROID_PROJECT = ROOT / "khandaq-android-trifa" / "android-refimpl-app"
PODFILE = ROOT / "khandaq-ios/Podfile.lock"
GEMFILE = ROOT / "khandaq-ios/Gemfile.lock"
REQS = ROOT / "infra/push/relay/requirements.txt"
DESKTOP = ROOT / "khandaq-desktop/buildscripts/bundled-deps.json"
ANDROID_NATIVE = ROOT / "khandaq-android-trifa/circle_scripts/deps.sh"


def read(p: Path) -> str:
    return p.read_text(encoding="utf-8", errors="replace") if p.is_file() else ""


def shipped_coordinates() -> set[str] | None:
    """`group:artifact:version` that survive into releaseRuntimeClasspath, or None if not captured."""
    if not RUNTIME_LIST.is_file():
        return None
    out = set()
    for line in read(RUNTIME_LIST).splitlines():
        line = line.strip()
        if line and not line.startswith("#"):
            out.add(line)
    return out or None


def maven_components() -> list[dict]:
    """`'group:artifact:version:file:sha256',` — the exact form the release build verifies against."""
    shipped = shipped_coordinates()
    out = []
    for m in re.finditer(r"^\s*'([^:']+):([^:']+):([^:']+):([^:']+):([0-9a-f]{64})',", read(WITNESS), re.M):
        group, artifact, version, _file, digest = m.groups()
        coord = f"{group}:{artifact}:{version}"
        comp = {
            "type": "library",
            "name": f"{group}:{artifact}",
            "version": version,
            "purl": f"pkg:maven/{group}/{artifact}@{version}",
            "hashes": [{"alg": "SHA-256", "content": digest}],
        }
        # CycloneDX scope: `required` means it is part of what ships. `excluded` is the spec's word
        # for a component present in the build but not in the delivered artifact. Unknown (no
        # captured list) deliberately reads as `required`, so a missing capture over-reports rather
        # than under-reports.
        comp["scope"] = "required" if (shipped is None or coord in shipped) else "excluded"
        out.append(comp)
    return out


def cocoapods_components() -> list[dict]:
    """The resolved SPEC CHECKSUMS section, not the PODS tree: it lists each pod exactly once."""
    text = read(PODFILE)
    section = text.split("SPEC CHECKSUMS:", 1)
    out = []
    versions = {}
    for m in re.finditer(r"^\s+- ([A-Za-z0-9_.+-]+) \(([^)]+)\)", text, re.M):
        versions.setdefault(m.group(1), m.group(2))
    if len(section) == 2:
        for m in re.finditer(r"^\s+([A-Za-z0-9_.+-]+): ([0-9a-f]{40})", section[1], re.M):
            name, digest = m.groups()
            ver = versions.get(name, "unknown")
            out.append({
                "type": "library",
                "name": name,
                "version": ver,
                "purl": f"pkg:cocoapods/{name}@{ver}",
                "hashes": [{"alg": "SHA-1", "content": digest}],
            })
    return out


def rubygems_components() -> list[dict]:
    text = read(GEMFILE)
    out, seen = [], set()
    body = text.split("GEM", 1)[-1].split("PLATFORMS", 1)[0]
    for m in re.finditer(r"^\s{4}([a-zA-Z0-9_.-]+) \(([0-9][^)]*)\)", body, re.M):
        name, ver = m.groups()
        if (name, ver) in seen:
            continue
        seen.add((name, ver))
        out.append({
            "type": "library", "name": name, "version": ver,
            "purl": f"pkg:gem/{name}@{ver}",
        })
    return out


def pypi_components() -> list[dict]:
    out = []
    for m in re.finditer(r"^([A-Za-z0-9_.-]+)==([^\s\\]+)", read(REQS), re.M):
        name, ver = m.groups()
        out.append({
            "type": "library", "name": name, "version": ver,
            "purl": f"pkg:pypi/{name.lower().replace('_', '-')}@{ver}",
        })
    return out


def desktop_components() -> list[dict]:
    """The curated bundled inventory. purl `generic` because these are vendored source tarballs."""
    if not DESKTOP.is_file():
        return []
    data = json.loads(read(DESKTOP))
    out = []
    for c in data.get("components", []):
        name, ver = c.get("name"), str(c.get("version", ""))
        comp = {
            "type": "library", "name": name, "version": ver,
            "purl": f"pkg:generic/{name}@{ver}",
            "description": c.get("purpose", ""),
        }
        if c.get("eol"):
            comp["properties"] = [{"name": "khandaq:eol", "value": c["eol"]}]
        out.append(comp)
    return out


def android_native_components() -> list[dict]:
    """The C libraries compiled into libjni-c-toxcore.so, which is in every APK.

    KHANDAQ (internal audit 2026-08-22). This was missing entirely, and its absence was worse than a
    gap: the desktop inventory contributed `pkg:generic/vpx@1.14.1`, so the SBOM positively asserted
    a version of libvpx that no Android user has. What ships on Android is 1.8.0 — a 2019 release
    that decodes video out of untrusted NGC packets. A reader checking whether a new libvpx advisory
    affects this product would have looked at the SBOM and concluded it does not.

    The versions are read from the build script that actually fetches them, so they cannot drift from
    what is compiled. Names are prefixed and the purl carries a platform qualifier, because the same
    library at a different version ships on the desktop and the two must not merge into one row.
    """
    text = read(ANDROID_NATIVE)
    if not text:
        return []
    out = []
    for var, name in (("_FFMPEG_VERSION_", "ffmpeg"), ("_OPUS_VERSION_", "opus"),
                      ("_VPX_VERSION_", "vpx"), ("_LIBSODIUM_VERSION_", "libsodium"),
                      ("_X264_VERSION_", "x264")):
        m = re.search(rf'^{re.escape(var)}="([^"]+)"', text, re.M)
        if not m:
            continue
        ver = m.group(1).lstrip("nv") if re.match(r"^[nv][0-9]", m.group(1)) else m.group(1)
        out.append({
            "type": "library",
            "name": f"android-native-{name}",
            "version": ver,
            "purl": f"pkg:generic/{name}@{ver}?platform=android",
            "description": f"compiled into libjni-c-toxcore.so, shipped in every APK ({name})",
            "properties": [
                {"name": "khandaq:platform", "value": "android"},
                {"name": "khandaq:pin_file", "value": "khandaq-android-trifa/circle_scripts/deps.sh"},
                {"name": "khandaq:pin_var", "value": var},
            ],
        })
    return out


def build() -> dict:
    groups = [
        ("maven", maven_components()),
        ("cocoapods", cocoapods_components()),
        ("gem", rubygems_components()),
        ("pypi", pypi_components()),
        ("generic", desktop_components()),
        ("android-native", android_native_components()),
    ]
    components = []
    for eco, items in groups:
        for c in items:
            c["group"] = eco
            components.append(c)
    components.sort(key=lambda c: (c["group"], c["name"], c["version"]))
    return {
        "bomFormat": "CycloneDX",
        "specVersion": "1.5",
        "version": 1,
        "metadata": {
            # No timestamp: it would change on every run, making the committed file permanently
            # dirty and `--check` useless. The git history is the timestamp.
            "component": {"type": "application", "name": "khandaq", "version": "see release-manifest.json"},
            "tools": [{"name": "scripts/generate-sbom.py"}],
            "properties": [
                {"name": "khandaq:sources",
                 "value": "witness.gradle, Podfile.lock, Gemfile.lock, requirements.txt, bundled-deps.json"},
            ],
        },
        "components": components,
    }


def refresh_runtime() -> int:
    """Capture what the release actually links against, so the SBOM can mark scope honestly."""
    import subprocess

    gradlew = ANDROID_PROJECT / "gradlew"
    if not gradlew.is_file():
        print("::error::нет gradlew — запустите из полного checkout", file=sys.stderr)
        return 1
    print("==> ./gradlew :app:dependencies --configuration releaseRuntimeClasspath")
    r = subprocess.run([str(gradlew), "--no-daemon", "-q", ":app:dependencies",
                        "--configuration", "releaseRuntimeClasspath", "--offline"],
                       cwd=ANDROID_PROJECT, capture_output=True, text=True)
    if r.returncode != 0 and not r.stdout.strip():
        print(r.stderr[-2000:], file=sys.stderr)
        print("::error::Gradle не смог разрешить конфигурацию", file=sys.stderr)
        return 1
    coords = set()
    for line in r.stdout.splitlines():
        # "+--- group:artifact:1.2.3" and "... -> 1.2.4" (a resolved override wins)
        m = re.search(r"([A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+):([0-9][A-Za-z0-9_.+-]*)(?:\s*->\s*([0-9][A-Za-z0-9_.+-]*))?",
                      line)
        if m:
            ga, declared, resolved = m.groups()
            coords.add(f"{ga}:{resolved or declared}")
    if not coords:
        print("::error::не разобрано ни одной координаты — формат вывода Gradle изменился",
              file=sys.stderr)
        return 1
    RUNTIME_LIST.parent.mkdir(parents=True, exist_ok=True)
    RUNTIME_LIST.write_text(
        "# Generated by scripts/generate-sbom.py --refresh-runtime.\n"
        "# group:artifact:version that survive into releaseRuntimeClasspath — i.e. what SHIPS.\n"
        "# Everything pinned in witness.gradle but absent here is build-time only.\n"
        + "\n".join(sorted(coords)) + "\n", encoding="utf-8")
    print(f"написан {RUNTIME_LIST.relative_to(ROOT)}: {len(coords)} координат(ы) в релизной сборке")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--print", action="store_true")
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--refresh-runtime", action="store_true",
                    help="перечитать releaseRuntimeClasspath через Gradle (нужны SDK и сеть/кэш)")
    args = ap.parse_args()

    if args.refresh_runtime:
        return refresh_runtime()

    sbom = build()
    text = json.dumps(sbom, indent=2, ensure_ascii=False, sort_keys=True) + "\n"
    counts: dict[str, int] = {}
    for c in sbom["components"]:
        counts[c["group"]] = counts.get(c["group"], 0) + 1
    summary = ", ".join(f"{k}={v}" for k, v in sorted(counts.items()))

    if args.print:
        sys.stdout.write(text)
        return 0
    if args.check:
        if not OUT.is_file():
            print(f"::error::{OUT.relative_to(ROOT)} отсутствует — запустите scripts/generate-sbom.py",
                  file=sys.stderr)
            return 1
        if OUT.read_text(encoding="utf-8") != text:
            print(f"::error::{OUT.relative_to(ROOT)} устарел относительно lock-файлов. "
                  f"Зависимость изменилась, а SBOM — нет, поэтому сканирование смотрит не на то, "
                  f"что собирается. Перегенерируйте: scripts/generate-sbom.py", file=sys.stderr)
            return 1
        print(f"ок: SBOM соответствует lock-файлам ({summary})")
        return 0

    if not sbom["components"]:
        print("::error::SBOM получился пустым — источники не разобрались, а пустой SBOM "
              "сканируется без единой находки и выглядит как чистый", file=sys.stderr)
        return 1
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(text, encoding="utf-8")
    print(f"написан {OUT.relative_to(ROOT)}: {len(sbom['components'])} компонент(ов) — {summary}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
