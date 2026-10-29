package com.zoffcc.applications.trifa;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ — what counts as a sent friend packet, and which friends get a long text in chunks.
 *
 * The JNI tox_friend_send_lossless_packet returns 1 when the packet is sent. The chunker, the keepalive,
 * edits, reactions and the delete queue tested for 0, so every sent packet read as a failure: long texts
 * never arrived, keepalives went out on every tick, sent edits, reactions and deletes stayed queued.
 */
public class FriendPacketSendTest
{
    private static final byte[] KEEPALIVE_WITH_CHUNKS = {(byte) ConnectionHealthMonitor.PKT_CONN_KEEPALIVE,
            (byte) ConnectionHealthMonitor.KEEPALIVE_FLAG_REASSEMBLES_CHUNKS};
    private static final byte[] BARE_KEEPALIVE = {(byte) ConnectionHealthMonitor.PKT_CONN_KEEPALIVE};

    @Test
    public void onlyAPositiveResultIsASentPacket()
    {
        assertTrue(HelperFriend.is_friend_lossless_packet_send_ok(1));
        assertFalse(HelperFriend.is_friend_lossless_packet_send_ok(0));
        assertFalse(HelperFriend.is_friend_lossless_packet_send_ok(-99)); // toxcore error
        assertFalse(HelperFriend.is_friend_lossless_packet_send_ok(-9991)); // Tox not running
    }

    @Test
    public void aFriendThatNeverSentAKeepaliveGetsNoChunks()
    {
        // iOS, desktop and other Tox clients drop chunk packets
        assertFalse(MessageChunker.canReassemble(9001));
    }

    @Test
    public void aFriendWhoseKeepaliveSaysSoGetsChunksUntilItGoesOffline()
    {
        ConnectionHealthMonitor.onKeepaliveReceived(9002, KEEPALIVE_WITH_CHUNKS, KEEPALIVE_WITH_CHUNKS.length);
        assertTrue(MessageChunker.canReassemble(9002));

        // the number can come back as another client
        ConnectionHealthMonitor.noteFriendOffline(9002);
        assertFalse(MessageChunker.canReassemble(9002));
        assertFalse(ConnectionHealthMonitor.sendsKeepalives(9002));
    }

    @Test
    public void aBareKeepaliveIsAnEarlierBuildThatWouldLoseTheText()
    {
        // it reassembles the chunks, then drops the text; it gets the length-capped single message instead
        ConnectionHealthMonitor.onKeepaliveReceived(9005, BARE_KEEPALIVE, BARE_KEEPALIVE.length);
        assertTrue(ConnectionHealthMonitor.sendsKeepalives(9005));
        assertFalse(MessageChunker.canReassemble(9005));

        ConnectionHealthMonitor.onKeepaliveReceived(9005, KEEPALIVE_WITH_CHUNKS, KEEPALIVE_WITH_CHUNKS.length);
        assertTrue(MessageChunker.canReassemble(9005));
        // the latest keepalive wins
        ConnectionHealthMonitor.onKeepaliveReceived(9005, BARE_KEEPALIVE, BARE_KEEPALIVE.length);
        assertFalse(MessageChunker.canReassemble(9005));
    }

    @Test
    public void aFriendThatNeverSendsKeepalivesIsNeverSilent()
    {
        ConnectionHealthMonitor.onFriendActivity(9003);
        final long now = System.currentTimeMillis();
        assertFalse(ConnectionHealthMonitor.isSilent(9003, now + 10 * 60_000L));
    }

    @Test
    public void aKeepaliveFriendQuietForAMinuteIsSilent()
    {
        ConnectionHealthMonitor.onKeepaliveReceived(9004, BARE_KEEPALIVE, BARE_KEEPALIVE.length);
        final long now = System.currentTimeMillis();
        assertFalse(ConnectionHealthMonitor.isSilent(9004, now + 1_000L));
        assertTrue(ConnectionHealthMonitor.isSilent(9004,
                now + ConnectionHealthMonitor.FRIEND_SILENT_PROBE_MS + 1_000L));
    }

    @Test
    public void onlyATextTooLongForOneMsgV3MessageNeedsChunks()
    {
        assertFalse(MessageChunker.needsChunks(null));
        assertFalse(MessageChunker.needsChunks(repeat("a", ToxVars.TOX_MSGV3_MAX_MESSAGE_LENGTH)));
        assertTrue(MessageChunker.needsChunks(repeat("a", ToxVars.TOX_MSGV3_MAX_MESSAGE_LENGTH + 1)));
        // bytes, not characters: U+0436 (Cyrillic zhe) is two bytes in UTF-8
        assertTrue(MessageChunker.needsChunks(repeat("ж", ToxVars.TOX_MSGV3_MAX_MESSAGE_LENGTH / 2 + 1)));
    }

    private static String repeat(final String s, final int times)
    {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < times; i++)
        {
            sb.append(s);
        }
        return sb.toString();
    }
}
