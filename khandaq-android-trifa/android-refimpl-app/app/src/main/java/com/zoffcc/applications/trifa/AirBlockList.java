package com.zoffcc.applications.trifa;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * The keys this device blocked (design/DESIGN.md, "Block, on every client"). Tox has no block, so it is
 * local: the other side is not told and the protocol does not change. A request from a listed key is
 * dropped as it lands, the way Decline drops one, and nothing is sent back.
 *
 * <p>The list is one JSON value in the encrypted globals table, next to AirContactMarks and
 * AirRequestStore, and never in the Tox savedata: an exported profile (the .tox file) does not carry it.
 * It holds public keys only, 64 hex characters in upper case, oldest first.
 *
 * <p>Calls may come from any thread: the toxcore request callback reads it, the dossier and the Blocked
 * screen change it. Writes reach the database on one background thread, in order; listeners hear on the
 * main thread.
 */
final class AirBlockList
{
    private static final String TAG = "trifa.AirBlocked";

    static final String OPTS_KEY = "air_blocked_keys";
    /** A guard for the stored value only: nobody blocks this many keys by hand. */
    static final int MAX_KEYS = 1000;

    private static final String FIELD_PUBLIC_KEY = "public_key";
    private static final String FIELD_BLOCKED_MS = "blocked_ms";
    private static final Pattern PUBLIC_KEY = Pattern.compile("[0-9A-F]{64}");

    interface Listener
    {
        void onAirBlockListChanged();
    }

    interface SnapshotCallback
    {
        void onAirBlockListSnapshot(List<Entry> entries);
    }

    /** One blocked key and when it was blocked. */
    static final class Entry
    {
        final String publicKey;
        final long blockedMs;

        Entry(final String publicKey, final long blockedMs)
        {
            this.publicKey = publicKey;
            this.blockedMs = Math.max(0L, blockedMs);
        }
    }

    private static final Object LOCK = new Object();
    /** Oldest first and unmodifiable; null until read from the database. */
    private static List<Entry> cache = null;
    /** The database the cache was read from: a profile switch replaces it, and the cache goes. */
    private static Object cacheSource = null;
    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static ExecutorService writer = null;
    private static Handler mainHandler = null;

    private AirBlockList()
    {
    }

    // ---- state ----

    /** Every blocked key, oldest first. Reads the database the first time; call it off the main thread. */
    static List<Entry> snapshot()
    {
        synchronized (LOCK)
        {
            return loadedLocked();
        }
    }

    static int count()
    {
        return snapshot().size();
    }

    /**
     * The list read on the writer thread, after any write still on its way, and handed back on the main
     * thread: the first read opens the database, which a screen must not wait for.
     */
    static void snapshotAsync(final SnapshotCallback callback)
    {
        writer().execute(() -> {
            final List<Entry> entries = snapshot();
            mainHandler().post(() -> callback.onAirBlockListSnapshot(entries));
        });
    }

    /** Whether requests from this key are dropped. Anything that is not a 64-hex key is never blocked. */
    static boolean isBlocked(final String publicKey)
    {
        return contains(snapshot(), publicKey);
    }

    /**
     * Puts the key on the list; a key already there keeps its place and date. Returns false when the text
     * is not a 64-hex public key (a whole 76-character ID is not taken either).
     */
    static boolean block(final String publicKey, final long nowMs)
    {
        final String key = normalizedKey(publicKey);
        if (key == null)
        {
            Log.i(TAG, "block: not a public key");
            return false;
        }
        final String json;
        synchronized (LOCK)
        {
            final List<Entry> current = loadedLocked();
            final List<Entry> next = withBlocked(current, key, nowMs);
            if (next == current)
            {
                return true;
            }
            cache = next;
            json = encode(next);
        }
        persist(json);
        notifyListeners();
        return true;
    }

    /** Takes the key off the list. The contact does not come back by itself: that takes a new request. */
    static boolean unblock(final String publicKey)
    {
        final String key = normalizedKey(publicKey);
        if (key == null)
        {
            return false;
        }
        final String json;
        synchronized (LOCK)
        {
            final List<Entry> current = loadedLocked();
            final List<Entry> next = without(current, key);
            if (next == current)
            {
                return false;
            }
            cache = next;
            json = next.isEmpty() ? null : encode(next);
        }
        persist(json);
        notifyListeners();
        return true;
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

    private static List<Entry> loadedLocked()
    {
        final Object source = TrifaToxService.orma;
        if (source == null)
        {
            // The database is not open yet (or any more); nothing can be read or kept.
            return cache == null ? Collections.<Entry>emptyList() : cache;
        }
        if (cache == null || cacheSource != source)
        {
            cache = decode(HelperGeneric.get_g_opts(OPTS_KEY));
            cacheSource = source;
        }
        return cache;
    }

    private static ExecutorService writer()
    {
        synchronized (LOCK)
        {
            if (writer == null)
            {
                writer = Executors.newSingleThreadExecutor(runnable -> {
                    final Thread thread = new Thread(runnable, "air-blocked-writer");
                    thread.setDaemon(true);
                    return thread;
                });
            }
            return writer;
        }
    }

    private static Handler mainHandler()
    {
        synchronized (LOCK)
        {
            if (mainHandler == null)
            {
                mainHandler = new Handler(Looper.getMainLooper());
            }
            return mainHandler;
        }
    }

    private static void persist(final String jsonOrNull)
    {
        writer().execute(() -> {
            if (TrifaToxService.orma == null)
            {
                return;
            }
            if (jsonOrNull == null)
            {
                HelperGeneric.del_g_opts(OPTS_KEY);
            }
            else
            {
                HelperGeneric.set_g_opts(OPTS_KEY, jsonOrNull);
            }
        });
    }

    private static void notifyListeners()
    {
        mainHandler().post(() -> {
            for (Listener listener : LISTENERS)
            {
                listener.onAirBlockListChanged();
            }
        });
    }

    // ---- pure list rules (unit tested) ----

    /**
     * The key as the list keeps it: 64 hex characters in upper case, surrounding spaces aside. Anything
     * else, a whole 76-character ID or a shorter string included, is null.
     */
    static String normalizedKey(final String text)
    {
        if (text == null)
        {
            return null;
        }
        final String key = text.trim().toUpperCase(Locale.ROOT);
        return PUBLIC_KEY.matcher(key).matches() ? key : null;
    }

    static boolean contains(final List<Entry> list, final String publicKey)
    {
        final String key = normalizedKey(publicKey);
        if (key == null || list == null)
        {
            return false;
        }
        for (Entry entry : list)
        {
            if (entry.publicKey.equals(key))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * The list with the key at its end, oldest first. A key already listed keeps its place and date, and the
     * same list comes back; past MAX_KEYS the oldest entries go.
     */
    static List<Entry> withBlocked(final List<Entry> current, final String publicKey, final long nowMs)
    {
        final String key = normalizedKey(publicKey);
        if (key == null || contains(current, key))
        {
            return current;
        }
        final List<Entry> next = new ArrayList<>(current.size() + 1);
        next.addAll(current);
        next.add(new Entry(key, nowMs));
        while (next.size() > MAX_KEYS)
        {
            next.remove(0);
        }
        return Collections.unmodifiableList(next);
    }

    /** The list without the key; the same list when it was not there. */
    static List<Entry> without(final List<Entry> current, final String publicKey)
    {
        final String key = normalizedKey(publicKey);
        if (key == null || !contains(current, key))
        {
            return current;
        }
        final List<Entry> next = new ArrayList<>(current.size());
        for (Entry entry : current)
        {
            if (!entry.publicKey.equals(key))
            {
                next.add(entry);
            }
        }
        return Collections.unmodifiableList(next);
    }

    static String encode(final List<Entry> list)
    {
        final JsonArray array = new JsonArray();
        for (Entry entry : list)
        {
            final JsonObject object = new JsonObject();
            object.addProperty(FIELD_PUBLIC_KEY, entry.publicKey);
            object.addProperty(FIELD_BLOCKED_MS, entry.blockedMs);
            array.add(object);
        }
        return array.toString();
    }

    /**
     * The stored list, oldest first as written. Entries that are not 64-hex keys and repeats are skipped,
     * so a damaged value never blocks something it should not; unreadable JSON is an empty list.
     */
    static List<Entry> decode(final String json)
    {
        if (json == null || json.trim().isEmpty())
        {
            return Collections.emptyList();
        }
        try
        {
            final JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonArray())
            {
                return Collections.emptyList();
            }
            List<Entry> list = Collections.emptyList();
            for (JsonElement element : root.getAsJsonArray())
            {
                if (!element.isJsonObject())
                {
                    continue;
                }
                final JsonObject object = element.getAsJsonObject();
                final JsonElement key = object.get(FIELD_PUBLIC_KEY);
                final JsonElement when = object.get(FIELD_BLOCKED_MS);
                if (key == null || !key.isJsonPrimitive())
                {
                    continue;
                }
                long blockedMs = 0L;
                if (when != null && when.isJsonPrimitive() && when.getAsJsonPrimitive().isNumber())
                {
                    blockedMs = when.getAsLong();
                }
                list = withBlocked(list, key.getAsString(), blockedMs);
            }
            return list;
        }
        catch (RuntimeException e)
        {
            // Unreadable: an empty list, which blocks nothing (no log here, so the rule stays a pure function).
            return Collections.emptyList();
        }
    }
}
