package com.zoffcc.applications.trifa;

import org.junit.Test;

import static com.zoffcc.applications.trifa.HelperFiletransfer.incoming_file_is_here;
import static com.zoffcc.applications.trifa.HelperFiletransfer.incoming_retry_applies;
import static com.zoffcc.applications.trifa.ToxVars.TOX_FILE_CONTROL.TOX_FILE_CONTROL_CANCEL;
import static com.zoffcc.applications.trifa.ToxVars.TOX_FILE_CONTROL.TOX_FILE_CONTROL_PAUSE;
import static com.zoffcc.applications.trifa.ToxVars.TOX_FILE_CONTROL.TOX_FILE_CONTROL_RESUME;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (QA live, F3): a file of theirs that came whole stays a finished file.
 *
 * QA: QA Windows sent a PNG, a PDF and a TXT in a row, and the TXT's row had no "Save to phone", also after
 * the chat was opened again. A row drawn while its file comes posts a retry of the transfer, and the TXT was
 * whole before that retry ran. The retry still held the message as it had been, with its transfer, and set
 * the finished file back to RESUME: the state of a transfer under way, whose row holder offers no Save.
 */
public class IncomingFileRetryTest
{
    private static final long FILE_ROW = 42;
    private static final long TRANSFER = 7;
    private static final long NONE = -1;

    @Test
    public void a_retry_resumes_a_transfer_that_has_no_whole_file()
    {
        // Under way, paused, or failed: fail_incoming_filetransfer drops the file row and keeps the transfer id.
        assertTrue(incoming_retry_applies(NONE, TRANSFER));
    }

    @Test
    public void a_retry_leaves_a_whole_file_alone()
    {
        // The QA case: the file came whole after the retry was asked for.
        assertFalse(incoming_retry_applies(FILE_ROW, NONE));
        // In the middle of the finish: the file row is in, the transfer id not cleared yet.
        assertFalse(incoming_retry_applies(FILE_ROW, TRANSFER));
        // No transfer to resume.
        assertFalse(incoming_retry_applies(NONE, NONE));
        assertFalse(incoming_retry_applies(NONE, 0));
    }

    @Test
    public void a_file_that_came_whole_is_here()
    {
        assertTrue(incoming_file_is_here(TOX_FILE_CONTROL_CANCEL.value, FILE_ROW, NONE));
        assertTrue(incoming_file_is_here(TOX_FILE_CONTROL_CANCEL.value, FILE_ROW, TRANSFER));
        // The rows the late retry left in the database.
        assertTrue(incoming_file_is_here(TOX_FILE_CONTROL_RESUME.value, FILE_ROW, NONE));
    }

    @Test
    public void a_file_on_its_way_or_failed_is_not_here()
    {
        assertFalse(incoming_file_is_here(TOX_FILE_CONTROL_PAUSE.value, NONE, TRANSFER));  // waits for Accept
        assertFalse(incoming_file_is_here(TOX_FILE_CONTROL_RESUME.value, NONE, TRANSFER)); // on its way
        assertFalse(incoming_file_is_here(TOX_FILE_CONTROL_CANCEL.value, NONE, TRANSFER)); // failed or declined
        // A file row next to a transfer under way is not taken for a file that is here.
        assertFalse(incoming_file_is_here(TOX_FILE_CONTROL_RESUME.value, FILE_ROW, TRANSFER));
    }
}
