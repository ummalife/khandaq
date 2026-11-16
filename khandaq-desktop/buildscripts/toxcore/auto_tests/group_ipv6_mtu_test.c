/* KHANDAQ (gc IPv6 MTU): every group packet we send fits the IPv6 minimum MTU, and the chunks a peer before this
 * change sends are still accepted.
 *
 * A full group chunk was 1372 bytes, sent as a 1454 byte UDP payload: a 1502 byte IPv6 packet, over a 1500 byte
 * MTU, so a direct IPv6 path split it into IP fragments, which were lost. MAX_GC_PACKET_CHUNK_SIZE, what we send,
 * is now 1150 (at most 1232 bytes of UDP payload); MAX_GC_PACKET_INCOMING_CHUNK_SIZE, what we accept, stays 1372.
 *
 * Two toxes in one group, connected over UDP. Every datagram they send goes through a Network that wraps the
 * system one and measures the group packets among them:
 * 1. each sends the group a 37000 byte lossless custom packet: both arrive whole, no group datagram either tox
 *    sent from its start was over 1232 bytes, and the full chunks went out at the largest size we send;
 * 2. tox 0 sends tox 1 a 37000 byte custom packet in chunks of 1372 bytes, wrapped as a peer before this change
 *    wraps them, in 1454 byte datagrams: it arrives whole;
 * 3. the group goes on after it: tox 0 sends another 37000 byte packet the usual way, and it arrives whole.
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "../testing/misc_tools.h"
#include "../toxcore/ccompat.h"
#include "../toxcore/crypto_core.h"
#include "../toxcore/group_chats.h"
#include "../toxcore/group_common.h"
#include "../toxcore/group_connection.h"
#include "../toxcore/Messenger.h"
#include "../toxcore/network.h"
#include "../toxcore/tox.h"
#include "../toxcore/tox_private.h"
#include "../toxcore/tox_struct.h"
#include "../toxcore/util.h"
#include "check_compat.h"

typedef struct State {
    uint32_t group_number;
    uint32_t peer_id;
    bool peer_joined;

    uint32_t packets_received;
    uint8_t last_tag;
    size_t last_length;
    bool last_intact;
} State;

#include "auto_test_support.h"

#define GROUP_NAME "ipv6 mtu"
#define NICK "peer"

#define PACKET_SIZE 37000
/* Iterations to meet in the group and go direct, and to get a packet across (an iteration is 200 ms of tox time). */
#define MAX_ITERATIONS 20000
#define MAX_PACKET_ITERATIONS 2000

/* A lossless group packet on the wire: net packet type, sender key, nonce, then encrypted the group packet type,
 * the message id and the data, and the MAC. A full chunk has no padding. */
#define WRAPPED_LOSSLESS_OVERHEAD (1 + ENC_PUBLIC_KEY_SIZE + CRYPTO_NONCE_SIZE + 1 + sizeof(uint64_t) + CRYPTO_MAC_SIZE)
/* The IPv6 minimum MTU less the IPv6 (40) and UDP (8) headers. */
#define MAX_SENT_DATAGRAM (1280 - 40 - 8)
/* What a peer before this change sends: chunks of 1372 bytes, padded up to that size. */
#define OLD_CHUNK_SIZE 1372
#define OLD_PADDING 8

/* The group datagrams the two toxes sent, seen in their Network's sendto. */
typedef struct Sent_Sizes {
    uint32_t datagrams;
    size_t largest;
    uint32_t full_chunks;      /* of a full chunk of MAX_GC_PACKET_CHUNK_SIZE */
    uint32_t old_full_chunks;  /* of a full chunk of OLD_CHUNK_SIZE */

    /* The other datagrams (DHT, onion, friend connections), which this change does not size: shown, not checked. */
    size_t largest_other;
    uint8_t largest_other_type;
} Sent_Sizes;

static Sent_Sizes sent_sizes;
static Network_Funcs measuring_funcs;
static Network measuring_network;
static Tox_System measuring_system;

static int measuring_sendto(void *obj, int sock, const uint8_t *buf, size_t len, const Network_Addr *addr)
{
    Sent_Sizes *sizes = (Sent_Sizes *)obj;

    if (len > 0 && (buf[0] == NET_PACKET_GC_HANDSHAKE || buf[0] == NET_PACKET_GC_LOSSLESS
                    || buf[0] == NET_PACKET_GC_LOSSY)) {
        ++sizes->datagrams;

        if (len > sizes->largest) {
            sizes->largest = len;
        }

        if (len == MAX_GC_PACKET_CHUNK_SIZE + WRAPPED_LOSSLESS_OVERHEAD) {
            ++sizes->full_chunks;
        }

        if (len == OLD_CHUNK_SIZE + WRAPPED_LOSSLESS_OVERHEAD) {
            ++sizes->old_full_chunks;
        }
    } else if (len > sizes->largest_other) {
        sizes->largest_other = len;
        sizes->largest_other_type = buf[0];
    }

    const Network *ns = system_network();
    return ns->funcs->sendto(ns->obj, sock, buf, len, addr);
}

#ifndef VANILLA_NACL
static uint8_t payload_byte(uint8_t tag, size_t position)
{
    return (uint8_t)(tag ^ (position % 251));
}

static uint8_t *new_payload(uint8_t tag)
{
    uint8_t *payload = (uint8_t *)malloc(PACKET_SIZE);
    ck_assert(payload != nullptr);
    payload[0] = tag;

    for (size_t i = 1; i < PACKET_SIZE; ++i) {
        payload[i] = payload_byte(tag, i);
    }

    return payload;
}

static void group_invite_handler(Tox *tox, uint32_t friend_number, const uint8_t *invite_data, size_t length,
                                 const uint8_t *group_name, size_t group_name_length, void *user_data)
{
    AutoTox *autotox = (AutoTox *)user_data;
    State *state = (State *)autotox->state;

    Tox_Err_Group_Invite_Accept err;
    state->group_number = tox_group_invite_accept(tox, friend_number, invite_data, length, (const uint8_t *)NICK,
                          sizeof(NICK) - 1, nullptr, 0, &err);
    ck_assert_msg(err == TOX_ERR_GROUP_INVITE_ACCEPT_OK, "tox_group_invite_accept failed: error %d", err);
}

static void group_peer_join_handler(Tox *tox, uint32_t group_number, uint32_t peer_id, void *user_data)
{
    AutoTox *autotox = (AutoTox *)user_data;
    State *state = (State *)autotox->state;

    state->peer_id = peer_id;
    state->peer_joined = true;
}

static void group_custom_packet_handler(Tox *tox, uint32_t group_number, uint32_t peer_id, const uint8_t *data,
                                        size_t length, void *user_data)
{
    AutoTox *autotox = (AutoTox *)user_data;
    State *state = (State *)autotox->state;

    ck_assert(length > 0);

    bool intact = true;

    for (size_t i = 1; i < length && intact; ++i) {
        intact = data[i] == payload_byte(data[0], i);
    }

    state->last_tag = data[0];
    state->last_length = length;
    state->last_intact = intact;
    ++state->packets_received;
}

static void wait_for_packet(AutoTox *autotoxes, uint32_t receiver, uint32_t received_before, uint8_t tag)
{
    const State *state = (const State *)autotoxes[receiver].state;

    for (uint32_t i = 0; state->packets_received == received_before; ++i) {
        ck_assert_msg(i < MAX_PACKET_ITERATIONS, "tox %u never got the packet tagged %u", receiver, tag);
        iterate_all_wait(autotoxes, 2, ITERATION_INTERVAL);
    }

    ck_assert_msg(state->packets_received == received_before + 1, "tox %u got %u packets, expected one",
                  receiver, state->packets_received - received_before);
    ck_assert_msg(state->last_tag == tag && state->last_length == PACKET_SIZE && state->last_intact,
                  "tox %u got a packet tagged %u of %u bytes (intact: %d), expected one tagged %u of %u bytes",
                  receiver, state->last_tag, (unsigned)state->last_length, state->last_intact, tag, PACKET_SIZE);
}

static void send_and_receive(AutoTox *autotoxes, uint32_t sender, uint32_t receiver, uint8_t tag)
{
    const State *sender_state = (const State *)autotoxes[sender].state;
    const State *receiver_state = (const State *)autotoxes[receiver].state;
    const uint32_t received_before = receiver_state->packets_received;

    uint8_t *payload = new_payload(tag);
    Tox_Err_Group_Send_Custom_Packet err;
    tox_group_send_custom_packet(autotoxes[sender].tox, sender_state->group_number, true, payload, PACKET_SIZE, &err);
    ck_assert_msg(err == TOX_ERR_GROUP_SEND_CUSTOM_PACKET_OK, "tox_group_send_custom_packet failed: error %d", err);
    free(payload);

    wait_for_packet(autotoxes, receiver, received_before, tag);
}

static void check_sent_sizes(const char *phase)
{
    ck_assert_msg(sent_sizes.largest <= MAX_SENT_DATAGRAM,
                  "%s: a group datagram of %u bytes went out, over the %u the IPv6 minimum MTU leaves",
                  phase, (unsigned)sent_sizes.largest, MAX_SENT_DATAGRAM);
    ck_assert_msg(sent_sizes.full_chunks > 0, "%s: no full chunk went out over UDP (%u group datagrams)", phase,
                  sent_sizes.datagrams);
    printf("   %u group datagrams, the largest %u bytes, %u full chunks of %u; the largest other datagram %u bytes "
           "(packet 0x%02x)\n", sent_sizes.datagrams, (unsigned)sent_sizes.largest, sent_sizes.full_chunks,
           (unsigned)(MAX_GC_PACKET_CHUNK_SIZE + WRAPPED_LOSSLESS_OVERHEAD), (unsigned)sent_sizes.largest_other,
           sent_sizes.largest_other_type);
}

/** Wraps a fragment as group_packet_wrap() of a peer before this change does, and sends it to the peer. */
static void send_old_fragment(const GC_Chat *chat, GC_Connection *gconn, uint64_t message_id, const uint8_t *chunk,
                              uint16_t chunk_length)
{
    uint8_t plain[OLD_PADDING + 1 + sizeof(uint64_t) + OLD_CHUNK_SIZE];
    const uint16_t padding = (OLD_CHUNK_SIZE - chunk_length) % OLD_PADDING;

    memset(plain, 0, padding);
    plain[padding] = GP_FRAGMENT;
    net_pack_u64(plain + padding + 1, message_id);

    if (chunk_length > 0) {
        memcpy(plain + padding + 1 + sizeof(uint64_t), chunk, chunk_length);
    }

    const uint16_t plain_length = padding + 1 + sizeof(uint64_t) + chunk_length;

    uint8_t packet[1 + ENC_PUBLIC_KEY_SIZE + CRYPTO_NONCE_SIZE + sizeof(plain) + CRYPTO_MAC_SIZE];
    uint8_t *nonce = packet + 1 + ENC_PUBLIC_KEY_SIZE;

    packet[0] = NET_PACKET_GC_LOSSLESS;
    memcpy(packet + 1, chat->self_public_key, ENC_PUBLIC_KEY_SIZE);
    random_nonce(system_random(), nonce);

    const int32_t encrypted = encrypt_data_symmetric(gconn->session_shared_key, nonce, plain, plain_length,
                              nonce + CRYPTO_NONCE_SIZE);
    ck_assert(encrypted == plain_length + CRYPTO_MAC_SIZE);

    const uint16_t packet_length = (uint16_t)(1 + ENC_PUBLIC_KEY_SIZE + CRYPTO_NONCE_SIZE + encrypted);
    ck_assert(gcc_send_packet(chat, gconn, packet, packet_length));
}

/** Returns the connection of a tox to the other one; in a group of two the other one is peer 1, the tox peer 0. */
static GC_Connection *connection_to_other(AutoTox *autotoxes, uint32_t index, const GC_Chat **chat)
{
    const State *state = (const State *)autotoxes[index].state;
    *chat = gc_get_group(autotoxes[index].tox->m->group_handler, state->group_number);
    ck_assert(*chat != nullptr);
    ck_assert_msg((*chat)->numpeers == 2, "tox %u has %u peers in the group", index, (*chat)->numpeers);

    GC_Connection *gconn = get_gc_connection(*chat, 1);
    ck_assert(gconn != nullptr);
    return gconn;
}

/** Sends tox 1 a custom packet from tox 0 in the chunks a peer before this change sends. */
static void send_like_a_peer_before_the_change(AutoTox *autotoxes, uint8_t tag)
{
    const GC_Chat *chat;
    const GC_Chat *receiver_chat;
    GC_Connection *gconn = connection_to_other(autotoxes, 0, &chat);
    const GC_Connection *receiver_gconn = connection_to_other(autotoxes, 1, &receiver_chat);

    /* The message ids taken here must be the next ones tox 1 expects, and no resend of an older packet of tox 0 may
     * come after them: nothing of tox 0 waits for an ack, and tox 1 took all of it. */
    for (uint32_t i = 0; gconn->send_array_start != gcc_get_array_index(gconn->send_message_id)
            || receiver_gconn->received_message_id + 1 != gconn->send_message_id; ++i) {
        ck_assert_msg(i < MAX_PACKET_ITERATIONS, "tox 0 kept packets for tox 1 in flight");
        iterate_all_wait(autotoxes, 2, ITERATION_INTERVAL);
        gconn = connection_to_other(autotoxes, 0, &chat);
        receiver_gconn = connection_to_other(autotoxes, 1, &receiver_chat);
    }

    uint8_t *payload = new_payload(tag);
    uint8_t chunk[OLD_CHUNK_SIZE];
    uint64_t message_id = gconn->send_message_id;

    /* As gcc_send_lossless_packet_fragments() of a peer before this change splits it: the group packet type and
     * the first bytes, then chunks of OLD_CHUNK_SIZE, then an empty fragment that ends the packet. */
    chunk[0] = GP_CUSTOM_PACKET;
    memcpy(chunk + 1, payload, OLD_CHUNK_SIZE - 1);
    send_old_fragment(chat, gconn, message_id, chunk, OLD_CHUNK_SIZE);
    ++message_id;

    for (uint32_t processed = OLD_CHUNK_SIZE - 1; processed < PACKET_SIZE;) {
        const uint16_t chunk_length = (uint16_t)min_u32(OLD_CHUNK_SIZE, PACKET_SIZE - processed);
        send_old_fragment(chat, gconn, message_id, payload + processed, chunk_length);
        ++message_id;
        processed += chunk_length;
    }

    send_old_fragment(chat, gconn, message_id, nullptr, 0);
    ++message_id;

    /* tox 0's own packets go on after the ones taken here; none of them is in its send array. */
    gcc_set_send_message_id(gconn, message_id);

    free(payload);
}
#endif  // VANILLA_NACL

static void group_ipv6_mtu_test(AutoTox *autotoxes)
{
#ifndef VANILLA_NACL
    State *state0 = (State *)autotoxes[0].state;
    State *state1 = (State *)autotoxes[1].state;

    for (uint32_t i = 0; i < 2; ++i) {
        tox_callback_group_peer_join(autotoxes[i].tox, group_peer_join_handler);
        tox_callback_group_custom_packet(autotoxes[i].tox, group_custom_packet_handler);
    }

    tox_callback_group_invite(autotoxes[1].tox, group_invite_handler);

    Tox_Err_Group_New err_new;
    state0->group_number = tox_group_new(autotoxes[0].tox, TOX_GROUP_PRIVACY_STATE_PRIVATE,
                                         (const uint8_t *)GROUP_NAME, sizeof(GROUP_NAME) - 1, (const uint8_t *)NICK,
                                         sizeof(NICK) - 1, &err_new);
    ck_assert_msg(err_new == TOX_ERR_GROUP_NEW_OK, "tox_group_new failed: error %d", err_new);

    Tox_Err_Group_Invite_Friend err_invite;
    tox_group_invite_friend(autotoxes[0].tox, state0->group_number, 0, &err_invite);
    ck_assert_msg(err_invite == TOX_ERR_GROUP_INVITE_FRIEND_OK, "tox_group_invite_friend failed: error %d",
                  err_invite);

    for (uint32_t i = 0; !(state0->peer_joined && state1->peer_joined); ++i) {
        ck_assert_msg(i < MAX_ITERATIONS, "the toxes never met in the group");
        iterate_all_wait(autotoxes, 2, ITERATION_INTERVAL);
    }

    for (uint32_t i = 0;; ++i) {
        const Tox_Connection conn0 = tox_group_peer_get_connection_status(autotoxes[0].tox, state0->group_number,
                                     state0->peer_id, nullptr);
        const Tox_Connection conn1 = tox_group_peer_get_connection_status(autotoxes[1].tox, state1->group_number,
                                     state1->peer_id, nullptr);

        if (conn0 == TOX_CONNECTION_UDP && conn1 == TOX_CONNECTION_UDP) {
            break;
        }

        ck_assert_msg(i < MAX_ITERATIONS, "the group connection never went direct over UDP (%d, %d)", conn0, conn1);
        iterate_all_wait(autotoxes, 2, ITERATION_INTERVAL);
    }

    printf("1. a %u byte packet each way, in chunks of at most %u bytes\n", PACKET_SIZE,
           (unsigned)MAX_GC_PACKET_CHUNK_SIZE);
    send_and_receive(autotoxes, 0, 1, 1);
    send_and_receive(autotoxes, 1, 0, 2);
    check_sent_sizes("1");

    printf("2. a %u byte packet in chunks of %u, as a peer before this change sends it\n", PACKET_SIZE,
           OLD_CHUNK_SIZE);
    memset(&sent_sizes, 0, sizeof(sent_sizes));
    const uint32_t received_before = state1->packets_received;
    send_like_a_peer_before_the_change(autotoxes, 3);
    ck_assert_msg(sent_sizes.old_full_chunks == (PACKET_SIZE + 1) / OLD_CHUNK_SIZE,
                  "%u datagrams of %u bytes went out over UDP, expected %u", sent_sizes.old_full_chunks,
                  (unsigned)(OLD_CHUNK_SIZE + WRAPPED_LOSSLESS_OVERHEAD), (PACKET_SIZE + 1) / OLD_CHUNK_SIZE);
    wait_for_packet(autotoxes, 1, received_before, 3);
    printf("   %u datagrams of %u bytes, all taken\n", sent_sizes.old_full_chunks,
           (unsigned)(OLD_CHUNK_SIZE + WRAPPED_LOSSLESS_OVERHEAD));

    printf("3. the group goes on after it\n");
    memset(&sent_sizes, 0, sizeof(sent_sizes));
    send_and_receive(autotoxes, 0, 1, 4);
    check_sent_sizes("3");
#endif  // VANILLA_NACL
}

int main(void)
{
    setvbuf(stdout, nullptr, _IONBF, 0);

    const Network *system_ns = system_network();
    ck_assert(system_ns != nullptr);
    measuring_funcs = *system_ns->funcs;
    measuring_funcs.sendto = measuring_sendto;
    measuring_network.funcs = &measuring_funcs;
    measuring_network.obj = &sent_sizes;
    measuring_system = tox_default_system();
    measuring_system.ns = &measuring_network;

    struct Tox_Options *options = tox_options_new(nullptr);
    ck_assert(options != nullptr);
    tox_options_set_operating_system(options, &measuring_system);

    Run_Auto_Options autotest_opts = default_run_auto_options();
    autotest_opts.graph = GRAPH_COMPLETE;

    run_auto_test(options, 2, group_ipv6_mtu_test, sizeof(State), &autotest_opts);

    tox_options_free(options);

    return 0;
}
