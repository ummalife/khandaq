package com.zoffcc.applications.trifa;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (gc IPv6 MTU): live media of a group (NGC video and audio) goes lossy only while toxcore sends the packet in
 * one piece. Since 0.2.57 toxcore sends group chunks of 1150 bytes, not 1372, so that a group packet fits the IPv6
 * minimum MTU, and it refuses a longer lossy packet (TOO_LONG): a frame of 1151 to 1371 bytes, which went lossy before,
 * would have been lost without a word. scripts/check-gc-ipv6-mtu.py keeps the threshold equal to toxcore's and both
 * sends on it.
 */
public class NgcLiveMediaPacketTest
{
    @Test
    public void a_packet_goes_lossy_up_to_the_chunk_toxcore_sends_and_lossless_above()
    {
        assertEquals(1150, ToxVars.MAX_GC_LOSSY_PACKET_SIZE);
        assertTrue(HelperGroup.is_ngc_media_packet_lossy(1));
        assertTrue(HelperGroup.is_ngc_media_packet_lossy(1150));
        assertFalse(HelperGroup.is_ngc_media_packet_lossy(1151));
        // The largest packet that went lossy before, and the old chunk size.
        assertFalse(HelperGroup.is_ngc_media_packet_lossy(1371));
        assertFalse(HelperGroup.is_ngc_media_packet_lossy(1372));
        assertFalse(HelperGroup.is_ngc_media_packet_lossy(ToxVars.TOX_MAX_NGC_VIDEO_AND_HEADER_SIZE));
    }

    @Test
    public void the_audio_encoder_keeps_the_buffer_toxav_fills()
    {
        // The JNI hands toxav_ngc_audio_encode the Java array without its length, and toxav writes up to
        // TOX_MAX_CUSTOM_PACKET_SIZE - 1 - 10 = 1362 bytes into it: the buffer stays at the old chunk size less 10.
        assertEquals(1362, ToxVars.MAX_GC_PACKET_CHUNK_SIZE - 10);
    }
}
