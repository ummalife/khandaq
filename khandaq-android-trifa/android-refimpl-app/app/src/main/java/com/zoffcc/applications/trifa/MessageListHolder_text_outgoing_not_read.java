/**
 * [TRIfA], Java part of Tox Reference Implementation for Android
 * Copyright (C) 2017 Zoff <zoff@zoff.cc>
 * <p>
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * version 2 as published by the Free Software Foundation.
 * <p>
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the
 * Free Software Foundation, Inc., 51 Franklin Street, Fifth Floor,
 * Boston, MA  02110-1301, USA.
 */

package com.zoffcc.applications.trifa;

import org.khandaq.messenger.R;

import android.content.Context;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.luseen.autolinklibrary.AutoLinkMode;
import com.luseen.autolinklibrary.AutoLinkOnClickListener;
import com.luseen.autolinklibrary.EmojiTextViewLinks;
import com.zoffcc.applications.sorm.Message;

import androidx.recyclerview.widget.RecyclerView;

import static com.zoffcc.applications.trifa.HelperGeneric.dp2px;
import static com.zoffcc.applications.trifa.HelperGeneric.format_chat_message_time;
import static com.zoffcc.applications.trifa.MainActivity.PREF__global_font_size;
import static com.zoffcc.applications.trifa.MainActivity.selected_messages;
import static com.zoffcc.applications.trifa.MessageListActivity.onClick_message_helper;
import static com.zoffcc.applications.trifa.MessageListActivity.onLongClick_message_helper;
import static com.zoffcc.applications.trifa.MessageListFragment.search_messages_text;
import static com.zoffcc.applications.trifa.TRIFAGlobals.MESSAGE_EMOJI_ONLY_EMOJI_SIZE;
import static com.zoffcc.applications.trifa.TRIFAGlobals.MESSAGE_EMOJI_SIZE;
import static com.zoffcc.applications.trifa.TRIFAGlobals.MESSAGE_TEXT_SIZE;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TOXURL_PATTERN;

public class MessageListHolder_text_outgoing_not_read extends RecyclerView.ViewHolder implements View.OnClickListener, View.OnLongClickListener
{
    private static final String TAG = "trifa.MessageListHolder";

    private Message message_;
    private Context context;

    EmojiTextViewLinks textView;
    ImageView imageView;
    TextView date_time;
    LinearLayout text_block_group;
    ViewGroup layout_message_container;
    boolean is_selected = false;
    TextView message_text_date_string;
    ViewGroup message_text_date;

    public MessageListHolder_text_outgoing_not_read(View itemView, Context c)
    {
        super(itemView);

        // Log.i(TAG, "MessageListHolder");

        this.context = c;

        textView = (EmojiTextViewLinks) itemView.findViewById(R.id.m_text);
        imageView = (ImageView) itemView.findViewById(R.id.m_icon);
        date_time = (TextView) itemView.findViewById(R.id.date_time);
        text_block_group = (LinearLayout) itemView.findViewById(R.id.text_block_group);
        layout_message_container = (ViewGroup) itemView.findViewById(R.id.layout_message_container);
        message_text_date_string = (TextView) itemView.findViewById(R.id.message_text_date_string);
        message_text_date = (ViewGroup) itemView.findViewById(R.id.message_text_date);

        // KHANDAQ (redesign, stage 2): the conversation's text rows are entries of the transcript.
        if (AirTranscript.isEntry(itemView))
        {
            AirTranscript.prepare(itemView, textView);
        }
    }

    public void bindMessageList(Message m)
    {
        // KHANDAQ (#192): reaction chips under the bubble
        final Message m_react = m;
        ChatReactionsUiHelper.bind_reactions_row(itemView, m_react.reactions,
                emoji -> HelperMessageReaction.toggleOwnFriendReaction(m_react, emoji));

        message_ = m;

        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, MESSAGE_TEXT_SIZE[PREF__global_font_size]);

        is_selected = false;
        if (selected_messages.isEmpty())
        {
            is_selected = false;
        }
        else
        {
            is_selected = selected_messages.contains(m.id);
        }

        if (is_selected)
        {
            layout_message_container.setBackgroundResource(AirTranscript.selectionPlate(layout_message_container));
        }
        else
        {
            layout_message_container.setBackgroundColor(Color.TRANSPARENT);
        }

        // --------- message date header ---------
        final int my_position = this.getAdapterPosition();
        if (MainActivity.message_list_fragment != null && MainActivity.message_list_fragment.adapter != null)
        {
            ChatDateSeparatorHelper.bindInlineDateHeader(itemView, my_position,
                    MainActivity.message_list_fragment.adapter);
        }
        // --------- message date header ---------


        itemView.setOnClickListener(this);
        itemView.setOnLongClickListener(this);

        layout_message_container.setOnClickListener(onclick_listener);
        layout_message_container.setOnLongClickListener(onlongclick_listener);

        textView.setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(View view)
            {
                layout_message_container.performClick();
            }
        });
        textView.setOnLongClickListener(new View.OnLongClickListener()
        {
            @Override
            public boolean onLongClick(View view)
            {
                layout_message_container.performLongClick();
                return true;
            }
        });

        date_time.setText(format_chat_message_time(m, true));

        textView.setCustomRegex(TOXURL_PATTERN);
        textView.addAutoLinkMode(AutoLinkMode.MODE_URL, AutoLinkMode.MODE_EMAIL, AutoLinkMode.MODE_HASHTAG,
                                 AutoLinkMode.MODE_MENTION, AutoLinkMode.MODE_CUSTOM);

        // KHANDAQ (redesign, E9): a call entry is said in the app's language now, not the one it was written in.
        final MessageReplyHelper.ParsedMessage parsedMessage = MessageReplyHelper.parse(HelperCall.shownText(context, m));
        final boolean locationBound = HelperLocationMessage.bind(itemView, textView, parsedMessage.bodyText);
        if (!locationBound)
        {
            if ((search_messages_text == null) || (search_messages_text.length() == 0))
            {
                textView.setAutoLinkText(parsedMessage.bodyText);
            }
            else
            {
                textView.setAutoLinkTextHighlight(parsedMessage.bodyText, search_messages_text);
            }
        }

        textView.setAutoLinkOnClickListener(new AutoLinkOnClickListener()
        {
            @Override
            public void onAutoLinkTextClick(AutoLinkMode autoLinkMode, String matchedText)
            {
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
                    AirLinkQuestion.openLink(context, "https://twitter.com/" +
                                                         matchedText.replaceFirst("^\\s", "").replaceFirst("^@", ""));
                }
                else if (autoLinkMode == AutoLinkMode.MODE_HASHTAG)
                {
                    AirLinkQuestion.openLink(context, "https://twitter.com/hashtag/" +
                                                         matchedText.replaceFirst("^\\s", "").replaceFirst("^#", ""));
                }
                else if (autoLinkMode == AutoLinkMode.MODE_CUSTOM) // tox: urls
                {
                    AirLinkQuestion.requestContact(context, matchedText.replaceFirst("^\\s", ""));
                }
            }
        });

        // KHANDAQ (redesign, stage 2): an entry of the transcript takes its look from AirTranscript: no
        // bubble, the time column, the author over a run and our mark; nothing below applies to it.
        if (AirTranscript.isEntry(itemView))
        {
            AirTranscript.bindText(itemView, my_position, m, parsedMessage, locationBound);
            return;
        }

        ChatBubbleUiHelper.bind_text_message_bubble(text_block_group, textView, true, parsedMessage.bodyText,
                PREF__global_font_size, parsedMessage.reply != null, locationBound);
        ChatBubbleUiHelper.bind_bubble_time(ChatBubbleUiHelper.find_bubble_time(itemView), date_time,
                format_chat_message_time(m, true), true, m.edited);
        ChatBubbleUiHelper.bind_outgoing_delivery_status(imageView, m);
        ChatBubbleUiHelper.bind_reply_quote(text_block_group, parsedMessage.reply,
                meta -> HelperReply.scrollToReplyTargetInDirectChat(meta));
    }

    @Override
    public void onClick(View v)
    {
        // Log.i(TAG, "onClick");
    }

    @Override
    public boolean onLongClick(final View v)
    {
        // Log.i(TAG, "onLongClick");
        return true;
    }


    private View.OnClickListener onclick_listener = new View.OnClickListener()
    {
        @Override
        public void onClick(final View v)
        {
            is_selected = onClick_message_helper(v, is_selected, message_);
        }
    };

    private View.OnLongClickListener onlongclick_listener = new View.OnLongClickListener()
    {
        @Override
        public boolean onLongClick(final View v)
        {
            MessageListActivity.long_click_message_return res = onLongClick_message_helper(context, v, is_selected,
                                                                                           message_);
            is_selected = res.is_selected;
            return res.ret_value;
        }
    };

}
