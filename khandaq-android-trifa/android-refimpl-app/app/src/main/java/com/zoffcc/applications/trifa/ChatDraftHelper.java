package com.zoffcc.applications.trifa;

import org.khandaq.messenger.R;

import android.content.Context;
import android.text.TextUtils;

import static com.zoffcc.applications.trifa.HelperGeneric.del_g_opts;
import static com.zoffcc.applications.trifa.HelperGeneric.get_g_opts;
import static com.zoffcc.applications.trifa.HelperGeneric.normalize_chat_input_text;
import static com.zoffcc.applications.trifa.HelperGeneric.set_g_opts;

final class ChatDraftHelper
{
    private static final String DRAFT_KEY_FRIEND_PREFIX = "draft_f_";
    private static final String DRAFT_KEY_GROUP_PREFIX = "draft_g_";
    private static final int PREVIEW_MAX_CHARS = 120;

    private ChatDraftHelper()
    {
    }

    static void save_friend_draft(final String friend_pubkey, final String text)
    {
        save_draft(draft_key_for_friend(friend_pubkey), text);
    }

    static void save_group_draft(final String group_identifier, final String text)
    {
        save_draft(draft_key_for_group(group_identifier), text);
    }

    static String load_friend_draft(final String friend_pubkey)
    {
        return load_draft(draft_key_for_friend(friend_pubkey));
    }

    static String load_group_draft(final String group_identifier)
    {
        return load_draft(draft_key_for_group(group_identifier));
    }

    static void clear_friend_draft(final String friend_pubkey)
    {
        del_g_opts(draft_key_for_friend(friend_pubkey));
    }

    static void clear_group_draft(final String group_identifier)
    {
        del_g_opts(draft_key_for_group(group_identifier));
    }

    static boolean has_friend_draft(final String friend_pubkey)
    {
        return !TextUtils.isEmpty(load_friend_draft(friend_pubkey));
    }

    static boolean has_group_draft(final String group_identifier)
    {
        return !TextUtils.isEmpty(load_group_draft(group_identifier));
    }

    static String format_draft_preview(final Context context, final String draft_text)
    {
        String body = draft_text.replace('\n', ' ').trim();
        if (body.length() > PREVIEW_MAX_CHARS)
        {
            body = body.substring(0, PREVIEW_MAX_CHARS - 3) + "...";
        }
        return context.getString(R.string.chat_list_preview_draft, body);
    }

    private static void save_draft(final String key, final String text)
    {
        if (TextUtils.isEmpty(key))
        {
            return;
        }

        final String normalized = normalize_chat_input_text(text);
        if (TextUtils.isEmpty(normalized))
        {
            del_g_opts(key);
        }
        else
        {
            set_g_opts(key, normalized);
        }
    }

    private static String load_draft(final String key)
    {
        if (TextUtils.isEmpty(key))
        {
            return null;
        }

        final String value = get_g_opts(key);
        if (TextUtils.isEmpty(value))
        {
            return null;
        }

        return value;
    }

    private static String draft_key_for_friend(final String friend_pubkey)
    {
        if (TextUtils.isEmpty(friend_pubkey))
        {
            return null;
        }

        return DRAFT_KEY_FRIEND_PREFIX + friend_pubkey.toUpperCase();
    }

    private static String draft_key_for_group(final String group_identifier)
    {
        if (TextUtils.isEmpty(group_identifier))
        {
            return null;
        }

        return DRAFT_KEY_GROUP_PREFIX + group_identifier.toLowerCase();
    }
}
