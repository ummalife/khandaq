package com.zoffcc.applications.trifa;

import android.content.Context;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.mikepenz.google_material_typeface_library.GoogleMaterial;
import com.mikepenz.iconics.IconicsDrawable;
import com.zoffcc.applications.sorm.GroupMessage;
import com.zoffcc.applications.sorm.Message;

import org.khandaq.messenger.R;

import static com.zoffcc.applications.trifa.HelperFiletransfer.guess_group_message_file_mime_type;
import static com.zoffcc.applications.trifa.HelperFiletransfer.guess_message_file_mime_type;

/** Telegram-style compact file attachment row inside message bubbles. */
final class ChatFileBubbleHelper
{
    private ChatFileBubbleHelper()
    {
    }

    static void hide(final View itemRoot)
    {
        if (itemRoot == null)
        {
            return;
        }
        final View row = itemRoot.findViewById(R.id.ft_file_attachment_row);
        if (row != null)
        {
            row.setVisibility(View.GONE);
        }
    }

    static void hideMediaChrome(final View itemRoot)
    {
        if (itemRoot == null)
        {
            return;
        }

        final View infoBar = itemRoot.findViewById(R.id.ft_media_info_bar);
        if (infoBar != null)
        {
            infoBar.setVisibility(View.GONE);
        }

        final View playOverlay = itemRoot.findViewById(R.id.ft_media_play_overlay);
        if (playOverlay != null)
        {
            playOverlay.setVisibility(View.GONE);
        }
    }

    static void bindVideoMediaChrome(final Context context, final View itemRoot, final GroupMessage message)
    {
        bindVideoMediaChrome(context, itemRoot, message.filesize > 0 ? message.filesize : 0L);
    }

    static void bindVideoMediaChrome(final Context context, final View itemRoot, final long fileSizeBytes)
    {
        if (context == null || itemRoot == null)
        {
            return;
        }

        ensureMediaPreviewFrame(context, itemRoot);

        final View infoBar = itemRoot.findViewById(R.id.ft_media_info_bar);
        if (infoBar != null)
        {
            infoBar.setVisibility(View.VISIBLE);
            final ImageView icon = infoBar.findViewById(R.id.ft_media_info_icon);
            final TextView label = infoBar.findViewById(R.id.ft_media_info_label);
            final TextView size = infoBar.findViewById(R.id.ft_media_info_size);
            if (icon != null)
            {
                icon.setImageDrawable(new IconicsDrawable(context).
                        icon(GoogleMaterial.Icon.gmd_videocam).
                        color(android.graphics.Color.WHITE).
                        sizeDp(14));
            }
            if (label != null)
            {
                label.setText(context.getString(R.string.media_label_video));
            }
            if (size != null)
            {
                if (fileSizeBytes > 0)
                {
                    size.setText(Formatter.formatFileSize(context, fileSizeBytes));
                    size.setVisibility(View.VISIBLE);
                }
                else
                {
                    size.setVisibility(View.GONE);
                }
            }
        }

        final ImageView playOverlay = itemRoot.findViewById(R.id.ft_media_play_overlay);
        if (playOverlay != null)
        {
            playOverlay.setVisibility(View.VISIBLE);
            playOverlay.setImageDrawable(new IconicsDrawable(context).
                    icon(GoogleMaterial.Icon.gmd_play_circle_filled).
                    backgroundColor(android.graphics.Color.TRANSPARENT).
                    color(android.graphics.Color.parseColor("#CCFFFFFF")).
                    sizeDp(48));
        }
    }

    private static void ensureMediaPreviewFrame(final Context context, final View itemRoot)
    {
        final ImageButton preview = itemRoot.findViewById(R.id.ft_preview_image);
        if (preview == null)
        {
            return;
        }

        if (itemRoot.findViewById(R.id.ft_media_info_bar) != null)
        {
            return;
        }

        final ViewGroup host = (ViewGroup) preview.getParent();
        if (host == null)
        {
            return;
        }

        final int index = host.indexOfChild(preview);
        final ViewGroup.LayoutParams previewLp = preview.getLayoutParams();
        host.removeViewAt(index);

        final FrameLayout frame = new FrameLayout(context);
        frame.setLayoutParams(previewLp);
        frame.addView(preview, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                previewLp != null ? previewLp.height : ViewGroup.LayoutParams.WRAP_CONTENT));

        LayoutInflater.from(context).inflate(R.layout.chat_media_play_overlay, frame, true);
        LayoutInflater.from(context).inflate(R.layout.chat_media_info_bar, frame, true);

        host.addView(frame, index, previewLp);
    }

    static void hideMediaPreview(final View itemRoot)
    {
        if (itemRoot == null)
        {
            return;
        }
        final ImageButton preview = itemRoot.findViewById(R.id.ft_preview_image);
        if (preview != null)
        {
            preview.setVisibility(View.GONE);
            preview.setImageDrawable(null);
            preview.setOnTouchListener(null);
        }
        hideMediaChrome(itemRoot);
    }

    static void showMediaPreview(final View itemRoot, final int heightPx)
    {
        if (itemRoot == null)
        {
            return;
        }
        final ImageButton preview = itemRoot.findViewById(R.id.ft_preview_image);
        if (preview == null)
        {
            return;
        }
        preview.setVisibility(View.VISIBLE);
        final ViewGroup.LayoutParams lp = preview.getLayoutParams();
        if (lp != null)
        {
            lp.height = heightPx;
            preview.setLayoutParams(lp);
        }
    }

    static void bindGenericMessage(final Context context, final View itemRoot, final Message message,
                                   final View.OnClickListener openListener, final int progressPercent)
    {
        bindGenericMessage(context, itemRoot, message, openListener,
                progressPercent >= 0 && progressPercent < 100
                        ? new ChatTransferProgressHelper.Snapshot(
                        ChatTransferProgressHelper.Phase.TRANSFERRING, 0L, resolveMessageFileSize(message),
                        progressPercent, 0f, true, false)
                        : new ChatTransferProgressHelper.Snapshot(
                        ChatTransferProgressHelper.Phase.COMPLETE, 0L, resolveMessageFileSize(message),
                        progressPercent, 0f, true, false));
    }

    static void bindGenericMessage(final Context context, final View itemRoot, final Message message,
                                   final View.OnClickListener openListener,
                                   final ChatTransferProgressHelper.Snapshot snap)
    {
        if (context == null || itemRoot == null || message == null)
        {
            return;
        }

        final View row = itemRoot.findViewById(R.id.ft_file_attachment_row);
        if (row == null)
        {
            return;
        }

        final String label = ChatMediaHelper.messageMediaDisplayLabel(context, message);
        final long size = snap != null && snap.bytesTotal > 0 ? snap.bytesTotal : resolveMessageFileSize(message);
        final String mime = guess_message_file_mime_type(context, message);

        bindRow(context, row, label, size, mime, openListener, snap);
    }

    static void bindGenericGroupMessage(final Context context, final View itemRoot, final GroupMessage message,
                                        final View.OnClickListener openListener, final int progressPercent)
    {
        bindGenericGroupMessage(context, itemRoot, message, openListener,
                progressPercent >= 0 && progressPercent < 100
                        ? new ChatTransferProgressHelper.Snapshot(
                        ChatTransferProgressHelper.Phase.TRANSFERRING, 0L,
                        message.filesize > 0 ? message.filesize : 0L, progressPercent, 0f, true, false)
                        : new ChatTransferProgressHelper.Snapshot(
                        ChatTransferProgressHelper.Phase.COMPLETE, 0L,
                        message.filesize > 0 ? message.filesize : 0L, progressPercent, 0f, true, false));
    }

    static void bindGenericGroupMessage(final Context context, final View itemRoot, final GroupMessage message,
                                        final View.OnClickListener openListener,
                                        final ChatTransferProgressHelper.Snapshot snap)
    {
        if (context == null || itemRoot == null || message == null)
        {
            return;
        }

        final View row = itemRoot.findViewById(R.id.ft_file_attachment_row);
        if (row == null)
        {
            return;
        }

        final String label = ChatMediaHelper.groupMessageMediaDisplayLabel(context, message);
        final long size = snap != null && snap.bytesTotal > 0 ? snap.bytesTotal : (message.filesize > 0 ? message.filesize : 0L);
        final String mime = guess_group_message_file_mime_type(context, message);

        bindRow(context, row, label, size, mime, openListener, snap, false);
    }

    static void bindGenericGroupMessage(final Context context, final View itemRoot, final GroupMessage message,
                                        final View.OnClickListener openListener,
                                        final ChatTransferProgressHelper.Snapshot snap, final boolean outgoing)
    {
        if (context == null || itemRoot == null || message == null)
        {
            return;
        }

        final View row = itemRoot.findViewById(R.id.ft_file_attachment_row);
        if (row == null)
        {
            return;
        }

        final String label = ChatMediaHelper.groupMessageMediaDisplayLabel(context, message);
        final long size = snap != null && snap.bytesTotal > 0 ? snap.bytesTotal : (message.filesize > 0 ? message.filesize : 0L);
        final String mime = guess_group_message_file_mime_type(context, message);

        bindRow(context, row, label, size, mime, openListener, snap, outgoing);
    }

    private static void bindRow(final Context context, final View row, final CharSequence fileName,
                                final long fileSizeBytes, final String mimeType,
                                final View.OnClickListener openListener,
                                final ChatTransferProgressHelper.Snapshot snap)
    {
        bindRow(context, row, fileName, fileSizeBytes, mimeType, openListener, snap, false);
    }

    private static void bindRow(final Context context, final View row, final CharSequence fileName,
                                final long fileSizeBytes, final String mimeType,
                                final View.OnClickListener openListener,
                                final ChatTransferProgressHelper.Snapshot snap, final boolean outgoing)
    {
        row.setVisibility(View.VISIBLE);
        row.setOnClickListener(openListener);
        row.setBackgroundResource(outgoing
                ? R.drawable.tg_file_attachment_bg_outgoing
                : R.drawable.tg_file_attachment_bg);

        final ImageView iconView = row.findViewById(R.id.ft_file_icon);
        final TextView nameView = row.findViewById(R.id.ft_file_name);
        final TextView sizeView = row.findViewById(R.id.ft_file_size);
        final ProgressBar progressView = row.findViewById(R.id.ft_file_progress);
        final TextView detailView = row.findViewById(R.id.ft_file_progress_detail);
        final TextView errorView = row.findViewById(R.id.ft_file_error);
        final ImageButton cancelView = row.findViewById(R.id.ft_file_cancel);
        final ImageButton retryView = row.findViewById(R.id.ft_file_retry);

        if (nameView != null)
        {
            nameView.setText(fileName);
            nameView.setTextColor(ContextCompat.getColor(context, R.color.message_bubble_text));
        }

        final ChatTransferProgressHelper.Phase phase =
                snap != null ? snap.phase : ChatTransferProgressHelper.Phase.COMPLETE;
        row.setAlpha(phase == ChatTransferProgressHelper.Phase.PENDING ? 0.55f : 1f);
        final int percent = snap != null ? snap.percent : -1;
        final long doneBytes = snap != null ? snap.bytesDone : 0L;
        final long totalBytes = snap != null && snap.bytesTotal > 0 ? snap.bytesTotal : fileSizeBytes;

        if (sizeView != null)
        {
            sizeView.setTextColor(ContextCompat.getColor(context, R.color.tg_chat_input_hint));
            if (phase == ChatTransferProgressHelper.Phase.TRANSFERRING && totalBytes > 0)
            {
                sizeView.setText(ChatTransferProgressHelper.formatProgressLine(context, snap));
                sizeView.setVisibility(View.VISIBLE);
            }
            else if (phase == ChatTransferProgressHelper.Phase.PENDING)
            {
                sizeView.setText(totalBytes > 0
                        ? Formatter.formatFileSize(context, totalBytes)
                        : "");
                sizeView.setVisibility(totalBytes > 0 ? View.VISIBLE : View.GONE);
            }
            else if (totalBytes > 0)
            {
                sizeView.setText(Formatter.formatFileSize(context, totalBytes));
                sizeView.setVisibility(View.VISIBLE);
            }
            else
            {
                sizeView.setVisibility(View.GONE);
            }
        }

        if (detailView != null)
        {
            if (phase == ChatTransferProgressHelper.Phase.PENDING)
            {
                detailView.setText(context.getString(R.string.file_transfer_waiting_sender));
                detailView.setVisibility(View.VISIBLE);
            }
            else if (phase == ChatTransferProgressHelper.Phase.TRANSFERRING && snap != null)
            {
                detailView.setText(ChatTransferProgressHelper.formatProgressLine(context, snap));
                detailView.setVisibility(View.VISIBLE);
            }
            else
            {
                detailView.setVisibility(View.GONE);
            }
        }

        if (errorView != null)
        {
            errorView.setVisibility(phase == ChatTransferProgressHelper.Phase.FAILED ? View.VISIBLE : View.GONE);
            if (phase == ChatTransferProgressHelper.Phase.FAILED && snap != null)
            {
                errorView.setText(context.getString(R.string.file_transfer_connection_issue));
            }
        }

        if (iconView != null)
        {
            if (phase == ChatTransferProgressHelper.Phase.PENDING)
            {
                iconView.setImageDrawable(new IconicsDrawable(context).
                        icon(GoogleMaterial.Icon.gmd_schedule).
                        color(android.graphics.Color.parseColor("#9E9E9E")).
                        sizeDp(28));
            }
            else
            {
                iconView.setImageDrawable(fileTypeIcon(context, mimeType, fileName != null ? fileName.toString() : null));
            }
        }

        if (progressView != null)
        {
            if (phase == ChatTransferProgressHelper.Phase.TRANSFERRING && percent >= 0 && percent < 100)
            {
                progressView.setVisibility(View.VISIBLE);
                progressView.setProgress(percent);
            }
            else
            {
                progressView.setVisibility(View.GONE);
            }
        }

        if (cancelView != null)
        {
            cancelView.setVisibility(phase == ChatTransferProgressHelper.Phase.TRANSFERRING ? View.VISIBLE : View.GONE);
        }
        if (retryView != null)
        {
            retryView.setVisibility(phase == ChatTransferProgressHelper.Phase.FAILED ? View.VISIBLE : View.GONE);
        }
    }

    private static long resolveMessageFileSize(final Message message)
    {
        if (message.filetransfer_id > 0)
        {
            try
            {
                final long sz = HelperFiletransfer.get_filetransfer_filesize_from_id(message.filetransfer_id);
                if (sz > 0)
                {
                    return sz;
                }
            }
            catch (Exception ignored)
            {
            }
        }

        try
        {
            if (message.filename_fullpath != null)
            {
                final java.io.File f = new java.io.File(message.filename_fullpath);
                if (f.isFile() && f.length() > 0)
                {
                    return f.length();
                }
            }
        }
        catch (Exception ignored)
        {
        }

        return 0L;
    }

    static android.graphics.drawable.Drawable fileTypeIcon(final Context context, final String mimeType,
                                                           final String fileName)
    {
        GoogleMaterial.Icon icon = GoogleMaterial.Icon.gmd_insert_drive_file;
        int color = android.graphics.Color.parseColor("#5C6BC0");

        if (mimeType != null)
        {
            if (mimeType.startsWith("application/pdf"))
            {
                icon = GoogleMaterial.Icon.gmd_picture_as_pdf;
                color = android.graphics.Color.parseColor("#E53935");
            }
            else if (mimeType.startsWith("audio/"))
            {
                icon = GoogleMaterial.Icon.gmd_audiotrack;
                color = android.graphics.Color.parseColor("#FB8C00");
            }
            else if (mimeType.startsWith("video/"))
            {
                icon = GoogleMaterial.Icon.gmd_videocam;
                color = android.graphics.Color.parseColor("#8E24AA");
            }
            else if (mimeType.startsWith("image/"))
            {
                icon = GoogleMaterial.Icon.gmd_image;
                color = android.graphics.Color.parseColor("#43A047");
            }
        }

        if (icon == GoogleMaterial.Icon.gmd_insert_drive_file && fileName != null)
        {
            final String lower = fileName.toLowerCase();
            if (lower.endsWith(".pdf"))
            {
                icon = GoogleMaterial.Icon.gmd_picture_as_pdf;
                color = android.graphics.Color.parseColor("#E53935");
            }
        }

        return new IconicsDrawable(context).
                icon(icon).
                color(color).
                sizeDp(28);
    }
}
