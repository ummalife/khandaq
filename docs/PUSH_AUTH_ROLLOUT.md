# Push-relay auth — staged rollout runbook (security NEW-2 / NEW-5)

The wake relay (`infra/push/relay/app.py`) can require a **replay-resistant, request-bound HMAC**
on every wake call. This doc is how to turn it on **without breaking already-installed clients**.

## Two endpoints, one signature
| endpoint | credentials travel in | status |
|---|---|---|
| `POST /wake` | JSON body + `Authorization: Bearer` + `X-Khandaq-Ts` | **preferred**; query-string credentials are refused here |
| `GET/POST /toxfcm/fcm.php` | query string (`?id=&from=&ts=&auth=`) | deprecated, kept for clients in the field; no new functionality |

`/wake` exists because a URL leaks through places a request body does not — client diagnostics,
crash reporters, proxies, `Referer`, an intermediary that logs before nginx redacts — and the FCM
registration token in `?id=` is a targeting secret. The **signed pre-image is identical** for both,
so migrating a client changes how it sends, not how it signs.

```
POST /wake
Authorization: Bearer <hex hmac-sha256>
X-Khandaq-Ts: <unix seconds>
Content-Type: application/json

{"token": "<fcm registration token>", "sender": "<64-hex tox pubkey, optional>"}
```

### Why the clients have not moved yet

The relay serves `/wake` today; no client calls it. That is deliberate, and the reason is that the
push URL is **not a client-to-server detail — it is a peer-to-peer protocol element**. A device
publishes its wake URL to its contacts over Tox (`OCTSubmanagerChatsImpl.m`, `KhandaqPush.java`,
`PushUrlValidator.java`), and it is the *sender's* client that fetches it. So the format is agreed
between two clients that may be years apart in version, and the receiving end validates the exact
shape it accepts (`"/toxfcm/fcm.php".equals(path)`).

Switching it therefore takes the same staging as any wire change, in this order:

1. Teach every client's validator to *accept* a `POST /wake`-shaped token (accept before emit).
2. Only once shipped clients accept it, let a device *publish* the new token.
3. Retire `/toxfcm/fcm.php` when the fleet has turned over.

Doing 2 before 1 means contacts on older builds silently stop being able to wake the device — the
same failure mode as flipping enforcement early, and just as invisible.

## What the auth is (and is not)
- Each sender signs the actual request:
  `msg = id + "\n" + from + "\n" + ts`  (raw, URL-decoded values, UTF-8; on `/wake` the same two
  values arrive as `token` and `sender` in the JSON body),
  `auth = lowercase hex HMAC-SHA256(secret, msg)`, sent as `Authorization: Bearer <auth>` +
  `X-Khandaq-Ts: <ts>` (the legacy endpoint also still accepts `&ts=&auth=`).
- The server recomputes and accepts only within `PUSH_AUTH_MAX_SKEW_SEC` (default 300s), so a
  captured value (e.g. from nginx logs) cannot be replayed.
- It is **NOT** strong auth: the shared secret is embedded in the shipped APK/IPA and is
  extractable by unpacking a client. It stops trivial replay/log-abuse and casual spoofing of the
  wake endpoint — it does **not** stop someone who reverse-engineers a client. Treat it as a
  rate-abuse / replay hardening, not access control.

## The footgun
`PUSH_RELAY_AUTH_SECRET` set + `PUSH_AUTH_ENFORCE=1` ⇒ every request **without a valid signature
gets 401**. Every app already in the field that shipped *before* the secret was baked in sends no
signature ⇒ its push wakeups die ⇒ users stop getting message notifications until they update. So
you must NOT jump straight to enforce.

## Server modes (`app.py` `_auth_ok`)
| `PUSH_RELAY_AUTH_SECRET` | `PUSH_AUTH_ENFORCE` | behaviour | `/health` `auth_mode` |
|---|---|---|---|
| empty | (any) | auth fully off — all requests pass | `off` |
| set | `0` (default) | **soft**: signed pass; unsigned/invalid **also pass but are logged** | `soft` |
| set | `1` | **enforce**: unsigned/invalid → `401` | `enforce` |

The soft warning line is: `push auth SOFT: unsigned/invalid request allowed from <ip> ...`.

## Where the secret is injected per client
- **Android**: `app/build.gradle` reads `System.getenv("KHANDAQ_PUSH_AUTH_SECRET")` →
  `BuildConfig.PUSH_RELAY_AUTH_SECRET`. Build release with that env var set.
  `KhandaqPush.withWakeParams()` signs.
- **iOS**: build setting `KHANDAQ_PUSH_AUTH_SECRET` (default empty in the project) →
  `$(KHANDAQ_PUSH_AUTH_SECRET)` substituted into `Antidote-Info.plist` key
  `KhandaqPushRelayAuthSecret`. Build release with `xcodebuild ... KHANDAQ_PUSH_AUTH_SECRET=<secret>`.
  The objcTox push builder (`OCTSubmanagerChatsImpl khandaqAppendRelayAuth`) reads the plist key and
  signs. Empty/absent ⇒ dormant (signs nothing).
- The secret is **never committed**: it lives only in the build environment and the server `.env`.

## Steps
1. **Generate one secret** (shared by all three): `openssl rand -hex 32`. Store it in your secret
   manager. This single value goes to: the relay `.env`, the Android build env, the iOS build.
2. **Server → SOFT.** The live relay is **push.khandaq.org** (a VPS running this repo's
   `infra/push/` via Docker Compose). SSH in as the deploy user, then:
   ```sh
   cd <khandaq checkout> && git pull            # get the soft-mode app.py (live one is older)
   # put the secret in /opt/khandaq-push/.env (chmod 600), OUTSIDE the git tree:
   #   PUSH_RELAY_AUTH_SECRET=<secret>
   #   PUSH_AUTH_ENFORCE=0
   #   PUSH_AUTH_MAX_SKEW_SEC=300
   set -a; . /opt/khandaq-push/.env; set +a      # load into shell so compose can interpolate it
   docker compose -f infra/push/docker-compose.yml up -d --build
   ```
   Verify: `curl -s https://push.khandaq.org/health` shows `"auth_mode":"soft"`. Existing clients
   keep working (unsigned requests pass + get logged). NB: the compose file reads these vars from the
   shell env via `${...}` interpolation, so they must be exported (the `set -a; . ...` line) before
   `up` — a plain file at `/opt/khandaq-push/.env` alone is not auto-loaded for interpolation.
3. **Build + ship signed clients.**
   - Android: `KHANDAQ_PUSH_AUTH_SECRET=<secret> ./gradlew :app:assembleRelease` → release.
   - iOS: `xcodebuild ... KHANDAQ_PUSH_AUTH_SECRET=<secret>` (or set it in the CI build step that
     `scripts/upload-testflight.sh` runs) → TestFlight/App Store.
4. **Watch adoption.** `curl -s https://push.khandaq.org/health | jq .auth_adoption` — `window_signed_pct`
   is the number this decision turns on. Tailing the relay for `push auth SOFT:` warnings still works,
   but counting log lines to decide whether real users will lose notifications is guesswork; the
   counter is not.

   > **Blocker as of 2026-08-20:** production `/health` answers **without** an `auth_adoption`
   > field, i.e. the deployed relay predates the counter (audit2 #4) that this step depends on.
   > `auth_mode` is `soft`. So step 5 cannot be taken responsibly yet — there is no adoption
   > evidence at all, and enforcing blind is exactly the footgun described above. **Redeploy the
   > current `infra/push/relay/app.py` first** (step 2's compose command), confirm the field
   > appears, then let it accumulate before deciding.
5. **Server → ENFORCE.** Set `PUSH_AUTH_ENFORCE=1`, redeploy, confirm `/health` → `"auth_mode":"enforce"`.
   From here unsigned/invalid wake calls get 401.

## Rollback
At any point set `PUSH_AUTH_ENFORCE=0` (back to soft) or clear `PUSH_RELAY_AUTH_SECRET` (fully off)
and redeploy. Clients with the secret keep signing harmlessly; clients without it keep working.

## Notes / clock skew
The signature includes a unix `ts`; server and client clocks must agree within
`PUSH_AUTH_MAX_SKEW_SEC` (300s). Mobile clocks are NTP-synced in practice; if you see SOFT/401 from
*updated* clients, check device time and the relay host time before raising the skew window.
