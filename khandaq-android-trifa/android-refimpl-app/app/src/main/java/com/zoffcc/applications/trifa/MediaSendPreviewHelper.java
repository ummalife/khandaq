package com.zoffcc.applications.trifa;

import org.khandaq.messenger.R;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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

    /**
     * KHANDAQ (Figma attachments — no Video/Photos/Audio tabs): build the cleanest media picker the
     * device offers — the modern system Photo Picker on Android 13+ (single clean grid, no tabs),
     * otherwise a media-only document picker (image/* + video/*, no Audio/Files tabs). Results come
     * back the same way (single = getData, multi = getClipData), so onActivityResult is unchanged.
     */
    public static Intent buildMediaPickerIntent(final Context context)
    {
        return buildMediaPickerIntent(context, Integer.MAX_VALUE);
    }

    /**
     * KHANDAQ (Khandaq Testers 8265): the same picker for at most {@code room} more items, for "Add" on the send
     * screen. Integer.MAX_VALUE keeps the picker's own limit, as the chat opens it.
     */
    static Intent buildMediaPickerIntent(final Context context, final int room)
    {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU)
        {
            final Intent intent = new Intent(android.provider.MediaStore.ACTION_PICK_IMAGES);
            try
            {
                final int max = pickImagesMax(room, android.provider.MediaStore.getPickImagesMaxLimit());
                if (max > 0)
                {
                    intent.putExtra(android.provider.MediaStore.EXTRA_PICK_IMAGES_MAX, max);
                }
            }
            catch (Throwable ignored)
            {
            }
            return intent;
        }
        final Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, room > 1);
        intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        return intent;
    }

    /**
     * KHANDAQ (Khandaq Testers 8265): the photo picker's EXTRA_PICK_IMAGES_MAX for a room, or 0 to leave it out.
     * The picker takes a limit above 1 and up to its own only, and cancels on any other, so a room of one picks
     * a single item, which is what it does without the extra.
     */
    static int pickImagesMax(final int room, final int pickerLimit)
    {
        final int max = Math.min(room, pickerLimit);
        return (max > 1) ? max : 0;
    }

    /**
     * KHANDAQ (Khandaq Testers 8265): the photo picker's own limit, which bounds one send as it bounded the first
     * pick; 0 where there is none (the document picker before Android 13).
     */
    static int sendItemLimit()
    {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU)
        {
            try
            {
                return Math.max(0, android.provider.MediaStore.getPickImagesMaxLimit());
            }
            catch (Throwable ignored)
            {
            }
        }
        return 0;
    }

    /** KHANDAQ (Khandaq Testers 8265): how many more items a send of {@code selected} takes; a limit of 0 is none. */
    static int addMoreRoom(final int selected, final int limit)
    {
        if (limit <= 0)
        {
            return Integer.MAX_VALUE;
        }
        return Math.max(0, limit - Math.max(0, selected));
    }

    /**
     * KHANDAQ (Khandaq Testers 8265): what "Add" brings to a send: the picked items it does not hold yet (a picker
     * may offer a chosen photo again), once each, in the order picked, as many as its room takes.
     */
    static <T> List<T> itemsToAdd(final List<T> current, final List<T> picked, final int room,
                                  final java.util.function.Function<T, String> key)
    {
        final List<T> out = new ArrayList<>();
        if ((picked == null) || (room <= 0))
        {
            return out;
        }
        final Set<String> seen = new LinkedHashSet<>();
        if (current != null)
        {
            for (final T item : current)
            {
                if (item != null)
                {
                    seen.add(key.apply(item));
                }
            }
        }
        for (final T item : picked)
        {
            if (out.size() >= room)
            {
                break;
            }
            if ((item != null) && seen.add(key.apply(item)))
            {
                out.add(item);
            }
        }
        return out;
    }

    /** KHANDAQ (Figma attachments grid): is the device media-read permission granted? */
    public static boolean hasMediaReadPermission(final Context context)
    {
        if (context == null)
        {
            return false;
        }
        final String perm = (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU)
                ? android.Manifest.permission.READ_MEDIA_IMAGES
                : android.Manifest.permission.READ_EXTERNAL_STORAGE;
        return context.checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    /** Recent device images + videos (newest first), as content Uris, for the in-app attach grid. */
    public static ArrayList<Uri> queryRecentMedia(final Context context, final int limit)
    {
        final ArrayList<Uri> out = new ArrayList<>();
        if (context == null || !hasMediaReadPermission(context))
        {
            return out;
        }
        android.database.Cursor c = null;
        try
        {
            final Uri collection = android.provider.MediaStore.Files.getContentUri("external");
            final String[] projection = {
                    android.provider.MediaStore.Files.FileColumns._ID,
                    android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE
            };
            final String selection = android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE + "=? OR "
                    + android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE + "=?";
            final String[] args = {
                    String.valueOf(android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE),
                    String.valueOf(android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO)
            };
            final String sort = android.provider.MediaStore.Files.FileColumns.DATE_MODIFIED + " DESC";
            c = context.getContentResolver().query(collection, projection, selection, args, sort);
            if (c != null)
            {
                final int idCol = c.getColumnIndexOrThrow(android.provider.MediaStore.Files.FileColumns._ID);
                final int typeCol = c.getColumnIndexOrThrow(android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE);
                while (c.moveToNext() && out.size() < limit)
                {
                    final long id = c.getLong(idCol);
                    final int type = c.getInt(typeCol);
                    final Uri base = (type == android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO)
                            ? android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                            : android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                    out.add(android.content.ContentUris.withAppendedId(base, id));
                }
            }
        }
        catch (Exception e)
        {
            Log.i(TAG, "queryRecentMedia:EE:" + e.getMessage());
        }
        finally
        {
            if (c != null)
            {
                try { c.close(); } catch (Exception ignored) { }
            }
        }
        return out;
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

        persistUriPermissions(activity, uris);

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
        grantUriReadPermissions(preview, activity, uris);
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

        persistUriPermissions(activity, uris);

        final String caption = data.getStringExtra(EXTRA_CAPTION);
        sendConfirmedMedia(activity, uris, caption, target, friendnum, groupId, activityPeer);
    }

    static void sendConfirmedMedia(final Context context, final List<Uri> uris, final String caption,
                                   final String target, final long friendnum, final String groupId,
                                   final boolean activityPeer)
    {
        final String captionText = (caption == null || caption.trim().isEmpty()) ? null : caption.trim();

        // KHANDAQ: GROUP captions must wait for the TRANSFER, not a fixed delay. An NGC receiver
        // only materializes the file bubble once the whole file has arrived, so a caption sent
        // 1.2s after initiation lands long before the photo and renders ABOVE it (tester: "the
        // emoji arrives first, then the photo separately"). The queue sends the caption after the
        // batch's last transfer completes (or fails / times out).
        if (TARGET_GROUP.equals(target))
        {
            final List<Uri> groupUris = new ArrayList<>(uris.size());
            for (Uri uri : uris)
            {
                if (uri != null)
                {
                    groupUris.add(uri);
                }
            }
            OutgoingAttachmentQueue.enqueueGroupUris(context, groupUris, groupId, activityPeer, captionText);
            return;
        }

        // KHANDAQ (0.2.56): the files picked here are one batch: the caption follows them (below).
        final OutgoingFilePreparation.Batch batch = OutgoingFilePreparation.ACTIVE.openBatch();
        try
        {
            dispatchAttachments(context, uris, target, friendnum, groupId, activityPeer);
        }
        finally
        {
            OutgoingFilePreparation.ACTIVE.closeBatch(batch);
        }

        if (captionText == null)
        {
            return;
        }

        // KHANDAQ (#127): the caption must not hit the wire before the file is initiated: the receiver stamps
        // each message on arrival and renders the caption ABOVE the photo. Tox keeps per-friend order, so
        // the file's offer (a fast control packet) going first is enough. (1:1 only — groups take the
        // transfer-completion path above.)
        // KHANDAQ (0.2.56): the caption went 1.2 s after Send, but a large video is copied for many seconds
        // before its transfer is queued, so it still went first. It now goes after the copies of its files
        // have ended and their transfers are queued (MessageListActivity.after_outgoing_file_preparations),
        // and not when none of them was. The text is composed now, with the reply it answers; when it goes
        // the field may hold a new text, which stays.
        final String pk = friendCaptionPubkey(context, friendnum, activityPeer);
        if (pk == null)
        {
            return;
        }
        final String body = HelperGeneric.normalize_chat_input_text(captionText);
        final String fullText = ChatReplyPreviewController.composeOutgoingText(body);
        MessageListActivity.after_outgoing_file_preparations(() ->
        {
            try
            {
                if (batch.captionGoes())
                {
                    MessageListActivity.send_caption_text(pk, body, fullText);
                }
                else
                {
                    Log.i(TAG, "sendConfirmedMedia:caption:no file of it was queued");
                }
            }
            catch (Exception e)
            {
                Log.i(TAG, "sendConfirmedMedia:caption:EE:" + e.getMessage());
            }
        });
    }

    /** Send picked URIs through the legacy attachment path (async friendnum wait, proven stable). */
    public static void dispatchAttachments(final Context context, final List<Uri> uris,
                                           final String target, final long friendnum,
                                           final String groupId, final boolean activityPeer)
    {
        if (context == null || uris == null || uris.isEmpty())
        {
            return;
        }

        // KHANDAQ (#85): for a GROUP, enqueue ALL picked URIs as ONE batch so the sequential
        // group-send worker (stageGroupBatch size>1 branch) serializes them — one file fully sent
        // before the next starts. The previous per-URI enqueueGroupUri fired N immediate sends
        // back-to-back, and send_group_file's cancelStaleOutgoingChunkedSends then killed every
        // in-flight sibling, so only the LAST picked photo/video survived.
        if (TARGET_GROUP.equals(target))
        {
            final List<Uri> groupUris = new ArrayList<>(uris.size());
            for (Uri uri : uris)
            {
                if (uri != null)
                {
                    groupUris.add(uri);
                }
            }
            OutgoingAttachmentQueue.enqueueGroupUris(context, groupUris, groupId, activityPeer);
            return;
        }

        for (Uri uri : uris)
        {
            if (uri == null)
            {
                continue;
            }

            final Intent single = new Intent();
            single.setData(uri);
            single.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            MessageListActivity.add_attachment(context, single, single, friendnum, activityPeer);
        }
    }

    public static void persistUriPermissions(final Context context, final List<Uri> uris)
    {
        if (!(context instanceof Activity) || uris == null)
        {
            return;
        }

        final Activity activity = (Activity) context;
        for (Uri uri : uris)
        {
            if (uri == null)
            {
                continue;
            }
            try
            {
                activity.getContentResolver().takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
            catch (Exception ignored)
            {
            }
        }
    }

    static void grantUriReadPermissions(final Intent intent, final Context context, final List<Uri> uris)
    {
        if (intent == null || context == null || uris == null || uris.isEmpty())
        {
            return;
        }

        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        final ClipData clip = ClipData.newUri(context.getContentResolver(), "media", uris.get(0));
        for (int i = 1; i < uris.size(); i++)
        {
            clip.addItem(new ClipData.Item(uris.get(i)));
        }
        intent.setClipData(clip);
    }

    /**
     * KHANDAQ (Khandaq Testers 8265): the read grants of a send, handed back to the chat with the result. Items
     * picked by "Add" came with grants the send screen holds, which end with it, before the chat has copied
     * them, wherever the system keeps no lasting grant. Only content URIs travel in the clip: a photo cropped
     * here is a file of this app and needs none, and a file URI in an intent's clip is refused.
     */
    static void grantContentUriReadPermissions(final Intent intent, final Context context, final List<Uri> uris)
    {
        if (uris == null)
        {
            return;
        }
        final List<Uri> content = new ArrayList<>(uris.size());
        for (final Uri uri : uris)
        {
            if ((uri != null) && android.content.ContentResolver.SCHEME_CONTENT.equals(uri.getScheme()))
            {
                content.add(uri);
            }
        }
        try
        {
            grantUriReadPermissions(intent, context, content);
        }
        catch (Exception e)
        {
            // The send goes on: a grant the system made lasting (persistUriPermissions) still holds.
            Log.i(TAG, "grantContentUriReadPermissions:EE:" + e.getMessage());
        }
    }

    /** The chat a caption of media sent from the preview goes to, by the same rules as its files; null for none. */
    private static String friendCaptionPubkey(final Context context, final long friendnum,
                                              final boolean activityPeer)
    {
        String pk = null;
        if (activityPeer && MainActivity.message_list_activity != null)
        {
            pk = MainActivity.message_list_activity.get_friend_pubkey();
        }
        else if (friendnum >= 0 && context instanceof MessageListActivity)
        {
            pk = HelperFriend.tox_friend_get_public_key__wrapper(friendnum);
        }
        return (pk == null || pk.isEmpty()) ? null : pk;
    }

    // package-private: OutgoingAttachmentQueue delivers group captions after the transfer finishes.
    // KHANDAQ: sends DIRECTLY into groupId — the caption may fire up to 90s after the pick (it waits
    // for the file broadcast), and the group activity might be gone or showing a DIFFERENT group by
    // then, so routing through the activity could drop the text or post it into the wrong chat.
    static void sendGroupCaption(final Context context, final String groupId, final String caption)
    {
        if (groupId == null || groupId.isEmpty() || "-1".equals(groupId)
                || caption == null || caption.trim().isEmpty())
        {
            return;
        }

        try
        {
            TrifaToxService.wakeup_tox_thread();

            // KHANDAQ (0.2.56): a caption longer than one group message goes as several, as a long text from
            // the field does (AirGroupModel.textParts). It was cut at tox_max_message_length() chars, which
            // lost its end, and a Cyrillic caption of that many chars was twice the bytes toxcore takes, so
            // it was refused and not sent at all.
            final String trimmed = caption.trim();
            final List<int[]> parts = AirGroupModel.textParts(trimmed, trimmed);

            final long group_num = HelperGroup.tox_group_by_groupid__wrapper(groupId);
            final long first_sent_timestamp = System.currentTimeMillis();
            for (int i = 0; i < parts.size(); i++)
            {
                final String msg = trimmed.substring(parts.get(i)[0], parts.get(i)[1]);
                final long message_id = HelperGroup.send_group_text_message_resilient(groupId, group_num, msg);
                final boolean queued = (message_id == HelperGroup.GROUP_SEND_QUEUE_WHEN_UNCONNECTED);
                if (!queued && (message_id < 0))
                {
                    Log.i(TAG, "sendGroupCaption:not sent res=" + message_id + " part=" + i + "/" + parts.size());
                    break;
                }
                // A part that waits for the group takes the parts after it along, so they go in order.
                final int last = queued ? parts.size() - 1 : i;
                for (int j = i; j <= last; j++)
                {
                    // KHANDAQ (QA 02.10): record the caption on this side whether it went out now or was
                    // queued. Only the queued case used to be written down, so a caption sent straight away
                    // reached the group but never appeared in the sender's own chat, not even after
                    // reopening it (the group activity's own send path has always recorded both).
                    final com.zoffcc.applications.sorm.GroupMessage m = new com.zoffcc.applications.sorm.GroupMessage();
                    m.is_new = false;
                    m.tox_group_peer_pubkey = MainActivity.tox_group_self_get_public_key(group_num).toUpperCase();
                    m.direction = 1;
                    m.TOX_MESSAGE_TYPE = 0;
                    m.read = true;
                    m.tox_group_peername = null;
                    m.private_message = 0;
                    m.group_identifier = groupId.toLowerCase();
                    m.TRIFA_MESSAGE_TYPE = TRIFAGlobals.TRIFA_MSG_TYPE.TRIFA_MSG_TYPE_TEXT.value;
                    m.sent_timestamp = first_sent_timestamp + j;
                    m.rcvd_timestamp = m.sent_timestamp;
                    m.text = trimmed.substring(parts.get(j)[0], parts.get(j)[1]);
                    m.was_synced = false;
                    m.TRIFA_SYNC_TYPE = TRIFAGlobals.TRIFA_SYNC_TYPE.TRIFA_SYNC_TYPE_NONE.value;
                    m.message_id_tox = queued ? HelperGroup.PENDING_GROUP_MESSAGE_ID_TOX
                                              : HelperGeneric.fourbytes_of_long_to_hex(message_id);
                    HelperGroup.insert_into_group_message_db(m, true);
                }
                if (queued)
                {
                    HelperGroup.schedule_pending_group_message_flush(groupId);
                    break;
                }
            }
        }
        catch (Exception e)
        {
            Log.i(TAG, "sendGroupCaption:EE:" + e.getMessage());
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

        final List<Uri> deduped = new ArrayList<>();
        final Set<String> seen = new LinkedHashSet<>();
        for (Uri uri : uris)
        {
            if (uri == null)
            {
                continue;
            }
            if (seen.add(uri.toString()))
            {
                deduped.add(uri);
            }
        }

        return deduped;
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
