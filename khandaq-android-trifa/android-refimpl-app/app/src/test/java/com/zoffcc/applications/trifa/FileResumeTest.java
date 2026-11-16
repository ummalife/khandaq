package com.zoffcc.applications.trifa;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (0.2.57, SPEC-network part 1): a 1:1 file cut off by a network change goes on where it stopped.
 * The rules (FileResume) are held as the phone runs them, and the transfer code is checked to ask them.
 */
public class FileResumeTest
{
    private static final String JAVA = "src/main/java/com/zoffcc/applications/trifa/";
    private static final String ID = "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF";
    private static final long SIZE = 52_428_800L;
    private static final long MINUTE = 60_000L;

    // ---- the resume decision (SPEC Tests: resume decision) ----

    @Test
    public void the_same_file_with_bytes_here_seeks_to_them_and_keeps_its_entry()
    {
        assertTrue(FileResume.sameFile(ID, SIZE, ID, SIZE));
        final FileResume.Decision d = FileResume.decide(true, false, 1_234_567L, SIZE);
        assertEquals(FileResume.Step.RESUME, d.step);
        assertEquals(1_234_567L, d.seekTo);
    }

    @Test
    public void a_file_that_is_whole_here_takes_its_last_byte_again()
    {
        final FileResume.Decision d = FileResume.decide(true, true, -1L, SIZE);
        assertEquals(FileResume.Step.ALREADY_HERE, d.step);
        assertEquals(SIZE - 1, d.seekTo);
        // A file of one byte takes that byte again.
        assertEquals(0L, FileResume.decide(true, true, -1L, 1L).seekTo);
    }

    @Test
    public void another_id_or_another_size_is_a_new_file()
    {
        final String other = ID.substring(0, 63) + "0";
        assertFalse(FileResume.sameFile(ID, SIZE, other, SIZE));
        assertFalse(FileResume.sameFile(ID, SIZE, ID, SIZE + 1));
        assertFalse(FileResume.sameFile(ID, SIZE, ID, SIZE - 1));
        // An entry that never knew its size, or its id, is not matched.
        assertFalse(FileResume.sameFile(ID, -1L, ID, SIZE));
        assertFalse(FileResume.sameFile(null, SIZE, ID, SIZE));
        assertFalse(FileResume.sameFile(ID, SIZE, null, SIZE));
        assertEquals(FileResume.Step.NEW_MESSAGE, FileResume.decide(false, false, 1_000L, SIZE).step);
    }

    @Test
    public void the_id_matches_in_either_case()
    {
        assertTrue(FileResume.sameFile(ID.toLowerCase(), SIZE, ID, SIZE));
        assertTrue(FileResume.sameFile(ID, SIZE, ID.toLowerCase(), SIZE));
        assertEquals(ID, FileResume.reusableFileId(ID.toLowerCase()));
    }

    @Test
    public void no_bytes_here_comes_from_the_first_byte_into_the_same_entry()
    {
        assertEquals(FileResume.Step.FROM_START, FileResume.decide(true, false, 0L, SIZE).step);
        assertEquals(-1L, FileResume.decide(true, false, 0L, SIZE).seekTo);
        // The partial data is gone.
        assertEquals(FileResume.Step.FROM_START, FileResume.decide(true, false, -1L, SIZE).step);
        // More bytes than the file has: not this file's, so from the first byte.
        assertEquals(FileResume.Step.FROM_START, FileResume.decide(true, false, SIZE + 10, SIZE).step);
    }

    @Test
    public void every_byte_here_without_the_end_takes_the_last_byte_again()
    {
        final FileResume.Decision d = FileResume.decide(true, false, SIZE, SIZE);
        assertEquals(FileResume.Step.RESUME, d.step);
        assertEquals(SIZE - 1, d.seekTo);
    }

    @Test
    public void a_recognised_offer_is_accepted_by_itself_only_as_the_rule_says()
    {
        assertTrue(FileResume.acceptsByItself(true, false, false));
        assertTrue(FileResume.acceptsByItself(false, false, true));
        assertFalse("waits for a tap", FileResume.acceptsByItself(false, false, false));
        assertFalse("the person said no", FileResume.acceptsByItself(true, true, true));
    }

    @Test
    public void the_seek_comes_right_before_the_accept_while_the_bytes_reach_it()
    {
        assertEquals(4096L, FileResume.seekBeforeAccept(4096L, 4096L, SIZE));
        assertEquals(4096L, FileResume.seekBeforeAccept(4096L, 8192L, SIZE));
        // The bytes on disk no longer reach the point it was bound at: from the first byte.
        assertEquals(-1L, FileResume.seekBeforeAccept(4096L, 100L, SIZE));
        assertEquals(-1L, FileResume.seekBeforeAccept(4096L, -1L, SIZE));
        // Nothing to seek to.
        assertEquals(-1L, FileResume.seekBeforeAccept(0L, 4096L, SIZE));
        assertEquals(-1L, FileResume.seekBeforeAccept(SIZE, SIZE, SIZE));
    }

    @Test
    public void the_size_of_an_entry_is_read_from_its_text()
    {
        assertEquals(SIZE, FileResume.sizeFromIncomingText("film.mp4\n" + SIZE + " bytes"));
        assertEquals(12L, FileResume.sizeFromIncomingText("a\nb.txt\n12 bytes"));
        assertEquals(-1L, FileResume.sizeFromIncomingText("film.mp4"));
        assertEquals(-1L, FileResume.sizeFromIncomingText("film.mp4\n12 MB"));
        assertEquals(-1L, FileResume.sizeFromIncomingText(null));
    }

    @Test
    public void the_receiver_offers_resume_from_its_own_offer_callback()
    {
        // No new entry for a file it knows: file_recv asks before it makes one, for DATA (desktop, iOS) and FTV2.
        final String recv = method(read(JAVA + "MainActivity.java"),
                                   "static void android_tox_callback_file_recv_cb_method(");
        final String ask = "if (HelperFiletransfer.resume_incoming_offer(friend_number, file_number, a_TOX_FILE_KIND,"
                           + " file_size,file_id_buffer_hex, filename)){return;}";
        final String bare = bare(recv);
        final int first = bare.indexOf(bare(ask));
        assertTrue(first > 0);
        final int second = bare.indexOf(bare(ask), first + 1);
        assertTrue("both kinds ask", second > first);
        assertTrue(first < bare.indexOf("Filetransferf=newFiletransfer();", first));
        assertTrue(second < bare.indexOf("Filetransferf=newFiletransfer();", second));
        // The last byte of a whole file writes nothing and notifies nothing: the chunk callback drops it first.
        final String chunk = bare(method(read(JAVA + "MainActivity.java"),
                                         "static void android_tox_callback_file_recv_chunk_cb_method("));
        final int drain = chunk.indexOf(
                "if(HelperFiletransfer.incoming_chunk_of_file_already_here(friend_number,file_number,length)){return;}");
        assertTrue(drain > 0);
        assertTrue(drain < chunk.indexOf("Filetransferf=null;"));
        // A chunk after a cut goes on from the bytes already in the file.
        assertTrue(chunk.contains("HelperGeneric.write_direct_chunk_to_VFS_file("));
        assertFalse(chunk.contains("write_chunk_to_VFS_file(f.path_name"));
    }

    // ---- the stable id (SPEC Tests: stable id across a re-queue, a restart and Retry) ----

    @Test
    public void an_id_given_once_is_passed_again_byte_for_byte()
    {
        final byte[] bytes = FileResume.fileIdBytes(ID);
        assertEquals(FileResume.FILE_ID_BYTES, bytes.length);
        final StringBuilder hex = new StringBuilder();
        for (final byte b : bytes)
        {
            hex.append(String.format("%02X", b & 0xFF));
        }
        assertEquals(ID, hex.toString());
        assertArrayEquals(bytes, FileResume.fileIdBytes(ID.toLowerCase()));
        // Not an id: a first offer makes one.
        assertNull(FileResume.fileIdBytes(null));
        assertNull(FileResume.fileIdBytes(""));
        assertNull(FileResume.fileIdBytes(ID.substring(2)));
        assertNull(FileResume.fileIdBytes(ID.substring(1) + "G"));
    }

    @Test
    public void every_offer_of_a_message_passes_the_id_of_its_first()
    {
        final String helper = read(JAVA + "HelperFiletransfer.java");
        final String start = bare(method(helper, "static void start_outgoing_ft(Message m)"));
        // The id of the message's anchor (or of its row) is passed again; only a first offer makes one.
        final int earlier = start.indexOf("finalStringearlier_id_hex=earlier_outgoing_file_id(m,ft);");
        final int bytes = start.indexOf("finalbyte[]earlier_id=FileResume.fileIdBytes(earlier_id_hex);");
        final int reuse = start.indexOf("if(earlier_id!=null){file_id_buffer.put(earlier_id);file_id_buffer.rewind();}"
                                        + "else{MainActivity.tox_messagev3_get_new_message_id(file_id_buffer);}");
        assertTrue(earlier > 0 && bytes > earlier && reuse > bytes);
        assertEquals("one place makes a new id", start.indexOf("tox_messagev3_get_new_message_id"),
                     start.lastIndexOf("tox_messagev3_get_new_message_id"));
        assertTrue(bare(method(helper, "private static String earlier_outgoing_file_id(")).contains(
                "finalStringanchor=FileResume.reusableFileId(m.ft_id_anchor_hex);"));
        // The anchor is written only when it changes: a first offer, or a message from before anchors.
        assertTrue(start.contains("if(!file_id_buffer_hex.equals(m.ft_id_anchor_hex))"));
        // The routes that offer a file again keep the anchor: the cut, a restart, the receiver's loss and Retry
        // put the message back in the queue, and the queue goes through start_outgoing_ft.
        for (final String route : new String[]{"private static void requeue_cut_off_outgoing(",
                                               "static synchronized void set_all_filetransfers_inactive()",
                                               "static void retry_outgoing_filetransfer(",
                                               "private static boolean outgoing_kill_means_lost_transfer("})
        {
            assertFalse(route, method(helper, route).contains("ft_id_anchor_hex"));
        }
        assertTrue(bare(method(helper, "static synchronized void set_all_filetransfers_inactive()")).contains(
                "requeue_cut_off_outgoing(f,m.id,\"start\");"));
        assertTrue(bare(method(helper, "static void on_friend_connection_lost(")).contains(
                "requeue_cut_off_outgoing(f,mid,\"friendoffline\");"));
        assertTrue(bare(method(helper, "static void queue_and_try_send_outgoing_file(")).contains("start_outgoing_ft(m);"));
    }

    @Test
    public void a_kill_right_after_the_friend_came_back_is_a_lost_transfer_once()
    {
        final long since = 10 * MINUTE;
        // Offered before the friend came back, killed within the window: the receiver lost it.
        assertTrue(FileResume.killMeansLostTransfer(since + 3_000L, since, since - MINUTE, false));
        assertTrue(FileResume.killMeansLostTransfer(since + FileResume.LOST_TRANSFER_WINDOW_MS, since, since - 1, false));
        // Once per connection.
        assertFalse(FileResume.killMeansLostTransfer(since + 3_000L, since, since - MINUTE, true));
        // Offered after the friend came back: the receiver said no.
        assertFalse(FileResume.killMeansLostTransfer(since + 3_000L, since, since + 1_000L, false));
        // Long after the friend came back: the receiver said no.
        assertFalse(FileResume.killMeansLostTransfer(since + FileResume.LOST_TRANSFER_WINDOW_MS + 1, since,
                                                     since - MINUTE, false));
        // Not known.
        assertFalse(FileResume.killMeansLostTransfer(since + 3_000L, 0L, since - MINUTE, false));
        assertFalse(FileResume.killMeansLostTransfer(since + 3_000L, since, 0L, false));
    }

    // ---- the bytes so far across a restart of the receiver (SPEC 1.5) ----

    @Test
    public void the_path_of_a_row_and_of_a_listing_name_the_same_temp_file()
    {
        // A row joins "/tempdir/files/" + "/" + key + "/" and "/" + name; a listing gives the plain path.
        assertEquals("/tempdir/files/KEY/qa.bin", FileResume.tempFileKey("/tempdir/files//KEY//qa.bin"));
        assertEquals("/tempdir/files/KEY/qa.bin", FileResume.tempFileKey("/tempdir/files/KEY/qa.bin"));
        assertEquals("/tempdir/files/KEY", FileResume.tempFileKey("/tempdir/files/KEY/"));
        assertEquals("/", FileResume.tempFileKey("/"));
        assertNotEquals(FileResume.tempFileKey("/tempdir/files/KEY/qa.bin"),
                        FileResume.tempFileKey("/tempdir/files/KEY/qa.bin2"));
        assertNull(FileResume.tempFileKey(null));
    }

    @Test
    public void the_cleanup_at_start_leaves_the_bytes_of_a_file_under_way_and_takes_the_rest() throws IOException
    {
        final File temp = Files.createTempDirectory("khandaq-tempdir").toFile();
        try
        {
            final File friend = new File(temp, "KEY");
            final File other = new File(temp, "OTHER");
            assertTrue(friend.mkdir());
            assertTrue(other.mkdir());
            final byte[] partial = new byte[70_000];
            new Random(15).nextBytes(partial);
            final File underWay = new File(friend, "qa057.bin");
            Files.write(underWay.toPath(), partial);
            Files.write(new File(friend, "cancelled.bin").toPath(), partial);
            Files.write(new File(other, "old.bin").toPath(), partial);
            Files.write(new File(temp, "loose.tmp").toPath(), partial);

            // As the row of the transfer the start keeps names it (path_name + "/" + file_name).
            final String rowPath = temp.getAbsolutePath() + "/" + "/KEY/" + "/" + "qa057.bin";
            final Set<String> kept = new HashSet<>(Collections.singletonList(FileResume.tempFileKey(rowPath)));
            assertTrue(HelperGeneric.vfs_deleteFilesAndFilesSubDirectories_real(temp.getAbsolutePath() + "/", kept));

            assertArrayEquals(partial, Files.readAllBytes(underWay.toPath()));
            assertFalse(new File(friend, "cancelled.bin").exists());
            assertFalse(other.exists());
            assertFalse(new File(temp, "loose.tmp").exists());

            // Nothing under way: the dir is emptied as before.
            assertFalse(HelperGeneric.vfs_deleteFilesAndFilesSubDirectories_real(temp.getAbsolutePath() + "/",
                                                                                 Collections.<String>emptySet()));
            assertFalse(friend.exists());
        }
        finally
        {
            HelperGeneric.vfs_deleteFilesAndFilesSubDirectories_real(temp.getAbsolutePath() + "/");
            assertTrue(temp.delete());
        }
    }

    @Test
    public void the_cleanup_at_start_asks_for_the_files_the_start_keeps()
    {
        final String cleanup = bare(method(read(JAVA + "HelperGeneric.java"), "public static void cleanup_temp_dirs()"));
        assertTrue(cleanup.contains("finalSet<String>kept=HelperFiletransfer.incoming_files_kept_after_restart();"));
        assertTrue(cleanup.contains("vfs_deleteFilesAndFilesSubDirectories_vfs(VFS_PREFIX+VFS_TMP_FILE_DIR+\"/\",kept);"));
        assertTrue(cleanup.contains("vfs_deleteFilesAndFilesSubDirectories_real(VFS_PREFIX+VFS_TMP_FILE_DIR+\"/\",kept);"));
        // The rows that could not be read leave the dir: the bytes are worth more than a tidy dir.
        assertTrue(cleanup.indexOf("if(kept==null)") < cleanup.indexOf("vfs_deleteFilesAndFilesSubDirectories_vfs("));

        // The same rows, by the same rule, as the start keeps (set_all_filetransfers_inactive). Both methods are
        // synchronized (the method lookups below are by their full signatures): that one cancels every row for a
        // moment, and the cleanup reads on its own thread.
        final String helper = read(JAVA + "HelperFiletransfer.java");
        final String keptFiles = bare(method(helper,
                                             "static synchronized Set<String> incoming_files_kept_after_restart()"));
        assertTrue(keptFiles.contains("for(Filetransferf:transfers_left_from_last_run())"));
        assertTrue(keptFiles.contains("(f.direction==TRIFA_FT_DIRECTION_INCOMING.value)"));
        assertTrue(keptFiles.contains("resumable_after_restart(f,message_of_filetransfer(f))"));
        assertTrue(keptFiles.contains("kept.add(FileResume.tempFileKey(f.path_name+\"/\"+f.file_name));"));
        final String start = bare(method(helper, "static synchronized void set_all_filetransfers_inactive()"));
        assertTrue(start.contains("finalList<Filetransfer>rows=transfers_left_from_last_run();"));
        assertTrue(start.contains("if(resumable_after_restart(f,m))"));
    }

    // ---- the sender reads at the requested position (SPEC Tests) ----

    @Test
    public void a_chunk_request_after_a_seek_reads_the_bytes_at_its_position() throws IOException
    {
        final File file = File.createTempFile("khandaq-resume", ".bin");
        try
        {
            final byte[] data = new byte[300_000];
            new Random(57).nextBytes(data);
            Files.write(file.toPath(), data);
            for (final long position : new long[]{1L, 4096L, 123_457L, data.length - 1371L})
            {
                final int length = (int) Math.min(1371L, data.length - position);
                final byte[] chunk = HelperGeneric.read_chunk_from_SD_file(file.getAbsolutePath(), position, length,
                                                                           true);
                assertArrayEquals("at " + position, Arrays.copyOfRange(data, (int) position, (int) position + length),
                                  chunk);
            }
            // Out of order, as after a seek back.
            final byte[] back = HelperGeneric.read_chunk_from_SD_file(file.getAbsolutePath(), 7L, 10, true);
            assertArrayEquals(Arrays.copyOfRange(data, 7, 17), back);
        }
        finally
        {
            assertTrue(file.delete());
        }
    }

    @Test
    public void the_chunk_request_callback_reads_at_its_position()
    {
        final String request = bare(method(read(JAVA + "MainActivity.java"),
                                           "static void android_tox_callback_file_chunk_request_cb_method("));
        assertTrue(request.contains("HelperGeneric.read_chunk_from_SD_file(fname,position,file_chunk_length,true);"));
        assertTrue(request.contains("tox_file_send_chunk(friend_number,file_number,position,file_chunk,"));
    }

    // ---- the entry while the connection is gone (SPEC Tests: waiting, never failed) ----

    @Test
    public void ours_waits_for_the_connection_and_never_fails()
    {
        // Under way and the friend gone.
        assertEquals(FileResume.Wait.CONNECTION, FileResume.outgoingWait(false, true, false));
        // Back in the queue with bytes sent, before its next offer, whether or not the friend is back.
        assertEquals(FileResume.Wait.CONNECTION, FileResume.outgoingWait(false, false, true));
        assertEquals(FileResume.Wait.CONNECTION, FileResume.outgoingWait(true, false, true));
        // Under way with the friend there, or never sent: nothing to say.
        assertEquals(FileResume.Wait.NONE, FileResume.outgoingWait(true, true, false));
        assertEquals(FileResume.Wait.NONE, FileResume.outgoingWait(false, false, false));
        for (final boolean online : new boolean[]{false, true})
        {
            for (final boolean underWay : new boolean[]{false, true})
            {
                for (final boolean cutOff : new boolean[]{false, true})
                {
                    assertNotEquals(FileResume.Wait.STOPPED, FileResume.outgoingWait(online, underWay, cutOff));
                }
            }
        }
    }

    @Test
    public void theirs_waits_for_the_connection_and_reads_as_stopped_only_once_back_without_an_offer()
    {
        // The sender gone: waits, however long.
        for (final long onlineFor : new long[]{-1L, 0L, 10 * MINUTE, Long.MAX_VALUE})
        {
            assertEquals(FileResume.Wait.CONNECTION, FileResume.incomingWait(false, onlineFor, true, false));
            assertEquals(FileResume.Wait.CONNECTION, FileResume.incomingWait(false, onlineFor, true, true));
        }
        // Back, and the next offer not here yet: still waits for a while.
        assertEquals(FileResume.Wait.CONNECTION, FileResume.incomingWait(true, 0L, true, false));
        assertEquals(FileResume.Wait.CONNECTION,
                     FileResume.incomingWait(true, FileResume.INCOMING_WAIT_AFTER_ONLINE_MS - 1, true, false));
        // Back that long and offered nothing (an older sender): stopped, with its Retry.
        assertEquals(FileResume.Wait.STOPPED,
                     FileResume.incomingWait(true, FileResume.INCOMING_WAIT_AFTER_ONLINE_MS, true, false));
        // A transfer carries it, or it was never under way: the usual words.
        assertEquals(FileResume.Wait.NONE, FileResume.incomingWait(true, 0L, true, true));
        assertEquals(FileResume.Wait.NONE, FileResume.incomingWait(false, 0L, false, false));
    }

    @Test
    public void the_entry_says_it_waits_before_any_failed_words()
    {
        final String entry = bare(method(read(JAVA + "AirFileEntry.java"), "static void applyDirectState("));
        final int wait = entry.indexOf("HelperFiletransfer.direct_file_wait(m,ft,outgoing,online);");
        final int words = entry.indexOf("if(wait==FileResume.Wait.CONNECTION){e.note=context.getString("
                                        + "R.string.air_file_waits_connection);e.bar=e.total>0;"
                                        + "e.cancel=view->calls.onCancel();}elseswitch(e.state)");
        assertTrue(wait > 0);
        assertTrue("the waiting words replace every other, failed among them", words > wait);
        assertTrue(entry.indexOf("caseFAILED:") > words);
    }

    @Test
    public void the_waiting_words_are_in_every_language_of_the_network_words()
    {
        final String english = "Waiting for the connection";
        assertEquals(english, stringOf("values", "air_file_waits_connection"));
        // The translations themselves live in the resources only; here: there is one, and it is not English.
        for (final String folder : new String[]{"values-ru", "values-ar", "values-zh-rCN"})
        {
            final String translated = stringOf(folder, "air_file_waits_connection");
            assertFalse(folder, translated.trim().isEmpty());
            assertNotEquals(folder, english, translated);
        }
    }

    private static String stringOf(final String folder, final String name)
    {
        final String strings = read("src/main/res/" + folder + "/strings_air_files.xml");
        final String open = "<string name=\"" + name + "\">";
        final int start = strings.indexOf(open);
        assertTrue(folder + " " + name, start >= 0);
        return strings.substring(start + open.length(), strings.indexOf("</string>", start));
    }

    private static String bare(final String source)
    {
        return source.replaceAll("\\s+", "");
    }

    /** The text of the method that starts with signature, up to its closing brace at class level. */
    private static String method(final String source, final String signature)
    {
        final int start = source.indexOf(signature);
        assertTrue(signature, start >= 0);
        final int end = source.indexOf("\n    }\n", start);
        assertTrue(signature, end > start);
        return source.substring(start, end);
    }

    private static String read(final String name)
    {
        // Gradle runs unit tests from the module directory; an IDE may use the project root.
        for (final String root : new String[]{"", "app/"})
        {
            final File f = new File(root + name);
            if (f.isFile())
            {
                try
                {
                    return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
                }
                catch (IOException e)
                {
                    throw new AssertionError(name, e);
                }
            }
        }
        throw new AssertionError(name + " not found from " + new File("").getAbsolutePath());
    }
}
