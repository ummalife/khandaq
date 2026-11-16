/*
 * Unit test of the pure helpers in community_common.h: everything the founder decides without a
 * Tox instance -- pairing codes, key prefixes, command parsing, the /peers listing, limits, topics,
 * msgV3 framing and reply chunking. build.sh runs it after every build.
 *
 * Exit status 0 only when every check passed.
 */

#define _GNU_SOURCE

#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "community_common.h"

static unsigned g_pass, g_fail;

static void check(bool ok, const char *what)
{
    if (ok) {
        g_pass++;
    } else {
        g_fail++;
        fprintf(stderr, "FAIL  %s\n", what);
    }
}

static void fill_key(uint8_t *key, uint8_t first, uint8_t rest)
{
    key[0] = first;
    memset(key + 1, rest, KEY_SIZE - 1);
}

static void test_hex(void)
{
    uint8_t bin[4] = {0x00, 0x9F, 0xA0, 0xFF};
    char hex[9];
    bin_to_hex(bin, sizeof bin, hex);
    check(strcmp(hex, "009FA0FF") == 0, "bin_to_hex writes upper case");

    uint8_t back[4];
    check(hex_to_bin("009fa0FF", back, sizeof back) && memcmp(back, bin, sizeof bin) == 0, "hex_to_bin, any case");
    check(!hex_to_bin("009fa0F", back, sizeof back), "hex_to_bin stops at a short string");
    check(!hex_to_bin("009fa0FG", back, sizeof back), "hex_to_bin rejects a non-hex digit");
    check(hex_to_bin("00112233trailing", back, sizeof back), "hex_to_bin ignores what follows");

    uint8_t key[KEY_SIZE];
    fill_key(key, 0xAB, 0xCD);
    char tag[KEY_TAG_LEN + 1];
    key_tag(key, tag);
    check(strcmp(tag, "ABCDCDCD") == 0, "key_tag is the first 8 hex digits");

    uint8_t be[4];
    put_u32_be(be, 0x01020304u);
    check(be[0] == 1 && be[3] == 4 && get_u32_be(be) == 0x01020304u, "u32 big endian round trip");
}

static void test_utf8(void)
{
    const char *s = "a\xC3\xA9z"; /* a, e-acute (2 bytes), z */
    check(utf8_cut(s, 4, 10) == 4, "utf8_cut keeps a short string");
    check(utf8_cut(s, 4, 3) == 3, "utf8_cut after a whole character");
    check(utf8_cut(s, 4, 2) == 1, "utf8_cut does not split a character");
    check(utf8_cut(s, 4, 0) == 0, "utf8_cut to nothing");

    char out[8];
    size_t off = 0;
    buf_append(out, sizeof out, &off, "%s", "abc");
    buf_append(out, sizeof out, &off, "%d", 12345);
    check(off == 7 && strcmp(out, "abc1234") == 0, "buf_append stops at the capacity");
    buf_append(out, sizeof out, &off, "x");
    check(off == 7 && strcmp(out, "abc1234") == 0, "buf_append on a full buffer is a no-op");
}

static void test_msgv3(void)
{
    uint8_t id[MSGV3_ID_LEN];
    for (size_t i = 0; i < sizeof id; i++) {
        id[i] = (uint8_t)(i * 7 + 1);
    }
    uint8_t buf[TOX_MAX_MESSAGE_LENGTH];
    const size_t len = msgv3_frame(buf, sizeof buf, "/stats", 6, id, 0x5F000001u);
    check(len == 6 + MSGV3_SUFFIX_LEN, "msgv3_frame length");

    Msgv3 m;
    msgv3_parse(buf, len, &m);
    check(m.v3 && m.text_len == 6 && memcmp(m.text, "/stats", 6) == 0, "msgv3_parse text");
    check(memcmp(m.id, id, sizeof id) == 0 && m.ts == 0x5F000001u, "msgv3_parse id and time");

    msgv3_parse((const uint8_t *)"plain", 5, &m);
    check(!m.v3 && m.text_len == 5, "msgv3_parse of a legacy message");

    char big[MSGV3_MAX_TEXT + 1];
    memset(big, 'x', sizeof big);
    check(msgv3_frame(buf, sizeof buf, big, MSGV3_MAX_TEXT, id, 1) == TOX_MAX_MESSAGE_LENGTH,
          "msgv3_frame fills one message exactly");
    check(msgv3_frame(buf, sizeof buf, big, MSGV3_MAX_TEXT + 1, id, 1) == 0, "msgv3_frame refuses too long a text");
    check(msgv3_frame(buf, sizeof buf, "", 0, id, 1) == 0, "msgv3_frame refuses an empty text");

    uint8_t ack[1 + MSGV3_SUFFIX_LEN];
    const size_t ack_len = msgv3_ack_frame(ack, id, 42);
    msgv3_parse(ack, ack_len, &m);
    check(ack[0] == '_' && m.v3 && m.text_len == 1 && memcmp(m.id, id, sizeof id) == 0 && m.ts == 42,
          "msgv3_ack_frame carries the id and time");
}

static void test_prefixes(void)
{
    Member members[4];
    memset(members, 0, sizeof members);
    fill_key(members[0].key, 0xA1, 0x11);
    fill_key(members[1].key, 0xA2, 0x22);
    fill_key(members[2].key, 0xB3, 0x33);
    fill_key(members[3].key, 0xC4, 0x44);
    size_t first = 99;
    size_t matches = 99;

    check(match_member_prefix("b3", members, 4, &first, &matches) == PREFIX_ONE && first == 2 && matches == 1,
          "a unique prefix, lower case");
    check(match_member_prefix("A22", members, 4, &first, &matches) == PREFIX_ONE && first == 1,
          "an odd number of digits");
    check(match_member_prefix("a", members, 4, &first, &matches) == PREFIX_AMBIGUOUS && matches == 2,
          "a shared prefix is ambiguous");
    check(match_member_prefix("d", members, 4, &first, &matches) == PREFIX_NONE && matches == 0, "no match");
    check(match_member_prefix("", members, 4, &first, &matches) == PREFIX_INVALID, "an empty prefix");
    check(match_member_prefix("xyz", members, 4, &first, &matches) == PREFIX_INVALID, "a non-hex prefix");
    check(match_member_prefix("a1 b", members, 4, &first, &matches) == PREFIX_INVALID, "a prefix with a space");
    check(match_member_prefix("a1", members, 0, &first, &matches) == PREFIX_NONE, "nobody online");

    char full[KEY_HEX_LEN + 2];
    bin_to_hex(members[3].key, KEY_SIZE, full);
    check(match_member_prefix(full, members, 4, &first, &matches) == PREFIX_ONE && first == 3, "a whole key");
    strcat(full, "4");
    check(match_member_prefix(full, members, 4, &first, &matches) == PREFIX_INVALID, "65 digits");
}

static void test_pairing_code(void)
{
    uint8_t bytes[PAIRING_CODE_LEN];
    for (size_t i = 0; i < sizeof bytes; i++) {
        bytes[i] = (uint8_t)(i * 37 + 200);
    }
    char code[PAIRING_CODE_LEN + 1];
    pairing_code_from_random(bytes, code);
    bool in_alphabet = strlen(code) == PAIRING_CODE_LEN;
    for (size_t i = 0; i < PAIRING_CODE_LEN; i++) {
        in_alphabet = in_alphabet && strchr(CROCKFORD_ALPHABET, code[i]) != NULL;
    }
    check(in_alphabet, "a generated code uses the Crockford alphabet");
    check(sizeof CROCKFORD_ALPHABET - 1 == 32 && !strpbrk(CROCKFORD_ALPHABET, "ILOU"), "the alphabet has 32 letters");

    char stored[] = "  7k9m-qrst  \n";
    check(!pairing_code_normalize(stored), "a dash is not part of a code");
    char stored2[] = "  7k9mqrstvw \n";
    check(pairing_code_normalize(stored2) && strcmp(stored2, "7K9MQRSTVW") == 0, "a stored code is normalized");
    char stored3[] = "7K9MQRSTV";
    check(!pairing_code_normalize(stored3), "nine characters are not a code");
    char stored4[] = "7K9MQRSTVWX";
    check(!pairing_code_normalize(stored4), "eleven characters are not a code");
    char stored5[] = "7K9MQRSTVU";
    check(!pairing_code_normalize(stored5), "U is not in the alphabet");
    char stored6[] = "off";
    check(!pairing_code_normalize(stored6), "\"off\" is not a code");
    char stored7[] = "o1ilqrstvw";
    check(pairing_code_normalize(stored7) && strcmp(stored7, "0111QRSTVW") == 0, "O reads as 0, I and L as 1");

    const char *canonical = "0111QRSTVW";
    check(pairing_code_matches((const uint8_t *)"0111QRSTVW", 10, canonical), "the exact code matches");
    check(pairing_code_matches((const uint8_t *)"oIlLqrstvw", 10, canonical), "case and look-alikes match");
    check(pairing_code_matches((const uint8_t *)" 0111QRSTVW\n\0\0", 14, canonical), "white space and NULs");
    check(!pairing_code_matches((const uint8_t *)"0111QRSTVX", 10, canonical), "one wrong character");
    check(!pairing_code_matches((const uint8_t *)"0111QRSTV", 9, canonical), "one character short");
    check(!pairing_code_matches((const uint8_t *)"0111QRSTVWW", 11, canonical), "one character too many");
    check(!pairing_code_matches((const uint8_t *)"0111 QRSTVW", 11, canonical), "a space inside");
    check(!pairing_code_matches((const uint8_t *)"", 0, canonical), "an empty message");
    check(!pairing_code_matches((const uint8_t *)"0111QRSTVW", 10, "short"), "a bad stored code matches nothing");
}

static void test_limit_and_topic(void)
{
    uint16_t v = 0;
    check(parse_peer_limit("250", &v) && v == 250, "limit 250");
    check(parse_peer_limit("10", &v) && v == 10, "limit 10");
    check(parse_peer_limit("1000", &v) && v == 1000, "limit 1000");
    check(parse_peer_limit("0300", &v) && v == 300, "limit with a leading zero");
    v = 7;
    check(!parse_peer_limit("9", &v) && v == 7, "limit 9 is too low and leaves the value alone");
    check(!parse_peer_limit("1001", &v), "limit 1001 is too high");
    check(!parse_peer_limit("00010", &v), "five digits");
    check(!parse_peer_limit("abc", &v), "not a number");
    check(!parse_peer_limit("", &v), "empty");
    check(!parse_peer_limit("+10", &v), "a sign");
    check(!parse_peer_limit("10 0", &v), "a space");
    check(!parse_peer_limit("-5", &v), "negative");

    char out[64];
    check(sanitize_topic("  Hello\r\nworld\t!  ", out, sizeof out) == 14 && strcmp(out, "Hello  world !") == 0,
          "line breaks and tabs become spaces, ends trimmed");
    check(sanitize_topic("a\x01\x7f" "b", out, sizeof out) == 2 && strcmp(out, "ab") == 0, "control characters dropped");
    check(sanitize_topic(" \n ", out, sizeof out) == 0, "white space only is empty");
    const char *cyrillic = "\xD0\xA2\xD0\xB5\xD0\xBC\xD0\xB0"; /* four Cyrillic letters, UTF-8 */
    check(sanitize_topic(cyrillic, out, sizeof out) == 8 && strcmp(out, cyrillic) == 0, "UTF-8 is kept");
    check(strlen(COMMUNITY_DEFAULT_TOPIC) <= TOX_GROUP_MAX_TOPIC_LENGTH && !strchr(COMMUNITY_DEFAULT_TOPIC, '\n'),
          "the default topic is one line that fits");

    char up[32];
    format_uptime(0, up, sizeof up);
    check(strcmp(up, "0d 00h 00m") == 0, "uptime zero");
    format_uptime((uint64_t)((2 * 86400 + 3 * 3600 + 4 * 60 + 59) * 1000ull), up, sizeof up);
    check(strcmp(up, "2d 03h 04m") == 0, "uptime days, hours, minutes");
}

static void test_chunks(void)
{
    check(next_chunk("short", 5, 10) == 5, "a short reply is one part");
    const char *lines = "aaaa\nbbbb\ncccc";
    check(next_chunk(lines, 14, 12) == 10, "cut after the last line end that fits");
    check(next_chunk("abcdefghij", 10, 4) == 4, "a long line is cut where it must");
    const char *wide = "\xC3\xA9\xC3\xA9\xC3\xA9"; /* three 2-byte characters */
    check(next_chunk(wide, 6, 3) == 2, "a long line is not cut inside a character");

    /* A reply sent in parts loses nothing. */
    char text[4000];
    size_t off = 0;
    for (int i = 0; i < 100; i++) {
        buf_append(text, sizeof text, &off, "line %03d of the reply\n", i);
    }
    size_t total = 0;
    size_t parts = 0;
    for (size_t left = off; left > 0;) {
        const size_t n = next_chunk(text + total, left, MSGV3_MAX_TEXT);
        check(n > 0 && n <= MSGV3_MAX_TEXT, "each part fits in one message");
        total += n;
        left -= n;
        parts++;
    }
    check(total == off && parts >= 2, "parts add up to the whole reply");
}

static void test_peers(void)
{
    char out[8192];
    check(format_peers(NULL, 0, out, sizeof out) > 0 && strcmp(out, "nobody is online in the group") == 0,
          "/peers with nobody online");

    Member one[1];
    memset(one, 0, sizeof one);
    fill_key(one[0].key, 0x12, 0x34);
    one[0].role = TOX_GROUP_ROLE_USER;
    snprintf(one[0].nick, sizeof one[0].nick, "%s", "Aisha");
    format_peers(one, 1, out, sizeof out);
    check(strcmp(out, "1 member online:\n12343434 user Aisha") == 0, "/peers with one member");

    Member three[3];
    memset(three, 0, sizeof three);
    fill_key(three[0].key, 0x30, 0x00);
    three[0].role = TOX_GROUP_ROLE_OBSERVER;
    fill_key(three[1].key, 0x20, 0x00);
    three[1].role = TOX_GROUP_ROLE_USER;
    fill_key(three[2].key, 0x10, 0x00);
    three[2].role = TOX_GROUP_ROLE_MODERATOR;
    format_peers(three, 3, out, sizeof out);
    check(strcmp(out, "3 members online:\n10000000 moderator -\n20000000 user -\n30000000 observer -") == 0,
          "/peers sorted by role, empty nicks shown as -");

    Member *many = calloc(45, sizeof *many);
    if (!many) {
        check(false, "memory");
        return;
    }
    for (int i = 0; i < 45; i++) {
        fill_key(many[i].key, (uint8_t)i, 0x55);
        many[i].role = TOX_GROUP_ROLE_USER;
        snprintf(many[i].nick, sizeof many[i].nick, "m%d", i);
    }
    format_peers(many, 45, out, sizeof out);
    unsigned lines = 0;
    for (const char *p = out; *p; p++) {
        lines += *p == '\n';
    }
    const char *header = "45 members online, the first 40:\n";
    check(strncmp(out, header, strlen(header)) == 0, "/peers header when cut");
    check(lines == 41 && strstr(out, "\n+5 more") != NULL, "/peers shows 40 members and how many more");
    free(many);

    char nick[MEMBER_NICK_MAX + 1];
    member_nick_clean((const uint8_t *)"Ab\ncd\x1b", 6, nick);
    check(strcmp(nick, "Abcd") == 0, "nick: control characters dropped");
    member_nick_clean((const uint8_t *)"\x01\x02", 2, nick);
    check(strcmp(nick, "-") == 0, "nick: nothing left is shown as -");
    uint8_t long_nick[60];
    for (size_t i = 0; i < sizeof long_nick; i += 2) {
        long_nick[i] = 0xD0;
        long_nick[i + 1] = 0xAF;
    }
    member_nick_clean(long_nick, sizeof long_nick, nick);
    check(strlen(nick) == 32, "nick: cut to 32 bytes on a character boundary");

    check(strcmp(role_name(TOX_GROUP_ROLE_FOUNDER), "founder") == 0 &&
              strcmp(role_name(TOX_GROUP_ROLE_OBSERVER), "observer") == 0,
          "role names");
}

static void test_commands(void)
{
    char cmd[16];
    char *arg = NULL;
    char t1[] = "  /Mute  1a2b  \n";
    check(split_command(t1, cmd, sizeof cmd, &arg) && strcmp(cmd, "mute") == 0 && strcmp(arg, "1a2b") == 0,
          "command word lower-cased, argument trimmed");
    char t2[] = "/topic  Hello world  ";
    check(split_command(t2, cmd, sizeof cmd, &arg) && strcmp(cmd, "topic") == 0 && strcmp(arg, "Hello world") == 0,
          "an argument with spaces");
    char t3[] = "/stats";
    check(split_command(t3, cmd, sizeof cmd, &arg) && strcmp(cmd, "stats") == 0 && *arg == '\0', "no argument");
    char t4[] = "hello";
    check(!split_command(t4, cmd, sizeof cmd, &arg), "plain text is not a command");
    char t5[] = "/averyveryverylongcommand x";
    check(split_command(t5, cmd, sizeof cmd, &arg) && cmd[0] == '\0', "too long a command word comes back empty");
    char t6[] = "/";
    check(split_command(t6, cmd, sizeof cmd, &arg) && cmd[0] == '\0', "a lone slash");

    check(strcmp(reply_body("[KQ|v1|abc]quoted text[KQ/end]/peers"), "/peers") == 0, "swipe-reply answer");
    check(strcmp(reply_body("/peers"), "/peers") == 0, "no wrapper");
    check(strcmp(reply_body("[KQ|v1 no end"), "[KQ|v1 no end") == 0, "a broken wrapper is left alone");
}

int main(void)
{
    test_hex();
    test_utf8();
    test_msgv3();
    test_prefixes();
    test_pairing_code();
    test_limit_and_topic();
    test_chunks();
    test_peers();
    test_commands();
    printf("community-unit-test: %u passed, %u failed\n", g_pass, g_fail);
    return g_fail == 0 ? 0 : 1;
}
