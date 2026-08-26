#!/usr/bin/env python3
"""KHANDAQ gate — the main thread must never wait on something that may never happen.

Play reported a user-perceived ANR rate of 2.17% against a crash rate of 0.00%: nothing was
failing, something was hanging. The mechanism was a wait that no longer had any way to end.

AudioRecording's thread waited on `native_audio_engine_running` and on nothing else. That flag
belongs to AudioReceiver, which sets it only after NativeAudio.createEngine() returns — inside a
try/catch, so a device where the engine fails to start leaves it false forever. close() set
`stopped`, which the loop did not read. AudioReceiver.close() set the flag to false, which is the
very value the loop was waiting to leave. And stop_audio_system() then join()ed that thread with no
timeout from the main thread — ConferenceAudioActivity.onPause() among the callers. Every part was
reachable; together they were a permanent freeze.

Four things are asserted here, and each is the general form of one of those parts rather than the
single line that was wrong. The bug in this repository has repeatedly been a protection applied to
one path and not to its neighbour, so a gate that pins one line is worth very little.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TRIFA = ROOT / "khandaq-android-trifa/android-refimpl-app/app/src/main/java/com/zoffcc/applications/trifa"

failures: list[str] = []


def body(text: str, start_pat: str) -> str:
    """Source from the line matching start_pat to the end of that brace-balanced block."""
    m = re.search(start_pat, text)
    if not m:
        return ""
    i = text.index("{", m.end() - 1) if "{" not in m.group(0) else m.start() + m.group(0).index("{")
    depth, j = 0, i
    while j < len(text):
        if text[j] == "{":
            depth += 1
        elif text[j] == "}":
            depth -= 1
            if depth == 0:
                return text[i:j + 1]
        j += 1
    return text[i:]


def strip_comments(text: str) -> str:
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return "\n".join(l for l in text.splitlines() if not l.strip().startswith("//"))


# ---------------------------------------------------------------- 1. no new unbounded Thread.join()
#
# Every argument-less .join() left in the package is listed here with the reason it is allowed to
# stay. The point is not the current tally — it is that adding a new one anywhere fails this gate
# until somebody writes down why the main thread can afford to wait on it forever.
ALLOWED_BARE_JOINS = {
    # file                            exact source line                       count  why
    ("TrifaToxService.java", "ToxServiceThread.join();"): (
        1, "runs on the background thread started at stop_tox_fg:457, and precedes tox_kill() — "
           "bounding it would let native teardown race the iterate loop"),
    ("GroupMessageListActivity.java", "t.join();"): (
        1, "set_peer_names_and_avatars, tracked as a separate change: the fix is a dedicated peer "
           "lock, not a timeout, and needs QA on a group with >100 peers"),
    ("ConferenceAudioActivity.java", "t.join();"): (
        3, "same shape as above, on the legacy conference path"),
    ("ConferenceMessageListActivity.java", "t.join();"): (
        2, "same shape as above, on the legacy conference path"),
    ("HelperGeneric.java", "new_thread.join();"): (
        1, "toxav video send path — runs on the encoder thread, never the main thread"),
    ("HelperGeneric.java", "new_thread2.join();"): (
        1, "same"),
    ("FileLoader2.java", "t.join();"): (
        1, "Glide model loader — runs on Glide's own executor"),
}

seen: dict[tuple[str, str], int] = {}
for path in sorted(TRIFA.glob("*.java")):
    for line in strip_comments(path.read_text(encoding="utf-8", errors="replace")).splitlines():
        stripped = line.strip()
        # Thread.join() only — CompletableFuture/byte-buffer .join() take no timeout and are not threads.
        if not re.search(r"\b(?!complete)\w+\.join\(\s*\)\s*;", stripped):
            continue
        seen[(path.name, stripped)] = seen.get((path.name, stripped), 0) + 1

for key, count in sorted(seen.items()):
    allowed = ALLOWED_BARE_JOINS.get(key)
    if allowed is None:
        failures.append(
            f"{key[0]}: new unbounded join — {key[1]!r}. If the main thread can reach it, give it a "
            f"timeout; if it cannot, add it to ALLOWED_BARE_JOINS with the reason.")
    elif count != allowed[0]:
        failures.append(
            f"{key[0]}: {count} occurrences of {key[1]!r}, expected {allowed[0]} — a new unbounded "
            f"join joined an allowed one and inherited its exemption without being looked at.")

for key, (expected, _why) in sorted(ALLOWED_BARE_JOINS.items()):
    if key not in seen:
        failures.append(
            f"{key[0]}: allowlisted join {key[1]!r} is gone — good, but drop it from "
            f"ALLOWED_BARE_JOINS so the list keeps meaning something.")

# ------------------------------------------- 2. the audio shutdown path the main thread walks
for fname, method in [("HelperGeneric.java", r"static void stop_audio_system\(\)"),
                      ("HelperGeneric.java", r"static void stop_ngc_audio_system\(\)"),
                      ("ConferenceAudioActivity.java", r"protected void onPause\(\)"),
                      ("ConfGroupAudioService.java", r"public static void stop_me\("),
                      ("GroupGroupAudioService.java", r"public static void stop_me\("),
                      ("CallAudioService.java", r"public static void stop_me\("),
                      ("CallingWaitingActivity.java", r"public static void stop_me\(")]:
    text = strip_comments((TRIFA / fname).read_text(encoding="utf-8", errors="replace"))
    src = body(text, method)
    if not src:
        failures.append(f"{fname}: {method} not found — this gate is aimed at a method that moved")
        continue
    if re.search(r"\b\w+\.join\(\s*\)\s*;", src):
        failures.append(
            f"{fname}: {method.strip()} joins a thread with no timeout, and the main thread reaches "
            f"it — this is the shape that froze the app, not a slow path.")

# ------------------------------------------- 3. the wait that could never end must still be bounded
rec = strip_comments((TRIFA / "AudioRecording.java").read_text(encoding="utf-8", errors="replace"))
pred = body(rec, r"static boolean should_keep_waiting_for_engine\(")
if not pred:
    failures.append(
        "AudioRecording.java: should_keep_waiting_for_engine is gone. The wait condition is a pure "
        "predicate so NativeAudioEngineWaitTest can hold it — inline it again and nothing checks "
        "that this wait ends.")
else:
    if "stop_requested" not in pred:
        failures.append(
            "AudioRecording.java: the native-engine wait ignores `stopped` again. close() would then "
            "have no way to end it — the flag it waits on belongs to AudioReceiver, and "
            "AudioReceiver.close() sets that flag to the very value the wait is trying to leave.")
    if "timeout_ms" not in pred:
        failures.append("AudioRecording.java: the native-engine wait has no deadline")
    if "engine_running" not in pred:
        failures.append("AudioRecording.java: the native-engine wait no longer reads the engine flag")

loop = re.search(r"while\s*\(\s*should_keep_waiting_for_engine\((.*?)\)\s*\)", rec, re.S)
if not loop:
    failures.append("AudioRecording.java: the wait loop no longer goes through should_keep_waiting_for_engine")
else:
    args = loop.group(1)
    for needed in ("native_audio_engine_running", "stopped", "NATIVE_AUDIO_ENGINE_START_TIMEOUT_MS"):
        if needed not in args:
            failures.append(
                f"AudioRecording.java: the wait loop no longer passes {needed} — the predicate is "
                f"tested, but not with what the thread actually reads.")
if "semaphore_audioprocessing_02.release();" not in rec.split("StartREC")[0]:
    failures.append(
        "AudioRecording.java: the give-up branch no longer releases semaphore_audioprocessing_02 — "
        "the thread exits and audio stays dead until the process restarts.")

# ------------------------------------------- 4. no full peer walk for a message that mentions nobody
mention = strip_comments((TRIFA / "GroupMentionHelper.java").read_text(encoding="utf-8", errors="replace"))
outgoing = body(mention, r"static List<MentionEntry> collectMentionsForOutgoing\(")
if not outgoing:
    failures.append("GroupMentionHelper.java: collectMentionsForOutgoing not found")
else:
    guard = outgoing.find("indexOf('@')")
    walk = outgoing.find("collect_group_members_for_display")
    if guard < 0 or (walk >= 0 and guard > walk):
        failures.append(
            "GroupMentionHelper.java: collectMentionsForOutgoing enumerates every peer before it "
            "checks for an '@'. That walk carries JNI and SQLCipher and runs on the main thread for "
            "every outgoing group message, nearly all of which mention nobody.")

# ------------------------------------------- 5. registration must not poll the database on a tick
cap = strip_comments((TRIFA / "KhandaqPushCapability.java").read_text(encoding="utf-8", errors="replace"))
if re.search(r"Thread\.sleep\(\s*250\s*\)", cap):
    failures.append(
        "KhandaqPushCapability.java: a 250ms poll is back. get_g_opts is two SELECTs, so that is "
        "eight a second for thirty seconds per contact, through the one shared JDBC connection "
        "behind a FAIR lock (OrmaDatabase:31, :37) — fair means the main thread queues behind it.")
if "private static String awaitNonce(" not in cap:
    failures.append("KhandaqPushCapability.java: awaitNonce is gone")
else:
    for caller in ("challenge", "register"):
        src = body(cap, rf"private static \S+ {caller}\(")
        if src and "awaitNonce(" not in src:
            failures.append(f"KhandaqPushCapability.java: {caller}() no longer waits through awaitNonce")

pool = strip_comments((TRIFA / "HelperFriend.java").read_text(encoding="utf-8", errors="replace"))
# The declaration existing proves nothing — what matters is that the blocking call goes through it.
# Walk back from each issueFor() to whichever came last, the pool or a raw thread.
for m in re.finditer(r"KhandaqPushCapability\.issueFor\(", pool):
    before = pool[:m.start()]
    via_pool = before.rfind("PUSHCAP_REGISTRATION_POOL.execute(")
    via_raw = max(before.rfind("new Thread("), before.rfind("new Thread()"))
    if via_raw > via_pool:
        failures.append(
            "HelperFriend.java: capability registration is back on a raw thread per contact. "
            "issueFor() blocks for up to ~46s, and after an FCM token change every contact needs "
            "one at once — all of them reading the one shared database connection the UI needs too.")
        break
else:
    if "PUSHCAP_REGISTRATION_POOL.execute(" not in pool:
        failures.append("HelperFriend.java: nothing dispatches capability registration any more")

# ------------------------------------------- 6. no unbounded spin on the engine-starting flag
#
# The same shape one level out: three places wait for `audio_engine_starting` to clear, and only
# AudioRoundtripActivity ever bounded its wait. The other two sit on the toxav callback thread, and
# an engine that never came up wedged them for as long as it stayed down — which, before the
# recording thread's own wait was bounded, was forever.
spins = 0
for path in sorted(TRIFA.glob("*.java")):
    text = strip_comments(path.read_text(encoding="utf-8", errors="replace"))
    for m in re.finditer(r"while\s*\((.*?)\)\s*\n", text, re.S):
        cond = m.group(1)
        if "audio_engine_starting" not in cond:
            continue
        spins += 1
        if "<" not in cond:
            lineno = text[:m.start()].count("\n") + 1
            failures.append(
                f"{path.name}:~{lineno}: waits for audio_engine_starting with no deadline. The flag "
                f"is cleared by the recording thread, so a thread that never gets there waits for "
                f"good — bound it by AudioRecording.NATIVE_AUDIO_ENGINE_START_TIMEOUT_MS, which is "
                f"the deadline that thread gives itself.")
if spins == 0:
    failures.append("nothing waits on audio_engine_starting any more — re-aim or drop this check")

if failures:
    print("FAIL — the main thread can wait on something that may never happen:\n")
    for f in failures:
        print(f"  - {f}")
    sys.exit(1)

print(f"ok — {len(seen)} bare joins accounted for, {spins} engine waits bounded, audio shutdown "
      f"bounded, mention walk guarded, push registration off the database tick")
