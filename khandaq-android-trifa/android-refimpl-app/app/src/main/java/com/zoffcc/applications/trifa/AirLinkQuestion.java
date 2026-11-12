package com.zoffcc.applications.trifa;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import androidx.core.text.BidiFormatter;
import androidx.core.text.TextDirectionHeuristicsCompat;

import org.khandaq.messenger.R;

import java.util.Locale;

import static com.zoffcc.applications.trifa.HelperFriend.add_friend_real;

/**
 * KHANDAQ (redesign, stage 5): what a tap on a link, a mail address or a Tox ID in a message asks before
 * anything opens, from below and in the app's language (AirQuestion). The steps are those of the old
 * dialogs of the message rows, whose words were English in every language: a link opens in another app
 * (https when it has no scheme), an address in a mail app, and an ID gets a contact request with the
 * usual greeting (add_friend_real, which also refuses what it refused before).
 */
final class AirLinkQuestion
{
    private static final String TAG = "trifa.AirLinkQuestion";

    private AirLinkQuestion()
    {
    }

    /** The link as it opens: one without a scheme gets https, as before. */
    static String withScheme(final String link)
    {
        final String trimmed = link == null ? "" : link.trim();
        return trimmed.contains("://") ? trimmed : "https://" + trimmed;
    }

    /** The ID as it is added: capitals, no spaces and no tox: prefix, as before. */
    static String bareId(final String id)
    {
        final String upper = id == null ? "" : id.trim().toUpperCase(Locale.ROOT).replace(" ", "");
        return upper.startsWith("TOX:") ? upper.substring(4) : upper;
    }

    static void openLink(final Context context, final String link)
    {
        final String url = withScheme(link);
        AirQuestion.ask(context, context.getString(R.string.air_text_link_question), ltr(url),
                        context.getString(R.string.air_text_link_open), false, () -> {
                            try
                            {
                                context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                            }
                            catch (Exception e)
                            {
                                Log.i(TAG, "no app opened the link: " + e.getClass().getSimpleName());
                            }
                        });
    }

    static void writeEmail(final Context context, final String address)
    {
        final String to = address == null ? "" : address.trim();
        AirQuestion.ask(context, context.getString(R.string.air_text_email_question), ltr(to),
                        context.getString(R.string.air_text_email_write), false, () -> {
                            try
                            {
                                final Intent mail = new Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", to, null));
                                context.startActivity(
                                        Intent.createChooser(mail, context.getString(R.string.air_text_email_chooser)));
                            }
                            catch (Exception e)
                            {
                                Log.i(TAG, "no app took the address: " + e.getClass().getSimpleName());
                            }
                        });
    }

    static void requestContact(final Context context, final String id)
    {
        final String bare = bareId(id);
        AirQuestion.ask(context, context.getString(R.string.air_text_id_question), ltr(bare),
                        context.getString(R.string.air_nc_send), false, () -> add_friend_real(bare));
    }

    /** Latin addresses keep their order in a right-to-left language. */
    private static CharSequence ltr(final String text)
    {
        return BidiFormatter.getInstance().unicodeWrap(text, TextDirectionHeuristicsCompat.LTR);
    }
}
