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

## iOS note

Production push requires real `GoogleService-Info.plist` and APNs entitlements (maintainer setup). Placeholder plist in tree is for local development only.
