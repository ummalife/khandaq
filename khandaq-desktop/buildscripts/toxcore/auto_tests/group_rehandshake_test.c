/* KHANDAQ (0.2.59, group files): a peer that comes back through a new handshake gets what is sent to it after.
 *
 * QA live of 0.2.59: an iPhone sent a 6 MiB file to a group while the network of a phone went away for 10 s and came
 * back with another address. The phone timed the iPhone out a minute later and came back through a new handshake,
 * which the iPhone, still holding the phone, took on the connection it had. The next file reached the phone only up to
 * chunk 90 of 171, with no cut at all. A handshake starts the message ids again at 2 and 3, and the connection kept
 * what the old ids had left in its arrays: a packet whose slot an old one still held was not queued for that peer
 * ("Send array entry isn't empty"), while the same group packet went to the other members, and an old packet kept in
 * the receive array would be handed on as the new message of its id.
 *
 * Two toxes in one group, direct over UDP; every group datagram goes through a Network that can drop the ones tox 0
 * sends:
 * 1. tox 1 hears nothing of tox 0 while tox 0 sends 40 packets of 37000 bytes, which tox 0 then holds for tox 1;
 * 2. tox 1 times tox 0 out, as it does after a minute without a packet of it, and comes back through a new handshake,
 *    which tox 0 takes on the connection it still has;
 * 3. tox 0 sends 200 packets of 37000 bytes, one per iteration: all arrive, whole and in order, and tox 0 holds
 *    nothing for tox 1 after them.
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
    uint32_t peer_exits;

    /* What tox 1 got of the packets of the running phase: each starts with its sequence number. */
    size_t expected_length;
    uint16_t next_sequence;
    uint32_t packets_received;
    uint32_t packets_out_of_order;
    uint32_t packets_damaged;
} State;

#include "auto_test_support.h"

#define GROUP_NAME "rehandshake"
#define NICK "peer"

#define BIG_PACKET_SIZE 37000
#define HELD_PACKETS 40
#define AFTER_PACKETS 200

/* Iterations (200 ms of tox time each). */
#define MAX_ITERATIONS 20000
#define CATCH_UP_ITERATIONS 250
#define SETTLE_ITERATIONS 10

/* The group datagrams of tox 0, seen in the Network's sendto. */
typedef struct Filter {
    uint8_t sender_key[ENC_PUBLIC_KEY_SIZE];
    bool key_set;
    bool cut;        /* every group datagram of tox 0 is dropped */
    uint32_t dropped;
} Filter;

static Filter filter;
static Network_Funcs filtering_funcs;
static Network filtering_network;
static Tox_System filtering_system;

static int filtering_sendto(void *obj, int sock, const uint8_t *buf, size_t len, const Network_Addr *addr)
{
    Filter *f = (Filter *)obj;

    if (f->cut && f->key_set && len > 1 + ENC_PUBLIC_KEY_SIZE
            && (buf[0] == NET_PACKET_GC_HANDSHAKE || buf[0] == NET_PACKET_GC_LOSSLESS || buf[0] == NET_PACKET_GC_LOSSY)
            && memcmp(buf + 1, f->sender_key, ENC_PUBLIC_KEY_SIZE) == 0) {
        ++f->dropped;
        /* As if it went out and was lost on the way. */
        return (int)len;
    }

    const Network *ns = system_network();
    return ns->funcs->sendto(ns->obj, sock, buf, len, addr);
}

#ifndef VANILLA_NACL
static uint8_t payload_byte(uint16_t sequence, size_t position)
{
    return (uint8_t)(sequence ^ (position % 251));
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

static void group_peer_exit_handler(Tox *tox, uint32_t group_number, uint32_t peer_id,
                                    Tox_Group_Exit_Type exit_type, const uint8_t *name, size_t name_length,
                                    const uint8_t *part_message, size_t length, void *user_data)
{
    AutoTox *autotox = (AutoTox *)user_data;
    State *state = (State *)autotox->state;

    ++state->peer_exits;
    printf("   tox %u: peer %u left the group (exit type %d)\n", autotox->index, peer_id, exit_type);
}

static void group_custom_packet_handler(Tox *tox, uint32_t group_number, uint32_t peer_id, const uint8_t *data,
                                        size_t length, void *user_data)
{
    AutoTox *autotox = (AutoTox *)user_data;
    State *state = (State *)autotox->state;

    if (state->expected_length == 0) {
        return;  /* a packet of phase 1, which tox 1 never hears of */
    }

    ++state->packets_received;

    if (length != state->expected_length || length < 2) {
        ++state->packets_damaged;
        return;
    }

    const uint16_t sequence = (uint16_t)((data[0] << 8) | data[1]);
    bool intact = true;

    for (size_t i = 2; i < length && intact; ++i) {
        intact = data[i] == payload_byte(sequence, i);
    }

    if (!intact) {
        ++state->packets_damaged;
    }

    if (sequence != state->next_sequence) {
        ++state->packets_out_of_order;
    }

    state->next_sequence = sequence + 1;
}

static void send_group_packet(AutoTox *autotoxes, uint16_t sequence)
{
    const State *state0 = (const State *)autotoxes[0].state;
    uint8_t *payload = (uint8_t *)malloc(BIG_PACKET_SIZE);
    ck_assert(payload != nullptr);

    payload[0] = (uint8_t)(sequence >> 8);
    payload[1] = (uint8_t)(sequence & 0xff);

    for (size_t i = 2; i < BIG_PACKET_SIZE; ++i) {
        payload[i] = payload_byte(sequence, i);
    }

    Tox_Err_Group_Send_Custom_Packet err;
    tox_group_send_custom_packet(autotoxes[0].tox, state0->group_number, true, payload, BIG_PACKET_SIZE, &err);
    ck_assert_msg(err == TOX_ERR_GROUP_SEND_CUSTOM_PACKET_OK, "tox_group_send_custom_packet failed: error %d", err);
    free(payload);
}

/** A tox's connection to the other one, peer 1 of its group of two; null while the other one is not in it. */
static GC_Connection *connection_to_other(AutoTox *autotoxes, uint32_t index)
{
    const State *state = (const State *)autotoxes[index].state;
    const GC_Chat *chat = gc_get_group(autotoxes[index].tox->m->group_handler, state->group_number);
    ck_assert(chat != nullptr);

    if (chat->numpeers != 2) {
        return nullptr;
    }

    GC_Connection *gconn = get_gc_connection(chat, 1);

    if (gconn == nullptr || gconn->pending_delete) {
        return nullptr;
    }

    return gconn;
}

/** The packets tox 0 holds for tox 1 anywhere in its send array, whatever their ids. */
static uint32_t held_for_tox1(AutoTox *autotoxes)
{
    const GC_Connection *gconn = connection_to_other(autotoxes, 0);
    ck_assert_msg(gconn != nullptr, "tox 1 is no longer in tox 0's group");

    uint32_t held = 0;

    for (uint32_t i = 0; i < GCC_BUFFER_SIZE; ++i) {
        if (gconn->send_array[i].time_added != 0) {
            ++held;
        }
    }

    return held;
}

static bool connected_over_udp(AutoTox *autotoxes)
{
    const State *state0 = (const State *)autotoxes[0].state;
    const State *state1 = (const State *)autotoxes[1].state;
    const GC_Connection *gconn0 = connection_to_other(autotoxes, 0);
    const GC_Connection *gconn1 = connection_to_other(autotoxes, 1);

    if (gconn0 == nullptr || gconn1 == nullptr || !gconn0->handshaked || !gconn1->handshaked || !gconn0->confirmed
            || !gconn1->confirmed) {
        return false;
    }

    return tox_group_peer_get_connection_status(autotoxes[0].tox, state0->group_number, state0->peer_id, nullptr)
           == TOX_CONNECTION_UDP
           && tox_group_peer_get_connection_status(autotoxes[1].tox, state1->group_number, state1->peer_id, nullptr)
           == TOX_CONNECTION_UDP;
}

/** Tox 0 keeps tox 1 throughout: before the fix, what the old handshake left in its send array kept the packets of
 * the new one out ("Failed to add payload to send array"), and tox 0 timed tox 1 out a little later. */
static void tox0_holds_tox1(AutoTox *autotoxes, const char *phase)
{
    const State *state0 = (const State *)autotoxes[0].state;
    ck_assert_msg(state0->peer_exits == 0 && connection_to_other(autotoxes, 0) != nullptr,
                  "%s: tox 0 lost tox 1 (exits seen by tox 0: %u)", phase, state0->peer_exits);
}
#endif  // VANILLA_NACL

static void group_rehandshake_test(AutoTox *autotoxes)
{
#ifndef VANILLA_NACL
    State *state0 = (State *)autotoxes[0].state;
    State *state1 = (State *)autotoxes[1].state;

    for (uint32_t i = 0; i < 2; ++i) {
        tox_callback_group_peer_join(autotoxes[i].tox, group_peer_join_handler);
        tox_callback_group_peer_exit(autotoxes[i].tox, group_peer_exit_handler);
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

    for (uint32_t i = 0; !(state0->peer_joined && state1->peer_joined && connected_over_udp(autotoxes)); ++i) {
        ck_assert_msg(i < MAX_ITERATIONS, "the toxes never met in the group over UDP");
        iterate_all_wait(autotoxes, 2, ITERATION_INTERVAL);
    }

    const GC_Chat *chat0 = gc_get_group(autotoxes[0].tox->m->group_handler, state0->group_number);
    ck_assert(chat0 != nullptr);
    memcpy(filter.sender_key, chat0->self_public_key, ENC_PUBLIC_KEY_SIZE);
    filter.key_set = true;

    printf("1. tox 1 hears nothing of tox 0 while tox 0 sends %u packets of %u bytes\n", HELD_PACKETS,
           BIG_PACKET_SIZE);
    filter.cut = true;

    for (uint16_t i = 0; i < HELD_PACKETS; ++i) {
        send_group_packet(autotoxes, i);
        iterate_all_wait(autotoxes, 2, ITERATION_INTERVAL);
    }

    const uint32_t held_before = held_for_tox1(autotoxes);
    printf("   tox 0 holds %u packets for tox 1 (%u datagrams of tox 0 lost)\n", held_before, filter.dropped);
    ck_assert_msg(held_before > 0, "1: tox 0 holds nothing for tox 1");
    ck_assert_msg(state1->packets_received == 0, "1: tox 1 got %u packets", state1->packets_received);

    printf("2. tox 1 times tox 0 out and comes back through a new handshake\n");
    {
        GC_Chat *chat1 = gc_get_group(autotoxes[1].tox->m->group_handler, state1->group_number);
        ck_assert(chat1 != nullptr);
        GC_Connection *gconn1 = connection_to_other(autotoxes, 1);
        ck_assert(gconn1 != nullptr);
        /* What do_peer_connections() does once it heard nothing of a peer for GC_CONFIRMED_PEER_TIMEOUT seconds. */
        gcc_mark_for_deletion(gconn1, chat1->tcp_conn, GC_EXIT_TYPE_TIMEOUT, nullptr, 0);
    }

    filter.cut = false;
    state1->peer_joined = false;

    for (uint32_t i = 0; !(state1->peer_exits > 0 && state1->peer_joined && connected_over_udp(autotoxes)); ++i) {
        ck_assert_msg(i < MAX_ITERATIONS, "2: tox 1 never came back (exits seen by tox 1: %u, joined: %d)",
                      state1->peer_exits, state1->peer_joined);
        iterate_all_wait(autotoxes, 2, ITERATION_INTERVAL);
        tox0_holds_tox1(autotoxes, "2");
    }

    printf("   back; tox 0 holds %u packets for tox 1\n", held_for_tox1(autotoxes));

    printf("3. %u packets of %u bytes, one per %u ms\n", AFTER_PACKETS, BIG_PACKET_SIZE, ITERATION_INTERVAL);
    state1->expected_length = BIG_PACKET_SIZE;
    state1->next_sequence = 0;
    state1->packets_received = 0;
    state1->packets_out_of_order = 0;
    state1->packets_damaged = 0;

    uint32_t i = 0;

    for (;; ++i) {
        if (i < AFTER_PACKETS) {
            send_group_packet(autotoxes, (uint16_t)i);
        }

        iterate_all_wait(autotoxes, 2, ITERATION_INTERVAL);
        tox0_holds_tox1(autotoxes, "3");

        if (i >= AFTER_PACKETS && state1->packets_received >= AFTER_PACKETS) {
            break;
        }

        ck_assert_msg(i < AFTER_PACKETS + CATCH_UP_ITERATIONS,
                      "3: %u s after the last packet went out, tox 1 got %u packets of %u",
                      CATCH_UP_ITERATIONS * ITERATION_INTERVAL / 1000, state1->packets_received, AFTER_PACKETS);
    }

    printf("   all %u packets %.1f s after the last went out\n", AFTER_PACKETS,
           (double)(i + 1 - AFTER_PACKETS) * ITERATION_INTERVAL / 1000);

    ck_assert_msg(state1->packets_received == AFTER_PACKETS && state1->packets_out_of_order == 0
                  && state1->packets_damaged == 0,
                  "3: tox 1 got %u packets of %u, %u out of order, %u damaged", state1->packets_received, AFTER_PACKETS,
                  state1->packets_out_of_order, state1->packets_damaged);

    for (uint32_t j = 0; j < SETTLE_ITERATIONS; ++j) {
        iterate_all_wait(autotoxes, 2, ITERATION_INTERVAL);
    }

    const uint32_t held_after = held_for_tox1(autotoxes);
    ck_assert_msg(held_after == 0, "3: %u s after the last packet came, tox 0 still holds %u packets for tox 1",
                  SETTLE_ITERATIONS * ITERATION_INTERVAL / 1000, held_after);
    tox0_holds_tox1(autotoxes, "3");
#endif  // VANILLA_NACL
}

int main(void)
{
    setvbuf(stdout, nullptr, _IONBF, 0);

    const Network *system_ns = system_network();
    ck_assert(system_ns != nullptr);
    filtering_funcs = *system_ns->funcs;
    filtering_funcs.sendto = filtering_sendto;
    filtering_network.funcs = &filtering_funcs;
    filtering_network.obj = &filter;
    filtering_system = tox_default_system();
    filtering_system.ns = &filtering_network;

    struct Tox_Options *options = tox_options_new(nullptr);
    ck_assert(options != nullptr);
    tox_options_set_operating_system(options, &filtering_system);

    Run_Auto_Options autotest_opts = default_run_auto_options();
    autotest_opts.graph = GRAPH_COMPLETE;

    run_auto_test(options, 2, group_rehandshake_test, sizeof(State), &autotest_opts);

    tox_options_free(options);

    return 0;
}
