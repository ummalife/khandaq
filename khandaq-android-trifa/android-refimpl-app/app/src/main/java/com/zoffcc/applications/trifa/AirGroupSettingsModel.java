package com.zoffcc.applications.trifa;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import org.khandaq.messenger.R;

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

    /**
     * KHANDAQ (inbox #13, 27.11): the words the settings and the dossier of a group say its topic in. Since #46 the
     * topic, while there is one, is the title a group shows in the chat list and at its top: the name of the group
     * that can change, as toxcore has no call for the one it was created with. Called "Topic", it read as a second
     * name, so a group's settings call it the group's name (GROUP_NAME_WORDS) and never say "topic". Khandaq
     * Community goes by its own name whatever its topic says (KhandaqCommunity.displayName): its topic keeps the
     * words it had (COMMUNITY_TOPIC_WORDS).
     */
    static final class TopicWords
    {
        /** The row, the title of its page and the hint of its field. */
        @StringRes
        final int title;
        /** What it does: under the row while it may change here, and under its field. */
        @StringRes
        final int note;
        /** Under the row while the lock is on and your role does not change it. */
        @StringRes
        final int locked;
        /** Under the row of an observer while the lock is off. */
        @StringRes
        final int notObservers;
        /** The refusal of an empty value. */
        @StringRes
        final int empty;
        /** The founder's switch of the lock. */
        @StringRes
        final int lock;
        /** The line under Group settings in the dossier. */
        @StringRes
        final int settingsDetail;
        /** The row shows the name the group goes by (topicRowValue), not its topic. */
        final boolean namesTheGroup;

        private TopicWords(@StringRes final int title, @StringRes final int note, @StringRes final int locked,
                           @StringRes final int notObservers, @StringRes final int empty, @StringRes final int lock,
                           @StringRes final int settingsDetail, final boolean namesTheGroup)
        {
            this.title = title;
            this.note = note;
            this.locked = locked;
            this.notObservers = notObservers;
            this.empty = empty;
            this.lock = lock;
            this.settingsDetail = settingsDetail;
            this.namesTheGroup = namesTheGroup;
        }
    }

    static final TopicWords GROUP_NAME_WORDS = new TopicWords(R.string.air_gs_group_name,
                                                              R.string.air_gs_group_name_note,
                                                              R.string.air_gs_group_name_locked,
                                                              R.string.air_gs_group_name_not_observers,
                                                              R.string.air_gs_group_name_empty,
                                                              R.string.air_gs_group_name_lock,
                                                              R.string.air_group_settings_detail_name, true);
    static final TopicWords COMMUNITY_TOPIC_WORDS = new TopicWords(R.string.air_gs_topic, R.string.air_gs_topic_note,
                                                                   R.string.air_gs_topic_locked,
                                                                   R.string.air_gs_topic_not_observers,
                                                                   R.string.air_gs_topic_empty,
                                                                   R.string.air_gs_topic_lock,
                                                                   R.string.air_group_settings_detail, false);

    private AirGroupSettingsModel()
    {
    }

    /** KHANDAQ (inbox #13, 27.11): the words of the group with this chat id, in either case. */
    static TopicWords topicWords(@Nullable final String groupId)
    {
        return KhandaqCommunity.isCommunity(groupId) ? COMMUNITY_TOPIC_WORDS : GROUP_NAME_WORDS;
    }

    /**
     * What stands under the row of the topic: what it does while it may change here, or while the device is not in
     * the group; else who changes it: the founder and the moderators while the lock is on, everyone but observers
     * while it is off.
     */
    @StringRes
    static int topicNote(final TopicWords words, final boolean changesHere, final int topicLock)
    {
        if (changesHere)
        {
            return words.note;
        }
        // An observer never changes it; while the topic is not locked everyone else may.
        return topicLock == TOPIC_LOCK_ENABLED ? words.locked : words.notObservers;
    }

    /**
     * KHANDAQ (inbox #13, 27.11): the name a group goes by now, as the chat list and the top of the group show it
     * (HelperGroup.get_effective_group_title): its topic while there is one, else the name it was created with; ""
     * when it has neither.
     */
    static String currentName(@Nullable final String topic, @Nullable final String createdName)
    {
        if (hasText(topic))
        {
            return topic;
        }
        return hasText(createdName) ? createdName : "";
    }

    /**
     * KHANDAQ (inbox #13, 27.11): what the row of the topic shows. For a group the name it goes by now
     * (currentName), else the start of its ID, as the list names a group with neither (HelperGroup.group_list_title);
     * never "No topic". For Khandaq Community its topic, or {@code noTopic} while it has none, as before.
     */
    static String topicRowValue(final TopicWords words, @Nullable final String topic,
                                @Nullable final String createdName, @Nullable final String groupId,
                                final String noTopic)
    {
        if (!words.namesTheGroup)
        {
            return topic == null || topic.isEmpty() ? noTopic : topic;
        }
        return HelperGroup.group_list_title(currentName(topic, createdName), null, groupId);
    }

    /**
     * KHANDAQ (inbox #13, 27.11): what the field of the topic starts with. For a group the name it goes by now
     * (currentName): a group with neither a topic nor a name starts empty, with the hint, not with the start of its
     * ID. For Khandaq Community its topic, as before.
     */
    static String topicFieldValue(final TopicWords words, @Nullable final String topic,
                                  @Nullable final String createdName)
    {
        if (!words.namesTheGroup)
        {
            return topic == null ? "" : topic;
        }
        return currentName(topic, createdName);
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

    /** Something other than spaces. */
    private static boolean hasText(@Nullable final String text)
    {
        return text != null && !trimSpaces(text).isEmpty();
    }
}
