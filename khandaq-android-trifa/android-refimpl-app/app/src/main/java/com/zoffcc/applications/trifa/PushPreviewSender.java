package com.zoffcc.applications.trifa;

import android.content.Context;
import android.util.Log;

import com.zoffcc.applications.sorm.GroupDB;
import com.zoffcc.applications.sorm.GroupMessage;
import com.zoffcc.applications.sorm.Message;

import org.khandaq.messenger.KhandaqPush;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.zoffcc.applications.trifa.TRIFAGlobals.TRIFA_MSG_TYPE.TRIFA_MSG_FILE;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TRIFA_MSG_TYPE.TRIFA_MSG_TYPE_TEXT;
import static com.zoffcc.applications.trifa.TrifaToxService.orma;

/**
 * KHANDAQ (0.2.64): what a wake push says about the event it is for (docs/PUSH_PREVIEWS.md, "Who sends a preview,
 * and when"). A text or a file to a friend who is offline: the wake it gets anyway carries a preview of it. A
 * reaction added to a friend's message while the friend is offline, or to a group message whose author is a friend
 * who is offline: one wake of its own, no retries. All of it needs the key K the friend handed us (PushPreviewPeers);
 * without one a message's wake goes as it always did, and a reaction wakes nobody. Calls never carry a preview.
 */
final class PushPreviewSender
{
    private static final String TAG = "trifa.PushPreviewSend";

    /**
     * A reaction wakes a few seconds after the tap, and only when it is still there then: a reaction taken back or
     * changed at once wakes nobody, or only for what it became.
     */
    private static final long REACTION_WAKE_DELAY_MS = 3000L;
    private static final ScheduledExecutorService REACTION_WAKES = Executors.newSingleThreadScheduledExecutor(
            runnable -> {
                final Thread thread = new Thread(runnable, "khandaq-reaction-wake");
                thread.setDaemon(true);
                return thread;
            });

    /** A sealed preview with the id the relay tells the retries of one event by. */
    static final class Preview
    {
        final String sealed;
        final String pid;

        Preview(final String sealed, final String pid)
        {
            this.sealed = sealed;
            this.pid = pid;
        }
    }

    private PushPreviewSender()
    {
    }

    // ---- the wake of a message ----

    /**
     * The preview the wake for an outgoing message carries (HelperFriend.friend_call_push_url): of the message that
     * wake counts for (HelperMessage.outgoing_message_for_wake). Null without a key or a message, and the wake goes
     * without one. On the wake's own thread: it reads the database.
     */
    static Preview forMessageWake(final String friendPubkey, final long messageTimestampMs)
    {
        try
        {
            final byte[] key = keyFor(friendPubkey);
            if (key == null)
            {
                return null;
            }
            final Message m = HelperMessage.outgoing_message_for_wake(friendPubkey, messageTimestampMs);
            return (m == null) ? null : forMessage(key, m);
        }
        catch (RuntimeException e)
        {
            Log.i(TAG, "forMessageWake:EE:" + e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * The preview of a text being sent (HelperGeneric.tox_friend_send_message_wrapper), whose row is not written yet:
     * made from the text and its msgV3 hash, so that its pid is the one the wakes for the row carry. Null without a
     * key or a hash: a text without one is told apart by its row only, and its wakes from the row carry the preview.
     */
    static Preview forSentText(final String friendPubkey, final String text, final String msgV3Hash,
                               final long sentSeconds)
    {
        try
        {
            final byte[] key = keyFor(friendPubkey);
            if (key == null)
            {
                return null;
            }
            final PushPreviewPayload payload = PushPreviewPayload.text(previewText(text), msgV3Hash, ownName(),
                                                                       sentSeconds);
            return (payload.eventKey == null) ? null : seal(key, payload, payload.eventKey);
        }
        catch (RuntimeException e)
        {
            Log.i(TAG, "forSentText:EE:" + e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * The preview the one wake for a friend's due messages carries (HelperFriend.friend_call_push_url_for_due_messages):
     * of the newest of them, which is what a person would want to read first.
     */
    static Preview forDueWake(final String friendPubkey, final List<PushBackoffPolicy.DueWake> due)
    {
        try
        {
            final byte[] key = keyFor(friendPubkey);
            if ((key == null) || (due == null) || due.isEmpty())
            {
                return null;
            }
            long newest = -1;
            for (final PushBackoffPolicy.DueWake d : due)
            {
                newest = Math.max(newest, d.messageId);
            }
            final List<Message> rows = orma.selectFromMessage().idEq(newest).toList();
            if ((rows == null) || rows.isEmpty() || !friendPubkey.equalsIgnoreCase(rows.get(0).tox_friendpubkey))
            {
                return null;
            }
            return forMessage(key, rows.get(0));
        }
        catch (RuntimeException e)
        {
            Log.i(TAG, "forDueWake:EE:" + e.getClass().getSimpleName());
            return null;
        }
    }

    private static Preview forMessage(final byte[] key, final Message m)
    {
        if (m.direction != 1)
        {
            return null;
        }
        final long ts = m.sent_timestamp / 1000L;
        final PushPreviewPayload payload;
        if (m.TRIFA_MESSAGE_TYPE == TRIFA_MSG_TYPE_TEXT.value)
        {
            payload = PushPreviewPayload.text(previewText(m.text), m.msg_idv3_hash, ownName(), ts);
        }
        else if (m.TRIFA_MESSAGE_TYPE == TRIFA_MSG_FILE.value)
        {
            final String name = wireFileName(m);
            payload = PushPreviewPayload.file(messageKind(m), shownFileName(name), fileAnchor(m), ownName(), ts);
        }
        else
        {
            return null;
        }
        // An event without a key of its own (a text that has no msgV3 hash before it leaves) is told apart by the
        // row, so that each retry of it carries the same pid, also after a restart: derived with K, so the relay
        // cannot tell anything from it, as a random one could not either.
        return seal(key, payload, (payload.eventKey != null)
                                  ? payload.eventKey : "local:" + m.id + ":" + m.sent_timestamp);
    }

    // ---- the wake of a reaction ----

    /**
     * The person added {@code emoji} to the friend's message {@code m} (HelperMessageReaction), and the reaction
     * could not go now. One wake, a little later, while the friend is still offline and the reaction still there.
     */
    static void wakeForFriendReaction(final Message m, final String emoji, final String anchorHex)
    {
        if ((m == null) || (m.direction != 0) || !PushPreviewPeers.usable())
        {
            return;
        }
        final long messageId = m.id;
        final String friendPubkey = m.tox_friendpubkey;
        schedule(() -> {
            final List<Message> rows = orma.selectFromMessage().idEq(messageId).toList();
            if ((rows == null) || rows.isEmpty())
            {
                return;
            }
            final Message now = rows.get(0);
            if (!emoji.equals(HelperMessageReaction.actorEmoji(now.reactions, HelperMessageReaction.OWN_MARKER))
                || !now.reactions_pending || !isOffline(friendPubkey))
            {
                return;
            }
            final PushPreviewPayload payload = PushPreviewPayload.reaction(
                    emoji, messageKind(now), previewText(now.text), anchorHex, ownName(),
                    System.currentTimeMillis() / 1000L);
            sendReactionWake(friendPubkey, payload);
        });
    }

    /**
     * The person added {@code emoji} to a member's message {@code gm} in a group (HelperMessageReaction). The friend
     * whose group presence holds that member wakes once, a little later, when offline and the reaction still there.
     * A presence two friends claim wakes neither: one of them has replayed the other's.
     */
    static void wakeForGroupReaction(final GroupMessage gm, final String emoji, final String wireAnchorHex)
    {
        if ((gm == null) || (gm.direction != 0) || !PushPreviewPeers.usable())
        {
            return;
        }
        final long messageId = gm.id;
        schedule(() -> {
            final List<GroupMessage> rows = orma.selectFromGroupMessage().idEq(messageId).toList();
            if ((rows == null) || rows.isEmpty())
            {
                return;
            }
            final GroupMessage now = rows.get(0);
            if (!emoji.equals(HelperMessageReaction.actorEmoji(now.reactions, HelperMessageReaction.OWN_MARKER)))
            {
                return;
            }
            final byte[] hash = PushPreviewCrypto.presenceHash(
                    PushPreviewCrypto.unhex(now.group_identifier, PushPreviewCrypto.CHAT_ID_BYTES),
                    PushPreviewCrypto.unhex(now.tox_group_peer_pubkey, PushPreviewCrypto.PUBLIC_KEY_BYTES));
            if (hash == null)
            {
                return;
            }
            final List<String> claimants = PushPreviewStore.friendsWithPresence(MainActivity.context_s,
                                                                                PushPreviewCrypto.hex(hash));
            if (claimants.size() != 1)
            {
                return;
            }
            final String author = claimants.get(0);
            if (!isOffline(author))
            {
                return;
            }
            final PushPreviewPayload payload = PushPreviewPayload.reaction(
                    emoji, messageKind(now), previewText(now.text), wireAnchorHex, ownName(),
                    System.currentTimeMillis() / 1000L).inGroup(groupName(now.group_identifier), now.group_identifier);
            sendReactionWake(author, payload);
        });
    }

    private static void sendReactionWake(final String friendPubkey, final PushPreviewPayload payload)
    {
        final String pushUrl = HelperFriend.wake_url_for_friend(friendPubkey);
        final byte[] key = keyFor(friendPubkey);
        if ((pushUrl == null) || (key == null) || (payload.eventKey == null))
        {
            return;
        }
        final Preview preview = seal(key, payload, payload.eventKey);
        if (preview != null)
        {
            // Not counted against the message's wakes (sent_push), and not retried: one reaction, one wake.
            HelperFriend.send_wake_request(pushUrl, preview);
        }
    }

    // ---- what a preview says ----

    /** The text as the chat shows it: without a reply's header or the mentions block. */
    static String previewText(final String raw)
    {
        if (raw == null)
        {
            return null;
        }
        final String body = GroupMentionHelper.parse(raw).bodyText;
        return (body == null) ? null : body.trim();
    }

    /** The name the file goes to the friend under: the copy's (HelperFiletransfer.copy_outgoing_file_to_sdcard_dir). */
    private static String wireFileName(final Message m)
    {
        final String name = HelperFiletransfer.basenameFromPath(m.filename_fullpath);
        return ((name != null) && !name.isEmpty()) ? name : HelperFiletransfer.display_name_from_message_text(m.text);
    }

    /** A name worth showing, or null for one that is only a transfer id or the like. */
    private static String shownFileName(final String name)
    {
        return ((name == null) || HelperFiletransfer.looksLikeInternalFtName(name)) ? null : name;
    }

    /**
     * The kind of a 1:1 message as a preview names it (docs/PUSH_PREVIEWS.md, "Kinds from a file"): text, voice,
     * video, photo or file; null for a row of any other type. The reaction notification over Tox
     * (HelperMessageReaction) names the reacted message by the same rule.
     */
    static String messageKind(final Message m)
    {
        if (m.TRIFA_MESSAGE_TYPE == TRIFA_MSG_TYPE_TEXT.value)
        {
            return PushPreviewPayload.KIND_TEXT;
        }
        if (m.TRIFA_MESSAGE_TYPE != TRIFA_MSG_FILE.value)
        {
            return null;
        }
        if (HelperFiletransfer.isVoiceMessage(m))
        {
            return PushPreviewPayload.KIND_VOICE;
        }
        final String name = wireFileName(m);
        return PushPreviewPayload.fileKind(name, HelperFiletransfer.guess_mime_type_from_filename(name));
    }

    /** The same for a group message. */
    static String messageKind(final GroupMessage gm)
    {
        if (gm.TRIFA_MESSAGE_TYPE == TRIFA_MSG_TYPE_TEXT.value)
        {
            return PushPreviewPayload.KIND_TEXT;
        }
        if (gm.TRIFA_MESSAGE_TYPE != TRIFA_MSG_FILE.value)
        {
            return null;
        }
        if (HelperFiletransfer.isGroupVoiceMessage(gm))
        {
            return PushPreviewPayload.KIND_VOICE;
        }
        return PushPreviewPayload.fileKind(gm.file_name, HelperFiletransfer.guess_mime_type_from_filename(gm.file_name));
    }

    /**
     * The Tox file id of an outgoing file, made now when it has none yet and the friend is offline (the file is first
     * offered when the friend comes online). start_outgoing_ft offers the file under the id its message holds
     * (earlier_outgoing_file_id), so the preview and the file share their event key "f:", and the recipient's two
     * notifications become one. While the friend is online an offer may be starting from a row read before this,
     * under an id of its own: then there is no id to give, and the recipient matches the file by its kind and name.
     */
    private static String fileAnchor(final Message m)
    {
        final String held = FileResume.reusableFileId(m.ft_id_anchor_hex);
        if ((held != null) || (m.direction != 1) || (m.id <= 0) || !isOffline(m.tox_friendpubkey))
        {
            return held;
        }
        try
        {
            final String made = PushPreviewCrypto.hex(PushPreviewCrypto.randomBytes(32)).toUpperCase(Locale.ROOT);
            // Only while no offer has started: an offer under way keeps the id it went out with.
            orma.updateMessage().idEq(m.id).ft_outgoing_startedEq(false).ft_id_anchor_hex(made).execute();
            final List<Message> rows = orma.selectFromMessage().idEq(m.id).toList();
            return ((rows == null) || rows.isEmpty()) ? null : FileResume.reusableFileId(rows.get(0).ft_id_anchor_hex);
        }
        catch (RuntimeException e)
        {
            Log.i(TAG, "fileAnchor:EE:" + e.getClass().getSimpleName());
            return null;
        }
    }

    // ---- inside ----

    /** The K the friend handed us, when previews can be made here at all. */
    private static byte[] keyFor(final String friendPubkey)
    {
        if (!PushPreviewPeers.usable())
        {
            return null;
        }
        final Context context = MainActivity.context_s;
        return (context == null) ? null : PushPreviewStore.peerKey(context, friendPubkey);
    }

    private static Preview seal(final byte[] key, final PushPreviewPayload payload, final String eventKey)
    {
        final byte[] json = payload.toJson();
        if (json == null)
        {
            return null;
        }
        final String sealed = PushPreviewCrypto.seal(key, json);
        final String pid = PushPreviewCrypto.eventId(key, eventKey);
        return (KhandaqPush.isPreview(sealed) && KhandaqPush.isPreviewId(pid)) ? new Preview(sealed, pid) : null;
    }

    /** Our own display name, as the friend would see it. */
    private static String ownName()
    {
        final String name = TRIFAGlobals.global_my_name;
        return ((name == null) || name.trim().isEmpty()) ? null : name.trim();
    }

    private static String groupName(final String groupIdentifier)
    {
        try
        {
            final List<GroupDB> rows = orma.selectFromGroupDB().
                    group_identifierEq(groupIdentifier.toLowerCase(Locale.ROOT)).toList();
            return ((rows == null) || rows.isEmpty()) ? null : rows.get(0).name;
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    /** The friend is not connected now, or not in Tox at all. */
    private static boolean isOffline(final String friendPubkey)
    {
        final long friendNumber = HelperFriend.tox_friend_by_public_key__wrapper(friendPubkey);
        if ((friendNumber < 0) || (friendNumber == TRIFAGlobals.UINT32_MAX_JAVA))
        {
            return true;
        }
        return MainActivity.tox_friend_get_connection_status(friendNumber)
               == ToxVars.TOX_CONNECTION.TOX_CONNECTION_NONE.value;
    }

    private static void schedule(final Runnable task)
    {
        try
        {
            REACTION_WAKES.schedule(() -> {
                try
                {
                    task.run();
                }
                catch (Throwable e)
                {
                    Log.i(TAG, "reaction wake:EE:" + e.getClass().getSimpleName());
                }
            }, REACTION_WAKE_DELAY_MS, TimeUnit.MILLISECONDS);
        }
        catch (RejectedExecutionException e)
        {
            Log.i(TAG, "reaction wake:EE:rejected");
        }
    }
}
