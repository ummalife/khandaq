#!/usr/bin/env python3
"""Upload an AAB to Google Play and assign it to tracks.

Everything that changes per release is an argument, because the previous version of this
script hardcoded one AAB path, one track pair and one release's notes — so every release
either edited the script or silently shipped the wrong text.

  ./publish-play.py --aab path/to/app-release.aab \
      --name "10400 (0.2.30)" --notes-json notes.json \
      --track internal --track alpha --track production:0.2

A track may carry a rollout fraction (`production:0.2` = 20% staged). Without one the
release goes out completed, i.e. to everyone on that track.
"""
import argparse, json, os, sys, time, urllib.parse, urllib.request, urllib.error
import jwt

SA_PATH = os.path.expanduser("~/.config/googleplay/service-account.json")
PACKAGE = "com.khandaq.messenger"
SCOPE = "https://www.googleapis.com/auth/androidpublisher"
TOKEN_URL = "https://oauth2.googleapis.com/token"
API = "https://androidpublisher.googleapis.com/androidpublisher/v3"
UPLOAD = "https://androidpublisher.googleapis.com/upload/androidpublisher/v3"

# Play rejects longer notes with a generic error that does not name the field.
MAX_NOTE_CHARS = 500


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


def parse_track(spec):
    """'production:0.2' -> ('production', 0.2); 'alpha' -> ('alpha', None)."""
    if ":" not in spec:
        return spec, None
    name, frac = spec.split(":", 1)
    frac = float(frac)
    if not (0.0 < frac < 1.0):
        sys.exit(f"rollout fraction for '{name}' must be between 0 and 1 (got {frac})")
    return name, frac


def load_notes(path):
    notes = json.load(open(path))
    too_long = {k: len(v) for k, v in notes.items() if len(v) > MAX_NOTE_CHARS}
    if too_long:
        # Checked before the upload so a bad string costs nothing but a re-run.
        sys.exit(f"release notes over {MAX_NOTE_CHARS} chars: {too_long}")
    return [{"language": k, "text": v} for k, v in notes.items()]


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--aab", required=True)
    p.add_argument("--name", required=True, help='release name shown in the console, e.g. "10400 (0.2.30)"')
    p.add_argument("--notes-json", required=True, help='{"en-US": "...", "ru-RU": "..."}')
    p.add_argument("--track", action="append", required=True,
                   help="track, optionally NAME:FRACTION for a staged rollout; repeatable")
    args = p.parse_args()

    tracks = [parse_track(t) for t in args.track]
    notes = load_notes(args.notes_json)

    tok = token()
    print("token OK")

    eid = call(tok, "POST", f"{API}/applications/{PACKAGE}/edits", {})["id"]
    print("edit:", eid)

    print(f"uploading AAB ({os.path.getsize(args.aab)//1024//1024} MB)...")
    with open(args.aab, "rb") as f:
        aab_bytes = f.read()
    up = call(tok, "POST",
              f"{UPLOAD}/applications/{PACKAGE}/edits/{eid}/bundles?uploadType=media",
              aab_bytes, ctype="application/octet-stream")
    vc = up.get("versionCode")
    print("bundle uploaded, versionCode:", vc)

    for name, frac in tracks:
        release = {
            "name": args.name,
            "versionCodes": [str(vc)],
            "releaseNotes": notes,
        }
        if frac is None:
            release["status"] = "completed"
            print(f"assigning {vc} to '{name}' (completed)...")
        else:
            release["status"] = "inProgress"
            release["userFraction"] = frac
            print(f"assigning {vc} to '{name}' (staged {frac:.0%})...")
        call(tok, "PUT", f"{API}/applications/{PACKAGE}/edits/{eid}/tracks/{name}",
             {"track": name, "releases": [release]})

    call(tok, "POST", f"{API}/applications/{PACKAGE}/edits/{eid}:commit")
    summary = ", ".join(n if f is None else f"{n} @{f:.0%}" for n, f in tracks)
    print(f"\n✅ committed — build {vc} is live on: {summary}")


if __name__ == "__main__":
    try:
        main()
    except urllib.error.HTTPError as e:
        detail = e.read().decode()
        sys.exit(f"HTTP {e.code}: {detail}")
