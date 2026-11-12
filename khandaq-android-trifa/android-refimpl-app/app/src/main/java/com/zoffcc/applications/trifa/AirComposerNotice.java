package com.zoffcc.applications.trifa;

import android.content.res.ColorStateList;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;

/**
 * KHANDAQ (redesign, E21): what the composer refused, or what failed when it sent, said on the line under
 * the field instead of in a toast (design/DESIGN.md, "Errors and questions on the phone"). A failure is
 * in the danger colour; a rule, such as a size limit, in the second ink with what to do. The words come
 * in with a fade and a 4 dp rise, are spoken, and give the line back to the conversation when the field
 * changes or after {@link #SHOW_MS}.
 */
final class AirComposerNotice
{
    static final long SHOW_MS = 6000L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private final TextView line;
    private final View dot;
    private final Runnable restore;
    private final ColorStateList inks;
    private final Runnable timeout = this::end;
    @Nullable
    private CharSequence words = null;

    /**
     * {@code line} is the panel's hint under the field and {@code dot} the dot before it; {@code restore}
     * draws the panel's own hint again once the notice is over.
     */
    AirComposerNotice(final TextView line, final View dot, final Runnable restore)
    {
        this.line = line;
        this.dot = dot;
        this.restore = restore;
        this.inks = line.getTextColors();
    }

    /** True while the line says the notice: the panel then leaves its hint for later. */
    boolean isShown()
    {
        return words != null;
    }

    void show(final CharSequence text, final boolean failed)
    {
        show(text, failed, null);
    }

    /**
     * A failure with a code for support (AirErrorCode, stage 5): the words end in Details, and the row
     * under the field opens the code with Copy (TalkBack reaches the row, not the line in it). Such words
     * stay twice as long, so there is time to open them.
     */
    void show(final CharSequence text, final boolean failed, @Nullable final String code)
    {
        if (text == null || text.length() == 0)
        {
            return;
        }
        final String details = failed ? code : null;
        words = text;
        MAIN.removeCallbacks(timeout);
        dot.setVisibility(View.GONE);
        AirUi.sayOnLine(line, text, failed, details);
        final View row = row();
        if (row != null)
        {
            AirErrorDetails.attach(row, details);
        }
        MAIN.postDelayed(timeout, details == null ? SHOW_MS : 2 * SHOW_MS);
    }

    /** Ends the notice now; the panel's own words come back. Nothing happens when none is shown. */
    void end()
    {
        MAIN.removeCallbacks(timeout);
        if (words == null)
        {
            return;
        }
        words = null;
        endDetails();
        line.animate().cancel();
        line.setAlpha(1f);
        line.setTranslationY(0f);
        line.setTextColor(inks);
        restore.run();
    }

    /** The panel goes: the notice ends without drawing anything. */
    void release()
    {
        MAIN.removeCallbacks(timeout);
        words = null;
        endDetails();
        line.animate().cancel();
    }

    /** The row the line stands in, under the field. */
    @Nullable
    private View row()
    {
        return line.getParent() instanceof View ? (View) line.getParent() : null;
    }

    private void endDetails()
    {
        AirErrorDetails.attach(line, null);
        final View row = row();
        if (row != null)
        {
            AirErrorDetails.attach(row, null);
        }
    }

    /**
     * For a call site without a panel at hand (a static send path, a helper): the words go to the
     * composer of the conversation on screen. With no conversation on screen there is no field to say
     * them under, and they come as the toast they were before.
     */
    static void say(final CharSequence text, final boolean failed)
    {
        say(text, failed, null);
    }

    /**
     * {@link #say} for a failure with a code for support: Details under the field opens it. A toast has
     * no Details, so the code goes with the words only where a field is on screen.
     */
    static void say(final CharSequence text, final boolean failed, @Nullable final String code)
    {
        if (text == null || text.length() == 0)
        {
            return;
        }
        MAIN.post(() -> {
            final GroupMessageListActivity group = MainActivity.group_message_list_activity;
            if (group != null && !group.isFinishing() && group.airPanel != null && group.airPanel.sayAtComposer(text,
                                                                                                                failed,
                                                                                                                code))
            {
                return;
            }
            final MessageListActivity chat = MainActivity.message_list_activity;
            if (chat != null && !chat.isFinishing() && chat.airPanel != null && chat.airPanel.sayAtComposer(text,
                                                                                                           failed,
                                                                                                           code))
            {
                return;
            }
            HelperGeneric.display_toast(text.toString(), true, 0);
        });
    }

    /** A conversation's line under its field: true when the words stand there. */
    interface Field
    {
        boolean say(CharSequence text, boolean failed);

        /**
         * A failure with its code for support (AirErrorCode): a place that shows Details takes the code,
         * any other says the words alone.
         */
        default boolean say(final CharSequence text, final boolean failed, @Nullable final String code)
        {
            return say(text, failed);
        }
    }

    /** How long the words of a screen that closes wait for the conversation under it to be back. */
    static final long RETURN_MS = 2000L;

    @Nullable
    private static CharSequence returnWords = null;
    private static boolean returnFailed = false;
    private static final Runnable RETURN_TIMEOUT = () -> {
        final CharSequence words = returnWords;
        returnWords = null;
        if (words != null)
        {
            HelperGeneric.display_toast(words.toString(), true, 0);
        }
    };

    /**
     * For a screen in front of a conversation that closes because of what it says (a viewer that cannot
     * show the file, a picker with nobody in it): the words wait for the conversation under it and stand
     * under its field once it is back on screen ({@link #deliverOnReturn}). When none is back within
     * {@link #RETURN_MS}, they come as the toast they were before.
     */
    static void sayOnReturn(final CharSequence text, final boolean failed)
    {
        if (text == null || text.length() == 0)
        {
            return;
        }
        MAIN.post(() -> {
            returnWords = text;
            returnFailed = failed;
            MAIN.removeCallbacks(RETURN_TIMEOUT);
            MAIN.postDelayed(RETURN_TIMEOUT, RETURN_MS);
        });
    }

    /** A conversation is back on screen (main thread): words a closing screen left stand under its field. */
    static void deliverOnReturn(final Field field)
    {
        final CharSequence words = returnWords;
        if (words != null && field.say(words, returnFailed))
        {
            returnWords = null;
            MAIN.removeCallbacks(RETURN_TIMEOUT);
        }
    }

    /**
     * For what happens by itself in one group (a message or a file that could not be read): said under
     * that group's field while the group is on screen, and nowhere else, so another screen is not
     * interrupted by it. An unknown group says nothing.
     */
    static void sayInGroup(@Nullable final String groupId, final CharSequence text, final boolean failed)
    {
        if (groupId == null || "-1".equals(groupId) || text == null || text.length() == 0)
        {
            return;
        }
        MAIN.post(() -> {
            final GroupMessageListActivity group = MainActivity.group_message_list_activity;
            if (group != null && !group.isFinishing() && group.airPanel != null
                && groupId.equalsIgnoreCase(group.get_current_group_id()))
            {
                group.airPanel.sayAtComposer(text, failed);
            }
        });
    }

    /**
     * For what happens by itself with one person (a file of theirs refused for its size): said under the
     * field of their conversation while it is on screen. Elsewhere it comes as the toast it was before,
     * since no entry of the transcript keeps it and it would be lost otherwise.
     */
    static void sayInChat(@Nullable final String publicKey, final CharSequence text, final boolean failed)
    {
        if (publicKey == null || publicKey.isEmpty() || text == null || text.length() == 0)
        {
            return;
        }
        MAIN.post(() -> {
            final MessageListActivity chat = MainActivity.message_list_activity;
            if (chat != null && !chat.isFinishing() && chat.airPanel != null
                && publicKey.equalsIgnoreCase(chat.get_friend_pubkey()) && chat.airPanel.sayAtComposer(text, failed))
            {
                return;
            }
            HelperGeneric.display_toast(text.toString(), true, 0);
        });
    }
}
