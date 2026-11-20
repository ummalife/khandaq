package com.zoffcc.applications.trifa;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static com.zoffcc.applications.trifa.PushPreviewCryptoTest.PEER_PK;
import static com.zoffcc.applications.trifa.PushPreviewCryptoTest.PLAINTEXT;
import static com.zoffcc.applications.trifa.PushPreviewCryptoTest.VECTOR_TEXT;
import static com.zoffcc.applications.trifa.PushPreviewCryptoTest.repeat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (0.2.64): the plaintext of a push preview (docs/PUSH_PREVIEWS.md, "Preview plaintext"): its bytes, its
 * caps on whole characters, the 900-byte limit, the parser, the event keys and the kinds of a file.
 */
public class PushPreviewPayloadTest
{
    private static final String WAVE = "\uD83D\uDC4B";
    /** Man, woman, girl, boy joined by zero width joiners: one character of 11 chars and 25 bytes. */
    private static final String FAMILY = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67\u200D\uD83D\uDC66";
    /** The flag of Russia: two regional indicators. */
    private static final String FLAG = "\uD83C\uDDF7\uD83C\uDDFA";
    private static final String THUMBS_UP = "\uD83D\uDC4D";
    private static final String HASH = repeat("ab", 32);
    private static final String CHAT_ID = repeat("0f", 32);

    // ---- the bytes ----

    @Test
    public void the_text_of_the_vector_gives_the_plaintext_of_the_vector()
    {
        final byte[] json = PushPreviewPayload.text(VECTOR_TEXT, HASH, "Isa", 1763571600L).toJson();
        assertEquals(PLAINTEXT, new String(json, StandardCharsets.UTF_8));
    }

    @Test
    public void the_plaintext_of_the_vector_parses()
    {
        final PushPreviewPayload p = PushPreviewPayload.parse(PLAINTEXT.getBytes(StandardCharsets.UTF_8));
        assertNotNull(p);
        assertEquals(PushPreviewPayload.KIND_TEXT, p.kind);
        assertEquals(VECTOR_TEXT, p.body);
        assertEquals("Isa", p.senderName);
        assertEquals(1763571600L, p.ts);
        assertEquals("t:" + repeat("AB", 32), p.eventKey);
        assertFalse(p.isGroupEvent());
    }

    @Test
    public void a_group_reaction_has_every_field_in_the_order_of_the_spec()
    {
        final String anchor = "0a0b0c0d" + repeat("0", 56);
        final PushPreviewPayload p = PushPreviewPayload.reaction(THUMBS_UP, PushPreviewPayload.KIND_TEXT, "hi",
                                                                 anchor, "Isa", 5L).inGroup("Team", CHAT_ID);
        assertEquals("{\"v\":1,\"k\":\"reaction\",\"b\":\"hi\",\"rk\":\"text\",\"e\":\"" + THUMBS_UP + "\",\"n\":\"Isa\","
                     + "\"g\":\"Team\",\"gid\":\"" + repeat("0F", 32) + "\",\"ts\":5,"
                     + "\"h\":\"r:0A0B0C0D" + repeat("0", 56) + ":" + THUMBS_UP + "\"}",
                     new String(p.toJson(), StandardCharsets.UTF_8));
        final PushPreviewPayload read = PushPreviewPayload.parse(p.toJson());
        assertEquals(THUMBS_UP, read.emoji);
        assertEquals(PushPreviewPayload.KIND_TEXT, read.reactedKind);
        assertEquals("Team", read.groupName);
        assertEquals(repeat("0F", 32), read.groupId);
        assertTrue(read.isGroupEvent());
    }

    @Test
    public void a_reaction_to_anything_but_a_text_does_not_quote_it()
    {
        final PushPreviewPayload p = PushPreviewPayload.reaction(THUMBS_UP, PushPreviewPayload.KIND_VOICE, "x.m4a",
                                                                 HASH, null, 1L);
        assertNull(p.body);
        assertEquals("{\"v\":1,\"k\":\"reaction\",\"rk\":\"voice\",\"e\":\"" + THUMBS_UP + "\",\"ts\":1,"
                     + "\"h\":\"r:" + repeat("AB", 32) + ":" + THUMBS_UP + "\"}",
                     new String(p.toJson(), StandardCharsets.UTF_8));
        // An unknown kind of the reacted message is left out: "your message".
        assertNull(PushPreviewPayload.reaction(THUMBS_UP, "sticker", "x", HASH, null, 1L).reactedKind);
    }

    @Test
    public void a_file_names_itself_only_when_it_is_a_plain_file()
    {
        final String id = repeat("cd", 32);
        final PushPreviewPayload photo = PushPreviewPayload.file(PushPreviewPayload.KIND_PHOTO, "IMG_1.jpg", id,
                                                                 "Isa", 2L);
        assertNull(photo.body);
        assertEquals("f:" + repeat("CD", 32), photo.eventKey);
        final PushPreviewPayload file = PushPreviewPayload.file(PushPreviewPayload.KIND_FILE, "report.pdf", id,
                                                                "Isa", 2L);
        assertEquals("report.pdf", file.body);
        assertEquals("{\"v\":1,\"k\":\"file\",\"b\":\"report.pdf\",\"n\":\"Isa\",\"ts\":2,\"h\":\"f:" + repeat("CD", 32)
                     + "\"}", new String(file.toJson(), StandardCharsets.UTF_8));
        // A file id that is not 32 bytes of hex gives no event key, and an unknown kind is a file.
        assertNull(PushPreviewPayload.file(PushPreviewPayload.KIND_FILE, "a", "cd", null, 2L).eventKey);
        assertEquals(PushPreviewPayload.KIND_FILE, PushPreviewPayload.file("audio", "a", id, null, 2L).kind);
    }

    @Test
    public void a_group_text_is_a_text_with_its_group_and_its_group_event_key()
    {
        // KHANDAQ (0.2.69, group message wakes): k and b as for a direct text, n, g and gid, and h = gt:.
        final String sender = PEER_PK.toLowerCase(java.util.Locale.ROOT);
        final PushPreviewPayload p = PushPreviewPayload.groupText("hi all", 0xABCDL, sender, "Isa", 7L)
                                                       .inGroup("Team", CHAT_ID);
        assertEquals("{\"v\":1,\"k\":\"text\",\"b\":\"hi all\",\"n\":\"Isa\",\"g\":\"Team\",\"gid\":\""
                     + repeat("0F", 32) + "\",\"ts\":7,\"h\":\"gt:0000ABCD:" + PEER_PK + "\"}",
                     new String(p.toJson(), StandardCharsets.UTF_8));
        final PushPreviewPayload read = PushPreviewPayload.parse(p.toJson());
        assertEquals(PushPreviewPayload.KIND_TEXT, read.kind);
        assertEquals("hi all", read.body);
        assertEquals("Team", read.groupName);
        assertEquals("gt:0000ABCD:" + PEER_PK, read.eventKey);
        assertTrue(read.isGroupEvent());
        // The first 140 whole characters, as a direct text keeps.
        assertEquals(repeat(WAVE, 140), PushPreviewPayload.groupText(repeat(WAVE, 141), 1L, PEER_PK, null, 0L).body);
    }

    @Test
    public void a_group_text_that_waits_has_no_event_key_and_names_its_sender_in_the_group()
    {
        // KHANDAQ (0.2.69, group message wakes): toxcore gives a text that has to wait its id only when it goes, so
        // its preview has no h, and gp, the sender's key in the group, instead.
        final PushPreviewPayload p = PushPreviewPayload.waitingGroupText(
                "hi all", PEER_PK.toLowerCase(java.util.Locale.ROOT), "Isa", 7L).inGroup("Team", CHAT_ID);
        assertEquals("{\"v\":1,\"k\":\"text\",\"b\":\"hi all\",\"n\":\"Isa\",\"g\":\"Team\",\"gid\":\""
                     + repeat("0F", 32) + "\",\"gp\":\"" + PEER_PK + "\",\"ts\":7}",
                     new String(p.toJson(), StandardCharsets.UTF_8));
        final PushPreviewPayload read = PushPreviewPayload.parse(p.toJson());
        assertEquals(PEER_PK, read.groupPeerKey);
        assertNull(read.eventKey);
        assertEquals("hi all", read.body);
        assertTrue(read.isGroupEvent());
        // Anything but a 64-hex key is no key: not written, and not read.
        assertNull(PushPreviewPayload.waitingGroupText("hi", "abcd", null, 1L).groupPeerKey);
        assertNull(PushPreviewPayload.waitingGroupText("hi", null, null, 1L).groupPeerKey);
        assertNull(PushPreviewPayload.parse(("{\"v\":1,\"k\":\"text\",\"gp\":\"" + repeat("zz", 32) + "\",\"ts\":1}")
                                                    .getBytes(StandardCharsets.UTF_8)).groupPeerKey);
        // Any other preview says none.
        assertNull(PushPreviewPayload.groupText("hi", 1L, PEER_PK, null, 1L).groupPeerKey);
    }

    @Test
    public void a_group_file_is_a_file_with_its_group_and_its_group_event_key()
    {
        final String fileHash = repeat("cd", 32);
        final PushPreviewPayload file = PushPreviewPayload.groupFile(PushPreviewPayload.KIND_FILE, "report.pdf",
                                                                     fileHash, "Isa", 2L).inGroup("Team", CHAT_ID);
        assertEquals("{\"v\":1,\"k\":\"file\",\"b\":\"report.pdf\",\"n\":\"Isa\",\"g\":\"Team\",\"gid\":\""
                     + repeat("0F", 32) + "\",\"ts\":2,\"h\":\"gf:" + repeat("CD", 32) + "\"}",
                     new String(file.toJson(), StandardCharsets.UTF_8));
        // A photo, a video or a voice message names no file, as in a direct chat.
        final PushPreviewPayload photo = PushPreviewPayload.groupFile(PushPreviewPayload.KIND_PHOTO, "IMG_1.jpg",
                                                                      fileHash, "Isa", 2L);
        assertNull(photo.body);
        assertEquals("gf:" + repeat("CD", 32), photo.eventKey);
        assertNull(PushPreviewPayload.groupFile(PushPreviewPayload.KIND_FILE, "a", "cd", null, 2L).eventKey);
    }

    // ---- whole characters ----

    @Test
    public void a_text_keeps_its_first_140_whole_characters_and_nothing_is_added()
    {
        assertEquals(repeat(WAVE, 140), PushPreviewPayload.text(repeat(WAVE, 200), null, null, 0L).body);
        assertEquals(repeat(FLAG, 140), PushPreviewPayload.text(repeat(FLAG, 150), null, null, 0L).body);
        assertEquals(repeat(FAMILY, 140), PushPreviewPayload.text(repeat(FAMILY, 141), null, null, 0L).body);
        assertEquals(repeat("e\u0301", 140), PushPreviewPayload.text(repeat("e\u0301", 141), null, null, 0L).body);
        final String exact = repeat("a", 140);
        assertEquals(exact, PushPreviewPayload.text(exact, null, null, 0L).body);
        assertEquals(exact, PushPreviewPayload.text(exact + "b", null, null, 0L).body);
    }

    @Test
    public void a_reacted_text_keeps_80_and_a_name_48_whole_characters()
    {
        final PushPreviewPayload p = PushPreviewPayload.reaction(THUMBS_UP, PushPreviewPayload.KIND_TEXT,
                                                                 repeat(WAVE, 81), HASH, repeat(FLAG, 49), 0L)
                                                       .inGroup(repeat(FAMILY, 50), CHAT_ID);
        assertEquals(repeat(WAVE, 80), p.body);
        assertEquals(repeat(FLAG, 48), p.senderName);
        assertEquals(repeat(FAMILY, 48), p.groupName);
    }

    @Test
    public void the_cut_never_splits_a_surrogate_pair()
    {
        assertEquals(0, MessageSplitter.wholeCharactersEnd(WAVE, 0));
        assertEquals(2, MessageSplitter.wholeCharactersEnd(WAVE + WAVE, 1));
        assertEquals(4, MessageSplitter.wholeCharactersEnd(FLAG + "a", 1));
        assertEquals(FAMILY.length(), MessageSplitter.wholeCharactersEnd(FAMILY + FAMILY, 1));
        assertEquals(3, MessageSplitter.wholeCharactersEnd("abc", 5));
    }

    // ---- 900 bytes ----

    @Test
    public void the_text_goes_first_when_the_plaintext_is_over_900_bytes()
    {
        // 140 emoji of 4 bytes, and names of 48: the text gives way, the names stay whole.
        final PushPreviewPayload p = PushPreviewPayload.text(repeat(WAVE, 140), HASH, repeat(WAVE, 48), 1763571600L)
                                                       .inGroup(repeat(WAVE, 48), CHAT_ID);
        final byte[] json = p.toJson();
        assertNotNull(json);
        assertTrue(json.length <= PushPreviewPayload.MAX_PLAINTEXT_BYTES);
        assertEquals(repeat(WAVE, 48), p.senderName);
        assertEquals(repeat(WAVE, 48), p.groupName);
        // The longest run of whole emoji that fits: one more would not.
        assertEquals(0, p.body.length() % 2);
        final int kept = p.body.length() / 2;
        assertEquals(repeat(WAVE, kept), p.body);
        assertTrue(json.length + 4 > PushPreviewPayload.MAX_PLAINTEXT_BYTES);
        assertEquals(p.body, PushPreviewPayload.parse(json).body);
    }

    @Test
    public void the_names_give_way_after_the_text()
    {
        // 48 families of 25 bytes each are 1200 bytes: the text goes, the group name goes, the name is cut.
        final PushPreviewPayload p = PushPreviewPayload.text("hello", HASH, repeat(FAMILY, 48), 1L)
                                                       .inGroup(repeat(FAMILY, 48), CHAT_ID);
        final byte[] json = p.toJson();
        assertNotNull(json);
        assertTrue(json.length <= PushPreviewPayload.MAX_PLAINTEXT_BYTES);
        assertNull(p.body);
        assertNull(p.groupName);
        assertEquals(0, p.senderName.length() % FAMILY.length());
        assertTrue(p.senderName.length() > 0);
        final PushPreviewPayload read = PushPreviewPayload.parse(json);
        assertEquals(p.senderName, read.senderName);
        assertEquals(repeat("0F", 32), read.groupId);
    }

    @Test
    public void a_file_name_is_cut_to_fit()
    {
        final PushPreviewPayload p = PushPreviewPayload.file(PushPreviewPayload.KIND_FILE, repeat("n", 2000) + ".pdf",
                                                             null, "Isa", 1L);
        final byte[] json = p.toJson();
        assertNotNull(json);
        assertEquals(PushPreviewPayload.MAX_PLAINTEXT_BYTES, json.length);
        assertTrue(p.body.startsWith("nnn"));
    }

    // ---- the strings ----

    @Test
    public void strings_are_escaped_as_python_writes_them()
    {
        final StringBuilder out = new StringBuilder();
        PushPreviewPayload.quote(out, "a\"b\\c\nd\re\tf\u0001g\u2028" + WAVE);
        assertEquals("\"a\\\"b\\\\c\\nd\\re\\tf\\u0001g\u2028" + WAVE + "\"", out.toString());
        final StringBuilder lone = new StringBuilder();
        PushPreviewPayload.quote(lone, "x\uD83Dy");
        assertEquals("\"x\uFFFDy\"", lone.toString());
    }

    @Test
    public void escaped_strings_read_back()
    {
        final String text = "line one\nline \"two\"\t\\ \u0001 " + FAMILY;
        final PushPreviewPayload read = PushPreviewPayload.parse(
                PushPreviewPayload.text(text, null, null, 3L).toJson());
        assertEquals(text, read.body);
        assertNull(read.eventKey);
    }

    // ---- the parser ----

    @Test
    public void unknown_fields_and_values_of_another_type_are_ignored()
    {
        final PushPreviewPayload p = parse("{\"v\":1,\"k\":\"photo\",\"x\":{\"y\":[1,2,{\"z\":null}]},\"n\":7,"
                                           + "\"ts\":1.5,\"gid\":\"zz\",\"b\":true,\"h\":\"f:1\"}");
        assertNotNull(p);
        assertEquals(PushPreviewPayload.KIND_PHOTO, p.kind);
        assertNull(p.senderName);
        assertEquals(0L, p.ts);
        assertNull(p.groupId);
        assertNull(p.body);
        assertEquals("f:1", p.eventKey);
        // White space between the tokens is JSON too.
        assertNotNull(parse(" { \"v\" : 1 , \"k\" : \"text\" } "));
        // An escaped solidus and a unicode escape.
        assertEquals("a/b\u00e9", parse("{\"v\":1,\"k\":\"text\",\"b\":\"a\\/b\\u00e9\"}").body);
    }

    @Test
    public void anything_but_a_version_1_preview_of_a_known_kind_is_not_read()
    {
        assertNull(parse("{\"v\":2,\"k\":\"text\"}"));
        assertNull(parse("{\"v\":\"1\",\"k\":\"text\"}"));
        assertNull(parse("{\"k\":\"text\"}"));
        assertNull(parse("{\"v\":1,\"k\":\"sticker\"}"));
        assertNull(parse("{\"v\":1}"));
        assertNull(parse("{\"v\":1,\"k\":\"text\"} x"));
        assertNull(parse("{\"v\":1,\"k\":\"text\""));
        assertNull(parse("{\"v\":1,\"k\":\"te\nxt\"}"));
        assertNull(parse("{\"v\":1,\"k\":\"text\",\"b\":\"\\x\"}"));
        assertNull(parse("[1]"));
        assertNull(parse(""));
        assertNull(PushPreviewPayload.parse(null));
        assertNull(parse("{\"v\":1,\"k\":\"text\",\"b\":" + repeat("[", 40) + repeat("]", 40) + "}"));
    }

    @Test
    public void reaction_fields_belong_to_a_reaction_only()
    {
        final PushPreviewPayload p = parse("{\"v\":1,\"k\":\"text\",\"b\":\"x\",\"e\":\"" + THUMBS_UP
                                           + "\",\"rk\":\"voice\"}");
        assertNull(p.emoji);
        assertNull(p.reactedKind);
    }

    // ---- event keys ----

    @Test
    public void event_keys_are_upper_case_and_need_their_ids()
    {
        assertEquals("t:" + repeat("AB", 32), PushPreviewPayload.textEventKey(HASH));
        assertNull(PushPreviewPayload.textEventKey(HASH.substring(2)));
        assertNull(PushPreviewPayload.textEventKey(repeat("zz", 32)));
        assertNull(PushPreviewPayload.textEventKey(""));
        assertNull(PushPreviewPayload.textEventKey(null));
        assertEquals("f:" + repeat("AB", 32), PushPreviewPayload.fileEventKey(HASH));
        assertEquals("r:00FF:" + THUMBS_UP, PushPreviewPayload.reactionEventKey("00ff", THUMBS_UP));
        // KHANDAQ (0.2.69, group message wakes): the vector of the spec, and the ends of a 32-bit message id.
        assertEquals("gt:0000ABCD:A1A2A3A4A5A6A7A8A9AAABACADAEAFB0B1B2B3B4B5B6B7B8B9BABBBCBDBEBFC0",
                     PushPreviewPayload.groupTextEventKey(0x0000ABCDL, PEER_PK.toLowerCase(java.util.Locale.ROOT)));
        assertEquals("gt:00000000:" + PEER_PK, PushPreviewPayload.groupTextEventKey(0L, PEER_PK));
        assertEquals("gt:FFFFFFFF:" + PEER_PK, PushPreviewPayload.groupTextEventKey(0xFFFFFFFFL, PEER_PK));
        assertNull(PushPreviewPayload.groupTextEventKey(0x100000000L, PEER_PK));
        assertNull(PushPreviewPayload.groupTextEventKey(-1L, PEER_PK));
        assertNull(PushPreviewPayload.groupTextEventKey(1L, PEER_PK.substring(2)));
        assertNull(PushPreviewPayload.groupTextEventKey(1L, repeat("zz", 32)));
        assertNull(PushPreviewPayload.groupTextEventKey(1L, null));
        assertEquals("gf:" + repeat("AB", 32), PushPreviewPayload.groupFileEventKey(HASH));
        assertNull(PushPreviewPayload.groupFileEventKey(HASH.substring(2)));
        assertNull(PushPreviewPayload.groupFileEventKey(null));
        assertNull(PushPreviewPayload.reactionEventKey("00fg", THUMBS_UP));
        assertNull(PushPreviewPayload.reactionEventKey("", THUMBS_UP));
        assertNull(PushPreviewPayload.reactionEventKey("00ff", ""));
    }

    // ---- kinds from a file ----

    @Test
    public void the_kind_of_a_file_comes_from_its_name()
    {
        assertEquals(PushPreviewPayload.KIND_VOICE, PushPreviewPayload.fileKind("1700000000.file.m4a", null));
        assertEquals(PushPreviewPayload.KIND_VOICE, PushPreviewPayload.fileKind(" VOICE.FILE.M4A ", "audio/mp4"));
        assertEquals(PushPreviewPayload.KIND_FILE, PushPreviewPayload.fileKind("song.m4a", "audio/mp4"));
        assertEquals(PushPreviewPayload.KIND_FILE, PushPreviewPayload.fileKind("song.mp3", "audio/mpeg"));
        for (final String video : new String[]{"a.mp4", "a.MOV", "a.m4v", "a.webm", "a.mkv", "a.3gp", "a.avi"})
        {
            assertEquals(video, PushPreviewPayload.KIND_VIDEO, PushPreviewPayload.fileKind(video, null));
        }
        assertEquals(PushPreviewPayload.KIND_VIDEO, PushPreviewPayload.fileKind("clip.bin", "video/x-matroska"));
        for (final String photo : new String[]{"a.jpg", "a.JPEG", "a.png", "a.gif", "a.heic", "a.heif", "a.webp"})
        {
            assertEquals(photo, PushPreviewPayload.KIND_PHOTO, PushPreviewPayload.fileKind(photo, "image/x"));
        }
        assertEquals(PushPreviewPayload.KIND_FILE, PushPreviewPayload.fileKind("a.bmp", "image/bmp"));
        assertEquals(PushPreviewPayload.KIND_FILE, PushPreviewPayload.fileKind("README", null));
        assertEquals(PushPreviewPayload.KIND_FILE, PushPreviewPayload.fileKind(null, null));
        assertTrue(PushPreviewPayload.isMessageKind(PushPreviewPayload.KIND_TEXT));
        assertFalse(PushPreviewPayload.isFileKind(PushPreviewPayload.KIND_TEXT));
        assertFalse(PushPreviewPayload.isMessageKind(PushPreviewPayload.KIND_REACTION));
    }

    private static PushPreviewPayload parse(final String json)
    {
        return PushPreviewPayload.parse(json.getBytes(StandardCharsets.UTF_8));
    }
}
