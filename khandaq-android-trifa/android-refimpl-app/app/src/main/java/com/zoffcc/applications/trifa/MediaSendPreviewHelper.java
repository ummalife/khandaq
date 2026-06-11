package com.zoffcc.applications.trifa;

import org.khandaq.messenger.R;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

import androidx.documentfile.provider.DocumentFile;

/**
 * Routes gallery picks through {@link MediaSendPreviewActivity} before sending.
 */
public final class MediaSendPreviewHelper
{
    private static final String TAG = "trifa.MediaSendPreview";

    public static final int REQUEST_PREVIEW = 8010;

    public static final String EXTRA_TARGET = "media_send_target";
    public static final String EXTRA_FRIENDNUM = "media_send_friendnum";
    public static final String EXTRA_GROUP_ID = "media_send_group_id";
    public static final String EXTRA_ACTIVITY_PEER = "media_send_activity_peer";
    public static final String EXTRA_URI_LIST = "media_send_uri_list";
    public static final String EXTRA_CAPTION = "media_send_caption";

    public static final String TARGET_FRIEND = "friend";
    public static final String TARGET_GROUP = "group";

    private MediaSendPreviewHelper()
    {
    }

    public static void configureGalleryPickerIntent(final Intent intent)
    {
        if (intent == null)
        {
            return;
        }
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
    }

    /** @return true if preview activity was started */
    public static boolean launchPreviewIfNeeded(final Activity activity, final Intent data,
                                                final String target, final long friendnum,
                                                final String groupId, final boolean activityPeer)
    {
        final List<Uri> uris = collectUris(data);
        if (uris.isEmpty())
        {
            return false;
        }

        if (!allPreviewableMedia(activity, uris))
        {
            return false;
        }

        final Intent preview = new Intent(activity, MediaSendPreviewActivity.class);
        preview.putParcelableArrayListExtra(EXTRA_URI_LIST, new ArrayList<>(uris));
        preview.putExtra(EXTRA_TARGET, target);
        preview.putExtra(EXTRA_FRIENDNUM, friendnum);
        preview.putExtra(EXTRA_GROUP_ID, groupId);
        preview.putExtra(EXTRA_ACTIVITY_PEER, activityPeer);
        activity.startActivityForResult(preview, REQUEST_PREVIEW);
        return true;
    }

    public static void handlePreviewResult(final Activity activity, final Intent data,
                                           final String target, final long friendnum,
                                           final String groupId, final boolean activityPeer)
    {
        if (data == null)
        {
            return;
        }

        final ArrayList<Uri> uris = data.getParcelableArrayListExtra(EXTRA_URI_LIST);
        if (uris == null || uris.isEmpty())
        {
            return;
        }

        final String caption = data.getStringExtra(EXTRA_CAPTION);
        sendConfirmedMedia(activity, uris, caption, target, friendnum, groupId, activityPeer);
    }

    static void sendConfirmedMedia(final Context context, final List<Uri> uris, final String caption,
                                   final String target, final long friendnum, final String groupId,
                                   final boolean activityPeer)
    {
        for (Uri uri : uris)
        {
            if (uri == null)
            {
                continue;
            }

            final Intent single = new Intent();
            single.setData(uri);
            single.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            if (TARGET_GROUP.equals(target))
            {
                GroupMessageListActivity.add_attachment_ngc(context, single, single, groupId, activityPeer);
            }
            else
            {
                MessageListActivity.add_attachment(context, single, single, friendnum, activityPeer);
            }
        }

        if (caption == null || caption.trim().isEmpty())
        {
            return;
        }

        try
        {
            if (TARGET_GROUP.equals(target))
            {
                sendGroupCaption(context, groupId, caption.trim());
            }
            else
            {
                sendFriendCaption(context, friendnum, activityPeer, caption.trim());
            }
        }
        catch (Exception e)
        {
            Log.i(TAG, "sendConfirmedMedia:caption:EE:" + e.getMessage());
        }
    }

    private static void sendFriendCaption(final Context context, final long friendnum,
                                          final boolean activityPeer, final String caption)
    {
        if (activityPeer && MainActivity.message_list_activity != null)
        {
            final String pk = MainActivity.message_list_activity.get_friend_pubkey();
            if (pk != null && !pk.isEmpty())
            {
                MainActivity.message_list_activity.send_text_message(pk, caption);
            }
            return;
        }

        if (friendnum >= 0 && context instanceof MessageListActivity)
        {
            final String pk = HelperFriend.tox_friend_get_public_key__wrapper(friendnum);
            if (pk != null && !pk.isEmpty())
            {
                ((MessageListActivity) context).send_text_message(pk, caption);
            }
        }
    }

    private static void sendGroupCaption(final Context context, final String groupId, final String caption)
    {
        if (groupId == null || groupId.isEmpty() || "-1".equals(groupId))
        {
            return;
        }

        if (MainActivity.group_message_list_activity != null)
        {
            MainActivity.group_message_list_activity.send_text_message_from_preview(caption);
            return;
        }

        if (context instanceof GroupMessageListActivity)
        {
            ((GroupMessageListActivity) context).send_text_message_from_preview(caption);
        }
    }

    public static List<Uri> collectUris(final Intent data)
    {
        final List<Uri> uris = new ArrayList<>();
        if (data == null)
        {
            return uris;
        }

        if (data.getClipData() != null)
        {
            final int count = data.getClipData().getItemCount();
            for (int i = 0; i < count; i++)
            {
                final Uri uri = data.getClipData().getItemAt(i).getUri();
                if (uri != null)
                {
                    uris.add(uri);
                }
            }
        }

        if (uris.isEmpty() && data.getData() != null)
        {
            uris.add(data.getData());
        }

        return uris;
    }

    static boolean allPreviewableMedia(final Context context, final List<Uri> uris)
    {
        if (uris.isEmpty())
        {
            return false;
        }

        for (Uri uri : uris)
        {
            if (!isPreviewableMedia(context, uri))
            {
                return false;
            }
        }
        return true;
    }

    static boolean isPreviewableMedia(final Context context, final Uri uri)
    {
        if (context == null || uri == null)
        {
            return false;
        }

        try
        {
            final String mime = context.getContentResolver().getType(uri);
            if (mime != null)
            {
                return mime.startsWith("image/") || mime.startsWith("video/");
            }
        }
        catch (Exception ignored)
        {
        }

        try
        {
            final DocumentFile doc = DocumentFile.fromSingleUri(context, uri);
            if (doc != null)
            {
                final String mime = doc.getType();
                if (mime != null)
                {
                    return mime.startsWith("image/") || mime.startsWith("video/");
                }
                final String name = doc.getName();
                if (name != null)
                {
                    final String lower = name.toLowerCase();
                    return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                            || lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".heic")
                            || lower.endsWith(".mp4") || lower.endsWith(".mov") || lower.endsWith(".mkv")
                            || lower.endsWith(".webm") || lower.endsWith(".3gp");
                }
            }
        }
        catch (Exception ignored)
        {
        }

        return false;
    }

    static boolean isVideoUri(final Context context, final Uri uri)
    {
        try
        {
            final String mime = context.getContentResolver().getType(uri);
            if (mime != null)
            {
                return mime.startsWith("video/");
            }
        }
        catch (Exception ignored)
        {
        }

        try
        {
            final DocumentFile doc = DocumentFile.fromSingleUri(context, uri);
            if (doc != null && doc.getType() != null)
            {
                return doc.getType().startsWith("video/");
            }
        }
        catch (Exception ignored)
        {
        }

        return false;
    }
}
