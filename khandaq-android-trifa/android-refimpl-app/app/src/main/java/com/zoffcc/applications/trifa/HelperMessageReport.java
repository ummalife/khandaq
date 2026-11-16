package com.zoffcc.applications.trifa;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import com.zoffcc.applications.sorm.GroupMessage;

import org.khandaq.messenger.R;

import java.util.List;

import static com.zoffcc.applications.trifa.TRIFAGlobals.TRIFA_MSG_TYPE.TRIFA_MSG_FILE;
import static com.zoffcc.applications.trifa.TrifaToxService.orma;

/**
 * KHANDAQ (0.2.57, SPEC-community part B, item 5): Report, in the actions of a message someone else wrote in a
 * group, any group. It opens the person's mail app with the mail of GroupReportMail, for them to read and send;
 * the app sends nothing itself.
 */
final class HelperMessageReport
{
    private static final String TAG = "trifa.MsgReport";

    private HelperMessageReport()
    {
    }

    /** Gate for the Report item: exactly one message of a group, written by someone else. */
    static boolean canReportCurrentSelection()
    {
        try
        {
            return selectedReportable() != null;
        }
        catch (Exception e)
        {
            return false;
        }
    }

    /** Opens the mail about the selected message; with no mail app here, says so under the field. */
    static void reportSelectedGroupMessage(final Context context)
    {
        final GroupMessage m;
        try
        {
            m = selectedReportable();
        }
        catch (Exception e)
        {
            Log.w(TAG, "report:EE:" + e.getMessage());
            return;
        }
        if (m == null)
        {
            return;
        }
        // The group by the name its chat shows.
        final String groupName = HelperGroup.group_list_title(
                HelperGroup.get_effective_group_title(HelperGroup.tox_group_by_groupid__wrapper(m.group_identifier),
                                                      m.group_identifier), null, m.group_identifier);
        // The message as it reads: its text as Copy gives it, a file by the name its entry shows.
        final String text = (m.TRIFA_MESSAGE_TYPE == TRIFA_MSG_FILE.value) ? HelperFiletransfer.groupFileDisplayLabel(
                context, m) : GroupMentionHelper.cleanForClipboard(m.text);
        // When it was written: a live message when it came, as its bubble shows; one history sync brought by the time
        // its record gave, as iOS and the desktop name it, not when the sync ran.
        final long time = GroupReportMail.reportedTimeMs(m.sent_timestamp, m.rcvd_timestamp, m.was_synced);

        // The author is the key stored with the message when it came, never looked up again from a member's number.
        // A message that came through history sync carries the relaying peer's unsigned claim: unknown, unless a
        // signature proved this very message, as its bubble shows it ("sender not verified").
        final String author = GroupReportMail.reportedAuthorKey(m.tox_group_peer_pubkey, m.was_synced,
                                                                m.was_synced && NgcSignedHistory.isAuthorVerified(m));
        final String link = GroupReportMail.mailto(groupName, author, time, text);
        final Intent mail = new Intent(Intent.ACTION_SENDTO, Uri.parse(link));
        // For the mail apps that read the extras rather than the link: the same subject and body.
        mail.putExtra(Intent.EXTRA_SUBJECT, GroupReportMail.subject(groupName));
        mail.putExtra(Intent.EXTRA_TEXT, GroupReportMail.body(groupName, author, time, text));
        // Who and where, never the words of the message.
        HelperGeneric.logI(TAG, "report: a mail to support about a message of " + GroupReportMail.authorPrefix(author)
                                + " in a group, " + link.length() + " characters");
        try
        {
            context.startActivity(mail);
        }
        catch (ActivityNotFoundException e)
        {
            AirComposerNotice.say(context.getString(R.string.MainActivity_no_email_app_found), true);
        }
    }

    /** The one selected message of a group when it can be reported, else null. */
    private static GroupMessage selectedReportable()
    {
        if (MainActivity.selected_group_messages.size() != 1)
        {
            return null;
        }
        final long id = MainActivity.selected_group_messages.iterator().next();
        final List<GroupMessage> rows = orma.selectFromGroupMessage().idEq(id).toList();
        if ((rows == null) || rows.isEmpty() || (rows.get(0).group_identifier == null))
        {
            return null;
        }
        final GroupMessage m = rows.get(0);
        final long groupNumber = HelperGroup.tox_group_by_groupid__wrapper(m.group_identifier);
        final String ownGroupKey = (groupNumber >= 0) ? HelperGroup.tox_group_self_get_public_key__wrapper(
                groupNumber) : null;
        return GroupReportMail.reportable(m.direction, m.tox_group_peer_pubkey, ownGroupKey) ? m : null;
    }
}
