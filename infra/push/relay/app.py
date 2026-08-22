#!/usr/bin/env python3
"""Khandaq push wake relay — FCM HTTP v1, tox.zoff.xyz compatible API."""
from __future__ import annotations

import base64
import hashlib
import hmac
import ipaddress
import logging
import os
import re
import secrets
import sqlite3
import time
from threading import Lock

import requests
from flask import Flask, g, jsonify, request

app = Flask(__name__)
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("khandaq-push")

FCM_PROJECT_ID = os.environ.get("FCM_PROJECT_ID", "khandaq-messenger")
FCM_SERVICE_ACCOUNT_FILE = os.environ.get("FCM_SERVICE_ACCOUNT_FILE", "")
FCM_SERVER_KEY = os.environ.get("FCM_SERVER_KEY", "")  # legacy fallback
RATE_LIMIT_PER_MIN = int(os.environ.get("PUSH_RATE_LIMIT_PER_MIN", "120"))
PUSH_RELAY_AUTH_SECRET = os.environ.get("PUSH_RELAY_AUTH_SECRET", "")
# KHANDAQ (audit 2026-08-21, K-03): overlapping key epochs, so rotating the shared secret no longer
# requires a window in which the relay accepts unsigned traffic.
#
# The finding is that the HMAC secret is baked into publicly downloadable binaries and is therefore
# extractable — true, acknowledged in PUSH-AUTH-SECRET-PROVISIONING.md, and NOT fixed by anything
# here: per-install capabilities are the actual answer and are a protocol project, scoped in
# DESIGN-push-per-install-capabilities.md. What IS fixed is the operational trap sitting on top of
# it: with exactly one secret configured, rotating meant every shipped client stopped verifying at
# once, so the documented procedure was to drop back to soft mode — i.e. to accept unsigned requests
# from anyone — for the length of a store rollout. A leaked key was therefore expensive to replace
# precisely when replacing it mattered most.
#
# Format: PUSH_RELAY_AUTH_SECRETS="3:<secret>,2:<secret>" — comma-separated `epoch:secret`, split on
# the FIRST colon. The client is unchanged and unaware: it still sends one hex HMAC, and the relay
# tries each configured key. Rotation becomes: add the new epoch beside the old, ship clients on it,
# watch /health -> auth_adoption.by_epoch until the old epoch reaches zero, delete the old entry.
PUSH_RELAY_AUTH_SECRETS_RAW = os.environ.get("PUSH_RELAY_AUTH_SECRETS", "")
PUSH_RELAY_AUTH_SECRET_EPOCH = os.environ.get("PUSH_RELAY_AUTH_SECRET_EPOCH", "1").strip() or "1"
_EPOCH_LABEL = re.compile(r"^[A-Za-z0-9_-]{1,16}$")


def _parse_auth_secrets() -> list:
    """
    [(epoch, secret)] from PUSH_RELAY_AUTH_SECRETS, with the legacy single variable folded in.

    Malformed entries are skipped with a warning rather than being fatal. A typo in one entry of a
    rotation list must not take authentication down for every client at once — the failure mode this
    whole change exists to avoid.
    """
    out, seen = [], set()
    for entry in PUSH_RELAY_AUTH_SECRETS_RAW.split(","):
        entry = entry.strip()
        if not entry:
            continue
        epoch, sep, secret = entry.partition(":")
        epoch, secret = epoch.strip(), secret.strip()
        if not sep or not secret:
            log.warning("push auth: ignoring malformed PUSH_RELAY_AUTH_SECRETS entry "
                        "(expected 'epoch:secret')")
            continue
        if not _EPOCH_LABEL.match(epoch):
            log.warning("push auth: ignoring PUSH_RELAY_AUTH_SECRETS entry with an invalid epoch "
                        "label (allowed: 1-16 chars of [A-Za-z0-9_-])")
            continue
        if epoch in seen:
            log.warning("push auth: PUSH_RELAY_AUTH_SECRETS lists epoch %s more than once; "
                        "the last one wins", epoch)
            out = [(e, s) for e, s in out if e != epoch]
        seen.add(epoch)
        out.append((epoch, secret))
    if PUSH_RELAY_AUTH_SECRET and PUSH_RELAY_AUTH_SECRET_EPOCH not in seen:
        out.append((PUSH_RELAY_AUTH_SECRET_EPOCH, PUSH_RELAY_AUTH_SECRET))
    return out


AUTH_SECRETS = _parse_auth_secrets()


def _set_auth_epoch(epoch: str) -> None:
    """
    Remember which key epoch verified THIS request, for the rotation counter.

    Deliberately request-scoped (flask.g) rather than a module global: gunicorn serves concurrently,
    and a global would let two requests overwrite each other's epoch — silently, under exactly the
    concurrency the counter exists to measure. Outside a request context (unit tests calling the
    helper directly) it is simply a no-op.
    """
    try:
        g.push_auth_epoch = epoch
    except RuntimeError:
        pass


def _current_auth_epoch():
    try:
        return getattr(g, "push_auth_epoch", None)
    except RuntimeError:
        return None
# Max allowed clock skew (seconds) for the request timestamp. Bounds the replay window.
AUTH_MAX_SKEW_SEC = int(os.environ.get("PUSH_AUTH_MAX_SKEW_SEC", "300"))
# Rollout gate. With a secret set but ENFORCE off (default), the relay runs in SOFT/MONITOR
# mode: it still serves unsigned/invalid requests (so already-installed clients that lack the
# secret keep working) but LOGS each one, so you can watch adoption. Flip to 1 (hard enforce,
# 401 on bad auth) only once the logs show field clients are signing. This avoids a hard cutover
# that would break every client that hasn't yet shipped + been updated with the secret.
# KHANDAQ (audit round 3, F-13): an unrecognised value must not mean "soft".
#
# This was `... in ("1", "true", "yes", "on")`, so PUSH_AUTH_ENFORCE=enforce — or any typo — selected
# the WEAKER mode silently. The neighbouring PUSH_AUTH_ENFORCE_BY already validates its input and
# logs an error on a bad one; the security-relevant boolean deserves at least that, and gets more:
# a value nobody recognises stops the container instead of quietly serving unauthenticated wakes.
# Failing to start is loud, local, and happens before any traffic; guessing is none of those.
_ENFORCE_TRUE = ("1", "true", "yes", "on")
_ENFORCE_FALSE = ("0", "false", "no", "off")
_enforce_raw = os.environ.get("PUSH_AUTH_ENFORCE", "0").strip().lower()
if _enforce_raw not in _ENFORCE_TRUE + _ENFORCE_FALSE:
    raise SystemExit(
        "PUSH_AUTH_ENFORCE=%r is not a recognised value. Use one of %s to enforce, or one of %s for "
        "soft/monitor mode. Refusing to start rather than guessing the weaker one."
        % (os.environ.get("PUSH_AUTH_ENFORCE", ""), ", ".join(_ENFORCE_TRUE), ", ".join(_ENFORCE_FALSE)))
PUSH_AUTH_ENFORCE = _enforce_raw in _ENFORCE_TRUE
# KHANDAQ (audit 2026-08-21, K-02): "Treat soft mode as an emergency compatibility mode with an
# expiry date, not a permanent default." An optional YYYY-MM-DD cutoff (unset = none). Past it, a
# relay still running in soft mode says so loudly at startup and reports it on /health.
#
# KHANDAQ (re-audit 2026-08-21): this used to say the monitoring "already in infra/monitoring" could
# alert on it. That was wishful. infra/monitoring is sixteen lines of Uptime Kuma with no checks in
# it at all — its rules live in that container's own database, not in this repository. So the signal
# is published and nothing is watching it yet. Said plainly, because a comment that claims coverage
# which does not exist is worse than one that admits the gap.
#
# It nags; it does not self-enforce. A relay that flipped itself to hard enforcement on a date would
# silence every unsigned client in the field, unattended, at a moment nobody chose — the exact
# outage soft mode exists to prevent. The decision stays with a human; only the forgetting is fixed.
PUSH_AUTH_ENFORCE_BY = os.environ.get("PUSH_AUTH_ENFORCE_BY", "").strip()

# KHANDAQ (re-audit 2026-08-22, K-01/K-02/K-03) — per-install capabilities, and the two operational
# gates around them.
#
# K-01 says the shared HMAC is extractable from any published binary, which is true and is not fixed
# by rotating it: every installation holds the same credential, so anyone who unpacks an APK can
# sign a wake for any device whose targeting token they also hold. The answer is a secret that is
# per (recipient, contact) rather than per fleet — DESIGN-push-per-install-capabilities.md, option
# 3.1 — and this is its relay half.
#
# The design stalled on one question, recorded there and guarded by a test: how does a device prove
# it may register a capability for a token, when the only credential it has is the fleet secret this
# work exists to remove? Anyone who can read a wake URL knows the token, so token-possession alone
# authorises nothing, and a forged registration would silently kill a victim's notifications at the
# step where capabilities become required.
#
# Resolved by asking FCM. The relay mints a nonce and pushes it, data-only, TO THE TOKEN; only the
# device that actually owns that token receives it, and only that device can echo it back. A contact
# who knows the token can start a challenge and cannot finish one. No shared secret is involved in
# the decision, which is exactly what K-01's third requirement asks for.
PUSH_CAP_ENFORCE_RAW = os.environ.get("PUSH_CAP_ENFORCE", "auto").strip().lower()
if PUSH_CAP_ENFORCE_RAW not in ("auto", "off", "always"):
    raise SystemExit(
        "PUSH_CAP_ENFORCE=%r is not recognised. Use 'auto' (require a capability only for tokens "
        "that have registered one — the anti-flag-day default), 'off' (accept but never require), "
        "or 'always' (refuse every wake without one). Refusing to start rather than guessing."
        % os.environ.get("PUSH_CAP_ENFORCE", ""))
# 'auto' is the shape the design argues for: the property starts holding per device, the moment that
# device registers, and a device that has not registered keeps working. There is no fleet-wide
# instant at which notifications can break.
PUSH_CAP_ENFORCE = PUSH_CAP_ENFORCE_RAW

# One row per (recipient, contact). The ceiling is a memory bound, not a security one — a device
# with more contacts than this evicts its least recently used capability, which that contact then
# re-obtains on the next publish.
PUSH_CAP_MAX_PER_TOKEN = max(1, int(os.environ.get("PUSH_CAP_MAX_PER_TOKEN", "512")))
# Unused capabilities are dropped. An FCM token that rotated is a token whose capabilities can never
# be used again, and keeping them forever would turn a privacy-minimal relay into a device registry.
PUSH_CAP_TTL_DAYS = max(1, int(os.environ.get("PUSH_CAP_TTL_DAYS", "180")))
# A challenge is single-use and short-lived: it exists only for the seconds between the device
# asking and the push arriving.
PUSH_CHALLENGE_TTL_SEC = max(30, int(os.environ.get("PUSH_CHALLENGE_TTL_SEC", "300")))
# The gap that would otherwise cost real notifications, and the only one in this design.
#
# A recipient registers a capability and re-publishes its wake URL to every contact over Tox. A
# contact that is offline at that moment still holds the OLD URL, without the capability, and would
# be refused the moment enforcement began — a message the user never sees, which is the one failure
# mode requirement 5 of the design calls unacceptable.
#
# So enforcement for a device starts a fortnight after its FIRST capability is registered, not
# immediately: long enough for contacts to come online and receive the new URL, short enough to be a
# transition rather than a permanent hole. Requests allowed by the grace are counted separately, so
# "is anyone still relying on this?" has an answer before it expires.
PUSH_CAP_GRACE_DAYS = max(0, int(os.environ.get("PUSH_CAP_GRACE_DAYS", "14")))

# K-03: retirement of the legacy GET wake endpoint, as a setting rather than a code change, so the
# date it happens is an operational decision taken on the adoption numbers below it.
#   serve — answer it (today)
#   410   — Gone: the honest answer to a client that should have moved
#   404   — as if it never existed
PUSH_LEGACY_GET = os.environ.get("PUSH_LEGACY_GET", "serve").strip().lower()
if PUSH_LEGACY_GET not in ("serve", "410", "404"):
    raise SystemExit(
        "PUSH_LEGACY_GET=%r is not recognised. Use 'serve', '410' or '404'." % PUSH_LEGACY_GET)

# K-02: soft mode must not be reachable by an ordinary configuration mistake in production. Setting
# PUSH_ENV=production without PUSH_AUTH_ENFORCE=1 now stops the container unless a break-glass reason
# is stated — and the reason is logged, so the bypass leaves a record instead of being a quiet flag.
PUSH_ENV = os.environ.get("PUSH_ENV", "").strip().lower()
PUSH_AUTH_BREAK_GLASS = os.environ.get("PUSH_AUTH_BREAK_GLASS", "").strip()
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
    # KHANDAQ (audit2 #4, widened by audit 2026-08-21 K-02): client-signing adoption, per UTC day.
    # The rollout plan is "ship signing clients -> confirm coverage -> enforce", and the middle step
    # needs a number rather than a log grep.
    #
    # It used to be two columns, signed and unsigned. Two is not enough to make the decision, because
    # the cases that land in `unsigned` do not all mean the same thing and do not all resolve the same
    # way. An old client that predates the secret heals when the fleet turns over. A build shipped
    # with the WRONG secret never heals — it looks identical, and an operator waiting for the
    # percentage to reach 100 waits forever without being told why. A broken replay store depresses
    # the number while nothing is wrong with any client at all. So: one row per (day, outcome), which
    # also means a new outcome never needs an ALTER TABLE.
    #
    # Still no token, no IP, no request detail — a count per outcome per day is all the decision needs.
    conn.execute("CREATE TABLE IF NOT EXISTS authoutcome ("
                 "day TEXT NOT NULL, outcome TEXT NOT NULL, n INTEGER NOT NULL DEFAULT 0, "
                 "PRIMARY KEY (day, outcome))")
    # KHANDAQ (audit 2026-08-21, K-03): which key epoch signed each accepted request. This is what
    # makes a rotation observable: add the new epoch, ship clients, and watch the old epoch's count
    # fall to zero before deleting it. Without it, "have all the clients moved over yet?" has no
    # answer short of retiring the old key and seeing what breaks.
    conn.execute("CREATE TABLE IF NOT EXISTS authepoch ("
                 "day TEXT NOT NULL, epoch TEXT NOT NULL, n INTEGER NOT NULL DEFAULT 0, "
                 "PRIMARY KEY (day, epoch))")
    # KHANDAQ (audit 2026-08-21, K-09): the per-IP rate window, shared by every gunicorn worker.
    # One row per currently-active client IP, holding the request timestamps inside the 60s window.
    conn.execute("CREATE TABLE IF NOT EXISTS ratelimit ("
                 "ip TEXT PRIMARY KEY, last REAL NOT NULL, hits TEXT NOT NULL)")
    # KHANDAQ (re-audit 2026-08-22, K-01): registered capabilities.
    #
    # Keyed by the token HASH, never the token. The relay deliberately does not persist FCM
    # registration tokens, and it does not have to: a wake presents both the token and the
    # capability, so verification is "is sha256(cap) registered against sha256(token)" — a lookup,
    # not a mapping. Storing the token would have made this table a device registry, which is
    # exactly the thing the /stats removal took out.
    conn.execute("CREATE TABLE IF NOT EXISTS pushcap ("
                 "th TEXT NOT NULL, caph TEXT NOT NULL, created INTEGER NOT NULL, "
                 "last_used INTEGER NOT NULL, PRIMARY KEY (th, caph))")
    conn.execute("CREATE INDEX IF NOT EXISTS pushcap_th ON pushcap (th)")
    # Outstanding registration challenges. `nonceh` is a hash for the same reason the signature
    # store keeps hashes: a database read must not yield anything replayable.
    conn.execute("CREATE TABLE IF NOT EXISTS pushchallenge ("
                 "cid TEXT PRIMARY KEY, th TEXT NOT NULL, nonceh TEXT NOT NULL, "
                 "exp INTEGER NOT NULL)")
    # KHANDAQ (re-audit 2026-08-22, K-03): which emission path clients actually use, per day, with
    # no token and no identifier of any kind. "Retire the legacy GET" is a decision that needs this
    # number, and asking the access log was never going to give it — the query string is redacted
    # there precisely because it holds the token.
    conn.execute("CREATE TABLE IF NOT EXISTS pushpath ("
                 "day TEXT NOT NULL, path TEXT NOT NULL, n INTEGER NOT NULL DEFAULT 0, "
                 "PRIMARY KEY (day, path))")
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


def _service_account_usable() -> bool:
    """
    KHANDAQ (production deploy 2026-08-21) — READABLE, not merely present.

    This was `os.path.isfile(...)`, which was true enough while the relay ran as root: root reads
    everything. K-05 moved the container to uid 10001, and the service account on the host is 0600
    root:root, so the file exists and cannot be opened. The old check reported the relay healthy and
    FCM configured while every single send failed with EACCES — including through the deploy script's
    own /health assertion, which would have declared the deploy successful over a total notification
    outage.

    That is the same failure shape K-02 already fixed once for auth_mode: a health field that is
    wrong in the direction that hides an outage. Ask whether the file can actually be OPENED.
    """
    if not FCM_SERVICE_ACCOUNT_FILE or not os.path.isfile(FCM_SERVICE_ACCOUNT_FILE):
        return False
    return os.access(FCM_SERVICE_ACCOUNT_FILE, os.R_OK)


def _fcm_configured() -> bool:
    return _service_account_usable() or bool(FCM_SERVER_KEY)


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


# Every way an incoming request can be classified by the auth check. "ok" is the only success.
# `store_error` is deliberately its own outcome and NOT lumped in with the failures: it says the
# relay's replay store is broken, not that a client is misbehaving, and counting it as "unsigned"
# made a disk problem look like un-adopted clients.
AUTH_OUTCOME_OK = "ok"
AUTH_OUTCOMES = ("ok", "missing", "malformed_ts", "stale", "badmac", "replay", "store_error")


def _record_auth_outcome(outcome: str) -> None:
    """
    KHANDAQ (audit2 #4, widened by K-02): tally the auth outcome for the enforcement decision.

    Never let this affect the request. It is a counter for an operator, not a security control, so
    unlike _claim_signature it fails OPEN and silently — a broken counter must not start dropping
    pushes, and it must not spam the log on every request either.
    """
    try:
        day = time.strftime("%Y-%m-%d", time.gmtime())
        with _stats_lock:
            conn = _stats_conn()
            try:
                conn.execute(
                    "INSERT INTO authoutcome (day, outcome, n) VALUES (?, ?, 1) "
                    "ON CONFLICT(day, outcome) DO UPDATE SET n = n + 1", (day, outcome))
                epoch = _current_auth_epoch()
                if outcome == AUTH_OUTCOME_OK and epoch:
                    conn.execute(
                        "INSERT INTO authepoch (day, epoch, n) VALUES (?, ?, 1) "
                        "ON CONFLICT(day, epoch) DO UPDATE SET n = n + 1", (day, epoch))
                conn.execute("DELETE FROM authepoch WHERE day < ?",
                             (time.strftime("%Y-%m-%d", time.gmtime(time.time() - 60 * 86400)),))
                # 60 days is plenty to watch a store rollout land, and keeps the table trivial.
                conn.execute("DELETE FROM authoutcome WHERE day < ?",
                             (time.strftime("%Y-%m-%d", time.gmtime(time.time() - 60 * 86400)),))
                conn.commit()
            finally:
                conn.close()
    except Exception:
        pass


def _auth_adoption_summary() -> dict:
    """
    What an operator needs to decide whether PUSH_AUTH_ENFORCE=1 will silence real users.

    The signed/unsigned/window_signed_pct keys are kept exactly as they were — the runbook names
    them and monitoring may already read them. What is new is the resolution underneath, because the
    single "unsigned" number could not answer the question it was being asked:

      missing       — a client that does not sign at all. Heals when the fleet turns over.
      badmac        — a client that signs with the WRONG secret. Never heals on its own, and is
                      indistinguishable from `missing` until you can see the two apart. This is the
                      case that makes an operator wait forever for a percentage that cannot move.
      stale         — clock skew, or a client sitting on a request. Heals; not an adoption problem.
      malformed_ts  — a client sending a non-integer ts, i.e. a client bug.
      replay        — a signature presented twice. Either an attack or a client retrying after a
                      network failure, which is why a rate-limited request no longer burns one.
      store_error   — the relay's own replay store is unavailable. Not a client problem at all.

    `unsigned_ex_store_error` is the percentage recomputed with `store_error` excluded, so a bad disk
    cannot read as un-adopted clients.
    """
    try:
        day = time.strftime("%Y-%m-%d", time.gmtime())
        with _stats_lock:
            conn = _stats_conn()
            try:
                today_rows = conn.execute(
                    "SELECT outcome, n FROM authoutcome WHERE day = ?", (day,)).fetchall()
                window_rows = conn.execute(
                    "SELECT outcome, SUM(n) FROM authoutcome GROUP BY outcome").fetchall()
                span = conn.execute(
                    "SELECT MIN(day), COUNT(DISTINCT day) FROM authoutcome").fetchone() or (None, 0)
                epoch_rows = conn.execute(
                    "SELECT epoch, SUM(n) FROM authepoch GROUP BY epoch").fetchall()
            finally:
                conn.close()

        today = {o: n for o, n in today_rows}
        window = {o: n for o, n in window_rows}

        def pct(signed, total):
            # null while there is no traffic, so an idle relay cannot read as "100% adopted".
            return round(signed * 100.0 / total, 2) if total else None

        w_signed = window.get(AUTH_OUTCOME_OK, 0)
        w_total = sum(window.values())
        w_total_ex_store = w_total - window.get("store_error", 0)
        return {
            "today_signed": today.get(AUTH_OUTCOME_OK, 0),
            "today_unsigned": sum(n for o, n in today.items() if o != AUTH_OUTCOME_OK),
            "window_signed": w_signed,
            "window_unsigned": w_total - w_signed,
            # The number the enforcement decision actually turns on.
            "window_signed_pct": pct(w_signed, w_total),
            "window_signed_pct_ex_store_error": pct(w_signed, w_total_ex_store),
            "today_outcomes": {o: today.get(o, 0) for o in AUTH_OUTCOMES},
            "window_outcomes": {o: window.get(o, 0) for o in AUTH_OUTCOMES},
            # Without these, a relay redeployed ten minutes ago and a relay observed for three weeks
            # produce the same-looking percentage. The counters live in /data, so they only survive a
            # redeploy because docker-compose gives that path a named volume — see infra/push/.
            # KHANDAQ (K-03): which key epoch each accepted signature used. LABELS AND COUNTS ONLY —
            # never a secret; a test asserts no configured secret appears anywhere in this response.
            # This is the number a rotation is driven by: add the new epoch, ship clients, wait for
            # the old one to reach zero, then delete it. No soft-mode window, ever.
            "epochs_configured": [e for e, _ in AUTH_SECRETS],
            "by_epoch": {e: n for e, n in epoch_rows},
            "since": span[0],
            "days_observed": span[1],
            # Request-weighted, not device-weighted: one chatty unsigned sender holds this down, and
            # a chatty signed one holds it up. Read it together with the outcome breakdown.
            "weighting": "requests",
        }
    except Exception:
        return {"error": "unavailable"}


def _enforce_overdue() -> bool:
    """True when a PUSH_AUTH_ENFORCE_BY date has passed and the relay is still serving soft."""
    if not PUSH_AUTH_ENFORCE_BY or PUSH_AUTH_ENFORCE:
        return False
    try:
        cutoff = time.strptime(PUSH_AUTH_ENFORCE_BY, "%Y-%m-%d")
    except ValueError:
        log.error("push auth: PUSH_AUTH_ENFORCE_BY=%r is not YYYY-MM-DD — ignoring it",
                  PUSH_AUTH_ENFORCE_BY)
        return False
    return time.gmtime() > cutoff


# KHANDAQ (re-audit 2026-08-22, K-02): production must not be able to fall into soft mode by an
# ordinary configuration mistake. The compose overlay sets PUSH_AUTH_ENFORCE=1; this makes the
# absence of it fatal rather than silent, and makes the exception explicit and recorded.
#
# The escape hatch is deliberately awkward and deliberately not a boolean: PUSH_AUTH_BREAK_GLASS
# must carry a REASON, which is logged at error level on every worker start. "Somebody typed a
# sentence explaining why the relay is unauthenticated today" is a different kind of event from
# "somebody set a flag to 0", and only the first leaves a record an incident review can read.
if PUSH_ENV == "production" and not PUSH_AUTH_ENFORCE:
    if not PUSH_AUTH_BREAK_GLASS:
        raise SystemExit(
            "PUSH_ENV=production with PUSH_AUTH_ENFORCE off. Soft mode serves unsigned wake requests "
            "to anyone inside the rate limit, and reaching production that way is a mistake, not a "
            "decision. Set PUSH_AUTH_ENFORCE=1, or state a reason in PUSH_AUTH_BREAK_GLASS "
            "(e.g. PUSH_AUTH_BREAK_GLASS='incident 2026-08-22: rollback to unblock 0.2.39 clients') "
            "— it is logged on every start and belongs in the incident record.")
    log.error("push auth BREAK-GLASS: production is running in SOFT mode by explicit override: %s",
              PUSH_AUTH_BREAK_GLASS)


def _enforce_deadline_days_left():
    """Days until PUSH_AUTH_ENFORCE_BY, or None when there is no deadline / it is already enforced."""
    if not PUSH_AUTH_ENFORCE_BY or PUSH_AUTH_ENFORCE:
        return None
    try:
        cutoff = time.mktime(time.strptime(PUSH_AUTH_ENFORCE_BY, "%Y-%m-%d"))
    except ValueError:
        return None
    return int((cutoff - time.time()) // 86400)


# The spec asks for an alert BEFORE the deadline as well as after it. A warning that only fires once
# the date has passed tells an operator they are already late, which is the least useful moment.
_days_left = _enforce_deadline_days_left()
if _days_left is not None and 0 <= _days_left <= 14:
    log.warning("push auth: %d day(s) left before the %s soft-mode cutoff. Read /health/detail -> "
                "auth_adoption.window_outcomes and plan the flip to PUSH_AUTH_ENFORCE=1.",
                _days_left, PUSH_AUTH_ENFORCE_BY)

if _enforce_overdue():
    # Once per worker at startup. The point is that a relay quietly left in soft mode long after the
    # cutover was supposed to happen announces itself, instead of being discovered by the next audit.
    log.error("push auth: STILL IN SOFT MODE past the %s cutoff — unsigned and invalid requests are "
              "being served. Read /health -> auth_adoption.window_outcomes and set "
              "PUSH_AUTH_ENFORCE=1, or move PUSH_AUTH_ENFORCE_BY out deliberately.",
              PUSH_AUTH_ENFORCE_BY)


# KHANDAQ (production deploy 2026-08-21): the same statement in the logs, once per worker at startup.
# A relay whose service account it cannot read will still answer /health with 200 and still accept
# wakes — it just never delivers one. Somebody has to be told, and the container log is where an
# operator looks after a deploy.
if FCM_SERVICE_ACCOUNT_FILE and os.path.isfile(FCM_SERVICE_ACCOUNT_FILE)         and not os.access(FCM_SERVICE_ACCOUNT_FILE, os.R_OK):
    log.error("fcm: %s exists but is NOT READABLE by uid %s — every send will fail while the relay "
              "otherwise looks healthy. chown it to the uid the container runs as.",
              FCM_SERVICE_ACCOUNT_FILE, os.getuid() if hasattr(os, "getuid") else "?")


def _auth_ok(token: str, sender: str, *, allow_query_auth: bool = True) -> bool:
    # KHANDAQ (audit A1): the relay must never run wide-open. docker-compose makes
    # PUSH_RELAY_AUTH_SECRET mandatory (:? -> the container refuses to start without it), so an
    # empty secret here is a misconfiguration, not "auth off" -> fail CLOSED instead of accepting
    # every request.
    if not AUTH_SECRETS:
        log.error("push auth: no secret configured (PUSH_RELAY_AUTH_SECRET / PUSH_RELAY_AUTH_SECRETS) "
                  "-> rejecting every request (set one in the relay .env)")
        return False

    outcome = _auth_signature_valid(token, sender, allow_query_auth=allow_query_auth)
    _record_auth_outcome(outcome)
    if outcome == AUTH_OUTCOME_OK:
        return True

    if PUSH_AUTH_ENFORCE:
        # KHANDAQ (audit 2026-08-21, K-02): the enforce path used to return False in silence. The
        # moment an operator flips the flag is exactly the moment they need to know who is being
        # rejected and why — "it broke" with no signal is how a cutover gets rolled back blind.
        log.warning("push auth REJECT (%s) from %s", outcome, _client_ip())
        return False  # hard enforce -> caller returns 401

    # Soft/monitor mode (TRANSITIONAL): allow the unsigned request through but record it, so
    # client-signing adoption can be measured. Old clients built before the secret was provisioned do
    # not sign yet; enforcing now would drop their pushes. Once the unsigned count falls to ~0 (i.e.
    # shipped clients sign), set PUSH_AUTH_ENFORCE=1 to close the window — and read
    # /health -> auth_adoption.window_outcomes first, because `missing` (an old client, heals) and
    # `badmac` (a build carrying the wrong secret, never heals) both used to look the same here.
    # KHANDAQ (audit round 3, F-12): the outcome label is what this line is for. The client IP added
    # nothing the per-IP counters do not already have, and putting it here put a sender IP in the same
    # container log as the recipient's stable token hash, one join away from each other. nginx still
    # records (IP, time) in its own access log; this stops the relay's log from being the second half.
    log.warning("push auth SOFT (%s): request allowed (set PUSH_AUTH_ENFORCE=1 after client adoption)",
                outcome)
    return True


# KHANDAQ (audit A2): single-use signature cache — an accepted ts-bound HMAC is consumed so it cannot
# be replayed inside the AUTH_MAX_SKEW_SEC window.
# KHANDAQ (audit2 #10): this used to be an in-process dict, but the container runs gunicorn -w 2, so a
# captured signature was accepted once PER WORKER. Back it with the SQLite file the relay already uses
# (same container FS => shared by every worker): INSERT OR IGNORE on a PRIMARY KEY is atomic, so
# exactly one caller — in one worker — can claim a given signature. Retention is bounded by the
# freshness window: anything older than 2x the skew can no longer pass the ts check, so it is purged.
_SIG_RETENTION_SEC = max(AUTH_MAX_SKEW_SEC, 60) * 2


def _claim_signature(sig: str) -> str:
    """
    "ok" if THIS request is the first to present `sig`, else why not.

    KHANDAQ (audit 2026-08-21, K-02): returns a reason rather than a bool, because "replay" and
    "the store is broken" are the same rejection to the caller and completely different facts to an
    operator — one is a client (or an attacker), the other is a disk.
    """
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
                return AUTH_OUTCOME_OK if claimed else "replay"
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
        #   * A SIGNED request that hits a DB error now gets "store_error" here, so
        #     _auth_signature_valid() reports that, and _auth_ok() falls into the soft branch: it
        #     logs "push auth SOFT (store_error)" and returns True. The wake is STILL DELIVERED. In
        #     soft mode this change therefore cannot reject any wake traffic.
        #   * Only once PUSH_AUTH_ENFORCE=1 does a broken store turn into 401s. That is the intended
        #     meaning of fail-closed, and the operator has this loud line saying exactly why.
        #
        # KHANDAQ (K-02): it used to be counted as an unsigned request, so a broken disk read as
        # un-adopted clients on the very dashboard the enforcement decision is made from. It now has
        # its own outcome and is excluded from window_signed_pct_ex_store_error.
        log.exception(
            "push auth: replay store UNAVAILABLE -> refusing signature (FAIL-CLOSED); "
            "check that %s exists and is writable by the relay container", COALESCE_DB)
        return "store_error"


def _auth_signature_valid(token: str, sender: str, *, allow_query_auth: bool = True) -> str:
    # KHANDAQ (audit 2026-08-21, K-02): returns one of AUTH_OUTCOMES rather than a bool. Every
    # early return below was a different fact about the caller collapsed into the same False, and
    # the enforcement decision needs them apart — see _auth_adoption_summary for which is which.
    #
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
        return "missing"

    try:
        ts_int = int(ts)
    except ValueError:
        return "malformed_ts"
    if abs(time.time() - ts_int) > AUTH_MAX_SKEW_SEC:
        return "stale"

    # KHANDAQ (audit 2026-08-21, K-03): try every configured key epoch. Every candidate goes through
    # hmac.compare_digest — never ==, and no length pre-check — so each comparison stays constant
    # time. Stopping at the first match leaks only WHICH epoch matched, which is a label, not a
    # secret. N HMAC-SHA256 over a sub-200-byte message is noise beside the SQLite write that follows.
    msg = (token + "\n" + sender + "\n" + ts).encode("utf-8")
    matched = None
    for epoch, secret in AUTH_SECRETS:
        candidate = hmac.new(secret.encode("utf-8"), msg, hashlib.sha256).hexdigest()
        # KHANDAQ (audit round 3, F-06): compare BYTES, not str.
        #
        # hmac.compare_digest() refuses two str arguments unless both are ASCII — it raises
        # TypeError. `supplied` comes verbatim from ?auth= (Flask decodes the query string as UTF-8)
        # or from an Authorization header (Werkzeug decodes headers as latin-1), and nothing
        # constrains its character set. So `?auth=%C3%A9` turned a security decision into an
        # unhandled 500: no @app.errorhandler exists, the request never reached
        # _record_auth_outcome() so it stayed invisible in the adoption counters, and every one of
        # them wrote a full traceback (~2.3 KB against ~104 bytes for a normal request) into the same
        # bounded json-file ring the operator is told to read before enforcing. Under
        # PUSH_AUTH_ENFORCE=1 it turned what should have been a 401 into a 500.
        #
        # Encoding both sides sidesteps the restriction without a length or charset pre-check, so the
        # comparison stays constant-time and a hostile value now lands in `badmac`, counted like any
        # other wrong signature.
        if hmac.compare_digest(supplied.encode("utf-8", "surrogatepass"),
                               candidate.encode("ascii")):
            matched = (epoch, candidate)
            break
    if matched is None:
        return "badmac"
    expected = matched[1]
    _set_auth_epoch(matched[0])
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
        # KHANDAQ (audit 2026-08-21): log the token's HASH, never a prefix of the token itself.
        # An FCM registration token is a targeting secret — whoever holds it can push to that device
        # — and "only 12 characters" is not a safeguard, it is a partial disclosure of a credential
        # into a log that outlives the request and is read by more people than the request path is.
        # _th() is the same 128-bit hash already used to key the coalesce table, so the log line
        # remains just as useful for correlating one device's wakes, and discloses nothing.
        # KHANDAQ (audit round 3, F-12): debug, not info. A stable per-device hash on every successful
        # wake is a durable activity record for that device, kept for as long as the log ring holds.
        # It is worth having while debugging and not worth retaining by default.
        log.debug("wake ok: token#%s", _th(token))
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


def _send_fcm_data(token: str, data: dict) -> tuple[bool, str]:
    """Deliver a DATA-ONLY message. No notification block, so nothing is shown to the user.

    Used for the registration challenge. It is deliberately a separate function rather than a flag
    on _send_wake: a wake must always produce a visible notification (see #163 — an apns payload
    without an alert makes the push silent and a force-quit iOS app never receives it), and a
    challenge must never produce one. Conflating them is how a device ends up saying "New message"
    because it registered a capability.
    """
    if not FCM_SERVICE_ACCOUNT_FILE or not os.path.isfile(FCM_SERVICE_ACCOUNT_FILE):
        return False, "FCM_SERVICE_ACCOUNT_FILE not configured"
    try:
        access = _get_access_token()
    except Exception as exc:
        _token_cache["token"] = None
        _token_cache["exp"] = 0.0
        return False, f"auth failed: {exc}"
    payload = {
        "message": {
            "token": token,
            "data": {k: str(v) for k, v in data.items()},
            "android": {"priority": "HIGH"},
            # content-available only: a background wake with nothing on screen.
            "apns": {"headers": {"apns-priority": "5", "apns-push-type": "background"},
                     "payload": {"aps": {"content-available": 1}}},
        }
    }
    try:
        resp = requests.post(
            f"https://fcm.googleapis.com/v1/projects/{FCM_PROJECT_ID}/messages:send",
            headers={"Authorization": f"Bearer {access}", "Content-Type": "application/json"},
            json=payload, timeout=10)
    except requests.RequestException as exc:
        return False, f"FCM v1 request failed: {exc}"
    if resp.status_code not in (200, 201):
        return False, f"FCM v1 HTTP {resp.status_code}"
    return True, "ok"


def _caph(cap: str) -> str:
    """The stored form of a capability. Never store or log the capability itself."""
    return hashlib.sha256(cap.encode("utf-8")).hexdigest()


_CAP_RE = re.compile(r"^[A-Za-z0-9_-]{22,128}$")   # base64url, 16..96 bytes of entropy


def _record_path(path: str) -> None:
    """Count which emission path a wake arrived on. Fails open and silent, like the auth counters."""
    try:
        day = time.strftime("%Y-%m-%d", time.gmtime())
        with _stats_lock:
            conn = _stats_conn()
            try:
                conn.execute("INSERT INTO pushpath (day, path, n) VALUES (?, ?, 1) "
                             "ON CONFLICT(day, path) DO UPDATE SET n = n + 1", (day, path))
                conn.execute("DELETE FROM pushpath WHERE day < ?",
                             (time.strftime("%Y-%m-%d", time.gmtime(time.time() - 60 * 86400)),))
                conn.commit()
            finally:
                conn.close()
    except Exception:
        pass


def _cap_state(token: str, cap: str) -> str:
    """
    "none"          - this token has registered no capability; legacy behaviour applies
    "ok"            - the presented capability is registered for this token
    "missing"       - the token HAS capabilities but the request presented none
    "bad"           - the token has capabilities and the presented one is not among them
    "missing_grace" - as "missing", inside the post-registration grace window
    "bad_grace"     - as "bad", inside the post-registration grace window
    "store_error"   - the store could not be read, so none of the above is known

    A database error must NOT read as "none": that would silently turn every capability-protected
    device back into an unprotected one, and an attacker who can fill a disk should not be able to
    disable an authorisation check. It must not read as "bad" either — the relay's standing rule is
    that an infrastructure failure fails toward DELIVERY (see the rate limiter, which fails open for
    exactly this reason, and requirement 5 of the design: a wrongly-refused wake is a message the
    user never sees). So it is its own state, and what happens next is a policy decision made once,
    in _cap_required, rather than a guess made here.
    """
    th = _th(token)
    now = int(time.time())
    try:
        return _cap_state_locked(th, cap, now)
    except Exception as exc:
        log.warning("push cap: capability store unavailable (%s) — cannot verify", exc)
        return "store_error"


def _cap_state_locked(th: str, cap: str, now: int) -> str:
    with _stats_lock:
        conn = _stats_conn()
        try:
            conn.execute("DELETE FROM pushcap WHERE last_used < ?",
                         (now - PUSH_CAP_TTL_DAYS * 86400,))
            rows = conn.execute("SELECT caph, created FROM pushcap WHERE th = ?", (th,)).fetchall()
            if not rows:
                conn.commit()
                return "none"
            in_grace = (PUSH_CAP_GRACE_DAYS > 0
                        and now < min(int(c) for _, c in rows) + PUSH_CAP_GRACE_DAYS * 86400)
            if not cap:
                conn.commit()
                return "missing_grace" if in_grace else "missing"
            want = _caph(cap)
            rows = [(h, c) for h, c in rows]
            # Constant-time over the candidate set. The set is small and the comparison is against a
            # hash rather than the secret, but a timing signal that says "your first 8 characters
            # were right" is not worth the two lines it costs to avoid.
            hit = False
            for stored, _created in rows:
                if hmac.compare_digest(stored, want):
                    hit = True
            if not hit:
                conn.commit()
                return "bad_grace" if in_grace else "bad"
            conn.execute("UPDATE pushcap SET last_used = ? WHERE th = ? AND caph = ?",
                         (now, th, want))
            conn.commit()
            return "ok"
        finally:
            conn.close()


def _cap_required(state: str) -> bool:
    if PUSH_CAP_ENFORCE == "off":
        return False
    if PUSH_CAP_ENFORCE == "always":
        # The operator has said every wake needs one. Honour that even when the store is unreadable:
        # in this mode a wake that cannot be authorised is a wake that must not happen.
        return True
    if state == "store_error":
        # 'auto' + broken store: deliver. The alternative is a disk fault becoming a fleet-wide
        # notification outage, which is the failure the whole rollout shape is designed to avoid.
        return False
    if state in ("missing_grace", "bad_grace"):
        # Inside the window a contact may still be holding the pre-capability URL. Deliver, and let
        # the counter say how much of that is still happening.
        return False
    # 'auto': required exactly for the devices that have opted in by registering one.
    return state != "none"


@app.route("/health")
def health():
    """
    KHANDAQ (audit round 3, F-10) — the PUBLIC health endpoint, deliberately uninformative.

    This used to return the whole picture to anyone on the internet: auth_mode, the epoch count, the
    service-account state and the complete auth_adoption summary — seven outcome buckets, signed and
    unsigned totals, per-epoch counts, 60 days of retention. No shipped client signs yet, so the
    `missing` bucket is essentially all traffic; polling this once a minute and differencing it gave a
    live request rate and a daily and weekly activity profile for the entire user base. Aggregate, with
    no identifier in it — which is why this is low and not worse — but nobody needs it to be public.

    Liveness is public. Telemetry moved to /health/detail, which nginx allows only from loopback.
    """
    return jsonify({"status": "ok"})


@app.route("/health/detail")
def health_detail():
    mode = "v1" if _service_account_usable() else ("legacy" if FCM_SERVER_KEY else "none")
    body = {
        "status": "ok",
        "fcm_configured": _fcm_configured(),
        "fcm_mode": mode,
        # KHANDAQ (production deploy 2026-08-21): says WHICH way the service account is unusable.
        # "missing" and "unreadable" need different fixes — provision the file, versus chown it to the
        # uid the container runs as — and an operator staring at fcm_configured:false cannot tell them
        # apart. Only present when a path is configured at all.
        **({"fcm_service_account": (
            "ok" if _service_account_usable()
            else ("unreadable" if os.path.isfile(FCM_SERVICE_ACCOUNT_FILE) else "missing"))}
           if FCM_SERVICE_ACCOUNT_FILE else {}),
        # True means "requests are being authenticated". With no secret configured, _auth_ok fails
        # CLOSED and 401s everything, so authentication is very much in force — it is the relay that
        # is broken, not the check that is off.
        "auth_required": True,
        "auth_epochs": len(AUTH_SECRETS),
        # KHANDAQ (audit 2026-08-21, K-02): this used to report "off" when PUSH_RELAY_AUTH_SECRET was
        # empty. That was false, and false in the dangerous direction: it reads as "the relay is
        # serving everyone without auth" while the relay is in fact refusing every single request.
        # An operator debugging a total push outage would have been sent looking in the wrong place.
        "auth_mode": (
            # KHANDAQ (K-03): keyed on the parsed epoch set, not on the legacy single variable, so a
            # relay configured only through PUSH_RELAY_AUTH_SECRETS reports its real mode.
            "misconfigured" if not AUTH_SECRETS
            else ("enforce" if PUSH_AUTH_ENFORCE else "soft")
        ),
        # KHANDAQ (audit2 #4): the numbers the "when do we set PUSH_AUTH_ENFORCE=1" decision turns on.
        # Enforcing while shipped clients still send unsigned requests silences their notifications,
        # so this must be read before flipping it — not guessed from log volume.
        "auth_adoption": _auth_adoption_summary(),
    }
    if PUSH_AUTH_ENFORCE_BY:
        body["enforce_by"] = PUSH_AUTH_ENFORCE_BY
        body["enforce_overdue"] = _enforce_overdue()
    # KHANDAQ (re-audit 2026-08-22, K-01/K-03): the two rollout numbers, on the INTERNAL endpoint
    # only. Public /health stays minimal — adoption percentages tell an outsider how much of the
    # fleet is still unprotected, which is operational detail for us and a targeting hint for them.
    body["capabilities"] = _cap_summary()
    body["emission_paths"] = _path_summary()
    if PUSH_AUTH_BREAK_GLASS:
        body["break_glass"] = PUSH_AUTH_BREAK_GLASS
    return jsonify(body)


def _cap_summary() -> dict:
    """How far the capability rollout has got: devices registered, and how wakes are landing."""
    try:
        with _stats_lock:
            conn = _stats_conn()
            try:
                devices = conn.execute("SELECT COUNT(DISTINCT th) FROM pushcap").fetchone()[0]
                caps = conn.execute("SELECT COUNT(*) FROM pushcap").fetchone()[0]
                since = time.strftime("%Y-%m-%d", time.gmtime(time.time() - 7 * 86400))
                rows = conn.execute(
                    "SELECT outcome, SUM(n) FROM authoutcome WHERE day >= ? AND outcome LIKE 'cap_%' "
                    "GROUP BY outcome", (since,)).fetchall()
            finally:
                conn.close()
        outcomes = {k: int(v) for k, v in rows}
        return {
            "mode": PUSH_CAP_ENFORCE,
            "devices_registered": int(devices),
            "capabilities_registered": int(caps),
            "window_days": 7,
            "window_outcomes": outcomes,
            "ttl_days": PUSH_CAP_TTL_DAYS,
            "grace_days": PUSH_CAP_GRACE_DAYS,
            # cap_missing_grace falling to zero is the signal that the transition has landed and the
            # grace window is no longer carrying anyone.
            "grace_still_used": int(outcomes.get("cap_missing_grace", 0) + outcomes.get("cap_bad_grace", 0)),
        }
    except Exception:
        return {"error": "unavailable"}


def _path_summary() -> dict:
    """
    The number the legacy-GET retirement decision turns on: what share still arrives on the old URL.

    This is why it exists at all — nginx redacts the query string on that endpoint precisely because
    it carries the token, so the access log cannot answer "have the clients moved yet?" without
    un-redacting the thing the redaction is there to protect.
    """
    try:
        since = time.strftime("%Y-%m-%d", time.gmtime(time.time() - 7 * 86400))
        with _stats_lock:
            conn = _stats_conn()
            try:
                rows = conn.execute("SELECT path, SUM(n) FROM pushpath WHERE day >= ? GROUP BY path",
                                    (since,)).fetchall()
            finally:
                conn.close()
        counts = {k: int(v) for k, v in rows}
        total = sum(counts.values())
        legacy = counts.get("legacy_get", 0) + counts.get("legacy_post", 0)
        return {
            "window_days": 7,
            "counts": counts,
            "legacy_pct": round(100.0 * legacy / total, 2) if total else None,
            "legacy_mode": PUSH_LEGACY_GET,
            "retire_when": "legacy_pct is 0 over a full store-rollout window; then PUSH_LEGACY_GET=410",
            # KHANDAQ (re-review 2026-08-22, KQ-02): "publish the retirement date in the
            # compatibility matrix". Published in docs/PUSH-COMPATIBILITY.md and repeated here, so an
            # operator reading /health/detail does not have to go and find the document.
            "retire_target": "2026-11-20 (step to 410); see docs/PUSH-COMPATIBILITY.md",
        }
    except Exception:
        return {"error": "unavailable"}


def _redact_token(detail: str, token: str) -> str:
    """Remove the device token from an error string before it is logged or returned."""
    if not token or not detail:
        return detail
    return detail.replace(token, "[REDACTED]")


def _deliver_wake(token_raw: str, sender_raw: str, *, allow_query_auth: bool, cap: str = "", path: str = "wake"):
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

    # KHANDAQ (re-audit 2026-08-22, K-03): count the emission path AFTER authentication, not in the
    # route handler. "Have the clients moved off the legacy URL yet?" is a question about real
    # clients — counting unauthenticated scanner traffic against the legacy endpoint would keep the
    # retirement number permanently above zero and the endpoint permanently alive. It also keeps a
    # refused flood from costing a database write per request, the same reason the auth counters sit
    # behind the rate limiter.
    _record_path(path)

    token = token_raw.strip()
    if not token or len(token) < 10 or len(token) > 4096:
        return jsonify({"error": "invalid token"}), 400

    # KHANDAQ (re-audit 2026-08-22, K-01): the per-recipient/per-contact capability check, AFTER the
    # shared-secret HMAC rather than instead of it. The two answer different questions — the HMAC
    # says "some Khandaq client sent this", the capability says "this recipient authorised THIS
    # contact to wake it" — and only the second one survives someone unpacking the APK.
    #
    # In the default 'auto' mode a token with no registered capability behaves exactly as before, so
    # there is no fleet-wide instant at which anything can break; the property starts holding for a
    # device the moment that device registers, and for nobody else.
    cap_state = _cap_state(token, (cap or "").strip())
    if cap_state in ("store_error", "missing_grace", "bad_grace"):
        _record_auth_outcome("cap_" + cap_state)
    if cap_state != "none" and cap_state != "ok" and _cap_required(cap_state):
        _record_auth_outcome("cap_" + cap_state)
        # No detail beyond the state: telling a caller "that capability is not registered" versus
        # "this token needs one" is a distinction only an attacker is enumerating for.
        log.warning("push cap REJECT (%s) from %s", cap_state, _client_ip())
        return jsonify({"error": "unauthorized"}), 401
    if cap_state == "ok":
        _record_auth_outcome("cap_ok")

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
        # KHANDAQ (audit round 3, F-11): log the detail, do NOT return it.
        #
        # `detail` carries up to 200 bytes of FCM's own response body, or str(exc) from google-auth.
        # That path is reachable without authentication in soft mode, and when the service account
        # existed but could not be read it answered a stranger with
        # `auth failed: [Errno 13] Permission denied: '/run/secrets/firebase-sa.json'` — an internal
        # path and the precise failure mode, handed out for free. Nothing about the upstream error
        # belongs on the far side of this boundary; the operator reads it in the line above.
        return jsonify({"error": "upstream send failed"}), 503 if "not configured" in detail else 502

    return jsonify({"success": 1}), 200


@app.route("/toxfcm/fcm.php", methods=["GET", "POST"])
def wake():
    # DEPRECATED (audit: credentials in URLs). Kept because clients in the field speak only this,
    # and taking it away would silence their notifications. It gains no new functionality: anything
    # new belongs on /wake below.
    #
    # KHANDAQ (re-audit 2026-08-22, K-03): retirement is a setting, not an edit. The share of
    # traffic still arriving here is counted on /health/detail, and when it reaches zero this
    # becomes PUSH_LEGACY_GET=410 — a decision taken on that number, on a day somebody chooses.
    if PUSH_LEGACY_GET != "serve":
        _record_path("legacy_refused")
        # 410 rather than 404 by default: a client that still speaks this deserves to be told the
        # endpoint is gone on purpose, not that it was never there.
        return (jsonify({"error": "gone", "use": "POST /wake"}), 410) if PUSH_LEGACY_GET == "410" \
            else ("", 404)
    return _deliver_wake(request.args.get("id", ""), request.args.get("from", ""),
                         allow_query_auth=True, cap=request.args.get("cap", ""),
                         path="legacy_get" if request.method == "GET" else "legacy_post")


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

    # A cap of the wrong TYPE is treated as absent rather than rejected. The relay's standing rule
    # is to fail toward delivery: for a token with no registered capability this changes nothing and
    # the wake goes through, and for a token that HAS one it lands on the "missing" path, which is
    # refused under enforcement exactly as it should be. Returning 400 here would let a client-side
    # type bug cost a real user their notification.
    cap = body.get("cap", "")
    if not isinstance(cap, str):
        cap = ""

    return _deliver_wake(token, sender or "", allow_query_auth=False, cap=cap or "", path="json_post")


# ---------------------------------------------------------------------------
# KHANDAQ (re-audit 2026-08-22, K-01): capability registration.
#
# DESIGN-push-per-install-capabilities.md §6 step 2 says, in bold, do not build this as written —
# because authenticating registration with the fleet-wide HMAC authenticates nothing. Every
# installation holds that secret, so anyone who unpacks an APK could register a capability against
# somebody else's token, and at the step where capabilities become required that would silently end
# the victim's notifications. The design leaves the question open and a test enforces the gap.
#
# The proof used here is not a secret at all. The relay pushes a nonce to the token, data-only, and
# the device echoes it back. Only whoever actually holds that FCM registration on that device can
# receive it. A contact who knows the token — and every contact does, it is in the wake URL — can
# ask for a challenge and can never complete one. Nothing about the fleet secret is involved in the
# decision, which is K-01's requirement 3 met rather than deferred.
#
# The registration flow, end to end:
#
#   device  -> POST /register/challenge {"token": "..."}          -> {"cid": "..."}
#   relay   -> FCM data push to that token: {khandaq_reg_nonce, khandaq_reg_cid}
#   device  -> POST /register/confirm {"cid", "nonce", "cap"}     -> {"registered": 1}
#   device  -> publishes ...&cap=<C> to one contact over Tox (already authenticated and E2EE)
#
# Revocation needs no challenge: presenting C proves you hold C, and holding C is the whole of the
# authority being given up.
# ---------------------------------------------------------------------------


def _register_rate_ok() -> bool:
    """Registration shares the wake rate limiter. It is far rarer, so it needs no separate budget."""
    return _rate_ok(_client_ip())


@app.route("/register/challenge", methods=["POST"])
def register_challenge():
    if not _register_rate_ok():
        return jsonify({"error": "rate limit"}), 429
    body = request.get_json(silent=True)
    if not isinstance(body, dict):
        return jsonify({"error": "expected a json object body"}), 400
    token = body.get("token")
    if not isinstance(token, str) or not (10 <= len(token.strip()) <= 4096):
        return jsonify({"error": "invalid token"}), 400
    token = token.strip()

    nonce = secrets.token_urlsafe(32)
    cid = secrets.token_urlsafe(16)
    exp = int(time.time()) + PUSH_CHALLENGE_TTL_SEC
    ok, detail = _send_fcm_data(token, {"khandaq_reg_nonce": nonce, "khandaq_reg_cid": cid})
    if not ok:
        # The detail names an internal path or an upstream body; it is logged, never returned
        # (audit round 3, F-11).
        log.warning("register challenge send failed: %s", _redact_token(detail, token))
        return jsonify({"error": "challenge delivery failed"}), 502

    with _stats_lock:
        conn = _stats_conn()
        try:
            conn.execute("DELETE FROM pushchallenge WHERE exp < ?", (int(time.time()),))
            conn.execute("INSERT INTO pushchallenge (cid, th, nonceh, exp) VALUES (?, ?, ?, ?)",
                         (cid, _th(token), hashlib.sha256(nonce.encode()).hexdigest(), exp))
            conn.commit()
        finally:
            conn.close()
    return jsonify({"cid": cid, "expires_in": PUSH_CHALLENGE_TTL_SEC}), 200


@app.route("/register/confirm", methods=["POST"])
def register_confirm():
    if not _register_rate_ok():
        return jsonify({"error": "rate limit"}), 429
    body = request.get_json(silent=True)
    if not isinstance(body, dict):
        return jsonify({"error": "expected a json object body"}), 400
    cid, nonce, cap = body.get("cid"), body.get("nonce"), body.get("cap")
    if not all(isinstance(v, str) for v in (cid, nonce, cap)):
        return jsonify({"error": "cid, nonce and cap must be strings"}), 400
    if not _CAP_RE.match(cap):
        # A short or oddly-shaped capability is a client bug, and accepting one would put a
        # guessable bearer token in the authorisation path.
        return jsonify({"error": "cap must be 22-128 base64url characters"}), 400

    now = int(time.time())
    with _stats_lock:
        conn = _stats_conn()
        try:
            conn.execute("DELETE FROM pushchallenge WHERE exp < ?", (now,))
            row = conn.execute("SELECT th, nonceh FROM pushchallenge WHERE cid = ?", (cid,)).fetchone()
            if row is None:
                conn.commit()
                return jsonify({"error": "unknown or expired challenge"}), 400
            th, nonceh = row
            if not hmac.compare_digest(nonceh, hashlib.sha256(nonce.encode()).hexdigest()):
                # Consume the challenge on a wrong nonce. Otherwise the endpoint is an oracle that
                # can be tried repeatedly for the lifetime of the challenge.
                conn.execute("DELETE FROM pushchallenge WHERE cid = ?", (cid,))
                conn.commit()
                return jsonify({"error": "unknown or expired challenge"}), 400
            conn.execute("DELETE FROM pushchallenge WHERE cid = ?", (cid,))
            conn.execute("INSERT INTO pushcap (th, caph, created, last_used) VALUES (?, ?, ?, ?) "
                         "ON CONFLICT(th, caph) DO UPDATE SET last_used = excluded.last_used",
                         (th, _caph(cap), now, now))
            # Bound the per-device set, evicting least-recently-used. A contact whose capability is
            # evicted gets a fresh one on the recipient's next publish; nothing is lost permanently.
            n = conn.execute("SELECT COUNT(*) FROM pushcap WHERE th = ?", (th,)).fetchone()[0]
            if n > PUSH_CAP_MAX_PER_TOKEN:
                conn.execute(
                    "DELETE FROM pushcap WHERE th = ? AND caph IN ("
                    "  SELECT caph FROM pushcap WHERE th = ? ORDER BY last_used ASC LIMIT ?)",
                    (th, th, n - PUSH_CAP_MAX_PER_TOKEN))
            conn.commit()
        finally:
            conn.close()
    log.info("push cap registered for token#%s", th)
    return jsonify({"registered": 1}), 200


@app.route("/register/revoke", methods=["POST"])
def register_revoke():
    """
    Drop one capability. Requires the same device proof as registering does.

    KHANDAQ (re-review follow-up 2026-08-22): this used to accept the capability alone, on the
    reasoning that presenting it proves you hold it and holding it was the whole authority being
    given up. That reasoning is right about ACCESS and wrong about the DEVICE, and end-to-end testing
    over HTTP showed why: revoking the LAST capability returns the token to the "none" state, in
    which no capability is required at all. A contact holding the only capability could therefore
    revoke it and silently return the recipient to being wakeable by anyone who knows the token —
    undoing, from the outside, exactly the property KQ-01 asks for.

    Requiring the challenge closes it without trading one failure for another. Refusing the
    downgrade instead would have let the same contact deny that device every notification until its
    next foreground, which is the "fail toward delivery" requirement broken in the other direction.
    Now a contact cannot change the recipient's protection state at all; only the device that
    receives the FCM push can. A contact that no longer wants its capability simply stops using it.
    """
    if not _register_rate_ok():
        return jsonify({"error": "rate limit"}), 429
    body = request.get_json(silent=True)
    if not isinstance(body, dict):
        return jsonify({"error": "expected a json object body"}), 400
    cid, nonce, cap = body.get("cid"), body.get("nonce"), body.get("cap")
    if not all(isinstance(v, str) for v in (cid, nonce, cap)) or not _CAP_RE.match(cap):
        return jsonify({"error": "cid, nonce and cap are required"}), 400

    now = int(time.time())
    with _stats_lock:
        conn = _stats_conn()
        try:
            conn.execute("DELETE FROM pushchallenge WHERE exp < ?", (now,))
            row = conn.execute("SELECT th, nonceh FROM pushchallenge WHERE cid = ?", (cid,)).fetchone()
            if row is None:
                conn.commit()
                return jsonify({"error": "unknown or expired challenge"}), 400
            th, nonceh = row
            if not hmac.compare_digest(nonceh, hashlib.sha256(nonce.encode()).hexdigest()):
                # Consumed on a wrong nonce, exactly as in confirm: otherwise this is an oracle.
                conn.execute("DELETE FROM pushchallenge WHERE cid = ?", (cid,))
                conn.commit()
                return jsonify({"error": "unknown or expired challenge"}), 400
            conn.execute("DELETE FROM pushchallenge WHERE cid = ?", (cid,))
            cur = conn.execute("DELETE FROM pushcap WHERE th = ? AND caph = ?", (th, _caph(cap)))
            conn.commit()
            removed = cur.rowcount
        finally:
            conn.close()
    # Same answer either way: whether a given capability was registered is not information a caller
    # who does not hold it should be able to obtain.
    return jsonify({"revoked": 1 if removed else 0}), 200


# KHANDAQ: /stats + /stats.json dashboard removed (attack-surface reduction). Only the wake +
# coalesce path remains below.


@app.route("/")
def root():
    return jsonify({
        "service": "khandaq-push-relay",
        "endpoints": [
            "POST /wake  (json body + Authorization header)",
            "POST /register/challenge, /register/confirm, /register/revoke  (per-install capability)",
            "/toxfcm/fcm.php?id=<fcm_token>&type=1  (deprecated: credentials in the URL)",
        ],
        "privacy": "wake-only, no message content; optional sender public key via &from=",
    })


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=int(os.environ.get("PORT", "8080")))
