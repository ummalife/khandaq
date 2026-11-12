package com.zoffcc.applications.trifa;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.zoffcc.applications.sorm.FriendList;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * What the conversation screen remembers that the Tox profile and its history do not: the hours a
 * contact was seen online (the day strip and the best time to write) and when their key was verified
 * in the dossier. The same marks as AirLocalStore on iOS; mute is the contact's own notification switch
 * (FriendList.notification_silent), which the notifications already obey.
 *
 * <p>All of it is one JSON value in the encrypted globals table, like drafts and AirRequestStore, so the
 * Message and FriendList tables keep their schema and nothing of it goes over the wire. Contacts are
 * filed under a hash of their key; hours are the starts of local clock hours in seconds since 1970 and
 * are kept for seven days.
 *
 * <p>Calls may come from any thread: toxcore's connection callback, the service loop, the chat screen.
 * Writes are coalesced and reach the database on one background thread; listeners hear on the main
 * thread. "Online since" lives in memory only, as on iOS: a restart starts it again.
 */
final class AirContactMarks
{
    private static final String TAG = "trifa.AirMarks";

    static final String OPTS_KEY = "air_contact_marks";
    /** The presence recorder marks the current hour of every online contact this often. */
    static final long TICK_MS = 300_000L;
    private static final long WRITE_DELAY_MS = 2_000L;

    private static final String FIELD_HOURS = "hours";
    private static final String FIELD_VERIFIED_MS = "verified_ms";

    interface Listener
    {
        void onAirContactMarksChanged();
    }

    /** The marks of one contact. Hours ascend and never repeat. */
    static final class Contact
    {
        static final Contact EMPTY = new Contact(Collections.<Long>emptyList(), 0L);

        final List<Long> hours;
        /** When the key was verified, 0 when it was not. */
        final long verifiedMs;

        Contact(final List<Long> hours, final long verifiedMs)
        {
            this.hours = Collections.unmodifiableList(new ArrayList<>(new TreeSet<>(hours)));
            this.verifiedMs = Math.max(0L, verifiedMs);
        }

        boolean isEmpty()
        {
            return hours.isEmpty() && verifiedMs <= 0L;
        }
    }

    private static final Object LOCK = new Object();
    /** Null until read from the database. */
    private static Map<String, Contact> cache = null;
    /** The database the cache was read from: a profile switch replaces it, and the cache goes. */
    private static Object cacheSource = null;
    private static final Map<String, Long> ONLINE_SINCE = new HashMap<>();
    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static ScheduledExecutorService writer = null;
    private static boolean writePending = false;
    private static Handler mainHandler = null;
    private static volatile long lastTickMs = 0L;

    private AirContactMarks()
    {
    }

    // ---- presence ----

    /** From toxcore's friend connection callback. */
    static void onConnectionChanged(final String publicKey, final boolean online)
    {
        if (online)
        {
            noteOnline(publicKey, System.currentTimeMillis());
        }
        else
        {
            noteOffline(publicKey);
        }
    }

    /**
     * A friend who stays online across an hour has no connection change at the turn of the hour, so the
     * service loop marks the current hour of everyone online every {@link #TICK_MS}. Runs on the loop's thread.
     */
    static void tick()
    {
        final long now = System.currentTimeMillis();
        if (now - lastTickMs < TICK_MS || TrifaToxService.orma == null)
        {
            return;
        }
        lastTickMs = now;
        try
        {
            final List<FriendList> friends = TrifaToxService.orma.selectFromFriendList().is_relayNotEq(true).toList();
            for (final FriendList friend : friends)
            {
                if (friend == null || TextUtils.isEmpty(friend.tox_public_key_string))
                {
                    continue;
                }
                if (FriendPresenceHelper.is_friend_online(friend))
                {
                    noteOnline(friend.tox_public_key_string, now);
                }
                else
                {
                    noteOffline(friend.tox_public_key_string);
                }
            }
        }
        catch (Exception e)
        {
            Log.i(TAG, "tick:EE:" + e.getMessage());
        }
    }

    /** Marks the clock hour of {@code nowMs} as an hour the contact was online; notes since when. */
    static void noteOnline(final String publicKey, final long nowMs)
    {
        final String key = hashedKey(publicKey);
        if (key.isEmpty())
        {
            return;
        }
        final long hour = AirConversationModel.hourStartSeconds(nowMs, TimeZone.getDefault());
        boolean changed = false;
        boolean save = false;
        synchronized (LOCK)
        {
            // The marks load first: reading those of a newly opened profile forgets who was online, and
            // the first connection after a start must not forget the contact it reports.
            final Map<String, Contact> current = loadedLocked();
            if (!ONLINE_SINCE.containsKey(key))
            {
                ONLINE_SINCE.put(key, nowMs);
                changed = true;
            }
            if (current != null)
            {
                final Map<String, Contact> next = withHour(current, key, hour, nowMs);
                if (next != null)
                {
                    cache = next;
                    changed = true;
                    save = true;
                }
            }
        }
        if (save)
        {
            scheduleWrite();
        }
        if (changed)
        {
            notifyListeners();
        }
    }

    static void noteOffline(final String publicKey)
    {
        final String key = hashedKey(publicKey);
        final boolean changed;
        synchronized (LOCK)
        {
            changed = ONLINE_SINCE.remove(key) != null;
        }
        if (changed)
        {
            notifyListeners();
        }
    }

    /** The starts of the hours the contact was seen online in the last week, in seconds since 1970. */
    static Set<Long> hours(final String publicKey)
    {
        synchronized (LOCK)
        {
            final Map<String, Contact> current = loadedLocked();
            final Contact contact = current == null ? null : current.get(hashedKey(publicKey));
            return contact == null ? Collections.<Long>emptySet() : new HashSet<>(contact.hours);
        }
    }

    /** When this device saw the contact come online, null while they are offline or not seen yet. */
    static Long onlineSince(final String publicKey)
    {
        synchronized (LOCK)
        {
            return ONLINE_SINCE.get(hashedKey(publicKey));
        }
    }

    // ---- the dossier ----

    /** When the key was verified, 0 when it was not. */
    static long verifiedMs(final String publicKey)
    {
        synchronized (LOCK)
        {
            final Map<String, Contact> current = loadedLocked();
            final Contact contact = current == null ? null : current.get(hashedKey(publicKey));
            return contact == null ? 0L : contact.verifiedMs;
        }
    }

    /** {@code whenMs} 0 takes the mark back. */
    static void setVerified(final String publicKey, final long whenMs)
    {
        final String key = hashedKey(publicKey);
        if (key.isEmpty())
        {
            return;
        }
        synchronized (LOCK)
        {
            final Map<String, Contact> current = loadedLocked();
            if (current == null)
            {
                return;
            }
            cache = withVerified(current, key, whenMs);
        }
        scheduleWrite();
        notifyListeners();
    }

    /** Forgets everything about a contact, when the contact itself is removed. */
    static void forget(final String publicKey)
    {
        final String key = hashedKey(publicKey);
        boolean save = false;
        synchronized (LOCK)
        {
            ONLINE_SINCE.remove(key);
            final Map<String, Contact> current = loadedLocked();
            if (current != null && current.containsKey(key))
            {
                final Map<String, Contact> next = new HashMap<>(current);
                next.remove(key);
                cache = next;
                save = true;
            }
        }
        if (save)
        {
            scheduleWrite();
            notifyListeners();
        }
    }

    static void addListener(final Listener listener)
    {
        if (listener != null)
        {
            LISTENERS.addIfAbsent(listener);
        }
    }

    static void removeListener(final Listener listener)
    {
        LISTENERS.remove(listener);
    }

    // ---- storage ----

    /** The marks of the open profile, read the first time; null while no database is open. */
    private static Map<String, Contact> loadedLocked()
    {
        final Object source = TrifaToxService.orma;
        if (source == null)
        {
            return null;
        }
        if (cache == null || cacheSource != source)
        {
            if (cacheSource != source)
            {
                ONLINE_SINCE.clear();
            }
            Map<String, Contact> read;
            try
            {
                read = pruned(decode(HelperGeneric.get_g_opts(OPTS_KEY)), System.currentTimeMillis());
            }
            catch (Exception e)
            {
                Log.i(TAG, "load:EE:" + e.getMessage());
                read = new HashMap<>();
            }
            cache = read;
            cacheSource = source;
        }
        return cache;
    }

    private static void scheduleWrite()
    {
        final ScheduledExecutorService executor;
        synchronized (LOCK)
        {
            if (writePending)
            {
                return;
            }
            writePending = true;
            if (writer == null)
            {
                writer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(runnable -> {
                    final Thread thread = new Thread(runnable, "air-marks-writer");
                    thread.setDaemon(true);
                    return thread;
                });
            }
            executor = writer;
        }
        executor.schedule(AirContactMarks::writeNow, WRITE_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private static void writeNow()
    {
        final String json;
        final Object source;
        synchronized (LOCK)
        {
            writePending = false;
            if (cache == null || cacheSource == null || cacheSource != TrifaToxService.orma)
            {
                return;
            }
            source = cacheSource;
            json = cache.isEmpty() ? null : encode(cache);
        }
        try
        {
            if (TrifaToxService.orma != source)
            {
                return;
            }
            if (json == null)
            {
                HelperGeneric.del_g_opts(OPTS_KEY);
            }
            else
            {
                HelperGeneric.set_g_opts(OPTS_KEY, json);
            }
        }
        catch (Exception e)
        {
            Log.i(TAG, "write:EE:" + e.getMessage());
        }
    }

    private static void notifyListeners()
    {
        if (LISTENERS.isEmpty())
        {
            return;
        }
        final Handler handler;
        synchronized (LOCK)
        {
            if (mainHandler == null)
            {
                mainHandler = new Handler(Looper.getMainLooper());
            }
            handler = mainHandler;
        }
        handler.post(() -> {
            for (Listener listener : LISTENERS)
            {
                listener.onAirContactMarksChanged();
            }
        });
    }

    // ---- pure rules (unit tested) ----

    /** The table never names a contact: 16 bytes of SHA-256 over the upper-cased key, as on iOS. */
    static String hashedKey(final String publicKey)
    {
        if (publicKey == null || publicKey.trim().isEmpty())
        {
            return "";
        }
        try
        {
            final byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    publicKey.trim().toUpperCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            final StringBuilder hex = new StringBuilder(32);
            for (int i = 0; i < 16; i++)
            {
                hex.append(String.format(Locale.ROOT, "%02x", digest[i] & 0xff));
            }
            return hex.toString();
        }
        catch (Exception e)
        {
            return "";
        }
    }

    /**
     * The marks with {@code hourStart} added for the contact, hours older than a week dropped; null when
     * the hour is already there, so nothing needs writing.
     */
    static Map<String, Contact> withHour(final Map<String, Contact> current, final String key, final long hourStart,
                                         final long nowMs)
    {
        final Contact contact = current.containsKey(key) ? current.get(key) : Contact.EMPTY;
        if (contact.hours.contains(hourStart))
        {
            return null;
        }
        final long oldest = nowMs / 1000L - AirConversationModel.KEEP_SECONDS;
        final List<Long> kept = new ArrayList<>(contact.hours.size() + 1);
        for (Long hour : contact.hours)
        {
            if (hour >= oldest)
            {
                kept.add(hour);
            }
        }
        kept.add(hourStart);
        final Map<String, Contact> next = new HashMap<>(current);
        next.put(key, new Contact(kept, contact.verifiedMs));
        return next;
    }

    static Map<String, Contact> withVerified(final Map<String, Contact> current, final String key, final long whenMs)
    {
        final Contact contact = current.containsKey(key) ? current.get(key) : Contact.EMPTY;
        final Map<String, Contact> next = new HashMap<>(current);
        final Contact changed = new Contact(contact.hours, whenMs);
        if (changed.isEmpty())
        {
            next.remove(key);
        }
        else
        {
            next.put(key, changed);
        }
        return next;
    }

    /** Hours older than a week dropped; contacts left with nothing are dropped too. */
    static Map<String, Contact> pruned(final Map<String, Contact> current, final long nowMs)
    {
        final long oldest = nowMs / 1000L - AirConversationModel.KEEP_SECONDS;
        final Map<String, Contact> next = new HashMap<>();
        for (Map.Entry<String, Contact> entry : current.entrySet())
        {
            final List<Long> kept = new ArrayList<>();
            for (Long hour : entry.getValue().hours)
            {
                if (hour >= oldest)
                {
                    kept.add(hour);
                }
            }
            final Contact contact = new Contact(kept, entry.getValue().verifiedMs);
            if (!contact.isEmpty())
            {
                next.put(entry.getKey(), contact);
            }
        }
        return next;
    }

    static String encode(final Map<String, Contact> contacts)
    {
        final JsonObject root = new JsonObject();
        for (Map.Entry<String, Contact> entry : new TreeMap<>(contacts).entrySet())
        {
            final JsonObject contact = new JsonObject();
            final JsonArray hours = new JsonArray();
            for (Long hour : entry.getValue().hours)
            {
                hours.add(hour);
            }
            contact.add(FIELD_HOURS, hours);
            if (entry.getValue().verifiedMs > 0L)
            {
                contact.addProperty(FIELD_VERIFIED_MS, entry.getValue().verifiedMs);
            }
            root.add(entry.getKey(), contact);
        }
        return root.toString();
    }

    /** Anything unreadable is skipped: a damaged value costs the marks, never the screen. */
    static Map<String, Contact> decode(final String json)
    {
        final Map<String, Contact> out = new HashMap<>();
        if (json == null || json.trim().isEmpty())
        {
            return out;
        }
        try
        {
            final JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject())
            {
                return out;
            }
            for (Map.Entry<String, JsonElement> entry : parsed.getAsJsonObject().entrySet())
            {
                if (!entry.getValue().isJsonObject() || entry.getKey().isEmpty())
                {
                    continue;
                }
                final JsonObject object = entry.getValue().getAsJsonObject();
                final List<Long> hours = new ArrayList<>();
                final JsonElement hoursElement = object.get(FIELD_HOURS);
                if (hoursElement != null && hoursElement.isJsonArray())
                {
                    for (JsonElement hour : hoursElement.getAsJsonArray())
                    {
                        try
                        {
                            hours.add(hour.getAsLong());
                        }
                        catch (Exception ignored)
                        {
                            // one bad number does not cost the rest
                        }
                    }
                }
                long verified = 0L;
                final JsonElement verifiedElement = object.get(FIELD_VERIFIED_MS);
                if (verifiedElement != null && verifiedElement.isJsonPrimitive())
                {
                    try
                    {
                        verified = verifiedElement.getAsLong();
                    }
                    catch (Exception ignored)
                    {
                        verified = 0L;
                    }
                }
                final Contact contact = new Contact(hours, verified);
                if (!contact.isEmpty())
                {
                    out.put(entry.getKey(), contact);
                }
            }
        }
        catch (Exception e)
        {
            return new HashMap<>();
        }
        return out;
    }
}
