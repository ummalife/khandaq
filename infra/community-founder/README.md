# Khandaq Community founder

A server-side bot that holds the founder key of **Khandaq Community**, the official public NGC
group whose chat id every Khandaq client has compiled in. It founds the group once, keeps the
founder settings in force and lets a few paired admins moderate the group from a 1:1 chat with it.
It never writes in the group.

## What it does

- **First start:** creates a public group named *Khandaq Community*, with the nick *Khandaq* in it,
  writes the 64-hex chat id to `group.txt` and prints it on stdout. That chat id goes into the
  clients.
- **Every start, and every 10 minutes:** brings the group back to the founder settings wherever
  they drifted: public, topic lock on, peer limit 250 (or what `/limit` set), the topic from
  `topic.txt`. With the lock on, only the founder and moderators can change the topic; a
  moderator's change is kept in `topic.txt`, so a restart does not undo it.
- **The topic is the group's name, `Khandaq Community`.** The clients show a group's topic as its
  title (an NGC group keeps the name it was created with, so they let the topic rename it), and a
  topic such as rules text would show up as the group's name in builds that do not fix the
  Community's title themselves. Keep `/topic` for that name.
- **Mutes outlast restarts and rejoins.** toxcore keeps mutes in memory only (at most 30 at a
  time), and both a restart of the bot and a kick drop them. The bot keeps the muted members' group
  keys in `muted.txt` and mutes them again when they come back, including mutes that moderators
  set from their own clients.
- **Admin channel:** a 1:1 chat. The bot accepts a contact request only when its message is the
  pairing code from `admin-code.txt` (10 characters, Crockford base32: case, surrounding spaces and
  the look-alikes `O`, `I` and `L` do not matter), or when the sender's key is already in
  `admins.txt`. A successful
  pairing adds the key to `admins.txt`. Every other request is dropped without an answer.

| Command | Effect |
| --- | --- |
| `/help` | the list below |
| `/stats` | members online, peer limit, uptime |
| `/peers` | who is online: 8-hex key tag, role, nick (at most 40 lines) |
| `/mod <prefix>`, `/unmod <prefix>` | make a moderator, make a moderator a user again |
| `/mute <prefix>`, `/unmute <prefix>` | observer (reads, cannot write), user again |
| `/kick <prefix>` | removes from the group; they can rejoin, and a muted member stays muted |
| `/topic <text>` | sets the topic (at most 512 bytes) |
| `/limit <n>` | peer limit, 10 to 1000; nobody online is removed by a lower limit |

`<prefix>` is the start of a member's group key as `/peers` shows it, in either case; a prefix that
matches several members gets "give more digits". Commands are case-insensitive, a swipe-reply that
carries a command works, and anything else gets a one-line error. Every message is acknowledged
(msgV3), and a resent message is acknowledged again but answered once.

## The two changes to toxcore in the founder build

`build.sh` compiles the toxcore the iOS client ships, as `infra/demo-contact` does, so that NGC
moderation, the topic lock and msgV3 behave exactly as between the clients. It applies two patches
to its copy, and stops if one no longer applies exactly, so a toxcore update in the pod gets them
reviewed.

`toxcore-founder-connected.patch`: a founder's group is connected as soon as it is restored from
savedata, the way a group is when it has just been created. Without it a restarted founder waits in
"connecting" for a sync response that only comes back to a peer that invited someone itself; members
who join a founder that came back alone never trigger one. While it waits it sends no pings, so every
member drops it after 58 s, and it gives joiners no topic. Both were seen with the probe before the
patch, and are gone with it.

`toxcore-founder-announce.patch`: the founder renews its group's announce on the DHT every 16 s per
node. A node keeps an announce for 30 s, and toxcore spaces a group's requests by the backoff it uses
for an offline friend, up to once an hour per node, so a founder that had been up for a few hours
could not be found by a join by chat id at all. Only the founder gets it: `build.sh` compiles
`onion_client.c` once more with `-DKHANDAQ_GROUP_ANNOUNCE_INTERVAL=8` for `khandaq-community`
alone, and the probe and the test client keep the clients' timing.

## Clients need toxcore that looks a group up by its chat id

Upstream toxcore walks the DHT toward a random key for every group (`m_create_group_connection`),
so the founder and a joiner announced to, and searched, different nodes: a join by chat id never
found the lone founder (300 s, no connection, on the public DHT). Since `e24bb519` every Khandaq copy
of toxcore searches by the chat id instead (`onion_friend_search_key()` in `onion_client.c`; the
desktop tree, the iOS pod and the Android `patches/apply_khandaq_group_search_key.py`, held by
`scripts/check-group-search-key.py`). The founder builds from the pod copy, so it carries the change
with no patch of its own; a fresh joiner then connects in 5 to 30 s.

A client built without it (0.2.56 and older) cannot find the group by its chat id. The probe is
built from the same tree, so a passing probe says nothing about such clients.

## Files

| File | Purpose |
| --- | --- |
| `khandaq_community.c` | the founder (single-threaded: one loop around `tox_iterate`) |
| `community_common.h` | helpers shared by the bot and its tools |
| `probe.c` | joins as a new member would and checks the group; exit 0 only when every check passes |
| `community_admin_test.c` | local end-to-end test of pairing and every command; also `--mesh`, a private local DHT |
| `community_unit_test.c` | unit tests of the shared helpers; `build.sh` runs it |
| `toxcore-founder-connected.patch`, `toxcore-founder-announce.patch` | the two changes above |
| `build.sh` | builds all four against the pod toxcore (Linux and macOS) |
| `deploy-community.sh` | builds on khandaq.org, backs up the state, installs, restarts, runs the probe |
| `khandaq-community.service` | hardened systemd unit |
| `assets/nodes.txt` | bootstrap nodes and TCP relays: a copy of `infra/demo-contact/assets/nodes.txt` |

## State: `/var/lib/khandaq-community`

**Never recreate it.** The chat id is derived from the founder key in `khandaq-community.tox`, and
every client has the chat id compiled in: a new state founds a different group that no client knows.

| File | Holds |
| --- | --- |
| `khandaq-community.tox` | the bot's identity and the group, with the founder key |
| `group.txt` | the chat id, 64 hex |
| `toxid.txt` | the bot's Tox ID, which admins add |
| `admin-code.txt` | the pairing code (mode 0600, never logged) |
| `admins.txt` | paired admins, one public key (or Tox ID) per line; `#` starts a comment |
| `limit.txt`, `topic.txt` | the peer limit and the topic the bot keeps in force |
| `muted.txt` | group keys of muted members |

The bot refuses to start (exit 3, which systemd does not restart) rather than guess when the state
does not add up: `group.txt` names a group the savedata does not hold, the savedata cannot be read,
the identity is not that group's founder, or the chat id differs from `--expect-group`. Editing
`limit.txt` or `topic.txt` while the bot is stopped changes the setting at the next start. To pair a
new admin without the code, add their public key to `admins.txt` and restart. Deleting
`admin-code.txt` makes a new code at the next start.

## Deploy

```sh
infra/community-founder/deploy-community.sh        # HOST=Khandaq by default
```

It builds on the server under `/opt/khandaq-community`, copies the state to
`/var/backups/khandaq-community` (ten newest kept, the pairing code left out), installs the binary
and the probe, refreshes `/etc/systemd/system/khandaq-community.service` and restarts the service.
The first deploy founds the group; the script waits for `group.txt`, prints the chat id and pins it
in `/etc/default/khandaq-community` (`COMMUNITY_ARGS="--expect-group <chat id>"`), and from then on
a state directory that lost its group stops the bot instead of founding another. It prints the Tox
ID for the admins, never the code. Read the code on the server:

```sh
ssh Khandaq cat /var/lib/khandaq-community/admin-code.txt
```

Then the probe runs on the server as a smoke test; `SKIP_PROBE=1` skips it. UDP port 33470 (and the
ten after it) is what the bot binds; no inbound firewall rule is needed.

## Check it

```sh
OUT=/tmp/kc infra/community-founder/build.sh
/tmp/kc/community-probe --chat-id "$(ssh Khandaq cat /var/lib/khandaq-community/group.txt)" \
    --nodes infra/community-founder/assets/nodes.txt
```

The probe joins with a fresh identity and checks: the group connects; its name; the founder online
with the role founder and the nick Khandaq; public, topic lock on, the peer limit (`--expect-limit`,
default 250); a topic (`--expect-topic` for the exact one); ourselves a plain user; nothing from the
founder; and the founder still there 75 s after joining (`--stay`), which catches a founder that has
stopped pinging. Then it leaves. About 90 s.

On one machine behind NAT, a founder on the public DHT announces its public address, which a probe
on the same machine reaches only if the router loops packets back. For local tests, run everything
on a private DHT:

```sh
T=/tmp/kc-test; mkdir -p $T
/tmp/kc/community-admin-test --mesh $T/mesh.txt --port 34700 &            # six local DHT nodes
/tmp/kc/khandaq-community --data $T/state --nodes $T/mesh.txt --port 34520 2>$T/founder.log &
/tmp/kc/community-probe --group-file $T/state/group.txt --nodes $T/mesh.txt
/tmp/kc/community-admin-test --data $T/state --nodes $T/mesh.txt --founder-log $T/founder.log
```

The mesh has no TCP relays, so members there can take a minute and a half to find each other
(each finds the founder within seconds); the tests wait for that.

The admin test pairs (a near-miss code is dropped, the right one accepted, a request without a code
never), joins with a member and fillers, drives every command with good and bad arguments, checks
the replies, the effects as members see them and the state files, and leaves the group at peer
limit 260 with a test topic, for a restart check. `--make-identity FILE` and `--preauth FILE` check
a key added to `admins.txt` by hand. `--restart-mute` mutes a member, prints "restart the founder
now" and checks that the member is still muted after the restart, for the founder, for the members
who stayed and for one who joins afterwards; restart the founder with its log appended to the same
`--founder-log`.

## Operating it

- Logs: `journalctl -u khandaq-community`. Counts and 8-hex key tags only: never a nick, a message,
  the topic text or the pairing code. Every 10 minutes the members online; every hour the traffic.
- Limits: 64 admins, 1 000 muted keys (the oldest is dropped when full), 20 logged dropped contact
  requests an hour. `MemoryMax=768M` covers a full group at the highest limit.
- A mute follows the member's group key. A member who leaves the group for good and joins again
  gets a new key, and with it no mute; mute them again.
- Replies to an admin who went offline before the answer arrived are lost; send the command again.
