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

    scripts/check-gc-ipv6-mtu.py
"""
from __future__ import annotations

import re
import subprocess
import sys
import tempfile
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


def main() -> int:
    problems: list[str] = []
    all_sizes = {}
    desktop_ok = False
    for label, rels in COPIES:
        missing = [rel for rel in rels if not (ROOT / rel).is_file()]
        if missing:
            problems += [f"{label}: {rel} is missing" for rel in missing]
            continue
        text = "".join((ROOT / rel).read_text(encoding="utf-8", errors="replace") for rel in rels)
        found, sizes = check_copy(label, text)
        problems += found
        if sizes is not None:
            all_sizes[label] = sizes
        if label == "desktop":
            desktop_ok = not found

    if len(set(all_sizes.values())) > 1:
        listed = ", ".join(f"{label} {sent}/{incoming}" for label, (sent, incoming) in all_sizes.items())
        problems.append(f"the copies send and accept chunks of different sizes (sent/accepted): {listed}")

    problems += check_test()

    if desktop_ok:
        desktop = {name: (ROOT / DESKTOP_DIR / name).read_text(encoding="utf-8", errors="replace")
                   for name in DESKTOP_FILES}
        problems += check_android(desktop)

    if problems:
        print("A copy of toxcore sends group packets an IPv6 path splits, or drops the chunks of an older peer:")
        for problem in problems:
            print(f"  - {problem}")
        return 1
    sent, incoming = all_sizes["desktop"]
    print(f"gc IPv6 MTU: {len(COPIES)} toxcore copies send chunks of {sent} ({sent + WRAPPED_OVERHEAD} bytes of UDP "
          f"payload) and accept {incoming}, as does the Android build ({ANDROID_ABIS} ABIs); the toxcore test is in")
    return 0


if __name__ == "__main__":
    sys.exit(main())
