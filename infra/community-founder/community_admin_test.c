/*
 * Khandaq Community admin test.
 *
 * End-to-end test of the founder's admin channel, run on one machine against a founder started
 * with a scratch state directory: it reads the founder's Tox ID, chat id and pairing code from that
 * directory (--data) and checks the founder's files there. Several Tox nodes run in this process:
 *   admin     sends a near-miss code, then the right one, and drives every command
 *   intruder  sends a request without the code and must never be accepted
 *   member    the target of /mod, /mute, /kick and the rest; checks their effect from inside
 *   fillers   more members: at least three, and enough for two keys to share a first hex digit,
 *             which the ambiguous-prefix check needs
 * With --founder-log, it also confirms from the founder's log that a refused request was really
 * received and dropped, and that a kicked muted member was re-muted on return.
 *
 * It never prints the pairing code, and exits 0 only when every check passed.
 *
 *   community-admin-test --data DIR [--nodes FILE] [--port N] [--founder-log FILE]
 *   community-admin-test --data DIR --preauth FILE [--nodes FILE] [--port N]
 *   community-admin-test --data DIR --restart-mute --founder-log FILE [--nodes FILE] [--port N]
 *   community-admin-test --make-identity FILE
 *   community-admin-test --mesh FILE [--port N]
 *
 * --make-identity writes a fresh identity to FILE and prints its public key, for admins.txt.
 * --preauth uses that identity: listed in admins.txt, it must be accepted without the code.
 * --restart-mute mutes a member, asks for a restart of the founder ("restart the founder now") and
 * checks that the member is still muted afterwards, for the founder, the members and a newcomer.
 * --mesh runs a private DHT on this machine until SIGINT or SIGTERM and lists its nodes in FILE,
 * in the nodes.txt format. A founder, probe and test that bootstrap from that file alone find
 * each other at loopback addresses, with no public node involved. On the public DHT a founder on
 * a machine behind NAT announces its public address, which a client on the same machine reaches
 * only if the router loops packets back.
 */

#define _GNU_SOURCE

#include <ctype.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#include <sodium.h>

#include "community_common.h"

#define MAX_NODES    24
#define MAX_TEXTS    256
#define MAX_ACKS     512
#define MAX_SENT     256
#define MIN_FILLERS  3
#define MAX_FILLERS  15 /* with admin and member, 17 keys: two must share a first hex digit */
#define REPLY_MS     30000u
#define EFFECT_MS    30000u
#define DROP_LINE    "contact request dropped"
#define MESH_NODES   6

typedef struct {
    char label[16];
    Tox *tox;
    bool dht;
    uint32_t bot;     /* the founder as a friend, UINT32_MAX for none */
    bool bot_online;
    bool bot_ever_online;
    uint32_t group;   /* UINT32_MAX for none */
    unsigned founder_messages;
    char *texts[MAX_TEXTS]; /* replies from the bot, in order */
    unsigned text_count;
    uint8_t acks[MAX_ACKS][MSGV3_ID_LEN];
    unsigned ack_count;
    char group_text[256]; /* the last group message from a member other than the founder */
    unsigned group_text_count;
} Node;

static Node g_nodes[MAX_NODES];
static size_t g_node_count;
static uint64_t g_t0;
static unsigned g_pass, g_fail;
static char g_data_dir[1024] = "";
static const char *g_nodes_path = "assets/nodes.txt";
static const char *g_founder_log;
static uint16_t g_base_port = 34600;
static uint8_t g_bot_address[TOX_ADDRESS_SIZE];
static uint8_t g_chat_id[KEY_SIZE];
static uint8_t g_sent[MAX_SENT][MSGV3_ID_LEN];
static unsigned g_sent_count;

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

/* ---- files ------------------------------------------------------------------------------------ */

static void data_path(const char *file, char *out, size_t cap)
{
    if (snprintf(out, cap, "%s/%s", g_data_dir, file) >= (int)cap) {
        say("path too long");
        exit(2);
    }
}

/* The whole file, NUL-terminated; NULL when it cannot be read. */
static char *read_text(const char *path)
{
    FILE *f = fopen(path, "rb");
    if (!f) {
        return NULL;
    }
    size_t cap = 4096;
    size_t len = 0;
    char *buf = malloc(cap);
    while (buf) {
        const size_t n = fread(buf + len, 1, cap - len - 1, f);
        len += n;
        if (n == 0) {
            break;
        }
        if (len + 1 == cap) {
            char *grown = realloc(buf, cap * 2);
            if (!grown) {
                free(buf);
                buf = NULL;
                break;
            }
            buf = grown;
            cap *= 2;
        }
    }
    fclose(f);
    if (buf) {
        buf[len] = '\0';
    }
    return buf;
}

static char *read_data_file(const char *file)
{
    char path[1200];
    data_path(file, path, sizeof path);
    return read_text(path);
}

static unsigned count_occurrences(const char *text, const char *needle)
{
    unsigned n = 0;
    if (!text) {
        return 0;
    }
    for (const char *p = strstr(text, needle); p; p = strstr(p + 1, needle)) {
        n++;
    }
    return n;
}

static unsigned founder_log_count(const char *needle)
{
    char *log = g_founder_log ? read_text(g_founder_log) : NULL;
    const unsigned n = count_occurrences(log, needle);
    free(log);
    return n;
}

static bool data_file_contains(const char *file, const char *needle)
{
    char *text = read_data_file(file);
    const bool found = text && strstr(text, needle) != NULL;
    free(text);
    return found;
}

/* ---- nodes ------------------------------------------------------------------------------------ */

static void remember_text(Node *node, const uint8_t *text, size_t len)
{
    if (node->text_count < MAX_TEXTS) {
        node->texts[node->text_count++] = strndup((const char *)text, len);
    }
}

static bool node_is_founder(Node *node, uint32_t gn, uint32_t peer_id)
{
    Tox_Err_Group_Peer_Query err;
    const Tox_Group_Role role = tox_group_peer_get_role(node->tox, gn, peer_id, &err);
    return err == TOX_ERR_GROUP_PEER_QUERY_OK && role == TOX_GROUP_ROLE_FOUNDER;
}

static void on_self_connection(Tox *tox, Tox_Connection status, void *ud)
{
    Node *node = ud;
    node->dht = status != TOX_CONNECTION_NONE;
}

static void on_friend_connection(Tox *tox, uint32_t fn, Tox_Connection status, void *ud)
{
    Node *node = ud;
    if (fn == node->bot) {
        node->bot_online = status != TOX_CONNECTION_NONE;
        node->bot_ever_online = node->bot_ever_online || node->bot_online;
    }
}

static void on_friend_message(Tox *tox, uint32_t fn, Tox_Message_Type type, const uint8_t *msg, size_t len,
                              void *ud)
{
    Node *node = ud;
    if (fn != node->bot) {
        return;
    }
    Msgv3 m;
    msgv3_parse(msg, len, &m);
    if (type == TOX_MESSAGE_TYPE_HIGH_LEVEL_ACK) {
        if (m.v3 && node->ack_count < MAX_ACKS) {
            memcpy(node->acks[node->ack_count++], m.id, MSGV3_ID_LEN);
        }
        return;
    }
    if (m.v3) {
        uint8_t ack[1 + MSGV3_SUFFIX_LEN];
        const size_t ack_len = msgv3_ack_frame(ack, m.id, m.ts);
        tox_friend_send_message(tox, fn, TOX_MESSAGE_TYPE_HIGH_LEVEL_ACK, ack, ack_len, NULL);
    }
    remember_text(node, m.text, m.text_len);
}

static void on_group_message(Tox *tox, uint32_t gn, uint32_t peer_id, Tox_Message_Type type,
                             const uint8_t *message, size_t length, uint32_t message_id, void *ud)
{
    Node *node = ud;
    if (node_is_founder(node, gn, peer_id)) {
        node->founder_messages++;
        return;
    }
    const size_t n = length < sizeof node->group_text - 1 ? length : sizeof node->group_text - 1;
    memcpy(node->group_text, message, n);
    node->group_text[n] = '\0';
    node->group_text_count++;
}

static void on_group_private_message(Tox *tox, uint32_t gn, uint32_t peer_id, Tox_Message_Type type,
                                     const uint8_t *message, size_t length, void *ud)
{
    Node *node = ud;
    if (node_is_founder(node, gn, peer_id)) {
        node->founder_messages++;
    }
}

static void pump(void)
{
    for (size_t i = 0; i < g_node_count; i++) {
        tox_iterate(g_nodes[i].tox, &g_nodes[i]);
    }
}

typedef bool Predicate(void *ctx);

static bool wait_for(Predicate *p, void *ctx, uint32_t timeout_ms)
{
    const uint64_t end = mono_ms() + timeout_ms;
    while (mono_ms() < end) {
        pump();
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
        pump();
        usleep(5000);
    }
}

static Node *node_new(const char *label, const uint8_t *savedata, size_t savedata_len)
{
    if (g_node_count == MAX_NODES) {
        say("too many nodes");
        exit(1);
    }
    Node *node = &g_nodes[g_node_count];
    memset(node, 0, sizeof *node);
    snprintf(node->label, sizeof node->label, "%s", label);
    node->bot = UINT32_MAX;
    node->group = UINT32_MAX;

    const uint16_t port = (uint16_t)(g_base_port + 3 * g_node_count);
    struct Tox_Options *opts = tox_options_new(NULL);
    tox_options_set_ipv6_enabled(opts, true);
    tox_options_set_udp_enabled(opts, true);
    tox_options_set_local_discovery_enabled(opts, false);
    tox_options_set_hole_punching_enabled(opts, true);
    tox_options_set_start_port(opts, port);
    tox_options_set_end_port(opts, (uint16_t)(port + 2));
    if (savedata) {
        tox_options_set_savedata_type(opts, TOX_SAVEDATA_TYPE_TOX_SAVE);
        tox_options_set_savedata_data(opts, savedata, savedata_len);
    }
    Tox_Err_New err;
    node->tox = tox_new(opts, &err);
    tox_options_free(opts);
    if (!node->tox) {
        say("tox_new for %s failed (%d)", label, (int)err);
        exit(1);
    }
    tox_self_set_name(node->tox, (const uint8_t *)label, strlen(label), NULL);
    tox_callback_self_connection_status(node->tox, on_self_connection);
    tox_callback_friend_connection_status(node->tox, on_friend_connection);
    tox_callback_friend_message(node->tox, on_friend_message);
    tox_callback_group_message(node->tox, on_group_message);
    tox_callback_group_private_message(node->tox, on_group_private_message);
    if (bootstrap_from_file(node->tox, g_nodes_path) <= 0) {
        say("no usable bootstrap node in %s", g_nodes_path);
        exit(1);
    }
    /* The test nodes also know each other directly, so their group connections need no relay. */
    if (g_node_count > 0) {
        uint8_t dht_id[TOX_PUBLIC_KEY_SIZE];
        tox_self_get_dht_id(g_nodes[0].tox, dht_id);
        const uint16_t first_port = tox_self_get_udp_port(g_nodes[0].tox, NULL);
        tox_bootstrap(node->tox, "127.0.0.1", first_port, dht_id, NULL);
    }
    g_node_count++;
    return node;
}

static bool node_join(Node *node)
{
    Tox_Err_Group_Join err;
    node->group = tox_group_join(node->tox, g_chat_id, (const uint8_t *)node->label, strlen(node->label), NULL, 0, &err);
    if (err != TOX_ERR_GROUP_JOIN_OK) {
        say("%s: tox_group_join failed (%d)", node->label, (int)err);
        node->group = UINT32_MAX;
        return false;
    }
    return true;
}

static bool node_group_key(const Node *node, uint8_t key[KEY_SIZE])
{
    return node->group != UINT32_MAX && tox_group_self_get_public_key(node->tox, node->group, key, NULL);
}

static int group_state(const Node *node)
{
    return node->group == UINT32_MAX ? -2 : tox_group_is_connected(node->tox, node->group, NULL);
}

static Tox_Group_Role self_role(const Node *node)
{
    return tox_group_self_get_role(node->tox, node->group, NULL);
}

/* The role viewer sees for the member with this group key; false while viewer does not see them. */
static bool role_seen_by(const Node *viewer, const uint8_t *key, Tox_Group_Role *role, uint32_t *peer_out)
{
    Tox_Err_Group_Peer_Query err;
    const uint32_t n = tox_group_peer_count(viewer->tox, viewer->group, &err);
    if (err != TOX_ERR_GROUP_PEER_QUERY_OK || n == 0) {
        return false;
    }
    uint32_t *ids = calloc(n, sizeof *ids);
    if (!ids) {
        return false;
    }
    tox_group_get_peerlist(viewer->tox, viewer->group, ids, &err);
    bool found = false;
    for (uint32_t i = 0; i < n && err == TOX_ERR_GROUP_PEER_QUERY_OK && !found; i++) {
        uint8_t k[KEY_SIZE];
        if (tox_group_peer_get_public_key(viewer->tox, viewer->group, ids[i], k, NULL) && memcmp(k, key, KEY_SIZE) == 0) {
            Tox_Err_Group_Peer_Query rerr;
            const Tox_Group_Role r = tox_group_peer_get_role(viewer->tox, viewer->group, ids[i], &rerr);
            if (rerr == TOX_ERR_GROUP_PEER_QUERY_OK) {
                found = true;
                if (role) {
                    *role = r;
                }
                if (peer_out) {
                    *peer_out = ids[i];
                }
            }
        }
    }
    free(ids);
    return found;
}

/* The founder's group key as viewer sees it. */
static bool founder_key(const Node *viewer, uint8_t key[KEY_SIZE])
{
    Tox_Err_Group_Peer_Query err;
    const uint32_t n = tox_group_peer_count(viewer->tox, viewer->group, &err);
    if (err != TOX_ERR_GROUP_PEER_QUERY_OK || n == 0) {
        return false;
    }
    uint32_t *ids = calloc(n, sizeof *ids);
    if (!ids) {
        return false;
    }
    tox_group_get_peerlist(viewer->tox, viewer->group, ids, &err);
    bool found = false;
    for (uint32_t i = 0; i < n && err == TOX_ERR_GROUP_PEER_QUERY_OK && !found; i++) {
        Tox_Err_Group_Peer_Query rerr;
        if (tox_group_peer_get_role(viewer->tox, viewer->group, ids[i], &rerr) == TOX_GROUP_ROLE_FOUNDER &&
            rerr == TOX_ERR_GROUP_PEER_QUERY_OK) {
            found = tox_group_peer_get_public_key(viewer->tox, viewer->group, ids[i], key, NULL);
        }
    }
    free(ids);
    return found;
}

static size_t node_topic(const Node *node, uint8_t *out)
{
    Tox_Err_Group_State_Queries err;
    const size_t n = tox_group_get_topic_size(node->tox, node->group, &err);
    if (err != TOX_ERR_GROUP_STATE_QUERIES_OK || n > TOX_GROUP_MAX_TOPIC_LENGTH ||
        (n > 0 && !tox_group_get_topic(node->tox, node->group, out, &err))) {
        return 0;
    }
    return n;
}

/* ---- predicates ------------------------------------------------------------------------------- */

static bool p_all_dht(void *ctx)
{
    for (size_t i = 0; i < g_node_count; i++) {
        if (!g_nodes[i].dht) {
            return false;
        }
    }
    return true;
}

static bool p_bot_online(void *ctx)
{
    return ((const Node *)ctx)->bot_online;
}

static bool p_log_count_above(void *ctx)
{
    const unsigned *before = ctx;
    return founder_log_count(DROP_LINE) > *before;
}

typedef struct {
    const char *needle;
    unsigned before;
} Log_Wait;

static bool p_log_needle(void *ctx)
{
    const Log_Wait *w = ctx;
    return founder_log_count(w->needle) > w->before;
}

static bool p_all_joined(void *ctx)
{
    for (size_t i = 0; i < g_node_count; i++) {
        if (g_nodes[i].group != UINT32_MAX && group_state(&g_nodes[i]) != 1) {
            return false;
        }
    }
    return true;
}

static bool p_connected(void *ctx)
{
    return group_state(ctx) == 1;
}

static bool p_kicked(void *ctx)
{
    return group_state(ctx) == -1;
}

typedef struct {
    const Node *viewer;
    const uint8_t *key; /* NULL: the viewer's own role */
    Tox_Group_Role role;
    bool absent;        /* wait until the viewer does not see key at all */
} Role_Wait;

static bool p_role(void *ctx)
{
    const Role_Wait *w = ctx;
    if (!w->key) {
        return self_role(w->viewer) == w->role;
    }
    Tox_Group_Role role;
    const bool seen = role_seen_by(w->viewer, w->key, &role, NULL);
    return w->absent ? !seen : seen && role == w->role;
}

/* The viewer sees the member with this key at all, in any role. Members find each other slowly (see
 * run_full), so the tests wait for this first and then check the role on its own. */
static bool p_sees(void *ctx)
{
    const Role_Wait *w = ctx;
    return role_seen_by(w->viewer, w->key, NULL, NULL);
}

typedef struct {
    const Node *node;
    unsigned from;
} Reply_Wait;

static bool p_reply(void *ctx)
{
    const Reply_Wait *w = ctx;
    return w->node->text_count > w->from;
}

typedef struct {
    const Node *node;
    const char *topic;
} Topic_Wait;

static bool p_topic(void *ctx)
{
    const Topic_Wait *w = ctx;
    uint8_t topic[TOX_GROUP_MAX_TOPIC_LENGTH];
    const size_t n = node_topic(w->node, topic);
    return n == strlen(w->topic) && memcmp(topic, w->topic, n) == 0;
}

typedef struct {
    const Node *node;
    uint16_t limit;
} Limit_Wait;

static bool p_limit(void *ctx)
{
    const Limit_Wait *w = ctx;
    return tox_group_get_peer_limit(w->node->tox, w->node->group, NULL) == w->limit;
}

typedef struct {
    const Node *node;
    unsigned before;
} Group_Text_Wait;

static bool p_group_text(void *ctx)
{
    const Group_Text_Wait *w = ctx;
    return w->node->group_text_count > w->before;
}

/* ---- the admin channel ------------------------------------------------------------------------ */

static uint8_t g_last_frame[TOX_MAX_MESSAGE_LENGTH];
static size_t g_last_frame_len;
static uint8_t g_last_id[MSGV3_ID_LEN];

static bool send_command(Node *admin, const char *text)
{
    uint8_t id[MSGV3_ID_LEN];
    tox_messagev3_get_new_message_id(id);
    const size_t len = msgv3_frame(g_last_frame, sizeof g_last_frame, text, strlen(text), id, (uint32_t)time(NULL));
    if (len == 0) {
        return false;
    }
    Tox_Err_Friend_Send_Message err;
    tox_friend_send_message(admin->tox, admin->bot, TOX_MESSAGE_TYPE_NORMAL, g_last_frame, len, &err);
    if (err != TOX_ERR_FRIEND_SEND_MESSAGE_OK) {
        say("sending a command failed (%d)", (int)err);
        return false;
    }
    g_last_frame_len = len;
    memcpy(g_last_id, id, sizeof id);
    if (g_sent_count < MAX_SENT) {
        memcpy(g_sent[g_sent_count++], id, sizeof id);
    }
    return true;
}

/* Send a command and wait for the bot's first reply to it. NULL when none came. */
static const char *command(Node *admin, const char *text)
{
    const unsigned before = admin->text_count;
    if (!send_command(admin, text)) {
        return NULL;
    }
    Reply_Wait w = {admin, before};
    if (!wait_for(p_reply, &w, REPLY_MS)) {
        return NULL;
    }
    return admin->texts[before];
}

/* The reply must contain expected. */
static const char *expect_reply(Node *admin, const char *text, const char *expected, const char *what)
{
    const char *reply = command(admin, text);
    const bool ok = reply && strstr(reply, expected) != NULL;
    check(ok, what);
    if (!ok) {
        say("      reply: %s", reply ? reply : "(none)");
    }
    return reply;
}

static unsigned ack_count_for(const Node *node, const uint8_t *id)
{
    unsigned n = 0;
    for (unsigned i = 0; i < node->ack_count; i++) {
        n += memcmp(node->acks[i], id, MSGV3_ID_LEN) == 0;
    }
    return n;
}

typedef struct {
    const Node *node;
    const uint8_t *id;
    unsigned at_least;
} Ack_Wait;

static bool p_ack(void *ctx)
{
    const Ack_Wait *w = ctx;
    return ack_count_for(w->node, w->id) >= w->at_least;
}

/* ---- shared steps ----------------------------------------------------------------------------- */

static void load_founder_files(void)
{
    char *toxid = read_data_file("toxid.txt");
    char *group = read_data_file("group.txt");
    if (!toxid || strlen(toxid) < 2 * TOX_ADDRESS_SIZE || !hex_to_bin(toxid, g_bot_address, TOX_ADDRESS_SIZE)) {
        say("no Tox ID in %s/toxid.txt", g_data_dir);
        exit(2);
    }
    if (!group || strlen(group) < KEY_HEX_LEN || !hex_to_bin(group, g_chat_id, KEY_SIZE)) {
        say("no chat id in %s/group.txt", g_data_dir);
        exit(2);
    }
    free(toxid);
    free(group);
}

/* One line per test member: its view of the group. After a failed check, to tell who misses whom. */
static void describe_views(void)
{
    for (size_t i = 0; i < g_node_count; i++) {
        const Node *node = &g_nodes[i];
        if (node->group == UINT32_MAX) {
            continue;
        }
        uint8_t key[KEY_SIZE];
        char tag[KEY_TAG_LEN + 1] = "?";
        if (node_group_key(node, key)) {
            key_tag(key, tag);
        }
        uint8_t founder[KEY_SIZE];
        const uint32_t peers = tox_group_peer_count(node->tox, node->group, NULL);
        say("      %s %s: state %d, %u peer(s), founder %s", node->label, tag, group_state(node), (unsigned)peers,
            founder_key(node, founder) ? "seen" : "not seen");
    }
}

static void final_checks(void)
{
    unsigned acked = 0;
    for (unsigned i = 0; i < g_sent_count; i++) {
        acked += ack_count_for(&g_nodes[0], g_sent[i]) > 0;
    }
    char what[96];
    snprintf(what, sizeof what, "every command was acknowledged (%u of %u)", acked, g_sent_count);
    check(g_sent_count > 0 && acked == g_sent_count, what);
    unsigned founder_messages = 0;
    for (size_t i = 0; i < g_node_count; i++) {
        founder_messages += g_nodes[i].founder_messages;
    }
    check(founder_messages == 0, "the founder never wrote in the group, nor in private");
}

static void cleanup(void)
{
    for (size_t i = 0; i < g_node_count; i++) {
        if (g_nodes[i].group != UINT32_MAX) {
            tox_group_leave(g_nodes[i].tox, g_nodes[i].group, NULL, 0, NULL);
        }
    }
    spin(2000);
    for (size_t i = 0; i < g_node_count; i++) {
        tox_kill(g_nodes[i].tox);
        for (unsigned t = 0; t < g_nodes[i].text_count; t++) {
            free(g_nodes[i].texts[t]);
        }
    }
    g_node_count = 0;
}

/* ---- the full test ---------------------------------------------------------------------------- */

/* Do two of these nodes' group keys share a first hex digit? *digit gets it. */
static bool first_digit_collision(char *digit)
{
    int seen[16];
    memset(seen, 0, sizeof seen);
    for (size_t i = 0; i < g_node_count; i++) {
        uint8_t key[KEY_SIZE];
        if (!node_group_key(&g_nodes[i], key)) {
            continue;
        }
        const int d = key[0] >> 4;
        if (++seen[d] == 2) {
            *digit = "0123456789ABCDEF"[d];
            return true;
        }
    }
    return false;
}

static unsigned joined_count(void)
{
    unsigned n = 0;
    for (size_t i = 0; i < g_node_count; i++) {
        n += g_nodes[i].group != UINT32_MAX;
    }
    return n;
}

/* The shortest prefix of key, at least min digits, that no other known key shares. */
static void unique_prefix(const uint8_t *key, size_t min, const uint8_t (*others)[KEY_SIZE], size_t other_count,
                          char *out)
{
    char hex[KEY_HEX_LEN + 1];
    bin_to_hex(key, KEY_SIZE, hex);
    for (size_t len = min; len <= KEY_HEX_LEN; len++) {
        bool unique = true;
        for (size_t i = 0; i < other_count && unique; i++) {
            char other[KEY_HEX_LEN + 1];
            bin_to_hex(others[i], KEY_SIZE, other);
            unique = memcmp(other, hex, len) != 0 || memcmp(others[i], key, KEY_SIZE) == 0;
        }
        if (unique) {
            memcpy(out, hex, len);
            out[len] = '\0';
            return;
        }
    }
    memcpy(out, hex, KEY_HEX_LEN + 1);
}

/* The pairing code from the founder's admin-code.txt, normalized; false when there is none. */
static bool load_pairing_code(char code[64])
{
    char *code_file = read_data_file("admin-code.txt");
    code[0] = '\0';
    if (code_file) {
        snprintf(code, 64, "%s", code_file);
        sodium_memzero(code_file, strlen(code_file));
        free(code_file);
    }
    if (!pairing_code_normalize(code)) {
        say("no pairing code in %s/admin-code.txt", g_data_dir);
        return false;
    }
    return true;
}

static int run_full(void)
{
    load_founder_files();
    char code[64];
    if (!load_pairing_code(code)) {
        return 2;
    }
    /* One character off, the kind of typo the forget step exists for. */
    char wrong[PAIRING_CODE_LEN + 1];
    memcpy(wrong, code, sizeof wrong);
    wrong[PAIRING_CODE_LEN - 1] = wrong[PAIRING_CODE_LEN - 1] == '7' ? '8' : '7';

    Node *admin = node_new("admin-test", NULL, 0);
    Node *intruder = node_new("intruder-test", NULL, 0);
    Node *member = node_new("member-test", NULL, 0);
    check(wait_for(p_all_dht, NULL, 120000), "admin, intruder and member are on the DHT");

    node_join(admin);
    node_join(member);
    unsigned fillers = 0;
    char ambiguous = 0;
    while (fillers < MIN_FILLERS || (!first_digit_collision(&ambiguous) && fillers < MAX_FILLERS)) {
        char label[16];
        snprintf(label, sizeof label, "filler-%u", ++fillers);
        node_join(node_new(label, NULL, 0));
    }
    first_digit_collision(&ambiguous);
    say("%u fillers; two members' keys start with %c", fillers, ambiguous ? ambiguous : '?');

    /* Pairing: a near-miss code is dropped, then the right code from the same key is accepted. */
    unsigned drops = founder_log_count(DROP_LINE);
    admin->bot = tox_friend_add(admin->tox, g_bot_address, (const uint8_t *)wrong, strlen(wrong), NULL);
    if (g_founder_log) {
        check(wait_for(p_log_count_above, &drops, 180000), "the founder received a near-miss code and dropped it");
    } else {
        spin(30000);
    }
    check(!admin->bot_ever_online, "a near-miss code is not accepted");
    tox_friend_delete(admin->tox, admin->bot, NULL);
    admin->bot = tox_friend_add(admin->tox, g_bot_address, (const uint8_t *)code, strlen(code), NULL);
    sodium_memzero(code, sizeof code);
    sodium_memzero(wrong, sizeof wrong);
    check(wait_for(p_bot_online, admin, 180000), "the right code from the same key is accepted after a wrong one");
    uint8_t admin_pk[TOX_PUBLIC_KEY_SIZE];
    char admin_hex[KEY_HEX_LEN + 1];
    tox_self_get_public_key(admin->tox, admin_pk);
    bin_to_hex(admin_pk, sizeof admin_pk, admin_hex);
    check(data_file_contains("admins.txt", admin_hex), "admins.txt lists the paired admin");

    drops = founder_log_count(DROP_LINE);
    const char *no_code = "hello, please add me";
    intruder->bot = tox_friend_add(intruder->tox, g_bot_address, (const uint8_t *)no_code, strlen(no_code), NULL);
    if (g_founder_log) {
        check(wait_for(p_log_count_above, &drops, 180000), "the founder received a request without the code and dropped it");
    }

    /* The group. */
    check(wait_for(p_all_joined, NULL, 240000), "admin, member and fillers joined the group");
    const unsigned expected_online = joined_count();
    uint8_t member_key[KEY_SIZE];
    uint8_t admin_key[KEY_SIZE];
    uint8_t bot_key[KEY_SIZE];
    node_group_key(member, member_key);
    node_group_key(admin, admin_key);
    const bool bot_known = founder_key(admin, bot_key);
    check(bot_known, "the admin sees the founder in the group");
    char member_tag[KEY_TAG_LEN + 1];
    char admin_tag[KEY_TAG_LEN + 1];
    char bot_tag[KEY_TAG_LEN + 1];
    key_tag(member_key, member_tag);
    key_tag(admin_key, admin_tag);
    key_tag(bot_key, bot_tag);
    say("admin %s, member %s, founder %s, %u members", admin_tag, member_tag, bot_tag, expected_online);

    uint8_t keys[MAX_NODES + 1][KEY_SIZE];
    size_t key_count = 0;
    for (size_t i = 0; i < g_node_count; i++) {
        if (node_group_key(&g_nodes[i], keys[key_count])) {
            key_count++;
        }
    }
    memcpy(keys[key_count++], bot_key, KEY_SIZE);
    char member_short[KEY_HEX_LEN + 1];
    unique_prefix(member_key, 6, (const uint8_t (*)[KEY_SIZE])keys, key_count, member_short);
    for (char *p = member_short; *p; p++) {
        *p = (char)tolower((unsigned char)*p);
    }

    char buf[1024];
    char what[160];

    /* /stats until the founder sees everyone: joins reach it one by one. */
    snprintf(what, sizeof what, "members online: %u\n", expected_online);
    bool counted = false;
    const uint64_t stats_end = mono_ms() + 180000;
    const char *reply = NULL;
    while (!counted && mono_ms() < stats_end) {
        reply = command(admin, "/stats");
        counted = reply && strstr(reply, what) == reply;
        if (!counted) {
            spin(5000);
        }
    }
    snprintf(buf, sizeof buf, "/stats counts the %u members online", expected_online);
    check(counted, buf);
    /* The limit the founder keeps: limit.txt once /limit (or an edit) set one, 250 before that. */
    char *limit_file = read_data_file("limit.txt");
    char limit_line[64];
    snprintf(limit_line, sizeof limit_line, "\npeer limit: %lu\nuptime: ",
             limit_file ? strtoul(limit_file, NULL, 10) : 250ul);
    free(limit_file);
    check(reply && strstr(reply, limit_line) != NULL, "/stats shows the peer limit and uptime");
    if (reply && !counted) {
        say("      reply: %s", reply);
    }
    if (!counted) {
        describe_views();
    }

    expect_reply(admin, "/help", "/mute <prefix>", "/help lists the commands");
    reply = command(admin, "/peers");
    snprintf(buf, sizeof buf, "%u members online:\n", expected_online);
    char admin_line[64];
    char member_line[64];
    snprintf(admin_line, sizeof admin_line, "%s user admin-test", admin_tag);
    snprintf(member_line, sizeof member_line, "%s user member-test", member_tag);
    const bool peers_counted = reply && strncmp(reply, buf, strlen(buf)) == 0;
    check(peers_counted, "/peers counts the members");
    if (reply && !peers_counted) {
        say("      reply: %s", reply);
    }
    check(reply && strstr(reply, admin_line) && strstr(reply, member_line), "/peers lists tag, role and nick");
    check(reply && !strstr(reply, bot_tag), "/peers leaves the bot out");

    /* The founder sees everyone by now, but members find each other through peer lists later (on a
     * local mesh without TCP relays, sometimes after a minute or two). The admin's view of the
     * member's role is checked below, so wait for it to see the member at all. */
    Role_Wait admin_sees = {admin, member_key, TOX_GROUP_ROLE_USER, false};
    Role_Wait member_sees = {member, admin_key, TOX_GROUP_ROLE_USER, false};
    check(wait_for(p_sees, &admin_sees, 180000) && wait_for(p_sees, &member_sees, 180000),
          "the admin and the member see each other");

    /* /mod and /unmod */
    snprintf(buf, sizeof buf, "/mod %s", member_tag);
    expect_reply(admin, buf, "is now a moderator", "/mod makes the member a moderator");
    Role_Wait self_wait = {member, NULL, TOX_GROUP_ROLE_MODERATOR, false};
    Role_Wait seen_wait = {admin, member_key, TOX_GROUP_ROLE_MODERATOR, false};
    check(wait_for(p_role, &self_wait, EFFECT_MS), "the member sees themself as moderator");
    check(wait_for(p_role, &seen_wait, EFFECT_MS), "the admin sees the member as moderator");
    expect_reply(admin, buf, "is already a moderator", "/mod twice says so");
    snprintf(buf, sizeof buf, "/unmod %s", member_short);
    expect_reply(admin, buf, "is now a user", "/unmod (short lower-case prefix) makes them a user");
    self_wait.role = seen_wait.role = TOX_GROUP_ROLE_USER;
    check(wait_for(p_role, &self_wait, EFFECT_MS), "the member sees themself as user again");
    check(wait_for(p_role, &seen_wait, EFFECT_MS), "the admin sees the member as user again");
    expect_reply(admin, buf, "is not a moderator", "/unmod of a user says so");

    /* /mute */
    snprintf(buf, sizeof buf, "/mute %s", member_tag);
    expect_reply(admin, buf, "is muted: they can read but not write", "/mute mutes the member");
    self_wait.role = seen_wait.role = TOX_GROUP_ROLE_OBSERVER;
    check(wait_for(p_role, &self_wait, EFFECT_MS), "the member sees themself as observer");
    check(wait_for(p_role, &seen_wait, EFFECT_MS), "the admin sees the member as observer");
    Tox_Err_Group_Send_Message serr;
    tox_group_send_message(member->tox, member->group, TOX_MESSAGE_TYPE_NORMAL, (const uint8_t *)"muted?", 6, NULL,
                           &serr);
    check(serr == TOX_ERR_GROUP_SEND_MESSAGE_PERMISSIONS, "a muted member cannot write");
    char member_hex[KEY_HEX_LEN + 1];
    bin_to_hex(member_key, KEY_SIZE, member_hex);
    check(data_file_contains("muted.txt", member_hex), "muted.txt lists the muted member");
    expect_reply(admin, buf, "is already muted", "/mute twice says so");

    /* /kick of a muted member: out, back with the same key, and muted again by the bot. */
    unsigned remuted_before = founder_log_count("re-muted ");
    snprintf(buf, sizeof buf, "/kick %s", member_tag);
    expect_reply(admin, buf, "is kicked; they can rejoin, and stay muted", "/kick kicks the muted member");
    check(wait_for(p_kicked, member, EFFECT_MS), "the kicked member is out of the group");
    Role_Wait gone_wait = {admin, member_key, TOX_GROUP_ROLE_USER, true};
    check(wait_for(p_role, &gone_wait, EFFECT_MS), "the admin no longer sees the kicked member");
    check(data_file_contains("muted.txt", member_hex), "muted.txt still lists the kicked member");
    Tox_Err_Group_Reconnect rerr;
    tox_group_reconnect(member->tox, member->group, &rerr);
    check(rerr == TOX_ERR_GROUP_RECONNECT_OK && wait_for(p_connected, member, 180000),
          "the kicked member reconnects");
    uint8_t key_again[KEY_SIZE];
    check(node_group_key(member, key_again) && memcmp(key_again, member_key, KEY_SIZE) == 0,
          "they come back with the same group key");
    seen_wait.role = TOX_GROUP_ROLE_OBSERVER;
    check(wait_for(p_role, &seen_wait, 150000), "the bot mutes them again: the admin sees an observer");
    check(wait_for(p_role, &self_wait, EFFECT_MS), "and the member sees themself as observer");
    if (g_founder_log) {
        Log_Wait lw = {"re-muted ", remuted_before};
        check(wait_for(p_log_needle, &lw, 5000), "the founder logged the re-mute");
    }

    /* /unmute */
    snprintf(buf, sizeof buf, "/unmute %s", member_tag);
    expect_reply(admin, buf, "is unmuted: they can write again", "/unmute lets the member write again");
    self_wait.role = seen_wait.role = TOX_GROUP_ROLE_USER;
    check(wait_for(p_role, &self_wait, EFFECT_MS), "the member sees themself as user");
    check(wait_for(p_role, &seen_wait, EFFECT_MS), "the admin sees the member as user");
    check(!data_file_contains("muted.txt", member_hex), "muted.txt no longer lists them");
    /* A group message goes to the peers the sender is connected to when it sends; since the kick, the
     * member has rebuilt its peer list and may not have found the admin again yet. */
    check(wait_for(p_sees, &member_sees, 180000), "the member sees the admin again");
    Group_Text_Wait gw = {admin, admin->group_text_count};
    const char *hello = "hello from the member";
    tox_group_send_message(member->tox, member->group, TOX_MESSAGE_TYPE_NORMAL, (const uint8_t *)hello,
                           strlen(hello), NULL, &serr);
    check(serr == TOX_ERR_GROUP_SEND_MESSAGE_OK && wait_for(p_group_text, &gw, EFFECT_MS) &&
              strcmp(admin->group_text, hello) == 0,
          "an unmuted member's message reaches the admin");
    expect_reply(admin, buf, "is not muted", "/unmute of a user says so");

    /* /topic */
    char topic[128];
    snprintf(topic, sizeof topic, "Test topic %04X \xC2\xB7 set by the admin test", (unsigned)(mono_ms() & 0xFFFF));
    snprintf(buf, sizeof buf, "/topic %s", topic);
    char expected[64];
    snprintf(expected, sizeof expected, "topic set (%zu bytes)", strlen(topic));
    expect_reply(admin, buf, expected, "/topic sets the topic");
    Topic_Wait tw = {admin, topic};
    check(wait_for(p_topic, &tw, EFFECT_MS), "the admin sees the new topic");
    tw.node = member;
    check(wait_for(p_topic, &tw, EFFECT_MS), "the member sees the new topic");
    snprintf(expected, sizeof expected, "%s\n", topic);
    char *topic_file = read_data_file("topic.txt");
    check(topic_file && strcmp(topic_file, expected) == 0, "topic.txt holds the new topic");
    free(topic_file);
    expect_reply(admin, buf, "topic unchanged", "/topic with the same text says so");
    expect_reply(admin, "/topic", "usage: /topic <text>", "/topic without text shows the usage");
    char long_topic[700];
    memcpy(long_topic, "/topic ", 7);
    memset(long_topic + 7, 'a', 600);
    long_topic[607] = '\0';
    expect_reply(admin, long_topic, "topic too long: 600 bytes, at most 512", "/topic refuses 600 bytes");

    /* /limit */
    expect_reply(admin, "/limit 300", "peer limit set to 300", "/limit 300");
    Limit_Wait lw300 = {admin, 300};
    check(wait_for(p_limit, &lw300, EFFECT_MS), "the admin sees the peer limit 300");
    check(data_file_contains("limit.txt", "300\n"), "limit.txt holds 300");
    const char *bad_limits[] = {"/limit 5", "/limit 9", "/limit 1001", "/limit abc", "/limit", "/limit 25 0"};
    for (size_t i = 0; i < sizeof bad_limits / sizeof bad_limits[0]; i++) {
        snprintf(what, sizeof what, "\"%s\" is refused", bad_limits[i]);
        expect_reply(admin, bad_limits[i], "the limit must be a whole number from 10 to 1000", what);
    }
    reply = expect_reply(admin, "/limit 10", "peer limit set to 10", "/limit 10, the lowest");
    if (expected_online > 10) {
        check(reply && strstr(reply, "members are online now: nobody is removed"), "/limit below the members online warns");
    }
    expect_reply(admin, "/limit 1000", "peer limit set to 1000", "/limit 1000, the highest");
    expect_reply(admin, "/limit 1000", "the peer limit is already 1000", "/limit to the same value says so");
    Limit_Wait lw1000 = {member, 1000};
    check(wait_for(p_limit, &lw1000, EFFECT_MS), "the member sees the peer limit 1000");

    /* Prefix errors */
    expect_reply(admin, "/mute", "usage: /mute <key prefix from /peers>", "a command without a prefix shows the usage");
    expect_reply(admin, "/mod xyz", "not a key prefix", "a prefix that is not hex is refused");
    snprintf(buf, sizeof buf, "/kick %s0", member_hex);
    expect_reply(admin, buf, "not a key prefix", "65 hex digits are refused");
    snprintf(buf, sizeof buf, "/mute %s", bot_tag);
    expect_reply(admin, buf, "is the bot itself", "the bot's own prefix is refused");
    uint8_t stranger[KEY_SIZE];
    tox_messagev3_get_new_message_id(stranger); /* 32 random bytes */
    char stranger_hex[KEY_HEX_LEN + 1];
    bin_to_hex(stranger, KEY_SIZE, stranger_hex);
    snprintf(buf, sizeof buf, "/unmute %s", stranger_hex);
    expect_reply(admin, buf, "no member online has a key starting with", "an unknown prefix is reported");
    if (ambiguous) {
        snprintf(buf, sizeof buf, "/mute %c", ambiguous);
        expect_reply(admin, buf, "members; give more digits", "an ambiguous prefix asks for more digits");
    } else {
        check(false, "fillers produced an ambiguous prefix");
    }
    reply = command(admin, "/peers");
    check(reply && !strstr(reply, " observer "), "the ambiguous /mute muted nobody");

    /* Other input */
    expect_reply(admin, "/frobnicate", "unknown command; send /help", "an unknown command");
    expect_reply(admin, "hello there", "unknown command; send /help", "plain text");
    expect_reply(admin, "/STATS", "members online:", "commands are case-insensitive");
    expect_reply(admin, "[KQ|v1|probe]quoted text[KQ/end]/peers", "online:", "a swipe-reply carries a command");

    /* A resend of the same msgV3 message is acknowledged again but answered once. */
    const unsigned acks_before = ack_count_for(admin, g_last_id);
    const unsigned texts_before = admin->text_count;
    tox_friend_send_message(admin->tox, admin->bot, TOX_MESSAGE_TYPE_NORMAL, g_last_frame, g_last_frame_len, NULL);
    Ack_Wait aw = {admin, g_last_id, acks_before + 1};
    check(wait_for(p_ack, &aw, 15000), "a resent command is acknowledged again");
    spin(6000);
    check(admin->text_count == texts_before, "a resent command is not answered twice");

    /* Leave the group as the restart check expects it. */
    expect_reply(admin, "/limit 260", "peer limit set to 260", "/limit 260 for the restart check");
    snprintf(topic, sizeof topic, "Khandaq Community restart check %04X", (unsigned)(mono_ms() & 0xFFFF));
    snprintf(buf, sizeof buf, "/topic %s", topic);
    expect_reply(admin, buf, "topic set (", "a topic for the restart check");
    tw.node = admin;
    tw.topic = topic;
    check(wait_for(p_topic, &tw, EFFECT_MS), "the admin sees the restart-check topic");

    final_checks();
    check(!intruder->bot_ever_online, "the request without the code was never accepted");
    uint8_t intruder_pk[TOX_PUBLIC_KEY_SIZE];
    char intruder_hex[KEY_HEX_LEN + 1];
    tox_self_get_public_key(intruder->tox, intruder_pk);
    bin_to_hex(intruder_pk, sizeof intruder_pk, intruder_hex);
    check(!data_file_contains("admins.txt", intruder_hex), "admins.txt does not list the intruder");
    cleanup();
    return 0;
}

/* ---- a mute across a restart of the founder ---------------------------------------------------- */

#define RESTART_LINE "community group restored"

/* toxcore saves no mutes, so a restarted founder starts with none: the bot has to learn them back
 * from the members or mute again from muted.txt. A member muted before the restart must still be
 * muted after it: in the founder's own view (/peers), in the views of the member and the admin, who
 * both stay online, and in the view of a member who joins only afterwards. The test mutes the member,
 * says "restart the founder now" and waits for a second start in --founder-log; whoever runs the test
 * restarts the founder, appending to the same log. */
static int run_restart_mute(void)
{
    if (!g_founder_log) {
        say("--restart-mute needs --founder-log");
        return 2;
    }
    load_founder_files();
    char code[64];
    if (!load_pairing_code(code)) {
        return 2;
    }
    Node *admin = node_new("admin-test", NULL, 0);
    Node *member = node_new("member-test", NULL, 0);
    check(wait_for(p_all_dht, NULL, 120000), "admin and member are on the DHT");
    admin->bot = tox_friend_add(admin->tox, g_bot_address, (const uint8_t *)code, strlen(code), NULL);
    sodium_memzero(code, sizeof code);
    check(wait_for(p_bot_online, admin, 180000), "the admin is paired");
    node_join(admin);
    node_join(member);
    check(wait_for(p_all_joined, NULL, 240000), "admin and member joined the group");

    uint8_t member_key[KEY_SIZE];
    uint8_t admin_key[KEY_SIZE];
    char member_tag[KEY_TAG_LEN + 1];
    char member_hex[KEY_HEX_LEN + 1];
    node_group_key(member, member_key);
    node_group_key(admin, admin_key);
    key_tag(member_key, member_tag);
    bin_to_hex(member_key, KEY_SIZE, member_hex);
    /* Connected to the group means connected to one peer, the founder; the two members find each
     * other through peer lists later (see run_full). Muting before that would leave the admin's view
     * to catch up through a later sync, which is not what this test is about. */
    Role_Wait admin_sees = {admin, member_key, TOX_GROUP_ROLE_USER, false};
    Role_Wait member_sees = {member, admin_key, TOX_GROUP_ROLE_USER, false};
    check(wait_for(p_sees, &admin_sees, 180000) && wait_for(p_sees, &member_sees, 180000),
          "the admin and the member see each other");
    char buf[128];
    snprintf(buf, sizeof buf, "/mute %s", member_tag);
    expect_reply(admin, buf, "is muted: they can read but not write", "/mute mutes the member");
    Role_Wait self_wait = {member, NULL, TOX_GROUP_ROLE_OBSERVER, false};
    Role_Wait seen_wait = {admin, member_key, TOX_GROUP_ROLE_OBSERVER, false};
    check(wait_for(p_role, &self_wait, EFFECT_MS), "the member sees themself as observer");
    check(wait_for(p_role, &seen_wait, EFFECT_MS), "the admin sees the member as observer");
    check(data_file_contains("muted.txt", member_hex), "muted.txt lists the member");

    uint8_t admin_pk[TOX_PUBLIC_KEY_SIZE];
    char admin_tag[KEY_TAG_LEN + 1];
    tox_self_get_public_key(admin->tox, admin_pk);
    key_tag(admin_pk, admin_tag);
    char online_line[64];
    snprintf(online_line, sizeof online_line, "admin %s online", admin_tag);
    Log_Wait started = {RESTART_LINE, founder_log_count(RESTART_LINE)};
    Log_Wait back = {online_line, founder_log_count(online_line)};
    const unsigned remuted_before = founder_log_count("re-muted ");
    say("restart the founder now, appending its log to %s", g_founder_log);
    check(wait_for(p_log_needle, &started, 300000), "the founder restarted");
    check(wait_for(p_log_needle, &back, 180000) && wait_for(p_bot_online, admin, 30000),
          "the admin reaches the restarted founder");

    /* The founder's own view, until it has the mute back: learnt from the members or set again. */
    char line[64];
    snprintf(line, sizeof line, "%s observer member-test", member_tag);
    bool founder_sees = false;
    const uint64_t end = mono_ms() + 150000;
    while (!founder_sees && mono_ms() < end) {
        const char *reply = command(admin, "/peers");
        founder_sees = reply && strstr(reply, line) != NULL;
        if (!founder_sees) {
            spin(5000);
        }
    }
    check(founder_sees, "the restarted founder sees the member as observer");
    say("%s", founder_log_count("re-muted ") > remuted_before ? "the founder muted the member again from muted.txt"
                                                                : "the founder learnt the mute back from the members");
    check(wait_for(p_role, &self_wait, EFFECT_MS), "the member still sees themself as observer");
    check(wait_for(p_role, &seen_wait, EFFECT_MS), "the admin still sees the member as observer");
    Tox_Err_Group_Send_Message serr;
    tox_group_send_message(member->tox, member->group, TOX_MESSAGE_TYPE_NORMAL, (const uint8_t *)"muted?", 6, NULL,
                           &serr);
    check(serr == TOX_ERR_GROUP_SEND_MESSAGE_PERMISSIONS, "the member still cannot write");

    Node *late = node_new("late-test", NULL, 0);
    check(wait_for(p_all_dht, NULL, 120000), "a late member is on the DHT");
    node_join(late);
    check(wait_for(p_connected, late, 180000), "the late member joined after the restart");
    Role_Wait late_wait = {late, member_key, TOX_GROUP_ROLE_OBSERVER, false};
    check(wait_for(p_sees, &late_wait, 180000), "the late member finds the muted member");
    Tox_Group_Role late_view = TOX_GROUP_ROLE_USER;
    const bool late_ok = wait_for(p_role, &late_wait, EFFECT_MS);
    check(late_ok, "the late member sees the member as observer");
    if (!late_ok && role_seen_by(late, member_key, &late_view, NULL)) {
        say("      the late member sees them with role %d (0 founder, 1 moderator, 2 user, 3 observer)",
            (int)late_view);
    }

    snprintf(buf, sizeof buf, "/unmute %s", member_tag);
    expect_reply(admin, buf, "is unmuted: they can write again", "/unmute after the restart");
    self_wait.role = TOX_GROUP_ROLE_USER;
    late_wait.role = TOX_GROUP_ROLE_USER;
    check(wait_for(p_role, &self_wait, EFFECT_MS), "the member sees themself as user");
    check(wait_for(p_role, &late_wait, EFFECT_MS), "the late member sees them as user");
    check(!data_file_contains("muted.txt", member_hex), "muted.txt no longer lists them");
    final_checks();
    cleanup();
    return 0;
}

/* ---- pre-authorized admin --------------------------------------------------------------------- */

static int run_preauth(const char *identity_path)
{
    load_founder_files();
    FILE *f = fopen(identity_path, "rb");
    if (!f) {
        say("cannot read %s", identity_path);
        return 2;
    }
    uint8_t savedata[64 * 1024];
    const size_t len = fread(savedata, 1, sizeof savedata, f);
    fclose(f);
    if (len == 0) {
        say("%s is empty", identity_path);
        return 2;
    }
    Node *admin = node_new("preauth-test", savedata, len);
    sodium_memzero(savedata, sizeof savedata);
    check(wait_for(p_all_dht, NULL, 120000), "the pre-authorized admin is on the DHT");
    const char *greeting = "hello, no code in this request";
    admin->bot = tox_friend_add(admin->tox, g_bot_address, (const uint8_t *)greeting, strlen(greeting), NULL);
    check(wait_for(p_bot_online, admin, 180000), "a key listed in admins.txt is accepted without the code");
    expect_reply(admin, "/help", "/mute <prefix>", "/help answers the pre-authorized admin");
    expect_reply(admin, "/stats", "members online:", "/stats answers the pre-authorized admin");
    uint8_t pk[TOX_PUBLIC_KEY_SIZE];
    char hex[KEY_HEX_LEN + 1];
    tox_self_get_public_key(admin->tox, pk);
    bin_to_hex(pk, sizeof pk, hex);
    char *admins = read_data_file("admins.txt");
    check(count_occurrences(admins, hex) == 1, "admins.txt lists the pre-authorized admin once, not appended again");
    free(admins);
    final_checks();
    cleanup();
    return 0;
}

static int make_identity(const char *path)
{
    /* No network at all: the identity is only written out, and nothing may touch the ports of a
     * Khandaq app running on the same machine. */
    struct Tox_Options *opts = tox_options_new(NULL);
    tox_options_set_udp_enabled(opts, false);
    tox_options_set_local_discovery_enabled(opts, false);
    Tox *tox = tox_new(opts, NULL);
    tox_options_free(opts);
    if (!tox) {
        say("tox_new failed");
        return 1;
    }
    const size_t size = tox_get_savedata_size(tox);
    uint8_t *data = malloc(size);
    if (!data) {
        tox_kill(tox);
        return 1;
    }
    tox_get_savedata(tox, data);
    const int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0600);
    const bool ok = fd >= 0 && write(fd, data, size) == (ssize_t)size && close(fd) == 0;
    sodium_memzero(data, size);
    free(data);
    if (!ok) {
        say("cannot write %s: %s", path, strerror(errno));
        tox_kill(tox);
        return 1;
    }
    uint8_t pk[TOX_PUBLIC_KEY_SIZE];
    char hex[KEY_HEX_LEN + 1];
    tox_self_get_public_key(tox, pk);
    bin_to_hex(pk, sizeof pk, hex);
    printf("%s\n", hex);
    tox_kill(tox);
    return 0;
}

static volatile sig_atomic_t g_mesh_stop;

static void on_mesh_signal(int sig)
{
    g_mesh_stop = 1;
}

static int run_mesh(const char *path)
{
    Tox *mesh[MESH_NODES];
    char text[64 + MESH_NODES * 96];
    int used = snprintf(text, sizeof text, "# local test mesh, written by community-admin-test --mesh\n");
    for (size_t i = 0; i < MESH_NODES; i++) {
        const uint16_t port = (uint16_t)(g_base_port + 3 * i);
        struct Tox_Options *opts = tox_options_new(NULL);
        tox_options_set_ipv6_enabled(opts, true);
        tox_options_set_udp_enabled(opts, true);
        tox_options_set_local_discovery_enabled(opts, false);
        tox_options_set_start_port(opts, port);
        tox_options_set_end_port(opts, (uint16_t)(port + 2));
        Tox_Err_New err;
        mesh[i] = tox_new(opts, &err);
        tox_options_free(opts);
        if (!mesh[i]) {
            say("tox_new for mesh node %zu failed (%d)", i, (int)err);
            while (i > 0) {
                tox_kill(mesh[--i]);
            }
            return 1;
        }
        uint8_t dht_id[TOX_PUBLIC_KEY_SIZE];
        char hex[KEY_HEX_LEN + 1];
        tox_self_get_dht_id(mesh[i], dht_id);
        bin_to_hex(dht_id, sizeof dht_id, hex);
        used += snprintf(text + used, sizeof text - (size_t)used, "127.0.0.1 %u %s\n",
                         (unsigned)tox_self_get_udp_port(mesh[i], NULL), hex);
        for (size_t j = 0; j < i; j++) {
            tox_self_get_dht_id(mesh[j], dht_id);
            tox_bootstrap(mesh[i], "127.0.0.1", tox_self_get_udp_port(mesh[j], NULL), dht_id, NULL);
        }
    }

    /* Written whole and renamed into place, so that nobody bootstraps from half a list. */
    char tmp[1100];
    snprintf(tmp, sizeof tmp, "%s.tmp", path);
    FILE *f = fopen(tmp, "w");
    bool written = f != NULL;
    if (f) {
        written = fputs(text, f) >= 0;
        written = fclose(f) == 0 && written;
    }
    if (!written || rename(tmp, path) != 0) {
        say("cannot write %s: %s", path, strerror(errno));
        for (size_t i = 0; i < MESH_NODES; i++) {
            tox_kill(mesh[i]);
        }
        return 1;
    }
    say("mesh of %d nodes listed in %s", MESH_NODES, path);

    signal(SIGINT, on_mesh_signal);
    signal(SIGTERM, on_mesh_signal);
    while (!g_mesh_stop) {
        for (size_t i = 0; i < MESH_NODES; i++) {
            tox_iterate(mesh[i], NULL);
        }
        usleep(20000);
    }
    for (size_t i = 0; i < MESH_NODES; i++) {
        tox_kill(mesh[i]);
    }
    unlink(path);
    say("mesh stopped");
    return 0;
}

static void usage(const char *argv0)
{
    fprintf(stderr,
            "usage: %s --data DIR [--nodes FILE] [--port N] [--founder-log FILE]\n"
            "       %s --data DIR --preauth FILE [--nodes FILE] [--port N]\n"
            "       %s --data DIR --restart-mute --founder-log FILE [--nodes FILE] [--port N]\n"
            "       %s --make-identity FILE\n"
            "       %s --mesh FILE [--port N]\n",
            argv0, argv0, argv0, argv0, argv0);
}

int main(int argc, char **argv)
{
    g_t0 = mono_ms();
    const char *preauth = NULL;
    const char *identity = NULL;
    const char *mesh = NULL;
    bool restart_mute = false;
    for (int i = 1; i < argc; i++) {
        if (!strcmp(argv[i], "--data") && i + 1 < argc) {
            snprintf(g_data_dir, sizeof g_data_dir, "%s", argv[++i]);
        } else if (!strcmp(argv[i], "--nodes") && i + 1 < argc) {
            g_nodes_path = argv[++i];
        } else if (!strcmp(argv[i], "--port") && i + 1 < argc) {
            const unsigned long port = strtoul(argv[++i], NULL, 10);
            if (port == 0 || port > 65535 - 3 * MAX_NODES) {
                usage(argv[0]);
                return 2;
            }
            g_base_port = (uint16_t)port;
        } else if (!strcmp(argv[i], "--founder-log") && i + 1 < argc) {
            g_founder_log = argv[++i];
        } else if (!strcmp(argv[i], "--preauth") && i + 1 < argc) {
            preauth = argv[++i];
        } else if (!strcmp(argv[i], "--make-identity") && i + 1 < argc) {
            identity = argv[++i];
        } else if (!strcmp(argv[i], "--mesh") && i + 1 < argc) {
            mesh = argv[++i];
        } else if (!strcmp(argv[i], "--restart-mute")) {
            restart_mute = true;
        } else {
            usage(argv[0]);
            return 2;
        }
    }
    if (identity) {
        return make_identity(identity);
    }
    if (mesh) {
        return run_mesh(mesh);
    }
    if (!g_data_dir[0]) {
        usage(argv[0]);
        return 2;
    }
    const int rc = preauth ? run_preauth(preauth) : restart_mute ? run_restart_mute() : run_full();
    if (rc != 0) {
        cleanup();
        return rc;
    }
    say("RESULT: %u passed, %u failed", g_pass, g_fail);
    return g_fail == 0 ? 0 : 1;
}
