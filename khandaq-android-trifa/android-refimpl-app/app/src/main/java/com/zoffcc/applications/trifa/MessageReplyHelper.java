package com.zoffcc.applications.trifa;

import android.content.Context;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import org.khandaq.messenger.R;

/** Encode/decode Telegram-style reply metadata embedded in plain message text. */
final class MessageReplyHelper
{
    static final String HEADER_PREFIX = "[KQ|";
    static final String HEADER_SUFFIX = "]";
    static final String FOOTER = "[KQ/end]";
    /** The mention block of older iOS builds; like the current one, it is not unknown markup. */
    private static final String LEGACY_MENTION_PREFIX = "[KQ|m]";

    static final class ReplyMeta
    {
        long localMessageId;
        /** Last 8 chars of sender pubkey (uppercase), may be empty for legacy DM. */
        String senderPubkeyTail;
        long sortTimestampMs;
        String senderName;
        String previewText;
        /** Legacy swipe-quote without structured metadata. */
        boolean legacyQuote;
    }

    static final class ParsedMessage
    {
        @Nullable
        public ReplyMeta reply;
        public String bodyText;
    }

    private MessageReplyHelper()
    {
    }

    static ParsedMessage parse(final String rawText)
    {
        final ParsedMessage parsed = new ParsedMessage();
        if (rawText == null || rawText.isEmpty())
        {
            parsed.bodyText = "";
            return parsed;
        }

        if (rawText.startsWith(HEADER_PREFIX))
        {
            final int headerEnd = rawText.indexOf(HEADER_SUFFIX);
            final ReplyMeta meta = headerEnd > 0
                    ? parseHeader(rawText.substring(HEADER_PREFIX.length(), headerEnd)) : null;
            if (meta != null)
            {
                final int previewStart = headerEnd + HEADER_SUFFIX.length();
                final int footerStart = rawText.indexOf(FOOTER);
                if (footerStart >= previewStart)
                {
                    // The normal form: the preview stands between the header and the [KQ/end] footer, the
                    // body follows the footer.
                    if (previewStart < footerStart)
                    {
                        meta.previewText = rawText.substring(previewStart, footerStart).trim();
                    }
                    parsed.bodyText = bodyStart(rawText, footerStart + FOOTER.length());
                }
                else
                {
                    // KHANDAQ (#146, as iOS): the footer is missing (a peer build that leaves it out, or a
                    // message cut short). parseHeader has read the message ID and the time as numbers, so
                    // this is a reply, and its marker is not shown as the body: the first line after the
                    // header is the quoted preview, the rest is the body.
                    int start = previewStart;
                    while (start < rawText.length() && isLineBreak(rawText.charAt(start)))
                    {
                        start++;
                    }
                    int lineEnd = start;
                    while (lineEnd < rawText.length() && !isLineBreak(rawText.charAt(lineEnd)))
                    {
                        lineEnd++;
                    }
                    meta.previewText = rawText.substring(start, lineEnd).trim();
                    parsed.bodyText = bodyStart(rawText, lineEnd + 1);
                }
                parsed.reply = meta;
                return parsed;
            }
        }

        if (rawText.startsWith(TRIFAGlobals.TEXT_QUOTE_STRING_1))
        {
            final int end = rawText.indexOf(TRIFAGlobals.TEXT_QUOTE_STRING_2);
            if (end > 0)
            {
                final ReplyMeta meta = new ReplyMeta();
                meta.legacyQuote = true;
                meta.previewText = rawText.substring(TRIFAGlobals.TEXT_QUOTE_STRING_1.length(), end).trim();
                parsed.reply = meta;
                int bodyStart = end + TRIFAGlobals.TEXT_QUOTE_STRING_2.length();
                while (bodyStart < rawText.length() && rawText.charAt(bodyStart) == '\n')
                {
                    bodyStart++;
                }
                parsed.bodyText = rawText.substring(bodyStart);
                return parsed;
            }
        }

        parsed.bodyText = readableBody(rawText);
        return parsed;
    }

    /**
     * Khandaq markup this build cannot read (a newer reply form, a block another client added) is not
     * shown as a service string (redesign stage 3, item 10; design/DESIGN.md, "Unknown markup, on every
     * client"). A message that opens with it keeps the text after the markup; one that is nothing but
     * markup says so in words. The rows, the chat list and the notifications all read the body here.
     */
    static String readableBody(final String rawText)
    {
        final String body = textAfterUnknownMarkup(rawText);
        if (body == null)
        {
            return rawText;
        }
        return body.isEmpty() ? unknownFormatSentence() : body;
    }

    /**
     * The text after markup this build cannot read, taken as iOS takes it: after the [KQ/end] footer when
     * there is one, otherwise after the first "]". Empty when nothing follows the markup; null when the
     * text does not open with such markup. Mention blocks are not unknown: the group parser reads them.
     */
    @Nullable
    static String textAfterUnknownMarkup(@Nullable final String rawText)
    {
        if (rawText == null || !rawText.startsWith(HEADER_PREFIX)
            || rawText.startsWith(GroupMentionHelper.MENTION_BLOCK_PREFIX)
            || rawText.startsWith(LEGACY_MENTION_PREFIX))
        {
            return null;
        }
        final int footerStart = rawText.indexOf(FOOTER);
        final int headerEnd = rawText.indexOf(HEADER_SUFFIX);
        final String rest;
        if (footerStart >= 0)
        {
            rest = rawText.substring(footerStart + FOOTER.length());
        }
        else if (headerEnd >= 0)
        {
            rest = rawText.substring(headerEnd + HEADER_SUFFIX.length());
        }
        else
        {
            rest = "";
        }
        return withoutControlCharacters(rest).trim();
    }

    /** The sentence for a message that is nothing but markup this build cannot read, in the app's language. */
    private static String unknownFormatSentence()
    {
        final Context context = MainActivity.context_s;
        return context == null ? "" : context.getString(R.string.air_message_unknown_format);
    }

    /** Control characters out, line breaks kept, as iOS cleans a body before showing it. */
    private static String withoutControlCharacters(final String value)
    {
        final StringBuilder kept = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++)
        {
            final char c = value.charAt(i);
            if (!Character.isISOControl(c) || c == '\n' || c == '\r')
            {
                kept.append(c);
            }
        }
        return kept.toString();
    }

    static String encode(final ReplyMeta meta, final String bodyText)
    {
        if (meta == null)
        {
            return bodyText == null ? "" : bodyText;
        }

        final String preview = truncatePreview(meta.previewText);
        final String safeName = sanitizeField(meta.senderName);
        final String tail = meta.senderPubkeyTail == null ? "" : meta.senderPubkeyTail.toUpperCase();
        final String header = HEADER_PREFIX + meta.localMessageId + "|" + tail + "|" + meta.sortTimestampMs + "|"
                + safeName + HEADER_SUFFIX + "\n" + preview + "\n" + FOOTER + "\n";
        return header + (bodyText == null ? "" : bodyText);
    }

    static String previewForDisplay(final Context context, final String text, final int maxLen)
    {
        final String single = TextUtils.isEmpty(text) ? "" : text.replace('\n', ' ').replace('\r', ' ').trim();
        if (single.length() <= maxLen)
        {
            return single;
        }
        return single.substring(0, Math.max(0, maxLen - 1)).trim() + "…";
    }

    static String pubkeyTail(@Nullable final String pubkey)
    {
        if (pubkey == null || pubkey.length() < 8)
        {
            return pubkey == null ? "" : pubkey.toUpperCase();
        }
        return pubkey.substring(pubkey.length() - 8).toUpperCase();
    }

    @Nullable
    private static ReplyMeta parseHeader(final String headerBody)
    {
        try
        {
            final String[] parts = headerBody.split("\\|", 4);
            if (parts.length < 4)
            {
                return null;
            }
            final ReplyMeta meta = new ReplyMeta();
            meta.localMessageId = Long.parseLong(parts[0].trim());
            meta.senderPubkeyTail = parts[1].trim();
            meta.sortTimestampMs = Long.parseLong(parts[2].trim());
            meta.senderName = parts[3].trim();
            return meta;
        }
        catch (Exception ignored)
        {
            return null;
        }
    }

    private static String truncatePreview(final String text)
    {
        // KHANDAQ (audit #18): strip the footer/header sentinels so a quoted message whose body literally
        // contains "[KQ/end]" can't make parse() split the preview at the wrong offset (garbled render).
        final String cleaned = text == null ? "" : text.replace(FOOTER, "").replace(HEADER_PREFIX, "");
        return previewForDisplay(null, cleaned, 200);
    }

    private static String sanitizeField(final String value)
    {
        if (value == null)
        {
            return "";
        }
        // KHANDAQ (audit #18): also strip the reply sentinels so a sender name containing ']' (HEADER_SUFFIX)
        // or a '[KQ..' token can't truncate the header early on parse (indexOf(']')) or forge a quote.
        return value.replace(FOOTER, "").replace(HEADER_PREFIX, "").replace(HEADER_SUFFIX, "")
                .replace('|', ' ').replace('\n', ' ').replace('\r', ' ').trim();
    }

    /** The body from index start on, without the white space it opens with; empty past the end. */
    private static String bodyStart(final String rawText, final int start)
    {
        return start < rawText.length() ? trimLeading(rawText.substring(start)) : "";
    }

    private static boolean isLineBreak(final char c)
    {
        return c == '\n' || c == '\r';
    }

    private static String trimLeading(final String value)
    {
        if (value == null || value.isEmpty())
        {
            return "";
        }
        int start = 0;
        while (start < value.length() && Character.isWhitespace(value.charAt(start)))
        {
            start++;
        }
        return value.substring(start);
    }
}
