# Khandaq design language: direction B, Air

The working design language for every Khandaq client: iOS, Android, desktop and the site. New screens
are built only from what is written here and shown on the design system page. Research and the
design plan behind it are in `docs/research.md` and `docs/design-rules.md` (local, Russian); the
screen-by-screen plan is `design/redesign/02-plan.md` (local as well, like everything under `design/redesign/`).

## Sources of truth

| What | Where |
| --- | --- |
| Tokens (colour, type, spacing, radius, stroke, elevation, sizes, breakpoints, motion) | `design/system/tokens.json` |
| Generated client files | `design/system/build.py` writes `dist/tokens.css`, `dist/tokens.js`, iOS `default-theme.yaml`, `dark-theme.yaml`, `DesignTokens.swift`, and the tables in this file |
| Every component, live, both themes, reduced motion | `design/system/index.html` with `site/system.css` and `site/system.js` |
| Fonts (OFL) | `design/system/fonts/` |
| All checks at once | `design/check.sh` (tokens up to date, contrast, scripts, forbidden glyphs) and `node design/system/smoke.mjs --out DIR` |

`build.py` fails when a colour pair drops below its contrast threshold, when the page CSS breaks at a
width that is not a breakpoint token, or when a generated file (this one included) is out of date.

## Direction

Paper and ink. The main screen is split by reachability, because Khandaq has no server in the middle:
a message stays with you until the other person comes online. Colour appears only when it reports on
delivery, and each colour has one meaning:

- green `live`: the person is reachable, the message leaves at once;
- amber `transit`: the message waits and will leave by itself (the On its way tray);
- red `danger`: broken, blocked, not sent;
- blue `focus`: the keyboard focus ring and nothing else.

Six `tone-*` colours tint author names in groups and the initials on tiles (on `sunken`). The tone is
fixed by a key, the same on every client: FNV-1a over the key in upper case, UTF-8, modulo 6; the key
is the person's public key, or the chat id for a group. Test vectors live in
`khandaq-ios/AntidoteTests/AirListModelTests.swift`. Everything else is `ink` on `bg`, `surface` and
`sunken`.

### The owner's choice

- Among directions A (Seal), B (Air) and C (Circle): «Мне B больше вариант нравится» ("I like B more").
- On 10.11.2026, after B's brand variants Tail and Moat were shown: «Я выбираю пункт B как было» ("I
  choose B as it was"), then the option «B + правила брифа» (B plus the brief rules):
  - B's palette, IBM Plex Sans, layout, delivery check marks and the amber tray stay;
  - labels in normal case, no small caps with letter-spacing;
  - times and counters in Plex Sans with equal-width digits; monospace only for IDs and keys;
  - no coloured stripes on the left edge;
  - status dots never pulse.

### Rejected directions

| Direction | What it was | Owner's words |
| --- | --- | --- |
| A, Seal | The person is a key, not a phone number; identity first | chose B instead: «Мне B больше вариант нравится» |
| C, Circle | Close people large, voice instead of the keyboard | chose B instead: «Мне B больше вариант нравится» |
| B Tail | The logo's speech-bubble tail replaces check marks and grows as the message travels; emerald palette from the app icon, Jost and Noto Kufi Arabic | «Я выбираю пункт B как было» |
| B Moat | A moat band separates people reachable now from the rest and sits over the tray | «Я выбираю пункт B как было» |

The pages stay in `design/redesign/directions/` as a record; nothing from them goes into the clients.

## Colour

Rules on top of the generated palette below:

- Surfaces separate by colour: white `surface` on grey `bg`, `sunken` blocks inside a surface. No
  frames around cards, statuses or dialogs.
- `line` is for separators that carry structure (table rows, a sticky header edge), never decoration.
- Hover, pressed and derived grounds are tokens too (`ink-hover`, `ink-pressed`, `ink-badge`,
  `live-cell`, `transit-tray`); no `color-mix` or opacity tricks in client code.
- Shadows only under overlays (`elevation-*` in `shadow`): toast, dialog, sheet, a row while it moves.
- On a `selected` row the status word is `ink-2` and only the dot stays `live`: `live` text is
  guaranteed on `bg`, `surface`, `raised` and `live-soft`, while on `selected` it holds just the 3:1
  a graphic needs (4.17:1 in the light theme, short of the 4.5:1 for text).

## Typography

- **IBM Plex Sans** for all text. All its digits share one width by default, so times, sizes and
  counters line up without any font feature.
- **IBM Plex Sans Arabic** for Arabic: the Arabic script of the same family, not a third typeface.
- **IBM Plex Mono** only for Tox IDs and keys, grouped by four. Never for times, counters or initials.
- Chinese falls back to the system face (PingFang SC, Noto Sans CJK).
- Weights 400, 500, 600. Normal case everywhere, no italics for emphasis.
- iOS scales every role with the person's text size (`UIFontMetrics`, see `KQFont` in
  `DesignTokens.swift`); the system face stands in until the Plex files are in the app bundle.
- At the accessibility text sizes on iOS nothing is cut: a button's words wrap whole and the button
  grows taller, a note takes as many lines as its words need, and the time of an entry stands above
  its text (see the conversation on the phone).
- Glyphs and the header keep their size while the text grows. At the accessibility text sizes on iOS a
  long press shows such a control large in the middle of the screen with its name (the Large Content
  Viewer, as the system does for its own bar items): the icon buttons of Air, the paperclip, the
  microphone and the arrow of the field, the buttons of a conversation's header, its title with the
  whole name the bar cuts, and the round back buttons of the older screens. A tap stays a tap.
  Lifting the finger on a control of the field acts on it (the paperclip opens its menu). In a
  navigation bar the bar shows the viewer from the properties of the item's view, and lifting the
  finger there does nothing; that view has no interaction of its own: with one, the title showed
  nothing. The microphone records while it is held: the viewer opens with the hold, and lifting the
  finger ends the recording only. The paperclip and the microphone of the field and its arrow grow to
  28 points in their 44 point targets at the accessibility sizes; at the other sizes they keep their
  size, so the store frames stay as they are.

## Layout

- **Phone.** No tab bar. Air on top: the "you" block (your name and how your node reaches the
  network) with Settings and Create, the command line "Find a chat or paste an ID", the pills
  Requests, In transit, Favorites with their counts. The list: Requests, Online now, Offline, Just
  for you; while searching, Messages. The conversation is a transcript without bubbles: time in its own left
  column (`time-column`), delivery marks on the right, the On its way tray above the composer. New
  contact is a bottom sheet with the ID field and its ruler. Touch targets are at least 44 pt; small
  marks (filter pills, inline Retry) keep their drawn size and get a 44 pt touch area around them.
- **Desktop.** The list column is `list-column` wide, conversation text stops at `text-measure`. The
  dossier opens from the conversation header over the transcript; the list stays. The command line
  takes `/`.
- **Radius shows the level of an element:** control 7, field 8, card 10, sheet 14, `round` for
  counters, badges and switches.

## Motion

- Only transform, opacity and clip-path move. Durations and curves are tokens; script-built keyframes
  read them at run time (`token()` in `design/system/site/system.js`).
- **Signature gesture, once per event: the person came online.** Their row moves from Offline to Online
  now (`layout`, FLIP) and the tray unloads into the transcript one message at a time (`stagger`).
  In code: `momentMove` and `momentTray` in `design/system/site/system.js`; the request accepted
  moment is `momentAccept`, the dossier is `toggleDossier`.
- **Reduced motion:** nothing travels; states change with a fade of at most 120 ms. Nothing in the
  product animates without end: waiting is said in words next to a still dot.

## Components

All live on the design system page, in both themes:

| Component | Class | Notes |
| --- | --- | --- |
| Buttons: ink, plain, quiet, danger; busy; disabled with a reason | `kq-btn` | busy is a still dot plus words |
| Icon button with a tooltip after 600 ms | `kq-icon`, `kq-tip` | `control` on desktop, 44 pt on iOS, 48 dp on Android |
| Text field, error | `kq-input` | focus ring is the global 2 px `focus` outline |
| Command line | `kq-cmd` | filters the list; a pasted ID opens New contact |
| ID field with ruler and real checksum | `kq-id-input`, `kq-ruler` | `mono-entry`; checksum as toxcore `data_checksum` |
| Composer with delivery hint | `kq-compose`, `kq-send`, `kq-hint` | grows to five lines |
| PIN | `kq-pin` | |
| Segmented control, switch, check box, radio | `kq-seg`, `kq-switch`, `kq-check`, `kq-radio` | |
| Filter pills, list rows, section heads, tiles | `kq-pill`, `kq-row`, `kq-sec-head`, `kq-tile` | initials in Plex Sans |
| Link mark, day strip, message states | `kq-lm`, `kq-cells`, `kq-state` | |
| Transcript, day head, tray | `kq-entry`, `kq-day`, `kq-queue` | |
| Incoming request, group invitation, read-only notice | `kq-req`, `kq-readonly` | |
| Skeleton, progress | `kq-skel`, `kq-bar` | skeletons stand still |
| Toast, dialog, sheet | `kq-toast`, `kq-dialog`, `kq-sheet` | the only elements with a shadow |

## Screen decisions

Screens are being rebuilt in stages (`design/redesign/02-plan.md`, section 4). Stage 1, the system and
the frame, is done; screen decisions are added here as each screen lands in the clients.

| Screen | Decision | State |
| --- | --- | --- |
| Theme choice on iOS | System, light or dark in Settings; colours resolve per trait collection | in the iOS client |
| Main screen Air (E1, E2) | One screen instead of four tabs, sections by reachability | in the iOS client; next Android, then desktop |
| Conversation with one person (E5, E6, E7, E10, E16) | A transcript with a time column, the In transit tray, the composer with its delivery hint, the dossier from the header | in the iOS and Android clients; next desktop |
| Block (contract for every client) | A local block list of public keys: Block in the dossier, the list in Settings > Privacy > Blocked | in the iOS client; next Android, then desktop |
| New contact and the command line (E11, E3) | The ID ruler with a real check, Send request above the keyboard, the start of an ID in the command line | in the iOS client; next Android, then desktop |
| Requests and group invitations (E12) | Cards above the sections until answered; Decline is taken back from a toast, not asked about | in the iOS client; next Android, then desktop |
| Groups (E13, E14, E15) | Members and who is online in the header, who gets a message now, a read-only bar for observers, the dossier with roles, New group as one sheet | in the iOS client; next Android, then desktop |
| Files, voice and calls (E8, E9) | A file as an entry with its state in words, the voice player, files In transit, access asked before a call, the call screens with the controls in the thumb zone | in the iOS client; next Android, then desktop |
| You (E17) | The QR card and Share ID first, name and status edited where they stand, the ID in its three parts, Log out that waits for the password, Delete set apart | in the iOS client; next Android, then desktop |
| Settings (E18) | Sections by task, each a screen of its own; the theme as a segment that follows the system by default; the profile file in its own section; the background mode's cost in one phrase | in the iOS client; next Android, then desktop |
| Network state (E4) | A quiet line under your name that opens the Network screen in words; the hint under the field speaks for your node while it connects; a reconnect asked for every 15 s while offline with a network; the connection pill retired | in the iOS client; next Android, then desktop |
| In-app notifications (E22) | A band under the safe area with the chat's tile, the sender and the text; a tap opens the chat, a swipe up puts it away; what was notified while away comes without a band | in the iOS client; next Android, then desktop |

### Air on the phone

Decided while building it in the iOS client (11.11.2026); Android and desktop follow the same rules.

- **No tabs.** Air is the root of one navigation stack. Settings opens from its button, the profile
  from the "you" block, New contact from Create; each is pushed over Air. After a theme or language
  change the same screen comes back over Air. The iPad keeps its split view.
- **One list for people, groups and requests.** Sections by reachability: Requests, Online now,
  Offline, Just for you. A contact without a chat is listed too and opens a new chat. Ties within a
  section are broken by name, so rows do not jump while nothing changes.
- **Reach in a few words**, not a status: "online", "online, relay", "connecting", "waiting for
  reply", "5 min ago", "3 h ago", "2 d ago", "a while ago"; for a group, "4 online". The words come
  from the link the app really has, not from the status the person set.
- **In transit** counts our own messages that have not left yet: texts not delivered to a person
  who is offline (while they are online the same texts are leaving or sent, as in the conversation),
  files queued for a person who is offline, group messages still pending. The pill shows the count in
  amber, and the row says how many are waiting.
- **Tiles** show the person's photo when they set one; otherwise initials in their tone. Your own
  tile is ink; Saved messages is a bookmark; a person who has not accepted your request yet is a
  dashed outline.
- **Command line.** Typing filters the list and, while it has focus, finds messages too. A whole Tox
  ID pasted into it opens New contact with the ID filled in; the start of one shows a New contact row
  (below, New contact and requests).
- **Select** is in the row menu, not in a header Edit button: the header stays the same on every
  visit. While selecting, the navigation bar shows the count and Delete.
- **Sideways swipe on the list** moves between the filters: everything, Requests, In transit,
  Favorites. The active pill pressed again returns to everything.
- **At the accessibility text sizes** (stage 5) your name and the line under it stand under your tile
  at the full width of the header, with Settings and Create on the tile's row; the letters of a tile
  stop growing at the largest regular size, as the tile itself does. The pills grow with their words
  instead of the words standing out of them. A card puts its time under its tag and its answers one
  above the other, and a toast puts Undo under its words. Beside the tile, the time or the action,
  a word as long as "ретранслятор" or "Приглашение" broke inside itself at AX5 (on the 375 pt phone
  already at AX3). A row of the list stacks too: the whole name, the reach under it, then the time,
  the last message on up to two lines and the flags; beside each other they left a few letters of the
  name already at AX3. A single word wider than the row (the long demo name at AX5) still breaks.

Deferred, with the screens they belong to:

| What | Why not now | Where |
| --- | --- | --- |
| Pinned chats | needs a stored order, a new field in the chat store | stage 3, list |
| Mute and mark read as swipe actions | mute exists only for groups (`groupNotificationsSilent`); a one-to-one mute needs a new stored field | stage 3, list |
| A flag for messages that mention you | the list would need a count of unread mentions per chat, which nothing keeps yet | stage 3, groups (E13) |
| "Paste ID" button for the clipboard | a programmatic clipboard read makes iOS ask for permission; pasting into the command line already works | stage 3, new contact (E11) |

### The conversation on the phone

Decided while building it in the iOS client (11.11.2026); Android and desktop follow the same rules.

- **A transcript, not bubbles.** Each entry: the time in a 38 pt column of tabular figures (wider
  with a large text size), the text, and at the far end the mark of our own texts. The author shows
  only where a run starts (same author, same day, under five minutes apart): "You" in `ink-2`, the
  other person by first name in their tone. The day ("Today", "Yesterday", a date) heads its first
  entry at the text column. No wallpaper. A new entry rises 8 pt and fades in; with reduced motion it
  only fades.
- **Marks:** amber clock in transit, faint clock while leaving, a check when sent, two checks when
  delivered, red when the network refused it. Notes for yourself carry none.
- **Header:** the name with a chevron opens the dossier; search, video and call sit beside it. While
  the person is offline a call says why it will not get through and offers to wait (see Files, voice
  and calls). Attachments moved into the dossier. Under the bar a band
  with the link mark, the presence line ("online since 9:40, direct link", "offline for 25 minutes")
  and the day strip.
- **Day strip:** 24 clock hours, each the latest time that hour came round. The marks are taken by
  this device only, hourly, kept for seven days in `air-local.json` next to the profile (keys hashed,
  outside the Realm schema). With no marks it says so; "usually" needs marks on two days at least.
- **In transit tray,** between the log and the field on the `transit-tray` ground, no stripe: our
  texts that wait for a person who is offline. Folded it reads "3 in transit"; unfolded it adds the
  line about the wake signal and the entries (at most 28% of the screen, then it scrolls); while the
  keyboard is up the wake line steps aside, the hint under the field says the same. An entry
  can be edited (texts with a msgV3 hash, not replies) or cancelled while it waits. When the person
  comes online the entries leave for the log one by one, 90 ms apart, their marks turn to a check and
  then two, and the tray folds; a day head appears if the day changed. Reduced motion: one fade.
- **Refused messages** stand above the tray with the red mark and "Not sent. Retry". Retry sends the
  same text through the same path as the field; a tap also offers Edit and Cancel. objcTox keeps no
  row for them, so each client keeps them itself, per chat with their text and time, until Retry
  sends them or the person cancels them: they come back after a restart, and removing or blocking
  the contact drops them. iOS keeps them in the sealed part of `air-local.json` (see Block).
- **Composer:** a framed field with the paperclip, five lines at most, the ink send button as tall as
  one line (44 pt), pressed at 0.97. The hint under it cross-fades without a jump: "Goes right away:
  Amina is online" (live dot), "Waits in transit: Yusuf is offline. It will go on its own" (transit
  dot), "The note stays only on this device" for yourself. The byte counter appears in the last
  tenth of 1334 bytes, what one Tox message carries after the msgV3 header; Send stops past it.
- **Dossier:** unfolds from the top under the bar in 440 ms while the conversation dims, the chevron
  turns; the same tap, a swipe up at its end, the dimmed area or the escape gesture close it.
  Sections: name and status with Rename; Link (direct or through a relay, drawn as a path); Last day
  (the strip with hours and "usually"); Notifications with Mute; Key verification; Key in groups of
  four with Copy key and QR code; Files; then Block and Remove, both behind a confirmation.
- **Local only:** mute and the verification date stay on this device. Mute silences this device's
  alerts; the push relay still sends its wake signal (no text in it). Block is local as well, below.
- **For yourself** stays last in the list with the bookmark tile: no marks, no tray, no dossier, the
  hint says the note stays on this device, and the header keeps search and files.
- **At the accessibility text sizes** (stage 5) the time leaves its column for the line above the
  text, and at the start of a run the time and the author are one line that wraps at its words, so a
  long first name goes whole to the next line. The band keeps the presence line, whole, and leaves
  the day strip and its hours to Last day in the dossier, a tap away (VoiceOver still reads them on
  the band): beside the strip the hours were cut. The tray's title and the words of the link picture
  in the dossier wrap instead of being cut. The log keeps room to read in: the band folds away while
  the keyboard is up and wherever the log would keep less than a quarter of the screen with it, the
  tray folds away too while the keyboard is up, and the refused messages and the tray take at most
  half of the room between the band and the field, their messages scrolling in that half. The hint
  under the field says what the band and the tray said, and the name in the header opens the dossier
  the band opened. At AX5 a refused message and the band had left the log 45 points, and the open
  tray, or the keyboard with the tray, none. The band folds on the next turn of the run loop, with a
  layout of the whole screen, never in the middle of a layout: folded while the open tray was laid
  out, in Arabic at AX5 it left its room above the log and the tray stood over the field. Open, the
  tray scrolls its explanation with the messages: at AX5 the six lines of it filled the screen and
  the title lost its end. The hint under
  the field shows all its words, and the length counter takes a line of its own under them, so a long
  name keeps the width of the line. The hint had kept only its reserved room (one line of three at
  AX5, two of three at AX3) in a conversation with a person: the hidden selection toolbar lies over
  the composer edge to edge and pressed it with the 44 points it hugs. The toolbar no longer hugs, and
  the hint's words hold their height above the default. Group chats put that toolbar above the field.
  The paperclip, the microphone and the arrow of the field grow to 28 points; they, the buttons of
  the header and its title show large with their names on a long press (see Typography). Left as the
  system draws them: a long name in the navigation bar cut at its end (a long press shows it whole),
  a word broken inside itself in a system action sheet, and the New contact form scrolling in the
  room above its bar with the keyboard up at AX5.

Deferred, with the screens they belong to:

| What | Why not now | Where |
| --- | --- | --- |
| "Copy ID" in the dossier | the 76-character ID of a contact is not stored, only its 64-character key | needs the ID kept on request |

### Android stage 2: the conversation

Built in the Android client on 11.11.2026 to the rules above, with the same string keys
(`air_delivery_*`, `air_presence_*`, `air_compose_hint_*`, `air_day_*`) in English, Russian, Arabic
and Chinese. Only what differs from iOS, or is particular to Android, is listed here.

- **Where it lives.** `MessageListActivity` keeps the old log and everything on it: replies, edits,
  reactions, forwarding, files, voice, location, calls, search. `AirChatPanel` adds the band under the
  bar, the tray, the hint and counter under the field and the dossier; `AirTranscript` draws text
  entries as a transcript; `AirConversationModel` and `AirContactMarks` are ports of the iOS model and
  marks, with the same unit cases.
- **Header** (stage 3): search and call are icons, as on iOS, and video is one too from 400 dp of
  width. Below that, at 360 dp, video stays in the overflow menu so that the name keeps ten
  characters ("Khandaq De..." at 360x640); the overflow keeps the log filters and "All messages". The
  name opens the dossier, and so does Information in the menu of a contact in the list.
- **Typing** is the word "typing" in the presence line. The three moving dots are gone: the brief
  bans endless animation.
- **Time column:** `size-time-column-phone` (38 dp), measured again for the system text size and the
  digits of the language so that "23:59" stays whole, at most twice as wide. At the accessibility text
  sizes on iOS the time and the name stand on a line of their own above the text, which then takes
  the whole width, so no word breaks inside a narrow column.
- **Digits** follow the app's language, as the counts of the list do: the time column, the tray, "online
  since", the hours of the day hints, the axis of the day strip and the "24" of its heading are written
  in the language's digits (Arabic-Indic in Arabic). The strings keep "24" and "%d:00" as on iOS;
  without this one Arabic line read "١٨:00" next to "24".
- **Mute switch** is `kq-switch`: a white knob in both states and both themes on a `line-strong`
  track, `switch-on` when on. A knob in the surface colour vanished on the dark theme.
- **Day strip and verification date** are kept by `AirContactMarks` in the encrypted globals table
  of the profile, one JSON value under a hash of the contact's key, seven days of marks; nothing goes
  over the wire. Mute is the existing `FriendList.notification_silent`, so no schema changes.
- **In transit tray.** A row is a text of ours to a person who is offline that has not been
  delivered. A text that never left the phone is rewritten in place when edited, so the person gets
  the edited text as a new message; a text that left once and came back to the queue is corrected
  with the edit packet. Cancel deletes the row, and sends the delete packet when the text had left
  once. While the person is online the same texts stand in the log with their marks.
- **Refused messages** keep their place in the log with the red mark and "Not sent. Retry" under the
  text, instead of a block above the tray: on Android a refused text is a stored history row (the
  delivery watchdog gave up on it), it may have reached the person already, and Retry resends it
  through the same path as the field.
- **Composer:** the field and the plates beside it are `size-field` (40 dp) tall; each button is a
  `size-target-android` (48 dp) touch target around its plate. The send button is the ink plate and
  replaces the microphone while there is text. The voice bar no longer blinks.
- **Dossier:** closes by the header, the dimmed area, Back or Esc, and by a swipe up only when its
  content was already at the end as the finger went down, so reading to the end does not close it.
  Files opens the log filtered to attachments; "All messages" in the overflow menu brings the whole
  log back. Block follows the contract below (stage 3).
  Dialogs keep their buttons in normal case. A dialog with a field (Rename, editing a tray entry)
  takes its keyboard down as it closes and is not closed by a tap outside, as an alert on iOS.
- **For yourself:** no band, no tray, no dossier and no marks; the hint says the note stays on this
  device.

Deferred on Android:

| What | Why not now | Where |
| --- | --- | --- |
| Files, voice and location as transcript entries | the old rows stay until their screens are redone; calls became entries in stage 3, files, voice messages and photos in 3b; a location is a message with its map over the coordinates, as in the text cell of iOS | done |
| The emoji panel of the old conference screen and of the media preview in the design's grounds | library views with their own colours; the conversation's panel moved in stage 3, the old conference screen's in stage 5 (`AirUi.emojiPanelColors`); the media preview stays the black media screen it is on iOS, with its panel dark on it | done |

### New contact and requests on the phone

Decided while building them in the iOS client (11.11.2026); Android and desktop follow the same rules.

- **The ID field** takes an ID in any usual shape: spaces, line breaks, invisible characters, a
  "tox:" prefix, lower case, a sentence around it. It shows the ID in groups of four, and Paste or
  Clear sits under it.
- **The ruler** under the field has the three parts of an ID, key 64, antispam 8 and check 4, in the
  proportions 64 : 16 : 12, each with its count; they stand left to right in every language, like the
  ID itself, and one under the other at the accessibility text sizes. The proportions give way to a
  name that needs more room, as an fr column of the web never gets narrower than its content: on a
  375 or 320 point phone "проверка" and the Arabic antispam stay whole (iOS since stage 5). The check
  is computed (XOR of the even and of the odd bytes of key and antispam): the old field recomputed it
  and so hid typos.
  When it fails the check part turns red and shakes once; with reduced motion it only turns red.
- **Verdicts** in this order: empty; characters left; check failed; your own ID (with any antispam);
  already a contact (Open chat); they asked you already (Open Requests); blocked (sending the request
  lifts the block); good.
- **Send request** sits on a bar above the keyboard with one line that says why it is off; the field
  being typed in scrolls clear of the bar. The message may stay empty: the greeting in the field is
  sent then. After sending, "Request on its way" draws the path from you to them with running dashes
  (standing still with reduced motion) and turns to "Request accepted" when they come online. Until
  then a request they have accepted still reads as waiting for an answer: objcTox does not tell it
  from one not answered yet.
- **Command line (E3):** 12 to 76 hex characters with at least one digit, spaces and a "tox:" prefix
  aside, read as the start of an ID. A "New contact with this ID" row above the matches says how many
  characters are missing or what is wrong and opens New contact with them.
- **Requests and invitations (E12)** are cards above the sections until they are answered. A request
  shows the time, the message, the start of the key and that the name comes once it is accepted. An
  invitation names the group and the contact who invites. toxcore hands an invitation over once, so
  the client keeps it until Join or Decline (iOS: in the sealed part of `air-local.json`, see Block)
  and drops it when that contact is removed. Join goes through the inviter: while they are offline
  the card says joining waits for them. An invitation from someone who is not a contact still asks
  at once.
- **Decline asks nothing.** The card leaves and a toast offers Undo for five seconds (ten with
  VoiceOver). The request or invitation is removed only when the toast goes without Undo, also when
  another toast takes its place; if the app is closed in those seconds, the card is back next time.

### Groups on the phone

Decided while building them in the iOS client (11.11.2026); Android and desktop follow the same rules.
The protocol is NGC as it is: the client asks toxcore before acting, these rules only decide what it
shows.

- **The header** names the group and says under it how many are in it and how many are online now:
  "9 members, 4 online now", or "nobody online now", or "not connected to the group". The count is
  toxcore's peer count, which includes you, or the members listed when that is larger; online counts
  the others.
- **Get it now** is a strip of the members online besides you, under that line, while you may write.
  A member coming online fades in (appears, with reduced motion). The strip opens the dossier.
- **The hint under the field** counts the others: "4 of 8 get it now, the rest when they come online"
  (the plan's "of 9" counted you too), "All 4 get it now", "The other member is online and gets it
  now", "Nobody is online", "Only you are in the group so far", or that messages wait on this device
  while the group is not connected.
- **The field counts down** the last tenth of 1372 bytes (TOX_GROUP_MAX_MESSAGE_LENGTH, a quoted reply
  included) and Send waits over the limit: toxcore refuses a longer group message instead of cutting
  it.
- **Who may write:** never an observer; nobody but the moderators and the founder, or the founder
  alone, when the voice state says so. In place of the field a bar says it ("Read only. You are an
  observer: a moderator can give you the right to write.") with "Write to a moderator" (or "the
  founder"): a private message to the one moderator, or a choice of them, online first; "the founder"
  writes to the founder alone. Nobody learns it on Send any more.
- **An observer has no "Write to a moderator"** (12.11.2026): toxcore refuses an observer's private
  messages too (`gc_send_private_message` returns -5 while your own role is observer), so the button
  opened a conversation where every Send failed. In its place the bar says "An observer cannot write
  here, not even privately: ask a moderator outside the group." A moderator can still write to an
  observer, and the observer reads it: in those private messages the field is off, the same line is
  under it, and Send tries nothing. A member under "only moderators" or "only the founder" keeps the
  button, since toxcore checks only your role for a private message, not the voice state. The words
  are the desktop's (`GroupChatForm`, the text `GroupObserverNoPrivate`), so the clients say one thing.
  The bar takes at most 40% of the conversation under the header and past that its text scrolls
  inside it, the scroll indicator flashing once: at AX5 the two lines of an observer had left the log
  5 points in Russian; held to that share, the log keeps a third of the screen.
- **Authors** are named in full over each run, in their tone, with the role of the founder and the
  moderators ("Amina Safina, moderator"); a relayed message whose signature does not check out reads
  "Author not confirmed" instead of a name. A message that mentions you, by your key in this group or
  by your name from an older client, has the `selected` ground and tells VoiceOver "Mentions you".
- **Our message** has the transit mark while the group is not connected, one check once it left, two
  once a member's device confirmed it.
- **The dossier** is a screen pushed from the title, not the folding panel of a person: the members
  with their search and the screens they open need the height. In order: name and counts; Your role
  in one or two sentences; the members by role, online first, then the most recently seen, each with
  role and presence, with a search from 20 members; the group key in groups of four with Copy, Invite,
  QR and who can join; Notifications; History (files, service messages, clearing it from this
  device); the group settings (topic, password, member limit, who may write); Leave the group with its
  consequence.
- **Roles and removing:** the founder gives any role but founder; a moderator moves members between
  member and observer and removes them; members and observers change nothing. A member row opens:
  Write privately; Open the conversation or Add to contacts (keys are per group, so a contact is the
  same key); Copy key; Mute in this group; then the role and removal actions allowed to you. Each is
  asked once, in one sentence that starts with the name, so no language has to decline it. A change
  of role is undone from the toast for five seconds and the row moves to its new place (a crossfade
  with reduced motion). Removing has no undo: the member needs a new invitation.
- **Invite** lists your contacts, those who can be invited first: an invitation goes over the
  connection with the contact, so only contacts online now can be invited; members read "already in
  the group". The choice goes by public key.
- **Write privately** opens the member's private messages drawn as the conversation with one person:
  the member's tile and name in the header, under it the role and whether they are online, at the
  start a note that only the two of you see these messages, then the transcript entries and the field
  without paperclip or microphone (an NGC private message carries text only, within the same 1372
  bytes). It leaves only while the member is online in the group: the line under the field says so
  before anything is typed ("Hamza is offline: write when they are back") and Send waits. A failure
  stays under the field with the text kept; a private message that did not leave is never stored, so
  there is no transit mark.
- **Group settings** are drawn as the screens of Settings: your name in this group and the topic, each
  with what it does; Who can join (the two choices, the password, the member limit); Who can write and
  whether only the founder and the moderators change the topic. The founder changes the last two;
  everyone else reads them with "The group's founder changes this.", and a topic they may not change
  reads who changes it. Each value opens a page of its own with the field, what it does and Save; a
  refusal or a failure takes the place of that line, a failure with Details. The calls to toxcore are
  the ones the old screen made.
- **New group** is one sheet instead of two steps: the name (one line, up to 48 bytes, no
  autocorrection, not changeable later), who can join (By invitation only, chosen at first, or Open
  to anyone with the group key), each with its consequence, the contacts online to invite, and Create
  group on a bar above the keyboard with the reason it waits. A password stays in the group settings.
- **Join a group** is the same kind of sheet, opened from Create or from the command line when it
  holds 64 hexadecimal characters: the key (typed, pasted or scanned from the dossier's QR code, with
  its count while it is short), the password if the founder set one, how joining works ("The group is
  found once one of its members is online. If a contact of yours is among them, Khandaq asks them to
  send you an invitation."), and Join with the reason it waits. A person's ID is told apart from a
  group key; the key of a group already on this device opens that group.

### Files, voice and calls on the phone

Decided while building them in the iOS client (11.11.2026); Android and desktop follow the same rules.
Transfers stay Tox file transfers, and a voice note keeps the `.file.m4a` name the clients share.

- **A file is an entry of the transcript:** the time column as for a text, then the file glyph, the
  name, the size and one line of state in words. Theirs waits for Accept (with Decline) when automatic
  download is off; ours says what it waits for: "Waits for Yusuf to accept it", or "Goes when Yusuf is
  online" while he is offline. While it moves, a 6 pt bar and "1.2 MB left", with Pause and Resume; a
  stopped transfer says which way it went ("The download stopped" with Download again, "Not sent"
  with Send again); a finished one of ours takes the delivered mark.
- **Sizes** count in thousands, as the Files app does, in the language chosen in the app: whole bytes
  and KB, MB with one decimal, GB with two; an amount that rounds up to 1000 takes the next unit.
- **Photos and videos** show inline in a box that takes its size before the image loads, so the row
  sizes itself once; a caption sent with the photo stands under it in the same entry.
- **A voice note** is a player: Listen and Pause, the wave, and the length in tabular figures (the time
  played while it plays). One not fetched yet shows its size and waits for Accept like any file.
- **A group says its limit before a file is chosen:** the attach sheet reads "A group takes files up
  to 200 MB", the limit its refusal already used.
- **In transit:** a file of ours that waits for a person who is offline lies in the tray with the
  texts ("trip-photos.zip, 8.4 MB") and can be cancelled there; it leaves once the person is back, when
  objcTox sends it again.
- **Calls in the transcript** are entries too: the phone glyph and "Call, 4:12" in tabular figures,
  "Missed call" for theirs that nobody answered, "No answer" for ours.
- **Calling someone offline** no longer starts a call that cannot get through. Both call buttons stay
  on; a tap says "Yusuf is offline: the call will not get through", whether a wake signal (with no
  text) goes to the phone, and offers Wait and call. The wait is a screen of its own: the name,
  "Waiting for Yusuf to come online", what the app did, and Cancel; the call starts by itself once the
  person is online. Nothing on it moves.
- **Access is asked before a call, never assumed.** Without the microphone a call does not start: an
  alert says so with Open Settings (it used to crash the app). An incoming call answered without the
  microphone is declined and explained the same way instead of going on in silence. Without the
  camera a video call offers to go with sound alone.
- **A voice note asks for the microphone on its first hold** (stage 5). Denied, a hold says why, with
  Open Settings, as a call does; it used to show its recording bar and send nothing. The answer to the
  first question comes after the finger has left the button, so a recording starts only while its
  hold is on and the next hold records: one started on the answer went on unseen until the next hold.
- **The call screens** are dark in both themes, on the flat lock ground (`lock-top`, one colour: the
  brief bans gradients, stage 5). At the top the person in the display size, the state ("Calling",
  "Incoming call", the timer in tabular figures, "Call ended", which VoiceOver also announces) and the
  link with its mark ("Direct link", "Via a relay", "No link right now"). At the bottom, in the thumb
  zone, 64 pt circles with their words under them, in equal columns so that four fit a 320 pt screen:
  Decline, Answer with video, Answer; on a call Microphone, Speaker, Camera, End. The switches read on
  or off to VoiceOver. An incoming call comes down from the top like a shade (a fade with reduced
  motion); once answered the whole screen gives way to the call.

### You on the phone

Decided while building it in the iOS client (11.11.2026); Android and desktop follow the same rules.

- **"You" opens from the "you" block** of the Air screen. Your tile flies to the head of the screen
  and grows as it fades, while the QR card comes out of its place and grows to its own size: the ink
  tile turns into the white card (transform and opacity only; with reduced motion the screen fades
  in). Going back is the usual edge swipe.
- **First the QR code** of your ID, dark modules on a white card in both themes (what cameras read
  best), set off by its ground, as wide as the phone and at most 400 pt; then Share ID (the system
  share menu) and Copy ID (a toast says the ID was copied).
- **Photo, name and status are edited where they stand.** A tap on the photo offers the camera, the
  library or removing it. The name and the status line are fields that save when editing ends (Done or
  a tap elsewhere), within the bytes Tox carries; an emptied name takes the old one back.
- **Details:** the ID in its three named parts (key, antispam, check) in groups of four, left to right
  in every language, then Change antispam and Password and PIN. Change antispam asks first and says
  what follows: "Your old ID will stop taking new requests. Contacts stay; the ID and the QR code
  change."
- **Log out says what it means** under the button and again when asked: "The profile stays on this
  device; you can log in again with its password." From then on every start opens the login form until
  the password is typed; it used to log straight back in from the keychain. The keychain keeps the
  password, so the logout after a PIN lockout and the one for an import behave as before; a flag that
  Log out sets and a login with the password clears decides.
- **Delete the profile from this device** stands apart at the very bottom in the danger style and is
  asked once with what it erases: "Samir: the profile, its contacts and history are erased from this
  phone. Without an exported copy they cannot be brought back."

### Settings on the phone

Decided while building them in the iOS client (11.11.2026); Android and desktop follow the same rules.

- **Sections by task.** Settings lists Appearance, Notifications, Privacy, Profile transfer and
  Network, then FAQ and About; each opens a screen of its own with the usual slide. The Appearance row
  shows the theme in use. Debugging is a row only in debug builds, never a hidden gesture.
- **Appearance:** the theme as a segment, Match system, Light and Dark, the system by default; the
  language, each language named in itself; group system messages, "Who joined, left or changed their
  name". A new theme or language rebuilds the interface under a cross-fade of the whole window and
  comes back to Appearance, not to the start of Settings.
- **The segment** is a sunken track with the chosen option raised and outlined by a hairline. When the
  options no longer fit on one line (a long translation, the accessibility text sizes) they stand one
  under another at full width.
- **Notifications:** Show message text, with what it means: "Message text and file names show on the
  lock screen. Turned off, a notification says only "New message"."
- **Profile transfer:** Export profile and Import profile with what the file carries: "The profile file
  holds your ID and contacts. Chat history stays on this device." Import asks first, because it logs
  out of this profile, which then waits for its password.
- **Network:** Use UDP (the network restarts after a question, as before) and Network connections;
  Download attachments as three choices with "Files that do not download by themselves wait for Accept
  in the chat"; the call audio engine under Calls; Restore default settings in the danger style. The
  longer background mode, still only in debug builds, says its cost in one phrase: "Messages get more
  time to go out after you leave the app, but the notification server learns which network this phone
  is on."

### Network state on the phone

Decided while building it in the iOS client (11.11.2026); Android and desktop follow the same rules.

- **The line under your name** on the Air screen says whether messages leave now: "online, direct",
  "online, via a relay", "connecting" or "no network: messages wait". The dot stands still and turns
  red only at "no network"; a change cross-fades the words. The line is a target of its own, a finger
  high; the tile and the name open You.
- **The Network screen** opens from the line and says the state in words: what it means for messages,
  whether the phone has internet and how the link goes. "Connect again" stands there only while the
  app connects with internet at hand, and a toast says it is on its way. The technical log for support
  is one row further; the screen itself shows no addresses and no probe codes. It follows the node
  while it is open.
- **In a conversation** the hint under the field speaks for your node while it is not connected
  ("Waits in transit: the app is connecting. It will go on its own"), whatever the other side's
  state. Nothing is refused; a message sent then waits In transit.
- **Reconnect:** while the node is offline on a phone that has a network, a reconnect is asked for
  every 15 seconds, as Android does (OFFLINE_PERIODIC), unless the backoff already has one waiting.
- **The connection pill is retired.** It never showed on iOS (its window belonged to no scene), and
  the line and the hint say the same without colour flashes.
- The node count of the plan ("12 nodes") is not shown: objcTox gives no count, and this work does
  not change objcTox.

### In-app band on the phone

Decided while building it in the iOS client (11.11.2026); Android and desktop follow the same rules.

- **A message from another chat** while the app is open comes as a band under the safe area: the
  chat's tile as on the Air screen (the person's photo when they chose one), the sender, then the text
  when previews are on, otherwise "New message". A tap opens the chat; a swipe up or the VoiceOver
  escape puts it away; it leaves by itself after 4.5 seconds.
- **A request is a kind of its own:** the dashed tile, "Incoming contact request" and its message; the
  tap opens the request.
- **An invitation to a group** names who invites, then the group ("Invites you to the group Saturday
  by the river"); the tap opens Requests, the new card first and whole. Requests rather than the card
  alone: the invitation may have been answered while the band was up.
- **No band** for the chat on screen or a muted one, nor for what was notified while away: the backlog
  the first 3 seconds after coming back and messages delivered late (sent over 45 seconds before) come
  without a band, as they come without a sound.
- The band's window takes the app's theme, is up only while a band shows (so it never decides the
  status bar) and lies under the PIN lock. It comes down in the layout time and leaves in the state
  time; reduced motion shortens both.

### Errors and questions on the phone

Decided while building it in the iOS client (12.11.2026); Android and desktop follow the same rules.

- **An error names the operation that failed** as its title ("The file wasn't sent", "Couldn't start
  the call"), then what to do. "Internal error" gives way to "Try again."; with a code there is also
  "write to support and attach the details". The code (domain and number) waits under Details with
  Copy and never enters the words. A code the build does not know gives the same words, never a crash.
- **A refusal by rule is said next to the action:** a typo in an ID under the field, a call without
  the microphone or to someone offline where the call was pressed.
- **A dangerous action asks from below,** in reach of the thumb: the consequence in one sentence, the
  verb on the button, Cancel apart. Block, a role change, removing a member, deleting or clearing a
  conversation, leaving a group, changing the antispam, logging out, deleting and importing a profile.
- **A toast only confirms** what is done, with one action at most (Undo), above the composer when
  there is one; it leaves by itself and VoiceOver hears it. Errors never come as toasts.

### First launch, sign-in and PIN on the phone

Decided while building them in the iOS client (12.11.2026); Android and desktop follow the same rules
where they apply.

- **First launch is three pages**, each at the full height of the phone with its main action on a bar
  above the keyboard and the reason it waits under it ("Type a name first."): What should we call you
  (one field, who sees the name, and a quieter "Move a profile from another device"), Profile
  password, Your ID is ready. Three short bars in the navigation bar show the step; the current one
  fills when its page has come in. The pages slide in the direction of travel and cross-fade with
  reduced motion. The field being typed into is scrolled into sight above the bar together with the
  note under it (a password and its repeat share one), again once the keyboard has settled and when
  an error takes more lines.
- **Profile password** says first that a password cannot be recovered because there are no servers,
  then two choices, each with its consequence before it is chosen. Set a password: the profile opens
  only with it and can be moved with it. Continue without a password: on iOS the profile is still
  encrypted, with a random key kept in this iPhone's keychain (after the first unlock, this device
  only), so it opens without typing but only on this iPhone; Export asks for a password first. The
  rule (8 characters, a letter and a digit) stands under the fields, which carry the new-password
  content type and rules for password managers. The page does not raise the keyboard by itself: the
  choices are read first.
- **Your ID is ready:** the QR code, the ID in its three parts whose groups come in one after another
  (at once with reduced motion or VoiceOver), Share, and Add the first person, which opens Air with
  the command line in focus.
- **Sign-in** is the profile's name and its password field; the name is the username half of the form,
  so a password manager pairs the two, and keeps to the page's leading edge whatever its script. Several profiles are a list under it, and a touch and hold
  deletes one after a question. A profile with the device key shows its name and Log in only. When
  the keychain of this iPhone no longer holds that key (a backup restored onto another iPhone, a reset
  keychain), the page says so instead of a password error: the profile opened with the key of the
  iPhone it was made on and does not open here, and what to do on that iPhone; Move a profile is the
  main action, with Create a new profile and Delete this profile (asked once more) under it, and the
  profile's row reads "No key on this iPhone". Before the first unlock after a restart the keychain
  does not answer, which is the no-access case below. Why it
  did not open stands under the field in red, never in an alert: a wrong password (the field shakes
  once, not with reduced motion; check the keyboard language and Caps Lock), a damaged file (move it
  in from an exported copy), no access (unlock the iPhone); a code waits under Details. A typed
  password is cleared when the page is left another way, so the system never offers to save a
  password that did not open the profile.
- **Forgot the password?** says it cannot be recovered and offers Create a new profile (the old one
  stays on the device and opens if the password comes back) or Delete this profile, asked once more.
- **A successful sign-in opens into Air:** the sign-in page lifts away (scale 1.04 and a fade in the
  reveal time), a fade alone with reduced motion.
- **Moving a profile in** starts from sign-in and first launch (Files), from Settings and from the
  share sheet; the last two ask first, because they log out of the profile in use. An encrypted file
  asks for the password it was saved with, then shows its name and ID before anything is written; a
  plain file shows them first, then asks for a password or the device key. A file that is no profile
  says so. The copy the system handed over is removed once the profile is in; the original stays.
  The app imports the type `org.khandaq.tox-profile` (conforming to public.data, extension tox,
  application/x-tox) with a document type of rank Alternate, so Files shows a `.tox` file with the
  Khandaq icon and an app that owns `.tox` keeps it. Files lists under Open With only the apps that
  open documents in place; Khandaq takes a copy, as it always has, so Open With stays empty for a
  `.tox` file (below, deferred). A file handed over before Khandaq is running waits until the app is
  up, and with a PIN set the question comes after the PIN.
- **Sharing into Khandaq** (Share, then Khandaq, in another app): the extension copies the text, link,
  photo or file into the app group `group.org.khandaq.messenger` and says to open Khandaq, since a share
  extension may not open its app. As the app comes up, after the PIN when one is set, it asks where it
  goes with the choice of Forward (Saved Messages, then the latest chats, people and groups), sends it
  through the calls of that chat's composer and empties the inbox; Cancel empties it too. Texts given
  together (a page's title and its link) go as one message, and a text longer than a message goes in
  parts ended between words.
- **Password and PIN** is drawn as the screens of Settings: PIN (Ask for a PIN, when it locks, counted
  from leaving Khandaq, and Face ID or Touch ID instead of it), Profile password and Profile transfer,
  each section saying what cannot be recovered where it applies. For a profile with the device key the
  password row reads Set a password, which seals the save and the history key with the new password
  and removes the key; its Export asks for a password first, because the file would open nowhere else.
  Change password asks for the old one first, and a wrong one is said under its field.
- **The PIN screens:** the lock on the flat lock ground of the call screens (`lock-top`), white on
  their translucent controls in both themes; setting it is a sheet on the background colour with the
  PIN cells (.kq-pin) and a note that a PIN cannot be recovered and that ten wrong tries close entry
  for a while. The keys are 64 pt circles in the thumb's reach, the digits in the app's numerals, the
  order kept in right-to-left languages. A wrong PIN shakes the cells once. Face ID or Touch ID first
  and the lockout after ten wrong tries are as they were.

Deferred, with the screens they belong to:

| What | Why not now | Where |
| --- | --- | --- |
| The back chevron after a right-to-left language is picked in the app, on iOS 15 and 16 | the window takes the app's direction through `traitOverrides` (iOS 17 and later); an earlier system draws the chevron by the language the app was started in until the next launch, and no iOS 15 or 16 runtime was at hand to check it | iOS 15 and 16 devices |
| Khandaq under Open With in Files for a `.tox` file | Files offers there only an app that opens documents in place; with that switched on Khandaq is offered for every file (a text file and a picture were checked in the simulator), because it has accepted any file since Antidote to send it on, and the stage asked for other files to stay as they were. The change was built and checked (the same question comes after Open With) and waits as `design/redesign/stage5/ios/open-in-place.patch` | owner |
| The moment between sealing the profile with a new password and keeping that password in the keychain | if the app is stopped exactly then, it does not log in by itself and sign-in asks for the new password, which opens the profile | known, nothing is lost |
| An old profile without encryption whose history key is sealed with another password | it did not open before this redesign either: the history needs the password it was sealed with | known, as before |
| A file transfer that stops moving, told on its row | a transfer that moves no byte for 30 seconds is still told in a dialog and cancelled, as before the redesign. Since stage 5 only while its conversation is on screen and the transfer still runs: before, the dialog also came after the conversation was left, or for a row scrolled out of sight, while the file was still moving | the file row, iOS |

### Android stage 3: requests, groups, calls, You and settings

Built in the Android client on 11 and 12.11.2026 to the rules above, with the iOS string keys where
the words are the same, in English, Russian, Arabic and Chinese. Only what differs from iOS, or is
particular to Android, is listed here.

- **Block** follows the contract below: `AirBlockList` keeps the keys in the encrypted globals table
  of the profile database, which a profile export does not carry; the request callback drops a listed
  key before anything is stored or shown. Settings > Privacy > Blocked lists them with Unblock.
- **New contact** is a screen of its own (`AirNewContactActivity`) with the field, the ruler, the
  verdicts and the bar of iOS; the old Add contact screen is gone. "Request on its way" draws the path
  with still dashes: the brief bans endless animation, so the dashes do not run on Android. The
  command line takes 12 to 75 hex characters as the start of an ID; a whole ID opens New contact at
  once. Since 3b it also takes a group key (64 hexadecimal characters, or a `khandaq:group:` or
  `khandaq://group/` link): its row says whether you are in that group, and Join asks the contacts
  online for an invitation (packet 184) and takes the first one back, so it works for open groups
  and needs a contact online who is in the group. Paste ID reads the clipboard only when it is
  tapped; a clipboard without an ID or a group key is said under the line.
- **Invitations** are kept in the globals table (`air_group_invites`, at most 50) until Join or
  Decline and dropped with the contact. One to a group we are in is not shown: taking it would
  replace our membership, and a founder's key, with a new one. Joining from a card clears the group's
  "left" mark, so the next start keeps the group instead of leaving it again. Since stage 5 a group
  joined from a card goes by the invitation's name until toxcore has its own, as iOS passes the
  invitation's name when it accepts (`HelperGroup.name_group_from_invitation`); it stood under the
  first characters of its ID until it connected.
- **Groups:** the header line, Get it now, the hint, the countdown over the last tenth of 1372 bytes
  and the read-only bar are ports of iOS (`AirGroupModel`, `AirGroupPanel`). Roles read as words and
  only the founder and the moderators see the role actions. Leave and Delete in the list ask in the
  app's language with the verb on the button ("Leave QA roles?" with Leave the group; Delete). Since
  3b the conversation is drawn as the one with a person: the time column, the sender's name in the
  accent, no bubbles and no wallpaper. The header says how many members there are and how many are
  online now, and the line under the field says who gets the message now ("1 of 2 gets it now, the
  rest when they come online"). The group dossier (`AirGroupDossierActivity`) is drawn as the dossier
  of a person: the members with presence and roles (your own row without the old "(you)" suffix),
  the group key with Copy, Invite and its QR code, notifications, Clear history (this device only),
  the group settings and Leave the group, asked from below. Since stage 5 the group settings
  (`GroupInfoActivity`) are drawn as on iOS: your name in this group, the topic, who can join and who
  can write, in words and never PUBLIC or PRIVATE; the name, the topic, the password and the limit
  each have a page of their own with Save, and a refusal or a failure stands under its field or its
  choices, a failure of the founder's settings with its code under Details. Who changes
  what, the toxcore calls and the database writes are the old screen's; the topic opens only where
  toxcore would take it, leaving the screen sends nothing (the old one sent the name, the topic and
  the limit on the way out), the members are listed in the dossier only, and the password, which
  toxcore does not give back, shows no value, so Remove the password is always offered. While the
  group is not connected no row says "online", yours included (yours says
  "offline", as on iOS), so the rows agree with the header and the line under the field. Since stage 5
  another member's role is shown only while the group is connected (`AirGroupModel.shownRole`): until
  the group syncs, toxcore reports the saved members it has not confirmed as members, so a founder
  read "member". The rows, the member's screen and the member menu then show no role and offer no
  change of role or removal, and "Write to a moderator" waits ("No moderator of this group is known
  yet"); your own role stays. iOS reads toxcore's role at that moment (`AirGroupMembers`), so it can
  show a moderator as a member until the sync.
- **Files and calls:** sizes count in thousands in the app's language and the attach sheet of a group
  says its 200 MB limit. Calls are entries of the transcript with the phone glyph: "Call, 0:47" from
  the moment toxav says the call is answered, "No answer" for ours, "Missed call" for theirs. The
  history keeps an entry in the words of the language the app had when the call ended; shown, in the
  transcript and in the list, the entry is read back from those words and said in the app's language
  now (build s3-20 on), and words of older builds stay as written. Calling someone offline offers
  Wait and call and the still waiting screen; without the microphone a call does not start and a
  dialog offers the app's settings. Since 3b files, voice messages and photos are transcript entries
  too: the file row with its name, size and state, the voice player, the photo in its frame. The
  transfers, the protocol and the database are unchanged, and declining or saving a file asks from
  below. Since stage 5 the call screens (waiting, outgoing, incoming, in call) stand on the flat lock
  ground (`lock-top`) in both themes, as on iOS: white words and light status bar icons, the switches
  translucent white circles (`media-control`) with no outline that turn white with a `lock-top` glyph
  while on, Answer in `live-fill`, Decline and End in `danger-fill`; a touch shows as a light ripple
  and the keyboard focus as a white ring (they stood on the flat ground of the theme in 3b). There is
  no Camera switch in an audio call: toxav could start sending video during a call (a video bit rate
  set from 0 adds the video capability), but this client fixes the kind of call when it starts. An
  audio call never opens the camera (`CallingActivity.scheduleCameraOpen`), and video that arrives
  during an audio call is dropped (`MainActivity.android_toxav_callback_video_receive_frame_cb_method`),
  so the camera an iPhone turns on in an audio call is not seen on Android either. Offering it means
  new call logic on both paths and a video test between two devices; a video call keeps Switch camera
  in that place. An incoming call without the microphone does as on iOS: never asked, the system asks
  and the call is answered after Allow; denied, the call is declined and one note says how to allow
  the microphone, with Open settings. A call never connects without sound, and Answer on a video call
  answers with sound only.
- **You** opens from the "you" block as the profile tab, titled You as on iOS (it said Profile before
  build s3-19), without the flight of the tile into the QR card. Name and status are fields in place
  with the 128 and 1007 UTF-8 byte limits of toxcore (`AirUtf8Limit`); a line break typed or pasted
  becomes a space, and a long text wraps to four lines instead of scrolling sideways, so the
  placeholder stays whole at a large font. The photo
  comes from the document picker, cropped as before; Android has no camera path for it. Under Log
  out, a profile without a password says so: "It has no password, so anyone who opens the app on
  this phone can get back into it." On Android such a profile opens without a password. Password
  and PIN is the former Details screen (lock, its timeout, the password), drawn anew in stage 4;
  deleting the profile moved from there to the bottom of You. Done on the keyboard, Enter on a
  hardware keyboard, a tap outside the fields, another tab or leaving the app end the editing and
  save it; an emptied name takes the name back.
- **Settings** are the sections of iOS. The Connection row under Network says the state in the words
  of the Network screen and follows it while shown. Since 3b Advanced draws the preferences of
  `pref_general.xml` as rows on plates by task, as Settings sorts its own, with their keys, defaults,
  dependencies and listeners as before, and says each in plain words in all four languages. Six rows
  are not drawn: Theme, which Settings has, and five switches that change nothing in this build
  (the compact list, the speakerphone tweak and the lowest sampling rate are read and never used;
  toxcore takes UDP and LAN discovery from the Tor choice alone). The debug sections show only in a
  debug build. The
  build details on About open only in debug builds; seven taps on the version no longer open them.
  The header over Settings and You is the title alone: its spinner turned without end while
  connecting, so it went with the dot; the state is said in words on Air, on the Network screen and
  in the Connection row.
- **Profile transfer** (stage 5): the dialogs of Export profile and Import profile are sheets from
  below (`AirSheet`; `AirQuestion` is its one-verb case), with the same steps and the same files.
  Export asks which file: Make an encrypted backup, or under it Export the plain .tox file. The plain
  file says what it is with its verb in the danger style, then asks for the phone's lock; on a phone
  without one the confirmation word is typed in a field, and Export waits until it matches. The backup
  asks for its password twice, says each refusal under its field and has Choose where to save as its
  verb; while it is written a still bar fills with the work. Import asks "Replace this profile with
  one from a file?" with Choose file in the danger style; moving the profile in waits on a sheet that
  says Khandaq stops, puts the profile in place and opens again (no spinner), and a copy that did not
  go through no longer says Profile imported. What came of an export, a backup or a refused file
  stands under the plate of the two rows, a failure in the danger colour; started from another screen,
  it is said from below with OK. A backup or a restore whose error had no message used to count as
  done (a backup said Backup created, a restore went on with what it had half extracted); the failure
  itself decides now. Restore of an encrypted backup, on the maintenance screen, keeps its dialogs.
- **Text size** (stage 5): a change of the phone's font size reaches the screens at once. They are
  drawn again for it, as for a change of theme: `fontScale` left the `configChanges` of every screen
  but the two call screens, which keep their layout through a call and take the size with the next
  one. Text size in conversations, in Advanced, counts from the next message drawn
  (`MainApplication`); before, only once the main screen had come back.
- **A contact's Information** in the list menu opens the conversation with its dossier. The old
  contact screen says the notifications in words; push addresses and relays show there, and "add as
  Tox proxy" in the menu, only with developer tracing in a debug build.
- **Network state:** the quality toasts at start are gone; the line under your name opens the Network
  screen (`AirNetworkActivity`). Since 3b both say No network as soon as the phone loses its default
  network (`NetworkConnectivityMonitor`, `registerDefaultNetworkCallback`): about 2.5 s after flight
  mode is switched on, where they waited about 25 s for Tox before. When a network is back, Tox's own
  state takes the line again (4.2 s in the same test); Tox alone tells connecting, direct and through
  a node.
- **Language change:** a change in Settings keeps the process, so whatever was formatted once at the
  start follows the new language where it is shown: the times in the old bubbles of a group take the
  digits of the app's language (build s3-21 on; before, Chinese after Arabic still had Arabic digits).
  Since stage 5 a language chosen for Khandaq in the phone's settings (Android 13 and later) is the
  app's language: Lingver takes it at the next start and at once while the app runs
  (`AppLocaleHelper.adoptPhoneAppLanguage`), and the screens open then are drawn again in it as each
  comes back (a call keeps its screen). "System default" there follows the phone again once a language
  had been set, there or here. Before, Lingver put its stored language back, so the phone named one
  language and Khandaq showed another.
- **Air at start:** a list at its top stays at its top when rows move between sections (build s3-22
  on). Before, a contact coming online moved above the rows in view and the list kept its old first
  row in place, so "Online now" and its first row sat above the edge; a list scrolled down is left
  where it is.
- **Favorites and the saved chat** have two names, as on iOS (3b): the filter is Favorites (Избранные,
  المفضلة, 收藏) and the conversation with yourself is Saved Messages (Избранное, الرسائل المحفوظة,
  收藏夹) in every string that names it. Its title is always the string of the app's language, whatever
  name an older build stored for it.
- **The conversation header** gives the name its room first (3b, `AirHeaderFit`): when the name would
  show fewer than ten characters (a large font, a narrow screen), Search moves into the overflow menu,
  then the chevron goes, since tapping the name opens the dossier anyway.
- **Theme** in Settings is a segment whose parts keep one line each, sized to their words; when the
  three do not fit (a large font, a long translation) the same choice is three radio rows (3b).
- **New contact** sets its hint in the body face; only the typed ID is in mono (3b). **You:** the name
  field has no spell check and no suggestions, the status keeps them (3b). The filter pills of Air
  grow with their words at the phone's largest text instead of cutting them; up to font 1.3 they keep
  their 32 dp (3b).
- **In-app band (E22, 3b):** a message from another conversation, a request and an invitation come as
  the band from the top; a tap opens it, a swipe up puts it away. Requests and invitations are
  notifications of their own kind, in the new channel `khandaq_requests` (Requests and invitations);
  every existing channel keeps its ID, so nobody's channel settings are reset.
- **Errors and questions (E21, 3b):** dangerous actions ask from below with the verb on the button
  (`AirQuestion`); a refusal or a failure in a conversation stands on the line under the field
  (`AirComposerNotice`), on other screens under the row or button it belongs to (`AirUi.sayUnder`); a
  screen that closes because of it (a viewer, a picker) leaves the words to the conversation under it.
  The line under the field wraps instead of being cut, as on iOS, both for these words and for the
  conversation's own (before 3b it kept one line below font 1.3 and cut the rest).
  Toasts only confirm (`AirToast`), with Undo where there is one. Toasts are kept where no screen is
  left to stand on: the fatal exit when the profile file cannot be opened, "Khandaq is starting" when
  something is shared into Khandaq before sign-in, and the fallback when a legacy layout has no room
  for the line. What happens by itself in a group (a message or a file that cannot be read) is said in
  that group while it is on screen only; a file over the limit from a person is said in their
  conversation, or as a toast elsewhere. Since stage 5 a failure that iOS gives a code for has the
  same code on Android (`AirErrorCode`: the domain and objcTox's number for toxcore's error, as
  "me.dvor.objcTox.OCTToxErrorDomain 3"). Its words end in a quiet underlined Details, and the line
  (under the field, the row it stands in, which TalkBack reaches) opens "Code for support" with Copy
  (`AirErrorDetails`); under the field such words stay twice as long. These are a change of role, its
  Undo and removing a member, adding a member as a contact, a group message toxcore refused, and a
  profile that did not open at sign-in: a save or a history it cannot read (ProfileOpening 1 and 2,
  looked at before the profile opens) and a save toxcore would not take (OCTManagerErrorDomain 19). A wrong password, a refusal by rule,
  a message kept for later, a group not connected, a private message to a member and a toast carry
  none, as on iOS.
- **Unknown markup** follows the rule below in `MessageReplyHelper.readableBody`, where the transcript
  rows, the tray, the chat list and the notifications read a body. Since 3b a valid reply header
  without its `[KQ/end]` footer is a reply on Android too, as iOS reads it (#146): the first line after
  the header is the quoted preview and the rest is the body; a body that contains the literal
  `[KQ/end]` still reads as before (audit #18).

### Android stage 4: first launch, sign-in and PIN

Built in the Android client on 12.11.2026 to the rules above, with the iOS string keys where the
words are the same, in English, Russian, Arabic and Chinese. What a profile has on Android was written
down first and kept exactly; the brief forbids new security mechanisms.

- **What a profile has on Android:** a password becomes, through PBKDF2, the key of the database, of
  the file store and of the Tox save. Without a password the profile is encrypted all the same, with a
  random key of 32 characters wrapped by an Android Keystore key (AES-GCM) and backed up under a key
  derived from the device (ANDROID_ID): it opens without typing, and only on this phone. A session
  that was not logged out opens by itself with the key kept for it, checked against the database. The
  PIN locks the app (4 digits, hashed with a salt in the preferences; after 0, 60, 300 or 1800 s, 60
  by default) and has no lockout after wrong tries. There is no biometric unlock; the phone's own
  credential is asked only before a plain export. A profile file moves in only without a password.
- **The key of a profile without a password** comes from SecureRandom since stage 5
  (`SecureRandomString`: the same 32 characters from the same alphabet; before, `java.util.Random`);
  keys made before stay as they are, nothing is re-keyed.
- **First launch** (`AirFirstLaunchActivity`, `AirIdReadyActivity`) is the three pages above. Continue
  without a password says what Android does: "The profile is still encrypted, with a key kept on this
  phone. It opens without a password for whoever can unlock this phone, and only here." The name and
  the new password are one form for a password manager (the username and newPassword hints); since
  stage 5 the page shows "For the profile Amina" over the fields, and only password managers get the
  bare name, as the username (`AirStepKit.UsernameText`; the bare name stood there before). Your ID
  is ready stands over Air once Tox runs with the new profile, and Add the first person opens Air with
  the command line in focus. Since stage 5 the battery question comes once from below, after the
  first launch and never over it: not while Your ID is ready opens and not while Add the first person
  has put the command line in focus, so it comes the next time Air is in front (`AirBatteryQuestion`).
  Open battery settings opens the same system list as the old dialog did; a phone where Khandaq is
  already exempt is not asked, and a profile that answered the old dialog is not asked again
  (`asked_whitelist_doze`).
- **Moving a profile in** reads the file first: its name and ID are shown before anything is written,
  and a file that is no profile or carries a password is refused under the button that picked it.
  Android moves in a plain save only, as before, and such a profile opens with this phone's key.
- **Sign-in** (`CheckPasswordActivity`): the name as the title and the username, the field with the
  password hint, a wrong password, a damaged file and no access told apart under the field; a wrong
  password shakes the field once without moving the page. Android keeps one profile, so Forgot the
  password? offers Delete and create new from below, after the consequence: the profile, its contacts
  and its history are deleted from the phone, and a new profile with a new ID takes its place. That
  delete now also removes the file store, the Tox save, the salt, the key anchor and the PIN; before,
  the next profile stopped on "Wrong credentials". A profile without a password that was logged out
  waits on this page with its name and Log in (it went back to first launch before). Since stage 5 a
  profile whose database, file store or Tox save does not open with its key (what 0.2.45 left behind
  its old Forgot password) no longer stops on Wrong Credentials or closes on every start: its session
  ends, and the next start waits on this page with the reason and Delete and create new, with or
  without a password. A save or a history the app cannot read stops the start on this page too, with
  "No access to the profile file" and its code, also for a session that was never logged out and for
  the right password (`AirProfileCheck.unreadable`), as iOS checks it first; before, the database
  opened and Tox started without its save, under another ID. Nothing is written, so once the file
  reads again the next start opens by itself.
- **Password and PIN** (`ProfileDetailsActivity`), **the PIN screens** (`PinActivity`) and **Change
  password** (`ChangePasswordActivity`) are drawn as Settings and the first launch. App lock (Ask for a
  PIN), with Lock timeout shown only while it is on. Set a PIN and Repeat the PIN use the cells of
  `.kq-pin` and say that a PIN cannot be recovered: forgetting it means clearing the app's data in the phone's
  settings, which deletes the profile. Since stage 5 the lock stands on the flat lock ground of the
  call screens (`lock-top`) in both themes, as on iOS: the wordmark with white letters, white words,
  the cells and a keypad of 64 dp circles in translucent white (`media-control`) with no outline, a
  key turning white with a `lock-top` digit while pressed, the digits in the app's numerals, in the
  same order in right-to-left languages; a wrong PIN shakes the cells once and is said under them in
  white (on the flat ground of the theme in stage 4). Setting a PIN and Change password stay on the
  theme ground. On a short screen (360 by 640, a large font) the keys shrink towards
  48 dp until the step fits. Change password asks for the old password first, keeps the rule under the
  new pair and each refusal under its field, and asks from below before Khandaq closes to encrypt the
  profile with the new password on the next start. That start shows a page with a still dot and words
  while it encrypts; a change that did not finish is rolled back and said on the same page, with
  Continue (it was a toast before).
- **Upgrade path:** two test profiles made with build s3-23, one with a password and one without, each
  with a contact, its history, a group invitation and a message in transit, kept all of it when build
  s3-24 was installed over them; both messages went when the network came back.

Deferred and left out on Android:

| What | Why not now | Where |
| --- | --- | --- |
| A lockout after wrong PINs, biometric unlock, moving in a file with a password | new mechanisms; the brief forbids them | owner |

### Android stage 5: the whole client, the release build and the store frames

Done in the Android client on 12.11.2026.

- **Store frames and measurements on Android.** A debug build fills a fresh test profile in a
  secondary Android user with the made-up world of the iOS store frames, Ibrahim Gasanov standing where
  iOS has Bilal Haddad: `am start` AirDemoWorldActivity with world store, bulk or clear and lang en or
  ru. The world lives in src/debug (AirDemoWorld, its strings, the activity, and a provider that puts
  its overlay back when the process starts) and is written to the database and the stores the screens
  read. Nothing of it reaches toxcore: the one hook in main, AirLiveOverride, is a source a release
  build never sets; while it is set, the app does not add its contacts to toxcore or join its groups,
  and reads a group's connection, your role and key, its members and counts from it. A profile with
  contacts or groups of its own is refused. The release APK holds no demo name and no seeding class:
  AirLiveOverrideTest checks src/main, the release proof greps the APK.
- **A group member, from their row and on their own screen.** The member's actions come from below
  as on iOS (GroupPeerActionsPresenter), in its order: Private group message, Open direct chat or Add
  friend, Copy ID, Mute or Unmute in this group, Remove from the group in the danger colour, the
  changes of role, Cancel apart; then, on Android only, "Key and link", the member's own screen, and
  "What is this bot?" for a test client. Only what your role allows is offered, by AirGroupModel's
  rules (AirGroupMemberChoices, AirGroupMemberChoicesTest), so nothing stands disabled; a long list
  scrolls between the name and Cancel. The private message (also "Write to a moderator"), the bot note
  and the questions are sheets too; only the choice of a moderator under a read-only group, when there
  are several, is still a list dialog (AirGroupPanel). The member's own screen (GroupPeerInfoActivity,
  opened also from an author or a mention in the conversation and from the old member drawer) is an Air
  screen: the name, then the role, presence and "muted" in one line as their row in the dossier says it
  (no role while the group is not connected); a private message with Send; the direct chat or adding
  them, Mute in this group as a switch, the changes of role, and Remove last in the danger colour, only
  those allowed; how this phone reaches them while they are online, in the contact dossier's words; and
  their key in this group in groups of four with Copy key. It follows the member every 3 s like the
  dossier. Gone with the old screen: the smiley avatar, the "Nick:", "Pubkey:" and "First Join Date:"
  pairs (the date was when this device first saw them, not when they joined), "Link: direct" and a
  disabled Remove with its rule under it. A private message typed and not sent stays as a draft for as
  long as the app runs, the same one in the sheet and on the screen; only Send sends it, and a send
  that fails keeps the words and says so under the field.
- **About, drawn as the rest of Settings.** About (Aboutpage) is an Air screen in the order of
  SettingsAboutController on iOS: Version and Build, the site khandaq.org and the owner's credit 1sa.me
  on one plate, toxcore's version on the next, then "Legal & info" with the open source licences and
  "What is Tox?", the licence line under them. Version, Build and the toxcore version only say a value:
  no chevron, not a button to TalkBack, the title and the value read as one. The build string breaks
  only after its own hyphens (AirAboutText, AirAboutTextTest), so at font 2.0 it wraps between its parts
  and never inside one. After them, on Android only, Support as before: Send crash report and Send
  feedback, which hand over to a mail app, and Copy diagnostics; a refusal stands under the crash report
  row. A debug build adds Build details, the diagnostics with the commits and a row for each commit; a
  release build has none, as before. Gone with the old page (activity_about): the wordmark, the name and
  the tagline, the capital headings and the colours of the old chat theme.
- **Release build.** `assembleRelease` with R8 as the release does it (minify on, the same ProGuard
  files), the last one (rel7) with every fix of this stage, built on the Windows node with the test
  guava override and lint and the dependency witness skipped there, signed with the debug key for the
  emulator; versionCode and versionName untouched, the native libraries those of SHA256SUMS.txt. Signed
  so, the APK measures 116,094,020 bytes (110.7 MB), 1,332,897 more than 0.2.45 built the same way
  (114,761,123): compressed, the IBM Plex fonts take 0.70 MB more, the code 0.26 MB and the resource
  table with the strings of four languages 0.25 MB. No keep rule was added: the release build went
  through Air, a conversation with the demo contact (a text sent and its echo back), a call that
  connected, a group and its dossier, a member's screen, You, Network, Settings, About, first launch,
  sign-in, Delete and create new and the App Review path with no FATAL, no ClassNotFoundException or
  NoSuchMethodError in the log and an empty crash buffer. The log keeps the warnings 0.2.45 has: a
  NullPointerException caught in `clear_audio_play_buffers` after a call, and "thread detach failed"
  from the native side.
- **App Review path** on that build, in a fresh secondary Android user: a new profile without a
  password, Create > New contact, the demo contact's ID from the App Review notes, Send request. On the
  last build (rel7) the demo contact accepted in 6 s (the page "Request accepted" with Done, and a
  notification) and within the same minute sent all the notes promise: the greeting, a list of what to
  try, a photo, a voice message of 7 s, Khandaq-demo.pdf (134 KB), a location with its map and
  coordinates, the invitation to "Khandaq Demo Group" and "That's everything". Right after Done the
  battery question comes from below, once (Open battery settings, Cancel), so the reviewer meets it. The
  card in Air has Join and Decline; Join opened the group already connected (2 members, the demo contact
  as founder online, its welcome). On rel5, while Bilal, another member of the same group, ran on the
  same emulator, the request took 102 s and the group 11 minutes to connect after Join, standing under
  the first characters of its ID until then; it goes by the invitation's name now (Invitations, above).
  The cause of that delay is not proven.
- **Old Forgot password** on rel5 and again on rel7: in a fresh secondary Android user, the state the
  old Forgot password of 0.2.45 left was made again as root: the file store and the Tox save of an old
  profile, and its key anchor, next to the database of a new one. 0.2.45 stopped there on "Wrong
  credentials". With a password, the start stops on sign-in with "The profile files on this phone do not
  open with this profile's key. To use Khandaq again, delete them and create a new profile."; the right
  password gets the same words (the file store does not mount), and Forgot the password? > Delete and
  create new leads to the first launch. The new profile reaches Air with a new ID, a new file store and
  Tox save (their hashes are not the old ones) and a new anchor, and opens by itself again after the app
  is stopped. Without a password the page has Delete and create new itself, with the same end.
- **A profile file that does not read**, on rel7 in a fresh secondary user: a logged-in profile with a
  password whose Tox save lost its read permission (chmod 000 as root) stops on sign-in with "No access
  to the profile file. Restart the phone and try again." and Details, which opens
  org.khandaq.messenger.ProfileOpening 1; the right password gets the same words, and once the file
  reads again the next start opens by itself. Found by this run: on rel5 the session opened by itself
  and Tox started without its save, an empty profile online under another ID (Sign-in, above).
- **Full pass.** Every screen the walk reaches (Air and its filters, the command line, Create, New
  contact, New group, a conversation, its menu and dossier, a group, its dossier and settings, You,
  Network, Settings, Move a profile, Advanced, About) at 1080 by 2400 in light, dark, ar, zh and ru, at
  font 1.3 and 2.0, at 360 by 640 and 430 by 932, and with animations off. Found and fixed at font 2.0:
  a Settings row whose value took the room its title needed broke the title inside words ("Connectio
  n"), so a value that would leave the title narrower than its widest word now goes under the title
  (`AirSettingRow`); the name under the far end of a person's link was cut at the bottom, so that view
  grows with the caption size (`AirPathView`); the hint of the command line stopped after "paste an", so
  a hint with no room ends in the platform's ellipsis while TalkBack reads all of it (`AirUi.fitHint`).
  The last walks on the last debug build (light, dark, ar, zh, font 2.0) found two more, both fixed
  below: a mention in a group that vanished on the dark theme, and English dialogs behind a link in a
  message. Every icon button has a label: the walk's audit of clickable nodes without words found only
  the scrim of a sheet (Material's touch_outside, which closes the sheet), on every variant.
- **Tablet.** A tablet AVD made from a system image already installed, both orientations: nothing
  clipped or overlapping. The pages of `AirScreenActivity` (New contact, the group dossier and settings,
  Network) keep the text measure (`size-text-measure`, 760 dp) in the middle of a wide window and run
  the full width below it.
- **Many chats.** A debug build on the emulator (no R8, debuggable: slower than a release build), with
  305 contacts, 2 groups and 7326 messages seeded (a conversation of 5000 and a group of 2000): Air
  scrolls at 17 ms a frame at the median, 19 to 20 ms at the 90th percentile and 22 to 25 ms at the
  99th, 0.2 to 1.1% of frames janky; the conversation of 5000 opens in 224 ms (median of five, the
  system's Displayed; 577 ms cold) and scrolls with 1.0% janky frames; the group of 2000 opens in 212 ms
  and scrolls with 2.5% (44 ms at the 99th percentile).
- **Words.** The legacy strings with banned glyphs are rewritten in all four languages (90 strings): a
  spaced dash became a colon, an ellipsis went, a middle dot became a comma, A to F reads A-F. The note
  under Move a profile in Settings says what each way carries: the encrypted backup the whole profile
  with its chats and files, the plain .tox file (the one Import takes) only the Tox ID and the contacts;
  it said only the second since before the backup was offered there.
- **The emoji panel** of the old conference screen stands on the design's grounds
  (`AirUi.emojiPanelColors`), as in the conversations. The media send preview stays the black media
  screen it is on iOS (`MediaSendPreviewController`): neither client redrew it, and its panel stays dark
  on that ground.
- **Links in a message.** A tap on a link, a mail address or a Tox ID in a message asks from below in
  the app's language (`AirLinkQuestion`): "Open this link?" with the address under it and Open, "Write
  to this address?" with Write, "Send a contact request to this ID?" with Send request. The old dialogs
  asked "open URL?", "send Email?" and "add ID?" with OK, in English in every language. The steps are
  the same, except that the mail app now gets the address (the old intent lost it to its type). So in a
  conversation, in Saved Messages, in a group and in an old conference. Android asks before it opens a
  link, as it did; iOS opens it at once.
- **A mention in a group** is drawn in ink and bold, as iOS draws it (its link-text is ink): it stood in
  the old dark green, which vanished on the dark theme on the selected ground of a message that mentions
  you (`GroupMentionHelper`, `AirLinkQuestionTest`).
- **Before the release.** The pages of `AirScreenActivity` close their header with a hairline
  (`air_screen_rule`, kq-line), as Air, the conversation and the opaque navigation bar of iOS do: New
  contact with the keyboard up slid its ID field under a header with no edge. The first-launch, sign-in
  and PIN lock pages keep an open header, as LoginCoordinator does on iOS (`AirHeaderRuleTest`). You and
  Settings, the two pages of the main screen under its own header, close that header with the same line
  (`main_header_rule`), as You does on iOS; Play frame 05 (You) is taken again with it in en-US and
  ru-RU. The Russian greeting of a request reads "Мир тебе! Добавьте меня, пожалуйста, в контакты.": the
  old imperative with a question mark read as a mistake. iOS and the computer change the same string.
- **Play frame 03 (New contact)** is taken again in en-US and ru-RU on a build with that line: the ID
  field whole with its top edge, the greeting whole, the keyboard up, the heading of the field wholly
  under the header. At the keyboard's normal height the form did not fit between the header and the
  keyboard, by 18 px in English and by 60 px in Russian, whose status under the ruler takes two lines.
  Both frames are taken with the keyboard's own Short height (Gboard, Preferences, Keyboard height), set
  in the test user only; the screen itself is not edited.
- **An observer writes nothing, not even privately.** toxcore refuses every private message of an
  observer (`gc_send_private_message` gives -5 while your own role is GR_OBSERVER), so "Write to a
  moderator" under an observer's read-only bar led to a private message that failed on every Send. That
  bar has no button now: under its words stands "An observer cannot write here, not even privately: ask
  a moderator outside the group.", word for word as on the computer (`air_group_observer_no_private`,
  four languages). Where the voice state holds a member back (moderators only, the founder only) the
  button stays: their own role is a member's, and toxcore takes the message. The private message to a
  member, from the member's sheet and on their own screen, keeps its field for an observer, but off,
  with the same line under it and Send off; the role is read again on Send, so nothing tries to go
  (`AirGroupModel.writesPrivately`, `ComposeRight.asks`, `AirGroupModelTest`). A field that is off is
  sunken, as a button that may not go (`air_bg_input`). What the founder or a moderator writes privately
  to an observer still comes, and is read in the group. The demo world of the debug build seeds no
  private message, and its member screen takes the observer's role from the same reading as the bar.
  Walked with two new test profiles in their own Android users, a founder and an observer in a group by
  invitation: the bar, the member's sheet and the member's screen in light and dark, Russian and Arabic,
  at font 1.0 and 1.3; with the writing left to the moderators, the same member's bar kept "Write to a
  moderator" and the private message went.
- **A sheet with fields takes the keyboard down as it closes** (`AirSheet.withKeyboard`, its dialog's
  dismiss, which every way out ends in): after Cancel or Send on the private message of "Write to a
  moderator" the read-only bar kept a keyboard with nothing to type in. The dialogs with a field of the
  conversation already take it down themselves (`AirChatPanel.hideKeyboardFrom`). The same now holds for
  the sheets of the backup password and of the typed confirmation before a plaintext export
  (`AirSheetKeyboardTest`).
- **Release candidate.** Built again after the observer fix and the sheet keyboard fix, from this tree
  on the Windows node with what the release has, not the test override of the builds above: guava
  33.7.2-android and its dependencies from Maven Central (15 files, 3,137,695 bytes, the five jars equal
  to the pins of `app/witness.gradle`), put into the Gradle cache through a loopback mirror so that the
  dependency witness checks them as it checks any artifact, and lint 31.6.0 from Google Maven (8 jars,
  99,375,548 bytes), each fetched once with the owner's leave. Then offline: `pinChecksums` (339 pins),
  `testDebugUnitTest` (487 tests, 2 skipped), `lintVitalRelease` (no issues) and `assembleRelease` with
  R8; `app/lint.xml` and `app/witness.gradle` unchanged. Signed with the debug key: 116,093,934 bytes,
  versionCode 10440 and versionName 0.2.45, not debuggable, toxcore of arm64-v8a and armeabi-v7a equal
  to SHA256SUMS.txt, nothing of the store world. In a new Android user with Bilal stopped, the App
  Review path: the demo contact accepted in 3 s and sent all the notes promise, Join opened its group
  connected after 19 s; then Air, the demo contact's conversation and dossier, the group and its
  dossier, New contact, Saved Messages, You, Network, Settings and About, with no FATAL, no
  ClassNotFoundException or NoSuchMethodError in the log and an empty crash buffer. Built outside a git
  checkout, About reads Build "????" (the commit comes from `git rev-parse`), so the AAB is to be built
  from a checkout.

Deferred and left out on Android, stage 5:

| What | Why not now | Where |
| --- | --- | --- |
| The conversation, Air, You and Settings at the text measure on a tablet | the conversation's list, its fast scroll and the tray measure the whole window (`AirChatPanel.measureTray`); only the `AirScreenActivity` pages take the measure now | after the release |
| A failed Join from an invitation card ("Could not join group."), and Join while the inviter is offline ("You can join once Amina is online."), are toasts | the card is a row of the live Air list, rebound on every refresh, so a line under its Join would be lost on the next one; the card itself already says the second | after the release |
| A group link opened from another app (tox: with a group key, khandaq:group:) opens the old Join Public Group screen | it joins the same way as the command line (`HelperGroup.join_public_group_by_id`); the old screen is reached only from outside | after the release |
| Codes for support where iOS has them on Join from an invitation card, Leave, and the first launch (ProfileOpening 3, 6 and 7: a file to move in that cannot be read, a key the phone would not keep) | Join from the card is the toast above; Leave does not read the result of toxcore; the first launch says these failures without a code | after the release |
| The PIN is not asked in the run after Log out of a profile with a password and a PIN; the sign-in page takes the password | when the lock runs is a security mechanism; the brief forbids new ones | owner |
| A password manager is checked by `AirAutofillFormTest` only | the emulator has no autofill service | none |
| Sharing into Khandaq from another app: the list of chats (`ShareActivity`, `FriendSelectSingleActivity`) keeps its old rows, round coloured tiles and Saved Messages first | only its words were in this stage (four languages, its own line for an empty list); iOS has no list to follow, it asks with the choice of Forward (above) | after the release |
| Only a member's private message in a read-only group, sent through "Write to a moderator", picks the moderator from a list dialog when there are several (`AirGroupPanel`) | the rest of the member's actions are sheets; this choice is reached only in a read-only group with more than one moderator | after the release |
| The group "QA roles" stays on the test profile Bilal | made by the stage 3 checks; it is never in store or site frames | none |

### Block, on every client

Decided 11.11.2026 for all clients; iOS has it, Android and the desktop follow this contract. Tox has
no block, so it is local: the protocol does not change and the other side is not told.

- **Where:** the dossier of a person, next to Remove. Block asks once; the confirmation states the
  consequence in one sentence ("The contact and the conversation are removed from this device, and
  new requests from this key will not be shown.") and its button is the verb, Block.
- **What it does,** in this order: the 64-character public key goes on this device's block list,
  then the contact is removed through the same path as Remove, local marks included (day strip, mute,
  verification date, refused messages, pin). With the key listed first, a request that lands in
  between is already dropped.
- **Requests from a blocked key are never shown:** not in the list, the badge or a notification. The
  client drops such a request as it lands, the way Decline drops one; nothing is sent back.
- **Settings > Privacy > Blocked** lists the keys in groups of four, oldest first, each with Unblock;
  the Privacy row shows how many there are, or "None". Unblock takes the key off the list and says
  "Key unblocked". The contact does not come back by itself: a new request from that key shows again.
- **The list** holds public keys only, 64 hex characters in upper case; a whole 76-character ID or a
  shorter string is not taken. It stays on this device, outside the Tox profile, so an exported
  profile does not carry it. iOS seals it together with the refused messages (AES-GCM, the key in
  this device's keychain): unlike the history, `air-local.json` is not encrypted with the profile. A
  backup restored on another phone loses both, and the store starts a new sealed part there.
- **Groups are out of scope:** NGC peer keys are per group and do not match the friend key.

### Unknown markup, on every client

Decided 12.11.2026 for all clients (stage 3, item 10); iOS has it, and Android since build s3-17.

- **Markup a build does not know** (a message that opens with `[KQ|` and is neither a reply nor a
  mention) shows as the text after the markup; with nothing after it, as the sentence "This message is
  in a form this app cannot show yet". A notification says the same. Plain text that merely contains
  `[KQ` stays as it is.
- **Edits, deletions and reactions** travel as the packets Android builds: 186, 187 and 188 between two
  people, 0x41, 0x42 and 0x43 in a group. A packet of another version, length or kind is dropped,
  never shown. The forward header keeps its mark at the start of the message text, since the other
  clients look for it there; the strings carry only the words.

## Deviations from the B prototype, with reasons

| In the prototype | Now | Why |
| --- | --- | --- |
| Initials, times and counters in Plex Mono | Plex Sans | brief: monospace only in code; owner's option «B + правила брифа» |
| Breathing dots for connecting and busy states | still dot plus words | brief bans endless pulsing; owner's option |
| Amber stripe on the left of the tray | plain `transit-tray` ground | brief bans coloured left stripes; owner's option |
| Small caps labels | normal case | brief; owner's option |
| Hairline frames around status cards, request card, dialog, boards | separation by ground colour | brief bans 1 px frames around cards |
| Rules on both sides of the day label | the day heads its messages, set at the text column | brief bans decorative separators |
| Translucent focus halo in fields | the same 2 px focus ring as everywhere | one focus style; no glow |
| Literal sizes and colour mixes in CSS | tokens only | brief: no colour, size or spacing outside tokens |
| Phone buttons and fields 40 px, pills 32 px | 44 pt targets | brief: touch targets from 44 px |
| Desktop tile 36 px, icons 18 px on the page | `tile-desk` 32, `icon` 20, `icon-small` 16 | back to the B token values |
| The lock-screen gradient (`lock-top` to `lock-bottom`) under calls and the PIN | a flat `lock-top` ground | brief bans gradients |

## Brief compliance

Checked against `~/Documents/design-prompt.md` on 10.11.2026 for stage 1 (the system page). Screens
are checked again as they land.

| Brief item | State | Evidence |
| --- | --- | --- |
| Research before pictures | done | `docs/research.md` |
| Design plan before markup | done | `docs/design-rules.md` |
| 2-3 directions as pictures, wait for the choice | done | directions A, B, C; variants Tail and Moat; owner's words above |
| Design system with tokens, local fonts, components, service page | done | this file, `design/system/` |
| No colour, size or spacing outside tokens | done for the system page | `site/system.css`: no literal colours or sizes except the `--sg-*` layout of the reference page itself and `@media` widths checked against `breakpoint` tokens |
| No more than two typefaces, monospace only in code | done | Plex Sans (with its Arabic) and Plex Mono for IDs and keys |
| Both themes, system by default, reduced motion | done | page switches, smoke test |
| Nothing from the banned list on the system page | done | frames, stripes, small caps, pulsing, identical card rows removed |
| Touch targets from 44 px on the phone | done on the page | 44 pt controls, larger touch areas on pills and Retry |
| One signature gesture | done | "the person came online" |
| Eye checks on all widths, two rounds | first round done for the system page; Air on iOS checked in both themes | smoke screenshots 390 to 1720 px, both themes; XCUITest walk shots in `design/redesign/stage1/ios/air/` |
| axe, Lighthouse, measurements | pending | stage 7 of the brief, before delivery |
| Screens in all clients | in progress: the Air frame on iOS | stage 1 item 6, then stage 2 onwards |

## Tokens

The tables below are written by `design/system/build.py` from `tokens.json`. Change the tokens, not
the tables.

<!-- generated by design/system/build.py from tokens.json: edit tokens.json, not these tables -->

### Palette

Lowest contrast is the worst pair from the contrast rules in each theme; `build.py` fails below the threshold.

| Token | Light | Dark | Use | Lowest contrast as text (light / dark) |
| --- | --- | --- | --- | --- |
| `bg` | `#f3f4f6` | `#0c0f13` | app background | ground for text |
| `surface` | `#ffffff` | `#12161b` | main surface: list, conversation | ground for text |
| `raised` | `#ffffff` | `#171c22` | buttons, sheets, popovers | ground for text |
| `sunken` | `#eceef2` | `#1c222a` | fields, tiles, command line | ground for text |
| `selected` | `#e8ebf0` | `#222a34` | selected row, own message tint | ground for text |
| `ink` | `#0f1218` | `#e7eaef` | primary text, primary button | 15.69:1 on `selected` / 12.02:1 on `selected` |
| `ink-2` | `#4a5160` | `#a3aab6` | secondary text | 6.66:1 on `selected` / 6.20:1 on `selected` |
| `ink-3` | `#5f6676` | `#8a92a0` | service text: time, captions | 4.82:1 on `selected` / 4.62:1 on `selected` |
| `on-ink` | `#ffffff` | `#12161b` | text on an ink button | 7.58:1 on `ink-pressed` / 8.24:1 on `ink-pressed` |
| `switch-on` | `#0f1218` | `#6b7686` | switch track when on, under a white knob | 16.14:1 on `sunken` / 3.48:1 on `sunken` |
| `line` | `#e0e3e8` | `#232a33` | separators | not used for text |
| `line-strong` | `#c3c8d1` | `#353e4a` | button and field borders | not used for text |
| `live` | `#11804b` | `#3fcf82` | reachable now, leaves at once | 4.17:1 on `selected` / 7.14:1 on `live-soft` |
| `live-soft` | `#e9f7ee` | `#10301f` | ground under live | ground for text |
| `transit` | `#9c5a06` | `#f0aa40` | on its way | 4.69:1 on `transit-soft` / 7.24:1 on `transit-soft` |
| `transit-soft` | `#fbedd6` | `#36270e` | ground under transit | ground for text |
| `danger` | `#bf3329` | `#ff8b7e` | broken, blocked, not sent | 4.65:1 on `danger-soft` / 7.04:1 on `danger-soft` |
| `danger-soft` | `#fbe4e1` | `#3a1714` | ground under danger | ground for text |
| `focus` | `#2456d6` | `#86a8ff` | keyboard focus ring only | 5.65:1 on `bg` / 7.39:1 on `raised` |
| `scrim` | `#0a0c1042` | `#00000073` | dimmed background under sheets | not used for text |
| `tone-1` | `#0b6e4f` | `#5ccf9e` | author name in groups, initials on a tile | 5.38:1 on `sunken` / 8.29:1 on `sunken` |
| `tone-2` | `#3446a8` | `#9aa7f5` | author name in groups, initials on a tile | 6.99:1 on `sunken` / 7.03:1 on `sunken` |
| `tone-3` | `#8b3a7f` | `#df98d3` | author name in groups, initials on a tile | 6.01:1 on `sunken` / 7.30:1 on `sunken` |
| `tone-4` | `#7f530e` | `#e0b36a` | author name in groups, initials on a tile | 5.75:1 on `sunken` / 8.25:1 on `sunken` |
| `tone-5` | `#0e6b78` | `#6dccd8` | author name in groups, initials on a tile | 5.33:1 on `sunken` / 8.60:1 on `sunken` |
| `tone-6` | `#9b3b2b` | `#ef9a86` | author name in groups, initials on a tile | 5.92:1 on `sunken` / 7.35:1 on `sunken` |
| `fixed-white` | `#ffffff` | `#ffffff` | text and icons on media and call screens | 4.98:1 on `live-fill` / 4.60:1 on `switch-on` |
| `fixed-black` | `#000000` | `#000000` | video background | not used for text |
| `live-fill` | `#11804b` | `#15784a` | filled green button with a white icon (answer a call) | ground for text |
| `danger-fill` | `#bf3329` | `#b8352b` | filled red button with a white icon (decline a call) | ground for text |
| `media-control` | `#ffffff33` | `#ffffff33` | translucent control over media and calls | not used for text |
| `media-shade` | `#0f1218eb` | `#05070aeb` | in-app banner, dark shade over media | ground for text |
| `lock-top` | `#0f1218` | `#0c0f13` | lock screen, top | ground for text |
| `lock-bottom` | `#1c222a` | `#171c22` | lock screen, bottom | ground for text |
| `ink-hover` | `#35383d` | `#c5c8cd` | primary button under the pointer | ground for text |
| `ink-pressed` | `#525459` | `#abafb4` | primary button while pressed | ground for text |
| `ink-badge` | `#44464b` | `#b8bbc0` | counter ground inside a pressed ink pill | ground for text |
| `live-cell` | `#89bca7` | `#2c7052` | hour the contact was online, in the day strip | not used for text |
| `transit-tray` | `#fdf5e8` | `#261f14` | ground of the On its way tray | ground for text |
| `shadow` | `#0f121847` | `#00000099` | shadow under overlays only (toast, dialog, sheet, a row being moved), never under resting elements | not used for text |

### Type roles

| Role | Family | Weight | Desktop px | Phone px | iOS style | Use |
| --- | --- | --- | --- | --- | --- | --- |
| `display` | text | 600 | 22/28 | 24/30 | title2 | screen titles |
| `heading` | text | 600 | 15/20 | 17/22 | headline | names, section heads |
| `body` | text | 400 | 14.5/21 | 16/22 | body | message text, paragraphs |
| `label` | text | 500 | 13.5/18 | 14/18 | subheadline | buttons, field labels, list secondary lines |
| `caption` | text | 400 | 12/16 | 12.5/16 | caption1 | service text: time, sizes, counters, hints |
| `mono` | mono | 500 | 12/16 | 12.5/16 | caption1 | Tox IDs and public keys only, never times or counters |
| `mono-entry` | mono | 400 | 14/20 | 16/22 | body | a Tox ID typed or pasted into a field, at reading size |

### Font files

Each client bundles these files from `design/system/fonts` (SIL Open Font License) and loads a face by its PostScript name, read from the file.

| File | PostScript name | Family | Weight |
| --- | --- | --- | --- |
| `IBMPlexSansArabic-Regular.ttf` | `IBMPlexSansArabic` | arabic | 400 |
| `IBMPlexSansArabic-Medium.ttf` | `IBMPlexSansArabic-Medm` | arabic | 500 |
| `IBMPlexSansArabic-SemiBold.ttf` | `IBMPlexSansArabic-SmBld` | arabic | 600 |
| `IBMPlexMono-Regular.ttf` | `IBMPlexMono` | mono | 400 |
| `IBMPlexMono-Medium.ttf` | `IBMPlexMono-Medm` | mono | 500 |
| `IBMPlexSans-Regular.ttf` | `IBMPlexSans` | text | 400 |
| `IBMPlexSans-Medium.ttf` | `IBMPlexSans-Medm` | text | 500 |
| `IBMPlexSans-SemiBold.ttf` | `IBMPlexSans-SmBld` | text | 600 |

### Spacing, radius, stroke

Spacing scale, px (`--kq-space-N`, `KQSpace.xN`): 2, 4, 6, 8, 10, 12, 16, 20, 24, 32, 40, 48, 64.

| Radius | px |
| --- | --- |
| `tick` | 2 |
| `small` | 4 |
| `control` | 7 |
| `field` | 8 |
| `card` | 10 |
| `sheet` | 14 |
| `round` | 999 |

| Stroke | px |
| --- | --- |
| `hairline` | 1 |
| `strong` | 1.5 |
| `focus` | 2 |
| `icon` | 1.7 |
| `mark` | 1.4 |

### Elevation

Overlays only, drawn in `shadow`; resting elements have no shadow.

| Elevation | x, y, blur (px) |
| --- | --- |
| `lift` | 0, 8, 24 |
| `toast` | 0, 10, 30 |
| `dialog` | 0, 24, 60 |
| `sheet` | 0, -10, 30 |

### Sizes and breakpoints

| Size | px |
| --- | --- |
| `target-ios` | 44 |
| `target-android` | 48 |
| `row-phone` | 68 |
| `row-desk` | 56 |
| `row-desk-compact` | 44 |
| `list-column` | 352 |
| `text-measure` | 760 |
| `icon` | 20 |
| `tile-phone` | 40 |
| `tile-desk` | 32 |
| `tile-compact` | 28 |
| `control` | 36 |
| `control-small` | 28 |
| `field` | 40 |
| `chip` | 32 |
| `badge` | 20 |
| `mark` | 18 |
| `icon-small` | 16 |
| `dot` | 8 |
| `bar` | 6 |
| `time-column` | 44 |
| `time-column-phone` | 38 |
| `switch-width` | 40 |
| `switch-height` | 24 |
| `switch-knob` | 18 |
| `pin-cell` | 52 |
| `id-field` | 92 |
| `compose-max` | 132 |
| `dialog` | 420 |
| `grip-width` | 36 |
| `grip-height` | 5 |
| `link-mark` | 30 |
| `hours` | 168 |
| `hour-cell` | 12 |
| `hour-cell-tall` | 28 |
| `skeleton-line` | 12 |

| Breakpoint | max-width px |
| --- | --- |
| `phone` | 760 |
| `tablet` | 1100 |

### Motion

| Duration | ms |
| --- | --- |
| `tap` | 120 |
| `state` | 200 |
| `layout` | 280 |
| `reveal` | 440 |
| `stagger` | 90 |

| Easing | cubic-bezier |
| --- | --- |
| `out` | 0.2, 0.8, 0.2, 1 |
| `standard` | 0.4, 0, 0.2, 1 |

With reduced motion every duration is capped at 120 ms and the stagger is 0.

<!-- end of generated tables -->
