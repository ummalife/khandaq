# Push compatibility matrix, enforcement threshold and retirement dates

KHANDAQ. Rewritten for the re-review v2 (2026-08-22, RR2-01/RR2-02/RR2-05). The previous version of
this file described a fleet in which no shipped client carried a signing key — true when it was
written, false from Android 0.2.42 and iOS build 142986 onward. It is an operational input for
deciding when to enforce authentication, so being one release out of date is not a documentation
problem; it is how you get either a notification outage or an indefinite soft mode.

## What the relay speaks

| Path | Status | Who uses it | Retires |
|---|---|---|---|
| `POST /wake` (JSON body, auth in headers) | current | Android 0.2.41 (10415) and iOS build 142985 onward | — |
| `GET/POST /toxfcm/fcm.php?id=…` | deprecated, still served | every client up to Android 0.2.40 / iOS build 142983 | see below |
| `POST /register/challenge` → `/confirm` → `/revoke` | current | Android 0.2.42 (10420) and iOS build 142986 onward | — |

**The request SHAPE and the SIGNATURE moved in different releases, and conflating them is how the
last version of this file went wrong.** 0.2.41 / build 142985 emit the new request shape but carry an
empty secret, so they do not sign. The first artifacts that actually sign — and that register
capabilities — are Android **0.2.42 (versionCode 10420)** and iOS **1.4.33 build 142986**, the first
builds produced with `KHANDAQ_PUSH_AUTH_SECRET` provisioned. An empty secret is an explicit no-op on
both platforms rather than a signature over an empty key, which is why the older builds are served
rather than rejected.

## What the shared HMAC is, and what it is not

The build-time secret is a **fleet-wide key embedded in public binaries**. Anyone who extracts it
from an APK or an IPA and learns a target token can produce a valid MAC. Treat it accordingly:

* It **is** replay and rate-abuse hardening. The pre-image binds recipient, sender and timestamp, the
  signature is single-use across workers, and epochs allow rotation without dropping back to
  unsigned.
* It is **not** client identity and not authorisation, and must not be described as either in
  operator or user-facing documentation.

The per-contact capability is the real boundary: it is scoped to one relationship and revocable, so a
compromise costs one contact rather than the fleet. Fleet-HMAC dependence is a transition state.

## When enforcement gets turned on

`PUSH_AUTH_ENFORCE=1` is not a judgement call made on the day. It requires **all** of:

| Condition | Source | Threshold |
|---|---|---|
| Signed share of wake traffic | `auth_adoption.window_signed_pct` | ≥ 99.5% for 7 consecutive days |
| Signature failures | `auth_adoption.window_outcomes.badmac` + `.malformed_ts` | 0 for 72 consecutive hours |
| Measurement freshness | `docs/release-evidence/push-*.json` | snapshot taken during the rollout window, not before it |
| Remaining old clients | store rollout state | a written statement of who breaks and why that is acceptable |

`stale` is deliberately NOT in that list: a stale timestamp is a clock problem on the device, and
enforcing against it would break correct clients with bad clocks. It is watched, not gated on.

Capability adoption is tracked **separately** — `capabilities.devices_registered`,
`capabilities.grace_still_used`. HMAC adoption is not capability adoption, and neither number
substitutes for the other. `PUSH_CAP_ENFORCE` stays `auto` through the rollout so that a device is
only ever held to a capability it actually registered.

## Legacy retirement

`emission_paths.legacy_pct` is the share of authenticated wake traffic still arriving on the
query-string endpoint. The sequence:

1. `legacy_pct` reaches 0 and stays there for a full store-rollout window (four weeks — long enough
   that users who open the app rarely have still updated).
2. `PUSH_LEGACY_GET=410` for one release window. A client that still speaks it gets told the endpoint
   is gone on purpose, which is a better failure than a silent one.
3. `PUSH_LEGACY_GET=404`, and then the route is deleted from `app.py`.

**Target: step 2 no earlier than 2026-11-20**, matching the other supportability deadlines (Qt 6 /
OpenSSL 3, and the build-time dependency waiver) so they are re-argued together. Earlier is allowed
if the number reaches zero sooner; later requires saying why.

## What is deployed right now

A record of production, not a design statement. The current measurement lives in
`docs/release-evidence/push-<version>-<sha>.json`, written by
`scripts/record-push-release-evidence.py` and **immutable once committed** — a later release gets a
new file rather than editing this one, because a record that can be quietly rewritten to describe the
previous release is what RR2-02 was about. `--check` refuses a release whose manifest has no matching
evidence.

| Property | Value | Consequence |
|---|---|---|
| `auth_mode` | `soft` | Unsigned wake requests are served, within the rate limit |
| Shipped HMAC secret | **provisioned** from Android 0.2.42 / iOS build 142986 | Clients from those builds sign; earlier ones do not and must keep working |
| `PUSH_CAP_ENFORCE` | `auto` | A capability is required only from devices that registered one |
| `PUSH_LEGACY_GET` | `serve` | Clients up to 0.2.40 / build 142983 still emit the legacy form |

## The order the rollout has to happen in

1. ~~Provision `KHANDAQ_PUSH_AUTH_SECRET` into release builds~~ — done, Android 0.2.42 / iOS 142986.
2. ~~Ship Android and iOS with signing and capability registration enabled~~ — done, both published.
3. **Now:** watch `auth_adoption.window_signed_pct` and `capabilities.devices_registered` climb as
   the fleet updates, capturing evidence at least daily during the rollout.
4. `PUSH_AUTH_ENFORCE=1` once every condition in the threshold table above holds — and only then.
5. Retire the legacy endpoint on the schedule above.

Steps 3 and 4 are the fleet's, not the code's. That is what remains of KQ-01 and KQ-02, now narrowed
from "no client can sign" to "not enough clients have updated yet".
