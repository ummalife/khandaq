/*
 * Helpers shared by the Khandaq Community founder (khandaq_community.c), its probe (probe.c), the
 * local admin test client (community_admin_test.c) and the unit test (community_unit_test.c).
 *
 * Each tool is a single translation unit linked against the pod toxcore, as in infra/demo-contact.
 * The helpers are static inline, so a tool that does not call one carries no copy of it and gets no
 * unused-function warning. Everything from "pure helpers" on takes no Tox instance and is covered by
 * the unit test.
 */

#ifndef KHANDAQ_COMMUNITY_COMMON_H
#define KHANDAQ_COMMUNITY_COMMON_H

#include <inttypes.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "toxcore/tox.h"

/* ---------------------------------------------------------------------------------------------- */
/* the group                                                                                       */
/* ---------------------------------------------------------------------------------------------- */

#define COMMUNITY_GROUP_NAME "Khandaq Community"
#define COMMUNITY_SELF_NICK  "Khandaq"
/* The group's name again: the clients show a group's topic as its title (an NGC group keeps the name
 * it was created with, so they let the topic rename it), and the owner wants the group called
 * "Khandaq Community" everywhere (2026-11-16). */
#define COMMUNITY_DEFAULT_TOPIC COMMUNITY_GROUP_NAME
#define COMMUNITY_DEFAULT_PEER_LIMIT 250
#define COMMUNITY_MIN_PEER_LIMIT     10
#define COMMUNITY_MAX_PEER_LIMIT     1000

/* A Tox public key and a member's key in a group are both 32 bytes. */
#define KEY_SIZE    32
#define KEY_HEX_LEN (2 * KEY_SIZE)
#define KEY_TAG_LEN 8

_Static_assert(KEY_SIZE == TOX_PUBLIC_KEY_SIZE, "Tox public key size");
_Static_assert(KEY_SIZE == TOX_GROUP_PEER_PUBLIC_KEY_SIZE, "group peer key size");
_Static_assert(KEY_SIZE == TOX_GROUP_CHAT_ID_SIZE, "chat id size");

/* msgV3 framing used by the Khandaq clients: text, two NUL guard bytes, 32-byte id, u32 BE time. */
#define MSGV3_GUARD_LEN  2
#define MSGV3_ID_LEN     32
#define MSGV3_TS_LEN     4
#define MSGV3_SUFFIX_LEN (MSGV3_GUARD_LEN + MSGV3_ID_LEN + MSGV3_TS_LEN)
#define MSGV3_MAX_TEXT   (TOX_MAX_MESSAGE_LENGTH - MSGV3_SUFFIX_LEN)

/* Pairing code: Crockford base32 (no I, L, O, U), so it survives being read out or retyped. */
#define PAIRING_CODE_LEN   10
#define CROCKFORD_ALPHABET "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

/* /peers: at most this many member lines, nicks cut to this many bytes. */
#define PEERS_MAX_LINES 40
#define MEMBER_NICK_MAX 32

/* ---------------------------------------------------------------------------------------------- */
/* small helpers                                                                                   */
/* ---------------------------------------------------------------------------------------------- */

static inline uint64_t mono_ms(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000u + (uint64_t)ts.tv_nsec / 1000000u;
}

static inline void put_u32_be(uint8_t *p, uint32_t v)
{
    p[0] = (uint8_t)(v >> 24);
    p[1] = (uint8_t)(v >> 16);
    p[2] = (uint8_t)(v >> 8);
    p[3] = (uint8_t)v;
}

static inline uint32_t get_u32_be(const uint8_t *p)
{
    return ((uint32_t)p[0] << 24) | ((uint32_t)p[1] << 16) | ((uint32_t)p[2] << 8) | (uint32_t)p[3];
}

static inline void bin_to_hex(const uint8_t *bin, size_t len, char *out)
{
    static const char digits[] = "0123456789ABCDEF";
    for (size_t i = 0; i < len; i++) {
        out[2 * i] = digits[bin[i] >> 4];
        out[2 * i + 1] = digits[bin[i] & 15];
    }
    out[2 * len] = '\0';
}

static inline int hex_value(char c)
{
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

/* Decode the first 2 * len characters of hex, which must all be hex digits; what follows is ignored.
 * A shorter string fails at its terminating NUL, so nothing past it is read. */
static inline bool hex_to_bin(const char *hex, uint8_t *out, size_t len)
{
    for (size_t i = 0; i < len; i++) {
        const int hi = hex_value(hex[2 * i]);
        if (hi < 0) {
            return false;
        }
        const int lo = hex_value(hex[2 * i + 1]);
        if (lo < 0) {
            return false;
        }
        out[i] = (uint8_t)(hi << 4 | lo);
    }
    return true;
}

/* First 8 hex digits of a key: enough to follow one member or admin through a log, not more. */
static inline void key_tag(const uint8_t *key, char out[KEY_TAG_LEN + 1])
{
    bin_to_hex(key, KEY_TAG_LEN / 2, out);
}

/* Cut a UTF-8 string to at most max bytes without splitting a code point. */
static inline size_t utf8_cut(const char *s, size_t len, size_t max)
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

static inline bool is_ascii_space(unsigned char c)
{
    return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == '\v';
}

/* Append printf-style to out, never past cap - 1 bytes; *off stops at cap - 1 once it is full. */
static inline void buf_append(char *out, size_t cap, size_t *off, const char *fmt, ...)
    __attribute__((format(printf, 4, 5)));
static inline void buf_append(char *out, size_t cap, size_t *off, const char *fmt, ...)
{
    if (cap == 0 || *off >= cap - 1) {
        return;
    }
    va_list ap;
    va_start(ap, fmt);
    const int n = vsnprintf(out + *off, cap - *off, fmt, ap);
    va_end(ap);
    if (n < 0) {
        out[*off] = '\0';
        return;
    }
    *off = (size_t)n >= cap - *off ? cap - 1 : *off + (size_t)n;
}

/* ---------------------------------------------------------------------------------------------- */
/* msgV3                                                                                           */
/* ---------------------------------------------------------------------------------------------- */

typedef struct {
    const uint8_t *text; /* the text part, not NUL-terminated */
    size_t text_len;     /* up to the first NUL of the text part */
    bool v3;
    uint8_t id[MSGV3_ID_LEN];
    uint32_t ts;
} Msgv3;

static inline void msgv3_parse(const uint8_t *msg, size_t len, Msgv3 *out)
{
    memset(out, 0, sizeof *out);
    out->text = msg;
    size_t text_len = len;
    if (len > MSGV3_SUFFIX_LEN && msg[len - MSGV3_SUFFIX_LEN] == 0 && msg[len - MSGV3_SUFFIX_LEN + 1] == 0) {
        out->v3 = true;
        text_len = len - MSGV3_SUFFIX_LEN;
        memcpy(out->id, msg + text_len + MSGV3_GUARD_LEN, MSGV3_ID_LEN);
        out->ts = get_u32_be(msg + text_len + MSGV3_GUARD_LEN + MSGV3_ID_LEN);
    }
    size_t n = 0;
    while (n < text_len && msg[n] != 0) {
        n++;
    }
    out->text_len = n;
}

/* text + two NUL guard bytes + id + time. Returns the frame length, or 0 when text is empty or too
 * long for one message. */
static inline size_t msgv3_frame(uint8_t *buf, size_t cap, const char *text, size_t len, const uint8_t *id,
                                 uint32_t ts)
{
    if (len == 0 || len > MSGV3_MAX_TEXT || cap < len + MSGV3_SUFFIX_LEN) {
        return 0;
    }
    memcpy(buf, text, len);
    buf[len] = 0;
    buf[len + 1] = 0;
    memcpy(buf + len + MSGV3_GUARD_LEN, id, MSGV3_ID_LEN);
    put_u32_be(buf + len + MSGV3_GUARD_LEN + MSGV3_ID_LEN, ts);
    return len + MSGV3_SUFFIX_LEN;
}

/* msgV3 high-level ACK, as the clients send it: text "_" carrying the original id and time. */
static inline size_t msgv3_ack_frame(uint8_t buf[1 + MSGV3_SUFFIX_LEN], const uint8_t *id, uint32_t ts)
{
    buf[0] = '_';
    buf[1] = 0;
    buf[2] = 0;
    memcpy(buf + 1 + MSGV3_GUARD_LEN, id, MSGV3_ID_LEN);
    put_u32_be(buf + 1 + MSGV3_GUARD_LEN + MSGV3_ID_LEN, ts);
    return 1 + MSGV3_SUFFIX_LEN;
}

/* ---------------------------------------------------------------------------------------------- */
/* bootstrap                                                                                       */
/* ---------------------------------------------------------------------------------------------- */

/* nodes.txt: "host port public_key_hex [tcp_port,tcp_port...]" per line, '#' comments.
 * Returns how many nodes tox_bootstrap accepted, or -1 when the file cannot be opened (errno set). */
static inline int bootstrap_from_file(Tox *tox, const char *path)
{
    FILE *f = fopen(path, "r");
    if (!f) {
        return -1;
    }
    char line[512];
    int ok = 0;
    while (fgets(line, sizeof line, f)) {
        char host[256];
        char key[80];
        char tcp[128] = "";
        unsigned port = 0;
        uint8_t pk[TOX_PUBLIC_KEY_SIZE];
        if (line[0] == '#' || sscanf(line, "%255s %u %79s %127s", host, &port, key, tcp) < 3 || port == 0 ||
            port > 65535 || strlen(key) != 2 * TOX_PUBLIC_KEY_SIZE || !hex_to_bin(key, pk, sizeof pk)) {
            continue;
        }
        if (tox_bootstrap(tox, host, (uint16_t)port, pk, NULL)) {
            ok++;
        }
        char *save = NULL;
        for (char *tok = strtok_r(tcp, ",", &save); tok; tok = strtok_r(NULL, ",", &save)) {
            const long tp = strtol(tok, NULL, 10);
            if (tp > 0 && tp < 65536) {
                tox_add_tcp_relay(tox, host, (uint16_t)tp, pk, NULL);
            }
        }
    }
    fclose(f);
    return ok;
}

/* ---------------------------------------------------------------------------------------------- */
/* pure helpers (covered by community_unit_test.c)                                                 */
/* ---------------------------------------------------------------------------------------------- */

static inline const char *role_name(Tox_Group_Role role)
{
    switch (role) {
        case TOX_GROUP_ROLE_FOUNDER:
            return "founder";
        case TOX_GROUP_ROLE_MODERATOR:
            return "moderator";
        case TOX_GROUP_ROLE_USER:
            return "user";
        case TOX_GROUP_ROLE_OBSERVER:
            return "observer";
    }
    return "unknown";
}

typedef struct {
    uint32_t peer_id;
    uint8_t key[KEY_SIZE]; /* the member's key in the group */
    Tox_Group_Role role;
    bool is_self;
    char nick[MEMBER_NICK_MAX + 1];
} Member;

static inline int role_rank(Tox_Group_Role role)
{
    switch (role) {
        case TOX_GROUP_ROLE_FOUNDER:
            return 0;
        case TOX_GROUP_ROLE_MODERATOR:
            return 1;
        case TOX_GROUP_ROLE_USER:
            return 2;
        case TOX_GROUP_ROLE_OBSERVER:
            return 3;
    }
    return 4;
}

/* /peers order: founder, moderators, users, observers; by key within a role. */
static inline int member_compare(const void *a, const void *b)
{
    const Member *x = a;
    const Member *y = b;
    const int rx = role_rank(x->role);
    const int ry = role_rank(y->role);
    if (rx != ry) {
        return rx < ry ? -1 : 1;
    }
    return memcmp(x->key, y->key, KEY_SIZE);
}

/* A nick made fit for one /peers line: control characters dropped, cut to MEMBER_NICK_MAX bytes
 * without splitting a UTF-8 character, "-" when nothing is left. */
static inline void member_nick_clean(const uint8_t *in, size_t len, char out[MEMBER_NICK_MAX + 1])
{
    char tmp[TOX_MAX_NAME_LENGTH + 1];
    size_t o = 0;
    for (size_t i = 0; i < len && o < TOX_MAX_NAME_LENGTH; i++) {
        if (in[i] >= 0x20 && in[i] != 0x7F) {
            tmp[o++] = (char)in[i];
        }
    }
    o = utf8_cut(tmp, o, MEMBER_NICK_MAX);
    if (o == 0) {
        out[0] = '-';
        out[1] = '\0';
        return;
    }
    memcpy(out, tmp, o);
    out[o] = '\0';
}

typedef enum {
    PREFIX_ONE,       /* exactly one member has a key that starts with the prefix */
    PREFIX_NONE,      /* nobody */
    PREFIX_AMBIGUOUS, /* more than one */
    PREFIX_INVALID,   /* not 1 to 64 hex digits */
} Prefix_Match;

static inline bool key_prefix_valid(const char *prefix)
{
    size_t n = 0;
    for (; prefix[n] != '\0'; n++) {
        if (n >= KEY_HEX_LEN || hex_value(prefix[n]) < 0) {
            return false;
        }
    }
    return n > 0;
}

/* Case-insensitive: does the hex form of key start with prefix? */
static inline bool key_has_prefix(const uint8_t *key, const char *prefix)
{
    for (size_t i = 0; prefix[i] != '\0'; i++) {
        if (i >= KEY_HEX_LEN) {
            return false;
        }
        const int digit = i % 2 == 0 ? key[i / 2] >> 4 : key[i / 2] & 15;
        if (hex_value(prefix[i]) != digit) {
            return false;
        }
    }
    return true;
}

/* *first is the first match, *matches how many there are. */
static inline Prefix_Match match_member_prefix(const char *prefix, const Member *members, size_t count, size_t *first,
                                               size_t *matches)
{
    *first = 0;
    *matches = 0;
    if (!key_prefix_valid(prefix)) {
        return PREFIX_INVALID;
    }
    for (size_t i = 0; i < count; i++) {
        if (key_has_prefix(members[i].key, prefix)) {
            if (*matches == 0) {
                *first = i;
            }
            ++*matches;
        }
    }
    return *matches == 0 ? PREFIX_NONE : *matches == 1 ? PREFIX_ONE : PREFIX_AMBIGUOUS;
}

/* Crockford's decoding: any case, O reads as 0, I and L as 1. 0 for anything outside the alphabet. */
static inline char crockford_canonical(unsigned char c)
{
    if (c >= 'a' && c <= 'z') {
        c = (unsigned char)(c - 'a' + 'A');
    }
    if (c == 'O') {
        return '0';
    }
    if (c == 'I' || c == 'L') {
        return '1';
    }
    if ((c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z' && c != 'U')) {
        return (char)c;
    }
    return 0;
}

/* PAIRING_CODE_LEN characters from as many random bytes. 256 is a multiple of 32, so keeping the low
 * five bits leaves every character equally likely. */
static inline void pairing_code_from_random(const uint8_t *bytes, char code[PAIRING_CODE_LEN + 1])
{
    static const char alphabet[] = CROCKFORD_ALPHABET;
    for (size_t i = 0; i < PAIRING_CODE_LEN; i++) {
        code[i] = alphabet[bytes[i] & 31];
    }
    code[PAIRING_CODE_LEN] = '\0';
}

/* Bring a code read from admin-code.txt to its canonical form in place. False when it is not
 * PAIRING_CODE_LEN characters of the alphabet (surrounding white space allowed). */
static inline bool pairing_code_normalize(char *code)
{
    const char *p = code;
    while (is_ascii_space((unsigned char)*p)) {
        p++;
    }
    char out[PAIRING_CODE_LEN + 1];
    size_t n = 0;
    while (*p != '\0' && !is_ascii_space((unsigned char)*p)) {
        const char c = crockford_canonical((unsigned char)*p++);
        if (c == 0 || n == PAIRING_CODE_LEN) {
            return false;
        }
        out[n++] = c;
    }
    while (is_ascii_space((unsigned char)*p)) {
        p++;
    }
    if (n != PAIRING_CODE_LEN || *p != '\0') {
        return false;
    }
    memcpy(code, out, PAIRING_CODE_LEN);
    code[PAIRING_CODE_LEN] = '\0';
    return true;
}

/* True when a contact request message is the pairing code, give or take surrounding white space and
 * trailing NULs, read with Crockford's rules. code must be canonical. Every character is compared,
 * without an early exit. */
static inline bool pairing_code_matches(const uint8_t *msg, size_t len, const char *code)
{
    if (strlen(code) != PAIRING_CODE_LEN) {
        return false;
    }
    size_t start = 0;
    size_t end = len;
    while (start < end && is_ascii_space(msg[start])) {
        start++;
    }
    while (end > start && (is_ascii_space(msg[end - 1]) || msg[end - 1] == 0)) {
        end--;
    }
    if (end - start != PAIRING_CODE_LEN) {
        return false;
    }
    unsigned diff = 0;
    for (size_t i = 0; i < PAIRING_CODE_LEN; i++) {
        diff |= (unsigned)(unsigned char)(crockford_canonical(msg[start + i]) ^ code[i]);
    }
    return diff == 0;
}

/* The /limit argument: a whole number from COMMUNITY_MIN_PEER_LIMIT to COMMUNITY_MAX_PEER_LIMIT. */
static inline bool parse_peer_limit(const char *arg, uint16_t *out)
{
    unsigned value = 0;
    size_t digits = 0;
    for (const char *p = arg; *p != '\0'; p++) {
        if (*p < '0' || *p > '9' || ++digits > 4) {
            return false;
        }
        value = value * 10 + (unsigned)(*p - '0');
    }
    if (digits == 0 || value < COMMUNITY_MIN_PEER_LIMIT || value > COMMUNITY_MAX_PEER_LIMIT) {
        return false;
    }
    *out = (uint16_t)value;
    return true;
}

/* One line for /topic: CR, LF and TAB become spaces, other control characters are dropped and
 * surrounding spaces trimmed. cap must exceed strlen(in), so that nothing is cut here and the caller
 * sees the real length. Returns the length written (NUL-terminated). */
static inline size_t sanitize_topic(const char *in, char *out, size_t cap)
{
    size_t o = 0;
    for (const unsigned char *p = (const unsigned char *)in; *p != '\0' && o + 1 < cap; p++) {
        unsigned char c = *p;
        if (c == '\r' || c == '\n' || c == '\t') {
            c = ' ';
        } else if (c < 0x20 || c == 0x7F) {
            continue;
        }
        if (c == ' ' && o == 0) {
            continue;
        }
        out[o++] = (char)c;
    }
    while (o > 0 && out[o - 1] == ' ') {
        o--;
    }
    out[o] = '\0';
    return o;
}

static inline void format_uptime(uint64_t ms, char *out, size_t cap)
{
    const uint64_t s = ms / 1000;
    snprintf(out, cap, "%" PRIu64 "d %02uh %02um", s / 86400, (unsigned)(s / 3600 % 24), (unsigned)(s / 60 % 60));
}

/* Length of the next part of a reply to send as one message: all of it when it fits in max bytes,
 * otherwise up to and including the last newline that fits, otherwise as much of the line as fits
 * without splitting a UTF-8 character. Never 0 for a non-empty text. */
static inline size_t next_chunk(const char *s, size_t len, size_t max)
{
    if (len <= max) {
        return len;
    }
    for (size_t i = max; i > 0; i--) {
        if (s[i - 1] == '\n') {
            return i;
        }
    }
    const size_t cut = utf8_cut(s, len, max);
    return cut > 0 ? cut : max;
}

/* The /peers reply for members (the bot not among them): a header, then one line per member -- key
 * tag, role, nick -- sorted by role and key, at most PEERS_MAX_LINES of them. Sorts members. */
static inline size_t format_peers(Member *members, size_t count, char *out, size_t cap)
{
    size_t off = 0;
    out[0] = '\0';
    if (count == 0) {
        buf_append(out, cap, &off, "nobody is online in the group");
        return off;
    }
    qsort(members, count, sizeof *members, member_compare);
    const size_t shown = count < PEERS_MAX_LINES ? count : PEERS_MAX_LINES;
    if (shown < count) {
        buf_append(out, cap, &off, "%zu members online, the first %zu:\n", count, shown);
    } else {
        buf_append(out, cap, &off, "%zu member%s online:\n", count, count == 1 ? "" : "s");
    }
    for (size_t i = 0; i < shown; i++) {
        char tag[KEY_TAG_LEN + 1];
        key_tag(members[i].key, tag);
        buf_append(out, cap, &off, "%s %s %s\n", tag, role_name(members[i].role),
                   members[i].nick[0] ? members[i].nick : "-");
    }
    if (shown < count) {
        buf_append(out, cap, &off, "+%zu more\n", count - shown);
    }
    if (off > 0 && out[off - 1] == '\n') {
        out[--off] = '\0';
    }
    return off;
}

/* "/Mute  1a2b " -> cmd "mute", *arg "1a2b" (trailing white space cut off in text). False when text
 * is not a command. A command word longer than cmd_cap - 1 comes back empty, which no command is. */
static inline bool split_command(char *text, char *cmd, size_t cmd_cap, char **arg)
{
    char *s = text;
    while (is_ascii_space((unsigned char)*s)) {
        s++;
    }
    size_t len = strlen(s);
    while (len > 0 && is_ascii_space((unsigned char)s[len - 1])) {
        s[--len] = '\0';
    }
    if (*s != '/') {
        return false;
    }
    s++;
    size_t n = 0;
    bool too_long = false;
    while (*s != '\0' && !is_ascii_space((unsigned char)*s)) {
        if (n + 1 < cmd_cap) {
            const char c = *s;
            cmd[n++] = c >= 'A' && c <= 'Z' ? (char)(c - 'A' + 'a') : c;
        } else {
            too_long = true;
        }
        s++;
    }
    cmd[too_long ? 0 : n] = '\0';
    while (is_ascii_space((unsigned char)*s)) {
        s++;
    }
    *arg = s;
    return true;
}

/* Khandaq sends a swipe-reply as "[KQ|<meta>]<quoted preview>[KQ/end]<answer>" (MessageReplyHelper on
 * Android, the same header on iOS); only the answer is what the person typed. Same parse as
 * infra/demo-contact: header prefix, first ']', footer after it. */
static inline const char *reply_body(const char *text)
{
    if (strncmp(text, "[KQ|", 4) != 0) {
        return text;
    }
    const char *header_end = strchr(text + 4, ']');
    const char *footer = strstr(text, "[KQ/end]");
    if (header_end == NULL || footer == NULL || footer < header_end) {
        return text;
    }
    return footer + strlen("[KQ/end]");
}

#endif /* KHANDAQ_COMMUNITY_COMMON_H */
