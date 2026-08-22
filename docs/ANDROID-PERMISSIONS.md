# Android permission-to-feature matrix

KHANDAQ (re-audit 2026-08-22, M-01). Every permission the release manifest requests, what asks for
it, and the narrowest API range it is meaningful over. `scripts/check-android-permissions.py` fails
the build when the manifest and this table disagree — so a permission cannot be added without a line
here saying what it is for, and one cannot be documented without actually being requested.

Owner for all of these: **@UMMAPROJECTS**. The column exists because the audit asked for it and
because a matrix with no owner is a list, not an accountability.

| Permission | API range | Feature | Where it is used |
|---|---|---|---|
| `INTERNET` | all | Reaching the Tox DHT and the push relay | everywhere |
| `CAMERA` | all | Video calls; scanning a Tox ID QR code | `CallActivity`, QR scanner |
| `RECORD_AUDIO` | all | Audio/video calls; voice messages | `CallActivity`, voice recorder |
| `MODIFY_AUDIO_SETTINGS` | all | Speaker/earpiece routing during a call | `HeadsetStateReceiver` |
| `WAKE_LOCK` | all | Keeping the CPU up for the duration of a call | Tox service |
| `BLUETOOTH` | ≤ 30 | Detecting a connected headset during a call | `HeadsetStateReceiver` |
| `BLUETOOTH_CONNECT` | 31+ | Same, under the API-31 permission split | `HeadsetStateReceiver` |
| `USE_FULL_SCREEN_INTENT` | all | Ringing an incoming call over the lock screen | `HelperNotification` |
| `FOREGROUND_SERVICE` | all | The Tox connection is a foreground service | `ToxService` |
| `FOREGROUND_SERVICE_SPECIAL_USE` | 34+ | Type declaration required from API 34 | `ToxService` |
| `RECEIVE_BOOT_COMPLETED` | all | Reconnecting after a reboot | `BootReceiver` |
| `SCHEDULE_EXACT_ALARM` | all | DHT keepalive/re-bootstrap | `ToxService` alarm |
| `POST_NOTIFICATIONS` | 33+ | Message and call notifications | `MainActivity` |
| `ACCESS_FINE_LOCATION` | all | "Share my location" as a chat message | `ShareLocationHelper` |
| `ACCESS_COARSE_LOCATION` | all | Same, when the user grants only approximate | `ShareLocationHelper` |
| `READ_MEDIA_IMAGES` | 33+ | In-app media picker | `MediaSendPreviewHelper` |
| `READ_MEDIA_VIDEO` | 33+ | In-app media picker | `MediaSendPreviewHelper` |
| `WRITE_EXTERNAL_STORAGE` | ≤ 28 | Saving received media to the gallery, pre-scoped-storage | `FileLoader2` |
| `READ_EXTERNAL_STORAGE` | ≤ 32 | Reading a picked file, before `READ_MEDIA_*` existed | `MediaSendPreviewHelper` |

## The ones that look surprising

**`SCHEDULE_EXACT_ALARM`.** Play scrutinises this, and rightly. An inexact alarm is batched by Doze
into a window of up to fifteen minutes; a Tox peer treats us as offline well before that, so a
delayed re-bootstrap is a message the user does not receive. It fires the keepalive and nothing else.

**Location.** It is a chat feature — "share my location" — not a background capability. The
permission is requested at the moment the user taps the button and never at start-up, a single fix
is taken rather than a subscription, and nothing about it runs while the app is in the background.

**Both storage permissions.** They are bounded rather than removed because the app still supports
API 21. Above their bounds they grant nothing: `requestLegacyExternalStorage` is `false`, so
`WRITE_EXTERNAL_STORAGE` has been inert since API 29, and the media picker already branches to
`READ_MEDIA_*` on API 33+.

## Removed on 2026-08-22

| Permission | Why it went |
|---|---|
| `SYSTEM_ALERT_WINDOW` | No `TYPE_APPLICATION_OVERLAY` and no `canDrawOverlays` anywhere in the app. Incoming calls use a full-screen intent, which is the approved path. Drawing over other apps is the single most abusable permission a messenger can hold, and this one was granting that power for nothing. |
| `DISABLE_KEYGUARD` | Nothing dismisses the lock screen. The only `KeyguardManager` use is `PlaintextExportGate` asking whether the device has a credential at all, which needs no permission. |
| `RAISED_THREAD_PRIORITY` | Not an AOSP permission — a vendor string that has never existed on any device this app ships to. It granted nothing and only widened what a reviewer had to account for. |

## What is deliberately still open

Nothing here is a placeholder, but two things are worth revisiting when the minimum API rises:

* dropping `WRITE_EXTERNAL_STORAGE` and `READ_EXTERNAL_STORAGE` entirely once `minSdkVersion` reaches
  33, at which point both bounds cover nothing;
* moving the media picker to `ACTION_PICK_IMAGES` (the photo picker), which needs no permission at
  all on API 33+ and would let `READ_MEDIA_*` go as well.
