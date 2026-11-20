package com.zoffcc.applications.trifa;

import org.junit.Test;

import java.util.Collections;

import static com.zoffcc.applications.trifa.PushPreviewCryptoTest.repeat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (0.2.64): one alert per event, whether the push preview or Tox tells of it first (docs/PUSH_PREVIEWS.md,
 * "Recipient").
 */
public class PushPreviewSeenTest
{
    private static final String SENDER = repeat("A1", 32);
    private static final String HASH = repeat("ab", 32);
    private static final String FILE_ID = repeat("cd", 32);
    private static final String THUMBS_UP = "\uD83D\uDC4D";
    private static final long T0 = 1_763_571_600_000L;

    @Test
    public void a_push_shown_first_keeps_the_message_from_alerting_again()
    {
        final MemoryPreferences prefs = new MemoryPreferences();
        final PushPreviewSeen seen = new PushPreviewSeen();
        final PushPreviewSeen.Event push = PushPreviewSeen.forPreview(SENDER, text("hello", HASH));
        assertTrue(seen.pushMayShow(prefs, push, T0));
        seen.pushShown(prefs, push, T0);
        assertTrue(seen.toxArrived(prefs, PushPreviewSeen.forText(SENDER, HASH, "hello", "hello"), T0 + 5_000L));
        // Another message alerts.
        assertFalse(seen.toxArrived(prefs, PushPreviewSeen.forText(SENDER, repeat("ac", 32), "hello", "hello"),
                                    T0 + 6_000L));
    }

    @Test
    public void the_message_first_keeps_a_late_push_from_alerting()
    {
        final MemoryPreferences prefs = new MemoryPreferences();
        final PushPreviewSeen seen = new PushPreviewSeen();
        assertFalse(seen.toxArrived(prefs, PushPreviewSeen.forText(SENDER, HASH, "hello", "hello"), T0));
        assertFalse(seen.pushMayShow(prefs, PushPreviewSeen.forPreview(SENDER, text("hello", HASH)), T0 + 60_000L));
        // Its event key is held back for 30 minutes.
        assertFalse(seen.pushMayShow(prefs, PushPreviewSeen.forPreview(SENDER, text("other", HASH)),
                                     T0 + PushPreviewSeen.REMEMBER_MS));
        assertTrue(seen.pushMayShow(prefs, PushPreviewSeen.forPreview(SENDER, text("other", HASH)),
                                    T0 + PushPreviewSeen.REMEMBER_MS + 1L));
    }

    @Test
    public void a_text_without_its_hash_is_known_by_what_it_says_once()
    {
        final MemoryPreferences prefs = new MemoryPreferences();
        final PushPreviewSeen seen = new PushPreviewSeen();
        assertFalse(seen.toxArrived(prefs, PushPreviewSeen.forText(SENDER, null, "see you", "see you"), T0));
        final PushPreviewSeen.Event push = PushPreviewSeen.forPreview(SENDER, text("see you", null));
        assertFalse(seen.pushMayShow(prefs, push, T0 + 1_000L));
        // That arrival is used up: the next push saying the same is another message.
        assertTrue(seen.pushMayShow(prefs, push, T0 + 2_000L));
        // From another sender it is another message too.
        assertFalse(seen.toxArrived(prefs, PushPreviewSeen.forText(SENDER, null, "see you", "see you"), T0 + 3_000L));
        assertTrue(seen.pushMayShow(prefs, PushPreviewSeen.forPreview(repeat("B2", 32), text("see you", null)),
                                    T0 + 4_000L));
    }

    @Test
    public void what_came_over_tox_holds_back_a_push_for_two_minutes()
    {
        final MemoryPreferences prefs = new MemoryPreferences();
        final PushPreviewSeen seen = new PushPreviewSeen();
        seen.toxArrived(prefs, PushPreviewSeen.forText(SENDER, null, "late", "late"), T0);
        assertTrue(seen.pushMayShow(prefs, PushPreviewSeen.forPreview(SENDER, text("late", null)),
                                    T0 + PushPreviewSeen.ARRIVAL_MS + 1L));
    }

    @Test
    public void a_preview_of_the_shown_text_matches_its_raw_text_too()
    {
        // The sender may have put the text with its reply header into the preview, or the text as shown.
        final MemoryPreferences prefs = new MemoryPreferences();
        final PushPreviewSeen seen = new PushPreviewSeen();
        seen.toxArrived(prefs, PushPreviewSeen.forText(SENDER, null, "answer", "> quoted\nanswer"), T0);
        assertFalse(seen.pushMayShow(prefs, PushPreviewSeen.forPreview(SENDER, text("> quoted\nanswer", null)),
                                     T0 + 1_000L));
    }

    @Test
    public void event_keys_outlive_the_process_and_texts_stay_off_the_disk()
    {
        final MemoryPreferences prefs = new MemoryPreferences();
        final PushPreviewSeen before = new PushPreviewSeen();
        final PushPreviewSeen.Event push = PushPreviewSeen.forPreview(SENDER, text("hello", HASH));
        before.pushShown(prefs, push, T0);
        assertEquals(1, prefs.values.size());
        for (final String key : prefs.values.keySet())
        {
            assertTrue(key, PushPreviewSeen.isEventMark(key));
            assertFalse("no event key in clear on the disk", key.contains(HASH.toUpperCase(java.util.Locale.ROOT)));
        }
        // A new process: the message comes over Tox, and does not alert again.
        final PushPreviewSeen after = new PushPreviewSeen();
        assertTrue(after.toxArrived(prefs, PushPreviewSeen.forText(SENDER, HASH, "hello", "hello"), T0 + 600_000L));
        // Neither its words nor anything that came without an event key are written.
        after.toxArrived(prefs, PushPreviewSeen.forText(SENDER, null, "words", "words"), T0 + 600_001L);
        for (final String key : prefs.values.keySet())
        {
            assertTrue(key, PushPreviewSeen.isEventMark(key));
        }
    }

    @Test
    public void an_old_event_key_on_the_disk_is_forgotten()
    {
        final MemoryPreferences prefs = new MemoryPreferences();
        new PushPreviewSeen().pushShown(prefs, PushPreviewSeen.forPreview(SENDER, text("hello", HASH)), T0);
        final PushPreviewSeen later = new PushPreviewSeen();
        assertFalse(later.toxArrived(prefs, PushPreviewSeen.forText(SENDER, HASH, "hello", "hello"),
                                     T0 + PushPreviewSeen.REMEMBER_MS + 1L));
    }

    @Test
    public void a_reaction_is_matched_by_its_anchor_and_emoji()
    {
        final MemoryPreferences prefs = new MemoryPreferences();
        final PushPreviewSeen seen = new PushPreviewSeen();
        final String anchor = "0a0b0c0d" + repeat("0", 56);
        final PushPreviewPayload reaction = PushPreviewPayload.reaction(THUMBS_UP, PushPreviewPayload.KIND_TEXT, "x",
                                                                        anchor, "Isa", T0 / 1000L);
        final PushPreviewSeen.Event push = PushPreviewSeen.forPreview(SENDER, reaction);
        assertTrue(push.reaction);
        assertEquals(1, push.marks.size());
        seen.pushShown(prefs, push, T0);
        // Over Tox the anchor comes as on the wire, in upper case.
        final PushPreviewSeen.Event tox = PushPreviewSeen.forReaction(anchor.toUpperCase(java.util.Locale.ROOT),
                                                                      THUMBS_UP);
        assertEquals(push.marks, tox.marks);
        assertTrue(seen.toxArrived(prefs, tox, T0 + 1_000L));
        // Another emoji on the same message is another event.
        assertFalse(seen.toxArrived(prefs, PushPreviewSeen.forReaction(anchor, "\u2764\uFE0F"), T0 + 2_000L));
    }

    @Test
    public void a_group_preview_is_known_by_its_event_key_only()
    {
        final PushPreviewPayload inGroup = PushPreviewPayload.reaction(THUMBS_UP, null, null, repeat("ef", 32), "Isa",
                                                                       1L).inGroup("Team", repeat("0f", 32));
        assertEquals(1, PushPreviewSeen.forPreview(SENDER, inGroup).marks.size());
        final PushPreviewPayload noKey = PushPreviewPayload.reaction(THUMBS_UP, null, null, "", "Isa", 1L)
                                                           .inGroup("Team", repeat("0f", 32));
        assertTrue(PushPreviewSeen.forPreview(SENDER, noKey).marks.isEmpty());
    }

    @Test
    public void a_group_text_a_push_showed_does_not_alert_again_when_it_comes_over_tox()
    {
        // KHANDAQ (0.2.69, group message wakes): the wake names the text by toxcore's message id and the sender's key
        // in the group; the row keeps the id as 8 hex, the callback gives it as a number. Both are the same mark.
        final MemoryPreferences prefs = new MemoryPreferences();
        final PushPreviewSeen seen = new PushPreviewSeen();
        final String member = repeat("c4", 32);
        final PushPreviewSeen.Event push = PushPreviewSeen.forPreview(
                SENDER, PushPreviewPayload.groupText("hello", 0xABCDL, member, "Isa", T0 / 1000L)
                                          .inGroup("Team", repeat("0f", 32)));
        assertEquals(1, push.marks.size());
        final String memberUpper = member.toUpperCase(java.util.Locale.ROOT);
        assertEquals(push.marks, PushPreviewSeen.forGroupText(0xABCDL, memberUpper).marks);
        assertEquals(push.marks, PushPreviewSeen.forGroupText("0000abcd", member).marks);
        assertTrue(seen.pushMayShow(prefs, push, T0));
        seen.pushShown(prefs, push, T0);
        assertTrue(seen.toxArrived(prefs, PushPreviewSeen.forGroupText(0xABCDL, member), T0 + 5_000L));

        // The same id from another member, or another id from the member, is another message, and alerts.
        assertFalse(seen.toxArrived(prefs, PushPreviewSeen.forGroupText(0xABCDL, repeat("c5", 32)), T0 + 6_000L));
        assertFalse(seen.toxArrived(prefs, PushPreviewSeen.forGroupText(0xABCEL, member), T0 + 7_000L));
        // A text that came first holds back a late push of it.
        assertFalse(seen.toxArrived(prefs, PushPreviewSeen.forGroupText(0x1234L, member), T0));
        assertFalse(seen.pushMayShow(prefs, PushPreviewSeen.forPreview(
                SENDER, PushPreviewPayload.groupText("late", 0x1234L, member, "Isa", T0 / 1000L)
                                          .inGroup("Team", repeat("0f", 32))), T0 + 60_000L));

        // No mark without a well-formed id and key; a private message carries none.
        assertTrue(PushPreviewSeen.forGroupText("abcd", member).marks.isEmpty());
        assertTrue(PushPreviewSeen.forGroupText("-0000001", member).marks.isEmpty());
        assertTrue(PushPreviewSeen.forGroupText("0000zzzz", member).marks.isEmpty());
        assertTrue(PushPreviewSeen.forGroupText((String) null, member).marks.isEmpty());
        assertTrue(PushPreviewSeen.forGroupText(-1L, member).marks.isEmpty());
        assertTrue(PushPreviewSeen.forGroupText(1L, null).marks.isEmpty());
    }

    @Test
    public void a_group_file_a_push_showed_does_not_alert_again_when_it_comes_over_tox()
    {
        final MemoryPreferences prefs = new MemoryPreferences();
        final PushPreviewSeen seen = new PushPreviewSeen();
        final PushPreviewSeen.Event push = PushPreviewSeen.forPreview(
                SENDER, PushPreviewPayload.groupFile(PushPreviewPayload.KIND_PHOTO, "IMG_1.jpg", FILE_ID, "Isa",
                                                     T0 / 1000L).inGroup("Team", repeat("0f", 32)));
        assertEquals(1, push.marks.size());
        final PushPreviewSeen.Event tox = PushPreviewSeen.forGroupFile(FILE_ID.toUpperCase(java.util.Locale.ROOT));
        assertEquals(push.marks, tox.marks);
        // The direct file of the same id is another event.
        final PushPreviewSeen.Event direct = PushPreviewSeen.forFile(SENDER, FILE_ID, PushPreviewPayload.KIND_PHOTO,
                                                                     "IMG_1.jpg");
        assertTrue(Collections.disjoint(tox.marks, direct.marks));
        seen.pushShown(prefs, push, T0);
        assertTrue(seen.toxArrived(prefs, tox, T0 + 2_000L));
        assertFalse(seen.toxArrived(prefs, PushPreviewSeen.forGroupFile(repeat("ce", 32)), T0 + 3_000L));
        assertTrue(PushPreviewSeen.forGroupFile("cd").marks.isEmpty());
        assertTrue(PushPreviewSeen.forGroupFile(null).marks.isEmpty());
    }

    @Test
    public void a_file_is_matched_by_its_id_or_by_its_kind_and_name()
    {
        final PushPreviewSeen.Event withId = PushPreviewSeen.forPreview(
                SENDER, PushPreviewPayload.file(PushPreviewPayload.KIND_FILE, "report.pdf", FILE_ID, "Isa", 1L));
        final PushPreviewSeen.Event tox = PushPreviewSeen.forFile(SENDER, FILE_ID.toUpperCase(java.util.Locale.ROOT),
                                                                  PushPreviewPayload.KIND_FILE, "report.pdf");
        // With its id it is known by that alone, and the file that comes over Tox carries it.
        assertEquals(1, withId.marks.size());
        assertTrue(tox.marks.containsAll(withId.marks));

        // Without an id: the kind and, for a plain file, the name.
        final PushPreviewSeen.Event noId = PushPreviewSeen.forPreview(
                SENDER, PushPreviewPayload.file(PushPreviewPayload.KIND_FILE, "report.pdf", null, "Isa", 1L));
        assertFalse(Collections.disjoint(noId.marks, tox.marks));
        final PushPreviewSeen.Event otherName = PushPreviewSeen.forFile(SENDER, null, PushPreviewPayload.KIND_FILE,
                                                                        "other.pdf");
        assertTrue(Collections.disjoint(noId.marks, otherName.marks));
        // A photo's name is not in its preview, so it does not count.
        final PushPreviewSeen.Event photo = PushPreviewSeen.forPreview(
                SENDER, PushPreviewPayload.file(PushPreviewPayload.KIND_PHOTO, "IMG_1.jpg", null, "Isa", 1L));
        assertFalse(Collections.disjoint(photo.marks,
                                         PushPreviewSeen.forFile(SENDER, null, PushPreviewPayload.KIND_PHOTO,
                                                                 "IMG_1.jpg").marks));
    }

    @Test
    public void the_memory_keeps_the_newest_marks_only()
    {
        final MemoryPreferences prefs = new MemoryPreferences();
        final PushPreviewSeen seen = new PushPreviewSeen();
        for (int i = 0; i < PushPreviewSeen.MAX_MARKS + 40; i++)
        {
            final String hash = String.format(java.util.Locale.ROOT, "%064x", i);
            seen.pushShown(prefs, PushPreviewSeen.forPreview(SENDER, text("m", hash)), T0 + i);
        }
        assertEquals(PushPreviewSeen.MAX_MARKS, prefs.values.size());
        // The newest is there, the oldest is not.
        final String newest = String.format(java.util.Locale.ROOT, "%064x", PushPreviewSeen.MAX_MARKS + 39);
        assertTrue(new PushPreviewSeen().toxArrived(prefs, PushPreviewSeen.forText(SENDER, newest, null, null),
                                                    T0 + 1_000L));
        assertFalse(new PushPreviewSeen().toxArrived(prefs, PushPreviewSeen.forText(SENDER, repeat("0", 64), null,
                                                                                    null), T0 + 1_000L));
    }

    @Test
    public void two_messages_saying_the_same_are_two_events()
    {
        // "ok" twice, or two photos: with event keys they do not hold each other back.
        final MemoryPreferences prefs = new MemoryPreferences();
        final PushPreviewSeen seen = new PushPreviewSeen();
        final PushPreviewSeen.Event first = PushPreviewSeen.forPreview(SENDER, text("ok", HASH));
        final PushPreviewSeen.Event second = PushPreviewSeen.forPreview(SENDER, text("ok", repeat("ac", 32)));
        assertTrue(Collections.disjoint(first.marks, second.marks));
        seen.pushShown(prefs, first, T0);
        assertTrue(seen.pushMayShow(prefs, second, T0 + 1_000L));
        final PushPreviewSeen.Event photo1 = PushPreviewSeen.forPreview(
                SENDER, PushPreviewPayload.file(PushPreviewPayload.KIND_PHOTO, "IMG_1.jpg", FILE_ID, "Isa", 1L));
        final PushPreviewSeen.Event photo2 = PushPreviewSeen.forPreview(
                SENDER, PushPreviewPayload.file(PushPreviewPayload.KIND_PHOTO, "IMG_2.jpg", repeat("ce", 32), "Isa", 1L));
        assertTrue(Collections.disjoint(photo1.marks, photo2.marks));
    }

    @Test
    public void a_text_a_push_showed_without_its_hash_does_not_alert_again_when_it_comes()
    {
        // The preview was swiped away before Tox brought the message (which has its msgV3 hash by then).
        final MemoryPreferences prefs = new MemoryPreferences();
        final PushPreviewSeen seen = new PushPreviewSeen();
        final PushPreviewSeen.Event push = PushPreviewSeen.forPreview(SENDER, text("see you", null));
        assertTrue(seen.pushMayShow(prefs, push, T0));
        seen.pushShown(prefs, push, T0);
        assertTrue("nothing it says is written", prefs.values.isEmpty());
        assertTrue(seen.toxArrived(prefs, PushPreviewSeen.forText(SENDER, HASH, "see you", "see you"), T0 + 600_000L));
        // Used up: the next message saying the same alerts.
        assertFalse(seen.toxArrived(prefs, PushPreviewSeen.forText(SENDER, repeat("ac", 32), "see you", "see you"),
                                    T0 + 601_000L));
        // And it holds for 30 minutes only.
        final MemoryPreferences laterPrefs = new MemoryPreferences();
        final PushPreviewSeen later = new PushPreviewSeen();
        later.pushShown(laterPrefs, push, T0);
        assertFalse(later.toxArrived(laterPrefs, PushPreviewSeen.forText(SENDER, HASH, "see you", "see you"),
                                     T0 + PushPreviewSeen.REMEMBER_MS + 1L));
    }

    @Test
    public void a_wiped_profile_remembers_nothing()
    {
        final MemoryPreferences prefs = new MemoryPreferences();
        final PushPreviewSeen seen = new PushPreviewSeen();
        seen.pushShown(prefs, PushPreviewSeen.forPreview(SENDER, text("hello", HASH)), T0);
        seen.pushShown(prefs, PushPreviewSeen.forPreview(SENDER, text("bye", null)), T0);
        seen.forgetAll();
        final MemoryPreferences cleared = new MemoryPreferences();
        assertFalse(seen.toxArrived(cleared, PushPreviewSeen.forText(SENDER, HASH, "hello", "hello"), T0 + 1_000L));
        assertFalse(seen.toxArrived(cleared, PushPreviewSeen.forText(SENDER, null, "bye", "bye"), T0 + 2_000L));
    }

    @Test
    public void nothing_known_is_nothing_seen()
    {
        assertFalse(PushPreviewSeen.toxArrived(null, PushPreviewSeen.forText(SENDER, HASH, "x", "x")));
        assertTrue(PushPreviewSeen.forText(null, null, null, null).marks.isEmpty());
    }

    private static PushPreviewPayload text(final String body, final String hash)
    {
        return PushPreviewPayload.text(body, hash, "Isa", T0 / 1000L);
    }
}
