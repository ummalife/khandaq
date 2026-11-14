package com.zoffcc.applications.trifa;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

import static com.zoffcc.applications.trifa.HelperFiletransfer.claim_outgoing_copy;
import static com.zoffcc.applications.trifa.HelperFiletransfer.delete_outgoing_copy;
import static com.zoffcc.applications.trifa.HelperFiletransfer.is_photo_picker_placeholder_name;
import static com.zoffcc.applications.trifa.HelperFiletransfer.outgoing_copy_dir_name;
import static com.zoffcc.applications.trifa.HelperFiletransfer.outgoing_copy_file_name;
import static com.zoffcc.applications.trifa.HelperFiletransfer.photo_picker_local_media_id;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (QA live, F4): a file we send reaches the peer under the name it was picked with.
 *
 * QA: a photo sent from Android showed on iOS and the desktop as "9ded_1000000022.png", not as
 * "qa-stripes-android.png". The app's copy of a file we send lay in the outgoing folder as "[4 hex]_[name]",
 * and that copy's name is the one that goes out. Each copy now lies in a folder of its own under the name
 * itself; copies from before stay where they are and are still deleted with their messages. And the
 * system Photo Picker names a photo after its gallery id ("1000000022.png"), so for its picks the gallery
 * is asked for the real name.
 *
 * That name comes from another app, so it is made safe for a path first, and nothing is written or deleted
 * outside the outgoing folder, also by way of a link (code scanning, java/path-injection).
 *
 * The outgoing folder is passed in: MainActivity, which holds it, cannot be loaded in a JVM test.
 */
public class OutgoingFileCopyTest
{
    private static final String PICKER = "com.android.providers.media.photopicker";

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private File outgoing;
    /** As MainActivity sets it: with a slash at the end. */
    private String outgoingDir;

    @Before
    public void makeTheOutgoingFolder()
    {
        outgoing = new File(folder.getRoot(), "outgoing");
        assertTrue(outgoing.mkdirs());
        outgoingDir = outgoing.getAbsolutePath() + "/";
    }

    private File claim(final String prefix, final String name) throws IOException
    {
        final File file = claim_outgoing_copy(outgoingDir, prefix, name);
        assertNotNull(file);
        return file;
    }

    private static void write(final File file, final String content) throws IOException
    {
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void a_copy_keeps_the_picked_name_in_a_folder_of_its_own() throws IOException
    {
        final File copy = claim("9ded", "qa-stripes-android.png");

        assertEquals(new File(new File(outgoing, "9ded"), "qa-stripes-android.png"), copy);
        assertTrue(copy.isFile());
        assertEquals(0L, copy.length());
        // The senders and the readers put path_name and file_name together like this.
        assertEquals(copy, new File(copy.getParentFile().getAbsolutePath() + "/" + copy.getName()));
    }

    @Test
    public void the_same_file_sent_twice_gets_a_second_folder_and_keeps_its_name() throws IOException
    {
        final File first = claim("9ded", "qa-stripes-android.png");
        final File second = claim("9ded", "qa-stripes-android.png");

        assertEquals(new File(new File(outgoing, "9ded_1"), "qa-stripes-android.png"), second);
        assertTrue(first.isFile());
        assertTrue(second.isFile());
    }

    @Test
    public void another_name_shares_a_folder() throws IOException
    {
        final File a = claim("9ded", "a.png");
        final File b = claim("9ded", "b.png");

        assertEquals(a.getParentFile(), b.getParentFile());
    }

    @Test
    public void a_copy_from_before_the_folders_named_like_a_folder_is_stepped_over() throws IOException
    {
        claim("9ded", "qa-stripes-android.png");
        claim("9ded", "qa-stripes-android.png");
        // The flat copy of a file named "2" from the same source, made before the folders.
        final File old = new File(outgoing, "9ded_2");
        write(old, "old");

        final File third = claim("9ded", "qa-stripes-android.png");

        assertEquals(new File(new File(outgoing, "9ded_3"), "qa-stripes-android.png"), third);
        assertEquals("old", new String(Files.readAllBytes(old.toPath()), StandardCharsets.UTF_8));
    }

    @Test
    public void deleting_a_copy_takes_its_folder_once_nothing_else_is_in_it() throws IOException
    {
        final File a = claim("9ded", "a.png");
        final File b = claim("9ded", "b.png");
        final File shared = a.getParentFile();

        assertTrue(delete_outgoing_copy(a.getAbsolutePath(), outgoingDir));
        assertFalse(a.exists());
        assertTrue(b.isFile());
        assertTrue(shared.isDirectory());

        assertTrue(delete_outgoing_copy(b.getAbsolutePath(), outgoingDir));
        assertFalse(shared.exists());
        assertTrue(outgoing.isDirectory());
    }

    @Test
    public void a_copy_from_before_the_folders_is_still_deleted_and_the_outgoing_folder_stays() throws IOException
    {
        final File old = new File(outgoing, "9ded_photo.png");
        write(old, "old");

        // Such a row holds the outgoing folder with its slash as path_name, so the full path has two.
        assertTrue(delete_outgoing_copy(outgoingDir + "/" + old.getName(), outgoingDir));
        assertFalse(old.exists());
        assertTrue(outgoing.isDirectory());
    }

    @Test
    public void nothing_outside_the_outgoing_folder_is_deleted() throws IOException
    {
        final File original = folder.newFile("keep.png");

        assertFalse(delete_outgoing_copy(original.getAbsolutePath(), outgoingDir));
        assertTrue(original.exists());
        assertFalse(delete_outgoing_copy(null, outgoingDir));
        // Before MainActivity sets the folder it is empty, and nothing matches it.
        assertFalse(delete_outgoing_copy(original.getAbsolutePath(), ""));
        assertTrue(original.exists());
    }

    @Test
    public void a_hostile_name_gets_a_safe_one_in_the_copy_s_own_folder() throws IOException
    {
        // Up one level would put the copy beside the folders, two levels next to the outgoing folder.
        assertEquals(new File(new File(outgoing, "9ded"), "___x"), claim("9ded", "../../x"));
        assertEquals(new File(new File(outgoing, "9ded"), "a_b.png"), claim("9ded", "a/b.png"));
        // Nothing usable left: the generated name. The same name again takes the next folder.
        assertEquals(new File(new File(outgoing, "9ded"), "9ded_"), claim("9ded", ".."));
        assertEquals(new File(new File(outgoing, "9ded_1"), "9ded_"), claim("9ded", ""));
        assertFalse(new File(outgoing, "x").exists());
        assertFalse(new File(folder.getRoot(), "x").exists());
    }

    @Test
    public void a_path_that_leads_out_of_the_outgoing_folder_is_not_deleted() throws IOException
    {
        final File original = folder.newFile("keep.png");

        // The text of the path starts with the outgoing folder, the path itself does not.
        assertFalse(delete_outgoing_copy(outgoingDir + "9ded/../../keep.png", outgoingDir));
        assertTrue(original.exists());
        // Nor is the outgoing folder itself a copy.
        assertFalse(delete_outgoing_copy(outgoingDir, outgoingDir));
        assertTrue(outgoing.isDirectory());
    }

    @Test
    public void a_folder_that_links_out_of_the_outgoing_folder_is_neither_written_nor_deleted_in() throws IOException
    {
        final File elsewhere = folder.newFolder("elsewhere");
        final File kept = new File(elsewhere, "keep.png");
        write(kept, "keep");
        Files.createSymbolicLink(new File(outgoing, "9ded").toPath(), elsewhere.toPath());

        // The path reads as the outgoing folder's own; with the link resolved it is not, so the next folder is used.
        assertEquals(new File(new File(outgoing, "9ded_1"), "photo.png"), claim("9ded", "photo.png"));
        assertFalse(new File(elsewhere, "photo.png").exists());
        assertFalse(delete_outgoing_copy(outgoingDir + "9ded/keep.png", outgoingDir));
        assertTrue(kept.exists());
    }

    @Test
    public void the_name_is_made_safe_for_a_path_and_a_blank_one_keeps_the_old_form()
    {
        assertEquals("qa-stripes-android.png", outgoing_copy_file_name("qa-stripes-android.png", "9ded"));
        assertEquals("a_b_c.png", outgoing_copy_file_name("a/b:c.png", "9ded"));
        assertEquals("a_b.png", outgoing_copy_file_name("a\\b.png", "9ded"));
        assertEquals("___x", outgoing_copy_file_name("../../x", "9ded"));
        assertEquals("x_y_.png", outgoing_copy_file_name("x\u0000y\n.png", "9ded"));
        assertEquals("hidden.png", outgoing_copy_file_name(".hidden.png", "9ded"));
        assertEquals("a_b.png", outgoing_copy_file_name("a..b.png", "9ded"));
        assertEquals("9ded_", outgoing_copy_file_name("", "9ded"));
        assertEquals("9ded_", outgoing_copy_file_name(null, "9ded"));
        assertEquals("9ded_", outgoing_copy_file_name(".", "9ded"));
        assertEquals("9ded_", outgoing_copy_file_name("..", "9ded"));
        assertEquals("9ded_", outgoing_copy_file_name("....", "9ded"));
        assertEquals("9ded", outgoing_copy_dir_name("9ded", 0));
        assertEquals("9ded_2", outgoing_copy_dir_name("9ded", 2));
    }

    private static String repeat(final String piece, final int times)
    {
        final StringBuilder out = new StringBuilder();
        for (int i = 0; i < times; i++)
        {
            out.append(piece);
        }
        return out.toString();
    }

    private static int utf8(final String text)
    {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    @Test
    public void a_long_name_is_cut_to_what_a_file_name_and_toxcore_take_and_keeps_its_extension()
    {
        final String cyrillic = outgoing_copy_file_name(repeat("я", 300) + ".png", "9ded");
        assertEquals(repeat("я", 125) + ".png", cyrillic);
        assertTrue(utf8(cyrillic) <= HelperFiletransfer.OUTGOING_COPY_NAME_MAX_BYTES);

        // Never half of an emoji's surrogate pair.
        final String emoji = outgoing_copy_file_name(repeat("😀", 100) + ".jpg", "9ded");
        assertEquals(repeat("😀", 62) + ".jpg", emoji);

        // No extension, or one too long to be one: the name is cut as it is.
        assertEquals(repeat("a", 255), outgoing_copy_file_name(repeat("a", 300), "9ded"));
        assertEquals(255, utf8(outgoing_copy_file_name("a." + repeat("b", 300), "9ded")));

        // A cut that ends on a dot does not make ".." with the extension.
        assertEquals(repeat("a", 250) + ".png",
                     outgoing_copy_file_name(repeat("a", 250) + "." + repeat("b", 10) + ".png", "9ded"));
    }

    @Test
    public void a_photo_picked_from_this_device_is_known_by_its_gallery_id()
    {
        assertEquals("1000000022",
                     photo_picker_local_media_id("media", Arrays.asList("picker", "0", PICKER, "media", "1000000022"), 0));
        // The picker standing in for a GET_CONTENT, in a work profile.
        assertEquals("1000000023", photo_picker_local_media_id("media", Arrays.asList("picker_get_content", "10", PICKER,
                                                                                      "media", "1000000023"), 10));
    }

    @Test
    public void any_other_uri_has_no_gallery_id()
    {
        // Another user's photo.
        assertNull(photo_picker_local_media_id("media", Arrays.asList("picker", "10", PICKER, "media", "1000000022"), 0));
        // A photo from a cloud provider.
        assertNull(photo_picker_local_media_id("media", Arrays.asList("picker", "0", "com.example.cloudpicker",
                                                                      "media", "abc"), 0));
        // A gallery uri, which already has the real name.
        assertNull(photo_picker_local_media_id("media", Arrays.asList("external", "images", "media", "1000000022"), 0));
        // A document.
        assertNull(photo_picker_local_media_id("com.android.providers.media.documents",
                                               Arrays.asList("document", "image:1000000022"), 0));
        // Damaged ids.
        assertNull(photo_picker_local_media_id("media", Arrays.asList("picker", "0", PICKER, "media", "12a"), 0));
        assertNull(photo_picker_local_media_id("media", Arrays.asList("picker", "0", PICKER, "media", ""), 0));
        assertNull(photo_picker_local_media_id(null, null, 0));
    }

    @Test
    public void only_the_picker_s_own_name_gives_way_to_the_gallery_s()
    {
        assertTrue(is_photo_picker_placeholder_name("1000000022.png", "1000000022"));
        assertTrue(is_photo_picker_placeholder_name("1000000022", "1000000022"));
        assertFalse(is_photo_picker_placeholder_name("qa-stripes-android.png", "1000000022"));
        assertFalse(is_photo_picker_placeholder_name("10000000221.png", "1000000022"));
        assertFalse(is_photo_picker_placeholder_name(null, "1000000022"));
    }
}
