package com.zoffcc.applications.trifa;

import org.junit.Test;

import static com.zoffcc.applications.trifa.ChatListUiHelper.received_file_name;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * KHANDAQ (QA live, F8): the chat list shows a file that reached us by its name, never with TRIfA's byte count.
 *
 * QA: QA Windows sent qa-note-win.txt to Android, and the row's preview read "qa-note-win.txt 4440 bytes".
 * The message text of a received file is TRIfA's "[name]\n[N] bytes" (MainActivity, HelperGroup), and the
 * preview flattened it into one line. The preview now takes the name out of it, or the photo, video or
 * voice label instead; those labels need a Context and are not built here.
 */
public class ChatListPreviewTest
{
    @Test
    public void a_received_file_is_known_by_its_name()
    {
        assertEquals("qa-note-win.txt", received_file_name("qa-note-win.txt\n4440 bytes"));
        assertEquals("photo 1.png", received_file_name("photo 1.png\n0 bytes"));
        // With stray spaces and a line end after the count.
        assertEquals("qa-doc-win.pdf", received_file_name(" qa-doc-win.pdf \n 123456 bytes \n"));
    }

    @Test
    public void any_other_text_is_not_a_received_file()
    {
        assertNull(received_file_name(null));
        assertNull(received_file_name(""));
        assertNull(received_file_name("qa-note-win.txt"));
        assertNull(received_file_name("see you\nat 5"));
        assertNull(received_file_name("qa-note-win.txt\n4440 bytes and more"));
        assertNull(received_file_name("\n4440 bytes"));
        assertNull(received_file_name("qa-note-win.txt\n bytes"));
        assertNull(received_file_name("qa-note-win.txt\n-4 bytes"));
        assertNull(received_file_name("qa-note-win.txt\n4440 Bytes"));
    }
}
