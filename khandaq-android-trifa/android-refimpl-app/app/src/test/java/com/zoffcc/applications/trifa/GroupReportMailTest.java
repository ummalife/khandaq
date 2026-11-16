package com.zoffcc.applications.trifa;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (0.2.57, SPEC-community part B, items 5 and 8): the Report mail about a message of a group is the
 * desktop's (aircommunity.cpp, reportMailUrl), byte for byte: support gets one mail from every platform. Non-ASCII
 * text is written here as escapes; the words themselves live in the resources only.
 */
public class GroupReportMailTest
{
    private static final String JAVA = "src/main/java/com/zoffcc/applications/trifa/";
    private static final String AUTHOR = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";
    private static final String OTHER = "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF";
    private static final long TIME = Instant.parse("2026-11-16T05:54:57.123Z").toEpochMilli();
    private static final String SMILE = "😀";
    private static final String HELLO_RU = "Привет";

    // ---- the mail (SPEC Tests: building the Report mail) ----

    @Test
    public void the_mail_names_the_group_the_author_the_time_and_the_text_as_the_desktop_does()
    {
        assertEquals("Report: Khandaq Community", GroupReportMail.subject("Khandaq Community"));
        assertEquals("Group: Khandaq Community\r\n" + "Author: ABCDEF01\r\n" + "Time: 2026-11-16T05:54:57Z\r\n"
                     + "Message:\r\n" + "Salam, how are you?",
                     GroupReportMail.body("Khandaq Community", AUTHOR, TIME, "Salam, how are you?"));
    }

    @Test
    public void the_desktops_sample_report_is_the_same_mail_here()
    {
        // aircommunity_test.cpp, aReportIsAMailToSupportWithTheMessage: 15:04:05 in Istanbul is 12:04:05 UTC.
        final long time = Instant.parse("2026-11-16T12:04:05Z").toEpochMilli();
        assertEquals("mailto:support@khandaq.org?subject=Report%3A%20Khandaq%20Community"
                     + "&body=Group%3A%20Khandaq%20Community%0D%0AAuthor%3A%200A1B2C3D%0D%0A"
                     + "Time%3A%202026-11-16T12%3A04%3A05Z%0D%0AMessage%3A%0D%0A" + "Buy%20followers%20now",
                     GroupReportMail.mailto("Khandaq Community",
                                            "0a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f9", time,
                                            "Buy followers now"));
        // everyPartOfTheReportIsEncoded: nothing in the link ends a field, starts another or reads as a space.
        final String group = "A&B=C #1\n+ Группа";
        final String text = "50% off & more=free?\nline two\r\nline three #tag +1 مرحبا "
                            + "你好 " + SMILE;
        final String link = GroupReportMail.mailto(group, OTHER, time, text);
        assertEquals(1, link.length() - link.replace("&", "").length());
        assertEquals(1, link.length() - link.replace("?", "").length());
        for (final String absent : new String[]{"#", "+", " ", "\n", "\r"})
        {
            assertFalse(absent, link.contains(absent));
        }
        for (final String present : new String[]{"%26", "%2B", "%23", "%25"})
        {
            assertTrue(present, link.contains(present));
        }
        assertEquals("Report: A&B=C #1 + Группа", GroupReportMail.subject(group));
        final String body = GroupReportMail.body(group, OTHER, time, text);
        assertTrue(body.startsWith("Group: A&B=C #1 + Группа\r\n"));
        assertTrue(body.endsWith("Message:\r\n50% off & more=free?\r\nline two\r\nline three #tag +1 "
                                 + "مرحبا 你好 " + SMILE));
        // An empty report is still a mail to support.
        assertEquals("Group: \r\nAuthor: unknown\r\nTime: \r\nMessage:\r\n", GroupReportMail.body(null, null, 0L, null));
    }

    @Test
    public void the_link_goes_to_support_with_the_subject_and_the_body_encoded()
    {
        assertEquals("mailto:support@khandaq.org?subject=Report%3A%20Khandaq%20Community&body="
                     + "Group%3A%20Khandaq%20Community%0D%0AAuthor%3A%20ABCDEF01%0D%0A"
                     + "Time%3A%202026-11-16T05%3A54%3A57Z%0D%0AMessage%3A%0D%0AHi",
                     GroupReportMail.mailto("Khandaq Community", AUTHOR, TIME, "Hi"));
    }

    @Test
    public void a_mail_app_reads_back_exactly_the_subject_and_the_body() throws UnsupportedEncodingException
    {
        final String tricky = "a&b=c+d%e #f?g/h\n" + SMILE + " " + HELLO_RU + "\r\nend";
        final String link = GroupReportMail.mailto("Group & Co", OTHER, TIME, tricky);
        assertTrue(link.startsWith("mailto:" + GroupReportMail.ADDRESS + "?"));
        final Map<String, String> query = new HashMap<>();
        for (final String pair : link.substring(link.indexOf('?') + 1).split("&"))
        {
            final int eq = pair.indexOf('=');
            query.put(pair.substring(0, eq), URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
        }
        assertEquals(2, query.size());
        assertEquals(GroupReportMail.subject("Group & Co"), query.get("subject"));
        assertEquals(GroupReportMail.body("Group & Co", OTHER, TIME, tricky), query.get("body"));
    }

    @Test
    public void the_time_is_utc_whatever_the_phone_zone()
    {
        final TimeZone zone = TimeZone.getDefault();
        try
        {
            for (final String id : new String[]{"Europe/Istanbul", "America/Los_Angeles", "Asia/Kolkata"})
            {
                TimeZone.setDefault(TimeZone.getTimeZone(id));
                assertEquals(id, "2026-11-16T05:54:57Z", GroupReportMail.isoUtc(TIME));
            }
        }
        finally
        {
            TimeZone.setDefault(zone);
        }
    }

    @Test
    public void the_author_is_the_first_8_characters_of_their_key_in_upper_case()
    {
        assertEquals("ABCDEF01", GroupReportMail.authorPrefix(AUTHOR));
        assertEquals("ABCDEF01", GroupReportMail.authorPrefix(AUTHOR.toUpperCase(java.util.Locale.ROOT)));
    }

    @Test
    public void a_message_without_a_stored_key_names_its_author_unknown_never_a_guess()
    {
        for (final String noKey : new String[]{null, "", "-1", "abc", AUTHOR.substring(1), "g" + AUTHOR.substring(1),
                                               AUTHOR + "0"})
        {
            assertEquals(String.valueOf(noKey), "unknown", GroupReportMail.authorPrefix(noKey));
        }
        assertEquals("Group: Khandaq Community\r\nAuthor: unknown\r\nTime: 2026-11-16T05:54:57Z\r\nMessage:\r\nhi",
                     GroupReportMail.body("Khandaq Community", null, TIME, "hi"));
        assertTrue(GroupReportMail.mailto("G", null, 0L, "x").contains("Author%3A%20unknown%0D%0A"));
    }

    @Test
    public void the_author_is_the_key_stored_with_the_message_never_looked_up_from_a_member_number()
    {
        // toxcore gives the number of a member who left to the next one who joins: a lookup by number would
        // name an innocent newcomer.
        final String helper = read(JAVA + "HelperMessageReport.java");
        assertTrue(helper.contains("GroupReportMail.mailto(groupName, m.tox_group_peer_pubkey, time, text)"));
        assertTrue(helper.contains("GroupReportMail.body(groupName, m.tox_group_peer_pubkey, time, text)"));
        assertTrue(helper.contains("GroupReportMail.reportable(m.direction, m.tox_group_peer_pubkey, ownGroupKey)"));
        for (final String lookup : new String[]{"tox_group_peer_get_public_key", "tox_group_peer_by_public_key",
                                                "peer_id", "peer_number"})
        {
            assertFalse(lookup, helper.contains(lookup));
        }
    }

    @Test
    public void the_text_goes_up_to_500_characters_and_no_character_is_cut_in_half()
    {
        assertEquals(500, GroupReportMail.TEXT_LIMIT);
        assertEquals(repeat('a', 500), GroupReportMail.firstCharacters(repeat('a', 600), 500));
        assertEquals("short", GroupReportMail.firstCharacters("short", 500));
        assertEquals("", GroupReportMail.firstCharacters(null, 500));
        // A pair of surrogates is one character: the 500th character comes whole, the 501st not at all.
        assertEquals(repeat('a', 499) + SMILE, GroupReportMail.firstCharacters(repeat('a', 499) + SMILE + "b", 500));
        assertEquals(repeat('a', 500), GroupReportMail.firstCharacters(repeat('a', 500) + SMILE, 500));
        final StringBuilder smiles = new StringBuilder();
        for (int i = 0; i < 600; i++)
        {
            smiles.append(SMILE);
        }
        assertEquals(1000, GroupReportMail.firstCharacters(smiles.toString(), 500).length());
        final String body = GroupReportMail.body("G", AUTHOR, TIME, repeat('x', 700));
        assertTrue(body.endsWith("Message:\r\n" + repeat('x', 500)));
    }

    @Test
    public void every_line_of_the_mail_ends_with_crlf()
    {
        assertEquals("one\r\ntwo\r\nthree\r\nfour", GroupReportMail.mailLines("one\ntwo\r\nthree\rfour"));
        final String body = GroupReportMail.body("G", AUTHOR, TIME, "one\ntwo\r\nthree\rfour\n");
        assertFalse(body.replace("\r\n", "").contains("\n"));
        assertFalse(body.replace("\r\n", "").contains("\r"));
        assertTrue(body.endsWith("one\r\ntwo\r\nthree\r\nfour\r\n"));
    }

    @Test
    public void the_group_name_is_trimmed_and_its_runs_of_white_space_are_one_space()
    {
        assertEquals("Report: Khandaq Community", GroupReportMail.subject("  Khandaq\n\t Community \r\n"));
        assertEquals("Khandaq Community", GroupReportMail.simplified("Khandaq  Community\u0085"));
        assertEquals("", GroupReportMail.simplified(" \t\n"));
        assertEquals("", GroupReportMail.simplified(null));
        assertTrue(GroupReportMail.body("Khandaq\nCommunity", AUTHOR, TIME, "").startsWith(
                "Group: Khandaq Community\r\nAuthor: "));
    }

    @Test
    public void encoding_is_the_utf_8_of_every_character_but_the_unreserved_ones()
    {
        assertEquals("AZaz09-._~", GroupReportMail.percentEncoded("AZaz09-._~"));
        assertEquals("%20%26%3D%3F%2B%23%25%2F%3A%40%21%2A%27%28%29%2C%3B",
                     GroupReportMail.percentEncoded(" &=?+#%/:@!*'(),;"));
        assertEquals("%0D%0A", GroupReportMail.percentEncoded("\r\n"));
        assertEquals("%D0%9F%D1%80%D0%B8%D0%B2%D0%B5%D1%82", GroupReportMail.percentEncoded(HELLO_RU));
        assertEquals("%F0%9F%98%80", GroupReportMail.percentEncoded(SMILE));
        // A surrogate without its pair is the replacement character, as Qt writes it.
        assertEquals("%EF%BF%BD", GroupReportMail.percentEncoded("\uD83D"));
        // The same bytes as the JDK's form encoding, once its "+" for a space and its kept "*" and encoded "~"
        // are put as RFC 3986 has them.
        for (final String text : new String[]{"Report: Khandaq Community", "xé举报إ" + SMILE,
                                               "line one\r\nline two", "100% & more"})
        {
            assertEquals(text, jdkPercentEncoded(text), GroupReportMail.percentEncoded(text));
        }
    }

    // ---- who can be reported ----

    @Test
    public void only_a_message_that_came_in_from_another_member_can_be_reported()
    {
        assertTrue(GroupReportMail.reportable(0, AUTHOR, null));
        assertTrue(GroupReportMail.reportable(0, AUTHOR, OTHER));
        // Our own message, by its direction or by our key in the group.
        assertFalse(GroupReportMail.reportable(1, AUTHOR, null));
        assertFalse(GroupReportMail.reportable(1, null, null));
        assertFalse(GroupReportMail.reportable(0, AUTHOR, AUTHOR.toUpperCase(java.util.Locale.ROOT)));
        // The app's own lines.
        assertEquals("-1", TRIFAGlobals.TRIFA_SYSTEM_MESSAGE_PEER_PUBKEY);
        assertFalse(GroupReportMail.reportable(0, TRIFAGlobals.TRIFA_SYSTEM_MESSAGE_PEER_PUBKEY, null));
        assertFalse(GroupReportMail.reportable(0, TRIFAGlobals.TRIFA_SYSTEM_MESSAGE_PEER_PUBKEY, OTHER));
        // A message that came in without its author's key stored: reported, its author named unknown.
        assertTrue(GroupReportMail.reportable(0, null, null));
        assertTrue(GroupReportMail.reportable(0, null, OTHER));
        assertTrue(GroupReportMail.reportable(0, "", OTHER));
        assertTrue(GroupReportMail.reportable(0, AUTHOR.substring(1), OTHER));
    }

    // ---- the action ----

    @Test
    public void report_is_an_action_of_one_message_in_a_group_and_opens_the_mail_app()
    {
        final String menu = read("src/main/res/menu/toolbar_message_activity.xml");
        final int item = menu.indexOf("android:id=\"@+id/action_report\"");
        assertTrue(item > 0);
        final String report = menu.substring(item, menu.indexOf("/>", item));
        assertTrue(report.contains("android:title=\"@string/chat_report_action\""));
        assertTrue("hidden until a message that can be reported is selected",
                   report.contains("android:visible=\"false\""));

        final String mode = read(JAVA + "ToolbarActionMode.java");
        assertTrue(bare(mode).contains("reportItem.setVisible(HelperMessageReport.canReportCurrentSelection());"));
        final String click = bare(mode.substring(mode.indexOf("public boolean onActionItemClicked(")));
        assertTrue(click.startsWith("publicbooleanonActionItemClicked(ActionModemode,MenuItemitem){"
                                    + "if(item.getItemId()==R.id.action_report){"));
        assertTrue(click.contains("HelperMessageReport.reportSelectedGroupMessage(context);mode.finish();returnfalse;}"));

        final String helper = read(JAVA + "HelperMessageReport.java");
        assertTrue(helper.contains("new Intent(Intent.ACTION_SENDTO, Uri.parse(link))"));
        assertTrue(helper.contains("GroupReportMail.mailto("));
        assertTrue(helper.contains("MainActivity.selected_group_messages.size() != 1"));
        // No mail app: the words the app has for that already, under the field.
        assertTrue(helper.contains("R.string.MainActivity_no_email_app_found"));
    }

    @Test
    public void report_is_in_the_languages_of_the_message_actions()
    {
        assertEquals("Report", stringOf("values", "chat_report_action"));
        assertEquals("Пожаловаться",
                     stringOf("values-ru", "chat_report_action"));
        assertEquals("إبلاغ", stringOf("values-ar", "chat_report_action"));
        assertEquals("举报", stringOf("values-zh-rCN", "chat_report_action"));
        // Where the message actions are translated, Report is too.
        for (final String folder : new String[]{"values-ru", "values-ar", "values-zh-rCN"})
        {
            stringOf(folder, "chat_react_action");
        }
    }

    private static String jdkPercentEncoded(final String text)
    {
        try
        {
            return URLEncoder.encode(text, "UTF-8").replace("+", "%20").replace("*", "%2A").replace("%7E", "~");
        }
        catch (UnsupportedEncodingException e)
        {
            throw new AssertionError(e);
        }
    }

    private static String repeat(final char c, final int count)
    {
        final StringBuilder out = new StringBuilder(count);
        for (int i = 0; i < count; i++)
        {
            out.append(c);
        }
        return out.toString();
    }

    private static String stringOf(final String folder, final String name)
    {
        final String strings = read("src/main/res/" + folder + "/strings.xml");
        final String open = "<string name=\"" + name + "\">";
        final int start = strings.indexOf(open);
        assertTrue(folder + " " + name, start >= 0);
        return strings.substring(start + open.length(), strings.indexOf("</string>", start));
    }

    private static String bare(final String source)
    {
        return source.replaceAll("\\s+", "");
    }

    private static String read(final String name)
    {
        // Gradle runs unit tests from the module directory; an IDE may use the project root.
        for (final String root : new String[]{"", "app/"})
        {
            final File f = new File(root + name);
            if (f.isFile())
            {
                try
                {
                    return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
                }
                catch (IOException e)
                {
                    throw new AssertionError(name, e);
                }
            }
        }
        throw new AssertionError(name + " not found from " + new File("").getAbsolutePath());
    }
}
