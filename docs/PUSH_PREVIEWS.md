# Push previews, reaction pushes and group message wakes (v1, Khandaq 0.2.64; group messages 0.2.69)

A wake push can carry a **preview** of the event that caused it: the text of a message, or the fact
that it is a voice message, a video, a photo or a file, or a reaction to one of your messages. The
preview is encrypted on the sender's device for the recipient's device. The relay, Google (FCM) and
Apple (APNs) carry an opaque string and never hold a key that opens it.

Reactions now wake the recipient too: adding a reaction to a contact's message while that contact is
offline sends one wake with a reaction preview. In groups this works when the message's author is
one of your contacts (see "Group presence"); a group member who is not a contact has no push
address you could use.

Group messages wake too (0.2.69): a message to a group wakes the members who are the sender's
contacts and are away, when they asked for it with packet 190/03 (see "Group message wakes"). A
member who is not a contact of the sender is not woken, for the same reason.

Every client also shows a local notification when someone reacts to **your** message, in a direct
chat or in a group, whenever the reaction arrives over Tox.

Nothing here changes the wake request signature (`token \n sender \n ts`), the capability check, or
what a client without previews does. A recipient that never published a preview key gets the same
generic "Khandaq / New message" wake as before.

## Keys

**Master key `M`** — 32 random bytes per installation, created by the recipient's app on first use
and kept where the push-handling code can read it while the device is locked after the first unlock:

| Platform | Store |
|---|---|
| iOS | Keychain generic password, service `org.khandaq.push.preview`, account `master`, access group `<AppIdentifierPrefix>org.khandaq.messenger` (shared with the notification service extension), `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` |
| Android | app-private SharedPreferences `khandaq_push_preview`, key `master` (64 lowercase hex) |

**Per-contact key** for the contact with Tox public key `P` (32 raw bytes):

    K = HMAC-SHA256(key = M, message = "khandaq push preview key v1" || P)

The label is ASCII without a terminator. Deriving `K` instead of storing one per contact means the
receiving side needs only `M` and the sender's public key, which every wake already carries.

**Distribution** — lossless custom packet **190 (0xBE)**, subtype **0x01**, version **0x01**:

    BE 01 01 <K: 32 bytes>            (35 bytes)

* Sent to a friend in the same places as packet 181 (the push URL): when the friend comes online,
  and when the push token changes. Only by a device that has a push token and can decrypt previews
  (Android: API 28 or later, for ChaCha20-Poly1305).
* The receiver keeps one `K` per friend and replaces it on every new packet. Any other length or
  version is ignored. Deleting a friend deletes their `K`.

| Platform | Where a received `K` is kept |
|---|---|
| iOS | Keychain generic password, service `org.khandaq.push.preview.peer`, account = friend public key (64 uppercase hex), `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`, app's own access group |
| Android | SharedPreferences `khandaq_push_preview_peers`, key = friend public key (64 uppercase hex), value = `K` (64 lowercase hex) |

Clients that do not know packet 190 ignore it (Android, iOS and the desktop all drop unknown
lossless packets), so the packet can be sent to everyone.

## Preview plaintext

A compact UTF-8 JSON object, at most **900 bytes**. Receivers ignore fields they do not know.

| Field | Type | Meaning |
|---|---|---|
| `v` | int | `1` |
| `k` | string | `text`, `voice`, `video`, `photo`, `file` or `reaction` |
| `b` | string, optional | `text`: the message (first 140 characters). `file`: the file name. `reaction`: the reacted message when it is text (first 80 characters). Absent for `voice`, `video`, `photo`. |
| `rk` | string, `reaction` only | kind of the reacted message: `text`, `voice`, `video`, `photo`, `file` |
| `e` | string, `reaction` only | the emoji |
| `n` | string, optional | the sender's own display name (first 48 characters) |
| `g` | string, group events only | the group's name as the sender sees it (first 48 characters) |
| `gid` | string, group events only | the group's chat id, 64 uppercase hex |
| `gp` | string, a group text without `h` only | the sender's public key in that group, 64 uppercase hex (a text that waits, see "Group message wakes") |
| `ts` | int | sender's clock, unix seconds |
| `h` | string, optional | event key, for de-duplication on the recipient (below) |

Truncation never splits a character: cut on whole user-perceived characters (iOS `Character`,
Android `BreakIterator`/code points — never inside a surrogate pair), then add nothing (no "…").

**Kinds from a file** (sender side): `voice` — the file name ends with `.file.m4a` (the voice note
naming every client uses); `video` — the extension is one of `mp4 mov m4v webm mkv 3gp avi` or the
platform reports a video type; `photo` — one of `jpg jpeg png gif heic heif webp`; otherwise `file`.

**Event key `h`**, identical on both ends so the recipient can match a push to the message that
arrives later over Tox:

* text: `t:` + msgV3 hash (64 uppercase hex); omitted when the message has none
* file: `f:` + the Tox file id (64 uppercase hex)
* reaction: `r:` + the reaction anchor (uppercase hex, as on the wire) + `:` + the emoji
* group text: `gt:` + the message id (8 uppercase hex, big-endian) + `:` + the sender's public key in
  that group (64 uppercase hex); omitted for a text that waits on the sender's device, which has no
  message id yet and carries `gp` instead (see "Group message wakes")
* group file: `gf:` + the file's 32-byte hash (64 uppercase hex), the one a reaction to it anchors on

A file queued for an offline friend takes its Tox file id when it is queued, not at its first offer
(the offer reuses it), so the wake for it can already name the event the recipient later matches.
In a group the reaction anchor is the 32 bytes of the packet: the message id big-endian followed by
28 zero bytes, or the file's 32-byte hash.

## Encryption

ChaCha20-Poly1305, IETF variant (RFC 8439): key `K`, a fresh random 12-byte nonce per preview,
associated data = ASCII `khandaq push preview v1`.

    preview = base64url_without_padding( 0x01 || nonce (12) || ciphertext || tag (16) )

A preview is at most 1400 characters.

**Event id** for the relay (`pid`): 32 lowercase hex characters, the same for every retry of one
event and unpredictable to the relay:

    pid = lowercase_hex( HMAC-SHA256(K, "khandaq push event v1" || h) )[0:32]

When an event has no `h`, the sender uses 32 random hex characters, generated once per event and
reused by that event's retries.

## The wake request

`POST /wake` gains two optional fields, sent together or not at all:

    {"token": "...", "sender": "<64 hex>", "cap": "...", "preview": "<base64url>", "pid": "<32 hex>"}

`sender` is required whenever `preview` is present, because the recipient derives `K` from it. The
HMAC pre-image is unchanged; the new fields are not signed (a preview that was tampered with fails
authentication on the recipient's device).

## Relay

* A `preview` that is not 24–1400 characters of `[A-Za-z0-9_-]`, a `pid` that is not
  `^[0-9a-f]{32}$`, or a missing `sender`: both fields are dropped and the request is an ordinary
  wake. The relay fails toward delivery, never toward refusal.
* A request with a preview is de-duplicated by **(token, pid)** instead of by token:
  * the same pid within `PUSH_COALESCE_SECONDS` (45 s): coalesced, nothing sent;
  * the same pid seen in the last `PUSH_PREVIEW_REPEAT_SECONDS` (6 h): sent as a **silent repeat**
    (no alert, so the retry still wakes the device without a second banner);
  * otherwise a **preview push**, within a per-token budget of `PUSH_PREVIEW_PER_MIN` (20) preview
    pushes per 60 s. Over the budget the request falls back to the ordinary token-coalesced wake.
* Preview push (FCM v1):
  * `data`: `wake=1`, `sender_pubkey`, `khq_pv` (the preview), `khq_pid`
  * `android`: priority HIGH and **no notification block** — a data-only message, so the app's own
    `FirebaseMessagingService` runs even in the background and draws the notification itself
  * `apns`: `apns-push-type: alert`, `apns-priority: 10`, `apns-collapse-id: <pid>`; `aps` = the
    usual fallback alert ("Khandaq" / "New message"), `sound`, `mutable-content: 1`,
    `content-available: 1`. The notification service extension replaces the fallback text.
* Silent repeat: `data` = `wake=1`, `sender_pubkey`, `khq_pid`, `khq_rep=1`; Android data-only HIGH;
  APNs `apns-push-type: background`, `apns-priority: 5`, `aps` = `{"content-available": 1}`.
* The relay never logs `preview` or `pid`.

## Recipient

**iOS notification service extension.** When `userInfo` has `khq_pv` and `sender_pubkey`: read `M`,
derive `K`, decrypt, parse. On success, and when the user allows previews, set the title and body
(below), `threadIdentifier` = `gid` or the sender key, and copy `h` to `userInfo["khq_ev"]`. On any
failure keep the relay's fallback text. A group event also copies `gid` to `userInfo["khq_gid"]`, and
tapping its notification opens the group rather than the chat with the contact who sent the wake.
The extension cannot read the app's settings, so the app mirrors two of them into the Keychain
(service `org.khandaq.push.preview`): account `show`, the "show message preview" setting (`1`/`0`;
missing means `1`), and account `lang`, the app's language (`en`, `ru`, `ar` or `zh`; missing means
the phone's language when the app speaks it, else English). The app's own notification of a message
or a reaction is skipped when a delivered notification already carries its event key in `khq_ev`,
or, for a group text, its mark in `khq_tm` (see "Group message wakes").

**Android `FirebaseMessagingService`.**
* `khq_pv` present: decrypt; on success post the notification on the chat's own notification id
  (so the message arriving later over Tox updates it rather than stacking a second one), unless that
  chat is open in the foreground, then nudge Tox as today. On failure: the existing `WakePush` path.
* `khq_rep=1` without `khq_pv`: nudge only, never the generic "A contact is trying to reach you".
* Remember the event keys shown from pushes for 30 minutes; the Tox path skips its own alert for an
  event key already shown (it may still update the notification silently).

**Both.** A preview older than 48 hours (`ts`) is shown as the fallback text. `notification
_show_content` (Android) / "show message preview" (iOS) off → title = the sender (or group) name,
body = "New message" / "New reaction".

| Event | Title | Body (en) | Body (ru) |
|---|---|---|---|
| text | `n` | `b` | `b` |
| voice | `n` | 🎤 Voice message | 🎤 Голосовое сообщение |
| video | `n` | 🎬 Video | 🎬 Видео |
| photo | `n` | 📷 Photo | 📷 Фото |
| file | `n` | 📎 `b` (or 📎 File) | 📎 `b` (или 📎 Файл) |
| reaction on text | `n` | Reacted `e` to "`b`" | Реакция `e` на «`b`» |
| reaction on voice/video/photo/file | `n` | Reacted `e` to your voice message / video / photo / file | Реакция `e` на ваше голосовое сообщение / видео / фото / ваш файл |
| reaction, nothing known | `n` | Reacted `e` to your message | Реакция `e` на ваше сообщение |
| group reaction | `g` | `n`: + the reaction body | `n`: + текст реакции |

A missing `n` falls back to the contact's local name if the platform has it, else "Khandaq". Arabic
and Chinese follow the same table in the app's wording for those languages.

## Who sends a preview, and when

| Event | Sender behaviour |
|---|---|
| Direct text or file to an offline friend | the existing wake (same triggers and retries) now carries a preview when the friend's `K` is known |
| Reaction **added** to a friend's (incoming) message while the friend is offline | **one** wake with a reaction preview; no retry loop. Removing a reaction never wakes |
| Reaction added to a group message whose author maps to an offline contact | one wake to that contact with a group reaction preview |
| Text or file to a group | one wake to each away contact who is a member and listed the group in its 190/03 set (see "Group message wakes"); no retry loop |
| Calls, self-wake, capability registration | unchanged, no preview |

A reaction wakes only a friend whose `K` is known: a friend on a client without previews would see
the generic "New message" for something that is not a message, so it gets no wake for a reaction and
sees it when it next comes online.

## Group presence (packet 190, subtype 0x02)

To wake the author of a group message, the reacting device must know which contact owns that group
peer key. Peer keys are per group, so each phone tells its friends — and only its friends — a hash
per group:

    hash = SHA-256("khandaq group presence v1" || chat_id (32) || own_peer_pk_in_that_group (32))[0:16]
    BE 02 01 <n: 1 byte, 0..64> <n × 16-byte hashes>

* Sent to a friend when the friend comes online (with 181 and 190/01), and to all online friends
  after joining or creating a group. It lists every group with a known own peer key; it replaces
  the set stored for that friend. More than 64 groups: the first 64.
* A friend who is not in a group cannot learn anything from its hash, because it needs that group's
  peer key. A friend who is in the group learns which member you are — which they could ask you.
* The reacting side computes the hash for (group chat id, author peer key) and wakes the friend
  whose set holds it, when that friend is offline.

| Platform | Where a friend's set is kept |
|---|---|
| iOS | `UserDefaults.standard`, key `khandaq.groupPresence.<friend pubkey>` → array of 32-hex strings |
| Android | SharedPreferences `khandaq_group_presence`, key = friend pubkey, value = comma-joined 32-hex |

The desktop has no push and neither sends nor needs these packets.

## Group message wakes (packet 190, subtype 0x03)

A message to a group wakes the members who are the sender's contacts and are away, as a direct
message wakes an away contact. Each device tells each friend which of its groups may wake it:

    BE 03 01 <n: 1 byte, 0..64> <n × 16-byte hashes>

Each hash is the group presence hash of one group (the 190/02 formula, with this device's own peer
key in that group). For friend F the set lists every group with a known own peer key, except:

* a group muted on this device (Android `GroupDB.notification_silent`, iOS
  `OCTChat.groupNotificationsSilent`);
* a group in which this device muted F's member, that is, the hash of the muted member's key in that
  group is in F's stored 190/02 set (Android `group_notification_silent_peer`, iOS
  `OCTGroupPeer.peerNotificationsSilent`).

More than 64 groups: the first 64. The set replaces the one stored for that friend.

**When it is sent**

* to a friend with 190/02: when the friend comes online, and to every online friend after a group is
  joined or created;
* to a friend right after a 190/02 from that friend is stored, since the second exception needs it;
* to every online friend a little after a group is muted, unmuted or left, or a member is muted or
  unmuted.

| Platform | Where a friend's set is kept |
|---|---|
| iOS | `UserDefaults.standard`, key `khandaq.groupWake.<friend pubkey>` → array of 32-hex strings |
| Android | SharedPreferences `khandaq_group_wake`, key = friend pubkey, value = comma-joined 32-hex |

The set goes with the friend (deleted or blocked), as the presence set does. A friend that never sent
190/03 (a client before 0.2.69) is never woken for a group message: it could not tell a muted group
from another, and it has not said it handles a group message preview.

**Who is woken.** When this device sends a message to group G (a text when `tox_group_send_message`
returns its id, or at once when it has to wait, below; a file when its first offer goes out), it
takes each member key it knows in G (the members there now and the saved ones, not a key that has
left), computes the presence hash of G and that key, and wakes contact C when all of these hold:

1. C's 190/03 set holds the hash, and no other contact's 190/03 set holds the same hash (two claimants:
   one replayed the other's, and neither is woken);
2. that member is not connected in G now (a member who is gets the message over Tox);
3. C is offline;
4. C's `K` and push address are known.

Three seconds later conditions 2 and 3 are checked again, then **one** wake goes with the preview:

* `k` and `b` as for a direct message of the same kind;
* `n` the sender's own name;
* `g` the group's name as the sender sees it;
* `gid` the group's chat id;
* `h` the group text or group file event key; for a text that waits, no `h` and `gp` instead.

No retries: C's app brings the message by the group's history sync when it connects. Each toxcore
message is one message here: a long text that goes in several parts wakes once per part, each part
with its own event key.

**A text that waits.** A text that cannot go yet, because nobody else is connected in G or G is not
connected, waits on the sender's device until a member is there (Android
`send_group_text_message_resilient`, iOS `-sendMessageToChat:text:type:successBlock:failureBlock:`).
That is the usual case of a small group whose members are away, so the wake goes as the text begins to
wait, under the same conditions (with nobody connected in G, condition 2 holds for every member). Its
preview has no `h`, since toxcore gives the text its id only when it goes, and carries `gp`, the
sender's key in G. When the text goes later, nobody is woken for it again. A wake that cannot go at
that moment (the sender's device has no internet) is not tried again: the text reaches the members by
Tox when one is there together with the sender, as before 0.2.69.

No wake goes for:

* a private message to one member;
* an edit;
* a row brought by history sync;
* a text that waited, when it finally goes: it woke when it began to wait. A text queued by a client
  before 0.2.69 wakes nobody.

**The recipient** shows it as any group preview: title `g`, body `n: ` + the body. Android drops it, as
it drops any preview, when the group is muted, notifications are off or the group is on screen. A group
message that arrives over Tox computes the same event key, and the app skips its own alert when a push
already showed that key, as for a direct message. That holds for a row the history sync brings as well,
where a client still alerts for one that is recent enough (Android); the others keep such rows silent
(0.2.65).

A preview without `h`, of a text that waited, is known by what it says, as a direct text without `h`
is: a group text that arrives from the member key `gp`, whose first 140 characters as the chat shows
them or as they came are `b`, both on one line (CR LF, LF and CR as spaces, then trimmed), is that
event, and the app skips its own alert for it once. A match uses the mark up, so that the next text
saying the same alerts. Android keeps the mark as for a direct text: in memory for 30 minutes and on
the notification it posted. iOS puts it in `userInfo["khq_tm"]`: 32 lowercase hex, the first 16
bytes of SHA-256 over the UTF-8 of `gp` in upper case, a line feed and `b` on one line. A text that
waited longer than that window may alert a second time when it comes, as a direct text that waited
may.

**Khandaq Community** follows the same rules: a contact who writes there wakes the contacts who have the
Community's notifications on. A member who is not a contact of the writer is not woken.

## Test vectors

    M         000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f
    P         7E5A6B1C2D3E4F50617283940A1B2C3D4E5F60718293A4B5C6D7E8F901122334
    K         76437c9f9a1e86d7731ab94a0b7329c55129122bd40e4f05c71c22a7b3d88e75
    packet    BE010176437C9F9A1E86D7731AB94A0B7329C55129122BD40E4F05C71C22A7B3D88E75
    nonce     000102030405060708090a0b
    plaintext {"v":1,"k":"text","b":"Привет 👋","n":"Isa","ts":1763571600,"h":"t:ABAB…AB"}   (h = "t:" + "AB" × 32)
    preview   AQABAgMEBQYHCAkKC0cuZhE-761wu62F31CwYSNfERS9di10rfHn1Jnd2SQOJlEMElxEyACoX6lIA3nRn3Sl9GcJwRsv9OPSe7vzhOealnmCAJTD3uSj8rkONYeGSckX8WD6qi-WbLXRv9rUwHr2UexIOPXc-ua7_kVhtnjMbOHSMf4h1pSNZa27AUhSAv9ipJnIDtdsoVdBinsLSmml2n6AJdWU5TGF3hQ
    pid       f7c7d2c8db7e2f187cad5b14889dfd07

    chat_id   0102030405060708090A0B0C0D0E0F101112131415161718191A1B1C1D1E1F20
    peer_pk   A1A2A3A4A5A6A7A8A9AAABACADAEAFB0B1B2B3B4B5B6B7B8B9BABBBCBDBEBFC0
    hash      9f033743ff83e9574563245fe61fac74
    packet    BE0201019F033743FF83E9574563245FE61FAC74
    190/03    BE0301019F033743FF83E9574563245FE61FAC74

    group text event key of message id 0x0000ABCD from peer_pk above:
    h         gt:0000ABCD:A1A2A3A4A5A6A7A8A9AAABACADAEAFB0B1B2B3B4B5B6B7B8B9BABBBCBDBEBFC0

    a text that waited, gp = peer_pk above, b = "Wake  test\none " (one line: "Wake  test one"):
    khq_tm    dda36f64ff2a17499a893ea2042bacad

The plaintext is serialised exactly as shown (no spaces, `ensure_ascii=false`); a decrypting test
compares the parsed object, an encrypting test compares the bytes with this nonce.
