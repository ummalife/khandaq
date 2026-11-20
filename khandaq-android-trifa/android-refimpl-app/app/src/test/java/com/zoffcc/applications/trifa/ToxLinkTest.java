package com.zoffcc.applications.trifa;

import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (testers' ideas, 0.2.67): a tox: link opens New contact with the ID filled in, checked there as a pasted
 * ID is; one that comes before the unlock waits for it (ToxLink).
 */
public class ToxLinkTest
{
    private static final String JAVA = "src/main/java/com/zoffcc/applications/trifa/";
    /** A whole ID with a correct check (the one of AirNewContactModelTest and the iOS tests). */
    private static final String ADDRESS = "56A1ADE4B65B86BCD51CC73E2CD4E542179F47959FE3E0E21B4B0ACDADE51855D34D34D37CB5";
    private static final String KEY = ADDRESS.substring(0, 64);

    @After
    public void nothing_waits()
    {
        ToxLink.takePending(0L);
    }

    @Test
    public void a_link_with_an_id_opens_new_contact_in_any_case_and_with_slashes()
    {
        for (final String link : new String[]{
                "tox:" + ADDRESS,
                "tox:" + ADDRESS.toLowerCase(Locale.ROOT),
                "tox://" + ADDRESS,
                "TOX:" + ADDRESS,
                "Tox://" + ADDRESS.toLowerCase(Locale.ROOT),
                "  tox:" + ADDRESS + "  ",
                "tox://" + ADDRESS + "/",
                "tox:" + ADDRESS + "?message=hi",
                "tox:" + ADDRESS + "#top",
                "tox:" + AirNewContactModel.grouped(ADDRESS).replace(" ", "%20"),
        })
        {
            final ToxLink.Parsed parsed = ToxLink.parse(link);
            assertEquals(link, ToxLink.Kind.CONTACT, parsed.kind);
            assertEquals(link, ADDRESS, parsed.hex);
            assertTrue(link, parsed.whole());
        }
    }

    @Test
    public void an_id_that_is_not_whole_still_opens_new_contact_which_says_why()
    {
        // A typo in the check: New contact says the check does not match.
        final String typo = ADDRESS.substring(0, 75) + (ADDRESS.charAt(75) == '0' ? '1' : '0');
        final ToxLink.Parsed bad = ToxLink.parse("tox:" + typo);
        assertEquals(ToxLink.Kind.CONTACT, bad.kind);
        assertEquals(typo, bad.hex);
        assertFalse(bad.whole());
        // Cut short.
        final ToxLink.Parsed cut = ToxLink.parse("tox:" + ADDRESS.substring(0, 40));
        assertEquals(ToxLink.Kind.CONTACT, cut.kind);
        assertFalse(cut.whole());
        // A letter typed for a digit: what the field keeps of it, which it then counts as one short.
        final ToxLink.Parsed letter = ToxLink.parse("tox:" + ADDRESS.substring(0, 10) + "O" + ADDRESS.substring(11));
        assertEquals(ToxLink.Kind.CONTACT, letter.kind);
        assertEquals(75, letter.hex.length());
        assertFalse(letter.whole());
    }

    @Test
    public void a_64_character_key_is_a_group_s()
    {
        final ToxLink.Parsed group = ToxLink.parse("tox:" + KEY.toLowerCase(Locale.ROOT));
        assertEquals(ToxLink.Kind.GROUP, group.kind);
        assertEquals(KEY, group.hex);
        assertFalse(group.whole());
    }

    @Test
    public void a_link_without_an_id_is_nothing()
    {
        assertFalse(ToxLink.isToxLink(null));
        assertFalse(ToxLink.isToxLink("khandaq://group/" + KEY));
        assertFalse(ToxLink.isToxLink("https://example.org/tox:" + ADDRESS));
        assertTrue(ToxLink.isToxLink(" TOX:"));
        assertSame(ToxLink.NOTHING, ToxLink.parse(null));
        assertSame(ToxLink.NOTHING, ToxLink.parse("tox:"));
        assertSame(ToxLink.NOTHING, ToxLink.parse("tox://"));
        assertSame(ToxLink.NOTHING, ToxLink.parse("tox:hello"));
        assertSame(ToxLink.NOTHING, ToxLink.parse("tox:abc123"));
        assertSame(ToxLink.NOTHING, ToxLink.parse("mailto:" + ADDRESS));
    }

    @Test
    public void the_id_of_a_link_waits_for_the_unlock_and_is_taken_once()
    {
        assertFalse(ToxLink.hasPending());
        ToxLink.storePending(ADDRESS, 1_000L);
        assertTrue(ToxLink.hasPending());
        assertEquals(ADDRESS, ToxLink.takePending(1_000L + ToxLink.PENDING_MAX_AGE_MS));
        assertFalse(ToxLink.hasPending());
        assertNull(ToxLink.takePending(1_000L + ToxLink.PENDING_MAX_AGE_MS));

        // A newer link takes the place of an older one.
        ToxLink.storePending(KEY, 2_000L);
        ToxLink.storePending(ADDRESS, 3_000L);
        assertEquals(ADDRESS, ToxLink.takePending(4_000L));
    }

    @Test
    public void an_id_left_waiting_too_long_is_not_opened_on_a_later_unlock()
    {
        ToxLink.storePending(ADDRESS, 1_000L);
        assertNull(ToxLink.takePending(1_001L + ToxLink.PENDING_MAX_AGE_MS));
        assertFalse(ToxLink.hasPending());
        // A clock set back is no reason to open it either.
        ToxLink.storePending(ADDRESS, 5_000L);
        assertNull(ToxLink.takePending(4_000L));
    }

    @Test
    public void the_share_screen_opens_new_contact_or_lets_the_id_wait_for_the_unlock()
    {
        final String share = read(JAVA + "ShareActivity.java");
        assertTrue(share.contains("if (ToxLink.isToxLink(dataString))"));
        assertTrue(share.contains("final ToxLink.Parsed link = ToxLink.parse(dataString);"));
        assertTrue(share.contains("handleToxFriendInvite(link.hex);"));
        assertTrue(share.contains("handleToxNGCPublicGroupInvite(link.hex);"));
        // The old reading took a group key for an ID and dropped the ID.
        assertFalse(share.contains("normalize_tox_address("));
        final String invite = method(share, "void handleToxFriendInvite(final String friend_address)");
        final int closed = invite.indexOf("if (TrifaToxService.orma == null)");
        assertTrue(closed > 0);
        assertTrue(invite.indexOf("ToxLink.storePending(friend_address, System.currentTimeMillis());") > closed);
        assertTrue(invite.indexOf("new Intent(this, StartMainActivityWrapper.class)") > closed);
        assertTrue(invite.contains("ToxLink.openNewContact(this, friend_address);"));
        assertTrue(invite.contains("this.finish();"));
        // The ID stays out of the log.
        assertTrue(invite.contains("HelperGeneric.logI(TAG, \"handleToxFriendInvite\");"));
        assertFalse(invite.contains("+ friend_address"));

        final String link = read(JAVA + "ToxLink.java");
        assertTrue(link.contains("intent.putExtra(AirNewContactActivity.EXTRA_ID, hex);"));
        // Kept while the profile is closed or a PIN is up: the main screen resumes again after it.
        assertTrue(link.contains("TrifaToxService.orma == null || AppLockHelper.isGateUp(activity)"));
        assertTrue(link.contains("activity.open_add_friend(hex);"));
        // New contact checks the ID it is given as a pasted one.
        assertTrue(read(JAVA + "AirNewContactActivity.java").contains("setId(AirNewContactModel.clean(prefill), !empty);"));
    }

    @Test
    public void the_main_screen_opens_the_waiting_id_after_the_first_launch_page()
    {
        final String main = read(JAVA + "MainActivity.java");
        final int resume = main.indexOf("protected void onResume()");
        assertTrue(resume > 0);
        final String body = main.substring(resume, main.indexOf("\n    }\n", resume));
        final int idPage = body.indexOf("final boolean idPageNext = AirIdReadyActivity.openIfPending(MainActivity.this);");
        final int open = body.indexOf("ToxLink.openPending(MainActivity.this);");
        assertTrue(idPage > 0 && open > idPage);
        assertTrue(body.substring(idPage, open).contains("if (!idPageNext)"));
        assertTrue(body.indexOf("HelperMsgNotification.consume_pending_open_chat(MainActivity.this);") < open);
    }

    /** The body of the method whose declaration starts with {@code signature}. */
    private static String method(final String source, final String signature)
    {
        final int start = source.indexOf(signature);
        assertTrue(signature, start > 0);
        assertEquals(signature + " is declared once", start, source.lastIndexOf(signature));
        final int end = source.indexOf("\n    }\n", start);
        assertTrue(signature, end > start);
        return source.substring(start, end);
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
                    throw new AssertionError(name + ": " + e.getMessage());
                }
            }
        }
        throw new AssertionError(name + " not found from " + new File("").getAbsolutePath());
    }
}
