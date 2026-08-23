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
              trusted_proxies: str = "", secrets: str = "", coalesce: str = "0",
              cap_grace: str = "0", cap_enforce: str = "auto"):
        monkeypatch.setenv("PUSH_RELAY_AUTH_SECRET", secret)
        # KHANDAQ (audit 2026-08-21, K-03): overlapping key epochs, "epoch:secret,epoch:secret".
        monkeypatch.setenv("PUSH_RELAY_AUTH_SECRETS", secrets)
        monkeypatch.setenv("PUSH_AUTH_ENFORCE", enforce)
        monkeypatch.setenv("STATS_DB", str(tmp_path / "stats.db"))
        # KHANDAQ (audit round 3, F-14): coalescing is off by default here because it is not what most
        # of these tests probe — but it was NOT overridable, so the suppression branch had no coverage
        # at all while running with a 45-second window in production. Now a test can ask for it.
        monkeypatch.setenv("PUSH_COALESCE_SECONDS", coalesce)
        monkeypatch.setenv("PUSH_RATE_LIMIT_PER_MIN", rate_limit)
        monkeypatch.setenv("PUSH_TRUSTED_PROXIES", trusted_proxies)
        # KHANDAQ (re-audit 2026-08-22, K-01): the post-registration grace window is OFF by default
        # here. In production it is 14 days and it is the right default — it stops a contact who was
        # offline during the re-publish from losing notifications. In a test it would mask every
        # enforcement assertion, so a test that wants to observe enforcement asks for grace=0, and
        # the one test that is ABOUT the grace window asks for it explicitly.
        monkeypatch.setenv("PUSH_CAP_GRACE_DAYS", cap_grace)
        monkeypatch.setenv("PUSH_CAP_ENFORCE", cap_enforce)
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


def test_an_fcm_error_tells_the_caller_nothing_about_upstream(relay, caplog):
    """
    KHANDAQ (audit round 3, F-11). This test used to assert the opposite: that the upstream text came
    back with the token replaced by [REDACTED]. Redaction was the wrong control. `detail` carries up
    to 200 bytes of FCM's own response body or str(exc) from google-auth, this path is reachable
    without authentication in soft mode, and when the service account existed but could not be read a
    stranger was answered with `auth failed: [Errno 13] Permission denied: '/run/secrets/...'` — an
    internal path and the exact failure mode, for free.

    So the property is now the stronger one: NOTHING from upstream crosses the response boundary.
    The operator still gets the detail, in the log, with the token still redacted there.
    """
    m = relay()
    m._send_wake = lambda token, sender="": (
        False, f"FCM v1 HTTP 400 [INVALID_ARGUMENT]: bad token {token} rejected at /run/secrets/x")
    ts = int(time.time())
    with caplog.at_level("WARNING"):
        r = m.app.test_client().post(
            "/wake",
            json={"token": TOKEN},
            headers={"Authorization": "Bearer " + sign(TOKEN, "", ts), "X-Khandaq-Ts": str(ts)},
        )
    body = r.get_data(as_text=True)
    assert r.status_code == 502
    assert body.strip() == '{"error":"upstream send failed"}'
    for leak in (TOKEN, "INVALID_ARGUMENT", "/run/secrets", "FCM"):
        assert leak not in body, f"{leak!r} reached the caller"

    logged = " | ".join(rec.getMessage() for rec in caplog.records)
    assert "INVALID_ARGUMENT" in logged, "the operator lost the detail this fix relies on them having"
    assert TOKEN not in logged, "the token must still be redacted in the log"
    assert "[REDACTED]" in logged


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
    body = m.app.test_client().get("/health/detail").get_json()
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
    adoption = m.app.test_client().get("/health/detail").get_json()["auth_adoption"]
    assert adoption["window_signed"] == 1
    assert adoption["window_unsigned"] == 1


# ------------------------------------------------- the rate window is shared, not per worker
#
# KHANDAQ (audit 2026-08-21, K-09): the window used to be a module-level dict, so `gunicorn -w 2`
# meant two of them. Measured before the fix, on four processes sharing one DB with a limit of 50:
# 200 requests accepted. After: exactly 50. Loading the module twice against one STATS_DB is the
# in-test stand-in for that — two independent module objects, one file.


def test_the_rate_window_is_shared_across_workers(relay):
    worker_a = relay(rate_limit="5")
    worker_b = relay(rate_limit="5")          # same tmp_path => same STATS_DB
    client_a, client_b = worker_a.app.test_client(), worker_b.app.test_client()

    assert flood(client_a, 3, real_ip="203.0.113.7").status_code == 200
    assert flood(client_b, 2, real_ip="203.0.113.7").status_code == 200, \
        "the second worker must see the first worker's three"
    assert flood(client_b, 1, real_ip="203.0.113.7").status_code == 429
    assert flood(client_a, 1, real_ip="203.0.113.7").status_code == 429, \
        "and the first must see the budget the second spent"
    assert flood(client_a, 1, real_ip="198.51.100.9").status_code == 200, \
        "a different client still has its own bucket"


def test_rate_limiting_fails_open_when_the_store_is_unavailable(relay, monkeypatch):
    """
    Deliberately the opposite of _claim_signature, which fails CLOSED.

    A dead rate store failing closed makes every wake a 429, and the Android client escalates a 429
    into a per-push-URL backoff toward two hours — so an unwritable /data would become a fleet-wide
    notification outage outliving the disk problem. nginx's stricter limit is still in front.
    """
    m = relay(rate_limit="1")
    client = m.app.test_client()
    assert flood(client, 1, real_ip="203.0.113.7").status_code == 200
    assert flood(client, 1, real_ip="203.0.113.7").status_code == 429

    def boom(*_a, **_kw):
        raise OSError("read-only file system")

    monkeypatch.setattr(m, "_stats_conn", boom)
    assert flood(client, 1, real_ip="203.0.113.7").status_code == 200, \
        "a broken store must not turn every wake into a 429"


def test_the_rate_table_does_not_grow_without_bound(relay):
    import sqlite3 as _sqlite3

    m = relay(rate_limit="100")
    client = m.app.test_client()
    for i in range(50):
        flood(client, 1, real_ip=f"203.0.113.{i}")

    conn = _sqlite3.connect(m.COALESCE_DB)
    assert conn.execute("SELECT COUNT(*) FROM ratelimit").fetchone()[0] == 50
    conn.execute("UPDATE ratelimit SET last = ?", (time.time() - 300,))
    conn.commit()
    conn.close()

    flood(client, 1, real_ip="198.51.100.9")   # one request prunes everything older than two windows
    conn = _sqlite3.connect(m.COALESCE_DB)
    assert conn.execute("SELECT COUNT(*) FROM ratelimit").fetchone()[0] == 1
    conn.close()


def test_a_rate_limited_request_does_not_burn_its_signature(relay):
    """
    KHANDAQ (K-09): auth used to run first, and validating a signature CONSUMES it. So a signed
    request that was then refused for rate had already spent its single-use signature, and the
    client's natural retry with the same one read as a replay — a 401 for a caller who did nothing
    wrong. Rate-limiting first makes the 429 free.
    """
    m = relay(enforce="1", rate_limit="1")
    client = m.app.test_client()
    ts = int(time.time())
    headers = {"Authorization": "Bearer " + sign(TOKEN, "", ts), "X-Khandaq-Ts": str(ts)}

    # Burn the single request this IP is allowed, with an unsigned call.
    assert flood(client, 1, real_ip="203.0.113.7").status_code == 401  # unsigned, enforcing
    # The signed request is now rate limited...
    assert client.post("/wake", json={"token": TOKEN}, headers=headers,
                       environ_base={"REMOTE_ADDR": BRIDGE_GATEWAY,
                                     "HTTP_X_REAL_IP": "203.0.113.7"}).status_code == 429
    # ...and the SAME signature still works from an IP with budget left, i.e. it was not consumed.
    assert client.post("/wake", json={"token": TOKEN}, headers=headers,
                       environ_base={"REMOTE_ADDR": BRIDGE_GATEWAY,
                                     "HTTP_X_REAL_IP": "198.51.100.9"}).status_code == 200


# ------------------------------------------------- per-outcome auth telemetry (K-02)
#
# KHANDAQ (audit 2026-08-21, K-02): the enforcement decision is "will PUSH_AUTH_ENFORCE=1 silence
# real users", and a single unsigned counter cannot answer it. `missing` (an old client, heals when
# the fleet turns over) and `badmac` (a build carrying the WRONG secret, never heals) looked
# identical, so an operator waiting for the percentage to reach 100 could wait forever with no way
# to see why. `store_error` is the relay's own disk and is not a client problem at all.


def _outcomes(module):
    return module.app.test_client().get("/health/detail").get_json()["auth_adoption"]["window_outcomes"]


def test_an_unsigned_request_counts_as_missing(relay):
    m = relay()
    m.app.test_client().get(f"/toxfcm/fcm.php?id={TOKEN}")
    assert _outcomes(m)["missing"] == 1


def test_a_wrong_secret_counts_as_badmac_not_as_an_old_client(relay):
    """The case the two-column counter could not express, and the reason for this whole change."""
    m = relay()
    ts = int(time.time())
    m.app.test_client().post(
        "/wake", json={"token": TOKEN},
        headers={"Authorization": "Bearer " + sign(TOKEN, "", ts, secret="a-different-build-secret"),
                 "X-Khandaq-Ts": str(ts)})
    counts = _outcomes(m)
    assert counts["badmac"] == 1
    assert counts["missing"] == 0, "a wrong secret must not read as a client that does not sign yet"


def test_a_stale_timestamp_counts_as_stale(relay):
    m = relay()
    ts = int(time.time()) - 10_000
    m.app.test_client().post(
        "/wake", json={"token": TOKEN},
        headers={"Authorization": "Bearer " + sign(TOKEN, "", ts), "X-Khandaq-Ts": str(ts)})
    assert _outcomes(m)["stale"] == 1


def test_a_non_integer_timestamp_counts_as_malformed(relay):
    m = relay()
    m.app.test_client().post(
        "/wake", json={"token": TOKEN},
        headers={"Authorization": "Bearer " + "0" * 64, "X-Khandaq-Ts": "not-a-number"})
    assert _outcomes(m)["malformed_ts"] == 1


def test_a_replayed_signature_counts_as_replay_not_as_unsigned(relay):
    m = relay()
    ts = int(time.time())
    headers = {"Authorization": "Bearer " + sign(TOKEN, "", ts), "X-Khandaq-Ts": str(ts)}
    m.app.test_client().post("/wake", json={"token": TOKEN}, headers=headers)
    m.app.test_client().post("/wake", json={"token": TOKEN}, headers=headers)
    counts = _outcomes(m)
    assert counts["ok"] == 1 and counts["replay"] == 1


def test_a_broken_replay_store_is_not_counted_as_an_unsigned_client(relay, monkeypatch):
    """
    A disk problem used to depress the adoption percentage, i.e. read as clients that do not sign.

    The counter itself lives in the same store, so it cannot be written while that store is broken —
    what is asserted here is the classification: once the store is back, the failed request is
    `store_error` and never `missing`, and the ex-store-error percentage ignores it.
    """
    m = relay()
    ts = int(time.time())
    real_conn = m._stats_conn
    calls = {"n": 0}

    def flaky(*a, **kw):
        calls["n"] += 1
        # Fail only the replay-store claim (the third connection in this request: rate, then
        # outcome-record happens after, so the claim is the second), then behave normally again.
        if calls["n"] == 2:
            raise OSError("read-only file system")
        return real_conn(*a, **kw)

    monkeypatch.setattr(m, "_stats_conn", flaky)
    m.app.test_client().post(
        "/wake", json={"token": TOKEN},
        headers={"Authorization": "Bearer " + sign(TOKEN, "", ts), "X-Khandaq-Ts": str(ts)})
    monkeypatch.setattr(m, "_stats_conn", real_conn)

    counts = _outcomes(m)
    assert counts["store_error"] == 1
    assert counts["missing"] == 0 and counts["badmac"] == 0


def test_health_does_not_call_an_unconfigured_relay_auth_off(relay):
    """
    KHANDAQ (K-02): with no secret, _auth_ok 401s everything — so "off" was a lie, and a lie in the
    dangerous direction: it reads as "serving everyone unauthenticated" while the relay is refusing
    every request. An operator chasing a total push outage was sent to the wrong place.
    """
    m = relay(secret="")
    body = m.app.test_client().get("/health/detail").get_json()
    assert body["auth_mode"] == "misconfigured"
    assert body["auth_required"] is True


def test_health_reports_an_overdue_soft_mode(relay, monkeypatch):
    monkeypatch.setenv("PUSH_AUTH_ENFORCE_BY", "2020-01-01")
    m = relay(enforce="0")
    body = m.app.test_client().get("/health/detail").get_json()
    assert body["enforce_by"] == "2020-01-01"
    assert body["enforce_overdue"] is True


def test_an_enforcing_relay_is_never_overdue(relay, monkeypatch):
    monkeypatch.setenv("PUSH_AUTH_ENFORCE_BY", "2020-01-01")
    m = relay(enforce="1")
    assert m.app.test_client().get("/health/detail").get_json()["enforce_overdue"] is False


def test_a_future_cutoff_is_not_overdue(relay, monkeypatch):
    monkeypatch.setenv("PUSH_AUTH_ENFORCE_BY", "2099-01-01")
    m = relay(enforce="0")
    assert m.app.test_client().get("/health/detail").get_json()["enforce_overdue"] is False


def test_a_malformed_cutoff_is_ignored_rather_than_crashing(relay, monkeypatch):
    monkeypatch.setenv("PUSH_AUTH_ENFORCE_BY", "next tuesday")
    m = relay(enforce="0")
    body = m.app.test_client().get("/health/detail").get_json()
    assert body["enforce_overdue"] is False
    assert body["status"] == "ok"


def test_the_fcm_token_never_reaches_the_log(relay, caplog):
    """
    KHANDAQ (audit 2026-08-21, §13 residual): the success line logged token[:12]. A registration
    token is a targeting secret; a 12-character prefix is a partial disclosure of a credential into
    a log that outlives the request. It is a hash now.
    """
    import re

    m = relay()
    with caplog.at_level("INFO"):
        m.app.test_client().get(f"/toxfcm/fcm.php?id={TOKEN}")
    logged = "\n".join(r.getMessage() for r in caplog.records)
    assert TOKEN[:12] not in logged, "no prefix of the token may appear in the log"

    # The success line itself lives inside the FCM send, which these tests stub out, so it cannot be
    # reached without talking to Google. Pin the class of defect at the source instead: no log call
    # may slice the token. `_th(token)` — the same 128-bit hash that already keys the coalesce table
    # — keeps one device's wakes correlatable in the log while disclosing nothing.
    with open(m.__file__, encoding="utf-8") as fh:
        src = fh.read()
    assert not re.search(r"log\.\w+\([^)]*\btoken\[:", src), \
        "a log call is slicing the FCM registration token"
    assert "_th(token)" in src


# ------------------------------------------------- overlapping key epochs (K-03)
#
# KHANDAQ (audit 2026-08-21, K-03): the push HMAC secret is baked into public binaries and is
# therefore extractable. Nothing below changes that — per-install capabilities are the real answer
# and are a protocol project (DESIGN-push-per-install-capabilities.md). What these tests hold down is
# the operational trap on top of it: with exactly one secret, rotating meant every shipped client
# stopped verifying at once, so the documented procedure was to drop back to SOFT mode — accepting
# unsigned requests from anyone — for the length of a store rollout. So a leaked key was most
# expensive to replace exactly when replacing it mattered.

EPOCH_A = "epoch-a-secret-value"
EPOCH_B = "epoch-b-secret-value"


def _signed(client, secret, endpoint="/wake", age=0):
    # `age` shifts the timestamp by a second or two so repeated calls in one test produce DIFFERENT
    # signatures. Without it the second call within the same second is a genuine replay and is
    # refused — correct behaviour, but not what these tests are probing.
    ts = int(time.time()) - age
    return client.post(endpoint, json={"token": TOKEN},
                       headers={"Authorization": "Bearer " + sign(TOKEN, "", ts, secret=secret),
                                "X-Khandaq-Ts": str(ts)})


def test_either_configured_epoch_is_accepted(relay):
    m = relay(enforce="1", secret="", secrets=f"2:{EPOCH_A},3:{EPOCH_B}")
    assert _signed(m.app.test_client(), EPOCH_A).status_code == 200
    assert _signed(m.app.test_client(), EPOCH_B).status_code == 200


def test_the_legacy_single_secret_still_works_beside_an_epoch_list(relay):
    """The backward-compatibility case that matters: an existing deployment changes nothing."""
    m = relay(enforce="1", secret=SECRET, secrets=f"2:{EPOCH_A}")
    assert _signed(m.app.test_client(), SECRET).status_code == 200
    assert _signed(m.app.test_client(), EPOCH_A).status_code == 200


def test_an_unconfigured_secret_is_still_refused(relay):
    m = relay(enforce="1", secret="", secrets=f"2:{EPOCH_A}")
    assert _signed(m.app.test_client(), "some-other-build-secret").status_code == 401


def test_a_retired_epoch_stops_being_accepted(relay):
    """The property rotation depends on: removing the entry actually removes the key."""
    before = relay(enforce="1", secret="", secrets=f"2:{EPOCH_A},3:{EPOCH_B}")
    assert _signed(before.app.test_client(), EPOCH_A).status_code == 200
    after = relay(enforce="1", secret="", secrets=f"3:{EPOCH_B}")
    assert _signed(after.app.test_client(), EPOCH_A).status_code == 401
    assert _signed(after.app.test_client(), EPOCH_B).status_code == 200


def test_adoption_is_broken_down_by_epoch(relay):
    """What tells an operator the fleet has moved over, before the old key is deleted."""
    m = relay(secret="", secrets=f"2:{EPOCH_A},3:{EPOCH_B}")
    _signed(m.app.test_client(), EPOCH_A)
    _signed(m.app.test_client(), EPOCH_B)
    _signed(m.app.test_client(), EPOCH_B, age=1)   # distinct ts => distinct signature, not a replay
    adoption = m.app.test_client().get("/health/detail").get_json()["auth_adoption"]
    assert adoption["by_epoch"] == {"2": 1, "3": 2}
    assert adoption["window_signed"] == 3
    assert adoption["epochs_configured"] == ["2", "3"]


def test_health_never_discloses_a_configured_secret(relay):
    import json as _json

    m = relay(secret=SECRET, secrets=f"2:{EPOCH_A},3:{EPOCH_B}")
    body = _json.dumps(m.app.test_client().get("/health/detail").get_json())
    for leaked in (SECRET, EPOCH_A, EPOCH_B):
        assert leaked not in body, "/health must expose epoch LABELS, never the keys"
    assert '"auth_epochs": 3' in body or m.app.test_client().get("/health/detail").get_json()["auth_epochs"] == 3


def test_single_use_replay_still_holds_across_the_multi_secret_path(relay):
    """Regression guard: the epoch loop must not bypass _claim_signature."""
    m = relay(enforce="1", secret="", secrets=f"2:{EPOCH_A}")
    client = m.app.test_client()
    ts = int(time.time())
    headers = {"Authorization": "Bearer " + sign(TOKEN, "", ts, secret=EPOCH_A),
               "X-Khandaq-Ts": str(ts)}
    assert client.post("/wake", json={"token": TOKEN}, headers=headers).status_code == 200
    assert client.post("/wake", json={"token": TOKEN}, headers=headers).status_code == 401


def test_a_malformed_epoch_list_does_not_take_authentication_down(relay):
    """
    A typo in one entry of a rotation list must not lock out every client at once — that is the
    failure this whole change exists to avoid, so it must not be reintroduced by the parser.
    """
    m = relay(enforce="1", secret="", secrets=f",,3:{EPOCH_B},garbage-no-colon,bad label:x,")
    assert _signed(m.app.test_client(), EPOCH_B).status_code == 200
    assert m.app.test_client().get("/health/detail").get_json()["auth_adoption"]["epochs_configured"] == ["3"]


def test_no_secret_in_either_variable_still_fails_closed(relay):
    m = relay(secret="", secrets="")
    assert m.app.test_client().get(f"/toxfcm/fcm.php?id={TOKEN}").status_code == 401
    assert m.app.test_client().get("/health/detail").get_json()["auth_mode"] == "misconfigured"


def test_a_secret_containing_a_colon_survives_parsing(relay):
    """Split on the FIRST colon only — a secret is opaque and may contain anything."""
    weird = "aa:bb:cc"
    m = relay(enforce="1", secret="", secrets=f"7:{weird}")
    assert _signed(m.app.test_client(), weird).status_code == 200


def test_an_unsigned_json_wake_is_still_served_in_soft_mode(relay):
    """
    KHANDAQ (re-audit 2026-08-21, R-02): the field-compatibility guarantee, on the NEW endpoint.

    There was a test for the legacy /toxfcm/fcm.php path and none for /wake, so half of what soft
    mode exists to protect was uncovered. It matters more than it looks: no shipped client signs at
    all — the push secret is empty in every release build — so if this ever started returning 401,
    every wake notification in the field would stop, and the only thing standing between that and a
    release is this assertion.
    """
    m = relay(enforce="0")
    r = m.app.test_client().post("/wake", json={"token": TOKEN})
    assert r.status_code == 200, r.get_data(as_text=True)
    assert m.sent == [(TOKEN, "")]
    outcomes = m.app.test_client().get("/health/detail").get_json()["auth_adoption"]["window_outcomes"]
    assert outcomes["missing"] == 1, "an unsigned request must still be COUNTED while it is served"


# ------------------------------------------------- per-install capabilities (re-audit R-01, step 1)


def test_json_wake_tolerates_an_unknown_cap_field(relay):
    """
    KHANDAQ (re-audit 2026-08-21, R-01). DESIGN-push-per-install-capabilities.md sequences the fix so
    that step 1 - "relay accepts cap, ignored when absent" - ships before any client emits one, and
    calls it "deployable immediately". It already is: /wake reads token and sender and ignores the
    rest of the body. Nothing pinned that, though, and "reject unknown fields" is exactly the kind of
    hardening someone adds on a quiet afternoon. It would break the rollout silently and only on the
    step where clients have already started sending cap.

    So this test is the pin, not a new feature. It asserts the tolerance, and it asserts the thing
    that would hurt more: cap must stay OUT of the HMAC pre-image. The signature below is computed
    over token/sender/ts exactly as a client with no idea capabilities exist would compute it. If a
    future change folds cap into the pre-image, every shipped client's signature stops verifying at
    once - a fleet-wide flag day, which is what the whole sequencing exists to avoid.
    """
    m = relay(enforce="1")
    ts = int(time.time())
    r = m.app.test_client().post(
        "/wake",
        json={"token": TOKEN, "sender": SENDER, "cap": "Zm9vYmFyLWNhcGFiaWxpdHktdmFsdWU"},
        headers={"Authorization": "Bearer " + sign(TOKEN, SENDER, ts), "X-Khandaq-Ts": str(ts)},
    )
    assert r.status_code == 200, r.get_data(as_text=True)
    assert m.sent == [(TOKEN, SENDER)]


@pytest.mark.parametrize("cap", ["", "x" * 8192, "!!not base64!!", None, 12345, {"nested": True}])
def test_a_hostile_cap_value_changes_nothing_yet(relay, cap):
    """
    Nothing consumes cap yet, so no value of it may alter the outcome - including the types that a
    naive later implementation would crash on. Step 1 has to be inert, not merely usually inert.
    """
    m = relay(enforce="1")
    ts = int(time.time())
    r = m.app.test_client().post(
        "/wake",
        json={"token": TOKEN, "sender": SENDER, "cap": cap},
        headers={"Authorization": "Bearer " + sign(TOKEN, SENDER, ts), "X-Khandaq-Ts": str(ts)},
    )
    assert r.status_code == 200, r.get_data(as_text=True)
    assert m.sent == [(TOKEN, SENDER)]


def test_bare_register_is_still_not_a_thing(relay):
    """
    The design's step 2 warned against a POST /register authenticated with the FLEET secret: every
    installation holds it, so anyone could register a capability against anyone else's token, and at
    the step where capabilities become required that silently ends the victim's notifications.

    That endpoint was never built. What exists instead is the three-call challenge flow below, whose
    proof is receiving an FCM push on the token — something a contact who merely knows the token
    cannot do. This test keeps the rejected shape rejected.
    """
    m = relay()
    assert m.app.test_client().post("/register", json={}).status_code == 404


# --------------------------------------------------------------------------- capability rollout
#
# KHANDAQ (re-audit 2026-08-22, K-01). The property under test is the one the finding asks for:
# extracting the shared secret from a published binary must not let you wake somebody else's device.


def _stub_challenge(m):
    """Capture the nonce the relay pushes, instead of sending it to Google."""
    m.pushed = []

    def fake_data(token, data):
        m.pushed.append((token, dict(data)))
        return True, "ok"

    m._send_fcm_data = fake_data
    return m


def _register(m, token, cap):
    """Walk the full flow the device walks: challenge -> receive push -> confirm."""
    c = m.app.test_client()
    r = c.post("/register/challenge", json={"token": token})
    assert r.status_code == 200, r.get_data(as_text=True)
    cid = r.get_json()["cid"]
    nonce = m.pushed[-1][1]["khandaq_reg_nonce"]
    r = c.post("/register/confirm", json={"cid": cid, "nonce": nonce, "cap": cap})
    assert r.status_code == 200, r.get_data(as_text=True)
    return cid, nonce


def _revoke(m, token, cap):
    """Revocation needs the same device proof as registration — see register_revoke's docstring."""
    c = m.app.test_client()
    r = c.post("/register/challenge", json={"token": token})
    assert r.status_code == 200, r.get_data(as_text=True)
    cid = r.get_json()["cid"]
    nonce = m.pushed[-1][1]["khandaq_reg_nonce"]
    return c.post("/register/revoke", json={"cid": cid, "nonce": nonce, "cap": cap})


CAP_A = "Zm9vYmFyLWNhcGFiaWxpdHktYWFhYWFhYWFhYQ"
CAP_B = "Zm9vYmFyLWNhcGFiaWxpdHktYmJiYmJiYmJiYg"


_wake_ts = [0]


def _wake(m, token=TOKEN, sender=SENDER, cap=None, secret=SECRET):
    # A distinct timestamp per call. Signatures are single-use (the replay store), and two wakes in
    # the same second with the same token and sender produce the SAME signature — so a test that
    # sends two would be measuring the replay guard rather than what it meant to measure.
    _wake_ts[0] += 1
    ts = int(time.time()) + _wake_ts[0] % 60
    body = {"token": token, "sender": sender}
    if cap is not None:
        body["cap"] = cap
    return m.app.test_client().post(
        "/wake", json=body,
        headers={"Authorization": "Bearer " + sign(token, sender, ts, secret), "X-Khandaq-Ts": str(ts)})


def test_a_device_that_has_not_registered_is_unaffected(relay):
    """The anti-flag-day property: nothing changes for anyone until they opt in."""
    m = _stub_challenge(relay(enforce="1"))
    assert _wake(m).status_code == 200
    assert _wake(m, cap="").status_code == 200


def test_knowing_the_token_is_not_enough_to_register(relay):
    """
    The whole point. Every contact of a recipient knows that recipient's FCM token — it is in the
    wake URL they were given. If knowing it were enough to register a capability, a contact could
    register one the recipient does not hold and kill their notifications at enforcement.

    The proof is the nonce, and the nonce only ever goes to the device that owns the token.
    """
    m = _stub_challenge(relay(enforce="1"))
    c = m.app.test_client()
    r = c.post("/register/challenge", json={"token": TOKEN})
    cid = r.get_json()["cid"]
    # The attacker knows the token and the challenge id, and guesses the nonce.
    for _ in range(m.PUSH_CHALLENGE_MAX_TRIES):
        r = c.post("/register/confirm", json={"cid": cid, "nonce": "not-the-nonce", "cap": CAP_A})
        assert r.status_code == 400
    # KHANDAQ (deep review 2026-08-23, RR3-05): a SMALL number of guesses is allowed and then the
    # challenge burns. It used to burn on the first one, which stopped guessing — and also let anyone
    # who knew a live cid extinguish the device's own registration with a single garbage request.
    # Three tries against a 256-bit nonce is not an oracle; one hostile request cancelling somebody
    # else's registration was a denial of service.
    real_nonce = m.pushed[-1][1]["khandaq_reg_nonce"]
    r = c.post("/register/confirm", json={"cid": cid, "nonce": real_nonce, "cap": CAP_A})
    assert r.status_code == 400, "after the allowance the challenge must be gone"
    # Nothing was registered, so the victim's wakes still work.
    assert _wake(m).status_code == 200


def test_once_registered_a_wake_needs_the_capability(relay):
    m = _stub_challenge(relay(enforce="1", cap_grace="0"))
    _register(m, TOKEN, CAP_A)
    assert _wake(m, cap=CAP_A).status_code == 200
    assert _wake(m, cap=None).status_code == 401, "a registered device must not be wakeable without it"
    assert _wake(m, cap=CAP_B).status_code == 401, "a capability nobody registered must not work"


def test_capability_a_does_not_work_with_token_b(relay):
    """The spec's explicit negative test: capabilities are bound to one device."""
    m = _stub_challenge(relay(enforce="1", cap_grace="0"))
    other = "another-fcm-registration-token"
    _register(m, TOKEN, CAP_A)
    _register(m, other, CAP_B)
    assert _wake(m, token=TOKEN, cap=CAP_B).status_code == 401
    assert _wake(m, token=other, cap=CAP_A).status_code == 401
    assert _wake(m, token=TOKEN, cap=CAP_A).status_code == 200
    assert _wake(m, token=other, cap=CAP_B).status_code == 200


def test_a_capability_can_be_revoked_without_shipping_an_app(relay):
    m = _stub_challenge(relay(enforce="1", cap_grace="0"))
    _register(m, TOKEN, CAP_A)
    _register(m, TOKEN, CAP_B)
    assert _wake(m, cap=CAP_B).status_code == 200
    assert _revoke(m, TOKEN, CAP_B).status_code == 200
    assert _wake(m, cap=CAP_B).status_code == 401, "a revoked capability must stop working"
    assert _wake(m, cap=CAP_A).status_code == 200, "revoking one must not affect the others"


def test_revoking_the_last_capability_returns_the_device_to_legacy_behaviour(relay):
    """Fail toward delivery: a device with nothing registered must never be left unwakeable."""
    m = _stub_challenge(relay(enforce="1"))
    _register(m, TOKEN, CAP_A)
    _revoke(m, TOKEN, CAP_A)
    assert _wake(m, cap=None).status_code == 200


def test_fcm_token_rotation_re_registers_without_losing_notifications(relay):
    """
    A rotated FCM token is a different token, so it starts with no capabilities and is wakeable —
    which is what keeps a rotation from being a silent notification outage — and the device then
    registers against the new one.
    """
    m = _stub_challenge(relay(enforce="1", cap_grace="0"))
    _register(m, TOKEN, CAP_A)
    rotated = "rotated-fcm-registration-token"
    assert _wake(m, token=rotated, cap=None).status_code == 200
    _register(m, rotated, CAP_B)
    assert _wake(m, token=rotated, cap=None).status_code == 401
    assert _wake(m, token=rotated, cap=CAP_B).status_code == 200


def test_a_challenge_cannot_be_replayed(relay):
    m = _stub_challenge(relay(enforce="1", cap_grace="0"))
    cid, nonce = _register(m, TOKEN, CAP_A)
    r = m.app.test_client().post("/register/confirm", json={"cid": cid, "nonce": nonce, "cap": CAP_B})
    assert r.status_code == 400, "a consumed challenge must not register a second capability"


def test_a_malformed_capability_is_refused_at_registration(relay):
    m = _stub_challenge(relay(enforce="1"))
    c = m.app.test_client()
    cid = c.post("/register/challenge", json={"token": TOKEN}).get_json()["cid"]
    nonce = m.pushed[-1][1]["khandaq_reg_nonce"]
    r = c.post("/register/confirm", json={"cid": cid, "nonce": nonce, "cap": "short"})
    assert r.status_code == 400, "a guessable capability must not enter the authorisation path"


def test_a_contact_cannot_revoke_and_thereby_disarm_the_device(relay):
    """
    KHANDAQ (re-review follow-up 2026-08-22) — found by running the flow over real HTTP.

    Revocation used to accept the capability alone: presenting it proved you held it, and holding it
    was the whole authority being given up. True about ACCESS, false about the DEVICE. Revoking the
    LAST capability returns the token to the "none" state, in which no capability is required — so a
    contact holding the only one could hand back its own access and, in the same call, silently
    return the recipient to being wakeable by anyone who knows the token.

    A contact has the capability and not the device, so it can no longer do this at all.
    """
    m = _stub_challenge(relay(enforce="1", cap_grace="0"))
    _register(m, TOKEN, CAP_A)
    assert _wake(m, cap=None).status_code == 401, "precondition: the device is protected"

    # Everything a malicious contact holds: the token and its own capability.
    r = m.app.test_client().post("/register/revoke", json={"token": TOKEN, "cap": CAP_A})
    assert r.status_code == 400, "revocation without device proof must be refused"

    # ...and the protection is still in place.
    assert _wake(m, cap=None).status_code == 401
    assert _wake(m, cap=CAP_A).status_code == 200


def test_the_device_itself_can_still_revoke(relay):
    """
    The other half: proving possession of the FCM registration is enough, as it is for registering.

    Two capabilities on purpose. Revoking the LAST one returns the token to the unregistered state,
    in which no capability is required — which is the device choosing to go back to pre-capability
    behaviour and is covered separately below. With two, the revocation is observable as such: the
    revoked one stops working while the other keeps working.
    """
    m = _stub_challenge(relay(enforce="1", cap_grace="0"))
    _register(m, TOKEN, CAP_A)
    _register(m, TOKEN, CAP_B)
    assert _revoke(m, TOKEN, CAP_B).status_code == 200
    assert _wake(m, cap=CAP_B).status_code == 401, "the revoked capability must stop working"
    assert _wake(m, cap=CAP_A).status_code == 200, "and only that one"


def test_a_contact_holding_the_old_url_keeps_working_during_the_grace_window(relay):
    """
    The one gap this design has, and the reason the window exists.

    A recipient registers a capability and re-publishes its wake URL over Tox. A contact that was
    offline at that moment still holds the URL WITHOUT the capability. Refusing it would cost that
    user real notifications — requirement 5, "fail toward delivery", which is the one failure the
    user cannot see. So it is delivered, and counted, for a fortnight.
    """
    m = _stub_challenge(relay(enforce="1", cap_grace="14"))
    _register(m, TOKEN, CAP_A)
    assert _wake(m, cap=None).status_code == 200, "a stale contact must not be cut off immediately"
    assert _wake(m, cap=CAP_B).status_code == 200, "nor one holding a superseded capability"
    assert _wake(m, cap=CAP_A).status_code == 200
    body = m.app.test_client().get("/health/detail").get_json()
    assert body["capabilities"]["grace_still_used"] >= 1, \
        "the grace window must be observable, or nobody can tell when it is safe to close"


def test_the_grace_window_does_not_last_forever(relay):
    m = _stub_challenge(relay(enforce="1", cap_grace="14"))
    _register(m, TOKEN, CAP_A)
    # Age the registration past the window rather than waiting a fortnight.
    import sqlite3 as _sqlite3
    conn = _sqlite3.connect(m.COALESCE_DB)
    conn.execute("UPDATE pushcap SET created = created - ?", (15 * 86400,))
    conn.commit()
    conn.close()
    assert _wake(m, cap=None).status_code == 401
    assert _wake(m, cap=CAP_A).status_code == 200


def test_a_broken_capability_store_still_delivers(relay, monkeypatch):
    """
    Same rule as the rate limiter: an infrastructure failure fails toward DELIVERY. Otherwise a full
    disk becomes a fleet-wide notification outage, and filling one becomes an attack.
    """
    m = _stub_challenge(relay(enforce="1", cap_grace="0"))
    _register(m, TOKEN, CAP_A)
    real = m._cap_state
    monkeypatch.setattr(m, "_cap_state", lambda *a, **k: "store_error")
    assert _wake(m, cap=None).status_code == 200
    monkeypatch.setattr(m, "_cap_state", real)
    assert _wake(m, cap=None).status_code == 401


def test_the_challenge_push_is_silent(relay):
    """A device must not say "New message" because it registered a capability."""
    m = _stub_challenge(relay())
    m.app.test_client().post("/register/challenge", json={"token": TOKEN})
    assert m.pushed, "no challenge was pushed"
    assert m.sent == [], "the challenge must not go through the wake path"
    assert "khandaq_reg_nonce" in m.pushed[-1][1]


def test_the_relay_never_stores_the_token_or_the_capability(relay):
    """
    The relay deliberately holds no device registry. Verification needs only "is sha256(cap)
    registered against sha256(token)", so neither secret has to be persisted — and a database that
    someone walks off with must not yield anything that wakes a device.
    """
    import sqlite3 as _sqlite3

    m = _stub_challenge(relay())
    _register(m, TOKEN, CAP_A)
    conn = _sqlite3.connect(m.COALESCE_DB)
    try:
        dump = "\n".join(line for line in conn.iterdump())
    finally:
        conn.close()
    assert TOKEN not in dump, "the FCM registration token reached the database"
    assert CAP_A not in dump, "the capability reached the database"


def test_the_legacy_get_endpoint_can_be_retired_by_configuration(relay, monkeypatch):
    """Retirement is an operational decision, not a code change — and it answers 410, not 404."""
    m = _stub_challenge(relay())
    monkeypatch.setattr(m, "PUSH_LEGACY_GET", "410")
    ts = int(time.time())
    r = m.app.test_client().get(
        f"/toxfcm/fcm.php?id={TOKEN}&auth={sign(TOKEN, '', ts)}&ts={ts}")
    assert r.status_code == 410
    assert m.sent == [], "a retired endpoint must not still deliver"


def test_health_detail_reports_the_rollout_numbers(relay):
    m = _stub_challenge(relay())
    _register(m, TOKEN, CAP_A)
    body = m.app.test_client().get("/health/detail").get_json()
    assert body["capabilities"]["devices_registered"] == 1
    assert body["capabilities"]["mode"] == "auto"
    assert "legacy_pct" in body["emission_paths"]


def test_public_health_does_not_leak_rollout_detail(relay):
    """Adoption percentages are operational detail for us and a targeting hint for anyone else."""
    m = _stub_challenge(relay())
    _register(m, TOKEN, CAP_A)
    body = m.app.test_client().get("/health").get_json()
    assert "capabilities" not in body and "emission_paths" not in body
    assert "auth_adoption" not in body


# ------------------------------------------- /health must not report a service account it cannot read


def _load_with_sa(relay, tmp_path, monkeypatch, mode):
    """mode: 'ok' | 'unreadable' | 'missing' | 'unset'."""
    sa = tmp_path / "firebase-sa.json"
    if mode != "missing" and mode != "unset":
        sa.write_text('{"type": "service_account"}', encoding="utf-8")
    if mode == "unset":
        monkeypatch.setenv("FCM_SERVICE_ACCOUNT_FILE", "")
    else:
        monkeypatch.setenv("FCM_SERVICE_ACCOUNT_FILE", str(sa))
    m = relay()
    if mode == "unreadable":
        # os.access() is what the code asks; patch it rather than relying on chmod, which does not
        # take effect for root and behaves differently on Windows. The production failure IS an
        # os.access/open refusal, so this substitutes for the mechanism, not around it.
        real_access = os.access
        monkeypatch.setattr(
            m.os, "access",
            lambda path, mode_, *a, **k: False if str(path) == str(sa) else real_access(path, mode_, *a, **k))
    return m


def test_health_reports_an_unreadable_service_account_as_unusable(relay, tmp_path, monkeypatch):
    """
    KHANDAQ (production deploy 2026-08-21). The relay container runs as uid 10001 since the K-05
    hardening; the service account on the host was 0600 root:root. The file therefore existed and
    could not be opened, and the old os.path.isfile() check reported "fcm_configured": true,
    "fcm_mode": "v1" over a total notification outage — including to the deploy script's own /health
    assertion, which would have called that deploy a success.

    A health field that is wrong in the direction that HIDES an outage is the failure this project
    has already had once (K-02, auth_mode reporting "off" while the relay refused everything). So:
    unreadable must read as unusable, and must say which of the two problems it is.
    """
    m = _load_with_sa(relay, tmp_path, monkeypatch, "unreadable")
    body = m.app.test_client().get("/health/detail").get_json()
    assert body["fcm_configured"] is False
    assert body["fcm_mode"] == "none"
    assert body["fcm_service_account"] == "unreadable"


def test_health_distinguishes_missing_from_unreadable(relay, tmp_path, monkeypatch):
    """The two need different fixes: provision the file, versus chown it. Do not conflate them."""
    m = _load_with_sa(relay, tmp_path, monkeypatch, "missing")
    body = m.app.test_client().get("/health/detail").get_json()
    assert body["fcm_configured"] is False
    assert body["fcm_service_account"] == "missing"


def test_health_reports_a_readable_service_account_as_ok(relay, tmp_path, monkeypatch):
    """The green path still has to be green, or the check above is just a way to fail."""
    m = _load_with_sa(relay, tmp_path, monkeypatch, "ok")
    body = m.app.test_client().get("/health/detail").get_json()
    assert body["fcm_configured"] is True
    assert body["fcm_mode"] == "v1"
    assert body["fcm_service_account"] == "ok"


def test_health_omits_the_field_when_no_service_account_is_configured(relay, tmp_path, monkeypatch):
    """No path configured is not a fault; do not invent a status for a thing nobody asked for."""
    m = _load_with_sa(relay, tmp_path, monkeypatch, "unset")
    body = m.app.test_client().get("/health/detail").get_json()
    assert "fcm_service_account" not in body


# ------------------------------------------------- audit round 3: F-06, F-12, F-13, F-14


@pytest.mark.parametrize("bad_auth", ["é", "ÿ" * 64, "cafébabe", "тест"])
def test_a_non_ascii_auth_value_is_a_refusal_not_a_crash(relay, bad_auth):
    """
    KHANDAQ (audit round 3, F-06). hmac.compare_digest() raises TypeError when handed two str
    arguments and either is non-ASCII. `supplied` comes verbatim out of ?auth= (Flask decodes the
    query string as UTF-8), so `?auth=%C3%A9` used to reach that comparison and produce an unhandled
    500 — in a security decision, on an unauthenticated path, with no @app.errorhandler anywhere to
    catch it.

    The damage was not the status code. Each one wrote a full traceback into the same bounded
    json-file ring the operator is told to read before enforcing (~2.3 KB against ~104 bytes for a
    normal request), and the request never reached _record_auth_outcome(), so it stayed invisible in
    the adoption counters it was flushing away. Under PUSH_AUTH_ENFORCE=1 it turned a 401 into a 500.

    A hostile value must land in `badmac` like any other wrong signature.
    """
    m = relay(enforce="1")
    ts = int(time.time())
    r = m.app.test_client().get(f"/toxfcm/fcm.php?id={TOKEN}&auth={bad_auth}&ts={ts}")
    assert r.status_code == 401, r.get_data(as_text=True)
    assert m.sent == []


def test_a_non_ascii_bearer_header_is_a_refusal_not_a_crash(relay):
    """The header path decodes as latin-1 rather than UTF-8, so it reaches the same comparison."""
    m = relay(enforce="1")
    ts = int(time.time())
    r = m.app.test_client().post(
        "/wake",
        json={"token": TOKEN, "sender": ""},
        headers={"Authorization": "Bearer ÿþ", "X-Khandaq-Ts": str(ts)},
    )
    assert r.status_code == 401
    assert m.sent == []


def test_a_non_ascii_auth_value_is_counted_as_badmac(relay):
    """It must be visible in the adoption counters, not silently absent from them."""
    m = relay()
    ts = int(time.time())
    m.app.test_client().get(f"/toxfcm/fcm.php?id={TOKEN}&auth=café&ts={ts}")
    outcomes = m.app.test_client().get("/health/detail").get_json()["auth_adoption"]["today_outcomes"]
    assert outcomes["badmac"] == 1, outcomes


@pytest.mark.parametrize("value", ["enforce", "ON!", "2", "yes please", "TRUE ", "", "maybe"])
def test_an_unrecognised_enforce_value_refuses_to_start(relay, value):
    """
    KHANDAQ (audit round 3, F-13). PUSH_AUTH_ENFORCE used to be `value in ("1","true","yes","on")`,
    so anything unrecognised — a typo, or the very plausible PUSH_AUTH_ENFORCE=enforce — selected the
    WEAKER mode without a word. The neighbouring PUSH_AUTH_ENFORCE_BY already validates and complains;
    the security-relevant boolean should not be the lax one.

    Note "TRUE " with the trailing space is accepted (it is stripped and lowercased) and "" is not:
    an empty value is a variable someone meant to set.
    """
    if value == "TRUE ":
        pytest.skip("whitespace is stripped and case is folded — this one is legitimately accepted")
    with pytest.raises(SystemExit) as exc:
        relay(enforce=value)
    assert "PUSH_AUTH_ENFORCE" in str(exc.value)


@pytest.mark.parametrize("value", ["1", "0", "true", "false", "yes", "no", "on", "off", "ON", " 1 "])
def test_the_documented_enforce_values_still_work(relay, value):
    """The strictness must not break the spellings the compose files and deploy script actually use."""
    m = relay(enforce=value)
    expected = "enforce" if value.strip().lower() in ("1", "true", "yes", "on") else "soft"
    assert m.app.test_client().get("/health/detail").get_json()["auth_mode"] == expected


def test_the_soft_mode_log_line_carries_no_client_ip(relay, caplog):
    """
    KHANDAQ (audit round 3, F-12). Soft mode logs one line per allowed request. It used to name the
    client IP, which put a sender's address in the same container log as the recipient's stable token
    hash — one join apart. The outcome label is what the line is for; the per-IP view already lives
    in the rate limiter, and nginx keeps its own (IP, time) record regardless.
    """
    m = relay()
    ts = int(time.time())
    with caplog.at_level("WARNING"):
        m.app.test_client().get(f"/toxfcm/fcm.php?id={TOKEN}&auth={'0' * 64}&ts={ts}")
    soft = [rec.getMessage() for rec in caplog.records if "push auth SOFT" in rec.getMessage()]
    assert soft, "soft mode must still say that it allowed an unsigned request"
    assert "127.0.0.1" not in soft[0]
    assert "badmac" in soft[0], "the outcome label is the part worth keeping"


def test_a_successful_wake_does_not_log_the_token_hash_at_info(relay, caplog):
    """
    KHANDAQ (audit round 3, F-12). _th(token) is a stable per-device identifier. Emitting it on every
    successful wake at INFO builds a durable activity record for that device for as long as the log
    ring holds. Useful while debugging, not worth retaining by default.
    """
    m = relay()
    m._send_wake = m.__dict__.get("_real_send_wake", m._send_wake)
    ts = int(time.time())
    with caplog.at_level("INFO"):
        m.app.test_client().get(
            f"/toxfcm/fcm.php?id={TOKEN}&auth={sign(TOKEN, '', ts)}&ts={ts}")
    assert not [r for r in caplog.records if r.levelname == "INFO" and "token#" in r.getMessage()]


# --- the coalescing branch, which had no coverage at all -------------------------------------------


def test_a_second_wake_inside_the_window_is_coalesced(relay):
    """
    KHANDAQ (audit round 3, F-14). PUSH_COALESCE_SECONDS defaults to 45 in production and the test
    fixture pinned it to 0 for every one of the tests in this file, so the suppression branch — the
    one that decides whether a real FCM push happens — was never executed by anything.
    """
    m = relay(coalesce="45")
    client = m.app.test_client()

    ts = int(time.time())
    first = client.get(f"/toxfcm/fcm.php?id={TOKEN}&auth={sign(TOKEN, '', ts)}&ts={ts}")
    assert first.status_code == 200
    assert first.get_json().get("coalesced") is None, "the first wake must actually be sent"
    assert m.sent == [(TOKEN, "")]

    ts2 = ts + 1
    second = client.get(f"/toxfcm/fcm.php?id={TOKEN}&auth={sign(TOKEN, '', ts2)}&ts={ts2}")
    assert second.status_code == 200, "a coalesced wake still reports success so the caller stops retrying"
    assert second.get_json().get("coalesced") == 1
    assert m.sent == [(TOKEN, "")], "the second wake must NOT reach FCM"


def test_coalescing_is_per_token(relay):
    """One busy device must not suppress wakes for a different one."""
    m = relay(coalesce="45")
    client = m.app.test_client()
    other = "a-different-device-registration-token"

    ts = int(time.time())
    client.get(f"/toxfcm/fcm.php?id={TOKEN}&auth={sign(TOKEN, '', ts)}&ts={ts}")
    ts2 = ts + 1
    r = client.get(f"/toxfcm/fcm.php?id={other}&auth={sign(other, '', ts2)}&ts={ts2}")

    assert r.get_json().get("coalesced") is None
    assert m.sent == [(TOKEN, ""), (other, "")]


def test_a_failed_send_releases_the_coalesce_slot(relay):
    """
    A send that failed must not leave the window claimed — otherwise one upstream blip silences the
    device for the rest of the window and the caller is told to stop retrying.
    """
    m = relay(coalesce="45")
    client = m.app.test_client()

    m._send_wake = lambda token, sender="": (False, "FCM v1 HTTP 503 []: unavailable")
    ts = int(time.time())
    assert client.get(f"/toxfcm/fcm.php?id={TOKEN}&auth={sign(TOKEN, '', ts)}&ts={ts}").status_code == 502

    m.sent = []
    m._send_wake = lambda token, sender="": (m.sent.append((token, sender)), (True, "ok"))[1]
    ts2 = ts + 1
    r = client.get(f"/toxfcm/fcm.php?id={TOKEN}&auth={sign(TOKEN, '', ts2)}&ts={ts2}")
    assert r.get_json().get("coalesced") is None, "the failed attempt must not have claimed the window"
    assert m.sent == [(TOKEN, "")]


# --- the public health endpoint must stay uninformative -------------------------------------------


def test_public_health_is_liveness_only(relay):
    """
    KHANDAQ (audit round 3, F-10). /health used to hand the whole picture to the internet: auth_mode,
    the epoch count, the service-account state and the full auth_adoption summary — seven outcome
    buckets, signed/unsigned totals, per-epoch counts, 60 days of retention. No shipped client signs,
    so `missing` is essentially all traffic; polling once a minute and differencing gave a live request
    rate and a daily and weekly activity profile of the whole user base.

    Liveness is public. Everything else is on /health/detail, which nginx allows only from loopback.
    """
    m = relay()
    body = m.app.test_client().get("/health").get_json()
    assert body == {"status": "ok"}, body


def test_public_health_leaks_nothing_even_when_misconfigured(relay):
    """The interesting states are exactly the ones worth not advertising."""
    m = relay(enforce="1")
    body = m.app.test_client().get("/health").get_json()
    assert set(body) == {"status"}
    text = m.app.test_client().get("/health").get_data(as_text=True)
    for leak in ("auth_mode", "enforce", "adoption", "fcm", "epoch", "soft"):
        assert leak not in text.lower()


def test_the_detail_endpoint_still_carries_what_the_operator_needs(relay):
    """Moving it must not lose it: the enforce decision is read from these fields."""
    m = relay()
    body = m.app.test_client().get("/health/detail").get_json()
    for key in ("status", "auth_mode", "auth_epochs", "auth_required", "auth_adoption",
                "fcm_configured", "fcm_mode"):
        assert key in body, f"{key} missing from /health/detail"


# ---------------------------------------------------------------------------
# KHANDAQ (re-review v2 2026-08-22, RR2-04): the registration challenge is an externally triggerable
# push. The nonce stops an attacker COMPLETING a registration; nothing stopped them repeating the
# first leg, and per-IP limiting does not bound abuse aimed at one victim token from many addresses.
# These are the three checks the review asks for.
# ---------------------------------------------------------------------------


def test_one_token_cannot_be_hammered_from_many_addresses(relay):
    """The finding itself: many source IPs, one victim token, and the device must stop buzzing."""
    m = _stub_challenge(relay(rate_limit="100000"))
    c = m.app.test_client()
    for i in range(40):
        c.post("/register/challenge", json={"token": TOKEN},
               environ_base={"REMOTE_ADDR": f"203.0.113.{i % 250 + 1}"})
    # Budget is a burst plus refill; nothing here can confirm a challenge, so nothing is refunded.
    # The exact number does not matter — that it is bounded, and small, does.
    assert len(m.pushed) <= m.PUSH_CHALLENGE_BURST + 1, (
        f"{len(m.pushed)} pushes reached the device from 40 requests across 40 addresses — "
        f"the per-token budget is not bounding anything")
    assert len(m.pushed) >= 1, "the first, legitimate-looking request must still be served"


def test_repeats_reuse_a_live_challenge_once_the_small_allowance_is_taken(relay):
    """
    KHANDAQ (deep review 2026-08-23, RR3-05). A token may hold a few live challenges, not one.

    With exactly one, a stranger who knew the token — every contact does — could take the slot and
    never confirm it; the device's own request then got that cid back, the push for it had already
    been delivered and discarded as unsolicited, and registration timed out. One request every five
    minutes held a device out of the capability scheme for good.

    So repeats create their own challenge up to a small allowance, and only then reuse. The flood
    bound is unchanged: it is the token bucket, not this number.
    """
    m = _stub_challenge(relay(rate_limit="100000"))
    c = m.app.test_client()
    first = c.post("/register/challenge", json={"token": TOKEN})
    assert first.status_code == 200
    codes = [c.post("/register/challenge", json={"token": TOKEN},
                    environ_base={"REMOTE_ADDR": f"198.51.100.{i + 1}"})
             for i in range(6)]
    reused = [r for r in codes if r.status_code == 200 and r.get_json().get("reused") == 1]
    throttled = [r for r in codes if r.status_code == 429]
    assert reused or throttled, "a repeat storm must end in reuse or a refusal, never in more pushes"
    assert len(m.pushed) <= m.PUSH_CHALLENGE_MAX_LIVE_PER_TOKEN, (
        f"{len(m.pushed)} pushes reached the device — the live-challenge allowance is not bounding")


def test_the_per_ip_limiter_still_bounds_many_tokens_from_one_address(relay):
    """The new per-token budget must not have replaced the old per-IP one."""
    m = _stub_challenge(relay(rate_limit="5"))
    c = m.app.test_client()
    codes = [c.post("/register/challenge", json={"token": f"token-number-{i}-aaaaaaaaaa"},
                    environ_base={"REMOTE_ADDR": "203.0.113.9"}).status_code
             for i in range(20)]
    assert 429 in codes, "one address enumerating many tokens must still hit the per-IP limiter"


def test_an_honest_device_registers_every_contact_without_being_throttled(relay):
    """
    The counterpart, and the reason the budget is refunded rather than merely large.

    Capabilities are per-contact, so a device with a real contact list walks challenge->confirm many
    times in a row. That must not be throttled — and it is not, because each round proves possession
    of the FCM registration and buys back the credit it spent. An abuser cannot do that even once.
    """
    m = _stub_challenge(relay(enforce="1", cap_grace="0"))
    caps = ["Y2FwYWJpbGl0eS1mb3ItY29udGFjdC1udW1iZXItJTAyZA" + f"{i:02d}" for i in range(12)]
    for cap in caps:
        _register(m, TOKEN, cap)
    assert len(m.pushed) == len(caps), "each completed round may cost exactly one push"
    for cap in caps:
        assert _wake(m, cap=cap).status_code == 200, "every registered contact must still wake it"
    assert _revoke(m, TOKEN, caps[0]).status_code == 200, "and revocation must still work after all that"


def test_a_failed_delivery_does_not_refund_the_budget(relay):
    """Making delivery fail must not be a way to buy unlimited attempts."""
    m = _stub_challenge(relay(rate_limit="100000"))

    def failing(token, data):
        return False, "upstream said no"

    m._send_fcm_data = failing
    c = m.app.test_client()
    codes = [c.post("/register/challenge", json={"token": TOKEN},
                    environ_base={"REMOTE_ADDR": f"203.0.113.{i + 1}"}).status_code
             for i in range(10)]
    assert codes.count(502) <= m.PUSH_CHALLENGE_BURST, (
        "a caller that can make delivery fail got more attempts than the budget allows")
    assert 429 in codes, "the budget must run out even when every send fails"
    body = c.get("/health/detail", environ_base={"REMOTE_ADDR": "127.0.0.1"}).get_json()
    assert body["challenges"]["outcomes"].get("throttled", 0) >= 1, "refusals must be counted"
    assert body["challenges"]["refused_pct"] > 0


def test_challenge_throttling_is_reported_without_identifying_anyone(relay):
    """/health/detail must answer "is this being abused" without answering "by whom"."""
    m = _stub_challenge(relay(rate_limit="100000"))
    c = m.app.test_client()
    for i in range(8):
        c.post("/register/challenge", json={"token": TOKEN},
               environ_base={"REMOTE_ADDR": f"203.0.113.{i + 1}"})
    body = c.get("/health/detail", environ_base={"REMOTE_ADDR": "127.0.0.1"}).get_json()
    ch = body.get("challenges")
    assert ch and "outcomes" in ch, "challenge throttling must be observable"
    # A live challenge absorbs the flood entirely: eight requests, one push, seven reuses. That is a
    # tighter bound than the token bucket behind it — the bucket is what catches the case where the
    # challenge has expired or been consumed, which test_a_failed_delivery_does_not_refund_the_budget
    # exercises. Asserting "throttled >= 1" here would have been asserting the weaker path.
    sent = ch["outcomes"].get("sent", 0)
    refused = ch["outcomes"].get("reused", 0) + ch["outcomes"].get("throttled", 0)
    assert sent <= m.PUSH_CHALLENGE_MAX_LIVE_PER_TOKEN, "the device must not be woken repeatedly"
    assert refused >= 1, "the rest must be absorbed, by reuse or by the budget"
    assert ch["outstanding"] >= 1
    blob = repr(body)
    assert TOKEN not in blob, "no token may appear in health output"
    assert hashlib.sha256(TOKEN.encode()).hexdigest()[:32] not in blob, "nor a token hash"


def test_cap_enforce_always_refuses_a_device_that_registered_nothing(relay):
    """
    KHANDAQ (internal audit 2026-08-22). PUSH_CAP_ENFORCE=always is the lever for "the fleet HMAC
    has leaked, close the relay now". It used to refuse nobody: the call site short-circuited on
    cap_state == "none" before asking the policy, and "none" is every device that has not registered
    — today, all of them. A valid signature over any known token still got a 200.
    """
    m = _stub_challenge(relay(enforce="1", cap_grace="0", cap_enforce="always"))
    assert _wake(m, cap=None).status_code == 401, "a token with no capability must be refused"
    assert _wake(m, cap="").status_code == 401, "an empty capability is not a capability"
    assert _wake(m, cap=CAP_A).status_code == 401, "an unregistered capability must not pass either"
    _register(m, TOKEN, CAP_A)
    assert _wake(m, cap=CAP_A).status_code == 200, "and a registered one must still work"


def test_cap_enforce_auto_still_lets_unregistered_devices_through(relay):
    """The counterpart: the default mode must not have been turned into a flag day by the fix."""
    m = _stub_challenge(relay(enforce="1", cap_grace="0", cap_enforce="auto"))
    assert _wake(m, cap=None).status_code == 200, "auto must not require what nobody registered"
    _register(m, TOKEN, CAP_A)
    assert _wake(m, cap=None).status_code == 401, "once registered, the device is held to it"
    assert _wake(m, cap=CAP_A).status_code == 200


def test_a_refused_capability_is_counted_once(relay):
    """The double-record made an enforcing relay overstate exactly the states an operator watches."""
    m = _stub_challenge(relay(enforce="1", cap_grace="0", cap_enforce="always"))
    assert _wake(m, cap=None).status_code == 401
    body = m.app.test_client().get("/health/detail",
                                   environ_base={"REMOTE_ADDR": "127.0.0.1"}).get_json()
    outcomes = body["capabilities"]["window_outcomes"]
    assert outcomes.get("cap_none") == 1, f"one refusal must count once, got {outcomes}"


def test_capability_outcomes_do_not_depress_the_signing_percentage(relay):
    """
    KHANDAQ (internal audit 2026-08-22). cap_* outcomes share the authoutcome table with the
    authentication ones. They were counted in the denominator of window_signed_pct and never in the
    numerator, so a fleet signing every request AND carrying a capability reported ~50%. That is the
    number the decision to set PUSH_AUTH_ENFORCE=1 turns on, and it could never reach the threshold.
    """
    m = _stub_challenge(relay(enforce="1", cap_grace="0"))
    _register(m, TOKEN, CAP_A)
    for _ in range(5):
        assert _wake(m, cap=CAP_A).status_code == 200
    body = m.app.test_client().get("/health/detail",
                                   environ_base={"REMOTE_ADDR": "127.0.0.1"}).get_json()
    adoption = body["auth_adoption"]
    assert adoption["window_signed_pct"] == 100.0, (
        f"every request was signed and carried a capability, yet adoption reads "
        f"{adoption['window_signed_pct']}% — the denominator is counting the wrong rows")
    assert set(adoption["window_outcomes"]) <= set(m.AUTH_OUTCOMES), \
        "only authentication outcomes belong in this summary"


def test_the_fleet_hmac_alone_cannot_wake_a_capability_protected_token(relay):
    """
    KHANDAQ (re-review v2 2026-08-22, RR2-05). The build-time HMAC is a fleet-wide key embedded in
    public binaries: anyone who extracts it from an APK and learns a token can produce a valid
    signature. That is why it is described as replay and rate-abuse hardening rather than as client
    identity, and why the per-contact capability is the boundary that actually matters.

    This is the property that claim rests on: a perfectly valid signature, made with the real secret,
    over a token that has registered a capability, is refused without that capability — and one
    revocation costs exactly one relationship.
    """
    m = _stub_challenge(relay(enforce="1", cap_grace="0"))
    _register(m, TOKEN, CAP_A)
    _register(m, TOKEN, CAP_B)

    # Correctly signed — _wake signs with SECRET, the same value a release build carries.
    assert _wake(m, cap=None).status_code == 401, \
        "possession of the fleet key must not be enough once the device has a capability"
    assert _wake(m, cap="Zm9yZ2VkLWNhcGFiaWxpdHktbm90LXJlZ2lzdGVyZWQtYWE").status_code == 401, \
        "nor must a made-up capability"
    assert _wake(m, cap=CAP_A).status_code == 200

    assert _revoke(m, TOKEN, CAP_A).status_code == 200
    assert _wake(m, cap=CAP_A).status_code == 401, "the revoked relationship stops"
    assert _wake(m, cap=CAP_B).status_code == 200, "and only that one"


def test_a_stranger_cannot_extinguish_the_devices_own_challenge(relay):
    """
    KHANDAQ (deep review 2026-08-23, RR3-05). A wrong nonce used to delete the challenge outright, so
    anyone who learned a live cid could cancel somebody else's registration with one garbage request.
    """
    m = _stub_challenge(relay(enforce="1", cap_grace="0"))
    c = m.app.test_client()
    r = c.post("/register/challenge", json={"token": TOKEN})
    cid = r.get_json()["cid"]
    nonce = m.pushed[-1][1]["khandaq_reg_nonce"]

    # Посторонний с чужого адреса пробует мусорный nonce — один раз, как это и делают.
    bad = c.post("/register/confirm", json={"cid": cid, "nonce": "garbage", "cap": CAP_B},
                 environ_base={"REMOTE_ADDR": "203.0.113.44"})
    assert bad.status_code == 400

    # Устройство завершает СВОЮ регистрацию как ни в чём не бывало.
    ok = c.post("/register/confirm", json={"cid": cid, "nonce": nonce, "cap": CAP_A})
    assert ok.status_code == 200, "чужой мусорный запрос не должен гасить регистрацию устройства"
    assert _wake(m, cap=CAP_A).status_code == 200


def test_a_held_challenge_slot_does_not_lock_the_device_out(relay):
    """
    The other half of RR3-05: a stranger who takes a slot and never confirms it must not prevent the
    device from obtaining a challenge whose nonce it actually receives.
    """
    m = _stub_challenge(relay(enforce="1", cap_grace="0", rate_limit="100000"))
    c = m.app.test_client()

    # Посторонний занимает слот и ничего не подтверждает.
    held = c.post("/register/challenge", json={"token": TOKEN},
                  environ_base={"REMOTE_ADDR": "203.0.113.9"})
    assert held.status_code == 200
    held_cid = held.get_json()["cid"]

    # Устройство просит свой challenge и должно получить ДРУГОЙ, с доставленным ему nonce.
    mine = c.post("/register/challenge", json={"token": TOKEN})
    assert mine.status_code == 200
    assert mine.get_json()["cid"] != held_cid, \
        "устройство получило чужой cid — nonce к нему уже был доставлен и отброшен"
    nonce = m.pushed[-1][1]["khandaq_reg_nonce"]
    r = c.post("/register/confirm", json={"cid": mine.get_json()["cid"], "nonce": nonce, "cap": CAP_A})
    assert r.status_code == 200, "устройство должно суметь зарегистрироваться при занятом слоте"
    assert _wake(m, cap=CAP_A).status_code == 200
