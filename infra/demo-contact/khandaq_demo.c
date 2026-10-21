/*
 * Khandaq demo contact.
 *
 * An always-online Tox peer that gives anyone holding a single device -- App Review above all -- a
 * counterpart for every feature of the Khandaq clients. It accepts contact requests, fills the new
 * chat with a photo, a voice message, a document, a location and a group invitation, answers
 * messages and reacts to them, sends received files back, demonstrates edit and delete-for-both,
 * answers audio and video calls with an echo of the caller's own voice and camera, and on request
 * calls the user back so the incoming-call screen can be seen.
 *
 * It links the toxcore the iOS client ships (see build.sh), so msgV3 framing, the "KQ" lossless
 * packets, NGC groups and toxav behave exactly as they do between two phones.
 *
 * Single-threaded on purpose: tox_iterate and toxav_iterate share one loop, every tox_* and toxav_*
 * call is made from that loop, and toxav callbacks only copy data into queues. toxcore is not
 * thread-safe, and calling toxav from inside its own callbacks is not re-entrant either.
 *
 * Privacy: message text and file contents are never logged or written to disk. Received files are
 * held in memory only for as long as it takes to send them back.
 */

#define _GNU_SOURCE

#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <limits.h>
#include <signal.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#include "toxav/toxav.h"
#include "toxcore/tox.h"

#ifndef PATH_MAX
#define PATH_MAX 4096
#endif

#define BOT_NAME    "Khandaq Demo"
#define BOT_STATUS  "Always-online demo contact. Say hi, send me a file or call me: I answer automatically."
#define GROUP_NAME  "Khandaq Demo Group"
#define GROUP_TOPIC "Demo group run by the Khandaq team. Write anything and the demo contact answers."

/* Apple Park: a location every reviewer recognises on the map card. */
#define DEMO_LOCATION "khandaq-location:37.33490,-122.00900"

#define MAX_FRIENDS            2000
#define FRIEND_EXPIRY_S        (30u * 24u * 3600u)
#define WELCOME_BACK_AFTER_S   (6u * 3600u)
#define WELCOME_PACK_EXPIRY_MS (24ull * 3600ull * 1000ull)

/* msgV3 framing used by the Khandaq clients: text, two NUL guard bytes, 32-byte id, u32 BE time. */
#define MSGV3_GUARD_LEN  2
#define MSGV3_ID_LEN     32
#define MSGV3_TS_LEN     4
#define MSGV3_SUFFIX_LEN (MSGV3_GUARD_LEN + MSGV3_ID_LEN + MSGV3_TS_LEN)
#define MSGV3_MAX_TEXT   (TOX_MAX_MESSAGE_LENGTH - MSGV3_SUFFIX_LEN)
#define SEEN_IDS         64
#define OUTBOX_SIZE      32
#define OUTBOX_MAX_TRIES 4
#define OUTBOX_TTL_S     (24u * 3600u)

/* Khandaq lossless packets ("KQ" family), byte layouts as parsed by objcTox OCTTox.m. */
#define PKT_PUSH_TOKEN           181
#define PKT_GROUP_INVITE_REQUEST 184
#define PKT_EDIT                 186
#define PKT_DELETE               187
#define PKT_REACTION             188

#define MAX_TRANSFERS          48
#define MAX_INCOMING_TRANSFERS 6
#define MAX_ECHO_FILE_BYTES    (20u * 1024u * 1024u)
#define MAX_VOICE_NOTE_BYTES   (4u * 1024u * 1024u)
#define TRANSFER_STALL_MS      (120u * 1000u)

#define MAX_CALLS                4
#define AUDIO_BITRATE_KBPS       48
#define VIDEO_BITRATE_KBPS       900
#define RING_BEFORE_ANSWER_MS    1500
#define CALL_ME_DELAY_MS         10000
#define OUTGOING_RING_TIMEOUT_MS 45000
#define CALL_MAX_MS              (5u * 60u * 1000u)
#define AUDIO_QUEUE_FRAMES       32
#define AUDIO_MAX_FRAME_SAMPLES  5760 /* 60 ms at 48 kHz, stereo: the largest frame toxav emits */
#define GREETING_FRAME_SAMPLES   960  /* 20 ms at 48 kHz */
#define VIDEO_ECHO_INTERVAL_MS   66
#define PATTERN_INTERVAL_MS      100
#define PATTERN_W                360
#define PATTERN_H                640
#define MAX_ECHO_DIMENSION       640

#define MAX_FOREIGN_GROUPS   16
#define FOREIGN_GROUP_TTL_MS (24ull * 3600ull * 1000ull)

#define TOKENS_MAX         10.0
#define TOKEN_REFILL_MS    2000.0

/* ---------------------------------------------------------------------------------------------- */
/* small helpers                                                                                   */
/* ---------------------------------------------------------------------------------------------- */

static uint64_t g_start_ms;
static volatile sig_atomic_t g_running = 1;

static uint64_t mono_ms(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000u + (uint64_t)ts.tv_nsec / 1000000u;
}

static uint32_t unix_now(void)
{
    return (uint32_t)time(NULL);
}

static void log_line(const char *fmt, ...) __attribute__((format(printf, 1, 2)));
static void log_line(const char *fmt, ...)
{
    va_list ap;
    fprintf(stderr, "[%8.1f] ", (double)(mono_ms() - g_start_ms) / 1000.0);
    va_start(ap, fmt);
    vfprintf(stderr, fmt, ap);
    va_end(ap);
    fputc('\n', stderr);
    fflush(stderr);
}

static void put_u32_be(uint8_t *p, uint32_t v)
{
    p[0] = (uint8_t)(v >> 24);
    p[1] = (uint8_t)(v >> 16);
    p[2] = (uint8_t)(v >> 8);
    p[3] = (uint8_t)v;
}

static uint32_t get_u32_be(const uint8_t *p)
{
    return ((uint32_t)p[0] << 24) | ((uint32_t)p[1] << 16) | ((uint32_t)p[2] << 8) | (uint32_t)p[3];
}

static void bin_to_hex(const uint8_t *bin, size_t len, char *out)
{
    static const char digits[] = "0123456789ABCDEF";
    for (size_t i = 0; i < len; i++) {
        out[2 * i] = digits[bin[i] >> 4];
        out[2 * i + 1] = digits[bin[i] & 15];
    }
    out[2 * len] = '\0';
}

static int hex_value(char c)
{
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

static bool hex_to_bin(const char *hex, uint8_t *out, size_t len)
{
    if (strlen(hex) < 2 * len) {
        return false;
    }
    for (size_t i = 0; i < len; i++) {
        const int hi = hex_value(hex[2 * i]);
        const int lo = hex_value(hex[2 * i + 1]);
        if (hi < 0 || lo < 0) {
            return false;
        }
        out[i] = (uint8_t)(hi << 4 | lo);
    }
    return true;
}

/* First 8 hex digits of a public key: enough to follow one contact through the log, not more. */
static const char *pk_tag(const uint8_t *pk)
{
    static char buf[9];
    bin_to_hex(pk, 4, buf);
    return buf;
}

/* Cut a UTF-8 string to at most max bytes without splitting a code point. */
static size_t utf8_cut(const char *s, size_t len, size_t max)
{
    if (len <= max) {
        return len;
    }
    size_t cut = max;
    while (cut > 0 && ((unsigned char)s[cut] & 0xC0) == 0x80) {
        cut--;
    }
    return cut;
}

static void sleep_ms(uint32_t ms)
{
    struct timespec ts = {ms / 1000, (long)(ms % 1000) * 1000000L};
    while (nanosleep(&ts, &ts) == -1 && errno == EINTR && g_running) {
    }
}

static bool read_file(const char *path, uint8_t **data, size_t *size, size_t max)
{
    FILE *f = fopen(path, "rb");
    if (!f) {
        return false;
    }
    if (fseek(f, 0, SEEK_END) != 0) {
        fclose(f);
        return false;
    }
    const long len = ftell(f);
    if (len <= 0 || (size_t)len > max || fseek(f, 0, SEEK_SET) != 0) {
        fclose(f);
        return false;
    }
    uint8_t *buf = malloc((size_t)len);
    if (!buf || fread(buf, 1, (size_t)len, f) != (size_t)len) {
        free(buf);
        fclose(f);
        return false;
    }
    fclose(f);
    *data = buf;
    *size = (size_t)len;
    return true;
}

/* Write a file atomically: a crash mid-write must never leave a truncated identity behind. */
static bool write_file_atomic(const char *path, const uint8_t *data, size_t size)
{
    char tmp[PATH_MAX];
    if (snprintf(tmp, sizeof tmp, "%s.tmp", path) >= (int)sizeof tmp) {
        return false;
    }
    const int fd = open(tmp, O_WRONLY | O_CREAT | O_TRUNC, 0600);
    if (fd < 0) {
        return false;
    }
    size_t off = 0;
    while (off < size) {
        const ssize_t n = write(fd, data + off, size - off);
        if (n < 0) {
            if (errno == EINTR) continue;
            close(fd);
            unlink(tmp);
            return false;
        }
        off += (size_t)n;
    }
    if (fsync(fd) != 0 || close(fd) != 0) {
        unlink(tmp);
        return false;
    }
    return rename(tmp, path) == 0;
}

/* ---------------------------------------------------------------------------------------------- */
/* configuration and assets                                                                        */
/* ---------------------------------------------------------------------------------------------- */

static char g_data_dir[PATH_MAX] = "data";
static char g_assets_dir[PATH_MAX] = "assets";
static char g_nodes_path[PATH_MAX] = "";
static uint16_t g_port = 33445;
static bool g_lan = false;

typedef struct {
    uint8_t *data;
    size_t size;
    const char *file;      /* name in the assets directory */
    const char *wire_name; /* name the receiving client sees */
} Asset;

static Asset g_avatar = {NULL, 0, "avatar.png", "avatar.png"};
static Asset g_photo = {NULL, 0, "photo.jpg", "khandaq-demo-photo.jpg"};
/* The ".file.m4a" suffix is what every Khandaq client treats as a voice message. */
static Asset g_voice = {NULL, 0, "voice_welcome.file.m4a", "voice_welcome.file.m4a"};
static Asset g_document = {NULL, 0, "Khandaq-demo.pdf", "Khandaq-demo.pdf"};
static uint8_t g_avatar_hash[TOX_HASH_LENGTH];
static int16_t *g_greeting; /* 48 kHz mono */
static size_t g_greeting_samples;

/* Join a directory and a file name. A path that does not fit is a configuration error, not
 * something to truncate silently into a different file. */
static void path_join(char *out, size_t cap, const char *dir, const char *file)
{
    const int n = snprintf(out, cap, "%s/%s", dir, file);
    if (n < 0 || (size_t)n >= cap) {
        log_line("path too long: %s/%s", dir, file);
        exit(2);
    }
}

static void load_asset(Asset *a)
{
    char path[PATH_MAX];
    path_join(path, sizeof path, g_assets_dir, a->file);
    if (read_file(path, &a->data, &a->size, MAX_ECHO_FILE_BYTES)) {
        log_line("asset %s: %zu bytes", a->file, a->size);
    } else {
        log_line("asset %s: missing, the feature that uses it is skipped", a->file);
    }
}

/* greeting.pcm is 16 kHz mono s16le; it is upsampled once to the 48 kHz the clients send. */
static void load_greeting(void)
{
    char path[PATH_MAX];
    uint8_t *raw = NULL;
    size_t raw_size = 0;
    path_join(path, sizeof path, g_assets_dir, "greeting.pcm");
    if (!read_file(path, &raw, &raw_size, 4u * 1024u * 1024u) || raw_size < 4) {
        log_line("asset greeting.pcm: missing, calls start straight in echo mode");
        free(raw);
        return;
    }
    const size_t in_samples = raw_size / 2;
    g_greeting = malloc(in_samples * 3 * sizeof(int16_t));
    if (!g_greeting) {
        free(raw);
        return;
    }
    for (size_t i = 0; i < in_samples; i++) {
        const int16_t a = (int16_t)(raw[2 * i] | raw[2 * i + 1] << 8);
        const int16_t b = i + 1 < in_samples ? (int16_t)(raw[2 * i + 2] | raw[2 * i + 3] << 8) : a;
        g_greeting[3 * i] = a;
        g_greeting[3 * i + 1] = (int16_t)((2 * a + b) / 3);
        g_greeting[3 * i + 2] = (int16_t)((a + 2 * b) / 3);
    }
    g_greeting_samples = in_samples * 3;
    free(raw);
    log_line("asset greeting.pcm: %.1f s", (double)g_greeting_samples / 48000.0);
}

/* ---------------------------------------------------------------------------------------------- */
/* global Tox state                                                                                */
/* ---------------------------------------------------------------------------------------------- */

static Tox *g_tox;
static ToxAV *g_av;
static bool g_dirty;
static uint64_t g_last_save_ms;
static TOX_CONNECTION g_self_conn = TOX_CONNECTION_NONE;

static void save_tox(void)
{
    char path[PATH_MAX];
    const size_t size = tox_get_savedata_size(g_tox);
    uint8_t *buf = malloc(size);
    if (!buf) {
        return;
    }
    tox_get_savedata(g_tox, buf);
    path_join(path, sizeof path, g_data_dir, "khandaq-demo.tox");
    if (!write_file_atomic(path, buf, size)) {
        log_line("save failed: %s", strerror(errno));
    }
    free(buf);
    g_dirty = false;
    g_last_save_ms = mono_ms();
}

/* ---------------------------------------------------------------------------------------------- */
/* per-friend runtime state                                                                        */
/* ---------------------------------------------------------------------------------------------- */

/* Messages we sent and the client has not yet ACKed. A connection can die some seconds before
 * either side notices, and whatever was sent into that gap is gone: the clients ACK every msgV3
 * message, so anything still here when the contact reconnects is sent again, with its original id,
 * which the clients use to drop the copy if the first one did arrive after all. */
typedef struct {
    bool used;
    uint8_t id[MSGV3_ID_LEN];
    uint32_t ts;
    unsigned tries;
    char *text;
} Outbox_Entry;

typedef struct {
    bool online;
    uint64_t online_since_ms;
    double tokens;
    uint64_t tokens_ms;
    uint8_t seen[SEEN_IDS][MSGV3_ID_LEN];
    unsigned seen_count;
    unsigned seen_pos;
    unsigned echo_count;
    bool missed_call_note;
    Outbox_Entry outbox[OUTBOX_SIZE];
    unsigned outbox_pos;
} Friend_State;

static Friend_State *g_fs;
static size_t g_fs_len;

static Friend_State *fstate(uint32_t fn)
{
    if (fn >= g_fs_len) {
        const size_t len = (size_t)fn + 16;
        Friend_State *grown = realloc(g_fs, len * sizeof *grown);
        if (!grown) {
            log_line("out of memory for friend state");
            exit(1);
        }
        memset(grown + g_fs_len, 0, (len - g_fs_len) * sizeof *grown);
        for (size_t i = g_fs_len; i < len; i++) {
            grown[i].tokens = TOKENS_MAX;
        }
        g_fs = grown;
        g_fs_len = len;
    }
    return &g_fs[fn];
}

static void fstate_reset(uint32_t fn)
{
    Friend_State *st = fstate(fn);
    for (unsigned i = 0; i < OUTBOX_SIZE; i++) {
        free(st->outbox[i].text);
    }
    memset(st, 0, sizeof *st);
    st->tokens = TOKENS_MAX;
    st->tokens_ms = mono_ms();
}

/* A plain token bucket per contact: replies are cheap, but nobody gets to make the bot spam. */
static bool take_token(double *tokens, uint64_t *tokens_ms, uint64_t now)
{
    if (*tokens_ms == 0) {
        *tokens_ms = now;
    }
    *tokens += (double)(now - *tokens_ms) / TOKEN_REFILL_MS;
    if (*tokens > TOKENS_MAX) {
        *tokens = TOKENS_MAX;
    }
    *tokens_ms = now;
    if (*tokens < 1.0) {
        return false;
    }
    *tokens -= 1.0;
    return true;
}

static bool friend_online(uint32_t fn)
{
    Tox_Err_Friend_Query err;
    const TOX_CONNECTION c = tox_friend_get_connection_status(g_tox, fn, &err);
    return err == TOX_ERR_FRIEND_QUERY_OK && c != TOX_CONNECTION_NONE;
}

static bool friend_number(const uint8_t *pk, uint32_t *fn)
{
    Tox_Err_Friend_By_Public_Key err;
    *fn = tox_friend_by_public_key(g_tox, pk, &err);
    return err == TOX_ERR_FRIEND_BY_PUBLIC_KEY_OK;
}

/* The contact's display name, stripped of control characters and kept short for greetings. */
static void friend_name(uint32_t fn, char *out, size_t cap)
{
    uint8_t name[TOX_MAX_NAME_LENGTH];
    Tox_Err_Friend_Query err;
    size_t len = tox_friend_get_name_size(g_tox, fn, &err);
    out[0] = '\0';
    if (err != TOX_ERR_FRIEND_QUERY_OK || len == 0 || len > sizeof name) {
        return;
    }
    if (!tox_friend_get_name(g_tox, fn, name, &err)) {
        return;
    }
    size_t o = 0;
    for (size_t i = 0; i < len && o + 1 < cap; i++) {
        if (name[i] >= 0x20 && name[i] != 0x7F) {
            out[o++] = (char)name[i];
        }
    }
    out[utf8_cut(out, o, 48)] = '\0';
}

/* ---------------------------------------------------------------------------------------------- */
/* "welcomed" registry: who already got the full welcome pack, persisted across restarts          */
/* ---------------------------------------------------------------------------------------------- */

typedef struct {
    uint8_t pk[TOX_PUBLIC_KEY_SIZE];
    uint32_t first;
    uint32_t last;
} Welcome_Entry;

static Welcome_Entry *g_welcomed;
static size_t g_welcomed_len;
static size_t g_welcomed_cap;

static void welcomed_save(void)
{
    char path[PATH_MAX];
    size_t cap = g_welcomed_len * 96 + 1;
    char *buf = malloc(cap);
    if (!buf) {
        return;
    }
    size_t off = 0;
    for (size_t i = 0; i < g_welcomed_len; i++) {
        char hex[2 * TOX_PUBLIC_KEY_SIZE + 1];
        bin_to_hex(g_welcomed[i].pk, TOX_PUBLIC_KEY_SIZE, hex);
        off += (size_t)snprintf(buf + off, cap - off, "%s %" PRIu32 " %" PRIu32 "\n", hex, g_welcomed[i].first,
                                g_welcomed[i].last);
    }
    path_join(path, sizeof path, g_data_dir, "welcomed.txt");
    if (!write_file_atomic(path, (const uint8_t *)buf, off)) {
        log_line("welcomed.txt: save failed");
    }
    free(buf);
}

static Welcome_Entry *welcomed_find(const uint8_t *pk)
{
    for (size_t i = 0; i < g_welcomed_len; i++) {
        if (memcmp(g_welcomed[i].pk, pk, TOX_PUBLIC_KEY_SIZE) == 0) {
            return &g_welcomed[i];
        }
    }
    return NULL;
}

static Welcome_Entry *welcomed_add(const uint8_t *pk, uint32_t first, uint32_t last)
{
    if (g_welcomed_len == g_welcomed_cap) {
        const size_t cap = g_welcomed_cap ? g_welcomed_cap * 2 : 64;
        Welcome_Entry *grown = realloc(g_welcomed, cap * sizeof *grown);
        if (!grown) {
            return NULL;
        }
        g_welcomed = grown;
        g_welcomed_cap = cap;
    }
    Welcome_Entry *e = &g_welcomed[g_welcomed_len++];
    memcpy(e->pk, pk, TOX_PUBLIC_KEY_SIZE);
    e->first = first;
    e->last = last;
    return e;
}

static void welcomed_remove(const uint8_t *pk)
{
    for (size_t i = 0; i < g_welcomed_len; i++) {
        if (memcmp(g_welcomed[i].pk, pk, TOX_PUBLIC_KEY_SIZE) == 0) {
            g_welcomed[i] = g_welcomed[--g_welcomed_len];
            return;
        }
    }
}

static void welcomed_load(void)
{
    char path[PATH_MAX];
    path_join(path, sizeof path, g_data_dir, "welcomed.txt");
    FILE *f = fopen(path, "r");
    if (!f) {
        return;
    }
    char line[256];
    while (fgets(line, sizeof line, f)) {
        char hex[2 * TOX_PUBLIC_KEY_SIZE + 1];
        unsigned long first = 0;
        unsigned long last = 0;
        uint8_t pk[TOX_PUBLIC_KEY_SIZE];
        if (sscanf(line, "%64s %lu %lu", hex, &first, &last) == 3 && hex_to_bin(hex, pk, sizeof pk)) {
            welcomed_add(pk, (uint32_t)first, (uint32_t)last);
        }
    }
    fclose(f);
}

/* ---------------------------------------------------------------------------------------------- */
/* message primitives                                                                              */
/* ---------------------------------------------------------------------------------------------- */

/* Send text in msgV3 framing so the client records an id for it: that id is what reactions, edits
 * and delete-for-both are addressed by, and without it the client marks us as a legacy peer. */
static bool send_v3_frame(uint32_t fn, const char *text, const uint8_t *id, uint32_t ts)
{
    uint8_t buf[TOX_MAX_MESSAGE_LENGTH];
    const size_t len = utf8_cut(text, strlen(text), MSGV3_MAX_TEXT);
    if (len == 0) {
        return false;
    }
    memcpy(buf, text, len);
    buf[len] = 0;
    buf[len + 1] = 0;
    memcpy(buf + len + MSGV3_GUARD_LEN, id, MSGV3_ID_LEN);
    put_u32_be(buf + len + MSGV3_GUARD_LEN + MSGV3_ID_LEN, ts);
    Tox_Err_Friend_Send_Message err;
    tox_friend_send_message(g_tox, fn, TOX_MESSAGE_TYPE_NORMAL, buf, len + MSGV3_SUFFIX_LEN, &err);
    return err == TOX_ERR_FRIEND_SEND_MESSAGE_OK;
}

static bool send_text(uint32_t fn, const char *text, uint8_t *id_out)
{
    uint8_t id[MSGV3_ID_LEN];
    const uint32_t ts = unix_now();
    tox_messagev3_get_new_message_id(id);
    if (!send_v3_frame(fn, text, id, ts)) {
        return false;
    }
    Friend_State *st = fstate(fn);
    Outbox_Entry *e = &st->outbox[st->outbox_pos];
    st->outbox_pos = (st->outbox_pos + 1) % OUTBOX_SIZE;
    free(e->text);
    e->text = strdup(text);
    e->used = e->text != NULL;
    memcpy(e->id, id, MSGV3_ID_LEN);
    e->ts = ts;
    e->tries = 1;
    if (id_out) {
        memcpy(id_out, id, MSGV3_ID_LEN);
    }
    return true;
}

static void outbox_ack(uint32_t fn, const uint8_t *id)
{
    Friend_State *st = fstate(fn);
    for (unsigned i = 0; i < OUTBOX_SIZE; i++) {
        Outbox_Entry *e = &st->outbox[i];
        if (e->used && memcmp(e->id, id, MSGV3_ID_LEN) == 0) {
            free(e->text);
            memset(e, 0, sizeof *e);
            return;
        }
    }
}

/* Resend in the order the messages were first sent, oldest first. */
static void outbox_resend(uint32_t fn)
{
    Friend_State *st = fstate(fn);
    const uint32_t now = unix_now();
    unsigned resent = 0;
    for (unsigned k = 0; k < OUTBOX_SIZE; k++) {
        Outbox_Entry *e = &st->outbox[(st->outbox_pos + k) % OUTBOX_SIZE];
        if (!e->used) {
            continue;
        }
        if (e->tries >= OUTBOX_MAX_TRIES || now - e->ts > OUTBOX_TTL_S) {
            free(e->text);
            memset(e, 0, sizeof *e);
            continue;
        }
        if (send_v3_frame(fn, e->text, e->id, e->ts)) {
            e->tries++;
            resent++;
        }
    }
    if (resent) {
        log_line("resent %u unacknowledged message(s)", resent);
    }
}

/* msgV3 high-level ACK, exactly as the clients send it: text "_" carrying the original id and time.
 * Without it the sender keeps the message as undelivered and resends it on every reconnect. */
static void send_ack(uint32_t fn, const uint8_t *id, uint32_t ts)
{
    uint8_t buf[1 + MSGV3_SUFFIX_LEN];
    buf[0] = '_';
    buf[1] = 0;
    buf[2] = 0;
    memcpy(buf + 1 + MSGV3_GUARD_LEN, id, MSGV3_ID_LEN);
    put_u32_be(buf + 1 + MSGV3_GUARD_LEN + MSGV3_ID_LEN, ts);
    tox_friend_send_message(g_tox, fn, TOX_MESSAGE_TYPE_HIGH_LEVEL_ACK, buf, sizeof buf, NULL);
}

/* 188: [0]=188 'K' 'Q' ver=1 anchor_type=1 [5..36]=msgV3 id [37..40]=ts BE [41]=add [42]=len emoji */
static void send_reaction(uint32_t fn, const uint8_t *id, const char *emoji)
{
    uint8_t pkt[64];
    const size_t elen = strlen(emoji);
    if (elen < 1 || elen > 16) {
        return;
    }
    pkt[0] = PKT_REACTION;
    pkt[1] = 'K';
    pkt[2] = 'Q';
    pkt[3] = 1;
    pkt[4] = 1;
    memcpy(pkt + 5, id, MSGV3_ID_LEN);
    put_u32_be(pkt + 37, unix_now());
    pkt[41] = 1;
    pkt[42] = (uint8_t)elen;
    memcpy(pkt + 43, emoji, elen);
    tox_friend_send_lossless_packet(g_tox, fn, pkt, 43 + elen, NULL);
}

/* 186: [0]=186 'K' 'Q' ver=1 [4..35]=msgV3 id [36..39]=edit time BE [40..]=new text */
static void send_edit(uint32_t fn, const uint8_t *id, const char *text)
{
    uint8_t pkt[TOX_MAX_CUSTOM_PACKET_SIZE];
    const size_t len = utf8_cut(text, strlen(text), sizeof pkt - 40);
    pkt[0] = PKT_EDIT;
    pkt[1] = 'K';
    pkt[2] = 'Q';
    pkt[3] = 1;
    memcpy(pkt + 4, id, MSGV3_ID_LEN);
    put_u32_be(pkt + 36, unix_now());
    memcpy(pkt + 40, text, len);
    tox_friend_send_lossless_packet(g_tox, fn, pkt, 40 + len, NULL);
}

/* 187 (text form, 40 bytes): [0]=187 'K' 'Q' ver=1 [4..35]=msgV3 id [36..39]=time BE */
static void send_delete(uint32_t fn, const uint8_t *id)
{
    uint8_t pkt[40];
    pkt[0] = PKT_DELETE;
    pkt[1] = 'K';
    pkt[2] = 'Q';
    pkt[3] = 1;
    memcpy(pkt + 4, id, MSGV3_ID_LEN);
    put_u32_be(pkt + 36, unix_now());
    tox_friend_send_lossless_packet(g_tox, fn, pkt, sizeof pkt, NULL);
}

static void set_typing(uint32_t fn, bool typing)
{
    tox_self_set_typing(g_tox, fn, typing, NULL);
}

/* ---------------------------------------------------------------------------------------------- */
/* file transfers                                                                                  */
/* ---------------------------------------------------------------------------------------------- */

typedef struct {
    bool used;
    bool outgoing;
    bool owned;
    uint32_t fn;
    uint32_t file_number;
    uint8_t pk[TOX_PUBLIC_KEY_SIZE];
    uint8_t *data;
    size_t size;
    size_t received;
    bool voice;
    const void *asset; /* the Asset being sent, for re-offering after a dropped connection */
    uint64_t last_activity_ms;
    char name[256];
} Transfer;

static Transfer g_tr[MAX_TRANSFERS];

static Transfer *transfer_find(uint32_t fn, uint32_t file_number, bool outgoing)
{
    for (size_t i = 0; i < MAX_TRANSFERS; i++) {
        if (g_tr[i].used && g_tr[i].fn == fn && g_tr[i].file_number == file_number && g_tr[i].outgoing == outgoing) {
            return &g_tr[i];
        }
    }
    return NULL;
}

static Transfer *transfer_alloc(void)
{
    for (size_t i = 0; i < MAX_TRANSFERS; i++) {
        if (!g_tr[i].used) {
            memset(&g_tr[i], 0, sizeof g_tr[i]);
            g_tr[i].used = true;
            g_tr[i].last_activity_ms = mono_ms();
            return &g_tr[i];
        }
    }
    return NULL;
}

static void transfer_free(Transfer *t)
{
    if (t->owned) {
        free(t->data);
    }
    memset(t, 0, sizeof *t);
}

static bool transfer_slots_full(void)
{
    for (size_t i = 0; i < MAX_TRANSFERS; i++) {
        if (!g_tr[i].used) {
            return false;
        }
    }
    return true;
}

static unsigned incoming_count(void)
{
    unsigned n = 0;
    for (size_t i = 0; i < MAX_TRANSFERS; i++) {
        n += g_tr[i].used && !g_tr[i].outgoing;
    }
    return n;
}

/* Offer data to a contact. When owned, the buffer is freed once the transfer ends either way. */
static bool send_data(uint32_t fn, uint32_t kind, const uint8_t *file_id, uint8_t *data, size_t size,
                      const char *name, bool owned)
{
    Transfer *t = transfer_alloc();
    if (!t) {
        if (owned) free(data);
        return false;
    }
    Tox_Err_File_Send err;
    const uint32_t file_number = tox_file_send(g_tox, fn, kind, size, file_id, (const uint8_t *)name, strlen(name), &err);
    if (err != TOX_ERR_FILE_SEND_OK) {
        log_line("file offer failed (%d)", (int)err);
        t->owned = owned;
        t->data = data;
        transfer_free(t);
        return false;
    }
    Tox_Err_Friend_Get_Public_Key pk_err;
    tox_friend_get_public_key(g_tox, fn, t->pk, &pk_err);
    t->outgoing = true;
    t->owned = owned;
    t->fn = fn;
    t->file_number = file_number;
    t->data = data;
    t->size = size;
    snprintf(t->name, sizeof t->name, "%s", name);
    return true;
}

static bool send_asset(uint32_t fn, const Asset *a)
{
    if (!a->data || !send_data(fn, TOX_FILE_KIND_DATA, NULL, a->data, a->size, a->wire_name, false)) {
        return false;
    }
    for (size_t i = 0; i < MAX_TRANSFERS; i++) {
        Transfer *t = &g_tr[i];
        if (t->used && t->outgoing && t->fn == fn && t->data == a->data && !t->asset) {
            t->asset = a;
            break;
        }
    }
    return true;
}

/* Avatars travel as TOX_FILE_KIND_AVATAR with the image hash as file id, so a client that already
 * has this exact picture cancels the transfer instead of downloading it again. */
static void send_avatar(uint32_t fn)
{
    if (g_avatar.data) {
        send_data(fn, TOX_FILE_KIND_AVATAR, g_avatar_hash, g_avatar.data, g_avatar.size, g_avatar.wire_name, false);
    }
}

static void reoffer_asset(const uint8_t *pk, const void *asset);

static void free_transfers_of(uint32_t fn)
{
    for (size_t i = 0; i < MAX_TRANSFERS; i++) {
        Transfer *t = &g_tr[i];
        if (t->used && t->fn == fn) {
            if (t->asset) {
                reoffer_asset(t->pk, t->asset);
            }
            transfer_free(t);
        }
    }
}

static bool ends_with_ci(const char *s, const char *suffix)
{
    const size_t a = strlen(s);
    const size_t b = strlen(suffix);
    if (b > a) {
        return false;
    }
    return strncasecmp(s + a - b, suffix, b) == 0;
}

/* Received names are attacker-chosen: keep printable bytes, no path separators, bounded length. */
static void sanitize_name(const uint8_t *in, size_t len, char *out, size_t cap)
{
    size_t o = 0;
    for (size_t i = 0; i < len && o + 1 < cap; i++) {
        const uint8_t c = in[i];
        if (c < 0x20 || c == 0x7F || c == '/' || c == '\\') {
            continue;
        }
        out[o++] = (char)c;
    }
    out[utf8_cut(out, o, 180)] = '\0';
    if (o == 0 || out[0] == '.') {
        snprintf(out, cap, "file");
    }
}

/* ---------------------------------------------------------------------------------------------- */
/* scheduled actions                                                                               */
/* ---------------------------------------------------------------------------------------------- */

typedef enum {
    ACT_TEXT,
    ACT_TEXT_THEN_EDIT,
    ACT_TEXT_THEN_DELETE,
    ACT_EDIT,
    ACT_DELETE,
    ACT_FILE,
    ACT_ECHO_FILE,
    ACT_AVATAR,
    ACT_LOCATION,
    ACT_GROUP_INVITE,
    ACT_REACT,
    ACT_CALL,
    ACT_GREETING,
    ACT_GROUP_TEXT,
    ACT_GROUP_WELCOME,
    ACT_ACCEPT_GROUP_INVITE,
    ACT_RESEND,
} Action_Kind;

typedef struct Action {
    struct Action *next;
    uint64_t due_ms;
    uint64_t expires_ms;
    Action_Kind kind;
    uint8_t pk[TOX_PUBLIC_KEY_SIZE];
    uint32_t group_number;
    uint32_t peer_id;
    bool flag;
    bool typing_off;
    const Asset *asset;
    uint8_t id[MSGV3_ID_LEN];
    char *text;
    uint8_t *blob;
    size_t blob_len;
} Action;

/* Actions created while the list is being walked land in g_incoming and join the main list on the
 * next pass, so the walk never has to cope with its own head changing underneath it. */
static Action *g_actions;
static Action *g_incoming;

static Action *schedule(Action_Kind kind, const uint8_t *pk, uint64_t delay_ms)
{
    Action *a = calloc(1, sizeof *a);
    if (!a) {
        return NULL;
    }
    const uint64_t now = mono_ms();
    a->kind = kind;
    a->due_ms = now + delay_ms;
    a->expires_ms = now + delay_ms + 10u * 60u * 1000u;
    if (pk) {
        memcpy(a->pk, pk, TOX_PUBLIC_KEY_SIZE);
    }
    a->next = g_incoming;
    g_incoming = a;
    return a;
}

static Action *schedule_text(const uint8_t *pk, uint64_t delay_ms, const char *text)
{
    Action *a = schedule(ACT_TEXT, pk, delay_ms);
    if (a) {
        a->text = strdup(text);
    }
    return a;
}

static void reoffer_asset(const uint8_t *pk, const void *asset)
{
    Action *a = schedule(ACT_FILE, pk, 3000);
    if (a) {
        a->asset = asset;
        a->expires_ms = a->due_ms + WELCOME_PACK_EXPIRY_MS;
    }
}

static void action_free(Action *a)
{
    free(a->text);
    free(a->blob);
    free(a);
}

/* ---------------------------------------------------------------------------------------------- */
/* groups                                                                                          */
/* ---------------------------------------------------------------------------------------------- */

static uint32_t g_demo_group = UINT32_MAX;
static uint8_t g_demo_chat_id[TOX_GROUP_CHAT_ID_SIZE];
static bool g_group_configured;
static double g_group_tokens = TOKENS_MAX;
static uint64_t g_group_tokens_ms;
static unsigned g_group_reply_count;

typedef struct {
    bool used;
    uint32_t group_number;
    uint64_t joined_ms;
} Foreign_Group;

static Foreign_Group g_foreign[MAX_FOREIGN_GROUPS];

static bool is_our_group(uint32_t gn)
{
    if (gn == g_demo_group) {
        return true;
    }
    for (size_t i = 0; i < MAX_FOREIGN_GROUPS; i++) {
        if (g_foreign[i].used && g_foreign[i].group_number == gn) {
            return true;
        }
    }
    return false;
}

static void group_peer_name(uint32_t gn, uint32_t peer, char *out, size_t cap)
{
    uint8_t name[TOX_MAX_NAME_LENGTH];
    Tox_Err_Group_Peer_Query err;
    const size_t len = tox_group_peer_get_name_size(g_tox, gn, peer, &err);
    out[0] = '\0';
    if (err != TOX_ERR_GROUP_PEER_QUERY_OK || len == 0 || len > sizeof name ||
        !tox_group_peer_get_name(g_tox, gn, peer, name, &err)) {
        return;
    }
    size_t o = 0;
    for (size_t i = 0; i < len && o + 1 < cap; i++) {
        if (name[i] >= 0x20 && name[i] != 0x7F) {
            out[o++] = (char)name[i];
        }
    }
    out[utf8_cut(out, o, 48)] = '\0';
}

static void group_send(uint32_t gn, const char *text)
{
    const size_t len = utf8_cut(text, strlen(text), TOX_MAX_MESSAGE_LENGTH);
    tox_group_send_message(g_tox, gn, TOX_MESSAGE_TYPE_NORMAL, (const uint8_t *)text, len, NULL, NULL);
}

static void write_group_id(void)
{
    char path[PATH_MAX];
    char hex[2 * TOX_GROUP_CHAT_ID_SIZE + 2];
    bin_to_hex(g_demo_chat_id, TOX_GROUP_CHAT_ID_SIZE, hex);
    strcat(hex, "\n");
    path_join(path, sizeof path, g_data_dir, "group.txt");
    write_file_atomic(path, (const uint8_t *)hex, strlen(hex));
}

/* Find the demo group restored from savedata, leave anything else a previous run had joined, and
 * create the group on first start. Group membership is part of the Tox savedata. */
static void ensure_demo_group(void)
{
    char path[PATH_MAX];
    uint8_t saved[TOX_GROUP_CHAT_ID_SIZE];
    bool have_saved = false;
    path_join(path, sizeof path, g_data_dir, "group.txt");
    FILE *f = fopen(path, "r");
    if (f) {
        char hex[2 * TOX_GROUP_CHAT_ID_SIZE + 2];
        if (fgets(hex, sizeof hex, f) && hex_to_bin(hex, saved, sizeof saved)) {
            have_saved = true;
        }
        fclose(f);
    }

    const uint32_t count = tox_group_get_number_groups(g_tox);
    uint32_t seen = 0;
    for (uint32_t gn = 0; seen < count && gn < count + 256; gn++) {
        uint8_t chat_id[TOX_GROUP_CHAT_ID_SIZE];
        Tox_Err_Group_State_Queries err;
        if (!tox_group_get_chat_id(g_tox, gn, chat_id, &err)) {
            continue;
        }
        seen++;
        if (have_saved && g_demo_group == UINT32_MAX && memcmp(chat_id, saved, sizeof saved) == 0) {
            g_demo_group = gn;
            memcpy(g_demo_chat_id, chat_id, sizeof chat_id);
        } else {
            tox_group_leave(g_tox, gn, NULL, 0, NULL);
            g_dirty = true;
        }
    }

    if (g_demo_group != UINT32_MAX) {
        log_line("demo group restored (group %" PRIu32 ")", g_demo_group);
        return;
    }

    /* Public, not private: toxcore never announces a private group to the DHT, so a joiner whose
     * invite handshake is lost (a UDP/TCP switch is enough) has no second way to find us and sits at
     * "connecting" for good. A public group is announced, and the joiner finds the founder there.
     * Nobody can stumble on it either way: Tox has no group directory, joining needs the chat id. */
    Tox_Err_Group_New err;
    g_demo_group = tox_group_new(g_tox, TOX_GROUP_PRIVACY_STATE_PUBLIC, (const uint8_t *)GROUP_NAME,
                                 strlen(GROUP_NAME), (const uint8_t *)BOT_NAME, strlen(BOT_NAME), &err);
    if (err != TOX_ERR_GROUP_NEW_OK) {
        log_line("demo group: creation failed (%d), group features disabled", (int)err);
        g_demo_group = UINT32_MAX;
        return;
    }
    tox_group_get_chat_id(g_tox, g_demo_group, g_demo_chat_id, NULL);
    write_group_id();
    g_dirty = true;
    log_line("demo group created (group %" PRIu32 ")", g_demo_group);
}

/* Bring a restored group to the current settings: public (see ensure_demo_group) and with its
 * topic. Both need the founder to be online, so this is retried from the loop until it holds. */
static void configure_demo_group(void)
{
    Tox_Err_Group_State_Queries qerr;
    if (tox_group_get_privacy_state(g_tox, g_demo_group, &qerr) != TOX_GROUP_PRIVACY_STATE_PUBLIC) {
        Tox_Err_Group_Founder_Set_Privacy_State perr;
        if (!tox_group_founder_set_privacy_state(g_tox, g_demo_group, TOX_GROUP_PRIVACY_STATE_PUBLIC, &perr)) {
            return;
        }
        log_line("demo group switched to public");
        g_dirty = true;
    }
    Tox_Err_Group_Topic_Set terr;
    if (!tox_group_set_topic(g_tox, g_demo_group, (const uint8_t *)GROUP_TOPIC, strlen(GROUP_TOPIC), &terr) &&
        terr != TOX_ERR_GROUP_TOPIC_SET_PERMISSIONS) {
        return;
    }
    g_group_configured = true;
}

static void foreign_group_add(uint32_t gn)
{
    if (is_our_group(gn)) {
        return;
    }
    size_t oldest = 0;
    for (size_t i = 0; i < MAX_FOREIGN_GROUPS; i++) {
        if (!g_foreign[i].used) {
            g_foreign[i] = (Foreign_Group){true, gn, mono_ms()};
            return;
        }
        if (g_foreign[i].joined_ms < g_foreign[oldest].joined_ms) {
            oldest = i;
        }
    }
    /* Full: give up the oldest membership rather than refuse the newest invitation. */
    tox_group_leave(g_tox, g_foreign[oldest].group_number, NULL, 0, NULL);
    g_foreign[oldest] = (Foreign_Group){true, gn, mono_ms()};
    g_dirty = true;
}

/* ---------------------------------------------------------------------------------------------- */
/* calls                                                                                           */
/* ---------------------------------------------------------------------------------------------- */

typedef struct {
    int16_t pcm[AUDIO_MAX_FRAME_SAMPLES];
    size_t samples;
    uint8_t channels;
    uint32_t rate;
} Audio_Frame;

typedef struct {
    bool used;
    bool incoming;
    bool answered;
    bool ended;
    bool want_video;
    bool peer_video;
    bool sending_video;
    bool greeting;
    bool state_changed;
    uint32_t state;
    uint32_t fn;
    uint8_t pk[TOX_PUBLIC_KEY_SIZE];
    uint64_t created_ms;
    uint64_t answer_due_ms;
    uint64_t connected_ms;
    uint64_t next_audio_ms;
    uint64_t next_pattern_ms;
    uint64_t last_peer_video_ms;
    uint64_t last_echo_video_ms;
    size_t greet_pos;
    Audio_Frame *queue;
    unsigned q_head;
    unsigned q_count;
    uint8_t *vbuf;
    size_t vbuf_cap;
    uint16_t vw;
    uint16_t vh;
    bool vframe_ready;
    uint32_t pattern_tick;
} Call;

static Call g_calls[MAX_CALLS];
/* Calls refused because every slot is busy; hung up from the loop, not from the toxav callback. */
static uint32_t g_refused[8];
static unsigned g_refused_count;
static uint8_t g_pattern[PATTERN_W * PATTERN_H * 3 / 2];

static Call *call_find(uint32_t fn)
{
    for (size_t i = 0; i < MAX_CALLS; i++) {
        if (g_calls[i].used && g_calls[i].fn == fn) {
            return &g_calls[i];
        }
    }
    return NULL;
}

static Call *call_alloc(uint32_t fn, bool incoming)
{
    for (size_t i = 0; i < MAX_CALLS; i++) {
        Call *c = &g_calls[i];
        if (c->used) {
            continue;
        }
        Audio_Frame *queue = calloc(AUDIO_QUEUE_FRAMES, sizeof *queue);
        if (!queue) {
            return NULL;
        }
        memset(c, 0, sizeof *c);
        c->used = true;
        c->incoming = incoming;
        c->fn = fn;
        c->queue = queue;
        c->created_ms = mono_ms();
        tox_friend_get_public_key(g_tox, fn, c->pk, NULL);
        return c;
    }
    return NULL;
}

static void call_free(Call *c)
{
    free(c->queue);
    free(c->vbuf);
    memset(c, 0, sizeof *c);
}

static bool any_call_active(void)
{
    for (size_t i = 0; i < MAX_CALLS; i++) {
        if (g_calls[i].used) {
            return true;
        }
    }
    return false;
}

static void call_media_start(Call *c, uint64_t now)
{
    c->answered = true;
    c->connected_ms = now;
    c->greeting = g_greeting != NULL;
    c->greet_pos = 0;
    c->next_audio_ms = now;
    c->next_pattern_ms = now;
    c->q_count = 0;
}

static void call_hangup(Call *c, const char *note)
{
    toxav_call_control(g_av, c->fn, TOXAV_CALL_CONTROL_CANCEL, NULL);
    if (note) {
        schedule_text(c->pk, 300, note);
    }
    call_free(c);
}

/* Moving bars in the app's greens: shown when we call with video and the other side sends none. */
static void render_pattern(uint32_t tick)
{
    uint8_t *y = g_pattern;
    uint8_t *u = y + PATTERN_W * PATTERN_H;
    uint8_t *v = u + (PATTERN_W / 2) * (PATTERN_H / 2);
    const int band = (int)((tick * 12) % (PATTERN_H + 120)) - 120;
    const int sq_x = (int)((tick * 7) % (2 * (PATTERN_W - 80)));
    const int sq_left = sq_x < PATTERN_W - 80 ? sq_x : 2 * (PATTERN_W - 80) - sq_x;
    for (int row = 0; row < PATTERN_H; row++) {
        for (int col = 0; col < PATTERN_W; col++) {
            uint8_t luma = 46; /* #08302A */
            if (row >= band && row < band + 120) {
                luma = 157; /* #68C793 */
            }
            if (col >= sq_left && col < sq_left + 80 && row >= 280 && row < 360) {
                luma = 235;
            }
            y[row * PATTERN_W + col] = luma;
        }
    }
    for (int row = 0; row < PATTERN_H / 2; row++) {
        for (int col = 0; col < PATTERN_W / 2; col++) {
            const int full_row = row * 2;
            const int full_col = col * 2;
            uint8_t cu = 129;
            uint8_t cv = 111;
            if (full_row >= band && full_row < band + 120) {
                cu = 119;
                cv = 90;
            }
            if (full_col >= sq_left && full_col < sq_left + 80 && full_row >= 280 && full_row < 360) {
                cu = 128;
                cv = 128;
            }
            u[row * (PATTERN_W / 2) + col] = cu;
            v[row * (PATTERN_W / 2) + col] = cv;
        }
    }
}

static void pump_calls(uint64_t now)
{
    for (unsigned i = 0; i < g_refused_count; i++) {
        uint8_t pk[TOX_PUBLIC_KEY_SIZE];
        toxav_call_control(g_av, g_refused[i], TOXAV_CALL_CONTROL_CANCEL, NULL);
        if (tox_friend_get_public_key(g_tox, g_refused[i], pk, NULL)) {
            schedule_text(pk, 300, "I'm on other calls right now. Please try again in a minute.");
        }
    }
    g_refused_count = 0;

    for (size_t i = 0; i < MAX_CALLS; i++) {
        Call *c = &g_calls[i];
        if (!c->used) {
            continue;
        }
        if (c->ended) {
            const bool talked = c->answered && now - c->connected_ms > 3000;
            const uint8_t *pk = c->pk;
            if (talked) {
                schedule_text(pk, 800, "Call ended. Thanks for calling! Type “call me” and I'll call you back.");
            }
            log_line("call with %s ended", pk_tag(pk));
            call_free(c);
            continue;
        }
        if (!friend_online(c->fn)) {
            call_free(c);
            continue;
        }

        if (c->incoming && !c->answered) {
            if (now >= c->answer_due_ms) {
                Toxav_Err_Answer err;
                const uint32_t vbr = c->want_video ? VIDEO_BITRATE_KBPS : 0;
                if (toxav_answer(g_av, c->fn, AUDIO_BITRATE_KBPS, vbr, &err)) {
                    c->sending_video = c->want_video;
                    call_media_start(c, now);
                    log_line("answered %s call from %s", c->want_video ? "video" : "audio", pk_tag(c->pk));
                } else {
                    log_line("answer failed (%d)", (int)err);
                    call_free(c);
                }
            }
            continue;
        }

        if (!c->incoming && !c->answered) {
            const uint32_t live = TOXAV_FRIEND_CALL_STATE_SENDING_A | TOXAV_FRIEND_CALL_STATE_ACCEPTING_A |
                                  TOXAV_FRIEND_CALL_STATE_SENDING_V | TOXAV_FRIEND_CALL_STATE_ACCEPTING_V;
            if (c->state_changed && (c->state & live)) {
                call_media_start(c, now);
                log_line("%s picked up", pk_tag(c->pk));
            } else if (now - c->created_ms > OUTGOING_RING_TIMEOUT_MS) {
                call_hangup(c, "No answer, so I hung up. Type “call me” to try again.");
            }
            c->state_changed = false;
            continue;
        }
        c->state_changed = false;

        if (now - c->connected_ms > CALL_MAX_MS) {
            call_hangup(c, "Demo calls end after five minutes. Call again any time!");
            continue;
        }

        /* The caller switched their camera on in an audio call: start returning video too. */
        if (c->peer_video && !c->sending_video) {
            if (toxav_video_set_bit_rate(g_av, c->fn, VIDEO_BITRATE_KBPS, NULL)) {
                c->sending_video = true;
            }
        }

        if (c->greeting) {
            while (c->greeting && now >= c->next_audio_ms) {
                int16_t frame[GREETING_FRAME_SAMPLES] = {0};
                const size_t left = g_greeting_samples - c->greet_pos;
                const size_t n = left < GREETING_FRAME_SAMPLES ? left : GREETING_FRAME_SAMPLES;
                memcpy(frame, g_greeting + c->greet_pos, n * sizeof(int16_t));
                toxav_audio_send_frame(g_av, c->fn, frame, GREETING_FRAME_SAMPLES, 1, 48000, NULL);
                c->greet_pos += n;
                c->next_audio_ms += 20;
                if (c->greet_pos >= g_greeting_samples) {
                    c->greeting = false;
                    c->q_count = 0;
                }
            }
        } else {
            while (c->q_count > 0) {
                Audio_Frame *f = &c->queue[c->q_head];
                toxav_audio_send_frame(g_av, c->fn, f->pcm, f->samples, f->channels, f->rate, NULL);
                c->q_head = (c->q_head + 1) % AUDIO_QUEUE_FRAMES;
                c->q_count--;
            }
        }

        if (c->sending_video) {
            if (c->vframe_ready && now - c->last_echo_video_ms >= VIDEO_ECHO_INTERVAL_MS) {
                const size_t ysize = (size_t)c->vw * c->vh;
                toxav_video_send_frame(g_av, c->fn, c->vw, c->vh, c->vbuf, c->vbuf + ysize, c->vbuf + ysize + ysize / 4,
                                       NULL);
                c->last_echo_video_ms = now;
                c->vframe_ready = false;
            } else if (now - c->last_peer_video_ms > 1500 && now >= c->next_pattern_ms) {
                render_pattern(c->pattern_tick++);
                toxav_video_send_frame(g_av, c->fn, PATTERN_W, PATTERN_H, g_pattern, g_pattern + PATTERN_W * PATTERN_H,
                                       g_pattern + PATTERN_W * PATTERN_H * 5 / 4, NULL);
                c->next_pattern_ms = now + PATTERN_INTERVAL_MS;
            }
        }
    }
}

/* toxav callbacks: record and copy only, never call back into toxav from here. */

static void on_av_call(ToxAV *av, uint32_t fn, bool audio, bool video, void *ud)
{
    if (call_find(fn)) {
        return;
    }
    Call *c = call_alloc(fn, true);
    if (!c) {
        if (g_refused_count < sizeof g_refused / sizeof g_refused[0]) {
            g_refused[g_refused_count++] = fn;
        }
        log_line("call from friend %" PRIu32 " refused: all call slots busy", fn);
        return;
    }
    c->want_video = video;
    c->peer_video = video;
    c->answer_due_ms = mono_ms() + RING_BEFORE_ANSWER_MS;
    log_line("incoming %s call from %s", video ? "video" : "audio", pk_tag(c->pk));
}

static void on_av_call_state(ToxAV *av, uint32_t fn, uint32_t state, void *ud)
{
    Call *c = call_find(fn);
    if (!c) {
        return;
    }
    c->state = state;
    c->state_changed = true;
    if (state & (TOXAV_FRIEND_CALL_STATE_FINISHED | TOXAV_FRIEND_CALL_STATE_ERROR)) {
        c->ended = true;
    }
    if (state & TOXAV_FRIEND_CALL_STATE_SENDING_V) {
        c->peer_video = true;
    }
}

static void on_audio_frame(ToxAV *av, uint32_t fn, const int16_t *pcm, size_t samples, uint8_t channels,
                           uint32_t rate, void *ud)
{
    Call *c = call_find(fn);
    if (!c || !c->answered || c->greeting || channels == 0 || samples * channels > AUDIO_MAX_FRAME_SAMPLES) {
        return;
    }
    if (c->q_count == AUDIO_QUEUE_FRAMES) {
        c->q_head = (c->q_head + 1) % AUDIO_QUEUE_FRAMES;
        c->q_count--;
    }
    Audio_Frame *f = &c->queue[(c->q_head + c->q_count) % AUDIO_QUEUE_FRAMES];
    memcpy(f->pcm, pcm, samples * channels * sizeof(int16_t));
    f->samples = samples;
    f->channels = channels;
    f->rate = rate;
    c->q_count++;
}

static void on_video_frame(ToxAV *av, uint32_t fn, uint16_t width, uint16_t height, const uint8_t *y,
                           const uint8_t *u, const uint8_t *v, int32_t ystride, int32_t ustride, int32_t vstride,
                           void *ud)
{
    Call *c = call_find(fn);
    if (!c || width < 2 || height < 2) {
        return;
    }
    c->last_peer_video_ms = mono_ms();
    c->peer_video = true;
    if (!c->answered) {
        return;
    }
    /* Halve the picture until it fits: echoing a phone's full-resolution camera would cost far more
     * CPU and bandwidth than seeing yourself on screen is worth. */
    unsigned step = 1;
    while (width / step > MAX_ECHO_DIMENSION || height / step > MAX_ECHO_DIMENSION) {
        step *= 2;
    }
    const uint16_t ow = (uint16_t)((width / step) & ~1u);
    const uint16_t oh = (uint16_t)((height / step) & ~1u);
    if (ow < 2 || oh < 2) {
        return;
    }
    const size_t need = (size_t)ow * oh * 3 / 2;
    if (need > c->vbuf_cap) {
        uint8_t *grown = realloc(c->vbuf, need);
        if (!grown) {
            return;
        }
        c->vbuf = grown;
        c->vbuf_cap = need;
    }
    const size_t ys = (size_t)(ystride < 0 ? -ystride : ystride);
    const size_t us = (size_t)(ustride < 0 ? -ustride : ustride);
    const size_t vs = (size_t)(vstride < 0 ? -vstride : vstride);
    uint8_t *oy = c->vbuf;
    uint8_t *ou = oy + (size_t)ow * oh;
    uint8_t *ov = ou + (size_t)(ow / 2) * (oh / 2);
    for (uint16_t r = 0; r < oh; r++) {
        for (uint16_t col = 0; col < ow; col++) {
            oy[(size_t)r * ow + col] = y[(size_t)r * step * ys + (size_t)col * step];
        }
    }
    for (uint16_t r = 0; r < oh / 2; r++) {
        for (uint16_t col = 0; col < ow / 2; col++) {
            ou[(size_t)r * (ow / 2) + col] = u[(size_t)r * step * us + (size_t)col * step];
            ov[(size_t)r * (ow / 2) + col] = v[(size_t)r * step * vs + (size_t)col * step];
        }
    }
    c->vw = ow;
    c->vh = oh;
    c->vframe_ready = true;
}

/* ---------------------------------------------------------------------------------------------- */
/* texts                                                                                           */
/* ---------------------------------------------------------------------------------------------- */

static const char *const TEXT_TRY =
    "Here is what you can try with me:\n"
    "• Send a message: I reply and react to it.\n"
    "• Send a photo, video, file or voice message: I send it back.\n"
    "• Call me, audio or video: I answer and play your own voice and camera back to you.\n"
    "• Type “call me” and I'll call you in 10 seconds. Lock your phone right after to see the "
    "incoming call on the lock screen.\n"
    "• Type “help” for all commands.";

static const char *const TEXT_HELP =
    "Commands:\n"
    "• call me: I call you (audio)\n"
    "• video call me: I call you with video\n"
    "• photo, voice, file, location: I send one\n"
    "• group: I invite you to the demo group\n"
    "• edit: I send a message and then edit it\n"
    "• delete: I send a message and then delete it for both of us\n"
    "• react: I react to your message\n"
    "Anything else you send, I answer or send back.";

static void schedule_welcome_pack(const uint8_t *pk)
{
    Action *a;
    a = schedule(ACT_GREETING, pk, 1500);
    if (a) a->flag = true;
    schedule_text(pk, 4000, TEXT_TRY);
    a = schedule(ACT_FILE, pk, 6500);
    if (a) a->asset = &g_photo;
    schedule_text(pk, 8000, "↑ A photo, sent peer to peer. Tap it to download.");
    a = schedule(ACT_FILE, pk, 9500);
    if (a) a->asset = &g_voice;
    a = schedule(ACT_FILE, pk, 11000);
    if (a) a->asset = &g_document;
    schedule(ACT_LOCATION, pk, 12500);
    schedule(ACT_GROUP_INVITE, pk, 14000);
    schedule_text(pk, 15000, "I've also invited you to “" GROUP_NAME "”. Tap “Join” in the invitation to try group chat.");
    schedule_text(pk, 17000, "That's everything. Type “help” any time to see the commands again.");
    /* A pack interrupted by the contact going offline resumes when they come back, for a day. */
    for (Action *it = g_incoming; it; it = it->next) {
        if (memcmp(it->pk, pk, TOX_PUBLIC_KEY_SIZE) == 0) {
            it->expires_ms = it->due_ms + WELCOME_PACK_EXPIRY_MS;
        }
    }
}

/* ---------------------------------------------------------------------------------------------- */
/* incoming messages                                                                               */
/* ---------------------------------------------------------------------------------------------- */

/* Lower-case ASCII, collapse spaces, drop trailing punctuation: "Call me!" and "call  me" match. */
static void normalize(const char *in, char *out, size_t cap)
{
    size_t o = 0;
    bool space = false;
    for (const char *p = in; *p && o + 1 < cap; p++) {
        char ch = *p;
        if (ch >= 'A' && ch <= 'Z') {
            ch = (char)(ch - 'A' + 'a');
        }
        if (ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r') {
            space = o > 0;
            continue;
        }
        if (space) {
            out[o++] = ' ';
            space = false;
            if (o + 1 >= cap) break;
        }
        out[o++] = ch;
    }
    while (o > 0 && (out[o - 1] == '.' || out[o - 1] == '!' || out[o - 1] == '?' || out[o - 1] == ',')) {
        o--;
    }
    out[o] = '\0';
}

static bool is_one_of(const char *s, const char *const *words)
{
    for (; *words; words++) {
        if (strcmp(s, *words) == 0) {
            return true;
        }
    }
    return false;
}

/* Khandaq sends a swipe-reply as "[KQ|<meta>]<quoted preview>[KQ/end]<answer>" (MessageReplyHelper on
 * Android, the same header on iOS). Only the answer is something the person typed: echoing the header
 * put "[KQ|17|C397E117|...]" walls into the chat, and a reply of "help" was not recognised as a command.
 * Same parse as MessageReplyHelper.parse: header prefix, first ']', footer after it. */
static const char *reply_body(const char *text, bool *is_reply)
{
    *is_reply = false;
    if (strncmp(text, "[KQ|", 4) != 0) {
        return text;
    }
    const char *header_end = strchr(text + 4, ']');
    const char *footer = strstr(text, "[KQ/end]");
    if (header_end == NULL || footer == NULL || footer < header_end) {
        return text;
    }
    const char *body = footer + strlen("[KQ/end]");
    while (*body == ' ' || *body == '\n' || *body == '\r' || *body == '\t') {
        body++;
    }
    *is_reply = true;
    return body;
}

static void handle_text(uint32_t fn, const uint8_t *pk, const char *text, const uint8_t *id)
{
    static const char *const help[] = {"help", "/help", "/start", "start", "menu", "commands", "?", NULL};
    static const char *const call[] = {"call me", "call", "audio call", "audio call me", "voice call",
                                       "voice call me", "call me please", "please call me", NULL};
    static const char *const vcall[] = {"video call me", "video call", "videocall", "videocall me",
                                        "video call me please", "please video call me", NULL};
    static const char *const photo[] = {"photo", "picture", "image", "pic", "send photo", "send a photo", NULL};
    static const char *const voice[] = {"voice", "voice message", "voice note", "send voice", "send a voice message",
                                        NULL};
    static const char *const file[] = {"file", "pdf", "document", "send file", "send a file", NULL};
    static const char *const location[] = {"location", "map", "send location", NULL};
    static const char *const group[] = {"group", "invite", "invite me", "group chat", NULL};
    static const char *const edit[] = {"edit", "edit message", NULL};
    static const char *const del[] = {"delete", "unsend", "delete message", NULL};
    static const char *const react[] = {"react", "reaction", "like", NULL};

    bool is_reply = false;
    text = reply_body(text, &is_reply);
    if (is_reply && *text == '\0') {
        return; /* a reply with nothing typed under the quote */
    }

    char cmd[64];
    normalize(text, cmd, sizeof cmd);
    Action *a;

    if (strncmp(text, "khandaq-location:", 17) == 0) {
        a = schedule_text(pk, 900, "Got your location \U0001F4CD Thanks!");
        if (a && id) {
            a = schedule(ACT_REACT, pk, 700);
            if (a) {
                memcpy(a->id, id, MSGV3_ID_LEN);
                a->text = strdup("\U0001F44D");
            }
        }
        return;
    }
    if (is_one_of(cmd, help)) {
        schedule_text(pk, 600, TEXT_HELP);
        return;
    }
    if (is_one_of(cmd, call) || is_one_of(cmd, vcall)) {
        const bool video = is_one_of(cmd, vcall);
        schedule_text(pk, 500, video ? "OK, video calling you in 10 seconds \U0001F4F9 Lock your phone now to see the "
                                       "incoming call on the lock screen."
                                     : "OK, calling you in 10 seconds \U0001F4DE Lock your phone now to see the incoming "
                                       "call on the lock screen.");
        a = schedule(ACT_CALL, pk, CALL_ME_DELAY_MS);
        if (a) {
            a->flag = video;
            a->expires_ms = a->due_ms + 60000;
        }
        return;
    }
    if (is_one_of(cmd, photo) || is_one_of(cmd, voice) || is_one_of(cmd, file)) {
        a = schedule(ACT_FILE, pk, 600);
        if (a) {
            a->asset = is_one_of(cmd, photo) ? &g_photo : is_one_of(cmd, voice) ? &g_voice : &g_document;
        }
        return;
    }
    if (is_one_of(cmd, location)) {
        schedule(ACT_LOCATION, pk, 600);
        return;
    }
    if (is_one_of(cmd, group)) {
        schedule(ACT_GROUP_INVITE, pk, 600);
        schedule_text(pk, 1200, "Invitation to “" GROUP_NAME "” sent. Tap “Join” to enter the group.");
        return;
    }
    if (is_one_of(cmd, edit)) {
        a = schedule(ACT_TEXT_THEN_EDIT, pk, 600);
        if (a) a->text = strdup("This message will be edited in 3 seconds…");
        return;
    }
    if (is_one_of(cmd, del)) {
        a = schedule(ACT_TEXT_THEN_DELETE, pk, 600);
        if (a) a->text = strdup("This message will be deleted for both of us in 3 seconds…");
        return;
    }
    if (is_one_of(cmd, react)) {
        if (id) {
            a = schedule(ACT_REACT, pk, 500);
            if (a) {
                memcpy(a->id, id, MSGV3_ID_LEN);
                a->text = strdup("❤️");
            }
            schedule_text(pk, 1200, "❤️ Reacted to your message.");
        } else {
            schedule_text(pk, 800, "Reactions need a message sent from the Khandaq app.");
        }
        return;
    }

    /* Anything else: show typing, answer with a quote, and react to the original. */
    Friend_State *st = fstate(fn);
    char reply[700];
    const size_t qlen = utf8_cut(text, strlen(text), 300);
    const char *said = is_reply ? "You replied" : "You wrote";
    if (st->echo_count < 2) {
        snprintf(reply, sizeof reply,
                 "%s: “%.*s”\nI'm the demo contact, so I repeat what you send. Type “help” to see "
                 "everything I can do.",
                 said, (int)qlen, text);
    } else {
        snprintf(reply, sizeof reply, "%s: “%.*s” ✓", said, (int)qlen, text);
    }
    st->echo_count++;
    set_typing(fn, true);
    a = schedule_text(pk, 1100, reply);
    if (a) a->typing_off = true;
    if (id) {
        a = schedule(ACT_REACT, pk, 700);
        if (a) {
            memcpy(a->id, id, MSGV3_ID_LEN);
            a->text = strdup("\U0001F44D");
        }
    }
}

static bool seen_before(Friend_State *st, const uint8_t *id)
{
    const unsigned n = st->seen_count < SEEN_IDS ? st->seen_count : SEEN_IDS;
    for (unsigned i = 0; i < n; i++) {
        if (memcmp(st->seen[i], id, MSGV3_ID_LEN) == 0) {
            return true;
        }
    }
    memcpy(st->seen[st->seen_pos], id, MSGV3_ID_LEN);
    st->seen_pos = (st->seen_pos + 1) % SEEN_IDS;
    st->seen_count++;
    return false;
}

static void on_friend_message(Tox *tox, uint32_t fn, TOX_MESSAGE_TYPE type, const uint8_t *msg, size_t len, void *ud)
{
    if (type == TOX_MESSAGE_TYPE_HIGH_LEVEL_ACK) {
        if (len >= 1 + MSGV3_SUFFIX_LEN && msg[len - MSGV3_SUFFIX_LEN] == 0 && msg[len - MSGV3_SUFFIX_LEN + 1] == 0) {
            outbox_ack(fn, msg + len - MSGV3_SUFFIX_LEN + MSGV3_GUARD_LEN);
        }
        return;
    }
    uint8_t pk[TOX_PUBLIC_KEY_SIZE];
    if (!tox_friend_get_public_key(tox, fn, pk, NULL)) {
        return;
    }

    bool v3 = false;
    uint8_t id[MSGV3_ID_LEN];
    uint32_t ts = 0;
    size_t text_len = len;
    if (len > MSGV3_SUFFIX_LEN) {
        const size_t pos = len - MSGV3_SUFFIX_LEN;
        if (msg[pos] == 0 && msg[pos + 1] == 0) {
            v3 = true;
            memcpy(id, msg + pos + MSGV3_GUARD_LEN, MSGV3_ID_LEN);
            ts = get_u32_be(msg + pos + MSGV3_GUARD_LEN + MSGV3_ID_LEN);
            text_len = pos;
        }
    }
    size_t n = 0;
    while (n < text_len && msg[n] != 0) {
        n++;
    }

    Friend_State *st = fstate(fn);
    if (v3) {
        send_ack(fn, id, ts);
        if (seen_before(st, id)) {
            return; /* a resend of something we already answered */
        }
    }
    if (n == 0 || !take_token(&st->tokens, &st->tokens_ms, mono_ms())) {
        return;
    }
    char *text = strndup((const char *)msg, n);
    if (!text) {
        return;
    }
    handle_text(fn, pk, text, v3 ? id : NULL);
    free(text);
}

static void on_friend_lossless(Tox *tox, uint32_t fn, const uint8_t *data, size_t len, void *ud)
{
    if (len < 2) {
        return;
    }
    uint8_t pk[TOX_PUBLIC_KEY_SIZE];
    if (!tox_friend_get_public_key(tox, fn, pk, NULL)) {
        return;
    }
    Friend_State *st = fstate(fn);
    const bool kq = len >= 4 && data[1] == 'K' && data[2] == 'Q' && data[3] == 1;

    switch (data[0]) {
        case PKT_GROUP_INVITE_REQUEST:
            if (len == 2 + TOX_GROUP_CHAT_ID_SIZE && data[1] == 1 && g_demo_group != UINT32_MAX &&
                memcmp(data + 2, g_demo_chat_id, TOX_GROUP_CHAT_ID_SIZE) == 0) {
                schedule(ACT_GROUP_INVITE, pk, 300);
            }
            return;
        case PKT_EDIT:
            if (kq && len >= 40 && take_token(&st->tokens, &st->tokens_ms, mono_ms())) {
                char reply[400];
                const size_t qlen = utf8_cut((const char *)data + 40, len - 40, 200);
                snprintf(reply, sizeof reply, "I see you edited a message. It now reads: “%.*s” ✓", (int)qlen,
                         (const char *)data + 40);
                schedule_text(pk, 700, reply);
            }
            return;
        case PKT_DELETE:
            if (kq && (len == 40 || len == 41) && take_token(&st->tokens, &st->tokens_ms, mono_ms())) {
                schedule_text(pk, 700, "You deleted a message for both of us ✓");
            }
            return;
        case PKT_REACTION:
            if (kq && len >= 44 && data[41] == 1 && data[42] >= 1 && data[42] <= 16 && len >= 43u + data[42] &&
                take_token(&st->tokens, &st->tokens_ms, mono_ms())) {
                char reply[96];
                snprintf(reply, sizeof reply, "You reacted %.*s ✓", (int)data[42], (const char *)data + 43);
                schedule_text(pk, 700, reply);
            }
            return;
        case PKT_PUSH_TOKEN:
        default:
            return; /* push registration and other client extensions need no answer */
    }
}

/* ---------------------------------------------------------------------------------------------- */
/* friend lifecycle                                                                                */
/* ---------------------------------------------------------------------------------------------- */

static void prune_friends(bool force_one)
{
    const size_t count = tox_self_get_friend_list_size(g_tox);
    uint32_t *list = calloc(count ? count : 1, sizeof *list);
    if (!list) {
        return;
    }
    tox_self_get_friend_list(g_tox, list);
    const uint64_t now = unix_now();
    uint32_t oldest = UINT32_MAX;
    uint64_t oldest_seen = UINT64_MAX;
    for (size_t i = 0; i < count; i++) {
        const uint32_t fn = list[i];
        if (friend_online(fn)) {
            continue;
        }
        Tox_Err_Friend_Get_Last_Online err;
        const uint64_t last = tox_friend_get_last_online(g_tox, fn, &err);
        if (err != TOX_ERR_FRIEND_GET_LAST_ONLINE_OK) {
            continue;
        }
        if (last < oldest_seen) {
            oldest_seen = last;
            oldest = fn;
        }
        if (now > last && now - last > FRIEND_EXPIRY_S) {
            uint8_t pk[TOX_PUBLIC_KEY_SIZE];
            if (tox_friend_get_public_key(g_tox, fn, pk, NULL)) {
                welcomed_remove(pk);
            }
            tox_friend_delete(g_tox, fn, NULL);
            g_dirty = true;
            if (oldest == fn) {
                oldest = UINT32_MAX;
            }
        }
    }
    if (force_one && tox_self_get_friend_list_size(g_tox) >= MAX_FRIENDS && oldest != UINT32_MAX) {
        uint8_t pk[TOX_PUBLIC_KEY_SIZE];
        if (tox_friend_get_public_key(g_tox, oldest, pk, NULL)) {
            welcomed_remove(pk);
        }
        tox_friend_delete(g_tox, oldest, NULL);
        g_dirty = true;
    }
    free(list);
    welcomed_save();
}

static void on_friend_request(Tox *tox, const uint8_t *pk, const uint8_t *msg, size_t len, void *ud)
{
    if (tox_self_get_friend_list_size(tox) >= MAX_FRIENDS) {
        prune_friends(true);
    }
    Tox_Err_Friend_Add err;
    const uint32_t fn = tox_friend_add_norequest(tox, pk, &err);
    if (err != TOX_ERR_FRIEND_ADD_OK) {
        log_line("friend request from %s not accepted (%d)", pk_tag(pk), (int)err);
        return;
    }
    fstate_reset(fn);
    g_dirty = true;
    log_line("accepted contact request from %s", pk_tag(pk));
}

static void on_friend_connection(Tox *tox, uint32_t fn, TOX_CONNECTION status, void *ud)
{
    Friend_State *st = fstate(fn);
    const bool was = st->online;
    st->online = status != TOX_CONNECTION_NONE;
    uint8_t pk[TOX_PUBLIC_KEY_SIZE];
    if (!tox_friend_get_public_key(tox, fn, pk, NULL)) {
        return;
    }
    if (!st->online) {
        if (was) {
            log_line("%s went offline", pk_tag(pk));
        }
        free_transfers_of(fn);
        return;
    }
    if (was) {
        return; /* UDP <-> TCP switch, not a new session */
    }
    st->online_since_ms = mono_ms();
    log_line("%s is online (%s)", pk_tag(pk), status == TOX_CONNECTION_UDP ? "udp" : "tcp");
    schedule(ACT_AVATAR, pk, 600);
    schedule(ACT_RESEND, pk, 1200);

    const uint32_t now = unix_now();
    Welcome_Entry *e = welcomed_find(pk);
    if (!e) {
        schedule_welcome_pack(pk);
        welcomed_add(pk, now, now);
        welcomed_save();
        return;
    }
    /* The first invitation may have been sent into a dead connection. Repeating it is harmless:
     * the clients ignore an invitation to a group they are already in. */
    if (now - e->first < 24u * 3600u) {
        schedule(ACT_GROUP_INVITE, pk, 5000);
    }
    if (now - e->last > WELCOME_BACK_AFTER_S) {
        Action *a = schedule(ACT_GREETING, pk, 1500);
        if (a) a->flag = false;
        e->last = now;
        welcomed_save();
    }
    if (st->missed_call_note) {
        st->missed_call_note = false;
        schedule_text(pk, 2500, "I tried to call you, but you were offline. Type “call me” again and lock the "
                                "phone within 10 seconds.");
    }
}

/* ---------------------------------------------------------------------------------------------- */
/* file callbacks                                                                                  */
/* ---------------------------------------------------------------------------------------------- */

static void on_file_chunk_request(Tox *tox, uint32_t fn, uint32_t file_number, uint64_t position, size_t length,
                                  void *ud)
{
    Transfer *t = transfer_find(fn, file_number, true);
    if (!t) {
        return;
    }
    t->last_activity_ms = mono_ms();
    if (length == 0) {
        transfer_free(t); /* delivered */
        return;
    }
    if (position >= t->size) {
        return;
    }
    if (position + length > t->size) {
        length = (size_t)(t->size - position);
    }
    tox_file_send_chunk(tox, fn, file_number, position, t->data + position, length, NULL);
}

static void on_file_recv_control(Tox *tox, uint32_t fn, uint32_t file_number, TOX_FILE_CONTROL control, void *ud)
{
    if (control != TOX_FILE_CONTROL_CANCEL) {
        return;
    }
    Transfer *t = transfer_find(fn, file_number, true);
    if (!t) {
        t = transfer_find(fn, file_number, false);
    }
    if (t) {
        transfer_free(t);
    }
}

static void on_file_recv(Tox *tox, uint32_t fn, uint32_t file_number, uint32_t kind, uint64_t size,
                         const uint8_t *filename, size_t filename_len, void *ud)
{
    uint8_t pk[TOX_PUBLIC_KEY_SIZE];
    if (kind != TOX_FILE_KIND_DATA || !tox_friend_get_public_key(tox, fn, pk, NULL)) {
        tox_file_control(tox, fn, file_number, TOX_FILE_CONTROL_CANCEL, NULL);
        return; /* the contact's avatar: nothing to do with it */
    }
    char name[256];
    sanitize_name(filename, filename_len, name, sizeof name);
    if (size == 0 || size > MAX_ECHO_FILE_BYTES) {
        tox_file_control(tox, fn, file_number, TOX_FILE_CONTROL_CANCEL, NULL);
        if (size > MAX_ECHO_FILE_BYTES && size != UINT64_MAX) {
            char reply[200];
            snprintf(reply, sizeof reply, "That file is %.1f MB. I send back files up to 20 MB; try a smaller one.",
                     (double)size / (1024.0 * 1024.0));
            schedule_text(pk, 500, reply);
        }
        return;
    }
    Transfer *t = incoming_count() < MAX_INCOMING_TRANSFERS ? transfer_alloc() : NULL;
    uint8_t *buf = t ? malloc((size_t)size) : NULL;
    if (!t || !buf) {
        if (t) {
            transfer_free(t);
        }
        tox_file_control(tox, fn, file_number, TOX_FILE_CONTROL_CANCEL, NULL);
        schedule_text(pk, 500, "I'm receiving too many files right now. Please send it again in a minute.");
        return;
    }
    t->outgoing = false;
    t->owned = true;
    t->fn = fn;
    t->file_number = file_number;
    memcpy(t->pk, pk, sizeof pk);
    t->data = buf;
    t->size = (size_t)size;
    t->voice = ends_with_ci(name, ".file.m4a") && size <= MAX_VOICE_NOTE_BYTES;
    snprintf(t->name, sizeof t->name, "%s", name);
    tox_file_control(tox, fn, file_number, TOX_FILE_CONTROL_RESUME, NULL);
}

static void on_file_recv_chunk(Tox *tox, uint32_t fn, uint32_t file_number, uint64_t position, const uint8_t *data,
                               size_t length, void *ud)
{
    Transfer *t = transfer_find(fn, file_number, false);
    if (!t) {
        return;
    }
    t->last_activity_ms = mono_ms();
    if (length == 0) {
        if (t->received >= t->size) {
            /* Hand the buffer to an echo action; the transfer record must not free it. */
            Action *a = schedule(ACT_ECHO_FILE, t->pk, 900);
            if (a) {
                a->blob = t->data;
                a->blob_len = t->size;
                a->flag = t->voice;
                a->text = strdup(t->name);
                t->owned = false;
            }
            char reply[300];
            snprintf(reply, sizeof reply, "Got your %s (%.1f KB). Sending it back to you…",
                     t->voice ? "voice message" : "file", (double)t->size / 1024.0);
            schedule_text(t->pk, 400, reply);
        }
        transfer_free(t);
        return;
    }
    if (position > t->size || length > t->size - position) {
        tox_file_control(tox, fn, file_number, TOX_FILE_CONTROL_CANCEL, NULL);
        transfer_free(t);
        return;
    }
    memcpy(t->data + position, data, length);
    if (position + length > t->received) {
        t->received = (size_t)(position + length);
    }
}

/* ---------------------------------------------------------------------------------------------- */
/* group callbacks                                                                                 */
/* ---------------------------------------------------------------------------------------------- */

static void on_group_invite(Tox *tox, uint32_t fn, const uint8_t *data, size_t len, const uint8_t *name, size_t nlen,
                            void *ud)
{
    uint8_t pk[TOX_PUBLIC_KEY_SIZE];
    if (len == 0 || len > 4096 || !tox_friend_get_public_key(tox, fn, pk, NULL)) {
        return;
    }
    Action *a = schedule(ACT_ACCEPT_GROUP_INVITE, pk, 1500);
    if (a) {
        a->blob = malloc(len);
        if (a->blob) {
            memcpy(a->blob, data, len);
            a->blob_len = len;
        }
    }
}

static void on_group_self_join(Tox *tox, uint32_t gn, void *ud)
{
    if (gn == g_demo_group) {
        return;
    }
    foreign_group_add(gn);
    Action *a = schedule(ACT_GROUP_TEXT, NULL, 2500);
    if (a) {
        a->group_number = gn;
        a->text = strdup("Thanks for adding me! I'm the Khandaq demo contact. Write anything here and I'll answer.");
    }
}

static void on_group_peer_join(Tox *tox, uint32_t gn, uint32_t peer, void *ud)
{
    if (!is_our_group(gn)) {
        return;
    }
    Action *a = schedule(ACT_GROUP_WELCOME, NULL, 3000);
    if (a) {
        a->group_number = gn;
        a->peer_id = peer;
    }
}

static void on_group_message(Tox *tox, uint32_t gn, uint32_t peer, TOX_MESSAGE_TYPE type, const uint8_t *msg,
                             size_t len, uint32_t message_id, void *ud)
{
    if (!is_our_group(gn) || len == 0) {
        return;
    }
    Tox_Err_Group_Self_Query err;
    if (peer == tox_group_self_get_peer_id(tox, gn, &err)) {
        return;
    }
    if (!take_token(&g_group_tokens, &g_group_tokens_ms, mono_ms())) {
        return;
    }
    Action *a = schedule(ACT_GROUP_TEXT, NULL, 1200);
    if (a) {
        a->group_number = gn;
        a->peer_id = peer;
        a->flag = true; /* reply addressed to the peer */
    }
}

/* ---------------------------------------------------------------------------------------------- */
/* action execution                                                                                */
/* ---------------------------------------------------------------------------------------------- */

typedef enum { DONE, RETRY } Outcome;

static Outcome perform(Action *a, uint64_t now)
{
    if (a->kind == ACT_GROUP_TEXT || a->kind == ACT_GROUP_WELCOME) {
        if (!is_our_group(a->group_number)) {
            return DONE;
        }
        Tox_Err_Group_Is_Connected err;
        if (tox_group_is_connected(g_tox, a->group_number, &err) != 1) {
            return RETRY;
        }
        char name[64];
        char text[600];
        if (a->kind == ACT_GROUP_WELCOME) {
            Tox_Err_Group_Self_Query serr;
            if (a->peer_id == tox_group_self_get_peer_id(g_tox, a->group_number, &serr)) {
                return DONE;
            }
            group_peer_name(a->group_number, a->peer_id, name, sizeof name);
            snprintf(text, sizeof text,
                     "Welcome to the group, %s! \U0001F44B Messages here are end-to-end encrypted and travel peer to "
                     "peer. Write anything and I'll answer.",
                     name[0] ? name : "friend");
            group_send(a->group_number, text);
        } else if (a->flag) {
            group_peer_name(a->group_number, a->peer_id, name, sizeof name);
            snprintf(text, sizeof text, "%s, got your message in the group ✓ (#%u)", name[0] ? name : "Friend",
                     ++g_group_reply_count);
            group_send(a->group_number, text);
        } else if (a->text) {
            group_send(a->group_number, a->text);
        }
        return DONE;
    }

    if (a->kind == ACT_ACCEPT_GROUP_INVITE) {
        uint32_t fn;
        if (!a->blob || !friend_number(a->pk, &fn)) {
            return DONE;
        }
        Tox_Err_Group_Invite_Accept err;
        const uint32_t gn = tox_group_invite_accept(g_tox, fn, a->blob, a->blob_len, (const uint8_t *)BOT_NAME,
                                                    strlen(BOT_NAME), NULL, 0, &err);
        if (err == TOX_ERR_GROUP_INVITE_ACCEPT_OK) {
            foreign_group_add(gn);
            g_dirty = true;
            log_line("joined a group on invitation from %s", pk_tag(a->pk));
        }
        return DONE;
    }

    uint32_t fn;
    if (!friend_number(a->pk, &fn)) {
        return DONE; /* contact deleted meanwhile */
    }
    if (!friend_online(fn)) {
        if (a->kind == ACT_CALL) {
            fstate(fn)->missed_call_note = true;
            return DONE;
        }
        return RETRY;
    }

    switch (a->kind) {
        case ACT_TEXT:
            if (a->text) {
                send_text(fn, a->text, NULL);
            }
            if (a->typing_off) {
                set_typing(fn, false);
            }
            return DONE;

        case ACT_GREETING: {
            char name[64];
            char text[400];
            friend_name(fn, name, sizeof name);
            if (a->flag) {
                snprintf(text, sizeof text,
                         "Hello%s%s! \U0001F44B I'm Khandaq Demo, an always-online test contact run by the Khandaq "
                         "team, so you can try every feature without a second phone.",
                         name[0] ? ", " : "", name);
            } else {
                snprintf(text, sizeof text,
                         "Welcome back%s%s! \U0001F44B I'm still here. Type “help” to see what you can try.",
                         name[0] ? ", " : "", name);
            }
            send_text(fn, text, NULL);
            return DONE;
        }

        case ACT_TEXT_THEN_EDIT:
        case ACT_TEXT_THEN_DELETE: {
            uint8_t id[MSGV3_ID_LEN];
            if (!a->text || !send_text(fn, a->text, id)) {
                return DONE;
            }
            Action *next = schedule(a->kind == ACT_TEXT_THEN_EDIT ? ACT_EDIT : ACT_DELETE, a->pk, 3000);
            if (next) {
                memcpy(next->id, id, MSGV3_ID_LEN);
                if (a->kind == ACT_TEXT_THEN_EDIT) {
                    next->text = strdup("✏️ Edited! Message editing works across devices.");
                }
            }
            return DONE;
        }

        case ACT_EDIT:
            if (a->text) {
                send_edit(fn, a->id, a->text);
            }
            return DONE;

        case ACT_DELETE:
            send_delete(fn, a->id);
            schedule_text(a->pk, 700, "\U0001F5D1️ Done: that message is now deleted for both of us.");
            return DONE;

        case ACT_FILE:
            if (!a->asset || !a->asset->data) {
                return DONE;
            }
            if (transfer_slots_full()) {
                return RETRY;
            }
            send_asset(fn, a->asset);
            return DONE;

        case ACT_ECHO_FILE: {
            char name[300];
            if (transfer_slots_full()) {
                return RETRY;
            }
            if (a->flag) {
                uint8_t r[4];
                char hex[9];
                tox_messagev3_get_new_message_id(r); /* 32 random bytes; four are plenty for a name */
                bin_to_hex(r, 4, hex);
                snprintf(name, sizeof name, "voice_echo_%s.file.m4a", hex);
            } else {
                snprintf(name, sizeof name, "echo_%s", a->text ? a->text : "file");
            }
            if (send_data(fn, TOX_FILE_KIND_DATA, NULL, a->blob, a->blob_len, name, true)) {
                a->blob = NULL; /* now owned by the transfer */
            }
            return DONE;
        }

        case ACT_AVATAR:
            send_avatar(fn);
            return DONE;

        case ACT_RESEND:
            outbox_resend(fn);
            return DONE;

        case ACT_LOCATION:
            send_text(fn, DEMO_LOCATION, NULL);
            return DONE;

        case ACT_GROUP_INVITE:
            if (g_demo_group != UINT32_MAX) {
                Tox_Err_Group_Invite_Friend err;
                tox_group_invite_friend(g_tox, g_demo_group, fn, &err);
                if (err == TOX_ERR_GROUP_INVITE_FRIEND_FAIL_SEND || err == TOX_ERR_GROUP_INVITE_FRIEND_DISCONNECTED) {
                    return RETRY;
                }
                if (err != TOX_ERR_GROUP_INVITE_FRIEND_OK) {
                    log_line("group invite to %s failed (%d)", pk_tag(a->pk), (int)err);
                }
            }
            return DONE;

        case ACT_REACT:
            if (a->text) {
                send_reaction(fn, a->id, a->text);
            }
            return DONE;

        case ACT_CALL: {
            if (call_find(fn)) {
                return DONE;
            }
            Call *c = call_alloc(fn, false);
            if (!c) {
                schedule_text(a->pk, 300, "I'm on other calls right now. Type “call me” again in a minute.");
                return DONE;
            }
            Toxav_Err_Call err;
            if (!toxav_call(g_av, fn, AUDIO_BITRATE_KBPS, a->flag ? VIDEO_BITRATE_KBPS : 0, &err)) {
                log_line("call to %s failed (%d)", pk_tag(a->pk), (int)err);
                call_free(c);
                return DONE;
            }
            c->sending_video = a->flag;
            log_line("calling %s (%s)", pk_tag(a->pk), a->flag ? "video" : "audio");
            return DONE;
        }

        default:
            return DONE;
    }
}

static void run_actions(uint64_t now)
{
    /* Splice newly scheduled actions in first; the walk below only ever sees a stable list. */
    while (g_incoming) {
        Action *a = g_incoming;
        g_incoming = a->next;
        a->next = g_actions;
        g_actions = a;
    }
    Action **pp = &g_actions;
    while (*pp) {
        Action *a = *pp;
        if (a->due_ms > now) {
            pp = &a->next;
            continue;
        }
        const Outcome r = perform(a, now);
        if (r == DONE || now > a->expires_ms) {
            *pp = a->next;
            action_free(a);
        } else {
            a->due_ms = now + 2000;
            pp = &a->next;
        }
    }
}

/* ---------------------------------------------------------------------------------------------- */
/* bootstrap and housekeeping                                                                      */
/* ---------------------------------------------------------------------------------------------- */

/* nodes.txt: "host port public_key_hex [tcp_port,tcp_port...]" per line, '#' comments. */
static void bootstrap(void)
{
    FILE *f = fopen(g_nodes_path, "r");
    if (!f) {
        log_line("nodes file %s: %s", g_nodes_path, strerror(errno));
        return;
    }
    char line[512];
    unsigned ok = 0;
    while (fgets(line, sizeof line, f)) {
        char host[256];
        char key[80];
        char tcp[128] = "";
        unsigned port = 0;
        uint8_t pk[TOX_PUBLIC_KEY_SIZE];
        if (line[0] == '#' || sscanf(line, "%255s %u %79s %127s", host, &port, key, tcp) < 3 || port == 0 ||
            port > 65535 || !hex_to_bin(key, pk, sizeof pk)) {
            continue;
        }
        if (tox_bootstrap(g_tox, host, (uint16_t)port, pk, NULL)) {
            ok++;
        }
        for (char *tok = strtok(tcp, ","); tok; tok = strtok(NULL, ",")) {
            const long tp = strtol(tok, NULL, 10);
            if (tp > 0 && tp < 65536) {
                tox_add_tcp_relay(g_tox, host, (uint16_t)tp, pk, NULL);
            }
        }
    }
    fclose(f);
    log_line("bootstrapped from %u nodes", ok);
}

static void on_self_connection(Tox *tox, TOX_CONNECTION status, void *ud)
{
    g_self_conn = status;
    log_line("DHT %s", status == TOX_CONNECTION_NONE ? "disconnected" : status == TOX_CONNECTION_UDP ? "connected (udp)"
                                                                                                      : "connected (tcp)");
}

static void stale_transfers(uint64_t now)
{
    for (size_t i = 0; i < MAX_TRANSFERS; i++) {
        Transfer *t = &g_tr[i];
        if (t->used && now - t->last_activity_ms > TRANSFER_STALL_MS) {
            tox_file_control(g_tox, t->fn, t->file_number, TOX_FILE_CONTROL_CANCEL, NULL);
            transfer_free(t);
        }
    }
}

static void expire_foreign_groups(uint64_t now)
{
    for (size_t i = 0; i < MAX_FOREIGN_GROUPS; i++) {
        if (g_foreign[i].used && now - g_foreign[i].joined_ms > FOREIGN_GROUP_TTL_MS) {
            tox_group_leave(g_tox, g_foreign[i].group_number, NULL, 0, NULL);
            g_foreign[i].used = false;
            g_dirty = true;
        }
    }
}

static void on_signal(int sig)
{
    (void)sig;
    g_running = 0;
}

static void usage(const char *argv0)
{
    fprintf(stderr,
            "usage: %s [--data DIR] [--assets DIR] [--nodes FILE] [--port N] [--lan] [--print-id]\n"
            "  --data DIR    identity, contact list and state (default ./data)\n"
            "  --assets DIR  welcome-pack media and nodes.txt (default ./assets)\n"
            "  --nodes FILE  bootstrap list (default ASSETS/nodes.txt)\n"
            "  --port N      first UDP port to try (default 33445)\n"
            "  --lan         enable LAN discovery (local testing only)\n"
            "  --print-id    print the Tox ID and exit\n",
            argv0);
}

int main(int argc, char **argv)
{
    bool print_id = false;
    g_start_ms = mono_ms();

    for (int i = 1; i < argc; i++) {
        if (!strcmp(argv[i], "--data") && i + 1 < argc) {
            snprintf(g_data_dir, sizeof g_data_dir, "%s", argv[++i]);
        } else if (!strcmp(argv[i], "--assets") && i + 1 < argc) {
            snprintf(g_assets_dir, sizeof g_assets_dir, "%s", argv[++i]);
        } else if (!strcmp(argv[i], "--nodes") && i + 1 < argc) {
            snprintf(g_nodes_path, sizeof g_nodes_path, "%s", argv[++i]);
        } else if (!strcmp(argv[i], "--port") && i + 1 < argc) {
            g_port = (uint16_t)strtoul(argv[++i], NULL, 10);
        } else if (!strcmp(argv[i], "--lan")) {
            g_lan = true;
        } else if (!strcmp(argv[i], "--print-id")) {
            print_id = true;
        } else {
            usage(argv[0]);
            return 2;
        }
    }
    if (!g_nodes_path[0]) {
        path_join(g_nodes_path, sizeof g_nodes_path, g_assets_dir, "nodes.txt");
    }
    mkdir(g_data_dir, 0700);

    signal(SIGINT, on_signal);
    signal(SIGTERM, on_signal);
    signal(SIGPIPE, SIG_IGN);

    struct Tox_Options *opts = tox_options_new(NULL);
    tox_options_set_ipv6_enabled(opts, true);
    tox_options_set_udp_enabled(opts, true);
    tox_options_set_local_discovery_enabled(opts, g_lan);
    tox_options_set_hole_punching_enabled(opts, true);
    tox_options_set_start_port(opts, g_port);
    tox_options_set_end_port(opts, (uint16_t)(g_port + 10));

    char save_path[PATH_MAX];
    path_join(save_path, sizeof save_path, g_data_dir, "khandaq-demo.tox");
    uint8_t *saved = NULL;
    size_t saved_size = 0;
    if (read_file(save_path, &saved, &saved_size, 64u * 1024u * 1024u)) {
        tox_options_set_savedata_type(opts, TOX_SAVEDATA_TYPE_TOX_SAVE);
        tox_options_set_savedata_data(opts, saved, saved_size);
    }
    Tox_Err_New err;
    g_tox = tox_new(opts, &err);
    tox_options_free(opts);
    free(saved);
    if (!g_tox) {
        log_line("tox_new failed (%d)", (int)err);
        return 1;
    }

    tox_self_set_name(g_tox, (const uint8_t *)BOT_NAME, strlen(BOT_NAME), NULL);
    tox_self_set_status_message(g_tox, (const uint8_t *)BOT_STATUS, strlen(BOT_STATUS), NULL);
    tox_self_set_status(g_tox, TOX_USER_STATUS_NONE);

    uint8_t address[TOX_ADDRESS_SIZE];
    char address_hex[2 * TOX_ADDRESS_SIZE + 2];
    tox_self_get_address(g_tox, address);
    bin_to_hex(address, TOX_ADDRESS_SIZE, address_hex);
    {
        char path[PATH_MAX];
        char line[sizeof address_hex + 1];
        snprintf(line, sizeof line, "%s\n", address_hex);
        path_join(path, sizeof path, g_data_dir, "toxid.txt");
        write_file_atomic(path, (const uint8_t *)line, strlen(line));
    }
    if (print_id) {
        save_tox();
        printf("%s\n", address_hex);
        tox_kill(g_tox);
        return 0;
    }
    log_line("Tox ID %s", address_hex);

    load_asset(&g_avatar);
    load_asset(&g_photo);
    load_asset(&g_voice);
    load_asset(&g_document);
    load_greeting();
    if (g_avatar.data) {
        tox_hash(g_avatar_hash, g_avatar.data, g_avatar.size);
    }
    welcomed_load();

    tox_callback_self_connection_status(g_tox, on_self_connection);
    tox_callback_friend_request(g_tox, on_friend_request);
    tox_callback_friend_connection_status(g_tox, on_friend_connection);
    tox_callback_friend_message(g_tox, on_friend_message);
    tox_callback_friend_lossless_packet(g_tox, on_friend_lossless);
    tox_callback_file_chunk_request(g_tox, on_file_chunk_request);
    tox_callback_file_recv_control(g_tox, on_file_recv_control);
    tox_callback_file_recv(g_tox, on_file_recv);
    tox_callback_file_recv_chunk(g_tox, on_file_recv_chunk);
    tox_callback_group_invite(g_tox, on_group_invite);
    tox_callback_group_self_join(g_tox, on_group_self_join);
    tox_callback_group_peer_join(g_tox, on_group_peer_join);
    tox_callback_group_message(g_tox, on_group_message);

    Toxav_Err_New av_err;
    g_av = toxav_new(g_tox, &av_err);
    if (!g_av) {
        log_line("toxav_new failed (%d)", (int)av_err);
        tox_kill(g_tox);
        return 1;
    }
    toxav_callback_call(g_av, on_av_call, NULL);
    toxav_callback_call_state(g_av, on_av_call_state, NULL);
    toxav_callback_audio_receive_frame(g_av, on_audio_frame, NULL);
    toxav_callback_video_receive_frame(g_av, on_video_frame, NULL);

    ensure_demo_group();
    save_tox();
    bootstrap();

    uint64_t last_bootstrap = mono_ms();
    uint64_t last_group_setup_try = 0;
    uint64_t last_minute = mono_ms();
    uint64_t last_hour = mono_ms();
    while (g_running) {
        tox_iterate(g_tox, NULL);
        toxav_iterate(g_av);
        const uint64_t now = mono_ms();
        run_actions(now);
        pump_calls(now);

        if (g_self_conn == TOX_CONNECTION_NONE && now - last_bootstrap > 20000) {
            bootstrap();
            last_bootstrap = now;
        }
        if (!g_group_configured && g_demo_group != UINT32_MAX && g_self_conn != TOX_CONNECTION_NONE &&
            now - last_group_setup_try > 5000) {
            last_group_setup_try = now;
            configure_demo_group();
        }
        if (now - last_minute > 60000) {
            stale_transfers(now);
            expire_foreign_groups(now);
            last_minute = now;
        }
        if (now - last_hour > 3600000) {
            prune_friends(false);
            last_hour = now;
        }
        if ((g_dirty && now - g_last_save_ms > 3000) || now - g_last_save_ms > 600000) {
            save_tox();
        }

        uint32_t wait = tox_iteration_interval(g_tox);
        const uint32_t av_wait = toxav_iteration_interval(g_av);
        if (av_wait < wait) wait = av_wait;
        if (any_call_active() && wait > 10) wait = 10;
        if (wait > 50) wait = 50;
        sleep_ms(wait);
    }

    log_line("shutting down");
    for (size_t i = 0; i < MAX_CALLS; i++) {
        if (g_calls[i].used) {
            toxav_call_control(g_av, g_calls[i].fn, TOXAV_CALL_CONTROL_CANCEL, NULL);
            call_free(&g_calls[i]);
        }
    }
    save_tox();
    welcomed_save();
    toxav_kill(g_av);
    tox_kill(g_tox);
    return 0;
}
