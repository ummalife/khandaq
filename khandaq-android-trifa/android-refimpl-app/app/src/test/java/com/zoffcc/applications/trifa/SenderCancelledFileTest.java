package com.zoffcc.applications.trifa;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (inbox #36): a file of theirs that its sender cancelled says so on this phone. Testers read
 * "The download stopped" with nothing to do about it and could not tell why. The handler of their CANCEL is the
 * only way to the mark, a new offer of the same file clears it, and the mark is a column of the message, so a
 * restart keeps it. The rows bind views, so these read the sources (comments left out).
 */
public class SenderCancelledFileTest
{
    @Test
    public void only_their_cancel_of_a_file_of_theirs_marks_it() throws Exception
    {
        final String helper = code("trifa/HelperFiletransfer.java");
        final String cancel = body(helper, "static void cancel_filetransfer(");
        final String incoming = body(cancel, "if (f.direction == TRIFA_FT_DIRECTION_INCOMING.value)");
        final String file = body(incoming, "if ((f.kind == TOX_FILE_KIND_DATA.value) || (f.kind == TOX_FILE_KIND_FTV2.value))");
        final int mark = file.indexOf("mark_incoming_cancelled_there(msg_id);");
        assertTrue(file, mark >= 0);
        // After the entry is cancelled and before it is drawn again, so the redraw reads the mark.
        assertTrue(mark > file.indexOf("HelperMessage.set_message_state_from_id(msg_id, TOX_FILE_CONTROL_CANCEL.value);"));
        assertTrue(mark < file.indexOf("HelperMessage.update_single_message_from_messge_id(msg_id, true);"));
        // Not an avatar, not a file of ours, and nowhere else: our own cancel or a failure here is not theirs.
        assertEquals(1, count(cancel, "mark_incoming_cancelled_there("));
        assertEquals(2, count(helper, "mark_incoming_cancelled_there("));
        assertEquals(1, count(code("trifa/MainActivity.java"), "HelperFiletransfer.cancel_filetransfer("));
    }

    @Test
    public void the_mark_is_written_to_its_entry() throws Exception
    {
        final String mark = bare(body(code("trifa/HelperFiletransfer.java"), "static void mark_incoming_cancelled_there("));
        assertTrue(mark, mark.startsWith("if(msg_id<0){return;}"));
        assertTrue(mark, mark.contains("orma.updateMessage().idEq(msg_id).ft_cancelled_there(true).execute();"));
    }

    @Test
    public void an_offer_of_it_again_clears_the_mark() throws Exception
    {
        final String offer = bare(body(code("trifa/HelperFiletransfer.java"), "static synchronized boolean resume_incoming_offer("));
        assertTrue(offer, offer.contains("orma.updateMessage().idEq(m.id).filetransfer_id(f.id).filetransfer_kind(kind)"
                                         + ".state(TOX_FILE_CONTROL_PAUSE.value).ft_accepted(accepted_before)"
                                         + ".ft_cancelled_there(false).execute();"));
    }

    @Test
    public void its_entry_says_the_sender_cancelled_it_with_no_alarm_and_no_retry() throws Exception
    {
        final String apply = body(code("trifa/AirFileEntry.java"), "static void applyDirectState(");
        final int failed = apply.indexOf("case FAILED:");
        assertTrue(failed >= 0);
        final String branch = bare(apply.substring(failed, apply.indexOf("case DONE:", failed)));
        // First, before the sender being offline: a cancelled file does not come when they are back.
        assertTrue(branch, branch.startsWith("caseFAILED:if(!outgoing&&m.ft_cancelled_there)"
                                             + "{e.note=context.getString(R.string.air_file_cancelled_there);}"
                                             + "elseif(!outgoing&&!online)"));
    }

    @Test
    public void the_mark_has_its_own_column_from_the_last_schema_step() throws Exception
    {
        final String main = code("trifa/MainActivity.java");
        final Matcher version = Pattern.compile("ORMA_CURRENT_DB_SCHEMA_VERSION\\s*=\\s*(\\d+)\\s*;").matcher(main);
        assertTrue(version.find());
        final int current = Integer.parseInt(version.group(1));
        final String upgrade = body(main, "upgrade_db_schema_do(");
        int last = -1;
        final Matcher step = Pattern.compile("new_version\\s*==\\s*(\\d+)").matcher(upgrade);
        while (step.find())
        {
            last = Math.max(last, Integer.parseInt(step.group(1)));
        }
        assertEquals("every schema step needs the version raised to it", last, current);
        final String column = bare(body(upgrade, "if (new_version == " + current + ")"));
        assertTrue(column, column.contains("run_multi_sql(\"ALTERTABLEMessageADDCOLUMNft_cancelled_thereBOOLEANNOTNULLDEFAULTfalse\");"));
    }

    @Test
    public void every_column_of_a_message_is_written_read_and_copied() throws Exception
    {
        final String message = code("sorm/Message.java");
        final String insert = body(message, "public long insert()");
        final int values = insert.indexOf("\"values\"");
        assertTrue(values > 0);

        final List<String> columns = new ArrayList<>();
        final Matcher literal = Pattern.compile("\"([^\"]*)\"").matcher(insert.substring(0, values));
        while (literal.find())
        {
            final String name = literal.group(1).replaceFirst("^,", "");
            if (name.matches("[A-Za-z_][A-Za-z0-9_]*"))
            {
                columns.add(name);
            }
        }
        assertTrue(columns.toString(), columns.contains("ft_cancelled_there"));

        final List<Integer> places = new ArrayList<>();
        final Matcher place = Pattern.compile("\\?(\\d+)").matcher(insert.substring(values, insert.indexOf("prepareStatement")));
        while (place.find())
        {
            places.add(Integer.parseInt(place.group(1)));
        }
        final List<String> bound = new ArrayList<>();
        final Matcher bind = Pattern.compile("insert_pstmt\\.set\\w+\\((\\d+),\\s*this\\.(\\w+)\\)").matcher(insert);
        while (bind.find())
        {
            assertEquals("bind order", bound.size() + 1, Integer.parseInt(bind.group(1)));
            bound.add(bind.group(2));
        }

        assertEquals("one place per column", columns.size(), places.size());
        for (int i = 0; i < places.size(); i++)
        {
            assertEquals("places run 1..n", i + 1, (int) places.get(i));
        }
        assertEquals("each column bound from its own field, in order", columns, bound);

        final String read = bare(message);
        final String copy = bare(body(message, "static Message deep_copy(Message in)"));
        for (final String c : columns)
        {
            assertTrue("read " + c, Pattern.compile("out\\." + c + "=rs\\.get\\w+\\(\"" + c + "\"\\);").matcher(read).find());
            assertTrue("copy " + c, copy.contains("out." + c + "=in." + c + ";"));
        }
        assertTrue(read.contains("publicMessageft_cancelled_there(booleanft_cancelled_there)"));
    }

    @Test
    public void the_words_are_there_in_every_language_of_the_entries() throws Exception
    {
        final String[][] words = {
                {"values", "The sender cancelled it"},
                {"values-ru", "Отправитель отменил отправку"},
                {"values-ar", "ألغى المرسل الإرسال"},
                {"values-zh-rCN", "发送方已取消发送"}};
        for (final String[] w : words)
        {
            final String xml = resource(w[0] + "/strings_air_files.xml");
            assertTrue(w[0], xml.contains("<string name=\"air_file_cancelled_there\">" + w[1] + "</string>"));
        }
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

    private static String code(final String path) throws java.io.IOException
    {
        return AntispamOnRequestTest.code(read("java/com/zoffcc/applications/" + path));
    }

    private static String resource(final String path) throws java.io.IOException
    {
        return read("res/" + path);
    }

    private static String bare(final String text)
    {
        return text.replaceAll("\\s+", "");
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

    private static String read(final String path) throws java.io.IOException
    {
        // Gradle runs unit tests from the module directory; an IDE may use the project root.
        for (final String root : new String[]{"src/main/", "app/src/main/"})
        {
            final java.io.File file = new java.io.File(root + path);
            if (file.isFile())
            {
                return new String(java.nio.file.Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError(path + " not found from " + new java.io.File("").getAbsolutePath());
    }
}
