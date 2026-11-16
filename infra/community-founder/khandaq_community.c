/*
 * Khandaq Community founder.
 *
 * Holds the founder key of "Khandaq Community", the official public NGC group whose chat id is
 * compiled into every Khandaq client. It creates the group on first start, keeps the founder
 * settings in force (public, topic lock on, peer limit, topic) and lets a few paired admins moderate
 * the group from a 1:1 chat with it. It never writes in the group itself.
 *
 * It links the toxcore the iOS client ships (see build.sh), so NGC behaves exactly as it does
 * between the clients, with two changes for the founder: its group is connected as soon as it is
 * restored (toxcore-founder-connected.patch), rather than silent until a member it invited syncs;
 * and it renews the group's announce on the DHT every 16 s per node, within the 30 s a node keeps
 * it (toxcore-founder-announce.patch), so a join by chat id finds it however long it has been up.
 *
 * Single-threaded: every tox_* call is made from the main loop or from a toxcore callback inside
 * tox_iterate. Admin commands and contact-request bookkeeping are queued by the callbacks and run
 * after tox_iterate returns, so moderation calls are never made from inside toxcore.
 *
 * Privacy: the log holds counts and 8-hex key tags only -- never a nick, a message, a topic or the
 * pairing code.
 *
 * State (--data; /var/lib/khandaq-community in production). Never recreate it: the chat id is
 * derived from the founder key in the savedata, and every client has the chat id compiled in.
 *   khandaq-community.tox  identity and group, including the founder key
 *   group.txt              chat id, 64 hex
 *   toxid.txt              the bot's Tox ID, for admins to add it
 *   admin-code.txt         pairing code, 10 Crockford base32 characters
 *   admins.txt             paired admins, one public key per line
 *   limit.txt, topic.txt   peer limit and topic, as /limit and /topic last set them
 *   muted.txt              group keys of muted members, re-muted after restarts and rejoins
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
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#include <sodium.h>

#include "community_common.h"

#ifndef PATH_MAX
#define PATH_MAX 4096
#endif

#define BOT_NAME   "Khandaq Community"
#define BOT_STATUS "Founder of the official Khandaq Community group. Admin channel only."

#define SAVE_FILE   "khandaq-community.tox"
#define GROUP_FILE  "group.txt"
#define TOXID_FILE  "toxid.txt"
#define ADMINS_FILE "admins.txt"
#define CODE_FILE   "admin-code.txt"
#define LIMIT_FILE  "limit.txt"
#define TOPIC_FILE  "topic.txt"
#define MUTED_FILE  "muted.txt"

/* Exit status for a state directory that does not add up. systemd does not restart on it
 * (RestartPreventExitStatus=3): starting again could only make things worse. */
#define EXIT_STATE 3

#define DEFAULT_PORT     33470
#define MAX_SAVEDATA     (64u * 1024u * 1024u)
#define MAX_ADMINS       64
#define MAX_MUTED        1000
#define MAX_FRIEND_SLOTS 4096
#define SEEN_IDS         32
#define COMMAND_QUEUE    16
#define FORGET_QUEUE     16
#define PENDING_UNMUTES  16
#define DROP_LOG_PER_HOUR 20

#define SETTINGS_RETRY_MS   5000u
#define SETTINGS_RECHECK_MS (10u * 60u * 1000u)
#define GROUP_POLL_MS       1000u
#define RECONNECT_RETRY_MS  30000u
#define MUTE_SETTLE_MS      30000u
#define MUTE_CHECK_MS       30000u
#define MUTE_AFTER_JOIN_MS  5000u
#define MUTE_FAIL_LOG_MS    (10u * 60u * 1000u)
#define UNMUTE_GRACE_MS     3000u
#define ONLINE_LOG_MS       (10u * 60u * 1000u)
#define TRAFFIC_LOG_MS      (60u * 60u * 1000u)
#define SAVE_DEBOUNCE_MS    3000u
#define SAVE_PERIOD_MS      (10u * 60u * 1000u)
#define SAVE_RETRY_MS       60000u
#define REBOOTSTRAP_MS      20000u

/* What toxcore can hold at once: MOD_MAX_NUM_SANCTIONS in group_moderation.h. */
#define TOXCORE_MAX_MUTED_ONLINE 30

/* ---------------------------------------------------------------------------------------------- */
/* small helpers                                                                                   */
/* ---------------------------------------------------------------------------------------------- */

static uint64_t g_start_ms;
static volatile sig_atomic_t g_running = 1;

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

static void sleep_ms(uint32_t ms)
{
    struct timespec ts = {ms / 1000, (long)(ms % 1000) * 1000000L};
    while (nanosleep(&ts, &ts) == -1 && errno == EINTR && g_running) {
    }
}

/* ---------------------------------------------------------------------------------------------- */
/* files                                                                                           */
/* ---------------------------------------------------------------------------------------------- */

static char g_data_dir[PATH_MAX] = "data";
static char g_nodes_path[PATH_MAX] = "assets/nodes.txt";
static uint16_t g_port = DEFAULT_PORT;
static bool g_lan;
static bool g_have_expected_group;
static uint8_t g_expected_group[KEY_SIZE];

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

static void state_path(const char *file, char out[PATH_MAX])
{
    path_join(out, PATH_MAX, g_data_dir, file);
}

typedef enum {
    READ_OK,
    READ_MISSING,
    READ_ERROR, /* errno says why */
    READ_BAD,   /* readable, but not what the file must hold */
} Read_Result;

/* The whole file, NUL-terminated (the terminator is not counted in *size). */
static Read_Result read_file(const char *path, uint8_t **data, size_t *size, size_t max)
{
    *data = NULL;
    *size = 0;
    const int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        return errno == ENOENT ? READ_MISSING : READ_ERROR;
    }
    struct stat st;
    if (fstat(fd, &st) != 0) {
        const int e = errno;
        close(fd);
        errno = e;
        return READ_ERROR;
    }
    if (!S_ISREG(st.st_mode) || st.st_size < 0 || (uint64_t)st.st_size > max) {
        close(fd);
        errno = S_ISREG(st.st_mode) ? EFBIG : EINVAL;
        return READ_ERROR;
    }
    const size_t len = (size_t)st.st_size;
    uint8_t *buf = malloc(len + 1);
    if (!buf) {
        close(fd);
        errno = ENOMEM;
        return READ_ERROR;
    }
    size_t off = 0;
    while (off < len) {
        const ssize_t n = read(fd, buf + off, len - off);
        if (n < 0 && errno == EINTR) {
            continue;
        }
        if (n <= 0) {
            const int e = n < 0 ? errno : EIO;
            free(buf);
            close(fd);
            errno = e;
            return READ_ERROR;
        }
        off += (size_t)n;
    }
    close(fd);
    buf[len] = 0;
    *data = buf;
    *size = len;
    return READ_OK;
}

static bool write_all(int fd, const uint8_t *data, size_t size)
{
    size_t off = 0;
    while (off < size) {
        const ssize_t n = write(fd, data + off, size - off);
        if (n < 0) {
            if (errno == EINTR) {
                continue;
            }
            return false;
        }
        off += (size_t)n;
    }
    return true;
}

/* A rename is only durable once the directory entry is on disk too. */
static void fsync_data_dir(void)
{
    const int fd = open(g_data_dir, O_RDONLY | O_CLOEXEC);
    if (fd >= 0) {
        fsync(fd);
        close(fd);
    }
}

/* Write a file atomically: a crash mid-write must never leave a truncated identity behind.
 * On failure errno says why. */
static bool write_file_atomic(const char *path, const uint8_t *data, size_t size)
{
    char tmp[PATH_MAX];
    if (snprintf(tmp, sizeof tmp, "%s.tmp", path) >= (int)sizeof tmp) {
        errno = ENAMETOOLONG;
        return false;
    }
    const int fd = open(tmp, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    if (fd < 0) {
        return false;
    }
    if (!write_all(fd, data, size) || fsync(fd) != 0) {
        const int e = errno;
        close(fd);
        unlink(tmp);
        errno = e;
        return false;
    }
    if (close(fd) != 0 || rename(tmp, path) != 0) {
        const int e = errno;
        unlink(tmp);
        errno = e;
        return false;
    }
    fsync_data_dir();
    return true;
}

static bool write_text_atomic(const char *file, const char *text)
{
    char path[PATH_MAX];
    state_path(file, path);
    return write_file_atomic(path, (const uint8_t *)text, strlen(text));
}

/* The next line of a NUL-terminated buffer, cut off in place; NULL at the end. Empty lines count. */
static char *next_line(char **cursor)
{
    char *line = *cursor;
    if (*line == '\0') {
        return NULL;
    }
    char *nl = strchr(line, '\n');
    if (nl) {
        *nl = '\0';
        *cursor = nl + 1;
    } else {
        *cursor = line + strlen(line);
    }
    return line;
}

/* ---------------------------------------------------------------------------------------------- */
/* global Tox state                                                                                */
/* ---------------------------------------------------------------------------------------------- */

static Tox *g_tox;
static Tox_Connection g_self_conn = TOX_CONNECTION_NONE;
static bool g_dirty;
static uint64_t g_last_save_ms;
static uint64_t g_save_retry_at_ms;

static uint32_t g_group = UINT32_MAX;
static uint8_t g_chat_id[KEY_SIZE];

static bool save_tox(void)
{
    char path[PATH_MAX];
    const size_t size = tox_get_savedata_size(g_tox);
    uint8_t *buf = malloc(size);
    if (!buf) {
        log_line("saving the identity failed: out of memory");
        g_save_retry_at_ms = mono_ms() + SAVE_RETRY_MS;
        return false;
    }
    tox_get_savedata(g_tox, buf);
    state_path(SAVE_FILE, path);
    const bool ok = write_file_atomic(path, buf, size);
    const int e = errno;
    sodium_memzero(buf, size); /* it holds the secret keys */
    free(buf);
    g_last_save_ms = mono_ms();
    if (!ok) {
        log_line("saving the identity failed: %s; retrying in %u s", strerror(e), SAVE_RETRY_MS / 1000);
        g_save_retry_at_ms = g_last_save_ms + SAVE_RETRY_MS;
        return false;
    }
    g_dirty = false;
    g_save_retry_at_ms = 0;
    return true;
}

/* ---------------------------------------------------------------------------------------------- */
/* admins and the pairing code                                                                     */
/* ---------------------------------------------------------------------------------------------- */

static uint8_t g_admins[MAX_ADMINS][KEY_SIZE];
static size_t g_admin_count;
static char g_code[PAIRING_CODE_LEN + 1];
static bool g_code_ok;

static bool admins_contains(const uint8_t *pk)
{
    for (size_t i = 0; i < g_admin_count; i++) {
        if (memcmp(g_admins[i], pk, KEY_SIZE) == 0) {
            return true;
        }
    }
    return false;
}

/* A Tox ID ends in a two-byte checksum: the XOR of the key and nospam, two bytes at a time. */
static bool tox_id_checksum_ok(const uint8_t *address)
{
    uint8_t sum[2] = {0, 0};
    for (size_t i = 0; i < TOX_ADDRESS_SIZE - 2; i++) {
        sum[i % 2] ^= address[i];
    }
    return sum[0] == address[TOX_ADDRESS_SIZE - 2] && sum[1] == address[TOX_ADDRESS_SIZE - 1];
}

/* One admin per line: a public key (64 hex) or a whole Tox ID (76 hex); text after the key (such
 * as the "# paired ..." note the bot appends) and lines starting with '#' are ignored. */
static void admins_load(void)
{
    char path[PATH_MAX];
    state_path(ADMINS_FILE, path);
    uint8_t *data = NULL;
    size_t size = 0;
    const Read_Result r = read_file(path, &data, &size, 1024u * 1024u);
    if (r == READ_MISSING) {
        return;
    }
    if (r != READ_OK) {
        log_line("cannot read %s: %s; no admin can use the bot", ADMINS_FILE, strerror(errno));
        return;
    }
    unsigned line_no = 0;
    char *cursor = (char *)data;
    for (char *line = next_line(&cursor); line; line = next_line(&cursor)) {
        line_no++;
        while (is_ascii_space((unsigned char)*line)) {
            line++;
        }
        if (*line == '\0' || *line == '#') {
            continue;
        }
        size_t n = 0;
        while (line[n] != '\0' && !is_ascii_space((unsigned char)line[n]) && line[n] != '#') {
            n++;
        }
        uint8_t address[TOX_ADDRESS_SIZE];
        const bool ok = (n == KEY_HEX_LEN && hex_to_bin(line, address, KEY_SIZE)) ||
                        (n == 2 * TOX_ADDRESS_SIZE && hex_to_bin(line, address, TOX_ADDRESS_SIZE) &&
                         tox_id_checksum_ok(address));
        if (!ok) {
            log_line("%s line %u ignored: not a public key or a Tox ID", ADMINS_FILE, line_no);
            continue;
        }
        if (admins_contains(address)) {
            continue;
        }
        if (g_admin_count == MAX_ADMINS) {
            log_line("%s: more than %d admins, the rest are ignored", ADMINS_FILE, MAX_ADMINS);
            break;
        }
        memcpy(g_admins[g_admin_count++], address, KEY_SIZE);
    }
    free(data);
}

/* Appended rather than rewritten, so whatever the operator wrote in the file stays as it is. */
static bool admins_append(const uint8_t *pk)
{
    char path[PATH_MAX];
    state_path(ADMINS_FILE, path);
    const int fd = open(path, O_RDWR | O_CREAT | O_APPEND | O_CLOEXEC, 0600);
    if (fd < 0) {
        return false;
    }
    bool need_newline = false;
    struct stat st;
    if (fstat(fd, &st) == 0 && st.st_size > 0) {
        char last = '\n';
        if (pread(fd, &last, 1, st.st_size - 1) == 1 && last != '\n') {
            need_newline = true;
        }
    }
    char hex[KEY_HEX_LEN + 1];
    bin_to_hex(pk, KEY_SIZE, hex);
    char when[32] = "";
    const time_t now = time(NULL);
    struct tm tm;
    if (gmtime_r(&now, &tm)) {
        strftime(when, sizeof when, "%Y-%m-%d %H:%M UTC", &tm);
    }
    char line[160];
    snprintf(line, sizeof line, "%s%s # paired %s\n", need_newline ? "\n" : "", hex, when);
    const bool ok = write_all(fd, (const uint8_t *)line, strlen(line)) && fsync(fd) == 0;
    const int e = errno;
    close(fd);
    if (!ok) {
        errno = e;
        return false;
    }
    fsync_data_dir();
    memcpy(g_admins[g_admin_count++], pk, KEY_SIZE);
    return true;
}

/* admin-code.txt is created once, on first start. A file that holds anything but a code -- an
 * operator writing "off" into it, say -- turns pairing by code off; it is never replaced. */
static void code_load(void)
{
    char path[PATH_MAX];
    state_path(CODE_FILE, path);
    uint8_t *data = NULL;
    size_t size = 0;
    const Read_Result r = read_file(path, &data, &size, 4096);
    if (r == READ_MISSING) {
        uint8_t noise[PAIRING_CODE_LEN];
        char line[PAIRING_CODE_LEN + 2];
        randombytes_buf(noise, sizeof noise);
        pairing_code_from_random(noise, g_code);
        sodium_memzero(noise, sizeof noise);
        snprintf(line, sizeof line, "%s\n", g_code);
        g_code_ok = write_file_atomic(path, (const uint8_t *)line, strlen(line));
        const int e = errno;
        sodium_memzero(line, sizeof line);
        if (g_code_ok) {
            log_line("pairing code created in %s", CODE_FILE);
        } else {
            sodium_memzero(g_code, sizeof g_code);
            log_line("cannot write %s: %s; pairing by code is off", CODE_FILE, strerror(e));
        }
        return;
    }
    if (r != READ_OK) {
        log_line("cannot read %s: %s; pairing by code is off", CODE_FILE, strerror(errno));
        return;
    }
    if (size <= 64) {
        char buf[65];
        memcpy(buf, data, size + 1);
        if (strlen(buf) == size && pairing_code_normalize(buf)) {
            memcpy(g_code, buf, sizeof g_code);
            g_code_ok = true;
        }
        sodium_memzero(buf, sizeof buf);
    }
    sodium_memzero(data, size);
    free(data);
    if (!g_code_ok) {
        log_line("%s does not hold a pairing code; pairing by code is off", CODE_FILE);
    }
}

/* ---------------------------------------------------------------------------------------------- */
/* stored settings: peer limit, topic, muted members                                               */
/* ---------------------------------------------------------------------------------------------- */

static uint16_t g_peer_limit = COMMUNITY_DEFAULT_PEER_LIMIT;
static char g_topic[TOX_GROUP_MAX_TOPIC_LENGTH + 1];
static size_t g_topic_len;
static bool g_limit_file_missing;
static bool g_topic_file_missing;
static bool g_topic_override; /* topic.txt differs from the topic saved with the group: apply it */
static bool g_topic_restore;  /* the topic lock had to be switched back on: anyone may have set it */

static bool limit_save(void)
{
    char line[16];
    snprintf(line, sizeof line, "%u\n", (unsigned)g_peer_limit);
    if (!write_text_atomic(LIMIT_FILE, line)) {
        log_line("cannot write %s: %s", LIMIT_FILE, strerror(errno));
        return false;
    }
    return true;
}

static bool topic_save(void)
{
    char buf[TOX_GROUP_MAX_TOPIC_LENGTH + 2];
    memcpy(buf, g_topic, g_topic_len);
    buf[g_topic_len] = '\n';
    buf[g_topic_len + 1] = '\0';
    if (!write_text_atomic(TOPIC_FILE, buf)) {
        log_line("cannot write %s: %s", TOPIC_FILE, strerror(errno));
        return false;
    }
    return true;
}

static void set_topic_value(const uint8_t *topic, size_t len)
{
    memcpy(g_topic, topic, len);
    g_topic[len] = '\0';
    g_topic_len = len;
}

/* limit.txt and topic.txt start out with the defaults (see settings_write_missing). A file the bot
 * cannot use is reported and left alone; the default applies until /limit or /topic writes a good
 * one. Reads only: nothing is written before the state directory has been checked. */
static void settings_load(void)
{
    char path[PATH_MAX];
    uint8_t *data = NULL;
    size_t size = 0;

    state_path(LIMIT_FILE, path);
    Read_Result r = read_file(path, &data, &size, 64);
    if (r == READ_OK) {
        size_t start = 0;
        size_t end = size;
        while (start < end && is_ascii_space(data[start])) {
            start++;
        }
        while (end > start && is_ascii_space(data[end - 1])) {
            end--;
        }
        data[end] = '\0';
        if (!parse_peer_limit((const char *)data + start, &g_peer_limit)) {
            log_line("%s does not hold a number from %d to %d; using %d", LIMIT_FILE, COMMUNITY_MIN_PEER_LIMIT,
                     COMMUNITY_MAX_PEER_LIMIT, COMMUNITY_DEFAULT_PEER_LIMIT);
            g_peer_limit = COMMUNITY_DEFAULT_PEER_LIMIT;
        }
    } else if (r == READ_MISSING) {
        g_limit_file_missing = true;
    } else {
        log_line("cannot read %s: %s; using %d", LIMIT_FILE, strerror(errno), COMMUNITY_DEFAULT_PEER_LIMIT);
    }
    free(data);
    data = NULL;

    set_topic_value((const uint8_t *)COMMUNITY_DEFAULT_TOPIC, strlen(COMMUNITY_DEFAULT_TOPIC));
    state_path(TOPIC_FILE, path);
    r = read_file(path, &data, &size, 64u * 1024u);
    if (r == READ_OK) {
        /* The whole file is the topic, less the line ending an editor leaves at the end. */
        while (size > 0 && (data[size - 1] == '\n' || data[size - 1] == '\r')) {
            size--;
        }
        if (size == 0 || size > TOX_GROUP_MAX_TOPIC_LENGTH || memchr(data, 0, size) != NULL) {
            log_line("%s must hold 1 to %d bytes of text; using the default topic", TOPIC_FILE,
                     TOX_GROUP_MAX_TOPIC_LENGTH);
        } else {
            set_topic_value(data, size);
        }
    } else if (r == READ_MISSING) {
        g_topic_file_missing = true;
    } else {
        log_line("cannot read %s: %s; using the default topic", TOPIC_FILE, strerror(errno));
    }
    free(data);
}

static void settings_write_missing(void)
{
    if (g_limit_file_missing) {
        limit_save();
    }
    if (g_topic_file_missing) {
        topic_save();
    }
}

/* Muted members, oldest first. toxcore keeps mutes ("sanctions") in memory only and holds at most
 * TOXCORE_MAX_MUTED_ONLINE of them, so this list is what makes a mute outlast a restart of the bot
 * or a kick, both of which drop it inside toxcore. */
static uint8_t g_muted[MAX_MUTED][KEY_SIZE];
static size_t g_muted_count;

static void muted_save(void)
{
    char *buf = malloc(g_muted_count * (KEY_HEX_LEN + 1) + 1);
    if (!buf) {
        log_line("cannot write %s: out of memory", MUTED_FILE);
        return;
    }
    size_t off = 0;
    for (size_t i = 0; i < g_muted_count; i++) {
        bin_to_hex(g_muted[i], KEY_SIZE, buf + off);
        off += KEY_HEX_LEN;
        buf[off++] = '\n';
    }
    buf[off] = '\0';
    char path[PATH_MAX];
    state_path(MUTED_FILE, path);
    if (!write_file_atomic(path, (const uint8_t *)buf, off)) {
        log_line("cannot write %s: %s", MUTED_FILE, strerror(errno));
    }
    free(buf);
}

static bool muted_contains(const uint8_t *key)
{
    for (size_t i = 0; i < g_muted_count; i++) {
        if (memcmp(g_muted[i], key, KEY_SIZE) == 0) {
            return true;
        }
    }
    return false;
}

static void muted_add(const uint8_t *key)
{
    if (muted_contains(key)) {
        return;
    }
    if (g_muted_count == MAX_MUTED) {
        memmove(g_muted[0], g_muted[1], (MAX_MUTED - 1) * KEY_SIZE);
        g_muted_count--;
        log_line("%s is full (%d): the oldest entry is dropped", MUTED_FILE, MAX_MUTED);
    }
    memcpy(g_muted[g_muted_count++], key, KEY_SIZE);
    muted_save();
}

static bool muted_remove(const uint8_t *key)
{
    for (size_t i = 0; i < g_muted_count; i++) {
        if (memcmp(g_muted[i], key, KEY_SIZE) == 0) {
            memmove(g_muted[i], g_muted[i + 1], (g_muted_count - i - 1) * KEY_SIZE);
            g_muted_count--;
            muted_save();
            return true;
        }
    }
    return false;
}

static void muted_load(void)
{
    char path[PATH_MAX];
    state_path(MUTED_FILE, path);
    uint8_t *data = NULL;
    size_t size = 0;
    const Read_Result r = read_file(path, &data, &size, 1024u * 1024u);
    if (r == READ_MISSING) {
        return;
    }
    if (r != READ_OK) {
        log_line("cannot read %s: %s; mutes are not re-applied", MUTED_FILE, strerror(errno));
        return;
    }
    unsigned ignored = 0;
    char *cursor = (char *)data;
    for (char *line = next_line(&cursor); line; line = next_line(&cursor)) {
        size_t n = strlen(line);
        while (n > 0 && is_ascii_space((unsigned char)line[n - 1])) {
            line[--n] = '\0';
        }
        if (n == 0) {
            continue;
        }
        uint8_t key[KEY_SIZE];
        if (n != KEY_HEX_LEN || !hex_to_bin(line, key, KEY_SIZE)) {
            ignored++;
            continue;
        }
        if (muted_contains(key)) {
            continue;
        }
        if (g_muted_count == MAX_MUTED) {
            memmove(g_muted[0], g_muted[1], (MAX_MUTED - 1) * KEY_SIZE);
            g_muted_count--;
        }
        memcpy(g_muted[g_muted_count++], key, KEY_SIZE);
    }
    free(data);
    if (ignored) {
        log_line("%s: %u line(s) ignored, not a key", MUTED_FILE, ignored);
    }
}

/* A moderator's unmute shows up as a role change to user. So does the first half of a moderator's
 * kick of a muted member (toxcore lifts the mute before kicking), and a kick must not end a mute.
 * The unmute is therefore applied only when no kick for the same member follows shortly. */
typedef struct {
    bool used;
    uint8_t key[KEY_SIZE];
    uint64_t due_ms;
} Pending_Unmute;

static Pending_Unmute g_pending_unmutes[PENDING_UNMUTES];

static bool pending_unmute_contains(const uint8_t *key)
{
    for (size_t i = 0; i < PENDING_UNMUTES; i++) {
        if (g_pending_unmutes[i].used && memcmp(g_pending_unmutes[i].key, key, KEY_SIZE) == 0) {
            return true;
        }
    }
    return false;
}

static void pending_unmute_cancel(const uint8_t *key)
{
    for (size_t i = 0; i < PENDING_UNMUTES; i++) {
        if (g_pending_unmutes[i].used && memcmp(g_pending_unmutes[i].key, key, KEY_SIZE) == 0) {
            g_pending_unmutes[i].used = false;
        }
    }
}

static void apply_pending_unmute(Pending_Unmute *p)
{
    char tag[KEY_TAG_LEN + 1];
    key_tag(p->key, tag);
    if (muted_remove(p->key)) {
        log_line("%s unmuted by a moderator", tag);
    }
    p->used = false;
}

static void pending_unmute_add(const uint8_t *key, uint64_t now)
{
    if (!muted_contains(key) || pending_unmute_contains(key)) {
        return;
    }
    Pending_Unmute *slot = NULL;
    for (size_t i = 0; i < PENDING_UNMUTES && !slot; i++) {
        if (!g_pending_unmutes[i].used) {
            slot = &g_pending_unmutes[i];
        }
    }
    if (!slot) {
        slot = &g_pending_unmutes[0];
        for (size_t i = 1; i < PENDING_UNMUTES; i++) {
            if (g_pending_unmutes[i].due_ms < slot->due_ms) {
                slot = &g_pending_unmutes[i];
            }
        }
        apply_pending_unmute(slot);
    }
    slot->used = true;
    memcpy(slot->key, key, KEY_SIZE);
    slot->due_ms = now + UNMUTE_GRACE_MS;
}

static void run_pending_unmutes(uint64_t now)
{
    for (size_t i = 0; i < PENDING_UNMUTES; i++) {
        if (g_pending_unmutes[i].used && now >= g_pending_unmutes[i].due_ms) {
            apply_pending_unmute(&g_pending_unmutes[i]);
        }
    }
}

/* ---------------------------------------------------------------------------------------------- */
/* the group                                                                                       */
/* ---------------------------------------------------------------------------------------------- */

static uint64_t g_group_connected_since_ms; /* 0 while not connected */
static int32_t g_group_state = 2;           /* last tox_group_is_connected result; 2 = not polled yet */
static uint64_t g_next_reconnect_ms;
static uint64_t g_next_settings_ms;
static bool g_settings_hold;
static unsigned g_settings_failed; /* SETTING_* bits already logged as failing */
static uint64_t g_next_mute_check_ms;
static uint64_t g_mute_fail_logged_ms;
static unsigned g_joins, g_exits, g_requests_dropped, g_drops_logged;

enum {
    SETTING_PRIVACY = 1u << 0,
    SETTING_LOCK = 1u << 1,
    SETTING_LIMIT = 1u << 2,
    SETTING_TOPIC = 1u << 3,
    SETTING_NICK = 1u << 4,
};

static void write_group_file(void)
{
    char line[KEY_HEX_LEN + 2];
    bin_to_hex(g_chat_id, KEY_SIZE, line);
    strcat(line, "\n");
    if (!write_text_atomic(GROUP_FILE, line)) {
        log_line("cannot write %s: %s", GROUP_FILE, strerror(errno));
        exit(1);
    }
}

static Read_Result read_group_file(uint8_t chat_id[KEY_SIZE])
{
    char path[PATH_MAX];
    state_path(GROUP_FILE, path);
    uint8_t *data = NULL;
    size_t size = 0;
    const Read_Result r = read_file(path, &data, &size, 4096);
    if (r != READ_OK) {
        return r;
    }
    size_t start = 0;
    size_t end = size;
    while (start < end && is_ascii_space(data[start])) {
        start++;
    }
    while (end > start && is_ascii_space(data[end - 1])) {
        end--;
    }
    const bool ok = end - start == KEY_HEX_LEN && hex_to_bin((const char *)data + start, chat_id, KEY_SIZE);
    free(data);
    return ok ? READ_OK : READ_BAD;
}

static bool group_topic_get(uint8_t *out, size_t *len)
{
    Tox_Err_Group_State_Queries err;
    const size_t n = tox_group_get_topic_size(g_tox, g_group, &err);
    if (err != TOX_ERR_GROUP_STATE_QUERIES_OK || n > TOX_GROUP_MAX_TOPIC_LENGTH) {
        return false;
    }
    if (n > 0 && !tox_group_get_topic(g_tox, g_group, out, &err)) {
        return false;
    }
    *len = n;
    return true;
}

static bool topic_equals(const uint8_t *topic, size_t len)
{
    return len == g_topic_len && memcmp(topic, g_topic, len) == 0;
}

/* Find the community group in the savedata, or create it on a first start. Returns 0, or the exit
 * status to stop with. Every case where the files disagree stops the bot instead of guessing:
 * a second group, created by mistake, would get a chat id no client knows. */
static int setup_group(const uint8_t *wanted, bool identity_restored)
{
    const uint32_t count = tox_group_get_number_groups(g_tox);
    uint32_t *numbers = calloc(count ? count : 1, sizeof *numbers);
    if (!numbers) {
        log_line("out of memory");
        return 1;
    }
    size_t found = 0;
    for (uint32_t gn = 0; found < count && gn < count + 256; gn++) {
        uint8_t chat_id[KEY_SIZE];
        if (tox_group_get_chat_id(g_tox, gn, chat_id, NULL)) {
            numbers[found++] = gn;
        }
    }

    if (wanted) {
        for (size_t i = 0; i < found && g_group == UINT32_MAX; i++) {
            uint8_t chat_id[KEY_SIZE];
            if (tox_group_get_chat_id(g_tox, numbers[i], chat_id, NULL) && memcmp(chat_id, wanted, KEY_SIZE) == 0) {
                g_group = numbers[i];
            }
        }
        if (g_group == UINT32_MAX) {
            log_line("the group in %s is not in %s: restore both files from the same backup", GROUP_FILE, SAVE_FILE);
            free(numbers);
            return EXIT_STATE;
        }
        if (tox_group_self_get_role(g_tox, g_group, NULL) != TOX_GROUP_ROLE_FOUNDER) {
            log_line("this identity is not the founder of the group in %s", GROUP_FILE);
            free(numbers);
            return EXIT_STATE;
        }
    } else {
        size_t founder_groups = 0;
        for (size_t i = 0; i < found; i++) {
            if (tox_group_self_get_role(g_tox, numbers[i], NULL) == TOX_GROUP_ROLE_FOUNDER) {
                founder_groups++;
                g_group = numbers[i];
            }
        }
        if (founder_groups > 1) {
            log_line("%s is missing and %s holds the founder key of %zu groups: write the right chat id to %s",
                     GROUP_FILE, SAVE_FILE, founder_groups, GROUP_FILE);
            free(numbers);
            return EXIT_STATE;
        }
        if (founder_groups == 0) {
            g_group = UINT32_MAX;
        }
    }

    /* Nothing else belongs in this identity. */
    for (size_t i = 0; i < found; i++) {
        if (numbers[i] != g_group) {
            tox_group_leave(g_tox, numbers[i], NULL, 0, NULL);
            g_dirty = true;
            log_line("left a group that is not the community group");
        }
    }
    free(numbers);

    char hex[KEY_HEX_LEN + 1];
    if (g_group != UINT32_MAX) {
        tox_group_get_chat_id(g_tox, g_group, g_chat_id, NULL);
        bin_to_hex(g_chat_id, KEY_SIZE, hex);
        if (g_have_expected_group && memcmp(g_chat_id, g_expected_group, KEY_SIZE) != 0) {
            log_line("this identity founds chat id %s, not the one --expect-group names", hex);
            return EXIT_STATE;
        }
        if (!wanted) {
            write_group_file();
            log_line("%s was missing: recovered from %s", GROUP_FILE, SAVE_FILE);
        }
        uint8_t topic[TOX_GROUP_MAX_TOPIC_LENGTH];
        size_t topic_len = 0;
        g_topic_override = group_topic_get(topic, &topic_len) && !topic_equals(topic, topic_len);
        log_line("community group restored, chat id %s", hex);
        return 0;
    }

    if (g_have_expected_group) {
        log_line("%s holds no community group, and --expect-group forbids creating one: restore the state "
                 "directory from a backup",
                 identity_restored ? SAVE_FILE : "the state directory");
        return EXIT_STATE;
    }

    /* Public, so that the group is announced on the DHT and anyone holding the chat id can join;
     * the clients have it compiled in. */
    Tox_Err_Group_New err;
    g_group = tox_group_new(g_tox, TOX_GROUP_PRIVACY_STATE_PUBLIC, (const uint8_t *)COMMUNITY_GROUP_NAME,
                            strlen(COMMUNITY_GROUP_NAME), (const uint8_t *)COMMUNITY_SELF_NICK,
                            strlen(COMMUNITY_SELF_NICK), &err);
    if (err != TOX_ERR_GROUP_NEW_OK) {
        log_line("creating the community group failed (%d)", (int)err);
        return 1;
    }
    tox_group_get_chat_id(g_tox, g_group, g_chat_id, NULL);
    /* The founder key goes to disk before group.txt: group.txt without the key behind it would be
     * a chat id nobody can ever found again. */
    if (!save_tox()) {
        return 1;
    }
    write_group_file();
    bin_to_hex(g_chat_id, KEY_SIZE, hex);
    log_line("community group created, chat id %s", hex);
    printf("%s\n", hex);
    fflush(stdout);
    return 0;
}

static void setting_failed(unsigned bit, const char *what, int err)
{
    if (!(g_settings_failed & bit)) {
        log_line("could not set the %s (error %d); retrying every %u s", what, err, SETTINGS_RETRY_MS / 1000);
        g_settings_failed |= bit;
    }
}

/* Bring the group to the founder settings wherever they drifted. True when all of them hold.
 * Founder changes need no members online: toxcore applies them locally and broadcasts them to
 * whoever is there, and members who join later receive them with the group state. */
static bool apply_settings(void)
{
    bool ok = true;
    bool changed = false;
    Tox_Err_Group_State_Queries qerr;

    if (tox_group_get_privacy_state(g_tox, g_group, &qerr) != TOX_GROUP_PRIVACY_STATE_PUBLIC ||
        qerr != TOX_ERR_GROUP_STATE_QUERIES_OK) {
        Tox_Err_Group_Founder_Set_Privacy_State err;
        if (tox_group_founder_set_privacy_state(g_tox, g_group, TOX_GROUP_PRIVACY_STATE_PUBLIC, &err)) {
            log_line("group switched to public");
            g_settings_failed &= ~SETTING_PRIVACY;
            changed = true;
        } else {
            setting_failed(SETTING_PRIVACY, "privacy state", (int)err);
            ok = false;
        }
    }

    if (tox_group_get_topic_lock(g_tox, g_group, &qerr) != TOX_GROUP_TOPIC_LOCK_ENABLED ||
        qerr != TOX_ERR_GROUP_STATE_QUERIES_OK) {
        Tox_Err_Group_Founder_Set_Topic_Lock err;
        if (tox_group_founder_set_topic_lock(g_tox, g_group, TOX_GROUP_TOPIC_LOCK_ENABLED, &err)) {
            log_line("topic lock switched on");
            g_settings_failed &= ~SETTING_LOCK;
            g_topic_restore = true;
            changed = true;
        } else {
            setting_failed(SETTING_LOCK, "topic lock", (int)err);
            ok = false;
        }
    }

    const uint16_t limit = tox_group_get_peer_limit(g_tox, g_group, &qerr);
    if (qerr != TOX_ERR_GROUP_STATE_QUERIES_OK || limit != g_peer_limit) {
        Tox_Err_Group_Founder_Set_Peer_Limit err;
        if (tox_group_founder_set_peer_limit(g_tox, g_group, g_peer_limit, &err)) {
            log_line("peer limit set to %u (was %u)", (unsigned)g_peer_limit, (unsigned)limit);
            g_settings_failed &= ~SETTING_LIMIT;
            changed = true;
        } else {
            setting_failed(SETTING_LIMIT, "peer limit", (int)err);
            ok = false;
        }
    }

    uint8_t topic[TOX_GROUP_MAX_TOPIC_LENGTH];
    size_t topic_len = 0;
    if (!group_topic_get(topic, &topic_len)) {
        setting_failed(SETTING_TOPIC, "topic", -1);
        ok = false;
    } else if (topic_len == 0 || ((g_topic_override || g_topic_restore) && !topic_equals(topic, topic_len))) {
        Tox_Err_Group_Topic_Set err;
        if (tox_group_set_topic(g_tox, g_group, (const uint8_t *)g_topic, g_topic_len, &err)) {
            log_line("topic set from %s (%zu bytes)", TOPIC_FILE, g_topic_len);
            g_settings_failed &= ~SETTING_TOPIC;
            g_topic_override = false;
            g_topic_restore = false;
            changed = true;
        } else {
            setting_failed(SETTING_TOPIC, "topic", (int)err);
            ok = false;
        }
    } else {
        g_topic_override = false;
        g_topic_restore = false;
    }

    Tox_Err_Group_Self_Query serr;
    const size_t nick_len = tox_group_self_get_name_size(g_tox, g_group, &serr);
    uint8_t nick[TOX_MAX_NAME_LENGTH];
    if (serr != TOX_ERR_GROUP_SELF_QUERY_OK || nick_len > sizeof nick ||
        !tox_group_self_get_name(g_tox, g_group, nick, &serr) || nick_len != strlen(COMMUNITY_SELF_NICK) ||
        memcmp(nick, COMMUNITY_SELF_NICK, nick_len) != 0) {
        Tox_Err_Group_Self_Name_Set err;
        if (tox_group_self_set_name(g_tox, g_group, (const uint8_t *)COMMUNITY_SELF_NICK,
                                    strlen(COMMUNITY_SELF_NICK), &err)) {
            log_line("own nick in the group restored"); /* no nick in the logs, not even its own */
            g_settings_failed &= ~SETTING_NICK;
            changed = true;
        } else {
            setting_failed(SETTING_NICK, "nick in the group", (int)err);
            ok = false;
        }
    }

    if (changed) {
        save_tox();
    }
    return ok;
}

static void reconcile_settings(uint64_t now)
{
    const bool ok = apply_settings();
    if (ok && !g_settings_hold) {
        log_line("settings hold: public, topic lock on, peer limit %u, topic %zu bytes", (unsigned)g_peer_limit,
                 g_topic_len);
    }
    g_settings_hold = ok;
    g_next_settings_ms = now + (ok ? SETTINGS_RECHECK_MS : SETTINGS_RETRY_MS);
}

static void poll_group(uint64_t now)
{
    Tox_Err_Group_Is_Connected err;
    const int32_t state = tox_group_is_connected(g_tox, g_group, &err);
    if (err != TOX_ERR_GROUP_IS_CONNECTED_OK) {
        return;
    }
    if (state != g_group_state) {
        if (state == 1) {
            log_line("group connected");
        } else if (state == 0) {
            log_line("group connecting");
        } else {
            log_line("group disconnected; reconnecting");
        }
        g_group_state = state;
    }
    if (state == 1) {
        if (g_group_connected_since_ms == 0) {
            g_group_connected_since_ms = now;
        }
        return;
    }
    g_group_connected_since_ms = 0;
    if (state < 0 && now >= g_next_reconnect_ms) {
        Tox_Err_Group_Reconnect rerr;
        if (!tox_group_reconnect(g_tox, g_group, &rerr)) {
            log_line("group reconnect failed (%d)", (int)rerr);
        }
        g_next_reconnect_ms = now + RECONNECT_RETRY_MS;
    }
}

/* Members online, as the bot sees them, including itself when include_self is set. NULL with
 * *count 0 when there is nobody (or no memory). */
static Member *collect_members(size_t *count, bool include_self)
{
    *count = 0;
    Tox_Err_Group_Peer_Query err;
    const uint32_t n = tox_group_peer_count(g_tox, g_group, &err);
    if (err != TOX_ERR_GROUP_PEER_QUERY_OK) {
        return NULL;
    }
    uint32_t *ids = calloc(n ? n : 1, sizeof *ids);
    Member *members = calloc((size_t)n + 1, sizeof *members);
    if (!ids || !members) {
        free(ids);
        free(members);
        return NULL;
    }
    if (n > 0) {
        tox_group_get_peerlist(g_tox, g_group, ids, &err);
        if (err != TOX_ERR_GROUP_PEER_QUERY_OK) {
            free(ids);
            free(members);
            return NULL;
        }
    }
    Tox_Err_Group_Self_Query serr;
    const uint32_t self_id = tox_group_self_get_peer_id(g_tox, g_group, &serr);
    const bool self_known = serr == TOX_ERR_GROUP_SELF_QUERY_OK;
    size_t m = 0;
    for (uint32_t i = 0; i < n; i++) {
        if (self_known && ids[i] == self_id) {
            continue; /* added last, below, when asked for */
        }
        Member *x = &members[m];
        Tox_Err_Group_Peer_Query perr;
        if (!tox_group_peer_get_public_key(g_tox, g_group, ids[i], x->key, &perr)) {
            continue;
        }
        x->role = tox_group_peer_get_role(g_tox, g_group, ids[i], &perr);
        if (perr != TOX_ERR_GROUP_PEER_QUERY_OK) {
            continue;
        }
        x->peer_id = ids[i];
        uint8_t nick[TOX_MAX_NAME_LENGTH];
        const size_t nick_len = tox_group_peer_get_name_size(g_tox, g_group, ids[i], &perr);
        if (perr == TOX_ERR_GROUP_PEER_QUERY_OK && nick_len <= sizeof nick &&
            tox_group_peer_get_name(g_tox, g_group, ids[i], nick, &perr)) {
            member_nick_clean(nick, nick_len, x->nick);
        } else {
            member_nick_clean(NULL, 0, x->nick);
        }
        m++;
    }
    free(ids);
    if (include_self && self_known) {
        Member *x = &members[m];
        if (tox_group_self_get_public_key(g_tox, g_group, x->key, NULL)) {
            x->peer_id = self_id;
            x->role = tox_group_self_get_role(g_tox, g_group, NULL);
            x->is_self = true;
            snprintf(x->nick, sizeof x->nick, "%s", COMMUNITY_SELF_NICK);
            m++;
        }
    }
    if (m == 0) {
        free(members);
        return NULL;
    }
    *count = m;
    return members;
}

static size_t members_online(void)
{
    size_t count = 0;
    free(collect_members(&count, false));
    return count;
}

/* Re-mute members on muted.txt whom toxcore sees as plain users: after a restart of the bot (toxcore
 * does not save mutes), or after a muted member was kicked and came back. Only once the group has
 * been connected for a while: a fresh founder learns the current mute list from the members, and
 * toxcore rejects a mute list older than the one the members hold. */
static void enforce_mutes(uint64_t now)
{
    g_next_mute_check_ms = now + MUTE_CHECK_MS;
    if (g_muted_count == 0 || g_group_connected_since_ms == 0 || now - g_group_connected_since_ms < MUTE_SETTLE_MS) {
        return;
    }
    size_t count = 0;
    Member *members = collect_members(&count, false);
    unsigned failed = 0;
    int last_err = 0;
    for (size_t i = 0; i < count; i++) {
        if (members[i].role != TOX_GROUP_ROLE_USER || !muted_contains(members[i].key) ||
            pending_unmute_contains(members[i].key)) {
            continue;
        }
        char tag[KEY_TAG_LEN + 1];
        key_tag(members[i].key, tag);
        Tox_Err_Group_Mod_Set_Role err;
        if (tox_group_mod_set_role(g_tox, g_group, members[i].peer_id, TOX_GROUP_ROLE_OBSERVER, &err)) {
            log_line("re-muted %s", tag);
        } else {
            failed++;
            last_err = (int)err;
        }
    }
    free(members);
    if (failed && (g_mute_fail_logged_ms == 0 || now - g_mute_fail_logged_ms >= MUTE_FAIL_LOG_MS)) {
        log_line("could not re-mute %u member(s) (error %d); retrying every %u s", failed, last_err,
                 MUTE_CHECK_MS / 1000);
        g_mute_fail_logged_ms = now;
    }
}

/* ---------------------------------------------------------------------------------------------- */
/* the admin channel                                                                               */
/* ---------------------------------------------------------------------------------------------- */

typedef struct {
    bool online;
    uint8_t seen[SEEN_IDS][MSGV3_ID_LEN];
    unsigned seen_pos;
    unsigned seen_count;
} Friend_State;

static Friend_State *g_fs;
static size_t g_fs_len;
static uint32_t g_last_reply_ts;

/* NULL for a friend number too large to track; such a friend still gets answers, without the
 * resend check. */
static Friend_State *fstate(uint32_t fn)
{
    if (fn >= MAX_FRIEND_SLOTS) {
        return NULL;
    }
    if (fn >= g_fs_len) {
        size_t len = g_fs_len ? g_fs_len : 16;
        while (len <= fn) {
            len *= 2;
        }
        Friend_State *grown = realloc(g_fs, len * sizeof *grown);
        if (!grown) {
            return NULL;
        }
        memset(grown + g_fs_len, 0, (len - g_fs_len) * sizeof *grown);
        g_fs = grown;
        g_fs_len = len;
    }
    return &g_fs[fn];
}

static void fstate_reset(uint32_t fn)
{
    Friend_State *st = fstate(fn);
    if (st) {
        memset(st, 0, sizeof *st);
    }
}

static bool seen_before(uint32_t fn, const uint8_t *id)
{
    Friend_State *st = fstate(fn);
    if (!st) {
        return false;
    }
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

/* msgV3, as the clients send it, so the reply gets an id and the client does not mark the bot as a
 * legacy peer. Timestamps never repeat, so the parts of a long reply keep their order. */
static bool send_v3(uint32_t fn, const char *text, size_t len)
{
    uint8_t buf[TOX_MAX_MESSAGE_LENGTH];
    uint8_t id[MSGV3_ID_LEN];
    tox_messagev3_get_new_message_id(id);
    uint32_t ts = unix_now();
    if (ts <= g_last_reply_ts) {
        ts = g_last_reply_ts + 1;
    }
    g_last_reply_ts = ts;
    const size_t frame_len = msgv3_frame(buf, sizeof buf, text, len, id, ts);
    if (frame_len == 0) {
        return false;
    }
    Tox_Err_Friend_Send_Message err;
    tox_friend_send_message(g_tox, fn, TOX_MESSAGE_TYPE_NORMAL, buf, frame_len, &err);
    return err == TOX_ERR_FRIEND_SEND_MESSAGE_OK;
}

/* A reply longer than one message goes out in parts, cut at line ends. */
static void send_reply(uint32_t fn, const char *text)
{
    const char *p = text;
    size_t len = strlen(text);
    while (len > 0) {
        const size_t n = next_chunk(p, len, MSGV3_MAX_TEXT);
        size_t send_len = n;
        while (send_len > 0 && p[send_len - 1] == '\n') {
            send_len--;
        }
        if (send_len > 0 && !send_v3(fn, p, send_len)) {
            return;
        }
        p += n;
        len -= n;
    }
}

static void send_replyf(uint32_t fn, const char *fmt, ...) __attribute__((format(printf, 2, 3)));
static void send_replyf(uint32_t fn, const char *fmt, ...)
{
    char buf[1024];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof buf, fmt, ap);
    va_end(ap);
    send_reply(fn, buf);
}

static void send_ack(uint32_t fn, const uint8_t *id, uint32_t ts)
{
    uint8_t buf[1 + MSGV3_SUFFIX_LEN];
    const size_t len = msgv3_ack_frame(buf, id, ts);
    tox_friend_send_message(g_tox, fn, TOX_MESSAGE_TYPE_HIGH_LEVEL_ACK, buf, len, NULL);
}

static const char HELP_TEXT[] =
    "Khandaq Community admin commands:\n"
    "/stats  members online, peer limit, uptime\n"
    "/peers  who is online: key prefix, role, nick\n"
    "/mod <prefix>  make a moderator\n"
    "/unmod <prefix>  make a moderator a user again\n"
    "/mute <prefix>  let read but not write (observer)\n"
    "/unmute <prefix>  let write again\n"
    "/kick <prefix>  remove from the group; they can rejoin\n"
    "/topic <text>  set the group topic\n"
    "/limit <n>  peer limit, 10 to 1000\n"
    "<prefix> is the start of a key as /peers shows it; a few hex digits are enough.";

typedef enum {
    ACT_MOD,
    ACT_UNMOD,
    ACT_MUTE,
    ACT_UNMUTE,
    ACT_KICK,
} Member_Action;

static const char *set_role_error(Tox_Err_Group_Mod_Set_Role err)
{
    switch (err) {
        case TOX_ERR_GROUP_MOD_SET_ROLE_OK:
            return "no error";
        case TOX_ERR_GROUP_MOD_SET_ROLE_GROUP_NOT_FOUND:
            return "the group is not loaded";
        case TOX_ERR_GROUP_MOD_SET_ROLE_PEER_NOT_FOUND:
            return "they just left";
        case TOX_ERR_GROUP_MOD_SET_ROLE_PERMISSIONS:
            return "not allowed";
        case TOX_ERR_GROUP_MOD_SET_ROLE_ASSIGNMENT:
            return "they already have that role";
        case TOX_ERR_GROUP_MOD_SET_ROLE_FAIL_ACTION:
            return "toxcore could not apply it";
        case TOX_ERR_GROUP_MOD_SET_ROLE_SELF:
            return "that is the bot itself";
    }
    return "unknown error";
}

static const char *kick_error(Tox_Err_Group_Mod_Kick_Peer err)
{
    switch (err) {
        case TOX_ERR_GROUP_MOD_KICK_PEER_OK:
            return "no error";
        case TOX_ERR_GROUP_MOD_KICK_PEER_GROUP_NOT_FOUND:
            return "the group is not loaded";
        case TOX_ERR_GROUP_MOD_KICK_PEER_PEER_NOT_FOUND:
            return "they just left";
        case TOX_ERR_GROUP_MOD_KICK_PEER_PERMISSIONS:
            return "not allowed";
        case TOX_ERR_GROUP_MOD_KICK_PEER_FAIL_ACTION:
            return "toxcore could not apply it";
        case TOX_ERR_GROUP_MOD_KICK_PEER_FAIL_SEND:
            return "the kick could not be sent";
        case TOX_ERR_GROUP_MOD_KICK_PEER_SELF:
            return "that is the bot itself";
    }
    return "unknown error";
}

static bool set_role(const Member *target, Tox_Group_Role role, Tox_Err_Group_Mod_Set_Role *err)
{
    if (!tox_group_mod_set_role(g_tox, g_group, target->peer_id, role, err)) {
        return false;
    }
    g_dirty = true; /* the moderator list is part of the savedata */
    return true;
}

static void cmd_member(uint32_t fn, const char *admin, Member_Action action, const char *name, const char *arg)
{
    if (arg[0] == '\0') {
        send_replyf(fn, "usage: /%s <key prefix from /peers>", name);
        return;
    }
    size_t count = 0;
    Member *members = collect_members(&count, true);
    size_t first = 0;
    size_t matches = 0;
    switch (match_member_prefix(arg, members, count, &first, &matches)) {
        case PREFIX_INVALID:
            send_reply(fn, "not a key prefix: use the hex digits /peers shows");
            free(members);
            return;
        case PREFIX_NONE:
            send_replyf(fn, "no member online has a key starting with %s", arg);
            free(members);
            return;
        case PREFIX_AMBIGUOUS:
            send_replyf(fn, "%s matches %zu members; give more digits", arg, matches);
            free(members);
            return;
        case PREFIX_ONE:
            break;
    }
    const Member target = members[first];
    free(members);
    char tag[KEY_TAG_LEN + 1];
    key_tag(target.key, tag);
    if (target.is_self) {
        send_replyf(fn, "%s is the bot itself", tag);
        return;
    }

    Tox_Err_Group_Mod_Set_Role err = TOX_ERR_GROUP_MOD_SET_ROLE_OK;
    bool ok = true;
    switch (action) {
        case ACT_MOD:
            if (target.role == TOX_GROUP_ROLE_MODERATOR) {
                send_replyf(fn, "%s is already a moderator", tag);
                return;
            }
            ok = set_role(&target, TOX_GROUP_ROLE_MODERATOR, &err);
            if (ok) {
                muted_remove(target.key);
                send_replyf(fn, "%s is now a moderator", tag);
            }
            break;
        case ACT_UNMOD:
            if (target.role != TOX_GROUP_ROLE_MODERATOR) {
                send_replyf(fn, "%s is not a moderator", tag);
                return;
            }
            ok = set_role(&target, TOX_GROUP_ROLE_USER, &err);
            if (ok) {
                send_replyf(fn, "%s is now a user", tag);
            }
            break;
        case ACT_MUTE:
            if (target.role == TOX_GROUP_ROLE_OBSERVER) {
                muted_add(target.key);
                send_replyf(fn, "%s is already muted", tag);
                return;
            }
            ok = set_role(&target, TOX_GROUP_ROLE_OBSERVER, &err);
            if (ok) {
                pending_unmute_cancel(target.key);
                muted_add(target.key);
                send_replyf(fn, "%s is muted: they can read but not write", tag);
            }
            break;
        case ACT_UNMUTE:
            if (target.role != TOX_GROUP_ROLE_OBSERVER) {
                if (muted_remove(target.key)) {
                    send_replyf(fn, "%s is unmuted: they can write again", tag);
                } else {
                    send_replyf(fn, "%s is not muted", tag);
                    return;
                }
                break;
            }
            ok = set_role(&target, TOX_GROUP_ROLE_USER, &err);
            if (ok) {
                muted_remove(target.key);
                send_replyf(fn, "%s is unmuted: they can write again", tag);
            }
            break;
        case ACT_KICK: {
            /* toxcore lifts a mute before it kicks; the list keeps it for when they come back. */
            const bool stay_muted = target.role == TOX_GROUP_ROLE_OBSERVER || muted_contains(target.key);
            if (stay_muted) {
                muted_add(target.key);
            }
            Tox_Err_Group_Mod_Kick_Peer kerr;
            if (!tox_group_mod_kick_peer(g_tox, g_group, target.peer_id, &kerr)) {
                send_replyf(fn, "%s: failed, %s", tag, kick_error(kerr));
                log_line("admin %s: /kick %s failed (%d)", admin, tag, (int)kerr);
                return;
            }
            g_exits++; /* toxcore reports no exit for a peer the bot kicks itself */
            g_dirty = true;
            send_replyf(fn, "%s is kicked; they can rejoin%s", tag, stay_muted ? ", and stay muted" : "");
            break;
        }
    }
    if (!ok) {
        if (action == ACT_MUTE && err == TOX_ERR_GROUP_MOD_SET_ROLE_FAIL_ACTION) {
            send_replyf(fn, "%s: failed, %s (toxcore holds at most %d mutes of members online)", tag,
                        set_role_error(err), TOXCORE_MAX_MUTED_ONLINE);
        } else {
            send_replyf(fn, "%s: failed, %s", tag, set_role_error(err));
        }
        log_line("admin %s: /%s %s failed (%d)", admin, name, tag, (int)err);
        return;
    }
    log_line("admin %s: /%s %s ok", admin, name, tag);
    save_tox();
}

static void cmd_topic(uint32_t fn, const char *admin, const char *arg)
{
    char topic[MSGV3_MAX_TEXT + 1];
    const size_t len = sanitize_topic(arg, topic, sizeof topic);
    if (len == 0) {
        send_reply(fn, "usage: /topic <text>");
        return;
    }
    if (len > TOX_GROUP_MAX_TOPIC_LENGTH) {
        send_replyf(fn, "topic too long: %zu bytes, at most %d", len, TOX_GROUP_MAX_TOPIC_LENGTH);
        return;
    }
    uint8_t current[TOX_GROUP_MAX_TOPIC_LENGTH];
    size_t current_len = 0;
    if (group_topic_get(current, &current_len) && current_len == len && memcmp(current, topic, len) == 0) {
        if (!topic_equals((const uint8_t *)topic, len)) {
            set_topic_value((const uint8_t *)topic, len);
            topic_save();
        }
        send_reply(fn, "topic unchanged");
        return;
    }
    Tox_Err_Group_Topic_Set err;
    if (!tox_group_set_topic(g_tox, g_group, (const uint8_t *)topic, len, &err)) {
        send_replyf(fn, "topic: failed (error %d)", (int)err);
        log_line("admin %s: /topic failed (%d)", admin, (int)err);
        return;
    }
    set_topic_value((const uint8_t *)topic, len);
    topic_save();
    save_tox();
    send_replyf(fn, "topic set (%zu bytes)", len);
    log_line("admin %s: /topic set (%zu bytes)", admin, len);
}

static void cmd_limit(uint32_t fn, const char *admin, const char *arg)
{
    uint16_t limit = 0;
    if (!parse_peer_limit(arg, &limit)) {
        send_replyf(fn, "the limit must be a whole number from %d to %d", COMMUNITY_MIN_PEER_LIMIT,
                    COMMUNITY_MAX_PEER_LIMIT);
        return;
    }
    Tox_Err_Group_State_Queries qerr;
    const uint16_t current = tox_group_get_peer_limit(g_tox, g_group, &qerr);
    if (qerr == TOX_ERR_GROUP_STATE_QUERIES_OK && current == limit && g_peer_limit == limit) {
        send_replyf(fn, "the peer limit is already %u", (unsigned)limit);
        return;
    }
    Tox_Err_Group_Founder_Set_Peer_Limit err;
    if (!tox_group_founder_set_peer_limit(g_tox, g_group, limit, &err)) {
        send_replyf(fn, "limit: failed (error %d)", (int)err);
        log_line("admin %s: /limit %u failed (%d)", admin, (unsigned)limit, (int)err);
        return;
    }
    g_peer_limit = limit;
    limit_save();
    save_tox();
    const size_t online = members_online();
    if (online > limit) {
        send_replyf(fn, "peer limit set to %u; %zu members are online now: nobody is removed, new joins wait",
                    (unsigned)limit, online);
    } else {
        send_replyf(fn, "peer limit set to %u", (unsigned)limit);
    }
    log_line("admin %s: /limit %u", admin, (unsigned)limit);
}

static void cmd_stats(uint32_t fn)
{
    char uptime[48];
    format_uptime(mono_ms() - g_start_ms, uptime, sizeof uptime);
    Tox_Err_Group_State_Queries qerr;
    const uint16_t limit = tox_group_get_peer_limit(g_tox, g_group, &qerr);
    send_replyf(fn, "members online: %zu\npeer limit: %u\nuptime: %s", members_online(),
                (unsigned)(qerr == TOX_ERR_GROUP_STATE_QUERIES_OK ? limit : g_peer_limit), uptime);
}

static void cmd_peers(uint32_t fn)
{
    size_t count = 0;
    Member *members = collect_members(&count, false);
    /* header + PEERS_MAX_LINES lines of tag, role and nick + the "more" line */
    char out[128 + PEERS_MAX_LINES * (KEY_TAG_LEN + 12 + MEMBER_NICK_MAX + 2)];
    format_peers(members, count, out, sizeof out);
    free(members);
    send_reply(fn, out);
}

static void run_command(uint32_t fn, char *text)
{
    uint8_t pk[KEY_SIZE];
    if (!tox_friend_get_public_key(g_tox, fn, pk, NULL) || !admins_contains(pk)) {
        return;
    }
    char admin[KEY_TAG_LEN + 1];
    key_tag(pk, admin);

    /* A swipe-reply carries the quoted message in front of what the admin typed. */
    char *body = (char *)reply_body(text);
    char cmd[16];
    char *arg = NULL;
    if (!split_command(body, cmd, sizeof cmd, &arg)) {
        send_reply(fn, "unknown command; send /help");
        log_line("admin %s: not a command", admin);
        return;
    }
    if (strcmp(cmd, "help") == 0) {
        send_reply(fn, HELP_TEXT);
        log_line("admin %s: /help", admin);
    } else if (strcmp(cmd, "stats") == 0) {
        cmd_stats(fn);
        log_line("admin %s: /stats", admin);
    } else if (strcmp(cmd, "peers") == 0) {
        cmd_peers(fn);
        log_line("admin %s: /peers", admin);
    } else if (strcmp(cmd, "mod") == 0) {
        cmd_member(fn, admin, ACT_MOD, cmd, arg);
    } else if (strcmp(cmd, "unmod") == 0) {
        cmd_member(fn, admin, ACT_UNMOD, cmd, arg);
    } else if (strcmp(cmd, "mute") == 0) {
        cmd_member(fn, admin, ACT_MUTE, cmd, arg);
    } else if (strcmp(cmd, "unmute") == 0) {
        cmd_member(fn, admin, ACT_UNMUTE, cmd, arg);
    } else if (strcmp(cmd, "kick") == 0) {
        cmd_member(fn, admin, ACT_KICK, cmd, arg);
    } else if (strcmp(cmd, "topic") == 0) {
        cmd_topic(fn, admin, arg);
    } else if (strcmp(cmd, "limit") == 0) {
        cmd_limit(fn, admin, arg);
    } else {
        send_reply(fn, "unknown command; send /help");
        log_line("admin %s: unknown command", admin);
    }
}

/* Commands wait here until tox_iterate returns, so that no moderation call runs inside toxcore. */
typedef struct {
    uint32_t fn;
    char text[MSGV3_MAX_TEXT + 1];
} Command;

static Command g_commands[COMMAND_QUEUE];
static size_t g_command_head;
static size_t g_command_count;
static bool g_queue_full_logged;

static void queue_command(uint32_t fn, const uint8_t *text, size_t len)
{
    if (g_command_count == COMMAND_QUEUE) {
        if (!g_queue_full_logged) {
            log_line("command queue full: commands dropped");
            g_queue_full_logged = true;
        }
        return;
    }
    Command *c = &g_commands[(g_command_head + g_command_count) % COMMAND_QUEUE];
    const size_t n = len < MSGV3_MAX_TEXT ? len : MSGV3_MAX_TEXT;
    c->fn = fn;
    memcpy(c->text, text, n);
    c->text[n] = '\0';
    g_command_count++;
}

static void run_commands(void)
{
    while (g_command_count > 0) {
        Command *c = &g_commands[g_command_head];
        g_command_head = (g_command_head + 1) % COMMAND_QUEUE;
        g_command_count--;
        run_command(c->fn, c->text);
        sodium_memzero(c->text, sizeof c->text);
    }
    g_queue_full_logged = false;
}

/* toxcore passes each key's contact request up once and silently drops that key's later requests
 * until it becomes a friend and is deleted again (friend_requests.c, request_received). Adding and
 * deleting a refused key at once clears that, so an admin who mistyped the code can try again
 * without a new identity. Done from the main loop, outside the callback that reported it. */
static uint8_t g_forget[FORGET_QUEUE][KEY_SIZE];
static size_t g_forget_count;

static void run_forgets(void)
{
    for (size_t i = 0; i < g_forget_count; i++) {
        const uint8_t *pk = g_forget[i];
        Tox_Err_Friend_By_Public_Key berr;
        tox_friend_by_public_key(g_tox, pk, &berr);
        if (berr == TOX_ERR_FRIEND_BY_PUBLIC_KEY_OK || admins_contains(pk)) {
            continue; /* a friend by now: never delete one */
        }
        Tox_Err_Friend_Add aerr;
        const uint32_t fn = tox_friend_add_norequest(g_tox, pk, &aerr);
        if (aerr == TOX_ERR_FRIEND_ADD_OK) {
            tox_friend_delete(g_tox, fn, NULL);
        }
    }
    g_forget_count = 0;
}

/* ---------------------------------------------------------------------------------------------- */
/* callbacks                                                                                       */
/* ---------------------------------------------------------------------------------------------- */

static void on_self_connection(Tox *tox, Tox_Connection status, void *ud)
{
    g_self_conn = status;
    log_line("DHT %s", status == TOX_CONNECTION_NONE  ? "disconnected"
                       : status == TOX_CONNECTION_UDP ? "connected (udp)"
                                                      : "connected (tcp)");
}

static void drop_request(const uint8_t *pk)
{
    g_requests_dropped++;
    if (g_drops_logged < DROP_LOG_PER_HOUR) {
        g_drops_logged++;
        log_line("contact request dropped: no valid pairing code%s",
                 g_drops_logged == DROP_LOG_PER_HOUR ? " (no more of these lines this hour)" : "");
    }
    if (g_forget_count < FORGET_QUEUE) {
        memcpy(g_forget[g_forget_count++], pk, KEY_SIZE);
    }
}

/* Accepted: a request whose message is the pairing code, or one from a key already in admins.txt.
 * Everything else is dropped without an answer, so the bot gives nothing away to strangers. */
static void on_friend_request(Tox *tox, const uint8_t *pk, const uint8_t *msg, size_t len, void *ud)
{
    char tag[KEY_TAG_LEN + 1];
    key_tag(pk, tag);
    const bool known = admins_contains(pk);
    if (!known) {
        if (!g_code_ok || !pairing_code_matches(msg, len, g_code)) {
            drop_request(pk);
            return;
        }
        if (g_admin_count == MAX_ADMINS) {
            log_line("pairing refused: %s already lists %d admins", ADMINS_FILE, MAX_ADMINS);
            drop_request(pk);
            return;
        }
        if (!admins_append(pk)) {
            log_line("pairing failed: cannot write %s: %s", ADMINS_FILE, strerror(errno));
            drop_request(pk);
            return;
        }
    }
    Tox_Err_Friend_Add err;
    const uint32_t fn = tox_friend_add_norequest(tox, pk, &err);
    if (err != TOX_ERR_FRIEND_ADD_OK) {
        log_line("contact request from admin %s not accepted (%d)", tag, (int)err);
        return;
    }
    fstate_reset(fn);
    g_dirty = true;
    if (known) {
        log_line("admin %s added the bot", tag);
    } else {
        log_line("paired admin %s", tag);
    }
}

static void on_friend_connection(Tox *tox, uint32_t fn, Tox_Connection status, void *ud)
{
    Friend_State *st = fstate(fn);
    const bool online = status != TOX_CONNECTION_NONE;
    if (st && st->online == online) {
        return; /* a UDP <-> TCP switch, not a new session */
    }
    if (st) {
        st->online = online;
    }
    uint8_t pk[KEY_SIZE];
    if (tox_friend_get_public_key(tox, fn, pk, NULL)) {
        char tag[KEY_TAG_LEN + 1];
        key_tag(pk, tag);
        log_line("admin %s %s", tag, online ? "online" : "offline");
    }
}

static void on_friend_message(Tox *tox, uint32_t fn, Tox_Message_Type type, const uint8_t *msg, size_t len,
                              void *ud)
{
    if (type == TOX_MESSAGE_TYPE_HIGH_LEVEL_ACK) {
        return; /* the admin's client confirming one of our replies */
    }
    uint8_t pk[KEY_SIZE];
    if (!tox_friend_get_public_key(tox, fn, pk, NULL) || !admins_contains(pk)) {
        return;
    }
    Msgv3 m;
    msgv3_parse(msg, len, &m);
    if (m.v3) {
        /* Without the ACK the client keeps the command as undelivered and resends it. */
        send_ack(fn, m.id, m.ts);
        if (seen_before(fn, m.id)) {
            return; /* a resend of something already answered */
        }
    }
    if (m.text_len > 0) {
        queue_command(fn, m.text, m.text_len);
    }
}

/* Avatars and files are of no use to the bot. */
static void on_file_recv(Tox *tox, uint32_t fn, uint32_t file_number, uint32_t kind, uint64_t file_size,
                         const uint8_t *filename, size_t filename_length, void *ud)
{
    tox_file_control(tox, fn, file_number, TOX_FILE_CONTROL_CANCEL, NULL);
}

static void on_group_peer_join(Tox *tox, uint32_t gn, uint32_t peer_id, void *ud)
{
    if (gn != g_group) {
        return;
    }
    g_joins++;
    const uint64_t due = mono_ms() + MUTE_AFTER_JOIN_MS;
    if (g_muted_count > 0 && due < g_next_mute_check_ms) {
        g_next_mute_check_ms = due;
    }
}

static void on_group_peer_exit(Tox *tox, uint32_t gn, uint32_t peer_id, Tox_Group_Exit_Type exit_type,
                               const uint8_t *name, size_t name_length, const uint8_t *part_message,
                               size_t part_message_length, void *ud)
{
    if (gn == g_group) {
        g_exits++;
    }
}

/* Moderators act from their own clients; the bot follows their mutes and unmutes so that muted.txt
 * does not undo them. toxcore reports list changes learnt while syncing with source and target
 * UINT32_MAX: those are not decisions anyone just made, and are ignored. */
static void on_group_moderation(Tox *tox, uint32_t gn, uint32_t source_peer_id, uint32_t target_peer_id,
                                Tox_Group_Mod_Event mod_type, void *ud)
{
    if (gn != g_group || source_peer_id == UINT32_MAX || target_peer_id == UINT32_MAX) {
        return;
    }
    Tox_Err_Group_Self_Query serr;
    const uint32_t self_id = tox_group_self_get_peer_id(tox, gn, &serr);
    if (serr == TOX_ERR_GROUP_SELF_QUERY_OK && source_peer_id == self_id) {
        return;
    }
    uint8_t key[KEY_SIZE];
    if (!tox_group_peer_get_public_key(tox, gn, target_peer_id, key, NULL)) {
        return;
    }
    char tag[KEY_TAG_LEN + 1];
    key_tag(key, tag);
    switch (mod_type) {
        case TOX_GROUP_MOD_EVENT_OBSERVER:
            pending_unmute_cancel(key);
            if (!muted_contains(key)) {
                muted_add(key);
                log_line("%s muted by a moderator", tag);
            }
            break;
        case TOX_GROUP_MOD_EVENT_USER:
        case TOX_GROUP_MOD_EVENT_MODERATOR:
            pending_unmute_add(key, mono_ms());
            break;
        case TOX_GROUP_MOD_EVENT_KICK:
            pending_unmute_cancel(key);
            break;
    }
}

/* With the topic lock on, toxcore accepts a topic only from the founder or a moderator, so a topic
 * that arrives is a moderator's decision: keep it, so that a restart does not undo it. With the
 * lock off (which apply_settings repairs) it is not trusted. */
static void on_group_topic(Tox *tox, uint32_t gn, uint32_t peer_id, const uint8_t *topic, size_t length, void *ud)
{
    if (gn != g_group || length == 0 || length > TOX_GROUP_MAX_TOPIC_LENGTH || topic_equals(topic, length)) {
        return;
    }
    Tox_Err_Group_State_Queries qerr;
    if (tox_group_get_topic_lock(tox, gn, &qerr) != TOX_GROUP_TOPIC_LOCK_ENABLED) {
        return;
    }
    set_topic_value(topic, length);
    topic_save();
    g_topic_override = false;
    g_dirty = true;
    char tag[KEY_TAG_LEN + 1] = "unknown";
    uint8_t key[KEY_SIZE];
    if (tox_group_peer_get_public_key(tox, gn, peer_id, key, NULL)) {
        key_tag(key, tag);
    }
    log_line("topic changed (received from %s, %zu bytes); kept in %s", tag, length, TOPIC_FILE);
}

/* ---------------------------------------------------------------------------------------------- */
/* main                                                                                            */
/* ---------------------------------------------------------------------------------------------- */

static void bootstrap(void)
{
    const int ok = bootstrap_from_file(g_tox, g_nodes_path);
    if (ok < 0) {
        log_line("cannot read %s: %s", g_nodes_path, strerror(errno));
    } else {
        log_line("bootstrapped from %d nodes", ok);
    }
}

/* Contacts that are not admins any more: whoever was taken out of admins.txt. */
static void prune_friends(void)
{
    const size_t n = tox_self_get_friend_list_size(g_tox);
    if (n == 0) {
        return;
    }
    uint32_t *list = calloc(n, sizeof *list);
    if (!list) {
        return;
    }
    tox_self_get_friend_list(g_tox, list);
    unsigned removed = 0;
    for (size_t i = 0; i < n; i++) {
        uint8_t pk[KEY_SIZE];
        if (tox_friend_get_public_key(g_tox, list[i], pk, NULL) && !admins_contains(pk) &&
            tox_friend_delete(g_tox, list[i], NULL)) {
            removed++;
        }
    }
    free(list);
    if (removed) {
        g_dirty = true;
        log_line("removed %u contact(s) not in %s", removed, ADMINS_FILE);
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
            "usage: %s [--data DIR] [--nodes FILE] [--port N] [--lan] [--expect-group CHAT_ID] [--print-id]\n"
            "  --data DIR             identity, group and admin state (default ./data); never recreate it\n"
            "  --nodes FILE           bootstrap list (default ./assets/nodes.txt)\n"
            "  --port N               first UDP port to try; the ten after it are tried too (default %d)\n"
            "  --lan                  enable LAN discovery (local testing only)\n"
            "  --expect-group ID      refuse to start unless DIR holds the founder key of this chat id\n"
            "  --print-id             print the bot's Tox ID and exit, changing nothing\n",
            argv0, DEFAULT_PORT);
}

int main(int argc, char **argv)
{
    bool print_id = false;
    g_start_ms = mono_ms();

    for (int i = 1; i < argc; i++) {
        if (!strcmp(argv[i], "--data") && i + 1 < argc) {
            snprintf(g_data_dir, sizeof g_data_dir, "%s", argv[++i]);
        } else if (!strcmp(argv[i], "--nodes") && i + 1 < argc) {
            snprintf(g_nodes_path, sizeof g_nodes_path, "%s", argv[++i]);
        } else if (!strcmp(argv[i], "--port") && i + 1 < argc) {
            char *end = NULL;
            const unsigned long port = strtoul(argv[++i], &end, 10);
            if (!end || *end != '\0' || port == 0 || port > 65525) {
                usage(argv[0]);
                return 2;
            }
            g_port = (uint16_t)port;
        } else if (!strcmp(argv[i], "--lan")) {
            g_lan = true;
        } else if (!strcmp(argv[i], "--expect-group") && i + 1 < argc) {
            const char *hex = argv[++i];
            if (strlen(hex) != KEY_HEX_LEN || !hex_to_bin(hex, g_expected_group, KEY_SIZE)) {
                usage(argv[0]);
                return 2;
            }
            g_have_expected_group = true;
        } else if (!strcmp(argv[i], "--print-id")) {
            print_id = true;
        } else {
            usage(argv[0]);
            return 2;
        }
    }

    if (sodium_init() < 0) {
        log_line("libsodium failed to initialise");
        return 1;
    }
    umask(077);
    signal(SIGINT, on_signal);
    signal(SIGTERM, on_signal);
    signal(SIGPIPE, SIG_IGN);

    /* Look at the state before creating anything in it. */
    struct stat st;
    if (stat(g_data_dir, &st) != 0) {
        const int e = errno;
        if (e != ENOENT || print_id) {
            log_line("cannot use %s: %s", g_data_dir, strerror(e));
            return e == ENOENT ? 1 : EXIT_STATE;
        }
        if (g_have_expected_group) {
            log_line("%s does not exist and --expect-group is set: restore it from a backup", g_data_dir);
            return EXIT_STATE;
        }
        if (mkdir(g_data_dir, 0700) != 0) {
            log_line("cannot create %s: %s", g_data_dir, strerror(errno));
            return 1;
        }
        log_line("created %s", g_data_dir);
    } else if (!S_ISDIR(st.st_mode)) {
        log_line("%s is not a directory", g_data_dir);
        return EXIT_STATE;
    }

    char save_path[PATH_MAX];
    state_path(SAVE_FILE, save_path);
    uint8_t *saved = NULL;
    size_t saved_size = 0;
    const Read_Result save_read = read_file(save_path, &saved, &saved_size, MAX_SAVEDATA);
    if (save_read == READ_ERROR) {
        log_line("cannot read %s: %s; it is left as it is", SAVE_FILE, strerror(errno));
        return EXIT_STATE;
    }
    if (save_read == READ_MISSING && print_id) {
        log_line("no identity in %s yet: start the bot once", g_data_dir);
        return 1;
    }
    uint8_t wanted[KEY_SIZE];
    const Read_Result group_read = print_id ? READ_MISSING : read_group_file(wanted);
    if (group_read == READ_ERROR || group_read == READ_BAD) {
        log_line("%s is %s; it is left as it is", GROUP_FILE,
                 group_read == READ_BAD ? "not a 64-hex chat id" : "unreadable");
        free(saved);
        return EXIT_STATE;
    }
    if (group_read == READ_OK && save_read == READ_MISSING) {
        log_line("%s names a group but %s is missing: restore %s from a backup", GROUP_FILE, SAVE_FILE, SAVE_FILE);
        return EXIT_STATE;
    }
    if (g_have_expected_group && save_read == READ_MISSING) {
        log_line("%s is missing and --expect-group is set: restore it from a backup", SAVE_FILE);
        return EXIT_STATE;
    }
    if (g_have_expected_group && group_read == READ_OK && memcmp(wanted, g_expected_group, KEY_SIZE) != 0) {
        log_line("%s names a different chat id than --expect-group", GROUP_FILE);
        free(saved);
        return EXIT_STATE;
    }

    struct Tox_Options *opts = tox_options_new(NULL);
    if (!opts) {
        free(saved);
        return 1;
    }
    tox_options_set_ipv6_enabled(opts, true);
    /* --print-id only reads the identity: no socket, so it can run beside the service. */
    tox_options_set_udp_enabled(opts, !print_id);
    tox_options_set_local_discovery_enabled(opts, g_lan && !print_id);
    tox_options_set_hole_punching_enabled(opts, true);
    tox_options_set_start_port(opts, g_port);
    tox_options_set_end_port(opts, (uint16_t)(g_port + 10));
    if (save_read == READ_OK) {
        tox_options_set_savedata_type(opts, TOX_SAVEDATA_TYPE_TOX_SAVE);
        tox_options_set_savedata_data(opts, saved, saved_size);
    }
    Tox_Err_New err;
    g_tox = tox_new(opts, &err);
    tox_options_free(opts);
    if (saved) {
        sodium_memzero(saved, saved_size);
        free(saved);
    }
    if (!g_tox) {
        if (err == TOX_ERR_NEW_LOAD_BAD_FORMAT || err == TOX_ERR_NEW_LOAD_ENCRYPTED) {
            log_line("%s cannot be loaded (%d); it is left as it is", SAVE_FILE, (int)err);
            return EXIT_STATE;
        }
        log_line("tox_new failed (%d)", (int)err);
        return 1;
    }

    uint8_t address[TOX_ADDRESS_SIZE];
    char address_hex[2 * TOX_ADDRESS_SIZE + 2];
    tox_self_get_address(g_tox, address);
    bin_to_hex(address, TOX_ADDRESS_SIZE, address_hex);
    if (print_id) {
        printf("%s\n", address_hex);
        tox_kill(g_tox);
        return 0;
    }

    tox_self_set_name(g_tox, (const uint8_t *)BOT_NAME, strlen(BOT_NAME), NULL);
    tox_self_set_status_message(g_tox, (const uint8_t *)BOT_STATUS, strlen(BOT_STATUS), NULL);
    tox_self_set_status(g_tox, TOX_USER_STATUS_NONE);

    settings_load();
    const int setup = setup_group(group_read == READ_OK ? wanted : NULL, save_read == READ_OK);
    if (setup != 0) {
        tox_kill(g_tox);
        return setup;
    }
    settings_write_missing();
    admins_load();
    code_load();
    muted_load();
    prune_friends();

    strcat(address_hex, "\n");
    if (!write_text_atomic(TOXID_FILE, address_hex)) {
        log_line("cannot write %s: %s", TOXID_FILE, strerror(errno));
    }
    char self_tag[KEY_TAG_LEN + 1];
    key_tag(address, self_tag);
    log_line("bot %s (Tox ID in %s), %zu admin(s), pairing by code %s, %zu muted, peer limit %u, topic %zu bytes",
             self_tag, TOXID_FILE, g_admin_count, g_code_ok ? "on" : "off", g_muted_count, (unsigned)g_peer_limit,
             g_topic_len);

    tox_callback_self_connection_status(g_tox, on_self_connection);
    tox_callback_friend_request(g_tox, on_friend_request);
    tox_callback_friend_connection_status(g_tox, on_friend_connection);
    tox_callback_friend_message(g_tox, on_friend_message);
    tox_callback_file_recv(g_tox, on_file_recv);
    tox_callback_group_peer_join(g_tox, on_group_peer_join);
    tox_callback_group_peer_exit(g_tox, on_group_peer_exit);
    tox_callback_group_moderation(g_tox, on_group_moderation);
    tox_callback_group_topic(g_tox, on_group_topic);

    /* Before any member can sync, so that the first one already gets the settings. */
    reconcile_settings(mono_ms());
    save_tox();
    bootstrap();

    uint64_t last_bootstrap = mono_ms();
    uint64_t next_group_poll = 0;
    uint64_t next_online_log = mono_ms() + ONLINE_LOG_MS;
    uint64_t next_traffic_log = mono_ms() + TRAFFIC_LOG_MS;
    g_next_mute_check_ms = mono_ms() + MUTE_CHECK_MS;
    while (g_running) {
        tox_iterate(g_tox, NULL);
        run_forgets();
        run_commands();
        const uint64_t now = mono_ms();

        if (g_self_conn == TOX_CONNECTION_NONE && now - last_bootstrap >= REBOOTSTRAP_MS) {
            bootstrap();
            last_bootstrap = now;
        }
        if (now >= next_group_poll) {
            poll_group(now);
            next_group_poll = now + GROUP_POLL_MS;
        }
        if (g_self_conn != TOX_CONNECTION_NONE && now >= g_next_settings_ms) {
            reconcile_settings(now);
        }
        run_pending_unmutes(now);
        if (now >= g_next_mute_check_ms) {
            enforce_mutes(now);
        }
        if (now >= next_online_log) {
            Tox_Err_Group_State_Queries qerr;
            log_line("online: %zu members, peer limit %u", members_online(),
                     (unsigned)tox_group_get_peer_limit(g_tox, g_group, &qerr));
            next_online_log = now + ONLINE_LOG_MS;
        }
        if (now >= next_traffic_log) {
            log_line("last hour: %u joins, %u exits, %u contact requests dropped", g_joins, g_exits,
                     g_requests_dropped);
            g_joins = g_exits = g_requests_dropped = g_drops_logged = 0;
            next_traffic_log = now + TRAFFIC_LOG_MS;
        }
        if (now >= g_save_retry_at_ms &&
            ((g_dirty && now - g_last_save_ms >= SAVE_DEBOUNCE_MS) || now - g_last_save_ms >= SAVE_PERIOD_MS)) {
            save_tox();
        }

        uint32_t wait = tox_iteration_interval(g_tox);
        if (wait > 50) {
            wait = 50;
        }
        sleep_ms(wait);
    }

    log_line("shutting down");
    save_tox();
    tox_kill(g_tox);
    free(g_fs);
    return 0;
}
