package com.zoffcc.applications.trifa;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.text.TextUtils;
import android.util.Log;

import com.zoffcc.applications.sorm.ConferenceDB;
import com.zoffcc.applications.sorm.ConferenceMessage;
import com.zoffcc.applications.sorm.FriendList;
import com.zoffcc.applications.sorm.GroupDB;
import com.zoffcc.applications.sorm.GroupMessage;
import com.zoffcc.applications.sorm.Message;

import org.khandaq.messenger.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.zoffcc.applications.trifa.TRIFAGlobals.LAST_ONLINE_TIMSTAMP_ONLINE_NOW;
import static com.zoffcc.applications.trifa.TRIFAGlobals.LAST_ONLINE_TIMSTAMP_ONLINE_OFFLINE;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TRIFA_MSG_TYPE.TRIFA_MSG_FILE;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TRIFA_MSG_TYPE.TRIFA_MSG_TYPE_TEXT;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TRIFA_SYSTEM_MESSAGE_PEER_PUBKEY;
import static com.zoffcc.applications.trifa.ToxVars.TOX_FILE_CONTROL.TOX_FILE_CONTROL_CANCEL;
import static com.zoffcc.applications.trifa.ToxVars.TOX_FILE_CONTROL.TOX_FILE_CONTROL_PAUSE;

/**
 * KHANDAQ (redesign, design/redesign/02-plan.md, screens 1 and 2): turns friends, groups, conferences,
 * saved messages and pending requests into the plain values of {@link AirModel}, off the main thread.
 *
 * <p>The old chat and contact lists stay alive, hidden, under the Air screen: every toxcore callback
 * already tells them when something changed, and their refresh methods call {@link #requestRefresh()}.
 * A refresh is cheap to ask for: requests within a short window fold into one, and nothing runs while
 * the Air screen is not started (it refreshes once it starts again).
 *
 * <p>The rules for "in transit", the delivery marks and "waiting for reply" are the ones the bubbles
 * in a conversation use (MessageStatusHelper, the outgoing file holders), so a row never contradicts
 * the chat it opens.
 */
final class AirRepository
{
    private static final String TAG = "trifa.AirRepository";

    /** The entry id of saved messages. */
    static final String SAVED_ID = "saved";
    static final String FRIEND_PREFIX = "f:";
    static final String GROUP_PREFIX = "g:";
    static final String CONFERENCE_PREFIX = "c:";

    private static final long REFRESH_DEBOUNCE_MS = 120L;
    private static final long REFRESH_MIN_GAP_MS = 400L;
    /** File progress ticks: the row only shows whether a file is still on its way, not how far. */
    private static final long LOW_PRIORITY_DELAY_MS = 1500L;
    private static final long RETRY_WITHOUT_DATABASE_MS = 1000L;
    /** A typing mark older than this is a lost "stopped typing". */
    private static final long TYPING_STALE_MS = 20_000L;
    /** Our own node counts as just online (contacts still "connecting") for this long. */
    private static final long SELF_LINK_SETTLING_MS = 60_000L;
    private static final int MESSAGE_HITS_MAX = 40;
    /** A guard on the "in transit" queries, which normally return a handful of rows. */
    private static final int TRANSIT_ROWS_MAX = 5000;
    private static final int SNIPPET_MAX_CHARS = 160;

    interface Listener
    {
        void onAirData(Data data);

        /** Messages that matched {@code query}; the screen drops them when the line has moved on. */
        void onAirMessageHits(String query, List<AirModel.MessageHit> hits);
    }

    /** What an entry stands for, to open it and to build its menu. Exactly one field is set, or none for saved messages. */
    static final class Source
    {
        final FriendList friend;
        final GroupDB group;
        final ConferenceDB conference;

        private Source(final FriendList friend, final GroupDB group, final ConferenceDB conference)
        {
            this.friend = friend;
            this.group = group;
            this.conference = conference;
        }
    }

    static final class Data
    {
        static final Data EMPTY = new Data(Collections.<AirModel.Entry>emptyList(),
                                           Collections.<AirModel.Request>emptyList(),
                                           AirModel.NetworkState.CONNECTING, "", null,
                                           Collections.<String, ChatBubbleUiHelper.AvatarMeta>emptyMap(),
                                           Collections.<String, Source>emptyMap(), false);

        final List<AirModel.Entry> entries;
        final List<AirModel.Request> requests;
        final AirModel.NetworkState network;
        /** Our own name, or "" when none is set. */
        final String selfName;
        /** Our own photo, or null when there is none. */
        final ProfileTabAvatarHelper.OwnPhoto selfPhoto;
        /** Friends' photos by {@link AirModel.Entry#photoKey}. */
        final Map<String, ChatBubbleUiHelper.AvatarMeta> photos;
        /** By {@link AirModel.Entry#id}. */
        final Map<String, Source> sources;
        /** False until the database has been read once: the screen does not show "nobody here" before. */
        final boolean loaded;

        Data(final List<AirModel.Entry> entries, final List<AirModel.Request> requests,
             final AirModel.NetworkState network, final String selfName,
             final ProfileTabAvatarHelper.OwnPhoto selfPhoto,
             final Map<String, ChatBubbleUiHelper.AvatarMeta> photos, final Map<String, Source> sources,
             final boolean loaded)
        {
            this.entries = Collections.unmodifiableList(entries);
            this.requests = Collections.unmodifiableList(requests);
            this.network = network;
            this.selfName = selfName;
            this.selfPhoto = selfPhoto;
            this.photos = Collections.unmodifiableMap(photos);
            this.sources = Collections.unmodifiableMap(sources);
            this.loaded = loaded;
        }

        AirModel.Entry entry(final String id)
        {
            for (final AirModel.Entry entry : entries)
            {
                if (entry.id.equals(id))
                {
                    return entry;
                }
            }
            return null;
        }

        /**
         * True when {@code other} draws exactly the same screen. Group maintenance alone asks for a
         * refresh about once a second with nothing new to show, and redrawing for it keeps the
         * window from ever going idle. The sources are left out on purpose: they are fresh copies
         * of the same rows, and a tap reads whichever data arrived last.
         */
        boolean sameScreen(final Data other)
        {
            return other != null && loaded == other.loaded && network == other.network
                   && selfName.equals(other.selfName) && Objects.equals(selfPhoto, other.selfPhoto)
                   && entries.equals(other.entries) && requests.equals(other.requests)
                   && samePhotos(photos, other.photos);
        }

        private static boolean samePhotos(final Map<String, ChatBubbleUiHelper.AvatarMeta> mine,
                                          final Map<String, ChatBubbleUiHelper.AvatarMeta> theirs)
        {
            if (mine.size() != theirs.size())
            {
                return false;
            }
            for (final Map.Entry<String, ChatBubbleUiHelper.AvatarMeta> item : mine.entrySet())
            {
                final ChatBubbleUiHelper.AvatarMeta a = item.getValue();
                final ChatBubbleUiHelper.AvatarMeta b = theirs.get(item.getKey());
                if (a == b)
                {
                    continue;
                }
                if (a == null || b == null || a.loadable != b.loadable || a.updateTs != b.updateTs
                    || !Objects.equals(a.path, b.path) || !Objects.equals(a.filename, b.filename))
                {
                    return false;
                }
            }
            return true;
        }
    }

    private static final Object LOCK = new Object();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** Who is typing to us, by public key, and since when. toxcore tells only the open chat otherwise. */
    private static final ConcurrentHashMap<String, Long> TYPING = new ConcurrentHashMap<>();

    private static Handler worker = null;
    private static volatile Listener listener = null;
    /**
     * The open one-to-one chat (redesign, stage 2) hears every change the list hears, so its header
     * and tray follow the same messages without a second set of hooks. Called on the caller's thread;
     * it only schedules its own refresh.
     */
    static volatile Runnable chatWatcher = null;
    /** The screen's context while it is started: its resources carry the language the user picked. */
    private static volatile Context uiContext = null;
    private static volatile boolean dirty = true;
    private static volatile Data latest = Data.EMPTY;
    /** Worker thread only. */
    private static long lastRefreshAt = 0L;
    /** A refresh is posted to the worker and has not started yet (Handler.hasCallbacks needs API 29). */
    private static final AtomicBoolean SCHEDULED = new AtomicBoolean(false);
    private static final AtomicBoolean LOW_SCHEDULED = new AtomicBoolean(false);

    private static final Runnable REFRESH = new Runnable()
    {
        @Override
        public void run()
        {
            SCHEDULED.set(false);
            refreshOnWorker();
        }
    };

    private static final Runnable LOW_REFRESH = new Runnable()
    {
        @Override
        public void run()
        {
            LOW_SCHEDULED.set(false);
            requestRefresh();
        }
    };

    private AirRepository()
    {
    }

    // ---- lifecycle (main thread) ----

    /** The Air screen started: data flows to {@code next} until {@link #stop}. */
    static void start(final Context context, final Listener next)
    {
        uiContext = context;
        listener = next;
        final Data current = latest;
        if (current.loaded)
        {
            next.onAirData(current);
        }
        requestRefreshNow();
    }

    static void stop(final Listener previous)
    {
        if (listener == previous)
        {
            listener = null;
            uiContext = null;
        }
    }

    /** Something changed somewhere: friends, groups, messages, our node. Safe from any thread. */
    static void requestRefresh()
    {
        dirty = true;
        notifyChatWatcher();
        if (listener == null)
        {
            return;
        }
        if (SCHEDULED.compareAndSet(false, true))
        {
            worker().postDelayed(REFRESH, REFRESH_DEBOUNCE_MS);
        }
    }

    /**
     * For changes that come in a stream, such as file transfer progress: at most one refresh every
     * {@link #LOW_PRIORITY_DELAY_MS}, so a long transfer does not rebuild the list several times a second.
     * Safe from any thread.
     */
    static void requestRefreshLowPriority()
    {
        // Transfer progress: nothing the open chat's header or tray shows, so the chat watcher is left out.
        if (listener == null)
        {
            dirty = true;
            return;
        }
        if (LOW_SCHEDULED.compareAndSet(false, true))
        {
            worker().postDelayed(LOW_REFRESH, LOW_PRIORITY_DELAY_MS);
        }
    }

    /** As {@link #requestRefresh()}, without the debounce: the screen just appeared or a minute passed. */
    static void requestRefreshNow()
    {
        dirty = true;
        notifyChatWatcher();
        if (listener == null)
        {
            return;
        }
        final Handler handler = worker();
        handler.removeCallbacks(REFRESH);
        SCHEDULED.set(true);
        handler.post(REFRESH);
    }

    private static void notifyChatWatcher()
    {
        final Runnable watcher = chatWatcher;
        if (watcher != null)
        {
            try
            {
                watcher.run();
            }
            catch (Throwable e)
            {
                Log.i(TAG, "chatWatcher:EE:" + e.getMessage());
            }
        }
    }

    /** From the toxcore typing callback. Only Air needs it for a friend whose chat is not open. */
    static void onFriendTyping(final long friendNumber, final int typing)
    {
        try
        {
            final String publicKey = HelperFriend.tox_friend_get_public_key__wrapper(friendNumber);
            if (TextUtils.isEmpty(publicKey))
            {
                return;
            }
            final String key = publicKey.toUpperCase(Locale.ROOT);
            final boolean changed = typing != 0 ? TYPING.put(key, System.currentTimeMillis()) == null
                                                : TYPING.remove(key) != null;
            if (changed)
            {
                requestRefresh();
            }
        }
        catch (Throwable e)
        {
            Log.i(TAG, "onFriendTyping:EE:" + e.getMessage());
        }
    }

    /** Messages whose text holds {@code query}, newest first; the answer comes through the listener. */
    static void searchMessages(final String query)
    {
        final String needle = query == null ? "" : query.trim();
        final Listener target = listener;
        if (target == null)
        {
            return;
        }
        if (needle.isEmpty())
        {
            target.onAirMessageHits("", Collections.<AirModel.MessageHit>emptyList());
            return;
        }
        worker().post(() -> {
            final List<AirModel.MessageHit> hits = findMessages(needle);
            MAIN.post(() -> {
                final Listener current = listener;
                if (current != null)
                {
                    current.onAirMessageHits(needle, hits);
                }
            });
        });
    }

    private static Handler worker()
    {
        synchronized (LOCK)
        {
            if (worker == null)
            {
                final HandlerThread thread = new HandlerThread("air-repository", Process.THREAD_PRIORITY_BACKGROUND);
                thread.start();
                worker = new Handler(thread.getLooper());
            }
            return worker;
        }
    }

    // ---- building (worker thread) ----

    private static void refreshOnWorker()
    {
        final Context context = uiContext;
        if (listener == null || context == null || !dirty)
        {
            return;
        }
        final long now = System.currentTimeMillis();
        final long sinceLast = now - lastRefreshAt;
        if (sinceLast < REFRESH_MIN_GAP_MS)
        {
            reschedule(REFRESH_MIN_GAP_MS - sinceLast);
            return;
        }
        if (TrifaToxService.orma == null)
        {
            reschedule(RETRY_WITHOUT_DATABASE_MS);
            return;
        }

        dirty = false;
        lastRefreshAt = now;
        final Data data;
        try
        {
            data = build(context, now);
        }
        catch (Throwable e)
        {
            // A half-read database (a profile switching, a migration) must never take the screen down.
            Log.i(TAG, "refresh:EE:" + e);
            dirty = true;
            reschedule(RETRY_WITHOUT_DATABASE_MS);
            return;
        }
        latest = data;
        MAIN.post(() -> {
            final Listener current = listener;
            if (current != null)
            {
                current.onAirData(data);
            }
        });
    }

    private static void reschedule(final long delayMs)
    {
        if (SCHEDULED.compareAndSet(false, true))
        {
            worker().postDelayed(REFRESH, delayMs);
        }
    }

    /** Our own node came online less than a minute ago, so a friend's link may still be on its way. */
    static boolean selfLinkSettling(final long now)
    {
        return TRIFAGlobals.global_self_connection_status != 0
               && TRIFAGlobals.global_self_last_went_online_timestamp > 0L
               && now - TRIFAGlobals.global_self_last_went_online_timestamp < SELF_LINK_SETTLING_MS;
    }

    /**
     * How a friend can be reached right now, by the rules of the list rows: the chat header says the
     * same as the row it was opened from. Reads the database, so call it off the main thread.
     */
    static AirModel.Reach friendReachNow(final FriendList friend)
    {
        if (friend == null || TextUtils.isEmpty(friend.tox_public_key_string))
        {
            return AirModel.Reach.AWAY_LONG_AGO;
        }
        final long now = System.currentTimeMillis();
        return friendReach(friend, friend.tox_public_key_string, now / 1000L, selfLinkSettling(now));
    }

    private static Data build(final Context context, final long now)
    {
        final long nowSeconds = now / 1000L;
        final boolean selfLinkSettling = selfLinkSettling(now);

        final List<AirModel.Entry> entries = new ArrayList<>();
        final Map<String, ChatBubbleUiHelper.AvatarMeta> photos = new HashMap<>();
        final Map<String, Source> sources = new HashMap<>();
        final Set<String> friendKeys = new HashSet<>();
        final Map<String, FriendList> friendsByKey = new HashMap<>();

        final Map<String, Integer> directTransit = directTransitCounts();
        final Map<String, Integer> groupTransit = groupTransitCounts();

        final List<FriendList> friends = TrifaToxService.orma.selectFromFriendList().is_relayNotEq(true).toList();
        for (final FriendList friend : friends)
        {
            final String publicKey = friend.tox_public_key_string;
            if (TextUtils.isEmpty(publicKey) || HelperFriend.is_own_public_key(publicKey)
                || FavoritesChatHelper.isFavoritesChat(publicKey))
            {
                continue;
            }
            friendKeys.add(publicKey.toUpperCase(Locale.ROOT));
            friendsByKey.put(publicKey.toUpperCase(Locale.ROOT), friend);
            final AirModel.Entry entry = friendEntry(context, friend, publicKey, now, nowSeconds, selfLinkSettling,
                                                     count(directTransit, publicKey), photos);
            entries.add(entry);
            sources.put(entry.id, new Source(friend, null, null));
        }

        final List<GroupDB> groups = TrifaToxService.orma.selectFromGroupDB().toList();
        final Set<String> memberOf = new HashSet<>();
        for (final GroupDB group : groups)
        {
            if (TextUtils.isEmpty(group.group_identifier)
                || HelperGroup.is_removed_legacy_public_community_group(group.group_identifier))
            {
                continue;
            }
            final AirModel.Entry entry = groupEntry(context, group,
                                                    count(groupTransit, group.group_identifier.toLowerCase(Locale.ROOT)));
            entries.add(entry);
            sources.put(entry.id, new Source(null, group, null));
            try
            {
                // In toxcore means a member; a group we left keeps its row but takes new invitations.
                if (HelperGroup.tox_group_by_groupid__wrapper(group.group_identifier) >= 0)
                {
                    memberOf.add(group.group_identifier);
                }
            }
            catch (Throwable ignored)
            {
                // The native side may not be up yet: the cards wait for the next build.
            }
        }

        final List<ConferenceDB> conferences = TrifaToxService.orma.selectFromConferenceDB().toList();
        for (final ConferenceDB conference : conferences)
        {
            if (TextUtils.isEmpty(conference.conference_identifier))
            {
                continue;
            }
            final AirModel.Entry entry = conferenceEntry(context, conference);
            entries.add(entry);
            sources.put(entry.id, new Source(null, null, conference));
        }

        entries.add(savedEntry(context));

        // A request from someone who is a contact by now (accepted here or on another path) is answered.
        AirRequestStore.forget(friendKeys);
        final List<AirModel.Request> requests = new ArrayList<>();
        for (final AirRequestStore.Pending pending : AirRequestStore.snapshot())
        {
            requests.add(new AirModel.Request(pending.publicKey, pending.message,
                                              ChatListUiHelper.format_chat_list_time(context, pending.receivedMs),
                                              pending.receivedMs));
        }
        // E12: invitations to groups wait beside the requests; Join goes through the contact who invites.
        // One to a group we are in by now (joined from another card or by ID) is answered.
        AirInviteStore.forgetGroups(memberOf);
        for (final AirInviteStore.Invite invite : AirInviteStore.snapshot())
        {
            final FriendList inviter = friendsByKey.get(invite.inviterKey);
            String inviterName = inviter == null ? "" : HelperFriend.display_name_from_friendlist(inviter, invite.inviterKey);
            if (TextUtils.isEmpty(inviterName))
            {
                inviterName = HelperFriend.peer_pubkey_short_id(invite.inviterKey);
            }
            // The same presence the contact's own row shows, so the card never says "once online" beside
            // a row that says "online".
            requests.add(AirModel.Request.invite(invite.id, invite.inviterKey, invite.groupName, inviterName,
                                                 FriendPresenceHelper.is_friend_online(inviter),
                                                 ChatListUiHelper.format_chat_list_time(context, invite.receivedMs),
                                                 invite.receivedMs));
        }

        final String name = TRIFAGlobals.global_my_name == null ? "" : TRIFAGlobals.global_my_name.trim();
        return new Data(entries, requests,
                        AirModel.NetworkState.from(TRIFAGlobals.global_self_connection_status,
                                                   TRIFAGlobals.HAVE_INTERNET_CONNECTIVITY,
                                                   NetworkConnectivityMonitor.defaultNetworkLost()),
                        name, ProfileTabAvatarHelper.ownPhoto(), photos, sources, true);
    }

    private static AirModel.Entry friendEntry(final Context context, final FriendList friend, final String publicKey,
                                              final long now, final long nowSeconds, final boolean selfLinkSettling,
                                              final int transitCount,
                                              final Map<String, ChatBubbleUiHelper.AvatarMeta> photos)
    {
        String title = HelperFriend.display_name_from_friendlist(friend, publicKey);
        if (TextUtils.isEmpty(title))
        {
            title = HelperFriend.peer_pubkey_short_id(publicKey);
        }

        final AirModel.Reach reach = friendReach(friend, publicKey, nowSeconds, selfLinkSettling);
        final Message last = lastDirectMessage(publicKey);
        final long activity = last == null ? 0L : effectiveTimeMs(last);
        final String draft = ChatDraftHelper.load_friend_draft(publicKey);

        String preview = "";
        AirModel.PreviewStyle style = AirModel.PreviewStyle.MESSAGE;
        if (isTyping(publicKey, now))
        {
            preview = context.getString(R.string.chat_presence_typing);
            style = AirModel.PreviewStyle.TYPING;
        }
        else if (!TextUtils.isEmpty(draft))
        {
            preview = ChatDraftHelper.format_draft_preview(context, draft);
            style = AirModel.PreviewStyle.DRAFT;
        }
        else if (last != null)
        {
            preview = ChatListUiHelper.air_message_body(context, HelperCall.shownText(context, last),
                                                        last.filename_fullpath);
        }
        else
        {
            preview = context.getString(reach.kind == AirModel.Reach.Kind.PENDING ? R.string.air_hint_request_sent
                                                                                 : R.string.air_hint_no_messages);
            style = AirModel.PreviewStyle.HINT;
        }

        String photoKey = null;
        final ChatBubbleUiHelper.AvatarMeta photo = ChatBubbleUiHelper.peer_avatar_meta(friend);
        if (photo.loadable)
        {
            // The update time is part of the key, so a new photo rebinds the row.
            photoKey = publicKey + "@" + photo.updateTs;
            photos.put(photoKey, photo);
        }

        final boolean hasConversation = last != null || !TextUtils.isEmpty(draft);
        return new AirModel.Entry(FRIEND_PREFIX + publicKey,
                                  hasConversation ? AirModel.EntryKind.DIRECT : AirModel.EntryKind.CONTACT,
                                  title, reach, preview, style,
                                  last == null ? null : directDelivery(last, reach.isLive()),
                                  last == null ? "" : ChatListUiHelper.format_chat_list_time(context, activity),
                                  activity, reach.messagesInTransit(transitCount),
                                  ChatListUiHelper.cached_friend_unread_count(publicKey),
                                  ChatFavoritesHelper.isFavorite(context, ChatFavoritesHelper.friendKey(publicKey)),
                                  photoKey, AirModel.Tone.index(publicKey));
    }

    private static AirModel.Reach friendReach(final FriendList friend, final String publicKey, final long nowSeconds,
                                              final boolean selfLinkSettling)
    {
        if (FriendPresenceHelper.is_friend_online(friend))
        {
            return AirModel.Reach.friend(friend.TOX_CONNECTION == AirModel.TOX_CONNECTION_UDP
                                         ? AirModel.TOX_CONNECTION_UDP : AirModel.TOX_CONNECTION_TCP,
                                         0L, nowSeconds, selfLinkSettling);
        }
        if (neverConnected(friend, publicKey))
        {
            return AirModel.Reach.PENDING;
        }
        final long lastSeenMs = FriendPresenceHelper.resolve_last_seen_ms(friend);
        if (lastSeenMs <= 0L)
        {
            return AirModel.Reach.AWAY_LONG_AGO;
        }
        return AirModel.Reach.friend(AirModel.TOX_CONNECTION_NONE, lastSeenMs / 1000L, nowSeconds, selfLinkSettling);
    }

    /**
     * Added, and the other side never answered: no capabilities ever exchanged, never seen online,
     * and nothing ever received from them. One of these alone can be missing on an old peer.
     */
    private static boolean neverConnected(final FriendList friend, final String publicKey)
    {
        if (friend.capabilities != 0L || friend.msgv3_capability == 1L)
        {
            return false;
        }
        final long seen = friend.last_online_timestamp_real;
        if (seen > 0L && seen != LAST_ONLINE_TIMSTAMP_ONLINE_OFFLINE && seen != LAST_ONLINE_TIMSTAMP_ONLINE_NOW)
        {
            return false;
        }
        return !HelperFriend.has_incoming_dm_messages(publicKey);
    }

    private static boolean isTyping(final String publicKey, final long now)
    {
        final String key = publicKey.toUpperCase(Locale.ROOT);
        final Long since = TYPING.get(key);
        if (since == null)
        {
            return false;
        }
        if (now - since > TYPING_STALE_MS)
        {
            TYPING.remove(key, since);
            return false;
        }
        return true;
    }

    /**
     * The newest message of a chat. An outgoing message keeps rcvd_timestamp at 0 until its receipt, so
     * ordering by that column alone misses what we just sent: ask by both columns and keep the later.
     */
    private static Message lastDirectMessage(final String publicKey)
    {
        final List<Message> bySent = TrifaToxService.orma.selectFromMessage().tox_friendpubkeyEq(publicKey)
                .orderBySent_timestampDesc().limit(1).toList();
        final List<Message> byReceived = TrifaToxService.orma.selectFromMessage().tox_friendpubkeyEq(publicKey)
                .orderByRcvd_timestampDesc().limit(1).toList();
        final Message first = bySent.isEmpty() ? null : bySent.get(0);
        final Message second = byReceived.isEmpty() ? null : byReceived.get(0);
        if (first == null)
        {
            return second;
        }
        if (second == null)
        {
            return first;
        }
        return effectiveTimeMs(second) > effectiveTimeMs(first) ? second : first;
    }

    private static long effectiveTimeMs(final Message message)
    {
        final long precise = Math.max(message.rcvd_timestamp_ms, message.sent_timestamp_ms);
        final long time = precise > 0L ? precise : Math.max(message.rcvd_timestamp, message.sent_timestamp);
        return ChatListUiHelper.normalize_message_timestamp_ms(time);
    }

    /**
     * Null for an incoming message, a call note or a cancelled file: those carry no mark. A text follows
     * the conversation's rule ({@link AirModel.Delivery#ofDirectText}): in transit while the person is
     * away, sent or leaving while they are online.
     */
    static AirModel.Delivery directDelivery(final Message message, final boolean personOnline)
    {
        if (message.direction != 1 || HelperMessageEdit.isLocalCallLogRow(message))
        {
            return null;
        }
        if (message.TRIFA_MESSAGE_TYPE == TRIFA_MSG_FILE.value)
        {
            // The outgoing file holders' states: waiting for the other side is a clock, accepted or
            // running is one check, finished is two; a cancelled transfer has no mark.
            if (message.state == TOX_FILE_CONTROL_CANCEL.value)
            {
                return message.filedb_id == -1 ? null : AirModel.Delivery.DELIVERED;
            }
            if (isFileInTransit(message))
            {
                return AirModel.Delivery.TRANSIT;
            }
            return AirModel.Delivery.SENT;
        }
        return AirModel.Delivery.ofDirectText(message.read, MessageDeliveryRetryHelper.isDirectFailed(message),
                                              message.message_id, personOnline);
    }

    /**
     * One of our 1:1 texts that goes on its own once the person is online: no receipt yet, and the
     * delivery watchdog has not given up on it (that one says "not sent" and offers Retry). Call notes,
     * notes to yourself and system rows never wait. The conversation's tray and the list's count both
     * use it, and both only while the person is away.
     */
    static boolean isWaitingDirectText(final Message m)
    {
        return m != null && m.direction == 1 && m.TRIFA_MESSAGE_TYPE == TRIFA_MSG_TYPE_TEXT.value && !m.read
               && !MessageDeliveryRetryHelper.isDirectFailed(m) && !HelperMessageEdit.isLocalCallLogRow(m)
               && !TextUtils.isEmpty(m.tox_friendpubkey) && !FavoritesChatHelper.isFavoritesChat(m.tox_friendpubkey)
               && !TRIFA_SYSTEM_MESSAGE_PEER_PUBKEY.equals(m.tox_friendpubkey);
    }

    static AirModel.Delivery groupDelivery(final GroupMessage message)
    {
        if (message == null || message.direction != 1)
        {
            return null;
        }
        return mapStatus(MessageStatusHelper.resolveGroupOutgoing(message));
    }

    private static AirModel.Delivery mapStatus(final MessageStatusHelper.OutgoingStatus status)
    {
        switch (status)
        {
            case SENDING:
            case NOT_DELIVERED:
            case FAILED:
                return AirModel.Delivery.TRANSIT;
            case DELIVERED:
            case READ:
                return AirModel.Delivery.DELIVERED;
            case SENT:
            default:
                return AirModel.Delivery.SENT;
        }
    }

    private static boolean isFileInTransit(final Message message)
    {
        if (message.state == TOX_FILE_CONTROL_CANCEL.value)
        {
            return false;
        }
        return message.ft_outgoing_queued || (message.state == TOX_FILE_CONTROL_PAUSE.value && !message.ft_accepted);
    }

    /**
     * Our 1:1 messages and files that have not reached the person yet, counted per public key: texts
     * without a receipt ({@link #isWaitingDirectText}) and files the other side has not taken. A row
     * shows the count only while the person is away ({@link AirModel.Reach#messagesInTransit}), as the
     * conversation's tray does.
     */
    private static Map<String, Integer> directTransitCounts()
    {
        final Map<String, Set<Long>> byKey = new HashMap<>();
        // Text without a receipt, whether toxcore has taken it or not: it waits for the person.
        collectDirectTransit(byKey, TrifaToxService.orma.selectFromMessage().directionEq(1)
                .TRIFA_MESSAGE_TYPEEq(TRIFA_MSG_TYPE_TEXT.value).readEq(false)
                .limit(TRANSIT_ROWS_MAX).toList());
        // Files the other side has not accepted yet, and files still queued behind another transfer.
        collectDirectTransit(byKey, TrifaToxService.orma.selectFromMessage().directionEq(1)
                .TRIFA_MESSAGE_TYPEEq(TRIFA_MSG_FILE.value).stateEq(TOX_FILE_CONTROL_PAUSE.value)
                .ft_acceptedEq(false).limit(TRANSIT_ROWS_MAX).toList());
        collectDirectTransit(byKey, TrifaToxService.orma.selectFromMessage().directionEq(1)
                .TRIFA_MESSAGE_TYPEEq(TRIFA_MSG_FILE.value).ft_outgoing_queuedEq(true)
                .stateNotEq(TOX_FILE_CONTROL_CANCEL.value).limit(TRANSIT_ROWS_MAX).toList());
        return sizes(byKey);
    }

    private static void collectDirectTransit(final Map<String, Set<Long>> byKey, final List<Message> rows)
    {
        for (final Message message : rows)
        {
            final boolean waiting = message.TRIFA_MESSAGE_TYPE == TRIFA_MSG_FILE.value
                                    ? isFileInTransit(message) && !HelperMessageEdit.isLocalCallLogRow(message)
                                      && !TextUtils.isEmpty(message.tox_friendpubkey)
                                      && !FavoritesChatHelper.isFavoritesChat(message.tox_friendpubkey)
                                    : isWaitingDirectText(message);
            if (!waiting)
            {
                continue;
            }
            final String key = message.tox_friendpubkey.toUpperCase(Locale.ROOT);
            Set<Long> ids = byKey.get(key);
            if (ids == null)
            {
                ids = new HashSet<>();
                byKey.put(key, ids);
            }
            ids.add(message.id);
        }
    }

    /** Our group messages that toxcore has not taken yet (no message id), counted per group id. */
    private static Map<String, Integer> groupTransitCounts()
    {
        final Map<String, Set<Long>> byGroup = new HashMap<>();
        collectGroupTransit(byGroup, TrifaToxService.orma.selectFromGroupMessage().directionEq(1).private_messageEq(0)
                .message_id_toxEq(HelperGroup.PENDING_GROUP_MESSAGE_ID_TOX).limit(TRANSIT_ROWS_MAX).toList());
        collectGroupTransit(byGroup, TrifaToxService.orma.selectFromGroupMessage().directionEq(1).private_messageEq(0)
                .message_id_toxEq("").limit(TRANSIT_ROWS_MAX).toList());
        collectGroupTransit(byGroup, TrifaToxService.orma.selectFromGroupMessage().directionEq(1).private_messageEq(0)
                .message_id_toxIsNull().limit(TRANSIT_ROWS_MAX).toList());
        return sizes(byGroup);
    }

    private static void collectGroupTransit(final Map<String, Set<Long>> byGroup, final List<GroupMessage> rows)
    {
        for (final GroupMessage message : rows)
        {
            if (TextUtils.isEmpty(message.group_identifier) || groupDelivery(message) != AirModel.Delivery.TRANSIT)
            {
                continue;
            }
            final String key = message.group_identifier.toLowerCase(Locale.ROOT);
            Set<Long> ids = byGroup.get(key);
            if (ids == null)
            {
                ids = new HashSet<>();
                byGroup.put(key, ids);
            }
            ids.add(message.id);
        }
    }

    private static Map<String, Integer> sizes(final Map<String, Set<Long>> sets)
    {
        final Map<String, Integer> result = new HashMap<>();
        for (final Map.Entry<String, Set<Long>> entry : sets.entrySet())
        {
            result.put(entry.getKey(), entry.getValue().size());
        }
        return result;
    }

    private static int count(final Map<String, Integer> counts, final String key)
    {
        final Integer value = counts.get(key.toUpperCase(Locale.ROOT));
        if (value != null)
        {
            return value;
        }
        final Integer lower = counts.get(key.toLowerCase(Locale.ROOT));
        return lower == null ? 0 : lower;
    }

    private static AirModel.Entry groupEntry(final Context context, final GroupDB group, final int transitCount)
    {
        final String groupId = group.group_identifier;
        final long groupNumber = HelperGroup.tox_group_by_groupid__wrapper(groupId);
        String title = HelperGroup.get_effective_group_title(groupNumber, groupId);
        if (TextUtils.isEmpty(title))
        {
            title = TextUtils.isEmpty(group.name) ? groupId.substring(0, Math.min(8, groupId.length())) : group.name;
        }

        int online = 0;
        final AirLiveOverride.Source standIn = AirLiveOverride.group(groupId);
        try
        {
            if (standIn != null)
            {
                if (standIn.groupConnected(groupId))
                {
                    online = (int) Math.max(0L, standIn.groupCounts(groupId)[0] - 1L);
                }
            }
            else if (group.group_active && groupNumber >= 0
                && MainActivity.tox_group_is_connected(groupNumber)
                   == TRIFAGlobals.TOX_GROUP_CONNECTION_STATUS.TOX_GROUP_CONNECTION_STATUS_CONNECTED.value)
            {
                // The count includes us.
                online = (int) Math.max(0L, HelperGroup.count_authoritative_group_members(groupId)[0] - 1L);
            }
        }
        catch (Throwable ignored)
        {
            // The native group may be gone while the row is still in the database.
        }

        final GroupMessage last = ChatListUiHelper.group_last_message(groupId);
        final long activity = last == null ? 0L : ChatListUiHelper.group_last_message_timestamp_ms(groupId);
        final String draft = ChatDraftHelper.load_group_draft(groupId);
        String preview = "";
        AirModel.PreviewStyle style = AirModel.PreviewStyle.MESSAGE;
        if (!TextUtils.isEmpty(draft))
        {
            preview = ChatDraftHelper.format_draft_preview(context, draft);
            style = AirModel.PreviewStyle.DRAFT;
        }
        else if (last != null)
        {
            preview = ChatListUiHelper.air_group_preview(context, last);
        }

        return new AirModel.Entry(GROUP_PREFIX + groupId, AirModel.EntryKind.GROUP, title, AirModel.Reach.group(online),
                                  preview, style, groupDelivery(last),
                                  activity > 0L ? ChatListUiHelper.format_chat_list_time(context, activity) : "",
                                  activity, transitCount, ChatListUiHelper.cached_group_unread_count(groupId),
                                  ChatFavoritesHelper.isFavorite(context, ChatFavoritesHelper.groupKey(groupId)),
                                  null, AirModel.Tone.index(groupId));
    }

    private static AirModel.Entry conferenceEntry(final Context context, final ConferenceDB conference)
    {
        final String conferenceId = conference.conference_identifier;
        String title = conference.name;
        if (TextUtils.isEmpty(title) || "-1".equals(title))
        {
            title = HelperConference.get_conference_title_from_confid(conferenceId);
        }
        if (TextUtils.isEmpty(title) || "-1".equals(title))
        {
            title = conferenceId.substring(0, Math.min(8, conferenceId.length()));
        }

        final ConferenceMessage last = lastConferenceMessage(conferenceId);
        final long activity = last == null ? 0L : ChatListUiHelper.normalize_message_timestamp_ms(
                Math.max(last.rcvd_timestamp, last.sent_timestamp));
        String preview = "";
        if (last != null)
        {
            final String body = ChatListUiHelper.air_message_body(context, last.text, null);
            preview = last.direction == 1 || TextUtils.isEmpty(last.tox_peername) || "-1".equals(last.tox_peername)
                      ? body : context.getString(R.string.chat_list_preview_sender, last.tox_peername, body);
        }

        final int online = conference.conference_active ? (int) Math.max(0L, conference.peer_count - 1L) : 0;
        return new AirModel.Entry(CONFERENCE_PREFIX + conferenceId, AirModel.EntryKind.GROUP, title,
                                  AirModel.Reach.group(online), preview, AirModel.PreviewStyle.MESSAGE, null,
                                  activity > 0L ? ChatListUiHelper.format_chat_list_time(context, activity) : "",
                                  activity, 0, ChatListUiHelper.cached_conference_unread_count(conferenceId),
                                  ChatFavoritesHelper.isFavorite(context, ChatFavoritesHelper.conferenceKey(conferenceId)),
                                  null, AirModel.Tone.index(conferenceId));
    }

    private static ConferenceMessage lastConferenceMessage(final String conferenceId)
    {
        final List<ConferenceMessage> bySent = TrifaToxService.orma.selectFromConferenceMessage()
                .conference_identifierEq(conferenceId).orderBySent_timestampDesc().limit(1).toList();
        final List<ConferenceMessage> byReceived = TrifaToxService.orma.selectFromConferenceMessage()
                .conference_identifierEq(conferenceId).orderByRcvd_timestampDesc().limit(1).toList();
        final ConferenceMessage first = bySent.isEmpty() ? null : bySent.get(0);
        final ConferenceMessage second = byReceived.isEmpty() ? null : byReceived.get(0);
        if (first == null)
        {
            return second;
        }
        if (second == null)
        {
            return first;
        }
        return Math.max(second.rcvd_timestamp, second.sent_timestamp) > Math.max(first.rcvd_timestamp, first.sent_timestamp)
               ? second : first;
    }

    private static AirModel.Entry savedEntry(final Context context)
    {
        final Message last = lastDirectMessage(FavoritesChatHelper.CHAT_ID);
        final long activity = last == null ? 0L : effectiveTimeMs(last);
        final String preview;
        final AirModel.PreviewStyle style;
        if (last != null)
        {
            preview = ChatListUiHelper.air_message_body(context, last.text, last.filename_fullpath);
            style = AirModel.PreviewStyle.MESSAGE;
        }
        else
        {
            preview = context.getString(R.string.air_hint_saved);
            style = AirModel.PreviewStyle.HINT;
        }
        return new AirModel.Entry(SAVED_ID, AirModel.EntryKind.SAVED, context.getString(R.string.favorites_chat_title),
                                  AirModel.Reach.LOCAL, preview, style, null,
                                  activity > 0L ? ChatListUiHelper.format_chat_list_time(context, activity) : "",
                                  activity, 0, FavoritesChatHelper.unreadCount(), false, null, 0);
    }

    // ---- message search (worker thread) ----

    private static final class Found
    {
        final String id;
        final String chatId;
        final String text;
        final long timeMs;

        Found(final String id, final String chatId, final String text, final long timeMs)
        {
            this.id = id;
            this.chatId = chatId;
            this.text = text;
            this.timeMs = timeMs;
        }
    }

    private static List<AirModel.MessageHit> findMessages(final String needle)
    {
        final Context context = uiContext;
        if (context == null || TrifaToxService.orma == null)
        {
            return Collections.emptyList();
        }
        final Data data = latest;
        final List<Found> found = new ArrayList<>();
        try
        {
            final String pattern = HelperGeneric.get_sqlite_search_string(needle);
            for (final Message message : TrifaToxService.orma.selectFromMessage().textLike(pattern)
                    .orderBySent_timestampDesc().limit(MESSAGE_HITS_MAX).toList())
            {
                if (TextUtils.isEmpty(message.tox_friendpubkey) || HelperMessageEdit.isLocalCallLogRow(message))
                {
                    continue;
                }
                final String chatId = FavoritesChatHelper.isFavoritesChat(message.tox_friendpubkey)
                                      ? SAVED_ID : FRIEND_PREFIX + message.tox_friendpubkey;
                found.add(new Found("m:" + message.id, chatId, message.text, effectiveTimeMs(message)));
            }
            for (final GroupMessage message : TrifaToxService.orma.selectFromGroupMessage().textLike(pattern)
                    .orderBySent_timestampDesc().limit(MESSAGE_HITS_MAX).toList())
            {
                if (TextUtils.isEmpty(message.group_identifier))
                {
                    continue;
                }
                found.add(new Found("gm:" + message.id, GROUP_PREFIX + message.group_identifier, message.text,
                                    GroupMessageLayoutHelper.effectiveSortTimestampMs(message)));
            }
        }
        catch (Throwable e)
        {
            // A read-only search never takes the screen down.
            Log.i(TAG, "findMessages:EE:" + e.getMessage());
            return Collections.emptyList();
        }

        Collections.sort(found, new Comparator<Found>()
        {
            @Override
            public int compare(final Found first, final Found second)
            {
                return Long.compare(second.timeMs, first.timeMs);
            }
        });

        final List<AirModel.MessageHit> hits = new ArrayList<>();
        for (final Found item : found)
        {
            if (hits.size() >= MESSAGE_HITS_MAX)
            {
                break;
            }
            final AirModel.Entry chat = data.entry(chatIdOf(data, item.chatId));
            final String snippet = snippet(item.text);
            if (chat == null || snippet.isEmpty())
            {
                // A message of a removed chat cannot be opened; markup alone is nothing to show.
                continue;
            }
            hits.add(new AirModel.MessageHit(item.id, chat.id, chat.title, snippet,
                                             ChatListUiHelper.format_chat_list_time(context, item.timeMs)));
        }
        return hits;
    }

    /** Entry ids carry the key as stored for the friend or group; a message may spell it in another case. */
    private static String chatIdOf(final Data data, final String chatId)
    {
        if (data.entry(chatId) != null)
        {
            return chatId;
        }
        for (final AirModel.Entry entry : data.entries)
        {
            if (entry.id.equalsIgnoreCase(chatId))
            {
                return entry.id;
            }
        }
        return chatId;
    }

    private static String snippet(final String text)
    {
        final String body = ChatListUiHelper.sanitize_preview_body(text);
        if (TextUtils.isEmpty(body))
        {
            return "";
        }
        final String single = body.replace('\n', ' ').replace('\r', ' ').trim();
        if (single.length() <= SNIPPET_MAX_CHARS)
        {
            return single;
        }
        int end = SNIPPET_MAX_CHARS;
        if (Character.isHighSurrogate(single.charAt(end - 1)))
        {
            end--;
        }
        return single.substring(0, end);
    }
}
