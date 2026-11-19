package com.zoffcc.applications.trifa;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (0.2.64): where push previews are wired into the app, pinned on the sources. Each of these is a line that a
 * merge resolved the easy way would drop without any test failing: previews would stop, or go where they must not,
 * while every message still arrives.
 */
public class PushPreviewWiringTest
{
    private static final String TRIFA = "src/main/java/com/zoffcc/applications/trifa/";
    private static final String MESSENGER = "src/main/java/org/khandaq/messenger/";

    @Test
    public void packet_190_is_registered_and_dispatched()
    {
        final String main = read(TRIFA + "MainActivity.java");
        final String registrar = method(main, "private void register_khandaq_lossless_pktids()");
        assertTrue(registrar.contains("tox_callback_friend_lossless_packet_per_pktid(PushPreviewCrypto.PKT_PUSH_PREVIEW)"));
        final int branch = main.indexOf("else if (data[0] == (byte) PushPreviewCrypto.PKT_PUSH_PREVIEW)");
        assertTrue("190 has its branch in the lossless dispatch", branch >= 0);
        assertTrue(main.indexOf("PushPreviewPeers.onPacket(friend_number, data, (int) length);", branch) > branch);
    }

    @Test
    public void the_key_and_the_presence_go_where_the_push_url_goes()
    {
        final String sendPushUrl = method(read(TRIFA + "HelperFriend.java"),
                                          "static void send_pushurl_to_friend(final String friend_pubkey)");
        final int pushUrl = sendPushUrl.indexOf("CONTROL_PROXY_MESSAGE_TYPE_PUSH_URL_FOR_FRIEND.value");
        final int publish = sendPushUrl.indexOf("PushPreviewPeers.publishTo(friend_pubkey);");
        assertTrue("181 is sent there", pushUrl >= 0);
        assertTrue("190/01 and 190/02 go after 181", publish > pushUrl);
    }

    @Test
    public void a_deleted_friend_takes_its_key_along()
    {
        final String deleteFriend = method(read(TRIFA + "HelperFriend.java"),
                                           "static void delete_friend(final String friend_pubkey)");
        assertTrue(deleteFriend.contains("PushPreviewPeers.forgetFriend(friend_pubkey);"));
    }

    @Test
    public void only_a_contact_given_the_key_and_kept_has_a_preview_shown()
    {
        // A deleted or blocked contact keeps the K and the wake URL it was given: the list of allowed senders is what
        // keeps its name and words off the lock screen.
        final String publish = method(read(TRIFA + "PushPreviewPeers.java"), "static void publishTo(final String friendPubkey)");
        final int allow = publish.indexOf("PushPreviewStore.allowSender(context, friendPubkey)");
        final int send = publish.indexOf("MainActivity.tox_friend_send_lossless_packet(friendNumber, keyPacket");
        assertTrue("the friend is on the list before the key goes", allow >= 0 && send > allow);
        final String receiver = read(TRIFA + "PushPreviewReceiver.java");
        final int check = receiver.indexOf("PushPreviewStore.isAllowedSender(context, senderKey)");
        final int open = receiver.indexOf("PushPreviewCrypto.open(key, preview)");
        assertTrue("a sender off the list is refused before anything is opened", check >= 0 && open > check);
        assertTrue(method(read(TRIFA + "DbSecretKeyStorage.java"), "static void wipeUserProfile(final Context context)")
                           .contains("PushPreviewStore.wipeAll(context);"));
    }

    @Test
    public void a_preview_is_read_before_the_generic_wake_and_after_the_capability_challenge()
    {
        final String service = read(MESSENGER + "KhandaqFirebaseMessagingService.java");
        final int challenge = service.indexOf("data.containsKey(\"khandaq_reg_nonce\")");
        final int preview = service.indexOf("com.zoffcc.applications.trifa.PushPreviewReceiver.onWake(this, data)");
        final int generic = service.indexOf("com.zoffcc.applications.trifa.WakePush.onWake(this,");
        assertTrue(challenge >= 0 && preview > challenge && generic > preview);
    }

    @Test
    public void the_wakes_of_calls_carry_no_preview()
    {
        assertTrue(read(TRIFA + "CallingWaitingActivity.java")
                           .contains("friend_call_push_url(calling_friend_pk, System.currentTimeMillis(), false);"));
        assertTrue(read(TRIFA + "MessageListActivity.java")
                           .contains("friend_call_push_url(friend_pk, System.currentTimeMillis(), false);"));
    }

    @Test
    public void the_wrapper_wakes_with_a_preview_of_the_text_it_sends()
    {
        final String generic = read(TRIFA + "HelperGeneric.java");
        assertTrue(generic.contains("() -> PushPreviewSender.forSentText(wake_pubkey, message, wake_hash,"));
        final String friend = read(TRIFA + "HelperFriend.java");
        assertTrue(friend.contains("PushPreviewSender.forDueWake(friend_pubkey, due_now)"));
        assertTrue(friend.contains("send_wake_request(pushurl_for_friend, preview)"));
    }

    @Test
    public void reactions_notify_and_wake_only_for_the_right_messages()
    {
        final String reactions = read(TRIFA + "HelperMessageReaction.java");
        // A notification only for a reaction to a message of ours.
        final int notify = reactions.indexOf("static void notifyFriendReaction(");
        assertTrue(notify >= 0);
        assertTrue(reactions.indexOf("if (m.direction != 1)", notify) > notify);
        assertTrue(reactions.contains("if (add && changed && ours)"));
        // A wake only for a reaction added to the other one's message.
        assertTrue(reactions.contains("if (add && m.reactions_pending)"));
        assertTrue(reactions.contains("if (add && (gm.direction == 0))"));
    }

    @Test
    public void joining_or_creating_a_group_announces_the_presence()
    {
        final String group = read(TRIFA + "HelperGroup.java");
        assertTrue(group.split(java.util.regex.Pattern.quote("PushPreviewPeers.groupsChanged();"), -1).length - 1 >= 2);
    }

    /** The text of the method that starts with signature, up to its closing brace at class level. */
    private static String method(final String source, final String signature)
    {
        final int start = source.indexOf(signature);
        assertTrue(signature, start >= 0);
        final int end = source.indexOf("\n    }\n", start);
        assertTrue(signature, end > start);
        return source.substring(start, end);
    }

    private static String read(final String path)
    {
        // Gradle runs unit tests from the module directory; an IDE may use the project root.
        for (final String root : new String[]{"", "app/"})
        {
            final File f = new File(root + path);
            if (f.isFile())
            {
                try
                {
                    return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
                }
                catch (IOException e)
                {
                    throw new AssertionError(path + ": " + e);
                }
            }
        }
        throw new AssertionError(path + " not found from " + new File("").getAbsolutePath());
    }
}
