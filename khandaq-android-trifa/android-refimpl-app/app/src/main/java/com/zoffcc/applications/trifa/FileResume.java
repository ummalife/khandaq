package com.zoffcc.applications.trifa;

import java.util.Locale;

/**
 * KHANDAQ (0.2.57, SPEC-network part 1): a 1:1 file that a network change cut off goes on from where it stopped.
 * Standard toxcore resume, so that it works with every client that does the same: the sender offers the file
 * again with the same Tox file id, kind, size and name, and the receiver, which knows the file by friend, id and
 * size, seeks to the bytes it already holds before it accepts.
 * <p>
 * The rules are kept here, free of Android and of toxcore, so that the unit tests hold them as the phone runs
 * them; the transfer code (HelperFiletransfer and the file callbacks of MainActivity) asks and acts.
 */
final class FileResume
{
    /** Bytes of a Tox file id (ToxVars.TOX_FILE_ID_LENGTH). */
    static final int FILE_ID_BYTES = 32;

    /**
     * A transfer killed by the receiver this soon after the friend came back, and offered before that, was lost
     * by the receiver: its app restarted while toxcore kept the FTV2 transfer of ours alive (break_files leaves
     * FTV2 transfers to it), and its new toxcore answers our next data with a kill. Offered again once.
     */
    static final long LOST_TRANSFER_WINDOW_MS = 20_000L;

    /**
     * A file of theirs that the cut stopped and that its sender has not offered again this long after coming
     * back reads as stopped: an older sender offers it again with a new id, or not at all (SPEC 1.7). Its bytes
     * stay, so a later offer of the same file still goes on from them.
     */
    static final long INCOMING_WAIT_AFTER_ONLINE_MS = 120_000L;

    private FileResume()
    {
    }

    // ---- the file id ----

    /** The id an earlier offer gave this file, in upper case; null when there is none toxcore could take again. */
    static String reusableFileId(final String hex)
    {
        if (hex == null || hex.length() != FILE_ID_BYTES * 2)
        {
            return null;
        }
        for (int i = 0; i < hex.length(); i++)
        {
            if (Character.digit(hex.charAt(i), 16) < 0)
            {
                return null;
            }
        }
        return hex.toUpperCase(Locale.ROOT);
    }

    /** The 32 bytes of a file id, or null when the text is not one. */
    static byte[] fileIdBytes(final String hex)
    {
        final String id = reusableFileId(hex);
        if (id == null)
        {
            return null;
        }
        final byte[] out = new byte[FILE_ID_BYTES];
        for (int i = 0; i < FILE_ID_BYTES; i++)
        {
            out[i] = (byte) ((Character.digit(id.charAt(2 * i), 16) << 4) | Character.digit(id.charAt(2 * i + 1), 16));
        }
        return out;
    }

    /** The same file of the same friend: the same id, in either case, and the same size. */
    static boolean sameFile(final String knownIdHex, final long knownSize, final String offeredIdHex,
                            final long offeredSize)
    {
        final String known = reusableFileId(knownIdHex);
        return known != null && known.equals(reusableFileId(offeredIdHex)) && knownSize > 0
               && knownSize == offeredSize;
    }

    /** The size an incoming file entry names in its text ("name", a new line, "123 bytes"), or -1. */
    static long sizeFromIncomingText(final String text)
    {
        if (text == null)
        {
            return -1L;
        }
        final int lineStart = text.lastIndexOf('\n') + 1;
        final String line = text.substring(lineStart).trim();
        final int space = line.indexOf(' ');
        if (space <= 0 || !line.substring(space + 1).trim().equals("bytes"))
        {
            return -1L;
        }
        try
        {
            final long size = Long.parseLong(line.substring(0, space));
            return size >= 0 ? size : -1L;
        }
        catch (NumberFormatException e)
        {
            return -1L;
        }
    }

    // ---- the receiver ----

    enum Step
    {
        /** No entry of this friend holds the file: a new entry, as before. */
        NEW_MESSAGE,
        /** The entry and its partial bytes stay; toxcore is told where to go on before the accept. */
        RESUME,
        /** The entry stays, its partial bytes do not: the file comes again from its first byte. */
        FROM_START,
        /** The file is whole here already: the last byte comes again so that both ends finish, silently. */
        ALREADY_HERE
    }

    static final class Decision
    {
        final Step step;
        /** The position for tox_file_seek before the accept, or -1 for none. */
        final long seekTo;

        Decision(final Step step, final long seekTo)
        {
            this.step = step;
            this.seekTo = seekTo;
        }

        @Override
        public String toString()
        {
            return step + "@" + seekTo;
        }
    }

    /**
     * What an incoming 1:1 offer does (SPEC 1.4). {@code found}: an entry of this friend has the same id and size;
     * {@code complete}: that entry's file is whole; {@code partialBytes}: the length of its partial data on disk,
     * -1 when that is gone.
     */
    static Decision decide(final boolean found, final boolean complete, final long partialBytes, final long size)
    {
        if (!found)
        {
            return new Decision(Step.NEW_MESSAGE, -1L);
        }
        if (complete)
        {
            return new Decision(Step.ALREADY_HERE, size >= 1 ? size - 1 : -1L);
        }
        if (partialBytes > 0 && partialBytes < size)
        {
            return new Decision(Step.RESUME, partialBytes);
        }
        if (partialBytes == size && size >= 1)
        {
            // Every byte is here and the end was not: the last one comes again and the transfer ends as any other.
            return new Decision(Step.RESUME, size - 1);
        }
        return new Decision(Step.FROM_START, -1L);
    }

    /**
     * Whether a recognised offer is accepted at once (SPEC 1.4): when the entry was accepted before, or the
     * automatic download allows it now. Never one the person declined or cancelled: that waits for a tap.
     */
    static boolean acceptsByItself(final boolean acceptedBefore, final boolean refusedByPerson,
                                   final boolean autoDownloadAllows)
    {
        return !refusedByPerson && (acceptedBefore || autoDownloadAllows);
    }

    /**
     * Where to seek right before an accept: the resume point the offer was bound at, while the bytes on disk still
     * reach it; -1 for an accept from the first byte, or when the transfer was accepted before (toxcore takes a
     * seek only before the accept).
     */
    static long seekBeforeAccept(final long resumeAt, final long partialBytes, final long size)
    {
        if (resumeAt > 0 && resumeAt < size && partialBytes >= resumeAt)
        {
            return resumeAt;
        }
        return -1L;
    }

    // ---- the sender ----

    /** A kill of an outgoing transfer that only means the receiver lost it (see LOST_TRANSFER_WINDOW_MS). */
    static boolean killMeansLostTransfer(final long now, final long friendOnlineSince, final long offeredAt,
                                         final boolean offeredAgainThisConnection)
    {
        return friendOnlineSince > 0 && offeredAt > 0 && offeredAt < friendOnlineSince
               && now >= friendOnlineSince && (now - friendOnlineSince) <= LOST_TRANSFER_WINDOW_MS
               && !offeredAgainThisConnection;
    }

    // ---- the entry ----

    /** The words of a 1:1 file under way while its connection is gone (SPEC 1.6). */
    enum Wait
    {
        /** Nothing to say: under way, waiting for an answer, or not cut off. */
        NONE,
        /** Its progress so far, "waiting for the connection", never "failed"; it goes on by itself. */
        CONNECTION,
        /** A file of theirs its sender did not offer again (SPEC 1.7): it reads as stopped. */
        STOPPED
    }

    /**
     * Ours: {@code underWay} once offered and not finished or cancelled; {@code cutOff} when it went back to the
     * queue with bytes sent (the cut, a restart) and was not offered again yet.
     */
    static Wait outgoingWait(final boolean friendOnline, final boolean underWay, final boolean cutOff)
    {
        if (cutOff)
        {
            return Wait.CONNECTION;
        }
        if (underWay && !friendOnline)
        {
            return Wait.CONNECTION;
        }
        return Wait.NONE;
    }

    /**
     * Theirs: {@code underWay} once accepted or with bytes here, not whole and not cancelled; {@code bound} while a
     * transfer of toxcore carries it; {@code onlineForMs}, how long its sender has been online again.
     */
    static Wait incomingWait(final boolean friendOnline, final long onlineForMs, final boolean underWay,
                             final boolean bound)
    {
        if (!underWay)
        {
            return Wait.NONE;
        }
        if (!friendOnline)
        {
            return Wait.CONNECTION;
        }
        if (bound)
        {
            return Wait.NONE;
        }
        return onlineForMs < INCOMING_WAIT_AFTER_ONLINE_MS ? Wait.CONNECTION : Wait.STOPPED;
    }
}
