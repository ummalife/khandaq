/* KHANDAQ (0.2.59, group files): a peer behind a gap catches up, and what it already holds is not sent to it again.
 *
 * QA live 0.2.57 and 0.2.58: a 6 MiB file to a group (lossless custom packets of about 37000 bytes, each sent as
 * fragments) never reached a phone that lost its network for 10 s while the file went out: its sender timed it out
 * ("Send array stuck") about a minute later, after sending it 46.8 MB. On a lossy path 2 of 3 members failed at 6 MiB
 * with no cut at all. The receiving side was why:
 * - a fragment that came out of order was dropped, so after one loss every fragment behind it had to come again, at
 *   the resend times of 3, 5, 9, 17 and 33 s;
 * - a fragment taken in order was not acknowledged: the sender sent each one again about 3 s later, and only that
 *   duplicate was acknowledged;
 * - a packet kept for later (it came out of order) was handed on at one per tox_iterate, and acknowledged as message
 *   0 (process_recv_array_entry read the id of the entry it had just cleared): the sender kept it until a resend of it
 *   came back as a duplicate;
 * - a kept packet that came again in order was taken from the wire, and its copy stayed in the receive array, where
 *   8192 messages later it would block the message of its slot, or be handed on in its place.
 * The sending side keeps the change the Android build has carried since #201 (apply_khandaq_media_resend.py), which
 * the desktop and iOS copies now carry too: the oldest packet the peer has not acknowledged goes again every second,
 * the others at the resend times.
 *
 * Two toxes in one group, direct over UDP. Every group datagram goes through a Network that counts the lossless ones
 * tox 0 sends and drops the ones a case asks for. After each case tox 0 holds nothing for tox 1, and tox 1 keeps
 * nothing of tox 0.
 * 1. no loss: 20 packets of 37000 bytes arrive whole and in order, and tox 0 sends each fragment once;
 * 2. a 10 s cut of every group datagram, both ways, while 40 such packets go out one per iteration, then one lossless
 *    datagram of tox 0 in 20 lost: all arrive whole and in order, and tox 1 stays in the group;
 * 3. the first of 400 small packets is lost, and the others go out a second later: all arrive in order, tox 1 stays
 *    in the group, and tox 0 sends each of the others once.
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

    /* What tox 1 got of the packets of the running case: each starts with its sequence number. */
    size_t expected_length;
    uint16_t next_sequence;
    uint32_t packets_received;
    uint32_t packets_out_of_order;
    uint32_t packets_damaged;
} State;

#include "auto_test_support.h"

#define GROUP_NAME "recv gap"
#define NICK "peer"

#define BIG_PACKET_SIZE 37000
#define SMALL_PACKET_SIZE 400

#define NO_LOSS_PACKETS 20
#define CUT_PACKETS 40
#define SMALL_PACKETS 400

/* Iterations (200 ms of tox time each). */
#define MAX_ITERATIONS 20000
#define CUT_START 5
#define CUT_LENGTH 50
/* Below GC_CONFIRMED_PEER_TIMEOUT: tox 1 must have caught up before tox 0 could have timed it out. */
#define CATCH_UP_ITERATIONS 250
#define SETTLE_ITERATIONS 10
#define QUIET_ITERATIONS 50

#define LOSS_AFTER_CUT 20

/* The group datagrams of the two toxes, seen in their Network's sendto. */
typedef struct Filter {
    uint8_t counted_key[ENC_PUBLIC_KEY_SIZE];
    bool key_set;
    uint32_t lossless_sent;  /* lossless group datagrams from counted_key, the dropped ones too */
    uint32_t dropped;

    bool cut;                /* every group datagram is dropped, both ways */
    uint32_t drop_next;      /* this many of the next lossless datagrams from counted_key are dropped */
    uint32_t drop_one_in;    /* one lossless datagram from counted_key in this many is dropped */
} Filter;

static Filter filter;
static Network_Funcs filtering_funcs;
static Network filtering_network;
static Tox_System filtering_system;

static int filtering_sendto(void *obj, int sock, const uint8_t *buf, size_t len, const Network_Addr *addr)
{
    Filter *f = (Filter *)obj;

    if (len > 0 && (buf[0] == NET_PACKET_GC_HANDSHAKE || buf[0] == NET_PACKET_GC_LOSSLESS
                    || buf[0] == NET_PACKET_GC_LOSSY)) {
        bool drop = f->cut;

        if (f->key_set && buf[0] == NET_PACKET_GC_LOSSLESS && len > 1 + ENC_PUBLIC_KEY_SIZE
                && memcmp(buf + 1, f->counted_key, ENC_PUBLIC_KEY_SIZE) == 0) {
            ++f->lossless_sent;

            if (f->drop_next > 0) {
                --f->drop_next;
                drop = true;
            } else if (f->drop_one_in > 0 && f->lossless_sent % f->drop_one_in == 0) {
                drop = true;
            }
        }

        if (drop) {
            ++f->dropped;
            /* As if it went out and was lost on the way. */
            return (int)len;
        }
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

static void send_group_packet(AutoTox *autotoxes, uint16_t sequence, size_t length)
{
    const State *state0 = (const State *)autotoxes[0].state;
    uint8_t *payload = (uint8_t *)malloc(length);
    ck_assert(payload != nullptr);

    payload[0] = (uint8_t)(sequence >> 8);
    payload[1] = (uint8_t)(sequence & 0xff);

    for (size_t i = 2; i < length; ++i) {
        payload[i] = payload_byte(sequence, i);
    }

    Tox_Err_Group_Send_Custom_Packet err;
    tox_group_send_custom_packet(autotoxes[0].tox, state0->group_number, true, payload, length, &err);
    ck_assert_msg(err == TOX_ERR_GROUP_SEND_CUSTOM_PACKET_OK, "tox_group_send_custom_packet failed: error %d", err);
    free(payload);
}

/** How many lossless datagrams one packet of `length` bytes takes: gcc_send_lossless_packet_fragments() sends the
 * group packet type and the first bytes, then chunks of MAX_GC_PACKET_CHUNK_SIZE, then an empty fragment. */
static uint32_t datagrams_for(size_t length)
{
    if (length <= MAX_GC_PACKET_CHUNK_SIZE) {
        return 1;
    }

    const size_t rest = length - (MAX_GC_PACKET_CHUNK_SIZE - 1);
    return (uint32_t)(1 + (rest + MAX_GC_PACKET_CHUNK_SIZE - 1) / MAX_GC_PACKET_CHUNK_SIZE + 1);
}

/** A tox's connection to the other one, peer 1 of its group of two; null once the other one is no longer in it. */
static const GC_Connection *connection_to_other(AutoTox *autotoxes, uint32_t index)
{
    const State *state = (const State *)autotoxes[index].state;
    const GC_Chat *chat = gc_get_group(autotoxes[index].tox->m->group_handler, state->group_number);
    ck_assert(chat != nullptr);

    if (chat->numpeers != 2) {
        return nullptr;
    }

    const GC_Connection *gconn = get_gc_connection(chat, 1);

    if (gconn == nullptr || gconn->pending_delete) {
        return nullptr;
    }

    return gconn;
}

static const GC_Connection *connection_to_tox1(AutoTox *autotoxes)
{
    return connection_to_other(autotoxes, 0);
}

/** The packets of tox 0 that tox 1 keeps in its receive array: ones that came out of order, or copies left behind. */
static uint32_t kept_by_tox1(AutoTox *autotoxes)
{
    const GC_Connection *gconn = connection_to_other(autotoxes, 1);
    ck_assert_msg(gconn != nullptr, "tox 0 is no longer in tox 1's group");

    uint32_t kept = 0;

    for (uint32_t i = 0; i < GCC_BUFFER_SIZE; ++i) {
        if (gconn->recv_array[i].time_added != 0) {
            ++kept;
        }
    }

    return kept;
}

/** The packets tox 0 has sent tox 1 and holds until tox 1 acknowledges them. */
static uint32_t in_flight(AutoTox *autotoxes)
{
    const GC_Connection *gconn = connection_to_tox1(autotoxes);
    ck_assert_msg(gconn != nullptr, "tox 1 is no longer in tox 0's group");

    uint32_t held = 0;
    const uint16_t end = gcc_get_array_index(gconn->send_message_id);

    for (uint16_t i = gconn->send_array_start; i != end; i = (i + 1) % GCC_BUFFER_SIZE) {
        if (gconn->send_array[i].time_added != 0) {
            ++held;
        }
    }

    return held;
}

static void check_tox1_stays(AutoTox *autotoxes, const char *phase)
{
    const State *state0 = (const State *)autotoxes[0].state;
    const State *state1 = (const State *)autotoxes[1].state;

    ck_assert_msg(state0->peer_exits == 0 && state1->peer_exits == 0 && connection_to_tox1(autotoxes) != nullptr,
                  "%s: the toxes lost each other (exits seen by tox 0: %u, by tox 1: %u)", phase, state0->peer_exits,
                  state1->peer_exits);
}

/** Starts a case once tox 0 holds nothing for tox 1. */
static void start_case(AutoTox *autotoxes, size_t length)
{
    for (uint32_t i = 0; in_flight(autotoxes) > 0; ++i) {
        ck_assert_msg(i < MAX_ITERATIONS, "tox 0 kept packets for tox 1 in flight");
        iterate_all_wait(autotoxes, 2, ITERATION_INTERVAL);
    }

    State *state1 = (State *)autotoxes[1].state;
    state1->expected_length = length;
    state1->next_sequence = 0;
    state1->packets_received = 0;
    state1->packets_out_of_order = 0;
    state1->packets_damaged = 0;

    filter.lossless_sent = 0;
    filter.dropped = 0;
    filter.cut = false;
    filter.drop_next = 0;
    filter.drop_one_in = 0;
}

static void check_received(AutoTox *autotoxes, const char *phase, uint32_t expected)
{
    const State *state1 = (const State *)autotoxes[1].state;

    ck_assert_msg(state1->packets_received == expected && state1->packets_out_of_order == 0
                  && state1->packets_damaged == 0,
                  "%s: tox 1 got %u packets of %u, %u out of order, %u damaged", phase, state1->packets_received,
                  expected, state1->packets_out_of_order, state1->packets_damaged);
}

/** Iterates a little after the last packet came, and checks that tox 1 acknowledged everything it took, and keeps
 * nothing: a copy left in its receive array would block or stand in for a message 8192 messages later. */
static void check_nothing_in_flight(AutoTox *autotoxes, const char *phase)
{
    for (uint32_t i = 0; i < SETTLE_ITERATIONS; ++i) {
        iterate_all_wait(autotoxes, 2, ITERATION_INTERVAL);
    }

    const uint32_t held = in_flight(autotoxes);
    ck_assert_msg(held == 0, "%s: %u s after the last packet came, tox 0 still holds %u packets for tox 1", phase,
                  SETTLE_ITERATIONS * ITERATION_INTERVAL / 1000, held);

    const uint32_t kept = kept_by_tox1(autotoxes);
    ck_assert_msg(kept == 0, "%s: after the last packet came, tox 1 still keeps %u packets of tox 0", phase, kept);
}

static void no_loss(AutoTox *autotoxes)
{
    const State *state1 = (const State *)autotoxes[1].state;
    start_case(autotoxes, BIG_PACKET_SIZE);

    for (uint16_t i = 0; i < NO_LOSS_PACKETS; ++i) {
        send_group_packet(autotoxes, i, BIG_PACKET_SIZE);
    }

    for (uint32_t i = 0; state1->packets_received < NO_LOSS_PACKETS; ++i) {
        ck_assert_msg(i < CATCH_UP_ITERATIONS, "1: tox 1 got %u packets of %u", state1->packets_received,
                      NO_LOSS_PACKETS);
        iterate_all_wait(autotoxes, 2, ITERATION_INTERVAL);
    }

    /* A resend of a fragment tox 1 took would come within these 10 s. */
    for (uint32_t i = 0; i < QUIET_ITERATIONS; ++i) {
        iterate_all_wait(autotoxes, 2, ITERATION_INTERVAL);
    }

    check_received(autotoxes, "1", NO_LOSS_PACKETS);
    check_tox1_stays(autotoxes, "1");

    const uint32_t needed = NO_LOSS_PACKETS * datagrams_for(BIG_PACKET_SIZE);
    printf("   %u lossless datagrams for the %u the packets take\n", filter.lossless_sent, needed);
    ck_assert_msg(filter.lossless_sent == needed, "1: tox 0 sent %u lossless datagrams for the %u the packets take",
                  filter.lossless_sent, needed);
    ck_assert_msg(in_flight(autotoxes) == 0, "1: tox 0 still holds packets for tox 1");
    ck_assert_msg(kept_by_tox1(autotoxes) == 0, "1: tox 1 still keeps packets of tox 0");
}

static void cut_then_loss(AutoTox *autotoxes)
{
    const State *state1 = (const State *)autotoxes[1].state;
    start_case(autotoxes, BIG_PACKET_SIZE);

    uint32_t i = 0;

    for (;; ++i) {
        if (i == CUT_START) {
            filter.cut = true;
        }

        if (i == CUT_START + CUT_LENGTH) {
            filter.cut = false;
            filter.drop_one_in = LOSS_AFTER_CUT;
        }

        if (i < CUT_PACKETS) {
            send_group_packet(autotoxes, (uint16_t)i, BIG_PACKET_SIZE);
        }

        iterate_all_wait(autotoxes, 2, ITERATION_INTERVAL);
        check_tox1_stays(autotoxes, "2");

        if (i >= CUT_START + CUT_LENGTH && state1->packets_received >= CUT_PACKETS) {
            break;
        }

        ck_assert_msg(i < CUT_START + CUT_LENGTH + CATCH_UP_ITERATIONS,
                      "2: %u s after the cut, tox 1 got %u packets of %u", CATCH_UP_ITERATIONS * ITERATION_INTERVAL / 1000,
                      state1->packets_received, CUT_PACKETS);
    }

    filter.drop_one_in = 0;

    const uint32_t needed = CUT_PACKETS * datagrams_for(BIG_PACKET_SIZE);
    printf("   all %u packets %.1f s after the cut, %u lossless datagrams (%u dropped) for the %u they take\n",
           CUT_PACKETS, (double)(i + 1 - CUT_START - CUT_LENGTH) * ITERATION_INTERVAL / 1000, filter.lossless_sent,
           filter.dropped, needed);

    check_received(autotoxes, "2", CUT_PACKETS);
    check_nothing_in_flight(autotoxes, "2");
    check_tox1_stays(autotoxes, "2");
}

static void one_loss_before_many_small(AutoTox *autotoxes)
{
    const State *state1 = (const State *)autotoxes[1].state;
    start_case(autotoxes, SMALL_PACKET_SIZE);

    /* The first packet is lost, and the others go out a second later: tox 0 sends the first one, the oldest, again
     * before the others are due for a resend (about 3 s after they went out), so it fills the gap and they are handed
     * on from tox 1's receive array, not taken again from the wire. */
    filter.drop_next = 1;
    send_group_packet(autotoxes, 0, SMALL_PACKET_SIZE);

    for (uint32_t i = 0; i < 1000 / ITERATION_INTERVAL; ++i) {
        iterate_all_wait(autotoxes, 2, ITERATION_INTERVAL);
    }

    for (uint16_t i = 1; i < SMALL_PACKETS; ++i) {
        send_group_packet(autotoxes, i, SMALL_PACKET_SIZE);
    }

    uint32_t i = 0;

    for (; state1->packets_received < SMALL_PACKETS; ++i) {
        ck_assert_msg(i < CATCH_UP_ITERATIONS, "3: %u s on, tox 1 got %u packets of %u",
                      CATCH_UP_ITERATIONS * ITERATION_INTERVAL / 1000, state1->packets_received, SMALL_PACKETS);
        iterate_all_wait(autotoxes, 2, ITERATION_INTERVAL);
        check_tox1_stays(autotoxes, "3");
    }

    printf("   all %u packets %.1f s after the last went out, %u lossless datagrams for the %u they take\n",
           SMALL_PACKETS, (double)i * ITERATION_INTERVAL / 1000, filter.lossless_sent, SMALL_PACKETS);

    check_received(autotoxes, "3", SMALL_PACKETS);
    check_nothing_in_flight(autotoxes, "3");
    check_tox1_stays(autotoxes, "3");

    /* The lost one once more; each of the others went out once. */
    ck_assert_msg(filter.lossless_sent <= SMALL_PACKETS + 1,
                  "3: tox 0 sent %u lossless datagrams for the %u the packets take, and the lost one again",
                  filter.lossless_sent, SMALL_PACKETS);
}
#endif  // VANILLA_NACL

static void group_recv_gap_test(AutoTox *autotoxes)
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

    const GC_Chat *chat0 = gc_get_group(autotoxes[0].tox->m->group_handler, state0->group_number);
    ck_assert(chat0 != nullptr);
    memcpy(filter.counted_key, chat0->self_public_key, ENC_PUBLIC_KEY_SIZE);
    filter.key_set = true;

    printf("1. %u packets of %u bytes, no loss\n", NO_LOSS_PACKETS, BIG_PACKET_SIZE);
    no_loss(autotoxes);

    printf("2. %u packets of %u bytes, one per %u ms, a cut of %u s, then 1 lossless datagram in %u lost\n",
           CUT_PACKETS, BIG_PACKET_SIZE, ITERATION_INTERVAL, CUT_LENGTH * ITERATION_INTERVAL / 1000, LOSS_AFTER_CUT);
    cut_then_loss(autotoxes);

    printf("3. %u packets of %u bytes, the first one lost, the others a second later\n", SMALL_PACKETS,
           SMALL_PACKET_SIZE);
    one_loss_before_many_small(autotoxes);
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

    run_auto_test(options, 2, group_recv_gap_test, sizeof(State), &autotest_opts);

    tox_options_free(options);

    return 0;
}
