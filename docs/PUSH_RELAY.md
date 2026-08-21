# Push Wake Relay

Mobile clients may register an opaque push URL with Tox friends. When a message cannot be delivered immediately (friend offline), the sender can trigger a **wake notification** without exposing message content.

## Public relay

| Field | Value |
|-------|-------|
| Base URL | `https://push.khandaq.org` |
| FCM endpoint | `https://push.khandaq.org/toxfcm/fcm.php?id=<token>&type=1` |
| Config | `config/khandaq_push.json` |

## Privacy model

From `config/khandaq_push.json`:

```json
"privacy": {
  "payload_contains_message_content": false,
  "payload_contains_tox_id": false,
  "payload_contains_sender_public_key": true,
  "sender_public_key_format": "64_hex_chars_optional_query_param_from"
}
```

The relay:

- Receives **FCM device token** (or compatible push token) in the URL
- Sends a generic notification to wake the app
- Does **not** receive Tox message plaintext
- Does **not** receive full Tox IDs (address + checksum/nospam)
- May receive an optional **sender public key** (64 hex chars) via `&from=` — forwarded in FCM `data` as `sender_pubkey` / `from` so the app can open the right chat
- Does **not** receive receiver Tox ID

## Allowed push URL prefixes (client-side validation)

Configured in `config/khandaq_push.json`:

- `https://push.khandaq.org/toxfcm/fcm.php?id=`
- Legacy (third-party, not Khandaq-operated): `https://tox.zoff.xyz/toxfcm/fcm.php?id=`
- Optional: UnifiedPush / ntfy prefixes (see config)

## Server deployment (maintainers)

Templates: `infra/push/` — Docker Compose + nginx. Firebase service account JSON is **never** committed; use `secrets/push-relay.env.example`.

## CA anchors and rotation (maintainers)

`push.khandaq.org` is the only host either mobile client pins, and the pinning is expressed **twice**,
by hand, in two languages:

| where | form | file |
|---|---|---|
| Android | four SHA-256 SPKI pins + `<pin-set expiration="…">` | `khandaq-android-trifa/…/res/xml/network_security_config.xml` |
| iOS | three base64 DER certificates passed to `SecTrustSetAnchorCertificates`, plus the same expiry as `c.year/c.month/c.day` | `khandaq-ios/…/OCTSubmanagerChatsImpl.m` |

The certificate is issued by Let's Encrypt through certbot (`scripts/deploy-push-relay.sh`), so every
anchor is an ISRG root or an LE intermediate and the calendar is theirs, not ours. Verified 21 Aug 2026
against the live chain: `leaf → YE1 → Root YE → ISRG Root X2` (X2 itself cross-signed by X1).

| anchor | notAfter | Android | iOS |
|---|---|---|---|
| ISRG Root X1 (RSA 4096) | 2035-06-04 | ✅ | ✅ |
| ISRG Root X2 (ECDSA P-384) | 2040-09-17 self-signed / 2032-09-02 as X1 cross-sign | ✅ | ✅ |
| ISRG Root YE (ECDSA P-384) | **2032-09-02** | ✅ | ✅ |
| LE YE1 intermediate | 2028-09-02 | ✅ | — *(deliberate)* |

YE1 is Android-only on purpose: an Android `<pin-set>` matches **any** certificate in the validated
chain, so pinning the issuing intermediate is a free extra check, while iOS uses
`SecTrustSetAnchorCertificatesOnly(true)`, where anchoring an intermediate adds nothing once Root YE is
anchored. Do not "fix" that asymmetry.

**The expiry is an anti-brick valve, not an oversight.** After `2027-08-01` both clients silently fall
back to ordinary system-CA validation, so a botched CA migration cannot permanently kill push. The
risk is that the refresh never happens and the control just disappears on a known date, so
`scripts/check-push-ca-anchors.py` (wired into `.github/workflows/security-checks.yml`, on every push
and weekly) fails the build when the expiry is within 120 days, when the two platforms' anchor sets
diverge, or when the two copies of the date disagree.

### Rotation procedure

1. Re-fetch the live chain and read it, rather than trusting any document including this one:
   `openssl s_client -connect push.khandaq.org:443 -servername push.khandaq.org -showcerts </dev/null`
2. For each new anchor, confirm it chains to a key that is **already** trusted here before you rely on
   it: `openssl verify -partial_chain -trusted <already-pinned.pem> <new.pem>`.
3. Compute the pin for the Android side:
   `openssl x509 -in c.pem -pubkey -noout | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | openssl base64`
4. Edit **both** clients in the same commit: the Android pin list *and* `expiration`, the iOS
   `kKhandaq…B64` constants *and* the `embedded` array *and* the `c.year/c.month/c.day` expiry.
5. Push the expiry out roughly 12 months and run `python3 scripts/check-push-ca-anchors.py` locally —
   it fails on exactly the mistakes this step invites.
6. Ship a client release on **both** stores, and only then let the old expiry approach.

**Add before you remove.** A release that adds a new anchor and drops an old one in the same step
strands every user who has not updated yet. Overlap for at least one release.

## iOS note

Production push requires real `GoogleService-Info.plist` and APNs entitlements (maintainer setup). Placeholder plist in tree is for local development only.
