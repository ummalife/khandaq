# Design — replacing the global push secret with per-install capabilities

**Status:** design only. Nothing here is implemented, and nothing in the tree depends on it.
**Closes:** external audit 2026-08-21, finding **K-03** (MEDIUM) — "Global push HMAC secret cannot
provide strong client authentication".
**Written:** 21 Aug 2026, alongside the key-epoch change that is *not* this.

---

## 0. What was already done, and why it is not this

The same audit round added **overlapping key epochs** to the relay (`PUSH_RELAY_AUTH_SECRETS`,
`infra/push/relay/app.py`). That fixed a real and separate problem: with a single secret, rotating it
meant every shipped client stopped verifying at once, so the documented procedure was to drop back to
soft mode — accepting unsigned requests from anyone — for the length of a store rollout. A leaked key
was most expensive to replace exactly when replacing it mattered most.

Epochs make a compromised key **cheap to replace**. They do nothing about it being compromised in the
first place, and it is worth being blunt about that: the two are often conflated, and shipping the
cheap one must not be mistaken for closing the finding.

## 1. The defect, stated precisely

The push HMAC secret is a **single global value shared by every installation**:

| platform | where it lives | how it is extracted |
|---|---|---|
| Android | `BuildConfig.PUSH_RELAY_AUTH_SECRET`, a compile-time constant baked from the CI env var (`app/build.gradle`) | `unzip` the APK, read the string out of `classes.dex` |
| iOS | a plaintext value substituted into `Antidote-Info.plist` at build time | `unzip` the IPA, `plutil -p` |
| relay | `PUSH_RELAY_AUTH_SECRET` in the container env | — |

So anyone who downloads the app from a public store holds the key that authenticates *every* client.
Once extracted, an attacker who also knows a recipient's FCM registration token can mint valid
signatures for it indefinitely. Hard enforcement raises the cost of casual abuse; it does not
establish client identity, and one extraction affects every installation at once.

The relay carries wake metadata, not message content, so the impact is notification abuse, battery
and bandwidth drain, and the availability of the wake path — not message confidentiality. That is why
this is MEDIUM and why it is a design project rather than a hotfix.

## 2. What the fix has to satisfy

1. **No global credential.** Reverse-engineering a shipped binary must not yield anything that
   authorises pushing to a device other than that device.
2. **Revocable per device.** Revoking one installation must not affect any other user.
3. **Distributable over a channel we already have.** No new server, no account system. Khandaq has
   exactly one authenticated channel between two users: Tox itself.
4. **Accept before emit.** Deployable in an order where shipped clients tolerate the new form before
   any client produces it — the same discipline the signed-history work follows
   (`DESIGN-ngc-signed-history-sync.md` §5).
5. **Fail toward delivery during the transition.** A wake that is wrongly refused is a message the
   user never sees. Every intermediate state must be safe in that direction.

## 3. Option A — per-recipient capability (recommended first step)

The recipient mints a random 32-byte capability `C`, registers `SHA-256(C)` with the relay against its
FCM token, and publishes `…&cap=<base64url C>` as part of the wake URL it already sends to each
contact over Tox. The relay wakes the device only when `SHA-256(cap)` is a registered capability for
that token.

```
recipient → relay :  register(token, sha256(C))          (over TLS, from the device itself)
recipient → contact: push URL incl. cap=C                (over Tox, already authenticated + E2EE)
contact  → relay :   wake(token, cap, ts, hmac)          (as today, plus cap)
```

**Why the shared HMAC still stays** for a while: it is what stops an unauthenticated stranger
enumerating the endpoint. The capability is what binds a request to *this recipient*. Removing the
HMAC is a later, separate decision.

**Good:** small, needs no new crypto, no new key management on the client, and the distribution
channel already exists — `HelperFriend.java` already publishes the wake URL to each contact as a Tox
lossless custom packet.

**Fortunate accident, worth verifying before relying on it:** the shipped URL validators
(`PushUrlValidator.java`, `KhandaqPush.swift`) check host, path and a non-empty `id` — they do not
reject unknown query parameters. So shipped clients would already *accept* a URL carrying `cap=`.
That satisfies requirement 4 for free — but confirm it on every field build before betting a rollout
on it.

**Bad:** one capability per recipient is still a bearer token that any of that recipient's contacts
can leak, deliberately or by being compromised. Revoking it means re-publishing to every contact.

### 3.1 Per-contact capabilities

The same shape with `C_contact` minted per contact fixes revocation: leak or misbehaviour is
attributable and revocable for one relationship. The cost is that the relay stores one row per
(recipient, contact) rather than per recipient, and the recipient must re-publish on friend add.

Recommended end state; it is also strictly more work than 3, and 3 is a usable intermediate.

## 4. Option B — per-device asymmetric signing

Do not mint a new keypair. The sender already has a Tox long-term key and already advertises its
public half in the `from=` parameter. Have the sender sign the existing pre-image with it, and let the
recipient tell the relay which sender keys may wake it.

**Good:** no bearer token anywhere, nothing to leak, and authorisation is exactly the recipient's
contact list. It also removes the shared secret entirely rather than layering on top of it.

**Bad:** Tox identity keys are Curve25519 (encryption), not Ed25519 (signing) — the same wall
`DESIGN-ngc-signed-history-sync.md` §2 hits. A signing key must therefore be derived and *bound*,
which is precisely the HSK announcement problem again. If the HSK work lands first, this option gets
much cheaper, because the binding machinery already exists.

## 5. Recommendation

**Option A/3.1, and only after the signed-history rollout is finished** — not because A depends on it,
but because both change the same live emission paths on two mobile clients, and running two
transitions at once on a fleet you cannot roll back is how a messenger loses its notifications.

Revisit Option B once HSKs are established; it is the better end state and its cost is dominated by
work the history-signing project is already doing.

## 6. Sequencing (each step is a release)

1. **Relay accepts `cap`** — optional, ignored when absent. No client change. Deployable immediately.
2. **Registration endpoint** — `POST /register` from the device, with the existing HMAC. Store
   `sha256(cap)` per token; nothing consumes it yet.
3. **Clients mint and publish** `cap`, keeping the old URL form working.
4. **Relay requires `cap`** for tokens that have one registered, and continues to serve tokens that do
   not. This is the step where the property actually starts holding, and it holds per device rather
   than fleet-wide — which is exactly the anti-flag-day shape.
5. **Registration becomes mandatory**, once telemetry shows registered tokens dominate.
6. **Retire the shared secret** — a separate decision, taken on evidence, not on a schedule.

## 7. What this does not fix

A contact you have authorised can still wake you as often as the rate limiter allows. That is not
impersonation and is bounded by the existing per-IP limit and per-token coalescing. The relay also
still learns which token is being woken and when — capabilities do not change what the relay sees, and
it is not a private-information-retrieval design.

## 8. Open questions

1. What happens on FCM token rotation? The token is the key of the registration; a rotated token needs
   re-registration, and until then the recipient is unreachable. The client already re-publishes its
   wake URL on token change — confirm that path covers registration too.
2. Storage growth on the relay: per-contact capabilities scale with the social graph. Bound it, and
   decide the eviction rule before shipping, not after.
3. Does `cap` in the URL reintroduce the "credentials in query parameters" defect that the `/wake`
   JSON endpoint was created to fix? Almost certainly yes for the legacy endpoint — so `cap` should be
   accepted **only** in the JSON body, and its presence should be one more reason to retire the
   query-string path.
