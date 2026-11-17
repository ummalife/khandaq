#!/usr/bin/env python3
"""Every copy of toxcore must send group packets that fit the IPv6 minimum MTU and still take the old ones.

KHANDAQ (0.2.57, gc IPv6 MTU). A full NGC chunk was 1372 bytes (MAX_GC_PACKET_CHUNK_SIZE), sent wrapped in
82 more: a 1454 byte UDP payload, a 1502 byte IPv6 packet, over a 1500 byte MTU. On a direct IPv6 path the
kernel split it into IP fragments, which were lost: an iPhone got none of 40260 datagrams of 1440 bytes or
more, while small packets passed. The group looked connected, and no group file chunk arrived.

MAX_GC_PACKET_CHUNK_SIZE is now what we send, 1150: a group packet is then at most 1232 bytes of UDP
payload, 1280 bytes as an IPv6 packet, the IPv6 minimum MTU. The same constant was the limit of what we
accept, and a peer before this change (0.2.56 and older, the Community bot) still sends 1372 byte chunks,
so lowering it alone would drop all of them. MAX_GC_PACKET_INCOMING_CHUNK_SIZE (1372) is that limit now,
the split upstream c-toxcore keeps: the UDP, TCP and TCP OOB length checks and the fragment reassembly use
it. The sender's size bounds every packet we build, so every client has to carry the change:
- desktop: buildscripts/toxcore/toxcore/group_{common.h,chats.c,connection.c} and both amalgamations;
- iOS: local_pod_repo/toxcore (group_common.h, group_chats.m, group_connection.m);
- Android: patches/apply_khandaq_gc_ipv6_mtu.py, run by circle_scripts/deps.sh on the cloned toxcore for
  each of the 4 ABIs.

This check holds the two sizes and the receive side in every copy, keeps the toxcore test that runs them on
two local toxes (auto_tests/group_ipv6_mtu_test.c) in the desktop build, and proves that the Android script
turns the desktop files without the change into exactly the desktop files.

Android and iOS send the live media of a group (NGC audio and video) lossy up to a threshold of their own and
lossless above. A lossy packet cannot be split into chunks, so toxcore refuses one over MAX_GC_PACKET_CHUNK_SIZE,
and a frame between the two sizes would be lost without a word. The check also holds each app's threshold equal
to the size every toxcore copy sends, and each send that may go lossy on that threshold. The desktop sends no
lossy group packet.

    scripts/check-gc-ipv6-mtu.py
    scripts/check-gc-ipv6-mtu.py --self-test    # every check must catch the mutant made for it (CI)
"""
from __future__ import annotations

import re
import subprocess
import sys
import tempfile
from collections.abc import Callable
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

DESKTOP_DIR = "khandaq-desktop/buildscripts/toxcore/toxcore"
IOS_DIR = "khandaq-ios/local_pod_repo/toxcore/toxcore/toxcore"
AMALGAMATION_DIR = "khandaq-desktop/buildscripts/toxcore/amalgamation"
DESKTOP_FILES = ("group_common.h", "group_chats.c", "group_connection.c")
COPIES = (
    ("desktop", tuple(f"{DESKTOP_DIR}/{name}" for name in DESKTOP_FILES)),
    ("desktop amalgamation", (f"{AMALGAMATION_DIR}/toxcore_amalgamation.c",)),
    ("desktop amalgamation (no toxav)", (f"{AMALGAMATION_DIR}/toxcore_amalgamation_no_toxav.c",)),
    ("iOS", (f"{IOS_DIR}/group_common.h", f"{IOS_DIR}/group_chats.m", f"{IOS_DIR}/group_connection.m")),
)
ANDROID_SCRIPT = "khandaq-android-trifa/patches/apply_khandaq_gc_ipv6_mtu.py"
ANDROID_DEPS = "khandaq-android-trifa/circle_scripts/deps.sh"
ANDROID_ABIS = 4
TEST = "khandaq-desktop/buildscripts/toxcore/auto_tests/group_ipv6_mtu_test.c"
TEST_LIST = "khandaq-desktop/buildscripts/toxcore/auto_tests/CMakeLists.txt"
TEST_ENTRY = "auto_test(group_ipv6_mtu)"

# A full lossless chunk on the wire: net packet type (1), sender key (32), nonce (24), group packet type (1),
# message id (8), MAC (16); a full chunk has no padding. The IPv6 minimum MTU less the IPv6 and UDP headers.
WRAPPED_OVERHEAD = 1 + 32 + 24 + 1 + 8 + 16
MAX_DATAGRAM = 1280 - 40 - 8
# What a peer before this change sends.
OLD_CHUNK = 1372

SENT_DEFINE = re.compile(r"^#define MAX_GC_PACKET_CHUNK_SIZE (\d+)\b", re.MULTILINE)
INCOMING_DEFINE = re.compile(r"^#define MAX_GC_PACKET_INCOMING_CHUNK_SIZE (\d+)\b", re.MULTILINE)
MTU_ASSERT = ("static_assert(1 + ENC_PUBLIC_KEY_SIZE + GC_MIN_LOSSLESS_PAYLOAD_SIZE + MAX_GC_PACKET_CHUNK_SIZE"
              " <= 1280 - 40 - 8,\n")
# The upper bound of the length check in handle_gc_tcp_packet, handle_gc_tcp_oob_packet and
# handle_gc_udp_packet; nothing else compares a received length with a chunk size plus these.
RECEIVE_BOUNDS = (
    ("TCP", "MIN_TCP_PACKET_SIZE + ENC_PUBLIC_KEY_SIZE"),
    ("TCP OOB", "CRYPTO_MAC_SIZE + CRYPTO_NONCE_SIZE"),
    ("UDP", "MIN_UDP_PACKET_SIZE + ENC_PUBLIC_KEY_SIZE"),
)
REASSEMBLY_ASSERT = "assert(entry->data_length <= MAX_GC_PACKET_{}CHUNK_SIZE);"

ANDROID_JAVA = "khandaq-android-trifa/android-refimpl-app/app/src/main/java/com/zoffcc/applications/trifa"
IOS_GROUPS = "khandaq-ios/local_pod_repo/objcTox/Classes/Private/Manager/Groups"
# Each app's threshold: the longest live media packet it sends lossy.
APP_THRESHOLDS = (
    ("Android", f"{ANDROID_JAVA}/ToxVars.java",
     re.compile(r"^    public static final int MAX_GC_LOSSY_PACKET_SIZE = (\d+);$", re.MULTILINE)),
    ("iOS", f"{IOS_GROUPS}/OCTNgcLiveMedia.h",
     re.compile(r"^static const size_t kOCTNgcMaxLossyPacketLength = (\d+);$", re.MULTILINE)),
)
# Each send that may go lossy, and the helper Android's sends ask: the decision it makes by the threshold and how
# many times, and what it decided by before (toxcore's old size).
IOS_DECISION = "const BOOL lossless = packetLength > kOCTNgcMaxLossyPacketLength;"
IOS_BEFORE = "packetLength >= TOX_MAX_CUSTOM_PACKET_SIZE"
APP_DECISIONS = (
    ("Android", f"{ANDROID_JAVA}/HelperGroup.java", "return length <= ToxVars.MAX_GC_LOSSY_PACKET_SIZE;", 1, None),
    ("Android", f"{ANDROID_JAVA}/GroupMessageListActivity.java",
     "if (HelperGroup.is_ngc_media_packet_lossy(data_length))", 2, "data_length < MAX_GC_PACKET_CHUNK_SIZE"),
    ("iOS", f"{IOS_GROUPS}/OCTNgcGroupLiveAudio.m", IOS_DECISION, 1, IOS_BEFORE),
    ("iOS", f"{IOS_GROUPS}/OCTNgcGroupLiveVideo.m", IOS_DECISION, 1, IOS_BEFORE),
)
# Android's NGC audio encoder writes into a buffer of ToxVars.MAX_GC_PACKET_CHUNK_SIZE - 10 bytes: the JNI hands it
# to toxav_ngc_audio_encode without its length, and toxav writes up to TOX_MAX_CUSTOM_PACKET_SIZE - 1 - 10 bytes. That
# constant is toxcore's old chunk size, not what is sent lossy, and must not follow toxcore's new one down.
ANDROID_AUDIO_CHUNK = re.compile(r"^    public static final int MAX_GC_PACKET_CHUNK_SIZE = (\d+);$", re.MULTILINE)
ANDROID_AUDIO_BUFFER = "final int max_encoded_bytes = (MAX_GC_PACKET_CHUNK_SIZE - 10);"
NGC_AUDIO_MAX_ENCODED = 1373 - 1 - 10


def check_copy(label: str, text: str) -> tuple[list[str], tuple[int, int] | None]:
    problems = []
    sizes = None
    sent = SENT_DEFINE.findall(text)
    incoming = INCOMING_DEFINE.findall(text)
    if len(sent) != 1 or len(incoming) != 1:
        problems.append(f"{label}: expected one MAX_GC_PACKET_CHUNK_SIZE and one MAX_GC_PACKET_INCOMING_CHUNK_SIZE, "
                        f"found {len(sent)} and {len(incoming)}")
    else:
        sizes = (int(sent[0]), int(incoming[0]))
        if sizes[0] + WRAPPED_OVERHEAD > MAX_DATAGRAM:
            problems.append(f"{label}: a full chunk of {sizes[0]} goes out as {sizes[0] + WRAPPED_OVERHEAD} bytes of "
                            f"UDP payload, over the {MAX_DATAGRAM} the IPv6 minimum MTU leaves")
        if sizes[1] < OLD_CHUNK:
            problems.append(f"{label}: MAX_GC_PACKET_INCOMING_CHUNK_SIZE {sizes[1]} would drop the {OLD_CHUNK} byte "
                            "chunks of a peer before this change")
    if text.count(MTU_ASSERT) != 1:
        problems.append(f"{label}: the static_assert that a full chunk fits the IPv6 minimum MTU is missing")

    for what, terms in RECEIVE_BOUNDS:
        incoming_bound = f"length > MAX_GC_PACKET_INCOMING_CHUNK_SIZE + {terms}) {{"
        if text.count(incoming_bound) != 1:
            problems.append(f"{label}: the {what} receive check must accept up to MAX_GC_PACKET_INCOMING_CHUNK_SIZE "
                            f"(found {text.count(incoming_bound)} such check(s))")
    if "length > MAX_GC_PACKET_CHUNK_SIZE + " in text:
        problems.append(f"{label}: a receive check still bounds a group packet by MAX_GC_PACKET_CHUNK_SIZE, so the "
                        f"{OLD_CHUNK} byte chunks of a peer before this change would be dropped")

    if text.count(REASSEMBLY_ASSERT.format("INCOMING_")) != 1 or REASSEMBLY_ASSERT.format("") in text:
        problems.append(f"{label}: the fragment reassembly must allow fragments of up to "
                        "MAX_GC_PACKET_INCOMING_CHUNK_SIZE")
    return problems, sizes


def without_change(name: str, text: str) -> str:
    """The desktop file as it was before this change."""
    if name == "group_common.h":
        return re.sub(r"/\* Max size of a packet chunk we send\..*?\n#define MAX_GC_PACKET_INCOMING_CHUNK_SIZE \d+\n",
                      "/* Max size of a packet chunk. Packets larger than this must be split up. */\n"
                      "#define MAX_GC_PACKET_CHUNK_SIZE 1372 // 500\n", text, count=1, flags=re.DOTALL)
    if name == "group_chats.c":
        text = re.sub(r"\n// KHANDAQ \(gc IPv6 MTU\): we accept every chunk.*?"
                      r"\"a full group packet chunk we send must fit the IPv6 minimum MTU\"\);\n", "", text,
                      count=1, flags=re.DOTALL)
        text = re.sub(r"/\*\* @brief (Returns the amount of empty padding a packet of designated length should "
                      r"have\.)\n \*\n \* KHANDAQ \(gc IPv6 MTU\).*? \*/\n", r"/** \1 */\n", text, count=1,
                      flags=re.DOTALL)
        text = text.replace("    // KHANDAQ (gc IPv6 MTU): a peer before this change sends chunks of up to "
                            "MAX_GC_PACKET_INCOMING_CHUNK_SIZE.\n", "")
        text = re.sub(r"(MIN_TCP_PACKET_SIZE|GC_MIN_HS_PACKET_PAYLOAD_SIZE|MIN_UDP_PACKET_SIZE),\n +"
                      r"MAX_GC_PACKET_INCOMING_CHUNK_SIZE \+", r"\1, MAX_GC_PACKET_CHUNK_SIZE +", text)
        return text.replace("length > MAX_GC_PACKET_INCOMING_CHUNK_SIZE + ", "length > MAX_GC_PACKET_CHUNK_SIZE + ")
    text = re.sub(r"        // KHANDAQ \(gc IPv6 MTU\)[^\n]*\n", "", text, count=1)
    return text.replace(REASSEMBLY_ASSERT.format("INCOMING_"), REASSEMBLY_ASSERT.format(""))


def check_test() -> list[str]:
    problems = []
    if not (ROOT / TEST).is_file():
        problems.append(f"toxcore test: {TEST} is missing")
    listed = (ROOT / TEST_LIST).read_text(encoding="utf-8", errors="replace").splitlines()
    if TEST_ENTRY not in (line.strip() for line in listed):
        problems.append(f"toxcore test: {TEST_LIST} does not build and run {TEST_ENTRY}")
    return problems


def check_android(desktop: dict[str, str]) -> list[str]:
    problems = []
    deps = (ROOT / ANDROID_DEPS).read_text(encoding="utf-8", errors="replace")
    wired = deps.count("python3 /root/work/patches/apply_khandaq_gc_ipv6_mtu.py toxcore || exit 1")
    if wired != ANDROID_ABIS:
        problems.append(f"Android: {ANDROID_DEPS} runs the gc IPv6 MTU patch at {wired} site(s), "
                        f"expected one per ABI ({ANDROID_ABIS})")

    script = ROOT / ANDROID_SCRIPT
    if not script.is_file():
        return problems + [f"Android: {ANDROID_SCRIPT} is missing"]
    with tempfile.TemporaryDirectory() as tmp:
        tree = Path(tmp) / "toxcore"
        tree.mkdir()
        for name, text in desktop.items():
            reverted = without_change(name, text)
            if "KHANDAQ (gc IPv6 MTU)" in reverted:
                return problems + [f"Android: cannot take the change out of the desktop {name} to compare"]
            (tree / name).write_text(reverted, encoding="utf-8")
        result = subprocess.run([sys.executable, "-I", str(script), str(tree)],
                                capture_output=True, text=True, check=False)
        if result.returncode != 0:
            return problems + [f"Android: {ANDROID_SCRIPT} failed on the desktop files without the change: "
                               f"{result.stderr.strip()}"]
        for name, text in desktop.items():
            if (tree / name).read_text(encoding="utf-8") != text:
                problems.append(f"Android: {ANDROID_SCRIPT} does not produce the desktop {name}, so Android would "
                                "send or accept group packets of other sizes")
    return problems


def check_apps(read: Callable[[str], str | None], all_sizes: dict[str, tuple[int, int]]) -> list[str]:
    """Each app sends live media lossy up to the size every toxcore copy sends in one piece, and no further."""
    problems = []
    for app, rel, pattern in APP_THRESHOLDS:
        text = read(rel)
        if text is None:
            problems.append(f"{app}: {rel} is missing")
            continue
        found = pattern.findall(text)
        if len(found) != 1:
            problems.append(f"{app}: expected one lossy threshold in {rel}, found {len(found)}")
            continue
        threshold = int(found[0])
        for label, (sent, _incoming) in all_sizes.items():
            if threshold != sent:
                problems.append(f"{app}: live media goes lossy up to {threshold} bytes, but the {label} toxcore sends "
                                f"a group packet in one piece up to {sent} (MAX_GC_PACKET_CHUNK_SIZE): "
                                + ("a frame in between is refused and lost" if threshold > sent
                                   else "a frame in between goes lossless"))

    for app, rel, decision, times, before in APP_DECISIONS:
        text = read(rel)
        if text is None:
            problems.append(f"{app}: {rel} is missing")
            continue
        if text.count(decision) != times:
            problems.append(f"{app}: {rel} must decide by the lossy threshold {times} time(s), "
                            f"`{decision}`, found {text.count(decision)}")
        if before is not None and before in text:
            problems.append(f"{app}: {rel} still decides by toxcore's old size, `{before}`")

    toxvars = read(f"{ANDROID_JAVA}/ToxVars.java") or ""
    activity = read(f"{ANDROID_JAVA}/GroupMessageListActivity.java") or ""
    chunk = ANDROID_AUDIO_CHUNK.findall(toxvars)
    if len(chunk) != 1 or int(chunk[0]) - 10 < NGC_AUDIO_MAX_ENCODED or activity.count(ANDROID_AUDIO_BUFFER) != 1:
        problems.append(f"Android: the NGC audio encoder's buffer, `{ANDROID_AUDIO_BUFFER}`, must hold the "
                        f"{NGC_AUDIO_MAX_ENCODED} bytes toxav writes into it (ToxVars.MAX_GC_PACKET_CHUNK_SIZE stays "
                        "toxcore's old chunk size)")
    return problems


def read_from(root: Path) -> Callable[[str], str | None]:
    def read(rel: str) -> str | None:
        path = root / rel
        return path.read_text(encoding="utf-8", errors="replace") if path.is_file() else None
    return read


def check_copies(read: Callable[[str], str | None]) -> tuple[list[str], dict[str, tuple[int, int]], bool]:
    """The problems of the toxcore copies, the sizes (sent, accepted) of each, and whether the desktop one is good."""
    problems: list[str] = []
    all_sizes = {}
    desktop_ok = False
    for label, rels in COPIES:
        texts = [read(rel) for rel in rels]
        missing = [rel for rel, text in zip(rels, texts) if text is None]
        if missing:
            problems += [f"{label}: {rel} is missing" for rel in missing]
            continue
        found, sizes = check_copy(label, "".join(texts))
        problems += found
        if sizes is not None:
            all_sizes[label] = sizes
        if label == "desktop":
            desktop_ok = not found

    if len(set(all_sizes.values())) > 1:
        listed = ", ".join(f"{label} {sent}/{incoming}" for label, (sent, incoming) in all_sizes.items())
        problems.append(f"the copies send and accept chunks of different sizes (sent/accepted): {listed}")
    return problems, all_sizes, desktop_ok


def self_test() -> int:
    """Each check must catch the mutant made for it, and the files as they are must pass."""
    read = read_from(ROOT)
    problems, all_sizes, _desktop_ok = check_copies(read)
    problems += check_apps(read, all_sizes)
    if problems:
        print("FAIL: check-gc-ipv6-mtu.py self-test: the files as they are do not pass:", file=sys.stderr)
        for problem in problems:
            print(f"  - {problem}", file=sys.stderr)
        return 1

    def mutant(rel: str, old: str, new: str, count: int = 1) -> Callable[[str], str | None]:
        original = read(rel)
        if original is None or old not in original:
            raise AssertionError(f"self-test: `{old}` is not in {rel}")
        changed = original.replace(old, new, count)
        return lambda path: changed if path == rel else read(path)

    android_threshold = "MAX_GC_LOSSY_PACKET_SIZE = 1150;"
    ios_threshold = "kOCTNgcMaxLossyPacketLength = 1150;"
    activity = f"{ANDROID_JAVA}/GroupMessageListActivity.java"
    apps = (
        ("Android goes lossy up to 1151", mutant(f"{ANDROID_JAVA}/ToxVars.java", android_threshold,
                                                 android_threshold.replace("1150", "1151")), all_sizes, "Android"),
        ("iOS goes lossy up to 1372", mutant(f"{IOS_GROUPS}/OCTNgcLiveMedia.h", ios_threshold,
                                             ios_threshold.replace("1150", "1372")), all_sizes, "iOS"),
        ("one toxcore copy sends 1100", read, {**all_sizes, "iOS": (1100, 1372)}, "the iOS toxcore"),
        ("Android's video send decides by 1372 again",
         mutant(activity, "if (HelperGroup.is_ngc_media_packet_lossy(data_length))",
                "if (data_length < MAX_GC_PACKET_CHUNK_SIZE)"), all_sizes, "old size"),
        ("Android's helper sends 1150 lossless",
         mutant(f"{ANDROID_JAVA}/HelperGroup.java", "length <= ToxVars.MAX_GC_LOSSY_PACKET_SIZE",
                "length < ToxVars.MAX_GC_LOSSY_PACKET_SIZE"), all_sizes, "HelperGroup.java"),
        ("iOS video decides by TOX_MAX_CUSTOM_PACKET_SIZE again",
         mutant(f"{IOS_GROUPS}/OCTNgcGroupLiveVideo.m", IOS_DECISION, f"const BOOL lossless = {IOS_BEFORE};"),
         all_sizes, "OCTNgcGroupLiveVideo.m"),
        ("Android's audio buffer follows toxcore down to 1150",
         mutant(f"{ANDROID_JAVA}/ToxVars.java", "MAX_GC_PACKET_CHUNK_SIZE = 1372;", "MAX_GC_PACKET_CHUNK_SIZE = 1150;"),
         all_sizes, "audio encoder"),
    )
    failed = []
    for name, mutant_read, sizes, expected in apps:
        found = check_apps(mutant_read, sizes)
        if not any(expected in problem for problem in found):
            failed.append(f"{name}: not caught (got {found or 'no problem'})")

    desktop = "".join(read(rel) or "" for rel in COPIES[0][1])
    copies = (
        ("toxcore sends chunks of 1372 again", desktop.replace("#define MAX_GC_PACKET_CHUNK_SIZE 1150",
                                                               "#define MAX_GC_PACKET_CHUNK_SIZE 1372"), "over the"),
        ("toxcore drops the chunks of an older peer over UDP",
         desktop.replace("length > MAX_GC_PACKET_INCOMING_CHUNK_SIZE + MIN_UDP_PACKET_SIZE + ENC_PUBLIC_KEY_SIZE) {",
                         "length > MAX_GC_PACKET_CHUNK_SIZE + MIN_UDP_PACKET_SIZE + ENC_PUBLIC_KEY_SIZE) {"), "UDP"),
    )
    for name, text, expected in copies:
        found, _sizes = check_copy("desktop", text)
        if text == desktop or not any(expected in problem for problem in found):
            failed.append(f"{name}: not caught (got {found or 'no problem'})")

    if failed:
        print("FAIL: check-gc-ipv6-mtu.py self-test:", file=sys.stderr)
        for line in failed:
            print(f"  - {line}", file=sys.stderr)
        return 1
    print(f"ok: check-gc-ipv6-mtu.py self-test, {len(apps) + len(copies)} mutants caught")
    return 0


def main() -> int:
    read = read_from(ROOT)
    problems, all_sizes, desktop_ok = check_copies(read)
    problems += check_test()

    if desktop_ok:
        desktop = {name: (ROOT / DESKTOP_DIR / name).read_text(encoding="utf-8", errors="replace")
                   for name in DESKTOP_FILES}
        problems += check_android(desktop)

    problems += check_apps(read, all_sizes)

    if problems:
        print("A copy of toxcore sends group packets an IPv6 path splits, drops the chunks of an older peer, or an app "
              "sends live media lossy that toxcore refuses:")
        for problem in problems:
            print(f"  - {problem}")
        return 1
    sent, incoming = all_sizes["desktop"]
    print(f"gc IPv6 MTU: {len(COPIES)} toxcore copies send chunks of {sent} ({sent + WRAPPED_OVERHEAD} bytes of UDP "
          f"payload) and accept {incoming}, as does the Android build ({ANDROID_ABIS} ABIs); Android and iOS send "
          f"live media lossy up to {sent}; the toxcore test is in")
    return 0


if __name__ == "__main__":
    if sys.argv[1:] == ["--self-test"]:
        sys.exit(self_test())
    if sys.argv[1:]:
        print("usage: check-gc-ipv6-mtu.py [--self-test]", file=sys.stderr)
        sys.exit(2)
    sys.exit(main())
