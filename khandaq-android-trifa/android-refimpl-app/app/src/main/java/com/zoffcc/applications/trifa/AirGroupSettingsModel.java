package com.zoffcc.applications.trifa;

import androidx.annotation.Nullable;

/**
 * KHANDAQ (redesign, stage 5): the rules of the group settings (GroupInfoActivity), as GroupInfoController
 * and AirGroupSettingsText on iOS. Plain functions, so they are tested without a group or a device.
 */
final class AirGroupSettingsModel
{
    /** NGC keeps the member limit in 16 bits. */
    static final int PEER_LIMIT_MAX = 65535;
    /** The most characters a typed limit may have, leading zeros included, as on iOS. */
    static final int PEER_LIMIT_MAX_CHARS = 6;
    /** The topic lock as toxcore and the JNI give it: on is 0, off is 1; anything else is not known. */
    static final int TOPIC_LOCK_ENABLED = ToxVars.Tox_Group_Topic_Lock.TOX_GROUP_TOPIC_LOCK_ENABLED.value;
    static final int TOPIC_LOCK_DISABLED = ToxVars.Tox_Group_Topic_Lock.TOX_GROUP_TOPIC_LOCK_DISABLED.value;

    private AirGroupSettingsModel()
    {
    }

    /**
     * Whether your role may change the topic, as toxcore decides it (gc_set_topic): an observer never;
     * while the topic lock is on, the founder and the moderators only; while it is off, everyone else. An
     * unknown role or lock leaves the attempt to toxcore, as the old screen did for everyone.
     */
    static boolean canEditTopic(final int selfRole, final int topicLock)
    {
        if (selfRole == AirGroupModel.ROLE_OBSERVER)
        {
            return false;
        }
        if (topicLock == TOPIC_LOCK_ENABLED && AirGroupModel.isKnownRole(selfRole))
        {
            return selfRole == AirGroupModel.ROLE_FOUNDER || selfRole == AirGroupModel.ROLE_MODERATOR;
        }
        return true;
    }

    /**
     * The member limit as typed: decimal digits of any script (an Arabic keyboard gives Arabic-Indic
     * ones), with spaces around them, from 1 to 65535. 0 for anything else: a sign, a separator, a letter,
     * too many characters or a number out of range.
     */
    static int peerLimit(@Nullable final String text)
    {
        if (text == null)
        {
            return 0;
        }
        final String trimmed = trimSpaces(text);
        final int length = trimmed.codePointCount(0, trimmed.length());
        if (length == 0 || length > PEER_LIMIT_MAX_CHARS)
        {
            return 0;
        }
        int value = 0;
        int i = 0;
        while (i < trimmed.length())
        {
            final int codePoint = trimmed.codePointAt(i);
            i += Character.charCount(codePoint);
            if (Character.getType(codePoint) != Character.DECIMAL_DIGIT_NUMBER)
            {
                return 0;
            }
            final int digit = Character.digit(codePoint, 10);
            if (digit < 0)
            {
                return 0;
            }
            value = value * 10 + digit;
        }
        return value >= 1 && value <= PEER_LIMIT_MAX ? value : 0;
    }

    /** No new member gets in while as many peers are in the group as its limit allows (toxcore: group full). */
    static boolean isFull(final long peersInGroup, final int limit)
    {
        return limit > 0 && peersInGroup >= limit;
    }

    /**
     * Who can write as one of the three choices, in the order the screen lists them: all members,
     * moderators and the founder, the founder only. -1 for a state toxcore did not give.
     */
    static int voiceChoice(final int voiceState)
    {
        if (voiceState == AirGroupModel.VOICE_ALL)
        {
            return 0;
        }
        if (voiceState == AirGroupModel.VOICE_MODERATOR)
        {
            return 1;
        }
        if (voiceState == AirGroupModel.VOICE_FOUNDER)
        {
            return 2;
        }
        return -1;
    }

    private static String trimSpaces(final String text)
    {
        int start = 0;
        int end = text.length();
        while (start < end && isSpace(text.charAt(start)))
        {
            start++;
        }
        while (end > start && isSpace(text.charAt(end - 1)))
        {
            end--;
        }
        return text.substring(start, end);
    }

    private static boolean isSpace(final char c)
    {
        return Character.isWhitespace(c) || Character.isSpaceChar(c);
    }
}
