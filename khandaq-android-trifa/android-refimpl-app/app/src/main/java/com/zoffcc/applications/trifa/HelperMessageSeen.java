package com.zoffcc.applications.trifa;

import android.app.KeyguardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import com.zoffcc.applications.sorm.FriendList;
import com.zoffcc.applications.sorm.Message;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.zoffcc.applications.trifa.TRIFAGlobals.TRIFA_MSG_TYPE.TRIFA_MSG_FILE;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TRIFA_MSG_TYPE.TRIFA_MSG_TYPE_TEXT;
import static com.zoffcc.applications.trifa.TRIFAGlobals.UINT32_MAX_JAVA;
import static com.zoffcc.applications.trifa.ToxVars.TOX_CONNECTION.TOX_CONNECTION_NONE;

/**
 * KHANDAQ (0.2.57): read receipts ("seen") between two people, protocol v1, byte for byte the same on iOS
 * and the desktop. Message.read stays what it was, the msgV3 receipt: delivered, never seen.
 *
 *   1:1 friend lossless packet (pkt id 189, "KQ" magic), exactly 41 bytes:
 *     [0]=0xBD  [1..2]='K','Q'  [3]=version(1)  [4]=anchor type (1 = text: msgV3 id, 2 = file: Tox file id)
 *     [5..36]=anchor id (32 bytes)  [37..40]=seen time, the reader's clock, unix seconds u32 BE (information only)
 *
 * It says "I have seen every message and file you sent me, up to and including the anchored one". The reader
 * names the newest incoming item that has an anchor while the conversation is resumed on an unlocked, lit
 * screen: at most one packet a second per friend, a later anchor replacing one that waits, and an anchor that
 * cannot go (friend offline, send refused) kept in g_opts until the friend is connected again. The sender finds
 * its OWN outgoing item with that anchor and moves the conversation's watermark to that item's sent time; it
 * never moves back, lives in g_opts, and every item at or below it that left this device shows as seen.
 * Anything else (another length, magic, version or anchor type, an anchor that is not ours) is dropped with a
 * debug line. Groups, Saved and friend requests have no seen state. Android up to 0.2.56 registers no
 * callback for 189 (MainActivity.register_khandaq_lossless_pktids took 184-188), so toxcore drops it there.
 */
final class HelperMessageSeen
{
    private static final String TAG = "trifa.HelperMsgSeen";

    // 1:1 friend lossless packet id (160..191; 186 = edits, 187 = deletes, 188 = reactions, 189 was free)
    static final int PKT_MSG_SEEN = 189;
    static final int PKT_LEN = 1 + 2 + 1 + 1 + 32 + 4; // 41
    static final int ANCHOR_TEXT = 1;
    static final int ANCHOR_FILE = 2;
    private static final byte MAGIC_1 = 'K';
    private static final byte MAGIC_2 = 'Q';
    private static final byte VERSION = 1;
    private static final int ANCHOR_BYTES = 32;

    /** g_opts key prefixes, by upper-case public key: how far the person has seen our items (ms of our clock). */
    static final String WATERMARK_KEY_PREFIX = "kqseen_w_";
    /** The anchor that waits for the person: "type:id:seen seconds". */
    static final String PENDING_KEY_PREFIX = "kqseen_p_";

    /** At most one packet per friend in this long; a later anchor replaces the one that waits meanwhile. */
    static final long MIN_GAP_MS = 1000L;
    /** A send toxcore refused while the friend looked online goes again this much later, a few times. */
    static final long RETRY_MS = 2000L;
    static final int MAX_RETRIES = 3;
    /** A new incoming row waits this long before the open chat looks for its newest anchor: bursts are one. */
    private static final long LOOK_DELAY_MS = 250L;
    /** Rows read, newest first, to find one with an anchor: call notes and texts of old clients have none. */
    private static final int NEWEST_SCAN = 50;

    private HelperMessageSeen()
    {
    }

    // =============================================================================================
    // the packet
    // =============================================================================================

    /** What one packet carries. {@code idHex} is 64 upper-case hex digits. */
    static final class Anchor
    {
        final int type;
        final String idHex;
        final long seenSec;

        Anchor(final int type, final String idHex, final long seenSec)
        {
            this.type = type;
            this.idHex = idHex;
            this.seenSec = seenSec;
        }

        /** The first eight digits, enough for a log line. */
        String shortId()
        {
            return idHex.substring(0, 8);
        }

        @Override
        public boolean equals(final Object other)
        {
            if (!(other instanceof Anchor))
            {
                return false;
            }
            final Anchor that = (Anchor) other;
            return type == that.type && seenSec == that.seenSec && idHex.equals(that.idHex);
        }

        @Override
        public int hashCode()
        {
            return (type * 31 + idHex.hashCode()) * 31 + Long.hashCode(seenSec);
        }

        @Override
        public String toString()
        {
            return "anchor{" + type + ":" + shortId() + "}";
        }
    }

    /** The 41 bytes of a seen packet; null for an anchor type other than 1 or 2 or an id that is not 32 bytes. */
    static byte[] buildPacket(final int type, final String idHex, final long seenSec)
    {
        if (type != ANCHOR_TEXT && type != ANCHOR_FILE)
        {
            return null;
        }
        final byte[] id = hexToBytes(idHex);
        if (id == null || id.length != ANCHOR_BYTES)
        {
            return null;
        }
        final byte[] out = new byte[PKT_LEN];
        out[0] = (byte) PKT_MSG_SEEN;
        out[1] = MAGIC_1;
        out[2] = MAGIC_2;
        out[3] = VERSION;
        out[4] = (byte) type;
        System.arraycopy(id, 0, out, 5, ANCHOR_BYTES);
        final long time = seenSec & 0xFFFFFFFFL;
        out[37] = (byte) (time >>> 24);
        out[38] = (byte) (time >>> 16);
        out[39] = (byte) (time >>> 8);
        out[40] = (byte) time;
        return out;
    }

    /** The anchor of a valid packet; null for any other length, id, magic, version or anchor type. */
    static Anchor parsePacket(final byte[] data, final int length)
    {
        if (dropReason(data, length) != null)
        {
            return null;
        }
        final long time = ((data[37] & 0xFFL) << 24) | ((data[38] & 0xFFL) << 16) | ((data[39] & 0xFFL) << 8)
                          | (data[40] & 0xFFL);
        return new Anchor(data[4], bytesToHex(data, 5, ANCHOR_BYTES), time);
    }

    /** Why {@link #parsePacket} drops these bytes, for the debug log; null when it takes them. */
    static String dropReason(final byte[] data, final int length)
    {
        if (data == null || length != PKT_LEN || data.length < PKT_LEN)
        {
            return "length " + length;
        }
        if (data[0] != (byte) PKT_MSG_SEEN)
        {
            return "id " + (data[0] & 0xFF);
        }
        if (data[1] != MAGIC_1 || data[2] != MAGIC_2)
        {
            return "magic";
        }
        if (data[3] != VERSION)
        {
            return "version " + (data[3] & 0xFF);
        }
        if (data[4] != ANCHOR_TEXT && data[4] != ANCHOR_FILE)
        {
            return "anchor type " + (data[4] & 0xFF);
        }
        return null;
    }

    // =============================================================================================
    // the rules, without toxcore or the database
    // =============================================================================================

    /** Where the watermarks and the waiting anchors live: g_opts in the app, a map in the tests. */
    interface Store
    {
        /** False while the database is not open: nothing is read, cached or written then. */
        boolean ready();

        String get(String key);

        void set(String key, String value);

        void delete(String key);
    }

    /** What the reader needs from toxcore and the clock; the tests drive it with their own. */
    interface Port
    {
        boolean connected(String publicKey);

        /** True when toxcore took the packet. */
        boolean send(String publicKey, byte[] packet);

        /** A monotonic clock, in ms. */
        long now();

        /** Runs {@code work} on the thread the reader lives on, {@code delayMs} from now. */
        void schedule(Runnable work, long delayMs);

        void log(String line);
    }

    /** True when the watermark covers an item sent at {@code sentMs}; nothing is covered before the first packet. */
    static boolean isCovered(final long sentMs, final long watermarkMs)
    {
        return watermarkMs > 0L && sentMs > 0L && sentMs <= watermarkMs;
    }

    /** A 64-digit public key in upper case, or null for anything else (Saved, the system rows' "-1", empty). */
    static String normalize(final String publicKey)
    {
        if (publicKey == null)
        {
            return null;
        }
        final String key = publicKey.trim().toUpperCase(Locale.ROOT);
        return isHex64(key) ? key : null;
    }

    /** Our side: how far each person has seen our items, as the sent time of the newest one they named. */
    static final class Watermarks
    {
        private final Store store;
        private final ConcurrentHashMap<String, Long> known = new ConcurrentHashMap<>();

        Watermarks(final Store store)
        {
            this.store = store;
        }

        /** The watermark of {@code publicKey} in ms of our clock; 0 while the person has seen nothing. */
        long get(final String publicKey)
        {
            final String key = normalize(publicKey);
            if (key == null)
            {
                return 0L;
            }
            final Long cached = known.get(key);
            if (cached != null)
            {
                return cached;
            }
            if (!store.ready())
            {
                return 0L;
            }
            return known.computeIfAbsent(key, this::load);
        }

        /** W = max(W, sentMs). True when it moved; it is stored then. */
        boolean advance(final String publicKey, final long sentMs)
        {
            final String key = normalize(publicKey);
            if (key == null || sentMs <= 0L || !store.ready())
            {
                return false;
            }
            final boolean[] moved = {false};
            known.compute(key, (k, old) -> {
                final long current = old != null ? old : load(k);
                if (sentMs <= current)
                {
                    return current;
                }
                store.set(WATERMARK_KEY_PREFIX + k, Long.toString(sentMs));
                moved[0] = true;
                return sentMs;
            });
            return moved[0];
        }

        /** The contact was removed. */
        void forget(final String publicKey)
        {
            final String key = normalize(publicKey);
            if (key == null)
            {
                return;
            }
            // The store first: a reader in between loads nothing instead of caching the old value again.
            if (store.ready())
            {
                store.delete(WATERMARK_KEY_PREFIX + key);
            }
            known.remove(key);
        }

        private long load(final String key)
        {
            final String value = store.get(WATERMARK_KEY_PREFIX + key);
            if (value == null)
            {
                return 0L;
            }
            try
            {
                return Math.max(0L, Long.parseLong(value.trim()));
            }
            catch (NumberFormatException e)
            {
                return 0L;
            }
        }
    }

    /** One of their items the reader can name: its anchor, and where it stands in the conversation. */
    static final class Candidate
    {
        final int type;
        final String idHex;
        final long sentMs;
        final long sentExtraMs;
        final long rowId;

        private Candidate(final int type, final String idHex, final long sentMs, final long sentExtraMs,
                          final long rowId)
        {
            this.type = type;
            this.idHex = idHex;
            this.sentMs = sentMs;
            this.sentExtraMs = sentExtraMs;
            this.rowId = rowId;
        }

        /** Null for one of ours and for an item without an anchor: a text without a msgV3 id, a call note. */
        static Candidate of(final Message m)
        {
            if (m == null || m.direction != 0 || HelperMessageEdit.isLocalCallLogRow(m))
            {
                return null;
            }
            final int type;
            final String id;
            if (m.TRIFA_MESSAGE_TYPE == TRIFA_MSG_TYPE_TEXT.value)
            {
                type = ANCHOR_TEXT;
                id = m.msg_idv3_hash;
            }
            else if (m.TRIFA_MESSAGE_TYPE == TRIFA_MSG_FILE.value)
            {
                type = ANCHOR_FILE;
                id = m.ft_id_anchor_hex;
            }
            else
            {
                return null;
            }
            if (id == null || !isHex64(id))
            {
                return null;
            }
            return new Candidate(type, id.toUpperCase(Locale.ROOT), m.sent_timestamp, m.sent_timestamp_ms, m.id);
        }

        /** Later in the conversation's order (MessageListFragment: sent time, its extra ms, then the row). */
        boolean isAfter(final Candidate other)
        {
            if (sentMs != other.sentMs)
            {
                return sentMs > other.sentMs;
            }
            if (sentExtraMs != other.sentExtraMs)
            {
                return sentExtraMs > other.sentExtraMs;
            }
            return rowId > other.rowId;
        }
    }

    /** The first item of {@code newestFirst} that has an anchor; null when none has. */
    static Candidate newestCandidate(final List<Message> newestFirst)
    {
        if (newestFirst == null)
        {
            return null;
        }
        for (final Message m : newestFirst)
        {
            final Candidate candidate = Candidate.of(m);
            if (candidate != null)
            {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Our own item among {@code rows} that the anchor names: sent by us, to this person, a text for a text
     * anchor and a file for a file anchor. The newest row when there are several; null when none is ours.
     */
    static Message ownItem(final List<Message> rows, final String publicKey, final Anchor anchor)
    {
        if (rows == null || anchor == null || publicKey == null)
        {
            return null;
        }
        Message own = null;
        for (final Message m : rows)
        {
            if (m == null || m.direction != 1 || !publicKey.equalsIgnoreCase(m.tox_friendpubkey))
            {
                continue;
            }
            final String id;
            if (anchor.type == ANCHOR_TEXT && m.TRIFA_MESSAGE_TYPE == TRIFA_MSG_TYPE_TEXT.value)
            {
                id = m.msg_idv3_hash;
            }
            else if (anchor.type == ANCHOR_FILE && m.TRIFA_MESSAGE_TYPE == TRIFA_MSG_FILE.value)
            {
                id = m.ft_id_anchor_hex;
            }
            else
            {
                continue;
            }
            if (id != null && id.equalsIgnoreCase(anchor.idHex) && (own == null || m.id > own.id))
            {
                own = m;
            }
        }
        return own;
    }

    /**
     * The reader's side, one per app: which anchor waits for whom, and when the last packet went. Not thread
     * safe: the app keeps every call on one thread (the executor of this class), the tests on theirs.
     */
    static final class Reader
    {
        private final Store store;
        private final Port port;
        private final Map<String, Friend> friends = new HashMap<>();

        static final class Friend
        {
            /** Waits to go: inside the one-second gap, for the friend to connect, or after a refused send. */
            Anchor due;
            /** The stored waiting anchor of an earlier run was read. */
            boolean loaded;
            /** What the store holds for this friend, null for nothing. */
            String stored;
            /** Their newest item already handed over, sent or waiting. */
            Candidate last;
            boolean hasSent;
            long lastSentAt;
            boolean timerArmed;
            int failures;
        }

        Reader(final Store store, final Port port)
        {
            this.store = store;
            this.port = port;
        }

        /** The conversation with {@code publicKey} is on screen and {@code newest} is their newest anchored item. */
        void offer(final String publicKey, final Candidate newest, final long seenSec)
        {
            final String key = normalize(publicKey);
            if (key == null || newest == null)
            {
                return;
            }
            final Friend f = friend(key);
            if (f.last != null && !newest.isAfter(f.last))
            {
                return;
            }
            f.last = newest;
            f.due = new Anchor(newest.type, newest.idHex, seenSec);
            f.failures = 0;
            flush(key, f);
        }

        /** The friend came online: the anchor that waits for them goes, from this run or an earlier one. */
        void onFriendOnline(final String publicKey)
        {
            final String key = normalize(publicKey);
            if (key == null)
            {
                return;
            }
            final Friend f = friend(key);
            f.failures = 0;
            flush(key, f);
        }

        /** The contact was removed. */
        void forget(final String publicKey)
        {
            final String key = normalize(publicKey);
            if (key == null)
            {
                return;
            }
            friends.remove(key);
            if (store.ready())
            {
                store.delete(PENDING_KEY_PREFIX + key);
            }
        }

        private Friend friend(final String key)
        {
            Friend f = friends.get(key);
            if (f == null)
            {
                f = new Friend();
                friends.put(key, f);
            }
            if (!f.loaded && store.ready())
            {
                f.loaded = true;
                final String value = store.get(PENDING_KEY_PREFIX + key);
                final Anchor saved = decode(value);
                if (saved != null)
                {
                    f.stored = value;
                    if (f.due == null)
                    {
                        f.due = saved;
                    }
                }
                else if (value != null)
                {
                    store.delete(PENDING_KEY_PREFIX + key);
                }
            }
            return f;
        }

        private void flush(final String key, final Friend f)
        {
            if (f.due == null)
            {
                return;
            }
            if (!port.connected(key))
            {
                keep(key, f);
                port.log("pending (offline) " + f.due);
                return;
            }
            if (f.hasSent)
            {
                final long wait = f.lastSentAt + MIN_GAP_MS - port.now();
                if (wait > 0L)
                {
                    arm(key, f, wait);
                    return;
                }
            }
            final byte[] packet = buildPacket(f.due.type, f.due.idHex, f.due.seenSec);
            if (packet == null)
            {
                f.due = null;
                release(key, f);
                return;
            }
            if (port.send(key, packet))
            {
                port.log("sent " + f.due);
                f.hasSent = true;
                f.lastSentAt = port.now();
                f.due = null;
                f.failures = 0;
                release(key, f);
                return;
            }
            keep(key, f);
            f.failures++;
            port.log("pending (send refused, try " + f.failures + ") " + f.due);
            if (f.failures <= MAX_RETRIES)
            {
                arm(key, f, RETRY_MS);
            }
        }

        private void keep(final String key, final Friend f)
        {
            final String value = encode(f.due);
            if (!value.equals(f.stored) && store.ready())
            {
                store.set(PENDING_KEY_PREFIX + key, value);
                f.stored = value;
            }
        }

        private void release(final String key, final Friend f)
        {
            if (f.stored != null && store.ready())
            {
                store.delete(PENDING_KEY_PREFIX + key);
                f.stored = null;
            }
        }

        private void arm(final String key, final Friend f, final long delayMs)
        {
            if (f.timerArmed)
            {
                return;
            }
            f.timerArmed = true;
            port.schedule(() -> {
                f.timerArmed = false;
                // Removed meanwhile: the contact is gone, and so is what waited for it.
                if (friends.get(key) == f)
                {
                    flush(key, f);
                }
            }, delayMs);
        }

        private static String encode(final Anchor anchor)
        {
            return anchor.type + ":" + anchor.idHex + ":" + anchor.seenSec;
        }

        private static Anchor decode(final String value)
        {
            if (value == null)
            {
                return null;
            }
            final String[] parts = value.split(":");
            if (parts.length != 3 || !isHex64(parts[1]))
            {
                return null;
            }
            try
            {
                final int type = Integer.parseInt(parts[0]);
                final long seenSec = Long.parseLong(parts[2]);
                if ((type != ANCHOR_TEXT && type != ANCHOR_FILE) || seenSec < 0L)
                {
                    return null;
                }
                return new Anchor(type, parts[1].toUpperCase(Locale.ROOT), seenSec);
            }
            catch (NumberFormatException e)
            {
                return null;
            }
        }
    }

    // =============================================================================================
    // the app
    // =============================================================================================

    private static final Store G_OPTS = new Store()
    {
        @Override
        public boolean ready()
        {
            return TrifaToxService.orma != null;
        }

        @Override
        public String get(final String key)
        {
            return HelperGeneric.get_g_opts(key);
        }

        @Override
        public void set(final String key, final String value)
        {
            HelperGeneric.set_g_opts(key, value);
        }

        @Override
        public void delete(final String key)
        {
            HelperGeneric.del_g_opts(key);
        }
    };

    private static final Port TOX = new Port()
    {
        @Override
        public boolean connected(final String publicKey)
        {
            final long friendNumber = HelperFriend.tox_friend_by_public_key__wrapper(publicKey);
            return isFriendNumber(friendNumber)
                   && MainActivity.tox_friend_get_connection_status(friendNumber) != TOX_CONNECTION_NONE.value;
        }

        @Override
        public boolean send(final String publicKey, final byte[] packet)
        {
            final long friendNumber = HelperFriend.tox_friend_by_public_key__wrapper(publicKey);
            return isFriendNumber(friendNumber) && HelperFriend.is_friend_lossless_packet_send_ok(
                    MainActivity.tox_friend_send_lossless_packet(friendNumber, packet, packet.length));
        }

        @Override
        public long now()
        {
            return SystemClock.elapsedRealtime();
        }

        @Override
        public void schedule(final Runnable work, final long delayMs)
        {
            executor().schedule(guarded(work), delayMs, TimeUnit.MILLISECONDS);
        }

        @Override
        public void log(final String line)
        {
            Log.i(TAG, line);
        }
    };

    private static volatile Watermarks watermarks = new Watermarks(G_OPTS);
    /** Executor thread only. */
    private static volatile Reader reader = new Reader(G_OPTS, TOX);

    /** The 1:1 conversation resumed on screen, by upper-case public key; null when none is. */
    private static volatile String shownKey = null;
    private static volatile Context appContext = null;
    /** Conversations with a look on its way: a burst of incoming rows is looked at once. */
    private static final Set<String> LOOKS = ConcurrentHashMap.newKeySet();

    private static final Object EXECUTOR_LOCK = new Object();
    private static ScheduledExecutorService executor = null;
    private static Handler main = null;

    /** Swaps the store and toxcore for the tests' own, with nothing remembered. */
    static void resetForTest(final Store store, final Port port)
    {
        watermarks = new Watermarks(store);
        reader = new Reader(store, port);
        shownKey = null;
        appContext = null;
        LOOKS.clear();
    }

    // ---- the sender's side ----

    /** Incoming 1:1 seen packet, from the friend lossless packet dispatcher (toxcore's thread). */
    static void handleIncomingFriendSeen(final long friend_number, final byte[] data, final int length)
    {
        final Anchor anchor = parsePacket(data, length);
        if (anchor == null)
        {
            Log.d(TAG, "drop: " + dropReason(data, length) + " fn=" + friend_number);
            return;
        }
        final String publicKey = HelperFriend.tox_friend_get_public_key__wrapper(friend_number);
        run(() -> onSeen(friend_number, publicKey, anchor));
    }

    private static void onSeen(final long friendNumber, final String publicKey, final Anchor anchor)
    {
        if (normalize(publicKey) == null || TrifaToxService.orma == null)
        {
            return;
        }
        final List<Message> rows = (anchor.type == ANCHOR_TEXT
                                    ? TrifaToxService.orma.selectFromMessage().msg_idv3_hashEq(anchor.idHex)
                                    : TrifaToxService.orma.selectFromMessage().ft_id_anchor_hexEq(anchor.idHex))
                .tox_friendpubkeyEq(publicKey).directionEq(1).orderByIdDesc().limit(8).toList();
        final Message own = ownItem(rows, publicKey, anchor);
        if (own == null)
        {
            Log.d(TAG, "drop: not ours " + anchor + " fn=" + friendNumber);
            return;
        }
        if (watermarks.advance(publicKey, own.sent_timestamp))
        {
            Log.i(TAG, "seen by fn=" + friendNumber + " up to " + anchor + " sent=" + own.sent_timestamp);
            showSeen(publicKey);
        }
    }

    /**
     * Our own 1:1 item whose sent time the person's watermark covers. Whether it shows as seen also depends on
     * its state: only one that left this device does (AirConversationModel.EntryStatus#seenIf).
     */
    static boolean coveredBySeen(final Message m)
    {
        if (m == null || m.direction != 1)
        {
            return false;
        }
        final String key = normalize(m.tox_friendpubkey);
        return key != null && isCovered(m.sent_timestamp, watermarks.get(key));
    }

    /** The watermark moved: the open conversation redraws its marks, the list its row. */
    private static void showSeen(final String publicKey)
    {
        AirRepository.requestRefresh();
        mainHandler().post(() -> {
            try
            {
                final MessageListActivity chat = MainActivity.message_list_activity;
                final MessageListFragment log = MainActivity.message_list_fragment;
                if (chat != null && log != null && publicKey.equalsIgnoreCase(chat.get_friend_pubkey()))
                {
                    log.refresh_air_entries();
                }
            }
            catch (Exception e)
            {
                Log.i(TAG, "showSeen:EE:" + e.getMessage());
            }
        });
    }

    // ---- the reader's side ----

    /**
     * The 1:1 conversation with {@code publicKey} is resumed, or its window has the focus again (the keyguard
     * went away): from now on what arrives there is looked at. Main thread. Never called for Saved.
     */
    static void onChatShown(final Context context, final String publicKey)
    {
        if (context == null)
        {
            return;
        }
        appContext = context.getApplicationContext();
        final String key = markShown(publicKey);
        if (key != null)
        {
            look(key, 0L);
        }
    }

    /** The conversation with {@code publicKey} is the one on screen; its key, or null for one never seen. */
    static String markShown(final String publicKey)
    {
        final String key = normalize(publicKey);
        if (key != null)
        {
            shownKey = key;
        }
        return key;
    }

    /** The conversation with {@code publicKey} is paused: nothing it receives now counts as seen. */
    static void onChatHidden(final String publicKey)
    {
        final String key = normalize(publicKey);
        if (key != null && key.equals(shownKey))
        {
            shownKey = null;
        }
    }

    /** True while the conversation with {@code publicKey} is the one resumed on screen. */
    static boolean isShown(final String publicKey)
    {
        final String key = normalize(publicKey);
        return key != null && key.equals(shownKey);
    }

    /** A row from the person was stored (HelperMessage.insert_into_message_db); any thread. */
    static void onIncomingItem(final Message m)
    {
        if (m == null || m.direction != 0)
        {
            return;
        }
        final String key = normalize(m.tox_friendpubkey);
        if (key != null && key.equals(shownKey))
        {
            look(key, LOOK_DELAY_MS);
        }
    }

    /** The friend just came online (toxcore's thread): the anchor that waits for them goes. */
    static void onFriendOnline(final long friend_number)
    {
        final String publicKey = normalize(HelperFriend.tox_friend_get_public_key__wrapper(friend_number));
        if (publicKey != null)
        {
            run(() -> reader.onFriendOnline(publicKey));
        }
    }

    /** The contact was removed (FriendListHolder.delete_friend_now): its watermark and waiting anchor go too. */
    static void forgetFriend(final String publicKey)
    {
        final String key = normalize(publicKey);
        if (key == null)
        {
            return;
        }
        onChatHidden(key);
        run(() -> {
            watermarks.forget(key);
            reader.forget(key);
        });
    }

    /**
     * Whether what the conversation shows counts as seen: it is the one resumed, the screen is lit and not
     * locked, and the app's own lock is not up over it.
     */
    static boolean maySee(final boolean shown, final boolean interactive, final boolean keyguardLocked,
                          final boolean appLockUp)
    {
        return shown && interactive && !keyguardLocked && !appLockUp;
    }

    private static void look(final String key, final long delayMs)
    {
        if (!LOOKS.add(key))
        {
            return;
        }
        executor().schedule(guarded(() -> {
            LOOKS.remove(key);
            lookNow(key);
        }), delayMs, TimeUnit.MILLISECONDS);
    }

    /** Executor: the newest anchored item from the person goes to them, if the conversation is really seen. */
    private static void lookNow(final String key)
    {
        final Context context = appContext;
        if (context == null || TrifaToxService.orma == null)
        {
            return;
        }
        final PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        final KeyguardManager keyguard = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        if (!maySee(key.equals(shownKey), power != null && power.isInteractive(),
                    keyguard == null || keyguard.isKeyguardLocked(), AppLockHelper.isGateUp(context)))
        {
            return;
        }
        // A contact, never a request we sent that waits, the relay or our own key.
        final FriendList friend = HelperFriend.main_get_friend(key);
        if (friend == null || friend.tox_public_key_string == null || HelperFriend.is_own_public_key(key)
            || HelperRelay.is_any_relay(key))
        {
            return;
        }
        final Candidate newest = newestCandidate(TrifaToxService.orma.selectFromMessage()
                                                         .tox_friendpubkeyEq(friend.tox_public_key_string)
                                                         .directionEq(0)
                                                         .orderBySent_timestampDesc()
                                                         .orderBySent_timestamp_msDesc()
                                                         .orderByIdDesc()
                                                         .limit(NEWEST_SCAN)
                                                         .toList());
        if (newest != null)
        {
            reader.offer(key, newest, System.currentTimeMillis() / 1000L);
        }
    }

    private static void run(final Runnable work)
    {
        executor().execute(guarded(work));
    }

    /** A failing step must never take the executor's later work down with it. */
    static Runnable guarded(final Runnable work)
    {
        return () -> {
            try
            {
                work.run();
            }
            catch (Throwable e)
            {
                Log.i(TAG, "EE:" + e.getMessage());
            }
        };
    }

    static ScheduledExecutorService executor()
    {
        synchronized (EXECUTOR_LOCK)
        {
            if (executor == null)
            {
                executor = Executors.newSingleThreadScheduledExecutor(work -> {
                    final Thread thread = new Thread(work, "kq-seen");
                    thread.setDaemon(true);
                    return thread;
                });
            }
            return executor;
        }
    }

    private static Handler mainHandler()
    {
        synchronized (EXECUTOR_LOCK)
        {
            if (main == null)
            {
                main = new Handler(Looper.getMainLooper());
            }
            return main;
        }
    }

    // ---------------------------------- utils ----------------------------------

    /** toxcore knows the friend: neither negative nor its not-found answer (UINT32_MAX). */
    static boolean isFriendNumber(final long friendNumber)
    {
        return friendNumber >= 0 && friendNumber != UINT32_MAX_JAVA;
    }

    static boolean isHex64(final String s)
    {
        if (s == null || s.length() != ANCHOR_BYTES * 2)
        {
            return false;
        }
        for (int i = 0; i < s.length(); i++)
        {
            if (Character.digit(s.charAt(i), 16) < 0)
            {
                return false;
            }
        }
        return true;
    }

    private static byte[] hexToBytes(final String s)
    {
        if (s == null || s.length() % 2 != 0)
        {
            return null;
        }
        final byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < s.length(); i += 2)
        {
            final int high = Character.digit(s.charAt(i), 16);
            final int low = Character.digit(s.charAt(i + 1), 16);
            if (high < 0 || low < 0)
            {
                return null;
            }
            out[i / 2] = (byte) ((high << 4) + low);
        }
        return out;
    }

    private static String bytesToHex(final byte[] data, final int start, final int length)
    {
        final char[] digits = "0123456789ABCDEF".toCharArray();
        final char[] out = new char[length * 2];
        for (int i = 0; i < length; i++)
        {
            final int v = data[start + i] & 0xFF;
            out[i * 2] = digits[v >>> 4];
            out[i * 2 + 1] = digits[v & 0x0F];
        }
        return new String(out);
    }
}
