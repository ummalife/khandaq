#!/usr/bin/env python3
# KHANDAQ (0.2.59, group files): a group member behind a gap catches up, and what it already holds is not sent to
# it again.
#
# QA live 0.2.57 and 0.2.58: a 6 MiB file to a group (lossless custom packets of about 37000 bytes, each sent as
# fragments) never reached a phone that lost its network for 10 s while the file went out: its sender timed it out
# ("Send array stuck") about a minute later, after sending it 46.8 MB. On a lossy path 2 of 3 members failed at
# 6 MiB with no cut at all. The receiving side of toxcore was why:
# - a fragment that came out of order was dropped, so after one loss every fragment behind it had to come again,
#   at the resend times of 3, 5, 9, 17 and 33 s. It is kept now, as any other packet that comes out of order;
# - a fragment taken was not acknowledged: the sender sent each one again about 3 s later, and only that duplicate
#   was acknowledged. A fragment with data is acknowledged when it is kept or taken; taking it later cannot fail;
# - a packet kept for later was handed on at one per tox_iterate, so a member that kept a few hundred took them at
#   about 20 a second while their sender went on. Up to 512 now, in one go;
# - a packet handed on from the receive array was acknowledged as message 0: process_recv_array_entry read the id
#   of the entry it had just cleared. Its sender kept it until a resend of it came back as a duplicate;
# - a kept packet that came again in order was taken from the wire, and its copy stayed in the receive array, where
#   8192 messages later it would block the message of its slot, or be handed on in its place.
# Nothing changes on the wire: an old peer sends and takes the same packets.
#
# QA live of 0.2.59: a phone whose network came back with another address timed its sender out a minute later and
# came back through a new handshake, which the sender took on the connection it still had. A handshake starts the
# message ids again at 2 and 3, and the connection kept what the old ids had left in its arrays: the next file reached
# that phone only up to chunk 90 of 171, with no cut at all, since a packet whose slot an old one still held was not
# queued for it. A peer that comes back through a new handshake leaves nothing of the old one in the arrays now;
# khandaq-desktop/buildscripts/toxcore/auto_tests/group_rehandshake_test.c runs it on two local toxes.
#
# The sender side is apply_khandaq_media_resend.py (#201), which runs before this script: the oldest unacknowledged
# packet goes again every second. The desktop copy of toxcore (khandaq-desktop/buildscripts/toxcore, both
# amalgamations) and the iOS copy (khandaq-ios/local_pod_repo/toxcore) carry both changes;
# scripts/check-gc-recv-gap.py holds them together, and
# khandaq-desktop/buildscripts/toxcore/auto_tests/group_recv_gap_test.c runs them on two local toxes with a cut and
# lost datagrams.
#
# Context-independent substring replace (robust to line drift), same convention as the other
# apply_khandaq_*.py scripts. A target that is missing or not unique fails the build, so an .so
# cannot ship without the change. Run during the native build against a freshly-cloned toxcore tree:
#   python3 apply_khandaq_gc_recv_gap.py toxcore
import os
import sys

MARKER = "KHANDAQ (0.2.59, group files)"

# --- group_connection.c: keep what comes out of order, take it all once the gap fills, acknowledge it by its id;
# a copy kept of what then comes in order goes ---
ORIG_RECEIVE = ("int gcc_handle_received_message(const Logger *log, const Mono_Time *mono_time, GC_Connection *gconn,\n"
                "                                const uint8_t *data, uint16_t length, uint8_t packet_type, uint64_t message_id,\n"
                "                                bool direct_conn)\n"
                "{\n"
                "    if (direct_conn) {\n"
                "        gconn->last_received_direct_time = mono_time_get(mono_time);\n"
                "    }\n"
                "\n"
                "    /* Appears to be a duplicate packet so we discard it */\n"
                "    if (message_id < gconn->received_message_id + 1) {\n"
                "        return 0;\n"
                "    }\n"
                "\n"
                "    if (packet_type == GP_FRAGMENT) { // we handle packet fragments as a special case\n"
                "        return 3;\n"
                "    }\n"
                "\n"
                "    /* we're missing an older message from this peer so we store it in recv_array */\n"
                "    if (message_id > gconn->received_message_id + 1) {\n"
                "        if (!store_in_recv_array(log, mono_time, gconn, data, length, packet_type, message_id)) {\n"
                "            return -1;\n"
                "        }\n"
                "\n"
                "        return 1;\n"
                "    }\n"
                "\n"
                "    gcc_set_recv_message_id(gconn, gconn->received_message_id + 1);\n"
                "\n"
                "    return 2;\n"
                "}\n"
                "\n"
                "/** @brief Handles peer_number's array entry with appropriate handler and clears it from array.\n"
                " *\n"
                " * This function increments the received message ID for `gconn`.\n"
                " *\n"
                " * Return true on success.\n"
                " */\n"
                "non_null(1, 2, 3, 5) nullable(6)\n"
                "static bool process_recv_array_entry(const GC_Session *c, GC_Chat *chat, GC_Connection *gconn, uint32_t peer_number,\n"
                "                                     GC_Message_Array_Entry *const array_entry, void *userdata)\n"
                "{\n"
                "    uint8_t sender_pk[ENC_PUBLIC_KEY_SIZE];\n"
                "    memcpy(sender_pk, get_enc_key(gconn->addr.public_key), ENC_PUBLIC_KEY_SIZE);\n"
                "\n"
                "    const bool ret = handle_gc_lossless_helper(c, chat, peer_number, array_entry->data, array_entry->data_length,\n"
                "                     array_entry->packet_type, userdata);\n"
                "\n"
                "    /* peer number can change from peer add operations in packet handlers */\n"
                "    peer_number = get_peer_number_of_enc_pk(chat, sender_pk, false);\n"
                "    gconn = get_gc_connection(chat, peer_number);\n"
                "\n"
                "    clear_array_entry(array_entry);\n"
                "\n"
                "    if (gconn == nullptr) {\n"
                "        return true;\n"
                "    }\n"
                "\n"
                "    if (!ret) {\n"
                "        gc_send_message_ack(chat, gconn, array_entry->message_id, GR_ACK_REQ);\n"
                "        return false;\n"
                "    }\n"
                "\n"
                "    gc_send_message_ack(chat, gconn, array_entry->message_id, GR_ACK_RECV);\n"
                "\n"
                "    gcc_set_recv_message_id(gconn, gconn->received_message_id + 1);\n"
                "\n"
                "    return true;\n"
                "}\n"
                "\n"
                "void gcc_check_recv_array(const GC_Session *c, GC_Chat *chat, GC_Connection *gconn, uint32_t peer_number,\n"
                "                          void *userdata)\n"
                "{\n"
                "    if (gconn->last_chunk_id != 0) {  // dont check array if we have an unfinished fragment sequence\n"
                "        return;\n"
                "    }\n"
                "\n"
                "    const uint16_t idx = (gconn->received_message_id + 1) % GCC_BUFFER_SIZE;\n"
                "    GC_Message_Array_Entry *const array_entry = &gconn->recv_array[idx];\n"
                "\n"
                "    if (!array_entry_is_empty(array_entry)) {\n"
                "        process_recv_array_entry(c, chat, gconn, peer_number, array_entry, userdata);\n"
                "    }\n"
                "}\n"
                "\n")

REPL_RECEIVE = ("int gcc_handle_received_message(const Logger *log, const Mono_Time *mono_time, GC_Connection *gconn,\n"
                "                                const uint8_t *data, uint16_t length, uint8_t packet_type, uint64_t message_id,\n"
                "                                bool direct_conn)\n"
                "{\n"
                "    if (direct_conn) {\n"
                "        gconn->last_received_direct_time = mono_time_get(mono_time);\n"
                "    }\n"
                "\n"
                "    /* Appears to be a duplicate packet so we discard it */\n"
                "    if (message_id < gconn->received_message_id + 1) {\n"
                "        return 0;\n"
                "    }\n"
                "\n"
                "    GC_Message_Array_Entry *const kept = &gconn->recv_array[gcc_get_array_index(message_id)];\n"
                "\n"
                "    /* we're missing an older message from this peer so we store it in recv_array */\n"
                "    if (message_id > gconn->received_message_id + 1) {\n"
                "        // KHANDAQ (0.2.59, group files): a fragment is kept too. Dropped, it had to come again with every fragment\n"
                "        // behind it, at the resend times. A resend of a message kept already is not kept twice.\n"
                "        if (!array_entry_is_empty(kept) && kept->message_id == message_id) {\n"
                "            return 1;\n"
                "        }\n"
                "\n"
                "        if (!store_in_recv_array(log, mono_time, gconn, data, length, packet_type, message_id)) {\n"
                "            return -1;\n"
                "        }\n"
                "\n"
                "        return 1;\n"
                "    }\n"
                "\n"
                "    // KHANDAQ (0.2.59, group files): the message comes in order while a copy of it, kept when it came out of order,\n"
                "    // still waits in recv_array: this one is taken and the copy goes. Left there, 8192 messages later it would block\n"
                "    // the message of its slot, or be handed on in its place.\n"
                "    if (!array_entry_is_empty(kept)) {\n"
                "        clear_array_entry(kept);\n"
                "    }\n"
                "\n"
                "    if (packet_type == GP_FRAGMENT) { // we handle packet fragments as a special case\n"
                "        return 3;\n"
                "    }\n"
                "\n"
                "    gcc_set_recv_message_id(gconn, gconn->received_message_id + 1);\n"
                "\n"
                "    return 2;\n"
                "}\n"
                "\n"
                "/** @brief Handles peer_number's array entry with appropriate handler and clears it from array.\n"
                " *\n"
                " * This function increments the received message ID for `gconn`.\n"
                " *\n"
                " * Return true on success.\n"
                " */\n"
                "non_null(1, 2, 3, 5) nullable(6)\n"
                "static bool process_recv_array_entry(const GC_Session *c, GC_Chat *chat, GC_Connection *gconn, uint32_t peer_number,\n"
                "                                     GC_Message_Array_Entry *const array_entry, void *userdata)\n"
                "{\n"
                "    uint8_t sender_pk[ENC_PUBLIC_KEY_SIZE];\n"
                "    memcpy(sender_pk, get_enc_key(gconn->addr.public_key), ENC_PUBLIC_KEY_SIZE);\n"
                "\n"
                "    // KHANDAQ (0.2.59, group files): read before the entry is cleared below. Read after it, it was 0: the message\n"
                "    // was acknowledged as message 0, and its sender kept it until a resend of it came back as a duplicate.\n"
                "    const uint64_t message_id = array_entry->message_id;\n"
                "\n"
                "    const bool ret = handle_gc_lossless_helper(c, chat, peer_number, array_entry->data, array_entry->data_length,\n"
                "                     array_entry->packet_type, userdata);\n"
                "\n"
                "    /* peer number can change from peer add operations in packet handlers */\n"
                "    peer_number = get_peer_number_of_enc_pk(chat, sender_pk, false);\n"
                "    gconn = get_gc_connection(chat, peer_number);\n"
                "\n"
                "    clear_array_entry(array_entry);\n"
                "\n"
                "    if (gconn == nullptr) {\n"
                "        return true;\n"
                "    }\n"
                "\n"
                "    if (!ret) {\n"
                "        gc_send_message_ack(chat, gconn, message_id, GR_ACK_REQ);\n"
                "        return false;\n"
                "    }\n"
                "\n"
                "    gc_send_message_ack(chat, gconn, message_id, GR_ACK_RECV);\n"
                "\n"
                "    gcc_set_recv_message_id(gconn, gconn->received_message_id + 1);\n"
                "\n"
                "    return true;\n"
                "}\n"
                "\n"
                "/** @brief KHANDAQ (0.2.59, group files): takes the fragment kept in `array_entry`, the next message expected from the\n"
                " * peer. A fragment with data stays where it is, as gcc_handle_packet_fragment() keeps one that comes in order; the\n"
                " * empty one that ends a sequence makes the packet whole and hands it on.\n"
                " *\n"
                " * Return true if the fragment was taken.\n"
                " */\n"
                "non_null(1, 2, 3, 5) nullable(6)\n"
                "static bool take_recv_array_fragment(const GC_Session *c, GC_Chat *chat, GC_Connection *gconn, uint32_t peer_number,\n"
                "                                     GC_Message_Array_Entry *const array_entry, void *userdata)\n"
                "{\n"
                "    const uint64_t message_id = array_entry->message_id;\n"
                "\n"
                "    if (array_entry->data_length > 0) {\n"
                "        gcc_set_recv_message_id(gconn, message_id);\n"
                "        gconn->last_chunk_id = message_id;\n"
                "        gc_send_message_ack(chat, gconn, message_id, GR_ACK_RECV);\n"
                "        return true;\n"
                "    }\n"
                "\n"
                "    clear_array_entry(array_entry);\n"
                "\n"
                "    uint8_t sender_pk[ENC_PUBLIC_KEY_SIZE];\n"
                "    memcpy(sender_pk, get_enc_key(gconn->addr.public_key), ENC_PUBLIC_KEY_SIZE);\n"
                "\n"
                "    if (gcc_handle_packet_fragment(c, chat, peer_number, gconn, nullptr, 0, GP_FRAGMENT, message_id, userdata) != 0) {\n"
                "        return false;\n"
                "    }\n"
                "\n"
                "    /* peer number can change from peer add operations in packet handlers */\n"
                "    gconn = get_gc_connection(chat, get_peer_number_of_enc_pk(chat, sender_pk, false));\n"
                "\n"
                "    if (gconn != nullptr) {\n"
                "        gc_send_message_ack(chat, gconn, message_id, GR_ACK_RECV);\n"
                "    }\n"
                "\n"
                "    return true;\n"
                "}\n"
                "\n"
                "/** KHANDAQ (0.2.59, group files): the most messages kept in recv_array that one gcc_check_recv_array() call takes.\n"
                " * It took one, so a peer that kept a few hundred messages behind a gap took them at one per tox_iterate while their\n"
                " * sender went on, and the sender's oldest unacknowledged message passed GC_CONFIRMED_PEER_TIMEOUT: it timed us out\n"
                " * (\"Send array stuck\"). Bounded, so that one tox_iterate does not hand the client thousands of packets at once.\n"
                " */\n"
                "#define GCC_RECV_ARRAY_TAKE_MAX 512\n"
                "\n"
                "void gcc_check_recv_array(const GC_Session *c, GC_Chat *chat, GC_Connection *gconn, uint32_t peer_number,\n"
                "                          void *userdata)\n"
                "{\n"
                "    uint8_t sender_pk[ENC_PUBLIC_KEY_SIZE];\n"
                "    memcpy(sender_pk, get_enc_key(gconn->addr.public_key), ENC_PUBLIC_KEY_SIZE);\n"
                "\n"
                "    for (uint32_t taken = 0; taken < GCC_RECV_ARRAY_TAKE_MAX; ++taken) {\n"
                "        const uint64_t message_id = gconn->received_message_id + 1;\n"
                "        GC_Message_Array_Entry *const array_entry = &gconn->recv_array[gcc_get_array_index(message_id)];\n"
                "\n"
                "        if (array_entry_is_empty(array_entry)) {\n"
                "            return;\n"
                "        }\n"
                "\n"
                "        if (array_entry->message_id != message_id) {\n"
                "            // left from a message long gone: never handed on as this one\n"
                "            clear_array_entry(array_entry);\n"
                "            return;\n"
                "        }\n"
                "\n"
                "        bool ok;\n"
                "\n"
                "        if (array_entry->packet_type == GP_FRAGMENT) {\n"
                "            ok = take_recv_array_fragment(c, chat, gconn, peer_number, array_entry, userdata);\n"
                "        } else if (gconn->last_chunk_id != 0) {  // dont hand on a packet in the middle of a fragment sequence\n"
                "            return;\n"
                "        } else {\n"
                "            ok = process_recv_array_entry(c, chat, gconn, peer_number, array_entry, userdata);\n"
                "        }\n"
                "\n"
                "        if (!ok) {\n"
                "            return;\n"
                "        }\n"
                "\n"
                "        /* peer number can change from peer add operations in packet handlers */\n"
                "        const int number = get_peer_number_of_enc_pk(chat, sender_pk, false);\n"
                "        gconn = get_gc_connection(chat, number);\n"
                "\n"
                "        if (gconn == nullptr || gconn->pending_delete) {\n"
                "            return;\n"
                "        }\n"
                "\n"
                "        peer_number = (uint32_t)number;\n"
                "    }\n"
                "}\n"
                "\n")

# --- group_chats.c: a fragment kept for its sequence is acknowledged ---
ORIG_FRAGMENT_ACK = ("    if (frag_ret == -1) {\n"
                     "        return false;\n"
                     "    }\n"
                     "\n"
                     "    if (frag_ret == 0) {\n"
                     "        gc_send_message_ack(chat, gconn, message_id, GR_ACK_RECV);\n"
                     "    }\n"
                     "\n")

REPL_FRAGMENT_ACK = ("    if (frag_ret == -1) {\n"
                     "        return false;\n"
                     "    }\n"
                     "\n"
                     "    // KHANDAQ (0.2.59, group files): a fragment kept for its sequence is acknowledged too. It was not: the sender\n"
                     "    // sent each one again about 3 s later, and only that duplicate was acknowledged.\n"
                     "    if (frag_ret == 0 || frag_ret == 1) {\n"
                     "        gc_send_message_ack(chat, gconn, message_id, GR_ACK_RECV);\n"
                     "    }\n"
                     "\n")

# --- group_chats.c: a fragment with data kept out of order is acknowledged at once ---
ORIG_KEPT_ACK = ("    /* request missing packet */\n"
                 "    if (lossless_ret == 1) {\n"
                 "        LOGGER_TRACE(chat->log, \"received out of order packet from peer %u. expected %llu, got %llu\", peer_number,\n"
                 "                     (unsigned long long)gconn->received_message_id + 1, (unsigned long long)message_id);\n"
                 "        free(data);\n"
                 "        return gc_send_message_ack(chat, gconn, gconn->received_message_id + 1, GR_ACK_REQ);\n"
                 "    }\n"
                 "\n")

REPL_KEPT_ACK = ("    /* request missing packet */\n"
                 "    if (lossless_ret == 1) {\n"
                 "        LOGGER_TRACE(chat->log, \"received out of order packet from peer %u. expected %llu, got %llu\", peer_number,\n"
                 "                     (unsigned long long)gconn->received_message_id + 1, (unsigned long long)message_id);\n"
                 "        free(data);\n"
                 "\n"
                 "        // KHANDAQ (0.2.59, group files): a fragment with data, kept until the gap before it fills, is acknowledged\n"
                 "        // now, so that its sender does not send it again: taking it later cannot fail. Any other packet kept is\n"
                 "        // acknowledged once it was handled, as before, since its handler may fail and ask for it again.\n"
                 "        if (packet_type == GP_FRAGMENT && len > 0) {\n"
                 "            gc_send_message_ack(chat, gconn, message_id, GR_ACK_RECV);\n"
                 "        }\n"
                 "\n"
                 "        return gc_send_message_ack(chat, gconn, gconn->received_message_id + 1, GR_ACK_REQ);\n"
                 "    }\n"
                 "\n")

# --- group_chats.c: a peer that comes back through a new handshake leaves nothing of the old one in the arrays ---
ORIG_CLEAR_ARRAYS = ("    return send_gc_handshake_packet(chat, gconn, GH_RESPONSE, gconn->pending_handshake_type, 0);\n"
                     "}\n"
                     "\n"
                     "/** @brief Handles handshake request packets.\n")

REPL_CLEAR_ARRAYS = ("    return send_gc_handshake_packet(chat, gconn, GH_RESPONSE, gconn->pending_handshake_type, 0);\n"
                     "}\n"
                     "\n"
                     "/** KHANDAQ (0.2.59, group files): drops every packet held for the peer of `gconn` and every one kept from it. */\n"
                     "non_null()\n"
                     "static void clear_message_arrays(GC_Connection *gconn)\n"
                     "{\n"
                     "    for (uint16_t i = 0; i < GCC_BUFFER_SIZE; ++i) {\n"
                     "        free(gconn->send_array[i].data);\n"
                     "        free(gconn->recv_array[i].data);\n"
                     "\n"
                     "        gconn->send_array[i] = (GC_Message_Array_Entry) {\n"
                     "            nullptr\n"
                     "        };\n"
                     "        gconn->recv_array[i] = (GC_Message_Array_Entry) {\n"
                     "            nullptr\n"
                     "        };\n"
                     "    }\n"
                     "}\n"
                     "\n"
                     "/** @brief Handles handshake request packets.\n")

ORIG_REHANDSHAKE = ("        if (gconn->handshaked) {\n"
                    "            gconn->handshaked = false;\n"
                    "            LOGGER_DEBUG(chat->log, \"Handshaked peer sent a handshake request\");\n")

REPL_REHANDSHAKE = ("        if (gconn->handshaked) {\n"
                    "            gconn->handshaked = false;\n"
                    "            // KHANDAQ (0.2.59, group files): the peer starts the connection again, and the new handshake starts the\n"
                    "            // message ids again at 2 and 3. What the old ids left in the arrays goes with them: a packet still held\n"
                    "            // for the peer took the slot of a new one, which was then not queued for it (\"Send array entry isn't\n"
                    "            // empty\") while the same group packet went to the other members, and a packet kept from the peer would\n"
                    "            // be handed on as the new message of its id.\n"
                    "            clear_message_arrays(gconn);\n"
                    "            LOGGER_DEBUG(chat->log, \"Handshaked peer sent a handshake request\");\n")

PATCHES = (
    ("group_connection.c", ((ORIG_RECEIVE, REPL_RECEIVE),)),
    ("group_chats.c", ((ORIG_FRAGMENT_ACK, REPL_FRAGMENT_ACK), (ORIG_KEPT_ACK, REPL_KEPT_ACK),
                       (ORIG_CLEAR_ARRAYS, REPL_CLEAR_ARRAYS), (ORIG_REHANDSHAKE, REPL_REHANDSHAKE))),
)


def main(src_dir):
    for name, edits in PATCHES:
        path = os.path.join(src_dir, name)
        src = open(path, "r", encoding="utf-8", errors="surrogateescape").read()

        if MARKER in src:
            print("apply_khandaq_gc_recv_gap: " + path + " already patched")
            continue

        for orig, repl in edits:
            count = src.count(orig)
            if count != 1:
                sys.stderr.write("apply_khandaq_gc_recv_gap: expected exactly one target in " + path + ", found "
                                 + str(count) + " -- toxcore may have changed; aborting so the build does not ship"
                                 " group transfers that a member behind a gap never catches up with.\n")
                return 1
            src = src.replace(orig, repl, 1)

        open(path, "w", encoding="utf-8", errors="surrogateescape").write(src)
        print("apply_khandaq_gc_recv_gap: patched " + path + " OK")

    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "toxcore"))
