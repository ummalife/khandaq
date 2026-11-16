package com.zoffcc.applications.trifa;

import java.util.List;
import java.util.Locale;

/**
 * KHANDAQ (0.2.57, SPEC-community part B): Khandaq Community, the official group of the app. Every profile joins
 * it once by itself, muted, and never again once the person has left it; otherwise it is a group like any other,
 * except that the files of others in it wait for a tap (item 6). Its founder is our bot on khandaq.org, whose
 * state keeps the chat id, so the id is compiled in. It is not the old TRIfA group that
 * HelperGroup.is_removed_legacy_public_community_group keeps away: that one has another id.
 * <p>
 * The rules are kept here, free of Android, toxcore and the database, so that the unit tests hold them as the
 * phone runs them; CommunityAutoJoin asks them and acts.
 */
final class KhandaqCommunity
{
    /** The chat id of the group, 64 hexadecimal characters in upper case. */
    static final String CHAT_ID = "CFF1995F235303B88C4EF1CE903DCEDC77CEAC82DD1323DAB2CACD2EF840646F";

    /** The name the group goes by on every screen, whatever its topic says (the owner's rule, 2026-11-16). */
    static final String DISPLAY_NAME = "Khandaq Community";

    /**
     * The profile has the group for good (SPEC item 1, "the group exists locally"): it has connected once, so toxcore
     * keeps it. Toxcore saves a group only once its state came from a member (gc_group_is_valid); a join that never
     * connected is gone after a restart, and the next start joins again. So is a group the app let go of while it
     * was full (noteGroupLetGo).
     */
    static final String MARK_AUTO_JOIN_DONE = "community.autoJoinDone";
    /** The person left the group, or deleted it (SPEC item 3). */
    static final String MARK_LEFT = "community.left";

    /**
     * The pause before the app asks a full Community again (SPEC-community: "a client whose join fails because the
     * group is full just retries later"): 5 minutes after the first refusal, doubled after each one in a row, an
     * hour at most.
     */
    static final long ROOM_RETRY_FIRST_MS = 5L * 60L * 1000L;
    static final long ROOM_RETRY_LONGEST_MS = 60L * 60L * 1000L;

    private static final int PUBLIC_KEY_HEX_LENGTH = 64;
    /** A profile is told apart by the start of its public key, as the logs name it: never the whole key. */
    private static final int PROFILE_TAG_LENGTH = 8;

    /** The profile's two marks, and the group's own notification switch. */
    interface Store
    {
        boolean autoJoinDone();

        boolean left();

        void setAutoJoinDone();

        void clearAutoJoinDone();

        void setLeft();

        /** Turns the notifications of the group with this chat id off, as its own switch does. */
        void mute(String chatId);
    }

    /** The app's join by chat id: whether the group exists here now. */
    interface Join
    {
        boolean join();
    }

    /** Tells the rows of a chat list apart, for pinRow. */
    interface ChatRows<T>
    {
        /** The row of Khandaq Community while the person is in it (pinned). */
        boolean pinned(T row);

        /** The Saved Messages row, which stands first in every filter of the list. */
        boolean saved(T row);
    }

    private KhandaqCommunity()
    {
    }

    /** Whether a group, by its chat id in either case, is Khandaq Community. */
    static boolean isCommunity(final String groupId)
    {
        return (groupId != null) && CHAT_ID.equalsIgnoreCase(groupId.trim());
    }

    /**
     * The name a group goes by: Khandaq Community by its own, whatever its founder put in its topic or its name, on
     * every screen that names it (the list, its chat, its dossier, notifications, the pickers, the search, a report);
     * any other group by {@code rawName}, or "" for none. HelperGroup.get_group_display_name asks it.
     */
    static String displayName(final String groupId, final String rawName)
    {
        if (isCommunity(groupId))
        {
            return DISPLAY_NAME;
        }
        return (rawName == null) ? "" : rawName;
    }

    /**
     * Whether the row of a group is pinned to the top of the chat list: Khandaq Community while the person is in it,
     * joined by itself or by hand. A row of it they left ({@code weLeft}) is a group like any other. There is no
     * unpin: leaving the group is how the person takes it away.
     */
    static boolean pinned(final String groupId, final boolean weLeft)
    {
        return isCommunity(groupId) && !weLeft;
    }

    /**
     * Puts the pinned row of Khandaq Community at the top of a chat list ({@code shown}): right below the Saved
     * Messages row when that one stands first, or first. The Saved Messages row stands in every filter of the list,
     * and so does this one: a filter that left it out of {@code shown} gets it from {@code all}. Without a pinned row
     * in {@code all} nothing changes.
     */
    static <T> void pinRow(final List<T> shown, final List<T> all, final ChatRows<T> rows)
    {
        T community = null;
        for (final T row : all)
        {
            if (rows.pinned(row))
            {
                community = row;
                break;
            }
        }
        if (community == null)
        {
            return;
        }
        for (int i = shown.size() - 1; i >= 0; i--)
        {
            if (rows.pinned(shown.get(i)))
            {
                shown.remove(i);
            }
        }
        final boolean savedOnTop = !shown.isEmpty() && rows.saved(shown.get(0));
        shown.add(savedOnTop ? 1 : 0, community);
    }

    /**
     * Whether to join it now: our node is connected, the profile never joined it by itself before (autoJoinDone)
     * nor left it (left), and it is not in it already, by hand or from an earlier join.
     */
    static boolean shouldAutoJoin(final boolean connected, final boolean autoJoinDone, final boolean left,
                                  final boolean alreadyInGroup)
    {
        return connected && !autoJoinDone && !left && !alreadyInGroup;
    }

    /**
     * Joins it through join when shouldAutoJoin says so, with the marks the store keeps. When the join made the
     * group here ({@code newGroup}: the app had no row of it), it is muted; a group the app had already (an earlier
     * join that toxcore did not keep, or one joined by hand) keeps its switch as the person left it. A failed join
     * changes nothing. The join is not marked done here: toxcore keeps the group only once it has connected
     * (noteGroupKept), and until then every start that finds it gone joins again. A group the profile is in
     * already never comes here. True when it joined.
     */
    static boolean autoJoin(final Store store, final boolean connected, final boolean alreadyInGroup,
                            final boolean newGroup, final Join join)
    {
        if (!shouldAutoJoin(connected, store.autoJoinDone(), store.left(), alreadyInGroup))
        {
            return false;
        }
        if (!join.join())
        {
            return false;
        }
        if (newGroup)
        {
            // The group mutes as it comes: a person added without asking hears nothing of it until they turn it on.
            store.mute(CHAT_ID);
        }
        return true;
    }

    /**
     * The group has connected ({@code kept}), so toxcore keeps it from now on: the profile has it for good, however
     * it was joined, and the app never joins it by itself again. True when that changed the store.
     */
    static boolean noteGroupKept(final Store store, final boolean kept)
    {
        if (!kept || store.autoJoinDone())
        {
            return false;
        }
        store.setAutoJoinDone();
        return true;
    }

    /**
     * A group was left or deleted by the person: when it is Khandaq Community it is never joined by itself again,
     * however it was joined. True when that changed the store.
     */
    static boolean noteGroupLeft(final Store store, final String groupId)
    {
        if (!isCommunity(groupId) || store.left())
        {
            return false;
        }
        store.setLeft();
        return true;
    }

    /**
     * Whether a refused join waits for room: the group is Khandaq Community, and it refused us because it has as many
     * members as its founder allows (toxcore's TOX_GROUP_JOIN_FAIL_PEER_LIMIT, {@code failType}). Every client over
     * its limit asked again at once and at every start, each try a handshake with the founder and the members, so the
     * app lets go of it and asks again later (roomRetryDelayMs). Any other refusal, and a refusal of any other group,
     * is tried again at once, as before.
     */
    static boolean waitsForRoom(final String groupId, final int failType)
    {
        return isCommunity(groupId) && (failType == ToxVars.TOX_GROUP_JOIN_FAIL.TOX_GROUP_JOIN_FAIL_PEER_LIMIT.value);
    }

    /**
     * The pause before the app asks the full Community again, after the {@code refusalsInARow}-th refusal in a row
     * (from 1): ROOM_RETRY_FIRST_MS, doubled each time, ROOM_RETRY_LONGEST_MS at most.
     */
    static long roomRetryDelayMs(final int refusalsInARow)
    {
        long delay = ROOM_RETRY_FIRST_MS;
        for (int refusal = 1; (refusal < refusalsInARow) && (delay < ROOM_RETRY_LONGEST_MS); refusal++)
        {
            delay *= 2;
        }
        return Math.min(delay, ROOM_RETRY_LONGEST_MS);
    }

    /**
     * The app let go of the group's instance while it is full (waitsForRoom): toxcore does not keep it now, so the
     * profile does not have it for good either, and the next try, or the next start, joins it by itself again, as
     * it does a join that never connected. The person's own leave (left) stays as it is, and so do the row and its
     * switch. True when that changed the store.
     */
    static boolean noteGroupLetGo(final Store store)
    {
        if (!store.autoJoinDone())
        {
            return false;
        }
        store.clearAutoJoinDone();
        return true;
    }

    /**
     * The key a mark of the profile is kept under among its settings: the mark, then the first 8 characters of
     * the profile's public key (the start of its Tox ID), in upper case. The marks are the profile's: another
     * profile imported over this one starts without them, and this one imported again finds its own. The key
     * goes to the log (set_g_opts), so it never holds the whole public key. Null while the Tox ID is not known.
     */
    static String markKey(final String mark, final String ownToxId)
    {
        if ((ownToxId == null) || (ownToxId.length() < PUBLIC_KEY_HEX_LENGTH))
        {
            return null;
        }
        for (int i = 0; i < PUBLIC_KEY_HEX_LENGTH; i++)
        {
            if (Character.digit(ownToxId.charAt(i), 16) < 0)
            {
                return null;
            }
        }
        return mark + ":" + ownToxId.substring(0, PROFILE_TAG_LENGTH).toUpperCase(Locale.ROOT);
    }

    /**
     * Whether a mention of the person breaks through the mute of a group ({@code groupMuted}), as it does in every
     * group: not in Khandaq Community, where strangers write. Muted, the Community posts no notification and plays no
     * sound, so it raises no badge of the launcher either (on Android that badge comes from notifications); its row
     * in the list keeps its unread count.
     */
    static boolean mentionBreaksMute(final String groupId, final boolean groupMuted)
    {
        return !groupMuted || !isCommunity(groupId);
    }

    /**
     * Whether the file of another member may come by itself, as the download setting says for every group
     * ({@code settingAllows}): in Khandaq Community, where strangers write, never; it waits for a tap (item 6).
     */
    static boolean fileComesByItself(final String groupId, final boolean settingAllows)
    {
        return settingAllows && !isCommunity(groupId);
    }
}
