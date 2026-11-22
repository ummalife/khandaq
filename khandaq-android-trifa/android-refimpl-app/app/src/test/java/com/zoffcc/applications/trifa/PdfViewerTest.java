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
 * KHANDAQ (Khandaq Testers 8263): a PDF of a chat, a group or Saved Messages opens in the app's own viewer
 * (PdfRenderer), which reads a plain copy of an encrypted file and deletes it when it closes; a PDF the system
 * cannot draw opens in another app with a word on why; the photo, video and PDF viewers have Share and Open in
 * another app in a More button. The rules are tested here; PdfRenderer, the viewers and the chooser cannot run
 * in a JVM test, so their wiring is read from the sources and resources, as the other audit tests do.
 */
public class PdfViewerTest
{
    private static final String DIR = "src/main/";
    private static final String JAVA = DIR + "java/com/zoffcc/applications/trifa/";
    private static final String RES = DIR + "res/";
    private static final String[] LOCALES = {"values", "values-ru", "values-ar", "values-zh-rCN"};
    private static final String[] NEW_STRINGS = {"media_viewer_more", "media_viewer_open_elsewhere",
                                                 "pdf_viewer_page_format", "pdf_viewer_opens_elsewhere"};

    @Test
    public void a_page_is_drawn_as_wide_as_asked_while_it_fits()
    {
        // A4 in points, on a screen 500 px wide drawn twice as wide.
        assertArrayEquals(new int[]{1000, 1415}, PdfPageFit.bitmapSize(595, 842, 1000, 4_000_000L, 4096));
        // A page in landscape.
        assertArrayEquals(new int[]{2160, 1526}, PdfPageFit.bitmapSize(842, 595, 2160, 4_000_000L, 4096));
        // No limit.
        assertArrayEquals(new int[]{300, 150}, PdfPageFit.bitmapSize(100, 50, 300, 0, 0));
    }

    @Test
    public void a_big_page_keeps_its_shape_under_the_pixel_limit()
    {
        final int[] size = PdfPageFit.bitmapSize(595, 842, 2160, PdfViewerActivity.MAX_PAGE_PIXELS,
                                                 PdfViewerActivity.MAX_PAGE_SIDE_PX);
        assertTrue((long) size[0] * size[1] <= PdfViewerActivity.MAX_PAGE_PIXELS);
        assertTrue(size[0] < 2160);
        assertEquals(595.0 / 842.0, (double) size[0] / size[1], 0.001);
        // 16 MB at 4 bytes a pixel.
        assertEquals(4_000_000L, PdfViewerActivity.MAX_PAGE_PIXELS);
    }

    @Test
    public void a_tall_page_stays_under_the_longest_side_a_phone_draws()
    {
        // A receipt ten times as tall as it is wide.
        assertArrayEquals(new int[]{409, 4096}, PdfPageFit.bitmapSize(200, 2000, 2160, 4_000_000L, 4096));
        assertEquals(4096, PdfViewerActivity.MAX_PAGE_SIDE_PX);
    }

    @Test
    public void a_page_of_no_size_is_still_a_bitmap()
    {
        assertArrayEquals(new int[]{1, 1}, PdfPageFit.bitmapSize(0, -5, 0, 4_000_000L, 4096));
        // A page as wide as it gets is a line of the width asked for, not nothing.
        assertArrayEquals(new int[]{2160, 1}, PdfPageFit.bitmapSize(Integer.MAX_VALUE, 1, 2160, 4_000_000L, 4096));
    }

    @Test
    public void only_a_pdf_opens_in_the_viewer()
    {
        assertTrue(ChatMediaHelper.opensInPdfViewer("application/pdf"));
        assertTrue(ChatMediaHelper.opensInPdfViewer("APPLICATION/PDF"));
        assertFalse(ChatMediaHelper.opensInPdfViewer(null));
        assertFalse(ChatMediaHelper.opensInPdfViewer("application/octet-stream"));
        assertFalse(ChatMediaHelper.opensInPdfViewer(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
        assertFalse(ChatMediaHelper.opensInPdfViewer("image/png"));
    }

    @Test
    public void the_chats_send_a_pdf_to_the_viewer_and_other_documents_elsewhere_as_before()
    {
        final String helper = read(JAVA + "ChatMediaHelper.java");
        // A chat with a person and Saved Messages, then a group: the PDF goes before the old way out.
        for (final String signature : new String[]{
                "public static void openMessageMedia(final Context context, final Message message, final String exportPath)",
                "public static void openGroupMessageMedia(final Context context, final GroupMessage message,"})
        {
            final String open = method(helper, signature);
            final int video = open.indexOf("mimeType.startsWith(\"video/\")");
            final int pdf = open.indexOf("if (opensInPdfViewer(mimeType))");
            final int viewer = open.indexOf("openPdfViewer(context, ", pdf);
            final int elsewhere = open.indexOf("HelperFiletransfer.open_local_file(exportPath, context);");
            final int encrypted = open.indexOf("IOCipherContentProvider.FILES_URI");
            assertTrue(signature, (video > 0) && (pdf > video) && (viewer > pdf));
            assertTrue(signature, (elsewhere > viewer) && (encrypted > elsewhere));
        }
        final String viewer = method(helper, "static void openPdfViewer(");
        assertTrue(viewer.contains("new Intent(context, PdfViewerActivity.class)"));
        assertTrue(viewer.contains("intent.putExtra(EXTRA_DISPLAY_NAME, displayName);"));
        // A file of a group goes under the name it was sent under.
        assertTrue(method(helper, "public static void openGroupMessageMedia(")
                           .contains("fileDisplayName(fresh.text, fresh.file_name, mediaPath)"));
    }

    @Test
    public void the_viewer_reads_a_plain_copy_of_its_own_and_deletes_it_on_close()
    {
        final String viewer = read(JAVA + "PdfViewerActivity.java");
        final String source = method(viewer, "private ParcelFileDescriptor openSource()");
        assertTrue(source.contains("plainCopy = new File(ChatMediaHelper.resolveVfsToCachedPath(vfsPath,"));
        assertTrue(source.contains("\"_pdf\" + Long.toHexString(System.nanoTime())"));
        assertTrue(source.contains("ParcelFileDescriptor.MODE_READ_ONLY"));

        final String release = method(viewer, "private void releaseDocument()");
        final int close = release.indexOf("renderer.close();");
        final int delete = release.indexOf("plainCopy.delete()");
        assertTrue((close > 0) && (delete > close));
        // On the thread that draws, after whatever it still opens or draws.
        final String destroy = method(viewer, "protected void onDestroy()");
        assertTrue(destroy.contains("worker.execute(this::releaseDocument);"));
        assertTrue(destroy.indexOf("worker.shutdown();") > destroy.indexOf("worker.execute(this::releaseDocument);"));
        // Share hands over the chat's file, never the copy that is deleted on close.
        assertFalse(method(viewer, "private ChatFileShare.Source shareSource()").contains("plainCopy"));
    }

    @Test
    public void a_pdf_the_system_cannot_draw_opens_in_another_app_with_a_word_on_why()
    {
        final String viewer = read(JAVA + "PdfViewerActivity.java");
        final String open = method(viewer, "private void openDocument()");
        final int failure = open.indexOf("catch (Exception e)");
        assertTrue(failure > open.indexOf("new PdfRenderer(descriptor)"));
        final String handled = open.substring(failure);
        assertTrue(handled.contains("closeQuietly(descriptor);"));
        assertTrue(handled.contains("releaseDocument();"));
        assertTrue(handled.contains("runOnUiThread(this::openElsewhere);"));

        final String elsewhere = method(viewer, "private void openElsewhere()");
        assertTrue(elsewhere.contains("AirComposerNotice.sayOnReturn(getString(R.string.pdf_viewer_opens_elsewhere), false);"));
        assertTrue(elsewhere.contains("ChatFileShare.start(this, shareSource(), ChatFileShare.Action.VIEW, loading, this::finish);"));
        assertEquals("This PDF cannot be shown here, so it opens in another app",
                     string(xml(RES + "values/strings.xml"), "pdf_viewer_opens_elsewhere"));
    }

    @Test
    public void the_viewers_have_share_and_open_in_another_app_in_a_more_button()
    {
        for (final String layout : new String[]{"activity_media_viewer.xml", "activity_imageviewer.xml",
                                                "activity_pdf_viewer.xml"})
        {
            final Element more = byId(xml(RES + "layout/" + layout), "@+id/media_overlay_more");
            assertNotNull(layout, more);
            assertEquals(layout, "ImageButton", more.getTagName());
            assertEquals(layout, "@string/media_viewer_more", more.getAttribute("android:contentDescription"));
            assertEquals(layout, "@drawable/ic_more_vert_white_24dp", more.getAttribute("android:src"));
            // It stands in the top bar with Back.
            assertEquals(layout, "@+id/media_overlay_bar",
                         ((Element) more.getParentNode()).getAttribute("android:id"));
        }
        for (final String activity : new String[]{"MediaViewerActivity.java", "ImageviewerActivity_SD.java",
                                                  "PdfViewerActivity.java"})
        {
            assertTrue(activity, read(JAVA + activity).contains("ChatFileShare.bindMoreButton(this, "));
        }

        final String share = read(JAVA + "ChatFileShare.java");
        assertTrue(share.contains("menu.getMenu().add(0, MENU_SHARE, 0, R.string.air_file_share);"));
        assertTrue(share.contains("menu.getMenu().add(0, MENU_OPEN_ELSEWHERE, 1, R.string.media_viewer_open_elsewhere);"));
        // One URI, read only, for the app picked in the chooser.
        assertTrue(share.contains("target.setClipData(ClipData.newRawUri(\"\", uri));"));
        assertTrue(share.contains("target.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);"));
        assertTrue(share.contains("activity.startActivity(Intent.createChooser(target, title));"));
        assertFalse(share.contains("FLAG_GRANT_WRITE_URI_PERMISSION"));
        // An encrypted file goes as a plain copy of its own; the stores of the app are never handed over whole.
        assertFalse(share.contains("IOCipherContentProvider"));
        assertTrue(share.contains("FileProvider.getUriForFile(context, KhandaqProviders.STD_FILE_PROVIDER, copy)"));
    }

    @Test
    public void copies_for_another_app_are_under_a_provider_path_and_go_at_the_next_start()
    {
        final String share = read(JAVA + "ChatFileShare.java");
        assertTrue(share.contains("new File(new File(MainActivity.SD_CARD_TMP_DIR, SHARE_DIR),"));
        assertTrue(read(RES + "xml/stdfilepaths.xml").contains("<external-files-path path=\"/tmpdir/\" name=\"tmpdir\" />"));
        final String cleanup = method(read(JAVA + "HelperGeneric.java"), "public static void cleanup_temp_dirs()");
        assertTrue(cleanup.contains("vfs_deleteFilesAndFilesSubDirectories_real(MainActivity.SD_CARD_TMP_DIR + \"/\");"));
        // It empties the folders under tmpdir too.
        assertTrue(method(read(JAVA + "HelperGeneric.java"),
                          "static boolean vfs_deleteFilesAndFilesSubDirectories_real(String directoryName, Set<String> kept)")
                           .contains("else if (file.isDirectory())"));
    }

    @Test
    public void a_copy_for_another_app_is_named_without_a_folder_or_a_control_character()
    {
        assertEquals("report.pdf", ChatFileShare.safeFileName("report.pdf", "file"));
        assertEquals("_.._etc_passwd", ChatFileShare.safeFileName("../../etc/passwd", "file"));
        assertEquals("a_b_c.pdf", ChatFileShare.safeFileName("a\nb\u0000c.pdf", "file"));
        assertEquals("C_ report_.pdf", ChatFileShare.safeFileName("C: report?.pdf", "file"));
        assertEquals("htaccess", ChatFileShare.safeFileName(".htaccess", "file"));
        assertEquals("file", ChatFileShare.safeFileName(null, "file"));
        assertEquals("file", ChatFileShare.safeFileName(" . .. ", "file"));
        // Names in the languages of the app stay as they are.
        assertEquals("Отчёт 2026.pdf",
                     ChatFileShare.safeFileName("Отчёт 2026.pdf", "file"));
        assertEquals("تقرير.pdf", ChatFileShare.safeFileName("تقرير.pdf", "file"));
    }

    @Test
    public void a_long_name_is_cut_between_characters_and_keeps_its_extension()
    {
        final String latin = ChatFileShare.safeFileName(repeat("a", 300) + ".pdf", "file");
        assertEquals(repeat("a", 196) + ".pdf", latin);
        assertEquals(ChatFileShare.MAX_NAME_BYTES, utf8(latin));

        // Two bytes a letter.
        final String cyrillic = ChatFileShare.safeFileName(repeat("ж", 150) + ".pdf", "file");
        assertEquals(repeat("ж", 98) + ".pdf", cyrillic);

        // Four bytes and two chars an emoji: never half of one.
        final String emoji = ChatFileShare.safeFileName(repeat("😀", 60) + ".pdf", "file");
        assertEquals(repeat("😀", 49) + ".pdf", emoji);
        assertTrue(utf8(emoji) <= ChatFileShare.MAX_NAME_BYTES);

        // No extension: the name is cut where it fits.
        assertEquals(repeat("b", 200), ChatFileShare.safeFileName(repeat("b", 300), "file"));
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
            final String page = string(strings, "pdf_viewer_page_format");
            assertTrue(locale, page.contains("%1$d") && page.contains("%2$d"));
        }
        final Document english = xml(RES + "values/strings.xml");
        assertEquals("More", string(english, "media_viewer_more"));
        assertEquals("Open in another app", string(english, "media_viewer_open_elsewhere"));
        assertEquals("Page %1$d of %2$d", string(english, "pdf_viewer_page_format"));
        // Share is the word of the file entries, already in every language.
        for (final String locale : LOCALES)
        {
            assertFalse(locale, string(xml(RES + locale + "/strings_air_files.xml"), "air_file_share").trim().isEmpty());
        }
    }

    @Test
    public void the_viewer_is_the_apps_own_and_dark_like_the_media_viewers()
    {
        final NodeList activities = xml(DIR + "AndroidManifest.xml").getElementsByTagName("activity");
        Element viewer = null;
        for (int i = 0; i < activities.getLength(); i++)
        {
            final Element activity = (Element) activities.item(i);
            if ("com.zoffcc.applications.trifa.PdfViewerActivity".equals(activity.getAttribute("android:name")))
            {
                viewer = activity;
            }
        }
        assertNotNull(viewer);
        assertEquals("false", viewer.getAttribute("android:exported"));
        assertEquals(0, viewer.getElementsByTagName("intent-filter").getLength());
        assertEquals("@style/KhandaqMediaTheme", viewer.getAttribute("android:theme"));
        assertTrue(viewer.getAttribute("android:configChanges").contains("orientation"));
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

    private static String repeat(final String text, final int times)
    {
        final StringBuilder out = new StringBuilder();
        for (int i = 0; i < times; i++)
        {
            out.append(text);
        }
        return out.toString();
    }

    private static int utf8(final String text)
    {
        return text.getBytes(StandardCharsets.UTF_8).length;
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
