package com.zoffcc.applications.trifa;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.zoffcc.applications.trifa.HelperFiletransfer.claim_outgoing_copy;
import static com.zoffcc.applications.trifa.HelperFiletransfer.delete_outgoing_copy;
import static com.zoffcc.applications.trifa.HelperFiletransfer.delete_unfinished_outgoing_copy;
import static com.zoffcc.applications.trifa.HelperFiletransfer.outgoing_copy_file_name;
import static com.zoffcc.applications.trifa.OutgoingFilePreparation.Batch;
import static com.zoffcc.applications.trifa.OutgoingFilePreparation.CHUNK_BYTES;
import static com.zoffcc.applications.trifa.OutgoingFilePreparation.Job;
import static com.zoffcc.applications.trifa.OutgoingFilePreparation.Outcome;
import static com.zoffcc.applications.trifa.OutgoingFilePreparation.PROGRESS_INTERVAL_MS;
import static com.zoffcc.applications.trifa.OutgoingFilePreparation.ProgressThrottle;
import static com.zoffcc.applications.trifa.OutgoingFilePreparation.Registry;
import static com.zoffcc.applications.trifa.OutgoingFilePreparation.Result;
import static com.zoffcc.applications.trifa.OutgoingFilePreparation.copy;
import static com.zoffcc.applications.trifa.OutgoingFilePreparation.isReadyToOffer;
import static com.zoffcc.applications.trifa.OutgoingFilePreparation.partFileFor;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * KHANDAQ (0.2.56): a large file picked from the gallery and sent.
 *
 * The owner sent a big video: after Send nothing showed for a long time, then the message came. The file is
 * copied into the outgoing folder before toxcore may offer it, and its entry used to be made only after that
 * copy. The entry now stands from Send on, with the copy's own bar and a Cancel, which moved two rules from
 * timing into state: no transfer starts from a row whose copy is not complete (the service loop picked up any
 * file row older than 1.5 s), and the path the row names never holds half a file (the copy is written beside
 * it and moved onto it once whole). A cancelled or failed copy leaves no file behind.
 *
 * The outgoing folder is passed in: MainActivity, which holds it, cannot be loaded in a JVM test.
 */
public class OutgoingFilePreparationTest
{
    private static final long LIMIT = 200L * 1024L * 1024L;

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private File outgoing;
    /** As MainActivity sets it: with a slash at the end. */
    private String outgoingDir;

    @Before
    public void makeTheOutgoingFolder()
    {
        outgoing = new File(folder.getRoot(), "outgoing");
        assertTrue(outgoing.mkdirs());
        outgoingDir = outgoing.getAbsolutePath() + "/";
    }

    /** The reserved place of a copy, as Send leaves it: empty, in a folder of its own. */
    private File reserve(final String name) throws IOException
    {
        final File copy = claim_outgoing_copy(outgoingDir, "9ded", name);
        assertNotNull(copy);
        assertEquals(0L, copy.length());
        return copy;
    }

    private static byte[] content(final int length)
    {
        final byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++)
        {
            bytes[i] = (byte) (i * 31 + 7);
        }
        return bytes;
    }

    private static Job job(final long messageId, final long expected)
    {
        return new Job(messageId, expected, null);
    }

    private static List<String> namesIn(final File dir)
    {
        final String[] names = dir.list();
        final List<String> list = names == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(names));
        java.util.Collections.sort(list);
        return list;
    }

    // ---- the copy ----

    @Test
    public void a_complete_copy_lands_on_its_reserved_place_and_no_part_stays() throws IOException
    {
        final File copy = reserve("video.mp4");
        final File part = partFileFor(copy, 42L);
        final byte[] data = content(3 * CHUNK_BYTES + 123);
        final Job job = job(42L, data.length);
        final List<Long> told = new ArrayList<>();

        final Result result = copy(new ByteArrayInputStream(data), part, copy, LIMIT, job, told::add);

        assertEquals(Outcome.DONE, result.outcome);
        assertEquals(data.length, result.bytes);
        assertArrayEquals(data, Files.readAllBytes(copy.toPath()));
        assertFalse(part.exists());
        assertEquals(Arrays.asList("video.mp4"), namesIn(copy.getParentFile()));
        assertEquals(data.length, job.copiedBytes());
        // The bar is told after every step, and it only ever moves on, up to the whole file.
        assertEquals(4, told.size());
        for (int i = 1; i < told.size(); i++)
        {
            assertTrue(told.get(i) > told.get(i - 1));
        }
        assertEquals(Long.valueOf(data.length), told.get(told.size() - 1));
    }

    @Test
    public void the_reserved_place_stays_empty_until_the_copy_is_complete() throws IOException
    {
        // Readers take the row's path as the file (the media preview, the playable check, toxcore's chunks):
        // half a video there is a broken video. The bytes go to the part file until the end.
        final File copy = reserve("video.mp4");
        final File part = partFileFor(copy, 7L);
        final byte[] data = content(2 * CHUNK_BYTES);
        final List<Long> seenAtCopy = new ArrayList<>();

        final Result result = copy(new ByteArrayInputStream(data), part, copy, LIMIT, job(7L, data.length),
                                   bytes -> {
                                       seenAtCopy.add(copy.length());
                                       assertEquals(bytes, part.length());
                                   });

        assertEquals(Outcome.DONE, result.outcome);
        assertEquals(Arrays.asList(0L, 0L), seenAtCopy);
        assertEquals(data.length, copy.length());
    }

    @Test
    public void a_cancel_stops_the_copy_and_nothing_of_it_stays() throws IOException
    {
        final File copy = reserve("video.mp4");
        final File part = partFileFor(copy, 9L);
        final byte[] data = content(5 * CHUNK_BYTES);
        final Job job = job(9L, data.length);

        final Result result = copy(new ByteArrayInputStream(data), part, copy, LIMIT, job, bytes -> job.cancel());

        assertEquals(Outcome.CANCELLED, result.outcome);
        assertTrue(result.bytes < data.length);
        assertFalse(part.exists());
        // The reserved place is the caller's to remove, as Cancel does; then the copy's folder goes too.
        assertEquals(0L, copy.length());
        assertTrue(delete_outgoing_copy(copy.getAbsolutePath(), outgoingDir));
        assertEquals(new ArrayList<String>(), namesIn(outgoing));
    }

    @Test
    public void a_copy_cancelled_before_its_turn_reads_and_writes_nothing() throws IOException
    {
        // Several files of one Send copy one after another; Cancel on one that waits for its turn.
        final File copy = reserve("photo.jpg");
        final File part = partFileFor(copy, 11L);
        final Job job = job(11L, 1000L);
        job.cancel();
        final InputStream untouched = new InputStream()
        {
            @Override
            public int read()
            {
                fail("a cancelled copy must not read its source");
                return -1;
            }
        };

        final Result result = copy(untouched, part, copy, LIMIT, job, bytes -> fail("nothing was copied"));

        assertEquals(Outcome.CANCELLED, result.outcome);
        assertFalse(part.exists());
    }

    @Test
    public void a_source_that_breaks_off_fails_and_no_part_stays() throws IOException
    {
        // The provider dies, or the storage is full, in the middle of the copy.
        final File copy = reserve("video.mp4");
        final File part = partFileFor(copy, 5L);
        final byte[] first = content(CHUNK_BYTES);
        final InputStream breaking = new InputStream()
        {
            private int at;

            @Override
            public int read() throws IOException
            {
                if (at < first.length)
                {
                    return first[at++] & 0xff;
                }
                throw new IOException("provider gone");
            }
        };

        final Result result = copy(breaking, part, copy, LIMIT, job(5L, 10L * CHUNK_BYTES), null);

        assertEquals(Outcome.FAILED, result.outcome);
        assertFalse(part.exists());
        assertEquals(0L, copy.length());
    }

    @Test
    public void an_empty_source_fails() throws IOException
    {
        // Tox cannot send a file of 0 bytes; a source that gives nothing is no file to send.
        final File copy = reserve("empty.bin");
        final File part = partFileFor(copy, 3L);

        final Result result = copy(new ByteArrayInputStream(new byte[0]), part, copy, LIMIT, job(3L, 100L), null);

        assertEquals(Outcome.FAILED, result.outcome);
        assertFalse(part.exists());
        assertEquals(0L, copy.length());
    }

    @Test
    public void a_source_longer_than_the_limit_fails_before_writing_past_it() throws IOException
    {
        // The provider said a size within the limit at Send and then gives more.
        final File copy = reserve("video.mp4");
        final File part = partFileFor(copy, 4L);
        final byte[] data = content(3 * CHUNK_BYTES);
        final List<Long> told = new ArrayList<>();

        final Result result = copy(new ByteArrayInputStream(data), part, copy, CHUNK_BYTES + 10L, job(4L, 10L),
                                   told::add);

        assertEquals(Outcome.FAILED, result.outcome);
        assertEquals(Arrays.asList((long) CHUNK_BYTES), told);
        assertFalse(part.exists());
        assertEquals(0L, copy.length());
    }

    // ---- the part file ----

    @Test
    public void the_part_file_is_hidden_beside_its_copy_and_never_a_copy_name()
    {
        final File copy = new File(new File(outgoing, "9ded"), "video.mp4");
        final File part = partFileFor(copy, 1234L);

        assertEquals(copy.getParentFile(), part.getParentFile());
        assertEquals(".1234.part", part.getName());
        // A copy's name never starts with a dot, so no copy can take the part's place, nor the part a copy's.
        assertFalse(part.getName().equals(outgoing_copy_file_name(part.getName(), "9ded")));
        // Short whatever the picked name: a 255-byte name with ".part" after it would be past the limit.
        final File longCopy = new File(copy.getParentFile(), new String(new char[255]).replace('\0', 'a'));
        assertEquals(".1234.part", partFileFor(longCopy, 1234L).getName());
    }

    @Test
    public void a_copy_left_unfinished_goes_with_its_part_and_its_folder() throws IOException
    {
        // The app was stopped in the middle of a copy: at the next start the half file and its row go.
        final File copy = reserve("video.mp4");
        final File part = partFileFor(copy, 77L);
        Files.write(part.toPath(), content(1000));

        delete_unfinished_outgoing_copy(copy.getAbsolutePath(), 77L, outgoingDir);

        assertFalse(part.exists());
        assertFalse(copy.exists());
        assertEquals(new ArrayList<String>(), namesIn(outgoing));
    }

    @Test
    public void an_unfinished_copy_outside_the_outgoing_folder_is_left_alone() throws IOException
    {
        final File elsewhere = folder.newFolder("elsewhere");
        final File copy = new File(elsewhere, "video.mp4");
        assertTrue(copy.createNewFile());
        final File part = partFileFor(copy, 8L);
        Files.write(part.toPath(), content(10));

        delete_unfinished_outgoing_copy(copy.getAbsolutePath(), 8L, outgoingDir);

        assertTrue(copy.exists());
        assertTrue(part.exists());
    }

    // ---- may it go ----

    @Test
    public void only_a_complete_copy_is_offered_to_toxcore()
    {
        assertTrue(isReadyToOffer(false, false));
        // The row's flag, which outlives the process (the service loop reads rows, not this process's copies).
        assertFalse(isReadyToOffer(true, false));
        // A copy still running here.
        assertFalse(isReadyToOffer(false, true));
        assertFalse(isReadyToOffer(true, true));
    }

    // ---- the copies that run ----

    @Test
    public void a_running_copy_is_found_by_its_message_until_it_ends()
    {
        final Registry registry = new Registry();
        final Job job = registry.begin(21L, 5000L);

        assertSame(job, registry.get(21L));
        assertTrue(registry.isRunning(21L));
        assertEquals(5000L, job.expectedBytes);
        assertEquals(0L, job.copiedBytes());
        assertNull(registry.get(22L));

        registry.end(21L);
        assertFalse(registry.isRunning(21L));
        assertNull(registry.get(21L));
    }

    @Test
    public void cancel_reaches_a_running_copy_and_says_when_there_is_none()
    {
        final Registry registry = new Registry();
        final Job job = registry.begin(31L, 10L);

        assertTrue(registry.cancel(31L));
        assertTrue(job.isCancelled());
        // A row with no copy running here (left from before a restart) is cleaned up by the caller itself.
        assertFalse(registry.cancel(32L));
    }

    // ---- the caption of a Send ----

    @Test
    public void a_caption_follows_its_files_once_one_of_them_is_queued()
    {
        final Registry registry = new Registry();
        final Batch batch = registry.openBatch();
        final Job first = registry.begin(1L, 10L);
        final Job second = registry.begin(2L, 10L);
        registry.closeBatch(batch);

        // While the copies run the caption waits (it is asked only after them, in order).
        assertFalse(batch.captionGoes());
        second.cancel();
        first.markQueued();
        assertTrue(batch.captionGoes());
    }

    @Test
    public void a_caption_is_dropped_when_none_of_its_files_was_queued()
    {
        final Registry registry = new Registry();
        final Batch batch = registry.openBatch();
        registry.begin(1L, 10L).cancel();
        registry.begin(2L, 10L);
        registry.closeBatch(batch);

        // One cancelled, one failed: nothing went, so neither does the text written for them.
        assertFalse(batch.captionGoes());
    }

    @Test
    public void a_send_without_copies_keeps_its_caption()
    {
        // Saved Messages store the file at once, and a file refused at Send has no copy: as before, the caption goes.
        final Registry registry = new Registry();
        final Batch batch = registry.openBatch();
        registry.closeBatch(batch);

        assertTrue(batch.captionGoes());
    }

    @Test
    public void copies_begun_outside_a_send_belong_to_none()
    {
        final Registry registry = new Registry();
        final Batch earlier = registry.openBatch();
        registry.closeBatch(earlier);
        registry.begin(5L, 10L);
        // Closing an older batch again does not close a newer one.
        final Batch later = registry.openBatch();
        registry.closeBatch(earlier);
        registry.begin(6L, 10L);
        registry.closeBatch(later);

        assertTrue(earlier.captionGoes());
        assertFalse(later.captionGoes());
    }

    // ---- the order of the entries ----

    @Test
    public void the_entries_of_one_send_get_later_and_later_sent_times()
    {
        final Registry registry = new Registry();

        assertEquals(1000L, registry.sentTimestamp(1000L));
        // Written within the same millisecond, or with the clock set back: each still after the one before.
        assertEquals(1001L, registry.sentTimestamp(1000L));
        assertEquals(1002L, registry.sentTimestamp(999L));
        // A later time is taken as it is.
        assertEquals(5000L, registry.sentTimestamp(5000L));
    }

    // ---- the bar while it runs ----

    @Test
    public void the_entry_is_drawn_at_once_then_at_most_every_interval()
    {
        final ProgressThrottle throttle = new ProgressThrottle();

        assertTrue(throttle.due(1000L));
        assertFalse(throttle.due(1000L + PROGRESS_INTERVAL_MS - 1L));
        assertTrue(throttle.due(1000L + PROGRESS_INTERVAL_MS));
        assertFalse(throttle.due(1000L + PROGRESS_INTERVAL_MS + 1L));
    }
}
