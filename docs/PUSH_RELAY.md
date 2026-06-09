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
  "notification_title": "Khandaq",
  "notification_body": "New message"
}
```

The relay:

- Receives **FCM device token** (or compatible push token) in the URL
- Sends a generic notification to wake the app
- Does **not** receive Tox message plaintext
- Does **not** receive sender/receiver Tox IDs in the push request

## Allowed push URL prefixes (client-side validation)

Configured in `config/khandaq_push.json`:

- `https://push.khandaq.org/toxfcm/fcm.php?id=`
- Legacy: `https://tox.zoff.xyz/toxfcm/fcm.php?id=`
- Optional: UnifiedPush / ntfy prefixes (see config)

## Server deployment (maintainers)

Templates: `infra/push/` — Docker Compose + nginx. Firebase service account JSON is **never** committed; use `secrets/push-relay.env.example`.

## iOS note

Production push requires real `GoogleService-Info.plist` and APNs entitlements (maintainer setup). Placeholder plist in tree is for local development only.
