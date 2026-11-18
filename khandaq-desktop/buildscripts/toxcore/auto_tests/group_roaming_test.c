/* KHANDAQ (0.2.60, group roaming): a group peer whose address changes gets what is sent to it at its new address.
 *
 * QA live of 0.2.59: the network of a phone went away for 10 s while a group file went out to it, and came back with
 * another address. The file stood still for a minute, and the phone caught up with it about two and a half minutes
 * after its network came back. The phone kept its group session and went on sending from its new address; each of its
 * packets counted as a direct one, so its connection stayed direct and the sender sent it everything over UDP to the
 * old address alone. The phone heard nothing of the sender until it timed it out, 58 s later, and came back through a
 * new handshake.
 *
 * Two toxes in one group, direct over UDP. A Network between them moves a tox: from then on the other tox sees its
 * datagrams come from another port, what the other tox sends to that port reaches it, and what it sends to the old
 * one is lost.
 * 1. tox 1 moves while tox 0 sends 150 packets of 37000 bytes: all arrive, whole and in order;
 * 2. tox 1 moves again while nothing is sent, and the group stays quiet for longer than a peer's timeout;
 * 3. tox 0 moves while it sends 150 packets of 37000 bytes: all arrive, whole and in order.
 * Neither tox ever loses the other, and after each move the other tox sends to the new port.
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#ifndef _WIN32
#include <netinet/in.h>
#include <sys/socket.h>
#endif

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

    /* What this tox got of the packets of the running phase: each starts with its sequence number. */
    size_t expected_length;
    uint16_t next_sequence;
    uint32_t packets_received;
    uint32_t packets_out_of_order;
    uint32_t packets_damaged;
} State;

#include "auto_test_support.h"

#define GROUP_NAME "roaming"
#define NICK "peer"

#define BIG_PACKET_SIZE 37000
#define PHASE_PACKETS 150
#define MOVE_AT_PACKET 30

/* Iterations (ITERATION_INTERVAL ms of tox time each). */
#define MAX_ITERATIONS 20000
#define CATCH_UP_ITERATIONS 300
/* Quiet for 75 s: longer than GC_CONFIRMED_PEER_TIMEOUT (58 s), after which a peer heard of no more is gone. */
#define QUIET_ITERATIONS (75 * 1000 / ITERATION_INTERVAL)

/* The ports the other tox sees a moved tox at: never bound by a tox of the test. */
#define FIRST_ALIAS_PORT 47001

#define MAX_DEAD_PORTS 8

#if !defined(VANILLA_NACL) && !defined(_WIN32)

/* The Network's sockaddr, as network.c lays it out. */
struct Network_Addr {
    struct sockaddr_storage addr;
    size_t size;
};

/* How the other tox sees a tox: at its own port until it moves, then at an alias of it. */
typedef struct Mover {
    uint16_t port;    /* the port the tox is bound to */
    uint16_t alias;   /* the port the other tox sees it at now; 0 while it has not moved */
    uint16_t dead[MAX_DEAD_PORTS];  /* ports it was seen at before, which no longer reach it */
    uint32_t dead_count;
    uint32_t lost;    /* datagrams of the other tox lost on their way to a dead port */
} Mover;

static Mover movers[2];
static bool ports_known;
static Network_Funcs roaming_funcs;
static Network roaming_network;
static Tox_System roaming_system;

static uint16_t addr_port(const Network_Addr *addr)
{
    if (addr->addr.ss_family == AF_INET) {
        return ntohs(((const struct sockaddr_in *)(const void *)&addr->addr)->sin_port);
    }

    if (addr->addr.ss_family == AF_INET6) {
        return ntohs(((const struct sockaddr_in6 *)(const void *)&addr->addr)->sin6_port);
    }

    return 0;
}

static void set_addr_port(Network_Addr *addr, uint16_t port)
{
    if (addr->addr.ss_family == AF_INET) {
        ((struct sockaddr_in *)(void *)&addr->addr)->sin_port = htons(port);
    } else if (addr->addr.ss_family == AF_INET6) {
        ((struct sockaddr_in6 *)(void *)&addr->addr)->sin6_port = htons(port);
    }
}

/** The tox that owns the socket, by the port it is bound to; -1 for another socket. */
static int tox_of_socket(int sock)
{
    if (!ports_known) {
        return -1;
    }

    struct sockaddr_storage local;
    socklen_t local_size = sizeof(local);
    memset(&local, 0, sizeof(local));

    if (getsockname(sock, (struct sockaddr *)&local, &local_size) != 0) {
        return -1;
    }

    Network_Addr bound;
    memset(&bound, 0, sizeof(bound));
    bound.addr = local;
    bound.size = local_size;
    const uint16_t port = addr_port(&bound);

    for (int i = 0; i < 2; ++i) {
        if (movers[i].port == port) {
            return i;
        }
    }

    return -1;
}

static bool is_dead(const Mover *mover, uint16_t port)
{
    for (uint32_t i = 0; i < mover->dead_count; ++i) {
        if (mover->dead[i] == port) {
            return true;
        }
    }

    return false;
}

static int roaming_recvfrom(void *obj, int sock, uint8_t *buf, size_t len, Network_Addr *addr)
{
    const Network *ns = system_network();
    const int ret = ns->funcs->recvfrom(ns->obj, sock, buf, len, addr);

    if (ret <= 0) {
        return ret;
    }

    const int observer = tox_of_socket(sock);

    if (observer < 0) {
        return ret;
    }

    const Mover *mover = &movers[1 - observer];

    if (mover->alias != 0 && addr_port(addr) == mover->port) {
        set_addr_port(addr, mover->alias);
    }

    return ret;
}

static int roaming_sendto(void *obj, int sock, const uint8_t *buf, size_t len, const Network_Addr *addr)
{
    const Network *ns = system_network();
    const int observer = tox_of_socket(sock);

    if (observer >= 0) {
        Mover *mover = &movers[1 - observer];
        const uint16_t port = addr_port(addr);

        if (mover->alias != 0 && port == mover->alias) {
            Network_Addr to = *addr;
            set_addr_port(&to, mover->port);
            return ns->funcs->sendto(ns->obj, sock, buf, len, &to);
        }

        if (is_dead(mover, port)) {
            ++mover->lost;
            /* As if it went out and was lost on the way. */
            return (int)len;
        }
    }

    return ns->funcs->sendto(ns->obj, sock, buf, len, addr);
}

/** From now on the other tox sees tox `index` at `alias`; the port it saw it at before reaches it no more. */
static void move_tox(uint32_t index, uint16_t alias)
{
    Mover *mover = &movers[index];
    ck_assert(mover->dead_count < MAX_DEAD_PORTS);
    mover->dead[mover->dead_count] = mover->alias != 0 ? mover->alias : mover->port;
    ++mover->dead_count;
    mover->alias = alias;
}

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
        return;
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

/** Sends packet `sequence` from tox `from` to the group. 150 of them fit in a send array (8192 fragments of about
 * 1200 bytes) with room to spare: none is ever refused for want of room. */
static void send_group_packet(AutoTox *autotoxes, uint32_t from, uint16_t sequence)
{
    const State *state = (const State *)autotoxes[from].state;
    uint8_t *payload = (uint8_t *)malloc(BIG_PACKET_SIZE);
    ck_assert(payload != nullptr);

    payload[0] = (uint8_t)(sequence >> 8);
    payload[1] = (uint8_t)(sequence & 0xff);

    for (size_t i = 2; i < BIG_PACKET_SIZE; ++i) {
        payload[i] = payload_byte(sequence, i);
    }

    Tox_Err_Group_Send_Custom_Packet err;
    tox_group_send_custom_packet(autotoxes[from].tox, state->group_number, true, payload, BIG_PACKET_SIZE, &err);
    free(payload);
    ck_assert_msg(err == TOX_ERR_GROUP_SEND_CUSTOM_PACKET_OK, "tox_group_send_custom_packet failed: error %d", err);
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

/** Neither tox lost the other: before the fix, the tox that moved timed the other out 58 s after it last heard of it. */
static void both_hold_each_other(AutoTox *autotoxes, const char *phase)
{
    for (uint32_t i = 0; i < 2; ++i) {
        const State *state = (const State *)autotoxes[i].state;
        ck_assert_msg(state->peer_exits == 0 && connection_to_other(autotoxes, i) != nullptr,
                      "%s: tox %u lost the other tox (exits seen by tox %u: %u)", phase, i, i, state->peer_exits);
    }
}

/** The port tox `observer` sends to the other tox at. */
static uint16_t port_in_use(AutoTox *autotoxes, uint32_t observer)
{
    const GC_Connection *gconn = connection_to_other(autotoxes, observer);
    ck_assert(gconn != nullptr);
    return net_ntohs(gconn->addr.ip_port.port);
}

/** Tox `from` sends PHASE_PACKETS packets, one per iteration; tox `mover` moves to `alias` after MOVE_AT_PACKET. */
static void send_while_moving(AutoTox *autotoxes, const char *phase, uint32_t from, uint32_t mover, uint16_t alias)
{
    State *receiver = (State *)autotoxes[1 - from].state;
    receiver->expected_length = BIG_PACKET_SIZE;
    receiver->next_sequence = 0;
    receiver->packets_received = 0;
    receiver->packets_out_of_order = 0;
    receiver->packets_damaged = 0;

    const uint32_t observer = 1 - mover;
    uint16_t sequence = 0;
    uint32_t moved_at = 0;
    uint32_t followed_at = 0;
    uint32_t last_sent_at = 0;

    for (uint32_t i = 0;; ++i) {
        if (sequence == MOVE_AT_PACKET && moved_at == 0) {
            move_tox(mover, alias);
            moved_at = i;
        }

        if (sequence < PHASE_PACKETS) {
            send_group_packet(autotoxes, from, sequence);
            ++sequence;
            last_sent_at = i;
        }

        iterate_all_wait(autotoxes, 2, ITERATION_INTERVAL);
        both_hold_each_other(autotoxes, phase);

        if (moved_at != 0 && followed_at == 0 && port_in_use(autotoxes, observer) == alias) {
            followed_at = i;
        }

        if (sequence >= PHASE_PACKETS && receiver->packets_received >= PHASE_PACKETS) {
            printf("   all %u packets %.1f s after the last went out; tox %u sent to the new port %.1f s after the "
                   "move; %u datagrams lost to the old port\n", PHASE_PACKETS,
                   (double)(i - last_sent_at) * ITERATION_INTERVAL / 1000, observer,
                   (double)(followed_at - moved_at) * ITERATION_INTERVAL / 1000, movers[mover].lost);
            break;
        }

        ck_assert_msg(i < PHASE_PACKETS + CATCH_UP_ITERATIONS,
                      "%s: %.1f s after the move, tox %u got %u packets of %u (tox %u sends to port %u, the new one is "
                      "%u)", phase, (double)(i - moved_at) * ITERATION_INTERVAL / 1000, 1 - from,
                      receiver->packets_received, PHASE_PACKETS, observer, port_in_use(autotoxes, observer), alias);
    }

    ck_assert_msg(receiver->packets_received == PHASE_PACKETS && receiver->packets_out_of_order == 0
                  && receiver->packets_damaged == 0,
                  "%s: tox %u got %u packets of %u, %u out of order, %u damaged", phase, 1 - from,
                  receiver->packets_received, PHASE_PACKETS, receiver->packets_out_of_order,
                  receiver->packets_damaged);
    ck_assert_msg(port_in_use(autotoxes, observer) == alias, "%s: tox %u sends to port %u, not to the new one %u",
                  phase, observer, port_in_use(autotoxes, observer), alias);
    receiver->expected_length = 0;
}
#endif  // !VANILLA_NACL && !_WIN32

static void group_roaming_test(AutoTox *autotoxes)
{
#if !defined(VANILLA_NACL) && !defined(_WIN32)
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

    for (uint32_t i = 0; i < 2; ++i) {
        movers[i].port = tox_self_get_udp_port(autotoxes[i].tox, nullptr);
        ck_assert(movers[i].port != 0);
    }

    ports_known = true;
    printf("tox 0 is on port %u, tox 1 on port %u\n", movers[0].port, movers[1].port);

    printf("1. tox 1 moves to port %u while tox 0 sends it %u packets of %u bytes\n", FIRST_ALIAS_PORT,
           PHASE_PACKETS, BIG_PACKET_SIZE);
    send_while_moving(autotoxes, "1", 0, 1, FIRST_ALIAS_PORT);

    printf("2. tox 1 moves to port %u while nothing is sent; the group stays quiet for %u s\n", FIRST_ALIAS_PORT + 1,
           QUIET_ITERATIONS * ITERATION_INTERVAL / 1000);
    move_tox(1, FIRST_ALIAS_PORT + 1);
    uint32_t followed_at = 0;

    for (uint32_t i = 0; i < QUIET_ITERATIONS; ++i) {
        iterate_all_wait(autotoxes, 2, ITERATION_INTERVAL);
        both_hold_each_other(autotoxes, "2");

        if (followed_at == 0 && port_in_use(autotoxes, 0) == FIRST_ALIAS_PORT + 1) {
            followed_at = i + 1;
        }
    }

    ck_assert_msg(followed_at != 0, "2: tox 0 still sends to port %u, not to the new one %u", port_in_use(autotoxes, 0),
                  FIRST_ALIAS_PORT + 1);
    printf("   tox 0 sent to the new port %.1f s after the move\n", (double)followed_at * ITERATION_INTERVAL / 1000);

    printf("3. tox 0 moves to port %u while it sends %u packets of %u bytes\n", FIRST_ALIAS_PORT + 2, PHASE_PACKETS,
           BIG_PACKET_SIZE);
    send_while_moving(autotoxes, "3", 0, 0, FIRST_ALIAS_PORT + 2);

    both_hold_each_other(autotoxes, "end");
#endif  // !VANILLA_NACL && !_WIN32
}

int main(void)
{
    setvbuf(stdout, nullptr, _IONBF, 0);

    struct Tox_Options *options = tox_options_new(nullptr);
    ck_assert(options != nullptr);

#if !defined(VANILLA_NACL) && !defined(_WIN32)
    const Network *system_ns = system_network();
    ck_assert(system_ns != nullptr);
    roaming_funcs = *system_ns->funcs;
    roaming_funcs.recvfrom = roaming_recvfrom;
    roaming_funcs.sendto = roaming_sendto;
    roaming_network.funcs = &roaming_funcs;
    roaming_network.obj = nullptr;
    roaming_system = tox_default_system();
    roaming_system.ns = &roaming_network;
    tox_options_set_operating_system(options, &roaming_system);
#endif

    Run_Auto_Options autotest_opts = default_run_auto_options();
    autotest_opts.graph = GRAPH_COMPLETE;

    run_auto_test(options, 2, group_roaming_test, sizeof(State), &autotest_opts);

    tox_options_free(options);

    return 0;
}
