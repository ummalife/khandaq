#!/usr/bin/env python3
"""Khandaq push wake relay — FCM HTTP v1, tox.zoff.xyz compatible API."""
from __future__ import annotations

import json
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
_rate: dict[str, list[float]] = defaultdict(list)
_rate_lock = Lock()
_token_cache: dict[str, object] = {"token": None, "exp": 0.0}


def _fcm_configured() -> bool:
    return bool(FCM_SERVICE_ACCOUNT_FILE and os.path.isfile(FCM_SERVICE_ACCOUNT_FILE)) or bool(FCM_SERVER_KEY)


def _rate_ok(client_ip: str) -> bool:
    now = time.time()
    with _rate_lock:
        window = [t for t in _rate[client_ip] if now - t < 60]
        if len(window) >= RATE_LIMIT_PER_MIN:
            return False
        window.append(now)
        _rate[client_ip] = window
    return True


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
        return False, f"auth failed: {exc}"

    url = f"https://fcm.googleapis.com/v1/projects/{FCM_PROJECT_ID}/messages:send"
    data_payload: dict[str, str] = {"wake": "1"}
    if sender_pubkey:
        data_payload["sender_pubkey"] = sender_pubkey
        data_payload["from"] = sender_pubkey

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
    resp = requests.post(
        url,
        headers={
            "Authorization": f"Bearer {access}",
            "Content-Type": "application/json",
        },
        json=payload,
        timeout=10,
    )
    if resp.status_code not in (200, 201):
        return False, f"FCM v1 HTTP {resp.status_code}: {resp.text[:200]}"
    return True, "ok"


def _send_fcm_legacy(token: str, sender_pubkey: str = "") -> tuple[bool, str]:
    if not FCM_SERVER_KEY:
        return False, "FCM_SERVER_KEY not configured"
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
    if resp.status_code != 200:
        return False, f"FCM legacy HTTP {resp.status_code}"
    body = resp.json()
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
    return jsonify({"status": "ok", "fcm_configured": _fcm_configured(), "fcm_mode": mode})


@app.route("/toxfcm/fcm.php", methods=["GET", "POST"])
def wake():
    client_ip = request.headers.get("X-Forwarded-For", request.remote_addr or "?").split(",")[0].strip()
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
        log.warning("wake fail: %s", detail)
        return jsonify({"error": detail}), 503 if "not configured" in detail else 502

    return jsonify({"success": 1}), 200


@app.route("/")
def root():
    return jsonify({
        "service": "khandaq-push-relay",
        "endpoints": ["/toxfcm/fcm.php?id=<fcm_token>&type=1"],
        "privacy": "wake-only, no message content",
    })


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=int(os.environ.get("PORT", "8080")))
