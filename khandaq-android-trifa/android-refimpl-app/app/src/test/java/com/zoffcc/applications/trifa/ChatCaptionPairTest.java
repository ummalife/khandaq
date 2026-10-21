package com.zoffcc.applications.trifa;

import com.zoffcc.applications.sorm.Message;

import org.junit.Test;

import static com.zoffcc.applications.trifa.ChatCaptionHelper.is_caption_pair;
import static com.zoffcc.applications.trifa.ChatCaptionHelper.within_window;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TRIFA_MSG_TYPE.TRIFA_MSG_FILE;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TRIFA_MSG_TYPE.TRIFA_MSG_TYPE_TEXT;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (QA 02.10) — which 1:1 text counts as the caption of the media right above it.
 *
 * QA: send a photo, a minute later send a text — the text appeared twice (inside the photo AND as
 * its own bubble) until the chat was reopened. An outgoing FILE row never gets a rcvd_timestamp and
 * an outgoing TEXT keeps 0 until the peer's receipt, so the receive-clock fallback saw "0 and 0, 0 s
 * apart" and merged them; the receipt then split them again with only one of the two rows redrawn.
 */
public class ChatCaptionPairTest
{
    private static final long T0 = 1_790_000_000_000L; // 2026-09-21, milliseconds

    private static Message file(final int direction, final long sent, final long rcvd)
    {
        final Message m = new Message();
        m.TRIFA_MESSAGE_TYPE = TRIFA_MSG_FILE.value;
        m.direction = direction;
        m.tox_friendpubkey = "A1B2";
        m.sent_timestamp = sent;
        m.rcvd_timestamp = rcvd;
        return m;
    }

    private static Message text(final int direction, final long sent, final long rcvd, final String body)
    {
        final Message m = new Message();
        m.TRIFA_MESSAGE_TYPE = TRIFA_MSG_TYPE_TEXT.value;
        m.direction = direction;
        m.tox_friendpubkey = "A1B2";
        m.sent_timestamp = sent;
        m.rcvd_timestamp = rcvd;
        m.text = body;
        return m;
    }

    @Test
    public void unset_timestamps_are_never_zero_seconds_apart()
    {
        assertFalse(within_window(0, 0));
        assertFalse(within_window(0, T0));
        assertFalse(within_window(T0, 0));
        assertTrue(within_window(T0, T0 + 5000));
        assertFalse(within_window(T0, T0 + 5001));
        assertFalse(within_window(T0 + 1, T0)); // a caption never precedes its media
    }

    @Test
    public void a_later_undelivered_text_is_not_the_caption_of_our_photo()
    {
        // The QA case, before the receipt: both receive clocks still 0.
        assertFalse(is_caption_pair(file(1, T0, 0), text(1, T0 + 60_000, 0, "У тебя Android?")));
    }

    @Test
    public void the_receipt_does_not_change_the_answer()
    {
        // ...and after it: if the pair flips on a receipt, one of the two rows is always stale.
        assertFalse(is_caption_pair(file(1, T0, 0), text(1, T0 + 60_000, T0 + 61_000, "У тебя Android?")));
    }

    @Test
    public void a_caption_we_send_with_the_photo_merges_before_and_after_delivery()
    {
        // MediaSendPreviewHelper sends the caption ~1.2 s after the file row is written.
        assertTrue(is_caption_pair(file(1, T0, 0), text(1, T0 + 1_200, 0, "caption")));
        assertTrue(is_caption_pair(file(1, T0, 0), text(1, T0 + 1_200, T0 + 9_000, "caption")));
    }

    @Test
    public void favorites_receive_clock_in_seconds_does_not_widen_the_window()
    {
        // FavoritesChatHelper stores rcvd_timestamp in SECONDS: 10 minutes is 600 "ms" apart there.
        assertFalse(is_caption_pair(file(1, T0, T0 / 1000), text(1, T0 + 600_000, (T0 + 600_000) / 1000, "later")));
    }

    @Test
    public void an_incoming_caption_may_pair_by_the_receive_clock()
    {
        // The sender's clock is off by an hour, both rows arrived 1.5 s apart.
        assertTrue(is_caption_pair(file(0, T0, T0), text(0, T0 + 3_600_000, T0 + 1_500, "caption")));
        assertFalse(is_caption_pair(file(0, T0, T0), text(0, T0 + 3_600_000, T0 + 7_000, "not a caption")));
    }

    @Test
    public void the_existing_exclusions_still_hold()
    {
        assertFalse(is_caption_pair(file(1, T0, 0), text(0, T0 + 500, T0 + 500, "other direction")));
        assertFalse(is_caption_pair(file(1, T0, 0), text(1, T0 + 500, 0, "   ")));
        assertFalse(is_caption_pair(file(1, T0, 0), text(1, T0 + 500, 0, "khandaq-location:41.0,29.0")));
        assertFalse(is_caption_pair(text(1, T0, 0, "a"), text(1, T0 + 500, 0, "b")));
    }
}
