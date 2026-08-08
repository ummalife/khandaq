package com.zoffcc.applications.trifa;

import android.content.Context;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import com.luseen.autolinklibrary.EmojiTextViewLinks;

import com.zoffcc.applications.sorm.GroupMessage;
import com.zoffcc.applications.sorm.Message;

import org.khandaq.messenger.R;

import java.util.List;

import androidx.recyclerview.widget.RecyclerView;

import static com.zoffcc.applications.trifa.MainActivity.PREF__global_font_size;
import static com.zoffcc.applications.trifa.TRIFAGlobals.MESSAGE_TEXT_SIZE;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TRIFA_MSG_TYPE.TRIFA_MSG_FILE;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TRIFA_SYSTEM_MESSAGE_PEER_PUBKEY;
import static com.zoffcc.applications.trifa.ToxVars.TOX_FILE_CONTROL.TOX_FILE_CONTROL_CANCEL;

/**
 * KHANDAQ: Telegram-style media captions (parity with iOS ChatPrivateController "captionMessage").
 * The wire format is unchanged — a captioned photo is still a FILE message followed by a plain TEXT
 * message. A TEXT that arrives right after a FILE (same direction / same group sender, within a
 * short window) is treated as that file's caption: it renders INSIDE the media bubble and its own
 * standalone row is collapsed. Merge only happens while the file row actually shows an inline media
 * preview, otherwise the caption stays a normal bubble (mirrors iOS isFileDisplayedInline).
 */
public class ChatCaptionHelper
{
    private static final long CAPTION_WINDOW_MS = 5000;

    // sender clocks and receive clocks must not be mixed: an incoming msgV3 TEXT can carry the
    // sender's timestamp while the FILE row carries receive time (or vice versa). Accept the pair
    // if EITHER metric places the text 0..5s after the file.
    private static boolean within_window(final long file_ts, final long text_ts)
    {
        final long delta = text_ts - file_ts;
        return (delta >= 0) && (delta <= CAPTION_WINDOW_MS);
    }

    // ---------------------------------- 1:1 chats ----------------------------------

    private static boolean is_caption_pair(final Message file_msg, final Message text_msg)
    {
        if ((file_msg == null) || (text_msg == null))
        {
            return false;
        }
        if (file_msg.TRIFA_MESSAGE_TYPE != TRIFA_MSG_FILE.value)
        {
            return false;
        }
        if (text_msg.TRIFA_MESSAGE_TYPE == TRIFA_MSG_FILE.value)
        {
            return false;
        }
        if ((text_msg.text == null) || (text_msg.text.trim().length() == 0))
        {
            return false;
        }
        // a shared location renders as its own map bubble — never fold it into the
        // previous media's caption (iOS parity: `LocationMessage.parse(text) == nil`)
        if (HelperLocationMessage.parse(text_msg.text) != null)
        {
            return false;
        }
        if (text_msg.direction != file_msg.direction)
        {
            return false;
        }
        if (TRIFA_SYSTEM_MESSAGE_PEER_PUBKEY.equals(text_msg.tox_friendpubkey))
        {
            return false;
        }
        return within_window(file_msg.sent_timestamp, text_msg.sent_timestamp)
               || within_window(file_msg.rcvd_timestamp, text_msg.rcvd_timestamp);
    }

    private static boolean file_shows_inline_media(final Context c, final Message m)
    {
        if ((m == null) || (m.TRIFA_MESSAGE_TYPE != TRIFA_MSG_FILE.value))
        {
            return false;
        }
        if (m.state != TOX_FILE_CONTROL_CANCEL.value)
        {
            return false;
        }
        if (m.filedb_id == -1)
        {
            return false;
        }
        if ((m.direction == 0) && (!HelperFiletransfer.is_message_file_complete(m)))
        {
            return false;
        }
        final String mime = HelperFiletransfer.guess_message_file_mime_type(c, m);
        if (mime == null)
        {
            return false;
        }
        return mime.startsWith("image/") || mime.startsWith("video/");
    }

    static String caption_for_file(final Context c, final List<Message> items, final int pos)
    {
        try
        {
            final Message file_msg = items.get(pos);
            if (!file_shows_inline_media(c, file_msg))
            {
                return null;
            }
            if ((pos + 1) >= items.size())
            {
                return null;
            }
            final Message next = items.get(pos + 1);
            return is_caption_pair(file_msg, next) ? next.text : null;
        }
        catch (Exception e)
        {
            return null;
        }
    }

    static boolean is_merged_caption(final Context c, final List<Message> items, final int pos)
    {
        try
        {
            if (pos < 1)
            {
                return false;
            }
            final Message prev = items.get(pos - 1);
            final Message cur = items.get(pos);
            return is_caption_pair(prev, cur) && file_shows_inline_media(c, prev);
        }
        catch (Exception e)
        {
            return false;
        }
    }

    static void apply_caption_state(final Context c, final List<Message> items, final int pos,
                                    final RecyclerView.ViewHolder holder)
    {
        try
        {
            final Message m = items.get(pos);
            if (m.TRIFA_MESSAGE_TYPE == TRIFA_MSG_FILE.value)
            {
                show_caption_in_file_row(holder, caption_for_file(c, items, pos));
            }
            else
            {
                collapse_text_row(holder, is_merged_caption(c, items, pos));
            }
        }
        catch (Exception ignored)
        {
        }
    }

    // ---------------------------------- NGC groups ----------------------------------

    private static boolean is_group_caption_pair(final GroupMessage file_msg, final GroupMessage text_msg)
    {
        if ((file_msg == null) || (text_msg == null))
        {
            return false;
        }
        if (file_msg.TRIFA_MESSAGE_TYPE != TRIFA_MSG_FILE.value)
        {
            return false;
        }
        if (text_msg.TRIFA_MESSAGE_TYPE == TRIFA_MSG_FILE.value)
        {
            return false;
        }
        if ((text_msg.text == null) || (text_msg.text.trim().length() == 0))
        {
            return false;
        }
        if (HelperLocationMessage.parse(text_msg.text) != null)
        {
            return false;
        }
        if (text_msg.direction != file_msg.direction)
        {
            return false;
        }
        // groups have many senders — a caption must come from the SAME peer as the file
        if ((file_msg.tox_group_peer_pubkey == null) || (text_msg.tox_group_peer_pubkey == null)
            || (!file_msg.tox_group_peer_pubkey.equalsIgnoreCase(text_msg.tox_group_peer_pubkey)))
        {
            return false;
        }
        return within_window(file_msg.sent_timestamp, text_msg.sent_timestamp)
               || within_window(file_msg.rcvd_timestamp, text_msg.rcvd_timestamp);
    }

    private static boolean group_file_is_media_bubble(final Context c, final GroupMessage m)
    {
        // group FT rows always render media-shaped bubbles for image/video (incl. the
        // tap-to-download placeholder), so the caption can merge in every state.
        if ((m == null) || (m.TRIFA_MESSAGE_TYPE != TRIFA_MSG_FILE.value))
        {
            return false;
        }
        return HelperFiletransfer.isGroupImageMessage(c, m) || HelperFiletransfer.isGroupVideoMessage(c, m);
    }

    static String group_caption_for_file(final Context c, final List<GroupMessage> items, final int pos)
    {
        try
        {
            final GroupMessage file_msg = items.get(pos);
            if (!group_file_is_media_bubble(c, file_msg))
            {
                return null;
            }
            if ((pos + 1) >= items.size())
            {
                return null;
            }
            final GroupMessage next = items.get(pos + 1);
            return is_group_caption_pair(file_msg, next) ? next.text : null;
        }
        catch (Exception e)
        {
            return null;
        }
    }

    static boolean is_merged_group_caption(final Context c, final List<GroupMessage> items, final int pos)
    {
        try
        {
            if (pos < 1)
            {
                return false;
            }
            final GroupMessage prev = items.get(pos - 1);
            final GroupMessage cur = items.get(pos);
            return is_group_caption_pair(prev, cur) && group_file_is_media_bubble(c, prev);
        }
        catch (Exception e)
        {
            return false;
        }
    }

    static void apply_group_caption_state(final Context c, final List<GroupMessage> items, final int pos,
                                          final RecyclerView.ViewHolder holder)
    {
        try
        {
            final GroupMessage m = items.get(pos);
            if (m.TRIFA_MESSAGE_TYPE == TRIFA_MSG_FILE.value)
            {
                show_caption_in_file_row(holder, group_caption_for_file(c, items, pos));
            }
            else
            {
                collapse_text_row(holder, is_merged_group_caption(c, items, pos));
            }
        }
        catch (Exception ignored)
        {
        }
    }

    // ---------------------------------- view plumbing ----------------------------------

    private static void show_caption_in_file_row(final RecyclerView.ViewHolder holder, final String caption)
    {
        final EmojiTextViewLinks tv = holder.itemView.findViewById(R.id.ft_caption_text);
        if (tv == null)
        {
            return;
        }
        if ((caption == null) || (caption.trim().length() == 0))
        {
            tv.setVisibility(View.GONE);
            return;
        }
        tv.setText(caption);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, MESSAGE_TEXT_SIZE[PREF__global_font_size]);
        // KHANDAQ (#emoji-caption): render the caption with the app's Google-sprite emoji (ft_caption_text
        // is now an EmojiTextViewLinks, not a plain TextView that fell back to the device font) and boost a
        // single-emoji caption to the sticker size — so 🦍 sent with a photo shows the SAME emoji the user
        // typed, at a readable size, instead of a tiny device-native glyph that looked like a different one.
        ChatBubbleUiHelper.configure_message_emoji_size(tv, caption, PREF__global_font_size, false);
        tv.setVisibility(View.VISIBLE);
    }

    private static void collapse_text_row(final RecyclerView.ViewHolder holder, final boolean collapse)
    {
        final View v = holder.itemView;
        final ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp == null)
        {
            return;
        }
        if (collapse)
        {
            lp.height = 0;
            v.setVisibility(View.GONE);
        }
        else if (lp.height == 0)
        {
            // recycled holder that was collapsed before — restore
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            v.setVisibility(View.VISIBLE);
        }
        v.setLayoutParams(lp);
    }
}
