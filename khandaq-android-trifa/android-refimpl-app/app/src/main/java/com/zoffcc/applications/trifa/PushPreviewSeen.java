package com.zoffcc.applications.trifa;

import android.content.Context;
import android.content.SharedPreferences;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * KHANDAQ (0.2.64): one alert per event, whether a push preview or Tox tells of it first (docs/PUSH_PREVIEWS.md,
 * "Recipient": the event keys shown from pushes are remembered for 30 minutes, and the Tox path does not alert
 * again for one of them).
 *
 * <p>An event is known by marks. Its event key h ("t:", "f:" or "r:") gives the exact mark, an event mark. A text or a
 * file that went without one (a text written to a friend who was offline gets its msgV3 hash only when it leaves) is
 * known by what it says instead: its sender and its first 140 characters, or its kind and name. So is a group text
 * that had to wait (toxcore gives it its id only when it goes), its sender being its key in the group. A preview with
 * an event key is known by that alone, so that two messages saying the same ("ok", two photos) are two events. Event
 * marks are kept here for 30 minutes and on disk, as hashes, since the push handler may run in a process that is
 * gone before Tox brings the message. The marks of what a text says stay off the disk: the push handler puts them on
 * the notification it shows (HelperMsgNotification) and keeps them in memory for 30 minutes, for when that
 * notification was swiped away before the message came, and the marks of what came over Tox are held in memory for
 * two minutes, the time a late push takes. A match uses one up, so that the next message saying the same alerts.
 */
final class PushPreviewSeen
{
    static final String PREFS_SEEN = "khandaq_push_preview_seen";
    /** How long an event key holds back a second alert. */
    static final long REMEMBER_MS = 30L * 60L * 1000L;
    /** How long a text or a file that came over Tox holds back a push saying the same. */
    static final long ARRIVAL_MS = 2L * 60L * 1000L;
    static final int MAX_MARKS = 256;

    /** The process-wide memory the app uses. */
    static final PushPreviewSeen SHARED = new PushPreviewSeen();

    private static final String EVENT = "e";
    private static final String TEXT = "t";
    private static final String FILE = "f";

    /** Event mark -> when it was shown, in ms; what the disk holds. */
    private final Map<String, Long> events = new HashMap<>();
    /** Mark of what came over Tox -> when it came, in ms; memory only. */
    private final Map<String, Long> arrivals = new HashMap<>();
    /** Mark of what a push showed of a text or a file without an event key -> when, in ms; memory only. */
    private final Map<String, Long> pushedContent = new HashMap<>();
    private boolean loaded;

    /** The marks of one event; made by the static helpers below. */
    static final class Event
    {
        final List<String> marks;
        /** A reaction: with message previews off its notification says "New reaction". */
        final boolean reaction;

        Event(final List<String> marks, final boolean reaction)
        {
            this.marks = Collections.unmodifiableList(marks);
            this.reaction = reaction;
        }
    }

    // ---- marks ----

    static boolean isEventMark(final String mark)
    {
        return mark != null && mark.startsWith(EVENT);
    }

    static String eventMark(final String eventKey)
    {
        return (eventKey == null || eventKey.isEmpty()) ? null : EVENT + digest(eventKey);
    }

    static String textMark(final String senderKey, final String text)
    {
        if (senderKey == null || text == null)
        {
            return null;
        }
        final String said = PushPreviewText.oneLine(text);
        return said.isEmpty() ? null : TEXT + digest(senderKey.toUpperCase(Locale.ROOT) + "\n" + said);
    }

    static String fileMark(final String senderKey, final String kind, final String fileName)
    {
        if (senderKey == null || kind == null)
        {
            return null;
        }
        // The name tells two files apart only for a plain file: a preview names no voice note, video or photo.
        final String name = (PushPreviewPayload.KIND_FILE.equals(kind) && fileName != null) ? fileName.trim() : "";
        return FILE + digest(senderKey.toUpperCase(Locale.ROOT) + "\n" + kind + "\n" + name);
    }

    /** The event a preview tells of, as the push handler knows it. */
    static Event forPreview(final String senderKey, final PushPreviewPayload p)
    {
        final List<String> list = new ArrayList<>(2);
        add(list, eventMark(p.eventKey));
        // What it says only for a preview without an event key: with one, the same words are another event.
        if (list.isEmpty() && !p.isGroupEvent())
        {
            if (PushPreviewPayload.KIND_TEXT.equals(p.kind))
            {
                add(list, textMark(senderKey, p.body));
            }
            else if (PushPreviewPayload.isFileKind(p.kind))
            {
                add(list, fileMark(senderKey, p.kind, p.body));
            }
        }
        // KHANDAQ (0.2.69, group message wakes): a group text that had to wait went without one too. It is known by
        // its sender's key in the group (gp) and what it says, as forGroupText knows it when it comes.
        else if (list.isEmpty() && PushPreviewPayload.KIND_TEXT.equals(p.kind) && (p.groupPeerKey != null))
        {
            add(list, textMark(p.groupPeerKey, p.body));
        }
        return new Event(list, PushPreviewPayload.KIND_REACTION.equals(p.kind));
    }

    /**
     * A 1:1 text that came over Tox: its first 140 characters as a sender cuts them, of the text as shown and of the
     * text as it came (with a reply's header), since a sender may have put either into its preview.
     */
    static Event forText(final String senderKey, final String msgV3Hash, final String shownText,
                         final String rawText)
    {
        final List<String> list = new ArrayList<>(3);
        add(list, eventMark(PushPreviewPayload.textEventKey(msgV3Hash)));
        add(list, textMark(senderKey,
                           PushPreviewPayload.firstCharacters(shownText, PushPreviewPayload.TEXT_CHARACTERS)));
        add(list, textMark(senderKey,
                           PushPreviewPayload.firstCharacters(rawText, PushPreviewPayload.TEXT_CHARACTERS)));
        return new Event(list, false);
    }

    /** A 1:1 file that came over Tox, of the kind its sender gave it (PushPreviewPayload.fileKind). */
    static Event forFile(final String senderKey, final String fileIdHex, final String kind, final String fileName)
    {
        final List<String> list = new ArrayList<>(2);
        add(list, eventMark(PushPreviewPayload.fileEventKey(fileIdHex)));
        add(list, fileMark(senderKey, kind, fileName));
        return new Event(list, false);
    }

    /**
     * KHANDAQ (0.2.69, group message wakes): a group text that came over Tox, by toxcore's message id and its sender's
     * key in the group, as a group preview with an event key is known (forPreview).
     */
    static Event forGroupText(final long messageId, final String senderPeerKey)
    {
        return forGroupText(messageId, senderPeerKey, null, null);
    }

    /**
     * As above, and by what it says from that key: a text that had to wait went to the push without an event key,
     * known by its sender's key in the group and its first 140 characters (forPreview). Those of the text as the chat
     * shows it and as it came, since a sender may have put either into its preview.
     */
    static Event forGroupText(final long messageId, final String senderPeerKey, final String shownText,
                              final String rawText)
    {
        final List<String> list = new ArrayList<>(3);
        add(list, eventMark(PushPreviewPayload.groupTextEventKey(messageId, senderPeerKey)));
        add(list, textMark(senderPeerKey,
                           PushPreviewPayload.firstCharacters(shownText, PushPreviewPayload.TEXT_CHARACTERS)));
        add(list, textMark(senderPeerKey,
                           PushPreviewPayload.firstCharacters(rawText, PushPreviewPayload.TEXT_CHARACTERS)));
        return new Event(list, false);
    }

    /** As above, with the message id as a row keeps it (message_id_tox: 8 hex); no event mark for any other. */
    static Event forGroupText(final String messageIdHex, final String senderPeerKey)
    {
        return forGroupText(messageIdHex, senderPeerKey, null, null);
    }

    /** As above, and by what it says (forGroupText(long, String, String, String)). */
    static Event forGroupText(final String messageIdHex, final String senderPeerKey, final String shownText,
                              final String rawText)
    {
        // Checked as 4 bytes of hex first: parseLong takes a sign and any number of digits too.
        final long messageId = (PushPreviewCrypto.unhex(messageIdHex, 4) == null)
                               ? -1L : Long.parseLong(messageIdHex, 16);
        return forGroupText(messageId, senderPeerKey, shownText, rawText);
    }

    /** KHANDAQ (0.2.69, group message wakes): a group file that came over Tox, by its 32-byte hash. */
    static Event forGroupFile(final String fileHashHex)
    {
        final List<String> list = new ArrayList<>(1);
        add(list, eventMark(PushPreviewPayload.groupFileEventKey(fileHashHex)));
        return new Event(list, false);
    }

    /** A reaction that came over Tox, its anchor as on the wire. */
    static Event forReaction(final String anchorHex, final String emoji)
    {
        final List<String> list = new ArrayList<>(1);
        add(list, eventMark(PushPreviewPayload.reactionEventKey(anchorHex, emoji)));
        return new Event(list, true);
    }

    // ---- the two paths ----

    /**
     * The push handler, before it shows a preview: false when its event key was shown in the last 30 minutes, or
     * when what it says came over Tox in the last two minutes (that arrival is used up).
     */
    synchronized boolean pushMayShow(final SharedPreferences prefs, final Event event, final long nowMs)
    {
        load(prefs, nowMs);
        boolean seen = false;
        for (final String mark : event.marks)
        {
            if (isEventMark(mark))
            {
                seen |= fresh(events.get(mark), nowMs, REMEMBER_MS);
            }
            else if (fresh(arrivals.get(mark), nowMs, ARRIVAL_MS))
            {
                arrivals.remove(mark);
                seen = true;
            }
        }
        return !seen;
    }

    /**
     * The push handler, once it showed a preview: its event key is remembered, here and on disk; what a preview
     * without one said, in memory only.
     */
    synchronized void pushShown(final SharedPreferences prefs, final Event event, final long nowMs)
    {
        load(prefs, nowMs);
        for (final String mark : event.marks)
        {
            if (!isEventMark(mark))
            {
                pushedContent.put(mark, nowMs);
            }
        }
        rememberEvents(prefs, event, nowMs);
    }

    /**
     * The Tox path, before it alerts: true when a push showed this event in the last 30 minutes, by its event key or
     * (once) by what it says. Either way the event is remembered, so that a push that comes late does not alert for
     * it again.
     */
    synchronized boolean toxArrived(final SharedPreferences prefs, final Event event, final long nowMs)
    {
        load(prefs, nowMs);
        boolean seen = false;
        for (final String mark : event.marks)
        {
            if (isEventMark(mark))
            {
                seen |= fresh(events.get(mark), nowMs, REMEMBER_MS);
            }
            else
            {
                if (fresh(pushedContent.get(mark), nowMs, REMEMBER_MS))
                {
                    pushedContent.remove(mark);
                    seen = true;
                }
                arrivals.put(mark, nowMs);
            }
        }
        rememberEvents(prefs, event, nowMs);
        return seen;
    }

    /** The profile is wiped: nothing of it is remembered (the file itself is cleared by PushPreviewStore.wipeAll). */
    synchronized void forgetAll()
    {
        events.clear();
        arrivals.clear();
        pushedContent.clear();
        loaded = false;
    }

    /** As {@link #toxArrived(SharedPreferences, Event, long)} for the app; false without a context or an event. */
    static boolean toxArrived(final Context context, final Event event)
    {
        if (context == null || event == null || event.marks.isEmpty())
        {
            return false;
        }
        try
        {
            return SHARED.toxArrived(prefs(context), event, System.currentTimeMillis());
        }
        catch (RuntimeException e)
        {
            return false;
        }
    }

    static SharedPreferences prefs(final Context context)
    {
        return context.getSharedPreferences(PREFS_SEEN, Context.MODE_PRIVATE);
    }

    // ---- inside ----

    private static boolean fresh(final Long at, final long nowMs, final long windowMs)
    {
        return at != null && nowMs - at >= 0 && nowMs - at <= windowMs;
    }

    private void rememberEvents(final SharedPreferences prefs, final Event event, final long nowMs)
    {
        boolean changed = false;
        for (final String mark : event.marks)
        {
            if (isEventMark(mark))
            {
                events.put(mark, nowMs);
                changed = true;
            }
        }
        changed |= prune(events, nowMs, REMEMBER_MS);
        prune(arrivals, nowMs, ARRIVAL_MS);
        prune(pushedContent, nowMs, REMEMBER_MS);
        if (changed)
        {
            save(prefs);
        }
    }

    /** The event keys shown in an earlier process, read once. */
    private void load(final SharedPreferences prefs, final long nowMs)
    {
        if (loaded)
        {
            return;
        }
        loaded = true;
        for (final Map.Entry<String, ?> entry : prefs.getAll().entrySet())
        {
            if (isEventMark(entry.getKey()) && entry.getValue() instanceof Long
                && fresh((Long) entry.getValue(), nowMs, REMEMBER_MS) && !events.containsKey(entry.getKey()))
            {
                events.put(entry.getKey(), (Long) entry.getValue());
            }
        }
    }

    /** Drops what is older than the window, then the oldest beyond MAX_MARKS; true when anything went. */
    private static boolean prune(final Map<String, Long> marks, final long nowMs, final long windowMs)
    {
        final int before = marks.size();
        final Iterator<Map.Entry<String, Long>> it = marks.entrySet().iterator();
        while (it.hasNext())
        {
            if (!fresh(it.next().getValue(), nowMs, windowMs))
            {
                it.remove();
            }
        }
        while (marks.size() > MAX_MARKS)
        {
            String oldest = null;
            long oldestAt = Long.MAX_VALUE;
            for (final Map.Entry<String, Long> entry : marks.entrySet())
            {
                if (entry.getValue() < oldestAt)
                {
                    oldest = entry.getKey();
                    oldestAt = entry.getValue();
                }
            }
            marks.remove(oldest);
        }
        return marks.size() != before;
    }

    private void save(final SharedPreferences prefs)
    {
        final SharedPreferences.Editor editor = prefs.edit().clear();
        for (final Map.Entry<String, Long> entry : events.entrySet())
        {
            editor.putLong(entry.getKey(), entry.getValue());
        }
        editor.apply();
    }

    private static void add(final List<String> list, final String mark)
    {
        if (mark != null && !list.contains(mark))
        {
            list.add(mark);
        }
    }

    private static String digest(final String text)
    {
        return PushPreviewCrypto.hex(PushPreviewCrypto.sha256(text.getBytes(StandardCharsets.UTF_8)), 0, 16);
    }
}
