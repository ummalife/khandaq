/*
 * Khandaq demo contact probe.
 *
 * Plays the part of a fresh iPhone: a new identity that adds the demo contact and checks, one by
 * one, everything App Review is told it can try -- the welcome pack, msgV3 ACKs, reactions, edit and
 * delete-for-both, file and voice-message echo, the group invitation and group replies, an audio
 * call, a video call and a "call me" callback. It speaks the same wire format as the clients
 * (msgV3 framing, "KQ" packets) because it links the same toxcore.
 *
 * Exit status 0 only when every check passed, so it can gate a deploy or run as a health check.
 *
 *   khandaq-demo-probe --bot TOX_ID [--nodes FILE] [--lan] [--quick]
 */

#define _GNU_SOURCE

#include <errno.h>
#include <math.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <time.h>
#include <unistd.h>

#include "toxav/toxav.h"
#include "toxcore/tox.h"

#define MSGV3_GUARD_LEN  2
#define MSGV3_ID_LEN     32
#define MSGV3_SUFFIX_LEN (MSGV3_GUARD_LEN + MSGV3_ID_LEN + 4)
#define MAX_EVENTS       256
#define MAX_FILES        32

static Tox *g_tox;
static ToxAV *g_av;
static uint32_t g_bot = UINT32_MAX;
static uint64_t g_t0;
static bool g_friend_online;
static bool g_dht;

static uint64_t mono_ms(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000u + (uint64_t)ts.tv_nsec / 1000000u;
}

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

static void put_u32_be(uint8_t *p, uint32_t v)
{
    p[0] = (uint8_t)(v >> 24);
    p[1] = (uint8_t)(v >> 16);
    p[2] = (uint8_t)(v >> 8);
    p[3] = (uint8_t)v;
}

static bool hex_to_bin(const char *hex, uint8_t *out, size_t len)
{
    if (strlen(hex) < 2 * len) {
        return false;
    }
    for (size_t i = 0; i < len; i++) {
        unsigned v;
        if (sscanf(hex + 2 * i, "%2x", &v) != 1) {
            return false;
        }
        out[i] = (uint8_t)v;
    }
    return true;
}

/* ---- recorded events ------------------------------------------------------------------------- */

typedef struct {
    char *text;
    bool v3;
    uint8_t id[MSGV3_ID_LEN];
    uint64_t at;
} Text_Event;

typedef struct {
    uint8_t type;
    uint8_t anchor[MSGV3_ID_LEN];
    uint64_t at;
} Packet_Event;

typedef struct {
    bool used;
    uint32_t file_number;
    uint32_t kind;
    char name[256];
    uint64_t size;
    uint64_t received;
    uint8_t *data;
    bool complete;
} File_Event;

static Text_Event g_texts[MAX_EVENTS];
static unsigned g_text_count;
static Packet_Event g_packets[MAX_EVENTS];
static unsigned g_packet_count;
static uint8_t g_acks[MAX_EVENTS][MSGV3_ID_LEN];
static unsigned g_ack_count;
static File_Event g_files[MAX_FILES];
static char *g_group_texts[MAX_EVENTS];
static unsigned g_group_text_count;
static uint8_t *g_invite;
static size_t g_invite_len;
static uint32_t g_group = UINT32_MAX;
static bool g_group_joined;

/* av state */
static bool g_incoming_call;
static bool g_incoming_video;
static uint32_t g_call_state;
static bool g_call_finished;
static unsigned g_audio_frames;
static unsigned g_audio_frames_window;
static double g_audio_energy_window;
static bool g_window_open;
static unsigned g_video_frames;

/* ---- callbacks ------------------------------------------------------------------------------- */

static void send_ack(uint32_t fn, const uint8_t *id, uint32_t ts)
{
    uint8_t buf[1 + MSGV3_SUFFIX_LEN] = {'_', 0, 0};
    memcpy(buf + 3, id, MSGV3_ID_LEN);
    put_u32_be(buf + 3 + MSGV3_ID_LEN, ts);
    tox_friend_send_message(g_tox, fn, TOX_MESSAGE_TYPE_HIGH_LEVEL_ACK, buf, sizeof buf, NULL);
}

static void on_self(Tox *tox, TOX_CONNECTION c, void *ud)
{
    g_dht = c != TOX_CONNECTION_NONE;
}

static void on_conn(Tox *tox, uint32_t fn, TOX_CONNECTION c, void *ud)
{
    if (fn == g_bot) {
        g_friend_online = c != TOX_CONNECTION_NONE;
        say("bot %s", g_friend_online ? (c == TOX_CONNECTION_UDP ? "online (udp)" : "online (tcp)") : "offline");
    }
}

static void on_message(Tox *tox, uint32_t fn, TOX_MESSAGE_TYPE type, const uint8_t *msg, size_t len, void *ud)
{
    bool v3 = false;
    uint8_t id[MSGV3_ID_LEN] = {0};
    size_t text_len = len;
    uint32_t ts = 0;
    if (len > MSGV3_SUFFIX_LEN && msg[len - MSGV3_SUFFIX_LEN] == 0 && msg[len - MSGV3_SUFFIX_LEN + 1] == 0) {
        v3 = true;
        text_len = len - MSGV3_SUFFIX_LEN;
        memcpy(id, msg + text_len + MSGV3_GUARD_LEN, MSGV3_ID_LEN);
        const uint8_t *p = msg + text_len + MSGV3_GUARD_LEN + MSGV3_ID_LEN;
        ts = (uint32_t)p[0] << 24 | (uint32_t)p[1] << 16 | (uint32_t)p[2] << 8 | p[3];
    }
    if (type == TOX_MESSAGE_TYPE_HIGH_LEVEL_ACK) {
        if (v3 && g_ack_count < MAX_EVENTS) {
            memcpy(g_acks[g_ack_count++], id, MSGV3_ID_LEN);
        }
        return;
    }
    if (v3) {
        send_ack(fn, id, ts); /* what the iPhone does on every msgV3 message */
    }
    if (g_text_count < MAX_EVENTS) {
        Text_Event *e = &g_texts[g_text_count++];
        e->text = strndup((const char *)msg, strnlen((const char *)msg, text_len));
        e->v3 = v3;
        memcpy(e->id, id, MSGV3_ID_LEN);
        e->at = mono_ms();
        say("text%s: %.70s%s", v3 ? "" : " (no msgV3)", e->text, strlen(e->text) > 70 ? "..." : "");
    }
}

static void on_lossless(Tox *tox, uint32_t fn, const uint8_t *data, size_t len, void *ud)
{
    if (len < 4 || g_packet_count >= MAX_EVENTS) {
        return;
    }
    Packet_Event *e = &g_packets[g_packet_count];
    e->type = data[0];
    e->at = mono_ms();
    if ((data[0] == 186 || data[0] == 187) && len >= 36) {
        memcpy(e->anchor, data + 4, MSGV3_ID_LEN);
    } else if (data[0] == 188 && len >= 37) {
        memcpy(e->anchor, data + 5, MSGV3_ID_LEN);
    } else {
        return;
    }
    g_packet_count++;
    say("packet %u", data[0]);
}

/* toxcore reuses file numbers once a transfer ends, so only a transfer still in flight may match. */
static File_Event *file_find(uint32_t file_number)
{
    for (int i = 0; i < MAX_FILES; i++) {
        if (g_files[i].used && !g_files[i].complete && g_files[i].file_number == file_number) {
            return &g_files[i];
        }
    }
    return NULL;
}

static void on_file_recv(Tox *tox, uint32_t fn, uint32_t file_number, uint32_t kind, uint64_t size,
                         const uint8_t *name, size_t name_len, void *ud)
{
    for (int i = 0; i < MAX_FILES; i++) {
        if (!g_files[i].used && size > 0 && size < 25u * 1024u * 1024u) {
            File_Event *f = &g_files[i];
            memset(f, 0, sizeof *f);
            f->used = true;
            f->file_number = file_number;
            f->kind = kind;
            f->size = size;
            snprintf(f->name, sizeof f->name, "%.*s", (int)(name_len < 255 ? name_len : 255), (const char *)name);
            f->data = malloc((size_t)size);
            say("file offered: %s (%llu bytes, kind %u)", f->name, (unsigned long long)size, kind);
            tox_file_control(tox, fn, file_number, TOX_FILE_CONTROL_RESUME, NULL);
            return;
        }
    }
    tox_file_control(tox, fn, file_number, TOX_FILE_CONTROL_CANCEL, NULL);
}

static void on_file_chunk(Tox *tox, uint32_t fn, uint32_t file_number, uint64_t pos, const uint8_t *data, size_t len,
                          void *ud)
{
    File_Event *f = file_find(file_number);
    if (!f || f->complete) {
        return;
    }
    if (len == 0) {
        f->complete = f->received >= f->size;
        say("file %s %s", f->name, f->complete ? "complete" : "INCOMPLETE");
        return;
    }
    if (f->data && pos + len <= f->size) {
        memcpy(f->data + pos, data, len);
        if (pos + len > f->received) {
            f->received = pos + len;
        }
    }
}

/* outgoing test files */
typedef struct {
    bool used;
    uint32_t file_number;
    uint8_t *data;
    size_t size;
} Out_File;
static Out_File g_out[4];

static void on_chunk_request(Tox *tox, uint32_t fn, uint32_t file_number, uint64_t pos, size_t len, void *ud)
{
    for (int i = 0; i < 4; i++) {
        if (g_out[i].used && g_out[i].file_number == file_number) {
            if (len == 0) {
                g_out[i].used = false;
                return;
            }
            if (pos + len > g_out[i].size) {
                len = g_out[i].size - pos;
            }
            tox_file_send_chunk(tox, fn, file_number, pos, g_out[i].data + pos, len, NULL);
            return;
        }
    }
}

static void on_group_invite(Tox *tox, uint32_t fn, const uint8_t *data, size_t len, const uint8_t *name, size_t nlen,
                            void *ud)
{
    free(g_invite);
    g_invite = malloc(len);
    if (g_invite) {
        memcpy(g_invite, data, len);
        g_invite_len = len;
    }
    say("group invitation: %.*s", (int)nlen, (const char *)name);
}

static void on_group_self_join(Tox *tox, uint32_t gn, void *ud)
{
    if (gn == g_group) {
        g_group_joined = true;
        say("joined the group");
    }
}

static void on_group_message(Tox *tox, uint32_t gn, uint32_t peer, TOX_MESSAGE_TYPE type, const uint8_t *msg,
                             size_t len, uint32_t mid, void *ud)
{
    if (g_group_text_count < MAX_EVENTS) {
        g_group_texts[g_group_text_count++] = strndup((const char *)msg, len);
        say("group text: %.70s", g_group_texts[g_group_text_count - 1]);
    }
}

static void on_av_call(ToxAV *av, uint32_t fn, bool a, bool v, void *ud)
{
    g_incoming_call = true;
    g_incoming_video = v;
    say("incoming %s call from the bot", v ? "video" : "audio");
}

static void on_av_state(ToxAV *av, uint32_t fn, uint32_t state, void *ud)
{
    g_call_state = state;
    if (state & (TOXAV_FRIEND_CALL_STATE_FINISHED | TOXAV_FRIEND_CALL_STATE_ERROR)) {
        g_call_finished = true;
    }
}

static void on_av_audio(ToxAV *av, uint32_t fn, const int16_t *pcm, size_t samples, uint8_t channels, uint32_t rate,
                        void *ud)
{
    g_audio_frames++;
    if (g_window_open) {
        double sum = 0;
        for (size_t i = 0; i < samples * channels; i++) {
            sum += (double)pcm[i] * pcm[i];
        }
        g_audio_frames_window++;
        g_audio_energy_window += sqrt(sum / (double)(samples * channels));
    }
}

static void on_av_video(ToxAV *av, uint32_t fn, uint16_t w, uint16_t h, const uint8_t *y, const uint8_t *u,
                        const uint8_t *v, int32_t ys, int32_t us, int32_t vs, void *ud)
{
    g_video_frames++;
}

/* ---- loop helpers ---------------------------------------------------------------------------- */

typedef bool Predicate(void *ctx);

static void spin(uint32_t ms)
{
    const uint64_t end = mono_ms() + ms;
    while (mono_ms() < end) {
        tox_iterate(g_tox, NULL);
        toxav_iterate(g_av);
        usleep(5000);
    }
}

static bool wait_for(Predicate *p, void *ctx, uint32_t timeout_ms)
{
    const uint64_t end = mono_ms() + timeout_ms;
    while (mono_ms() < end) {
        tox_iterate(g_tox, NULL);
        toxav_iterate(g_av);
        if (p(ctx)) {
            return true;
        }
        usleep(5000);
    }
    return p(ctx);
}

static bool p_dht(void *ctx) { return g_dht; }
static bool p_online(void *ctx) { return g_friend_online; }

typedef struct {
    unsigned from;
    const char *needle;
} Text_Wait;

static bool p_text(void *ctx)
{
    Text_Wait *w = ctx;
    for (unsigned i = w->from; i < g_text_count; i++) {
        if (strcasestr(g_texts[i].text, w->needle)) {
            return true;
        }
    }
    return false;
}

static const Text_Event *find_text(unsigned from, const char *needle)
{
    for (unsigned i = from; i < g_text_count; i++) {
        if (strcasestr(g_texts[i].text, needle)) {
            return &g_texts[i];
        }
    }
    return NULL;
}

typedef struct {
    uint8_t type;
    const uint8_t *anchor;
} Packet_Wait;

static bool p_packet(void *ctx)
{
    Packet_Wait *w = ctx;
    for (unsigned i = 0; i < g_packet_count; i++) {
        if (g_packets[i].type == w->type && memcmp(g_packets[i].anchor, w->anchor, MSGV3_ID_LEN) == 0) {
            return true;
        }
    }
    return false;
}

static bool p_ack(void *ctx)
{
    for (unsigned i = 0; i < g_ack_count; i++) {
        if (memcmp(g_acks[i], ctx, MSGV3_ID_LEN) == 0) {
            return true;
        }
    }
    return false;
}

typedef struct {
    const char *suffix_or_name;
    bool prefix_match;
} File_Wait;

static File_Event *find_file(const char *needle, bool complete_only)
{
    for (int i = 0; i < MAX_FILES; i++) {
        if (g_files[i].used && strcasestr(g_files[i].name, needle) && (!complete_only || g_files[i].complete)) {
            return &g_files[i];
        }
    }
    return NULL;
}

static bool p_file_complete(void *ctx) { return find_file(ctx, true) != NULL; }
static bool p_invite(void *ctx) { return g_invite != NULL; }
static bool p_group_joined(void *ctx) { return g_group_joined; }

typedef struct {
    unsigned from;
    const char *needle;
} Group_Wait;

static bool p_group_text(void *ctx)
{
    Group_Wait *w = ctx;
    for (unsigned i = w->from; i < g_group_text_count; i++) {
        if (strcasestr(g_group_texts[i], w->needle)) {
            return true;
        }
    }
    return false;
}

static bool p_call_live(void *ctx)
{
    return (g_call_state & (TOXAV_FRIEND_CALL_STATE_SENDING_A | TOXAV_FRIEND_CALL_STATE_ACCEPTING_A)) != 0 ||
           g_call_finished;
}

static bool p_incoming_call(void *ctx) { return g_incoming_call; }

static bool send_text(const char *text, uint8_t *id_out)
{
    uint8_t buf[TOX_MAX_MESSAGE_LENGTH];
    uint8_t id[MSGV3_ID_LEN];
    const size_t len = strlen(text);
    tox_messagev3_get_new_message_id(id);
    memcpy(buf, text, len);
    buf[len] = 0;
    buf[len + 1] = 0;
    memcpy(buf + len + 2, id, MSGV3_ID_LEN);
    put_u32_be(buf + len + 2 + MSGV3_ID_LEN, (uint32_t)time(NULL));
    Tox_Err_Friend_Send_Message err;
    tox_friend_send_message(g_tox, g_bot, TOX_MESSAGE_TYPE_NORMAL, buf, len + MSGV3_SUFFIX_LEN, &err);
    if (id_out) {
        memcpy(id_out, id, MSGV3_ID_LEN);
    }
    return err == TOX_ERR_FRIEND_SEND_MESSAGE_OK;
}

static bool send_file(const char *name, size_t size, uint8_t **data_out)
{
    for (int i = 0; i < 4; i++) {
        if (!g_out[i].used) {
            uint8_t *data = malloc(size);
            if (!data) return false;
            for (size_t k = 0; k < size; k++) data[k] = (uint8_t)(rand() & 0xFF);
            Tox_Err_File_Send err;
            const uint32_t num = tox_file_send(g_tox, g_bot, TOX_FILE_KIND_DATA, size, NULL, (const uint8_t *)name,
                                               strlen(name), &err);
            if (err != TOX_ERR_FILE_SEND_OK) {
                free(data);
                return false;
            }
            g_out[i] = (Out_File){true, num, data, size};
            *data_out = data;
            return true;
        }
    }
    return false;
}

/* Stream a call for duration_ms: 440 Hz sine audio, and moving I420 video when with_video. */
static void stream_call(uint32_t duration_ms, bool with_video, uint32_t window_from_ms)
{
    static int16_t pcm[960];
    static uint8_t frame[320 * 240 * 3 / 2];
    const uint64_t start = mono_ms();
    uint64_t next_audio = start;
    uint64_t next_video = start;
    double phase = 0;
    unsigned tick = 0;
    while (mono_ms() - start < duration_ms && !g_call_finished) {
        const uint64_t now = mono_ms();
        g_window_open = now - start >= window_from_ms;
        while (now >= next_audio) {
            for (int i = 0; i < 960; i++) {
                pcm[i] = (int16_t)(8000.0 * sin(phase));
                phase += 2.0 * M_PI * 440.0 / 48000.0;
            }
            toxav_audio_send_frame(g_av, g_bot, pcm, 960, 1, 48000, NULL);
            next_audio += 20;
        }
        if (with_video && now >= next_video) {
            memset(frame, (int)(16 + (tick * 5) % 200), 320 * 240);
            memset(frame + 320 * 240, 128, 320 * 240 / 2);
            toxav_video_send_frame(g_av, g_bot, 320, 240, frame, frame + 320 * 240, frame + 320 * 240 * 5 / 4, NULL);
            tick++;
            next_video = now + 100;
        }
        tox_iterate(g_tox, NULL);
        toxav_iterate(g_av);
        usleep(4000);
    }
    g_window_open = false;
}

/* ---- checks ---------------------------------------------------------------------------------- */

static unsigned g_pass, g_fail;

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

static void bootstrap(const char *path)
{
    FILE *f = fopen(path, "r");
    if (!f) {
        say("nodes file %s: %s", path, strerror(errno));
        return;
    }
    char line[512];
    while (fgets(line, sizeof line, f)) {
        char host[256], key[80], tcp[128] = "";
        unsigned port;
        uint8_t pk[TOX_PUBLIC_KEY_SIZE];
        if (line[0] == '#' || sscanf(line, "%255s %u %79s %127s", host, &port, key, tcp) < 3 ||
            !hex_to_bin(key, pk, sizeof pk)) {
            continue;
        }
        tox_bootstrap(g_tox, host, (uint16_t)port, pk, NULL);
        for (char *tok = strtok(tcp, ","); tok; tok = strtok(NULL, ",")) {
            tox_add_tcp_relay(g_tox, host, (uint16_t)atoi(tok), pk, NULL);
        }
    }
    fclose(f);
}

int main(int argc, char **argv)
{
    const char *bot_hex = NULL;
    const char *nodes = "assets/nodes.txt";
    bool lan = false;
    bool quick = false;
    for (int i = 1; i < argc; i++) {
        if (!strcmp(argv[i], "--bot") && i + 1 < argc) bot_hex = argv[++i];
        else if (!strcmp(argv[i], "--nodes") && i + 1 < argc) nodes = argv[++i];
        else if (!strcmp(argv[i], "--lan")) lan = true;
        else if (!strcmp(argv[i], "--quick")) quick = true;
    }
    uint8_t bot_address[TOX_ADDRESS_SIZE];
    if (!bot_hex || !hex_to_bin(bot_hex, bot_address, sizeof bot_address)) {
        fprintf(stderr, "usage: %s --bot TOX_ID [--nodes FILE] [--lan] [--quick]\n", argv[0]);
        return 2;
    }
    srand((unsigned)time(NULL));
    g_t0 = mono_ms();

    struct Tox_Options *o = tox_options_new(NULL);
    tox_options_set_local_discovery_enabled(o, lan);
    tox_options_set_start_port(o, 33500);
    tox_options_set_end_port(o, 33600);
    g_tox = tox_new(o, NULL);
    tox_options_free(o);
    g_av = toxav_new(g_tox, NULL);
    if (!g_tox || !g_av) {
        say("tox init failed");
        return 1;
    }
    tox_self_set_name(g_tox, (const uint8_t *)"Khandaq Probe", 13, NULL);
    tox_callback_self_connection_status(g_tox, on_self);
    tox_callback_friend_connection_status(g_tox, on_conn);
    tox_callback_friend_message(g_tox, on_message);
    tox_callback_friend_lossless_packet(g_tox, on_lossless);
    tox_callback_file_recv(g_tox, on_file_recv);
    tox_callback_file_recv_chunk(g_tox, on_file_chunk);
    tox_callback_file_chunk_request(g_tox, on_chunk_request);
    tox_callback_group_invite(g_tox, on_group_invite);
    tox_callback_group_self_join(g_tox, on_group_self_join);
    tox_callback_group_message(g_tox, on_group_message);
    toxav_callback_call(g_av, on_av_call, NULL);
    toxav_callback_call_state(g_av, on_av_state, NULL);
    toxav_callback_audio_receive_frame(g_av, on_av_audio, NULL);
    toxav_callback_video_receive_frame(g_av, on_av_video, NULL);

    bootstrap(nodes);
    const bool dht = wait_for(p_dht, NULL, 90000);
    check(dht, "joined the Tox network");
    const uint64_t t_dht = mono_ms() - g_t0;

    Tox_Err_Friend_Add add_err;
    g_bot = tox_friend_add(g_tox, bot_address, (const uint8_t *)"Hello from the probe", 20, &add_err);
    if (add_err != TOX_ERR_FRIEND_ADD_OK) {
        say("friend add failed (%d)", (int)add_err);
        return 1;
    }
    const uint64_t t_add = mono_ms();
    const bool online = wait_for(p_online, NULL, 180000);
    check(online, "demo contact accepted the request and came online");
    if (!online) {
        return 1;
    }
    const double t_online_s = (double)(mono_ms() - t_add) / 1000.0;
    say("time from contact request to online: %.1f s (DHT took %.1f s)", t_online_s, (double)t_dht / 1000.0);

    /* Welcome pack: greeting, instructions, photo, voice, document, location, group invite. */
    Text_Wait tw = {0, "That's everything"};
    wait_for(p_text, &tw, 45000);
    spin(4000); /* let the last files finish */
    check(find_text(0, "I'm Khandaq Demo") != NULL, "welcome greeting received");
    check(find_text(0, "what you can try") != NULL, "instructions received");
    check(find_text(0, "khandaq-location:") != NULL, "location message received");
    check(g_text_count > 0 && g_texts[0].v3, "bot messages carry msgV3 ids");
    check(wait_for(p_file_complete, (void *)"khandaq-demo-photo.jpg", 30000), "photo downloaded");
    check(wait_for(p_file_complete, (void *)".file.m4a", 30000), "voice message downloaded");
    check(wait_for(p_file_complete, (void *)"Khandaq-demo.pdf", 30000), "document downloaded");
    check(wait_for(p_file_complete, (void *)"avatar.png", 15000), "avatar received");
    check(wait_for(p_invite, NULL, 20000), "group invitation received");

    /* Group: accept, expect a welcome, then a reply to our message. */
    if (g_invite) {
        Tox_Err_Group_Invite_Accept gerr;
        g_group = tox_group_invite_accept(g_tox, g_bot, g_invite, g_invite_len, (const uint8_t *)"Probe", 5, NULL, 0,
                                          &gerr);
        check(gerr == TOX_ERR_GROUP_INVITE_ACCEPT_OK, "group invitation accepted");
        check(wait_for(p_group_joined, NULL, 60000), "joined the demo group");
        Group_Wait gw = {0, "Welcome to the group"};
        check(wait_for(p_group_text, &gw, 60000), "group welcome message");
        const unsigned from = g_group_text_count;
        tox_group_send_message(g_tox, g_group, TOX_MESSAGE_TYPE_NORMAL, (const uint8_t *)"probe group hello", 17,
                               NULL, NULL);
        Group_Wait gr = {from, "got your message in the group"};
        check(wait_for(p_group_text, &gr, 30000), "bot replied in the group");
    }

    /* Text, ACK, reply and reaction. */
    uint8_t id[MSGV3_ID_LEN];
    unsigned from = g_text_count;
    send_text("hello from the probe", id);
    check(wait_for(p_ack, id, 15000), "msgV3 high-level ACK for our message");
    Text_Wait reply = {from, "You wrote"};
    check(wait_for(p_text, &reply, 15000), "reply to our message");
    Packet_Wait react = {188, id};
    check(wait_for(p_packet, &react, 15000), "reaction to our message (packet 188)");

    /* A resend of the same message must be ACKed again but not answered twice. */
    {
        uint8_t buf[64];
        const char *t = "hello from the probe";
        const size_t len = strlen(t);
        memcpy(buf, t, len);
        buf[len] = buf[len + 1] = 0;
        memcpy(buf + len + 2, id, MSGV3_ID_LEN);
        put_u32_be(buf + len + 2 + MSGV3_ID_LEN, (uint32_t)time(NULL));
        const unsigned before = g_text_count;
        tox_friend_send_message(g_tox, g_bot, TOX_MESSAGE_TYPE_NORMAL, buf, len + MSGV3_SUFFIX_LEN, NULL);
        spin(4000);
        check(find_text(before, "You wrote") == NULL, "duplicate (resent) message not answered twice");
    }

    /* Edit and delete-for-both demos. */
    from = g_text_count;
    send_text("edit", NULL);
    Text_Wait ew = {from, "will be edited"};
    if (wait_for(p_text, &ew, 15000)) {
        const Text_Event *t = find_text(from, "will be edited");
        Packet_Wait pw = {186, t->id};
        check(wait_for(p_packet, &pw, 15000), "edit packet for the bot's own message (186)");
    } else {
        check(false, "edit demo message");
    }
    from = g_text_count;
    send_text("delete", NULL);
    Text_Wait dw = {from, "will be deleted"};
    if (wait_for(p_text, &dw, 15000)) {
        const Text_Event *t = find_text(from, "will be deleted");
        Packet_Wait pw = {187, t->id};
        check(wait_for(p_packet, &pw, 15000), "delete-for-both packet for the bot's own message (187)");
    } else {
        check(false, "delete demo message");
    }

    /* File echo and voice-message echo. */
    uint8_t *sent = NULL;
    if (send_file("probe.bin", 50000, &sent)) {
        const bool got = wait_for(p_file_complete, (void *)"echo_probe.bin", 45000);
        File_Event *f = find_file("echo_probe.bin", true);
        check(got && f && f->size == 50000 && memcmp(f->data, sent, 50000) == 0, "file sent back byte for byte");
    }
    if (send_file("voice_probe.file.m4a", 20000, &sent)) {
        const bool got = wait_for(p_file_complete, (void *)"voice_echo_", 45000);
        File_Event *f = find_file("voice_echo_", true);
        check(got && f && f->size == 20000 && strstr(f->name, ".file.m4a") && memcmp(f->data, sent, 20000) == 0,
              "voice message sent back as a voice message");
    }

    if (!quick) {
        /* Audio call: greeting first, then our own 440 Hz tone echoed back. */
        g_call_state = 0;
        g_call_finished = false;
        g_audio_frames = g_audio_frames_window = 0;
        g_audio_energy_window = 0;
        toxav_call(g_av, g_bot, 48, 0, NULL);
        const bool live = wait_for(p_call_live, NULL, 20000) && !g_call_finished;
        check(live, "audio call answered");
        if (live) {
            stream_call(26000, false, 19000);
            const double avg = g_audio_frames_window ? g_audio_energy_window / g_audio_frames_window : 0;
            say("audio: %u frames received, %u in the echo window, mean RMS %.0f", g_audio_frames,
                g_audio_frames_window, avg);
            check(g_audio_frames_window > 100 && avg > 1000, "audio echo after the greeting");
            toxav_call_control(g_av, g_bot, TOXAV_CALL_CONTROL_CANCEL, NULL);
            spin(2000);
        }

        /* Video call: our moving frames come back. */
        g_call_state = 0;
        g_call_finished = false;
        g_video_frames = 0;
        toxav_call(g_av, g_bot, 48, 600, NULL);
        const bool vlive = wait_for(p_call_live, NULL, 20000) && !g_call_finished;
        check(vlive, "video call answered");
        if (vlive) {
            stream_call(10000, true, 0);
            say("video: %u frames received", g_video_frames);
            check(g_video_frames > 20, "video echoed back");
            toxav_call_control(g_av, g_bot, TOXAV_CALL_CONTROL_CANCEL, NULL);
            spin(2000);
        }

        /* "call me": the bot calls us within about ten seconds. */
        g_incoming_call = false;
        g_call_state = 0;
        g_call_finished = false;
        g_audio_frames = 0;
        send_text("call me", NULL);
        const bool rang = wait_for(p_incoming_call, NULL, 25000);
        check(rang, "\"call me\" made the bot call us");
        if (rang) {
            toxav_answer(g_av, g_bot, 48, 0, NULL);
            stream_call(6000, false, 0);
            check(g_audio_frames > 100, "audio received in the bot's call");
            toxav_call_control(g_av, g_bot, TOXAV_CALL_CONTROL_CANCEL, NULL);
            spin(2000);
        }
    }

    say("RESULT: %u passed, %u failed; contact online %.1f s after the request", g_pass, g_fail, t_online_s);
    toxav_kill(g_av);
    tox_kill(g_tox);
    return g_fail == 0 ? 0 : 1;
}
