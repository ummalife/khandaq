#!/usr/bin/env python3
"""Khandaq push wake relay — FCM HTTP v1, tox.zoff.xyz compatible API."""
from __future__ import annotations

import hashlib
import hmac
import ipaddress
import logging
import os
import time
from collections import defaultdict
from threading import Lock

import requests
from flask import Flask, jsonify, request

app = Flask(__name__)
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("khandaq-push")

FCM_PROJECT_ID = os.environ.get("FCM_PROJECT_ID", "khandaq-messenger")
FCM_SERVICE_ACCOUNT_FILE = os.environ.get("FCM_SERVICE_ACCOUNT_FILE", "")
FCM_SERVER_KEY = os.environ.get("FCM_SERVER_KEY", "")  # legacy fallback
RATE_LIMIT_PER_MIN = int(os.environ.get("PUSH_RATE_LIMIT_PER_MIN", "120"))
PUSH_RELAY_AUTH_SECRET = os.environ.get("PUSH_RELAY_AUTH_SECRET", "")
# Max allowed clock skew (seconds) for the request timestamp. Bounds the replay window.
AUTH_MAX_SKEW_SEC = int(os.environ.get("PUSH_AUTH_MAX_SKEW_SEC", "300"))
# Rollout gate. With a secret set but ENFORCE off (default), the relay runs in SOFT/MONITOR
# mode: it still serves unsigned/invalid requests (so already-installed clients that lack the
# secret keep working) but LOGS each one, so you can watch adoption. Flip to 1 (hard enforce,
# 401 on bad auth) only once the logs show field clients are signing. This avoids a hard cutover
# that would break every client that hasn't yet shipped + been updated with the secret.
PUSH_AUTH_ENFORCE = os.environ.get("PUSH_AUTH_ENFORCE", "0").strip().lower() in ("1", "true", "yes", "on")
# KHANDAQ (security NEW-4): only honour X-Real-IP when the direct connection comes from a trusted
# reverse proxy. The relay binds to 127.0.0.1 behind nginx (and reaches it via the Docker bridge), so
# by default we trust loopback + private/link-local source addresses; an externally-exposed port would
# see public client IPs, which are NOT trusted (so X-Real-IP can't be spoofed to dodge rate limiting).
# PUSH_TRUSTED_PROXIES (CSV of exact IPs) adds explicit entries on top.
TRUSTED_PROXIES = {
    p.strip() for p in os.environ.get("PUSH_TRUSTED_PROXIES", "").split(",") if p.strip()
}


def _is_trusted_proxy(addr: str) -> bool:
    if not addr:
        return False
    if addr in TRUSTED_PROXIES:
        return True
    try:
        ip = ipaddress.ip_address(addr)
    except ValueError:
        return False
    return ip.is_loopback or ip.is_private or ip.is_link_local
_rate: dict[str, list[float]] = defaultdict(list)
_rate_lock = Lock()
_token_cache: dict[str, object] = {"token": None, "exp": 0.0}


def _fcm_configured() -> bool:
    return bool(FCM_SERVICE_ACCOUNT_FILE and os.path.isfile(FCM_SERVICE_ACCOUNT_FILE)) or bool(FCM_SERVER_KEY)


def _client_ip() -> str:
    # KHANDAQ (security NEW-4): X-Real-IP is only trustworthy when it comes FROM the trusted reverse
    # proxy. Honour it only if the direct peer (remote_addr) is a trusted proxy; otherwise a client
    # talking to the relay directly (if the port is ever exposed) could spoof X-Real-IP to dodge the
    # per-IP rate limit. Trusted proxies default to loopback; override via PUSH_TRUSTED_PROXIES (CSV).
    remote = (request.remote_addr or "").strip()
    if _is_trusted_proxy(remote):
        real_ip = request.headers.get("X-Real-IP", "").strip()
        if real_ip:
            return real_ip.split(",")[0].strip()
    return remote or "?"


def _rate_ok(client_ip: str) -> bool:
    now = time.time()
    with _rate_lock:
        # Purge stale keys to prevent memory exhaustion (#6).
        stale = [k for k, ts in _rate.items() if not ts or now - ts[-1] > 120]
        for k in stale:
            del _rate[k]

        window = [t for t in _rate[client_ip] if now - t < 60]
        if len(window) >= RATE_LIMIT_PER_MIN:
            return False
        window.append(now)
        _rate[client_ip] = window
    return True


def _auth_ok() -> bool:
    # Auth stays OFF until a secret is provisioned (current production state).
    if not PUSH_RELAY_AUTH_SECRET:
        return True

    if _auth_signature_valid():
        return True

    # Secret set, but the request is unsigned/invalid.
    if PUSH_AUTH_ENFORCE:
        return False  # hard enforce -> caller returns 401

    # Soft/monitor mode: allow it through but record it, so adoption can be measured
    # before flipping PUSH_AUTH_ENFORCE=1. client_ip is best-effort here.
    log.warning("push auth SOFT: unsigned/invalid request allowed from %s (set PUSH_AUTH_ENFORCE=1 after client adoption)", _client_ip())
    return True


def _auth_signature_valid() -> bool:
    # KHANDAQ (security NEW-2): replay-resistant, request-bound auth.
    # The old scheme signed a CONSTANT (HMAC(secret, "khandaq-push-relay")) → the same value
    # forever, baked into every build, replayable without limit. Instead the sender signs the
    # actual request (recipient token `id` + sender `from` + a unix timestamp `ts`), and we
    # accept it only inside a small freshness window. The HMAC is per-request and time-bound, so
    # even if it leaks (e.g. nginx logs) it is useless after AUTH_MAX_SKEW_SEC.
    #   ts   = unix seconds (integer string)
    #   msg  = id + "\n" + from + "\n" + ts   (raw, URL-decoded values, UTF-8)
    #   auth = lowercase hex HMAC-SHA256(secret, msg)
    # `auth` may come via ?auth= or "Authorization: Bearer"; `ts` via ?ts= or X-Khandaq-Ts.
    supplied = request.args.get("auth", "").strip()
    if not supplied:
        auth_header = request.headers.get("Authorization", "")
        if auth_header.lower().startswith("bearer "):
            supplied = auth_header[7:].strip()

    ts = request.args.get("ts", "").strip() or request.headers.get("X-Khandaq-Ts", "").strip()
    if not supplied or not ts:
        return False

    try:
        ts_int = int(ts)
    except ValueError:
        return False
    if abs(time.time() - ts_int) > AUTH_MAX_SKEW_SEC:
        return False

    token = request.args.get("id", "")
    sender = request.args.get("from", "")
    msg = (token + "\n" + sender + "\n" + ts).encode("utf-8")
    expected = hmac.new(PUSH_RELAY_AUTH_SECRET.encode("utf-8"), msg, hashlib.sha256).hexdigest()
    return hmac.compare_digest(supplied, expected)


def _get_access_token() -> str:
    now = time.time()
    if _token_cache["token"] and now < float(_token_cache["exp"]) - 60:
        return str(_token_cache["token"])

    from google.auth.transport.requests import Request as GoogleAuthRequest
    from google.oauth2 import service_account

    creds = service_account.Credentials.from_service_account_file(
        FCM_SERVICE_ACCOUNT_FILE,
        scopes=["https://www.googleapis.com/auth/firebase.messaging"],
    )
    creds.refresh(GoogleAuthRequest())
    _token_cache["token"] = creds.token
    _token_cache["exp"] = now + 3300
    return creds.token


def _send_fcm_v1(token: str, sender_pubkey: str = "") -> tuple[bool, str]:
    if not FCM_SERVICE_ACCOUNT_FILE or not os.path.isfile(FCM_SERVICE_ACCOUNT_FILE):
        return False, "FCM_SERVICE_ACCOUNT_FILE not configured"
    try:
        access = _get_access_token()
    except Exception as exc:
        _token_cache["token"] = None
        _token_cache["exp"] = 0.0
        return False, f"auth failed: {exc}"

    url = f"https://fcm.googleapis.com/v1/projects/{FCM_PROJECT_ID}/messages:send"
    # NB: "from" is a reserved FCM data key (HTTP 400 INVALID_ARGUMENT) — only sender_pubkey.
    data_payload: dict[str, str] = {"wake": "1"}
    if sender_pubkey:
        data_payload["sender_pubkey"] = sender_pubkey

    payload = {
        "message": {
            "token": token,
            "data": data_payload,
            "notification": {
                "title": os.environ.get("PUSH_NOTIFY_TITLE", "Khandaq"),
                "body": os.environ.get("PUSH_NOTIFY_BODY", "New message"),
            },
            "android": {
                "priority": "HIGH",
                "notification": {"channel_id": "khandaq_fcm_wake"},
            },
            "apns": {
                "headers": {"apns-priority": "10"},
                "payload": {"aps": {"content-available": 1}},
            },
        }
    }
    for attempt in (1, 2):
        try:
            resp = requests.post(
                url,
                headers={
                    "Authorization": f"Bearer {access}",
                    "Content-Type": "application/json",
                },
                json=payload,
                timeout=10,
            )
        except requests.RequestException as exc:
            return False, f"FCM v1 request failed: {exc}"

        # Cached token can outlive its real validity (google revokes early, worker slept, …):
        # on 401 drop the cache and retry ONCE with a freshly-minted token.
        if resp.status_code == 401 and attempt == 1:
            _token_cache["token"] = None
            _token_cache["exp"] = 0.0
            try:
                access = _get_access_token()
            except Exception as exc:
                return False, f"auth failed: {exc}"
            continue

        if resp.status_code not in (200, 201):
            return False, f"FCM v1 HTTP {resp.status_code}: {resp.text[:200]}"
        return True, "ok"
    return False, "FCM v1: unreachable"


def _send_fcm_legacy(token: str, sender_pubkey: str = "") -> tuple[bool, str]:
    if not FCM_SERVER_KEY:
        return False, "FCM_SERVER_KEY not configured"
    try:
        resp = requests.post(
            "https://fcm.googleapis.com/fcm/send",
            headers={
                "Authorization": f"key={FCM_SERVER_KEY}",
                "Content-Type": "application/json",
            },
            json={
                "to": token,
                "priority": "high",
                "data": {
                    "wake": "1",
                    **({"sender_pubkey": sender_pubkey, "from": sender_pubkey} if sender_pubkey else {}),
                },
                "notification": {
                    "title": os.environ.get("PUSH_NOTIFY_TITLE", "Khandaq"),
                    "body": os.environ.get("PUSH_NOTIFY_BODY", "New message"),
                },
            },
            timeout=10,
        )
    except requests.RequestException as exc:
        return False, f"FCM legacy request failed: {exc}"

    if resp.status_code != 200:
        return False, f"FCM legacy HTTP {resp.status_code}"
    try:
        body = resp.json()
    except ValueError:
        return False, "FCM legacy invalid JSON response"
    if body.get("failure", 0) > 0:
        return False, f"FCM failure: {body.get('results', body)}"
    return True, "ok"


def _send_wake(token: str, sender_pubkey: str = "") -> tuple[bool, str]:
    if FCM_SERVICE_ACCOUNT_FILE and os.path.isfile(FCM_SERVICE_ACCOUNT_FILE):
        return _send_fcm_v1(token, sender_pubkey)
    return _send_fcm_legacy(token, sender_pubkey)


@app.route("/health")
def health():
    mode = "v1" if FCM_SERVICE_ACCOUNT_FILE and os.path.isfile(FCM_SERVICE_ACCOUNT_FILE) else (
        "legacy" if FCM_SERVER_KEY else "none"
    )
    return jsonify({
        "status": "ok",
        "fcm_configured": _fcm_configured(),
        "fcm_mode": mode,
        "auth_required": bool(PUSH_RELAY_AUTH_SECRET),
        "auth_mode": (
            "off" if not PUSH_RELAY_AUTH_SECRET else ("enforce" if PUSH_AUTH_ENFORCE else "soft")
        ),
    })


@app.route("/toxfcm/fcm.php", methods=["GET", "POST"])
def wake():
    if not _auth_ok():
        return jsonify({"error": "unauthorized"}), 401

    client_ip = _client_ip()
    if not _rate_ok(client_ip):
        return jsonify({"error": "rate limit"}), 429

    token = request.args.get("id", "").strip()
    if not token or len(token) < 10 or len(token) > 4096:
        return jsonify({"error": "invalid token"}), 400

    sender_pubkey = request.args.get("from", "").strip().upper()
    if sender_pubkey and (len(sender_pubkey) != 64 or not all(c in "0123456789ABCDEF" for c in sender_pubkey)):
        sender_pubkey = ""

    ok, detail = _send_wake(token, sender_pubkey)
    if not ok:
        log.warning("wake fail from %s: %s", client_ip, detail)
        return jsonify({"error": detail}), 503 if "not configured" in detail else 502

    return jsonify({"success": 1}), 200


@app.route("/")
def root():
    return jsonify({
        "service": "khandaq-push-relay",
        "endpoints": ["/toxfcm/fcm.php?id=<fcm_token>&type=1"],
        "privacy": "wake-only, no message content; optional sender public key via &from=",
    })


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=int(os.environ.get("PORT", "8080")))
