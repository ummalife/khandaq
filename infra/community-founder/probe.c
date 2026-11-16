/*
 * Khandaq Community probe.
 *
 * Joins the community group by its chat id with a fresh identity, as a new client does, and checks
 * what every member must find there: the group connects; its name; the founder online with the
 * role founder and the nick Khandaq; the group public, with the topic lock on and the expected peer
 * limit; a topic (the expected one, when given); ourselves a plain user; not a word from the
 * founder, in the group or in private; and, with --stay, the founder still there that long after we
 * connected. Members drop a peer they have heard nothing from for about a minute, so a founder that
 * stops pinging shows up only in a stay longer than that. Then it leaves.
 *
 * It prints key tags and lengths only, never a nick or the topic text. Exit status 0 only when
 * every check passed, so it can gate a deploy or run as a health check.
 *
 *   community-probe (--chat-id HEX | --group-file FILE) [--nodes FILE] [--port N] [--lan]
 *                   [--expect-limit N] [--expect-topic TEXT] [--join-timeout S] [--stay S]
 */

#define _GNU_SOURCE

#include <errno.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#include "community_common.h"

#define PROBE_NICK     "Khandaq probe"
#define QUIET_CHECK_MS 8000u
#define DEFAULT_STAY_S 75 /* toxcore drops a confirmed peer after 58 s without a packet from it */

static Tox *g_tox;
static uint64_t g_t0;
static bool g_dht;
static uint32_t g_group = UINT32_MAX;
static bool g_join_failed;
static int g_join_fail_type = -1;
static unsigned g_founder_messages;
static uint32_t g_founder_id = UINT32_MAX; /* the founder's peer id once seen */
static unsigned g_founder_exits;
static unsigned g_pass, g_fail;

static void say(const char *fmt, ...) __attribute__((format(printf, 1, 2)));
static void say(const char *fmt, ...)
{
    va_list ap;
    fprintf(stderr, "[%6.1f] ", (double)(mono_ms() - g_t0) / 1000.0);
    va_start(ap, fmt);
    vfprintf(stderr, fmt, ap);
    va_end(ap);
    fputc('\n', stderr);
}

static void check(bool ok, const char *what)
{
    if (ok) {
        g_pass++;
        say("PASS  %s", what);
    } else {
        g_fail++;
        say("FAIL  %s", what);
    }
}

typedef bool Predicate(void *ctx);

static bool wait_for(Predicate *p, void *ctx, uint32_t timeout_ms)
{
    const uint64_t end = mono_ms() + timeout_ms;
    while (mono_ms() < end) {
        tox_iterate(g_tox, NULL);
        if (p(ctx)) {
            return true;
        }
        usleep(5000);
    }
    return p(ctx);
}

static void spin(uint32_t ms)
{
    const uint64_t end = mono_ms() + ms;
    while (mono_ms() < end) {
        tox_iterate(g_tox, NULL);
        usleep(5000);
    }
}

/* ---- the group as this member sees it ------------------------------------------------------- */

static bool is_founder(uint32_t gn, uint32_t peer_id)
{
    Tox_Err_Group_Peer_Query err;
    const Tox_Group_Role role = tox_group_peer_get_role(g_tox, gn, peer_id, &err);
    return err == TOX_ERR_GROUP_PEER_QUERY_OK && role == TOX_GROUP_ROLE_FOUNDER;
}

/* The founder's peer id, or UINT32_MAX while it is not among the members we see. */
static uint32_t find_founder(void)
{
    Tox_Err_Group_Peer_Query err;
    const uint32_t n = tox_group_peer_count(g_tox, g_group, &err);
    if (err != TOX_ERR_GROUP_PEER_QUERY_OK || n == 0) {
        return UINT32_MAX;
    }
    uint32_t *ids = calloc(n, sizeof *ids);
    if (!ids) {
        return UINT32_MAX;
    }
    tox_group_get_peerlist(g_tox, g_group, ids, &err);
    uint32_t founder = UINT32_MAX;
    const uint32_t self_id = tox_group_self_get_peer_id(g_tox, g_group, NULL);
    for (uint32_t i = 0; i < n && err == TOX_ERR_GROUP_PEER_QUERY_OK && founder == UINT32_MAX; i++) {
        if (ids[i] != self_id && is_founder(g_group, ids[i])) {
            founder = ids[i];
        }
    }
    free(ids);
    return founder;
}

static size_t topic_get(uint8_t *out)
{
    Tox_Err_Group_State_Queries err;
    const size_t n = tox_group_get_topic_size(g_tox, g_group, &err);
    if (err != TOX_ERR_GROUP_STATE_QUERIES_OK || n > TOX_GROUP_MAX_TOPIC_LENGTH ||
        (n > 0 && !tox_group_get_topic(g_tox, g_group, out, &err))) {
        return 0;
    }
    return n;
}

/* ---- callbacks -------------------------------------------------------------------------------- */

static void on_self_connection(Tox *tox, Tox_Connection status, void *ud)
{
    g_dht = status != TOX_CONNECTION_NONE;
}

static void on_group_message(Tox *tox, uint32_t gn, uint32_t peer_id, Tox_Message_Type type,
                             const uint8_t *message, size_t length, uint32_t message_id, void *ud)
{
    if (gn == g_group && is_founder(gn, peer_id)) {
        g_founder_messages++;
    }
}

static void on_group_private_message(Tox *tox, uint32_t gn, uint32_t peer_id, Tox_Message_Type type,
                                     const uint8_t *message, size_t length, void *ud)
{
    if (gn == g_group && is_founder(gn, peer_id)) {
        g_founder_messages++;
    }
}

static void on_group_peer_exit(Tox *tox, uint32_t gn, uint32_t peer_id, Tox_Group_Exit_Type exit_type,
                               const uint8_t *name, size_t name_length, const uint8_t *part_message,
                               size_t part_message_length, void *ud)
{
    if (gn == g_group && g_founder_id != UINT32_MAX && peer_id == g_founder_id) {
        g_founder_exits++;
        g_founder_id = UINT32_MAX;
        say("the founder left our view (exit type %d)", (int)exit_type);
    }
}

static void on_group_join_fail(Tox *tox, uint32_t gn, Tox_Group_Join_Fail fail_type, void *ud)
{
    if (gn == g_group) {
        g_join_failed = true;
        g_join_fail_type = (int)fail_type;
    }
}

/* ---- predicates ------------------------------------------------------------------------------- */

static bool p_dht(void *ctx)
{
    return g_dht;
}

static bool p_connected(void *ctx)
{
    return g_join_failed || tox_group_is_connected(g_tox, g_group, NULL) == 1;
}

static bool p_founder(void *ctx)
{
    return find_founder() != UINT32_MAX;
}

typedef struct {
    const char *expected; /* NULL: any non-empty topic */
} Topic_Wait;

static bool p_topic(void *ctx)
{
    const Topic_Wait *w = ctx;
    uint8_t topic[TOX_GROUP_MAX_TOPIC_LENGTH];
    const size_t n = topic_get(topic);
    if (!w->expected) {
        return n > 0;
    }
    return n == strlen(w->expected) && memcmp(topic, w->expected, n) == 0;
}

static bool p_limit(void *ctx)
{
    return tox_group_get_peer_limit(g_tox, g_group, NULL) == *(const uint16_t *)ctx;
}

/* ---- main ------------------------------------------------------------------------------------- */

static bool read_chat_id(const char *path, uint8_t chat_id[KEY_SIZE])
{
    FILE *f = fopen(path, "r");
    if (!f) {
        return false;
    }
    char line[KEY_HEX_LEN + 8] = "";
    const bool ok = fgets(line, sizeof line, f) && strlen(line) >= KEY_HEX_LEN && hex_to_bin(line, chat_id, KEY_SIZE);
    fclose(f);
    return ok;
}

static void usage(const char *argv0)
{
    fprintf(stderr,
            "usage: %s (--chat-id HEX | --group-file FILE) [--nodes FILE] [--port N] [--lan]\n"
            "          [--expect-limit N] [--expect-topic TEXT] [--join-timeout S] [--stay S]\n"
            "  --chat-id HEX        the community group (64 hex), or --group-file with it on the first line\n"
            "  --nodes FILE         bootstrap list (default ./assets/nodes.txt)\n"
            "  --port N             first UDP port to try (default 33500)\n"
            "  --lan                enable LAN discovery (local testing only)\n"
            "  --expect-limit N     peer limit to expect (default %d)\n"
            "  --expect-topic TEXT  topic to expect (default: any non-empty topic)\n"
            "  --join-timeout S     seconds to wait for the group to connect (default 180)\n"
            "  --stay S             check that the founder is still there S seconds after we connected\n"
            "                       (default %d; 0 skips the check)\n",
            argv0, COMMUNITY_DEFAULT_PEER_LIMIT, DEFAULT_STAY_S);
}

int main(int argc, char **argv)
{
    g_t0 = mono_ms();
    const char *nodes = "assets/nodes.txt";
    const char *expect_topic = NULL;
    uint16_t expect_limit = COMMUNITY_DEFAULT_PEER_LIMIT;
    unsigned long port = 33500;
    unsigned long join_timeout_s = 180;
    unsigned long stay_s = DEFAULT_STAY_S;
    bool lan = false;
    bool have_chat_id = false;
    uint8_t chat_id[KEY_SIZE];

    for (int i = 1; i < argc; i++) {
        if (!strcmp(argv[i], "--chat-id") && i + 1 < argc) {
            const char *hex = argv[++i];
            have_chat_id = strlen(hex) == KEY_HEX_LEN && hex_to_bin(hex, chat_id, KEY_SIZE);
            if (!have_chat_id) {
                usage(argv[0]);
                return 2;
            }
        } else if (!strcmp(argv[i], "--group-file") && i + 1 < argc) {
            have_chat_id = read_chat_id(argv[++i], chat_id);
            if (!have_chat_id) {
                fprintf(stderr, "no chat id in %s\n", argv[i]);
                return 2;
            }
        } else if (!strcmp(argv[i], "--nodes") && i + 1 < argc) {
            nodes = argv[++i];
        } else if (!strcmp(argv[i], "--port") && i + 1 < argc) {
            port = strtoul(argv[++i], NULL, 10);
            if (port == 0 || port > 65525) {
                usage(argv[0]);
                return 2;
            }
        } else if (!strcmp(argv[i], "--lan")) {
            lan = true;
        } else if (!strcmp(argv[i], "--expect-limit") && i + 1 < argc) {
            if (!parse_peer_limit(argv[++i], &expect_limit)) {
                usage(argv[0]);
                return 2;
            }
        } else if (!strcmp(argv[i], "--expect-topic") && i + 1 < argc) {
            expect_topic = argv[++i];
        } else if (!strcmp(argv[i], "--join-timeout") && i + 1 < argc) {
            join_timeout_s = strtoul(argv[++i], NULL, 10);
            if (join_timeout_s == 0 || join_timeout_s > 3600) {
                usage(argv[0]);
                return 2;
            }
        } else if (!strcmp(argv[i], "--stay") && i + 1 < argc) {
            char *end = NULL;
            stay_s = strtoul(argv[++i], &end, 10);
            if (end == argv[i] || *end != '\0' || stay_s > 3600) {
                usage(argv[0]);
                return 2;
            }
        } else {
            usage(argv[0]);
            return 2;
        }
    }
    if (!have_chat_id) {
        usage(argv[0]);
        return 2;
    }

    struct Tox_Options *opts = tox_options_new(NULL);
    tox_options_set_ipv6_enabled(opts, true);
    tox_options_set_udp_enabled(opts, true);
    tox_options_set_local_discovery_enabled(opts, lan);
    tox_options_set_hole_punching_enabled(opts, true);
    tox_options_set_start_port(opts, (uint16_t)port);
    tox_options_set_end_port(opts, (uint16_t)(port + 10));
    Tox_Err_New nerr;
    g_tox = tox_new(opts, &nerr);
    tox_options_free(opts);
    if (!g_tox) {
        say("tox_new failed (%d)", (int)nerr);
        return 1;
    }
    tox_callback_self_connection_status(g_tox, on_self_connection);
    tox_callback_group_message(g_tox, on_group_message);
    tox_callback_group_private_message(g_tox, on_group_private_message);
    tox_callback_group_join_fail(g_tox, on_group_join_fail);
    tox_callback_group_peer_exit(g_tox, on_group_peer_exit);

    char chat_hex[KEY_HEX_LEN + 1];
    bin_to_hex(chat_id, KEY_SIZE, chat_hex);
    say("probing chat id %s", chat_hex);
    const int booted = bootstrap_from_file(g_tox, nodes);
    if (booted <= 0) {
        say("no usable bootstrap node in %s", nodes);
        tox_kill(g_tox);
        return 1;
    }

    check(wait_for(p_dht, NULL, 90000), "connected to the DHT");

    Tox_Err_Group_Join jerr;
    g_group = tox_group_join(g_tox, chat_id, (const uint8_t *)PROBE_NICK, strlen(PROBE_NICK), NULL, 0, &jerr);
    if (jerr != TOX_ERR_GROUP_JOIN_OK) {
        say("tox_group_join failed (%d)", (int)jerr);
        tox_kill(g_tox);
        return 1;
    }
    const uint64_t join_start = mono_ms();
    const bool connected = wait_for(p_connected, NULL, (uint32_t)(join_timeout_s * 1000)) && !g_join_failed;
    if (g_join_failed) {
        say("join failed, type %d (0 = peer limit, 1 = password, 2 = unknown)", g_join_fail_type);
    }
    check(connected, "joined the group (connected)");
    if (!connected) {
        tox_group_leave(g_tox, g_group, NULL, 0, NULL);
        tox_kill(g_tox);
        say("RESULT: %u passed, %u failed", g_pass, g_fail);
        return 1;
    }
    const uint64_t connected_at = mono_ms();
    say("connected after %.1f s", (double)(connected_at - join_start) / 1000.0);

    const bool founder_seen = wait_for(p_founder, NULL, 60000);
    check(founder_seen, "the founder is online, with the role founder");
    if (founder_seen) {
        const uint32_t founder = find_founder();
        g_founder_id = founder;
        uint8_t key[KEY_SIZE];
        char tag[KEY_TAG_LEN + 1] = "?";
        if (tox_group_peer_get_public_key(g_tox, g_group, founder, key, NULL)) {
            key_tag(key, tag);
        }
        uint8_t nick[TOX_MAX_NAME_LENGTH];
        Tox_Err_Group_Peer_Query perr;
        const size_t nick_len = tox_group_peer_get_name_size(g_tox, g_group, founder, &perr);
        const bool nick_ok = perr == TOX_ERR_GROUP_PEER_QUERY_OK && nick_len == strlen(COMMUNITY_SELF_NICK) &&
                             tox_group_peer_get_name(g_tox, g_group, founder, nick, &perr) &&
                             memcmp(nick, COMMUNITY_SELF_NICK, nick_len) == 0;
        say("founder %s", tag);
        check(nick_ok, "the founder's nick is " COMMUNITY_SELF_NICK);
    }

    Topic_Wait tw = {expect_topic};
    const bool topic_ok = wait_for(p_topic, &tw, 30000);
    uint8_t topic[TOX_GROUP_MAX_TOPIC_LENGTH];
    const size_t topic_len = topic_get(topic);
    say("topic: %zu bytes", topic_len);
    check(topic_ok, expect_topic ? "the topic is the expected one" : "the group has a topic");

    char name[TOX_GROUP_MAX_GROUP_NAME_LENGTH + 1] = "";
    Tox_Err_Group_State_Queries qerr;
    const size_t name_len = tox_group_get_name_size(g_tox, g_group, &qerr);
    if (qerr == TOX_ERR_GROUP_STATE_QUERIES_OK && name_len < sizeof name &&
        tox_group_get_name(g_tox, g_group, (uint8_t *)name, &qerr)) {
        name[name_len] = '\0';
    }
    check(strcmp(name, COMMUNITY_GROUP_NAME) == 0, "the group is named " COMMUNITY_GROUP_NAME);
    check(tox_group_get_privacy_state(g_tox, g_group, NULL) == TOX_GROUP_PRIVACY_STATE_PUBLIC, "the group is public");
    check(tox_group_get_topic_lock(g_tox, g_group, NULL) == TOX_GROUP_TOPIC_LOCK_ENABLED, "the topic lock is on");
    const bool limit_ok = wait_for(p_limit, &expect_limit, 15000);
    say("peer limit: %u", (unsigned)tox_group_get_peer_limit(g_tox, g_group, NULL));
    char what[96]; /* the longest, the stay check with a 20-digit count, is 69 bytes */
    snprintf(what, sizeof what, "the peer limit is %u", (unsigned)expect_limit);
    check(limit_ok, what);
    check(tox_group_self_get_role(g_tox, g_group, NULL) == TOX_GROUP_ROLE_USER, "we joined as a plain user");

    spin(QUIET_CHECK_MS);
    check(g_founder_messages == 0, "the founder sent nothing, in the group or in private");

    if (stay_s > 0 && founder_seen) {
        const uint64_t until = connected_at + (uint64_t)stay_s * 1000u;
        if (until > mono_ms()) {
            spin((uint32_t)(until - mono_ms()));
        }
        const bool stayed = g_founder_exits == 0 && g_founder_id != UINT32_MAX && find_founder() == g_founder_id;
        snprintf(what, sizeof what, "the founder is still there %lu s after we connected", stay_s);
        check(stayed, what);
    }

    tox_group_leave(g_tox, g_group, NULL, 0, NULL);
    spin(1500);
    tox_kill(g_tox);
    say("RESULT: %u passed, %u failed", g_pass, g_fail);
    return g_fail == 0 ? 0 : 1;
}
