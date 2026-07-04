#!/usr/bin/env python3
"""Google Play Developer API helper (service-account JWT -> OAuth2 -> REST)."""
import json, os, sys, time, urllib.parse, urllib.request, urllib.error
import jwt

SA_PATH = os.path.expanduser("~/.config/googleplay/service-account.json")
PACKAGE = "com.khandaq.messenger"
SCOPE = "https://www.googleapis.com/auth/androidpublisher"
TOKEN_URL = "https://oauth2.googleapis.com/token"
API = "https://androidpublisher.googleapis.com/androidpublisher/v3"


def access_token():
    d = json.load(open(SA_PATH))
    now = int(time.time())
    assertion = jwt.encode(
        {"iss": d["client_email"], "scope": SCOPE, "aud": TOKEN_URL,
         "iat": now, "exp": now + 3600},
        d["private_key"], algorithm="RS256")
    body = urllib.parse.urlencode({
        "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
        "assertion": assertion}).encode()
    req = urllib.request.Request(TOKEN_URL, data=body, method="POST")
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)["access_token"]


def api(token, method, path, body=None):
    url = f"{API}{path}"
    payload = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(url, data=payload, method=method)
    req.add_header("Authorization", f"Bearer {token}")
    if body is not None:
        req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req, timeout=60) as r:
        raw = r.read().decode()
        return json.loads(raw) if raw else {}


def main():
    print("getting access token...")
    try:
        token = access_token()
    except urllib.error.HTTPError as e:
        print("TOKEN FAILED:", e.code, e.read().decode()[:400]); sys.exit(1)
    print("token OK")

    print(f"opening edit for {PACKAGE}...")
    try:
        edit = api(token, "POST", f"/applications/{PACKAGE}/edits", {})
        print("EDIT OK:", edit.get("id"), "expires", edit.get("expiryTimeSeconds"))
        # list tracks to confirm read access
        tracks = api(token, "GET", f"/applications/{PACKAGE}/edits/{edit['id']}/tracks")
        names = [t.get("track") for t in tracks.get("tracks", [])]
        print("tracks:", names)
        # clean up the probe edit (do not commit)
        api(token, "DELETE", f"/applications/{PACKAGE}/edits/{edit['id']}")
        print("probe edit deleted (nothing committed)")
        print("\n✅ Play API access WORKS — ready to publish.")
    except urllib.error.HTTPError as e:
        detail = e.read().decode()
        print(f"\nHTTP {e.code}:\n{detail[:700]}")
        if e.code == 403 and "has not been used" in detail:
            print("\n>>> Enable 'Google Play Android Developer API' in the Cloud project.")
        elif e.code in (401, 403):
            print("\n>>> Grant the service account access in Play Console (Users & permissions / API access).")
        elif e.code == 404:
            print(f"\n>>> App {PACKAGE} not found for this account, or wrong package name.")


if __name__ == "__main__":
    main()
