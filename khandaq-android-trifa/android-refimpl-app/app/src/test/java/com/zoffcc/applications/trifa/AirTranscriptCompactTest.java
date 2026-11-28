package com.zoffcc.applications.trifa;

import com.zoffcc.applications.sorm.GroupMessage;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * KHANDAQ (owner, 28.11.2026): the conversation, with one person and in a group, without the time column and
 * without the "sender not verified" line. An entry starts at the 16 margin (a group member's entry after the tile's
 * column), its text runs to the other margin, and its meta (edited, the time, our mark) is at its trailing edge,
 * placed by AirEntryMetaLayout (its geometry: AirEntryMetaLayoutTest). The layouts and the wiring are read from the
 * resources and the sources, as the other audit tests do: the app's views cannot be inflated in a JVM test here.
 */
public class AirTranscriptCompactTest
{
    private static final String RES = "src/main/res/";
    private static final String JAVA = "src/main/java/com/zoffcc/applications/trifa/";
    private static final String META_LAYOUT = "com.zoffcc.applications.trifa.AirEntryMetaLayout";
    private static final String ENTRY_TEXT = "com.zoffcc.applications.trifa.AirEntryTextView";
    private static final String[] FILE_LAYOUTS = {"message_list_ft_incoming.xml", "message_list_ft_incoming_compact.xml",
                                                  "message_list_ft_outgoing.xml", "message_list_ft_outgoing_compact.xml",
                                                  "message_group_list_ft_incoming.xml",
                                                  "message_group_list_ft_incoming_compact.xml"};
    private static final String[] LANGUAGES = {"values", "values-ru", "values-ar", "values-zh-rCN"};

    // ---- no time column ----

    @Test
    public void no_entry_has_a_time_column_or_a_mark_column()
    {
        final List<String> layouts = new ArrayList<>();
        layouts.add("air_entry_text.xml");
        for (final String file : FILE_LAYOUTS)
        {
            layouts.add(file);
        }
        for (final String name : layouts)
        {
            final String source = read(RES + "layout/" + name);
            assertFalse(name, source.contains("kq_size_time_column_phone"));
            final Document layout = xml(RES + "layout/" + name);
            final Element container = byId(layout, "@+id/layout_message_container");
            // The row is the tile's column, when a group has one, and the content: no time before, no mark after.
            for (final Element child : children(container))
            {
                final String id = child.getAttribute("android:id");
                assertFalse(name + ": " + id, "@+id/date_time".equals(id) || "@+id/m_icon".equals(id));
            }
            // The time and the mark are the meta of the content, in the layout that places it.
            final Element time = byId(layout, "@+id/date_time");
            final Element mark = byId(layout, "@+id/m_icon");
            assertEquals(name, META_LAYOUT, ((Element) time.getParentNode()).getTagName());
            assertEquals(name, META_LAYOUT, ((Element) mark.getParentNode()).getTagName());
            assertEquals(name, "wrap_content", time.getAttribute("android:layout_width"));
            // The caption type, ink-3, tabular figures, as the column had them.
            assertEquals(name, "@style/TextAppearance.KQ.Caption", time.getAttribute("android:textAppearance"));
            assertEquals(name, "@color/kq_ink_3", time.getAttribute("android:textColor"));
            assertEquals(name, "tnum", time.getAttribute("android:fontFeatureSettings"));
            // A mark is gone until our own entry has one: no column waits for it.
            assertEquals(name, "gone", mark.getAttribute("android:visibility"));
            assertEquals(name, "@dimen/kq_size_mark", mark.getAttribute("android:layout_width"));
        }
    }

    @Test
    public void the_text_and_its_meta_share_one_layout_and_edited_joins_the_meta()
    {
        final Document entry = xml(RES + "layout/air_entry_text.xml");
        final Element frame = byId(entry, "@+id/air_entry_frame");
        assertEquals(META_LAYOUT, frame.getTagName());
        assertEquals("match_parent", frame.getAttribute("android:layout_width"));
        final List<Element> kids = children(frame);
        assertEquals(4, kids.size());
        assertEquals("@+id/m_text", kids.get(0).getAttribute("android:id"));
        assertEquals("@+id/air_entry_edited", kids.get(1).getAttribute("android:id"));
        assertEquals("@+id/date_time", kids.get(2).getAttribute("android:id"));
        assertEquals("@+id/m_icon", kids.get(3).getAttribute("android:id"));
        // The frame is the text's place: the quote over it and the reactions under it stay in the text block.
        final Element block = (Element) frame.getParentNode();
        assertEquals("@+id/text_block_group", block.getAttribute("android:id"));
        final List<Element> blockKids = children(block);
        assertEquals(frame, blockKids.get(blockKids.size() - 2));
        // Read more and Retry keep the row under the text; the edited word left it.
        final List<Element> meta = children(byId(entry, "@+id/air_entry_meta"));
        assertEquals(2, meta.size());
        assertEquals("@+id/air_entry_more", meta.get(0).getAttribute("android:id"));
        assertEquals("@+id/air_entry_retry", meta.get(1).getAttribute("android:id"));
        // The edited word in the caption type and ink-3, beside the time.
        final Element edited = kids.get(1);
        assertEquals("@style/TextAppearance.KQ.Caption", edited.getAttribute("android:textAppearance"));
        assertEquals("@color/kq_ink_3", edited.getAttribute("android:textColor"));
        assertEquals("gone", edited.getAttribute("android:visibility"));

        // The text runs to the 16 margin: no margins of the content, the container's padding 16 on both sides.
        final Element body = byId(entry, "@+id/air_entry_body");
        assertEquals("", body.getAttribute("android:layout_marginStart"));
        assertEquals("", body.getAttribute("android:layout_marginEnd"));
        final Element container = byId(entry, "@+id/layout_message_container");
        assertEquals("@dimen/kq_space_16", container.getAttribute("android:paddingStart"));
        assertEquals("@dimen/kq_space_16", container.getAttribute("android:paddingEnd"));
    }

    @Test
    public void a_paragraph_aligns_by_its_own_direction()
    {
        // So a right-to-left paragraph in a left-to-right screen ends at the trailing edge and its meta goes under it.
        // The view gives a paragraph of figures without a letter the left-to-right direction in every screen
        // (AirTextDirection; its geometry: AirEntryMetaLayoutTest).
        final Element text = byId(xml(RES + "layout/air_entry_text.xml"), "@+id/m_text");
        assertEquals("textStart", text.getAttribute("android:textAlignment"));
        assertEquals(ENTRY_TEXT, text.getTagName());
        for (final String name : FILE_LAYOUTS)
        {
            final Element caption = byId(xml(RES + "layout/" + name), "@+id/ft_caption_text");
            assertEquals(name, "textStart", caption.getAttribute("android:textAlignment"));
            assertEquals(name, ENTRY_TEXT, caption.getTagName());
        }
    }

    @Test
    public void a_file_entry_has_its_content_and_meta_in_one_layout_and_a_caption_takes_the_meta()
    {
        for (final String name : FILE_LAYOUTS)
        {
            final Document layout = xml(RES + "layout/" + name);
            final Element frame = byId(layout, "@+id/air_file_frame");
            assertEquals(name, META_LAYOUT, frame.getTagName());
            final List<Element> kids = children(frame);
            assertEquals(name, 3, kids.size());
            assertEquals(name, "@+id/ft_preview_container", kids.get(0).getAttribute("android:id"));
            assertEquals(name, "@+id/date_time", kids.get(1).getAttribute("android:id"));
            assertEquals(name, "@+id/m_icon", kids.get(2).getAttribute("android:id"));
            // The caption is the last view of the content, the line the meta may follow.
            final List<Element> content = children(kids.get(0));
            assertEquals(name, "@+id/ft_caption_text", content.get(content.size() - 1).getAttribute("android:id"));
            // The reactions stay under the content.
            assertTrue(name, read(RES + "layout/" + name).indexOf("air_file_frame")
                             < read(RES + "layout/" + name).indexOf("@layout/chat_reactions_row"));
        }
        final String file = bare(code(JAVA + "AirFileEntry.java"));
        assertTrue(file.contains(bare("v.frame.setTailText(v.caption);")));
        assertTrue(file.contains(bare("frame = root.findViewById(R.id.air_file_frame);")));
        // The photo box leaves the meta its room beside it, unless the caption takes the meta.
        assertTrue(file.contains(bare("final int column = AirTranscript.authorTileRoom(v.tile) + (visible(v.caption) ? 0"
                                      + " : metaRoom(v, e));")));
        assertTrue(file.contains(bare("room > 0 ? room + res.getDimensionPixelSize(R.dimen.kq_space_8) : 0")));
        assertFalse(file.contains("timeColumnWidth"));
        assertFalse(file.contains("firstLine("));
    }

    @Test
    public void the_day_and_the_unread_mark_start_at_the_16_margin()
    {
        assertEquals("16dp", dimen(RES + "values/air_dimens.xml", "air_entry_text_start"));
        for (final String name : new String[]{"air_entry_text.xml", "air_unread_mark.xml", FILE_LAYOUTS[0],
                                              FILE_LAYOUTS[2], FILE_LAYOUTS[4]})
        {
            assertTrue(name, read(RES + "layout/" + name).contains(
                    "android:paddingStart=\"@dimen/air_entry_text_start\""));
        }
        // Nothing moves the day mark after the time column any more; the unread mark reads where the day stands.
        final String transcript = code(JAVA + "AirTranscript.java");
        assertFalse(transcript.contains("dayRow"));
        assertFalse(bare(body(transcript, "private static void bindTime(")).contains("timeColumnWidth"));
        assertTrue(read(JAVA + "AirUnreadMark.java").contains("return day.getPaddingStart();"));
    }

    @Test
    public void the_in_transit_tray_keeps_its_time_column()
    {
        // Out of scope of the change: the tray above the field keeps its own layout.
        assertTrue(read(RES + "layout/air_tray_item.xml").contains("@dimen/kq_size_time_column_phone"));
        assertTrue(read(JAVA + "AirChatPanel.java").contains("AirTranscript.timeColumnWidth(time)"));
        assertEquals("38dp", dimen(RES + "values/kq_dimens.xml", "kq_size_time_column_phone"));
    }

    @Test
    public void a_mark_without_a_status_is_gone_not_kept_as_a_column()
    {
        final String show = bare(body(code(JAVA + "AirTranscript.java"), "private static void showMark("));
        assertTrue(show.contains(bare("mark.setVisibility(View.GONE); return;")));
        assertFalse(show.contains("View.INVISIBLE"));
        final String render = bare(body(code(JAVA + "AirFileEntry.java"), "private static void render("));
        assertTrue(render.contains(bare("if (e.status == AirConversationModel.EntryStatus.NONE) {"
                                        + " v.mark.setVisibility(View.GONE); }")));
    }

    @Test
    public void the_edited_word_is_in_the_meta_and_still_spoken()
    {
        final String transcript = code(JAVA + "AirTranscript.java");
        final String state = bare(body(transcript, "private static void bindState("));
        assertTrue(state.contains(bare("views.meta.setVisibility(failed || showsMore(views) ? View.VISIBLE : View.GONE);")));
        final String group = bare(body(transcript, "static void bindGroupText("));
        assertTrue(group.contains(bare("views.meta.setVisibility(showsMore(views) ? View.VISIBLE : View.GONE);")));
        assertTrue(bare(body(transcript, "static void bindText(")).contains(
                bare("views.edited.setVisibility(m.edited ? View.VISIBLE : View.GONE);")));
        // A call row is a body that is not a text; a group entry is always a text.
        assertTrue(bare(body(transcript, "static void bindText(")).contains(bare("views.frame.setTextBody(!callNote);")));
        assertTrue(group.contains(bare("views.frame.setTextBody(true);")));
        // What is spoken stays: author, time, quote, text, edited, status.
        assertTrue(bare(body(transcript, "static String spoken(")).contains(
                bare("append(sentence, context.getString(R.string.message_edited_marker));")));
    }

    // ---- no "sender not verified" ----

    @Test
    public void a_synced_unsigned_group_row_has_no_unverified_line_and_the_members_own_tile()
    {
        final GroupMessage row = new GroupMessage();
        row.direction = 0;
        row.was_synced = true;
        row.tox_group_peer_pubkey = "DFE8032B0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF012379E5";
        row.TRIFA_SYNC_TYPE = TRIFAGlobals.TRIFA_SYNC_TYPE.TRIFA_SYNC_TYPE_NGC_PEERS.value;
        // The row is still what the audit calls unverified, and still never notifies (the rule is kept)...
        assertTrue(GroupMessageListHolder_text_incoming_not_read.should_mark_unverified_sender(false, row, false));
        // ...but it is drawn as the member's other entries: their tile, their name, a tap that opens them.
        assertFalse(GroupMessageListHolder_text_incoming_not_read.should_show_plain_author_tile(row, true));
        assertFalse(GroupMessageListHolder_text_incoming_not_read.should_hide_claimed_author(false, row));

        // No line for it in the entry, and no field to carry one.
        assertFalse(read(RES + "layout/air_entry_text.xml").contains("air_entry_unverified"));
        try
        {
            AirTranscript.GroupEntry.class.getDeclaredField("senderUnverified");
            fail("GroupEntry still carries senderUnverified");
        }
        catch (NoSuchFieldException expected)
        {
            // gone
        }
        final String transcript = code(JAVA + "AirTranscript.java");
        assertFalse(transcript.contains("group_msg_sender_unverified"));
        assertFalse(transcript.contains("unverified"));
        // Nor in what TalkBack says for it: the sentence of a group entry names the private note and a mention only.
        final String state = bare(body(transcript, "private static void bindGroupState("));
        assertTrue(state.contains(bare("append(spokenText, entry.privateNote);")));
        assertTrue(state.contains(bare("append(spokenText, context.getString(R.string.air_group_mentions_you));")));
    }

    @Test
    public void the_strings_stay_in_every_language()
    {
        // Other code (the old bubble's marker) and KQ-03 still use them; removing them would churn four languages.
        for (final String folder : LANGUAGES)
        {
            final String strings = read(RES + folder + "/strings.xml");
            assertTrue(folder, strings.contains("<string name=\"group_msg_sender_unverified\">"));
            assertTrue(folder, read(RES + folder + "/strings_air.xml").contains(
                    "<string name=\"air_group_author_unconfirmed\">"));
        }
        // KQ-03 keeps "Author not confirmed" and its plain tile.
        final String entry = bare(body(code(JAVA + "GroupMessageListHolder_text_incoming_not_read.java"),
                                       "private void bindEntry("));
        assertTrue(entry.contains(bare("entry.author = context.getString(R.string.air_group_author_unconfirmed);")));
    }

    // ---- helpers ----

    private static List<Element> children(final Element parent)
    {
        final List<Element> found = new ArrayList<>();
        final NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++)
        {
            if (nodes.item(i).getNodeType() == Node.ELEMENT_NODE)
            {
                found.add((Element) nodes.item(i));
            }
        }
        return found;
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

    private static String dimen(final String file, final String name)
    {
        final NodeList nodes = xml(file).getElementsByTagName("dimen");
        for (int i = 0; i < nodes.getLength(); i++)
        {
            final Element dimen = (Element) nodes.item(i);
            if (name.equals(dimen.getAttribute("name")))
            {
                return dimen.getTextContent().trim();
            }
        }
        throw new AssertionError(name + " not found in " + file);
    }

    private static String code(final String name)
    {
        return AntispamOnRequestTest.code(read(name));
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
