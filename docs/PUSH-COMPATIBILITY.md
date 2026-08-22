# Push compatibility matrix and retirement dates

KHANDAQ (re-review 2026-08-22, KQ-01/KQ-02). The review asks for two things this file exists to
provide: a published retirement date for the legacy wake endpoint, and a statement of what is
actually deployed rather than what the code can do. Both findings are open precisely because
security here depends on the fleet, not on the repository.

## What the relay speaks

| Path | Status | Who uses it | Retires |
|---|---|---|---|
| `POST /wake` (JSON body, auth in headers) | current | clients from 0.2.41 / iOS 1.4.34 onward | — |
| `GET/POST /toxfcm/fcm.php?id=…` | deprecated, still served | every shipped client up to 0.2.40 / iOS 1.4.33 | see below |
| `POST /register/challenge` → `/confirm` → `/revoke` | current | capability-enabled clients | — |

**Legacy retirement is driven by a number, not by a date guessed in advance.**
`/health/detail` → `emission_paths.legacy_pct` is the share of authenticated wake traffic still
arriving on the query-string endpoint. The sequence is:

1. `legacy_pct` reaches 0 and stays there for a full store-rollout window (four weeks — long enough
   that users who open the app rarely have still updated).
2. `PUSH_LEGACY_GET=410` for one release window. A client that still speaks it gets told the endpoint
   is gone on purpose, which is a better failure than a silent one.
3. `PUSH_LEGACY_GET=404`, and then the route is deleted from `app.py`.

**Target: step 2 no earlier than 2026-11-20**, matching the other supportability deadlines
(Qt 6 / OpenSSL 3, and the build-time dependency waiver) so they are re-argued together. Earlier is
allowed if the number reaches zero sooner; later requires saying why.

## What is deployed right now

Do not read this section as a design statement. It is a record of production, and it is the reason
KQ-01 and KQ-02 are open rather than closed:

| Property | Value | Consequence |
|---|---|---|
| `auth_mode` | `soft` | Unsigned wake requests are served, within the rate limit |
| Shipped HMAC secret | **empty** in the release APK and the iOS archive | No shipped client signs, so hard enforcement would silence every notification in the field |
| `PUSH_CAP_ENFORCE` | `auto` | A capability is required only from devices that registered one — no device has yet |
| `PUSH_LEGACY_GET` | `serve` | Shipped clients still emit the legacy form |

`scripts/record-push-release-evidence.py` captures this at release time into
`docs/push-release-evidence.json`, so a release carries the relay state it actually shipped against
rather than a description written from memory.

## The order the rollout has to happen in

1. Provision `KHANDAQ_PUSH_AUTH_SECRET` into release builds — the one step nothing in this
   repository can do, because it is a secret.
2. Ship Android and iOS with signing and capability registration enabled. Both are implemented; the
   binaries in the stores predate them.
3. Watch `auth_adoption.window_signed_pct` and `capabilities.devices_registered` climb.
4. `PUSH_AUTH_ENFORCE=1` once the signed share is high enough that the remainder is acceptable
   breakage, and only then.
5. Retire the legacy endpoint on the schedule above.

Steps 1 and 2 are the fleet's, not the code's. That is the whole content of KQ-01.
