# Push-relay auth — staged rollout runbook (security NEW-2 / NEW-5)

The wake relay (`infra/push/relay/app.py`) can require a **replay-resistant, request-bound HMAC**
on every wake call. This doc is how to turn it on **without breaking already-installed clients**.

## What the auth is (and is not)
- Each sender signs the actual request:
  `msg = id + "\n" + from + "\n" + ts`  (raw, URL-decoded values, UTF-8),
  `auth = lowercase hex HMAC-SHA256(secret, msg)`, sent as `&ts=&auth=`
  (or `Authorization: Bearer <auth>` + `X-Khandaq-Ts: <ts>`).
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
2. **Server → SOFT.** In `/opt/khandaq-push/.env`: set `PUSH_RELAY_AUTH_SECRET=<secret>`,
   `PUSH_AUTH_ENFORCE=0` (and optionally `PUSH_AUTH_MAX_SKEW_SEC=300`). Redeploy:
   `docker compose -f infra/push/docker-compose.yml up -d --build`. Verify
   `curl -s https://push.khandaq.org/health` shows `"auth_mode":"soft"`. Existing clients keep working.
3. **Build + ship signed clients.**
   - Android: `KHANDAQ_PUSH_AUTH_SECRET=<secret> ./gradlew :app:assembleRelease` → release.
   - iOS: `xcodebuild ... KHANDAQ_PUSH_AUTH_SECRET=<secret>` (or set it in the CI build step that
     `scripts/upload-testflight.sh` runs) → TestFlight/App Store.
4. **Watch adoption.** Tail the relay for `push auth SOFT:` warnings. As users update, these drop.
   Wait until they are negligible (give the slowest store + user-update cycle time — typically weeks).
5. **Server → ENFORCE.** Set `PUSH_AUTH_ENFORCE=1`, redeploy, confirm `/health` → `"auth_mode":"enforce"`.
   From here unsigned/invalid wake calls get 401.

## Rollback
At any point set `PUSH_AUTH_ENFORCE=0` (back to soft) or clear `PUSH_RELAY_AUTH_SECRET` (fully off)
and redeploy. Clients with the secret keep signing harmlessly; clients without it keep working.

## Notes / clock skew
The signature includes a unix `ts`; server and client clocks must agree within
`PUSH_AUTH_MAX_SKEW_SEC` (300s). Mobile clocks are NTP-synced in practice; if you see SOFT/401 from
*updated* clients, check device time and the relay host time before raising the skew window.
