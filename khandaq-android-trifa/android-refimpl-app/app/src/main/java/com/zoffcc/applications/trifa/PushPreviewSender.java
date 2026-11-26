package com.zoffcc.applications.trifa;

import android.content.Context;
import android.util.Log;

import com.zoffcc.applications.sorm.GroupDB;
import com.zoffcc.applications.sorm.GroupMessage;
import com.zoffcc.applications.sorm.GroupPeerDB;
import com.zoffcc.applications.sorm.Message;

import org.khandaq.messenger.KhandaqPush;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
 * KHANDAQ (0.2.69, group message wakes): a text or a file of ours to a group wakes once each member away who is our
 * contact and listed the group in its 190/03 set.
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
    /**
     * KHANDAQ (0.2.69, group message wakes): a member and its contact are asked again this long after the message
     * went, and only then is the contact woken.
     */
    private static final long GROUP_MESSAGE_WAKE_DELAY_MS = 3000L;
    /** KHANDAQ (0.2.69, group message wakes): our files whose first offer went, in this run; each wakes once. */
    private static final int MAX_OFFERED_FILES = 512;
    private static final Map<String, Boolean> OFFERED_FILES = Collections.synchronizedMap(
            new LinkedHashMap<String, Boolean>()
            {
                @Override
                protected boolean removeEldestEntry(final Map.Entry<String, Boolean> eldest)
                {
                    return size() > MAX_OFFERED_FILES;
                }
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
            sendOneWake(friendPubkey, payload);
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
            sendOneWake(author, payload);
        });
    }

    // ---- the wake of a group message ----

    /**
     * KHANDAQ (0.2.69, group message wakes): a text of ours went to a group, toxcore gave it its message id
     * (HelperGroup.send_group_text_message_resilient). Private messages, edits and history sync go other ways and wake
     * nobody, and so does a text that waited, when it goes: it woke when it began to wait (wakeForWaitingGroupText).
     */
    static void wakeForGroupText(final long groupNumber, final String groupIdentifier, final String text,
                                 final long messageId)
    {
        if (!PushPreviewPeers.usable() || (groupIdentifier == null)
            || !HelperFriend.is_group_text_send_success(messageId))
        {
            return;
        }
        // Our key in the group names the message (its event key), read while the number is surely the group's.
        final String ownPeerKey = HelperGroup.tox_group_self_get_public_key__wrapper(groupNumber);
        final PushPreviewPayload payload = PushPreviewPayload.groupText(previewText(text), messageId, ownPeerKey,
                                                                        ownName(), System.currentTimeMillis() / 1000L);
        wakeGroupMembers(groupIdentifier, ownPeerKey, payload, payload.eventKey);
    }

    /**
     * KHANDAQ (0.2.69, group message wakes): a text of ours has to wait on this device, since nobody else is connected
     * in the group or the group is not (HelperGroup.send_group_text_message_resilient). That is how a text to a small
     * group whose members are away goes, so the away contacts wake now, under the same rules; when the text goes later
     * nobody wakes again. toxcore gives it its id only when it goes: the preview has no event key and says our key in
     * the group instead (PushPreviewPayload.waitingGroupText), and the pid is one of its own, as the wake is one.
     */
    static void wakeForWaitingGroupText(final String groupIdentifier, final String text)
    {
        if (!PushPreviewPeers.usable() || (groupIdentifier == null))
        {
            return;
        }
        final long groupNumber = HelperGroup.tox_group_by_groupid__wrapper(groupIdentifier);
        final String ownPeerKey = (groupNumber < 0) ? null
                                                    : HelperGroup.tox_group_self_get_public_key__wrapper(groupNumber);
        final PushPreviewPayload payload = PushPreviewPayload.waitingGroupText(previewText(text), ownPeerKey,
                                                                               ownName(),
                                                                               System.currentTimeMillis() / 1000L);
        if (payload.groupPeerKey == null)
        {
            return;
        }
        wakeGroupMembers(groupIdentifier, ownPeerKey, payload, "gw:" + UUID.randomUUID());
    }

    /**
     * KHANDAQ (0.2.69, group message wakes): a file of ours was offered to a group: its BEGIN went to a member
     * (NgcGroupFileTransfer.sendChunkedFrom) or its one packet to the group (HelperGroup.send_group_image); or it has
     * nobody to go to and waits (NgcGroupFileTransfer.waitForMember). Only the first of these wakes: the same file
     * offered again in this run (sent again, or resumed after a wait) wakes nobody.
     */
    static void wakeForGroupFile(final GroupMessage g, final long groupNumber)
    {
        if (!PushPreviewPeers.usable() || (g == null) || (g.group_identifier == null) || (g.msg_id_hash == null))
        {
            return;
        }
        final String file = g.group_identifier.toLowerCase(Locale.ROOT) + "|" + g.msg_id_hash.toUpperCase(Locale.ROOT);
        if (OFFERED_FILES.put(file, Boolean.TRUE) != null)
        {
            return;
        }
        final String ownPeerKey = HelperGroup.tox_group_self_get_public_key__wrapper(groupNumber);
        // ts is when it goes, as for a text: a file that waited days for a member is no stale preview (48 hours).
        final PushPreviewPayload payload = PushPreviewPayload.groupFile(messageKind(g), shownFileName(g.file_name),
                                                                        g.msg_id_hash, ownName(),
                                                                        System.currentTimeMillis() / 1000L);
        wakeGroupMembers(g.group_identifier, ownPeerKey, payload, payload.eventKey);
    }

    /**
     * The contacts a message of ours to the group wakes (GroupMessageWakes.contactsToWake), each one away with its K
     * and push address known. Three seconds later its member's connection in the group and its own are asked again,
     * then one wake each and no retries: the contact's app brings the message by the group's history sync when it
     * connects. Off the caller's thread: it reads toxcore and the database. {@code pidSource} gives the wake its pid:
     * the event key, or for a text without one a value of its own.
     */
    private static void wakeGroupMembers(final String groupIdentifier, final String ownPeerKey,
                                         final PushPreviewPayload message, final String pidSource)
    {
        if ((ownPeerKey == null) || (pidSource == null))
        {
            return;
        }
        schedule(() -> {
            final Map<String, Set<String>> wakeSets = PushPreviewStore.wakeSets(MainActivity.context_s);
            if (wakeSets.isEmpty() || (orma == null))
            {
                return;
            }
            final Set<String> members = new LinkedHashSet<>();
            final Set<String> connected = new HashSet<>();
            liveMembers(HelperGroup.tox_group_by_groupid__wrapper(groupIdentifier), members, connected);
            final List<GroupPeerDB> saved = orma.selectFromGroupPeerDB().
                    group_identifierEq(groupIdentifier.toLowerCase(Locale.ROOT)).toList();
            if (saved != null)
            {
                for (final GroupPeerDB peer : saved)
                {
                    if (peer.tox_group_peer_pubkey != null)
                    {
                        members.add(peer.tox_group_peer_pubkey.toUpperCase(Locale.ROOT));
                    }
                }
            }
            final Set<String> left = new HashSet<>(GroupPeerKeys.leavingKeys(groupIdentifier,
                                                                             System.currentTimeMillis()));
            for (final String member : members)
            {
                if (HelperGroup.is_group_peer_departed(groupIdentifier, member))
                {
                    left.add(member);
                }
            }
            final Map<String, String> away = new LinkedHashMap<>();
            for (final Map.Entry<String, String> wake : GroupMessageWakes.contactsToWake(
                    groupIdentifier, ownPeerKey, members, left, connected, wakeSets).entrySet())
            {
                final String contact = wake.getKey();
                if (isOffline(contact) && (keyFor(contact) != null)
                    && (HelperFriend.wake_url_for_friend(contact) != null))
                {
                    away.put(contact, wake.getValue());
                }
            }
            if (away.isEmpty())
            {
                return;
            }
            final PushPreviewPayload payload = message.inGroup(groupName(groupIdentifier), groupIdentifier);
            schedule(() -> {
                final Set<String> connectedNow = new HashSet<>();
                liveMembers(HelperGroup.tox_group_by_groupid__wrapper(groupIdentifier), new HashSet<String>(),
                            connectedNow);
                for (final Map.Entry<String, String> wake : away.entrySet())
                {
                    if (!connectedNow.contains(wake.getValue()) && isOffline(wake.getKey()))
                    {
                        sendOneWake(wake.getKey(), payload, pidSource);
                    }
                }
            }, GROUP_MESSAGE_WAKE_DELAY_MS);
        }, 0L);
    }

    /** The keys toxcore lists in the group now, upper case, and those of them connected (they get it over Tox). */
    private static void liveMembers(final long groupNumber, final Set<String> members, final Set<String> connected)
    {
        if (groupNumber < 0)
        {
            return;
        }
        final long[] peers = MainActivity.tox_group_get_peerlist(groupNumber);
        if (peers == null)
        {
            return;
        }
        for (final long peerId : peers)
        {
            final String key = HelperGroup.tox_group_peer_get_public_key__wrapper(groupNumber, peerId);
            if (key == null)
            {
                continue;
            }
            final String member = key.toUpperCase(Locale.ROOT);
            members.add(member);
            final int status = MainActivity.tox_group_peer_get_connection_status(groupNumber, peerId);
            if (HelperGroup.is_group_peer_online(status))
            {
                connected.add(member);
            }
        }
    }

    private static void sendOneWake(final String friendPubkey, final PushPreviewPayload payload)
    {
        sendOneWake(friendPubkey, payload, payload.eventKey);
    }

    private static void sendOneWake(final String friendPubkey, final PushPreviewPayload payload,
                                    final String pidSource)
    {
        final String pushUrl = HelperFriend.wake_url_for_friend(friendPubkey);
        final byte[] key = keyFor(friendPubkey);
        if ((pushUrl == null) || (key == null) || (pidSource == null))
        {
            return;
        }
        final Preview preview = seal(key, payload, pidSource);
        if (preview != null)
        {
            // Not counted against the message's wakes (sent_push), and not retried: one event, one wake.
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

    private static Preview seal(final byte[] key, final PushPreviewPayload payload, final String pidSource)
    {
        final byte[] json = payload.toJson();
        if (json == null)
        {
            return null;
        }
        final String sealed = PushPreviewCrypto.seal(key, json);
        final String pid = PushPreviewCrypto.eventId(key, pidSource);
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
        schedule(task, REACTION_WAKE_DELAY_MS);
    }

    private static void schedule(final Runnable task, final long delayMs)
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
            }, delayMs, TimeUnit.MILLISECONDS);
        }
        catch (RejectedExecutionException e)
        {
            Log.i(TAG, "reaction wake:EE:rejected");
        }
    }
}
