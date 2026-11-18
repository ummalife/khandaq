package com.zoffcc.applications.trifa;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.widget.EditText;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import androidx.core.view.ContentInfoCompat;
import androidx.core.view.ViewCompat;
import androidx.test.core.app.ApplicationProvider;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (0.2.60): a photo or a video pasted into a chat's field goes to the send preview; text stays the field's.
 * The payloads are built here as the field receives them (ContentInfoCompat), never through the clipboard.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ComposerMediaPasteTest
{
    private static final String AUTHORITY = "org.khandaq.test.media";
    private static final Uri PHOTO = Uri.parse("content://" + AUTHORITY + "/photo/1");
    private static final Uri VIDEO = Uri.parse("content://" + AUTHORITY + "/video/2");
    private static final Uri DOCUMENT = Uri.parse("content://" + AUTHORITY + "/document/3");
    private static final String JAVA = "src/main/java/com/zoffcc/applications/trifa/";

    private final Context context = ApplicationProvider.getApplicationContext();
    private final List<List<Uri>> sent = new ArrayList<>();

    /** A provider that says what its files are, as a file manager's or a gallery's does. */
    public static final class MediaProvider extends ContentProvider
    {
        @Override
        public boolean onCreate()
        {
            return true;
        }

        @Override
        public String getType(final Uri uri)
        {
            final String path = uri.getPath() == null ? "" : uri.getPath();
            if (path.startsWith("/photo/"))
            {
                return "image/jpeg";
            }
            if (path.startsWith("/video/"))
            {
                return "video/mp4";
            }
            return "application/pdf";
        }

        @Override
        public Cursor query(final Uri uri, final String[] projection, final String selection,
                            final String[] selectionArgs, final String sortOrder)
        {
            return null;
        }

        @Override
        public Uri insert(final Uri uri, final ContentValues values)
        {
            return null;
        }

        @Override
        public int delete(final Uri uri, final String selection, final String[] selectionArgs)
        {
            return 0;
        }

        @Override
        public int update(final Uri uri, final ContentValues values, final String selection,
                          final String[] selectionArgs)
        {
            return 0;
        }
    }

    private ContentInfoCompat.Builder payload(final int source, final String[] mimeTypes, final ClipData.Item... items)
    {
        final ClipData clip = new ClipData(new ClipDescription("pasted", mimeTypes), items[0]);
        for (int i = 1; i < items.length; i++)
        {
            clip.addItem(items[i]);
        }
        return new ContentInfoCompat.Builder(clip, source);
    }

    private ContentInfoCompat received(final ContentInfoCompat payload)
    {
        return new ComposerMediaPaste.Listener(sent::add).onReceiveContent(new EditText(context), payload);
    }

    @Test
    public void a_pasted_photo_goes_to_the_preview_and_nothing_is_left_for_the_field()
    {
        final ContentInfoCompat payload = payload(ContentInfoCompat.SOURCE_CLIPBOARD, new String[]{"image/png"},
                                                  new ClipData.Item(PHOTO)).build();
        assertNull(received(payload));
        assertEquals(Collections.singletonList(Collections.singletonList(PHOTO)), sent);
    }

    @Test
    public void text_is_pasted_by_the_field_as_before()
    {
        final ContentInfoCompat payload = payload(ContentInfoCompat.SOURCE_CLIPBOARD,
                                                  new String[]{ClipDescription.MIMETYPE_TEXT_PLAIN},
                                                  new ClipData.Item("as-salamu alaykum")).build();
        assertSame(payload, received(payload));
        assertTrue(sent.isEmpty());
    }

    @Test
    public void a_keyboards_image_and_a_video_go_together_and_the_text_beside_them_stays()
    {
        final ContentInfoCompat payload = payload(ContentInfoCompat.SOURCE_INPUT_METHOD,
                                                  new String[]{"text/plain", "image/gif", "video/mp4"},
                                                  new ClipData.Item("caption"), new ClipData.Item(PHOTO),
                                                  new ClipData.Item(VIDEO)).build();
        final ContentInfoCompat rest = received(payload);
        assertEquals(Collections.singletonList(Arrays.asList(PHOTO, VIDEO)), sent);
        assertEquals(1, rest.getClip().getItemCount());
        assertEquals("caption", String.valueOf(rest.getClip().getItemAt(0).getText()));
        assertEquals(ContentInfoCompat.SOURCE_INPUT_METHOD, rest.getSource());
    }

    @Test
    public void a_file_of_a_clip_that_does_not_say_what_it_holds_is_taken_only_when_it_is_a_photo_or_a_video()
    {
        Robolectric.setupContentProvider(MediaProvider.class, AUTHORITY);
        // A file manager's clip: its uris, with no image or video type said.
        final ContentInfoCompat photo = payload(ContentInfoCompat.SOURCE_CLIPBOARD,
                                                new String[]{ClipDescription.MIMETYPE_TEXT_URILIST},
                                                new ClipData.Item(PHOTO)).build();
        assertNull(received(photo));
        assertEquals(Collections.singletonList(Collections.singletonList(PHOTO)), sent);

        final ContentInfoCompat document = payload(ContentInfoCompat.SOURCE_CLIPBOARD,
                                                   new String[]{ClipDescription.MIMETYPE_TEXT_URILIST},
                                                   new ClipData.Item(DOCUMENT)).build();
        assertSame("not a photo: the field pastes it as before", document, received(document));
        assertEquals(1, sent.size());
    }

    @Test
    public void the_field_offers_keyboards_images_and_videos_and_hands_what_it_receives_to_the_preview()
    {
        final EditText field = new EditText(context);
        ComposerMediaPaste.attach(field, sent::add);
        assertArrayEquals(new String[]{"image/*", "video/*"}, ViewCompat.getOnReceiveContentMimeTypes(field));

        final ContentInfoCompat gif = payload(ContentInfoCompat.SOURCE_INPUT_METHOD, new String[]{"image/gif"},
                                              new ClipData.Item(PHOTO)).build();
        assertNull(ViewCompat.performReceiveContent(field, gif));
        assertEquals(Collections.singletonList(Collections.singletonList(PHOTO)), sent);
    }

    @Test
    public void both_chats_send_what_is_pasted_into_their_field_through_the_preview()
    {
        assertTrue(bare(read(JAVA + "MessageListActivity.java")).contains(
                "ChatReplyPreviewController.bind(this,ml_new_message);"
                + "ComposerMediaPaste.attach(ml_new_message,this::sendPickedMediaToPreview);"));
        assertTrue(bare(read(JAVA + "GroupMessageListActivity.java")).contains(
                "ChatReplyPreviewController.bind(this,ml_new_group_message);"
                + "ComposerMediaPaste.attach(ml_new_group_message,this::sendPickedMediaToGroup);"));
    }

    private static String bare(final String source)
    {
        return source.replaceAll("//[^\n]*", "").replaceAll("\\s+", "");
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
                    return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
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
