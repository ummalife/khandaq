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
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.amulyakhare.textdrawable.TextDrawable;
import com.daimajia.swipe.SwipeLayout;
import com.luseen.autolinklibrary.AutoLinkMode;
import com.luseen.autolinklibrary.AutoLinkOnClickListener;
import com.luseen.autolinklibrary.EmojiTextViewLinks;
import com.mikepenz.google_material_typeface_library.GoogleMaterial;
import com.mikepenz.iconics.IconicsDrawable;
import com.zoffcc.applications.sorm.FriendList;
import com.zoffcc.applications.sorm.GroupMessage;

import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.RecyclerView;

import static com.zoffcc.applications.trifa.GroupMessageListActivity.add_quote_group_message_text;
import static com.zoffcc.applications.trifa.GroupMessageListFragment.group_search_messages_text;
import static com.zoffcc.applications.trifa.HelperFriend.add_friend_real;
import static com.zoffcc.applications.trifa.HelperGeneric.darkenColor;
import static com.zoffcc.applications.trifa.HelperGeneric.dp2px;
import static com.zoffcc.applications.trifa.HelperGeneric.hash_to_bucket;
import static com.zoffcc.applications.trifa.HelperGeneric.isColorDarkBrightness;
import static com.zoffcc.applications.trifa.HelperGeneric.lightenColor;
import static com.zoffcc.applications.trifa.HelperGeneric.long_date_time_format;
import static com.zoffcc.applications.trifa.HelperGeneric.string_is_in_list;
import static com.zoffcc.applications.trifa.HelperGroup.tox_group_peer_get_name__wrapper;
import static com.zoffcc.applications.trifa.Identicon.bytesToHex;
import static com.zoffcc.applications.trifa.MainActivity.PREF__compact_chatlist;
import static com.zoffcc.applications.trifa.MainActivity.PREF__global_font_size;
import static com.zoffcc.applications.trifa.MainActivity.PREF__toxirc_muted_peers;
import static com.zoffcc.applications.trifa.MainActivity.selected_group_messages;
import static com.zoffcc.applications.trifa.TRIFAGlobals.CONFERENCE_CHAT_BG_CORNER_RADIUS_IN_PX;
import static com.zoffcc.applications.trifa.TRIFAGlobals.MESSAGES_TIMEDELTA_NO_TIMESTAMP_MS;
import static com.zoffcc.applications.trifa.TRIFAGlobals.MESSAGE_EMOJI_ONLY_EMOJI_SIZE;
import static com.zoffcc.applications.trifa.TRIFAGlobals.MESSAGE_EMOJI_SIZE;
import static com.zoffcc.applications.trifa.TRIFAGlobals.MESSAGE_TEXT_SIZE;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TOXIRC_NGC_PUBKEY;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TOXIRC_TOKTOK_GROUPID;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TOXIRC_TOKTOK_IRC_USER_PUBKEY;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TOXURL_PATTERN;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TRIFA_SYSTEM_MESSAGE_PEER_CHATCOLOR;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TRIFA_SYSTEM_MESSAGE_PEER_PUBKEY;

public class GroupMessageListHolder_text_incoming_not_read extends RecyclerView.ViewHolder implements View.OnClickListener, View.OnLongClickListener
{
    private static final String TAG = "trifa.MessageListHolder";

    private GroupMessage message_;
    private Context context;

    EmojiTextViewLinks textView;
    ImageView imageView;
    de.hdodenhof.circleimageview.CircleImageView img_avatar;
    TextView date_time;
    ViewGroup textView_container;
    ViewGroup layout_peer_name_container;
    TextView peer_name_text;
    ViewGroup layout_message_container;
    boolean is_selected = false;
    boolean is_system_message = false;
    ImageView img_corner;
    int swipe_state = 0;
    int swipe_state_done = 0;
    SwipeLayout swipeLayout = null;

    public GroupMessageListHolder_text_incoming_not_read(View itemView, Context c)
    {
        super(itemView);

        // Log.i(TAG, "MessageListHolder");

        this.context = c;

        textView_container = (ViewGroup) itemView.findViewById(R.id.m_container);
        textView = (EmojiTextViewLinks) itemView.findViewById(R.id.m_text);
        imageView = (ImageView) itemView.findViewById(R.id.m_icon);
        img_avatar = (de.hdodenhof.circleimageview.CircleImageView) itemView.findViewById(R.id.img_avatar);
        date_time = (TextView) itemView.findViewById(R.id.date_time);
        layout_peer_name_container = (ViewGroup) itemView.findViewById(R.id.layout_peer_name_container);
        peer_name_text = (TextView) itemView.findViewById(R.id.peer_name_text);
        layout_message_container = (ViewGroup) itemView.findViewById(R.id.layout_message_container);
        img_corner = (ImageView) itemView.findViewById(R.id.img_corner);

        swipe_state = 0;
        swipe_state_done = 0;

        swipeLayout = (SwipeLayout) itemView.findViewById(R.id.msg_swipe_container);
        swipeLayout.setShowMode(SwipeLayout.ShowMode.PullOut);
        // KHANDAQ (#T3): swipe-to-reply for incoming group text now goes through the RecyclerView-level
        // ItemTouchHelper (reliable from the first open) like every other row type. Disable the old
        // per-ViewHolder daimajia swipe here so the two don't fight over the horizontal drag. The
        // listener below stays registered but never fires while swipe is disabled.
        swipeLayout.setSwipeEnabled(false);

        // KHANDAQ (#31 leak): attach the swipe listener ONCE per ViewHolder here instead of on every
        // bindMessageList — daimajia addSwipeListener() appends without dedup, so re-adding per bind
        // accumulated a listener each rebind. The body only reads instance fields (message_ is refreshed
        // on each bind), so a single registration is correct.
        swipeLayout.addSwipeListener(new SwipeLayout.SwipeListener()
        {
            @Override
            public void onClose(SwipeLayout layout)
            {
                // when the SurfaceView totally cover the BottomView.
                // Log.i(TAG, "onClose: state=");
            }

            @Override
            public void onUpdate(SwipeLayout layout, int leftOffset, int topOffset)
            {
                // you are swiping.
                // Log.i(TAG, "onUpdate: " + leftOffset + " " + topOffset);
                if (leftOffset > 60)
                {
                    swipeLayout.close(true);
                    // Log.i(TAG, "onUpdate: --> close");
                    if (swipe_state == 0)
                    {
                        swipe_state = 1;
                    }
                }
                else if (leftOffset == 0)
                {
                    if (swipe_state == 1)
                    {
                        swipe_state = 0;
                        Log.i(TAG, "onUpdate: --> QUOTE");
                        ChatReplyPreviewController.startReplyToGroupMessage(context, message_);
                    }
                }
            }

            @Override
            public void onStartOpen(SwipeLayout layout)
            {
                // Log.i(TAG, "onStartOpen");
            }

            @Override
            public void onOpen(SwipeLayout layout)
            {
                // when the BottomView totally show.
                // Log.i(TAG, "onOpen");
                swipeLayout.close(true);
            }

            @Override
            public void onStartClose(SwipeLayout layout)
            {
                // Log.i(TAG, "onStartClose");
            }

            @Override
            public void onHandRelease(SwipeLayout layout, float xvel, float yvel)
            {
                // when user's hand released.
                // Log.i(TAG, "onHandRelease");
                swipeLayout.close(true);
            }
        });
    }

    public void bindMessageList(GroupMessage m)
    {
        // KHANDAQ (#192): reaction chips under the bubble
        final GroupMessage m_react = m;
        ChatReactionsUiHelper.bind_reactions_row(itemView, m_react.reactions,
                emoji -> HelperMessageReaction.toggleOwnGroupReaction(m_react, emoji));

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
            message__text = "Private Message:\n" + m.text;
        }

        if (m.tox_group_peer_role == ToxVars.Tox_Group_Role.TOX_GROUP_ROLE_FOUNDER.value)
        {
            // TODO: make something nice looking here // message__text = "*Founder Message*\n" + message__text;
        }
        else if (m.tox_group_peer_role == ToxVars.Tox_Group_Role.TOX_GROUP_ROLE_MODERATOR.value)
        {
            // TODO: make something nice looking here // message__text = "+Moderator Message+\n" + message__text;
        }

        String message__tox_peername = m.tox_group_peername;
        String message__tox_peerpubkey = m.tox_group_peer_pubkey;

        /*
        try
        {
            message__text = message__text + "\npeerid=" +
                            tox_group_peer_by_public_key(tox_group_by_groupid__wrapper(m.group_identifier),
                                                         m.tox_group_peer_pubkey);
        }
        catch (Exception e)
        {
        }
        */

        boolean handle_special_name = false;

        name_test_pk res = correct_pubkey(m);
        if (res.changed)
        {
            try
            {
                message__tox_peername = res.tox_peername;
                peer_name_text.setText(message__tox_peername);
                message__text = res.text;
                if (m.private_message == 1)
                {
                    message__text = "Private Message:\n" + res.text;
                }
                message__tox_peerpubkey = res.tox_peerpubkey;
                handle_special_name = true;
            }
            catch (Exception e)
            {
            }
        }

        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, MESSAGE_TEXT_SIZE[PREF__global_font_size]);

        // Log.i(TAG, "have_avatar_for_pubkey:0000:==========================");

        is_system_message = TRIFA_SYSTEM_MESSAGE_PEER_PUBKEY.equals(message__tox_peerpubkey);
        // Log.i(TAG, "is_system_message=" + is_system_message + " message__tox_peerpubkey=" + message__tox_peerpubkey);

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
            layout_message_container.setBackgroundResource(R.drawable.bg_message_selection);
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

        // Log.i(TAG, "bindMessageList");

        // textView.setText("#" + m.id + ":" + message__text);

        try
        {
            String peer_name = tox_group_peer_get_name__wrapper(m.group_identifier, message__tox_peerpubkey);
            final String shortPeerId = HelperFriend.peer_pubkey_short_id(message__tox_peerpubkey);
            if (((peer_name == null) || peer_name.isEmpty() || shortPeerId.equalsIgnoreCase(peer_name))
                    && (message__tox_peername != null) && !message__tox_peername.isEmpty()
                    && !"-1".equals(message__tox_peername))
            {
                peer_name = message__tox_peername;
            }

            if (peer_name == null)
            {
                peer_name = message__tox_peername;

                if ((peer_name == null) || (message__tox_peername.equals("")) || (peer_name.equals("-1")))
                {
                    peer_name = "Unknown";
                }
            }
            else
            {
                if (peer_name.equals("-1"))
                {
                    if ((message__tox_peername == null) || (message__tox_peername.equals("")))
                    {
                        peer_name = "Unknown";
                    }
                    else
                    {
                        peer_name = message__tox_peername;
                    }
                }
            }

            layout_peer_name_container.setVisibility(View.GONE);
            try
            {
                if (message__tox_peerpubkey.compareTo("-1") == 0)
                {
                    peer_name_text.setText("-system-");
                }
                else
                {
                    peer_name_text.setText(peer_name);
                }
            }
            catch (Exception e2)
            {
                e2.printStackTrace();
                Log.i(TAG, "bindMessageList:EE2:" + e2.getMessage());

                peer_name_text.setText(peer_name);
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
            Log.i(TAG, "bindMessageList:EE:" + e.getMessage());
        }

        //        textView.setAutoLinkText("" + message__tox_peerpubkey.substring((message__tox_peerpubkey.length() - 6),
        //                //
        //                message__tox_peerpubkey.length())
        //                //
        //                + ":" + message__text);

        final String displayText = GroupMessageLayoutHelper.displayTextForMessage(context, m);
        final GroupMentionHelper.ParsedGroupText parsedGroup = GroupMentionHelper.parse(displayText);
        ChatBubbleUiHelper.apply_peer_name_style(peer_name_text, message__tox_peerpubkey);
        ChatBubbleUiHelper.bind_bubble_time(ChatBubbleUiHelper.find_bubble_time(itemView), date_time,
                HelperGeneric.format_group_message_time(m, false), false, m.edited);

        // KHANDAQ (#T2): render a "khandaq-location:LAT,LON" group message as a map bubble (parity with 1:1).
        final boolean locationBound = HelperLocationMessage.bind(itemView, textView, parsedGroup.bodyText, true);
        if (!locationBound)
        {
            GroupMessageBubbleTextHelper.bind(textView, parsedGroup, m.group_identifier, context,
                    group_search_messages_text);
        }
        ChatBubbleUiHelper.bind_reply_quote(textView_container, parsedGroup.reply,
                meta -> HelperReply.scrollToReplyTargetInGroupChat(meta));

        img_corner.setVisibility(View.GONE);
        ChatBubbleUiHelper.hide_delivery_indicator(imageView);

        if (is_system_message)
        {
            img_avatar.setVisibility(View.GONE);
            img_corner.setVisibility(View.GONE);
            imageView.setVisibility(View.GONE);
            textView_container.setMinimumHeight(4);
            textView_container.setPadding((int) dp2px(4), textView_container.getPaddingTop(), (int) dp2px(4),
                                          textView_container.getPaddingBottom()); // left, top, right, bottom
            LinearLayout.LayoutParams parameter = (LinearLayout.LayoutParams) textView_container.getLayoutParams();
            parameter.setMargins((int) dp2px(20), parameter.topMargin, parameter.rightMargin,
                                 parameter.bottomMargin); // left, top, right, bottom
            textView_container.setLayoutParams(parameter);
            // peer_name_text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);

            // -------------------------------
            // make text smaller for system messages
            int system_font_size_used = MESSAGE_TEXT_SIZE[PREF__global_font_size] - 5;
            if (system_font_size_used < 9)
            {
                system_font_size_used = 9;
            }
            textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, system_font_size_used);
            // -------------------------------
        }
        else
        {

            // TODO: do we need to reset here? -> yes
            img_avatar.setVisibility(View.VISIBLE);
            img_corner.setVisibility(View.GONE);
            ChatBubbleUiHelper.hide_delivery_indicator(imageView);
            textView_container.setMinimumHeight((int) dp2px(0));
            LinearLayout.LayoutParams parameter = (LinearLayout.LayoutParams) textView_container.getLayoutParams();
            parameter.setMargins(0, parameter.topMargin, parameter.rightMargin,
                                 parameter.bottomMargin); // left, top, right, bottom
            textView_container.setLayoutParams(parameter);
            // peer_name_text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);

            final String peer_pubkey_for_profile = message__tox_peerpubkey;
            final View.OnClickListener open_peer_profile_listener = new View.OnClickListener()
            {
                @Override
                public void onClick(final View v)
                {
                    if (!is_selected)
                    {
                        GroupMessageListActivity.open_group_peer_info_activity(v.getContext(),
                                peer_pubkey_for_profile, m.group_identifier);
                    }
                }
            };
            img_avatar.setOnClickListener(open_peer_profile_listener);
            layout_peer_name_container.setOnClickListener(open_peer_profile_listener);
            peer_name_text.setOnClickListener(open_peer_profile_listener);

        }

        // --------- peer name / avatar grouping (Telegram-style) ---------

        final int my_position = this.getAdapterPosition();
        if (MainActivity.group_message_list_fragment != null
                && MainActivity.group_message_list_fragment.adapter != null)
        {
            ChatDateSeparatorHelper.bindInlineDateHeader(itemView, my_position,
                    MainActivity.group_message_list_fragment.adapter);
        }

        final GroupMessageLayoutHelper.RowLayout rowLayout =
                GroupMessageLayoutHelper.layoutFor(m, my_position, context);
        GroupMessageLayoutHelper.applyRowVisibility(itemView, layout_message_container, rowLayout);
        GroupMessageLayoutHelper.applyTopMargin(itemView, rowLayout);
        if (!is_system_message)
        {
            if (rowLayout.showPeerName)
            {
                peer_name_text.setVisibility(View.VISIBLE);
                if (!PREF__compact_chatlist)
                {
                    layout_peer_name_container.setVisibility(View.VISIBLE);
                }
            }
            else
            {
                layout_peer_name_container.setVisibility(View.GONE);
                peer_name_text.setVisibility(View.GONE);
            }
            if (rowLayout.showAvatar)
            {
                img_avatar.setVisibility(View.VISIBLE);
                ChatBubbleUiHelper.fill_group_peer_avatar(context, message__tox_peerpubkey,
                        peer_name_text.getText().toString(), img_avatar);
            }
            else
            {
                img_avatar.setVisibility(View.INVISIBLE);
            }
            img_corner.setVisibility(View.GONE);
            ChatBubbleUiHelper.hide_delivery_indicator(imageView);
            ChatBubbleUiHelper.bind_text_message_bubble(textView_container, textView, false,
                    parsedGroup.bodyText, PREF__global_font_size, parsedGroup.reply != null, false);
        }

        // KHANDAQ (audit2 #1, step 2 of DESIGN-ngc-signed-history-sync.md): a message relayed to us by
        // history sync carries an author nobody signed for — the transport authenticates the SYNCING
        // peer, not the claimed original sender. Until the signature lands (that needs a protocol
        // version), the least we owe the user is to stop rendering such a row identically to a live,
        // toxcore-authenticated one. This does not remove the forgery, it removes the deception.
        //
        // Placed last on purpose: hide_delivery_indicator(imageView) is called on three separate
        // branches above, so anything set earlier would be wiped. The old Tox conference holder has
        // used this same m_icon slot for a synced/direct dot for years — this is that affordance,
        // brought to NGC groups, where the incoming path leaves the slot unused.
        mark_unverified_sender(is_system_message, m);
    }

    /**
     * Whether a row must carry the "sender not verified" marker — pure, so it can be unit tested.
     *
     * The positive case is awkward to see on a device: it needs an incoming row that arrived via
     * history sync, which needs a second live group peer. Extracting the decision means the rule
     * itself is covered automatically even when the visual case is not reproducible.
     *
     * @param is_system_message system rows have no claimed author to be unverified about
     */
    static boolean should_mark_unverified_sender(final boolean is_system_message, final GroupMessage m)
    {
        return should_mark_unverified_sender(is_system_message, m, false);
    }

    /**
     * KHANDAQ (audit #2 finding 1, step 3): the same rule, with the one thing that can now clear
     * the marker — a signature.
     *
     * @param author_signature_verified the author's own history-signing key verified a signature
     *                                  over this exact row (see NgcSignedHistory). That is the only
     *                                  evidence that ever makes a relayed row trustworthy: the
     *                                  transport authenticates the peer who RELAYED it, never the
     *                                  author it names.
     */
    static boolean should_mark_unverified_sender(final boolean is_system_message, final GroupMessage m,
                                                 final boolean author_signature_verified)
    {
        return !is_system_message && m != null && m.was_synced && !author_signature_verified;
    }

    /**
     * Shows the "relayed history, sender not verified" marker on the message's status slot.
     *
     * Deliberately fail-safe: any problem here must leave the bubble exactly as it was rather than
     * break a chat row, so it never throws. System messages are excluded — they have no claimed
     * author to be unverified about.
     */
    private void mark_unverified_sender(final boolean is_system_message, final GroupMessage m)
    {
        try
        {
            if (imageView == null)
            {
                return;
            }
            if (!should_mark_unverified_sender(is_system_message, m,
                                               NgcSignedHistory.isAuthorVerified(m)))
            {
                return;
            }
            imageView.setImageResource(R.drawable.circle_orange);
            // Also the hook the UI test and accessibility read; the icon alone says nothing to either.
            imageView.setContentDescription(context.getString(R.string.group_msg_sender_unverified));
            imageView.setVisibility(View.VISIBLE);
        }
        catch (Exception e)
        {
            // never let a decoration take a chat row down
        }
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

    static void showDialog_url(final Context c, final String title, final String url1)
    {
        String url2 = url1;

        // check to see if protocol is specified in URL, otherwise add "http://"
        if (!url2.contains("://"))
        {
            url2 = "https://" + url1;
        }
        final String url = url2;

        final AlertDialog.Builder builder = new AlertDialog.Builder(c);
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

    static void showDialog_email(final Context c, final String title, final String email_addr)
    {
        final AlertDialog.Builder builder = new AlertDialog.Builder(c);
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

    static void showDialog_tox(final Context c, final String title, final String toxid)
    {
        final AlertDialog.Builder builder = new AlertDialog.Builder(c);
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

    class name_test_pk
    {
        boolean changed;
        String tox_peername;
        String text;
        String tox_peerpubkey;
    }

    name_test_pk correct_pubkey(GroupMessage m)
    {
        name_test_pk ret = new name_test_pk();
        ret.changed = false;

        try
        {
            if (m.group_identifier.equals(TOXIRC_TOKTOK_GROUPID))
            {
                try
                {
                    if (m.tox_group_peer_pubkey.equals(TOXIRC_NGC_PUBKEY))
                    {
                        // toxirc messages will be displayed in a special way
                        if (m.text.length() > (3 + 1))
                        {
                            if (m.text.startsWith("<"))
                            {
                                int start_pos = m.text.indexOf("<");
                                int end_pos = m.text.indexOf("> ");

                                if ((start_pos > -1) && (end_pos > -1) && (end_pos > start_pos))
                                {
                                    try
                                    {
                                        String peer_name_corrected = m.text.substring(start_pos + 1, end_pos);

                                        ret.tox_peername = peer_name_corrected;

                                        if (string_is_in_list(peer_name_corrected, PREF__toxirc_muted_peers))
                                        {
                                            ret.text = "** muted **";
                                        }
                                        else
                                        {
                                            ret.text = m.text.substring(end_pos + 2);
                                        }

                                        String new_fake_pubkey = bytesToHex(TrifaSetPatternActivity.sha256(
                                                TrifaSetPatternActivity.StringToBytes2(
                                                        m.tox_group_peer_pubkey + "--" + peer_name_corrected)));

                                        new_fake_pubkey = new_fake_pubkey.substring(1, new_fake_pubkey.length() - 2);
                                        ret.tox_peerpubkey = new_fake_pubkey;
                                        ret.changed = true;
                                    }
                                    catch (Exception e)
                                    {
                                    }
                                }
                            }
                        }
                    }
                }
                catch (Exception ignored)
                {
                }

                try
                {
                    if (ret.tox_peerpubkey.equals(TOXIRC_TOKTOK_IRC_USER_PUBKEY) && ret.changed)
                    {
                        // toktok irc messages will be displayed in a special way
                        if (ret.text.length() > (3 + 1))
                        {
                            if (ret.text.startsWith("<"))
                            {
                                int start_pos = ret.text.indexOf("<");
                                int end_pos = ret.text.indexOf("> ");

                                if ((start_pos > -1) && (end_pos > -1) && (end_pos > start_pos))
                                {
                                    try
                                    {
                                        String peer_name_corrected = ret.text.substring(start_pos + 1, end_pos);

                                        ret.tox_peername = peer_name_corrected;

                                        if (string_is_in_list(peer_name_corrected, PREF__toxirc_muted_peers))
                                        {
                                            ret.text = "** muted **";
                                        }
                                        else
                                        {
                                            ret.text = ret.text.substring(end_pos + 2);
                                        }

                                        String new_fake_pubkey = bytesToHex(TrifaSetPatternActivity.sha256(
                                                TrifaSetPatternActivity.StringToBytes2(
                                                        ret.tox_peerpubkey + "--" + peer_name_corrected)));

                                        new_fake_pubkey = new_fake_pubkey.substring(1, new_fake_pubkey.length() - 2);
                                        ret.tox_peerpubkey = new_fake_pubkey;
                                        ret.changed = true;
                                    }
                                    catch (Exception e)
                                    {
                                    }
                                }
                            }
                        }
                    }
                }
                catch (Exception ignored)
                {
                }
            }
        }
        catch (Exception ignored)
        {
        }
        return ret;
    }
}
