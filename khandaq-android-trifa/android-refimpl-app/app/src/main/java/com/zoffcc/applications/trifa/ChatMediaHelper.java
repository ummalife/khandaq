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
import com.zoffcc.applications.sorm.Message;

import java.io.File;

import static com.zoffcc.applications.trifa.HelperFiletransfer.guess_message_file_mime_type;
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

    public static final String MODE_IMAGE = "image";
    public static final String MODE_VIDEO = "video";

    private ChatMediaHelper()
    {
    }

    public static void openMessageMedia(final Context context, final Message message, final String exportPath)
    {
        if ((context == null) || (message == null))
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
            openVideoViewer(context, message, exportPath, mimeType);
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
                HelperGeneric.display_toast("opening file failed", false, 0);
            }
        }
    }

    public static void openImageViewer(Context context, Message message, String exportPath, String mimeType)
    {
        try
        {
            if ((message.storage_frame_work) && (exportPath == null))
            {
                final Intent intent = new Intent(context, ImageviewerActivity_SD.class);
                intent.putExtra("image_filename", message.filename_fullpath);
                intent.putExtra("image_cache_key", message.filename_fullpath + "#" + message.id);
                intent.putExtra("storage_frame_work", "1");
                context.startActivity(intent);
                return;
            }

            if (exportPath != null)
            {
                final Intent intent = new Intent(context, ImageviewerActivity_SD.class);
                intent.putExtra("image_filename", exportPath);
                intent.putExtra("image_cache_key", exportPath + "#" + message.id);
                context.startActivity(intent);
                return;
            }

            final java.io.File direct = new java.io.File(message.filename_fullpath);
            if (direct.exists() && direct.isFile())
            {
                final Intent intent = new Intent(context, ImageviewerActivity_SD.class);
                intent.putExtra("image_filename", direct.getAbsolutePath());
                intent.putExtra("image_cache_key", direct.getAbsolutePath() + "#" + message.id);
                context.startActivity(intent);
                return;
            }

            final Intent intent = new Intent(context, MediaViewerActivity.class);
            intent.putExtra(EXTRA_MODE, MODE_IMAGE);
            intent.putExtra(EXTRA_VFS_PATH, message.filename_fullpath);
            if (exportPath != null)
            {
                intent.putExtra(EXTRA_EXPORT_PATH, exportPath);
            }
            intent.putExtra(EXTRA_MESSAGE_ID, message.id);
            intent.putExtra(EXTRA_MIME_TYPE, mimeType);
            context.startActivity(intent);
        }
        catch (Exception e)
        {
            e.printStackTrace();
            HelperGeneric.display_toast("opening file failed", false, 0);
        }
    }

    public static void openVideoViewer(Context context, Message message, String exportPath, String mimeType)
    {
        try
        {
            final Intent intent = new Intent(context, MediaViewerActivity.class);
            intent.putExtra(EXTRA_MODE, MODE_VIDEO);
            intent.putExtra(EXTRA_VFS_PATH, message.filename_fullpath);
            if (exportPath != null)
            {
                intent.putExtra(EXTRA_EXPORT_PATH, exportPath);
            }
            if (message.storage_frame_work)
            {
                intent.putExtra(EXTRA_STORAGE_FRAMEWORK, "1");
            }
            intent.putExtra(EXTRA_MESSAGE_ID, message.id);
            intent.putExtra(EXTRA_MIME_TYPE, mimeType);
            context.startActivity(intent);
        }
        catch (Exception e)
        {
            e.printStackTrace();
            HelperGeneric.display_toast("opening file failed", false, 0);
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

    public static void bindVideoPreview(final Context context, final Message message, final String exportPath,
                                        final ImageView previewImage)
    {
        previewImage.setImageResource(R.drawable.round_loading_animation);

        final android.graphics.drawable.Drawable playOverlay = new IconicsDrawable(context).
                icon(GoogleMaterial.Icon.gmd_play_circle_filled).
                backgroundColor(android.graphics.Color.TRANSPARENT).
                color(android.graphics.Color.parseColor("#CCFFFFFF")).sizeDp(48);

        final RequestOptions glideOptions = new RequestOptions().
                fitCenter().
                optionalTransform(new RoundedCorners((int) dp2px(20)));

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
                        final java.io.File direct = new java.io.File(message.filename_fullpath);
                        if (direct.exists() && (direct.length() > 0))
                        {
                            localPath = direct.getAbsolutePath();
                        }
                        else if (VFS_ENCRYPT)
                        {
                            localPath = resolveVfsToCachedPath(message.filename_fullpath, "_vthumb");
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
}
