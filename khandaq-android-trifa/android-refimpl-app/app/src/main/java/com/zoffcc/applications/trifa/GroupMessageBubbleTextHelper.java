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
                GroupMessageListHolder_text_incoming_not_read.showDialog_url(context, "open URL?",
                        matchedText.replaceFirst("^\\s", ""));
            }
            else if (autoLinkMode == AutoLinkMode.MODE_EMAIL)
            {
                GroupMessageListHolder_text_incoming_not_read.showDialog_email(context, "send Email?",
                        matchedText.replaceFirst("^\\s", ""));
            }
            else if (autoLinkMode == AutoLinkMode.MODE_MENTION)
            {
                GroupMentionHelper.openMentionFromMatchedText(context, groupIdentifier, matchedText);
            }
            else if (autoLinkMode == AutoLinkMode.MODE_HASHTAG)
            {
                GroupMessageListHolder_text_incoming_not_read.showDialog_url(context, "open URL?",
                        "https://twitter.com/hashtag/"
                                + matchedText.replaceFirst("^\\s", "").replaceFirst("^#", ""));
            }
            else if (autoLinkMode == AutoLinkMode.MODE_CUSTOM)
            {
                GroupMessageListHolder_text_incoming_not_read.showDialog_tox(context,
                        context.getString(org.khandaq.messenger.R.string.add_id_dialog_title),
                        matchedText.replaceFirst("^\\s", ""));
            }
        };
    }
}
