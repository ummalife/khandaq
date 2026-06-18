# Khandaq — NGC chat-id join fix: current status / handoff

**Updated:** 2026-06-17

## The problem being solved
Joining a **public NGC group by chat-id** ("Join a public group → paste ID") shows an empty room
(no participants, no messages) on UDP-restricted networks (mobile carriers, CGNAT, some countries).
Root cause: chat-id join needs the DHT/onion **announce-lookup**, which depends on UDP. Groups the
phone is already in were added **manually / by friend-invite** (works over TCP), not by chat-id.
See [KHANDAQ_NGC_TCP_DISCOVERY_AND_NDK_PLAN.md](KHANDAQ_NGC_TCP_DISCOVERY_AND_NDK_PLAN.md).

## What is DONE (this work)
1. **App-layer mitigations (Java, built & verified on devices):**
   - `HelperGroup.maybe_hint_udp_blocked` — one-shot in-chat hint when a public group is stuck
     CONNECTING with no peers and no friend to fall back on (RU/EN strings added).
   - Smart group-share invite: `GroupInfoActivity` long-press "copy id" → shares `Group ID + my Tox ID`.
   - Receiving side: `JoinPublicGroupActivity` parses a pasted invite (chat-id + inviter Tox ID),
     adds the inviter as a contact and arms the friend-assisted join (`send_group_invite_request_to_friends`).
   - Hardening: on friend-online, force-resend pending invite-requests + delayed retries
     (`HelperGroup.schedule_friend_online_invite_resends`), bypassing the 30s rate-limit.
   - Honesty: `MainActivity` udp-tcp migration is annotated as a no-op (overridden by jni:699).

2. **Native fix (toxcore) — the real cure, NOW BUILT & RUNNING:**
   - Patch: `patches/khandaq-ngc-tcp-announce.patch` (+ context-independent applier
     `patches/apply_khandaq_patch.py`). In `self_announce_group` (Messenger.c), when the group's
     `chat->tcp_conn` has 0 connected relays, fall back to the global net_crypto TCP relay pool
     (`nc_get_tcp_c`) so the announce ALWAYS carries a reachable TCP relay → a UDP-blocked/TCP-only
     joiner can reach the announcer. This is the "undelivered patch" from the stability report (#12).
   - **Build pipeline now works:** `scripts/build-android-native-so.sh` runs the upstream TRIfA
     Docker build (`circle_scripts/deps.sh`) with the patch auto-injected, after fixing 4 legacy
     toolchain-rot issues (NDK r13b vs modern deps): `--api 21`, `--disable-mediacodec`,
     libsodium `1.0.18` + `--disable-asm`, robust python patcher.
   - **VERIFIED:** patched `arm64-v8a libjni-c-toxcore.so` built from source, installed, and the app
     loads + tox bootstraps + goes ONLINE on the emulator (no crash). Native log confirms the fix:
     `self_announce_group: Published group announce. TCP relays: 1` (announce carries a TCP relay).
   - Patched .so is installed into `android-refimpl-app/app/nativelibs/arm64-v8a/libjni-c-toxcore.so`
     (original backed up at `/tmp/khq/libjni-arm64-ORIG-backup.so`).

## IMPORTANT caveats
- The built .so is a **TEST build**: no HW mediacodec, libsodium 1.0.18, software x264. Fine for
  verifying the fix; a **release** .so must be built in proper x86_64 Linux/CI (the harness works
  there natively, ~30-60 min, no emulation). Building on this Apple-Silicon Mac uses slow x86_64
  emulation and the full 4-ABI build gets time-killed (~69 min); arm64-v8a completes, others may not.
- The fix is the **announcer side** (members advertise a TCP relay). The **joiner-side onion
  announce-lookup over pure TCP** (path-nodes are DHT/UDP-seeded) remains an open toxcore issue, so
  this fix improves reachability of members-with-the-fix but does not, alone, make a fully-UDP-blocked
  joiner resolve a cold chat-id.
- `CTOXCORE_NATIVE_LOGGING` in `MainActivity.java` was flipped to `true` for QA — **revert to false
  before release**.
- Emulators are arm64 (Apple Silicon); 32-bit armeabi .so won't run on them. Two same-host emulators
  can't P2P (shared NAT); cross-device tests need a real device or a well-connected rendezvous group.

## How to continue (nothing is committed yet)
- Changed/added files (uncommitted): `HelperGroup.java`, `MainActivity.java`, `GroupInfoActivity.java`,
  `JoinPublicGroupActivity.java`, `res/values/strings.xml`, `res/values-ru/strings.xml`,
  `patches/*`, `scripts/build-android-native-so.sh`, several `docs/*`.
- Build the debug APK: `cd khandaq-android-trifa/android-refimpl-app && ./gradlew :app:assembleDebug`
  (bundles the patched arm64 .so from `app/nativelibs/`).
- Field-verify the fix with two REAL devices (a mobile joiner + a member running the patched build).
- Build a release .so on x86_64 Linux/CI via `scripts/build-android-native-so.sh`.

## Field test 2026-06-17 (real device + emulator, adversarially verified)
Setup: JOINER = real Xiaomi 2201116SG (arm64) on Turkcell LTE/CGNAT, Wi-Fi off; ANNOUNCER =
arm64 emulator (founder of a fresh public group, chat-id `828f445d…c53f23`). Both ran the patched
debug APK (arm64 patched `.so` bundled from `app/nativelibs/`). Native QA logging on. Verified by a
4-lens adversarial log review (see workflow `verify-ngc-join-fieldtest`).

What was PROVEN:
- Patched `.so` loads & runs on the real Xiaomi with no crash (existing profile/groups intact).
- The fix's mechanism executes: announcer logs `self_announce_group … TCP relays: 1` continuously,
  including **10× at `UDP status: 0`** — i.e. the new `nc_get_tcp_c` fallback (`if (tcp_num==0)`)
  really fires and supplies a relay when `chat->tcp_conn` has none. (The Xiaomi also logged
  `TCP relays:1 / UDP status:0` in an earlier pre-group capture.) Announcer-side fix = working.

What FAILED (bug reproduced): cold chat-id join from the LTE joiner → **empty room**,
`group_conn=0 tox_peers=1` for >8 min (doc budget is 30–90 s). Both sides' discovery-dependent
group never gained a peer. Meanwhile a friend-added NGC group on the same joiner reached
`group_conn=1 tox_peers=3 direct=1 relay=1` (TCP transport fine) → failure is isolated to cold
chat-id **discovery**, not transport. Announcer health ruled out as the cause (its other public
group was `group_conn=1 tox_peers=4` in the same window).

Conclusion (CONFIRMED, medium confidence): the announcer-side fix is **necessary but not
sufficient**. The binding blocker is the **joiner-side onion announce-lookup (the unimplemented
“part B”)**: its path-nodes are seeded from the UDP-DHT close-list, and on the LTE joiner the
UDP/DHT bootstrap did not come up, so a cold chat-id can’t be routed. This empirically matches the
"IMPORTANT caveats" above and `KHANDAQ_NGC_TCP_DISCOVERY_AND_NDK_PLAN.md` §B.

Honest corrections from the verification (don’t overclaim):
- The joiner’s observed `tox_bootstrap … UDP: FAILURE` lines (51/51) were **IPv6-only** nodes
  failing `Network is unreachable` (Turkcell has no IPv6 route); app-level `bootstrap_udp_parallel:
  ok≈17/20` shows IPv4 UDP sockets did reach node ports. So "UDP-DHT is dead" is **inferred**
  (from behaviour + docs), not observed at DHT/onion-lookup packet level. No per-packet DHT logs exist.
- The fallback-at-`UDP status:0` proof is announcer-side; during the join window the joiner’s
  announces were all `UDP status:1` (its `if(tcp_num==0)` branch wasn’t needed there).
- Test-rig confounds: announcer was a single emulator behind host NAT (a weak endpoint, though not
  the binding cause); group `gn` labels differ between captures (joiner target was `gn=7 id=c53f23`).

Next step to make it conclusive / fix it: instrument toxcore to log onion announce-lookup path-node
seeding (`onion_client.c populate_path_nodes`) so the inferred chain becomes observed; re-test on two
REAL UDP-restricted devices; then implement **part B** (seed onion announce-lookup path-nodes from
TCP-relay-reachable nodes). Secondary: prefer IPv4 bootstrap nodes to remove the IPv6 confound.
Captured logs: `/tmp/khq/logs/{xiaomi_join,xiaomi_full,emu_announce,emu_full}.log` (transient).
Nothing committed; `CTOXCORE_NATIVE_LOGGING` still `true` at `MainActivity.java:354` (revert for release).

## Part B implemented + instrumentation finding 2026-06-17
A toxcore-source deep-dive (workflow `design-part-b-tcp-discovery`) localized the cold-join bug to a
single asymmetry in `onion_client.c`: the onion announce-LOOKUP for a group runs through `do_friend()`,
whose per-friend repopulate block drew candidate nodes ONLY from the UDP-DHT pool `path_nodes`. When
that pool is empty (UDP-DHT down) it hit `n==0` and returned, never sending a lookup request → empty
room. `do_announce()` already falls back to the bootstrap pool `path_nodes_bs` in exactly this case;
`do_friend()` did not. The onion SEND path over TCP relays is already fully wired (hop-0 = TCP relay
when `dht_isconnected()==false`); inner hops 2–3 must be UDP-family DHT nodes, which `path_nodes_bs`
provides (TCP relays are hop-0 only — seeding them as inner hops is wrong, and is explicitly NOT done).

Patch (built, in `.so`): `patches/apply_khandaq_partb.py` (+ ref `patches/khandaq-ngc-partb.patch`),
wired into `scripts/build-android-native-so.sh` like the Messenger.c patch.
- STEP 1 fix: in `do_friend` mirror the `do_announce` `path_nodes_bs` fallback, gated on
  `!onion_c->udp_connected` so UDP networks are byte-for-byte unchanged; send loop reads the selected
  `pool` instead of `onion_c->path_nodes`.
- Instrumentation (no behavior change): probe 1 in `do_friend` logs
  `KHANDAQ partB do_friend … pn_idx pn_bs_idx num_nodes n udp tcp_relays onion_status pool_bs`; probe 2
  in `do_onion_client` logs `KHANDAQ partB onion_state …` each tick when `!udp_connected`.

Instrumentation field run (Xiaomi/Turkcell LTE joiner + emulator announcer) — IMPORTANT refinement:
the probe showed the joiner is **NOT actually UDP-blocked** on this network:
`udp=1 pn_idx=31 pn_bs_idx=26 num_nodes=31 n=8 onion_status=2`, and probe 2 (fires only at `udp=0`)
never fired. So `udp_connected=1`, both path-node pools are full, and lookup requests ARE being sent
(`n=8`). The earlier `tox_bootstrap … UDP: FAILURE` lines are IPv6-no-route noise; IPv4 UDP-DHT works.
Therefore: (a) the simple "empty path_nodes" mechanism is NOT what failed on this network; (b) the
empty room is better explained by the weak **emulator announcer behind host NAT** (the verification
confound); (c) `pn_bs_idx=26` confirms the bootstrap pool IS seeded, so STEP 1's fallback has material
to use IF/when `udp_connected` is genuinely 0.

STATUS of part B: STEP 1 + instrumentation BUILT into the patched arm64 `.so`, smoke-tested (no
regression on UDP), THEN validated against a forced `udp=0` joiner — and the validation REFUTED STEP 1.

### Forced udp=0 validation 2026-06-17 (SOCKS5 proxy) — STEP 1 DISPROVEN
Method: ran a local SOCKS5 proxy on the Mac, `adb reverse tcp:9050 tcp:1080` on the emulator, set
`orbot_enabled=true` in the app prefs (via run-as on the throwaway emulator) so the JNI sets
`options.proxy_type = TOX_PROXY_TYPE_SOCKS5` (127.0.0.1:9050) → toxcore forces `udp_disabled` even
though `jni-c-toxcore.c:699` hardcodes `udp_enabled=true`. (The app has NO UI to enable a proxy:
`orbot_enabled` is never written via `putBoolean`, and the udp_enabled toggle is the documented no-op.)

Observed (instrumentation): probe 2 fired (`udp=0` confirmed). With udp=0 + working TCP the onion came
up over TCP (`onion_status=1` = ONION_CONNECTION_STATUS_TCP), tox went online, and `path_nodes` (pn_idx)
grew to 400+ — seeded by TCP onion announce RESPONSES, themselves bootstrapped by `do_announce`'s
EXISTING `path_nodes_bs` fallback. `do_friend` ran and sent group lookups (`n=3`). A populated NGC group
(`TEST APP NEW`) FULLY CONNECTED at udp=0: `group_conn=1 tox_peers=4 relay=3`.
**`pool_bs=1` never appeared — STEP 1's fallback fired ZERO times.**

Why STEP 1 is effectively dead code: `do_friend` only runs when `onion_connection_status != NONE`, and
the onion only reaches a connected status AFTER `path_nodes` is seeded (pn_idx > 0). So by the time
`do_friend` runs, `path_nodes` is never empty → the `pool_index == 0` fallback condition is unreachable
in practice. The do_friend↔do_announce "asymmetry" the design flagged is real in source but masked by the
onion-connected gating. STEP 1 is harmless (gated, no regression) but is NOT a fix.

REVISED ROOT-CAUSE PICTURE (two theories now disproven by instrumentation): the empty room on cold
chat-id join is NOT (a) "UDP-DHT dead on the joiner" (the Xiaomi's UDP works; failures were IPv6 noise)
and NOT (b) "do_friend lacks a path_nodes_bs fallback" (path_nodes populates over TCP; the fallback
never fires; a TCP-only node connects to groups whose peers are reachable). The determining factor is
**announcer / group-member REACHABILITY**: `TEST APP NEW` (peers online & reachable) connects at udp=0,
while the cold `KHQ-TCP-TEST` did not — its only "member" was the host-NAT'd emulator announcer, which
is not discoverable. I.e. the joiner side already works over TCP; the open problem is making a cold
group's announcer findable/reachable, not seeding joiner path-nodes.

RECOMMENDATION: do NOT ship STEP 1 as a "fix" (it is inert). Options: (1) revert STEP 1's behavioral
change, keep part A + (optionally) the instrumentation; (2) re-scope part B to announcer
discoverability — test cold join against a REACHABLE announcer (a real well-connected device or a
rendezvous relay), and instrument the announce-LOOKUP resolution (`do_friend` lookup → response →
`gc_add_peers_from_announces`) to see where a cold chat-id fails to yield a usable peer+relay;
(3) lean on the already-working app-layer friend-assisted invite for UDP-restricted users.
Test artifacts: `/tmp/khq/socks5.py`, prefs backups; emulator pref reverted, proxy stopped.

### Part A validation 2026-06-17 (reachable announcer: Xiaomi announcer + emulator joiner)
To test whether part A's relay-in-announce makes a cold chat-id join succeed when the announcer is a
real device (not the host-NAT'd emulator), the Xiaomi (part A, public group `KHQ`, online, publishing
`TCP relays:1`) was the ANNOUNCER and emulator-5556 (well-connected: it sits in other groups at
tox_peers 3/5/6, onion `status=2` UDP, `pn_idx=1070`, sending lookups `n=8`) cold-joined `KHQ` by chat-id.

Result after >3 min: FAILED. Joiner's new group `gn=8 id=31cc6d` stayed `tox_peers=1 conn=0`
(`post_join_peer_discovery round=2..7 peers=1 conn=0`); the Xiaomi announcer's `KHQ` stayed
`group_conn=0 tox_peers=1`. They never found each other. So **part A alone does NOT fix cold join**:
the joiner is healthy and sends announce-lookups, the announcer publishes a relay, but the lookup does
not resolve to the lone founder. Cold join failed in BOTH directions tested (Xiaomi↔emulator).

### Overall conclusion of the native-fix investigation (2026-06-17)
- Part A (announce always carries a TCP relay) — mechanically correct & verified, but NOT sufficient to
  fix the user-facing bug on its own.
- Part B / STEP 1 (do_friend path_nodes_bs fallback) — DEAD CODE (never fires; onion-connected gating
  guarantees path_nodes is non-empty when do_friend runs). Recommend reverting.
- Joiner side over pure TCP WORKS (onion comes up TCP, path_nodes seeds via TCP, lookups sent, and it
  connects to groups that have multiple reachable members — even at udp=0).
- The binding bottleneck is **announce-LOOKUP resolution for a sparse / hard-to-reach announcer**: a
  lone-founder public group on a CGNAT/NAT node is not discoverable by a cold joiner, regardless of the
  relay in its announce. Groups with several established reachable members connect fine.
- IMPORTANT test limitation: the only public groups under test control were lone-founder (worst case).
  The realistic scenario — cold-joining a public group that ALREADY has several reachable members — was
  NOT tested (needs a well-populated rendezvous group / more real devices) and may behave better.
- Practical fix for users remains the already-implemented app-layer friend-assisted invite.
### DECISIVE test 2026-06-17: cold join of a REAL populated group — BUG CONFIRMED
A genuine community group "Grrrroooouuuppp" (chat-id 71f8bb…87aadd78, real human members:
тест/11AE2E/Abdul Halim, multi-day history, shown "4 members · 3 online") was cold-joined by
emulator-5554 (which was NOT a member). Result: FAILED — its new group gn=5 id=aadd78 stayed
`tox_peers=1 conn=0` for >5 min (post_join_peer_discovery gave up after 7 rounds). CONTROL: in the
SAME window, emulator-5556 (same host-NAT as 5554) AND the Xiaomi were both connected to that group
at `tox_peers=3`, and 5554 itself was connected to another group (gn=0 3cca28, tox_peers=4).

This is decisive: a reachable member of the group exists on the very same host (5556), yet the fresh
cold-chat-id joiner (5554, which carries part A) cannot resolve/connect to the group. So:
- The bug is REAL for real populated groups — NOT just empty/lone-founder groups (earlier hypothesis
  refuted). Joining a public NGC group by cold chat-id does not connect, even when the group is live
  and reachable from the same network.
- It exactly matches the original report: invite-joined memberships stay connected (5556/Xiaomi), but
  a fresh chat-id join shows an empty room.
- Part A does NOT fix it. The broken component is the cold-chat-id announce-LOOKUP (finding the group
  by id), isolated because reachability is proven (5556 same-host is connected) yet lookup fails.
Residual caveat: the currently-online members were our own NAT'd nodes (Xiaomi CGNAT + 5556 host-NAT);
a non-NAT online member was not guaranteed. But since 5556 (same host as the joiner) is connected,
reachability is not the blocker — the chat-id lookup is.

RECOMMENDED next directions: (1) test cold join against a public group with multiple reachable members
(real community group or 3+ real devices); (2) if pursuing native discovery, instrument/improve the
onion announce STORE+LOOKUP so a sparse announcer is findable over TCP (deeper than part A/B); (3) ship
part A + app mitigations as incremental improvements and revert the inert STEP 1.
