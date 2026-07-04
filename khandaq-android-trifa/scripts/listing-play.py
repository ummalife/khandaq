#!/usr/bin/env python3
"""Push store listing (text + images) to Google Play via androidpublisher."""
import json, os, time, urllib.parse, urllib.request, urllib.error
import jwt

SA = os.path.expanduser("~/.config/googleplay/service-account.json")
PACKAGE = "com.khandaq.messenger"
ASMETA = "/Users/lucyok/Khandaq/khandaq-ios/scripts/appstore-metadata.json"
ASSETS = "/Users/lucyok/Khandaq/secrets/play-assets"
SCOPE = "https://www.googleapis.com/auth/androidpublisher"
TOKEN_URL = "https://oauth2.googleapis.com/token"
API = "https://androidpublisher.googleapis.com/androidpublisher/v3"
UP = "https://androidpublisher.googleapis.com/upload/androidpublisher/v3"

asm = json.load(open(ASMETA))["locales"]

def clip(s, n):
    return s if len(s) <= n else s[:n]

LISTINGS = {
    "en-US": {
        "title": "Khandaq — Secure Messenger",
        "shortDescription": clip("Encrypted Tox chat, calls & files. No phone number, no tracking, open source.", 80),
        "fullDescription": asm["en-US"]["description"],
    },
    "ru-RU": {
        "title": clip("Khandaq: защищённый мессенджер", 30),
        "shortDescription": clip("Зашифрованный чат на Tox: сообщения, звонки, файлы. Без номера телефона.", 80),
        "fullDescription": asm["ru"]["description"],
    },
}

SCREENSHOTS = ["screen-1-security.png", "screen-2-decentralized.png",
               "screen-3-encryption.png", "screen-4-control.png"]


def token():
    d = json.load(open(SA)); now = int(time.time())
    a = jwt.encode({"iss": d["client_email"], "scope": SCOPE, "aud": TOKEN_URL,
                    "iat": now, "exp": now + 3600}, d["private_key"], algorithm="RS256")
    body = urllib.parse.urlencode({"grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
                                   "assertion": a}).encode()
    with urllib.request.urlopen(urllib.request.Request(TOKEN_URL, data=body, method="POST"), timeout=30) as r:
        return json.load(r)["access_token"]


def call(tok, method, url, body=None, ctype="application/json"):
    data = None
    if body is not None:
        data = body if isinstance(body, bytes) else json.dumps(body).encode()
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", f"Bearer {tok}")
    if body is not None:
        req.add_header("Content-Type", ctype)
    with urllib.request.urlopen(req, timeout=300) as r:
        raw = r.read().decode()
        return json.loads(raw) if raw else {}


def upload_img(tok, eid, lang, kind, path):
    url = f"{UP}/applications/{PACKAGE}/edits/{eid}/listings/{lang}/{kind}?uploadType=media"
    with open(path, "rb") as f:
        return call(tok, "POST", url, f.read(), ctype="image/png")


def main():
    tok = token(); print("token OK")
    eid = call(tok, "POST", f"{API}/applications/{PACKAGE}/edits", {})["id"]
    print("edit:", eid)

    for lang, L in LISTINGS.items():
        call(tok, "PUT", f"{API}/applications/{PACKAGE}/edits/{eid}/listings/{lang}", {
            "language": lang, "title": L["title"],
            "shortDescription": L["shortDescription"], "fullDescription": L["fullDescription"],
        })
        print(f"listing {lang}: title={len(L['title'])} short={len(L['shortDescription'])} full={len(L['fullDescription'])}")

        # images (same assets for both locales)
        upload_img(tok, eid, lang, "icon", f"{ASSETS}/play-icon-512.png")
        upload_img(tok, eid, lang, "featureGraphic", f"{ASSETS}/feature-graphic-1024x500.png")
        call(tok, "DELETE", f"{API}/applications/{PACKAGE}/edits/{eid}/listings/{lang}/phoneScreenshots")
        for s in SCREENSHOTS:
            upload_img(tok, eid, lang, "phoneScreenshots", f"{ASSETS}/{s}")
        print(f"  images: icon + featureGraphic + {len(SCREENSHOTS)} screenshots")

    call(tok, "POST", f"{API}/applications/{PACKAGE}/edits/{eid}:commit")
    print("\n✅ committed — store listing (en-US + ru-RU) with images is live in Play Console.")


if __name__ == "__main__":
    try:
        main()
    except urllib.error.HTTPError as e:
        print(f"\nHTTP {e.code}:\n{e.read().decode()[:900]}")
