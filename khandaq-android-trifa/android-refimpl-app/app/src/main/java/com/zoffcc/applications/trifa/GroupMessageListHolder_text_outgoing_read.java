/**
 * [TRIfA], Java part of Tox Reference Implementation for Android
 * Copyright (C) 2022 Zoff <zoff@zoff.cc>
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
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.luseen.autolinklibrary.AutoLinkMode;
import com.luseen.autolinklibrary.AutoLinkOnClickListener;
import com.luseen.autolinklibrary.EmojiTextViewLinks;
import com.mikepenz.fontawesome_typeface_library.FontAwesome;
import com.mikepenz.iconics.IconicsDrawable;
import com.zoffcc.applications.sorm.GroupMessage;

import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.RecyclerView;

import static com.zoffcc.applications.trifa.GroupMessageListFragment.group_search_messages_text;
import static com.zoffcc.applications.trifa.HelperFriend.add_friend_real;
import static com.zoffcc.applications.trifa.HelperGeneric.dp2px;
import static com.zoffcc.applications.trifa.HelperGeneric.long_date_time_format;
import static com.zoffcc.applications.trifa.HelperFriend.resolve_name_for_pubkey;
import static com.zoffcc.applications.trifa.HelperGroup.tox_group_peer_get_name__wrapper;
import static com.zoffcc.applications.trifa.MainActivity.PREF__global_font_size;
import static com.zoffcc.applications.trifa.MainActivity.selected_group_messages;
import static com.zoffcc.applications.trifa.TRIFAGlobals.MESSAGES_TIMEDELTA_NO_TIMESTAMP_MS;
import static com.zoffcc.applications.trifa.TRIFAGlobals.MESSAGE_EMOJI_ONLY_EMOJI_SIZE;
import static com.zoffcc.applications.trifa.TRIFAGlobals.MESSAGE_EMOJI_SIZE;
import static com.zoffcc.applications.trifa.TRIFAGlobals.MESSAGE_TEXT_SIZE;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TOXURL_PATTERN;

public class GroupMessageListHolder_text_outgoing_read extends RecyclerView.ViewHolder implements View.OnClickListener, View.OnLongClickListener
{
    private static final String TAG = "trifa.MessageListHolder";

    private GroupMessage message_;
    private Context context;

    EmojiTextViewLinks textView;
    ImageView imageView;
    de.hdodenhof.circleimageview.CircleImageView img_avatar;
    TextView date_time;
    ImageView img_corner;
    LinearLayout text_block_group;
    ViewGroup layout_message_container;
    boolean is_selected = false;

    public GroupMessageListHolder_text_outgoing_read(View itemView, Context c)
    {
        super(itemView);

        // Log.i(TAG, "MessageListHolder");

        this.context = c;

        textView = (EmojiTextViewLinks) itemView.findViewById(R.id.m_text);
        imageView = (ImageView) itemView.findViewById(R.id.m_icon);
        img_avatar = (de.hdodenhof.circleimageview.CircleImageView) itemView.findViewById(R.id.img_avatar);
        date_time = (TextView) itemView.findViewById(R.id.date_time);
        img_corner = (ImageView) itemView.findViewById(R.id.img_corner);
        text_block_group = (LinearLayout) itemView.findViewById(R.id.text_block_group);
        layout_message_container = (ViewGroup) itemView.findViewById(R.id.layout_message_container);
    }

    public void bindMessageList(GroupMessage m)
    {
        message_ = m;

        if (!GroupMessageLayoutHelper.isRenderableMessage(context, m))
        {
            GroupMessageLayoutHelper.applyRowVisibility(itemView, layout_message_container,
                    GroupMessageLayoutHelper.hiddenRowLayout());
            return;
        }

        String message__text = m.text;

        if (m.private_message == 1)
        {
            try
            {
                if ((m.sent_privately_to_tox_group_peer_pubkey == null) ||
                    (m.sent_privately_to_tox_group_peer_pubkey.length() < 10))
                {
                    message__text = "Private Message to " + "*unknown*" + ":\n" + m.text;
                }
                else
                {
                    String display_peer_name = "*unknown*";

                    try
                    {
                        String peer_name_pubkey_part = m.sent_privately_to_tox_group_peer_pubkey.substring(
                                (m.sent_privately_to_tox_group_peer_pubkey.length() - 6),
                                m.sent_privately_to_tox_group_peer_pubkey.length());


                        String peer_name_name_part = resolve_name_for_pubkey(
                                m.sent_privately_to_tox_group_peer_pubkey,
                                tox_group_peer_get_name__wrapper(m.group_identifier,
                                                                 m.sent_privately_to_tox_group_peer_pubkey));

                        if (peer_name_name_part == null)
                        {
                            peer_name_name_part = display_peer_name;
                        }
                        else
                        {
                            if (peer_name_name_part.equals("-1"))
                            {
                                peer_name_name_part = display_peer_name;
                            }
                        }

                        display_peer_name = peer_name_name_part + " / " + peer_name_pubkey_part;
                    }
                    catch (Exception e)
                    {
                        e.printStackTrace();
                    }

                    message__text = "Private Message to " + display_peer_name + ":\n" + m.text;
                }
            }
            catch (Exception e)
            {
                message__text = "Private Message to " + "*unknown*" + ":\n" + m.text;
            }
        }

        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, MESSAGE_TEXT_SIZE[PREF__global_font_size]);

        is_selected = false;
        if (selected_group_messages.isEmpty())
        {
            is_selected = false;
        }
        else
        {
            is_selected = selected_group_messages.contains(m.id);
        }

        if (is_selected)
        {
            layout_message_container.setBackgroundColor(Color.GRAY);
        }
        else
        {
            layout_message_container.setBackgroundColor(Color.TRANSPARENT);
        }

        layout_message_container.setOnClickListener(onclick_listener);
        layout_message_container.setOnLongClickListener(onlongclick_listener);

        textView.setOnLongClickListener(new View.OnLongClickListener()
        {
            @Override
            public boolean onLongClick(View view)
            {
                layout_message_container.performLongClick();
                return true;
            }
        });

        // textView.setText("#" + m.id + ":" + message__text);
        textView.setCustomRegex(TOXURL_PATTERN);

        final GroupMentionHelper.ParsedGroupText parsedGroup = GroupMentionHelper.parse(message__text);
        GroupMessageBubbleTextHelper.bind(textView, parsedGroup, m.group_identifier, context,
                group_search_messages_text);

        HelperGeneric.fill_own_avatar_icon(context, img_avatar);
        img_avatar.setVisibility(View.GONE);

        final int layoutPosition = this.getAdapterPosition();
        if (MainActivity.group_message_list_fragment != null
                && MainActivity.group_message_list_fragment.adapter != null)
        {
            ChatDateSeparatorHelper.bindInlineDateHeader(itemView, layoutPosition,
                    MainActivity.group_message_list_fragment.adapter);
        }

        final GroupMessageLayoutHelper.RowLayout rowLayout =
                GroupMessageLayoutHelper.layoutFor(m, layoutPosition, context);
        GroupMessageLayoutHelper.applyRowVisibility(itemView, layout_message_container, rowLayout);
        GroupMessageLayoutHelper.applyTopMargin(itemView, rowLayout);
        img_avatar.setVisibility(View.GONE);

        ChatBubbleUiHelper.bind_text_message_bubble(text_block_group, textView, true, parsedGroup.bodyText,
                PREF__global_font_size, parsedGroup.reply != null, false);
        ChatBubbleUiHelper.bind_bubble_time(ChatBubbleUiHelper.find_bubble_time(itemView), date_time,
                HelperGeneric.format_group_message_time(m, true), true);
        ChatBubbleUiHelper.bind_outgoing_delivery_status(imageView, m);
        ChatBubbleUiHelper.bind_reply_quote(text_block_group, parsedGroup.reply,
                meta -> HelperReply.scrollToReplyTargetInGroupChat(meta));

        HelperGeneric.set_avatar_img_height_in_chat(img_avatar);
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

    private void showDialog_url(final Context c, final String title, final String url1)
    {
        String url2 = url1;

        // check to see if protocol is specified in URL, otherwise add "http://"
        if (!url2.contains("://"))
        {
            url2 = "http://" + url1;
        }
        final String url = url2;

        final AlertDialog.Builder builder = new AlertDialog.Builder(this.context);
        builder.setMessage(url).setTitle(title).
                setCancelable(false).
                setPositiveButton("OK", new DialogInterface.OnClickListener()
                {
                    public void onClick(DialogInterface dialog, int id)
                    {
                        try
                        {
                            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                            c.startActivity(intent);
                        }
                        catch (Exception e)
                        {
                            e.printStackTrace();
                        }
                        dialog.dismiss();
                    }
                }).setNegativeButton("Cancel", new DialogInterface.OnClickListener()
        {
            public void onClick(DialogInterface dialog, int id)
            {
            }
        });

        final AlertDialog alert = builder.create();
        alert.show();
    }

    private void showDialog_email(final Context c, final String title, final String email_addr)
    {
        final AlertDialog.Builder builder = new AlertDialog.Builder(this.context);
        builder.setMessage(email_addr).setTitle(title).
                setCancelable(false).
                setPositiveButton("OK", new DialogInterface.OnClickListener()
                {
                    public void onClick(DialogInterface dialog, int id)
                    {
                        try
                        {
                            Intent emailIntent = new Intent(Intent.ACTION_SENDTO,
                                                            Uri.fromParts("mailto", email_addr, null));
                            emailIntent.setType("message/rfc822");
                            // emailIntent.putExtra(Intent.EXTRA_SUBJECT, "Subject");
                            // emailIntent.putExtra(Intent.EXTRA_TEXT, "Body");
                            c.startActivity(Intent.createChooser(emailIntent, "Send email..."));
                        }
                        catch (Exception e)
                        {
                            e.printStackTrace();
                        }
                        dialog.dismiss();
                    }
                }).setNegativeButton("Cancel", new DialogInterface.OnClickListener()
        {
            public void onClick(DialogInterface dialog, int id)
            {
            }
        });

        final AlertDialog alert = builder.create();
        alert.show();
    }

    private void showDialog_tox(final Context c, final String title, final String toxid)
    {
        final AlertDialog.Builder builder = new AlertDialog.Builder(this.context);
        builder.setMessage(toxid.toUpperCase()).setTitle(title).
                setCancelable(false).
                setPositiveButton("OK", new DialogInterface.OnClickListener()
                {
                    public void onClick(DialogInterface dialog, int id)
                    {
                        try
                        {
                            String friend_tox_id = toxid.toUpperCase().replace(" ", "").replaceFirst("tox:",
                                                                                                     "").replaceFirst(
                                    "TOX:", "").replaceFirst("Tox:", "");
                            add_friend_real(friend_tox_id);
                        }
                        catch (Exception e)
                        {
                            e.printStackTrace();
                        }
                        dialog.dismiss();
                    }
                }).setNegativeButton("Cancel", new DialogInterface.OnClickListener()
        {
            public void onClick(DialogInterface dialog, int id)
            {
            }
        });

        final AlertDialog alert = builder.create();
        alert.show();
    }

    private View.OnClickListener onclick_listener = new View.OnClickListener()
    {
        @Override
        public void onClick(final View v)
        {
            is_selected = GroupMessageListActivity.onClick_message_helper(v, is_selected, message_);
        }
    };

    private View.OnLongClickListener onlongclick_listener = new View.OnLongClickListener()
    {
        @Override
        public boolean onLongClick(final View v)
        {
            GroupMessageListActivity.long_click_message_return res = GroupMessageListActivity.onLongClick_message_helper(
                    context, v, is_selected, message_);
            is_selected = res.is_selected;
            return res.ret_value;
        }
    };
}
