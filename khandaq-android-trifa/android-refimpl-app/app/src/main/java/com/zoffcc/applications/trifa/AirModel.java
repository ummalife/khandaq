package com.zoffcc.applications.trifa;

import java.nio.charset.StandardCharsets;
import java.text.BreakIterator;
import java.text.Collator;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * KHANDAQ (redesign, design/redesign/02-plan.md, screens 1 and 2): the Air screen as plain values.
 * <p>
 * A port of khandaq-ios/Antidote/AirListModel.swift with the same rules and the same test vectors.
 * Nothing here touches the database, Android or a view, so the plain JUnit tests in AirModelTest cover
 * it. AirRepository turns friends, groups, saved messages and requests into these values, and
 * {@link ListBuilder} decides the sections, their order, filtering, empty states and every count on the
 * screen, so no two numbers come from different places.
 */
final class AirModel
{
    private AirModel()
    {
    }

    /** The filter pills. ALL is the resting state: tapping the active pill again returns to it. */
    enum Filter
    {
        ALL("all"),
        REQUESTS("requests"),
        TRANSIT("transit"),
        FAVORITES("favorites");

        /** The pills, in the order they stand on screen. */
        static final List<Filter> PILLS = Collections.unmodifiableList(
                java.util.Arrays.asList(REQUESTS, TRANSIT, FAVORITES));

        /** Order for the sideways swipe on the list: everything first, then the pills left to right. */
        static final List<Filter> SWIPE_ORDER = Collections.unmodifiableList(
                java.util.Arrays.asList(ALL, REQUESTS, TRANSIT, FAVORITES));

        /** Stable name for test tags and content descriptions the UI tests look for. */
        final String accessibilityName;

        Filter(final String accessibilityName)
        {
            this.accessibilityName = accessibilityName;
        }

        static Filter fromName(final String name)
        {
            for (final Filter filter : values())
            {
                if (filter.accessibilityName.equals(name))
                {
                    return filter;
                }
            }
            return ALL;
        }
    }

    /** Our own node, for the quiet line under the name in the "you" block. */
    enum NetworkState
    {
        /** Online, UDP: peers are reached directly. */
        DIRECT,
        /** Online through a TCP relay. */
        RELAY,
        /** The device has a network, Tox is not connected yet. */
        CONNECTING,
        /** No network at all: whatever is sent waits on this device. */
        OFFLINE;

        /** {@code toxConnection} is TOX_CONNECTION: 0 none, 1 TCP, 2 UDP. */
        static NetworkState from(final int toxConnection, final boolean networkReachable)
        {
            return from(toxConnection, networkReachable, false);
        }

        /**
         * As above, with the default network gone (the system reported it lost and no other network took
         * its place): then nothing leaves this device, whatever Tox still believes until its own timeouts
         * run out. With a network, Tox's state decides between connecting, direct and relay.
         */
        static NetworkState from(final int toxConnection, final boolean networkReachable,
                                 final boolean defaultNetworkLost)
        {
            if (defaultNetworkLost)
            {
                return OFFLINE;
            }
            if (toxConnection == TOX_CONNECTION_UDP)
            {
                return DIRECT;
            }
            if (toxConnection == TOX_CONNECTION_TCP)
            {
                return RELAY;
            }
            return networkReachable ? CONNECTING : OFFLINE;
        }
    }

    static final int TOX_CONNECTION_NONE = 0;
    static final int TOX_CONNECTION_TCP = 1;
    static final int TOX_CONNECTION_UDP = 2;

    private static final long ONE_DAY_SECONDS = 86400L;

    /** How the person or group behind a row can be reached right now. */
    static final class Reach
    {
        enum Kind
        {
            /** Online, the link goes straight to their device. */
            DIRECT,
            /** Online through a TCP relay. */
            RELAY,
            /** Not online; {@link #secondsAgo} since they were last seen. */
            AWAY,
            /** Added, but never online since: our request has not been answered yet. */
            PENDING,
            /**
             * Not linked yet, but active within the last day while our own node has only just come
             * online: their link is most likely still being set up, so the row says so instead of a
             * stale "last seen".
             */
            CONNECTING,
            /** A group and how many of its members are online; zero when nobody is or it is offline. */
            GROUP,
            /** Saved messages: never sent anywhere. */
            LOCAL
        }

        static final Reach DIRECT = new Reach(Kind.DIRECT, 0L, 0);
        static final Reach RELAY = new Reach(Kind.RELAY, 0L, 0);
        static final Reach PENDING = new Reach(Kind.PENDING, 0L, 0);
        static final Reach CONNECTING = new Reach(Kind.CONNECTING, 0L, 0);
        static final Reach LOCAL = new Reach(Kind.LOCAL, 0L, 0);
        /** Away for as long as anyone can tell: a removed contact, or one never seen with a date. */
        static final Reach AWAY_LONG_AGO = new Reach(Kind.AWAY, Long.MAX_VALUE, 0);

        final Kind kind;
        final long secondsAgo;
        final int online;

        private Reach(final Kind kind, final long secondsAgo, final int online)
        {
            this.kind = kind;
            this.secondsAgo = secondsAgo;
            this.online = online;
        }

        static Reach away(final long secondsAgo)
        {
            return new Reach(Kind.AWAY, Math.max(0L, secondsAgo), 0);
        }

        static Reach group(final int online)
        {
            return new Reach(Kind.GROUP, 0L, Math.max(0, online));
        }

        /** A message sent now would arrive now. */
        boolean isLive()
        {
            switch (kind)
            {
                case DIRECT:
                case RELAY:
                    return true;
                case GROUP:
                    return online > 0;
                default:
                    return false;
            }
        }

        /**
         * How many of a person's undelivered messages wait in transit: all of them while the person is
         * away, none while they are online, when the conversation shows the same messages as leaving or
         * sent ({@link AirConversationModel.EntryStatus#ofOutgoingText}).
         */
        int messagesInTransit(final int undelivered)
        {
            return isLive() ? 0 : undelivered;
        }

        /**
         * {@code lastSeenSeconds} is 0 or less when the friend was never online since being added.
         * {@code selfLinkSettling}: our own node came online less than a minute ago.
         */
        static Reach friend(final int toxConnection, final long lastSeenSeconds, final long nowSeconds,
                            final boolean selfLinkSettling)
        {
            if (toxConnection == TOX_CONNECTION_UDP)
            {
                return DIRECT;
            }
            if (toxConnection == TOX_CONNECTION_TCP)
            {
                return RELAY;
            }
            if (lastSeenSeconds <= 0L)
            {
                return PENDING;
            }
            final long secondsAgo = Math.max(0L, nowSeconds - lastSeenSeconds);
            if (selfLinkSettling && secondsAgo < ONE_DAY_SECONDS)
            {
                return CONNECTING;
            }
            return away(secondsAgo);
        }

        @Override
        public boolean equals(final Object other)
        {
            if (this == other)
            {
                return true;
            }
            if (!(other instanceof Reach))
            {
                return false;
            }
            final Reach that = (Reach) other;
            return kind == that.kind && secondsAgo == that.secondsAgo && online == that.online;
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(kind, secondsAgo, online);
        }

        @Override
        public String toString()
        {
            switch (kind)
            {
                case AWAY:
                    return "away(" + secondsAgo + ")";
                case GROUP:
                    return "group(" + online + ")";
                default:
                    return kind.name().toLowerCase(Locale.ROOT);
            }
        }
    }

    /** The state of our own last message in a row, drawn in front of the preview. */
    enum Delivery
    {
        TRANSIT,
        SENT,
        DELIVERED;

        /**
         * Our own last text to one person, by the conversation's rule
         * ({@link AirConversationModel.EntryStatus#ofOutgoingText}): in transit while the person is away,
         * a clock while it is leaving, then sent and delivered.
         */
        static Delivery ofOutgoingText(final boolean isDelivered, final long messageId, final boolean personOnline)
        {
            return of(AirConversationModel.EntryStatus.ofOutgoingText(false, isDelivered, messageId, personOnline));
        }

        /**
         * The same for a text row of the Android history, which also knows that the delivery watchdog
         * gave up on it: the row keeps a clock for that, the conversation says "not sent".
         */
        static Delivery ofDirectText(final boolean isDelivered, final boolean gaveUp, final long messageId,
                                     final boolean personOnline)
        {
            return of(AirConversationModel.EntryStatus.ofDirectText(false, isDelivered, gaveUp, messageId,
                                                                    personOnline));
        }

        private static Delivery of(final AirConversationModel.EntryStatus status)
        {
            switch (status)
            {
                case DELIVERED:
                    return DELIVERED;
                case SENT:
                    return SENT;
                default:
                    return TRANSIT;
            }
        }
    }

    enum EntryKind
    {
        DIRECT,
        GROUP,
        SAVED,
        /** A contact without a conversation: opening the row starts one. */
        CONTACT
    }

    enum PreviewStyle
    {
        MESSAGE,
        DRAFT,
        TYPING,
        /** A quiet explanation instead of a message (a request waiting for an answer, saved messages). */
        HINT
    }

    /** One row of the list: a conversation, or a contact who has no conversation yet. */
    static final class Entry
    {
        /** Unique across kinds: "f:" + public key, "g:" + group id, "c:" + conference id, or "saved". */
        final String id;
        final EntryKind kind;
        final String title;
        final Reach reach;
        final String preview;
        final PreviewStyle previewStyle;
        /** Null when the last message is not ours. */
        final Delivery lastDelivery;
        final String timeText;
        /** When the conversation last moved, in milliseconds; zero for a contact without one. */
        final long activity;
        final int transitCount;
        final int unreadCount;
        final boolean isFavorite;
        /** Whose photo the tile shows (a friend's public key), or null for none. */
        final String photoKey;
        /** {@link Tone} index for the initials, from the friend's public key or the group's id. */
        final int tone;

        Entry(final String id, final EntryKind kind, final String title, final Reach reach, final String preview,
              final PreviewStyle previewStyle, final Delivery lastDelivery, final String timeText,
              final long activity, final int transitCount, final int unreadCount, final boolean isFavorite,
              final String photoKey, final int tone)
        {
            this.id = id;
            this.kind = kind;
            this.title = title == null ? "" : title;
            this.reach = reach;
            this.preview = preview == null ? "" : preview;
            this.previewStyle = previewStyle;
            this.lastDelivery = lastDelivery;
            this.timeText = timeText == null ? "" : timeText;
            this.activity = activity;
            this.transitCount = Math.max(0, transitCount);
            this.unreadCount = Math.max(0, unreadCount);
            this.isFavorite = isFavorite;
            this.photoKey = photoKey;
            this.tone = tone;
        }

        SectionKind section()
        {
            if (kind == EntryKind.SAVED)
            {
                return SectionKind.SAVED;
            }
            return reach.isLive() ? SectionKind.LIVE : SectionKind.AWAY;
        }

        @Override
        public boolean equals(final Object other)
        {
            if (this == other)
            {
                return true;
            }
            if (!(other instanceof Entry))
            {
                return false;
            }
            final Entry that = (Entry) other;
            return activity == that.activity && transitCount == that.transitCount
                   && unreadCount == that.unreadCount && isFavorite == that.isFavorite && tone == that.tone
                   && id.equals(that.id) && kind == that.kind && title.equals(that.title)
                   && reach.equals(that.reach) && preview.equals(that.preview)
                   && previewStyle == that.previewStyle && lastDelivery == that.lastDelivery
                   && timeText.equals(that.timeText) && Objects.equals(photoKey, that.photoKey);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(id, kind, title, reach, preview, previewStyle, lastDelivery, timeText, activity,
                                transitCount, unreadCount, isFavorite, photoKey, tone);
        }

        @Override
        public String toString()
        {
            return "Entry{" + id + ", " + kind + ", " + title + ", " + reach + "}";
        }
    }

    /** An incoming friend request or an invitation to a group, drawn as a card above the sections (E12). */
    static final class Request
    {
        enum Kind
        {
            CONTACT,
            /** An invitation to a group: it names the group and the contact who invites. */
            GROUP_INVITE
        }

        /** A request: the public key, upper case, one card per person however often they ask. An invitation: its ID. */
        final String id;
        final Kind kind;
        /** The person who asks, or the contact who invites. */
        final String publicKey;
        final String message;
        final String timeText;
        /** When it arrived, in milliseconds. */
        final long date;
        /** For an invitation: the group's name (may be empty), who invites and whether they are online now. */
        final String groupName;
        final String inviterName;
        final boolean inviterOnline;

        Request(final String publicKey, final String message, final String timeText, final long date)
        {
            this(Kind.CONTACT, null, publicKey, message, timeText, date, "", "", false);
        }

        private Request(final Kind kind, final String id, final String publicKey, final String message,
                        final String timeText, final long date, final String groupName, final String inviterName,
                        final boolean inviterOnline)
        {
            this.kind = kind;
            this.publicKey = publicKey == null ? "" : publicKey.toUpperCase(Locale.ROOT);
            this.id = id == null ? this.publicKey : id;
            this.message = message == null ? "" : message.trim();
            this.timeText = timeText == null ? "" : timeText;
            this.date = date;
            this.groupName = groupName == null ? "" : groupName.trim();
            this.inviterName = inviterName == null ? "" : inviterName.trim();
            this.inviterOnline = inviterOnline;
        }

        static Request invite(final String id, final String inviterKey, final String groupName, final String inviterName,
                              final boolean inviterOnline, final String timeText, final long date)
        {
            return new Request(Kind.GROUP_INVITE, id, inviterKey, "", timeText, date, groupName, inviterName,
                               inviterOnline);
        }

        @Override
        public boolean equals(final Object other)
        {
            if (this == other)
            {
                return true;
            }
            if (!(other instanceof Request))
            {
                return false;
            }
            final Request that = (Request) other;
            return date == that.date && kind == that.kind && inviterOnline == that.inviterOnline && id.equals(that.id)
                   && message.equals(that.message) && timeText.equals(that.timeText)
                   && groupName.equals(that.groupName) && inviterName.equals(that.inviterName);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(id, kind, message, timeText, date, groupName, inviterName, inviterOnline);
        }
    }

    /** A message that matched the command line, listed under the rows while searching. */
    static final class MessageHit
    {
        final String id;
        /** The entry id of the conversation it belongs to. */
        final String chatId;
        final String title;
        final String snippet;
        final String timeText;

        MessageHit(final String id, final String chatId, final String title, final String snippet,
                   final String timeText)
        {
            this.id = id;
            this.chatId = chatId;
            this.title = title == null ? "" : title;
            this.snippet = snippet == null ? "" : snippet;
            this.timeText = timeText == null ? "" : timeText;
        }

        @Override
        public boolean equals(final Object other)
        {
            if (this == other)
            {
                return true;
            }
            if (!(other instanceof MessageHit))
            {
                return false;
            }
            final MessageHit that = (MessageHit) other;
            return id.equals(that.id) && chatId.equals(that.chatId) && title.equals(that.title)
                   && snippet.equals(that.snippet) && timeText.equals(that.timeText);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(id, chatId, title, snippet, timeText);
        }
    }

    enum SectionKind
    {
        REQUESTS("requests"),
        LIVE("live"),
        AWAY("away"),
        SAVED("saved"),
        MESSAGES("messages");

        /** The order sections stand in, top to bottom. */
        static final List<SectionKind> ORDER = Collections.unmodifiableList(
                java.util.Arrays.asList(REQUESTS, LIVE, AWAY, SAVED, MESSAGES));

        final String accessibilityName;

        SectionKind(final String accessibilityName)
        {
            this.accessibilityName = accessibilityName;
        }

        /** Request cards carry their own label; every other section has a heading with its count. */
        boolean hasHeading()
        {
            return this != REQUESTS;
        }
    }

    /** One item of a section. Exactly one of the payloads is set. */
    static final class Item
    {
        final Request request;
        final Entry entry;
        final MessageHit message;

        private Item(final Request request, final Entry entry, final MessageHit message)
        {
            this.request = request;
            this.entry = entry;
            this.message = message;
        }

        static Item of(final Request request)
        {
            return new Item(request, null, null);
        }

        static Item of(final Entry entry)
        {
            return new Item(null, entry, null);
        }

        static Item of(final MessageHit hit)
        {
            return new Item(null, null, hit);
        }

        /** Identity for animated updates: a person keeps the same id when their row changes section. */
        String id()
        {
            if (request != null)
            {
                return "request:" + request.id;
            }
            if (entry != null)
            {
                return "entry:" + entry.id;
            }
            return "message:" + message.id;
        }

        @Override
        public boolean equals(final Object other)
        {
            if (this == other)
            {
                return true;
            }
            if (!(other instanceof Item))
            {
                return false;
            }
            final Item that = (Item) other;
            return Objects.equals(request, that.request) && Objects.equals(entry, that.entry)
                   && Objects.equals(message, that.message);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(request, entry, message);
        }
    }

    /** What the list says when it has nothing to show for the current filter or query. */
    enum EmptyState
    {
        /** No contacts and no requests yet: only saved messages. */
        NOTHING_YET,
        /** The command line matched nothing. */
        NO_MATCH,
        NO_REQUESTS,
        NO_TRANSIT,
        /** Nothing starred: only saved messages. */
        NO_FAVORITES
    }

    /** The numbers on the pills, counted over everything whatever the filter or the query. */
    static final class Counts
    {
        final int requests;
        final int transit;

        Counts(final int requests, final int transit)
        {
            this.requests = requests;
            this.transit = transit;
        }

        @Override
        public boolean equals(final Object other)
        {
            return other instanceof Counts && ((Counts) other).requests == requests
                   && ((Counts) other).transit == transit;
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(requests, transit);
        }
    }

    static final class Section
    {
        final SectionKind kind;
        final List<Item> items;

        Section(final SectionKind kind, final List<Item> items)
        {
            this.kind = kind;
            this.items = Collections.unmodifiableList(new ArrayList<>(items));
        }
    }

    /**
     * One line of the RecyclerView: a section heading or an item. The heading of a section keeps its id
     * when its count changes, so DiffUtil rolls the number instead of replacing the line.
     */
    static final class Row
    {
        enum Type
        {
            HEADER,
            REQUEST,
            ENTRY,
            MESSAGE
        }

        final Type type;
        final SectionKind section;
        /** For a heading: how many items the section holds. */
        final int count;
        /** Null for a heading. */
        final Item item;

        private Row(final Type type, final SectionKind section, final int count, final Item item)
        {
            this.type = type;
            this.section = section;
            this.count = count;
            this.item = item;
        }

        String id()
        {
            return type == Type.HEADER ? "header:" + section.accessibilityName : item.id();
        }

        boolean sameContent(final Row other)
        {
            return other != null && type == other.type && section == other.section && count == other.count
                   && Objects.equals(item, other.item);
        }
    }

    static final class Snapshot
    {
        static final Snapshot EMPTY = new Snapshot(Collections.<Section>emptyList(), new Counts(0, 0), null);

        final List<Section> sections;
        final Counts counts;
        /** Null when there is something to show. */
        final EmptyState emptyState;
        final List<Row> rows;

        Snapshot(final List<Section> sections, final Counts counts, final EmptyState emptyState)
        {
            this.sections = Collections.unmodifiableList(new ArrayList<>(sections));
            this.counts = counts;
            this.emptyState = emptyState;
            final List<Row> flat = new ArrayList<>();
            for (final Section section : this.sections)
            {
                if (section.kind.hasHeading())
                {
                    flat.add(new Row(Row.Type.HEADER, section.kind, section.items.size(), null));
                }
                for (final Item item : section.items)
                {
                    final Row.Type type = item.request != null ? Row.Type.REQUEST
                                          : item.entry != null ? Row.Type.ENTRY : Row.Type.MESSAGE;
                    flat.add(new Row(type, section.kind, 0, item));
                }
            }
            this.rows = Collections.unmodifiableList(flat);
        }

        int itemCount()
        {
            int total = 0;
            for (final Section section : sections)
            {
                total += section.items.size();
            }
            return total;
        }

        Section section(final SectionKind kind)
        {
            for (final Section section : sections)
            {
                if (section.kind == kind)
                {
                    return section;
                }
            }
            return null;
        }
    }

    static final class ListBuilder
    {
        private ListBuilder()
        {
        }

        static Snapshot build(final List<Entry> entries, final List<Request> requests,
                              final List<MessageHit> messageHits, final Filter filter, final String query)
        {
            int transit = 0;
            for (final Entry entry : entries)
            {
                transit += entry.transitCount;
            }
            final Counts counts = new Counts(requests.size(), transit);

            final String needle = query == null ? "" : query.trim();
            final boolean searching = !needle.isEmpty();
            final String foldedNeedle = fold(needle);

            final List<Entry> visible = new ArrayList<>();
            for (final Entry entry : entries)
            {
                if (!matches(entry, filter))
                {
                    continue;
                }
                if (searching && !fold(entry.title).contains(foldedNeedle))
                {
                    continue;
                }
                visible.add(entry);
            }

            final List<Section> sections = new ArrayList<>();

            if (!searching && (filter == Filter.ALL || filter == Filter.REQUESTS) && !requests.isEmpty())
            {
                final List<Request> newestFirst = new ArrayList<>(requests);
                Collections.sort(newestFirst, new Comparator<Request>()
                {
                    @Override
                    public int compare(final Request first, final Request second)
                    {
                        if (first.date != second.date)
                        {
                            return first.date > second.date ? -1 : 1;
                        }
                        return first.id.compareTo(second.id);
                    }
                });
                final List<Item> items = new ArrayList<>();
                for (final Request request : newestFirst)
                {
                    items.add(Item.of(request));
                }
                sections.add(new Section(SectionKind.REQUESTS, items));
            }

            final Comparator<Entry> order = rowOrder();
            for (final SectionKind kind : new SectionKind[]{SectionKind.LIVE, SectionKind.AWAY, SectionKind.SAVED})
            {
                final List<Entry> inSection = new ArrayList<>();
                for (final Entry entry : visible)
                {
                    if (entry.section() == kind)
                    {
                        inSection.add(entry);
                    }
                }
                if (inSection.isEmpty())
                {
                    continue;
                }
                Collections.sort(inSection, order);
                final List<Item> items = new ArrayList<>();
                for (final Entry entry : inSection)
                {
                    items.add(Item.of(entry));
                }
                sections.add(new Section(kind, items));
            }

            if (searching && messageHits != null && !messageHits.isEmpty())
            {
                final List<Item> items = new ArrayList<>();
                for (final MessageHit hit : messageHits)
                {
                    items.add(Item.of(hit));
                }
                sections.add(new Section(SectionKind.MESSAGES, items));
            }

            final EmptyState state = emptyState(sections, entries, visible, requests, filter, searching);
            return new Snapshot(sections, counts, state);
        }

        static boolean matches(final Entry entry, final Filter filter)
        {
            switch (filter)
            {
                case REQUESTS:
                    return entry.reach.kind == Reach.Kind.PENDING;
                case TRANSIT:
                    return entry.transitCount > 0;
                case FAVORITES:
                    return entry.isFavorite || entry.kind == EntryKind.SAVED;
                case ALL:
                default:
                    return true;
            }
        }

        /** Latest activity first; contacts without a conversation (no activity) last, by name. */
        static Comparator<Entry> rowOrder()
        {
            final Collator collator = Collator.getInstance();
            collator.setStrength(Collator.SECONDARY);
            return new Comparator<Entry>()
            {
                @Override
                public int compare(final Entry first, final Entry second)
                {
                    if (first.activity != second.activity)
                    {
                        return first.activity > second.activity ? -1 : 1;
                    }
                    final int byName = collator.compare(first.title, second.title);
                    if (byName != 0)
                    {
                        return byName;
                    }
                    return first.id.compareTo(second.id);
                }
            };
        }

        private static EmptyState emptyState(final List<Section> sections, final List<Entry> entries,
                                             final List<Entry> visible, final List<Request> requests,
                                             final Filter filter, final boolean searching)
        {
            if (searching)
            {
                return sections.isEmpty() ? EmptyState.NO_MATCH : null;
            }

            switch (filter)
            {
                case REQUESTS:
                    return sections.isEmpty() ? EmptyState.NO_REQUESTS : null;
                case TRANSIT:
                    return sections.isEmpty() ? EmptyState.NO_TRANSIT : null;
                case FAVORITES:
                    for (final Entry entry : visible)
                    {
                        if (entry.kind != EntryKind.SAVED)
                        {
                            return null;
                        }
                    }
                    return EmptyState.NO_FAVORITES;
                case ALL:
                default:
                    if (!requests.isEmpty())
                    {
                        return null;
                    }
                    for (final Entry entry : entries)
                    {
                        if (entry.kind != EntryKind.SAVED)
                        {
                            return null;
                        }
                    }
                    return EmptyState.NOTHING_YET;
            }
        }
    }

    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");

    /** Case- and diacritic-insensitive form for matching a query, as iOS compares titles. */
    static String fold(final String text)
    {
        if (text == null || text.isEmpty())
        {
            return "";
        }
        final String decomposed = Normalizer.normalize(text, Normalizer.Form.NFD);
        return COMBINING_MARKS.matcher(decomposed).replaceAll("").toLowerCase(Locale.ROOT);
    }

    /** Where every occurrence of the query starts and ends in {@code text}, ignoring case and marks. */
    static List<int[]> matchRanges(final String text, final String query)
    {
        final List<int[]> ranges = new ArrayList<>();
        final String needle = query == null ? "" : query.trim();
        if (text == null || text.isEmpty() || needle.isEmpty())
        {
            return ranges;
        }
        // Fold character by character so positions in the folded text map back to the original.
        final StringBuilder folded = new StringBuilder();
        final List<Integer> origin = new ArrayList<>();
        int index = 0;
        while (index < text.length())
        {
            final int codePoint = text.codePointAt(index);
            final int next = index + Character.charCount(codePoint);
            final String piece = fold(text.substring(index, next));
            for (int i = 0; i < piece.length(); i++)
            {
                folded.append(piece.charAt(i));
                origin.add(index);
            }
            index = next;
        }
        origin.add(text.length());

        final String foldedText = folded.toString();
        final String foldedNeedle = fold(needle);
        if (foldedNeedle.isEmpty())
        {
            return ranges;
        }
        int from = 0;
        while (from <= foldedText.length() - foldedNeedle.length())
        {
            final int found = foldedText.indexOf(foldedNeedle, from);
            if (found < 0)
            {
                break;
            }
            final int end = found + foldedNeedle.length();
            final int start = origin.get(found);
            int stop = origin.get(end);
            // A folded end inside one original character still covers the whole character.
            if (stop <= start)
            {
                stop = Math.min(text.length(), start + 1);
            }
            ranges.add(new int[]{start, stop});
            from = end;
        }
        return ranges;
    }

    /** The first letters of the first two words, or one letter for a single word. */
    static final class Initials
    {
        private static final Pattern WHITESPACE = Pattern.compile("[\\s\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000]+");

        private Initials()
        {
        }

        static String from(final String title)
        {
            if (title == null)
            {
                return "";
            }
            final StringBuilder letters = new StringBuilder();
            int words = 0;
            for (final String word : WHITESPACE.split(title))
            {
                if (word.isEmpty())
                {
                    continue;
                }
                if (words == 2)
                {
                    break;
                }
                final BreakIterator characters = BreakIterator.getCharacterInstance();
                characters.setText(word);
                final int end = characters.next();
                final String first = word.substring(0, end == BreakIterator.DONE ? word.length() : end);
                if (words > 0)
                {
                    // A zero-width non-joiner keeps two Arabic letters in their separate forms.
                    letters.append('‌');
                }
                letters.append(first.toUpperCase(Locale.ROOT));
                words++;
            }
            return letters.toString();
        }
    }

    /**
     * The six identity tones: the initials on a person's or a group's tile. A tone only tells people
     * apart; it never carries a state.
     */
    static final class Tone
    {
        static final int COUNT = 6;

        private Tone()
        {
        }

        /**
         * A stable tone for a key (a public key or a group id): the same person keeps the same colour on
         * every launch and every device, iOS included, so the hash is spelled out (FNV-1a, 32 bit).
         */
        static int index(final String key)
        {
            int hash = 0x811C9DC5;
            final byte[] bytes = (key == null ? "" : key).toUpperCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
            for (final byte value : bytes)
            {
                hash ^= (value & 0xFF);
                hash *= 0x01000193;
            }
            return Integer.remainderUnsigned(hash, COUNT);
        }

        static int wrap(final int index)
        {
            return ((index % COUNT) + COUNT) % COUNT;
        }
    }

    /**
     * What the command line holds: a search, a whole Tox ID that opens "new contact", or the start of one
     * that the row "New contact with this ID" offers while the search goes on.
     */
    static final class Command
    {
        enum Type
        {
            EMPTY,
            QUERY,
            CONTACT_ADDRESS,
            /** 12 to 75 hex characters with a digit among them; the value is that hex, also searched for. */
            PARTIAL_ADDRESS
        }

        static final int TOX_ADDRESS_HEX_LENGTH = 76;
        private static final String[] PREFIXES = {"TOX://", "KHANDAQ://", "TOX:", "KHANDAQ:", "TOX-ID:", "TOXID:"};

        final Type type;
        /** The trimmed query, the address in upper case, or "". */
        final String value;

        private Command(final Type type, final String value)
        {
            this.type = type;
            this.value = value;
        }

        static Command parse(final String raw)
        {
            final String address = standaloneToxAddress(raw);
            if (address != null)
            {
                return new Command(Type.CONTACT_ADDRESS, address);
            }
            final String start = AirNewContactModel.commandCandidate(raw);
            if (start != null && start.length() < TOX_ADDRESS_HEX_LENGTH)
            {
                return new Command(Type.PARTIAL_ADDRESS, start);
            }
            final String trimmed = raw == null ? "" : raw.trim();
            return trimmed.isEmpty() ? new Command(Type.EMPTY, "") : new Command(Type.QUERY, trimmed);
        }

        /**
         * A full 76-character Tox ID typed or pasted on its own, in upper case; null for anything else.
         * Spaces, line breaks, invisible formatting characters and one "tox:"-style prefix are allowed,
         * but every other character has to be part of the ID: a search like "cafe bead" stays a search.
         * A 64-character key is not accepted, since it is also the length of a group ID. The checksum is
         * left to New contact, which already validates it.
         */
        static String standaloneToxAddress(final String raw)
        {
            if (raw == null)
            {
                return null;
            }
            final String upper = raw.toUpperCase(Locale.ROOT);
            final StringBuilder text = new StringBuilder(upper.length());
            int index = 0;
            while (index < upper.length())
            {
                final int codePoint = upper.codePointAt(index);
                index += Character.charCount(codePoint);
                if (isInvisibleOrSpace(codePoint))
                {
                    continue;
                }
                text.appendCodePoint(codePoint);
            }

            String compact = text.toString();
            for (final String prefix : PREFIXES)
            {
                if (compact.startsWith(prefix))
                {
                    compact = compact.substring(prefix.length());
                    break;
                }
            }

            if (compact.length() != TOX_ADDRESS_HEX_LENGTH)
            {
                return null;
            }
            for (int i = 0; i < compact.length(); i++)
            {
                final char c = compact.charAt(i);
                if (!((c >= '0' && c <= '9') || (c >= 'A' && c <= 'F')))
                {
                    return null;
                }
            }
            return compact;
        }

        private static boolean isInvisibleOrSpace(final int codePoint)
        {
            if ((codePoint >= 0x200B && codePoint <= 0x200D) || codePoint == 0xFEFF || codePoint == 0x2060
                || codePoint == 0x00AD || codePoint == 0x2028 || codePoint == 0x2029 || codePoint == 0x00A0
                || codePoint == 0x202F || codePoint == 0x3000)
            {
                return true;
            }
            return Character.isWhitespace(codePoint) || Character.isISOControl(codePoint)
                   || Character.isSpaceChar(codePoint);
        }

        @Override
        public boolean equals(final Object other)
        {
            return other instanceof Command && ((Command) other).type == type && ((Command) other).value.equals(value);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(type, value);
        }

        @Override
        public String toString()
        {
            return type + "(" + value + ")";
        }
    }

    /**
     * The first twelve characters of a key in groups of four: enough to compare with what the person
     * sent, never mistaken for a whole ID.
     */
    static String groupedKey(final String publicKey)
    {
        if (publicKey == null)
        {
            return "";
        }
        final String head = publicKey.toUpperCase(Locale.ROOT).substring(0, Math.min(12, publicKey.length()));
        final StringBuilder grouped = new StringBuilder();
        for (int i = 0; i < head.length(); i += 4)
        {
            if (i > 0)
            {
                grouped.append(' ');
            }
            grouped.append(head, i, Math.min(i + 4, head.length()));
        }
        return grouped.toString();
    }

    /** Index of {@code filter} in {@link Filter#SWIPE_ORDER} moved by {@code step}, or null at either end. */
    static Filter neighbourFilter(final Filter filter, final int step)
    {
        final int index = Filter.SWIPE_ORDER.indexOf(filter) + step;
        if (index < 0 || index >= Filter.SWIPE_ORDER.size())
        {
            return null;
        }
        return Filter.SWIPE_ORDER.get(index);
    }

    /** "Away" words for a row: which of the reach strings applies and the number it carries. */
    static final class AwayWords
    {
        enum Unit
        {
            JUST_NOW,
            MINUTES,
            HOURS,
            DAYS,
            LONG_AGO
        }

        final Unit unit;
        final long amount;

        private AwayWords(final Unit unit, final long amount)
        {
            this.unit = unit;
            this.amount = amount;
        }

        static AwayWords of(final long secondsAgo)
        {
            if (secondsAgo < 60L)
            {
                return new AwayWords(Unit.JUST_NOW, 0L);
            }
            if (secondsAgo < 3600L)
            {
                return new AwayWords(Unit.MINUTES, Math.max(1L, secondsAgo / 60L));
            }
            if (secondsAgo < ONE_DAY_SECONDS)
            {
                return new AwayWords(Unit.HOURS, Math.max(1L, secondsAgo / 3600L));
            }
            if (secondsAgo < ONE_DAY_SECONDS * 7L)
            {
                return new AwayWords(Unit.DAYS, Math.max(1L, secondsAgo / ONE_DAY_SECONDS));
            }
            return new AwayWords(Unit.LONG_AGO, 0L);
        }
    }
}
