package com.zoffcc.applications.trifa;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (testers' ideas, 0.2.67): nothing is sent by accident. A shot from the camera opens the media preview
 * with its Send, as a gallery pick does; a document picked with File is asked about with its name and size,
 * Send and Cancel. Both in a conversation and in a group. The screens are pinned in the source.
 */
public class AirFileSendQuestionTest
{
    private static final String JAVA = "src/main/java/com/zoffcc/applications/trifa/";
    private static final String RES = "src/main/res/";

    @Test
    public void the_sheet_names_the_file_and_its_size_or_the_name_alone()
    {
        assertEquals("report.pdf, 2.4 MB", AirFileSendQuestion.what("%1$s, %2$s", "report.pdf", "2.4 MB"));
        assertEquals("report.pdf، 2.4 MB", AirFileSendQuestion.what("%1$s، %2$s", "report.pdf", "2.4 MB"));
        assertEquals("report.pdf", AirFileSendQuestion.what("%1$s, %2$s", "report.pdf", null));
        assertEquals("report.pdf", AirFileSendQuestion.what("%1$s, %2$s", "report.pdf", ""));
        // A name with a percent sign is an argument, never a format.
        assertEquals("100% done.txt, 3 KB", AirFileSendQuestion.what("%1$s, %2$s", "100% done.txt", "3 KB"));
    }

    @Test
    public void a_shot_from_the_camera_opens_the_preview_in_a_conversation()
    {
        final String result = activityResult(read(JAVA + "MessageListActivity.java"));
        final String camera = branch(result, "(requestCode == CAMERA_PHOTO_ID || requestCode == CAMERA_VIDEO_ID)");
        assertTrue(camera, camera.contains("sendPickedMediaToPreview(java.util.Collections.singletonList(captured));"));
        assertFalse(camera, camera.contains("dispatchAttachments("));
        // The camera writes a .jpg or a .mp4, which the preview takes as a photo or a video.
        final String chat = read(JAVA + "MessageListActivity.java");
        assertTrue(chat.contains("(video ? \".mp4\" : \".jpg\")"));
    }

    @Test
    public void a_shot_from_the_camera_opens_the_preview_in_a_group()
    {
        final String result = activityResult(read(JAVA + "GroupMessageListActivity.java"));
        final String camera = branch(result, "((requestCode == CAMERA_PHOTO_ID || requestCode == CAMERA_VIDEO_ID)");
        assertTrue(camera, camera.contains("sendPickedMediaToGroup(java.util.Collections.singletonList(captured));"));
        assertFalse(camera, camera.contains("dispatchAttachments("));
    }

    @Test
    public void a_document_is_asked_about_before_it_goes()
    {
        final String chat = branch(activityResult(read(JAVA + "MessageListActivity.java")),
                                   "(requestCode == FILEPICK_ID && resultCode == Activity.RESULT_OK)");
        assertAskedFirst(chat, "add_attachment(this, data, data, -1, true);");

        final String group = branch(activityResult(read(JAVA + "GroupMessageListActivity.java")),
                                    "(requestCode == FILEPICK_ID && resultCode == Activity.RESULT_OK)");
        assertAskedFirst(group, "add_attachment_ngc(this, data, data, group_id, true);");

        // The sheet's verb is Send and nothing else sends: the action runs only from the verb.
        final String question = read(JAVA + "AirFileSendQuestion.java");
        assertTrue(question.contains("AirQuestion.ask(activity, activity.getString(R.string.air_file_send_title)"));
        assertTrue(question.contains("activity.getString(R.string.air_compose_send), false, send);"));
    }

    @Test
    public void the_question_is_there_in_all_four_languages()
    {
        final String[][] expected = {
                {"values", "Send the file?", "%1$s, %2$s"},
                {"values-ru", "Отправить файл?", "%1$s, %2$s"},
                {"values-ar", "إرسال الملف؟", "%1$s، %2$s"},
                {"values-zh-rCN", "发送这个文件？", "%1$s，%2$s"},
        };
        for (final String[] language : expected)
        {
            final String files = read(RES + language[0] + "/strings_air_files.xml");
            assertEquals(language[0], language[1], string(files, "air_file_send_title", language[0]));
            assertEquals(language[0], language[2], string(files, "air_file_send_what_format", language[0]));
            assertFalse(string(read(RES + language[0] + "/strings_air.xml"), "air_compose_send", language[0]).isEmpty());
        }
    }

    private static void assertAskedFirst(final String branch, final String send)
    {
        final int ask = branch.indexOf("AirFileSendQuestion.ask(this, data.getData(), () -> {");
        final int sent = branch.indexOf(send);
        assertTrue(branch, ask > 0 && sent > ask);
        // Sent once, and only from inside the question.
        assertEquals(branch, sent, branch.lastIndexOf(send));
        assertTrue(branch, branch.indexOf("});", sent) > sent);
    }

    /** The body of onActivityResult. */
    private static String activityResult(final String source)
    {
        final int start = source.indexOf("public void onActivityResult(int requestCode, int resultCode, Intent data)");
        assertTrue(start > 0);
        final int end = source.indexOf("\n    }\n", start);
        assertTrue(end > start);
        return source.substring(start, end);
    }

    /** One branch of onActivityResult: from its condition to the next branch at the same depth. */
    private static String branch(final String body, final String condition)
    {
        final int start = body.indexOf(condition);
        assertTrue(condition, start > 0);
        final int next = body.indexOf("\n        }\n", start);
        assertTrue(condition, next > start);
        return body.substring(start, next);
    }

    private static String string(final String strings, final String name, final String language)
    {
        final Matcher found = Pattern.compile("<string name=\"" + name + "\">([^<]*)</string>").matcher(strings);
        assertTrue(language + " has no " + name, found.find());
        return found.group(1);
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
