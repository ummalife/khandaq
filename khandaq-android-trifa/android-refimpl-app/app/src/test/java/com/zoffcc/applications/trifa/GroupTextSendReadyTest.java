package com.zoffcc.applications.trifa;

import org.junit.Test;

import static com.zoffcc.applications.trifa.TRIFAGlobals.TOX_GROUP_CONNECTION_STATUS.TOX_GROUP_CONNECTION_STATUS_CONNECTED;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TOX_GROUP_CONNECTION_STATUS.TOX_GROUP_CONNECTION_STATUS_CONNECTING;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TOX_GROUP_CONNECTION_STATUS.TOX_GROUP_CONNECTION_STATUS_ERROR;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (0.2.56): a group text goes out only when someone gets it.
 *
 * toxcore marks a group CONNECTED on the first sync response, before any other peer is confirmed, and keeps it
 * CONNECTED after every peer has timed out; a group just created is CONNECTED with only its founder. Its send goes
 * to the confirmed peers only and still returns a message id, so a message written then showed as sent and reached
 * nobody, and a private group never synced it later. tox_group_peer_count counts the confirmed peers, us included.
 */
public class GroupTextSendReadyTest
{
    private static final int CONNECTED = TOX_GROUP_CONNECTION_STATUS_CONNECTED.value;
    private static final int CONNECTING = TOX_GROUP_CONNECTION_STATUS_CONNECTING.value;
    private static final int ERROR = TOX_GROUP_CONNECTION_STATUS_ERROR.value;

    @Test
    public void connectedWithAnotherMemberIsReady()
    {
        assertTrue(HelperGroup.is_group_text_send_ready(CONNECTED, 2L));
        assertTrue(HelperGroup.is_group_text_send_ready(CONNECTED, 100L));
    }

    @Test
    public void connectedAloneIsNotReady()
    {
        // The bug: a new group, or one whose other members all timed out, is connected with only us in it.
        assertFalse(HelperGroup.is_group_text_send_ready(CONNECTED, 1L));
        assertFalse(HelperGroup.is_group_text_send_ready(CONNECTED, 0L));
    }

    @Test
    public void aFailedPeerCountIsNotReady()
    {
        // The JNI count: -98 when toxcore refused the query, -99 without Tox or NGC.
        assertFalse(HelperGroup.is_group_text_send_ready(CONNECTED, -98L));
        assertFalse(HelperGroup.is_group_text_send_ready(CONNECTED, -99L));
    }

    @Test
    public void aGroupThatIsNotConnectedIsNeverReady()
    {
        // Peers do not make up for the connection: that path waits, reconnects and retries as before.
        for (final int conn : new int[]{CONNECTING, ERROR})
        {
            assertFalse("conn " + conn, HelperGroup.is_group_text_send_ready(conn, 5L));
        }
        // A raw GC_Conn_State that skipped TOX_GROUP_CONNECTION_STATUS.from_gc_conn_state is not connected either.
        assertFalse(HelperGroup.is_group_text_send_ready(3, 5L));
    }

    @Test
    public void theSendKeepsTheMessageAtOnceWhenNobodyElseIsInTheGroup() throws Exception
    {
        final String helper = AntispamOnRequestTest.code(source("HelperGroup.java"));
        final int send = helper.indexOf("static long send_group_text_message_resilient(");
        assertTrue(send > 0);
        final int end = helper.indexOf("static String group_identifier_short(", send);
        assertTrue(end > send);
        final String body = helper.substring(send, end);

        final int ready = body.indexOf("if (!is_group_text_send_ready(conn, peers))");
        assertTrue(ready > 0);
        assertTrue(body.indexOf("tox_group_send_message(group_num, 0, message)") > ready);
        // Kept for the flush at once: no reconnect of a connected group, no sleep, the tox thread woken.
        final String alone = body.substring(ready, body.indexOf("return GROUP_SEND_QUEUE_WHEN_UNCONNECTED;", ready));
        assertFalse(alone.contains("schedule_group_auto_reconnect("));
        assertFalse(alone.contains("Thread.sleep("));
        assertTrue(alone.contains("TrifaToxService.wakeup_tox_thread()"));
        assertTrue(alone.contains("nudge_public_group_dht(group_num, group_identifier)"));
    }

    @Test
    public void aMemberJoiningFlushesTheWaitingMessagesInEveryGroup() throws Exception
    {
        final String main = AntispamOnRequestTest.code(source("MainActivity.java"));
        final int join = main.indexOf("static void android_tox_callback_group_peer_join_cb_method(");
        assertTrue(join > 0);
        final int end = main.indexOf("static void android_tox_callback_group_peer_exit_cb_method(", join);
        assertTrue(end > join);
        final String body = main.substring(join, end);

        final int publicOnly = body.indexOf(
                "if (privacy_state == ToxVars.TOX_GROUP_PRIVACY_STATE.TOX_GROUP_PRIVACY_STATE_PUBLIC.value)");
        final int flush = body.indexOf("HelperGroup.schedule_pending_group_message_flush(temp_group_identifier,");
        assertTrue(publicOnly > 0);
        assertTrue(flush > publicOnly);
        // Outside the public-only block: each brace opened after its "if" is closed before the flush.
        final String between = body.substring(publicOnly, flush);
        assertEquals(count(between, '{'), count(between, '}'));
        assertTrue(body.indexOf("HelperGroup.GROUP_PEER_JOIN_FLUSH_DELAY_MS", flush) > flush);
        assertTrue(HelperGroup.GROUP_PEER_JOIN_FLUSH_DELAY_MS > 0L);
    }

    @Test
    public void historyIsNotServedForAMessageThatHasNotGoneOut() throws Exception
    {
        // Served under the PENDING placeholder, the joiner kept it, and again under the real id the flush gave it.
        final String helper = AntispamOnRequestTest.code(source("HelperGroup.java"));
        final int serve = helper.indexOf("static void sync_group_message_history(final long group_number, final long peer_id)");
        assertTrue(serve > 0);
        final int skip = helper.indexOf("PENDING_GROUP_MESSAGE_ID_TOX.equals(gm.message_id_tox)", serve);
        assertTrue(skip > serve);
        assertTrue(helper.indexOf("NgcSignedHistory.sendSignedTextTo(group_identifier, peer_pubkey, gm)", serve) > skip);
        assertTrue(helper.indexOf("send_ngch_syncmsg(group_identifier, peer_pubkey, gm)", serve) > skip);
    }

    @Test
    public void aWaitingMessageShowsAsWaitingWhileNobodyElseIsInTheGroup() throws Exception
    {
        // The transit mark, not the clock of a message on its way, while the group is connected with only us.
        final String holder = AntispamOnRequestTest.code(source("GroupMessageListHolder_text_outgoing_read.java"));
        assertTrue(holder.contains("HelperGroup.is_group_text_send_ready(MainActivity.tox_group_is_connected(groupNum),"));
    }

    private static int count(final String text, final char c)
    {
        int n = 0;
        for (int i = 0; i < text.length(); i++)
        {
            if (text.charAt(i) == c)
            {
                n++;
            }
        }
        return n;
    }

    private static String source(final String name) throws java.io.IOException
    {
        // Gradle runs unit tests from the module directory; an IDE may use the project root.
        for (final String root : new String[]{"src/main/java", "app/src/main/java"})
        {
            final java.io.File file = new java.io.File(root, "com/zoffcc/applications/trifa/" + name);
            if (file.isFile())
            {
                return new String(java.nio.file.Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError(name + " not found from " + new java.io.File("").getAbsolutePath());
    }
}
