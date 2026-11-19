/* KHANDAQ (0.2.63, F9, group TCP relays): a group keeps TCP relays of its own.
 *
 * QA live of 0.2.62 (F9): members of a group behind the same NAT never saw each other, and an invite from a desktop
 * founder could die without a word. A group runs its TCP connections under its own key and takes their relays from
 * the messenger's. It took only those the messenger was connected to, and took them again only when the number of
 * its own changed: a group made before the first relay was up never got a relay, and neither did a group made while
 * the messenger's relays slept, as they do on a client that reaches all of its friends over UDP. Such a group had no
 * relay to hand a peer it could not reach over UDP, and none to reach that peer through.
 *
 * Two toxes, friends over UDP, and a third tox that is nothing but a TCP relay.
 * 1. tox 0 makes a group before it knows of any relay; then both toxes get the relay. The group joins the relay
 *    under its own key within two relay checks (TCP_RELAYS_CHECK_INTERVAL);
 * 2. tox 0 restarts from its save, as a client does every time it starts: toxcore loads the group in tox_new(),
 *    before the messenger is back on the relay. The group joins the relay again within two relay checks.
 *
 * A group made while the messenger's relays sleep is not covered here: two local toxes reach the DHT over LAN
 * addresses only, so their messengers keep their relays awake for the onion and never put one to sleep.
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "../testing/misc_tools.h"
#include "../toxcore/ccompat.h"
#include "../toxcore/group_chats.h"
#include "../toxcore/group_common.h"
#include "../toxcore/Messenger.h"
#include "../toxcore/net_crypto.h"
#include "../toxcore/TCP_connection.h"
#include "../toxcore/tox.h"
#include "../toxcore/tox_struct.h"
#include "check_compat.h"

typedef struct State {
    uint32_t unused;
} State;

#include "auto_test_support.h"

#define GROUP_NAME "relays"
#define NICK "peer"

/* Iterations (ITERATION_INTERVAL ms of tox time each): two relay checks of a group (TCP_RELAYS_CHECK_INTERVAL,
 * 10 s), and time to connect. */
#define JOIN_ITERATIONS (25 * 1000 / ITERATION_INTERVAL)

#define FIRST_RELAY_PORT 33400
#define RELAY_PORTS 200

#ifndef VANILLA_NACL

static const TCP_Connections *messenger_relays(const AutoTox *autotox)
{
    return nc_get_tcp_c(autotox->tox->m->net_crypto);
}

/* The relays group `group_number` of `autotox` is connected to under its own key. */
static uint32_t group_connected_relays(const AutoTox *autotox, uint32_t group_number)
{
    const GC_Chat *chat = gc_get_group(autotox->tox->m->group_handler, group_number);
    ck_assert(chat != nullptr && chat->tcp_conn != nullptr);
    return tcp_connected_relays_count(chat->tcp_conn);
}

static void iterate(AutoTox *autotoxes, Tox *relay)
{
    tox_iterate(relay, nullptr);
    iterate_all_wait(autotoxes, 2, ITERATION_INTERVAL);
}

/* A tox that is only a TCP relay: no UDP, no friends. */
static Tox *start_relay(uint16_t *port, uint8_t *key)
{
    struct Tox_Options *options = tox_options_new(nullptr);
    ck_assert(options != nullptr);
    tox_options_set_udp_enabled(options, false);
    tox_options_set_local_discovery_enabled(options, false);

    Tox *relay = nullptr;

    for (uint16_t p = FIRST_RELAY_PORT; p < FIRST_RELAY_PORT + RELAY_PORTS && relay == nullptr; ++p) {
        tox_options_set_tcp_port(options, p);
        Tox_Err_New err;
        relay = tox_new_log(options, &err, nullptr);

        if (relay != nullptr) {
            *port = p;
        }
    }

    tox_options_free(options);
    ck_assert_msg(relay != nullptr, "no free TCP port for the relay in %u..%u", FIRST_RELAY_PORT,
                  FIRST_RELAY_PORT + RELAY_PORTS - 1);
    tox_self_get_dht_id(relay, key);
    return relay;
}

static void add_relay(AutoTox *autotox, uint16_t relay_port, const uint8_t *relay_key)
{
    Tox_Err_Bootstrap err;
    tox_add_tcp_relay(autotox->tox, "127.0.0.1", relay_port, relay_key, &err);
    ck_assert_msg(err == TOX_ERR_BOOTSTRAP_OK, "tox %u could not add the relay: error %d", autotox->index, err);
}

static uint32_t new_group(AutoTox *autotox)
{
    Tox_Err_Group_New err;
    const uint32_t group_number = tox_group_new(autotox->tox, TOX_GROUP_PRIVACY_STATE_PRIVATE,
                                                (const uint8_t *)GROUP_NAME, sizeof(GROUP_NAME) - 1,
                                                (const uint8_t *)NICK, sizeof(NICK) - 1, &err);
    ck_assert_msg(err == TOX_ERR_GROUP_NEW_OK, "tox_group_new failed: error %d", err);
    return group_number;
}

/* Iterations until group `group_number` of tox 0 is connected to a relay under its own key; 0 if it never is. */
static uint32_t wait_for_group_relay(AutoTox *autotoxes, Tox *relay, uint32_t group_number)
{
    for (uint32_t i = 0; i < JOIN_ITERATIONS; ++i) {
        iterate(autotoxes, relay);

        if (group_connected_relays(&autotoxes[0], group_number) > 0) {
            return i + 1;
        }
    }

    return 0;
}

#endif  // !VANILLA_NACL

static void group_tcp_relays_test(AutoTox *autotoxes)
{
#ifndef VANILLA_NACL
    uint16_t relay_port = 0;
    uint8_t relay_key[TOX_PUBLIC_KEY_SIZE];
    Tox *relay = start_relay(&relay_port, relay_key);
    printf("the relay listens on TCP port %u\n", relay_port);

    printf("1. tox 0 makes a group before it knows of any relay\n");
    ck_assert_msg(tcp_connected_relays_count(messenger_relays(&autotoxes[0])) == 0,
                  "tox 0 was on a relay before the test gave it one");
    const uint32_t group_number = new_group(&autotoxes[0]);
    ck_assert(group_connected_relays(&autotoxes[0], group_number) == 0);

    for (uint32_t i = 0; i < 2; ++i) {
        add_relay(&autotoxes[i], relay_port, relay_key);
    }

    const uint32_t made_joined = wait_for_group_relay(autotoxes, relay, group_number);
    ck_assert_msg(made_joined != 0, "1: the group made before any relay was up joined none in %u s (tox 0's "
                  "messenger is on %u relay(s))", JOIN_ITERATIONS * ITERATION_INTERVAL / 1000,
                  tcp_connected_relays_count(messenger_relays(&autotoxes[0])));
    printf("   the group joined the relay under its own key %.1f s after the messenger got it\n",
           (double)made_joined * ITERATION_INTERVAL / 1000);

    printf("2. tox 0 restarts: toxcore loads the group before the messenger is back on the relay\n");
    save_autotox(&autotoxes[0]);
    reload(&autotoxes[0]);
    ck_assert(tox_group_get_number_groups(autotoxes[0].tox) == 1);
    ck_assert_msg(group_connected_relays(&autotoxes[0], group_number) == 0,
                  "2: the loaded group was on a relay before the messenger was");
    add_relay(&autotoxes[0], relay_port, relay_key);

    const uint32_t loaded_joined = wait_for_group_relay(autotoxes, relay, group_number);
    ck_assert_msg(loaded_joined != 0, "2: the group loaded in tox_new() joined no relay in %u s (tox 0's messenger is "
                  "on %u relay(s))", JOIN_ITERATIONS * ITERATION_INTERVAL / 1000,
                  tcp_connected_relays_count(messenger_relays(&autotoxes[0])));
    printf("   the loaded group joined the relay under its own key %.1f s after the restart\n",
           (double)loaded_joined * ITERATION_INTERVAL / 1000);

    Tox_Err_Group_Leave err_leave;
    tox_group_leave(autotoxes[0].tox, group_number, nullptr, 0, &err_leave);
    ck_assert(err_leave == TOX_ERR_GROUP_LEAVE_OK);

    tox_kill(relay);
    printf("Test passed!\n");
#endif  // !VANILLA_NACL
}

int main(void)
{
    setvbuf(stdout, nullptr, _IONBF, 0);

    struct Tox_Options *options = tox_options_new(nullptr);
    ck_assert(options != nullptr);

    Run_Auto_Options autotest_opts = default_run_auto_options();
    autotest_opts.graph = GRAPH_COMPLETE;

    run_auto_test(options, 2, group_tcp_relays_test, sizeof(State), &autotest_opts);

    tox_options_free(options);

    return 0;
}
