#!/usr/bin/env python3
"""Ask NVD about the vendored C libraries, which OSV cannot answer for.

KHANDAQ (re-review v2 2026-08-22, RR2-08). scripts/check-vulnerable-deps.py deliberately skips
`pkg:generic/*` because OSV has no such ecosystem, and delegated them to check-bundled-deps-eol.py —
which is a lifecycle inventory, not an advisory feed. So a newly disclosed CVE in libvpx, libexif,
OpenAL, SQLCipher or libsodium could appear with no source change and every scheduled job would stay
green. That is the largest memory-unsafe surface in the product: it decodes media and protocol
packets that arrive from other peers.

This closes that by querying NVD by CPE. Components that have no CPE must say so in
security/native-cpe-map.json with a reason — a component that is neither scanned nor explicitly
excused fails the run, so the coverage gap cannot quietly reopen.

    scripts/check-native-advisories.py
    scripts/check-native-advisories.py --offline   # только структура и полнота карты
    scripts/check-native-advisories.py --since 2025-01-01

NVD without an API key allows 5 requests per 30 seconds, so a full run takes a few minutes. It is a
scheduled job, not a per-pull-request gate.
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SBOM = ROOT / "sbom" / "khandaq-sbom.json"
CPE_MAP = ROOT / "security" / "native-cpe-map.json"
WAIVERS = ROOT / "security-waivers.json"
NVD = "https://services.nvd.nist.gov/rest/json/cves/2.0"
# Без ключа NVD разрешает 5 запросов за 30 секунд. Берём с запасом.
SLEEP_SEC = 7.0


def die(msg: str) -> int:
    print(f"::error::{msg}", file=sys.stderr)
    return 1


def native_components() -> list[dict]:
    sbom = json.loads(SBOM.read_text(encoding="utf-8"))
    out = []
    for c in sbom.get("components", []):
        purl = c.get("purl", "")
        if not purl.startswith("pkg:generic/"):
            continue
        name = purl.split("/", 1)[1].split("@")[0]
        version = purl.split("@", 1)[1].split("?")[0] if "@" in purl else c.get("version", "")
        platform = ""
        for prop in c.get("properties") or []:
            if prop.get("name") == "khandaq:platform":
                platform = prop.get("value", "")
        out.append({"key": name, "name": c.get("name", name), "version": version,
                    "platform": platform or "desktop"})
    return out


def query_nvd(cpe: str, version: str) -> list[dict]:
    # Точный CPE конкретной версии: широкий поиск по продукту вернул бы советы про все ветки сразу.
    cpe_name = f"{cpe}:{version}:*:*:*:*:*:*:*"
    url = NVD + "?" + urllib.parse.urlencode({"cpeName": cpe_name})
    req = urllib.request.Request(url, headers={"User-Agent": "khandaq-native-scan/1"})
    try:
        with urllib.request.urlopen(req, timeout=90) as resp:  # noqa: S310 - fixed https host
            data = json.load(resp)
    except urllib.error.HTTPError as exc:
        if exc.code == 404:
            return []
        raise
    out = []
    for item in data.get("vulnerabilities", []):
        cve = item.get("cve", {})
        metrics = cve.get("metrics", {})
        sev = ""
        for key in ("cvssMetricV31", "cvssMetricV30", "cvssMetricV2"):
            if metrics.get(key):
                sev = (metrics[key][0].get("cvssData", {}).get("baseSeverity")
                       or metrics[key][0].get("baseSeverity") or "")
                break
        out.append({"id": cve.get("id", "?"), "severity": (sev or "UNKNOWN").upper(),
                    "published": cve.get("published", "")[:10]})
    return out


def verify_patched(mapping_raw: dict) -> tuple[dict, list[str]]:
    """Advisories closed by a build-time backport, and proof the backport is still there.

    KHANDAQ (2026-08-23). The scanner asks NVD about a VERSION, so it cannot see a patch. Without
    this section a fixed vulnerability looked identical to an accepted one — and, worse, the day
    somebody dropped the patch from deps.sh nothing would have said so, because the version string
    would not move. So each entry is checked rather than believed: the patch file must exist, and the
    build script must still apply it at least as many times as it did when the entry was written
    (libvpx is cloned once per ABI, so one missing site is one architecture left vulnerable).
    """
    out, problems = {}, []
    for cve, rec in (mapping_raw.get("patched") or {}).items():
        patch = ROOT / str(rec.get("patch_file", ""))
        applier = ROOT / str(rec.get("applied_by", ""))
        want = int(rec.get("min_apply_sites", 1))
        if not patch.is_file():
            problems.append(f"{cve}: файла патча {rec.get('patch_file')} нет — уязвимость считалась "
                            f"закрытой, а закрывать её больше нечем")
            continue
        if not applier.is_file():
            problems.append(f"{cve}: нет {rec.get('applied_by')}, который должен применять патч")
            continue
        # Считаем строки, которые ПРИМЕНЯЮТ патч, а не любые упоминания имени файла: в шапке
        # deps.sh стоит комментарий с тем же именем, и подсчёт вхождений давал на единицу больше —
        # проверка проходила бы даже после удаления одного места применения. Найдено собственным
        # отрицательным тестом; без него гейт молча ослаб бы на один ABI.
        sites = sum(1 for ln in applier.read_text(encoding="utf-8", errors="replace").splitlines()
                    if patch.name in ln and "patch -p1" in ln and not ln.strip().startswith("#"))
        if sites < want:
            problems.append(
                f"{cve}: {applier.name} применяет {patch.name} {sites} раз(а) вместо {want}. "
                f"libvpx клонируется отдельно под каждый ABI — недостающее место это архитектура, "
                f"которая уезжает пользователю без исправления.")
            continue
        out[(cve, str(rec.get("package")), str(rec.get("version")))] = rec
    return out, problems


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--offline", action="store_true")
    ap.add_argument("--since", default="", help="сообщать только про CVE, опубликованные после даты")
    args = ap.parse_args()

    if not SBOM.is_file():
        return die("нет SBOM — сначала scripts/generate-sbom.py")
    if not CPE_MAP.is_file():
        return die(f"нет {CPE_MAP.relative_to(ROOT)} — нативные компоненты не с чем сопоставить")

    mapping_raw = json.loads(CPE_MAP.read_text(encoding="utf-8"))
    mapping = mapping_raw.get("components", {})
    patched, patch_problems = verify_patched(mapping_raw)
    if patch_problems:
        for p in patch_problems:
            print(f"::error::{p}", file=sys.stderr)
        return 1
    if patched:
        print(f"==> Бэкпортов проверено: {len(patched)} (файл патча на месте и применяется)")
        for (cve, pkg, ver) in sorted(patched):
            print(f"    ЗАКРЫТО ПАТЧЕМ {cve}  {pkg}@{ver}")
    comps = native_components()
    if not comps:
        return die("в SBOM нет ни одного generic-компонента — это не 'чисто', это сломанный SBOM")

    unmapped = sorted({c["key"] for c in comps if c["key"] not in mapping})
    if unmapped:
        return die(f"нет записи в {CPE_MAP.name} для: {', '.join(unmapped)}. Каждый нативный "
                   f"компонент должен быть либо просканирован, либо явно объяснён — иначе пробел "
                   f"в покрытии выглядит как чистый результат.")

    scannable = [c for c in comps if mapping[c["key"]].get("cpe")]
    excused = [c for c in comps if not mapping[c["key"]].get("cpe")]
    print(f"==> Нативных компонентов: {len(comps)}; сканируется {len(scannable)}, "
          f"объяснено без CPE {len(excused)}")
    for c in excused:
        why = mapping[c["key"]].get("why", "").strip()
        if not why:
            return die(f"{c['key']}: cpe не задан и причина не написана")
        print(f"    не сканируется {c['key']}@{c['version']} — {why}")

    # KHANDAQ (re-review 2026-08-23, KQ-R12): validate the waivers BEFORE the offline exit.
    #
    # They used to be parsed only on the network path, and --offline is what runs on every pull
    # request — so an EXPIRED waiver passed CI silently and would only surface in the weekly
    # scheduled run. The whole point of an expiry is that somebody is made to re-argue it on time.
    # Checking dates needs no network.
    waived = set()
    expired: list[str] = []
    if WAIVERS.is_file():
        raw = json.loads(WAIVERS.read_text(encoding="utf-8"))
        today = dt.date.today()
        for w in raw.get("native_waivers", []):
            # Ключ включает ВЕРСИЮ: ffmpeg едет и на Android (6.0), и на десктопе (4.4.8), и waiver,
            # выписанный на одну сборку, не должен молча закрывать ту же CVE в другой.
            try:
                if not str(w.get("owner", "")).strip() or not str(w.get("exploitability", "")).strip():
                    return die(f"native_waivers {w.get('id')}: нужны owner и exploitability")
                if dt.date.fromisoformat(str(w["expires"])) >= today:
                    waived.add((w["id"], w["package"], str(w["version"])))
                else:
                    # An expired waiver is a FAILURE, not merely an inactive entry. Dropping it
                    # silently meant the advisory came back as "unwaived" — but only on the network
                    # path, so on a pull request nothing said anything at all. The date exists to
                    # make somebody re-argue the case on time; letting it slide defeats it.
                    expired.append(f"{w['id']} для {w['package']}@{w['version']} истёк "
                                   f"{w['expires']} (владелец {w.get('owner', '?')})")
            except (KeyError, ValueError):
                return die(f"native_waivers: запись {w!r} без корректных id/package/version/expires")

    if expired:
        for e in expired:
            print(f"::error::waiver {e}", file=sys.stderr)
        return die(f"{len(expired)} waiver-ов истекли. Переобоснуйте с новой датой или закройте "
                   f"находку — срок для того и ставится.")

    if args.offline:
        print(f"режим --offline: проверены полнота карты, структура и сроки waiver-ов "
              f"({len(waived)} действующих)")
        return 0

    findings, errors = [], []
    for i, c in enumerate(scannable):
        cpe = mapping[c["key"]]["cpe"]
        try:
            hits = query_nvd(cpe, c["version"])
        except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError, OSError, ValueError) as exc:
            errors.append(f"{c['key']}@{c['version']}: NVD недоступен ({exc})")
            hits = []
        label = f"{c['key']}@{c['version']} [{c['platform']}]"
        if hits:
            for h in hits:
                if args.since and h["published"] and h["published"] < args.since:
                    continue
                if (h["id"], c["key"], c["version"]) in patched:
                    # Закрыто бэкпортом, проверенным выше. Не находка и не waiver.
                    continue
                if (h["id"], c["key"], c["version"]) in waived:
                    print(f"    ВАЙВЕР {h['id']} {label}")
                    continue
                findings.append((h["severity"], h["id"], label, h["published"]))
        print(f"    [{i + 1}/{len(scannable)}] {label}: {len(hits)} advisory")
        if i + 1 < len(scannable):
            time.sleep(SLEEP_SEC)

    print()
    if errors:
        for e in errors:
            print(f"::error::{e}", file=sys.stderr)
        print("::error::сканер, который при сбое сети пропускает, — это не гейт", file=sys.stderr)
        return 1
    if findings:
        order = {"CRITICAL": 0, "HIGH": 1, "MEDIUM": 2, "LOW": 3, "UNKNOWN": 4}
        findings.sort(key=lambda f: order.get(f[0], 9))
        print(f"::error::найдено {len(findings)} незакрытых advisory в нативных библиотеках",
              file=sys.stderr)
        for sev, cid, label, pub in findings:
            print(f"::error::  [{sev}] {cid}  {label}  опубликовано {pub}  "
                  f"https://nvd.nist.gov/vuln/detail/{cid}", file=sys.stderr)
        print("::error::закройте обновлением версии или добавьте запись в native_waivers "
              "(id, package, owner, exploitability, expires) в security-waivers.json", file=sys.stderr)
        return 1
    print("ВСЁ ЧИСТО: по нативным библиотекам незакрытых advisory нет")
    return 0


if __name__ == "__main__":
    sys.exit(main())
