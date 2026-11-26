# Groups: a member to contacts, private messages in the feed, @mentions

The owner's report of 26.11.2026, the same on every client:

1. "Copy key" on a group member, then New contact with that key, said "12 characters missing".
2. A message written with "Write privately" must stand out in the group by colour, with a note that
   only you and that member see it, and nobody else may see it.
3. Typing `@` in a group must offer the members to pick, as Telegram does, and the picked name must
   stand out in the message.

## 1. Why a member key never makes a contact

`tox_group_peer_get_public_key` returns the member's key **in this group**. toxcore makes a new key
pair for every group it creates or joins (`create_new_chat_ext_keypair` in `group_chats.c`); it is not
the key of the member's Khandaq ID and nothing in the group says which ID is behind it
(`NgcHskAnnounce.java` says the same). So:

- 64 hex characters from a group are not 76 minus 12: no nospam and checksum can complete them;
- `tox_friend_add` refuses them (no checksum), and `tox_friend_add_norequest` with them makes a
  contact that never comes online, because no Khandaq ID owns that key (iOS did this since #15).

A member becomes a contact only by the two of them exchanging their IDs, which needs the other
side's consent. The exchange below goes over the group's private channel, so it works only between
Khandaq clients, and only while both are in the group and online.

### Packets

Lossless group custom **private** packets (`tox_group_send_custom_private_packet`, lossless = true),
with the header of every Khandaq NGC packet:

| what      | bytes | contents                                                    |
|-----------|-------|-------------------------------------------------------------|
| magic     | 6     | `66 77 88 11 34 35`                                         |
| version   | 1     | `01`                                                        |
| pkt id    | 1     | `61` offer, `62` accept                                     |
| address   | 38    | the sender's own Tox address (`tox_self_get_address`): key 32, nospam 4, checksum 2 |
| nonce     | 8     | random bytes of the offer; the accept repeats them          |

54 bytes exactly; any other length, or an address whose checksum does not hold, is dropped.
`61`/`62` are free in every client (history sync uses `01`–`04`, files `11`–`14`, video `21`,
audio `31`, edit, delete, reaction and leave notice `41`–`44`, the HSK announce `50`). A client older
than this drops them: its dispatcher knows only its own ids.

### Who does what

**A taps "Add to contacts" on member B** (the member sheet, the member's screen, or the New contact
line holding B's group key, see below):

1. B must be online in the group now; otherwise the place says
   "B is offline in this group: ask again when they are back" and nothing is sent.
2. A sends the offer to B, keeps it as a pending offer `{group id, B's group key, B's group name,
   nonce, time}` on disk for 30 days (a new offer to the same member replaces the old one), and says
   "Request sent: B will be in your contacts once they accept".

**B receives an offer from member A:**

1. Drop it if the length or the checksum is wrong, if it is from ourselves, from a member muted or
   kicked, or the second offer of that member within 60 seconds.
2. If A's key (the first 32 bytes of the address) is already a contact: answer with an accept at
   once, ask nothing.
3. Otherwise it is an incoming contact request, in the place where Tox contact requests are shown,
   with A's group name and the line "From the group «G»". Its accept and its decline are the
   request's own. Declining sends nothing, as with a Tox request.
4. **Accept** does two things:
   - `tox_friend_add(A's address, message)` with the message
     `[KQ|c|<nonce as 16 lowercase hex>]` + `\n` + "I accepted your request from the group «G»". toxcore
     keeps sending it until A is online, wherever A is.
   - the accept packet (B's own address, the offer's nonce) to A over the group, if A is online
     there.
   The new contact is named with A's group name until A's own name arrives.

**A receives either of them**, whichever comes first, and the second one finds the contact there
and changes nothing:

- the **accept packet** from member B of a group, with the nonce of a pending offer to that member:
  `tox_friend_add_norequest(B's key)`, the contact named with B's group name, the offer done,
  "B is now in your contacts";
- a **Tox contact request** whose message starts `[KQ|c|` with the nonce of a pending offer:
  accepted without asking (`tox_friend_add_norequest`), the same as above. A request with that
  prefix and an unknown nonce is an ordinary request, shown without the prefix.

**Both sides remember** the member behind the contact: `{group id, group key} → contact key` (and the
member's full address), on disk. The member's sheet then offers "Open the conversation" instead of
"Add to contacts", and "Copy ID" copies the full 76-character ID. A member who is not a contact
has no "Copy ID" in the sheet: their group key is shown on their own screen as "Key in this group",
with copy, and with the sentence "This key is theirs only in this group: it does not add them as a
contact. Use Add to contacts.".

**The New contact line** (command line, New contact screen) that holds 64 hex characters equal to
the group key of a member in one of our groups shows, instead of "12 characters missing", the row
"«B» in the group «G»: Send a contact request", which runs the offer above. (A group key of the same
length keeps its "Join the group" row.)

## 2. Private messages in the group feed

An NGC private message (`tox_group_send_private_message`) reaches only the one member it is sent to;
toxcore gives it to nobody else. Every client shows it, to its sender and to its recipient, in the
group's feed among the other messages, and also in the member's private conversation where the
client has one. In the feed it is drawn so that it cannot be taken for a message to the group:

- the whole entry stands on the `private-soft` ground (`design/system/tokens.json`), with a small
  lock and the note in the `private` colour on its first line:
  - our own: **"Privately to B: other members don't see this"**;
  - received: **"Privately to you: other members don't see this"** (the author's name over it as
    usual);
- the long-press actions are the entry's own, except Reply: a reply into the group would quote the
  private words to everyone, so Reply on a private entry either opens a private answer to that
  member or is not offered.

**Nobody else may get it**, so a private message is never:
- served by the group history sync (packets `01 02` / `02 02`) or any other relay of group history;
- counted or forwarded as a message to the group (no push "wake" to the other members, no delivery
  receipt to the group);
- quoted into the group by Forward without the user choosing to.

Each client checks its own history-sync serving code for this and has a test that a stored private
message is not in what it serves.

## 3. @mentions as in Telegram

The wire format stays the one all three clients read since 2026-08: a block before the body
(after a reply header, if any)

```
[KQ|m|<MEMBER KEY HEX>:<handle>;<MEMBER KEY HEX>:<handle>][KQ/m]\n<body with @handle in it>
```

**Handles** are made the same way everywhere, from the member's name in the group:
letters and digits of any script are kept, with the combining marks that follow them (Indic vowel
signs), a run of spaces, `-` or `.` becomes one `_`, everything else is dropped, `_` at the end is trimmed; an empty result is `u` + the first 8 hex of the member
key; a handle that two members would share gets `_` + the first 4 hex of the member key.
`@Иса Дагестани` → `@Иса_Дагестани`, `@محمد علي` → `@محمد_علي`.

**The field:** `@` typed at the start of a word (after the start of the text, a space or a line
break) opens the members above the field: tile, name, `@handle`, yourself left out, online members
first, then by name. What follows the `@` filters it (start of the handle or anywhere in the name,
case and diacritics ignored, any script). A tap, or Enter/Tab on the desktop, puts `@handle ` in place
of what was typed; Escape, a space, or the caret leaving the word closes it. Arrow keys move the
choice on the desktop.

**Sending:** every `@token` in the body (`@([\p{L}\p{M}\p{N}_]+)`, Unicode-aware on every client) that is
the handle of a member at that moment goes into the block with the member's key; others stay plain
text.

**Showing:** each `@handle` listed in the block is drawn as DESIGN.md draws a mention (ink and bold,
which holds on both themes and on the selected ground) and opens that member (their sheet or screen)
on a tap. A message that mentions you has the selected ground (as now) and
notifies even where the group is set to notify only on mentions. The previews in the chat list and
the notifications show the body without the block.

## Tokens

`private` and `private-soft` were added to `design/system/tokens.json` with their contrast rules;
`design/system/build.py` writes them to Android (`kq_colors.xml`), iOS (`DesignTokens.swift`) and the
site; the desktop takes them through `khandaq-desktop/tools/gen-design-tokens.py`.
