package com.zoffcc.applications.trifa;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.mikepenz.google_material_typeface_library.GoogleMaterial;
import com.mikepenz.iconics.IconicsDrawable;
import com.zoffcc.applications.sorm.Filetransfer;
import com.zoffcc.applications.sorm.GroupMessage;
import com.zoffcc.applications.sorm.Message;

import org.khandaq.messenger.R;

import static com.zoffcc.applications.trifa.HelperFiletransfer.format_speed;
import static com.zoffcc.applications.trifa.HelperFiletransfer.isAudioMessage;
import static com.zoffcc.applications.trifa.HelperFiletransfer.isGroupAudioMessage;
import static com.zoffcc.applications.trifa.HelperFiletransfer.isGroupImageMessage;
import static com.zoffcc.applications.trifa.HelperFiletransfer.isGroupMessageMediaReady;
import static com.zoffcc.applications.trifa.HelperFiletransfer.isGroupVideoMessage;
import static com.zoffcc.applications.trifa.HelperFiletransfer.isMediaFileReadyForPlayback;
import static com.zoffcc.applications.trifa.HelperFiletransfer.is_message_file_complete;
import static com.zoffcc.applications.trifa.MainActivity.tox_file_control;
import static com.zoffcc.applications.trifa.ToxVars.TOX_FILE_CONTROL.TOX_FILE_CONTROL_CANCEL;
import static com.zoffcc.applications.trifa.ToxVars.TOX_FILE_CONTROL.TOX_FILE_CONTROL_PAUSE;
import static com.zoffcc.applications.trifa.ToxVars.TOX_FILE_CONTROL.TOX_FILE_CONTROL_RESUME;
import static com.zoffcc.applications.trifa.TrifaToxService.orma;

/** Telegram-style transfer progress for direct and group file bubbles. */
final class ChatTransferProgressHelper
{
    enum Phase
    {
        IDLE,
        PENDING,
        TRANSFERRING,
        COMPLETE,
        FAILED
    }

    static final class Snapshot
    {
        final Phase phase;
        final long bytesDone;
        final long bytesTotal;
        final int percent;
        final float speedBps;
        final boolean outgoing;
        final boolean showMediaOverlay;

        Snapshot(final Phase phase, final long bytesDone, final long bytesTotal, final int percent,
                 final float speedBps, final boolean outgoing, final boolean showMediaOverlay)
        {
            this.phase = phase;
            this.bytesDone = bytesDone;
            this.bytesTotal = bytesTotal;
            this.percent = percent;
            this.speedBps = speedBps;
            this.outgoing = outgoing;
            this.showMediaOverlay = showMediaOverlay;
        }
    }

    interface Callbacks
    {
        void onCancel();

        void onRetry();
    }

    private ChatTransferProgressHelper()
    {
    }

    static Snapshot snapshotFromMessage(final Context context, final Message message, final boolean outgoing)
    {
        if (message == null)
        {
            return idleSnapshot(false);
        }

        final boolean fileComplete = is_message_file_complete(message);
        final boolean filePlayable = isMediaFileReadyForPlayback(message.filename_fullpath);

        if (message.filedb_id == -1)
        {
            if (filePlayable)
            {
                return completeSnapshot(outgoing);
            }
            if (outgoing && message.filetransfer_id > 0)
            {
                final long total = resolveMessageTotalBytes(message);
                final boolean media = isDirectMediaPreview(context, message);
                if (!message.ft_outgoing_started)
                {
                    if (message.ft_outgoing_queued
                            || !HelperFiletransfer.is_dm_sender_online(message.tox_friendpubkey))
                    {
                        return new Snapshot(Phase.PENDING, 0L, total, 0, 0f, true, media);
                    }
                    HelperFiletransfer.kick_stalled_outgoing_send(message);
                    return new Snapshot(Phase.PENDING, 0L, total, 0, 0f, true, media);
                }
                return new Snapshot(Phase.TRANSFERRING, 0L, total, 0, 0f, true, media);
            }
            if (!outgoing && !filePlayable)
            {
                final long total = resolveMessageTotalBytes(message);
                final boolean media = isDirectMediaPreview(context, message);
                if (!HelperFiletransfer.is_dm_sender_online(message.tox_friendpubkey))
                {
                    return new Snapshot(Phase.PENDING, 0L, total, 0, 0f, false, media);
                }
                if (message.ft_accepted)
                {
                    return new Snapshot(Phase.TRANSFERRING, 0L, total, 0, 0f, false, media);
                }
                return new Snapshot(Phase.FAILED, 0L, total, 0, 0f, false, media);
            }
            return idleSnapshot(outgoing);
        }

        if (fileComplete || filePlayable)
        {
            final long ftId = message.filetransfer_id;
            if (ftId <= 0)
            {
                return completeSnapshot(outgoing);
            }

            try
            {
                if (orma.selectFromFiletransfer().idEq(ftId).count() != 1)
                {
                    return completeSnapshot(outgoing);
                }

                final Filetransfer ft = orma.selectFromFiletransfer().idEq(ftId).get(0);
                final long total = ft.filesize > 0 ? ft.filesize : resolveMessageTotalBytes(message);
                final long done = Math.max(0L, Math.min(ft.current_position, total));
                final int percent = total > 0 ? (int) (100L * done / total) : 100;
                final float speed = computeDirectSpeedBps(ft.transfer_start_ts, done);

                if (message.state == TOX_FILE_CONTROL_CANCEL.value)
                {
                    return completeSnapshot(outgoing);
                }

                if (message.state == TOX_FILE_CONTROL_RESUME.value
                        || (message.state == TOX_FILE_CONTROL_PAUSE.value && message.ft_accepted))
                {
                    if (percent >= 100 || fileComplete || filePlayable)
                    {
                        return completeSnapshot(outgoing);
                    }
                    final boolean media = isDirectMediaPreview(context, message);
                    return new Snapshot(Phase.TRANSFERRING, done, total, percent, speed, outgoing, media);
                }

                if (message.state == TOX_FILE_CONTROL_PAUSE.value && !message.ft_accepted)
                {
                    return new Snapshot(Phase.TRANSFERRING, done, total, percent, speed, outgoing,
                            isDirectMediaPreview(context, message));
                }
            }
            catch (Exception ignored)
            {
            }

            return completeSnapshot(outgoing);
        }

        if (!outgoing)
        {
            final long total = resolveMessageTotalBytes(message);
            final boolean media = isDirectMediaPreview(context, message);
            if (!HelperFiletransfer.is_dm_sender_online(message.tox_friendpubkey))
            {
                return new Snapshot(Phase.PENDING, 0L, total, 0, 0f, false, media);
            }
            if (message.ft_accepted || message.filetransfer_id > 0)
            {
                return new Snapshot(Phase.TRANSFERRING, 0L, total, 0, 0f, false, media);
            }
            return new Snapshot(Phase.FAILED, 0L, total, 0, 0f, false, media);
        }

        if (message.ft_accepted || outgoing)
        {
            final long total = resolveMessageTotalBytes(message);
            final boolean media = isDirectMediaPreview(context, message);
            return new Snapshot(Phase.TRANSFERRING, 0L, total, 0, 0f, outgoing, media);
        }

        return idleSnapshot(outgoing);
    }

    static Snapshot snapshotFromGroupMessage(final Context context, final GroupMessage message, final boolean outgoing)
    {
        if (message == null)
        {
            return idleSnapshot(false);
        }

        final long total = message.filesize > 0 ? message.filesize : 0L;
        final int pct = HelperGroup.ngc_file_transfer_progress_percent(message.group_identifier, message.msg_id_hash);
        final long bytesTracked = HelperGroup.ngc_file_transfer_bytes_done_count(
                message.group_identifier, message.msg_id_hash);
        final long done = bytesTracked >= 0L ? bytesTracked
                : (pct >= 0 && total > 0 ? (long) (total * pct / 100.0) : 0L);
        final float speed = HelperGroup.ngc_file_transfer_speed_bps(message.group_identifier, message.msg_id_hash, done);
        final boolean media = isGroupImageMessage(context, message)
                || isGroupVideoMessage(context, message)
                || isGroupAudioMessage(context, message);

        if (outgoing && HelperGroup.is_ngc_outgoing_send_complete(message.group_identifier, message.msg_id_hash))
        {
            return completeSnapshot(true);
        }

        if (NgcGroupFileTransfer.shouldUseChunkedTransfer(total) && outgoing)
        {
            if (HelperGroup.is_ngc_file_transfer_failed(message.group_identifier, message.msg_id_hash)
                    || HelperGroup.is_ngc_transfer_stalled(message.group_identifier, message.msg_id_hash))
            {
                return new Snapshot(Phase.FAILED, done, total, Math.max(pct, 0), speed, true, media);
            }

            final long groupNum = HelperGroup.tox_group_by_groupid__wrapper(message.group_identifier);
            final int groupConn = groupNum >= 0 ? MainActivity.tox_group_is_connected(groupNum) : -1;
            if (groupConn != TRIFAGlobals.TOX_GROUP_CONNECTION_STATUS.TOX_GROUP_CONNECTION_STATUS_CONNECTED.value)
            {
                return new Snapshot(Phase.PENDING, 0L, total, 0, 0f, true, media);
            }

            if (!HelperGroup.is_ngc_transfer_started(message.group_identifier, message.msg_id_hash))
            {
                HelperGroup.schedule_auto_retry_group_file_send(message);
                return new Snapshot(Phase.PENDING, 0L, total, 0, 0f, true, media);
            }

            if (pct >= 0 && pct < 100)
            {
                return new Snapshot(Phase.TRANSFERRING, done, total, pct, speed, true, media);
            }
            if (done > 0L)
            {
                final int derivedPct = total > 0L
                        ? (int) Math.min(100L, (100L * done) / total)
                        : 0;
                return new Snapshot(Phase.TRANSFERRING, done, total, derivedPct, speed, true, media);
            }
            return new Snapshot(Phase.TRANSFERRING, 0L, total, 0, 0f, true, media);
        }

        if (isGroupMessageMediaReady(message, null))
        {
            return completeSnapshot(outgoing);
        }

        if (HelperGroup.is_ngc_file_transfer_failed(message.group_identifier, message.msg_id_hash)
                || HelperGroup.is_ngc_transfer_stalled(message.group_identifier, message.msg_id_hash))
        {
            return new Snapshot(Phase.FAILED, done, total, Math.max(pct, 0), speed, outgoing, media);
        }

        if (pct >= 0 && pct < 100)
        {
            return new Snapshot(Phase.TRANSFERRING, done, total, pct, speed, outgoing, media);
        }

        if (!outgoing)
        {
            if (!HelperGroup.is_group_message_sender_online(message))
            {
                return new Snapshot(Phase.PENDING, 0L, total, 0, 0f, false, media);
            }

            // KHANDAQ (#120): respect the attachment-download policy. When the file has not started and the
            // policy forbids auto-download (and the user has not tapped to fetch it), do NOT kick off a pull;
            // render the manual download affordance instead (FAILED → retry button → onRetry).
            if (!isGroupMessageMediaReady(message, null)
                    && pct < 0
                    && !HelperGroup.ngc_incoming_download_allowed(message.group_identifier, message.msg_id_hash))
            {
                return new Snapshot(Phase.FAILED, 0L, total, 0, 0f, false, media);
            }

            if (NgcGroupFileTransfer.shouldUseChunkedTransfer(total)
                    && !isGroupMessageMediaReady(message, null)
                    && pct < 0
                    && !HelperGroup.is_ngc_file_transfer_failed(message.group_identifier, message.msg_id_hash))
            {
                HelperGroup.kickstart_incoming_chunked_file_transfer(message);
            }

            final int pctAfterKick = HelperGroup.ngc_file_transfer_progress_percent(
                    message.group_identifier, message.msg_id_hash);
            if (pctAfterKick >= 0 && pctAfterKick < 100)
            {
                final long doneAfter = total > 0 ? (long) (total * pctAfterKick / 100.0) : 0L;
                final float speedAfter = HelperGroup.ngc_file_transfer_speed_bps(
                        message.group_identifier, message.msg_id_hash, doneAfter);
                return new Snapshot(Phase.TRANSFERRING, doneAfter, total, pctAfterKick, speedAfter, false, media);
            }

            if (HelperGroup.is_incoming_chunked_file_stalled(message))
            {
                return new Snapshot(Phase.FAILED, 0L, total, 0, 0f, false, media);
            }

            return new Snapshot(Phase.TRANSFERRING, 0L, total, 0, 0f, false, media);
        }

        if (outgoing && total > 0)
        {
            final long groupNum = HelperGroup.tox_group_by_groupid__wrapper(message.group_identifier);
            final int groupConn = groupNum >= 0 ? MainActivity.tox_group_is_connected(groupNum) : -1;
            if (groupConn != TRIFAGlobals.TOX_GROUP_CONNECTION_STATUS.TOX_GROUP_CONNECTION_STATUS_CONNECTED.value)
            {
                return new Snapshot(Phase.PENDING, 0L, total, 0, 0f, true, media);
            }
            if (!HelperGroup.is_ngc_transfer_started(message.group_identifier, message.msg_id_hash))
            {
                HelperGroup.schedule_auto_retry_group_file_send(message);
                return new Snapshot(Phase.PENDING, 0L, total, 0, 0f, true, media);
            }
            if (pct >= 0 && pct < 100)
            {
                return new Snapshot(Phase.TRANSFERRING, done, total, pct, speed, true, media);
            }
            if (done > 0L)
            {
                final int derivedPct = total > 0L
                        ? (int) Math.min(100L, (100L * done) / total)
                        : 0;
                return new Snapshot(Phase.TRANSFERRING, done, total, derivedPct, speed, true, media);
            }
            return new Snapshot(Phase.TRANSFERRING, 0L, total, 0, 0f, true, media);
        }

        return completeSnapshot(outgoing);
    }

    static void applyDirect(final Context context, final View itemRoot, final Message message, final boolean outgoing)
    {
        if (context == null || itemRoot == null || message == null)
        {
            return;
        }

        final Snapshot snap = snapshotFromMessage(context, message, outgoing);
        final String mime = HelperFiletransfer.guess_message_file_mime_type(context, message);
        final boolean isImage = mime != null && mime.startsWith("image/");
        final boolean isVideo = mime != null && mime.startsWith("video/");
        final boolean isAudio = isAudioMessage(context, message);

        if (!outgoing && (snap.phase == Phase.TRANSFERRING || snap.phase == Phase.FAILED))
        {
            HelperFiletransfer.schedule_auto_retry_dm_incoming_file(message);
        }

        apply(context, itemRoot, snap, directCallbacks(context, itemRoot, message, outgoing));

        if (isImage || isVideo)
        {
            ChatFileBubbleHelper.hide(itemRoot);
        }
        else if (!isAudio)
        {
            bindDirectFileRow(context, itemRoot, message, outgoing, snap);
        }
        else
        {
            ChatFileBubbleHelper.hide(itemRoot);
        }

        finishFileBubbleStyle(itemRoot, outgoing, snap, isImage || isVideo);
    }

    static void applyGroup(final Context context, final View itemRoot, final GroupMessage message, final boolean outgoing)
    {
        if (context == null || itemRoot == null || message == null)
        {
            return;
        }

        final Snapshot snap = snapshotFromGroupMessage(context, message, outgoing);
        final boolean isImage = isGroupImageMessage(context, message);
        final boolean isVideo = isGroupVideoMessage(context, message);
        final boolean isAudio = isGroupAudioMessage(context, message);
        final boolean isMediaPreview = isImage || isVideo;

        if (!outgoing && (snap.phase == Phase.TRANSFERRING || snap.phase == Phase.FAILED))
        {
            HelperGroup.schedule_auto_retry_group_file_download(message);
        }
        if (outgoing && (snap.phase == Phase.TRANSFERRING || snap.phase == Phase.FAILED))
        {
            HelperGroup.schedule_auto_retry_group_file_send(message);
        }

        itemRoot.setTag(R.id.m_container, message.msg_id_hash + "#" + message.id);

        ChatFileBubbleHelper.hideMediaChrome(itemRoot);
        final View staleOverlay = itemRoot.findViewById(R.id.ft_transfer_overlay);
        if (staleOverlay != null)
        {
            staleOverlay.setVisibility(View.GONE);
        }

        apply(context, itemRoot, snap, groupCallbacks(context, message, outgoing));

        if (snap.phase == Phase.PENDING && outgoing)
        {
            final TextView detail = itemRoot.findViewById(R.id.ft_transfer_progress_detail);
            if (detail != null)
            {
                detail.setText(context.getString(R.string.group_info_connection_connecting));
                detail.setVisibility(View.VISIBLE);
            }
        }

        if (isMediaPreview)
        {
            ChatFileBubbleHelper.hide(itemRoot);
            if (isVideo)
            {
                ChatFileBubbleHelper.bindVideoMediaChrome(context, itemRoot, message);
            }
            else
            {
                ChatFileBubbleHelper.hideMediaChrome(itemRoot);
            }
        }
        else if (!isAudio)
        {
            bindGroupFileRow(context, itemRoot, message, outgoing, snap);
        }
        else
        {
            ChatFileBubbleHelper.hide(itemRoot);
        }

        finishFileBubbleStyle(itemRoot, outgoing, snap, isMediaPreview);
    }

    private static void apply(final Context context, final View itemRoot, final Snapshot snap, final Callbacks callbacks)
    {
        final com.daimajia.numberprogressbar.NumberProgressBar bar =
                itemRoot.findViewById(R.id.ft_progressbar);
        final View buttons = itemRoot.findViewById(R.id.ft_buttons_container);
        final ImageButton cancelBtn = itemRoot.findViewById(R.id.ft_button_cancel);
        final ImageButton okBtn = itemRoot.findViewById(R.id.ft_button_ok);
        final ImageButton preview = itemRoot.findViewById(R.id.ft_preview_image);

        if (bar != null)
        {
            if (snap.phase == Phase.TRANSFERRING)
            {
                bar.setMax(100);
                bar.setProgress(snap.percent);
                bar.setVisibility(View.VISIBLE);
            }
            else
            {
                bar.setVisibility(View.GONE);
            }
        }

        // Voice messages render their own player and must never show the inline cancel (red X) / retry button.
        final View audioPlayerForButtons = itemRoot.findViewById(R.id.ft_audio_player);
        final boolean isVoiceMessage = audioPlayerForButtons != null
                && audioPlayerForButtons.getVisibility() == View.VISIBLE;

        if (buttons != null && cancelBtn != null)
        {
            if (isVoiceMessage)
            {
                buttons.setVisibility(View.GONE);
                cancelBtn.setVisibility(View.GONE);
                if (okBtn != null)
                {
                    okBtn.setVisibility(View.GONE);
                }
            }
            else if (snap.phase == Phase.TRANSFERRING)
            {
                if (snap.showMediaOverlay)
                {
                    buttons.setVisibility(View.GONE);
                }
                else
                {
                    buttons.setVisibility(View.VISIBLE);
                    cancelBtn.setVisibility(View.VISIBLE);
                    if (okBtn != null)
                    {
                        okBtn.setVisibility(View.GONE);
                    }
                    final Drawable cancelIcon = new IconicsDrawable(context).
                            icon(GoogleMaterial.Icon.gmd_close).
                            backgroundColor(android.graphics.Color.TRANSPARENT).
                            color(android.graphics.Color.parseColor("#CCFFFFFF")).sizeDp(28);
                    cancelBtn.setImageDrawable(cancelIcon);
                    cancelBtn.setOnClickListener(v -> {
                        if (callbacks != null)
                        {
                            callbacks.onCancel();
                        }
                    });
                }
            }
            else if (snap.phase == Phase.FAILED)
            {
                buttons.setVisibility(View.VISIBLE);
                cancelBtn.setVisibility(View.GONE);
                if (okBtn != null)
                {
                    okBtn.setVisibility(View.VISIBLE);
                    final android.graphics.drawable.Drawable retryIcon = new IconicsDrawable(context).
                            icon(GoogleMaterial.Icon.gmd_refresh).
                            backgroundColor(android.graphics.Color.TRANSPARENT).
                            color(android.graphics.Color.parseColor("#CCFFFFFF")).sizeDp(28);
                    okBtn.setImageDrawable(retryIcon);
                    okBtn.setOnClickListener(v -> {
                        if (callbacks != null)
                        {
                            callbacks.onRetry();
                        }
                    });
                }
            }
            else
            {
                buttons.setVisibility(View.GONE);
            }
        }

        bindMediaOverlay(context, itemRoot, preview, snap, callbacks);
    }

    private static void bindDirectFileRow(final Context context, final View itemRoot, final Message message,
                                          final boolean outgoing, final Snapshot snap)
    {
        final View.OnClickListener open = snap.phase == Phase.COMPLETE
                ? v -> ChatMediaHelper.openMessageMedia(context, message, null)
                : null;
        ChatFileBubbleHelper.bindGenericMessage(context, itemRoot, message, open, snap);
        wireFileRowActions(itemRoot, snap, directCallbacks(context, itemRoot, message, outgoing));
    }

    private static void bindGroupFileRow(final Context context, final View itemRoot, final GroupMessage message,
                                         final boolean outgoing, final Snapshot snap)
    {
        final View.OnClickListener open = snap.phase == Phase.COMPLETE
                ? v -> ChatMediaHelper.openGroupMessageMedia(context, message, null)
                : null;
        ChatFileBubbleHelper.bindGenericGroupMessage(context, itemRoot, message, open, snap, outgoing);
        wireFileRowActions(itemRoot, snap, groupCallbacks(context, message, outgoing));
    }

    private static void wireFileRowActions(final View itemRoot, final Snapshot snap, final Callbacks callbacks)
    {
        final View row = itemRoot.findViewById(R.id.ft_file_attachment_row);
        if (row == null)
        {
            return;
        }
        final ImageButton cancel = row.findViewById(R.id.ft_file_cancel);
        final ImageButton retry = row.findViewById(R.id.ft_file_retry);
        if (cancel != null)
        {
            cancel.setOnClickListener(v -> {
                if (callbacks != null)
                {
                    callbacks.onCancel();
                }
            });
        }
        if (retry != null)
        {
            retry.setOnClickListener(v -> {
                if (callbacks != null)
                {
                    callbacks.onRetry();
                }
            });
        }
    }

    private static void bindMediaOverlay(final Context context, final View itemRoot, final View preview,
                                           final Snapshot snap, final Callbacks callbacks)
    {
        if (preview == null || !(preview.getParent() instanceof ViewGroup))
        {
            return;
        }

        final ViewGroup host = (ViewGroup) preview.getParent();

        // Voice messages render their own player; once it's visible (own/finished file) never cover it with
        // the big transfer overlay + red cancel X. (Incoming voice still downloading keeps its player hidden,
        // so the progress overlay is shown as usual until playback is ready.)
        final View audioPlayer = itemRoot.findViewById(R.id.ft_audio_player);
        if (audioPlayer != null && audioPlayer.getVisibility() == View.VISIBLE)
        {
            final View existingOverlay = host.findViewById(R.id.ft_transfer_overlay);
            if (existingOverlay != null)
            {
                existingOverlay.setVisibility(View.GONE);
            }
            return;
        }

        View overlay = host.findViewById(R.id.ft_transfer_overlay);
        if (overlay == null && snap.phase == Phase.TRANSFERRING && snap.showMediaOverlay)
        {
            overlay = LayoutInflater.from(context).inflate(R.layout.chat_media_transfer_overlay, host, false);
            if (preview instanceof ImageButton && host instanceof FrameLayout)
            {
                host.addView(overlay);
            }
            else if (preview.getParent() instanceof ViewGroup)
            {
                wrapPreviewWithOverlay(context, preview, overlay);
            }
        }

        if (overlay == null)
        {
            return;
        }

        if (snap.phase != Phase.TRANSFERRING && snap.phase != Phase.FAILED && snap.phase != Phase.PENDING)
        {
            overlay.setVisibility(View.GONE);
            return;
        }

        if (!snap.showMediaOverlay && snap.phase == Phase.TRANSFERRING)
        {
            overlay.setVisibility(View.GONE);
            return;
        }

        if (!snap.showMediaOverlay && snap.phase == Phase.PENDING)
        {
            overlay.setVisibility(View.GONE);
            return;
        }

        overlay.setVisibility(View.VISIBLE);
        final ProgressBar progress = overlay.findViewById(R.id.ft_transfer_progress);
        final TextView detail = overlay.findViewById(R.id.ft_transfer_progress_detail);
        final TextView error = overlay.findViewById(R.id.ft_transfer_error);
        final ImageButton cancel = overlay.findViewById(R.id.ft_transfer_cancel);
        final ImageButton retry = overlay.findViewById(R.id.ft_transfer_retry);

        if (progress != null)
        {
            progress.setProgress(snap.percent);
        }
        if (detail != null)
        {
            if (snap.phase == Phase.PENDING)
            {
                if (snap.outgoing)
                {
                    detail.setText(context.getString(R.string.chat_ft_status_waiting_friend_online));
                }
                else
                {
                    detail.setText(context.getString(R.string.file_transfer_sender_offline));
                }
                detail.setVisibility(View.VISIBLE);
            }
            else
            {
                detail.setText(formatProgressLine(context, snap));
                detail.setVisibility(snap.phase == Phase.FAILED ? View.GONE : View.VISIBLE);
            }
        }
        if (error != null)
        {
            error.setVisibility(snap.phase == Phase.FAILED ? View.VISIBLE : View.GONE);
            if (snap.phase == Phase.FAILED)
            {
                error.setText(snap.outgoing
                        ? context.getString(R.string.file_transfer_upload_failed)
                        : context.getString(R.string.file_transfer_download_failed));
            }
        }
        if (cancel != null)
        {
            cancel.setVisibility(snap.phase == Phase.TRANSFERRING ? View.VISIBLE : View.GONE);
            cancel.setOnClickListener(v -> {
                if (callbacks != null)
                {
                    callbacks.onCancel();
                }
            });
        }
        if (retry != null)
        {
            retry.setVisibility(snap.phase == Phase.FAILED ? View.VISIBLE : View.GONE);
            retry.setOnClickListener(v -> {
                if (callbacks != null)
                {
                    callbacks.onRetry();
                }
            });
        }
    }

    private static void wrapPreviewWithOverlay(final Context context, final View preview, final View overlay)
    {
        final ViewGroup parent = (ViewGroup) preview.getParent();
        final int index = parent.indexOfChild(preview);
        final ViewGroup.LayoutParams lp = preview.getLayoutParams();
        parent.removeViewAt(index);

        final FrameLayout frame = new FrameLayout(context);
        frame.setLayoutParams(lp);
        parent.addView(frame, index, lp);
        frame.addView(preview, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        frame.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    static String formatProgressLine(final Context context, final Snapshot snap)
    {
        if (snap.bytesTotal <= 0)
        {
            return snap.percent + "%";
        }

        final String doneStr = Formatter.formatFileSize(context, snap.bytesDone);
        final String totalStr = Formatter.formatFileSize(context, snap.bytesTotal);
        if (snap.speedBps > 0f)
        {
            return context.getString(R.string.file_transfer_progress_with_speed,
                    doneStr, totalStr, snap.percent, format_speed(snap.speedBps));
        }
        return context.getString(R.string.file_transfer_progress_format, doneStr, totalStr, snap.percent);
    }

    private static Callbacks directCallbacks(final Context context, final View itemRoot, final Message message,
                                             final boolean outgoing)
    {
        return new Callbacks()
        {
            @Override
            public void onCancel()
            {
                if (outgoing)
                {
                    HelperFiletransfer.cancel_outgoing_filetransfer(message);
                }
                else
                {
                    HelperFiletransfer.cancel_incoming_filetransfer(message);
                }
            }

            @Override
            public void onRetry()
            {
                if (outgoing)
                {
                    HelperFiletransfer.retry_outgoing_filetransfer(message);
                }
                else
                {
                    HelperFiletransfer.retry_incoming_filetransfer(message);
                }
            }
        };
    }

    private static Callbacks groupCallbacks(final Context context, final GroupMessage message, final boolean outgoing)
    {
        return new Callbacks()
        {
            @Override
            public void onCancel()
            {
                if (outgoing)
                {
                    HelperGroup.cancel_ngc_file_send(message.group_identifier, message.msg_id_hash);
                }
            }

            @Override
            public void onRetry()
            {
                if (outgoing)
                {
                    HelperGroup.retry_group_file_send(message);
                }
                else
                {
                    // KHANDAQ (#120): explicit user request → bypass the attachment-download policy for this file.
                    HelperGroup.mark_ngc_manual_download_requested(message.group_identifier, message.msg_id_hash);
                    HelperGroup.retry_group_incoming_file_download(message);
                }
            }
        };
    }

    private static long resolveMessageTotalBytes(final Message message)
    {
        try
        {
            if (message.filetransfer_id > 0)
            {
                return HelperFiletransfer.get_filetransfer_filesize_from_id(message.filetransfer_id);
            }
            if (message.filename_fullpath != null)
            {
                final java.io.File f = new java.io.File(message.filename_fullpath);
                if (f.isFile())
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

    private static float computeDirectSpeedBps(final long startTs, final long bytesDone)
    {
        if (startTs <= 0)
        {
            return 0f;
        }
        final long deltaMs = Math.max(1L, System.currentTimeMillis() - startTs);
        return ((float) bytesDone / (float) deltaMs) * 1000f;
    }

    private static boolean isDirectMediaPreview(final Context context, final Message message)
    {
        try
        {
            final String mime = HelperFiletransfer.guess_message_file_mime_type(context, message);
            return mime != null && (mime.startsWith("image/") || mime.startsWith("video/") || mime.startsWith("audio/"));
        }
        catch (Exception ignored)
        {
        }
        return false;
    }

    private static void finishFileBubbleStyle(final View itemRoot, final boolean outgoing, final Snapshot snap,
                                              final boolean edgeToEdgeMedia)
    {
        final ViewGroup bubble = findFileBubbleContainer(itemRoot);
        if (bubble == null)
        {
            return;
        }

        boolean edgeToEdge = edgeToEdgeMedia;
        if (!edgeToEdge)
        {
            edgeToEdge = snap != null && snap.showMediaOverlay;
            if (!edgeToEdge)
            {
                final View preview = itemRoot.findViewById(R.id.ft_preview_image);
                edgeToEdge = preview != null && preview.getVisibility() == View.VISIBLE;
            }
        }

        ChatBubbleUiHelper.apply_file_message_bubble(bubble, outgoing, edgeToEdge);
        ChatBubbleUiHelper.style_media_preview(itemRoot.findViewById(R.id.ft_preview_image));
    }

    private static ViewGroup findFileBubbleContainer(final View itemRoot)
    {
        ViewGroup bubble = itemRoot.findViewById(R.id.ft_outgoing_rounded_bg);
        if (bubble == null)
        {
            bubble = itemRoot.findViewById(R.id.ft_incoming_rounded_bg);
        }
        if (bubble == null)
        {
            bubble = itemRoot.findViewById(R.id.m_container);
        }
        return bubble;
    }

    private static Snapshot idleSnapshot(final boolean outgoing)
    {
        return new Snapshot(Phase.IDLE, 0L, 0L, 0, 0f, outgoing, false);
    }

    private static Snapshot completeSnapshot(final boolean outgoing)
    {
        return new Snapshot(Phase.COMPLETE, 0L, 0L, 100, 0f, outgoing, false);
    }
}
