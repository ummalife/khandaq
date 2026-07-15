package com.zoffcc.applications.trifa;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.zoffcc.applications.sorm.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.zoffcc.applications.trifa.MainActivity.main_handler_s;
import static com.zoffcc.applications.trifa.TrifaToxService.orma;

/**
 * Sends multiple picked attachments one after another to avoid concurrent tox FT / NGC send crashes.
 * All items are staged in the chat first, then transfers run sequentially.
 */
public final class OutgoingAttachmentQueue
{
    private static final String TAG = "trifa.OutgoingAttachQ";

    private static final long WAIT_STEP_MS = 400L;
    private static final long GROUP_FILE_TIMEOUT_MS = 45 * 60 * 1000L;

    private static final Handler MAIN = main_handler_s != null ? main_handler_s : new Handler(Looper.getMainLooper());

    private static final ConcurrentHashMap<String, ConcurrentLinkedQueue<GroupMessageListActivity.GroupOutgoingFileHandle>> groupSendQueues =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, AtomicBoolean> groupSendRunning = new ConcurrentHashMap<>();

    // KHANDAQ: value = last enqueue timestamp. This map used to be a permanent "seen" set whose
    // keys were only released on staging FAILURE — so the SAME file could be sent to a group only
    // once per process lifetime; every later attempt was silently dropped (tester: photo with an
    // emoji caption "sends only the emoji" — the caption goes out via a separate path). Keep it
    // only as a short double-dispatch guard: an identical uri is rejected within a small window,
    // then allowed again.
    private static final long DUPLICATE_SUPPRESS_MS = 5_000L;
    private static final ConcurrentHashMap<String, ConcurrentHashMap<String, Long>> queuedUriKeys =
            new ConcurrentHashMap<>();

    private OutgoingAttachmentQueue()
    {
    }

    public static void enqueueFriendUris(final Context context, final List<Uri> uris,
                                         final long friendnum, final boolean activityPeer)
    {
        if (context == null || uris == null || uris.isEmpty())
        {
            return;
        }

        final Context appContext = context.getApplicationContext();
        final String key = friendQueueKey(friendnum, activityPeer);
        runOnMain(() -> stageFriendBatch(appContext, uris, friendnum, activityPeer, key));
    }

    public static void enqueueGroupUris(final Context context, final List<Uri> uris,
                                        final String groupId, final boolean activityPeer)
    {
        enqueueGroupUris(context, uris, groupId, activityPeer, null);
    }

    public static void enqueueGroupUris(final Context context, final List<Uri> uris,
                                        final String groupId, final boolean activityPeer,
                                        final String caption)
    {
        if (context == null || uris == null || uris.isEmpty() || groupId == null || groupId.isEmpty())
        {
            return;
        }

        final Context appContext = context.getApplicationContext();
        final String key = groupQueueKey(groupId, activityPeer);
        runOnMain(() -> stageGroupBatch(appContext, uris, groupId, activityPeer, key, caption));
    }

    public static void enqueueFriendUri(final Context context, final Uri uri,
                                        final long friendnum, final boolean activityPeer)
    {
        if (uri == null)
        {
            return;
        }
        final List<Uri> one = new ArrayList<>(1);
        one.add(uri);
        enqueueFriendUris(context, one, friendnum, activityPeer);
    }

    public static void enqueueGroupUri(final Context context, final Uri uri,
                                       final String groupId, final boolean activityPeer)
    {
        if (uri == null)
        {
            return;
        }
        final List<Uri> one = new ArrayList<>(1);
        one.add(uri);
        enqueueGroupUris(context, one, groupId, activityPeer);
    }

    private static void stageFriendBatch(final Context appContext, final List<Uri> uris,
                                         final long friendnum, final boolean activityPeer, final String key)
    {
        String pubkey = null;
        int staged = 0;
        final boolean multi = uris.size() > 1;

        for (Uri uri : uris)
        {
            if (uri == null || !queueAcceptsUri(key, uri))
            {
                continue;
            }

            final boolean startNow = !multi && staged == 0;
            final long messageId = MessageListActivity.add_attachment_from_uri(
                    appContext, uri, friendnum, activityPeer, startNow);
            if (messageId <= 0L)
            {
                queueReleaseUri(key, uri);
                Log.i(TAG, "stageFriendBatch:skip failed uri=" + uri);
                continue;
            }

            staged++;
            try
            {
                final Message m = (Message) orma.selectFromMessage().idEq(messageId).get(0);
                if (m != null && m.tox_friendpubkey != null && !m.tox_friendpubkey.isEmpty())
                {
                    pubkey = m.tox_friendpubkey;
                }
            }
            catch (Exception ignored)
            {
            }
        }

        if (multi && pubkey != null)
        {
            HelperFiletransfer.try_start_next_queued_outgoing_file(pubkey);
        }
    }

    private static void stageGroupBatch(final Context appContext, final List<Uri> uris,
                                        final String groupId, final boolean activityPeer, final String key,
                                        final String caption)
    {
        final List<GroupMessageListActivity.GroupOutgoingFileHandle> handles = new ArrayList<>();

        for (Uri uri : uris)
        {
            if (uri == null || !queueAcceptsUri(key, uri))
            {
                continue;
            }

            final GroupMessageListActivity.GroupOutgoingFileHandle handle =
                    GroupMessageListActivity.add_attachment_from_uri(
                            appContext, uri, groupId, activityPeer, false);
            if (handle == null)
            {
                queueReleaseUri(key, uri);
                Log.w(TAG, "stageGroupBatch:skip groupId=" + groupId + " uri=" + uri);
                continue;
            }
            handles.add(handle);
        }

        if (handles.isEmpty())
        {
            if (caption != null)
            {
                // nothing staged — still deliver the caption text like a plain message
                MAIN.postDelayed(() -> MediaSendPreviewHelper.sendGroupCaption(appContext, groupId, caption),
                        1_200L);
            }
            return;
        }

        if (caption != null)
        {
            // Send the caption only after the batch's LAST file finishes broadcasting: NGC
            // receivers render a file only once it fully arrives, so a caption sent earlier
            // shows up ABOVE its photo on their side.
            startGroupCaptionWatcher(appContext, groupId, handles.get(handles.size() - 1), caption);
        }

        if (handles.size() == 1)
        {
            final GroupMessageListActivity.GroupOutgoingFileHandle handle = handles.get(0);
            HelperGroup.start_group_file_send_by_hash(handle.groupId, handle.msgIdHash);
            return;
        }

        final ConcurrentLinkedQueue<GroupMessageListActivity.GroupOutgoingFileHandle> queue =
                groupSendQueues.computeIfAbsent(key, k -> new ConcurrentLinkedQueue<>());
        for (GroupMessageListActivity.GroupOutgoingFileHandle handle : handles)
        {
            queue.add(handle);
        }
        kickGroupSendWorker(appContext, key);
    }

    /** How long a group caption waits for its file broadcast before being sent anyway. */
    private static final long GROUP_CAPTION_MAX_WAIT_MS = 90_000L;

    private static void startGroupCaptionWatcher(final Context appContext, final String groupId,
                                                 final GroupMessageListActivity.GroupOutgoingFileHandle handle,
                                                 final String caption)
    {
        final Thread watcher = new Thread(() -> {
            try
            {
                final long deadline = System.currentTimeMillis() + GROUP_CAPTION_MAX_WAIT_MS;
                while (System.currentTimeMillis() < deadline)
                {
                    if (HelperGroup.is_ngc_outgoing_send_complete(handle.groupId, handle.msgIdHash)
                            || HelperGroup.is_ngc_file_transfer_failed(handle.groupId, handle.msgIdHash)
                            || HelperGroup.ngc_file_transfer_progress_percent(handle.groupId, handle.msgIdHash) >= 100)
                    {
                        break;
                    }
                    try
                    {
                        Thread.sleep(500L);
                    }
                    catch (InterruptedException ignored)
                    {
                    }
                }
            }
            catch (Exception e)
            {
                Log.i(TAG, "groupCaptionWatcher:EE:" + e.getMessage());
            }
            // small grace so the receiver-side file insert lands before the text packet
            MAIN.postDelayed(() -> {
                try
                {
                    MediaSendPreviewHelper.sendGroupCaption(appContext, groupId, caption);
                }
                catch (Exception e)
                {
                    Log.i(TAG, "groupCaptionWatcher:send:EE:" + e.getMessage());
                }
            }, 700L);
        }, "group-caption-wait");
        watcher.setDaemon(true);
        watcher.start();
    }

    private static void kickGroupSendWorker(final Context appContext, final String key)
    {
        final AtomicBoolean flag = groupSendRunning.computeIfAbsent(key, k -> new AtomicBoolean(false));
        if (!flag.compareAndSet(false, true))
        {
            return;
        }

        final Thread worker = new Thread(() -> runGroupSendWorker(appContext, key), "outgoing-group-" + key);
        worker.setDaemon(true);
        worker.start();
    }

    private static void runGroupSendWorker(final Context appContext, final String key)
    {
        try
        {
            final ConcurrentLinkedQueue<GroupMessageListActivity.GroupOutgoingFileHandle> queue =
                    groupSendQueues.get(key);
            if (queue == null)
            {
                return;
            }

            GroupMessageListActivity.GroupOutgoingFileHandle handle;
            while ((handle = queue.poll()) != null)
            {
                try
                {
                    HelperGroup.start_group_file_send_by_hash(handle.groupId, handle.msgIdHash);
                    waitForGroupFileSend(handle, GROUP_FILE_TIMEOUT_MS);
                }
                catch (Exception e)
                {
                    Log.i(TAG, "runGroupSendWorker:job:EE:" + e.getMessage());
                }
            }
        }
        finally
        {
            final AtomicBoolean flag = groupSendRunning.get(key);
            if (flag != null)
            {
                flag.set(false);
            }
            final ConcurrentLinkedQueue<GroupMessageListActivity.GroupOutgoingFileHandle> queue =
                    groupSendQueues.get(key);
            if (queue != null && !queue.isEmpty())
            {
                kickGroupSendWorker(appContext, key);
            }
        }
    }

    private static final long GROUP_SEND_STALL_MS = 30_000L;

    private static void waitForGroupFileSend(final GroupMessageListActivity.GroupOutgoingFileHandle handle,
                                             final long timeoutMs)
    {
        if (handle == null)
        {
            return;
        }

        if (!handle.chunked)
        {
            HelperGroup.record_ngc_transfer_started(handle.groupId, handle.msgIdHash);
            final long deadline = System.currentTimeMillis() + 60_000L;
            while (System.currentTimeMillis() < deadline)
            {
                if (HelperGroup.is_ngc_file_transfer_failed(handle.groupId, handle.msgIdHash))
                {
                    return;
                }
                final int percent = HelperGroup.ngc_file_transfer_progress_percent(
                        handle.groupId, handle.msgIdHash);
                if (percent >= 100 || HelperGroup.is_ngc_outgoing_send_complete(handle.groupId, handle.msgIdHash))
                {
                    return;
                }
                try
                {
                    Thread.sleep(WAIT_STEP_MS);
                }
                catch (InterruptedException ignored)
                {
                }
            }
            return;
        }

        HelperGroup.record_ngc_transfer_started(handle.groupId, handle.msgIdHash);

        final long deadline = System.currentTimeMillis() + timeoutMs;
        long lastProgressTs = System.currentTimeMillis();
        boolean sawProgress = false;
        while (System.currentTimeMillis() < deadline)
        {
            if (HelperGroup.is_ngc_file_transfer_failed(handle.groupId, handle.msgIdHash))
            {
                return;
            }

            final int percent = HelperGroup.ngc_file_transfer_progress_percent(
                    handle.groupId, handle.msgIdHash);
            if (percent >= 100 || HelperGroup.is_ngc_outgoing_send_complete(handle.groupId, handle.msgIdHash))
            {
                return;
            }

            if (percent >= 0)
            {
                sawProgress = true;
                lastProgressTs = System.currentTimeMillis();
            }

            if (!sawProgress && (System.currentTimeMillis() - lastProgressTs) >= GROUP_SEND_STALL_MS)
            {
                HelperGroup.mark_ngc_file_transfer_failed(handle.groupId, handle.msgIdHash);
                return;
            }

            if (sawProgress && percent < 0)
            {
                if (HelperGroup.is_ngc_outgoing_send_complete(handle.groupId, handle.msgIdHash))
                {
                    return;
                }
            }

            if (HelperGroup.is_ngc_outgoing_send_complete(handle.groupId, handle.msgIdHash))
            {
                return;
            }

            try
            {
                Thread.sleep(WAIT_STEP_MS);
            }
            catch (InterruptedException ignored)
            {
            }
        }

        if (!HelperGroup.is_ngc_outgoing_send_complete(handle.groupId, handle.msgIdHash)
                && (!sawProgress || HelperGroup.ngc_file_transfer_progress_percent(handle.groupId, handle.msgIdHash) < 100))
        {
            HelperGroup.mark_ngc_file_transfer_failed(handle.groupId, handle.msgIdHash);
        }
    }

    private static void runOnMain(final Runnable runnable)
    {
        if (Looper.myLooper() == Looper.getMainLooper())
        {
            runnable.run();
        }
        else
        {
            MAIN.post(runnable);
        }
    }

    private static boolean queueAcceptsUri(final String queueKey, final Uri uri)
    {
        final ConcurrentHashMap<String, Long> keys =
                queuedUriKeys.computeIfAbsent(queueKey, k -> new ConcurrentHashMap<>());
        final String uriKey = uri.toString();
        final long now = System.currentTimeMillis();
        final Long last = keys.get(uriKey);
        if (last != null && (now - last) < DUPLICATE_SUPPRESS_MS)
        {
            return false;
        }
        keys.put(uriKey, now);
        return true;
    }

    private static void queueReleaseUri(final String queueKey, final Uri uri)
    {
        if (uri == null)
        {
            return;
        }

        final ConcurrentHashMap<String, Long> keys = queuedUriKeys.get(queueKey);
        if (keys != null)
        {
            keys.remove(uri.toString());
        }
    }

    private static String friendQueueKey(final long friendnum, final boolean activityPeer)
    {
        if (activityPeer)
        {
            return "friend:activity";
        }
        return "friend:" + friendnum;
    }

    private static String groupQueueKey(final String groupId, final boolean activityPeer)
    {
        if (activityPeer)
        {
            return "group:activity";
        }
        return "group:" + groupId.toLowerCase();
    }
}
