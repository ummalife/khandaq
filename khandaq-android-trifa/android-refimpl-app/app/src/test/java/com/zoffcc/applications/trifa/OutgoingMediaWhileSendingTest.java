package com.zoffcc.applications.trifa;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (inbox #34): our photo or video is its own picture in every state of ours, while it goes too. The rows
 * bind views, so these read the sources (comments left out) of the four holders of a file of ours before it has
 * gone and of the binder they share.
 *
 * A tester sent a video: while it went, the phone that sent it showed the paperclip on the still plate with the
 * bar under it, as if the video were loading there. The holder of a transfer under way drew a picture for a photo
 * only; the holders before it and the one after it had the video's.
 */
public class OutgoingMediaWhileSendingTest
{
    private static final String[] OURS_BEFORE_IT_HAS_GONE = {
            "MessageListHolder_file_outgoing_state_pause_not_yet_started.java",
            "MessageListHolder_file_outgoing_state_pause_not_yet_accepted.java",
            "MessageListHolder_file_outgoing_state_pause_has_accepted.java",
            "MessageListHolder_file_outgoing_state_resume.java"};

    @Test
    public void every_state_of_ours_before_it_has_gone_draws_a_photo_or_a_video_the_same_way() throws Exception
    {
        for (final String holder : OURS_BEFORE_IT_HAS_GONE)
        {
            final String bind = body(code(holder), "public void bindMessageList(");
            assertTrue(holder, bind.contains("bindOutgoingCompactMediaUi("));
        }
    }

    @Test
    public void a_transfer_under_way_shows_the_picture_before_any_file_glyph() throws Exception
    {
        final String bind = body(code("MessageListHolder_file_outgoing_state_resume.java"),
                                 "public void bindMessageList(");
        final int media = bind.indexOf("bindOutgoingCompactMediaUi(");
        assertTrue(media > bind.indexOf("isAudioMessage(context, message)"));
        // The bar of the old bubble and the paperclip are left to a file that is neither.
        assertTrue(media < bind.indexOf("ft_progressbar.setVisibility(View.VISIBLE)"));
        assertTrue(media < bind.indexOf("GoogleMaterial.Icon.gmd_attachment"));
        // It goes: the transfer's own state is drawn over it, as in the holders before it.
        final String call = bare(bind.substring(media, bind.indexOf(')', bind.indexOf("button_cancel", media)) + 1));
        assertTrue(call, call.endsWith(",button_ok,button_cancel,true)"));
        final String taken = bare(body(bind.substring(media), "{"));
        assertEquals("setup_cancel_button(message);HelperGeneric.set_avatar_img_height_in_chat(img_avatar);"
                     + "ChatTransferProgressHelper.applyDirect(context,itemView,message,true);return;", taken);
    }

    @Test
    public void a_video_of_ours_opens_from_its_picture_as_a_photo_does() throws Exception
    {
        final String binder = body(code("HelperFiletransfer.java"), "static boolean bindOutgoingCompactMediaUi(");
        final int branch = binder.indexOf("if (isVideo)");
        assertTrue(branch >= 0);
        final String video = body(binder.substring(branch), "if (isVideo)");
        final String afterVideo = binder.substring(binder.indexOf(video, branch) + video.length());
        final String photo = body(afterVideo, "else");
        assertFalse(video.contains("setOnTouchListener"));
        assertFalse(photo.contains("setOnTouchListener"));
        // One tap for both, set after the branch so that no recycled row keeps the one it had.
        final String rest = bare(afterVideo.substring(afterVideo.indexOf(photo) + photo.length()));
        assertTrue(rest, rest.startsWith("}ft_preview_image.setOnTouchListener(ChatMediaHelper.mediaOpenTouchListener("
                                         + "context,message,null,itemView.findViewById(R.id.layout_message_container)));"
                                         + "returntrue;"));
    }

    private static String code(final String name) throws java.io.IOException
    {
        return AntispamOnRequestTest.code(source(name));
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

    private static String source(final String name) throws java.io.IOException
    {
        // Gradle runs unit tests from the module directory; an IDE may use the project root.
        for (final String root : new String[]{"src/main/java", "app/src/main/java"})
        {
            final java.io.File file = new java.io.File(root, "com/zoffcc/applications/trifa/" + name);
            if (file.isFile())
            {
                return new String(java.nio.file.Files.readAllBytes(file.toPath()),
                                  java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError(name + " not found from " + new java.io.File("").getAbsolutePath());
    }
}
