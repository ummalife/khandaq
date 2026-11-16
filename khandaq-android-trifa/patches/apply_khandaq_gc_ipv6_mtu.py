#!/usr/bin/env python3
# KHANDAQ (gc IPv6 MTU): every group packet we send fits the IPv6 minimum MTU; what a peer before this change
# sends is still accepted.
#
# A full NGC chunk was 1372 bytes, sent wrapped in 82 more (packet type, sender key, nonce, group packet type,
# message id, MAC): a 1454 byte UDP payload, a 1502 byte IPv6 packet, over a 1500 byte MTU. On a direct IPv6 path
# the kernel split it into IP fragments, which were lost (an iPhone got none of 40260 datagrams of 1440 bytes or
# more), while small packets passed: the group looked connected and no group file chunk arrived.
#
# MAX_GC_PACKET_CHUNK_SIZE is now what we send, 1150: every group packet is then at most 1232 bytes of UDP payload,
# 1280 bytes as an IPv6 packet, the IPv6 minimum MTU. It was also the limit of what we accept, so lowering it alone
# would drop every packet of a peer before this change (0.2.56 and older, the Community bot).
# MAX_GC_PACKET_INCOMING_CHUNK_SIZE (1372) is that limit now, the split upstream c-toxcore keeps (500 sent, 1372
# accepted): the UDP, TCP and TCP OOB length checks and the fragment reassembly use it. Splitting a packet into
# fragments, wrapping a packet, the sync response buffer and the cap of a lossy group packet use the size we send.
#
# The desktop copy of toxcore (khandaq-desktop/buildscripts/toxcore, both amalgamations) and the iOS copy
# (khandaq-ios/local_pod_repo/toxcore) carry the same change; scripts/check-gc-ipv6-mtu.py holds them together,
# and khandaq-desktop/buildscripts/toxcore/auto_tests/group_ipv6_mtu_test.c runs it on two local toxes.
#
# Context-independent substring replace (robust to line drift), same convention as the other
# apply_khandaq_*.py scripts. A target that is missing or not unique fails the build, so an .so
# cannot ship without the change. Run during the native build against a freshly-cloned toxcore tree:
#   python3 apply_khandaq_gc_ipv6_mtu.py toxcore
import os
import sys

MARKER = "KHANDAQ (gc IPv6 MTU)"

# --- group_common.h: the size we send and the size we accept ---
ORIG_CHUNK = ("/* Max size of a packet chunk. Packets larger than this must be split up. */\n"
              "#define MAX_GC_PACKET_CHUNK_SIZE 1372 // 500\n")

REPL_CHUNK = ("/* Max size of a packet chunk we send. Packets larger than this must be split up.\n"
              " *\n"
              " * KHANDAQ (gc IPv6 MTU): a full chunk goes out wrapped in 82 more bytes (packet type, sender key, nonce,\n"
              " * group packet type, message id, MAC). At 1372 that was a 1454 byte UDP payload, a 1502 byte IPv6 packet:\n"
              " * over a 1500 byte MTU, so a direct IPv6 path split it into IP fragments, which were lost, and no group file\n"
              " * chunk arrived while small packets passed. At 1150 every group packet we send is at most 1232 bytes of UDP\n"
              " * payload, 1280 bytes with the IPv6 and UDP headers: the IPv6 minimum MTU. A lossy group packet cannot be\n"
              " * split, so this is also the largest lossy group packet we send.\n"
              " */\n"
              "#define MAX_GC_PACKET_CHUNK_SIZE 1150 // 500\n"
              "\n"
              "/* KHANDAQ (gc IPv6 MTU): max size of a packet chunk we accept. A peer before this change (0.2.56 and older,\n"
              " * the Community bot) sends chunks of up to 1372, so every incoming length check and assert uses this; only\n"
              " * what we build and send uses MAX_GC_PACKET_CHUNK_SIZE. Upstream c-toxcore keeps the same split (500 sent,\n"
              " * 1372 accepted).\n"
              " */\n"
              "#define MAX_GC_PACKET_INCOMING_CHUNK_SIZE 1372\n")

# --- group_chats.c: what the two sizes must satisfy ---
ORIG_ASSERTS = ("static_assert(MAX_GC_PACKET_SIZE <= UINT16_MAX - MAX_GC_PACKET_CHUNK_SIZE,\n"
                "              \"MAX_GC_PACKET_SIZE must be <= UINT16_MAX - MAX_GC_PACKET_CHUNK_SIZE\");\n")

REPL_ASSERTS = (ORIG_ASSERTS
                + "\n"
                "// KHANDAQ (gc IPv6 MTU): we accept every chunk we send, a reassembled packet stays within 16 bits, and a\n"
                "// full chunk we send, wrapped (packet type, sender key, nonce, group packet type, message id, MAC; a full\n"
                "// chunk has no padding), fits the IPv6 minimum MTU of 1280 bytes less the IPv6 (40) and UDP (8) headers.\n"
                "static_assert(MAX_GC_PACKET_INCOMING_CHUNK_SIZE >= MAX_GC_PACKET_CHUNK_SIZE,\n"
                "              \"MAX_GC_PACKET_INCOMING_CHUNK_SIZE must be >= MAX_GC_PACKET_CHUNK_SIZE\");\n"
                "\n"
                "static_assert(MAX_GC_PACKET_INCOMING_CHUNK_SIZE < MAX_GC_PACKET_SIZE,\n"
                "              \"MAX_GC_PACKET_INCOMING_CHUNK_SIZE must be < MAX_GC_PACKET_SIZE\");\n"
                "\n"
                "static_assert(MAX_GC_PACKET_SIZE <= UINT16_MAX - MAX_GC_PACKET_INCOMING_CHUNK_SIZE,\n"
                "              \"MAX_GC_PACKET_SIZE must be <= UINT16_MAX - MAX_GC_PACKET_INCOMING_CHUNK_SIZE\");\n"
                "\n"
                "static_assert(1 + ENC_PUBLIC_KEY_SIZE + GC_MIN_LOSSLESS_PAYLOAD_SIZE + MAX_GC_PACKET_CHUNK_SIZE <= 1280 - 40 - 8,\n"
                "              \"a full group packet chunk we send must fit the IPv6 minimum MTU\");\n")

# --- group_chats.c: the padding is the sender's own business ---
ORIG_PADDING = ("/** Returns the amount of empty padding a packet of designated length should have. */\n"
                "static uint16_t group_packet_padding_length(uint16_t length)\n")

REPL_PADDING = ("/** @brief Returns the amount of empty padding a packet of designated length should have.\n"
                " *\n"
                " * KHANDAQ (gc IPv6 MTU): only the sender uses it. The receiver drops the zero bytes in front of the group\n"
                " * packet type (group_packet_unwrap), so a packet padded up to another chunk size, as a peer before this\n"
                " * change pads it, reads the same.\n"
                " */\n"
                "static uint16_t group_packet_padding_length(uint16_t length)\n")


def _incoming_check(min_name, max_terms, what):
    """The two length checks of a group packet handler, as upstream has them and with the incoming size."""
    orig_max = "MAX_GC_PACKET_CHUNK_SIZE + " + max_terms
    repl_max = "MAX_GC_PACKET_INCOMING_CHUNK_SIZE + " + max_terms
    log = ("        LOGGER_WARNING(m->log, \"Got " + what + " with invalid length: %u (expected %u to %u)\", length,\n")
    orig = ("    if (length <= " + min_name + ") {\n"
            + log
            + "                       " + min_name + ", " + orig_max + ");\n"
            "        return -1;\n"
            "    }\n"
            "\n"
            "    if (length > " + orig_max + ") {\n"
            + log
            + "                       " + min_name + ", " + orig_max + ");\n"
            "        return -1;\n"
            "    }\n")
    repl = ("    // KHANDAQ (gc IPv6 MTU): a peer before this change sends chunks of up to MAX_GC_PACKET_INCOMING_CHUNK_SIZE.\n"
            "    if (length <= " + min_name + ") {\n"
            + log
            + "                       " + min_name + ",\n"
            "                       " + repl_max + ");\n"
            "        return -1;\n"
            "    }\n"
            "\n"
            "    if (length > " + repl_max + ") {\n"
            + log
            + "                       " + min_name + ",\n"
            "                       " + repl_max + ");\n"
            "        return -1;\n"
            "    }\n")
    return orig, repl


# --- group_chats.c: the length checks of a group packet over TCP, TCP OOB (handshake) and UDP ---
ORIG_TCP, REPL_TCP = _incoming_check("MIN_TCP_PACKET_SIZE", "MIN_TCP_PACKET_SIZE + ENC_PUBLIC_KEY_SIZE",
                                     "tcp packet")
ORIG_TCP_OOB, REPL_TCP_OOB = _incoming_check("GC_MIN_HS_PACKET_PAYLOAD_SIZE", "CRYPTO_MAC_SIZE + CRYPTO_NONCE_SIZE",
                                             "tcp oob packet")
ORIG_UDP, REPL_UDP = _incoming_check("MIN_UDP_PACKET_SIZE", "MIN_UDP_PACKET_SIZE + ENC_PUBLIC_KEY_SIZE",
                                     "UDP packet")

# --- group_connection.c: a received fragment ---
ORIG_REASSEMBLE = "        assert(entry->data_length <= MAX_GC_PACKET_CHUNK_SIZE);\n"

REPL_REASSEMBLE = ("        // KHANDAQ (gc IPv6 MTU): a fragment from a peer before this change holds up to 1372 bytes.\n"
                   "        assert(entry->data_length <= MAX_GC_PACKET_INCOMING_CHUNK_SIZE);\n")

PATCHES = (
    ("group_common.h", ((ORIG_CHUNK, REPL_CHUNK),)),
    ("group_chats.c", ((ORIG_ASSERTS, REPL_ASSERTS), (ORIG_PADDING, REPL_PADDING), (ORIG_TCP, REPL_TCP),
                       (ORIG_TCP_OOB, REPL_TCP_OOB), (ORIG_UDP, REPL_UDP))),
    ("group_connection.c", ((ORIG_REASSEMBLE, REPL_REASSEMBLE),)),
)


def main(src_dir):
    for name, edits in PATCHES:
        path = os.path.join(src_dir, name)
        src = open(path, "r", encoding="utf-8", errors="surrogateescape").read()

        if MARKER in src:
            print("apply_khandaq_gc_ipv6_mtu: " + path + " already patched")
            continue

        for orig, repl in edits:
            count = src.count(orig)
            if count != 1:
                sys.stderr.write("apply_khandaq_gc_ipv6_mtu: expected exactly one target in " + path + ", found "
                                 + str(count) + " -- toxcore may have changed; aborting so the build does not ship"
                                 " group packets that IPv6 splits into fragments.\n")
                return 1
            src = src.replace(orig, repl, 1)

        open(path, "w", encoding="utf-8", errors="surrogateescape").write(src)
        print("apply_khandaq_gc_ipv6_mtu: patched " + path + " OK")

    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "toxcore"))
