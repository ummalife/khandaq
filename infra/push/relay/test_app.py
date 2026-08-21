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
              trusted_proxies: str = "", secrets: str = ""):
        monkeypatch.setenv("PUSH_RELAY_AUTH_SECRET", secret)
        # KHANDAQ (audit 2026-08-21, K-03): overlapping key epochs, "epoch:secret,epoch:secret".
        monkeypatch.setenv("PUSH_RELAY_AUTH_SECRETS", secrets)
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
    return module.app.test_client().get("/health").get_json()["auth_adoption"]["window_outcomes"]


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
    body = m.app.test_client().get("/health").get_json()
    assert body["auth_mode"] == "misconfigured"
    assert body["auth_required"] is True


def test_health_reports_an_overdue_soft_mode(relay, monkeypatch):
    monkeypatch.setenv("PUSH_AUTH_ENFORCE_BY", "2020-01-01")
    m = relay(enforce="0")
    body = m.app.test_client().get("/health").get_json()
    assert body["enforce_by"] == "2020-01-01"
    assert body["enforce_overdue"] is True


def test_an_enforcing_relay_is_never_overdue(relay, monkeypatch):
    monkeypatch.setenv("PUSH_AUTH_ENFORCE_BY", "2020-01-01")
    m = relay(enforce="1")
    assert m.app.test_client().get("/health").get_json()["enforce_overdue"] is False


def test_a_future_cutoff_is_not_overdue(relay, monkeypatch):
    monkeypatch.setenv("PUSH_AUTH_ENFORCE_BY", "2099-01-01")
    m = relay(enforce="0")
    assert m.app.test_client().get("/health").get_json()["enforce_overdue"] is False


def test_a_malformed_cutoff_is_ignored_rather_than_crashing(relay, monkeypatch):
    monkeypatch.setenv("PUSH_AUTH_ENFORCE_BY", "next tuesday")
    m = relay(enforce="0")
    body = m.app.test_client().get("/health").get_json()
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
    adoption = m.app.test_client().get("/health").get_json()["auth_adoption"]
    assert adoption["by_epoch"] == {"2": 1, "3": 2}
    assert adoption["window_signed"] == 3
    assert adoption["epochs_configured"] == ["2", "3"]


def test_health_never_discloses_a_configured_secret(relay):
    import json as _json

    m = relay(secret=SECRET, secrets=f"2:{EPOCH_A},3:{EPOCH_B}")
    body = _json.dumps(m.app.test_client().get("/health").get_json())
    for leaked in (SECRET, EPOCH_A, EPOCH_B):
        assert leaked not in body, "/health must expose epoch LABELS, never the keys"
    assert '"auth_epochs": 3' in body or m.app.test_client().get("/health").get_json()["auth_epochs"] == 3


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
    assert m.app.test_client().get("/health").get_json()["auth_adoption"]["epochs_configured"] == ["3"]


def test_no_secret_in_either_variable_still_fails_closed(relay):
    m = relay(secret="", secrets="")
    assert m.app.test_client().get(f"/toxfcm/fcm.php?id={TOKEN}").status_code == 401
    assert m.app.test_client().get("/health").get_json()["auth_mode"] == "misconfigured"


def test_a_secret_containing_a_colon_survives_parsing(relay):
    """Split on the FIRST colon only — a secret is opaque and may contain anything."""
    weird = "aa:bb:cc"
    m = relay(enforce="1", secret="", secrets=f"7:{weird}")
    assert _signed(m.app.test_client(), weird).status_code == 200
