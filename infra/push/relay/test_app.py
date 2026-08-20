#!/usr/bin/env python3
"""
KHANDAQ — tests for the push wake relay.

Written with the audit's remediation of "push credentials in query parameters", which adds a JSON
endpoint beside the legacy one. Two things need holding down at once, and neither was covered by a
test before:

  * the NEW endpoint must actually be stricter — body instead of URL, header instead of ?auth=,
    and no way to fall back to the old habit;
  * the LEGACY endpoint must behave exactly as it did, because every client in the field speaks it
    and a silent change there is an outage, not a regression.

The FCM send is stubbed throughout: nothing here should reach Google, and what is under test is the
authentication/authorisation path, not the delivery.

Run:  python -m pytest infra/push/relay/test_app.py -q
"""
from __future__ import annotations

import hashlib
import hmac
import importlib
import os
import sys
import time

import pytest

SECRET = "test-secret-not-a-real-one"


@pytest.fixture()
def relay(tmp_path, monkeypatch):
    """A fresh app module per test, with its own env, its own SQLite file and no real FCM."""

    def _load(enforce: str = "0", secret: str = SECRET):
        monkeypatch.setenv("PUSH_RELAY_AUTH_SECRET", secret)
        monkeypatch.setenv("PUSH_AUTH_ENFORCE", enforce)
        monkeypatch.setenv("STATS_DB", str(tmp_path / "stats.db"))
        monkeypatch.setenv("PUSH_COALESCE_SECONDS", "0")  # coalescing is not what these tests probe
        monkeypatch.setenv("PUSH_RATE_LIMIT_PER_MIN", "10000")
        sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
        if "app" in sys.modules:
            del sys.modules["app"]
        module = importlib.import_module("app")
        # Stub the send: these tests are about who is allowed in, not about FCM.
        module.sent = []
        module._send_wake = lambda token, sender="": (module.sent.append((token, sender)), (True, "ok"))[1]
        module.app.config["TESTING"] = True
        return module

    return _load


def sign(token: str, sender: str, ts: int, secret: str = SECRET) -> str:
    msg = (token + "\n" + sender + "\n" + str(ts)).encode("utf-8")
    return hmac.new(secret.encode("utf-8"), msg, hashlib.sha256).hexdigest()


TOKEN = "fcm-registration-token-value"
SENDER = "A" * 64


# --------------------------------------------------------------------------- the new JSON endpoint


def test_json_wake_accepts_a_signed_request(relay):
    m = relay()
    ts = int(time.time())
    r = m.app.test_client().post(
        "/wake",
        json={"token": TOKEN, "sender": SENDER},
        headers={"Authorization": "Bearer " + sign(TOKEN, SENDER, ts), "X-Khandaq-Ts": str(ts)},
    )
    assert r.status_code == 200, r.get_data(as_text=True)
    assert m.sent == [(TOKEN, SENDER)]


def test_json_wake_carries_no_credentials_in_the_url(relay):
    """The point of the change: a valid call must be expressible with an empty query string."""
    m = relay()
    ts = int(time.time())
    client = m.app.test_client()
    r = client.post(
        "/wake",
        json={"token": TOKEN, "sender": ""},
        headers={"Authorization": "Bearer " + sign(TOKEN, "", ts), "X-Khandaq-Ts": str(ts)},
    )
    assert r.status_code == 200
    assert r.request.query_string == b""


def test_json_wake_refuses_credentials_in_the_query_string(relay):
    """?auth=/?ts= must not work here even when they are correct — that is the habit being removed."""
    m = relay(enforce="1")
    ts = int(time.time())
    r = m.app.test_client().post(
        f"/wake?auth={sign(TOKEN, SENDER, ts)}&ts={ts}",
        json={"token": TOKEN, "sender": SENDER},
    )
    assert r.status_code == 401
    assert m.sent == []


def test_json_wake_rejects_a_bad_signature(relay):
    m = relay(enforce="1")
    ts = int(time.time())
    r = m.app.test_client().post(
        "/wake",
        json={"token": TOKEN, "sender": SENDER},
        headers={"Authorization": "Bearer " + "0" * 64, "X-Khandaq-Ts": str(ts)},
    )
    assert r.status_code == 401
    assert m.sent == []


def test_json_wake_rejects_a_signature_over_different_values(relay):
    """The signature is request-bound: signing token A must not authorise waking token B."""
    m = relay(enforce="1")
    ts = int(time.time())
    r = m.app.test_client().post(
        "/wake",
        json={"token": "some-other-device-token", "sender": SENDER},
        headers={"Authorization": "Bearer " + sign(TOKEN, SENDER, ts), "X-Khandaq-Ts": str(ts)},
    )
    assert r.status_code == 401
    assert m.sent == []


def test_json_wake_rejects_a_stale_timestamp(relay):
    m = relay(enforce="1")
    ts = int(time.time()) - 4000  # far outside PUSH_AUTH_MAX_SKEW_SEC
    r = m.app.test_client().post(
        "/wake",
        json={"token": TOKEN, "sender": SENDER},
        headers={"Authorization": "Bearer " + sign(TOKEN, SENDER, ts), "X-Khandaq-Ts": str(ts)},
    )
    assert r.status_code == 401


def test_json_wake_rejects_a_replayed_signature(relay):
    """Single-use signatures, the property the shared usedsig table exists for."""
    m = relay(enforce="1")
    ts = int(time.time())
    headers = {"Authorization": "Bearer " + sign(TOKEN, SENDER, ts), "X-Khandaq-Ts": str(ts)}
    client = m.app.test_client()
    assert client.post("/wake", json={"token": TOKEN, "sender": SENDER}, headers=headers).status_code == 200
    assert client.post("/wake", json={"token": TOKEN, "sender": SENDER}, headers=headers).status_code == 401
    assert m.sent == [(TOKEN, SENDER)]


@pytest.mark.parametrize("body", ["not json at all", "[]", '"a string"', "null", "123"])
def test_json_wake_rejects_a_body_that_is_not_an_object(relay, body):
    m = relay()
    ts = int(time.time())
    r = m.app.test_client().post(
        "/wake",
        data=body,
        content_type="application/json",
        headers={"Authorization": "Bearer " + sign(TOKEN, SENDER, ts), "X-Khandaq-Ts": str(ts)},
    )
    assert r.status_code == 400


@pytest.mark.parametrize("token", [None, 42, {"a": 1}, ["x"]])
def test_json_wake_rejects_a_non_string_token(relay, token):
    """Coercing these with str() would build a token nobody signed and nothing can deliver to."""
    m = relay()
    ts = int(time.time())
    r = m.app.test_client().post(
        "/wake",
        json={"token": token},
        headers={"Authorization": "Bearer " + sign(str(token), "", ts), "X-Khandaq-Ts": str(ts)},
    )
    assert r.status_code == 400
    assert m.sent == []


def test_json_wake_rejects_a_short_token_after_auth(relay):
    m = relay()
    ts = int(time.time())
    r = m.app.test_client().post(
        "/wake",
        json={"token": "short"},
        headers={"Authorization": "Bearer " + sign("short", "", ts), "X-Khandaq-Ts": str(ts)},
    )
    assert r.status_code == 400


def test_json_wake_does_not_answer_get(relay):
    m = relay()
    assert m.app.test_client().get("/wake").status_code == 405


def test_a_malformed_sender_is_dropped_not_forwarded(relay):
    """Same normalisation as the legacy path: a bogus pubkey is blanked, never passed to FCM."""
    m = relay()
    ts = int(time.time())
    bad = "not-a-pubkey"
    r = m.app.test_client().post(
        "/wake",
        json={"token": TOKEN, "sender": bad},
        headers={"Authorization": "Bearer " + sign(TOKEN, bad, ts), "X-Khandaq-Ts": str(ts)},
    )
    assert r.status_code == 200
    assert m.sent == [(TOKEN, "")]


def test_an_fcm_error_never_carries_the_token_back(relay):
    """Token redaction has to hold on the error path too, not just in the nginx access log."""
    m = relay()
    m._send_wake = lambda token, sender="": (False, f"FCM v1 HTTP 400 []: bad token {token} rejected")
    ts = int(time.time())
    r = m.app.test_client().post(
        "/wake",
        json={"token": TOKEN},
        headers={"Authorization": "Bearer " + sign(TOKEN, "", ts), "X-Khandaq-Ts": str(ts)},
    )
    assert r.status_code == 502
    assert TOKEN not in r.get_data(as_text=True)
    assert "[REDACTED]" in r.get_data(as_text=True)


# ------------------------------------------------------------------ the legacy endpoint, unchanged


def test_legacy_endpoint_still_accepts_query_auth(relay):
    m = relay(enforce="1")
    ts = int(time.time())
    r = m.app.test_client().get(
        f"/toxfcm/fcm.php?id={TOKEN}&from={SENDER}&auth={sign(TOKEN, SENDER, ts)}&ts={ts}"
    )
    assert r.status_code == 200
    assert m.sent == [(TOKEN, SENDER)]


def test_legacy_endpoint_still_accepts_header_auth(relay):
    m = relay(enforce="1")
    ts = int(time.time())
    r = m.app.test_client().get(
        f"/toxfcm/fcm.php?id={TOKEN}&from={SENDER}",
        headers={"Authorization": "Bearer " + sign(TOKEN, SENDER, ts), "X-Khandaq-Ts": str(ts)},
    )
    assert r.status_code == 200


def test_legacy_unsigned_request_is_served_in_soft_mode(relay):
    """Soft mode is the current production state; breaking it would silence every shipped client."""
    m = relay(enforce="0")
    r = m.app.test_client().get(f"/toxfcm/fcm.php?id={TOKEN}")
    assert r.status_code == 200
    assert m.sent == [(TOKEN, "")]


def test_legacy_unsigned_request_is_refused_under_enforcement(relay):
    m = relay(enforce="1")
    r = m.app.test_client().get(f"/toxfcm/fcm.php?id={TOKEN}")
    assert r.status_code == 401
    assert m.sent == []


def test_an_empty_secret_fails_closed(relay):
    """A misconfigured relay must refuse, not run wide open — even in soft mode."""
    m = relay(enforce="0", secret="")
    assert m.app.test_client().get(f"/toxfcm/fcm.php?id={TOKEN}").status_code == 401
    assert m.app.test_client().post("/wake", json={"token": TOKEN}).status_code == 401


# ------------------------------------------------------------------------------------------ health


def test_health_reports_the_auth_mode(relay):
    m = relay(enforce="1")
    body = m.app.test_client().get("/health").get_json()
    assert body["auth_mode"] == "enforce"
    assert body["auth_required"] is True


def test_adoption_counters_separate_signed_from_unsigned(relay):
    m = relay(enforce="0")
    client = m.app.test_client()
    ts = int(time.time())
    client.post(
        "/wake",
        json={"token": TOKEN},
        headers={"Authorization": "Bearer " + sign(TOKEN, "", ts), "X-Khandaq-Ts": str(ts)},
    )
    client.get(f"/toxfcm/fcm.php?id={TOKEN}")
    adoption = m.app.test_client().get("/health").get_json()["auth_adoption"]
    assert adoption["window_signed"] == 1
    assert adoption["window_unsigned"] == 1
