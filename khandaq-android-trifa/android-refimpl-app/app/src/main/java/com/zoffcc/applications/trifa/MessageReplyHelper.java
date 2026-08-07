package com.zoffcc.applications.trifa;

import android.content.Context;
import android.text.TextUtils;

import androidx.annotation.Nullable;

/** Encode/decode Telegram-style reply metadata embedded in plain message text. */
final class MessageReplyHelper
{
    static final String HEADER_PREFIX = "[KQ|";
    static final String HEADER_SUFFIX = "]";
    static final String FOOTER = "[KQ/end]";

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
            final int footerStart = rawText.indexOf(FOOTER);
            if (headerEnd > 0 && footerStart > headerEnd)
            {
                final ReplyMeta meta = parseHeader(rawText.substring(HEADER_PREFIX.length(), headerEnd));
                if (meta != null)
                {
                    final int previewStart = headerEnd + HEADER_SUFFIX.length();
                    if (previewStart < footerStart)
                    {
                        meta.previewText = rawText.substring(previewStart, footerStart).trim();
                    }
                    parsed.reply = meta;
                    final int bodyStart = footerStart + FOOTER.length();
                    parsed.bodyText = bodyStart < rawText.length()
                            ? trimLeading(rawText.substring(bodyStart)) : "";
                    return parsed;
                }
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

        parsed.bodyText = rawText;
        return parsed;
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
