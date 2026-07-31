#!/usr/bin/env python3
"""Upload an AAB to Google Play internal track via androidpublisher (draft)."""
import json, os, sys, time, urllib.parse, urllib.request, urllib.error
import jwt

SA_PATH = os.path.expanduser("~/.config/googleplay/service-account.json")
PACKAGE = "com.khandaq.messenger"
AAB = "/Users/lucyok/Khandaq/secrets/khandaq-com-0.2.12-10368.aab"
SCOPE = "https://www.googleapis.com/auth/androidpublisher"
TOKEN_URL = "https://oauth2.googleapis.com/token"
API = "https://androidpublisher.googleapis.com/androidpublisher/v3"
UPLOAD = "https://androidpublisher.googleapis.com/upload/androidpublisher/v3"
TRACKS = ("internal", "alpha")


def token():
    d = json.load(open(SA_PATH))
    now = int(time.time())
    a = jwt.encode({"iss": d["client_email"], "scope": SCOPE, "aud": TOKEN_URL,
                    "iat": now, "exp": now + 3600}, d["private_key"], algorithm="RS256")
    body = urllib.parse.urlencode({
        "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer", "assertion": a}).encode()
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
    with urllib.request.urlopen(req, timeout=600) as r:
        raw = r.read().decode()
        return json.loads(raw) if raw else {}


def main():
    tok = token()
    print("token OK")

    edit = call(tok, "POST", f"{API}/applications/{PACKAGE}/edits", {})
    eid = edit["id"]
    print("edit:", eid)

    print(f"uploading AAB ({os.path.getsize(AAB)//1024//1024} MB)...")
    with open(AAB, "rb") as f:
        aab_bytes = f.read()
    up = call(tok, "POST",
              f"{UPLOAD}/applications/{PACKAGE}/edits/{eid}/bundles?uploadType=media",
              aab_bytes, ctype="application/octet-stream")
    vc = up.get("versionCode")
    print("bundle uploaded, versionCode:", vc)

    for track in TRACKS:
        print(f"assigning versionCode {vc} to '{track}' (completed)...")
        call(tok, "PUT", f"{API}/applications/{PACKAGE}/edits/{eid}/tracks/{track}", {
            "track": track,
            "releases": [{
                "name": f"{vc} (0.2.12)",
                "versionCodes": [str(vc)],
                "status": "completed",
                "releaseNotes": [
                    {"language": "en-US", "text": "Redesigned, cleaner call screen. The incoming-call ringtone now plays as a proper ringtone (no more overlapping media melody) and you can Accept a call straight from the notification. Fixed the chat header for Arabic (right-to-left) names so the online status is no longer clipped."},
                    {"language": "ru-RU", "text": "Обновлённый, чистый экран звонка. Рингтон входящего звонка теперь звучит как настоящий рингтон (без наложения нескольких мелодий), а принять звонок можно прямо из уведомления. Исправлена шапка чата для арабских (справа-налево) имён — статус «в сети» больше не обрезается."},
                ],
            }],
        })

    call(tok, "POST", f"{API}/applications/{PACKAGE}/edits/{eid}:commit")
    print(f"\n✅ committed — build {vc} is LIVE (completed) on tracks: {', '.join(TRACKS)}.")


if __name__ == "__main__":
    try:
        main()
    except urllib.error.HTTPError as e:
        detail = e.read().decode()
        print(f"\nHTTP {e.code}:\n{detail[:900]}")
