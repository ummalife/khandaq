#!/usr/bin/env python3
# KHANDAQ (0.2.60, group roaming): a group peer whose address changes gets what we send it at its new address.
#
# QA live of 0.2.59: a phone whose network went away for 10 s came back with another address (the airplane mode of an
# emulator; Wi-Fi to mobile data does the same). The group file going out to it stood still for a minute, and the phone
# caught up with it about two and a half minutes after its network came back. The phone kept its group session and
# went on sending from its new address; each of its packets was taken and counted as a direct packet, so its connection
# stayed direct, and the sender sent it everything over UDP to the old address alone, never over a relay. The phone
# heard nothing of the sender until it timed it out (GC_CONFIRMED_PEER_TIMEOUT, 58 s) and came back through a new
# handshake.
#
# A packet that comes over UDP and opens with a peer's session key was sent by that peer. We take its address as the
# peer's (follow_moved_gc_peer in group_chats.c):
# - for a lossless message we have not had yet (gcc_message_is_new). A copy of an old one proves nothing of where its
#   peer is: anyone who saw it go by can send it again from elsewhere;
# - for any packet, when what we send the peer goes unanswered: the oldest message we hold for it has waited
#   GCC_UNANSWERED_TIMEOUT seconds for its acknowledgement (gcc_sent_messages_unanswered);
# - and a peer we have heard nothing of for a ping and a half (GC_SILENT_PEER_PROBE_TIMEOUT) is sent a peer info
#   request with our next ping: if it is not gone, our network changed and it sends to where we were, and this lossless
#   packet from where we are now moves it here. A peer that is gone ignores it and times out as before.
# Nothing changes on the wire. A peer without this change still sends to the old address until the timeout, as before.
#
# The desktop copy of toxcore (khandaq-desktop/buildscripts/toxcore, both amalgamations) and the iOS copy
# (khandaq-ios/local_pod_repo/toxcore) carry the same text; scripts/check-gc-roaming.py holds them together, and
# khandaq-desktop/buildscripts/toxcore/auto_tests/group_roaming_test.c runs it on two local toxes whose addresses move.
#
# Context-independent substring replace (robust to line drift), same convention as the other
# apply_khandaq_*.py scripts. A target that is missing or not unique fails the build, so an .so
# cannot ship without the change. Run during the native build against a freshly-cloned toxcore tree,
# after apply_khandaq_gc_recv_gap.py:
#   python3 apply_khandaq_gc_roaming.py toxcore
import os
import sys

MARKER = "KHANDAQ (0.2.60, group roaming)"

# --- group_connection.h: whether a message of a peer is new, and whether what we send it goes unanswered ---
ORIG_DECLARATIONS = ("non_null(1) nullable(2)\n"
                     "void gcc_set_ip_port(GC_Connection *gconn, const IP_Port *ipp);\n")

REPL_DECLARATIONS = ("non_null(1) nullable(2)\n"
                     "void gcc_set_ip_port(GC_Connection *gconn, const IP_Port *ipp);\n"
                     "\n"
                     "/** @brief KHANDAQ (0.2.60, group roaming): Return true if the lossless message `message_id` of the peer\n"
                     " * of `gconn` is one we have not had yet: neither taken nor kept in the receive array.\n"
                     " */\n"
                     "non_null()\n"
                     "bool gcc_message_is_new(const GC_Connection *gconn, uint64_t message_id);\n"
                     "\n"
                     "/** @brief KHANDAQ (0.2.60, group roaming): Return true if the oldest lossless message we hold for the\n"
                     " * peer of `gconn` has waited GCC_UNANSWERED_TIMEOUT seconds for its acknowledgement: what we send that\n"
                     " * peer does not reach it.\n"
                     " */\n"
                     "non_null()\n"
                     "bool gcc_sent_messages_unanswered(const Mono_Time *mono_time, const GC_Connection *gconn);\n")

# --- group_connection.c: how long the oldest message for a peer may wait for its acknowledgement ---
ORIG_UNANSWERED_TIMEOUT = ("/** Seconds since last direct UDP packet was sent before we can try again. Cheap NAT hole punch */\n"
                           "#define GCC_UDP_DIRECT_RETRY 1\n")

REPL_UNANSWERED_TIMEOUT = ("/** Seconds since last direct UDP packet was sent before we can try again. Cheap NAT hole punch */\n"
                           "#define GCC_UDP_DIRECT_RETRY 1\n"
                           "\n"
                           "/** KHANDAQ (0.2.60, group roaming): seconds the oldest message we hold for a peer may wait for its\n"
                           " * acknowledgement before we take it that what we send the peer does not reach it; up to a second less,\n"
                           " * as mono time counts whole seconds. The oldest message goes again every second meanwhile (#201).\n"
                           " */\n"
                           "#define GCC_UNANSWERED_TIMEOUT 3\n")

# --- group_connection.c: the two checks ---
ORIG_CHECKS = ("void gcc_set_ip_port(GC_Connection *gconn, const IP_Port *ipp)\n"
               "{\n"
               "    if (ipp != nullptr && ipport_isset(ipp)) {\n"
               "        gconn->addr.ip_port = *ipp;\n"
               "    }\n"
               "}\n")

REPL_CHECKS = ("void gcc_set_ip_port(GC_Connection *gconn, const IP_Port *ipp)\n"
               "{\n"
               "    if (ipp != nullptr && ipport_isset(ipp)) {\n"
               "        gconn->addr.ip_port = *ipp;\n"
               "    }\n"
               "}\n"
               "\n"
               "bool gcc_message_is_new(const GC_Connection *gconn, uint64_t message_id)\n"
               "{\n"
               "    if (message_id <= gconn->received_message_id) {\n"
               "        return false;\n"
               "    }\n"
               "\n"
               "    const GC_Message_Array_Entry *kept = &gconn->recv_array[gcc_get_array_index(message_id)];\n"
               "    return array_entry_is_empty(kept) || kept->message_id != message_id;\n"
               "}\n"
               "\n"
               "bool gcc_sent_messages_unanswered(const Mono_Time *mono_time, const GC_Connection *gconn)\n"
               "{\n"
               "    const GC_Message_Array_Entry *oldest = &gconn->send_array[gconn->send_array_start];\n"
               "    return !array_entry_is_empty(oldest)\n"
               "           && mono_time_is_timeout(mono_time, oldest->time_added, GCC_UNANSWERED_TIMEOUT);\n"
               "}\n")

# --- group_chats.c: follow a moved peer; the lossless handler takes where a direct packet came from ---
ORIG_LOSSLESS_HEAD = ("/** @brief Handles lossless groupchat packets.\n"
                      " *\n"
                      " * This function assumes the length has already been validated.\n"
                      " *\n"
                      " * Returns true if packet is successfully handled.\n"
                      " */\n"
                      "non_null(1, 2, 3, 4) nullable(7)\n"
                      "static bool handle_gc_lossless_packet(const GC_Session *c, GC_Chat *chat, const uint8_t *sender_pk,\n"
                      "                                      const uint8_t *packet, uint16_t length, bool direct_conn, void *userdata)\n"
                      "{\n")

REPL_LOSSLESS_HEAD = ("/** @brief KHANDAQ (0.2.60, group roaming): Follows a peer whose packets come from another address now.\n"
                      " *\n"
                      " * A peer whose network changes (Wi-Fi to mobile data, or a NAT that maps it anew) keeps its session with\n"
                      " * us and goes on sending from its new address. Its packets were taken and counted as direct, so its\n"
                      " * connection stayed direct, and what we sent it went over UDP to the old address alone, never over a\n"
                      " * relay: the peer heard nothing of us until it timed us out (GC_CONFIRMED_PEER_TIMEOUT) and came back\n"
                      " * through a new handshake.\n"
                      " *\n"
                      " * `ipp` is where the packet came from over UDP; it opened with the session key of the peer, so the peer\n"
                      " * sent it. We take that address when the packet is a message we have not had (`new_message`: a copy of\n"
                      " * an old one proves nothing, as anyone who saw it go by can send it again from elsewhere), or when what\n"
                      " * we send the peer goes unanswered (gcc_sent_messages_unanswered).\n"
                      " */\n"
                      "non_null(1, 2) nullable(3)\n"
                      "static void follow_moved_gc_peer(GC_Chat *chat, GC_Connection *gconn, const IP_Port *ipp, bool new_message)\n"
                      "{\n"
                      "    if (ipp == nullptr || !ipport_isset(ipp) || !gconn->confirmed || ipport_equal(ipp, &gconn->addr.ip_port)) {\n"
                      "        return;\n"
                      "    }\n"
                      "\n"
                      "    if (!new_message && !gcc_sent_messages_unanswered(chat->mono_time, gconn)) {\n"
                      "        return;\n"
                      "    }\n"
                      "\n"
                      "    Ip_Ntoa old_ip;\n"
                      "    Ip_Ntoa new_ip;\n"
                      "    LOGGER_INFO(chat->log, \"Group peer moved from %s:%d to %s:%d\", net_ip_ntoa(&gconn->addr.ip_port.ip, &old_ip),\n"
                      "                net_ntohs(gconn->addr.ip_port.port), net_ip_ntoa(&ipp->ip, &new_ip), net_ntohs(ipp->port));\n"
                      "\n"
                      "    gcc_set_ip_port(gconn, ipp);\n"
                      "    add_gc_saved_peers(chat, gconn);\n"
                      "}\n"
                      "\n"
                      "/** @brief Handles lossless groupchat packets.\n"
                      " *\n"
                      " * This function assumes the length has already been validated. KHANDAQ (0.2.60, group roaming): `ipp`\n"
                      " * is where a direct packet came from; null for one over TCP.\n"
                      " *\n"
                      " * Returns true if packet is successfully handled.\n"
                      " */\n"
                      "non_null(1, 2, 3, 4) nullable(7, 8)\n"
                      "static bool handle_gc_lossless_packet(const GC_Session *c, GC_Chat *chat, const uint8_t *sender_pk,\n"
                      "                                      const uint8_t *packet, uint16_t length, bool direct_conn, const IP_Port *ipp,\n"
                      "                                      void *userdata)\n"
                      "{\n")

# --- group_chats.c: a new message from another address moves us to it ---
ORIG_LOSSLESS_FOLLOW = ("    const int lossless_ret = gcc_handle_received_message(chat->log, chat->mono_time, gconn, data, (uint16_t) len,\n"
                        "                             packet_type, message_id, direct_conn);\n")

REPL_LOSSLESS_FOLLOW = ("    // KHANDAQ (0.2.60, group roaming): a peer that sends from another address now gets what we send it there.\n"
                        "    if (direct_conn) {\n"
                        "        follow_moved_gc_peer(chat, gconn, ipp, gcc_message_is_new(gconn, message_id));\n"
                        "    }\n"
                        "\n"
                        "    const int lossless_ret = gcc_handle_received_message(chat->log, chat->mono_time, gconn, data, (uint16_t) len,\n"
                        "                             packet_type, message_id, direct_conn);\n")

# --- group_chats.c: the lossy handler takes where a direct packet came from ---
ORIG_LOSSY_HEAD = (" * Return true if packet is handled successfully.\n"
                   " */\n"
                   "non_null(1, 2, 3, 4) nullable(7)\n"
                   "static bool handle_gc_lossy_packet(const GC_Session *c, GC_Chat *chat, const uint8_t *sender_pk,\n"
                   "                                   const uint8_t *packet, uint16_t length, bool direct_conn, void *userdata)\n"
                   "{\n")

REPL_LOSSY_HEAD = (" * KHANDAQ (0.2.60, group roaming): `ipp` is where a direct packet came from; null for one over TCP.\n"
                   " *\n"
                   " * Return true if packet is handled successfully.\n"
                   " */\n"
                   "non_null(1, 2, 3, 4) nullable(7, 8)\n"
                   "static bool handle_gc_lossy_packet(const GC_Session *c, GC_Chat *chat, const uint8_t *sender_pk,\n"
                   "                                   const uint8_t *packet, uint16_t length, bool direct_conn, const IP_Port *ipp,\n"
                   "                                   void *userdata)\n"
                   "{\n")

# --- group_chats.c: a lossy packet from another address moves us only when what we send goes unanswered ---
ORIG_LOSSY_FOLLOW = ("    int ret = -1;\n"
                     "    const uint16_t payload_len = (uint16_t)len;\n"
                     "\n"
                     "    switch (packet_type) {\n"
                     "        case GP_MESSAGE_ACK: {\n")

REPL_LOSSY_FOLLOW = ("    // KHANDAQ (0.2.60, group roaming): a lossy packet has no id, so a copy of an old one looks like a new one:\n"
                     "    // it moves us to where it came from only when what we send its peer goes unanswered.\n"
                     "    if (direct_conn) {\n"
                     "        follow_moved_gc_peer(chat, gconn, ipp, false);\n"
                     "    }\n"
                     "\n"
                     "    int ret = -1;\n"
                     "    const uint16_t payload_len = (uint16_t)len;\n"
                     "\n"
                     "    switch (packet_type) {\n"
                     "        case GP_MESSAGE_ACK: {\n")

# --- group_chats.c: the callers say where a packet came from ---
ORIG_TCP_LOSSLESS = "            if (!handle_gc_lossless_packet(c, chat, sender_pk, payload, payload_len, false, userdata)) {\n"
REPL_TCP_LOSSLESS = "            if (!handle_gc_lossless_packet(c, chat, sender_pk, payload, payload_len, false, nullptr, userdata)) {\n"

ORIG_TCP_LOSSY = "            if (!handle_gc_lossy_packet(c, chat, sender_pk, payload, payload_len, false, userdata)) {\n"
REPL_TCP_LOSSY = "            if (!handle_gc_lossy_packet(c, chat, sender_pk, payload, payload_len, false, nullptr, userdata)) {\n"

ORIG_UDP_LOSSLESS = "            ret = handle_gc_lossless_packet(c, chat, sender_pk, payload, payload_len, true, userdata);\n"
REPL_UDP_LOSSLESS = "            ret = handle_gc_lossless_packet(c, chat, sender_pk, payload, payload_len, true, ipp, userdata);\n"

ORIG_UDP_LOSSY = "            ret = handle_gc_lossy_packet(c, chat, sender_pk, payload, payload_len, true, userdata);\n"
REPL_UDP_LOSSY = "            ret = handle_gc_lossy_packet(c, chat, sender_pk, payload, payload_len, true, ipp, userdata);\n"

# --- group_chats.c: a peer silent for a ping and a half gets a lossless packet from where we are now ---
ORIG_PROBE_TIMEOUT = ("#define GC_DO_PINGS_INTERVAL 2\n"
                      "non_null()\n"
                      "static void do_gc_ping_and_key_rotation(GC_Chat *chat)\n")

REPL_PROBE_TIMEOUT = ("#define GC_DO_PINGS_INTERVAL 2\n"
                      "/** KHANDAQ (0.2.60, group roaming): seconds of silence of a peer after which a ping goes with a peer info\n"
                      " * request.\n"
                      " */\n"
                      "#define GC_SILENT_PEER_PROBE_TIMEOUT (GC_PING_TIMEOUT + GC_PING_TIMEOUT / 2)\n"
                      "non_null()\n"
                      "static void do_gc_ping_and_key_rotation(GC_Chat *chat)\n")

ORIG_PROBE = ("        if (mono_time_is_timeout(chat->mono_time, gconn->last_sent_ping_time, GC_PING_TIMEOUT)) {\n"
              "            if (ping_peer(chat, gconn)) {\n"
              "                gconn->last_sent_ping_time = tm;\n"
              "            }\n"
              "        }\n")

REPL_PROBE = ("        if (mono_time_is_timeout(chat->mono_time, gconn->last_sent_ping_time, GC_PING_TIMEOUT)) {\n"
              "            if (ping_peer(chat, gconn)) {\n"
              "                gconn->last_sent_ping_time = tm;\n"
              "            }\n"
              "\n"
              "            // KHANDAQ (0.2.60, group roaming): a peer we have heard nothing of for a ping and a half may be\n"
              "            // sending to an address we left, our network having changed. A lossless packet from where we are\n"
              "            // now moves it here (follow_moved_gc_peer); a peer that is gone ignores it and times out.\n"
              "            if (mono_time_is_timeout(chat->mono_time, gconn->last_received_packet_time, GC_SILENT_PEER_PROBE_TIMEOUT)) {\n"
              "                send_gc_peer_info_request(chat, gconn);\n"
              "            }\n"
              "        }\n")

PATCHES = (
    ("group_connection.h", ((ORIG_DECLARATIONS, REPL_DECLARATIONS),)),
    ("group_connection.c", ((ORIG_UNANSWERED_TIMEOUT, REPL_UNANSWERED_TIMEOUT), (ORIG_CHECKS, REPL_CHECKS))),
    ("group_chats.c", ((ORIG_LOSSLESS_HEAD, REPL_LOSSLESS_HEAD), (ORIG_LOSSLESS_FOLLOW, REPL_LOSSLESS_FOLLOW),
                       (ORIG_LOSSY_HEAD, REPL_LOSSY_HEAD), (ORIG_LOSSY_FOLLOW, REPL_LOSSY_FOLLOW),
                       (ORIG_TCP_LOSSLESS, REPL_TCP_LOSSLESS), (ORIG_TCP_LOSSY, REPL_TCP_LOSSY),
                       (ORIG_UDP_LOSSLESS, REPL_UDP_LOSSLESS), (ORIG_UDP_LOSSY, REPL_UDP_LOSSY),
                       (ORIG_PROBE_TIMEOUT, REPL_PROBE_TIMEOUT), (ORIG_PROBE, REPL_PROBE))),
)


def main(src_dir):
    for name, edits in PATCHES:
        path = os.path.join(src_dir, name)
        src = open(path, "r", encoding="utf-8", errors="surrogateescape").read()

        if MARKER in src:
            print("apply_khandaq_gc_roaming: " + path + " already patched")
            continue

        for orig, repl in edits:
            count = src.count(orig)
            if count != 1:
                sys.stderr.write("apply_khandaq_gc_roaming: expected exactly one target in " + path + ", found "
                                 + str(count) + " -- toxcore may have changed; aborting so the build does not ship"
                                 " groups that lose a member whose network changes for a minute.\n")
                return 1
            src = src.replace(orig, repl, 1)

        open(path, "w", encoding="utf-8", errors="surrogateescape").write(src)
        print("apply_khandaq_gc_roaming: patched " + path + " OK")

    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "toxcore"))
