package com.zoffcc.applications.trifa;

import java.util.Locale;

import androidx.annotation.Nullable;

/**
 * KHANDAQ (tester 21.11, 8257): the name you go by in one group, set in that group's settings.
 *
 * <p>A tester renamed themselves in a group ("AZ iOS" to "AZ iOS1") and later had the profile name there again.
 * toxcore keeps the name of each group in the profile's save, but the app wrote over it: every save of the profile
 * (its name, its status, a field left on the profile screen) sent the profile name into every group
 * (HelperGroup.sync_self_display_name_to_all_groups), and every join the app makes of a group again (a group toxcore
 * lost, a join by ID, an accepted invitation) took the profile name, which on_group_self_joined then set once more.
 * Nothing remembered that the group had a name of its own.
 *
 * <p>The name is kept per group in a g_opts row (no schema change, see NgcHskStore): set when the group's settings
 * save a name other than the profile's, removed when they save the profile's again or the group is deleted. A group
 * with a name of its own keeps it through the profile's saves and its joins; the other groups follow the profile
 * name, as before.
 *
 * <p>Pure static logic, unit-tested without a Tox instance or a database (GroupOwnNamesTest); HelperGroup keeps the
 * rows.
 */
final class GroupOwnNames
{
    /** The g_opts row of a group: this prefix, then the group's ID in lower case. */
    static final String ROW_KEY_PREFIX = "kqngcselfname_";

    private static final int CHAT_ID_HEX_LENGTH = ToxVars.TOX_GROUP_CHAT_ID_SIZE * 2;

    private GroupOwnNames()
    {
    }

    /** The row of the group, or null for an ID that is not a group's (64 hexadecimal digits). */
    @Nullable
    static String rowKey(@Nullable final String groupIdentifier)
    {
        if (groupIdentifier == null)
        {
            return null;
        }
        final String id = groupIdentifier.trim().toLowerCase(Locale.ROOT);
        if (id.length() != CHAT_ID_HEX_LENGTH)
        {
            return null;
        }
        for (int i = 0; i < id.length(); i++)
        {
            final char c = id.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')))
            {
                return null;
            }
        }
        return ROW_KEY_PREFIX + id;
    }

    /**
     * What the group keeps of a name its settings saved: the name, or null when it is the profile's name (the group
     * follows the profile again) or blank. Both as toxcore takes them (HelperGroup.sanitize_group_peer_name).
     */
    @Nullable
    static String nameToKeep(@Nullable final String saved, @Nullable final String profileName)
    {
        final String name = decode(saved);
        if ((name == null) || name.equals(decode(profileName)))
        {
            return null;
        }
        return name;
    }

    /** The name a join of the group takes: its own when it has one, else the profile's. */
    static String joinName(@Nullable final String ownName, final String profileName)
    {
        final String own = decode(ownName);
        return (own != null) ? own : profileName;
    }

    /** A stored name read back: the name, or null for none. */
    @Nullable
    static String decode(@Nullable final String stored)
    {
        if (stored == null)
        {
            return null;
        }
        final String name = stored.trim();
        return name.isEmpty() ? null : name;
    }
}
