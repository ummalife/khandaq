package com.zoffcc.applications.trifa;

import java.nio.charset.StandardCharsets;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The group screens (design/redesign/02-plan.md, E13 to E15) as plain values: the same rules as
 * AirGroupModel.swift on iOS, case for case. Who is in the group and in what order, who gets a message
 * right now, who may write, and what the founder and the moderators may change. The rules mirror NGC
 * (toxcore): the screens ask toxcore before acting, these only decide what to show. Roles and voice
 * states are toxcore's numbers (ToxVars.Tox_Group_Role, ToxVars.Tox_Group_Voice_State).
 */
final class AirGroupModel
{
    static final int ROLE_FOUNDER = 0;
    static final int ROLE_MODERATOR = 1;
    static final int ROLE_USER = 2;
    static final int ROLE_OBSERVER = 3;

    static final int VOICE_ALL = 0;
    static final int VOICE_MODERATOR = 1;
    static final int VOICE_FOUNDER = 2;

    /** From this many members on, the member list offers a search by name. */
    static final int SEARCH_THRESHOLD = 20;
    /** NGC takes at most 48 bytes of a group name (TOX_GROUP_MAX_GROUP_NAME_LENGTH). */
    static final int NAME_MAX_BYTES = 48;
    /**
     * NGC carries at most 1372 bytes in one group message (TOX_GROUP_MAX_MESSAGE_LENGTH) and toxcore
     * refuses a longer one instead of cutting it, so the counter shows the room left once a tenth of it
     * remains and Send waits over the limit.
     */
    static final int COMPOSE_LIMIT_BYTES = 1372;

    private AirGroupModel()
    {
    }

    // ---- roles ----

    /** Founder first, observers last; a role toxcore did not report sorts with the members. */
    static int rank(final int role)
    {
        switch (role)
        {
            case ROLE_FOUNDER:
                return 0;
            case ROLE_MODERATOR:
                return 1;
            case ROLE_OBSERVER:
                return 3;
            case ROLE_USER:
            default:
                return 2;
        }
    }

    static boolean isKnownRole(final int role)
    {
        return role >= ROLE_FOUNDER && role <= ROLE_OBSERVER;
    }

    /** No role to show: the row says none, and no change of role or removal is offered. */
    static final int ROLE_UNKNOWN = -1;

    /**
     * The role a member's row shows (stage 5). Yours always: toxcore keeps it with the group's state on
     * this device. Another member's only while the group is connected: until the group syncs, toxcore
     * reports everyone it has not confirmed yet as a member, which would show a moderator as one, and a
     * role stored here may be out of date. iOS shows toxcore's role then; here the row says none.
     */
    static int shownRole(final boolean groupConnected, final boolean self, final int role)
    {
        return isKnownRole(role) && (self || groupConnected) ? role : ROLE_UNKNOWN;
    }

    /**
     * KHANDAQ (QA live 2026-11-14): the role another member's stored row keeps when toxcore reports one. A
     * member who is offline is shown with this stored role, and an offline founder was sometimes shown as a
     * member. A role read before the group synced was stored, and one read of a member toxcore listed again,
     * unconfirmed, while it reconnected: toxcore reports such members as members (see {@link #shownRole}).
     *
     * <ul>
     * <li>A role read while the group is not connected, or of a peer toxcore has not confirmed, is not stored.</li>
     * <li>A failed read (no known role) keeps what is stored.</li>
     * <li>The founder stays the founder: toxcore gives that role to no one and takes it from no one.</li>
     * </ul>
     *
     * @param readIsKnown whether toxcore's read is the member's own role: the group is connected and the peer
     *                    confirmed (HelperGroup.group_peer_role_is_known)
     * @return the role to store; {@link #ROLE_UNKNOWN} while none is known
     */
    static int roleToStore(final int stored, final int read, final boolean readIsKnown)
    {
        if (stored == ROLE_FOUNDER || !readIsKnown || !isKnownRole(read))
        {
            return isKnownRole(stored) ? stored : ROLE_UNKNOWN;
        }
        return read;
    }

    /**
     * KHANDAQ (QA live 2026-11-14): whether a member missing from toxcore's saved-peer roster is still listed
     * offline. toxcore leaves out of that roster the members online when it is read, so a member seen online at
     * or after the read is missing from it for that reason alone; one last seen before it was dropped by toxcore.
     *
     * @param lastSeenMs   when the member was last seen online, 0 if never
     * @param rosterReadMs when the roster was read, null while it has not been
     */
    static boolean seenSinceRosterRead(final long lastSeenMs, final Long rosterReadMs)
    {
        return rosterReadMs != null && lastSeenMs > 0L && lastSeenMs >= rosterReadMs;
    }

    /**
     * Whether {@code actor} may give {@code target} the role {@code role}: the founder gives any role but
     * founder to anyone else; a moderator moves members and observers between those two roles.
     */
    static boolean canSet(final int role, final int target, final int actor)
    {
        if (!isKnownRole(role) || !isKnownRole(target) || role == target || role == ROLE_FOUNDER
            || target == ROLE_FOUNDER)
        {
            return false;
        }
        switch (actor)
        {
            case ROLE_FOUNDER:
                return true;
            case ROLE_MODERATOR:
                return target != ROLE_MODERATOR && role != ROLE_MODERATOR;
            default:
                return false;
        }
    }

    /** Whether {@code actor} may remove a member with the role {@code target} from the group. */
    static boolean canKick(final int target, final int actor)
    {
        if (!isKnownRole(target))
        {
            return false;
        }
        switch (actor)
        {
            case ROLE_FOUNDER:
                return target != ROLE_FOUNDER;
            case ROLE_MODERATOR:
                return target == ROLE_USER || target == ROLE_OBSERVER;
            default:
                return false;
        }
    }

    // ---- members ----

    /** A member of a group as the group screens show them. */
    static final class Member
    {
        /** The member's key in this group (NGC keys are per group), upper case; empty when unknown. */
        final String publicKey;
        final String name;
        final int role;
        final boolean online;
        /** When this device last saw the member online, 0 when it never did. */
        final long lastSeenMs;
        final boolean self;

        Member(final String publicKey, final String name, final int role, final boolean online,
               final long lastSeenMs, final boolean self)
        {
            this.publicKey = publicKey == null ? "" : publicKey.trim().toUpperCase(Locale.ROOT);
            this.name = name == null ? "" : name;
            this.role = role;
            this.online = online;
            this.lastSeenMs = Math.max(0L, lastSeenMs);
            this.self = self;
        }

        /** The key the member's tone and initials come from: the group key when known, else the name. */
        String toneKey()
        {
            return publicKey.isEmpty() ? name : publicKey;
        }
    }

    /**
     * Whether a member's row says "online". You are online while the group is connected, as on iOS
     * (AirGroupDossier.members). Another member is too, only while the group is connected: the header and
     * the delivery line count nobody online without that connection, and the rows say the same.
     */
    static boolean shownOnline(final boolean groupConnected, final boolean self, final boolean seenOnline)
    {
        return groupConnected && (self || seenOnline);
    }

    /** By role, then the ones online now, then the most recently seen, then by name. */
    static List<Member> sorted(final List<Member> members)
    {
        final List<Member> result = new ArrayList<>(members);
        final Collator collator = Collator.getInstance();
        collator.setStrength(Collator.SECONDARY);
        Collections.sort(result, new Comparator<Member>()
        {
            @Override
            public int compare(final Member left, final Member right)
            {
                final int byRank = Integer.compare(rank(left.role), rank(right.role));
                if (byRank != 0)
                {
                    return byRank;
                }
                if (left.online != right.online)
                {
                    return left.online ? -1 : 1;
                }
                if (left.lastSeenMs != right.lastSeenMs)
                {
                    return left.lastSeenMs > right.lastSeenMs ? -1 : 1;
                }
                final int byName = collator.compare(left.name, right.name);
                if (byName != 0)
                {
                    return byName;
                }
                return left.publicKey.compareTo(right.publicKey);
            }
        });
        return result;
    }

    /** The members whose name contains the query, ignoring case and diacritics. */
    static List<Member> filtered(final List<Member> members, final String query)
    {
        final String needle = AirModel.fold(query == null ? "" : query.trim());
        if (needle.isEmpty())
        {
            return new ArrayList<>(members);
        }
        final List<Member> result = new ArrayList<>();
        for (final Member member : members)
        {
            if (AirModel.fold(member.name).contains(needle))
            {
                result.add(member);
            }
        }
        return result;
    }

    /** Who gets a message the moment it is sent: everyone online but you, in the order of the strip. */
    static List<Member> reachableNow(final List<Member> members)
    {
        final List<Member> result = new ArrayList<>();
        for (final Member member : members)
        {
            if (member.online && !member.self)
            {
                result.add(member);
            }
        }
        return sorted(result);
    }

    /** The people an observer can ask for the right to write: moderators and the founder, online first. */
    static List<Member> moderators(final List<Member> members)
    {
        final List<Member> result = new ArrayList<>();
        for (final Member member : members)
        {
            if (!member.self && (member.role == ROLE_MODERATOR || member.role == ROLE_FOUNDER))
            {
                result.add(member);
            }
        }
        return sorted(result);
    }

    // ---- who gets it now ----

    /**
     * How many are in the group and how many get a message now: the line under the header and the hint
     * under the field. peerCount is toxcore's count, which includes you; online counts the others.
     */
    static final class Reach
    {
        enum Header
        {
            /** "9 members, not connected to the group". */
            OFFLINE,
            /** "9 members, nobody online now". */
            NOBODY,
            /** "9 members, 4 online now". */
            SOME
        }

        enum Hint
        {
            /** No connection to the group: messages wait on this device. */
            OFFLINE,
            /** Only you are in the group so far. */
            ALONE,
            /** Nobody is online: members get it when they come online. */
            NOBODY,
            /** The one other member is online and gets it now. */
            ONE_OTHER,
            /** All N get it now. */
            ALL,
            /** M of N get it now, the rest when they come online. */
            SOME
        }

        final int total;
        final int online;
        final boolean connected;

        Reach(final int peerCount, final int knownMembers, final int online, final boolean connected)
        {
            this.total = Math.max(Math.max(peerCount, knownMembers), 1);
            this.online = connected ? Math.min(Math.max(online, 0), total - 1) : 0;
            this.connected = connected;
        }

        /** Everyone a message is for: the members but you. */
        int recipients()
        {
            return total - 1;
        }

        Header header()
        {
            if (!connected)
            {
                return Header.OFFLINE;
            }
            return online == 0 ? Header.NOBODY : Header.SOME;
        }

        Hint hint()
        {
            if (!connected)
            {
                return Hint.OFFLINE;
            }
            if (recipients() == 0)
            {
                return Hint.ALONE;
            }
            if (online == 0)
            {
                return Hint.NOBODY;
            }
            if (online == recipients())
            {
                // One other member is a case of its own, not a plural form: Russian takes 21 as "one" too.
                return recipients() == 1 ? Hint.ONE_OTHER : Hint.ALL;
            }
            return Hint.SOME;
        }

        AirConversationModel.ComposeHint.Dot dot()
        {
            switch (hint())
            {
                case OFFLINE:
                case NOBODY:
                    return AirConversationModel.ComposeHint.Dot.TRANSIT;
                case ALONE:
                    return AirConversationModel.ComposeHint.Dot.NONE;
                default:
                    return AirConversationModel.ComposeHint.Dot.LIVE;
            }
        }

        @Override
        public boolean equals(final Object other)
        {
            if (!(other instanceof Reach))
            {
                return false;
            }
            final Reach that = (Reach) other;
            return total == that.total && online == that.online && connected == that.connected;
        }

        @Override
        public int hashCode()
        {
            return (total * 31 + online) * 2 + (connected ? 1 : 0);
        }
    }

    // ---- who may write ----

    /**
     * Whether you may write in the group now. Observers never may, not even privately; the founder can
     * also leave the writing to the moderators or to themselves alone (the voice state).
     */
    enum ComposeRight
    {
        WRITE,
        OBSERVER,
        MODERATORS_ONLY,
        FOUNDER_ONLY;

        static ComposeRight make(final int selfRole, final int voiceState)
        {
            if (selfRole == ROLE_OBSERVER)
            {
                return OBSERVER;
            }
            switch (voiceState)
            {
                case VOICE_MODERATOR:
                    return selfRole == ROLE_FOUNDER || selfRole == ROLE_MODERATOR ? WRITE : MODERATORS_ONLY;
                case VOICE_FOUNDER:
                    return selfRole == ROLE_FOUNDER ? WRITE : FOUNDER_ONLY;
                case VOICE_ALL:
                default:
                    return WRITE;
            }
        }

        /** "Write to the founder" instead of "Write to a moderator". */
        boolean asksFounder()
        {
            return this == FOUNDER_ONLY;
        }

        /**
         * Whether the read-only bar offers "Write to a moderator" or "Write to the founder", a private
         * message: only where the voice state keeps a member from writing to everyone. An observer cannot
         * write privately either (writesPrivately), so their bar says why instead
         * (air_group_observer_no_private), as on the computer.
         */
        boolean asks()
        {
            return this == MODERATORS_ONLY || this == FOUNDER_ONLY;
        }

        /** Whether you may write privately to a member of the group: {@link AirGroupModel#writesPrivately}. */
        boolean writesPrivately()
        {
            return this != OBSERVER;
        }
    }

    /**
     * Whether toxcore takes a private message from you in the group. Never from an observer:
     * gc_send_private_message gives -5 while your own role is GR_OBSERVER, so the field is off and
     * nothing tries to go; an observer still reads what a moderator writes to them. While your role is
     * not known (-1) the field stays, as the group's own does (ComposeRight.make).
     */
    static boolean writesPrivately(final int selfRole)
    {
        return selfRole != ROLE_OBSERVER;
    }

    // ---- what the founder and the moderators may do ----

    /** What the founder or a moderator can do to one member, in the order of the menu. */
    enum MemberAction
    {
        MAKE_MODERATOR(ROLE_MODERATOR),
        MAKE_USER(ROLE_USER),
        MAKE_OBSERVER(ROLE_OBSERVER),
        KICK(-1);

        /** The role the action gives, -1 for removing. */
        final int role;

        MemberAction(final int role)
        {
            this.role = role;
        }

        /** The actions on {@code target} that {@code actor} may take, by the rules above. */
        static List<MemberAction> available(final int target, final int actor)
        {
            final List<MemberAction> actions = new ArrayList<>();
            for (final MemberAction action : new MemberAction[]{MAKE_MODERATOR, MAKE_USER, MAKE_OBSERVER})
            {
                if (canSet(action.role, target, actor))
                {
                    actions.add(action);
                }
            }
            if (canKick(target, actor))
            {
                actions.add(KICK);
            }
            return actions;
        }

        /** The action that puts a member back into {@code role}: Undo of a change of role. */
        static MemberAction giving(final int role)
        {
            switch (role)
            {
                case ROLE_MODERATOR:
                    return MAKE_MODERATOR;
                case ROLE_OBSERVER:
                    return MAKE_OBSERVER;
                case ROLE_USER:
                    return MAKE_USER;
                default:
                    return null;
            }
        }
    }

    // ---- inviting ----

    /** A contact on the invitation list of "New group". */
    static final class InviteCandidate
    {
        final long friendNumber;
        final String publicKey;
        final String name;
        final boolean online;
        /** Already in this group: shown, but not offered again. */
        final boolean member;

        InviteCandidate(final long friendNumber, final String publicKey, final String name, final boolean online,
                        final boolean member)
        {
            this.friendNumber = friendNumber;
            this.publicKey = publicKey == null ? "" : publicKey.trim().toUpperCase(Locale.ROOT);
            this.name = name == null ? "" : name;
            this.online = online;
            this.member = member;
        }

        /**
         * tox_group_invite_friend goes over the friend connection, so only a contact online now can be
         * invited; the others wait in the list.
         */
        boolean canInvite()
        {
            return online && !member && friendNumber >= 0;
        }
    }

    enum InviteNote
    {
        NONE,
        ALREADY_MEMBER,
        WHEN_ONLINE;

        static InviteNote of(final InviteCandidate candidate)
        {
            if (candidate.member)
            {
                return ALREADY_MEMBER;
            }
            return candidate.online ? NONE : WHEN_ONLINE;
        }
    }

    /** Those who can be invited first, then online, then by name; a search keeps names containing it. */
    static List<InviteCandidate> arranged(final List<InviteCandidate> candidates, final String query)
    {
        final String needle = AirModel.fold(query == null ? "" : query.trim());
        final List<InviteCandidate> kept = new ArrayList<>();
        for (final InviteCandidate candidate : candidates)
        {
            if (needle.isEmpty() || AirModel.fold(candidate.name).contains(needle))
            {
                kept.add(candidate);
            }
        }
        final Collator collator = Collator.getInstance();
        collator.setStrength(Collator.SECONDARY);
        Collections.sort(kept, new Comparator<InviteCandidate>()
        {
            @Override
            public int compare(final InviteCandidate left, final InviteCandidate right)
            {
                if (left.canInvite() != right.canInvite())
                {
                    return left.canInvite() ? -1 : 1;
                }
                if (left.online != right.online)
                {
                    return left.online ? -1 : 1;
                }
                final int byName = collator.compare(left.name, right.name);
                if (byName != 0)
                {
                    return byName;
                }
                return left.publicKey.compareTo(right.publicKey);
            }
        });
        return kept;
    }

    // ---- new group ----

    /** One line: a pasted line break or a run of spaces becomes one space, and the ends are trimmed. */
    static String cleanName(final String text)
    {
        if (text == null)
        {
            return "";
        }
        final StringBuilder out = new StringBuilder(text.length());
        boolean space = false;
        int i = 0;
        while (i < text.length())
        {
            final int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);
            if (AirConversationModel.isSpace(codePoint))
            {
                space = out.length() > 0;
                continue;
            }
            if (space)
            {
                out.append(' ');
                space = false;
            }
            out.appendCodePoint(codePoint);
        }
        return out.toString();
    }

    /** 0 while the name fits; otherwise the bytes over the limit. */
    static int nameOverflow(final String text)
    {
        final int bytes = cleanName(text).getBytes(StandardCharsets.UTF_8).length;
        return bytes > NAME_MAX_BYTES ? bytes - NAME_MAX_BYTES : 0;
    }

    /** Null while there is plenty of room; otherwise the bytes left, negative once over the limit. */
    static Integer composeRemaining(final int byteCount)
    {
        return AirConversationModel.composeRemaining(byteCount, COMPOSE_LIMIT_BYTES);
    }
}
