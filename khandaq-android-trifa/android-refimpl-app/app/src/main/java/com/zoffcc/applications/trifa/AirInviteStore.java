package com.zoffcc.applications.trifa;

import android.os.Handler;
import android.os.Looper;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * KHANDAQ (redesign, E12): invitations to groups that wait for an answer, as AirGroupInvites on iOS.
 * toxcore hands an invitation over once and joins through the contact who sent it, so the invitation
 * is kept here until Join or Decline, and goes when that contact is removed. Nothing is joined without
 * consent: before, the app took every invitation as it came. An invitation that answers our own join by
 * ID (HelperGroup's friend-assisted join) is still taken at once, since asking for it was the consent.
 *
 * <p>Kept like the requests (AirRequestStore): one JSON value in the encrypted globals table of the
 * profile, written in order on one background thread; listeners hear on the main thread.
 */
final class AirInviteStore
{
    static final String OPTS_KEY = "air_group_invites";
    static final int MAX_INVITES = 50;
    /** A group name is at most TOX_GROUP_MAX_GROUP_NAME_LENGTH (48) bytes; the cap only guards the store. */
    static final int MAX_NAME_LENGTH = 48;
    /** The group's ID: the first 32 bytes of an NGC invite, bytes 3 to 35 of a conference cookie. */
    static final int GROUP_ID_HEX_LENGTH = 64;
    private static final int CONFERENCE_ID_OFFSET_HEX = 6;

    private static final String FIELD_ID = "id";
    private static final String FIELD_KIND = "kind";
    private static final String FIELD_INVITER = "inviter_key";
    private static final String FIELD_NAME = "group_name";
    private static final String FIELD_DATA = "data";
    private static final String FIELD_CONFERENCE_TYPE = "conference_type";
    private static final String FIELD_RECEIVED_MS = "received_ms";
    private static final Pattern PUBLIC_KEY = Pattern.compile("[0-9A-F]{64}");
    private static final Pattern HEX = Pattern.compile("([0-9A-F]{2})+");

    interface Listener
    {
        void onAirInvitesChanged();
    }

    enum Kind
    {
        /** A group of today (NGC): joined with tox_group_invite_accept. */
        GROUP("group"),
        /** An old conference: joined with tox_conference_join. */
        CONFERENCE("conference");

        final String storedName;

        Kind(final String storedName)
        {
            this.storedName = storedName;
        }

        static Kind fromStored(final String name)
        {
            for (Kind kind : values())
            {
                if (kind.storedName.equals(name))
                {
                    return kind;
                }
            }
            return null;
        }
    }

    /** One invitation, as toxcore handed it over. */
    static final class Invite
    {
        final String id;
        final Kind kind;
        /** The contact who sent it, 64 hex in upper case; their friend number is looked up at Join. */
        final String inviterKey;
        final String groupName;
        /** toxcore's invite data (a conference's cookie), in upper-case hex. */
        final String data;
        /** TOX_CONFERENCE_TYPE of a conference; 0 for a group. */
        final int conferenceType;
        final long receivedMs;

        private Invite(final String id, final Kind kind, final String inviterKey, final String groupName,
                       final String data, final int conferenceType, final long receivedMs)
        {
            this.id = id;
            this.kind = kind;
            this.inviterKey = inviterKey;
            this.groupName = groupName;
            this.data = data;
            this.conferenceType = conferenceType;
            this.receivedMs = receivedMs;
        }

        /** Null when a part is missing or damaged: no key, data too short for the group's ID. */
        static Invite of(final String id, final Kind kind, final String inviterKey, final String groupName,
                         final String data, final int conferenceType, final long receivedMs)
        {
            if (id == null || id.trim().isEmpty() || kind == null || inviterKey == null || data == null)
            {
                return null;
            }
            final String key = inviterKey.trim().toUpperCase(Locale.ROOT);
            final String hex = data.trim().toUpperCase(Locale.ROOT);
            if (!PUBLIC_KEY.matcher(key).matches() || !HEX.matcher(hex).matches()
                || hex.length() < groupIdOffset(kind) + GROUP_ID_HEX_LENGTH)
            {
                return null;
            }
            return new Invite(id.trim(), kind, key, cleanName(groupName), hex,
                              kind == Kind.CONFERENCE ? conferenceType : 0, Math.max(0L, receivedMs));
        }

        /** The ID of the group invited to, upper-case hex: one card per group and inviter. */
        String groupId()
        {
            final int from = groupIdOffset(kind);
            return data.substring(from, from + GROUP_ID_HEX_LENGTH);
        }

        byte[] dataBytes()
        {
            final byte[] bytes = new byte[data.length() / 2];
            for (int i = 0; i < bytes.length; i++)
            {
                bytes[i] = (byte) Integer.parseInt(data.substring(2 * i, 2 * i + 2), 16);
            }
            return bytes;
        }

        @Override
        public boolean equals(final Object other)
        {
            if (this == other)
            {
                return true;
            }
            if (!(other instanceof Invite))
            {
                return false;
            }
            final Invite that = (Invite) other;
            return conferenceType == that.conferenceType && receivedMs == that.receivedMs && id.equals(that.id)
                   && kind == that.kind && inviterKey.equals(that.inviterKey) && groupName.equals(that.groupName)
                   && data.equals(that.data);
        }

        @Override
        public int hashCode()
        {
            int result = id.hashCode();
            result = 31 * result + data.hashCode();
            result = 31 * result + Long.hashCode(receivedMs);
            return result;
        }

        @Override
        public String toString()
        {
            return "Invite{" + kind.storedName + ", " + inviterKey.substring(0, 8) + ", " + receivedMs + "}";
        }
    }

    private static final Comparator<Invite> NEWEST_FIRST = new Comparator<Invite>()
    {
        @Override
        public int compare(final Invite a, final Invite b)
        {
            final int byTime = Long.compare(b.receivedMs, a.receivedMs);
            return byTime != 0 ? byTime : a.id.compareTo(b.id);
        }
    };

    private static final Object LOCK = new Object();
    /** Newest first and unmodifiable; null until read from the database. */
    private static List<Invite> cache = null;
    /** The database the cache was read from: a profile switch replaces it, and the cache goes. */
    private static Object cacheSource = null;
    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static ExecutorService writer = null;
    private static Handler mainHandler = null;

    private AirInviteStore()
    {
    }

    // ---- state ----

    /** Waiting invitations, newest first. Reads the database the first time; call it off the main thread. */
    static List<Invite> snapshot()
    {
        synchronized (LOCK)
        {
            return loadedLocked();
        }
    }

    static Invite find(final String id)
    {
        synchronized (LOCK)
        {
            for (Invite invite : loadedLocked())
            {
                if (invite.id.equals(id))
                {
                    return invite;
                }
            }
            return null;
        }
    }

    /** Keeps an invitation; a newer one from the same contact to the same group takes its place. */
    static Invite add(final Kind kind, final String inviterKey, final String groupName, final byte[] data,
                      final int conferenceType, final long receivedMs)
    {
        final Invite incoming = Invite.of(UUID.randomUUID().toString(), kind, inviterKey, groupName, hex(data),
                                          conferenceType, receivedMs);
        if (incoming == null)
        {
            return null;
        }
        final String json;
        synchronized (LOCK)
        {
            cache = withAdded(loadedLocked(), incoming);
            json = encode(cache);
        }
        persist(json);
        notifyListeners();
        return incoming;
    }

    /** Removes the invitation and returns it; null when it was not there. */
    static Invite remove(final String id)
    {
        final Invite removed;
        final String json;
        synchronized (LOCK)
        {
            final List<Invite> current = loadedLocked();
            removed = findIn(current, id);
            if (removed == null)
            {
                return null;
            }
            cache = without(current, id, null);
            json = cache.isEmpty() ? null : encode(cache);
        }
        persist(json);
        notifyListeners();
        return removed;
    }

    /** A contact who is gone takes their invitations along: toxcore joins only through them. */
    static void removeFrom(final String inviterKey)
    {
        if (inviterKey == null)
        {
            return;
        }
        final String json;
        synchronized (LOCK)
        {
            final List<Invite> current = loadedLocked();
            final List<Invite> kept = without(current, null, inviterKey);
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
     * Invitations to groups we are in by now (joined from another card, by ID or on another device) are
     * answered: the cards go. {@code groupIds} are NGC group IDs in any case; conferences are not touched.
     */
    static void forgetGroups(final Set<String> groupIds)
    {
        if (groupIds == null || groupIds.isEmpty())
        {
            return;
        }
        final String json;
        synchronized (LOCK)
        {
            final List<Invite> current = loadedLocked();
            final List<Invite> kept = withoutGroups(current, groupIds);
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

    private static List<Invite> loadedLocked()
    {
        final Object source = TrifaToxService.orma;
        if (source == null)
        {
            return cache == null ? Collections.<Invite>emptyList() : cache;
        }
        if (cache == null || cacheSource != source)
        {
            cache = decode(HelperGeneric.get_g_opts(OPTS_KEY));
            cacheSource = source;
        }
        return cache;
    }

    private static void persist(final String jsonOrNull)
    {
        final ExecutorService executor;
        synchronized (LOCK)
        {
            if (writer == null)
            {
                writer = Executors.newSingleThreadExecutor(runnable -> {
                    final Thread thread = new Thread(runnable, "air-invites-writer");
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
                listener.onAirInvitesChanged();
            }
        });
    }

    // ---- pure list rules (unit tested) ----

    static int groupIdOffset(final Kind kind)
    {
        return kind == Kind.CONFERENCE ? CONFERENCE_ID_OFFSET_HEX : 0;
    }

    /**
     * The name a group joined from an invitation goes by until toxcore has its own: the invitation's, but never
     * over a name the group already has. Empty when there is nothing to set.
     */
    static String placeholderName(final String storedName, final String invitationName)
    {
        if (storedName != null && !storedName.trim().isEmpty())
        {
            return "";
        }
        return cleanName(invitationName);
    }

    /** One line, the ends trimmed, at most MAX_NAME_LENGTH characters. */
    static String cleanName(final String name)
    {
        if (name == null)
        {
            return "";
        }
        String text = name.replace('\n', ' ').replace('\r', ' ').trim();
        if ("(null)".equals(text))
        {
            return "";
        }
        if (text.length() > MAX_NAME_LENGTH)
        {
            int end = MAX_NAME_LENGTH;
            if (Character.isHighSurrogate(text.charAt(end - 1)))
            {
                end--;
            }
            text = text.substring(0, end).trim();
        }
        return text;
    }

    static String hex(final byte[] data)
    {
        if (data == null)
        {
            return null;
        }
        final StringBuilder out = new StringBuilder(data.length * 2);
        for (byte b : data)
        {
            out.append(String.format(Locale.ROOT, "%02X", b & 0xFF));
        }
        return out.toString();
    }

    /**
     * The list with the invitation added, newest first, at most MAX_INVITES: an earlier one from the same
     * contact to the same group goes, so a repeated invitation is one card.
     */
    static List<Invite> withAdded(final List<Invite> current, final Invite incoming)
    {
        final List<Invite> next = new ArrayList<>(current.size() + 1);
        for (Invite existing : current)
        {
            final boolean same = existing.id.equals(incoming.id)
                                 || (existing.kind == incoming.kind && existing.inviterKey.equals(incoming.inviterKey)
                                     && existing.groupId().equals(incoming.groupId()));
            if (!same)
            {
                next.add(existing);
            }
        }
        next.add(incoming);
        Collections.sort(next, NEWEST_FIRST);
        while (next.size() > MAX_INVITES)
        {
            next.remove(next.size() - 1);
        }
        return Collections.unmodifiableList(next);
    }

    /** The list without the invitation with this ID and without those from this contact (either may be null). */
    static List<Invite> without(final List<Invite> current, final String id, final String inviterKey)
    {
        final List<Invite> next = new ArrayList<>(current.size());
        for (Invite existing : current)
        {
            final boolean byId = id != null && existing.id.equals(id);
            final boolean byInviter = inviterKey != null && existing.inviterKey.equalsIgnoreCase(inviterKey.trim());
            if (!byId && !byInviter)
            {
                next.add(existing);
            }
        }
        return Collections.unmodifiableList(next);
    }

    /** The list without the invitations to these NGC groups (IDs in any case). */
    static List<Invite> withoutGroups(final List<Invite> current, final Set<String> groupIds)
    {
        final Set<String> ids = new HashSet<>();
        for (final String groupId : groupIds)
        {
            if (groupId != null)
            {
                ids.add(groupId.trim().toUpperCase(Locale.ROOT));
            }
        }
        final List<Invite> next = new ArrayList<>(current.size());
        for (final Invite existing : current)
        {
            if (existing.kind != Kind.GROUP || !ids.contains(existing.groupId()))
            {
                next.add(existing);
            }
        }
        return Collections.unmodifiableList(next);
    }

    static String encode(final List<Invite> invites)
    {
        final JsonArray array = new JsonArray();
        for (Invite invite : invites)
        {
            final JsonObject object = new JsonObject();
            object.addProperty(FIELD_ID, invite.id);
            object.addProperty(FIELD_KIND, invite.kind.storedName);
            object.addProperty(FIELD_INVITER, invite.inviterKey);
            object.addProperty(FIELD_NAME, invite.groupName);
            object.addProperty(FIELD_DATA, invite.data);
            object.addProperty(FIELD_CONFERENCE_TYPE, invite.conferenceType);
            object.addProperty(FIELD_RECEIVED_MS, invite.receivedMs);
            array.add(object);
        }
        return array.toString();
    }

    /** Reads what {@link #encode} wrote. Damaged entries are skipped, never fatal. */
    static List<Invite> decode(final String json)
    {
        List<Invite> result = Collections.emptyList();
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
            final Invite invite = Invite.of(stringField(object, FIELD_ID),
                                            Kind.fromStored(stringField(object, FIELD_KIND)),
                                            stringField(object, FIELD_INVITER), stringField(object, FIELD_NAME),
                                            stringField(object, FIELD_DATA), (int) longField(object, FIELD_CONFERENCE_TYPE),
                                            longField(object, FIELD_RECEIVED_MS));
            if (invite != null)
            {
                result = withAdded(result, invite);
            }
        }
        return result;
    }

    private static Invite findIn(final List<Invite> invites, final String id)
    {
        for (Invite invite : invites)
        {
            if (invite.id.equals(id))
            {
                return invite;
            }
        }
        return null;
    }

    private static String stringField(final JsonObject object, final String name)
    {
        final JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
        {
            return null;
        }
        return value.getAsString();
    }

    private static long longField(final JsonObject object, final String name)
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
