package com.zoffcc.applications.trifa;

import android.animation.ValueAnimator;
import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.ColorInt;
import androidx.annotation.IntegerRes;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.content.ContextCompat;
import androidx.core.view.AccessibilityDelegateCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;

import org.khandaq.messenger.R;

/**
 * KHANDAQ (redesign): the small rules every part of the Air screen shares, so a row, a pill and the
 * "you" block say the same thing the same way. Mirrors AirUi on iOS.
 */
final class AirUi
{
    /** Counts above this read "99+". */
    static final int COUNT_CAP = 99;

    private static final int[] TONES = {
            R.color.kq_tone_1, R.color.kq_tone_2, R.color.kq_tone_3,
            R.color.kq_tone_4, R.color.kq_tone_5, R.color.kq_tone_6,
    };

    private AirUi()
    {
    }

    @ColorInt
    static int color(final Context context, final int colorRes)
    {
        return ContextCompat.getColor(context, colorRes);
    }

    /** The initials colour of a person or group tile, from its stable tone index. */
    @ColorInt
    static int toneColor(final Context context, final int tone)
    {
        return color(context, TONES[AirModel.Tone.wrap(tone)]);
    }

    /** The system "remove animations" setting (developer options or accessibility). */
    static boolean reducedMotion()
    {
        return !ValueAnimator.areAnimatorsEnabled();
    }

    /** A token duration, capped for reduced motion so state still changes visibly but briefly. */
    static long duration(final Context context, @IntegerRes final int res)
    {
        final int value = context.getResources().getInteger(res);
        if (reducedMotion())
        {
            return Math.min(value, context.getResources().getInteger(R.integer.kq_dur_reduced_max));
        }
        return value;
    }

    /**
     * E21: the words of a refusal or a failure on the line they stand on: a failure in the danger
     * colour, a rule in the second ink. They fade in with a 4 dp rise, at once with reduced motion, and
     * are spoken.
     */
    static void sayOnLine(final TextView line, final CharSequence text, final boolean failed)
    {
        sayOnLine(line, text, failed, null);
    }

    /**
     * {@link #sayOnLine} for a failure with a code for support (AirErrorCode, stage 5): the words end in
     * Details, and the line opens the code with Copy. A rule has no code; one given with it is ignored.
     */
    static void sayOnLine(final TextView line, final CharSequence text, final boolean failed,
                          @Nullable final String code)
    {
        final Context context = line.getContext();
        final String details = failed ? code : null;
        line.setText(AirErrorDetails.words(context, text, details));
        AirErrorDetails.attach(line, details);
        line.setTextColor(color(context, failed ? R.color.kq_danger : R.color.kq_ink_2));
        line.setVisibility(View.VISIBLE);
        line.animate().cancel();
        if (reducedMotion())
        {
            line.setAlpha(1f);
            line.setTranslationY(0f);
        }
        else
        {
            line.setAlpha(0f);
            line.setTranslationY(4f * context.getResources().getDisplayMetrics().density);
            line.animate().alpha(1f).translationY(0f).setDuration(duration(context, R.integer.kq_dur_state)).start();
        }
        line.announceForAccessibility(text);
    }

    /** Takes such words away: the line goes, with nothing left halfway through its fade. */
    static void endLine(final TextView line)
    {
        AirErrorDetails.attach(line, null);
        line.animate().cancel();
        line.setAlpha(1f);
        line.setTranslationY(0f);
        line.setVisibility(View.GONE);
    }

    /**
     * E21 on a screen that has no line of its own for it: the words stand on a caption line right under
     * {@code anchor} (a field, a button or their row), inserted once into the nearest vertical column
     * around it and found there again, then said as {@link #sayOnLine} says them. False when no such
     * column holds the anchor, so the caller can say it another way.
     */
    static boolean sayUnder(@Nullable final View anchor, final CharSequence text, final boolean failed)
    {
        return sayUnder(anchor, text, failed, null);
    }

    /** {@link #sayUnder} for a failure with a code for support: Details after the words opens it. */
    static boolean sayUnder(@Nullable final View anchor, final CharSequence text, final boolean failed,
                            @Nullable final String code)
    {
        final TextView line = lineUnder(anchor, true);
        if (line == null)
        {
            return false;
        }
        sayOnLine(line, text, failed, code);
        return true;
    }

    /** Takes the words under {@code anchor} away, when there are any: the field changed, or it went through. */
    static void endUnder(@Nullable final View anchor)
    {
        final TextView line = lineUnder(anchor, false);
        if (line != null && line.getVisibility() != View.GONE)
        {
            endLine(line);
        }
    }

    @Nullable
    private static TextView lineUnder(@Nullable final View anchor, final boolean create)
    {
        if (anchor == null)
        {
            return null;
        }
        View child = anchor;
        ViewParent parent = anchor.getParent();
        // A field and its button in one row: the words go under the whole row, in the column holding it.
        while (parent instanceof ViewGroup)
        {
            if (parent instanceof LinearLayout && ((LinearLayout) parent).getOrientation() == LinearLayout.VERTICAL)
            {
                final LinearLayout column = (LinearLayout) parent;
                final int index = column.indexOfChild(child);
                final View next = column.getChildAt(index + 1);
                if (next instanceof TextView && Boolean.TRUE.equals(next.getTag(R.id.air_note_under)))
                {
                    return (TextView) next;
                }
                if (!create)
                {
                    return null;
                }
                final Context context = anchor.getContext();
                final TextView line = AirScreenKit.note(context, "");
                line.setTag(R.id.air_note_under, Boolean.TRUE);
                line.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
                line.setVisibility(View.GONE);
                final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                params.topMargin = context.getResources().getDimensionPixelSize(R.dimen.kq_space_4);
                // Lined up with what it stands under.
                final ViewGroup.LayoutParams above = child.getLayoutParams();
                if (above instanceof ViewGroup.MarginLayoutParams)
                {
                    params.setMarginStart(((ViewGroup.MarginLayoutParams) above).getMarginStart());
                    params.setMarginEnd(((ViewGroup.MarginLayoutParams) above).getMarginEnd());
                }
                column.addView(line, index + 1, params);
                return line;
            }
            child = (View) parent;
            parent = parent.getParent();
        }
        return null;
    }

    /** TalkBack announces a styled text or layout as a button, the way it does a real one. */
    static void markAsButton(final View view)
    {
        ViewCompat.setAccessibilityDelegate(view, new AccessibilityDelegateCompat()
        {
            @Override
            public void onInitializeAccessibilityNodeInfo(final View host, final AccessibilityNodeInfoCompat info)
            {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(Button.class.getName());
            }
        });
    }

    /**
     * Shows a dialog with its buttons as everywhere in the design: labels in normal case (the dialog
     * theme sets capitals), the action in ink or, when it cannot be taken back, in the danger colour,
     * and the way out in the second ink.
     */
    static void showDialog(final Context context, final androidx.appcompat.app.AlertDialog dialog,
                           final boolean destructive)
    {
        dialog.show();
        final Button positive = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE);
        if (positive != null)
        {
            positive.setAllCaps(false);
            positive.setTextColor(color(context, destructive ? R.color.kq_danger : R.color.kq_ink));
        }
        final Button neutral = dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL);
        if (neutral != null)
        {
            neutral.setAllCaps(false);
            neutral.setTextColor(color(context, R.color.kq_ink));
        }
        final Button negative = dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE);
        if (negative != null)
        {
            negative.setAllCaps(false);
            negative.setTextColor(color(context, R.color.kq_ink_2));
        }
    }

    /**
     * KHANDAQ (redesign, stage 3): the emoji panel on the design's grounds in both themes, the ground of
     * the composer under its icons; it was a fixed dark grey whatever the theme.
     */
    static com.vanniktech.emoji.EmojiPopup.Builder emojiPanelColors(final Context context,
                                                                 final com.vanniktech.emoji.EmojiPopup.Builder builder)
    {
        return builder.setBackgroundColor(color(context, R.color.kq_surface))
                      .setIconColor(color(context, R.color.kq_ink_3))
                      .setSelectedIconColor(color(context, R.color.kq_ink))
                      .setDividerColor(color(context, R.color.kq_line));
    }

    /** A toggle such as a filter pill: announced as a button with its selected state. */
    static void markAsToggle(final View view)
    {
        ViewCompat.setAccessibilityDelegate(view, new AccessibilityDelegateCompat()
        {
            @Override
            public void onInitializeAccessibilityNodeInfo(final View host, final AccessibilityNodeInfoCompat info)
            {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(Button.class.getName());
                info.setCheckable(false);
                info.setSelected(host.isSelected());
            }
        });
    }

    /**
     * A one-line field whose hint the phone's font leaves no room for: the hint ends in the platform's
     * ellipsis, as a title does, instead of stopping after a word; TalkBack still reads all of it. An
     * EditText draws its hint unellipsized, so the fitted hint is set after each layout.
     */
    static void fitHint(final EditText field, @StringRes final int hint)
    {
        ViewCompat.setAccessibilityDelegate(field, new AccessibilityDelegateCompat()
        {
            @Override
            public void onInitializeAccessibilityNodeInfo(final View host, final AccessibilityNodeInfoCompat info)
            {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setHintText(host.getContext().getString(hint));
            }
        });
        field.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            final int room = field.getWidth() - field.getCompoundPaddingLeft() - field.getCompoundPaddingRight();
            if (room <= 0)
            {
                return;
            }
            final CharSequence fitted = TextUtils.ellipsize(field.getContext().getString(hint), field.getPaint(),
                                                            room, TextUtils.TruncateAt.END);
            if (!TextUtils.equals(fitted, field.getHint()))
            {
                field.post(() -> field.setHint(fitted));
            }
        });
    }

    /** "99+" above the cap. Digits come out in the user's locale (Arabic-Indic in Arabic). */
    static String countText(final Context context, final int count)
    {
        final java.text.NumberFormat format = java.text.NumberFormat.getIntegerInstance(locale(context));
        if (count > COUNT_CAP)
        {
            return format.format(COUNT_CAP) + "+";
        }
        return format.format(Math.max(0, count));
    }

    static java.util.Locale locale(final Context context)
    {
        return context.getResources().getConfiguration().getLocales().get(0);
    }

    /**
     * The text in the digits {@link #countText} uses: a translation's "24", the ":00" after an hour and the
     * old clock formatter (it keeps the language the app started in) then match the numbers the app's
     * language formats itself.
     */
    static String localDigits(final Context context, final String text)
    {
        return AirConversationModel.localDigits(
                text, java.text.DecimalFormatSymbols.getInstance(locale(context)).getZeroDigit());
    }

    /** The words after a name: "online", "3 min ago", "waiting for reply", "5 online". Empty for saved messages. */
    static String reachText(final Context context, final AirModel.Reach reach)
    {
        switch (reach.kind)
        {
            case DIRECT:
                return context.getString(R.string.air_reach_live);
            case RELAY:
                return context.getString(R.string.air_reach_relay);
            case PENDING:
                return context.getString(R.string.air_reach_pending);
            case CONNECTING:
                return context.getString(R.string.air_reach_connecting);
            case GROUP:
                return reach.online > 0 ? context.getString(R.string.air_reach_group_online, reach.online)
                                        : context.getString(R.string.air_reach_group_none);
            case AWAY:
                return awayText(context, reach.secondsAgo);
            case LOCAL:
            default:
                return "";
        }
    }

    static String awayText(final Context context, final long secondsAgo)
    {
        final AirModel.AwayWords words = AirModel.AwayWords.of(secondsAgo);
        final int amount = (int) Math.min(Integer.MAX_VALUE, words.amount);
        switch (words.unit)
        {
            case JUST_NOW:
                return context.getString(R.string.air_reach_just_now);
            case MINUTES:
                return context.getString(R.string.air_reach_minutes, amount);
            case HOURS:
                return context.getString(R.string.air_reach_hours, amount);
            case DAYS:
                return context.getString(R.string.air_reach_days, amount);
            case LONG_AGO:
            default:
                return context.getString(R.string.air_reach_long_ago);
        }
    }

    static String networkText(final Context context, final AirModel.NetworkState network)
    {
        switch (network)
        {
            case DIRECT:
                return context.getString(R.string.air_net_direct);
            case RELAY:
                return context.getString(R.string.air_net_relay);
            case OFFLINE:
                return context.getString(R.string.air_net_offline);
            case CONNECTING:
            default:
                return context.getString(R.string.air_net_connecting);
        }
    }

    static String deliveryWord(final Context context, final AirModel.Delivery delivery)
    {
        if (delivery == null)
        {
            return "";
        }
        switch (delivery)
        {
            case TRANSIT:
                return context.getString(R.string.air_delivery_transit);
            case DELIVERED:
                return context.getString(R.string.air_delivery_delivered);
            case SENT:
            default:
                return context.getString(R.string.air_delivery_sent);
        }
    }

    static AirTileView.Kind tileKind(final AirModel.Entry entry)
    {
        switch (entry.kind)
        {
            case SAVED:
                return AirTileView.Kind.SAVED;
            case GROUP:
                return AirTileView.Kind.GROUP;
            case DIRECT:
            case CONTACT:
            default:
                return entry.reach.kind == AirModel.Reach.Kind.PENDING ? AirTileView.Kind.PENDING
                                                                       : AirTileView.Kind.PERSON;
        }
    }

    /** One TalkBack sentence for a row, in the order the eye reads it. */
    static String rowDescription(final Context context, final AirModel.Entry entry)
    {
        final StringBuilder text = new StringBuilder(entry.title);
        append(text, reachText(context, entry.reach));
        append(text, deliveryWord(context, entry.lastDelivery));
        append(text, entry.preview);
        if (entry.transitCount > 0)
        {
            append(text, context.getString(R.string.air_transit_format, entry.transitCount));
        }
        if (entry.unreadCount > 0)
        {
            append(text, context.getString(R.string.air_unread_a11y, entry.unreadCount));
        }
        append(text, entry.timeText);
        return text.toString();
    }

    private static void append(final StringBuilder text, final String part)
    {
        if (part != null && !part.isEmpty())
        {
            text.append(", ").append(part);
        }
    }
}
