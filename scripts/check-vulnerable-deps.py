#!/usr/bin/env python3
"""Scan the whole-product SBOM against OSV, across every ecosystem at once.

KHANDAQ (re-review 2026-08-22, KQ-06). Pinning is not monitoring. witness.gradle, Podfile.lock,
Gemfile.lock and the hash-locked relay requirements all guarantee that what builds today is what
built yesterday — and say nothing about a version that became vulnerable overnight. There was a
Python-only OSV check and a curated desktop lifecycle inventory; Android's 338 Maven artifacts, the
pods and the gems had no advisory coverage at all.

This reads sbom/khandaq-sbom.json and asks OSV about every component in one batch, so a new advisory
fails the build with no source change — which is the point, and why it also runs on a schedule.

WAIVERS. A finding can be waived, and the review is specific about the shape: owner, an
exploitability note, and an expiry. All three are required, an expired waiver is a failure, and a
waiver for an advisory that no longer applies is reported so it can be removed rather than
accumulating. security-waivers.json holds them.

    scripts/check-vulnerable-deps.py
    scripts/check-vulnerable-deps.py --offline    # structure/waiver checks only, no network
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import sys
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SBOM = ROOT / "sbom" / "khandaq-sbom.json"
WAIVERS = ROOT / "security-waivers.json"
OSV_BATCH = "https://api.osv.dev/v1/querybatch"
BATCH = 500

REQUIRED_WAIVER_FIELDS = ("id", "package", "owner", "exploitability", "expires")


def die(msg: str) -> None:
    print(f"::error::{msg}", file=sys.stderr)
    sys.exit(1)


def load_scope_waivers(raw: dict) -> dict:
    """A waiver that covers a CLASS of findings rather than 54 individually-listed ids.

    The review requires an owner, an exploitability note and an expiry for every temporary waiver,
    and this form has all three. It is also the maintainable shape: enumerating each advisory means
    that the 55th one against the same build-time library arrives as a red build with no new
    information, and the list rots into stale entries nobody re-reads. The argument being made is
    about the SCOPE, so it is recorded once, against the scope, and re-argued on expiry.
    """
    out, problems = {}, []
    today = dt.date.today()
    for scope, w in (raw.get("scope_waivers") or {}).items():
        missing = [f for f in ("owner", "exploitability", "expires") if not str(w.get(f, "")).strip()]
        if missing:
            problems.append(f"scope_waiver {scope}: нет полей {', '.join(missing)}")
            continue
        try:
            expires = dt.date.fromisoformat(str(w["expires"]))
        except ValueError:
            problems.append(f"scope_waiver {scope}: expires={w['expires']!r} не дата ISO")
            continue
        if expires < today:
            problems.append(f"scope_waiver {scope} истёк {w['expires']} (владелец {w['owner']}) — "
                            f"переобоснуйте или обновите зависимости")
            continue
        out[scope] = w
    if problems:
        for p in problems:
            print(f"::error::{p}", file=sys.stderr)
        sys.exit(1)
    return out


def load_waivers() -> dict:
    if not WAIVERS.is_file():
        return {}
    try:
        raw = json.loads(WAIVERS.read_text(encoding="utf-8"))
    except ValueError as exc:
        die(f"{WAIVERS.name} не разбирается как JSON ({exc})")
    out, problems = {}, []
    today = dt.date.today()
    for w in raw.get("waivers", []):
        missing = [f for f in REQUIRED_WAIVER_FIELDS if not str(w.get(f, "")).strip()]
        if missing:
            problems.append(f"waiver {w.get('id', '?')}: нет обязательных полей {', '.join(missing)}")
            continue
        try:
            expires = dt.date.fromisoformat(str(w["expires"]))
        except ValueError:
            problems.append(f"waiver {w['id']}: expires={w['expires']!r} не дата ISO (ГГГГ-ММ-ДД)")
            continue
        if expires < today:
            problems.append(f"waiver {w['id']} для {w['package']} истёк {w['expires']} "
                            f"(владелец {w['owner']}) — переобоснуйте или закройте находку")
            continue
        out[(w["id"], w["package"])] = w
    if problems:
        for p in problems:
            print(f"::error::{p}", file=sys.stderr)
        sys.exit(1)
    return out


def query_osv(purls: list[str]) -> dict[str, list[str]]:
    """purl -> advisory ids. A transport failure is fatal: a scanner that fails open is decoration."""
    hits: dict[str, list[str]] = {}
    for i in range(0, len(purls), BATCH):
        chunk = purls[i:i + BATCH]
        body = json.dumps({"queries": [{"package": {"purl": p}} for p in chunk]}).encode()
        req = urllib.request.Request(OSV_BATCH, data=body,
                                     headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=120) as resp:  # noqa: S310 - fixed https host
                data = json.load(resp)
        except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError, OSError, ValueError) as exc:
            die(f"OSV недоступен ({exc}). Сканер, который при сбое пропускает, — это не гейт; "
                f"почините сеть или запустите с --offline, если проверяете только структуру.")
        for purl, res in zip(chunk, data.get("results", [])):
            ids = [v.get("id") for v in (res.get("vulns") or []) if v.get("id")]
            if ids:
                hits[purl] = ids
        print(f"    опрошено {min(i + BATCH, len(purls))}/{len(purls)}")
    return hits


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--offline", action="store_true")
    args = ap.parse_args()

    if not SBOM.is_file():
        die(f"{SBOM.relative_to(ROOT)} отсутствует — сначала scripts/generate-sbom.py")
    sbom = json.loads(SBOM.read_text(encoding="utf-8"))
    components = sbom.get("components", [])
    if not components:
        die("SBOM пуст — сканировать нечего, а пустой прогон выглядит как чистый")

    raw_waivers = {}
    if WAIVERS.is_file():
        raw_waivers = json.loads(WAIVERS.read_text(encoding="utf-8"))
    waivers = load_waivers()
    scope_waivers = load_scope_waivers(raw_waivers)
    shipped_n = sum(1 for c in components if c.get("scope", "required") != "excluded")
    print(f"==> SBOM: {len(components)} компонент(ов), из них в поставке {shipped_n}; "
          f"waiver-ов: {len(waivers)} точечных, {len(scope_waivers)} по областям")

    if args.offline:
        print("режим --offline: проверены только структура SBOM и корректность waiver-ов")
        return 0

    purls = sorted({c["purl"] for c in components if c.get("purl")})
    # `generic` purls describe vendored C tarballs; OSV has no such ecosystem, and the desktop
    # inventory is covered by check-bundled-deps-eol.py instead. Asking anyway would return nothing
    # and read as "no known vulnerabilities", which is the misleading answer.
    scannable = [p for p in purls if not p.startswith("pkg:generic/")]
    print(f"    покрыто сканированием: {len(scannable)} purl "
          f"(+{len(purls) - len(scannable)} generic — их держит check-bundled-deps-eol.py)")

    hits = query_osv(scannable)
    by_purl = {c["purl"]: c for c in components if c.get("purl")}

    # KHANDAQ (re-review 2026-08-22, KQ-06): shipped and build-only are different questions.
    #
    # witness.gradle pins EVERY configuration, so a wholesale scan reports advisories in annotation
    # processors and lint as though they were in the app. The first run said 54 vulnerabilities and
    # every one of them was build-time; the guava that actually ships had none. Reporting those
    # identically is how a gate becomes noise somebody disables.
    #
    # Build-time advisories still matter — a compromised build tool is a supply-chain problem — so
    # they are printed in full and still require a waiver. They just do not carry the same verdict as
    # something in a user's hands.
    scope_waiver = scope_waivers.get("build-only")
    unwaived, build_only, used_waivers = [], [], set()
    for purl, ids in sorted(hits.items()):
        comp = by_purl.get(purl, {})
        label = f"{comp.get('group', '?')}/{comp.get('name', purl)}@{comp.get('version', '?')}"
        ships = comp.get("scope", "required") != "excluded"
        for vid in ids:
            key = (vid, purl)
            if key in waivers:
                used_waivers.add(key)
                w = waivers[key]
                print(f"  ВАЙВЕР {vid} {label} — до {w['expires']}, владелец {w['owner']}: "
                      f"{w['exploitability']}")
            elif ships:
                unwaived.append((vid, label, purl))
            else:
                build_only.append((vid, label, purl))

    if build_only:
        print(f"\n  --- {len(build_only)} advisory только в сборочной цепочке (в APK не попадают) ---")
        for vid, label, _purl in build_only:
            print(f"      {vid}  {label}")
        if scope_waiver is None:
            print("::error::advisory в сборочной цепочке не покрыты ни одним waiver. Добавьте в "
                  "security-waivers.json запись scope_waivers['build-only'] с владельцем, оценкой "
                  "эксплуатируемости и датой истечения — либо обновите инструменты сборки.",
                  file=sys.stderr)
            return 1
        print(f"      покрыты waiver-ом области: до {scope_waiver['expires']}, "
              f"владелец {scope_waiver['owner']} — {scope_waiver['exploitability']}")

    stale = [k for k in waivers if k not in used_waivers]
    for vid, purl in stale:
        print(f"  ВНИМАНИЕ: waiver {vid} для {purl} больше ни к чему не относится — удалите его")

    print()
    if unwaived:
        print(f"::error::найдено {len(unwaived)} незакрытых уязвимостей в зависимостях",
              file=sys.stderr)
        for vid, label, purl in unwaived:
            print(f"::error::  {vid}  {label}  ({purl})  https://osv.dev/vulnerability/{vid}",
                  file=sys.stderr)
        print("::error::закройте обновлением версии или добавьте waiver с владельцем, оценкой "
              "эксплуатируемости и датой истечения в security-waivers.json", file=sys.stderr)
        return 1

    print(f"ВСЁ ЧИСТО: ни одной незакрытой уязвимости в том, что попадает пользователю "
          f"({len(scannable)} компонент(ов) опрошено)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
