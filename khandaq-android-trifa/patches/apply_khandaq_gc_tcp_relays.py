#!/usr/bin/env python3
# KHANDAQ (F9, group TCP relays): a group keeps relays of its own, so that a peer it cannot reach over UDP can still
# reach it, and it can reach that peer.
#
# QA live of 0.2.62 (F9): members of a group behind the same NAT never saw each other, a member who joined later stayed
# out of step with the others for good (a sync every 24 s), and an invite from a desktop founder could die without a
# word. A group's TCP connections run under the group's own key and take their relays from the messenger's
# (add_tcp_relays_to_chat), and four things kept a group without any:
# - it took only the relays the messenger was connected to. On a client that reaches all of its friends over UDP
#   those relays sleep, so a group made then got none;
# - it took them again only when the number of its own connected relays changed, so a group that started with none
#   (every group loaded at tox_new(), every group made before the first relay was up) never got any;
# - an invite confirm carried only the group's connected relays: with none, and no UDP address for the friend, the
#   founder dropped the invite and the friend never heard why;
# - a relay a group shares sleeps while every peer on it is direct, so a peer that can reach us only over TCP (one
#   behind our NAT, which gets our public address and cannot hairpin) sent its first handshake to a relay we had left.
# Now a group tops itself up to GC_MIN_CONNECTED_TCP_RELAYS relays every TCP_RELAYS_CHECK_INTERVAL, from the
# messenger's connected relays and then its sleeping ones; an invite confirm carries the group's relays, connected or
# asleep, and then the messenger's; and for GC_TCP_AWAKE_AFTER_SYNC_REQUEST seconds after a sync request every TCP
# connection of the group stays awake. Waking a connection takes back the sleepers it counted on its relays (as TokTok
# PR #3081), or a relay could fall asleep under a connection that is still on it. Nothing changes on the wire.
#
# The desktop copy of toxcore (khandaq-desktop/buildscripts/toxcore, both amalgamations) and the iOS copy
# (khandaq-ios/local_pod_repo/toxcore) carry the same text; scripts/check-gc-tcp-relays.py holds them together, and
# khandaq-desktop/buildscripts/toxcore/auto_tests/group_tcp_relays_test.c runs it on local toxes.
#
# Context-independent substring replace (robust to line drift), same convention as the other
# apply_khandaq_*.py scripts. A target that is missing or not unique fails the build, so an .so
# cannot ship without the change. Run during the native build against a freshly-cloned toxcore tree,
# after apply_khandaq_gc_roaming.py:
#   python3 apply_khandaq_gc_tcp_relays.py toxcore
import os
import sys

MARKER = "KHANDAQ (F9, group TCP relays)"

# --- group_chats.c: the relay minimum and the awake window ---
ORIG_CONSTANTS = ("#define TCP_RELAYS_CHECK_INTERVAL 10\n"
                  "non_null(1, 2) nullable(3)\n"
                  "static void do_gc_tcp(const GC_Session *c, GC_Chat *chat, void *userdata)\n")

REPL_CONSTANTS = ("#define TCP_RELAYS_CHECK_INTERVAL 10\n"
                  "\n"
                  "/* KHANDAQ (F9, group TCP relays): the group keeps at least this many relays connected under its own\n"
                  " * key: as many as an invite confirm carries, and no more than kill_nonused_tcp() leaves standing. */\n"
                  "#define GC_MIN_CONNECTED_TCP_RELAYS GCC_MAX_TCP_SHARED_RELAYS\n"
                  "\n"
                  "/* KHANDAQ (F9, group TCP relays): how long after our last sync request every TCP connection of the group\n"
                  " * stays awake. Longer than the 24 s between the requests of a peer whose peer list stays out of step (a\n"
                  " * ping every GC_PING_TIMEOUT, at most one request per GC_SYNC_REQUEST_LIMIT), so it holds while we\n"
                  " * re-sync. */\n"
                  "#define GC_TCP_AWAKE_AFTER_SYNC_REQUEST (GC_PING_TIMEOUT * 3)\n"
                  "\n"
                  "non_null(1, 2) nullable(3)\n"
                  "static void do_gc_tcp(const GC_Session *c, GC_Chat *chat, void *userdata)\n")

# --- group_chats.c: the peer loop and the relay check of do_gc_tcp ---
ORIG_DO_GC_TCP = ("    do_tcp_connections(chat->log, chat->tcp_conn, userdata);\n"
                  "\n"
                  "    for (uint32_t i = 1; i < chat->numpeers; ++i) {\n"
                  "        const GC_Connection *gconn = get_gc_connection(chat, i);\n"
                  "        assert(gconn != nullptr);\n"
                  "\n"
                  "        const bool tcp_set = !gcc_conn_is_direct(chat->mono_time, gconn);\n"
                  "        set_tcp_connection_to_status(chat->tcp_conn, gconn->tcp_connection_num, tcp_set);\n"
                  "    }\n"
                  "\n"
                  "    if (mono_time_is_timeout(chat->mono_time, chat->last_checked_tcp_relays, TCP_RELAYS_CHECK_INTERVAL)\n"
                  "            && tcp_connected_relays_count(chat->tcp_conn) != chat->connected_tcp_relays) {\n")

REPL_DO_GC_TCP = ("    do_tcp_connections(chat->log, chat->tcp_conn, userdata);\n"
                  "\n"
                  "    /* KHANDAQ (F9, group TCP relays): a peer announces us on relays tied to our connection with it, and\n"
                  "     * that connection going direct (UDP) puts them to sleep. A peer that can reach us only over TCP -- one\n"
                  "     * behind our NAT, which gets our public address from the announce and cannot hairpin -- then sends its\n"
                  "     * first handshake to a relay we are no longer on, and the relay drops it. While we re-sync the peer\n"
                  "     * list (someone has peers we lack), keep every connection awake so that we stay on those relays. */\n"
                  "    const bool resyncing = !mono_time_is_timeout(chat->mono_time, chat->last_sync_request,\n"
                  "                           GC_TCP_AWAKE_AFTER_SYNC_REQUEST);\n"
                  "    uint32_t num_woken = 0;\n"
                  "\n"
                  "    for (uint32_t i = 1; i < chat->numpeers; ++i) {\n"
                  "        const GC_Connection *gconn = get_gc_connection(chat, i);\n"
                  "        assert(gconn != nullptr);\n"
                  "\n"
                  "        const bool direct = gcc_conn_is_direct(chat->mono_time, gconn);\n"
                  "        const bool tcp_set = !direct || resyncing;\n"
                  "\n"
                  "        // 0 only when the status changed, so this counts the direct peers woken now\n"
                  "        if (set_tcp_connection_to_status(chat->tcp_conn, gconn->tcp_connection_num, tcp_set) == 0\n"
                  "                && direct && tcp_set) {\n"
                  "            ++num_woken;\n"
                  "        }\n"
                  "    }\n"
                  "\n"
                  "    if (num_woken > 0) {\n"
                  "        LOGGER_INFO(chat->log, \"KHANDAQ F9: group %d re-syncing, TCP kept awake for %u direct peer(s)\",\n"
                  "                    chat->group_number, num_woken);\n"
                  "    }\n"
                  "\n"
                  "    /* KHANDAQ (F9, group TCP relays): also top the group up while it has fewer than\n"
                  "     * GC_MIN_CONNECTED_TCP_RELAYS connected relays. The old test alone (the count changed) never fires for\n"
                  "     * a pool that started empty -- every group loaded at tox_new() and every group created before the\n"
                  "     * first relay was up -- so such a group never had a relay to hand anyone. */\n"
                  "    if (mono_time_is_timeout(chat->mono_time, chat->last_checked_tcp_relays, TCP_RELAYS_CHECK_INTERVAL)\n"
                  "            && (tcp_connected_relays_count(chat->tcp_conn) != chat->connected_tcp_relays\n"
                  "                || tcp_connected_relays_count(chat->tcp_conn) < GC_MIN_CONNECTED_TCP_RELAYS)) {\n")

# --- group_chats.c: the relays a group takes from the messenger ---
ORIG_SEEDING = ("/** Attempts to associate new TCP relays with our group connection. */\n"
                "static void add_tcp_relays_to_chat(const GC_Session *c, GC_Chat *chat)\n"
                "{\n"
                "    const Messenger *m = c->messenger;\n"
                "\n"
                "    const uint32_t num_relays = tcp_connections_count(nc_get_tcp_c(m->net_crypto));\n"
                "\n"
                "    if (num_relays == 0) {\n"
                "        return;\n"
                "    }\n"
                "\n"
                "    Node_format *tcp_relays = (Node_format *)calloc(num_relays, sizeof(Node_format));\n"
                "\n"
                "    if (tcp_relays == nullptr) {\n"
                "        return;\n"
                "    }\n"
                "\n"
                "    const uint32_t num_copied = tcp_copy_connected_relays(nc_get_tcp_c(m->net_crypto), tcp_relays, (uint16_t)num_relays);\n"
                "\n"
                "    for (uint32_t i = 0; i < num_copied; ++i) {\n"
                "        add_tcp_relay_global(chat->tcp_conn, &tcp_relays[i].ip_port, tcp_relays[i].public_key);\n"
                "    }\n"
                "\n"
                "    free(tcp_relays);\n"
                "}\n")

REPL_SEEDING = ("/** KHANDAQ (F9, group TCP relays): appends `relay` to `tcp_relays` unless it is full or holds a relay\n"
                " * with that key. */\n"
                "non_null()\n"
                "static void append_unique_tcp_relay(Node_format *tcp_relays, uint32_t *num, uint32_t max_num,\n"
                "                                    const Node_format *relay)\n"
                "{\n"
                "    if (*num >= max_num) {\n"
                "        return;\n"
                "    }\n"
                "\n"
                "    for (uint32_t i = 0; i < *num; ++i) {\n"
                "        if (pk_equal(tcp_relays[i].public_key, relay->public_key)) {\n"
                "            return;\n"
                "        }\n"
                "    }\n"
                "\n"
                "    tcp_relays[*num] = *relay;\n"
                "    ++*num;\n"
                "}\n"
                "\n"
                "/** @brief KHANDAQ (F9, group TCP relays): appends the relays of `tcp_c` whose status is `status`\n"
                " * (TCP_CONN_CONNECTED or TCP_CONN_SLEEPING) to `tcp_relays`, up to `max_num` in all, skipping keys already\n"
                " * there.\n"
                " *\n"
                " * A sleeping relay keeps its address and key in its TCP_con, and tying it to a connection that is awake\n"
                " * (add_tcp_relay_connection) wakes it. The family is set to TCP_INET/TCP_INET6, as\n"
                " * tcp_copy_connected_relays() does.\n"
                " */\n"
                "non_null()\n"
                "static void append_tcp_relays_with_status(const TCP_Connections *tcp_c, uint8_t status, Node_format *tcp_relays,\n"
                "        uint32_t *num, uint32_t max_num)\n"
                "{\n"
                "    const uint32_t count = tcp_connections_count(tcp_c);\n"
                "\n"
                "    for (uint32_t i = 0; i < count && *num < max_num; ++i) {\n"
                "        const TCP_con *tcp_con = get_tcp_connection(tcp_c, i);\n"
                "\n"
                "        if (tcp_con == nullptr || tcp_con->status != status) {\n"
                "            continue;\n"
                "        }\n"
                "\n"
                "        Node_format relay;\n"
                "        memset(&relay, 0, sizeof(relay));\n"
                "\n"
                "        if (status == TCP_CONN_CONNECTED) {\n"
                "            if (tcp_con->connection == nullptr) {\n"
                "                continue;\n"
                "            }\n"
                "\n"
                "            memcpy(relay.public_key, tcp_con_public_key(tcp_con->connection), CRYPTO_PUBLIC_KEY_SIZE);\n"
                "            relay.ip_port = tcp_con_ip_port(tcp_con->connection);\n"
                "        } else {\n"
                "            memcpy(relay.public_key, tcp_con->relay_pk, CRYPTO_PUBLIC_KEY_SIZE);\n"
                "            relay.ip_port = tcp_con->ip_port;\n"
                "        }\n"
                "\n"
                "        if (net_family_is_ipv4(relay.ip_port.ip.family)) {\n"
                "            relay.ip_port.ip.family = net_family_tcp_ipv4();\n"
                "        } else if (net_family_is_ipv6(relay.ip_port.ip.family)) {\n"
                "            relay.ip_port.ip.family = net_family_tcp_ipv6();\n"
                "        } else {\n"
                "            continue;\n"
                "        }\n"
                "\n"
                "        append_unique_tcp_relay(tcp_relays, num, max_num, &relay);\n"
                "    }\n"
                "}\n"
                "\n"
                "/** @brief KHANDAQ (F9, group TCP relays): puts up to `max_num` relays to hand a peer that joins through\n"
                " * us in `tcp_relays`.\n"
                " *\n"
                " * Connected group relays first, then the group's sleeping relays, then the messenger's (connected, then\n"
                " * sleeping). add_gc_tcp_relays() ties each to the peer's connection, which wakes a sleeping one and joins a\n"
                " * messenger one under the group key. Before this an inviter whose group relays were all asleep, or had\n"
                " * never been seeded, confirmed the invite with no relay -- or not at all, when it had no UDP address for\n"
                " * the friend either.\n"
                " *\n"
                " * @return the number of relays put in `tcp_relays`.\n"
                " */\n"
                "non_null()\n"
                "static uint32_t copy_gc_shareable_tcp_relays(const GC_Session *c, const GC_Chat *chat, Node_format *tcp_relays,\n"
                "        uint32_t max_num)\n"
                "{\n"
                "    const TCP_Connections *m_tcp_c = nc_get_tcp_c(c->messenger->net_crypto);\n"
                "\n"
                "    uint32_t num = tcp_copy_connected_relays(chat->tcp_conn, tcp_relays, (uint16_t)max_num);\n"
                "    append_tcp_relays_with_status(chat->tcp_conn, TCP_CONN_SLEEPING, tcp_relays, &num, max_num);\n"
                "    append_tcp_relays_with_status(m_tcp_c, TCP_CONN_CONNECTED, tcp_relays, &num, max_num);\n"
                "    append_tcp_relays_with_status(m_tcp_c, TCP_CONN_SLEEPING, tcp_relays, &num, max_num);\n"
                "\n"
                "    return num;\n"
                "}\n"
                "\n"
                "/** Attempts to associate new TCP relays with our group connection. */\n"
                "static void add_tcp_relays_to_chat(const GC_Session *c, GC_Chat *chat)\n"
                "{\n"
                "    const Messenger *m = c->messenger;\n"
                "    const TCP_Connections *m_tcp_c = nc_get_tcp_c(m->net_crypto);\n"
                "\n"
                "    const uint32_t num_relays = tcp_connections_count(m_tcp_c);\n"
                "\n"
                "    if (num_relays == 0) {\n"
                "        return;\n"
                "    }\n"
                "\n"
                "    Node_format *tcp_relays = (Node_format *)calloc(num_relays, sizeof(Node_format));\n"
                "\n"
                "    if (tcp_relays == nullptr) {\n"
                "        return;\n"
                "    }\n"
                "\n"
                "    /* KHANDAQ (F9, group TCP relays): the messenger's sleeping relays too, connected ones first. On a\n"
                "     * client whose friends are all direct the messenger's relays sleep, and a group seeded from connected\n"
                "     * relays alone started (and, see do_gc_tcp, stayed) empty. New ones only while the group has fewer\n"
                "     * than GC_MIN_CONNECTED_TCP_RELAYS connected: kill_nonused_tcp() closes unused ones above three\n"
                "     * anyway. */\n"
                "    uint32_t num_copied = 0;\n"
                "    append_tcp_relays_with_status(m_tcp_c, TCP_CONN_CONNECTED, tcp_relays, &num_copied, num_relays);\n"
                "    append_tcp_relays_with_status(m_tcp_c, TCP_CONN_SLEEPING, tcp_relays, &num_copied, num_relays);\n"
                "\n"
                "    const uint32_t num_connected = tcp_connected_relays_count(chat->tcp_conn);\n"
                "    uint32_t num_added = 0;\n"
                "\n"
                "    for (uint32_t i = 0; i < num_copied && num_connected + num_added < GC_MIN_CONNECTED_TCP_RELAYS; ++i) {\n"
                "        if (add_tcp_relay_global(chat->tcp_conn, &tcp_relays[i].ip_port, tcp_relays[i].public_key) == 0) {\n"
                "            ++num_added;\n"
                "        }\n"
                "    }\n"
                "\n"
                "    if (num_added > 0) {\n"
                "        LOGGER_INFO(chat->log, \"KHANDAQ F9: group %d joined %u relay(s) under its key (%u connected before)\",\n"
                "                    chat->group_number, num_added, num_connected);\n"
                "    }\n"
                "\n"
                "    free(tcp_relays);\n"
                "}\n")

# --- group_chats.c: the relays of an invite confirm ---
ORIG_CONFIRM_RELAYS = ("    Node_format tcp_relays[GCC_MAX_TCP_SHARED_RELAYS];\n"
                       "    const uint32_t num_tcp_relays = tcp_copy_connected_relays(chat->tcp_conn, tcp_relays, GCC_MAX_TCP_SHARED_RELAYS);\n"
                       "\n"
                       "    const bool copy_ip_port_result = copy_friend_ip_port_to_gconn(m, friend_number, gconn);\n")

REPL_CONFIRM_RELAYS = ("    Node_format tcp_relays[GCC_MAX_TCP_SHARED_RELAYS];\n"
                       "    // KHANDAQ (F9, group TCP relays): not only the connected group relays, see copy_gc_shareable_tcp_relays().\n"
                       "    const uint32_t num_tcp_relays = copy_gc_shareable_tcp_relays(c, chat, tcp_relays, GCC_MAX_TCP_SHARED_RELAYS);\n"
                       "\n"
                       "    const bool copy_ip_port_result = copy_friend_ip_port_to_gconn(m, friend_number, gconn);\n"
                       "\n"
                       "    LOGGER_INFO(chat->log, \"KHANDAQ F9: invite confirm to friend %d: %u TCP relay(s) (%u connected in group %d), \"\n"
                       "                \"friend address %s\", friend_number, num_tcp_relays, tcp_connected_relays_count(chat->tcp_conn),\n"
                       "                chat->group_number, copy_ip_port_result ? \"known\" : \"unknown\");\n")

# --- group_chats.c: the length of the join data ---
ORIG_CONFIRM_LENGTH = ("        len += nodes_len;\n"
                       "    }\n"
                       "\n"
                       "    return send_gc_invite_confirmed_packet(m, chat, friend_number, out_data, len);\n")

REPL_CONFIRM_LENGTH = ("        if (nodes_len > 0) {  // KHANDAQ (F9, group TCP relays): a failed pack must not cut the join data short\n"
                       "            len += nodes_len;\n"
                       "        }\n"
                       "    }\n"
                       "\n"
                       "    return send_gc_invite_confirmed_packet(m, chat, friend_number, out_data, len);\n")

# --- TCP_connection.c: the sleepers a waking connection takes back ---
ORIG_WAKE = ("                if (tcp_con->status == TCP_CONN_SLEEPING) {\n"
             "                    tcp_con->unsleep = 1;\n"
             "                }\n"
             "            }\n"
             "        }\n"
             "\n"
             "        con_to->status = TCP_CONN_VALID;\n")

REPL_WAKE = ("                if (tcp_con->status == TCP_CONN_SLEEPING) {\n"
             "                    tcp_con->unsleep = 1;\n"
             "                }\n"
             "\n"
             "                /* KHANDAQ (F9, group TCP relays), as TokTok PR #3081: going to sleep counted this\n"
             "                 * connection's online slots as sleepers on the relay; waking must take them back, or every\n"
             "                 * sleep-wake-sleep cycle leaves a phantom sleeper behind, `lock_count == sleep_count` holds\n"
             "                 * while an awake connection is still online on the relay, and do_tcp_conns() puts the relay\n"
             "                 * to sleep under it. */\n"
             "                if (con_to->connections[i].status == TCP_CONNECTIONS_STATUS_ONLINE && tcp_con->sleep_count > 0) {\n"
             "                    --tcp_con->sleep_count;\n"
             "                }\n"
             "            }\n"
             "        }\n"
             "\n"
             "        con_to->status = TCP_CONN_VALID;\n")

PATCHES = (
    ("group_chats.c", ((ORIG_CONSTANTS, REPL_CONSTANTS), (ORIG_DO_GC_TCP, REPL_DO_GC_TCP), (ORIG_SEEDING, REPL_SEEDING), (ORIG_CONFIRM_RELAYS, REPL_CONFIRM_RELAYS), (ORIG_CONFIRM_LENGTH, REPL_CONFIRM_LENGTH),)),
    ("TCP_connection.c", ((ORIG_WAKE, REPL_WAKE),)),
)


def main(src_dir):
    for name, edits in PATCHES:
        path = os.path.join(src_dir, name)
        src = open(path, "r", encoding="utf-8", errors="surrogateescape").read()

        if MARKER in src:
            print("apply_khandaq_gc_tcp_relays: " + path + " already patched")
            continue

        for orig, repl in edits:
            count = src.count(orig)
            if count != 1:
                sys.stderr.write("apply_khandaq_gc_tcp_relays: expected exactly one target in " + path + ", found "
                                 + str(count) + " -- toxcore may have changed; aborting so the build does not ship"
                                 " groups whose members behind a NAT never meet.\n")
                return 1
            src = src.replace(orig, repl, 1)

        open(path, "w", encoding="utf-8", errors="surrogateescape").write(src)
        print("apply_khandaq_gc_tcp_relays: patched " + path + " OK")

    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "toxcore"))
