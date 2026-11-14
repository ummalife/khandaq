package com.zoffcc.applications.trifa;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Incoming contact requests that wait for an answer. Air shows them as cards above the list.
 *
 * <p>toxcore keeps no requests of its own. Before Air the app showed a dialog and forgot the
 * request, and a request that arrived while the main window was closed was dropped outright. The
 * store keeps every pending request in the encrypted globals table (the one drafts use) as a
 * single JSON value until it is accepted or declined.
 *
 * <p>KHANDAQ (QA live, F2): it also remembers the requests we accepted. toxcore does not say which side
 * asked, and Air reads a contact never seen online as our own request that waits for an answer
 * ("waiting for reply", "request sent"); a contact added by accepting their request is never seen
 * either until the first link, so it looked the same, also after a restart. The keys we accepted
 * are kept as a second JSON value of the same table, with the time each request reached us.
 *
 * <p>Reads and writes may come from any thread: the toxcore callback adds, the UI answers, the
 * Air repository reads. Writes reach the database on one background thread, in order. Listeners
 * are told on the main thread.
 */
final class AirRequestStore
{
    private static final String TAG = "trifa.AirRequests";

    static final String OPTS_KEY = "air_incoming_requests";
    static final int MAX_REQUESTS = 50;
    static final String ACCEPTED_OPTS_KEY = "air_accepted_requests";
    /** As many as the block list keeps: one per contact ever accepted on this device, at most. */
    static final int MAX_ACCEPTED = 1000;
    /** {@link #acceptedRequestMs} of a key whose request we did not accept. */
    static final long NOT_ACCEPTED = -1L;
    /** TOX_MAX_FRIEND_REQUEST_LENGTH: toxcore never delivers more, the cap only guards the store. */
    static final int MAX_MESSAGE_LENGTH = 1016;

    private static final String FIELD_PUBLIC_KEY = "public_key";
    private static final String FIELD_MESSAGE = "message";
    private static final String FIELD_RECEIVED_MS = "received_ms";
    private static final Pattern PUBLIC_KEY = Pattern.compile("[0-9A-F]{64}");

    interface Listener
    {
        void onAirRequestsChanged();
    }

    /** One pending request. The key is the sender's 64-hex public key, upper case. */
    static final class Pending
    {
        final String publicKey;
        final String message;
        final long receivedMs;

        private Pending(String publicKey, String message, long receivedMs)
        {
            this.publicKey = publicKey;
            this.message = message;
            this.receivedMs = receivedMs;
        }

        /** Returns null when the key is not a 64-hex public key. */
        static Pending of(String publicKey, String message, long receivedMs)
        {
            if (publicKey == null)
            {
                return null;
            }
            final String key = publicKey.trim().toUpperCase(Locale.ROOT);
            if (!PUBLIC_KEY.matcher(key).matches())
            {
                return null;
            }
            String text = message == null ? "" : message.trim();
            if (text.length() > MAX_MESSAGE_LENGTH)
            {
                int end = MAX_MESSAGE_LENGTH;
                if (Character.isHighSurrogate(text.charAt(end - 1)))
                {
                    end--;
                }
                text = text.substring(0, end);
            }
            return new Pending(key, text, Math.max(0L, receivedMs));
        }

        @Override
        public boolean equals(Object other)
        {
            if (this == other)
            {
                return true;
            }
            if (!(other instanceof Pending))
            {
                return false;
            }
            final Pending that = (Pending) other;
            return receivedMs == that.receivedMs && publicKey.equals(that.publicKey) && message.equals(that.message);
        }

        @Override
        public int hashCode()
        {
            int result = publicKey.hashCode();
            result = 31 * result + message.hashCode();
            result = 31 * result + Long.hashCode(receivedMs);
            return result;
        }

        @Override
        public String toString()
        {
            return "Pending{" + publicKey.substring(0, 8) + ", " + receivedMs + "}";
        }
    }

    private static final Comparator<Pending> NEWEST_FIRST = new Comparator<Pending>()
    {
        @Override
        public int compare(Pending a, Pending b)
        {
            final int byTime = Long.compare(b.receivedMs, a.receivedMs);
            return byTime != 0 ? byTime : a.publicKey.compareTo(b.publicKey);
        }
    };

    private static final Object LOCK = new Object();
    /** Newest first and unmodifiable; null until read from the database. */
    private static List<Pending> cache = null;
    /** The database the cache was read from: a profile switch replaces it, and the cache goes. */
    private static Object cacheSource = null;
    /** The requests we accepted, as Pending without their words; newest first, null until read. */
    private static List<Pending> acceptedCache = null;
    private static Object acceptedSource = null;
    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static ExecutorService writer = null;
    private static Handler mainHandler = null;

    private AirRequestStore()
    {
    }

    // ---- state ----

    /** Pending requests, newest first. Reads the database the first time; call it off the main thread. */
    static List<Pending> snapshot()
    {
        synchronized (LOCK)
        {
            return loadedLocked();
        }
    }

    /** Keeps a request; a second request from the same key replaces the first. */
    static void add(String publicKey, String message, long receivedMs)
    {
        final Pending incoming = Pending.of(publicKey, message, receivedMs);
        if (incoming == null)
        {
            Log.i(TAG, "add: ignored a request without a valid public key");
            return;
        }
        final String json;
        synchronized (LOCK)
        {
            cache = withAdded(loadedLocked(), incoming);
            json = encode(cache);
        }
        persist(json);
        notifyListeners();
    }

    /** Removes the request from this key and returns it, for an undo; null when there was none. */
    static Pending remove(String publicKey)
    {
        final Pending probe = Pending.of(publicKey, "", 0);
        if (probe == null)
        {
            return null;
        }
        final Pending removed;
        final String json;
        synchronized (LOCK)
        {
            final List<Pending> current = loadedLocked();
            removed = find(current, probe.publicKey);
            if (removed == null)
            {
                return null;
            }
            cache = without(current, Collections.singleton(probe.publicKey));
            json = cache.isEmpty() ? null : encode(cache);
        }
        persist(json);
        notifyListeners();
        return removed;
    }

    /** Puts back a request taken by {@link #remove}, with its original time. */
    static void restore(Pending pending)
    {
        if (pending != null)
        {
            add(pending.publicKey, pending.message, pending.receivedMs);
        }
    }

    /** Drops requests from keys that are already contacts, such as one accepted on another path. */
    static void forget(Collection<String> knownPublicKeys)
    {
        if (knownPublicKeys == null || knownPublicKeys.isEmpty())
        {
            return;
        }
        final String json;
        synchronized (LOCK)
        {
            final List<Pending> current = loadedLocked();
            final List<Pending> kept = without(current, knownPublicKeys);
            if (kept.size() == current.size())
            {
                return;
            }
            cache = kept;
            json = kept.isEmpty() ? null : encode(kept);
        }
        persist(json);
        notifyListeners();
    }

    /**
     * Notes that we accepted the request from this key. Call it before the contact is added, so that no
     * build of the list sees the new contact without the mark, and before {@link #remove}: the mark takes
     * the time the waiting request reached us (the person was online then), 0 when none waits.
     */
    static void markAccepted(String publicKey)
    {
        final String json;
        synchronized (LOCK)
        {
            final Pending mark = acceptedMark(loadedLocked(), publicKey);
            if (mark == null)
            {
                return;
            }
            acceptedCache = withAdded(acceptedLoadedLocked(), mark, MAX_ACCEPTED);
            json = encode(acceptedCache);
        }
        persist(ACCEPTED_OPTS_KEY, json);
    }

    /**
     * When the request we accepted from this key reached us, 0 when that is not known, or
     * {@link #NOT_ACCEPTED}. Reads the database the first time; call it off the main thread.
     */
    static long acceptedRequestMs(String publicKey)
    {
        synchronized (LOCK)
        {
            return acceptedMs(acceptedLoadedLocked(), publicKey);
        }
    }

    /** The key is no longer one whose request we accepted: the contact is removed, or we asked them ourselves. */
    static void forgetAccepted(String publicKey)
    {
        final Pending probe = Pending.of(publicKey, "", 0);
        if (probe == null)
        {
            return;
        }
        final String json;
        synchronized (LOCK)
        {
            final List<Pending> current = acceptedLoadedLocked();
            if (find(current, probe.publicKey) == null)
            {
                return;
            }
            acceptedCache = without(current, Collections.singleton(probe.publicKey));
            json = acceptedCache.isEmpty() ? null : encode(acceptedCache);
        }
        persist(ACCEPTED_OPTS_KEY, json);
    }

    static void addListener(Listener listener)
    {
        if (listener != null)
        {
            LISTENERS.addIfAbsent(listener);
        }
    }

    static void removeListener(Listener listener)
    {
        LISTENERS.remove(listener);
    }

    private static List<Pending> loadedLocked()
    {
        final Object source = TrifaToxService.orma;
        if (source == null)
        {
            // The database is not open yet (or any more); nothing can be read or kept.
            return cache == null ? Collections.<Pending>emptyList() : cache;
        }
        if (cache == null || cacheSource != source)
        {
            cache = decode(HelperGeneric.get_g_opts(OPTS_KEY));
            cacheSource = source;
        }
        return cache;
    }

    private static List<Pending> acceptedLoadedLocked()
    {
        final Object source = TrifaToxService.orma;
        if (source == null)
        {
            return acceptedCache == null ? Collections.<Pending>emptyList() : acceptedCache;
        }
        if (acceptedCache == null || acceptedSource != source)
        {
            acceptedCache = decode(HelperGeneric.get_g_opts(ACCEPTED_OPTS_KEY), MAX_ACCEPTED);
            acceptedSource = source;
        }
        return acceptedCache;
    }

    private static void persist(final String jsonOrNull)
    {
        persist(OPTS_KEY, jsonOrNull);
    }

    private static void persist(final String optsKey, final String jsonOrNull)
    {
        final ExecutorService executor;
        synchronized (LOCK)
        {
            if (writer == null)
            {
                writer = Executors.newSingleThreadExecutor(runnable -> {
                    final Thread thread = new Thread(runnable, "air-requests-writer");
                    thread.setDaemon(true);
                    return thread;
                });
            }
            executor = writer;
        }
        executor.execute(() -> {
            if (TrifaToxService.orma == null)
            {
                return;
            }
            if (jsonOrNull == null)
            {
                HelperGeneric.del_g_opts(optsKey);
            }
            else
            {
                HelperGeneric.set_g_opts(optsKey, jsonOrNull);
            }
        });
    }

    private static void notifyListeners()
    {
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
                listener.onAirRequestsChanged();
            }
        });
    }

    // ---- pure list rules (unit tested) ----

    /** The list with the request added: one entry per key, newest first, at most MAX_REQUESTS. */
    static List<Pending> withAdded(List<Pending> current, Pending incoming)
    {
        return withAdded(current, incoming, MAX_REQUESTS);
    }

    /** As above, at most {@code max} entries. */
    static List<Pending> withAdded(List<Pending> current, Pending incoming, int max)
    {
        final List<Pending> next = new ArrayList<>(current.size() + 1);
        Pending merged = incoming;
        for (Pending existing : current)
        {
            if (existing.publicKey.equals(incoming.publicKey))
            {
                // A repeated request keeps the newer time; an empty repeat keeps the earlier words.
                final String message = incoming.message.isEmpty() ? existing.message : incoming.message;
                merged = new Pending(incoming.publicKey, message, Math.max(existing.receivedMs, incoming.receivedMs));
            }
            else
            {
                next.add(existing);
            }
        }
        next.add(merged);
        Collections.sort(next, NEWEST_FIRST);
        while (next.size() > max)
        {
            next.remove(next.size() - 1);
        }
        return Collections.unmodifiableList(next);
    }

    /**
     * The mark of a request we accept from this key: the time the waiting request reached us, never its
     * words; 0 when none waits. Null when the key is not a 64-hex public key.
     */
    static Pending acceptedMark(List<Pending> waiting, String publicKey)
    {
        final Pending probe = Pending.of(publicKey, "", 0);
        if (probe == null)
        {
            return null;
        }
        final Pending request = find(waiting, probe.publicKey);
        return request == null ? probe : Pending.of(probe.publicKey, "", request.receivedMs);
    }

    /** When the accepted request from this key reached us, 0 when not known, or NOT_ACCEPTED. */
    static long acceptedMs(List<Pending> accepted, String publicKey)
    {
        final Pending probe = Pending.of(publicKey, "", 0);
        final Pending mark = probe == null ? null : find(accepted, probe.publicKey);
        return mark == null ? NOT_ACCEPTED : mark.receivedMs;
    }

    static List<Pending> without(List<Pending> current, Collection<String> publicKeys)
    {
        final List<Pending> next = new ArrayList<>(current.size());
        for (Pending existing : current)
        {
            if (!containsKey(publicKeys, existing.publicKey))
            {
                next.add(existing);
            }
        }
        return Collections.unmodifiableList(next);
    }

    static String encode(List<Pending> requests)
    {
        final JsonArray array = new JsonArray();
        for (Pending pending : requests)
        {
            final JsonObject object = new JsonObject();
            object.addProperty(FIELD_PUBLIC_KEY, pending.publicKey);
            object.addProperty(FIELD_MESSAGE, pending.message);
            object.addProperty(FIELD_RECEIVED_MS, pending.receivedMs);
            array.add(object);
        }
        return array.toString();
    }

    /** Reads what {@link #encode} wrote. Damaged entries are skipped, never fatal. */
    static List<Pending> decode(String json)
    {
        return decode(json, MAX_REQUESTS);
    }

    /** As above, keeping at most {@code max} entries. */
    static List<Pending> decode(String json, int max)
    {
        List<Pending> result = Collections.emptyList();
        if (json == null || json.trim().isEmpty())
        {
            return result;
        }
        final JsonElement root;
        try
        {
            root = JsonParser.parseString(json);
        }
        catch (RuntimeException e)
        {
            return result;
        }
        if (!root.isJsonArray())
        {
            return result;
        }
        for (JsonElement element : root.getAsJsonArray())
        {
            if (!element.isJsonObject())
            {
                continue;
            }
            final JsonObject object = element.getAsJsonObject();
            final Pending pending = Pending.of(stringField(object, FIELD_PUBLIC_KEY), stringField(object, FIELD_MESSAGE),
                                               longField(object, FIELD_RECEIVED_MS));
            if (pending != null)
            {
                result = withAdded(result, pending, max);
            }
        }
        return result;
    }

    private static Pending find(List<Pending> requests, String publicKey)
    {
        for (Pending pending : requests)
        {
            if (pending.publicKey.equals(publicKey))
            {
                return pending;
            }
        }
        return null;
    }

    private static boolean containsKey(Collection<String> publicKeys, String publicKey)
    {
        for (String key : publicKeys)
        {
            if (key != null && key.equalsIgnoreCase(publicKey))
            {
                return true;
            }
        }
        return false;
    }

    private static String stringField(JsonObject object, String name)
    {
        final JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
        {
            return null;
        }
        return value.getAsString();
    }

    private static long longField(JsonObject object, String name)
    {
        final JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
        {
            return 0L;
        }
        try
        {
            return value.getAsLong();
        }
        catch (NumberFormatException e)
        {
            return 0L;
        }
    }
}
