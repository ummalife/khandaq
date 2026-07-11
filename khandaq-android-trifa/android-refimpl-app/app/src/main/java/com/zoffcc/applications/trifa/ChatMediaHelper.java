package com.zoffcc.applications.trifa;

import org.khandaq.messenger.R;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;

import com.bumptech.glide.Priority;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.bumptech.glide.request.RequestOptions;
import com.mikepenz.google_material_typeface_library.GoogleMaterial;
import com.mikepenz.iconics.IconicsDrawable;
import com.zoffcc.applications.sorm.GroupMessage;
import com.zoffcc.applications.sorm.Message;

import java.io.File;

import static com.zoffcc.applications.trifa.HelperFiletransfer.guess_group_message_file_mime_type;
import static com.zoffcc.applications.trifa.HelperFiletransfer.guess_message_file_mime_type;
import static com.zoffcc.applications.trifa.HelperFiletransfer.isIocipherVirtualPath;
import static com.zoffcc.applications.trifa.HelperFiletransfer.isGroupMessageMediaOpenable;
import static com.zoffcc.applications.trifa.HelperFiletransfer.isGroupMessageMediaReady;
import static com.zoffcc.applications.trifa.HelperFiletransfer.isGroupVoiceMessage;
import static com.zoffcc.applications.trifa.HelperFiletransfer.isMediaFileReadyToView;
import static com.zoffcc.applications.trifa.HelperFiletransfer.isMessageMediaReady;
import static com.zoffcc.applications.trifa.HelperFiletransfer.isVoiceMessage;
import static com.zoffcc.applications.trifa.HelperGeneric.copy_vfs_file_to_real_file;
import static com.zoffcc.applications.trifa.HelperGeneric.dp2px;
import static com.zoffcc.applications.trifa.MainActivity.SD_CARD_TMP_DIR;
import static com.zoffcc.applications.trifa.MainActivity.VFS_ENCRYPT;

public final class ChatMediaHelper
{
    private static final String TAG = "trifa.ChatMediaHelper";

    public static final String EXTRA_MODE = "media_mode";
    public static final String EXTRA_VFS_PATH = "vfs_path";
    public static final String EXTRA_EXPORT_PATH = "export_path";
    public static final String EXTRA_STORAGE_FRAMEWORK = "storage_frame_work";
    public static final String EXTRA_MESSAGE_ID = "message_id";
    public static final String EXTRA_MIME_TYPE = "mime_type";
    // KHANDAQ (Figma): sender name + timestamp shown in the media viewer overlay bar.
    public static final String EXTRA_SENDER_NAME = "sender_name";
    public static final String EXTRA_TIMESTAMP = "timestamp_millis";

    public static final String MODE_IMAGE = "image";
    public static final String MODE_VIDEO = "video";

    private ChatMediaHelper()
    {
    }

    // 1:1 sender: incoming (direction 0) = friend's display name; outgoing = own name.
    private static String senderNameForMessage(final Message message)
    {
        if (message == null)
        {
            return "";
        }
        if (message.direction == 0)
        {
            final String name = HelperFriend.get_friend_name_from_pubkey(message.tox_friendpubkey);
            return (name == null) ? "" : name;
        }
        return (TRIFAGlobals.global_my_name == null) ? "" : TRIFAGlobals.global_my_name;
    }

    // timestampMillis: Message.sent_timestamp is already in milliseconds (System.currentTimeMillis()).
    static void putMediaMeta(final Intent intent, final String senderName, final long timestampMillis)
    {
        if (intent == null)
        {
            return;
        }
        intent.putExtra(EXTRA_SENDER_NAME, (senderName == null) ? "" : senderName);
        intent.putExtra(EXTRA_TIMESTAMP, timestampMillis);
    }

    // KHANDAQ (Figma): fills the media viewer top overlay (back + sender name + date).
    static void bindMediaOverlay(final android.app.Activity activity)
    {
        if (activity == null)
        {
            return;
        }
        final View backButton = activity.findViewById(R.id.media_overlay_back);
        if (backButton != null)
        {
            backButton.setOnClickListener(v -> activity.finish());
        }

        final Intent intent = activity.getIntent();
        final String senderName = (intent == null) ? "" : intent.getStringExtra(EXTRA_SENDER_NAME);
        final long timestampMillis = (intent == null) ? 0L : intent.getLongExtra(EXTRA_TIMESTAMP, 0L);

        final android.widget.TextView nameView = activity.findViewById(R.id.media_overlay_name);
        if (nameView != null)
        {
            if ((senderName != null) && (!senderName.isEmpty()))
            {
                nameView.setText(senderName);
                nameView.setVisibility(View.VISIBLE);
            }
            else
            {
                nameView.setVisibility(View.GONE);
            }
        }

        final android.widget.TextView dateView = activity.findViewById(R.id.media_overlay_date);
        if (dateView != null)
        {
            if (timestampMillis > 0L)
            {
                dateView.setText(android.text.format.DateUtils.formatDateTime(activity, timestampMillis,
                        android.text.format.DateUtils.FORMAT_SHOW_DATE
                                | android.text.format.DateUtils.FORMAT_SHOW_TIME
                                | android.text.format.DateUtils.FORMAT_ABBREV_ALL));
                dateView.setVisibility(View.VISIBLE);
            }
            else
            {
                dateView.setVisibility(View.GONE);
            }
        }
    }

    public static void openMessageMedia(final Context context, final Message message, final String exportPath)
    {
        if ((context == null) || (message == null))
        {
            return;
        }

        if (!ensureMessageMediaOpenable(context, message, exportPath))
        {
            return;
        }

        final String mimeType = guess_message_file_mime_type(context, message);

        if ((mimeType != null) && mimeType.startsWith("image/"))
        {
            openImageViewer(context, message, exportPath, mimeType);
            return;
        }

        if ((mimeType != null) && mimeType.startsWith("video/"))
        {
            openVideoViewer(context, message.filename_fullpath, message.id, message.storage_frame_work, exportPath,
                            mimeType, senderNameForMessage(message), message.sent_timestamp);
            return;
        }

        if (exportPath != null)
        {
            HelperFiletransfer.open_local_file(exportPath, context);
            return;
        }

        if (VFS_ENCRYPT)
        {
            try
            {
                final Uri uri = Uri.parse(IOCipherContentProvider.FILES_URI + message.filename_fullpath);
                final Intent sendIntent = new Intent(Intent.ACTION_VIEW, uri);
                context.startActivity(sendIntent);
            }
            catch (Exception e)
            {
                Log.i(TAG, "openMessageMedia:fallback:EE:" + e.getMessage());
                HelperGeneric.display_toast(context.getString(R.string.chat_opening_file_failed), false, 0);
            }
        }
    }

    public static void openImageViewer(Context context, Message message, String exportPath, String mimeType)
    {
        if ((context == null) || (message == null))
        {
            return;
        }

        if (!isMediaFileReadyToView(message.filename_fullpath, message.storage_frame_work, exportPath))
        {
            showMediaNotReady(context, message, null, exportPath);
            return;
        }

        final String sn = senderNameForMessage(message);
        final long ts = message.sent_timestamp;

        try
        {
            if ((message.storage_frame_work) && (exportPath == null))
            {
                final Intent intent = new Intent(context, ImageviewerActivity_SD.class);
                putMediaMeta(intent, sn, ts);
                intent.putExtra("image_filename", message.filename_fullpath);
                intent.putExtra("image_cache_key", message.filename_fullpath + "#" + message.id);
                intent.putExtra("storage_frame_work", "1");
                context.startActivity(intent);
                return;
            }

            if (exportPath != null)
            {
                final Intent intent = new Intent(context, ImageviewerActivity_SD.class);
                putMediaMeta(intent, sn, ts);
                intent.putExtra("image_filename", exportPath);
                intent.putExtra("image_cache_key", exportPath + "#" + message.id);
                context.startActivity(intent);
                return;
            }

            final String localPath = message.filename_fullpath;
            if ((localPath != null) && (!localPath.isEmpty()) && (!localPath.startsWith("content://"))
                    && (!isIocipherVirtualPath(localPath)))
            {
                final java.io.File directLocal = new java.io.File(localPath);
                if (directLocal.isFile() && directLocal.length() > 0L)
                {
                    final Intent intent = new Intent(context, ImageviewerActivity_SD.class);
                putMediaMeta(intent, sn, ts);
                    intent.putExtra("image_filename", directLocal.getAbsolutePath());
                    intent.putExtra("image_cache_key", directLocal.getAbsolutePath() + "#" + message.id);
                    context.startActivity(intent);
                    return;
                }
            }

            if (VFS_ENCRYPT && isIocipherVirtualPath(localPath))
            {
                try
                {
                    final String cachedPath = resolveVfsToCachedPath(localPath, "_dmview");
                    final Intent intent = new Intent(context, ImageviewerActivity_SD.class);
                putMediaMeta(intent, sn, ts);
                    intent.putExtra("image_filename", cachedPath);
                    intent.putExtra("image_cache_key", cachedPath + "#" + message.id);
                    context.startActivity(intent);
                    return;
                }
                catch (Exception e)
                {
                    Log.i(TAG, "openImageViewer:vfs_cache:EE:" + e.getMessage());
                    final Intent intent = new Intent(context, ImageviewerActivity_SD.class);
                putMediaMeta(intent, sn, ts);
                    intent.putExtra("image_filename", localPath);
                    intent.putExtra("image_cache_key", localPath + "#" + message.id);
                    context.startActivity(intent);
                    return;
                }
            }

            final java.io.File direct = new java.io.File(message.filename_fullpath);
            if (direct.exists() && direct.isFile())
            {
                final Intent intent = new Intent(context, ImageviewerActivity_SD.class);
                putMediaMeta(intent, sn, ts);
                intent.putExtra("image_filename", direct.getAbsolutePath());
                intent.putExtra("image_cache_key", direct.getAbsolutePath() + "#" + message.id);
                context.startActivity(intent);
                return;
            }

            showMediaNotReady(context, message, null, exportPath);
        }
        catch (Exception e)
        {
            e.printStackTrace();
            HelperGeneric.display_toast(context.getString(R.string.chat_opening_file_failed), false, 0);
        }
    }

    public static void openGroupMessageMedia(final Context context, final GroupMessage message,
                                             final String exportPath)
    {
        if ((context == null) || (message == null))
        {
            return;
        }

        final GroupMessage fresh = HelperFiletransfer.reloadGroupMessageFromDb(message);

        // KHANDAQ (#120): an incoming group file that has not been downloaded yet (attachment policy =
        // Никогда / Wi-Fi-only off Wi-Fi, so the broadcast chunks were dropped) renders as a loading
        // placeholder. A tap is an explicit "download it now" — set the per-file bypass marker and kick
        // off the fetch instead of silently doing nothing; progress then replaces the placeholder.
        if ((fresh != null) && (fresh.direction == 0)
                && !HelperFiletransfer.isGroupMessageMediaReady(fresh, null)
                && !HelperGroup.ngc_incoming_download_allowed(fresh.group_identifier, fresh.msg_id_hash))
        {
            HelperGroup.mark_ngc_manual_download_requested(fresh.group_identifier, fresh.msg_id_hash);
            HelperGroup.retry_group_incoming_file_download(fresh);
            HelperGeneric.display_toast(context.getString(R.string.file_transfer_downloading), false, 0);
            return;
        }

        if (!ensureGroupMessageMediaOpenable(context, fresh, exportPath))
        {
            return;
        }

        final String mimeType = guess_group_message_file_mime_type(context, fresh);

        if ((mimeType != null) && mimeType.startsWith("image/"))
        {
            openGroupImageViewer(context, fresh, exportPath, mimeType);
            return;
        }

        if ((mimeType != null) && mimeType.startsWith("video/"))
        {
            final String mediaPath = HelperFiletransfer.resolveGroupMessageMediaPath(fresh);
            openVideoViewer(context, mediaPath, fresh.id, fresh.storage_frame_work, exportPath,
                            mimeType, "", fresh.sent_timestamp);
            return;
        }

        if (exportPath != null)
        {
            HelperFiletransfer.open_local_file(exportPath, context);
            return;
        }

        final String mediaPath = HelperFiletransfer.resolveGroupMessageMediaPath(fresh);
        if (VFS_ENCRYPT && (mediaPath != null) && !mediaPath.isEmpty())
        {
            try
            {
                final Uri uri = Uri.parse(IOCipherContentProvider.FILES_URI + mediaPath);
                final Intent sendIntent = new Intent(Intent.ACTION_VIEW, uri);
                context.startActivity(sendIntent);
            }
            catch (Exception e)
            {
                Log.i(TAG, "openGroupMessageMedia:fallback:EE:" + e.getMessage());
                HelperGeneric.display_toast(context.getString(R.string.chat_opening_file_failed), false, 0);
            }
        }
    }

    private static void openGroupImageViewer(final Context context, final GroupMessage message,
                                             final String exportPath, final String mimeType)
    {
        if ((context == null) || (message == null))
        {
            return;
        }

        final String mediaPath = HelperFiletransfer.resolveGroupMessageMediaPath(message);
        if ((mediaPath == null) || mediaPath.isEmpty())
        {
            HelperGeneric.display_toast(context.getString(R.string.chat_opening_file_failed), false, 0);
            return;
        }

        try
        {
            if ((message.storage_frame_work) && (exportPath == null))
            {
                launchGroupImageViewerSd(context, mediaPath, message.id, true, message.sent_timestamp);
                return;
            }

            if (exportPath != null)
            {
                launchGroupImageViewerSd(context, exportPath, message.id, false, message.sent_timestamp);
                return;
            }

            if ((!mediaPath.startsWith("content://")) && (!isIocipherVirtualPath(mediaPath)))
            {
                final java.io.File directLocal = new java.io.File(mediaPath);
                if (directLocal.isFile() && directLocal.length() > 0L)
                {
                    launchGroupImageViewerSd(context, directLocal.getAbsolutePath(), message.id, false, message.sent_timestamp);
                    return;
                }
            }

            if (VFS_ENCRYPT && isIocipherVirtualPath(mediaPath))
            {
                // Same VFS path + Glide settings as group chat thumbnails (iocipher File + RESOURCE cache).
                launchGroupImageViewerSd(context, mediaPath, message.id, false, message.sent_timestamp);
                return;
            }

            final java.io.File direct = new java.io.File(mediaPath);
            if (direct.isFile() && direct.length() > 0L)
            {
                launchGroupImageViewerSd(context, direct.getAbsolutePath(), message.id, false, message.sent_timestamp);
                return;
            }

            HelperGeneric.display_toast(context.getString(R.string.chat_opening_file_failed), false, 0);
        }
        catch (Exception e)
        {
            e.printStackTrace();
            HelperGeneric.display_toast(context.getString(R.string.chat_opening_file_failed), false, 0);
        }
    }

    private static void launchGroupImageViewerSd(final Context context, final String imagePath,
                                                 final long messageId, final boolean storageFramework,
                                                 final long timestampSecs)
    {
        final Intent intent = new Intent(context, ImageviewerActivity_SD.class);
        intent.putExtra("image_filename", imagePath);
        intent.putExtra("image_cache_key", imagePath + "#" + messageId);
        if (storageFramework)
        {
            intent.putExtra("storage_frame_work", "1");
        }
        // Group sender name resolution is deferred (gallery phase); show date only for now.
        putMediaMeta(intent, "", timestampSecs);
        context.startActivity(intent);
    }

    public static void openVideoViewer(Context context, Message message, String exportPath, String mimeType)
    {
        openVideoViewer(context, message.filename_fullpath, message.id, message.storage_frame_work, exportPath,
                        mimeType, senderNameForMessage(message), message.sent_timestamp);
    }

    static void openVideoViewer(final Context context, final String vfsPath, final long messageId,
                                final boolean storageFramework, final String exportPath, final String mimeType,
                                final String senderName, final long timestampSecs)
    {
        if (context == null)
        {
            return;
        }

        if (!isMediaFileReadyToView(vfsPath, storageFramework, exportPath))
        {
            return;
        }

        try
        {
            final Intent intent = new Intent(context, MediaViewerActivity.class);
            intent.putExtra(EXTRA_MODE, MODE_VIDEO);
            intent.putExtra(EXTRA_VFS_PATH, vfsPath);
            if (exportPath != null)
            {
                intent.putExtra(EXTRA_EXPORT_PATH, exportPath);
            }
            if (storageFramework)
            {
                intent.putExtra(EXTRA_STORAGE_FRAMEWORK, "1");
            }
            intent.putExtra(EXTRA_MESSAGE_ID, messageId);
            intent.putExtra(EXTRA_MIME_TYPE, mimeType);
            putMediaMeta(intent, senderName, timestampSecs);
            context.startActivity(intent);
        }
        catch (Exception e)
        {
            e.printStackTrace();
            HelperGeneric.display_toast(context.getString(R.string.chat_opening_file_failed), false, 0);
        }
    }

    public static String messageMediaDisplayLabel(Context context, Message message)
    {
        if (isVoiceMessage(message))
        {
            return context.getString(R.string.voice_message_label);
        }

        final String mimeType = guess_message_file_mime_type(context, message);
        if (mimeType != null)
        {
            if (mimeType.startsWith("image/"))
            {
                return context.getString(R.string.media_label_photo);
            }
            if (mimeType.startsWith("video/"))
            {
                return context.getString(R.string.media_label_video);
            }
            if (mimeType.startsWith("audio/"))
            {
                return context.getString(R.string.media_label_audio);
            }
        }

        return HelperFiletransfer.outgoingFileDisplayLabel(context, message);
    }

    public static String groupMessageMediaDisplayLabel(final Context context, final GroupMessage message)
    {
        if (HelperFiletransfer.isGroupVoiceMessage(message))
        {
            return context.getString(R.string.voice_message_label);
        }

        final String mimeType = guess_group_message_file_mime_type(context, message);
        if (mimeType != null)
        {
            if (mimeType.startsWith("image/"))
            {
                return context.getString(R.string.media_label_photo);
            }
            if (mimeType.startsWith("video/"))
            {
                return context.getString(R.string.media_label_video);
            }
            if (mimeType.startsWith("audio/"))
            {
                return context.getString(R.string.media_label_audio);
            }
        }

        return HelperFiletransfer.groupFileDisplayLabel(context, message);
    }

    public static void bindVideoPreview(final Context context, final Message message, final String exportPath,
                                        final ImageView previewImage)
    {
        bindVideoPreview(context, message.filename_fullpath, message.id, exportPath, previewImage);
    }

    static void bindVideoPreview(final Context context, final String vfsPath, final long messageId,
                                 final String exportPath, final ImageView previewImage)
    {
        previewImage.setImageResource(R.drawable.round_loading_animation);

        final android.graphics.drawable.Drawable playOverlay = new IconicsDrawable(context).
                icon(GoogleMaterial.Icon.gmd_play_circle_filled).
                backgroundColor(android.graphics.Color.TRANSPARENT).
                color(android.graphics.Color.parseColor("#CCFFFFFF")).sizeDp(48);

        final RequestOptions glideOptions = new RequestOptions().
                centerCrop().
                optionalTransform(new RoundedCorners(ChatBubbleUiHelper.media_corner_radius_px(context)));

        new Thread()
        {
            @Override
            public void run()
            {
                Bitmap frame = null;
                String localPath = exportPath;
                try
                {
                    if ((localPath == null) || localPath.isEmpty())
                    {
                        final java.io.File direct = new java.io.File(vfsPath);
                        if (direct.exists() && (direct.length() > 0))
                        {
                            localPath = direct.getAbsolutePath();
                        }
                        else if (VFS_ENCRYPT)
                        {
                            localPath = resolveVfsToCachedPath(vfsPath, "_vthumb");
                        }
                    }

                    if (localPath != null)
                    {
                        frame = extractVideoFrame(localPath);
                    }
                }
                catch (Exception e)
                {
                    Log.i(TAG, "bindVideoPreview:EE:" + e.getMessage());
                }

                final Bitmap frameFinal = frame;
                new Handler(Looper.getMainLooper()).post(new Runnable()
                {
                    @Override
                    public void run()
                    {
                        if (frameFinal != null)
                        {
                            GlideApp.
                                    with(context).
                                    load(frameFinal).
                                    diskCacheStrategy(DiskCacheStrategy.NONE).
                                    skipMemoryCache(true).
                                    apply(glideOptions).
                                    placeholder(R.drawable.round_loading_animation).
                                    error(playOverlay).
                                    into(previewImage);
                        }
                        else
                        {
                            GlideApp.
                                    with(context).
                                    load(playOverlay).
                                    diskCacheStrategy(DiskCacheStrategy.NONE).
                                    apply(glideOptions).
                                    into(previewImage);
                        }
                    }
                });
            }
        }.start();
    }

    public static void bindOutgoingImagePreview(final Context context, final Message message,
                                                final ImageView previewImage)
    {
        if ((context == null) || (message == null) || (previewImage == null))
        {
            return;
        }

        previewImage.setImageResource(R.drawable.round_loading_animation);

        final RequestOptions glideOptions = new RequestOptions().
                centerCrop().
                optionalTransform(new RoundedCorners(ChatBubbleUiHelper.media_corner_radius_px(context)));

        try
        {
            if (message.storage_frame_work)
            {
                GlideApp.
                        with(context).
                        load(Uri.parse(message.filename_fullpath)).
                        diskCacheStrategy(DiskCacheStrategy.RESOURCE).
                        skipMemoryCache(false).
                        priority(Priority.LOW).
                        apply(glideOptions).
                        placeholder(R.drawable.round_loading_animation).
                        into(previewImage);
            }
            else
            {
                GlideApp.
                        with(context).
                        load(new File(message.filename_fullpath)).
                        diskCacheStrategy(DiskCacheStrategy.RESOURCE).
                        skipMemoryCache(false).
                        priority(Priority.LOW).
                        apply(glideOptions).
                        placeholder(R.drawable.round_loading_animation).
                        into(previewImage);
            }
        }
        catch (Exception e)
        {
            Log.i(TAG, "bindOutgoingImagePreview:EE:" + e.getMessage());
        }
    }

    public static View.OnTouchListener groupMediaOpenTouchListener(final Context context, final GroupMessage message,
                                                                   final String exportPath)
    {
        return new View.OnTouchListener()
        {
            @Override
            public boolean onTouch(View v, MotionEvent event)
            {
                if (event.getAction() == MotionEvent.ACTION_UP)
                {
                    openGroupMessageMedia(context, message, exportPath);
                }
                return true;
            }
        };
    }

    public static View.OnTouchListener mediaOpenTouchListener(final Context context, final Message message,
                                                              final String exportPath)
    {
        return new View.OnTouchListener()
        {
            @Override
            public boolean onTouch(View v, MotionEvent event)
            {
                if (event.getAction() == MotionEvent.ACTION_UP)
                {
                    openMessageMedia(context, message, exportPath);
                }
                return true;
            }
        };
    }

    static String resolveVfsToCachedPath(String vfsPath, String suffix) throws Exception
    {
        if ((vfsPath == null) || vfsPath.isEmpty())
        {
            throw new Exception("empty path");
        }

        info.guardianproject.iocipher.File vfsFile = new info.guardianproject.iocipher.File(vfsPath);
        if (!vfsFile.exists())
        {
            throw new Exception("missing vfs file");
        }

        String extension = "";
        final int dot = vfsPath.lastIndexOf('.');
        if (dot > 0)
        {
            extension = vfsPath.substring(dot);
        }

        final String cacheName = "media_" + Math.abs(vfsPath.hashCode()) + suffix + extension;
        final File cached = new File(SD_CARD_TMP_DIR, cacheName);
        if (cached.exists() && (cached.length() > 0))
        {
            return cached.getAbsolutePath();
        }

        final String tmpName = copy_vfs_file_to_real_file(vfsFile.getParent(), vfsFile.getName(), SD_CARD_TMP_DIR,
                                                          suffix);
        if ((tmpName == null) || tmpName.isEmpty())
        {
            throw new Exception("vfs copy failed");
        }

        final File tmp = new File(SD_CARD_TMP_DIR, tmpName);
        if (!tmp.exists())
        {
            throw new Exception("tmp missing");
        }

        if (tmp.renameTo(cached))
        {
            return cached.getAbsolutePath();
        }

        return tmp.getAbsolutePath();
    }

    static Uri resolvePlaybackUri(Context context, String vfsPath, String exportPath, boolean storageFramework,
                                  String mimeType) throws Exception
    {
        if (storageFramework)
        {
            if ((vfsPath == null) || vfsPath.isEmpty())
            {
                throw new Exception("empty content uri");
            }
            return Uri.parse(vfsPath);
        }

        if ((vfsPath != null) && vfsPath.startsWith("content://"))
        {
            return Uri.parse(vfsPath);
        }

        if ((exportPath != null) && (!exportPath.isEmpty()))
        {
            final File exportFile = new File(exportPath);
            if (!exportFile.exists())
            {
                throw new Exception("export file missing");
            }
            return Uri.fromFile(exportFile);
        }

        final File direct = new File(vfsPath);
        if (direct.exists() && (direct.length() > 0))
        {
            return Uri.fromFile(direct);
        }

        if (VFS_ENCRYPT)
        {
            final String localPath = resolveVfsToCachedPath(vfsPath, "_play");
            final File cached = new File(localPath);
            if (!cached.exists())
            {
                throw new Exception("vfs cache missing");
            }
            return Uri.fromFile(cached);
        }

        if (direct.exists())
        {
            return Uri.fromFile(direct);
        }

        throw new Exception("no local playback path");
    }

    private static Bitmap extractVideoFrame(String localPath)
    {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try
        {
            retriever.setDataSource(localPath);
            return retriever.getFrameAtTime(0);
        }
        catch (Exception e)
        {
            return null;
        }
        finally
        {
            try
            {
                retriever.release();
            }
            catch (Exception ignored)
            {
            }
        }
    }

    private static boolean ensureMessageMediaOpenable(final Context context, final Message message,
                                                      final String exportPath)
    {
        if (isMessageMediaReady(message, exportPath))
        {
            return true;
        }

        showMediaNotReady(context, message, null, exportPath);
        return false;
    }

    private static boolean ensureGroupMessageMediaOpenable(final Context context, final GroupMessage message,
                                                           final String exportPath)
    {
        if (isGroupMessageMediaOpenable(message, exportPath))
        {
            return true;
        }

        showMediaNotReady(context, null, message, exportPath);
        return false;
    }

    private static void showMediaNotReady(final Context context, final Message directMessage,
                                          final GroupMessage groupMessage, final String exportPath)
    {
        // Transfer state is shown inline on the file bubble — no blocking dialog.
    }
}
