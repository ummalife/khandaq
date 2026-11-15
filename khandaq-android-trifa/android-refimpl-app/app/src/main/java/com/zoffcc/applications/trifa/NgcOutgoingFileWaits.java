package com.zoffcc.applications.trifa;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * KHANDAQ (0.2.56): our group files that wait for a member to send them to.
 *
 * <p>A file sent into a group with nobody else online waited 45 s for a member, then 20 s more for its BEGIN, and was
 * said not sent; and nothing sent it when a member came, as a file of ours is always whole on this device
 * (isGroupMessageMediaReady), which the retries took for "sent". Now its send hands it here and ends, leaving its
 * thread to the other files. The service's tick says, for each group with a waiting file, whether the group has
 * someone to send to; once it has had someone for {@link #PEER_SETTLE_MS}, its files leave here, in the order they
 * began to wait, and go again from their BEGIN. Not at once: toxcore reports a member before the member has our peer
 * info, and the member drops a packet from a peer it has no info for.
 *
 * <p>Memory only: a restart brings the files back from their pending mark (NgcGroupFileTransfer). Thread-safe.
 */
final class NgcOutgoingFileWaits
{
    /** How long a group has had someone to send to before its waiting files go. */
    static final long PEER_SETTLE_MS = 2_000L;

    /** A waiting file. */
    static final class File
    {
        final String groupId;
        final String msgIdHash;

        File(final String groupId, final String msgIdHash)
        {
            this.groupId = groupId;
            this.msgIdHash = msgIdHash;
        }
    }

    /** In the order they began to wait. */
    private final LinkedHashMap<String, File> files = new LinkedHashMap<>();
    /** Since when each group with a waiting file has had someone to send to. */
    private final Map<String, Long> reachableSinceMs = new HashMap<>();

    private static String groupKey(final String groupId)
    {
        return groupId.toLowerCase(Locale.ROOT);
    }

    private static String fileKey(final String groupId, final String msgIdHash)
    {
        return groupKey(groupId) + "|" + msgIdHash.toLowerCase(Locale.ROOT);
    }

    /** The file waits, after those that already do. Its send just found nobody, so the group's wait starts again. */
    synchronized void add(final String groupId, final String msgIdHash)
    {
        if (groupId == null || msgIdHash == null)
        {
            return;
        }
        final String key = fileKey(groupId, msgIdHash);
        if (!files.containsKey(key))
        {
            files.put(key, new File(groupKey(groupId), msgIdHash.toLowerCase(Locale.ROOT)));
        }
        reachableSinceMs.remove(groupKey(groupId));
    }

    /** The file no longer waits: it goes, or it is gone. True when it did wait. */
    synchronized boolean remove(final String groupId, final String msgIdHash)
    {
        if (groupId == null || msgIdHash == null)
        {
            return false;
        }
        final boolean removed = files.remove(fileKey(groupId, msgIdHash)) != null;
        if (removed && !hasFileOf(groupKey(groupId)))
        {
            reachableSinceMs.remove(groupKey(groupId));
        }
        return removed;
    }

    synchronized boolean contains(final String groupId, final String msgIdHash)
    {
        return groupId != null && msgIdHash != null && files.containsKey(fileKey(groupId, msgIdHash));
    }

    /** The groups with a waiting file, each once, in the order of their first waiting file. */
    synchronized List<String> groups()
    {
        final List<String> out = new ArrayList<>();
        for (final File f : files.values())
        {
            if (!out.contains(f.groupId))
            {
                out.add(f.groupId);
            }
        }
        return out;
    }

    /**
     * Whether the group has someone to send to at {@code nowMs}. Returns the files of the group whose turn came:
     * all of them, in their order, once the group has had someone for {@link #PEER_SETTLE_MS}. They no longer wait;
     * a send that finds nobody again brings them back ({@link #add}).
     */
    synchronized List<File> due(final String groupId, final boolean reachable, final long nowMs)
    {
        final List<File> out = new ArrayList<>();
        if (groupId == null)
        {
            return out;
        }
        final String group = groupKey(groupId);
        if (!reachable || !hasFileOf(group))
        {
            reachableSinceMs.remove(group);
            return out;
        }
        final Long since = reachableSinceMs.get(group);
        if (since == null)
        {
            reachableSinceMs.put(group, nowMs);
            return out;
        }
        if ((nowMs - since) < PEER_SETTLE_MS)
        {
            return out;
        }
        final Iterator<File> it = files.values().iterator();
        while (it.hasNext())
        {
            final File f = it.next();
            if (f.groupId.equals(group))
            {
                out.add(f);
                it.remove();
            }
        }
        reachableSinceMs.remove(group);
        return out;
    }

    private boolean hasFileOf(final String group)
    {
        for (final File f : files.values())
        {
            if (f.groupId.equals(group))
            {
                return true;
            }
        }
        return false;
    }
}
