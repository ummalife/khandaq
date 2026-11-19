package com.zoffcc.applications.trifa;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * KHANDAQ (0.2.64): where push previews keep what they know (docs/PUSH_PREVIEWS.md): this installation's master key
 * M, the K each contact handed us, the group presence each contact announced, and the chats muted on this device.
 * All of it is in app-private SharedPreferences, which the push handler can read in a process that has not opened
 * the database. The logic takes the SharedPreferences, so that the unit tests run it on memory.
 */
final class PushPreviewStore
{
    static final String PREFS_MASTER = "khandaq_push_preview";
    static final String KEY_MASTER = "master";
    static final String PREFS_PEERS = "khandaq_push_preview_peers";
    static final String PREFS_PRESENCE = "khandaq_group_presence";
    /**
     * The chats whose notifications are off on this device (FriendList and GroupDB notification_silent), copied
     * out of the database for the push handler, which must not open it.
     */
    static final String PREFS_MUTED = "khandaq_push_preview_muted";
    static final String KEY_MUTED = "chats";
    /**
     * The contacts this device handed its K to and still has: the only senders whose previews it shows. Someone
     * deleted or blocked keeps the K they were given (and the wake URL), and without this list their wakes would
     * still put their name and words on the lock screen.
     */
    static final String PREFS_ALLOWED = "khandaq_push_preview_allowed";
    static final String KEY_ALLOWED = "senders";
    /** Every file this store and PushPreviewSeen write, for a profile that is wiped. */
    private static final String[] ALL_PREFS = {PREFS_MASTER, PREFS_PEERS, PREFS_PRESENCE, PREFS_MUTED, PREFS_ALLOWED,
                                               PushPreviewSeen.PREFS_SEEN};
    private static final Object ALLOWED_LOCK = new Object();

    private static final Object MASTER_LOCK = new Object();

    private PushPreviewStore()
    {
    }

    /** Previews need ChaCha20-Poly1305, which Android has from 9 (API 28) on. */
    static boolean supported()
    {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P;
    }

    private static SharedPreferences prefs(final Context context, final String name)
    {
        return context.getSharedPreferences(name, Context.MODE_PRIVATE);
    }

    // ---- this installation's master key ----

    /** M, made and stored on first use; null when it cannot be stored. Never on the main thread: it commits. */
    static byte[] masterKey(final Context context)
    {
        return context == null ? null : masterKey(prefs(context, PREFS_MASTER), true);
    }

    /** M when there is one; the push handler never makes one, since a new M opens nothing that came before. */
    static byte[] existingMasterKey(final Context context)
    {
        return context == null ? null : masterKey(prefs(context, PREFS_MASTER), false);
    }

    static byte[] masterKey(final SharedPreferences prefs, final boolean create)
    {
        synchronized (MASTER_LOCK)
        {
            final byte[] stored = PushPreviewCrypto.unhex(prefs.getString(KEY_MASTER, null), PushPreviewCrypto.KEY_BYTES);
            if (stored != null || !create)
            {
                return stored;
            }
            final byte[] fresh = PushPreviewCrypto.randomBytes(PushPreviewCrypto.KEY_BYTES);
            // commit, not apply: a K handed to a contact must never outlive the M it came from.
            if (!prefs.edit().putString(KEY_MASTER, PushPreviewCrypto.hex(fresh)).commit())
            {
                return null;
            }
            return fresh;
        }
    }

    // ---- what contacts handed us ----

    /** The key under which a contact is kept: its public key, 64 uppercase hex, or null for anything else. */
    static String friendKey(final String friendPublicKey)
    {
        if (friendPublicKey == null || friendPublicKey.length() < PushPreviewCrypto.PUBLIC_KEY_BYTES * 2)
        {
            return null;
        }
        final String key = friendPublicKey.substring(0, PushPreviewCrypto.PUBLIC_KEY_BYTES * 2);
        return PushPreviewCrypto.unhex(key, PushPreviewCrypto.PUBLIC_KEY_BYTES) == null
               ? null : key.toUpperCase(Locale.ROOT);
    }

    static void storePeerKey(final Context context, final String friendPublicKey, final byte[] key)
    {
        if (context != null)
        {
            storePeerKey(prefs(context, PREFS_PEERS), friendPublicKey, key);
        }
    }

    static void storePeerKey(final SharedPreferences prefs, final String friendPublicKey, final byte[] key)
    {
        final String friend = friendKey(friendPublicKey);
        if (friend == null || key == null || key.length != PushPreviewCrypto.KEY_BYTES)
        {
            return;
        }
        final String value = PushPreviewCrypto.hex(key);
        // The same key comes again each time the contact connects: nothing to write then.
        if (!value.equals(prefs.getString(friend, null)))
        {
            prefs.edit().putString(friend, value).apply();
        }
    }

    /** The K a contact handed us, or null: then its wakes stay the generic ones. */
    static byte[] peerKey(final Context context, final String friendPublicKey)
    {
        return context == null ? null : peerKey(prefs(context, PREFS_PEERS), friendPublicKey);
    }

    static byte[] peerKey(final SharedPreferences prefs, final String friendPublicKey)
    {
        final String friend = friendKey(friendPublicKey);
        return friend == null ? null
                              : PushPreviewCrypto.unhex(prefs.getString(friend, null), PushPreviewCrypto.KEY_BYTES);
    }

    // ---- group presence ----

    static void storePresence(final Context context, final String friendPublicKey, final List<String> hashes)
    {
        if (context != null)
        {
            storePresence(prefs(context, PREFS_PRESENCE), friendPublicKey, hashes);
        }
    }

    /** Each packet replaces the set kept for that contact; an empty one removes it. */
    static void storePresence(final SharedPreferences prefs, final String friendPublicKey, final List<String> hashes)
    {
        final String friend = friendKey(friendPublicKey);
        if (friend == null || hashes == null)
        {
            return;
        }
        final StringBuilder joined = new StringBuilder(hashes.size() * 33);
        for (final String hash : hashes)
        {
            if (isPresenceHash(hash))
            {
                if (joined.length() > 0)
                {
                    joined.append(',');
                }
                joined.append(hash.toLowerCase(Locale.ROOT));
            }
        }
        final String value = joined.toString();
        if (value.isEmpty())
        {
            if (prefs.contains(friend))
            {
                prefs.edit().remove(friend).apply();
            }
        }
        else if (!value.equals(prefs.getString(friend, null)))
        {
            prefs.edit().putString(friend, value).apply();
        }
    }

    /** The contacts whose announced set holds this presence hash (32 hex). */
    static List<String> friendsWithPresence(final Context context, final String hash)
    {
        return context == null ? Collections.<String>emptyList()
                               : friendsWithPresence(prefs(context, PREFS_PRESENCE), hash);
    }

    static List<String> friendsWithPresence(final SharedPreferences prefs, final String hash)
    {
        final List<String> friends = new ArrayList<>();
        if (!isPresenceHash(hash))
        {
            return friends;
        }
        final String wanted = hash.toLowerCase(Locale.ROOT);
        for (final Map.Entry<String, ?> entry : prefs.getAll().entrySet())
        {
            if (!(entry.getValue() instanceof String) || friendKey(entry.getKey()) == null)
            {
                continue;
            }
            for (final String held : ((String) entry.getValue()).split(","))
            {
                if (held.equals(wanted))
                {
                    friends.add(entry.getKey());
                    break;
                }
            }
        }
        return friends;
    }

    static boolean isPresenceHash(final String hash)
    {
        return PushPreviewCrypto.unhex(hash, PushPreviewCrypto.PRESENCE_HASH_BYTES) != null;
    }

    // ---- the senders whose previews are shown ----

    /**
     * Before our K goes to a contact: from then on their previews may be shown. False when it could not be written;
     * the K must not go then, since every preview it seals would come out as the generic wake.
     */
    static boolean allowSender(final Context context, final String friendPublicKey)
    {
        return context != null && allowSender(prefs(context, PREFS_ALLOWED), friendPublicKey);
    }

    static boolean allowSender(final SharedPreferences prefs, final String friendPublicKey)
    {
        final String friend = friendKey(friendPublicKey);
        if (friend == null)
        {
            return false;
        }
        synchronized (ALLOWED_LOCK)
        {
            final Set<String> held = prefs.getStringSet(KEY_ALLOWED, Collections.<String>emptySet());
            if (held.contains(friend))
            {
                return true;
            }
            // A copy: the set getStringSet returns must not be changed. commit, not apply: the K goes right after.
            final Set<String> updated = new HashSet<>(held);
            updated.add(friend);
            return prefs.edit().putStringSet(KEY_ALLOWED, updated).commit();
        }
    }

    /** True when this device handed its K to that contact and has not deleted or blocked them since. */
    static boolean isAllowedSender(final Context context, final String friendPublicKey)
    {
        return context != null && isAllowedSender(prefs(context, PREFS_ALLOWED), friendPublicKey);
    }

    static boolean isAllowedSender(final SharedPreferences prefs, final String friendPublicKey)
    {
        final String friend = friendKey(friendPublicKey);
        return friend != null && prefs.getStringSet(KEY_ALLOWED, Collections.<String>emptySet()).contains(friend);
    }

    // ---- a contact removed ----

    /**
     * Deleting a friend (blocking one deletes them too) deletes their K and their group presence, and their previews
     * are not shown any more.
     */
    static void forgetFriend(final Context context, final String friendPublicKey)
    {
        if (context != null)
        {
            forgetFriend(prefs(context, PREFS_PEERS), prefs(context, PREFS_PRESENCE), prefs(context, PREFS_ALLOWED),
                         friendPublicKey);
        }
    }

    static void forgetFriend(final SharedPreferences peers, final SharedPreferences presence,
                             final SharedPreferences allowed, final String friendPublicKey)
    {
        final String friend = friendKey(friendPublicKey);
        if (friend == null)
        {
            return;
        }
        if (peers.contains(friend))
        {
            peers.edit().remove(friend).apply();
        }
        if (presence.contains(friend))
        {
            presence.edit().remove(friend).apply();
        }
        synchronized (ALLOWED_LOCK)
        {
            final Set<String> held = allowed.getStringSet(KEY_ALLOWED, Collections.<String>emptySet());
            if (held.contains(friend))
            {
                final Set<String> updated = new HashSet<>(held);
                updated.remove(friend);
                // commit: a preview from them that comes in right after must already be refused.
                allowed.edit().putStringSet(KEY_ALLOWED, updated).commit();
            }
        }
    }

    /**
     * The profile is wiped (DbSecretKeyStorage.wipeUserProfile): M goes, and with it every K handed out under it, so
     * that no contact of the old profile can put a preview on the screen, together with everything else kept here.
     */
    static void wipeAll(final Context context)
    {
        if (context == null)
        {
            return;
        }
        synchronized (MASTER_LOCK)
        {
            synchronized (ALLOWED_LOCK)
            {
                for (final String name : ALL_PREFS)
                {
                    prefs(context, name).edit().clear().commit();
                }
            }
        }
        PushPreviewSeen.SHARED.forgetAll();
    }

    // ---- chats muted on this device ----

    static void storeMutedChats(final Context context, final Collection<String> chatKeys)
    {
        if (context != null)
        {
            storeMutedChats(prefs(context, PREFS_MUTED), chatKeys);
        }
    }

    static void storeMutedChats(final SharedPreferences prefs, final Collection<String> chatKeys)
    {
        final Set<String> keys = new HashSet<>();
        for (final String key : chatKeys)
        {
            if (key != null && !key.isEmpty())
            {
                keys.add(key.toUpperCase(Locale.ROOT));
            }
        }
        if (!keys.equals(prefs.getStringSet(KEY_MUTED, Collections.<String>emptySet())))
        {
            prefs.edit().putStringSet(KEY_MUTED, keys).apply();
        }
    }

    /** True when the chat of this friend key or group id is muted on this device, as last copied. */
    static boolean isMuted(final Context context, final String chatKey)
    {
        return context != null && isMuted(prefs(context, PREFS_MUTED), chatKey);
    }

    static boolean isMuted(final SharedPreferences prefs, final String chatKey)
    {
        return chatKey != null
               && prefs.getStringSet(KEY_MUTED, Collections.<String>emptySet()).contains(chatKey.toUpperCase(Locale.ROOT));
    }
}
