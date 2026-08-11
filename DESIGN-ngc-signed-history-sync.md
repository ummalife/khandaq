# Design — authenticated authorship for NGC history sync

**Status:** proposal, awaiting sign-off. Nothing here is implemented.
**Closes:** external audit #2, finding 1 (the half that needs a protocol version).
**Author:** written 11 Aug 2026 as the follow-up promised in `SECURITY-REVIEW2-RESPONSE.md`. Lives at the repo root, not under `docs/`, because `docs/**` is gitignored here.

---

## 1. The defect, stated precisely

A history-sync packet carries the *alleged original author* as 32 raw bytes that nobody signed.

Wire layout today (`0x02` = text history, `0x03` = file history), from
`HelperGroup.handle_incoming_sync_group_message`:

| offset | size | field |
|---|---|---|
| 0 | 6 | magic `66 77 88 11 34 35` |
| 6 | 1 | version, always `0x01` |
| 7 | 1 | pkt id (`0x02`) |
| 8 | 4 | message id |
| 12 | 32 | **alleged original author pubkey** |
| 44 | 4 | timestamp (low 4 bytes of an 8-byte BE value) |
| 48 | 25 | alleged author display name |
| 73 | … | UTF-8 text |

The transport authenticates the **syncing** peer — `tox_group_peer_get_public_key__wrapper(group_number, peer_id)`, which we already read — but offsets 12 and 48 are attacker-chosen. Any group member can therefore manufacture a message that displays as another member's, with a chosen timestamp inside the sync window.

**What is already mitigated** (shipped): unauthenticated sync packets cannot write the persistent peer table; synced rows are never re-served, so a forgery cannot launder through an honest client; packets claiming to originate from us are rejected; private member-to-member messages are not served through history sync at all; and per-group row/byte/notification budgets bound the flooding half of the finding.

**What is not**: the attribution itself.

---

## 2. Why the obvious fix does not exist

The instinct is "sign it with the Tox identity key". That key is **Curve25519**, an encryption key. `crypto_sign` needs **Ed25519**. libsodium converts Ed25519 → Curve25519 and deliberately not the reverse, so there is no way to produce a signature that verifies against a peer's Tox public key.

Deriving a fresh Ed25519 key from the Tox secret is easy; **publishing it credibly is the hard part**. Announcing "my signing key is X" inside an unsigned packet is circular — that announcement is exactly as forgeable as the field we are trying to protect.

## 3. The idea that breaks the circle

**toxcore already authenticates the sender of a live group packet.** Only *relayed* history is unauthenticated. So the live channel is a trustworthy carrier for a key announcement, and history verified against a key learned that way inherits that trust.

---

## 4. Design

### 4.1 History-signing key (HSK)

Each profile holds an Ed25519 keypair, generated once and stored in the encrypted profile database beside the other secrets. It is *not* derived from the Tox secret key — deriving it buys nothing (the binding comes from the announcement, not the derivation) and would couple two key lifetimes for no reason. It is regenerated when the Tox identity changes.

### 4.2 Announcement — new packet `version=0x02, pktid=0x50`

```
magic(6) | 0x02 | 0x50 | hsk_pub(32) | valid_from_ts(8, BE) | sig(64)
```

`sig` is over `"KQ-HSK-ANNOUNCE-1" || tox_pubkey(32) || hsk_pub(32) || valid_from_ts(8)`, made with the announced key itself. The self-signature proves possession; the **binding to the Tox identity comes from the authenticated transport**, not from the signature.

Sent on joining a group, on key change, and periodically (a peer that joins later must be able to learn it — see §6, open question 1).

Receivers store `(group_id, tox_pubkey, hsk_pub, first_seen_ts, last_seen_ts)`. **First announcement wins**: a later announcement with a different `hsk_pub` for the same `tox_pubkey` is recorded but does not replace the first one automatically, because silently accepting a replacement re-opens the impersonation door for anyone who can get a packet in first after a peer goes offline. Replacement requires the peer to be currently connected *and* the old key to be absent for longer than a grace period.

### 4.3 Signed history — new packets `version=0x02, pktid=0x02 / 0x03`

Identical layout to today's, plus a 64-byte signature appended after the text, with the text length made explicit so the signature cannot be confused with content:

```
magic(6) | 0x02 | 0x02 | msg_id(4) | author_pub(32) | ts(8, BE) | name(25) | text_len(4, BE) | text | sig(64)
```

Two changes beyond the signature, both worth making while the version is being bumped: the timestamp becomes the **full 8 bytes** (today only the low 4 are transmitted — a latent 2038-class truncation), and the text is explicitly length-prefixed.

`sig` is over:

```
"KQ-HISTSYNC-1" || group_id(32) || author_pub(32) || msg_id(4) || ts(8) || sha256(text)(32)
```

All fields fixed-width, domain-separated by the prefix, so no two distinct messages share a signed pre-image.

### 4.4 Verification on receive

1. Unknown version → ignored by every shipped client (see §5). Unknown `hsk_pub` for the claimed author → store the row **unverified**, never notify.
2. Signature fails → drop the packet. Not "store unverified" — a *present but wrong* signature is an attack, not an old client.
3. Signature verifies → store as **verified**, normal handling.

### 4.5 Transition and display

A new `GroupMessage.author_verified` column: `VERIFIED`, `UNVERIFIED_LEGACY` (no signature, sender predates the rollout), `UNVERIFIED_NO_KEY` (signed variant but we never learned the author's key).

Anything not `VERIFIED` renders with a marker on the bubble and is excluded from notification. Old Tox conference bubbles already carry an orange/green synced-vs-direct dot (`ConferenceMessageListHolder_text_incoming_not_read`), so there is precedent for the affordance; the NGC group holder has no such indicator today and needs one.

**Note:** adding this marker to NGC bubbles is useful on its own and does not need the protocol change. It is the one piece of this document that could ship first.

---

## 5. Backward compatibility — verified on all three platforms

This was step 1 of §8 and it is **done**. Every shipped parser gates on the version byte and drops anything it does not recognise:

| platform | check | file |
|---|---|---|
| Android | every branch requires `data[6] == 0x1`; unknown version falls to the `else` and is ignored | `MainActivity.android_tox_callback_group_custom_packet_cb_method` |
| iOS — history sync | `if (bytes[6] != kOCTNgcHistLayer) return;`, `kOCTNgcHistLayer = 0x01` | `OCTNgcGroupHistSync.m:143` |
| iOS — edit/delete/reaction | `b[6] != 0x01 → return NO` | `OCTSubmanagerGroupsImpl.m:2534, 2708` |
| desktop | `data[6] != NGC_VERSION → return`, `NGC_VERSION = 0x01` | `core/core.cpp:1880` |

So a shipped client receiving `version=0x02` does nothing at all — no mis-parse, no crash, no half-message. The version byte is a usable upgrade lever, and the design can rely on it.

### 5.1 Packet-id registry (so the next feature does not collide)

Ids observed in use today, under `version=0x01`:

| id | meaning |
|---|---|
| `0x01` | history-sync request |
| `0x02` | history-sync text |
| `0x03` | history-sync file |
| `0x11` | group file, single-packet |
| `0x12` | chunked file BEGIN |
| `0x13` | chunked file CHUNK |
| `0x14` | chunked file REQUEST |
| `0x21` | live audio |
| `0x31` | live video |
| `0x41` / `0x42` / `0x43` | message edit / delete / reaction |

`0x50` is free, which is why §4.2 uses it. **Anything new must be added to this table in the same commit that introduces it** — the ids are spread across three codebases and there is no other place they are written down.

That makes the rollout safe in both directions:

- **New → old:** the old client ignores the signed packet. It loses that history item, exactly as if the syncing peer had not sent it. Nothing is corrupted.
- **Old → new:** the new client receives `version=0x01`, marks the row `UNVERIFIED_LEGACY`, and displays it with the marker.

Dual-emitting `0x01` and `0x02` for the same message would keep old clients fed, but it also lets an attacker drop the signed copy and keep the unsigned one — the classic downgrade. **Recommendation: do not dual-emit.** Signed clients emit only `0x02`; old clients simply receive less history during the transition, and the transition ends when the fleet has turned over.

---

## 6. Open questions — these need your decision

1. **Late joiners.** A peer that joins after an announcement has no key for older authors, so their history arrives `UNVERIFIED_NO_KEY`. Options: periodic re-announce (bandwidth, trivially simple), announce-on-request (one more packet type), or accept the gap. *Recommendation: periodic re-announce on a long timer, plus on join.*
2. **Key rotation / reinstall.** A user who reinstalls generates a new HSK. Under "first announcement wins" their new key is refused until the grace period elapses, so their history reads unverified for that window. Is that acceptable, or should a currently-connected peer be allowed to replace its own key immediately? *Recommendation: allow immediate replacement only while the peer is connected in the group, and log it.*
3. **Do we quarantine display before the protocol lands?** §4.5's marker can ship on its own. It removes the deception without removing the forgery. *Recommendation: yes, ship it first — but it is a change to the most-used screen in the app and needs device QA, so it should not be bundled with a release nobody can QA.*
4. **Cross-platform scope.** Android, iOS and desktop all parse these packets, and signing must land on all three before it means anything. The version gate itself is no longer a question — §5 confirms all three. Worth noting for scheduling: desktop is a **history-sync consumer only** (`core.cpp:1600` says it never emits a request), so it needs verification but not signing, which makes it the cheapest of the three.

---

## 7. What this does not fix

A group member can still forge history **as themselves** with a false timestamp, and can still withhold history. Neither is impersonation, and neither is in scope for this finding.

## 8. Rough sequencing

1. ~~Confirm the version gate on iOS and desktop parsers (§5).~~ **Done — all three gate on the version byte and ignore unknown values.**
2. Unverified-display marker on NGC bubbles (§4.5), with device QA.
3. HSK generation + storage + announcement, all three platforms, with verification disabled.
4. Emit signed history, still accepting `0x01`.
5. Flip display: unsigned → `UNVERIFIED_LEGACY` marker.
6. When the fleet has turned over, stop accepting `0x01` history entirely.

Steps 3–6 are a release each. This is not a one-batch change, which is why it was not attempted as one.
