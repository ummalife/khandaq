package com.zoffcc.applications.trifa;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (join by ID) — the quiet period after taking a friend's invite to a group.
 *
 * <p>Several friends answer one invite request, and toxcore now replaces a chat no peer has answered
 * yet, so every accepted invite would restart the join. These pin what keeps that from happening: the
 * first invite is taken, the rest are dropped for a minute, and nothing else is held back by it.
 */
public class GroupInviteAcceptPolicyTest
{
    private static final String GROUP = "AABBCCDDEEFF00112233445566778899AABBCCDDEEFF00112233445566778899";
    private static final String OTHER_GROUP = "1122334455667788990011223344556677889900112233445566778899001122";
    private static final long T0 = 1_700_000_000_000L;

    @Before
    public void reset()
    {
        GroupInviteAcceptPolicy.resetAllForTest();
    }

    @Test
    public void aGroupWeNeverTookAnInviteToIsNotHeldBack()
    {
        assertFalse(GroupInviteAcceptPolicy.recentlyAccepted(GROUP, T0));
    }

    @Test
    public void furtherInvitesAreDroppedForAMinute()
    {
        GroupInviteAcceptPolicy.noteAccepted(GROUP, T0);

        assertTrue(GroupInviteAcceptPolicy.recentlyAccepted(GROUP, T0));
        assertTrue(GroupInviteAcceptPolicy.recentlyAccepted(GROUP, T0 + GroupInviteAcceptPolicy.QUIET_MS - 1));
        assertFalse("and the next request's invite is taken again",
                    GroupInviteAcceptPolicy.recentlyAccepted(GROUP, T0 + GroupInviteAcceptPolicy.QUIET_MS));
    }

    @Test
    public void theChatIdIsMatchedWhateverItsCase()
    {
        GroupInviteAcceptPolicy.noteAccepted(GROUP.toLowerCase(), T0);

        assertTrue(GroupInviteAcceptPolicy.recentlyAccepted(GROUP, T0 + 1));
    }

    @Test
    public void anotherGroupIsNotHeldBack()
    {
        GroupInviteAcceptPolicy.noteAccepted(GROUP, T0);

        assertFalse(GroupInviteAcceptPolicy.recentlyAccepted(OTHER_GROUP, T0 + 1));
    }

    @Test
    public void leavingTheGroupEndsTheQuietPeriod()
    {
        GroupInviteAcceptPolicy.noteAccepted(GROUP, T0);
        GroupInviteAcceptPolicy.forget(GROUP.toLowerCase());

        assertFalse(GroupInviteAcceptPolicy.recentlyAccepted(GROUP, T0 + 1));
    }

    @Test
    public void expiredEntriesAreDroppedWhenAnotherInviteIsTaken()
    {
        GroupInviteAcceptPolicy.noteAccepted(GROUP, T0);
        GroupInviteAcceptPolicy.noteAccepted(OTHER_GROUP, T0 + GroupInviteAcceptPolicy.QUIET_MS);

        // Asked about a moment inside its old quiet period, a pruned entry is gone rather than expired.
        assertFalse(GroupInviteAcceptPolicy.recentlyAccepted(GROUP, T0 + 1));
        assertTrue(GroupInviteAcceptPolicy.recentlyAccepted(OTHER_GROUP, T0 + GroupInviteAcceptPolicy.QUIET_MS + 1));
    }

    @Test
    public void aMissingChatIdIsNeitherRecordedNorHeldBack()
    {
        GroupInviteAcceptPolicy.noteAccepted(null, T0);
        GroupInviteAcceptPolicy.forget(null);

        assertFalse(GroupInviteAcceptPolicy.recentlyAccepted(null, T0));
    }
}
