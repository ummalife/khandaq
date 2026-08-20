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

    def _load(enforce: str = "0", secret: str = SECRET, rate_limit: str = "10000",
              trusted_proxies: str = ""):
        monkeypatch.setenv("PUSH_RELAY_AUTH_SECRET", secret)
        monkeypatch.setenv("PUSH_AUTH_ENFORCE", enforce)
        monkeypatch.setenv("STATS_DB", str(tmp_path / "stats.db"))
        monkeypatch.setenv("PUSH_COALESCE_SECONDS", "0")  # coalescing is not what these tests probe
        monkeypatch.setenv("PUSH_RATE_LIMIT_PER_MIN", rate_limit)
        monkeypatch.setenv("PUSH_TRUSTED_PROXIES", trusted_proxies)
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


# -------------------------------------------------------------------------------- rate limiting
#
# KHANDAQ (audit 2026-08-20): these exist because the per-IP limiter had silently collapsed into a
# single global bucket in production and no test noticed — the suite raised the limit out of the way
# instead of exercising it. The deployment shape that matters is nginx on the host proxying into a
# bridge-networked container, so the peer address the relay sees is the bridge gateway, and every
# caller's identity lives in X-Real-IP.

BRIDGE_GATEWAY = "172.18.0.1"


def flood(client, times, real_ip=None, remote_addr=BRIDGE_GATEWAY):
    headers = {"X-Real-IP": real_ip} if real_ip else {}
    last = None
    for _ in range(times):
        last = client.get(f"/toxfcm/fcm.php?id={TOKEN}", headers=headers,
                          environ_base={"REMOTE_ADDR": remote_addr})
    return last


def test_one_flooding_client_does_not_rate_limit_everyone_else(relay):
    """The regression itself: without X-Real-IP being honoured, B is 429 because A flooded."""
    m = relay(rate_limit="5")
    client = m.app.test_client()

    assert flood(client, 5, real_ip="203.0.113.7").status_code == 200
    assert flood(client, 1, real_ip="203.0.113.7").status_code == 429, "the flooder must be limited"
    assert flood(client, 1, real_ip="198.51.100.9").status_code == 200, \
        "a different client must have its own bucket"


def test_x_real_ip_is_honoured_from_the_docker_bridge_gateway(relay):
    """
    The gateway is not loopback. Trusting loopback only is what broke this, so pin the shape.
    """
    m = relay(rate_limit="2")
    client = m.app.test_client()
    assert flood(client, 2, real_ip="203.0.113.7").status_code == 200
    assert flood(client, 1, real_ip="203.0.113.7").status_code == 429
    for other in ("198.51.100.1", "198.51.100.2", "198.51.100.3"):
        assert flood(client, 1, real_ip=other).status_code == 200


# NB: do NOT use the documentation ranges (192.0.2.0/24, 198.51.100.0/24, 203.0.113.0/24) as a
# stand-in for "a public address" here. Python's ipaddress module reports them as `is_private`,
# because they are in the IANA special-purpose registry, so a test written with them would pass for
# the wrong reason — the peer would be trusted and the assertion would never exercise the guard.
# These two are genuinely globally-routable.
PUBLIC_PEER = "8.8.8.8"
OTHER_PUBLIC_PEER = "1.1.1.1"


def test_x_real_ip_is_ignored_when_the_peer_is_not_a_trusted_hop(relay):
    """The other half: a client talking to the relay directly cannot spoof its way out of a bucket."""
    m = relay(rate_limit="3")
    client = m.app.test_client()

    # Same public peer each time, but a fresh forged X-Real-IP per request.
    for i in range(3):
        assert flood(client, 1, real_ip=f"10.0.0.{i}", remote_addr=PUBLIC_PEER).status_code == 200
    assert flood(client, 1, real_ip="10.0.0.99", remote_addr=PUBLIC_PEER).status_code == 429, \
        "a public peer must be limited on its own address, whatever X-Real-IP claims"
    assert flood(client, 1, real_ip="10.0.0.99", remote_addr=OTHER_PUBLIC_PEER).status_code == 200, \
        "and a different public peer is still its own bucket"


def test_an_explicitly_pinned_proxy_is_trusted(relay):
    m = relay(rate_limit="2", trusted_proxies=PUBLIC_PEER)
    client = m.app.test_client()
    assert flood(client, 2, real_ip="198.51.100.1", remote_addr=PUBLIC_PEER).status_code == 200
    assert flood(client, 1, real_ip="198.51.100.2", remote_addr=PUBLIC_PEER).status_code == 200, \
        "pinned proxy: each X-Real-IP gets its own bucket"


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
