package com.zoffcc.applications.trifa;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * The name and the ID of a plain profile file before it is moved in (redesign stage 4, E20). The ID is
 * checked against a real one: the demo contact's, whose key and antispam give its published check.
 */
public class ToxSaveSummaryTest
{
    private static final String DEMO_KEY = "872F45C1BAF8AC12C854003E6420C7459339056FF6E40E5BAD8B123AC397E117";
    private static final String DEMO_ID = DEMO_KEY + "B66A6A0E90B7";
    private static final int COOKIE = 0x01ce;

    @Test
    public void reads_the_id_and_the_name()
    {
        final ToxSaveSummary summary = ToxSaveSummary.read(save(nospamKeys(DEMO_KEY, "B66A6A0E"),
                                                                section(4, "Khandaq Demo".getBytes(StandardCharsets.UTF_8)),
                                                                section(255, new byte[0])));
        assertNotNull(summary);
        assertEquals(DEMO_ID, summary.toxId);
        assertEquals("Khandaq Demo", summary.name);
    }

    @Test
    public void a_name_in_any_script_and_no_name_at_all()
    {
        final ToxSaveSummary arabic = ToxSaveSummary.read(save(section(4, " بلال ".getBytes(StandardCharsets.UTF_8)),
                                                               nospamKeys(DEMO_KEY, "B66A6A0E")));
        assertNotNull(arabic);
        assertEquals("بلال", arabic.name);
        assertEquals(DEMO_ID, arabic.toxId);

        final ToxSaveSummary nameless = ToxSaveSummary.read(save(nospamKeys(DEMO_KEY, "B66A6A0E")));
        assertNotNull(nameless);
        assertEquals("", nameless.name);
    }

    @Test
    public void what_is_not_a_plain_profile_file_gives_nothing()
    {
        assertNull(ToxSaveSummary.read(null));
        assertNull(ToxSaveSummary.read(new byte[0]));
        assertNull(ToxSaveSummary.read("toxEsave and more bytes".getBytes(StandardCharsets.US_ASCII)));
        // The header alone, with no keys.
        assertNull(ToxSaveSummary.read(save(section(4, "Name".getBytes(StandardCharsets.UTF_8)))));
        // A section longer than what is left of the file.
        final byte[] whole = save(nospamKeys(DEMO_KEY, "B66A6A0E"));
        final byte[] cut = new byte[whole.length - 10];
        System.arraycopy(whole, 0, cut, 0, cut.length);
        assertNull(ToxSaveSummary.read(cut));
        // A keys section of the wrong size.
        assertNull(ToxSaveSummary.read(save(section(1, new byte[40]))));
    }

    @Test
    public void a_garbled_section_cookie_gives_nothing()
    {
        final byte[] bytes = save(nospamKeys(DEMO_KEY, "B66A6A0E"));
        bytes[8 + 6] = 0x00;
        assertNull(ToxSaveSummary.read(bytes));
    }

    // ---- building files the way toxcore writes them ----

    private static byte[] save(final byte[]... sections)
    {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeInt(out, 0);
        writeInt(out, 0x15ed1b1f);
        for (final byte[] section : sections)
        {
            out.write(section, 0, section.length);
        }
        return out.toByteArray();
    }

    private static byte[] nospamKeys(final String publicKeyHex, final String nospamHex)
    {
        final byte[] data = new byte[68];
        final byte[] nospam = hex(nospamHex);
        System.arraycopy(nospam, 0, data, 0, 4);
        System.arraycopy(hex(publicKeyHex), 0, data, 4, 32);
        // The secret key stays zero: it plays no part in the ID.
        return section(1, data);
    }

    private static byte[] section(final int type, final byte[] data)
    {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeInt(out, data.length);
        out.write(type & 0xff);
        out.write((type >> 8) & 0xff);
        out.write(COOKIE & 0xff);
        out.write((COOKIE >> 8) & 0xff);
        out.write(data, 0, data.length);
        return out.toByteArray();
    }

    private static void writeInt(final ByteArrayOutputStream out, final int value)
    {
        out.write(value & 0xff);
        out.write((value >> 8) & 0xff);
        out.write((value >> 16) & 0xff);
        out.write((value >> 24) & 0xff);
    }

    private static byte[] hex(final String text)
    {
        final byte[] bytes = new byte[text.length() / 2];
        for (int i = 0; i < bytes.length; i++)
        {
            bytes[i] = (byte) Integer.parseInt(text.substring(2 * i, 2 * i + 2), 16);
        }
        return bytes;
    }
}
