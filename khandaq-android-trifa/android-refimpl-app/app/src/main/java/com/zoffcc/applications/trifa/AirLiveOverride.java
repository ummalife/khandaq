package com.zoffcc.applications.trifa;

import androidx.annotation.Nullable;

import java.util.List;

/**
 * KHANDAQ (redesign, stage 5): another source for what the screens read from toxcore about some contacts and
 * groups. Only a debug build sets one (src/debug), to fill a test profile for the store captures and for the
 * measurements with hundreds of chats; a release build never sets it, so every reader asks toxcore as before.
 *
 * <p>A contact or a group the source answers for lives in the database only: the app never adds it to
 * toxcore or joins it, and reads the group's connection, your role and key in it, its members with their
 * presence, and its counts from the source. A contact's presence stays in its database row as always.
 */
final class AirLiveOverride
{
    interface Source
    {
        /** True for a contact that lives in the database only. */
        boolean ownsFriend(String publicKey);

        /** True for a group that lives in the database only. */
        boolean ownsGroup(String groupId);

        boolean groupConnected(String groupId);

        /** Your role in the group, as tox_group_self_get_role gives it. */
        int groupSelfRole(String groupId);

        /** Your key in the group in upper-case hex, null when there is none. */
        @Nullable
        String groupSelfPublicKey(String groupId);

        /** The members as HelperGroup.collect_group_members_for_display gives them, you included. */
        List<HelperGroup.GroupMemberDisplay> groupMembers(String groupId);

        /** {online, offline} as HelperGroup.count_authoritative_group_members gives them: online counts you. */
        long[] groupCounts(String groupId);
    }

    @Nullable
    static volatile Source source = null;

    private AirLiveOverride()
    {
    }

    /** True when the installed source keeps this contact out of toxcore. */
    static boolean ownsFriend(@Nullable final String publicKey)
    {
        final Source current = source;
        return (current != null) && (publicKey != null) && current.ownsFriend(publicKey);
    }

    /** The installed source when it answers for this group, else null: then toxcore answers. */
    @Nullable
    static Source group(@Nullable final String groupId)
    {
        final Source current = source;
        return (current != null) && (groupId != null) && current.ownsGroup(groupId) ? current : null;
    }
}
