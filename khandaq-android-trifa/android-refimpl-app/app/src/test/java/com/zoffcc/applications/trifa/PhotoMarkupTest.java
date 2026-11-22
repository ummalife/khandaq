package com.zoffcc.applications.trifa;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (Khandaq Testers 8264): on the send screen the edit button asks which edit, Crop and rotate (uCrop,
 * as before) or Draw and write (PhotoMarkupActivity), as on iOS. A drawing is saved as a new JPEG in the
 * screen's cache, which takes the page's place: the photo picked is never written. The geometry is tested
 * here; the views, Glide and the JPEG cannot run in a JVM test, so the wiring is read from the sources and
 * resources, as the other audit tests do.
 */
public class PhotoMarkupTest
{
    private static final String DIR = "src/main/";
    private static final String JAVA = DIR + "java/com/zoffcc/applications/trifa/";
    private static final String RES = DIR + "res/";
    private static final String[] LOCALES = {"values", "values-ru", "values-ar", "values-zh-rCN"};
    private static final String[] NEW_STRINGS = {
            "media_send_edit_crop", "media_send_edit_markup", "photo_markup_undo", "photo_markup_done",
            "photo_markup_text", "photo_markup_add_text", "photo_markup_text_hint", "photo_markup_color_white",
            "photo_markup_color_black", "photo_markup_color_red", "photo_markup_color_yellow",
            "photo_markup_color_green", "photo_markup_color_blue"};

    @Test
    public void a_photo_fits_the_screen_in_its_middle()
    {
        // Wide in a square: full width, centred up and down.
        assertArrayEquals(new float[]{0.5f, 0f, 250f}, PhotoMarkupMath.fit(2000, 1000, 1000, 1000), 0.0001f);
        // Tall in a square: full height, centred left and right.
        assertArrayEquals(new float[]{0.5f, 250f, 0f}, PhotoMarkupMath.fit(1000, 2000, 1000, 1000), 0.0001f);
        // A small photo grows to the screen.
        assertArrayEquals(new float[]{2f, 0f, 100f}, PhotoMarkupMath.fit(500, 400, 1000, 1000), 0.0001f);
        // Nothing to fit yet (before the first layout).
        assertArrayEquals(new float[]{1f, 0f, 0f}, PhotoMarkupMath.fit(1000, 1000, 0, 0), 0.0001f);
        assertArrayEquals(new float[]{1f, 0f, 0f}, PhotoMarkupMath.fit(0, 1000, 1000, 1000), 0.0001f);
    }

    @Test
    public void the_line_and_the_words_are_sized_by_the_photo()
    {
        assertEquals(2560f / 150f, PhotoMarkupMath.brushWidth(2560, 1920), 0.001f);
        assertEquals(2560f / 150f, PhotoMarkupMath.brushWidth(1920, 2560), 0.001f);
        assertEquals(2f, PhotoMarkupMath.brushWidth(100, 50), 0.001f);
        assertEquals(160f, PhotoMarkupMath.textSize(2560, 1920), 0.001f);
        assertEquals(12f, PhotoMarkupMath.textSize(100, 50), 0.001f);
        // Words dragged off the photo stay on its edge.
        assertEquals(0f, PhotoMarkupMath.clamp(-40f, 0f, 2560f), 0f);
        assertEquals(2560f, PhotoMarkupMath.clamp(3000f, 0f, 2560f), 0f);
        assertEquals(700f, PhotoMarkupMath.clamp(700f, 0f, 2560f), 0f);
    }

    @Test
    public void the_edit_button_offers_the_crop_as_before_and_the_drawing()
    {
        final String screen = read(JAVA + "MediaSendPreviewActivity.java");
        assertTrue(screen.contains("editButton.setOnClickListener(v -> showEditMenu(v, pager.getCurrentItem()));"));
        assertTrue(screen.contains("menu.getMenu().add(0, EDIT_CROP, 0, R.string.media_send_edit_crop);"));
        assertTrue(screen.contains("menu.getMenu().add(0, EDIT_MARKUP, 1, R.string.media_send_edit_markup);"));
        // The crop is uCrop's, untouched.
        assertTrue(screen.contains("startActivityForResult(crop, com.yalantis.ucrop.UCrop.REQUEST_CROP);"));
        // Photos only: a video has no edit.
        final String launch = method(screen, "private void launchMarkupEditor(final int position)");
        assertTrue(launch.contains("MediaSendPreviewHelper.isVideoUri(this, source)"));
        assertTrue(launch.contains("new java.io.File(getCacheDir(),"));
        assertTrue(launch.contains("markup.putExtra(PhotoMarkupActivity.EXTRA_OUTPUT_PATH, dest.getAbsolutePath());"));
        assertTrue(launch.contains("startActivityForResult(markup, REQUEST_MARKUP);"));
        // The copy takes the page's place, as a crop does; a failure is said where the edit button is.
        final String result = method(screen, "protected void onActivityResult(int requestCode, int resultCode, Intent data)");
        assertTrue(result.contains("IntentCompat.getParcelableExtra(data, PhotoMarkupActivity.EXTRA_OUTPUT_URI, Uri.class)"));
        assertTrue(result.contains("data.getBooleanExtra(PhotoMarkupActivity.EXTRA_FAILED, false)"));
        assertTrue(result.contains("replaceEdited(pos, cropped);"));
        assertTrue(method(screen, "private void replaceEdited(final int pos, final Uri edited)")
                           .contains("uris.set(pos, edited);"));
    }

    @Test
    public void the_drawing_is_a_new_jpeg_and_the_photo_is_never_written()
    {
        final String editor = read(JAVA + "PhotoMarkupActivity.java");
        // Read through Glide, from no cache, at most the size of a crop's result.
        assertTrue(editor.contains(".downsample(DownsampleStrategy.CENTER_INSIDE)"));
        assertTrue(editor.contains(".skipMemoryCache(true)"));
        assertTrue(editor.contains(".diskCacheStrategy(DiskCacheStrategy.NONE)"));
        assertTrue(editor.contains(".submit(MAX_SIDE_PX, MAX_SIDE_PX);"));
        assertEquals(2560, PhotoMarkupActivity.MAX_SIDE_PX);
        assertTrue(read(JAVA + "MediaSendPreviewActivity.java").contains(".withMaxResultSize(2560, 2560)"));
        // Written to the path the send screen gives, and nowhere else.
        assertFalse(editor.contains("openOutputStream"));
        assertTrue(editor.contains("try (FileOutputStream out = new FileOutputStream(target))"));
        assertTrue(editor.contains("marked.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)"));
        assertEquals(90, PhotoMarkupActivity.JPEG_QUALITY);
        // A copy that failed half way is not left behind; nothing drawn is no copy at all.
        final String save = method(editor, "private void save()");
        assertTrue(save.contains("if (canvas.markCount() == 0)"));
        assertTrue(save.contains("target.delete()"));
        // The marks go on a copy of the photo.
        final String view = read(JAVA + "PhotoMarkupView.java");
        assertTrue(method(view, "Bitmap render()").contains("photo.copy(Bitmap.Config.ARGB_8888, true)"));
        // The URI comes back as an extra, as uCrop's: a file URI as an intent's data trips StrictMode.
        assertTrue(editor.contains("new Intent().putExtra(EXTRA_OUTPUT_URI, Uri.fromFile(target))"));
    }

    @Test
    public void the_editor_has_cancel_undo_done_text_and_six_named_colours()
    {
        final Document layout = xml(RES + "layout/activity_photo_markup.xml");
        final Element cancel = byId(layout, "@+id/photo_markup_cancel");
        assertNotNull(cancel);
        assertEquals("@string/cancel", cancel.getAttribute("android:contentDescription"));
        assertEquals("@string/photo_markup_done",
                     byId(layout, "@+id/photo_markup_done").getAttribute("android:contentDescription"));
        assertEquals("@string/photo_markup_undo", byId(layout, "@+id/photo_markup_undo").getAttribute("android:text"));
        assertEquals("@string/photo_markup_text", byId(layout, "@+id/photo_markup_text").getAttribute("android:text"));
        assertEquals("com.zoffcc.applications.trifa.PhotoMarkupView",
                     byId(layout, "@+id/photo_markup_canvas").getTagName());

        final String editor = read(JAVA + "PhotoMarkupActivity.java");
        assertTrue(editor.contains("swatch.setContentDescription(getString(COLOR_NAMES[i]));"));
        assertTrue(editor.contains("swatch.setSelected(chosen);"));
        // Back is Cancel, through the dispatcher the app's other screens use.
        assertTrue(editor.contains("getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true)"));
    }

    @Test
    public void the_words_are_in_every_language_the_app_ships()
    {
        for (final String locale : LOCALES)
        {
            final Document strings = xml(RES + locale + "/strings.xml");
            for (final String name : NEW_STRINGS)
            {
                assertFalse(locale + " " + name, string(strings, name).trim().isEmpty());
            }
        }
        final Document english = xml(RES + "values/strings.xml");
        assertEquals("Crop and rotate", string(english, "media_send_edit_crop"));
        assertEquals("Draw and write", string(english, "media_send_edit_markup"));
    }

    @Test
    public void the_editor_is_the_apps_own_and_keeps_the_drawing_when_the_phone_turns()
    {
        final NodeList activities = xml(DIR + "AndroidManifest.xml").getElementsByTagName("activity");
        Element editor = null;
        for (int i = 0; i < activities.getLength(); i++)
        {
            final Element activity = (Element) activities.item(i);
            if ("com.zoffcc.applications.trifa.PhotoMarkupActivity".equals(activity.getAttribute("android:name")))
            {
                editor = activity;
            }
        }
        assertNotNull(editor);
        assertEquals("false", editor.getAttribute("android:exported"));
        assertEquals("@style/KhandaqMediaTheme", editor.getAttribute("android:theme"));
        final String changes = editor.getAttribute("android:configChanges");
        for (final String change : new String[]{"orientation", "screenSize", "smallestScreenSize", "screenLayout",
                                                "uiMode"})
        {
            assertTrue(change, changes.contains(change));
        }
    }

    private static String method(final String source, final String signature)
    {
        final int start = source.indexOf(signature);
        assertTrue(signature, start >= 0);
        // The closing brace of a member stands four spaces in.
        final int end = source.indexOf("\n    }", start);
        assertTrue(signature, end > start);
        return source.substring(start, end);
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

    private static Element byId(final Document document, final String id)
    {
        final NodeList all = document.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++)
        {
            final Element e = (Element) all.item(i);
            if (id.equals(e.getAttribute("android:id")))
            {
                return e;
            }
        }
        return null;
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
