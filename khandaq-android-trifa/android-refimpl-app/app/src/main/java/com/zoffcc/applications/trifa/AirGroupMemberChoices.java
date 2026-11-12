package com.zoffcc.applications.trifa;

import java.util.ArrayList;
import java.util.List;

/**
 * KHANDAQ (redesign, stage 5): what can be done with one member of a group, as plain values, so the
 * sheet from a member's row and the member's own screen offer the same things by the same rules
 * (AirGroupModel). The roles given here are the ones the group screens show (AirGroupModel.shownRole):
 * another member's is none while the group is not connected, and then nothing changes a role or
 * removes anyone.
 */
final class AirGroupMemberChoices
{
    enum Choice
    {
        PRIVATE_MESSAGE,
        OPEN_CHAT,
        ADD_FRIEND,
        COPY_ID,
        MUTE,
        UNMUTE,
        KICK,
        MAKE_MODERATOR,
        MAKE_USER,
        MAKE_OBSERVER,
        /** The member's own screen: their key in full and how this phone reaches them. */
        DETAILS,
        /** What an automated test client in a public group is. */
        BOT_INFO
    }

    private AirGroupMemberChoices()
    {
    }

    /**
     * The sheet of another member, in the order of GroupPeerActionsPresenter on iOS: the private
     * message, the direct chat or adding them, copying their ID, muting them in this group, removing
     * them in the danger colour, the changes of role; then, on Android, the member's own screen and, for
     * a test client, what it is.
     */
    static List<Choice> sheet(final boolean friend, final boolean muted, final int target, final int actor,
                              final boolean probableBot)
    {
        final List<Choice> choices = new ArrayList<>();
        choices.add(Choice.PRIVATE_MESSAGE);
        choices.add(friend ? Choice.OPEN_CHAT : Choice.ADD_FRIEND);
        choices.add(Choice.COPY_ID);
        choices.add(muted ? Choice.UNMUTE : Choice.MUTE);
        final List<AirGroupModel.MemberAction> allowed = AirGroupModel.MemberAction.available(target, actor);
        if (allowed.contains(AirGroupModel.MemberAction.KICK))
        {
            choices.add(Choice.KICK);
        }
        for (final AirGroupModel.MemberAction action : allowed)
        {
            if (action != AirGroupModel.MemberAction.KICK)
            {
                choices.add(of(action));
            }
        }
        choices.add(Choice.DETAILS);
        if (probableBot)
        {
            choices.add(Choice.BOT_INFO);
        }
        return choices;
    }

    /**
     * The rows of the member's own screen, under the private message: the direct chat or adding them,
     * muting them in this group (a switch, so {@link Choice#MUTE} whatever its state), the changes of
     * role, and removing them last, in the danger colour. Your own screen has none.
     */
    static List<Choice> screen(final boolean self, final boolean friend, final int target, final int actor)
    {
        final List<Choice> choices = new ArrayList<>();
        if (self)
        {
            return choices;
        }
        choices.add(friend ? Choice.OPEN_CHAT : Choice.ADD_FRIEND);
        choices.add(Choice.MUTE);
        // available() keeps the order of the menu: the changes of role, then removing.
        for (final AirGroupModel.MemberAction action : AirGroupModel.MemberAction.available(target, actor))
        {
            choices.add(of(action));
        }
        return choices;
    }

    /** The choice that stands for a change of role or a removal. */
    static Choice of(final AirGroupModel.MemberAction action)
    {
        switch (action)
        {
            case MAKE_MODERATOR:
                return Choice.MAKE_MODERATOR;
            case MAKE_USER:
                return Choice.MAKE_USER;
            case MAKE_OBSERVER:
                return Choice.MAKE_OBSERVER;
            case KICK:
            default:
                return Choice.KICK;
        }
    }

    /** The change of role or the removal behind a choice; null for every other choice. */
    static AirGroupModel.MemberAction action(final Choice choice)
    {
        switch (choice)
        {
            case MAKE_MODERATOR:
                return AirGroupModel.MemberAction.MAKE_MODERATOR;
            case MAKE_USER:
                return AirGroupModel.MemberAction.MAKE_USER;
            case MAKE_OBSERVER:
                return AirGroupModel.MemberAction.MAKE_OBSERVER;
            case KICK:
                return AirGroupModel.MemberAction.KICK;
            default:
                return null;
        }
    }
}
