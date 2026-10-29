package com.zoffcc.applications.trifa;

import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * KHANDAQ (join by ID) — which of several friend invites to one group we take.
 *
 * <p>A join by group ID that the DHT announce does not complete is finished by a friend: we ask every
 * online friend for an invite (lossless packet 184, HelperGroup.send_group_invite_request_to_friends)
 * and accept what comes back. Several friends answer one request. toxcore replaces a chat with the
 * invite's ID that no peer has answered yet (patches/apply_khandaq_join_by_id.py), so accepting each
 * of those invites would start the join over and drop the confirmation the previous inviter is about
 * to send. The first invite is taken and further ones are dropped for a minute. iOS does the same
 * (OCTSubmanagerGroupsImpl, kOCTGroupInviteAcceptQuietSec).
 *
 * <p>Pure static logic over an explicit clock, so it is unit-testable without a Tox instance or an
 * Android runtime — same reason as {@link NgcHistoryRequestPolicy}.
 */
final class GroupInviteAcceptPolicy
{
    /** How long further invites to a group are dropped once one was taken. Matches iOS. */
    static final long QUIET_MS = 60_000L;

    // chat id (hex, lowercase) -> when we took an invite to it
    private static final Map<String, Long> ACCEPTED_MS = new ConcurrentHashMap<>();

    private GroupInviteAcceptPolicy()
    {
    }

    private static String key(final String groupIdentifier)
    {
        return groupIdentifier == null ? null : groupIdentifier.toLowerCase(Locale.ROOT);
    }

    /** True if an invite to this group was taken less than {@link #QUIET_MS} ago. */
    static boolean recentlyAccepted(final String groupIdentifier, final long nowMs)
    {
        final String k = key(groupIdentifier);
        if (k == null)
        {
            return false;
        }
        final Long acceptedMs = ACCEPTED_MS.get(k);
        return acceptedMs != null && nowMs - acceptedMs < QUIET_MS;
    }

    static void noteAccepted(final String groupIdentifier, final long nowMs)
    {
        final String k = key(groupIdentifier);
        if (k == null)
        {
            return;
        }
        // Expired entries say nothing any more; drop them so the map holds only open quiet periods.
        for (final Iterator<Long> it = ACCEPTED_MS.values().iterator(); it.hasNext(); )
        {
            if (nowMs - it.next() >= QUIET_MS)
            {
                it.remove();
            }
        }
        ACCEPTED_MS.put(k, nowMs);
    }

    /** Leaving a group ends its quiet period, so a rejoin can take a friend's invite at once. */
    static void forget(final String groupIdentifier)
    {
        final String k = key(groupIdentifier);
        if (k != null)
        {
            ACCEPTED_MS.remove(k);
        }
    }

    static void resetAllForTest()
    {
        ACCEPTED_MS.clear();
    }
}
