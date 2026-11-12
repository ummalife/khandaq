package com.zoffcc.applications.trifa;

import android.content.Context;

import androidx.annotation.Nullable;

import com.luseen.autolinklibrary.AutoLinkMode;
import com.luseen.autolinklibrary.AutoLinkOnClickListener;
import com.luseen.autolinklibrary.EmojiTextViewLinks;

import static com.zoffcc.applications.trifa.TRIFAGlobals.TOXURL_PATTERN;

final class GroupMessageBubbleTextHelper
{
    private GroupMessageBubbleTextHelper()
    {
    }

    static void bind(final EmojiTextViewLinks textView,
                     final GroupMentionHelper.ParsedGroupText parsed,
                     final String groupIdentifier,
                     final Context context,
                     @Nullable final String searchHighlight)
    {
        GroupMentionHelper.bindMessageText(textView, parsed, groupIdentifier, context, searchHighlight,
                createAutoLinkListener(context, groupIdentifier));
    }

    static AutoLinkOnClickListener createAutoLinkListener(final Context context, final String groupIdentifier)
    {
        return (autoLinkMode, matchedText) -> {
            if (autoLinkMode == AutoLinkMode.MODE_URL)
            {
                AirLinkQuestion.openLink(context, matchedText.replaceFirst("^\\s", ""));
            }
            else if (autoLinkMode == AutoLinkMode.MODE_EMAIL)
            {
                AirLinkQuestion.writeEmail(context, matchedText.replaceFirst("^\\s", ""));
            }
            else if (autoLinkMode == AutoLinkMode.MODE_MENTION)
            {
                GroupMentionHelper.openMentionFromMatchedText(context, groupIdentifier, matchedText);
            }
            else if (autoLinkMode == AutoLinkMode.MODE_HASHTAG)
            {
                AirLinkQuestion.openLink(context, "https://twitter.com/hashtag/"
                                + matchedText.replaceFirst("^\\s", "").replaceFirst("^#", ""));
            }
            else if (autoLinkMode == AutoLinkMode.MODE_CUSTOM)
            {
                AirLinkQuestion.requestContact(context, matchedText.replaceFirst("^\\s", ""));
            }
        };
    }
}
