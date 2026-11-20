package com.zoffcc.applications.trifa;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (0.2.69, group message wakes): where group message wakes are wired into the app (docs/PUSH_PREVIEWS.md,
 * "Group message wakes"), pinned on the sources with their comments left out. The rules are GroupMessageWakesTest's;
 * these are the lines a merge resolved the easy way would drop without any other test failing: a group message would
 * wake nobody, or wake a contact that muted the group, or alert twice.
 */
public class GroupMessageWakeWiringTest
{
    @Test
    public void a_group_text_wakes_when_toxcore_gives_it_its_id_and_nowhere_else() throws Exception
    {
        final String group = code("HelperGroup.java");
        final String send = body(group, "static long send_group_text_message_resilient(");
        final int sent = send.indexOf("if (HelperFriend.is_group_text_send_success(message_id))");
        final int wake = send.indexOf(
                "PushPreviewSender.wakeForGroupText(group_num, group_identifier, message, message_id);");
        assertTrue(sent > 0);
        assertTrue(wake > sent);
        assertTrue(wake < send.indexOf("return message_id;", sent));
        // Every text of ours goes out there (a queued one too, once it goes); a private message, an edit and a row of
        // history sync go other ways and wake nobody.
        assertEquals(1, count(group, "tox_group_send_message("));
        assertEquals(1, count(group, "PushPreviewSender.wakeForGroupText("));
        for (final String other : new String[]{"GroupMessageListActivity.java", "MediaSendPreviewHelper.java"})
        {
            assertEquals(other, 0, count(code(other), "tox_group_send_message("));
        }
    }

    @Test
    public void a_group_file_wakes_at_its_first_offer_only() throws Exception
    {
        final String ngc = code("NgcGroupFileTransfer.java");
        final String send = body(ngc, "private static void sendChunkedFrom(");
        final int begin = send.indexOf("final int beginRes = send.begin();");
        final int unsent = send.indexOf("endUnsent(g, ownSend, beginRes, \"BEGIN\");");
        final int first = send.indexOf("if (ownSend && (startChunk == 0))");
        final int wake = send.indexOf("PushPreviewSender.wakeForGroupFile(g, groupNum);");
        assertTrue(begin > 0);
        assertTrue(unsent > begin);
        assertTrue("only once the BEGIN went, and not for a resume or a member's request", first > unsent);
        assertTrue(wake > first);
        assertTrue(wake < send.indexOf("send.chunk("));
        assertEquals(1, count(ngc, "PushPreviewSender.wakeForGroupFile("));

        // A small file is offered whole, in one packet to the group.
        final String image = body(code("HelperGroup.java"), "static void send_group_image(");
        final int packet = image.indexOf("NgcGroupFileTransfer.spendPace(data_length, members.length, overRelay);");
        final int imageWake = image.indexOf("PushPreviewSender.wakeForGroupFile(g, groupNum);");
        assertTrue(packet > image.indexOf("if (!is_ngc_custom_packet_send_ok(sendRes))"));
        assertTrue(imageWake > packet);
        assertTrue(imageWake < image.indexOf("NgcGroupFileTransfer.outgoingSent(g)"));

        // The same file offered again in this run wakes nobody again.
        assertTrue(body(code("PushPreviewSender.java"), "static void wakeForGroupFile(").contains(
                "if (OFFERED_FILES.put(file, Boolean.TRUE) != null)"));
    }

    @Test
    public void a_wake_goes_to_a_contact_still_away_three_seconds_later() throws Exception
    {
        final String sender = code("PushPreviewSender.java");
        assertTrue(sender.contains("private static final long GROUP_MESSAGE_WAKE_DELAY_MS = 3000L;"));
        final String members = bare(body(sender, "private static void wakeGroupMembers("));
        assertTrue(members.contains("GroupMessageWakes.contactsToWake(groupIdentifier,ownPeerKey,members,left,"
                                    + "connected,wakeSets)"));
        // Offline, with its K and its push address known; then its member and itself asked again, and one wake.
        assertTrue(members.contains("if(isOffline(contact)&&(keyFor(contact)!=null)"
                                    + "&&(HelperFriend.wake_url_for_friend(contact)!=null))"));
        assertTrue(members.contains("if(!connectedNow.contains(wake.getValue())&&isOffline(wake.getKey())){"
                                    + "sendOneWake(wake.getKey(),payload);}"));
        assertTrue(members.contains("},GROUP_MESSAGE_WAKE_DELAY_MS);"));
        // The members it knows: those toxcore lists now and the saved ones, less those that left.
        assertTrue(members.contains("orma.selectFromGroupPeerDB()."));
        assertTrue(members.contains("GroupPeerKeys.leavingKeys(groupIdentifier,"));
        assertTrue(members.contains("HelperGroup.is_group_peer_departed(groupIdentifier,member)"));
    }

    @Test
    public void packet_190_03_goes_with_190_02_and_is_kept_when_it_comes() throws Exception
    {
        final String peers = code("PushPreviewPeers.java");
        final String publish = body(peers, "static void publishTo(final String friendPubkey)");
        final int presence = publish.indexOf("PushPreviewCrypto.presencePacket(ownPresenceHashes())");
        assertTrue(presence > 0);
        assertTrue(publish.indexOf("sendWakeSet(context, friendNumber, friendPubkey, ownGroupsForWakes());")
                   > presence);
        final String announce = body(peers, "private static void announcePresence()");
        assertTrue(announce.contains("sendWakeSet(context, friendNumber, friendPubkey, groups);"));
        // Each friend its own set: the groups in which we muted its member are left out of it.
        assertTrue(bare(body(peers, "private static void sendWakeSet(")).contains(
                "GroupMessageWakes.wakeSet(groups,PushPreviewStore.presenceOf(context,friendPubkey))"));

        final String packet = body(peers, "static void onPacket(");
        final int stored = packet.indexOf("PushPreviewStore.storePresence(context, friendPubkey, hashes);");
        assertTrue(stored > 0);
        assertTrue("a friend's 190/02 brings our 190/03 for it again",
                   packet.indexOf("sendWakeSetSoon(friendPubkey);") > stored);
        final int dispatch = packet.indexOf("else if (data[1] == PushPreviewCrypto.SUBTYPE_GROUP_WAKE)");
        assertTrue(dispatch > 0);
        assertTrue(packet.indexOf("PushPreviewStore.storeWakeSet(context, friendPubkey, hashes);", dispatch)
                   > dispatch);
    }

    @Test
    public void muting_unmuting_or_leaving_sends_the_sets_again() throws Exception
    {
        assertEquals(2, count(code("GroupListHolder.java"), "PushPreviewPeers.wakeSetsChanged();"));
        assertTrue(body(code("AirGroupDossierActivity.java"), "private void setMuted(final boolean muted)").contains(
                "PushPreviewPeers.wakeSetsChanged();"));
        assertTrue(body(code("CommunityAutoJoin.java"), "public void unmute(final String chatId)").contains(
                "PushPreviewPeers.wakeSetsChanged();"));
        final String member = body(code("GroupMemberActions.java"), "static void setMemberMuted(");
        final int muted = member.indexOf("group_notification_silent_peer_set(group_id, peer_pubkey, muted);");
        assertTrue(muted >= 0);
        assertTrue(member.indexOf("PushPreviewPeers.wakeSetsChanged();") > muted);

        // Leaving tells the friends at once, also while toxcore keeps the group for the leave notice.
        final String leave = body(code("HelperGroup.java"), "static void leave_group_with_notice(");
        final int left = leave.indexOf("PushPreviewPeers.groupsChanged();");
        assertTrue(left > 0);
        assertTrue(left < leave.indexOf("record_pending_group_leave("));

        // A group joined, created or left changes 190/02 too; a mute changes 190/03 only. One announcement for both.
        final String peers = code("PushPreviewPeers.java");
        assertTrue(body(peers, "static void groupsChanged()").contains("announceLater(true);"));
        assertTrue(body(peers, "static void wakeSetsChanged()").contains("announceLater(false);"));
        assertTrue(bare(body(peers, "private static void announceLater(final boolean presence)")).contains(
                "presencePending|=presence;"));
    }

    @Test
    public void the_tox_path_skips_the_alert_a_push_gave_for_a_group_message() throws Exception
    {
        final String group = code("HelperGroup.java");
        final String live = bare(body(group, "static void android_tox_callback_group_message_cb_method_wrapper("));
        assertTrue(live.contains("finalPushPreviewSeen.Eventevent=is_private_message?null"
                                 + ":PushPreviewSeen.forGroupText(message_id,peer_pubkey);"));
        assertTrue(live.contains("m.sent_timestamp,peer_pubkey,event);"));
        // A row from history sync notifies while it is fresh (AirBandRule.LATE_DELIVERY_MS), so it asks too.
        assertTrue(bare(body(group, "static void group_message_add_from_sync(")).contains(
                "PushPreviewSeen.forGroupText(m.message_id_tox,m.tox_group_peer_pubkey));"));
        for (final String head : new String[]{"private static void notify_incoming_group_file(",
                                              "static void handle_incoming_group_file_complete(",
                                              "static void handle_incoming_group_file(long group_number,",
                                              "private static void group_file_add_from_sync("})
        {
            assertTrue(head, body(group, head).contains("PushPreviewSeen.forGroupFile(m.msg_id_hash)"));
        }
        // Every alert of a group message names its event: two texts, four files.
        assertEquals(6, count(group, "change_msg_notification("));
        assertEquals(2, count(group, "PushPreviewSeen.forGroupText("));
        assertEquals(4, count(group, "PushPreviewSeen.forGroupFile("));
        assertTrue(code("HelperMsgNotification.java").contains("PushPreviewSeen.toxArrived(context_s, event)"));
    }

    @Test
    public void the_wake_sets_go_with_the_friend_and_with_the_profile() throws Exception
    {
        final String store = code("PushPreviewStore.java");
        assertTrue(bare(store).contains("ALL_PREFS={PREFS_MASTER,PREFS_PEERS,PREFS_PRESENCE,PREFS_WAKE,"));
        assertTrue(bare(body(store, "static void forgetFriend(final Context context, final String friendPublicKey)"))
                           .contains("prefs(context,PREFS_WAKE)"));
    }

    // ---- helpers ----

    private static String code(final String name) throws java.io.IOException
    {
        return AntispamOnRequestTest.code(source(name));
    }

    private static String bare(final String text)
    {
        return text.replaceAll("\\s+", "");
    }

    private static int count(final String text, final String what)
    {
        int n = 0;
        for (int at = text.indexOf(what); at >= 0; at = text.indexOf(what, at + what.length()))
        {
            n++;
        }
        return n;
    }

    private static String body(final String text, final String head)
    {
        final int at = text.indexOf(head);
        assertTrue(head + " not found", at >= 0);
        final int open = text.indexOf('{', at);
        assertTrue("no opening brace", open >= 0);
        int depth = 0;
        for (int i = open; i < text.length(); i++)
        {
            final char c = text.charAt(i);
            if (c == '{')
            {
                depth++;
            }
            else if (c == '}')
            {
                depth--;
                if (depth == 0)
                {
                    return text.substring(open + 1, i);
                }
            }
        }
        throw new AssertionError("unbalanced braces");
    }

    private static String source(final String name) throws java.io.IOException
    {
        // Gradle runs unit tests from the module directory; an IDE may use the project root.
        for (final String root : new String[]{"src/main/java", "app/src/main/java"})
        {
            final java.io.File file = new java.io.File(root, "com/zoffcc/applications/trifa/" + name);
            if (file.isFile())
            {
                return new String(java.nio.file.Files.readAllBytes(file.toPath()),
                                  java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError(name + " not found from " + new java.io.File("").getAbsolutePath());
    }
}
