package com.zoffcc.applications.trifa;

import android.content.Context;
import android.util.Log;

import com.zoffcc.applications.sorm.FriendList;
import com.zoffcc.applications.sorm.GroupDB;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static com.zoffcc.applications.trifa.TrifaToxService.orma;

/**
 * KHANDAQ (0.2.64): what push previews exchange between friends over Tox, lossless packet 190
 * (docs/PUSH_PREVIEWS.md). 190/01 hands a friend the key K their wakes to us are sealed with; 190/02 tells a friend
 * which group member we are in each of our groups, as hashes, so that their reaction to our message in a group can
 * wake us. Both go where packet 181 (our push URL) goes, and 190/02 also to the friends online when a group is
 * joined or created. They go only from a device that can show previews: a push token and Android 9 or later. What
 * friends send is kept in PushPreviewStore.
 *
 * <p>The Tox calls and the master key (whose first use writes it) run on one worker thread of their own: the callers
 * are the Tox thread, the main thread and the capability pool.
 */
final class PushPreviewPeers
{
    private static final String TAG = "trifa.PushPreviewPeers";
    /** After a group is joined or created: its key is there at once, the delay gathers a burst of joins. */
    private static final long PRESENCE_DELAY_MS = 5000L;

    private static final ScheduledExecutorService WORKER = Executors.newSingleThreadScheduledExecutor(runnable -> {
        final Thread thread = new Thread(runnable, "khandaq-push-preview-peers");
        thread.setDaemon(true);
        return thread;
    });
    private static final Object LOCK = new Object();
    private static ScheduledFuture<?> presenceAnnouncement = null;
    private static volatile Boolean cipherChecked = null;

    private PushPreviewPeers()
    {
    }

    /** This device can read previews: Android 9 or later, with the cipher there. */
    static boolean usable()
    {
        if (!PushPreviewStore.supported())
        {
            return false;
        }
        Boolean available = cipherChecked;
        if (available == null)
        {
            available = PushPreviewCrypto.cipherAvailable();
            cipherChecked = available;
        }
        return available;
    }

    // ---- out ----

    /**
     * Where 181 goes to a friend (HelperFriend.send_pushurl_to_friend, which runs only with a push token): 190/01 with
     * the key for them and 190/02 with our group presence go along.
     */
    static void publishTo(final String friendPubkey)
    {
        if (!usable() || (PushPreviewStore.friendKey(friendPubkey) == null))
        {
            return;
        }
        run(() -> {
            final Context context = MainActivity.context_s;
            final long friendNumber = friendNumberOf(friendPubkey);
            if ((context == null) || (friendNumber < 0))
            {
                return;
            }
            // The copy of the muted chats first: no preview can come before the friend has the key.
            refreshMutedChatsNow(context);
            final byte[] key = PushPreviewCrypto.friendKey(
                    PushPreviewStore.masterKey(context),
                    PushPreviewCrypto.unhex(PushPreviewStore.friendKey(friendPubkey),
                                            PushPreviewCrypto.PUBLIC_KEY_BYTES));
            final byte[] keyPacket = PushPreviewCrypto.keyPacket(key);
            // The friend goes on the list of senders whose previews are shown before the key goes to them, and the key
            // goes only to someone on it (PushPreviewStore.PREFS_ALLOWED).
            if ((keyPacket != null) && PushPreviewStore.allowSender(context, friendPubkey))
            {
                MainActivity.tox_friend_send_lossless_packet(friendNumber, keyPacket, keyPacket.length);
            }
            final byte[] presence = PushPreviewCrypto.presencePacket(ownPresenceHashes());
            MainActivity.tox_friend_send_lossless_packet(friendNumber, presence, presence.length);
        });
    }

    /**
     * A group was joined or created (HelperGroup): a little later, 190/02 to every friend online, and the copy of the
     * muted chats brought up to date, since a group may come in muted.
     */
    static void groupsChanged()
    {
        if (!usable())
        {
            return;
        }
        synchronized (LOCK)
        {
            if (presenceAnnouncement != null)
            {
                presenceAnnouncement.cancel(false);
            }
            try
            {
                presenceAnnouncement = WORKER.schedule(PushPreviewPeers::announcePresence, PRESENCE_DELAY_MS,
                                                        TimeUnit.MILLISECONDS);
            }
            catch (RejectedExecutionException e)
            {
                Log.i(TAG, "groupsChanged:EE:rejected");
            }
        }
    }

    private static void announcePresence()
    {
        try
        {
            final Context context = MainActivity.context_s;
            if (context == null)
            {
                return;
            }
            refreshMutedChatsNow(context);
            HelperRelay.own_push_token_load();
            if (!MainActivity.PREF__use_push_service || (TRIFAGlobals.global_notification_token == null))
            {
                return;
            }
            final long[] friends = MainActivity.tox_self_get_friend_list();
            if (friends == null)
            {
                return;
            }
            final byte[] presence = PushPreviewCrypto.presencePacket(ownPresenceHashes());
            for (final long friendNumber : friends)
            {
                if (MainActivity.tox_friend_get_connection_status(friendNumber)
                    == ToxVars.TOX_CONNECTION.TOX_CONNECTION_NONE.value)
                {
                    continue;
                }
                final String friendPubkey = HelperFriend.tox_friend_get_public_key__wrapper(friendNumber);
                if ((PushPreviewStore.friendKey(friendPubkey) == null) || HelperRelay.is_any_relay(friendPubkey))
                {
                    continue;
                }
                MainActivity.tox_friend_send_lossless_packet(friendNumber, presence, presence.length);
            }
        }
        catch (Throwable e)
        {
            Log.i(TAG, "announcePresence:EE:" + e.getClass().getSimpleName());
        }
    }

    /** The presence hash of each group we are in and have our key of, the first 64, none twice. */
    private static List<byte[]> ownPresenceHashes()
    {
        final List<byte[]> hashes = new ArrayList<>();
        final long[] groups = MainActivity.tox_group_get_grouplist();
        if (groups == null)
        {
            return hashes;
        }
        final Set<String> listed = new HashSet<>();
        for (final long groupNumber : groups)
        {
            if (hashes.size() == PushPreviewCrypto.MAX_PRESENCE_HASHES)
            {
                break;
            }
            // A group being left is gone for the app (GroupPendingLeaves) while toxcore still holds it.
            if (GroupPendingLeaves.holdsGroupNumber(groupNumber))
            {
                continue;
            }
            final byte[] hash = PushPreviewCrypto.presenceHash(
                    PushPreviewCrypto.unhex(HelperGroup.tox_group_by_groupnum__wrapper(groupNumber),
                                            PushPreviewCrypto.CHAT_ID_BYTES),
                    PushPreviewCrypto.unhex(MainActivity.tox_group_self_get_public_key(groupNumber),
                                            PushPreviewCrypto.PUBLIC_KEY_BYTES));
            if ((hash != null) && listed.add(PushPreviewCrypto.hex(hash)))
            {
                hashes.add(hash);
            }
        }
        return hashes;
    }

    // ---- in ----

    /** Packet 190 from a friend (MainActivity's lossless dispatch, Tox thread). Anything malformed is dropped. */
    static void onPacket(final long friendNumber, final byte[] data, final int length)
    {
        try
        {
            if (!usable() || (data == null) || (length < 3) || (data.length < length))
            {
                return;
            }
            final Context context = MainActivity.context_s;
            final String friendPubkey = HelperFriend.tox_friend_get_public_key__wrapper(friendNumber);
            if ((context == null) || (PushPreviewStore.friendKey(friendPubkey) == null))
            {
                return;
            }
            if (data[1] == PushPreviewCrypto.SUBTYPE_KEY)
            {
                final byte[] key = PushPreviewCrypto.keyFromPacket(data, length);
                if (key != null)
                {
                    PushPreviewStore.storePeerKey(context, friendPubkey, key);
                }
            }
            else if (data[1] == PushPreviewCrypto.SUBTYPE_GROUP_PRESENCE)
            {
                final List<String> hashes = PushPreviewCrypto.presenceFromPacket(data, length);
                if (hashes != null)
                {
                    PushPreviewStore.storePresence(context, friendPubkey, hashes);
                }
            }
        }
        catch (RuntimeException e)
        {
            Log.i(TAG, "onPacket:EE:" + e.getClass().getSimpleName());
        }
    }

    // ---- what goes with a friend ----

    /** A friend deleted (HelperFriend.delete_friend): their K and their group presence go too. */
    static void forgetFriend(final String friendPubkey)
    {
        // On the worker: the caller may be the main thread, and the first read of these preferences reads a file.
        run(() -> PushPreviewStore.forgetFriend(MainActivity.context_s, friendPubkey));
    }

    // ---- chats muted on this device ----

    /**
     * A chat was muted or unmuted: the copy the push handler reads (it must not open the database) follows, off the
     * caller's thread.
     */
    static void refreshMutedChats()
    {
        if (!usable())
        {
            return;
        }
        run(() -> {
            final Context context = MainActivity.context_s;
            if (context != null)
            {
                refreshMutedChatsNow(context);
            }
        });
    }

    private static void refreshMutedChatsNow(final Context context)
    {
        if (orma == null)
        {
            return;
        }
        try
        {
            final Set<String> muted = new HashSet<>();
            final List<FriendList> friends = orma.selectFromFriendList().notification_silentEq(true).toList();
            if (friends != null)
            {
                for (final FriendList f : friends)
                {
                    muted.add(f.tox_public_key_string);
                }
            }
            final List<GroupDB> groups = orma.selectFromGroupDB().notification_silentEq(true).toList();
            if (groups != null)
            {
                for (final GroupDB g : groups)
                {
                    muted.add(g.group_identifier);
                }
            }
            PushPreviewStore.storeMutedChats(context, muted);
        }
        catch (RuntimeException e)
        {
            Log.i(TAG, "refreshMutedChats:EE:" + e.getClass().getSimpleName());
        }
    }

    // ---- inside ----

    private static long friendNumberOf(final String friendPubkey)
    {
        final long friendNumber = HelperFriend.tox_friend_by_public_key__wrapper(friendPubkey);
        return ((friendNumber < 0) || (friendNumber == TRIFAGlobals.UINT32_MAX_JAVA)) ? -1 : friendNumber;
    }

    private static void run(final Runnable task)
    {
        try
        {
            WORKER.execute(() -> {
                try
                {
                    task.run();
                }
                catch (Throwable e)
                {
                    // A Tox call can fail while Tox shuts down; the next connect sends again.
                    Log.i(TAG, "worker:EE:" + e.getClass().getSimpleName());
                }
            });
        }
        catch (RejectedExecutionException e)
        {
            Log.i(TAG, "worker:EE:rejected");
        }
    }
}
