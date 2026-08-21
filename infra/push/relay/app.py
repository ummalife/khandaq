#!/usr/bin/env python3
"""Khandaq push wake relay — FCM HTTP v1, tox.zoff.xyz compatible API."""
from __future__ import annotations

import hashlib
import hmac
import ipaddress
import logging
import os
import sqlite3
import time
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
    """
    True when `addr` is a hop whose X-Real-IP we are willing to believe.

    KHANDAQ (audit 2026-08-20): this used to return `ip.is_loopback` alone, and that silently
    disabled per-IP rate limiting in production.

    nginx runs ON THE HOST (scripts/deploy-push-relay.sh writes /etc/nginx/sites-available and
    reloads systemd nginx) and proxies to 127.0.0.1:8088, which docker-compose publishes as
    `127.0.0.1:8088:8080` on an ordinary bridge network. The address the CONTAINER sees for that
    connection is therefore the bridge gateway — 172.x.0.1 — never loopback. With only loopback
    trusted, `_client_ip()` fell through to that one gateway address for every request on earth,
    `_rate_ok` keyed every caller into a single bucket, and PUSH_RATE_LIMIT_PER_MIN stopped being
    "per client" and became a global ceiling of ~120/min/worker for the entire service. One host
    flooding the endpoint takes push notifications away from everybody, and the Android client turns
    a transient 429 into a two-hour local block per push URL (HelperFriend.java), so the outage far
    outlives the flood.

    Trusting private/link-local peers is safe HERE and is not a licence to expose the port: the
    published port is bound to 127.0.0.1 (infra/push/docker-compose.yml), so no host off the machine
    can be a direct peer of this socket at all. If that binding is ever widened, pin the exact hop
    with PUSH_TRUSTED_PROXIES instead of relying on this default.
    """
    if not addr:
        return False
    if addr in TRUSTED_PROXIES:
        return True
    try:
        ip = ipaddress.ip_address(addr)
    except ValueError:
        return False
    return ip.is_loopback or ip.is_private or ip.is_link_local
_token_cache: dict[str, object] = {"token": None, "exp": 0.0}

# ---------------------------------------------------------------------------
# KHANDAQ: the /stats usage dashboard AND its data collection were removed to cut attack surface — the
# relay no longer persists device hashes, countries, or "online" state, and there is no STATS_KEY-gated
# page. Only the wake-coalesce table survives (pushsent: last actual FCM send per token hash), which is
# purely operational (dedupe redundant wake banners) and exposes no endpoint.
# ---------------------------------------------------------------------------
COALESCE_DB = os.environ.get("STATS_DB", "/data/stats.db")

_stats_lock = Lock()


def _stats_conn():
    os.makedirs(os.path.dirname(COALESCE_DB), exist_ok=True)
    conn = sqlite3.connect(COALESCE_DB, timeout=5)
    conn.execute("PRAGMA journal_mode=WAL")
    # last ACTUAL FCM send per token hash — used to coalesce wake-push spam (see wake()).
    conn.execute("CREATE TABLE IF NOT EXISTS pushsent (th TEXT PRIMARY KEY, ts INTEGER)")
    # KHANDAQ (audit2 #10): consumed auth signatures, shared by ALL gunicorn workers (see
    # _claim_signature). Same container filesystem -> one store for the whole -w N process pool.
    conn.execute("CREATE TABLE IF NOT EXISTS usedsig (sig TEXT PRIMARY KEY, ts INTEGER)")
    # KHANDAQ (audit2 #4): client-signing adoption, per UTC day. The rollout plan is "ship signing
    # clients -> confirm coverage -> enforce", but the only evidence we had for the middle step was a
    # log line per unsigned request, i.e. the operator had to grep for a number that decides whether
    # turning on enforcement will silence real users. Count it instead. No token, no IP, no request
    # detail is stored - two integers per day, which is all the decision needs.
    conn.execute("CREATE TABLE IF NOT EXISTS authadopt ("
                 "day TEXT PRIMARY KEY, signed INTEGER NOT NULL DEFAULT 0, "
                 "unsigned INTEGER NOT NULL DEFAULT 0)")
    # KHANDAQ (audit 2026-08-21, K-09): the per-IP rate window, shared by every gunicorn worker.
    # One row per currently-active client IP, holding the request timestamps inside the 60s window.
    conn.execute("CREATE TABLE IF NOT EXISTS ratelimit ("
                 "ip TEXT PRIMARY KEY, last REAL NOT NULL, hits TEXT NOT NULL)")
    return conn


# KHANDAQ (tester): the client watchdog re-triggers a wake push every few seconds for an undelivered
# item, so the recipient saw a stream of "New message" banners with nothing new in the app. Coalesce:
# actually deliver an FCM wake at most once per PUSH_COALESCE_SECONDS per recipient token. The app
# fetches ALL pending messages when it wakes, so suppressed wakes lose no messages — only redundant
# banners. Last-SENT time is tracked separately from `recent` (which every request refreshes for the
# online-now stat), so a continuous spam still re-wakes the device once per window, not never.
COALESCE_SECONDS = int(os.environ.get("PUSH_COALESCE_SECONDS", "45"))


def _th(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()[:32]


def _should_coalesce(token: str) -> bool:
    if COALESCE_SECONDS <= 0:
        return False
    try:
        with _stats_lock:
            conn = _stats_conn()
            try:
                row = conn.execute("SELECT ts FROM pushsent WHERE th = ?", (_th(token),)).fetchone()
            finally:
                conn.close()
        return bool(row) and (int(time.time()) - row[0] < COALESCE_SECONDS)
    except Exception:
        return False


def _mark_push_sent(token: str) -> None:
    try:
        now = int(time.time())
        with _stats_lock:
            conn = _stats_conn()
            try:
                conn.execute(
                    "INSERT INTO pushsent VALUES (?, ?) "
                    "ON CONFLICT(th) DO UPDATE SET ts = excluded.ts", (_th(token), now))
                conn.execute("DELETE FROM pushsent WHERE ts < ?", (now - 86400,))
                conn.commit()
            finally:
                conn.close()
    except Exception:
        pass


# KHANDAQ (audit A6): atomically reserve the coalesce window for `token` in ONE SQLite transaction,
# BEFORE the network send, so two concurrent requests (incl. across gunicorn -w2 processes — the
# threading.Lock alone can't serialize processes; BEGIN IMMEDIATE takes a DB-level RESERVED lock that
# does) can't both pass the check and double-send. Returns True if THIS caller won the slot.
def _claim_coalesce_slot(token: str) -> bool:
    if COALESCE_SECONDS <= 0:
        return True
    now = int(time.time())
    try:
        with _stats_lock:
            conn = _stats_conn()
            try:
                conn.execute("BEGIN IMMEDIATE")
                row = conn.execute("SELECT ts FROM pushsent WHERE th = ?", (_th(token),)).fetchone()
                if row and now - row[0] < COALESCE_SECONDS:
                    conn.commit()
                    return False
                conn.execute(
                    "INSERT INTO pushsent VALUES (?, ?) "
                    "ON CONFLICT(th) DO UPDATE SET ts = excluded.ts", (_th(token), now))
                conn.execute("DELETE FROM pushsent WHERE ts < ?", (now - 86400,))
                conn.commit()
                return True
            finally:
                conn.close()
    except Exception:
        # DB busy/error → fail toward delivery (never drop a wake); worst case is the old behaviour.
        return True


# KHANDAQ (audit A6): release a claimed slot when the send FAILED, so the immediate retry isn't
# swallowed by coalescing (the claim marks "sent" before the network call).
def _release_coalesce_slot(token: str) -> None:
    try:
        with _stats_lock:
            conn = _stats_conn()
            try:
                conn.execute("DELETE FROM pushsent WHERE th = ?", (_th(token),))
                conn.commit()
            finally:
                conn.close()
    except Exception:
        pass


# KHANDAQ: the /stats dashboard + its data collection were removed to reduce attack surface. Only the
# push-wake path and its coalesce table (pushsent) remain.


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
    """
    True if this request fits inside `client_ip`'s 60-second budget.

    KHANDAQ (audit 2026-08-21, K-09): the window used to live in a module-level dict guarded by a
    threading.Lock. The container runs `gunicorn -w 2`, so there were two of them: a single source
    got up to 2 x PUSH_RATE_LIMIT_PER_MIN, and every bucket reset on reload. The replay store and the
    coalesce slot had already been moved into the shared SQLite file for exactly this reason; this
    one had not, which made the configured number mean something other than what it says.

    Same file, same discipline: BEGIN IMMEDIATE takes a DB-level RESERVED lock, which serialises
    across PROCESSES — a threading.Lock cannot. Measured on four concurrent processes sharing one DB
    with a limit of 50: 200 requests accepted before this change, exactly 50 after.

    Deliberately a timestamp log rather than a fixed-window counter, which would be shorter but lets
    a caller spend the whole budget at t=59s and again at t=61s — 2x across the boundary, i.e. the
    very thing being fixed. Keeping the log preserves the exact semantics of the old in-process
    version, so no existing test quietly changes meaning. Growth is bounded on both axes: rows by the
    120s prune (only currently-active IPs survive), row width by RATE_LIMIT_PER_MIN entries.

    FAIL OPEN on a broken store — and note this is the opposite of _claim_signature, on purpose.
    A dead replay store failing closed costs nothing. A dead rate store failing closed turns EVERY
    wake into a 429, and the Android client converts a 429 into an escalating per-push-URL backoff
    toward a two-hour ceiling (PushBackoffPolicy / HelperFriend), so an unwritable /data would become
    a fleet-wide notification outage lasting far longer than the disk problem. nginx also carries a
    stricter shared limit (30r/m burst=10) in front of this, so failing open here loses defence in
    depth, not the defence. _claim_coalesce_slot fails open for the same reason.
    """
    now = time.time()
    try:
        with _stats_lock:
            conn = _stats_conn()
            try:
                conn.execute("BEGIN IMMEDIATE")
                conn.execute("DELETE FROM ratelimit WHERE last < ?", (now - 120,))
                row = conn.execute("SELECT hits FROM ratelimit WHERE ip = ?", (client_ip,)).fetchone()
                window = ([t for t in (float(x) for x in row[0].split(",") if x) if now - t < 60]
                          if row else [])
                allowed = len(window) < RATE_LIMIT_PER_MIN
                if allowed:
                    window.append(now)
                    conn.execute(
                        "INSERT INTO ratelimit (ip, last, hits) VALUES (?, ?, ?) "
                        "ON CONFLICT(ip) DO UPDATE SET last = excluded.last, hits = excluded.hits",
                        (client_ip, now, ",".join("%.3f" % t for t in window)))
                conn.commit()
                return allowed
            finally:
                conn.close()
    except Exception:
        log.exception("push rate limit: store UNAVAILABLE -> allowing the request (FAIL-OPEN); "
                      "nginx limit_req is still in front. Check that %s is writable", COALESCE_DB)
        return True


def _record_auth_adoption(signed: bool) -> None:
    """
    KHANDAQ (audit2 #4): tally signed vs unsigned wake requests for the enforcement decision.

    Never let this affect the request. It is a counter for an operator, not a security control, so
    unlike _claim_signature it fails OPEN and silently — a broken counter must not start dropping
    pushes, and it must not spam the log on every request either.
    """
    column = "signed" if signed else "unsigned"
    try:
        day = time.strftime("%Y-%m-%d", time.gmtime())
        with _stats_lock:
            conn = _stats_conn()
            try:
                conn.execute("INSERT OR IGNORE INTO authadopt (day) VALUES (?)", (day,))
                conn.execute(f"UPDATE authadopt SET {column} = {column} + 1 WHERE day = ?", (day,))
                # 60 days is plenty to watch a store rollout land, and keeps the table trivial.
                conn.execute("DELETE FROM authadopt WHERE day < ?",
                             (time.strftime("%Y-%m-%d", time.gmtime(time.time() - 60 * 86400)),))
                conn.commit()
            finally:
                conn.close()
    except Exception:
        pass


def _auth_adoption_summary() -> dict:
    """Today's and the trailing window's signed/unsigned counts, for /health."""
    try:
        day = time.strftime("%Y-%m-%d", time.gmtime())
        with _stats_lock:
            conn = _stats_conn()
            try:
                today = conn.execute("SELECT signed, unsigned FROM authadopt WHERE day = ?",
                                     (day,)).fetchone() or (0, 0)
                window = conn.execute("SELECT COALESCE(SUM(signed), 0), COALESCE(SUM(unsigned), 0) "
                                      "FROM authadopt").fetchone() or (0, 0)
            finally:
                conn.close()
        total = window[0] + window[1]
        return {
            "today_signed": today[0],
            "today_unsigned": today[1],
            "window_signed": window[0],
            "window_unsigned": window[1],
            # The number the enforcement decision actually turns on. null while there is no traffic,
            # so an idle relay cannot read as "100% adopted".
            "window_signed_pct": (round(window[0] * 100.0 / total, 2) if total else None),
        }
    except Exception:
        return {"error": "unavailable"}


def _auth_ok(token: str, sender: str, *, allow_query_auth: bool = True) -> bool:
    # KHANDAQ (audit A1): the relay must never run wide-open. docker-compose makes
    # PUSH_RELAY_AUTH_SECRET mandatory (:? -> the container refuses to start without it), so an
    # empty secret here is a misconfiguration, not "auth off" -> fail CLOSED instead of accepting
    # every request.
    if not PUSH_RELAY_AUTH_SECRET:
        log.error("push auth: PUSH_RELAY_AUTH_SECRET is empty -> rejecting (set it in the relay .env)")
        return False

    if _auth_signature_valid(token, sender, allow_query_auth=allow_query_auth):
        _record_auth_adoption(True)
        return True

    # Secret set, but the request is unsigned/invalid.
    _record_auth_adoption(False)
    if PUSH_AUTH_ENFORCE:
        return False  # hard enforce -> caller returns 401

    # Soft/monitor mode (TRANSITIONAL): allow the unsigned request through but record it, so
    # client-signing adoption can be measured from these logs. Old clients built before the secret
    # was provisioned do not sign yet; enforcing now would drop their pushes. Once the "SOFT" line
    # count falls to ~0 (i.e. shipped clients sign), set PUSH_AUTH_ENFORCE=1 to close the window.
    log.warning("push auth SOFT: unsigned/invalid request allowed from %s (set PUSH_AUTH_ENFORCE=1 after client adoption)", _client_ip())
    return True


# KHANDAQ (audit A2): single-use signature cache — an accepted ts-bound HMAC is consumed so it cannot
# be replayed inside the AUTH_MAX_SKEW_SEC window.
# KHANDAQ (audit2 #10): this used to be an in-process dict, but the container runs gunicorn -w 2, so a
# captured signature was accepted once PER WORKER. Back it with the SQLite file the relay already uses
# (same container FS => shared by every worker): INSERT OR IGNORE on a PRIMARY KEY is atomic, so
# exactly one caller — in one worker — can claim a given signature. Retention is bounded by the
# freshness window: anything older than 2x the skew can no longer pass the ts check, so it is purged.
_SIG_RETENTION_SEC = max(AUTH_MAX_SKEW_SEC, 60) * 2


def _claim_signature(sig: str) -> bool:
    """True if THIS request is the first to present `sig` (i.e. it is not a replay)."""
    now = int(time.time())
    try:
        with _stats_lock:
            conn = _stats_conn()
            try:
                conn.execute("BEGIN IMMEDIATE")
                conn.execute("DELETE FROM usedsig WHERE ts < ?", (now - _SIG_RETENTION_SEC,))
                cur = conn.execute("INSERT OR IGNORE INTO usedsig VALUES (?, ?)", (sig, now))
                claimed = cur.rowcount == 1  # 0 rows => the signature was already consumed
                conn.commit()
                return claimed
            finally:
                conn.close()
    except Exception:
        # KHANDAQ (audit3 #3): FAIL CLOSED. This is a security control (single-use signatures), not a
        # best-effort cache like the coalesce slot: swallowing the error and returning True silently
        # disabled replay protection for as long as the store stayed broken, with nothing in the log.
        # Refuse the signature and shout, so a read-only/locked/corrupt DB is visible immediately.
        #
        # Blast radius, traced against the CURRENT rollout state (PUSH_AUTH_ENFORCE unset => SOFT):
        #   * Unsigned requests — i.e. every field client today, since clients do not sign yet —
        #     never reach this function at all: _auth_signature_valid() returns False at the
        #     `not supplied or not ts` guard above, before any DB access. Unaffected.
        #   * A SIGNED request that hits a DB error now gets False here, so _auth_signature_valid()
        #     returns False, and _auth_ok() falls into the soft branch: it logs "push auth SOFT" and
        #     returns True. The wake is STILL DELIVERED. In soft mode this change therefore cannot
        #     reject any wake traffic — it can only add a SOFT line (which slightly inflates the
        #     adoption counter; the log.exception below disambiguates "DB broken" from "old client").
        #   * Only once PUSH_AUTH_ENFORCE=1 does a broken store turn into 401s. That is the intended
        #     meaning of fail-closed, and the operator has this loud line saying exactly why.
        log.exception(
            "push auth: replay store UNAVAILABLE -> refusing signature (FAIL-CLOSED); "
            "check that %s exists and is writable by the relay container", COALESCE_DB)
        return False


def _auth_signature_valid(token: str, sender: str, *, allow_query_auth: bool = True) -> bool:
    # KHANDAQ (security NEW-2): replay-resistant, request-bound auth.
    # The old scheme signed a CONSTANT (HMAC(secret, "khandaq-push-relay")) → the same value
    # forever, baked into every build, replayable without limit. Instead the sender signs the
    # actual request (recipient token `id` + sender `from` + a unix timestamp `ts`), and we
    # accept it only inside a small freshness window. The HMAC is per-request and time-bound, so
    # even if it leaks (e.g. nginx logs) it is useless after AUTH_MAX_SKEW_SEC.
    #   ts   = unix seconds (integer string)
    #   msg  = id + "\n" + from + "\n" + ts   (raw, URL-decoded values, UTF-8)
    #   auth = lowercase hex HMAC-SHA256(secret, msg)
    # `auth` may come via "Authorization: Bearer" or — legacy endpoint only — ?auth=; `ts` via
    # X-Khandaq-Ts or, again legacy only, ?ts=.
    #
    # KHANDAQ (audit: credentials in URLs): `token` and `sender` are now PARAMETERS rather than
    # something read straight out of `request.args`, so the same pre-image can be signed over values
    # that arrived in a JSON body. The pre-image itself is unchanged, deliberately: four
    # implementations (relay, Android, iOS, desktop) agree on it byte for byte, and a transport
    # change is not a reason to make them re-agree. `allow_query_auth=False` on the JSON endpoint
    # refuses credentials in the query string outright, so the new path cannot quietly keep the old
    # habit alive.
    supplied = ""
    if allow_query_auth:
        supplied = request.args.get("auth", "").strip()
    if not supplied:
        auth_header = request.headers.get("Authorization", "")
        if auth_header.lower().startswith("bearer "):
            supplied = auth_header[7:].strip()

    ts = ""
    if allow_query_auth:
        # Same precedence as before this refactor: query first, header as the fallback.
        ts = request.args.get("ts", "").strip()
    if not ts:
        ts = request.headers.get("X-Khandaq-Ts", "").strip()
    if not supplied or not ts:
        return False

    try:
        ts_int = int(ts)
    except ValueError:
        return False
    if abs(time.time() - ts_int) > AUTH_MAX_SKEW_SEC:
        return False

    msg = (token + "\n" + sender + "\n" + ts).encode("utf-8")
    expected = hmac.new(PUSH_RELAY_AUTH_SECRET.encode("utf-8"), msg, hashlib.sha256).hexdigest()
    if not hmac.compare_digest(supplied, expected):
        return False
    # KHANDAQ (audit A2): consume the signature so a captured signed URL can't be replayed to 200
    # repeatedly within the freshness window. Each valid signature is accepted exactly once —
    # KHANDAQ (audit2 #10): across ALL gunicorn workers, not just the one that served the original.
    return _claim_signature(expected)


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
                # KHANDAQ (#163): a present apns.payload OVERRIDES message.notification for iOS, so
                # the old {"content-available": 1}-only aps turned the push into a SILENT one — and
                # iOS never delivers silent pushes to a force-quit (swiped-away) app. Build a full
                # alert push here: alert → shown even after swipe-kill, mutable-content → the
                # NotificationServiceExtension runs, content-available → a backgrounded app is woken.
                "headers": {"apns-priority": "10", "apns-push-type": "alert"},
                "payload": {"aps": {
                    "alert": {
                        "title": os.environ.get("PUSH_NOTIFY_TITLE", "Khandaq"),
                        "body": os.environ.get("PUSH_NOTIFY_BODY", "New message"),
                    },
                    "sound": "default",
                    "mutable-content": 1,
                    "content-available": 1,
                }},
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
            # surface FCM's errorCode (e.g. THIRD_PARTY_AUTH_ERROR = broken/missing APNs key in
            # the Firebase project) — the generic 401 text alone points at the WRONG credential.
            err_code = ""
            try:
                for d in resp.json().get("error", {}).get("details", []):
                    if "errorCode" in d:
                        err_code = d["errorCode"]
                        break
            except Exception:
                pass
            return False, f"FCM v1 HTTP {resp.status_code} [{err_code}]: {resp.text[:200]}"
        log.info("wake ok: token %s…", token[:12])
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
        # KHANDAQ (audit2 #4): the number the "when do we set PUSH_AUTH_ENFORCE=1" decision turns on.
        # Enforcing while shipped clients still send unsigned requests silences their notifications,
        # so this must be read before flipping it — not guessed from log volume.
        "auth_adoption": _auth_adoption_summary(),
    })


def _redact_token(detail: str, token: str) -> str:
    """Remove the device token from an error string before it is logged or returned."""
    if not token or not detail:
        return detail
    return detail.replace(token, "[REDACTED]")


def _deliver_wake(token_raw: str, sender_raw: str, *, allow_query_auth: bool):
    """
    The wake path, shared by the legacy query-string endpoint and the JSON one.

    `token_raw`/`sender_raw` are the values EXACTLY as they arrived, because that is what the client
    signed; the stripping/normalising below happens after authentication, never before it.
    """
    # KHANDAQ (audit 2026-08-21, K-09): rate-limit BEFORE authenticating. The old order had two
    # concrete costs, both invisible from the outside:
    #
    #   * _auth_signature_valid CONSUMES the signature (_claim_signature) before the rate check ran.
    #     A signed request that then got 429 had already burned its single-use signature, so the
    #     client's retry with the same signature reads as a replay — under enforcement that is a 401
    #     for a caller who did nothing wrong.
    #   * _auth_ok writes to SQLite on every request via _record_auth_adoption, so a flood that was
    #     about to be refused still cost a write each and still inflated the `unsigned` counter that
    #     the PUSH_AUTH_ENFORCE=1 decision is read from — the flood, not the fleet, moved the number.
    #
    # The limiter keys on the client IP either way, so nothing is lost by checking it first.
    client_ip = _client_ip()
    if not _rate_ok(client_ip):
        return jsonify({"error": "rate limit"}), 429

    if not _auth_ok(token_raw, sender_raw, allow_query_auth=allow_query_auth):
        return jsonify({"error": "unauthorized"}), 401

    token = token_raw.strip()
    if not token or len(token) < 10 or len(token) > 4096:
        return jsonify({"error": "invalid token"}), 400

    sender_pubkey = sender_raw.strip().upper()
    if sender_pubkey and (len(sender_pubkey) != 64 or not all(c in "0123456789ABCDEF" for c in sender_pubkey)):
        sender_pubkey = ""

    # Coalesce wake-push spam: if we already delivered a wake to this token within the window, skip
    # the FCM send (no redundant "New message" banner) but keep the stats/online-now accurate and
    # report success so the caller stops retrying this cycle. Messages still arrive on the prior wake.
    # KHANDAQ (audit A6): claim the coalesce slot ATOMICALLY before sending (no check-then-send race).
    if not _claim_coalesce_slot(token):
        return jsonify({"success": 1, "coalesced": 1}), 200

    ok, detail = _send_wake(token, sender_pubkey)
    if not ok:
        _release_coalesce_slot(token)  # send failed → let the retry through
        # KHANDAQ (audit: keep token redaction in ALL error paths). `detail` can carry up to 200
        # bytes of FCM's own response text, which is then both logged and echoed back to the caller.
        # FCM does not normally quote the registration token back, but "normally" is not a property
        # worth betting a targeting secret on, and the redaction costs one string replace.
        detail = _redact_token(detail, token)
        log.warning("wake fail from %s: %s", client_ip, detail)
        return jsonify({"error": detail}), 503 if "not configured" in detail else 502

    return jsonify({"success": 1}), 200


@app.route("/toxfcm/fcm.php", methods=["GET", "POST"])
def wake():
    # DEPRECATED (audit: credentials in URLs). Kept because clients in the field speak only this,
    # and taking it away would silence their notifications. It gains no new functionality: anything
    # new belongs on /wake below.
    return _deliver_wake(request.args.get("id", ""), request.args.get("from", ""),
                         allow_query_auth=True)


@app.route("/wake", methods=["POST"])
def wake_json():
    """
    KHANDAQ (audit: push credentials in query parameters).

    The FCM registration token is a targeting secret — whoever holds it can push to that device —
    and it used to travel in the URL, alongside the HMAC and its timestamp. nginx redacts the query
    string here (see nginx-push.conf), but a URL leaks through more than one log: client-side
    diagnostics, crash reporters, proxies, Referer, browser history, an intermediary that logs
    before the redaction ever applies. A request BODY leaks through none of those by default.

    So: token and sender in a JSON body, authentication in the Authorization header, timestamp in
    X-Khandaq-Ts, and query-string credentials refused outright on this endpoint. The HMAC pre-image
    is unchanged (`token \\n sender \\n ts`), so a client moving to this endpoint changes how it
    sends the request, not how it signs it.

      POST /wake
      Authorization: Bearer <hex hmac-sha256>
      X-Khandaq-Ts: <unix seconds>
      Content-Type: application/json

      {"token": "<fcm registration token>", "sender": "<64-hex tox pubkey, optional>"}

    Neither the body nor the Authorization header is ever logged.
    """
    body = request.get_json(silent=True)
    if not isinstance(body, dict):
        return jsonify({"error": "expected a json object body"}), 400

    token = body.get("token")
    sender = body.get("sender", "")
    # Types are checked rather than coerced: str(...) of a dict or a list would produce a "token"
    # that is neither what the client signed nor anything FCM can use, and would then fail deep in
    # the send path instead of here.
    if not isinstance(token, str) or (sender is not None and not isinstance(sender, str)):
        return jsonify({"error": "token and sender must be strings"}), 400

    return _deliver_wake(token, sender or "", allow_query_auth=False)


# KHANDAQ: /stats + /stats.json dashboard removed (attack-surface reduction). Only the wake +
# coalesce path remains below.


@app.route("/")
def root():
    return jsonify({
        "service": "khandaq-push-relay",
        "endpoints": [
            "POST /wake  (json body + Authorization header)",
            "/toxfcm/fcm.php?id=<fcm_token>&type=1  (deprecated: credentials in the URL)",
        ],
        "privacy": "wake-only, no message content; optional sender public key via &from=",
    })


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=int(os.environ.get("PORT", "8080")))
