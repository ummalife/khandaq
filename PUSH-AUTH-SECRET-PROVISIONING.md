# Provisioning the push-relay signing secret

**Why this file exists:** external audit #2, finding 4. The relay accepts unsigned wake requests because
**no shipped client signs** — the signing secret is empty in every release build, so the signing code is
dormant. Enforcing authentication before clients sign would silence notifications for every installed
user. This is the sequence that fixes that, and the only step nobody but you can do is step 1.

**The code is already written on all four sides and verified to agree byte-for-byte** (checked
11 Aug 2026):

| side | file | message signed |
|---|---|---|
| relay | `infra/push/relay/app.py` `_auth_signature_valid()` | `token + "\n" + sender + "\n" + ts` |
| Android | `app/src/main/java/org/khandaq/messenger/KhandaqPush.java` | `id + "\n" + from + "\n" + ts` |
| iOS (Swift) | `Antidote/KhandaqPush.swift` | `\(idValue)\n\(fromValue)\n\(ts)` |
| iOS (objcTox — the one that actually sends) | `OCTSubmanagerChatsImpl.m` `khandaqAppendRelayAuth` | `%@\n%@\n%@` (id, from, ts) |

All four: HMAC-SHA256, lowercase hex, raw URL-decoded values, UTF-8. Nothing needs to be written — only
provisioned.

---

## Step 1 — generate the secret (you, not the assistant)

Generate it on your own machine so it never passes through a chat transcript, a terminal log, or a
tool result:

```sh
openssl rand -hex 32
```

32 bytes is right: it is an HMAC-SHA256 key, so more than 32 bytes buys nothing and less weakens it.

**Understand what this key is before you store it.** It is a *shared* secret that gets baked into
publicly downloadable app binaries. Anyone who unpacks an APK or IPA can extract it. It therefore
raises the cost of spamming the relay from "trivial" to "you must first pull the key out of a binary" —
it is not, and cannot be, real client authentication. Per-install capabilities are the actual answer and
are out of scope here; the audit response says so plainly.

## Step 2 — store it in the three places that build or serve

Same value in all three, or signatures will not verify:

1. **Relay** — `/opt/khandaq-push/.env` on the push host:
   `PUSH_RELAY_AUTH_SECRET=<value>`
   Then `docker compose up -d` to restart the container. Note `docker-compose.yml` uses `:?` on this
   variable, so the container refuses to start without it — an empty value cannot silently happen.
2. **Android CI** — GitHub repository secret named `KHANDAQ_PUSH_AUTH_SECRET`. `app/build.gradle:289`
   reads it from the environment at build time into `BuildConfig.PUSH_RELAY_AUTH_SECRET`.
3. **iOS CI / local release builds** — the same name, `KHANDAQ_PUSH_AUTH_SECRET`, as a build setting.
   `Antidote-Info.plist` already maps it to the `KhandaqPushRelayAuthSecret` key that both iOS
   implementations read.

## Step 3 — ship signing clients

Cut a release of both apps with the secret present in the build environment. Nothing else changes;
the signing code activates on a non-empty secret and stays dormant on an empty one.

## Step 4 — watch adoption before enforcing

`GET https://push.khandaq.org/health` now returns an `auth_adoption` object:

```json
"auth_adoption": {
  "today_signed": 0, "today_unsigned": 0,
  "window_signed": 0, "window_unsigned": 0,
  "window_signed_pct": null
}
```

`window_signed_pct` is the number the decision turns on. Two properties worth knowing:

- it is `null`, not `100`, when there has been no traffic at all — an idle relay must not read as
  "fully adopted" and invite you to enforce;
- the counter fails **open** and silently. It is telemetry for you, not a security control, so a broken
  or read-only store never starts dropping pushes.

Wait until `window_signed_pct` is at or very near 100 over a window that covers your slowest updaters.
Anything still unsigned at that point is a client that will lose notifications the moment you enforce.

## Step 5 — enforce

Set `PUSH_AUTH_ENFORCE=1` in the relay `.env` and restart. From then on an unsigned or invalid request
gets 401 instead of a `push auth SOFT` log line. Replay protection is already hard: a valid signature is
consumed once, in a SQLite store shared across both gunicorn workers, and it fails **closed**.

`/health` reports `auth_mode` — it will read `enforce` once this is on. Verify that before you walk away.

## Rotating it later

Same value everywhere, so rotation is a flag-day unless you stage it: set `PUSH_AUTH_ENFORCE=0` first,
roll the new secret to clients, wait for adoption on the new value, then re-enforce. There is no
dual-secret acceptance window in the relay today — if you want rotation without a soft-mode gap, that
is a small change to `_auth_signature_valid()` to try a list of secrets, and it should be added before
the first rotation rather than during it.
