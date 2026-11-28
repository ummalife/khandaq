package com.zoffcc.applications.trifa;

import com.zoffcc.applications.sorm.GroupMessage;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import javax.xml.parsers.DocumentBuilderFactory;

import static com.zoffcc.applications.trifa.AirConversationModel.RunKey;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (tester 21.11, 8255): the sender's tile beside the messages of a group, as Telegram has it and as iOS has
 * it since a260002f: at the bottom of a run of one member's messages, with the name kept over the first, a tap on it
 * opening the member as the name does, and the plain tile, which opens nobody, beside a message nobody confirmed.
 * KHANDAQ (owner, 28.11.2026): history brought without a signature has the member's own tile, and the column of
 * the tile starts at the 16 margin, with no time column before it.
 * Our own messages and the conversation with one person keep their rows. The rules are pure and tested here; views
 * cannot be built in a JVM test here, so the layouts and the wiring are read from the resources and the sources
 * (comments left out), as GroupAuthorTapTest does.
 */
public class GroupAuthorTileTest
{
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");
    /** A member's key in the group: 64 hex digits. */
    private static final String MEMBER_KEY = "DFE8032B" + "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123" + "79E5";

    private static long utc(final int year, final int month, final int day, final int hour, final int minute)
    {
        final Calendar calendar = Calendar.getInstance(UTC);
        calendar.clear();
        calendar.set(year, month - 1, day, hour, minute, 0);
        return calendar.getTimeInMillis();
    }

    private static GroupMessage incoming(final long sentMs)
    {
        final GroupMessage m = new GroupMessage();
        m.direction = 0;
        m.tox_group_peer_pubkey = MEMBER_KEY;
        m.group_identifier = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        m.TRIFA_MESSAGE_TYPE = TRIFAGlobals.TRIFA_MSG_TYPE.TRIFA_MSG_TYPE_TEXT.value;
        m.TRIFA_SYNC_TYPE = TRIFAGlobals.TRIFA_SYNC_TYPE.TRIFA_SYNC_TYPE_NONE.value;
        m.sent_timestamp = sentMs;
        m.text = "salam";
        return m;
    }

    private static GroupMessage relayed(final int syncType)
    {
        final GroupMessage m = incoming(utc(2026, 11, 21, 12, 0));
        m.was_synced = true;
        m.TRIFA_SYNC_TYPE = syncType;
        return m;
    }

    private static boolean plain(final GroupMessage m, final boolean named)
    {
        return GroupMessageListHolder_text_incoming_not_read.should_show_plain_author_tile(m, named);
    }

    // ---- where a run ends ----

    @Test
    public void the_last_entry_of_a_run_is_the_one_the_next_does_not_go_on_from()
    {
        final long start = utc(2026, 11, 11, 12, 0);
        final RunKey first = new RunKey("PEER", start, true);
        final RunKey soon = new RunKey("PEER", start + 4 * 60_000L, true);
        final RunKey late = new RunKey("PEER", start + 6 * 60_000L, true);
        final RunKey other = new RunKey("OTHER", start + 60_000L, true);
        final RunKey ours = new RunKey("", start + 60_000L, true);
        final RunKey file = new RunKey("PEER", start + 60_000L, false);

        assertTrue("the newest entry of the log", AirConversationModel.endsRun(first, null, UTC));
        assertFalse("the run goes on", AirConversationModel.endsRun(first, soon, UTC));
        assertTrue("five minutes and more", AirConversationModel.endsRun(first, late, UTC));
        assertTrue("another member", AirConversationModel.endsRun(first, other, UTC));
        assertTrue("your own message", AirConversationModel.endsRun(first, ours, UTC));
        assertTrue("a file stands on its own", AirConversationModel.endsRun(file, soon, UTC));
        assertFalse("a file goes on from a text", AirConversationModel.endsRun(first, file, UTC));

        final RunKey beforeMidnight = new RunKey("PEER", utc(2026, 11, 10, 23, 58), true);
        final RunKey afterMidnight = new RunKey("PEER", utc(2026, 11, 11, 0, 1), true);
        assertTrue("the day mark", AirConversationModel.endsRun(beforeMidnight, afterMidnight, UTC));
    }

    @Test
    public void a_run_has_its_name_over_the_first_entry_and_its_tile_beside_the_last()
    {
        // In a log of runs [A A A] [B] [A A] the name is over the first, fourth and fifth entries and the tile beside
        // the third, fourth and sixth: every entry either goes on with the run of the one before it or starts one.
        final long start = utc(2026, 11, 11, 12, 0);
        final List<RunKey> log = Arrays.asList(new RunKey("PEER", start, true),
                                               new RunKey("PEER", start + 4 * 60_000L, true),
                                               new RunKey("PEER", start + 5 * 60_000L, true),
                                               new RunKey("OTHER", start + 5 * 60_000L + 10_000L, true),
                                               new RunKey("PEER", start + 5 * 60_000L + 20_000L, true),
                                               new RunKey("PEER", start + 5 * 60_000L + 30_000L, true));
        final List<Boolean> names = new ArrayList<>();
        final List<Boolean> tiles = new ArrayList<>();
        for (int i = 0; i < log.size(); i++)
        {
            names.add(AirConversationModel.startsRun(log.get(i), i > 0 ? log.get(i - 1) : null, UTC));
            tiles.add(AirConversationModel.endsRun(log.get(i), i + 1 < log.size() ? log.get(i + 1) : null, UTC));
        }
        assertEquals(Arrays.asList(true, false, false, true, true, false), names);
        assertEquals(Arrays.asList(false, false, true, true, false, true), tiles);
    }

    @Test
    public void a_caption_under_a_photo_is_part_of_the_photo_and_no_text_a_run_goes_on_from()
    {
        // ChatCaptionHelper shows a text sent with a photo under the photo and folds its own row away. A file breaks
        // the run after it, so the next text of the same member starts a run of its own, with its name, as after a
        // photo without a caption: before, the folded caption counted as a text, and that text had neither.
        final long start = utc(2026, 11, 21, 12, 0);
        final GroupMessage photo = incoming(start);
        photo.TRIFA_MESSAGE_TYPE = TRIFAGlobals.TRIFA_MSG_TYPE.TRIFA_MSG_FILE.value;
        final GroupMessage caption = incoming(start + 1_000L);
        final GroupMessage next = incoming(start + 60_000L);

        assertFalse(AirTranscript.groupRunKey(photo, false).isText);
        assertFalse(AirTranscript.groupRunKey(caption, true).isText);
        assertTrue(AirTranscript.groupRunKey(caption, false).isText);
        final RunKey after = AirTranscript.groupRunKey(next, false);
        assertTrue(AirConversationModel.startsRun(after, AirTranscript.groupRunKey(photo, false), UTC));
        assertTrue(AirConversationModel.startsRun(after, AirTranscript.groupRunKey(caption, true), UTC));
        // The photo closes its run, so its tile shows beside it and the caption under it.
        assertTrue(AirConversationModel.endsRun(AirTranscript.groupRunKey(photo, false),
                                                AirTranscript.groupRunKey(caption, false), UTC));

        // The rules look the caption up beside the row they look at, both ways.
        final String transcript = code("AirTranscript.java");
        assertTrue(bare(body(transcript, "private static boolean startsGroupRun(")).contains(
                bare("groupRunKey(previous, isMergedCaption(context, adapter, i))")));
        assertTrue(bare(body(transcript, "private static boolean endsGroupRun(")).contains(
                bare("groupRunKey(m, isMergedCaption(context, adapter, position))")));
        assertTrue(bare(body(transcript, "private static boolean isMergedCaption(")).contains(
                bare("ChatCaptionHelper.is_merged_group_caption(context, Arrays.asList(file, text), 1)")));
    }

    @Test
    public void the_end_of_a_run_passes_over_the_rows_the_list_hides_as_its_start_does()
    {
        final String ends = bare(body(code("AirTranscript.java"), "private static boolean endsGroupRun("));
        assertTrue(ends.contains(bare("if (!GroupMessageLayoutHelper.isRenderableMessage(null, next)) { continue; }")));
        assertTrue(ends.contains(bare("return AirConversationModel.endsRun(entry, groupRunKey(next, false),"
                                      + " TimeZone.getDefault());")));
        // Bound off the list, or with no list, the tile shows rather than hides wrongly, as the name does.
        assertTrue(ends.contains(bare("if (current == null || current.id != m.id) { return true; }")));
        assertTrue(ends.contains("i<count&&looked<GROUP_RUN_LOOKBACK"));
    }

    // ---- the slot of an entry ----

    @Test
    public void every_entry_of_another_member_keeps_the_column_and_the_last_of_a_run_shows_the_tile()
    {
        assertEquals(AirTranscript.TileSlot.SHOWN, AirTranscript.tileSlot(true, true, 1.0f));
        assertEquals(AirTranscript.TileSlot.EMPTY, AirTranscript.tileSlot(true, false, 1.0f));
        assertEquals(AirTranscript.TileSlot.SHOWN, AirTranscript.tileSlot(true, true, 1.15f));
        // Ours, a service line and the conversation with one person have no tile, so no column either.
        assertEquals(AirTranscript.TileSlot.NONE, AirTranscript.tileSlot(false, true, 1.0f));
        assertEquals(AirTranscript.TileSlot.NONE, AirTranscript.tileSlot(false, false, 1.0f));
    }

    @Test
    public void at_the_large_text_sizes_the_column_is_left_out()
    {
        // The threshold of the redesign's stacked layouts on this phone, where iOS leaves it out at the
        // accessibility sizes: the text keeps the width, the name over the first entry says who wrote it.
        assertEquals(1.3f, AirTranscript.TILE_COLUMN_FONT_SCALE, 0f);
        assertEquals(AirTranscript.TileSlot.NONE, AirTranscript.tileSlot(true, true, 1.3f));
        assertEquals(AirTranscript.TileSlot.NONE, AirTranscript.tileSlot(true, false, 2.0f));
        final String place = bare(body(code("AirTranscript.java"), "static void placeAuthorTile("));
        assertTrue(place.contains("res.getConfiguration().fontScale"));
    }

    @Test
    public void the_tile_stands_level_with_the_bottom_of_the_entry_and_the_empty_column_takes_no_height()
    {
        final String place = bare(body(code("AirTranscript.java"), "static void placeAuthorTile("));
        assertTrue(place.contains(bare("tile.setVisibility(slot == TileSlot.SHOWN ? View.VISIBLE"
                                       + " : slot == TileSlot.EMPTY ? View.INVISIBLE : View.GONE);")));
        // Only an entry that shows the tile is at least as tall as it: a one-line text grows to it, the others not.
        assertTrue(place.contains(bare("final int height = slot == TileSlot.SHOWN"
                                       + " ? res.getDimensionPixelSize(R.dimen.kq_size_tile_compact) : 0;")));
        // The text and the name stand 8 after the tile, or at the 16 margin themselves without the column: there is no
        // time column before them any more (owner, 28.11.2026).
        assertTrue(place.contains(bare("setMarginStart(body, slot == TileSlot.NONE ? 0"
                                       + " : res.getDimensionPixelSize(R.dimen.kq_space_8));")));
        // A tile worked out for another message (a recycled row not bound again) stands for nobody here.
        assertTrue(place.contains(bare("final AuthorTile model = (tag instanceof AuthorTile && ((AuthorTile) tag).messageId"
                                       + " == m.id) ? (AuthorTile) tag : null;")));
        // The margin follows a right-to-left layout.
        assertTrue(bare(body(code("AirTranscript.java"), "private static void setMarginStart(")).contains(
                "params.setMarginStart(start);"));
    }

    // ---- the plain tile ----

    @Test
    public void a_member_who_wrote_the_entry_has_their_own_tile()
    {
        assertFalse(plain(incoming(utc(2026, 11, 21, 12, 0)), true));
        // History the author signed is theirs, and so, since 28.11.2026 (owner), is history without a signature:
        // every row of a client that does not sign is drawn as the member's other entries, tile and all.
        final GroupMessage unsigned = relayed(TRIFAGlobals.TRIFA_SYNC_TYPE.TRIFA_SYNC_TYPE_NGC_PEERS.value);
        assertTrue(GroupMessageListHolder_text_incoming_not_read.should_mark_unverified_sender(false, unsigned, false));
        assertFalse(plain(unsigned, true));
        // A file the history brought, which never carries a signature, too.
        final GroupMessage file = relayed(TRIFAGlobals.TRIFA_SYNC_TYPE.TRIFA_SYNC_TYPE_NGC_PEERS.value);
        file.TRIFA_MESSAGE_TYPE = TRIFAGlobals.TRIFA_MSG_TYPE.TRIFA_MSG_FILE.value;
        assertFalse(plain(file, true));
        assertTrue(bare(body(code("GroupMessageListHolder_file_incoming_state_cancel.java"),
                             "public void bindMessageList(")).contains(
                bare("should_show_plain_author_tile(m, author_named)")));
    }

    @Test
    public void an_author_nobody_confirmed_has_the_plain_tile()
    {
        // "Author not confirmed" (KQ-03), whatever the row carries.
        assertTrue(plain(relayed(TRIFAGlobals.TRIFA_SYNC_TYPE.TRIFA_SYNC_TYPE_NGC_PEERS_UNATTRIBUTED.value), true));
        // "A member": the entry names nobody.
        assertTrue(plain(incoming(utc(2026, 11, 21, 12, 0)), false));
        assertTrue(plain(null, true));
    }

    @Test
    public void the_plain_tile_draws_neither_initials_nor_a_tone()
    {
        final String tile = code("AirTileView.java");
        assertTrue(bare(body(tile, "void bind(")).contains(
                bare("case UNCONFIRMED: initials.setVisibility(View.GONE); icon.setVisibility(View.GONE); break;")));
        // Nor a photo, and showPhoto(false) does not bring initials back.
        assertTrue(bare(body(tile, "private boolean glyphOnly(")).contains("kind==Kind.UNCONFIRMED"));
        // The sunken ground with its line, as every tile of a person: neither the ink of ours nor a dashed outline.
        assertTrue(bare(body(tile, "protected void onDraw(")).contains(
                bare("fill.setColor(AirUi.color(context, kind == Kind.ME ? R.color.kq_ink : R.color.kq_sunken));")));
        assertTrue(bare(body(code("AirTranscript.java"), "static void bindAuthorTile(")).contains(
                bare("tile.bind(model.plain ? AirTileView.Kind.UNCONFIRMED : AirTileView.Kind.PERSON,"
                     + " model.plain ? \"\" : model.name, model.tone);")));
    }

    // ---- the tap ----

    @Test
    public void the_tile_opens_the_member_as_the_name_does_and_the_plain_tile_opens_nobody()
    {
        final String bind = bare(body(code("AirTranscript.java"), "static void bindAuthorTile("));
        assertTrue(bind.contains(bare("final View.OnClickListener open = (model == null || model.plain) ? null : model.open;")));
        assertTrue(bind.contains("tile.setOnClickListener(open);"));
        // A long press on the tile is the entry's (the message's actions), as on the name.
        assertTrue(bind.contains(bare("tile.setOnLongClickListener(open != null && row != null ? v -> {"
                                      + " row.performLongClick(); return true; } : null);")));
        // A recycled tile that opened a member does not stay a button; without a tap the touch goes to the entry.
        assertTrue(bind.contains("tile.setClickable(open!=null);"));
        assertTrue(bind.contains("tile.setLongClickable(open!=null&&row!=null);"));

        // The text entry hands the tile the name's own listener, worked out by the name's rule.
        final String entry = bare(body(code("GroupMessageListHolder_text_incoming_not_read.java"),
                                       "private void bindEntry("));
        assertTrue(entry.contains(bare("tile = new AirTranscript.AuthorTile(m.id, should_show_plain_author_tile(m,"
                                       + " tileName != null), tileName, tileTone, openAuthor);")));
        // KHANDAQ (owner, 28.11.2026): the entry no longer asks whether relayed history was signed.
        assertFalse(entry.contains("should_mark_unverified_sender"));
        assertFalse(entry.contains("NgcSignedHistory.isAuthorVerified"));
        assertTrue(entry.contains(bare("AirTranscript.bindAuthorTile(entry_tile, entry_body, layout_message_container,"
                                       + " m, position, tile);")));
        // After the entry is drawn, as the name's tap.
        assertTrue(entry.indexOf("AirTranscript.bindGroupText(") < entry.indexOf("AirTranscript.bindAuthorTile("));
        // A service line has none.
        assertTrue(entry.contains(bare("AirTranscript.AuthorTile tile = null; if (!is_system_message)")));
        // The tile takes the tone of the name over the entry.
        assertTrue(entry.contains(bare("tileTone = AirModel.Tone.index(keyless ? name : peerPubkey);"
                                       + " entry.authorColor = AirUi.toneColor(context, tileTone);")));

        // The file entry does the same with the name's listener of its own.
        final String file = bare(body(code("GroupMessageListHolder_file_incoming_state_cancel.java"),
                                      "public void bindMessageList("));
        assertTrue(file.contains(bare("AirTranscript.bindAuthorTile(file_tile, file_body, layout_message_container, m,"
                                      + " this.getAdapterPosition(), is_system_message ? null : new"
                                      + " AirTranscript.AuthorTile( m.id, GroupMessageListHolder_text_incoming_not_read"
                                      + " .should_show_plain_author_tile(m, author_named), tile_name,"
                                      + " AirModel.Tone.index(tone_key), open_author));")));
    }

    // ---- the rows ----

    @Test
    public void the_text_entry_has_the_tile_before_the_text_gone_by_default()
    {
        // air_entry_text is the row of every text: of a group, ours, and the conversation with one person. Gone,
        // the tile leaves each of them as it was; only a group entry of another member puts it.
        checkRow("src/main/res/layout/air_entry_text.xml", "@+id/air_entry_tile", "@+id/air_entry_body");
    }

    @Test
    public void the_file_entries_of_a_group_have_it_too()
    {
        checkRow("src/main/res/layout/message_group_list_ft_incoming.xml", "@+id/air_file_tile", "@+id/air_file_body");
        checkRow("src/main/res/layout/message_group_list_ft_incoming_compact.xml", "@+id/air_file_tile",
                 "@+id/air_file_body");
    }

    @Test
    public void the_column_takes_from_the_text_what_the_photo_box_leaves_for_it()
    {
        // The column is the tile and 8 after it, from the 16 margin: 28 + 8 (owner, 28.11.2026: no time column).
        assertEquals(28.0, dp("@dimen/kq_size_tile_compact"), 0.0);
        assertEquals(8.0, dp("@dimen/kq_space_8"), 0.0);
        final String room = bare(body(code("AirTranscript.java"), "static int authorTileRoom("));
        assertTrue(room.contains(bare("return res.getDimensionPixelSize(R.dimen.kq_size_tile_compact)"
                                      + " + res.getDimensionPixelSize(R.dimen.kq_space_8);")));
        // A photo or a video is sized for the screen less what stands beside it: now the column too, or the box of
        // a wide photo ran past the end of the narrower column; and the meta beside it, unless a caption takes it.
        final String box = bare(body(code("AirFileEntry.java"), "private static void box("));
        assertTrue(box.contains(bare("final int column = AirTranscript.authorTileRoom(v.tile) + (visible(v.caption)"
                                     + " ? 0 : metaRoom(v, e));")));
    }

    @Test
    public void a_neighbour_that_comes_or_goes_puts_the_tile_again()
    {
        // An insert, a removal or a change of a row redraws its neighbours with the caption payload: a text entry
        // takes the full bind for it now, a file entry puts its tile in AirFileEntry.bindGroup.
        final String payloads = bare(body(code("GroupMessagelistAdapter.java"),
                                          "public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder,"));
        assertTrue(payloads.contains(bare("if ((selection || caption) && AirTranscript.isEntry(holder.itemView))"
                                          + " { partial = false; }")));
        assertTrue(payloads.contains(bare("AirFileEntry.bindGroup(holder.itemView, m, position);")));
        final String group = bare(body(code("AirFileEntry.java"), "static void bindGroup("));
        assertTrue(group.contains(bare("AirTranscript.placeAuthorTile(views.tile, views.body, m, position);")));
        // Before the frame and the state, which size the box of a photo beside the column.
        assertTrue(group.indexOf("AirTranscript.placeAuthorTile(") < group.indexOf("frame("));
    }

    @Test
    public void only_another_members_group_entry_draws_a_tile()
    {
        for (final String name : new String[]{"GroupMessageListHolder_text_outgoing_read.java",
                                              "GroupMessageListHolder_file_outgoing_state_cancel.java",
                                              "MessageListHolder_text_incoming_not_read.java",
                                              "MessageListHolder_text_outgoing_read.java",
                                              "MessageListHolder_text_outgoing_not_read.java",
                                              "MessageListHolder_file_incoming_state_cancel.java",
                                              "AirFileEntry.java",
                                              "AirChatPanel.java"})
        {
            assertFalse(name, code(name).contains("bindAuthorTile"));
        }
        // The conversation with one person draws its entries in bindText, which has no tile.
        assertFalse(body(code("AirTranscript.java"), "static void bindText(").contains("Tile"));
    }

    // ---- the layouts ----

    /**
     * The tile stands first in the row, at the 16 margin, before the content, 28 square, level with the bottom of
     * the row, gone until a group entry puts it, out of TalkBack's way; start and end only, so right to left mirrors
     * it. KHANDAQ (owner, 28.11.2026): no time column before it and no margin of its own; the content has none by
     * default either (8 after the tile, AirTranscript.placeAuthorTile).
     */
    private static void checkRow(final String layout, final String tileId, final String bodyId)
    {
        final Document row = xml(layout);
        final Element tile = byId(row, tileId);
        assertEquals("com.zoffcc.applications.trifa.AirTileView", tile.getTagName());
        assertEquals("@dimen/kq_size_tile_compact", tile.getAttribute("android:layout_width"));
        assertEquals("@dimen/kq_size_tile_compact", tile.getAttribute("android:layout_height"));
        assertEquals("", tile.getAttribute("android:layout_marginStart"));
        assertEquals("bottom", tile.getAttribute("android:layout_gravity"));
        assertEquals("gone", tile.getAttribute("android:visibility"));
        assertEquals("no", tile.getAttribute("android:importantForAccessibility"));
        assertNoLeftOrRight(layout, tile);

        assertEquals("nothing before the tile", null, previousElement(tile));
        final Element body = nextElement(tile);
        assertNotNull(body);
        assertEquals(bodyId, body.getAttribute("android:id"));
        assertEquals("", body.getAttribute("android:layout_marginStart"));
        assertEquals("", body.getAttribute("android:layout_marginEnd"));
        assertEquals("1", body.getAttribute("android:layout_weight"));
        assertEquals("the mark column is gone", null, nextElement(body));
        assertNoLeftOrRight(layout, body);
        final Element container = (Element) tile.getParentNode();
        assertEquals("@+id/layout_message_container", container.getAttribute("android:id"));
        assertEquals("horizontal", container.getAttribute("android:orientation"));
    }

    private static void assertNoLeftOrRight(final String layout, final Element element)
    {
        final NamedNodeMap attributes = element.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++)
        {
            final Node attribute = attributes.item(i);
            final String said = (attribute.getNodeName() + "=" + attribute.getNodeValue()).toLowerCase(Locale.ROOT);
            assertFalse(layout + ": " + said, said.contains("left") || said.contains("right"));
        }
    }

    private static Element previousElement(final Node node)
    {
        Node at = node.getPreviousSibling();
        while (at != null && at.getNodeType() != Node.ELEMENT_NODE)
        {
            at = at.getPreviousSibling();
        }
        return (Element) at;
    }

    private static Element nextElement(final Node node)
    {
        Node at = node.getNextSibling();
        while (at != null && at.getNodeType() != Node.ELEMENT_NODE)
        {
            at = at.getNextSibling();
        }
        return (Element) at;
    }

    private static Element byId(final Document layout, final String id)
    {
        final NodeList nodes = layout.getElementsByTagName("*");
        Element found = null;
        for (int i = 0; i < nodes.getLength(); i++)
        {
            final Element element = (Element) nodes.item(i);
            if (id.equals(element.getAttribute("android:id")))
            {
                assertTrue("one view " + id, found == null);
                found = element;
            }
        }
        assertNotNull(id, found);
        return found;
    }

    /** A dimension of the design tokens in dp. */
    private static double dp(final String reference)
    {
        final String name = reference.substring("@dimen/".length());
        final Document dimens = xml("src/main/res/values/kq_dimens.xml");
        final NodeList nodes = dimens.getElementsByTagName("dimen");
        for (int i = 0; i < nodes.getLength(); i++)
        {
            final Element dimen = (Element) nodes.item(i);
            if (name.equals(dimen.getAttribute("name")))
            {
                final String value = dimen.getTextContent().trim();
                assertTrue(value, value.endsWith("dp"));
                return Double.parseDouble(value.substring(0, value.length() - 2));
            }
        }
        throw new AssertionError(reference + " not found");
    }

    // ---- sources ----

    private static String code(final String name)
    {
        return AntispamOnRequestTest.code(read("src/main/java/com/zoffcc/applications/trifa/" + name));
    }

    private static String body(final String text, final String head)
    {
        final int at = text.indexOf(head);
        assertTrue(head + " not found", at >= 0);
        final int open = text.indexOf('{', at);
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
        throw new AssertionError(head + " has no end");
    }

    private static String bare(final String source)
    {
        return source.replaceAll("\\s+", "");
    }

    private static Document xml(final String name)
    {
        try
        {
            return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file(name));
        }
        catch (Exception e)
        {
            throw new AssertionError("cannot read " + name, e);
        }
    }

    private static String read(final String name)
    {
        try
        {
            return new String(Files.readAllBytes(file(name).toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
        catch (IOException e)
        {
            throw new AssertionError(name, e);
        }
    }

    private static File file(final String name)
    {
        // Gradle runs unit tests from the module directory; an IDE may use the project root.
        for (final String root : new String[]{"", "app/"})
        {
            final File f = new File(root + name);
            if (f.isFile())
            {
                return f;
            }
        }
        throw new AssertionError(name + " not found from " + new File("").getAbsolutePath());
    }
}
