package com.zoffcc.applications.trifa;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

import static com.zoffcc.applications.trifa.KhandaqJoinedEmoji.joinedSequences;
import static com.zoffcc.applications.trifa.KhandaqJoinedEmoji.takePicturesOff;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (owner, 2026-11-14; QA live F14): a joined emoji sequence is drawn as one character, unless
 * the picker does not offer it.
 *
 * QA: emoji-google 0.9.0 drew the heart on fire (Emoji 13.1, not in the set) as a heart and a fire. The
 * set's pictures inside such a sequence are now taken off where the system font has the sequence; the
 * sequences the picker does not offer keep their members' pictures, drawn apart.
 */
public class KhandaqJoinedEmojiTest
{
    private static final String ZWJ = "‍";
    private static final String VS16 = "️";

    private static final String HEART_ON_FIRE = "❤" + VS16 + ZWJ + "🔥";
    private static final String PIRATE_FLAG = "🏴" + ZWJ + "☠" + VS16;
    private static final String FAMILY = "👨" + ZWJ + "👩" + ZWJ + "👦";
    private static final String WOMAN_RUNNER_MEDIUM = "🏃🏽" + ZWJ + "♀" + VS16;
    private static final String HEALTH_WORKER = "👨" + ZWJ + "⚕" + VS16;

    private static final Predicate<String> FONT_HAS_ALL = sequence -> true;
    private static final Predicate<String> FONT_HAS_NONE = sequence -> false;

    private static int[] range(final int start, final int end)
    {
        return new int[]{start, end};
    }

    /** The set's pictures of each member of a sequence that starts at 0: each member and its extenders. */
    private static List<int[]> picturesOfMembers(final String sequence)
    {
        final List<int[]> pictures = new ArrayList<>();
        int start = 0;
        for (final String member : sequence.split(ZWJ))
        {
            pictures.add(range(start, start + member.length()));
            start += member.length() + ZWJ.length();
        }
        return pictures;
    }

    private static void assertOneSequence(final String text, final int start, final int end)
    {
        final List<int[]> sequences = joinedSequences(text);
        assertEquals("sequences in: " + text, 1, sequences.size());
        assertArrayEquals(range(start, end), sequences.get(0));
    }

    @Test
    public void a_joined_sequence_is_found_with_its_selectors_and_skin_tones()
    {
        assertOneSequence(HEART_ON_FIRE, 0, HEART_ON_FIRE.length());
        assertOneSequence(PIRATE_FLAG, 0, PIRATE_FLAG.length());
        assertOneSequence(FAMILY, 0, FAMILY.length());
        assertOneSequence(WOMAN_RUNNER_MEDIUM, 0, WOMAN_RUNNER_MEDIUM.length());
        assertOneSequence("ok " + HEART_ON_FIRE + "!", 3, 3 + HEART_ON_FIRE.length());
    }

    @Test
    public void two_sequences_next_to_each_other_are_two()
    {
        final List<int[]> sequences = joinedSequences(FAMILY + HEART_ON_FIRE);
        assertEquals(2, sequences.size());
        assertArrayEquals(range(0, FAMILY.length()), sequences.get(0));
        assertArrayEquals(range(FAMILY.length(), FAMILY.length() + HEART_ON_FIRE.length()), sequences.get(1));
    }

    @Test
    public void what_is_not_joined_is_not_a_sequence()
    {
        assertTrue(joinedSequences("👍🏽").isEmpty());       // a skin tone
        assertTrue(joinedSequences("🇹🇷").isEmpty());        // a flag
        assertTrue(joinedSequences("1" + VS16 + "⃣").isEmpty()); // a keycap
        assertTrue(joinedSequences("🙂🙂").isEmpty());
        assertTrue(joinedSequences("👨" + ZWJ).isEmpty());   // a joiner at the end
        assertTrue(joinedSequences("👨" + ZWJ + "a").isEmpty()); // a joiner before a letter
        assertTrue(joinedSequences("a" + ZWJ + "b").isEmpty());
        assertTrue(joinedSequences("").isEmpty());
        assertTrue(joinedSequences(null).isEmpty());
    }

    @Test
    public void the_heart_on_fire_is_left_to_the_font_that_has_it()
    {
        // The QA case: the set has a heart and a fire, not the heart on fire.
        final List<int[]> pictures = picturesOfMembers(HEART_ON_FIRE);
        assertEquals(2, pictures.size());
        assertTrue(takePicturesOff(HEART_ON_FIRE, 0, HEART_ON_FIRE.length(), pictures, FONT_HAS_ALL));
        // A font without it: the pictures stay, which is still better than two glyphs of the font.
        assertFalse(takePicturesOff(HEART_ON_FIRE, 0, HEART_ON_FIRE.length(), pictures, FONT_HAS_NONE));
    }

    @Test
    public void a_picture_of_the_whole_sequence_stays()
    {
        assertFalse(takePicturesOff(FAMILY, 0, FAMILY.length(),
                                    Collections.singletonList(range(0, FAMILY.length())), FONT_HAS_ALL));
        // The key of the set without the U+FE0F at the end is still the whole sequence.
        assertFalse(takePicturesOff(HEALTH_WORKER, 0, HEALTH_WORKER.length(),
                                    Collections.singletonList(range(0, HEALTH_WORKER.length() - 1)),
                                    FONT_HAS_ALL));
    }

    @Test
    public void one_picture_of_a_member_alone_is_taken_off()
    {
        // The set has the fire, not the heart (or the heart is not offered): one picture, not the whole.
        final int fireStart = HEART_ON_FIRE.indexOf("🔥");
        assertTrue(takePicturesOff(HEART_ON_FIRE, 0, HEART_ON_FIRE.length(),
                                   Collections.singletonList(range(fireStart, HEART_ON_FIRE.length())),
                                   FONT_HAS_ALL));
    }

    @Test
    public void nothing_to_take_off_without_pictures()
    {
        assertFalse(takePicturesOff(PIRATE_FLAG, 0, PIRATE_FLAG.length(), new ArrayList<>(), FONT_HAS_ALL));
        assertFalse(takePicturesOff(PIRATE_FLAG, 0, PIRATE_FLAG.length(), null, FONT_HAS_ALL));
    }

    @Test
    public void a_sequence_the_picker_does_not_offer_keeps_its_members_apart()
    {
        final String twoFathers = "👨" + ZWJ + "👨" + ZWJ + "👦";
        final String coupleWithTones = "👨🏻" + ZWJ + "❤" + VS16 + ZWJ + "👨🏼";
        final String rainbowFlag = "🏳" + VS16 + ZWJ + "🌈";
        for (final String sequence : Arrays.asList(twoFathers, coupleWithTones, rainbowFlag))
        {
            assertFalse("must stay apart: " + sequence,
                        takePicturesOff(sequence, 0, sequence.length(), picturesOfMembers(sequence), FONT_HAS_ALL));
        }
    }

    @Test
    public void the_font_is_asked_about_the_whole_sequence_inside_a_message()
    {
        final String message = "hi " + HEART_ON_FIRE + " there";
        final int start = 3;
        final int end = start + HEART_ON_FIRE.length();
        final List<String> asked = new ArrayList<>();
        final List<int[]> pictures = new ArrayList<>();
        for (final int[] picture : picturesOfMembers(HEART_ON_FIRE))
        {
            pictures.add(range(start + picture[0], start + picture[1]));
        }
        assertTrue(takePicturesOff(message, start, end, pictures, sequence -> asked.add(sequence)));
        assertEquals(Collections.singletonList(HEART_ON_FIRE), asked);
    }
}
