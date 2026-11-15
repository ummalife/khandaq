package com.zoffcc.applications.trifa;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * KHANDAQ (0.2.56): a file picked to send is copied into the outgoing folder before toxcore may offer it, and
 * for a large video that copy takes many seconds. Its entry stands in the chat from Send on, its row flagged
 * {@code ft_outgoing_preparing} (MessageListActivity.prepare_outgoing_file); this class holds what the copy
 * does meanwhile and the rules around it:
 * <ul>
 * <li>the copy is written to a hidden part file next to its reserved place and moved onto that place only once
 * it is complete, so the path the row names never holds half a file ({@link #copy});</li>
 * <li>no transfer starts from a row whose copy is not complete ({@link #isReadyToOffer});</li>
 * <li>how far each running copy is, and its cancel, by message id ({@link Registry});</li>
 * <li>a caption goes once the copies of its Send have ended, and only when one of their files was queued
 * ({@link Batch}).</li>
 * </ul>
 * Nothing here touches Android, so the JVM tests run it as the phone does.
 */
final class OutgoingFilePreparation
{
    /** The copies running in this process. */
    static final Registry ACTIVE = new Registry();

    /** Bytes read and written per step; a cancel is seen between two steps. */
    static final int CHUNK_BYTES = 256 * 1024;

    /** While a copy runs, its entry is drawn again at most this often. */
    static final long PROGRESS_INTERVAL_MS = 200L;

    private static final String PART_SUFFIX = ".part";

    private OutgoingFilePreparation()
    {
    }

    enum Outcome
    {
        DONE,
        CANCELLED,
        FAILED
    }

    /** How a copy ended, and the bytes it wrote. */
    static final class Result
    {
        final Outcome outcome;
        final long bytes;

        Result(final Outcome outcome, final long bytes)
        {
            this.outcome = outcome;
            this.bytes = bytes;
        }
    }

    /** Told the bytes copied so far after every step, on the copying thread. */
    interface ProgressListener
    {
        void copied(long bytes);
    }

    /**
     * The part file of a copy: in the copy's own folder, hidden and named after its message. A copy's name never
     * starts with a dot (HelperFiletransfer.outgoing_copy_file_name), so the two cannot meet, and the part's name
     * stays short however long the picked name is.
     */
    static File partFileFor(final File copy, final long messageId)
    {
        return new File(copy.getParentFile(), "." + messageId + PART_SUFFIX);
    }

    /**
     * Whether toxcore may be offered this outgoing file: only once its copy is complete. The row's flag says so
     * across restarts; a copy of this process that still runs says so before the flag is cleared.
     */
    static boolean isReadyToOffer(final boolean preparingFlag, final boolean copyRunning)
    {
        return !preparingFlag && !copyRunning;
    }

    /**
     * Copies {@code in} into {@code part}, then moves {@code part} onto {@code copy}, the place reserved for it
     * (created empty when the copy was claimed). Any end but DONE leaves no part file; {@code copy} is then the
     * caller's to remove. A cancel stops the copy between two steps; a copy longer than {@code limitBytes}, an
     * empty one and one that cannot be read or written fail.
     */
    static Result copy(final InputStream in, final File part, final File copy, final long limitBytes,
                       final Job job, final ProgressListener progress)
    {
        if (job.isCancelled())
        {
            return new Result(Outcome.CANCELLED, 0L);
        }
        boolean moved = false;
        long copied = 0L;
        try
        {
            try (OutputStream out = new FileOutputStream(part))
            {
                final byte[] buffer = new byte[CHUNK_BYTES];
                int read;
                while ((read = in.read(buffer)) != -1)
                {
                    if (job.isCancelled())
                    {
                        return new Result(Outcome.CANCELLED, copied);
                    }
                    if (copied + read > limitBytes)
                    {
                        return new Result(Outcome.FAILED, copied);
                    }
                    out.write(buffer, 0, read);
                    copied += read;
                    job.setCopiedBytes(copied);
                    if (progress != null)
                    {
                        progress.copied(copied);
                    }
                }
            }
            if (job.isCancelled())
            {
                return new Result(Outcome.CANCELLED, copied);
            }
            if (copied < 1L)
            {
                return new Result(Outcome.FAILED, copied);
            }
            // One rename in the same folder: the reserved place goes from empty to complete, never in between.
            Files.move(part.toPath(), copy.toPath(), StandardCopyOption.ATOMIC_MOVE);
            moved = true;
            return new Result(Outcome.DONE, copied);
        }
        catch (IOException | RuntimeException e)
        {
            return new Result(Outcome.FAILED, copied);
        }
        finally
        {
            if (!moved)
            {
                //noinspection ResultOfMethodCallIgnored
                part.delete();
            }
        }
    }

    /** One copy: how far it is, of how much, and whether it was cancelled. */
    static final class Job
    {
        final long messageId;
        final long expectedBytes;
        private final Batch batch;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private volatile long copiedBytes;

        Job(final long messageId, final long expectedBytes, final Batch batch)
        {
            this.messageId = messageId;
            this.expectedBytes = expectedBytes;
            this.batch = batch;
        }

        long copiedBytes()
        {
            return copiedBytes;
        }

        void setCopiedBytes(final long bytes)
        {
            copiedBytes = bytes;
        }

        boolean isCancelled()
        {
            return cancelled.get();
        }

        void cancel()
        {
            cancelled.set(true);
        }

        /** Its file is queued for the transfer: the caption of its Send may follow it. */
        void markQueued()
        {
            if (batch != null)
            {
                batch.fileQueued();
            }
        }
    }

    /** The copies that run, by message id. */
    static final class Registry
    {
        private final Map<Long, Job> jobs = new ConcurrentHashMap<>();
        private final AtomicLong lastSentMs = new AtomicLong();
        private volatile Batch openBatch;

        /**
         * The sent time of a new entry: now, or one millisecond after the entry made before when now is not
         * later. The entries of one Send are written within the same millisecond at times, and both the chat
         * and the transfer queue order them by this time.
         */
        long sentTimestamp(final long nowMs)
        {
            return lastSentMs.accumulateAndGet(nowMs, (last, now) -> Math.max(now, last + 1L));
        }

        /** A copy begins for this message; it belongs to the Send being dispatched, if there is one. */
        Job begin(final long messageId, final long expectedBytes)
        {
            final Batch batch = openBatch;
            final Job job = new Job(messageId, expectedBytes, batch);
            if (batch != null)
            {
                batch.fileAdded();
            }
            jobs.put(messageId, job);
            return job;
        }

        Job get(final long messageId)
        {
            return jobs.get(messageId);
        }

        boolean isRunning(final long messageId)
        {
            return jobs.containsKey(messageId);
        }

        /** Asks the copy of this message to stop; false when none runs. */
        boolean cancel(final long messageId)
        {
            final Job job = jobs.get(messageId);
            if (job == null)
            {
                return false;
            }
            job.cancel();
            return true;
        }

        /** The copy of this message is over, whichever way it ended. */
        void end(final long messageId)
        {
            jobs.remove(messageId);
        }

        /** The copies begun until {@link #closeBatch} belong to one Send. */
        Batch openBatch()
        {
            final Batch batch = new Batch();
            openBatch = batch;
            return batch;
        }

        void closeBatch(final Batch batch)
        {
            if (openBatch == batch)
            {
                openBatch = null;
            }
        }
    }

    /** The files of one Send, for the caption that follows them. */
    static final class Batch
    {
        private final AtomicInteger files = new AtomicInteger();
        private final AtomicInteger queued = new AtomicInteger();

        void fileAdded()
        {
            files.incrementAndGet();
        }

        void fileQueued()
        {
            queued.incrementAndGet();
        }

        /**
         * Whether the caption goes, asked once the copies of its Send have ended. It goes when one of their
         * files was queued; when every one of them was cancelled or failed, it is dropped with them. A Send
         * whose files needed no copy (Saved Messages, a file refused at once) sends it as before.
         */
        boolean captionGoes()
        {
            return files.get() == 0 || queued.get() > 0;
        }
    }

    /** Whether a running copy's entry is drawn again now: the first time, then at most every interval. */
    static final class ProgressThrottle
    {
        private boolean drawn;
        private long lastMs;

        boolean due(final long nowMs)
        {
            if (drawn && (nowMs - lastMs) < PROGRESS_INTERVAL_MS)
            {
                return false;
            }
            drawn = true;
            lastMs = nowMs;
            return true;
        }
    }
}
