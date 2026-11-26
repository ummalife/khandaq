package com.zoffcc.applications.trifa;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.text.style.ClickableSpan;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.core.widget.TextViewCompat;

import com.luseen.autolinklibrary.EmojiTextViewLinks;
import com.zoffcc.applications.sorm.GroupMessage;
import com.zoffcc.applications.sorm.Message;

import org.khandaq.messenger.R;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import static com.zoffcc.applications.trifa.MainActivity.PREF_GLOBAL_FONT_SIZE_DEFAULT;
import static com.zoffcc.applications.trifa.MainActivity.PREF__global_font_size;
import static com.zoffcc.applications.trifa.TRIFAGlobals.MESSAGE_TEXT_SIZE;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TRIFA_MSG_TYPE.TRIFA_MSG_TYPE_TEXT;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TRIFA_SYSTEM_MESSAGE_PEER_PUBKEY;

/**
 * KHANDAQ (redesign, stage 2): a text message of the conversation with one person as an entry of the
 * transcript in design/system/index.html (02-plan.md, screen 5), as AirEntryTextCell on iOS. The row
 * holders keep their data binding (links, location, reactions, selection, swipe to reply) and hand the
 * look to this class: the time in a column of tabular figures, the author over the first entry of a run
 * in their tone, our own mark at the far end, no bubble, the day where the text starts.
 *
 * <p>While the person is away, our texts without a receipt are not entries: they wait in the "in
 * transit" tray of {@link AirChatPanel} and join the log when the person comes online, rising into it.
 * While the person is online the same texts stand in the log as leaving or sent, never in transit.
 */
final class AirTranscript
{
    private static final String TAG = "trifa.AirTranscript";

    /** The plate of an entry chosen for the message actions. */
    static final int SELECTED_BG = R.drawable.air_bg_entry_selected;

    /** The faint clock of a message leaving right now. */
    private static final float SENDING_ALPHA = 0.45f;

    /** The open conversation, set by AirChatPanel on the main thread: whose tone and name an author takes. */
    static volatile String personKey = null;
    static volatile String personName = "";
    static volatile boolean personOnline = false;

    private AirTranscript()
    {
    }

    // ---- which rows ----

    /** The row is an entry of the transcript (air_entry_text), not a bubble of another layout. */
    static boolean isEntry(final View itemView)
    {
        return itemView != null && itemView.findViewById(R.id.air_entry_author) != null;
    }

    /**
     * One of our texts to the open conversation's person that waits for them
     * ({@link AirRepository#isWaitingDirectText}) while they are away: it stands in the tray, not in the
     * log. While they are online there is no tray, as on iOS.
     */
    static boolean isTrayRow(final Message m)
    {
        final String key = personKey;
        return !personOnline && key != null && m != null && key.equalsIgnoreCase(m.tox_friendpubkey)
               && AirRepository.isWaitingDirectText(m);
    }

    /** The log without the tray's messages; the same list when there are none. */
    static List<Message> withoutTrayRows(final List<Message> rows)
    {
        if (rows == null)
        {
            return null;
        }
        boolean any = false;
        for (final Message m : rows)
        {
            if (isTrayRow(m))
            {
                any = true;
                break;
            }
        }
        if (!any)
        {
            return rows;
        }
        final List<Message> kept = new ArrayList<>(rows.size());
        for (final Message m : rows)
        {
            if (!isTrayRow(m))
            {
                kept.add(m);
            }
        }
        return kept;
    }

    /**
     * The plate a chosen row of the conversation with one person takes; the rows of groups and
     * conferences, which share the selection helpers, keep theirs.
     */
    static int selectionPlate(final View row)
    {
        Context context = row == null ? null : row.getContext();
        while (context instanceof ContextWrapper)
        {
            if (context instanceof MessageListActivity)
            {
                return SELECTED_BG;
            }
            context = ((ContextWrapper) context).getBaseContext();
        }
        return R.drawable.bg_message_selection;
    }

    // ---- day marks ----

    private static final Object DAY_LOCK = new Object();
    private static Locale dayLocale = null;
    private static String dayZone = null;
    private static SimpleDateFormat sameYearFormat = null;
    private static SimpleDateFormat otherYearFormat = null;

    /** "Today", "Yesterday", "11 November", "11 November 2025": the mark above the first entry of a day. */
    static String dayText(final Context context, final long ms)
    {
        if (context == null || ms <= 0L)
        {
            return "";
        }
        final TimeZone zone = TimeZone.getDefault();
        final AirConversationModel.DayKind kind = AirConversationModel.dayKind(ms, System.currentTimeMillis(), zone);
        if (kind == AirConversationModel.DayKind.TODAY)
        {
            return context.getString(R.string.air_day_today);
        }
        if (kind == AirConversationModel.DayKind.YESTERDAY)
        {
            return context.getString(R.string.air_day_yesterday);
        }
        final Locale locale = AirUi.locale(context);
        synchronized (DAY_LOCK)
        {
            if (sameYearFormat == null || !locale.equals(dayLocale) || !zone.getID().equals(dayZone))
            {
                dayLocale = locale;
                dayZone = zone.getID();
                sameYearFormat = dayFormat(locale, zone, AirConversationModel.DayKind.SAME_YEAR);
                otherYearFormat = dayFormat(locale, zone, AirConversationModel.DayKind.OTHER_YEAR);
            }
            final SimpleDateFormat format = kind == AirConversationModel.DayKind.SAME_YEAR ? sameYearFormat
                                                                                         : otherYearFormat;
            return format.format(new Date(ms));
        }
    }

    private static SimpleDateFormat dayFormat(final Locale locale, final TimeZone zone,
                                              final AirConversationModel.DayKind kind)
    {
        final SimpleDateFormat format = new SimpleDateFormat(
                DateFormat.getBestDateTimePattern(locale, kind.dateTemplate()), locale);
        format.setTimeZone(zone);
        return format;
    }

    static void styleDayMark(final TextView label)
    {
        if (label == null)
        {
            return;
        }
        label.setBackground(null);
        label.setTextColor(AirUi.color(label.getContext(), R.color.kq_ink_3));
    }

    /** The day that floats over the top edge while the list scrolls. */
    static void styleFloatingDay(final TextView label)
    {
        if (label == null)
        {
            return;
        }
        label.setBackgroundResource(R.drawable.air_bg_day_float);
        label.setTextColor(AirUi.color(label.getContext(), R.color.kq_ink_2));
    }

    // ---- an entry ----

    /** The views of an entry, looked up once per holder. */
    private static final class Views
    {
        final ViewGroup container;
        final ViewGroup dayRow;
        final ViewGroup group;
        final TextView time;
        final TextView author;
        final ViewGroup meta;
        final TextView edited;
        final TextView retry;
        final ImageView mark;
        final EmojiTextViewLinks text;
        /** Group entries only: the private-message line over the text and the unverified-sender line. */
        final TextView note;
        final TextView unverified;
        /** What TalkBack says of the quote over a reply ({@link #spokenQuote}), kept for {@link #refreshState}. */
        String spokenQuote = "";

        Views(final View itemView)
        {
            container = itemView.findViewById(R.id.layout_message_container);
            dayRow = itemView.findViewById(R.id.message_text_date);
            group = itemView.findViewById(R.id.text_block_group);
            time = itemView.findViewById(R.id.date_time);
            author = itemView.findViewById(R.id.air_entry_author);
            meta = itemView.findViewById(R.id.air_entry_meta);
            edited = itemView.findViewById(R.id.air_entry_edited);
            retry = itemView.findViewById(R.id.air_entry_retry);
            mark = itemView.findViewById(R.id.m_icon);
            text = itemView.findViewById(R.id.m_text);
            note = itemView.findViewById(R.id.air_entry_note);
            unverified = itemView.findViewById(R.id.air_entry_unverified);
        }
    }

    private static Views views(final View itemView)
    {
        final Object tag = itemView.getTag(R.id.air_entry_views);
        if (tag instanceof Views)
        {
            return (Views) tag;
        }
        final Views views = new Views(itemView);
        itemView.setTag(R.id.air_entry_views, views);
        return views;
    }

    /**
     * Once per holder: the text takes the body face without the bubble's padding, spacing and link
     * colours that EmojiTextViewLinks starts with. Links stay ink, underlined.
     */
    static void prepare(final View itemView, final EmojiTextViewLinks text)
    {
        if (itemView == null || text == null)
        {
            return;
        }
        views(itemView);
        final Context context = text.getContext();
        bodyFace(text);

        final TextView retry = itemView.findViewById(R.id.air_entry_retry);
        if (retry != null)
        {
            AirUi.markAsButton(retry);
            retry.setContentDescription(context.getString(R.string.air_failed_retry) + ". "
                                        + context.getString(R.string.air_failed_retry_hint));
        }
    }

    /**
     * The body face of an entry's words, without the bubble's padding, spacing and link colours that
     * EmojiTextViewLinks starts with. Links stay ink, underlined. Also the caption of a file entry.
     */
    static void bodyFace(final EmojiTextViewLinks text)
    {
        final Context context = text.getContext();
        final int ink = AirUi.color(context, R.color.kq_ink);
        TextViewCompat.setTextAppearance(text, R.style.TextAppearance_KQ_Body);
        text.setIncludeFontPadding(false);
        text.setPadding(0, 0, 0, 0);
        text.setLetterSpacing(0f);
        text.setTextColor(ink);
        text.setLinkTextColor(ink);
        text.setMentionModeColor(ink);
        text.setHashtagModeColor(ink);
        text.setUrlModeColor(ink);
        text.setPhoneModeColor(ink);
        text.setEmailModeColor(ink);
        text.setCustomModeColor(ink);
        text.setSelectedStateColor(AirUi.color(context, R.color.kq_ink_2));
        text.enableUnderLine();
    }

    /**
     * The body size and line of an entry's words: the size the user picked in the app's settings,
     * relative to the design's body size. {@code emojiProbe} is the plain text whose emoji may take the
     * sticker size, or null for the usual one.
     */
    static void bodySize(final EmojiTextViewLinks text, @Nullable final CharSequence emojiProbe)
    {
        final Resources res = text.getResources();
        final float factor = textFactor();
        text.setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.kq_text_body) * factor);
        // setLineHeight keeps a spacing equal to the line it asks for, so the bubble's multiplier goes first.
        text.setLineSpacing(0f, 1f);
        TextViewCompat.setLineHeight(text, Math.round(res.getDimension(R.dimen.kq_line_body) * factor));
        ChatBubbleUiHelper.configure_message_emoji_size(text, emojiProbe, PREF__global_font_size, false);
    }

    /**
     * After the holder has set the text, its links and the location: the look of the entry. The text
     * keeps the size the user picked in the app's settings, relative to the design's body size.
     */
    static void bindText(final View itemView, final int position, final Message m,
                         final MessageReplyHelper.ParsedMessage parsed, final boolean locationBound)
    {
        if (itemView == null || m == null)
        {
            return;
        }
        final Views views = views(itemView);
        if (views.text == null || views.time == null || views.mark == null)
        {
            return;
        }
        final Context context = itemView.getContext();
        final Resources res = context.getResources();
        final boolean outgoing = m.direction == 1;
        final boolean callNote = HelperMessageEdit.isLocalCallLogRow(m);

        bodySize(views.text, (parsed != null && parsed.reply == null && !locationBound) ? parsed.bodyText : null);
        views.text.setTextColor(AirUi.color(context, callNote ? R.color.kq_ink_2 : R.color.kq_ink));
        // KHANDAQ (redesign, E9): a call is an entry with the phone glyph before its words.
        TextViewCompat.setCompoundDrawablesRelative(views.text, callNote ? callGlyph(context) : null, null,
                                                    null, null);
        views.text.setCompoundDrawablePadding(callNote ? res.getDimensionPixelSize(R.dimen.kq_space_8) : 0);

        bindTime(views, res, timeText(context, m, outgoing));

        if (views.group != null)
        {
            ChatBubbleUiHelper.bind_reply_quote(views.group, parsed == null ? null : parsed.reply,
                                                meta -> HelperReply.scrollToReplyTargetInDirectChat(meta));
            styleQuote(views.group);
        }
        views.spokenQuote = spokenQuote(context, parsed == null ? null : parsed.reply);

        if (views.edited != null)
        {
            views.edited.setText(R.string.message_edited_marker);
            views.edited.setVisibility(m.edited ? View.VISIBLE : View.GONE);
        }

        bindState(views, m, position);
        applyArrival(views.container, m.id);
    }

    /** The time column, widened for the text size, and the day mark lined up with the text after it. */
    private static void bindTime(final Views views, final Resources res, final CharSequence time)
    {
        views.time.setText(time);
        views.time.setVisibility(View.VISIBLE);
        final int column = timeColumnWidth(views.time);
        final ViewGroup.LayoutParams timeParams = views.time.getLayoutParams();
        if (timeParams != null && timeParams.width != column)
        {
            timeParams.width = column;
            views.time.setLayoutParams(timeParams);
        }
        if (views.dayRow != null)
        {
            final int start = res.getDimensionPixelSize(R.dimen.kq_space_16) + column
                              + res.getDimensionPixelSize(R.dimen.kq_space_10);
            if (views.dayRow.getPaddingStart() != start)
            {
                views.dayRow.setPaddingRelative(start, views.dayRow.getPaddingTop(), views.dayRow.getPaddingEnd(),
                                                views.dayRow.getPaddingBottom());
            }
        }
    }

    /** The phone glyph of a call entry, in the second ink and the size of the design's icons. */
    private static Drawable callGlyph(final Context context)
    {
        final Drawable icon = AppCompatResources.getDrawable(context, R.drawable.kq_ic_phone);
        if (icon == null)
        {
            return null;
        }
        final Drawable glyph = DrawableCompat.wrap(icon.mutate());
        DrawableCompat.setTint(glyph, AirUi.color(context, R.color.kq_ink_2));
        final int size = context.getResources().getDimensionPixelSize(R.dimen.kq_size_icon);
        glyph.setBounds(0, 0, size, size);
        return glyph;
    }

    /**
     * The parts of an entry that follow its neighbours and the person: the author over a run, the mark,
     * "Retry" and the sentence TalkBack reads. For a payload update, without touching the text.
     */
    static void refreshState(final View itemView, final Message m, final int position)
    {
        if (itemView == null || m == null)
        {
            return;
        }
        bindState(views(itemView), m, position);
    }

    private static void bindState(final Views views, final Message m, final int position)
    {
        final Context context = views.text.getContext();
        final Resources res = context.getResources();
        final boolean outgoing = m.direction == 1;
        final boolean saved = FavoritesChatHelper.isFavoritesChat(m.tox_friendpubkey);
        final boolean callNote = HelperMessageEdit.isLocalCallLogRow(m);

        // The author over the first entry of a run: "You" for ours, the first name in their tone otherwise.
        String fullAuthor = "";
        String shownAuthor = null;
        int authorColor = AirUi.color(context, R.color.kq_ink_2);
        if (!saved && !callNote)
        {
            if (outgoing)
            {
                fullAuthor = context.getString(R.string.air_you);
                authorColor = AirUi.youColor(context); // KHANDAQ (0.2.60): tone-5, not ink-2
            }
            else
            {
                final String name = personName;
                fullAuthor = TextUtils.isEmpty(name) ? "" : name;
                authorColor = AirUi.toneColor(context, AirModel.Tone.index(m.tox_friendpubkey));
            }
            if (!fullAuthor.isEmpty() && startsRun(context, m, position))
            {
                shownAuthor = outgoing ? fullAuthor : AirConversationModel.shortName(fullAuthor);
            }
        }
        if (views.author != null)
        {
            if (shownAuthor != null)
            {
                views.author.setText(shownAuthor);
                views.author.setTextColor(authorColor);
                views.author.setVisibility(View.VISIBLE);
            }
            else
            {
                views.author.setText(null);
                views.author.setVisibility(View.GONE);
            }
        }

        // The time and the mark sit on the first line: the author's, or the text's.
        final float factor = textFactor();
        final int firstLine = shownAuthor != null ? res.getDimensionPixelSize(R.dimen.kq_line_label)
                                                  : Math.round(res.getDimension(R.dimen.kq_line_body) * factor);
        setTopMargin(views.time, Math.max(0, (firstLine - res.getDimensionPixelSize(R.dimen.kq_line_caption)) / 2));
        setTopMargin(views.mark, Math.max(0, (firstLine - res.getDimensionPixelSize(R.dimen.kq_size_mark)) / 2));

        final AirConversationModel.EntryStatus status = statusOf(m);
        showMark(views.mark, status, m.id, true);

        final boolean failed = status == AirConversationModel.EntryStatus.FAILED;
        if (views.retry != null)
        {
            views.retry.setVisibility(failed ? View.VISIBLE : View.GONE);
            views.retry.setOnClickListener(failed ? v -> AirChatPanel.retryEntry(v.getContext(), m) : null);
            views.retry.setClickable(failed);
        }
        if (views.meta != null)
        {
            views.meta.setVisibility(failed || m.edited ? View.VISIBLE : View.GONE);
        }

        if (views.container != null)
        {
            views.container.setContentDescription(spoken(context, fullAuthor, views.time.getText(), views.spokenQuote,
                                                         views.text.getText(), m.edited, status));
        }
        // The container speaks for the entry; the text is reached on its own only for its links.
        final CharSequence shown = views.text.getText();
        final boolean links = shown instanceof Spanned
                              && ((Spanned) shown).getSpans(0, shown.length(), ClickableSpan.class).length > 0;
        views.text.setImportantForAccessibility(links ? View.IMPORTANT_FOR_ACCESSIBILITY_YES
                                                      : View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /**
     * Our mark: a clock while it waits or leaves, one check sent, two delivered, red when it was refused.
     * KHANDAQ (0.2.57): two teal checks once the person's seen watermark covers one sent or delivered.
     */
    static AirConversationModel.EntryStatus statusOf(final Message m)
    {
        if (m == null || m.direction != 1 || HelperMessageEdit.isLocalCallLogRow(m)
            || FavoritesChatHelper.isFavoritesChat(m.tox_friendpubkey)
            || TRIFA_SYSTEM_MESSAGE_PEER_PUBKEY.equals(m.tox_friendpubkey))
        {
            return AirConversationModel.EntryStatus.NONE;
        }
        return AirConversationModel.EntryStatus.ofDirectText(false, m.read,
                                                             MessageDeliveryRetryHelper.isDirectFailed(m),
                                                             m.message_id, personOnline)
                                               .seenIf(HelperMessageSeen.coveredBySeen(m));
    }

    /** The word TalkBack says for a mark, empty for none. */
    static String statusWord(final Context context, final AirConversationModel.EntryStatus status)
    {
        switch (status)
        {
            case TRANSIT:
                return context.getString(R.string.air_delivery_transit);
            case SENDING:
                return context.getString(R.string.air_delivery_sending);
            case SENT:
                return context.getString(R.string.air_delivery_sent);
            case DELIVERED:
                return context.getString(R.string.air_delivery_delivered);
            case SEEN:
                return context.getString(R.string.air_delivery_seen);
            case FAILED:
                return context.getString(R.string.air_delivery_failed);
            case NONE:
            default:
                return "";
        }
    }

    /** Puts the mark of {@code status} on {@code mark}, as a group draws it; tint and opacity as on iOS. */
    static void setMarkIcon(final ImageView mark, final AirConversationModel.EntryStatus status)
    {
        setMarkIcon(mark, status, false);
    }

    /**
     * Puts the mark of {@code status} on {@code mark}. KHANDAQ (0.2.57): in a conversation with one person
     * ({@code direct}) sent and delivered take the muted ink of the time beside them, so that seen, in brand
     * teal, stands out; a group, which has no seen, keeps its marks as they were.
     */
    static void setMarkIcon(final ImageView mark, final AirConversationModel.EntryStatus status,
                            final boolean direct)
    {
        final Context context = mark.getContext();
        final int icon;
        final int tint;
        switch (status)
        {
            case TRANSIT:
                icon = R.drawable.kq_ic_clock;
                tint = R.color.kq_transit;
                break;
            case SENDING:
                icon = R.drawable.kq_ic_clock;
                tint = R.color.kq_ink_3;
                break;
            case SENT:
                icon = R.drawable.kq_ic_check;
                tint = direct ? R.color.kq_ink_3 : R.color.kq_ink;
                break;
            case DELIVERED:
                icon = R.drawable.kq_ic_check_double;
                tint = direct ? R.color.kq_ink_3 : R.color.kq_ink;
                break;
            case SEEN:
                icon = R.drawable.kq_ic_check_double;
                tint = R.color.khandaq_teal;
                break;
            case FAILED:
                icon = R.drawable.kq_ic_alert;
                tint = R.color.kq_danger;
                break;
            case NONE:
            default:
                mark.setImageDrawable(null);
                return;
        }
        mark.setImageResource(icon);
        mark.setImageTintList(android.content.res.ColorStateList.valueOf(AirUi.color(context, tint)));
    }

    static float markAlpha(final AirConversationModel.EntryStatus status)
    {
        return status == AirConversationModel.EntryStatus.SENDING ? SENDING_ALPHA : 1f;
    }

    /** What a mark showed for which message, so a change on the same entry cross-fades. */
    private static final class MarkState
    {
        final long messageId;
        final AirConversationModel.EntryStatus status;

        MarkState(final long messageId, final AirConversationModel.EntryStatus status)
        {
            this.messageId = messageId;
            this.status = status;
        }
    }

    private static void showMark(final ImageView mark, final AirConversationModel.EntryStatus status,
                                 final long messageId, final boolean direct)
    {
        final Object before = mark.getTag(R.id.air_entry_status);
        mark.setTag(R.id.air_entry_status, new MarkState(messageId, status));
        mark.animate().cancel();
        if (status == AirConversationModel.EntryStatus.NONE)
        {
            mark.setImageDrawable(null);
            mark.setAlpha(1f);
            mark.setVisibility(View.INVISIBLE);
            return;
        }
        final float alpha = markAlpha(status);
        mark.setVisibility(View.VISIBLE);
        final boolean change = before instanceof MarkState && ((MarkState) before).messageId == messageId
                               && ((MarkState) before).status != status
                               && ((MarkState) before).status != AirConversationModel.EntryStatus.NONE;
        if (!change || AirUi.reducedMotion() || !mark.isAttachedToWindow())
        {
            setMarkIcon(mark, status, direct);
            mark.setAlpha(alpha);
            return;
        }
        final long half = Math.max(1L, AirUi.duration(mark.getContext(), R.integer.kq_dur_state) / 2L);
        mark.animate().alpha(0f).setDuration(half).withEndAction(() -> {
            setMarkIcon(mark, status, direct);
            mark.animate().alpha(alpha).setDuration(half).start();
        }).start();
    }

    /** One sentence in reading order: who, when, what it answers, what, edited, and the mark of our own. */
    static String spoken(final Context context, final String author, final CharSequence time, final String quote,
                         final CharSequence text, final boolean edited, final AirConversationModel.EntryStatus status)
    {
        final StringBuilder sentence = new StringBuilder();
        append(sentence, author);
        append(sentence, time == null ? "" : time.toString());
        append(sentence, quote);
        append(sentence, text == null ? "" : text.toString());
        if (edited)
        {
            append(sentence, context.getString(R.string.message_edited_marker));
        }
        append(sentence, statusWord(context, status));
        return sentence.toString();
    }

    /**
     * What TalkBack says of the quote over a reply, "In reply to Amina, the quoted words", as
     * {@link ChatBubbleUiHelper#bind_reply_quote} shows it; "" for an entry that shows none. The container speaks
     * for the entry and TalkBack does not reach the quote under it, so a reply was heard as a message of its own
     * (QA live 0.2.61).
     */
    static String spokenQuote(final Context context, @Nullable final MessageReplyHelper.ReplyMeta reply)
    {
        if (reply == null || reply.previewText == null || reply.previewText.trim().isEmpty())
        {
            return "";
        }
        final String name = reply.senderName == null || reply.senderName.isEmpty()
                            ? context.getString(R.string.chat_reply_unknown_sender) : reply.senderName;
        return context.getString(R.string.chat_reply_header, name) + ", "
               + MessageReplyHelper.previewForDisplay(context, reply.previewText, 160);
    }

    private static void append(final StringBuilder sentence, final String part)
    {
        if (part == null || part.trim().isEmpty())
        {
            return;
        }
        if (sentence.length() > 0)
        {
            sentence.append(", ");
        }
        sentence.append(part);
    }

    // ---- a group entry (redesign, stage 3b) ----

    /**
     * What a group entry shows besides its text. The holder works it out: it has the peer lookups and
     * the rules on relayed history (GroupMessageListHolder_text_incoming_not_read).
     */
    static final class GroupEntry
    {
        /** In full: "You", the name with the founder's or a moderator's role, or "Author not confirmed". */
        String author = "";
        int authorColor;
        /** "Private message to you" or "Private message to Amina"; null for a message to the group. */
        String privateNote;
        boolean senderUnverified;
        boolean mentionsYou;
        boolean system;
        AirConversationModel.EntryStatus status = AirConversationModel.EntryStatus.NONE;
    }

    /**
     * After the holder has set the text, its mentions, links, location and quote: the look of a group
     * entry, the same row as in a conversation with one person. Authors are named in full over a run, in
     * their tone, with the founder's or a moderator's role; a message that mentions you has the selected
     * ground; a service message is a line in the second ink without an author.
     */
    static void bindGroupText(final View itemView, final int position, final GroupMessage m,
                              final GroupMentionHelper.ParsedGroupText parsed, final boolean locationBound,
                              final boolean selected, final GroupEntry entry)
    {
        if (itemView == null || m == null || entry == null)
        {
            return;
        }
        final Views views = views(itemView);
        if (views.text == null || views.time == null || views.mark == null)
        {
            return;
        }
        final Context context = itemView.getContext();
        final Resources res = context.getResources();
        final boolean outgoing = m.direction == 1;

        final float factor = textFactor();
        views.text.setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.kq_text_body) * factor);
        views.text.setLineSpacing(0f, 1f);
        TextViewCompat.setLineHeight(views.text, Math.round(res.getDimension(R.dimen.kq_line_body) * factor));
        ChatBubbleUiHelper.configure_message_emoji_size(views.text,
                                                        (parsed != null && parsed.reply == null && !locationBound)
                                                        ? parsed.bodyText : null, PREF__global_font_size, false);
        views.text.setTextColor(AirUi.color(context, entry.system ? R.color.kq_ink_2 : R.color.kq_ink));
        TextViewCompat.setCompoundDrawablesRelative(views.text, null, null, null, null);
        views.text.setCompoundDrawablePadding(0);

        bindTime(views, res, AirUi.localDigits(context, HelperGeneric.format_group_message_time(m, outgoing)));
        if (views.group != null)
        {
            styleQuote(views.group);
        }
        if (views.edited != null)
        {
            views.edited.setText(R.string.message_edited_marker);
            views.edited.setVisibility(m.edited ? View.VISIBLE : View.GONE);
        }
        if (views.meta != null)
        {
            views.meta.setVisibility(m.edited ? View.VISIBLE : View.GONE);
        }
        if (views.retry != null)
        {
            views.retry.setVisibility(View.GONE);
            views.retry.setOnClickListener(null);
            views.retry.setClickable(false);
        }
        views.spokenQuote = spokenQuote(context, parsed == null ? null : parsed.reply);
        bindGroupState(views, m, position, selected, entry);
    }

    /**
     * KHANDAQ (0.2.58): the author over a member's entry in a group opens the member's profile with a tap,
     * as the photo and the name over the old bubble did; {@code open} keeps the holder's rules. TalkBack
     * reaches the name as a button of its own, as it reaches a link in the text. A long press on it is the
     * entry's own (the message's actions), and the list takes a swipe to reply before the name sees it.
     * With {@code open} null the author is words only, as over every other entry: our own, a service line,
     * an author the group could not confirm. The rows of a conversation with one person never call this.
     */
    static void bindAuthorTap(@Nullable final TextView author, @Nullable final View row,
                              @Nullable final View.OnClickListener open)
    {
        if (author == null)
        {
            return;
        }
        final boolean opens = open != null;
        author.setOnClickListener(open);
        // Always taken, as the text's own forwarder does: a long press that the row declines must not
        // become a tap on the name when the finger lifts.
        author.setOnLongClickListener(opens && row != null ? v -> {
            row.performLongClick();
            return true;
        } : null);
        // Null listeners leave the flags as they were: a recycled row that opened a profile must not stay a button.
        author.setClickable(opens);
        author.setLongClickable(opens && row != null);
        author.setImportantForAccessibility(opens ? View.IMPORTANT_FOR_ACCESSIBILITY_YES
                                                  : View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        if (opens)
        {
            AirUi.markAsButton(author, author.getContext().getString(R.string.group_msg_author_hint));
        }
        else
        {
            author.setAccessibilityDelegate(null);
        }
    }

    // ---- the author's tile beside a group entry (tester 21.11, 8255) ----

    /**
     * From this system text size on, the entries of a group leave the column of the author's tile out so that their
     * text keeps the width, as iOS does at the accessibility sizes; the threshold the redesign's stacked layouts take
     * (AirDossier, AirFileEntry). The name over the first entry of a run still says who wrote it.
     */
    static final float TILE_COLUMN_FONT_SCALE = 1.3f;

    /** Where an entry stands to the column of its author's tile. */
    enum TileSlot
    {
        /** No column: our own entries, a service line, the conversation with one person, the large text sizes. */
        NONE,
        /** The column with the tile in it: the last entry of a run. */
        SHOWN,
        /** The column kept empty, so that the entries of a run keep one line: every other entry of the run. */
        EMPTY
    }

    /**
     * KHANDAQ (tester 21.11, 8255): the tile of the member who wrote a group entry, as Telegram has it. Every entry
     * of another member keeps a column for it between the time and the text, and the last entry of a run shows it,
     * level with the bottom of the entry, while the name stays over the first one. It is the member's tile of the
     * band and the dossier (initials in their tone), or the plain one of an author nobody confirmed. The holder
     * works it out, as it does the author.
     */
    static final class AuthorTile
    {
        /** The message it was worked out for: a recycled row never shows another message's tile. */
        final long messageId;
        /** The plain tile: neither initials nor a tone, and no tap. */
        final boolean plain;
        final String name;
        final int tone;
        /** What a tap does, the same as a tap on the name; null leaves the tile a picture. */
        @Nullable
        final View.OnClickListener open;

        AuthorTile(final long messageId, final boolean plain, @Nullable final String name, final int tone,
                   @Nullable final View.OnClickListener open)
        {
            this.messageId = messageId;
            this.plain = plain;
            this.name = name == null ? "" : name;
            this.tone = tone;
            this.open = open;
        }
    }

    /** The slot of an entry: without a tile no column, the tile on the end of a run, the column kept empty before it. */
    static TileSlot tileSlot(final boolean hasTile, final boolean endsRun, final float fontScale)
    {
        if (!hasTile || fontScale >= TILE_COLUMN_FONT_SCALE)
        {
            return TileSlot.NONE;
        }
        return endsRun ? TileSlot.SHOWN : TileSlot.EMPTY;
    }

    /**
     * Draws {@code model} in the tile of a group entry ({@code null} for an entry without one: ours, a service line)
     * and puts it in its slot ({@link #placeAuthorTile}). A tap on the tile opens the member, as the name does
     * ({@link #bindAuthorTap}), and a long press on it is the entry's (the message's actions). The plain tile, and
     * one without a tap, take no touch: it goes to the entry. TalkBack passes the tile by: the entry says who wrote
     * it, and the name over the first entry of the run is the member's button.
     */
    static void bindAuthorTile(@Nullable final AirTileView tile, @Nullable final View body, @Nullable final View row,
                               @Nullable final GroupMessage m, final int position, @Nullable final AuthorTile model)
    {
        if (tile == null || m == null)
        {
            return;
        }
        tile.setTag(R.id.air_entry_tile_model, model);
        if (model != null)
        {
            tile.bind(model.plain ? AirTileView.Kind.UNCONFIRMED : AirTileView.Kind.PERSON, model.plain ? "" : model.name,
                      model.tone);
        }
        final View.OnClickListener open = (model == null || model.plain) ? null : model.open;
        tile.setOnClickListener(open);
        tile.setOnLongClickListener(open != null && row != null ? v -> {
            row.performLongClick();
            return true;
        } : null);
        // Null listeners leave the flags as they were: a recycled tile that opened a member must not stay a button.
        tile.setClickable(open != null);
        tile.setLongClickable(open != null && row != null);
        placeAuthorTile(tile, body, m, position);
    }

    /**
     * Puts the tile of a group entry in its slot by the entry's place in its run, again whenever a neighbour comes or
     * goes and moves the end of the run. The empty column keeps its width and takes no height: only an entry that
     * shows the tile is at least as tall as the tile. A tile the holder worked out for another message (a recycled
     * row it has not bound again) stands for nobody here, so the entry has none.
     */
    static void placeAuthorTile(@Nullable final AirTileView tile, @Nullable final View body,
                                @Nullable final GroupMessage m, final int position)
    {
        if (tile == null || m == null)
        {
            return;
        }
        final Object tag = tile.getTag(R.id.air_entry_tile_model);
        final AuthorTile model = (tag instanceof AuthorTile && ((AuthorTile) tag).messageId == m.id) ? (AuthorTile) tag
                                                                                                       : null;
        final Context context = tile.getContext();
        final Resources res = context.getResources();
        final TileSlot slot = tileSlot(model != null, model != null && endsGroupRun(context, m, position),
                                       res.getConfiguration().fontScale);
        tile.setVisibility(slot == TileSlot.SHOWN ? View.VISIBLE : slot == TileSlot.EMPTY ? View.INVISIBLE : View.GONE);
        final ViewGroup.LayoutParams params = tile.getLayoutParams();
        final int height = slot == TileSlot.SHOWN ? res.getDimensionPixelSize(R.dimen.kq_size_tile_compact) : 0;
        if (params != null && params.height != height)
        {
            params.height = height;
            tile.setLayoutParams(params);
        }
        setMarginStart(body, res.getDimensionPixelSize(slot == TileSlot.NONE ? R.dimen.kq_space_10
                                                                             : R.dimen.kq_space_8));
    }

    /** The width the column of the tile takes from the content of an entry: the tile and the gap after it, or none. */
    static int authorTileRoom(@Nullable final View tile)
    {
        if (tile == null || tile.getVisibility() == View.GONE)
        {
            return 0;
        }
        final Resources res = tile.getResources();
        return res.getDimensionPixelSize(R.dimen.kq_size_tile_compact) + res.getDimensionPixelSize(R.dimen.kq_space_8);
    }

    private static void bindGroupState(final Views views, final GroupMessage m, final int position,
                                       final boolean selected, final GroupEntry entry)
    {
        final Context context = views.text.getContext();
        final Resources res = context.getResources();

        final String shownAuthor = (!entry.system && !TextUtils.isEmpty(entry.author)
                                    && startsGroupRun(context, m, position)) ? entry.author : null;
        if (views.author != null)
        {
            views.author.setText(shownAuthor);
            views.author.setTextColor(entry.authorColor);
            views.author.setVisibility(shownAuthor != null ? View.VISIBLE : View.GONE);
        }
        // KHANDAQ (26.11.2026): a private message stands out from the messages to the group: its note says who
        // sees it, in the private colour behind a lock, and the entry stands on the private ground.
        final boolean privateEntry = !TextUtils.isEmpty(entry.privateNote);
        if (views.note != null)
        {
            views.note.setText(entry.privateNote);
            views.note.setVisibility(privateEntry ? View.VISIBLE : View.GONE);
            bindPrivateNote(views.note, privateEntry);
        }
        if (views.unverified != null)
        {
            views.unverified.setText(entry.senderUnverified ? context.getString(R.string.group_msg_sender_unverified)
                                                            : null);
            views.unverified.setVisibility(entry.senderUnverified ? View.VISIBLE : View.GONE);
        }

        // The time and the mark sit on the first line: the author's, the private note's, or the text's.
        final float factor = textFactor();
        final int firstLine;
        if (shownAuthor != null)
        {
            firstLine = res.getDimensionPixelSize(R.dimen.kq_line_label);
        }
        else if (!TextUtils.isEmpty(entry.privateNote))
        {
            firstLine = res.getDimensionPixelSize(R.dimen.kq_line_caption);
        }
        else
        {
            firstLine = Math.round(res.getDimension(R.dimen.kq_line_body) * factor);
        }
        setTopMargin(views.time, Math.max(0, (firstLine - res.getDimensionPixelSize(R.dimen.kq_line_caption)) / 2));
        setTopMargin(views.mark, Math.max(0, (firstLine - res.getDimensionPixelSize(R.dimen.kq_size_mark)) / 2));
        showMark(views.mark, outgoingStatus(m, entry), m.id, false);

        if (views.container != null)
        {
            // Chosen for the message actions, or naming you: the selected ground, as on iOS. A private message has
            // its own ground, which a mention in it does not change: it is yours anyway.
            if (selected || (entry.mentionsYou && m.direction != 1 && !privateEntry))
            {
                views.container.setBackgroundResource(selectionPlate(views.container));
            }
            else if (privateEntry)
            {
                views.container.setBackgroundResource(R.drawable.air_bg_entry_private);
            }
            else
            {
                views.container.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            }
            final StringBuilder spokenText = new StringBuilder(
                    spoken(context, entry.author, views.time.getText(), views.spokenQuote, views.text.getText(),
                           m.edited, outgoingStatus(m, entry)));
            if (!TextUtils.isEmpty(entry.privateNote))
            {
                append(spokenText, entry.privateNote);
            }
            if (entry.senderUnverified)
            {
                append(spokenText, context.getString(R.string.group_msg_sender_unverified));
            }
            if (entry.mentionsYou && m.direction != 1)
            {
                append(spokenText, context.getString(R.string.air_group_mentions_you));
            }
            views.container.setContentDescription(spokenText.toString());
        }
        final CharSequence shown = views.text.getText();
        final boolean links = shown instanceof Spanned
                              && ((Spanned) shown).getSpans(0, shown.length(), ClickableSpan.class).length > 0;
        views.text.setImportantForAccessibility(links ? View.IMPORTANT_FOR_ACCESSIBILITY_YES
                                                      : View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /**
     * The note of a private message: the private colour and the lock before it, at the caption's size; any
     * other entry the row is recycled for gets the note's plain look back.
     */
    private static void bindPrivateNote(final TextView note, final boolean privateEntry)
    {
        final Context context = note.getContext();
        if (!privateEntry)
        {
            note.setTextColor(AirUi.color(context, R.color.kq_ink_2));
            note.setCompoundDrawablesRelative(null, null, null, null);
            return;
        }
        final int ink = AirUi.color(context, R.color.kq_private);
        note.setTextColor(ink);
        final Drawable lock = AppCompatResources.getDrawable(context, R.drawable.kq_ic_lock);
        if (lock != null)
        {
            final Drawable tinted = DrawableCompat.wrap(lock.mutate());
            DrawableCompat.setTint(tinted, ink);
            final int size = Math.round(note.getTextSize());
            tinted.setBounds(0, 0, size, size);
            note.setCompoundDrawablesRelative(tinted, null, null, null);
            note.setCompoundDrawablePadding(context.getResources().getDimensionPixelSize(R.dimen.kq_space_4));
        }
    }

    private static AirConversationModel.EntryStatus outgoingStatus(final GroupMessage m, final GroupEntry entry)
    {
        return (m.direction == 1 && !entry.system) ? entry.status : AirConversationModel.EntryStatus.NONE;
    }

    /**
     * Our group message's mark: the transit mark while it waits for the group, a clock while it leaves,
     * one check once it left, two once a member's device confirmed it. A private message in a group gets
     * no receipt and counts as sent once the send returned.
     */
    static AirConversationModel.EntryStatus groupStatus(final GroupMessage m, final boolean groupConnected)
    {
        if (m == null || m.direction != 1 || TRIFA_SYSTEM_MESSAGE_PEER_PUBKEY.equals(m.tox_group_peer_pubkey))
        {
            return AirConversationModel.EntryStatus.NONE;
        }
        switch (MessageStatusHelper.resolveGroupOutgoing(m))
        {
            case SENDING:
                return groupConnected ? AirConversationModel.EntryStatus.SENDING
                                      : AirConversationModel.EntryStatus.TRANSIT;
            case DELIVERED:
                return AirConversationModel.EntryStatus.DELIVERED;
            case SENT:
            default:
                return AirConversationModel.EntryStatus.SENT;
        }
    }

    /**
     * An entry opens a run unless the entry before it is a text by the same member on the same day,
     * under five minutes earlier. Rows the list hides (connection noise) do not break a run, and a text the
     * photo before it shows as its caption is that file's, as in a conversation with one person (startsRun).
     */
    private static boolean startsGroupRun(final Context context, final GroupMessage m, final int position)
    {
        final GroupMessageListFragment fragment = MainActivity.group_message_list_fragment;
        if (position <= 0 || fragment == null || fragment.adapter == null)
        {
            return true;
        }
        final GroupMessagelistAdapter adapter = fragment.adapter;
        final GroupMessage current = adapter.get_item(position);
        if (current == null || current.id != m.id)
        {
            // Bound off the list (a stale position): name the author rather than hide it wrongly.
            return true;
        }
        for (int i = position - 1, looked = 0; i >= 0 && looked < GROUP_RUN_LOOKBACK; i--, looked++)
        {
            final GroupMessage previous = adapter.get_item(i);
            if (previous == null)
            {
                return true;
            }
            if (!GroupMessageLayoutHelper.isRenderableMessage(null, previous))
            {
                continue;
            }
            return AirConversationModel.startsRun(groupRunKey(m, false),
                                                  groupRunKey(previous, isMergedCaption(context, adapter, i)),
                                                  TimeZone.getDefault());
        }
        return true;
    }

    /**
     * KHANDAQ (tester 21.11, 8255): an entry closes its run, and shows its author's tile, unless the entry after it
     * goes on with the run (AirConversationModel.endsRun, the rule of {@link #startsGroupRun} seen from the other
     * side). Rows the list hides are passed over, as there.
     */
    private static boolean endsGroupRun(final Context context, final GroupMessage m, final int position)
    {
        final GroupMessageListFragment fragment = MainActivity.group_message_list_fragment;
        if (position < 0 || fragment == null || fragment.adapter == null)
        {
            return true;
        }
        final GroupMessagelistAdapter adapter = fragment.adapter;
        final GroupMessage current = adapter.get_item(position);
        if (current == null || current.id != m.id)
        {
            // Bound off the list (a stale position): show the tile rather than hide it wrongly.
            return true;
        }
        final AirConversationModel.RunKey entry = groupRunKey(m, isMergedCaption(context, adapter, position));
        final int count = adapter.getItemCount();
        for (int i = position + 1, looked = 0; i < count && looked < GROUP_RUN_LOOKBACK; i++, looked++)
        {
            final GroupMessage next = adapter.get_item(i);
            if (next == null)
            {
                return true;
            }
            if (!GroupMessageLayoutHelper.isRenderableMessage(null, next))
            {
                continue;
            }
            return AirConversationModel.endsRun(entry, groupRunKey(next, false), TimeZone.getDefault());
        }
        return true;
    }

    /**
     * A text that the row of the photo or the video before it shows as its caption (ChatCaptionHelper): it belongs
     * to that file's entry, and its own row is folded away.
     */
    private static boolean isMergedCaption(final Context context, final GroupMessagelistAdapter adapter,
                                           final int position)
    {
        if (context == null || position < 1)
        {
            return false;
        }
        final GroupMessage file = adapter.get_item(position - 1);
        final GroupMessage text = adapter.get_item(position);
        return file != null && text != null
               && ChatCaptionHelper.is_merged_group_caption(context, Arrays.asList(file, text), 1);
    }

    /** Hidden rows skipped while looking for the entry before or after; past this many the author, or the tile, shows. */
    private static final int GROUP_RUN_LOOKBACK = 20;

    /**
     * Who wrote a group entry, when, and whether a run goes on from it. {@code caption}: a text the photo before it
     * shows as its caption, which stands in that file's entry, and a file breaks the run after it.
     */
    static AirConversationModel.RunKey groupRunKey(final GroupMessage m, final boolean caption)
    {
        final boolean system = TRIFA_SYSTEM_MESSAGE_PEER_PUBKEY.equals(m.tox_group_peer_pubkey);
        final boolean isText = m.TRIFA_MESSAGE_TYPE == TRIFA_MSG_TYPE_TEXT.value && !system && !caption;
        final String author = m.direction == 1 ? ""
                              : (m.tox_group_peer_pubkey == null ? "" : m.tox_group_peer_pubkey.toUpperCase(Locale.ROOT));
        // A private message stands apart from the group's run: its line says to whom it went.
        final String key = m.private_message != 0 ? "private:" + author : author;
        return new AirConversationModel.RunKey(m.direction == 1 && m.private_message == 0 ? "" : key,
                                               GroupMessageLayoutHelper.effectiveSortTimestampMs(m), isText);
    }

    // ---- runs of one author ----

    private static boolean startsRun(final Context context, final Message m, final int position)
    {
        final MessageListFragment fragment = MainActivity.message_list_fragment;
        if (position <= 0 || fragment == null || fragment.data_values == null)
        {
            return true;
        }
        final List<Message> items = fragment.data_values;
        if (position >= items.size() || items.get(position) == null || items.get(position).id != m.id)
        {
            // Bound off the list (a stale position): name the author rather than hide it wrongly.
            return true;
        }
        final Message previous = items.get(position - 1);
        if (previous == null)
        {
            return true;
        }
        final boolean previousIsText = previous.TRIFA_MESSAGE_TYPE == TRIFA_MSG_TYPE_TEXT.value
                                       && !HelperMessageEdit.isLocalCallLogRow(previous)
                                       && !TRIFA_SYSTEM_MESSAGE_PEER_PUBKEY.equals(previous.tox_friendpubkey)
                                       && !ChatCaptionHelper.is_merged_caption(context, items, position - 1);
        return AirConversationModel.startsRun(runKey(m, true), runKey(previous, previousIsText), TimeZone.getDefault());
    }

    /** Whether a file entry opens a run, by the rule of a text (AirFileEntry draws its author with it). */
    static boolean startsDirectRun(final Context context, final Message m, final int position)
    {
        return startsRun(context, m, position);
    }

    /** Whether a file entry of a group opens a run, by the rule of a text. */
    static boolean startsGroupRunFor(final Context context, final GroupMessage m, final int position)
    {
        return startsGroupRun(context, m, position);
    }

    private static AirConversationModel.RunKey runKey(final Message m, final boolean isText)
    {
        final boolean outgoing = m.direction == 1;
        long ms = outgoing ? (m.sent_timestamp > 0 ? m.sent_timestamp : m.rcvd_timestamp)
                           : (m.rcvd_timestamp > 0 ? m.rcvd_timestamp : m.sent_timestamp);
        return new AirConversationModel.RunKey(outgoing ? "" : "peer", ms, isText);
    }

    // ---- sizes ----

    /** The text size the user picked in the app's settings, relative to the default one. */
    private static float textFactor()
    {
        try
        {
            return MESSAGE_TEXT_SIZE[PREF__global_font_size] / (float) MESSAGE_TEXT_SIZE[PREF_GLOBAL_FONT_SIZE_DEFAULT];
        }
        catch (Exception e)
        {
            return 1f;
        }
    }

    private static final Object WIDTH_LOCK = new Object();
    private static float widthTextSize = -1f;
    private static String widthLocale = null;
    private static int widthPx = 0;

    /** The clock of a message, in the time column and the tray, in the digits of the app's language. */
    static String timeText(final Context context, final Message m, final boolean outgoing)
    {
        return AirUi.localDigits(context, HelperGeneric.format_chat_message_time(m, outgoing));
    }

    /**
     * The 38dp time column, widened with the system text size (and for wider digits) so "23:59" stays
     * whole, at most twice as wide. Measured with the paint of {@code time}, a caption in tabular figures.
     */
    static int timeColumnWidth(final TextView time)
    {
        final Resources res = time.getResources();
        // Kept by the size the paint really has, not by the system's font scale: the conversation takes a new
        // scale without being recreated, so its live rows still measure at the old size while the scale is new,
        // and a width kept by the scale was then too narrow for the next screen's larger text.
        final float textSize = time.getTextSize();
        // The old clock formatter keeps the language the app started in; the digits follow the app's (timeText).
        final String locale = Locale.getDefault() + "|" + AirUi.locale(time.getContext());
        synchronized (WIDTH_LOCK)
        {
            if (widthPx > 0 && textSize == widthTextSize && locale.equals(widthLocale))
            {
                return widthPx;
            }
        }
        final Calendar late = Calendar.getInstance();
        late.set(Calendar.HOUR_OF_DAY, 23);
        late.set(Calendar.MINUTE, 59);
        float widest = 0f;
        for (final String sample : new String[]{"00:00", "23:59", HelperGeneric.short_time_format(late.getTimeInMillis())})
        {
            if (sample != null)
            {
                widest = Math.max(widest, time.getPaint().measureText(AirUi.localDigits(time.getContext(), sample)));
            }
        }
        final int base = res.getDimensionPixelSize(R.dimen.kq_size_time_column_phone);
        final int width = Math.min(2 * base, Math.max(base, (int) Math.ceil(widest)));
        synchronized (WIDTH_LOCK)
        {
            widthTextSize = textSize;
            widthLocale = locale;
            widthPx = width;
        }
        return width;
    }

    private static void setTopMargin(final View view, final int top)
    {
        if (view == null || !(view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams))
        {
            return;
        }
        final ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
        if (params.topMargin != top)
        {
            params.topMargin = top;
            view.setLayoutParams(params);
        }
    }

    /** The margin on the side the text starts from, so that it follows a right-to-left layout. */
    private static void setMarginStart(@Nullable final View view, final int start)
    {
        if (view == null || !(view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams))
        {
            return;
        }
        final ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
        if (params.getMarginStart() != start)
        {
            params.setMarginStart(start);
            view.setLayoutParams(params);
        }
    }

    // ---- the quote and the reactions ----

    /** The quoted message over a reply: a sunken plate in the second ink, no stripe at the side. */
    private static void styleQuote(final View group)
    {
        final View block = group.findViewById(R.id.chat_reply_quote_block);
        if (block == null || block.getVisibility() != View.VISIBLE)
        {
            return;
        }
        final Context context = group.getContext();
        final Resources res = context.getResources();
        final View bar = group.findViewById(R.id.chat_reply_quote_bar);
        if (bar != null)
        {
            bar.setVisibility(View.GONE);
        }
        block.setBackgroundResource(R.drawable.air_bg_quote);
        final int h = res.getDimensionPixelSize(R.dimen.kq_space_8);
        final int v = res.getDimensionPixelSize(R.dimen.kq_space_4);
        block.setPaddingRelative(h, v, h, v);
        if (block.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)
        {
            final ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) block.getLayoutParams();
            params.bottomMargin = res.getDimensionPixelSize(R.dimen.kq_space_4);
            block.setLayoutParams(params);
        }
        final int ink2 = AirUi.color(context, R.color.kq_ink_2);
        final TextView name = group.findViewById(R.id.chat_reply_quote_name);
        if (name != null)
        {
            TextViewCompat.setTextAppearance(name, R.style.TextAppearance_KQ_Air_CaptionStrong);
            // The layout asks for bold, which AppCompat keeps across a text appearance: the semibold face
            // is already the weight, so the synthetic bold on top of it goes.
            final Typeface face = name.getTypeface();
            name.setTypeface(face == null ? null : Typeface.create(face, Typeface.NORMAL), Typeface.NORMAL);
            TextViewCompat.setLineHeight(name, res.getDimensionPixelSize(R.dimen.kq_line_caption));
            name.setTextColor(ink2);
        }
        final TextView quoted = group.findViewById(R.id.chat_reply_quote_text);
        if (quoted != null)
        {
            TextViewCompat.setTextAppearance(quoted, R.style.TextAppearance_KQ_Caption);
            TextViewCompat.setLineHeight(quoted, res.getDimensionPixelSize(R.dimen.kq_line_caption));
            quoted.setTextColor(ink2);
        }
    }

    /** A reaction under an entry: a raised chip with a line, ink, the count in tabular figures. */
    static void styleReactionChip(final TextView chip, final boolean own)
    {
        final Context context = chip.getContext();
        final Resources res = context.getResources();
        TextViewCompat.setTextAppearance(chip, R.style.TextAppearance_KQ_Caption);
        TextViewCompat.setLineHeight(chip, res.getDimensionPixelSize(R.dimen.kq_line_caption));
        chip.setFontFeatureSettings("tnum");
        chip.setTextColor(AirUi.color(context, R.color.kq_ink));
        chip.setBackgroundResource(own ? R.drawable.air_bg_reaction_own : R.drawable.air_bg_reaction);
        final int h = res.getDimensionPixelSize(R.dimen.kq_space_8);
        final int v = Math.round(3 * res.getDisplayMetrics().density);
        chip.setPaddingRelative(h, v, h, v);
        chip.setMinHeight(res.getDimensionPixelSize(R.dimen.kq_size_control_small));
        chip.setGravity(android.view.Gravity.CENTER_VERTICAL);
        if (chip.getLayoutParams() instanceof LinearLayout.LayoutParams)
        {
            ((LinearLayout.LayoutParams) chip.getLayoutParams()).gravity = android.view.Gravity.CENTER_VERTICAL;
        }
    }

    // ---- arrival ----

    private static final Object ARRIVAL_LOCK = new Object();
    /** Message id to the uptime its rise starts at. */
    private static final Map<Long, Long> ARRIVALS = new HashMap<>();
    private static long lastArrivalStart = 0L;
    /** An arrival nobody draws within this long (scrolled away, another chat) is forgotten. */
    private static final long ARRIVAL_KEEP_MS = 3000L;
    private static Interpolator easeOut = null;

    /**
     * The entry of {@code id} rises into the log when it is next drawn: a message that just came in, or
     * one of ours that just went out or left the tray. Several at once (the tray emptying) rise one by
     * one, {@code kq_dur_stagger} apart. Nothing moves under reduced motion.
     */
    static void markArrival(final Context context, final long id)
    {
        if (id <= 0L || context == null || AirUi.reducedMotion())
        {
            return;
        }
        final long stagger = context.getResources().getInteger(R.integer.kq_dur_stagger);
        final long now = SystemClock.uptimeMillis();
        synchronized (ARRIVAL_LOCK)
        {
            final Iterator<Map.Entry<Long, Long>> it = ARRIVALS.entrySet().iterator();
            while (it.hasNext())
            {
                if (now - it.next().getValue() > ARRIVAL_KEEP_MS)
                {
                    it.remove();
                }
            }
            if (ARRIVALS.containsKey(id))
            {
                return;
            }
            final long start = Math.max(now, lastArrivalStart + stagger);
            ARRIVALS.put(id, start);
            lastArrivalStart = start;
        }
    }

    /**
     * Rises the entry of {@code id} 8dp into place with a fade, or puts it in place. Bound again while it
     * rises (the list rebinds every row on an insert), it carries on from where it is.
     */
    static void applyArrival(final View row, final long id)
    {
        if (row == null)
        {
            return;
        }
        final Long start;
        synchronized (ARRIVAL_LOCK)
        {
            start = ARRIVALS.get(id);
        }
        final Object previous = row.getTag(R.id.air_entry_arrival);
        final long now = SystemClock.uptimeMillis();
        final Context context = row.getContext();
        final long duration = context.getResources().getInteger(R.integer.kq_dur_state);
        if (start == null || AirUi.reducedMotion() || now - start >= duration)
        {
            if (previous != null)
            {
                row.animate().cancel();
                row.setTag(R.id.air_entry_arrival, null);
            }
            row.setAlpha(1f);
            row.setTranslationY(0f);
            return;
        }
        if (previous instanceof Long && (Long) previous == id && row.getAlpha() < 1f)
        {
            // Already rising on this row: leave the running animation alone.
            return;
        }
        if (easeOut == null)
        {
            easeOut = AnimationUtils.loadInterpolator(context, R.interpolator.kq_ease_out);
        }
        row.animate().cancel();
        row.setTag(R.id.air_entry_arrival, id);
        final float rise = context.getResources().getDimension(R.dimen.air_reveal_rise);
        final long elapsed = now - start;
        final float done = elapsed <= 0L ? 0f : easeOut.getInterpolation(elapsed / (float) duration);
        row.setAlpha(done);
        row.setTranslationY(rise * (1f - done));
        row.animate().alpha(1f).translationY(0f).setInterpolator(easeOut)
           .setStartDelay(elapsed < 0L ? -elapsed : 0L)
           .setDuration(elapsed < 0L ? duration : duration - elapsed)
           .withEndAction(() -> row.setTag(R.id.air_entry_arrival, null))
           .start();
    }
}
