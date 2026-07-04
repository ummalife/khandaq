#!/usr/bin/env python3
"""Prepare Khandaq iOS App Store listing (metadata + screenshots + build) as a DRAFT.

Does NOT submit for review. Idempotent: safe to re-run.
Usage:
  python3 prepare-appstore.py --meta <metadata.json> --shots <dir> [--build 142905] [--dry-run]
"""
from __future__ import annotations
import argparse, hashlib, json, os, time, urllib.error, urllib.parse, urllib.request
import jwt

BUNDLE_ID = "org.khandaq.messenger"
CONFIG_PATH = os.path.expanduser("~/.appstoreconnect/config")
SHOT_DISPLAY_TYPE = "APP_IPHONE_65"  # 1242x2688
LOCALES = ("en-US", "ru", "ar-SA")
LIMITS = {"name": 30, "subtitle": 30, "keywords": 100, "promotionalText": 170,
          "description": 4000, "whatsNew": 4000}

DRY = False
INCLUDE_WHATS_NEW = False  # first release rejects whatsNew; enable for future updates


def load_config():
    cfg = {}
    with open(CONFIG_PATH) as h:
        for line in h:
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                cfg[k.strip()] = v.strip()
    return cfg


def make_token(cfg):
    with open(cfg["ASC_KEY_PATH"]) as h:
        key = h.read()
    return jwt.encode({"iss": cfg["ASC_ISSUER_ID"], "exp": int(time.time()) + 1200,
                       "aud": "appstoreconnect-v1"}, key, algorithm="ES256",
                      headers={"kid": cfg["ASC_KEY_ID"], "typ": "JWT"})


def api(cfg, method, path, body=None, ok_codes=(200, 201, 204)):
    url = f"https://api.appstoreconnect.apple.com{path}"
    payload = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(url, data=payload, method=method)
    req.add_header("Authorization", f"Bearer {make_token(cfg)}")
    if body is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=90) as r:
            raw = r.read().decode()
            return json.loads(raw) if raw else {}
    except urllib.error.HTTPError as e:
        detail = e.read().decode()
        raise SystemExit(f"HTTP {e.code} {method} {path}\n{detail[:600]}")


def put_binary(url, headers, data):
    req = urllib.request.Request(url, data=data, method="PUT")
    for h in headers:
        req.add_header(h["name"], h["value"])
    with urllib.request.urlopen(req, timeout=180) as r:
        return r.status


# ---------- metadata ----------

def validate(meta):
    errs = []
    if len(meta["name"]) > LIMITS["name"]:
        errs.append(f"name too long ({len(meta['name'])}>30)")
    for loc, d in meta["locales"].items():
        for field, limit in LIMITS.items():
            if field in d and len(d[field]) > limit:
                errs.append(f"{loc}.{field} too long ({len(d[field])}>{limit})")
    if errs:
        raise SystemExit("Validation failed:\n  " + "\n  ".join(errs))
    print("Validation OK (all fields within App Store limits)")


def resolve_app(cfg):
    apps = api(cfg, "GET", f"/v1/apps?filter[bundleId]={urllib.parse.quote(BUNDLE_ID)}&limit=1").get("data", [])
    if not apps:
        raise SystemExit("App not found in ASC")
    return apps[0]["id"]


def ios_version(cfg, app_id):
    vers = api(cfg, "GET", f"/v1/apps/{app_id}/appStoreVersions?limit=20").get("data", [])
    for v in vers:
        a = v["attributes"]
        if a.get("platform") == "IOS" and a.get("appStoreState") in (
                "PREPARE_FOR_SUBMISSION", "DEVELOPER_REJECTED", "REJECTED", "METADATA_REJECTED"):
            return v
    raise SystemExit("No editable iOS version found")


def set_version_string(cfg, ver, version_string):
    if ver["attributes"].get("versionString") == version_string:
        print(f"versionString already {version_string}")
        return
    print(f"versionString {ver['attributes'].get('versionString')} -> {version_string}")
    if DRY:
        return
    api(cfg, "PATCH", f"/v1/appStoreVersions/{ver['id']}",
        {"data": {"type": "appStoreVersions", "id": ver["id"],
                  "attributes": {"versionString": version_string}}})


def set_category(cfg, app_id, category):
    infos = api(cfg, "GET", f"/v1/apps/{app_id}/appInfos?limit=5").get("data", [])
    info = next((i for i in infos if i["attributes"].get("appStoreState") in
                 ("PREPARE_FOR_SUBMISSION", "DEVELOPER_REJECTED", "REJECTED", "METADATA_REJECTED")), infos[0])
    info_id = info["id"]
    cur = info.get("relationships", {}).get("primaryCategory", {}).get("data")
    if cur and cur.get("id") == category:
        print(f"primaryCategory already {category}")
        return info_id
    print(f"primaryCategory -> {category}")
    if not DRY:
        api(cfg, "PATCH", f"/v1/appInfos/{info_id}",
            {"data": {"type": "appInfos", "id": info_id,
                      "relationships": {"primaryCategory": {"data": {"type": "appCategories", "id": category}}}}})
    return info_id


def upsert_app_info_locale(cfg, info_id, meta):
    existing = {l["attributes"]["locale"]: l for l in
                api(cfg, "GET", f"/v1/appInfos/{info_id}/appInfoLocalizations?limit=50").get("data", [])}
    for loc in LOCALES:
        attrs = {"name": meta["name"], "subtitle": meta["locales"][loc].get("subtitle"),
                 "privacyPolicyUrl": meta.get("privacyPolicyUrl")}
        if loc in existing:
            lid = existing[loc]["id"]
            print(f"appInfoLoc[{loc}]: patch name/subtitle/privacy")
            if not DRY:
                api(cfg, "PATCH", f"/v1/appInfoLocalizations/{lid}",
                    {"data": {"type": "appInfoLocalizations", "id": lid, "attributes": attrs}})
        else:
            print(f"appInfoLoc[{loc}]: create")
            if not DRY:
                body = {"data": {"type": "appInfoLocalizations",
                                 "attributes": {**attrs, "locale": loc},
                                 "relationships": {"appInfo": {"data": {"type": "appInfos", "id": info_id}}}}}
                api(cfg, "POST", "/v1/appInfoLocalizations", body)


def upsert_version_locale(cfg, ver_id, meta):
    existing = {l["attributes"]["locale"]: l for l in
                api(cfg, "GET", f"/v1/appStoreVersions/{ver_id}/appStoreVersionLocalizations?limit=50").get("data", [])}
    result = {}
    for loc in LOCALES:
        d = meta["locales"][loc]
        attrs = {"description": d.get("description"), "keywords": d.get("keywords"),
                 "promotionalText": d.get("promotionalText"),
                 "supportUrl": meta.get("supportUrl"), "marketingUrl": meta.get("marketingUrl")}
        # whatsNew is rejected on a first release (no prior version); include only when allowed.
        if INCLUDE_WHATS_NEW and d.get("whatsNew"):
            attrs["whatsNew"] = d.get("whatsNew")
        if loc in existing:
            lid = existing[loc]["id"]
            print(f"versionLoc[{loc}]: patch description/keywords/promo/whatsNew/urls")
            if not DRY:
                api(cfg, "PATCH", f"/v1/appStoreVersionLocalizations/{lid}",
                    {"data": {"type": "appStoreVersionLocalizations", "id": lid, "attributes": attrs}})
            result[loc] = lid
        else:
            print(f"versionLoc[{loc}]: create")
            if not DRY:
                body = {"data": {"type": "appStoreVersionLocalizations",
                                 "attributes": {**attrs, "locale": loc},
                                 "relationships": {"appStoreVersion": {"data": {"type": "appStoreVersions", "id": ver_id}}}}}
                r = api(cfg, "POST", "/v1/appStoreVersionLocalizations", body)
                result[loc] = r["data"]["id"]
    return result


def upload_screenshots(cfg, version_loc_ids, shot_files):
    for loc, loc_id in version_loc_ids.items():
        # find or create screenshot set of the right display type
        sets = api(cfg, "GET", f"/v1/appStoreVersionLocalizations/{loc_id}/appScreenshotSets?limit=20").get("data", [])
        the_set = next((s for s in sets if s["attributes"].get("screenshotDisplayType") == SHOT_DISPLAY_TYPE), None)
        if the_set is None:
            print(f"screenshotSet[{loc}]: create {SHOT_DISPLAY_TYPE}")
            if DRY:
                continue
            the_set = api(cfg, "POST", "/v1/appScreenshotSets",
                          {"data": {"type": "appScreenshotSets",
                                    "attributes": {"screenshotDisplayType": SHOT_DISPLAY_TYPE},
                                    "relationships": {"appStoreVersionLocalization":
                                        {"data": {"type": "appStoreVersionLocalizations", "id": loc_id}}}}})["data"]
        set_id = the_set["id"]
        existing = api(cfg, "GET", f"/v1/appScreenshotSets/{set_id}/appScreenshots?limit=20").get("data", [])
        if len(existing) >= len(shot_files):
            print(f"screenshotSet[{loc}]: already has {len(existing)} shots, skip")
            continue
        for path in shot_files:
            data = open(path, "rb").read()
            name = os.path.basename(path)
            print(f"  upload[{loc}] {name} ({len(data)} bytes)")
            if DRY:
                continue
            # 1) reserve
            res = api(cfg, "POST", "/v1/appScreenshots",
                      {"data": {"type": "appScreenshots",
                                "attributes": {"fileSize": len(data), "fileName": name},
                                "relationships": {"appScreenshotSet":
                                    {"data": {"type": "appScreenshotSets", "id": set_id}}}}})["data"]
            shot_id = res["id"]
            ops = res["attributes"]["uploadOperations"]
            # 2) put chunks
            for op in ops:
                chunk = data[op["offset"]: op["offset"] + op["length"]]
                put_binary(op["url"], op["requestHeaders"], chunk)
            # 3) commit
            md5 = hashlib.md5(data).hexdigest()
            api(cfg, "PATCH", f"/v1/appScreenshots/{shot_id}",
                {"data": {"type": "appScreenshots", "id": shot_id,
                          "attributes": {"uploaded": True, "sourceFileChecksum": md5}}})


def attach_build(cfg, app_id, ver_id, build_number):
    builds = api(cfg, "GET", f"/v1/builds?filter[app]={app_id}&sort=-uploadedDate&limit=20").get("data", [])
    target = next((b for b in builds if b["attributes"].get("version") == str(build_number)), None)
    if not target:
        print(f"build {build_number} not found in ASC (skip attach)")
        return
    if target["attributes"].get("processingState") != "VALID":
        print(f"build {build_number} state={target['attributes'].get('processingState')} (not VALID yet, skip)")
        return
    print(f"attach build {build_number} ({target['id']}) to version")
    if not DRY:
        api(cfg, "PATCH", f"/v1/appStoreVersions/{ver_id}/relationships/build",
            {"data": {"type": "builds", "id": target["id"]}})


def main():
    global DRY
    ap = argparse.ArgumentParser()
    ap.add_argument("--meta", required=True)
    ap.add_argument("--shots", required=True)
    ap.add_argument("--build", default="142905")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--skip-shots", action="store_true")
    args = ap.parse_args()
    DRY = args.dry_run

    meta = json.load(open(args.meta))
    validate(meta)
    shot_files = sorted(os.path.join(args.shots, f) for f in os.listdir(args.shots) if f.endswith(".png"))
    print(f"{'DRY-RUN — ' if DRY else ''}{len(shot_files)} screenshots, locales {LOCALES}")

    cfg = load_config()
    app_id = resolve_app(cfg)
    ver = ios_version(cfg, app_id)
    ver_id = ver["id"]
    print(f"app_id={app_id} iosVersion={ver_id} ({ver['attributes'].get('versionString')})")

    set_version_string(cfg, ver, meta["versionString"])
    info_id = set_category(cfg, app_id, meta["primaryCategory"])
    upsert_app_info_locale(cfg, info_id, meta)
    loc_ids = upsert_version_locale(cfg, ver_id, meta)
    if not args.skip_shots:
        upload_screenshots(cfg, loc_ids, shot_files)
    attach_build(cfg, app_id, ver_id, args.build)
    print("\nDONE. Draft prepared. NOT submitted for review.")


if __name__ == "__main__":
    main()
