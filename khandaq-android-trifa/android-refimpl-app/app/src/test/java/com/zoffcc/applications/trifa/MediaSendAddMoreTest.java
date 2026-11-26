package com.zoffcc.applications.trifa;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (Khandaq Testers 8265): the send screen of photos and videos has a tile at the end of its strip that
 * picks more with the chat's own media picker and puts them after those already chosen, as on iOS. A send takes
 * as many items as the photo picker's limit, as the first pick did; the size of the files is checked when they
 * are sent, as before. The rules are tested here; the screen, which cannot run in a JVM test, is read from its
 * resources and sources, as the other audit tests do.
 */
public class MediaSendAddMoreTest
{
    private static final String DIR = "src/main/";
    private static final String JAVA = DIR + "java/com/zoffcc/applications/trifa/";
    private static final String RES = DIR + "res/";
    private static final String[] LOCALES = {"values", "values-ru", "values-ar", "values-zh-rCN"};

    @Test
    public void the_room_of_a_send_is_the_picker_limit_less_what_it_holds()
    {
        assertEquals(99, MediaSendPreviewHelper.addMoreRoom(1, 100));
        assertEquals(1, MediaSendPreviewHelper.addMoreRoom(99, 100));
        assertEquals(0, MediaSendPreviewHelper.addMoreRoom(100, 100));
        assertEquals(0, MediaSendPreviewHelper.addMoreRoom(130, 100));
        // No limit where the picker has none (the document picker before Android 13).
        assertEquals(Integer.MAX_VALUE, MediaSendPreviewHelper.addMoreRoom(500, 0));
    }

    @Test
    public void the_photo_picker_is_asked_for_the_room_and_never_for_one()
    {
        // The chat's own pick: the picker's limit, as before.
        assertEquals(100, MediaSendPreviewHelper.pickImagesMax(Integer.MAX_VALUE, 100));
        assertEquals(7, MediaSendPreviewHelper.pickImagesMax(7, 100));
        assertEquals(100, MediaSendPreviewHelper.pickImagesMax(150, 100));
        // The picker cancels on a limit of 1: one item is picked without the extra.
        assertEquals(0, MediaSendPreviewHelper.pickImagesMax(1, 100));
        assertEquals(0, MediaSendPreviewHelper.pickImagesMax(0, 100));
        assertEquals(0, MediaSendPreviewHelper.pickImagesMax(5, 1));
    }

    @Test
    public void picked_items_go_after_the_others_once_each_and_up_to_the_room()
    {
        final List<String> current = Arrays.asList("content://a", "file:///cropped.jpg");
        final List<String> picked = Arrays.asList("content://b", "content://a", "content://c", "content://b",
                                                  "content://d");
        assertEquals(Arrays.asList("content://b", "content://c", "content://d"),
                     MediaSendPreviewHelper.itemsToAdd(current, picked, 10, s -> s));
        assertEquals(Arrays.asList("content://b", "content://c"),
                     MediaSendPreviewHelper.itemsToAdd(current, picked, 2, s -> s));
        assertEquals(Collections.emptyList(), MediaSendPreviewHelper.itemsToAdd(current, picked, 0, s -> s));
        assertEquals(Collections.emptyList(),
                     MediaSendPreviewHelper.itemsToAdd(current, Arrays.asList("content://a"), 10, s -> s));
        assertEquals(Collections.emptyList(), MediaSendPreviewHelper.itemsToAdd(current, null, 10, s -> s));
        assertEquals(Arrays.asList("content://x"),
                     MediaSendPreviewHelper.itemsToAdd(null, Arrays.asList(null, "content://x"), 10, s -> s));
    }

    @Test
    public void the_tile_is_a_thumbnail_with_a_plus_named_in_every_language()
    {
        final Document tile = xml(RES + "layout/item_media_send_preview_add.xml");
        final Element root = tile.getDocumentElement();
        assertEquals("@string/media_send_add_more", root.getAttribute("android:contentDescription"));
        assertEquals("true", root.getAttribute("android:clickable"));
        assertEquals("true", root.getAttribute("android:focusable"));
        // The size of a thumbnail of the strip.
        final Element thumb = xml(RES + "layout/item_media_send_preview_thumb.xml").getDocumentElement();
        assertEquals(thumb.getAttribute("android:layout_width"), root.getAttribute("android:layout_width"));
        assertEquals(thumb.getAttribute("android:layout_height"), root.getAttribute("android:layout_height"));
        assertTrue(read(RES + "layout/item_media_send_preview_add.xml").contains("@drawable/kq_ic_plus"));
        for (final String locale : LOCALES)
        {
            assertFalse(locale, string(xml(RES + locale + "/strings.xml"), "media_send_add_more").trim().isEmpty());
        }
        assertEquals("Add photos or videos", string(xml(RES + "values/strings.xml"), "media_send_add_more"));
    }

    @Test
    public void the_screen_adds_with_the_chat_picker_and_keeps_the_edit()
    {
        final String helper = read(JAVA + "MediaSendPreviewHelper.java");
        // The chats open the picker as before.
        assertTrue(helper.contains("return buildMediaPickerIntent(context, Integer.MAX_VALUE);"));
        final String screen = read(JAVA + "MediaSendPreviewActivity.java");
        assertTrue(screen.contains("MediaSendPreviewHelper.buildMediaPickerIntent(this, room), REQUEST_ADD_MORE"));
        assertTrue(screen.contains("MediaSendPreviewHelper.itemsToAdd(uris, media, addMoreRoom(), Uri::toString)"));
        assertTrue(screen.contains("MediaSendPreviewHelper.isPreviewableMedia(this, uri)"));
        assertTrue(screen.contains("MediaSendPreviewHelper.persistUriPermissions(this, picked);"));
        // The strip ends in the tile for one item too, and loses it once the send takes no more.
        assertTrue(screen.contains("thumbAdapter.setAddShown(addMoreRoom() > 0);"));
        assertTrue(screen.contains("R.layout.item_media_send_preview_add"));
        // The result holds the whole list, and the chat gets the grants of what was added here.
        final int send = screen.indexOf("result.putParcelableArrayListExtra(MediaSendPreviewHelper.EXTRA_URI_LIST, uris);");
        assertTrue(send > 0);
        assertTrue(screen.indexOf("MediaSendPreviewHelper.grantContentUriReadPermissions(result, this, uris);", send) > send);
        assertTrue(helper.contains("android.content.ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())"));
        // The crop editor stays.
        assertTrue(screen.contains("startActivityForResult(crop, com.yalantis.ucrop.UCrop.REQUEST_CROP);"));
        assertTrue(screen.contains("replaceEdited(pos, cropped);"));
        assertTrue(screen.contains("uris.set(pos, edited);"));
    }

    private static String read(final String path)
    {
        try
        {
            return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
        }
        catch (Exception e)
        {
            throw new AssertionError(path, e);
        }
    }

    private static Document xml(final String path)
    {
        try
        {
            return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new File(path));
        }
        catch (Exception e)
        {
            throw new AssertionError(path, e);
        }
    }

    private static String string(final Document strings, final String name)
    {
        final NodeList all = strings.getElementsByTagName("string");
        for (int i = 0; i < all.getLength(); i++)
        {
            final Element e = (Element) all.item(i);
            if (name.equals(e.getAttribute("name")))
            {
                return e.getTextContent();
            }
        }
        throw new AssertionError("no string " + name);
    }
}
