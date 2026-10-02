# Khandaq demo contact

An always-online Tox peer that anyone with a single device can add and use as the other side of
every Khandaq feature. It exists because of App Review: on 29 September 2026 Apple rejected iOS
1.4.33 (142990) under Guideline 2.1(a), asking for "a way to verify all app features", with
"pre-populated content … such as chats". Khandaq has no accounts and no server, so there is no
user name and password to hand over; a reviewer who creates a profile meets an empty app with nobody
to talk to. The demo contact is the demonstration mode Apple allows instead.

It is just as useful to a new user who has nobody on Khandaq yet.

## What it does

When someone adds it, it accepts at once and fills the new chat:

- a greeting and a list of what to try,
- a photo, a voice message and a PDF,
- a shared location (the map card),
- an invitation to *Khandaq Demo Group*, where it greets newcomers and answers too.

After that it answers everything:

| You do | It does |
| --- | --- |
| send a message | replies with a quote and reacts 👍 |
| send a photo, video, file or voice message | sends the same bytes back (voice comes back as a voice message) |
| call it, audio or video | answers after a ring, plays a short spoken greeting, then echoes your own voice and camera |
| type `call me` / `video call me` | calls you 10 s later: lock the phone to see the CallKit incoming-call screen |
| type `edit` / `delete` | sends a message, then edits it / deletes it for both sides |
| type `react`, `photo`, `voice`, `file`, `location`, `group` | the obvious |
| type `help` | lists the commands |
| react to, edit or delete one of your messages | confirms it saw the change |
| invite it to your own group | joins, greets, answers; leaves after 24 h |

## Why it is built from the iOS client's toxcore

`build.sh` compiles `khandaq-ios/local_pod_repo/toxcore` — the toxcore the iPhone app ships — rather
than a distribution `libtoxcore`. The Khandaq features a reviewer is asked to try ride on extensions
to plain Tox: msgV3 message ids and high-level ACKs, the "KQ" lossless packets for reactions (188),
edits (186) and delete-for-both (187), the toxcore capability handshake, NGC groups, and the older
toxav generation the iPhone still runs. Building from the same sources means the demo contact
behaves on the wire exactly like a second phone. The wire formats in `khandaq_demo.c` are the ones
`OCTTox.m` parses.

The messages it sends carry msgV3 ids, so the app treats it as a full Khandaq peer (reactions, edit
and delete work on its messages). It ACKs every msgV3 message it receives, as the clients do, and it
keeps its own unacknowledged messages and resends them, with the original ids, when a contact
reconnects: a connection can die some seconds before either side notices, and anything sent into
that gap is otherwise lost. The app drops a copy whose id it already has.

## Files

| File | Purpose |
| --- | --- |
| `khandaq_demo.c` | the demo contact (single-threaded, one loop for `tox_iterate` and `toxav_iterate`) |
| `probe.c` | end-to-end check that plays a fresh iPhone; exit 0 only when every check passes |
| `build.sh` | builds both against the pod toxcore (Linux and macOS) |
| `deploy-demo-contact.sh` | builds on khandaq.org, installs, restarts, runs the probe as a smoke test |
| `khandaq-demo.service` | hardened systemd unit |
| `make-assets.sh` | regenerates `assets/` from the brand files (macOS: `say`, `sips`) |
| `assets/` | avatar, photo, voice message, PDF, call greeting, bootstrap nodes |

`assets/nodes.txt` is generated from the iOS client's `nodes.json`, so the demo contact uses the same
public bootstrap nodes and TCP relays as the app.

## Deploy

```sh
infra/demo-contact/deploy-demo-contact.sh          # HOST=Khandaq by default
```

It builds on the server under `/opt/khandaq-demo`, installs the binary and media, refreshes
`/etc/systemd/system/khandaq-demo.service`, restarts the service and runs the probe on the server
(`--quick`: no calls). `SKIP_PROBE=1` skips the smoke test.

**The identity lives in `/var/lib/khandaq-demo/khandaq-demo.tox`.** Its Tox ID is in the App Review
Notes and in the QR image attached to the submission. The deploy script never touches that
directory; losing or recreating the file gives the demo contact a new ID and silently breaks the
instructions App Review follows. Keep a copy of the file somewhere safe.

The current ID is always readable on the server:

```sh
ssh Khandaq cat /var/lib/khandaq-demo/toxid.txt
```

## Check it

```sh
OUT=/tmp/kd infra/demo-contact/build.sh
/tmp/kd/khandaq-demo-probe --bot "$(ssh Khandaq cat /var/lib/khandaq-demo/toxid.txt)" \
    --nodes infra/demo-contact/assets/nodes.txt
```

The full run takes about two minutes and covers 29 checks: joining the network, the contact coming
online, every item of the welcome pack, msgV3 ACKs, reply and reaction, de-duplication of a resent
message, edit and delete-for-both, file and voice-message echo, the group invitation, group welcome
and group reply, an audio call with echo, a video call with echo, and `call me`. From a home
connection to khandaq.org the contact is typically online 4–5 s after the request.

## Operating it

- Logs: `journalctl -u khandaq-demo`. They contain events and the first 8 hex digits of a contact's
  key, never message text or file contents. Received files are kept in memory only until they are
  sent back.
- Limits: 2 000 contacts (offline ones expire after 30 days, the oldest goes first when full), files
  up to 20 MB, 6 incoming transfers at once, 4 calls at once, 5 minutes per call, a token bucket on
  replies per contact and per group. `MemoryMax=384M`, `CPUQuota=150%`.
- No inbound firewall port is needed: toxcore reaches the clients through UDP hole punching and,
  where UDP is blocked, through the public TCP relays.
