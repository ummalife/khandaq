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

# ---------------------------------------------------------------------------
# KHANDAQ: privacy-preserving usage stats. The relay necessarily SEES a push
# token and a client IP on every wake (that's how pushes work) — for stats we
# persist only aggregates: SHA-256 of the token (to count unique devices per
# day; the token itself is never stored) and the ISO country code resolved
# from the IP via a local DB-IP lite database (the IP itself is never stored).
# The /stats page is gated by STATS_KEY (server env, not in git).
# ---------------------------------------------------------------------------
STATS_KEY = os.environ.get("STATS_KEY", "")
STATS_DB = os.environ.get("STATS_DB", "/data/stats.db")
GEOIP_DB = os.environ.get("GEOIP_DB", "/app/dbip-country.mmdb")

_geoip_reader = None
try:
    if os.path.isfile(GEOIP_DB):
        import maxminddb

        _geoip_reader = maxminddb.open_reader(GEOIP_DB)
except Exception as exc:  # stats must never break the wake path
    log.warning("geoip disabled: %s", exc)

_stats_lock = Lock()


def _stats_conn():
    import sqlite3

    os.makedirs(os.path.dirname(STATS_DB), exist_ok=True)
    conn = sqlite3.connect(STATS_DB, timeout=5)
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("CREATE TABLE IF NOT EXISTS tokens (day TEXT, th TEXT, PRIMARY KEY(day, th))")
    conn.execute("CREATE TABLE IF NOT EXISTS countries (day TEXT, cc TEXT, hits INTEGER, PRIMARY KEY(day, cc))")
    conn.execute("CREATE TABLE IF NOT EXISTS wakes (day TEXT PRIMARY KEY, hits INTEGER)")
    # last-seen per token hash — powers the "online now" card (activity in the last 5 minutes)
    conn.execute("CREATE TABLE IF NOT EXISTS recent (th TEXT PRIMARY KEY, ts INTEGER)")
    return conn


def _country_for_ip(addr: str) -> str:
    if _geoip_reader is None or not addr:
        return "??"
    try:
        rec = _geoip_reader.get(addr)
        return (rec or {}).get("country", {}).get("iso_code") or "??"
    except Exception:
        return "??"


def _record_stats(token: str, client_ip: str) -> None:
    try:
        day = time.strftime("%Y-%m-%d", time.gmtime())
        th = hashlib.sha256(token.encode("utf-8")).hexdigest()[:32]
        cc = _country_for_ip(client_ip)
        with _stats_lock:
            conn = _stats_conn()
            try:
                conn.execute("INSERT OR IGNORE INTO tokens VALUES (?, ?)", (day, th))
                conn.execute(
                    "INSERT INTO countries VALUES (?, ?, 1) "
                    "ON CONFLICT(day, cc) DO UPDATE SET hits = hits + 1", (day, cc))
                conn.execute(
                    "INSERT INTO wakes VALUES (?, 1) "
                    "ON CONFLICT(day) DO UPDATE SET hits = hits + 1", (day,))
                now = int(time.time())
                conn.execute(
                    "INSERT INTO recent VALUES (?, ?) "
                    "ON CONFLICT(th) DO UPDATE SET ts = excluded.ts", (th, now))
                conn.execute("DELETE FROM recent WHERE ts < ?", (now - 86400,))
                conn.commit()
            finally:
                conn.close()
    except Exception as exc:
        log.warning("stats record failed: %s", exc)


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

    _record_stats(token, client_ip)
    return jsonify({"success": 1}), 200


def _stats_snapshot() -> dict:
    conn = _stats_conn()
    try:
        now = int(time.time())
        today = time.strftime("%Y-%m-%d", time.gmtime())
        cutoff30 = time.strftime("%Y-%m-%d", time.gmtime(now - 30 * 86400))
        cutoff7 = time.strftime("%Y-%m-%d", time.gmtime(now - 7 * 86400))
        online = conn.execute("SELECT COUNT(*) FROM recent WHERE ts >= ?", (now - 300,)).fetchone()[0]
        active24h = conn.execute("SELECT COUNT(*) FROM recent WHERE ts >= ?", (now - 86400,)).fetchone()[0]
        today_dev = conn.execute("SELECT COUNT(DISTINCT th) FROM tokens WHERE day = ?", (today,)).fetchone()[0]
        today_wakes = conn.execute("SELECT COALESCE(hits,0) FROM wakes WHERE day = ?", (today,)).fetchone()
        uniq7 = conn.execute("SELECT COUNT(DISTINCT th) FROM tokens WHERE day >= ?", (cutoff7,)).fetchone()[0]
        uniq30 = conn.execute("SELECT COUNT(DISTINCT th) FROM tokens WHERE day >= ?", (cutoff30,)).fetchone()[0]
        days = conn.execute(
            "SELECT t.day, COUNT(DISTINCT t.th), COALESCE(w.hits, 0) FROM tokens t "
            "LEFT JOIN wakes w ON w.day = t.day GROUP BY t.day ORDER BY t.day DESC LIMIT 30").fetchall()
        countries = conn.execute(
            "SELECT cc, SUM(hits) FROM countries WHERE day >= ? "
            "GROUP BY cc ORDER BY SUM(hits) DESC LIMIT 20", (cutoff30,)).fetchall()
    finally:
        conn.close()
    return {
        "online": online,
        "active24h": active24h,
        "today_devices": today_dev,
        "today_wakes": (today_wakes[0] if today_wakes else 0),
        "uniq7": uniq7,
        "uniq30": uniq30,
        "days": [{"day": d, "devices": u, "wakes": w} for d, u, w in days],
        "countries": [{"cc": cc, "hits": h} for cc, h in countries],
        "ts": now,
    }


def _stats_key_ok() -> bool:
    key = request.args.get("key", "")
    return bool(STATS_KEY) and hmac.compare_digest(key, STATS_KEY)


@app.route("/stats.json")
def stats_json():
    if not _stats_key_ok():
        return jsonify({"error": "not found"}), 404
    return jsonify(_stats_snapshot())


@app.route("/stats")
def stats():
    if not _stats_key_ok():
        return jsonify({"error": "not found"}), 404

    html = """<!doctype html><html lang="ru"><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="robots" content="noindex,nofollow">
<title>Khandaq — Dashboard</title>
<style>
:root{--bg:#12141f;--card:#1a1d2b;--card2:#20243a;--tx:#eef1f6;--mut:#8b93a7;--line:#2a2f45}
*{box-sizing:border-box;margin:0}
body{font-family:-apple-system,system-ui,'Segoe UI',Roboto,sans-serif;background:
 radial-gradient(1200px 600px at 80% -10%,#232a4d 0%,transparent 60%),var(--bg);
 color:var(--tx);min-height:100vh;padding:2rem clamp(1rem,4vw,3rem)}
h1{font-size:1.7rem;margin-bottom:1.4rem}
.grid{display:grid;gap:1rem;grid-template-columns:repeat(auto-fit,minmax(210px,1fr));margin-bottom:1.6rem}
.card{background:var(--card);border:1px solid var(--line);border-radius:16px;padding:1.2rem 1.3rem}
.card .ic{width:44px;height:44px;border-radius:12px;display:flex;align-items:center;justify-content:center;
 font-size:22px;margin-bottom:.9rem}
.card .lb{color:var(--mut);font-size:.72rem;letter-spacing:.08em;text-transform:uppercase;margin-bottom:.35rem}
.card .v{font-size:1.9rem;font-weight:700}
.badge{display:inline-block;margin-top:.6rem;font-size:.72rem;padding:.2rem .55rem;border-radius:999px;
 background:#173626;color:#4ade80}
.badge.gray{background:#252a3e;color:var(--mut)}
.pulse{display:inline-block;width:8px;height:8px;border-radius:50%;background:#4ade80;margin-right:.45rem;
 animation:p 1.6s infinite}
@keyframes p{0%{box-shadow:0 0 0 0 rgba(74,222,128,.5)}70%{box-shadow:0 0 0 8px transparent}100%{box-shadow:0 0 0 0 transparent}}
.panels{display:grid;gap:1rem;grid-template-columns:2fr 1fr}
@media(max-width:800px){.panels{grid-template-columns:1fr}}
.panel{background:var(--card);border:1px solid var(--line);border-radius:16px;padding:1.2rem 1.3rem;overflow-x:auto}
.panel h2{font-size:.95rem;margin-bottom:.9rem;color:var(--tx)}
table{border-collapse:collapse;width:100%;font-size:.88rem}
td,th{padding:.45rem .6rem;text-align:right;border-bottom:1px solid var(--line)}
td:first-child,th:first-child{text-align:left}
th{color:var(--mut);font-weight:500;font-size:.75rem;text-transform:uppercase;letter-spacing:.06em}
.bar{height:6px;border-radius:3px;background:linear-gradient(90deg,#6d5cff,#22d3ee);min-width:2px}
.flag{font-size:1.15rem;margin-right:.5rem}
footer{margin-top:1.4rem;color:var(--mut);font-size:.78rem;max-width:70ch}
#upd{color:var(--mut);font-size:.78rem;margin-left:.8rem;font-weight:400}
</style>
<h1>Khandaq — Dashboard <span id="upd"></span></h1>
<div class="grid">
 <div class="card"><div class="ic" style="background:#173626">🟢</div><div class="lb">Онлайн сейчас</div>
  <div class="v" id="online">—</div><span class="badge"><span class="pulse"></span>активность за 5 мин</span></div>
 <div class="card"><div class="ic" style="background:#2b2450">👥</div><div class="lb">Устройств за 24 часа</div>
  <div class="v" id="active24h">—</div><span class="badge gray" id="todaydev">—</span></div>
 <div class="card"><div class="ic" style="background:#123a46">📅</div><div class="lb">Устройств за 7 дней</div>
  <div class="v" id="uniq7">—</div><span class="badge gray" id="uniq30b">—</span></div>
 <div class="card"><div class="ic" style="background:#3a2a12">🔔</div><div class="lb">Пушей сегодня</div>
  <div class="v" id="wakes">—</div><span class="badge gray">доставлено через relay</span></div>
</div>
<div class="panels">
 <div class="panel"><h2>По дням (UTC)</h2>
  <table id="daysT"><tr><th>День</th><th>Устройств</th><th>Пушей</th><th style="width:40%"></th></tr></table></div>
 <div class="panel"><h2>Страны · 30 дней</h2>
  <table id="ccT"><tr><th>Страна</th><th>Пушей</th></tr></table></div>
</div>
<footer>Считаются только агрегаты: хэш push-токена (уникальные устройства) и страна по IP; сами токены и
IP-адреса не сохраняются. Покрывает пользователей с включёнными пушами на обеих платформах, независимо от
источника установки (Play, TestFlight, APK с сайта или GitHub).</footer>
<script>
const KEY=new URLSearchParams(location.search).get('key');
const flag=cc=>cc&&cc!=='??'?String.fromCodePoint(...[...cc.toUpperCase()].map(c=>127397+c.charCodeAt(0))):'🌐';
const NAMES={'RU':'Россия','TR':'Турция','DE':'Германия','US':'США','AE':'ОАЭ','KZ':'Казахстан','UZ':'Узбекистан',
'TJ':'Таджикистан','AZ':'Азербайджан','GE':'Грузия','NL':'Нидерланды','FR':'Франция','GB':'Британия','??':'Неизвестно'};
async function tick(){
 try{
  const r=await fetch('/stats.json?key='+encodeURIComponent(KEY),{cache:'no-store'});
  if(!r.ok)return;
  const d=await r.json();
  for(const id of['online','active24h','uniq7'])document.getElementById(id).textContent=d[id];
  document.getElementById('wakes').textContent=d.today_wakes;
  document.getElementById('todaydev').textContent='сегодня: '+d.today_devices;
  document.getElementById('uniq30b').textContent='за 30 дней: '+d.uniq30;
  const max=Math.max(1,...d.days.map(x=>x.devices));
  document.getElementById('daysT').innerHTML='<tr><th>День</th><th>Устройств</th><th>Пушей</th><th style="width:40%"></th></tr>'+
   d.days.map(x=>`<tr><td>${x.day}</td><td>${x.devices}</td><td>${x.wakes}</td>`+
   `<td><div class="bar" style="width:${Math.round(100*x.devices/max)}%"></div></td></tr>`).join('');
  document.getElementById('ccT').innerHTML='<tr><th>Страна</th><th>Пушей</th></tr>'+
   d.countries.map(x=>`<tr><td><span class="flag">${flag(x.cc)}</span>${NAMES[x.cc]||x.cc}</td><td>${x.hits}</td></tr>`).join('');
  document.getElementById('upd').textContent='обновлено '+new Date(d.ts*1000).toLocaleTimeString();
 }catch(e){}
}
tick();setInterval(tick,10000);
</script></html>"""
    return html, 200, {"Content-Type": "text/html; charset=utf-8"}


@app.route("/")
def root():
    return jsonify({
        "service": "khandaq-push-relay",
        "endpoints": ["/toxfcm/fcm.php?id=<fcm_token>&type=1"],
        "privacy": "wake-only, no message content; optional sender public key via &from=",
    })


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=int(os.environ.get("PORT", "8080")))
