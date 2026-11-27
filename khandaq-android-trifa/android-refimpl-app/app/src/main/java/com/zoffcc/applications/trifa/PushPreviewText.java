package com.zoffcc.applications.trifa;

/**
 * KHANDAQ (0.2.64): what a notification made from a push preview says (docs/PUSH_PREVIEWS.md, "Recipient"): the
 * title and the body for the kind of event, the fallback for an old preview, and the plain words when message
 * previews are turned off. Pure Java; the words come from {@link Labels}, which the app reads from its resources.
 */
final class PushPreviewText
{
    /** A preview older than this (by its ts) is shown as the fallback text. */
    static final long MAX_AGE_SECONDS = 48L * 60L * 60L;

    /** The words, in the language of the device. */
    interface Labels
    {
        /** "Khandaq". */
        String appTitle();

        /** "New message". */
        String newMessage();

        /** "New reaction". */
        String newReaction();

        /** The voice message label, its emoji first. */
        String voice();

        /** The video label, its emoji first. */
        String video();

        /** The photo label, its emoji first. */
        String photo();

        /** The file label, its emoji first. */
        String file();

        /** The file label with the file name in place of the word. */
        String fileNamed(String name);

        /** Reacted e to "text". */
        String reactionToText(String emoji, String text);

        String reactionToVoice(String emoji);

        String reactionToVideo(String emoji);

        String reactionToPhoto(String emoji);

        String reactionToFile(String emoji);

        /** Reacted e to your message. */
        String reactionToMessage(String emoji);

        /** KHANDAQ (owner, 28.11): a place sent as a message, its emoji first. */
        String location();

        /** Reacted e to your location. */
        String reactionToLocation(String emoji);
    }

    /** Title and body of the notification. */
    static final class Shown
    {
        final String title;
        final String body;
        /** True when the body is a text of the sender, which the app shows as it shows message text. */
        final boolean messageText;

        Shown(final String title, final String body, final boolean messageText)
        {
            this.title = title;
            this.body = body;
            this.messageText = messageText;
        }
    }

    private PushPreviewText()
    {
    }

    /**
     * @param localSenderName the name this device knows the sender by, or null when it is not at hand
     * @param showContent     the "show message preview" setting
     */
    static Shown select(final PushPreviewPayload p, final String localSenderName, final boolean showContent,
                        final long nowSeconds, final Labels labels)
    {
        if (nowSeconds - p.ts > MAX_AGE_SECONDS)
        {
            return new Shown(labels.appTitle(), labels.newMessage(), false);
        }
        final String sender = firstNonEmpty(p.senderName, localSenderName);
        final String senderTitle = sender != null ? sender : labels.appTitle();
        final boolean group = p.isGroupEvent();
        final String title = (group && p.groupName != null) ? p.groupName : senderTitle;
        final boolean reaction = PushPreviewPayload.KIND_REACTION.equals(p.kind);

        if (!showContent)
        {
            return new Shown(title, reaction ? labels.newReaction() : labels.newMessage(), false);
        }

        final String body;
        boolean messageText = false;
        switch (p.kind)
        {
            case PushPreviewPayload.KIND_TEXT:
                if (isLocation(p.body))
                {
                    // KHANDAQ (owner, 28.11): a place is named, not shown as its wire text "khandaq-location:...".
                    body = labels.location();
                }
                else if (p.body != null)
                {
                    body = p.body;
                    messageText = true;
                }
                else
                {
                    body = labels.newMessage();
                }
                break;
            case PushPreviewPayload.KIND_VOICE:
                body = labels.voice();
                break;
            case PushPreviewPayload.KIND_VIDEO:
                body = labels.video();
                break;
            case PushPreviewPayload.KIND_PHOTO:
                body = labels.photo();
                break;
            case PushPreviewPayload.KIND_FILE:
                body = p.body != null ? labels.fileNamed(oneLine(p.body)) : labels.file();
                break;
            default:
                body = reactionBody(p, labels);
                break;
        }
        if (group && sender != null)
        {
            // A group event names its group in the title and who it was in the body (n: + the body).
            return new Shown(title, sender + ": " + (messageText ? oneLine(body) : body), false);
        }
        return new Shown(title, body, messageText);
    }

    /** The body of a reaction by what is known of the reacted message (the table of the spec). */
    static String reactionBody(final PushPreviewPayload p, final Labels labels)
    {
        if (p.emoji == null)
        {
            return labels.newReaction();
        }
        final String kind = p.reactedKind == null ? "" : p.reactedKind;
        switch (kind)
        {
            case PushPreviewPayload.KIND_TEXT:
                if (isLocation(p.body))
                {
                    return labels.reactionToLocation(p.emoji);
                }
                return p.body != null ? labels.reactionToText(p.emoji, oneLine(p.body))
                                      : labels.reactionToMessage(p.emoji);
            case PushPreviewPayload.KIND_VOICE:
                return labels.reactionToVoice(p.emoji);
            case PushPreviewPayload.KIND_VIDEO:
                return labels.reactionToVideo(p.emoji);
            case PushPreviewPayload.KIND_PHOTO:
                return labels.reactionToPhoto(p.emoji);
            case PushPreviewPayload.KIND_FILE:
                return labels.reactionToFile(p.emoji);
            default:
                return labels.reactionToMessage(p.emoji);
        }
    }

    /**
     * KHANDAQ (owner, 28.11): whether a text is a place sent as a message, the wire text of every client
     * "khandaq-location:LAT,LON" (HelperLocationMessage.encode, iOS LocationMessage.payload).
     */
    static boolean isLocation(final String text)
    {
        return text != null && text.trim().toLowerCase(java.util.Locale.ROOT).startsWith("khandaq-location:");
    }

    /** Line breaks as spaces, ends trimmed: a quoted text and a name sit on one line. */
    static String oneLine(final String text)
    {
        return text.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static String firstNonEmpty(final String first, final String second)
    {
        if (first != null && !first.trim().isEmpty())
        {
            return first.trim();
        }
        if (second != null && !second.trim().isEmpty())
        {
            return second.trim();
        }
        return null;
    }
}
