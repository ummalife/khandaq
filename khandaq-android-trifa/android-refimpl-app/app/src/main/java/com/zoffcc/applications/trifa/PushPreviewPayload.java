package com.zoffcc.applications.trifa;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * KHANDAQ (0.2.64): the plaintext of a push preview (docs/PUSH_PREVIEWS.md, "Preview plaintext"): what an event
 * says about itself, built by the sender and read by the recipient. A compact UTF-8 JSON object of at most 900
 * bytes, fields in the order of the spec, so that one input always gives the same bytes. Pure Java: org.json is a
 * stub in the JVM unit tests, and the tests compare the bytes with the vectors of the spec.
 */
final class PushPreviewPayload
{
    static final String KIND_TEXT = "text";
    static final String KIND_VOICE = "voice";
    static final String KIND_VIDEO = "video";
    static final String KIND_PHOTO = "photo";
    static final String KIND_FILE = "file";
    static final String KIND_REACTION = "reaction";

    static final int VERSION = 1;
    static final int MAX_PLAINTEXT_BYTES = 900;
    /** b of a text, in user-perceived characters. */
    static final int TEXT_CHARACTERS = 140;
    /** b of a reaction: the reacted text. */
    static final int REACTED_TEXT_CHARACTERS = 80;
    /** n and g. */
    static final int NAME_CHARACTERS = 48;

    /** The voice note naming every client uses. */
    private static final String VOICE_SUFFIX = ".file.m4a";
    private static final String[] VIDEO_EXTENSIONS = {"mp4", "mov", "m4v", "webm", "mkv", "3gp", "avi"};
    private static final String[] PHOTO_EXTENSIONS = {"jpg", "jpeg", "png", "gif", "heic", "heif", "webp"};

    final String kind;
    String body;
    String reactedKind;
    String emoji;
    String senderName;
    String groupName;
    String groupId;
    long ts;
    String eventKey;

    private PushPreviewPayload(final String kind)
    {
        this.kind = kind;
    }

    // ---- what the sender says ----

    /** A text: b is its first 140 characters, h is "t:" + its msgV3 hash when it has one. */
    static PushPreviewPayload text(final String text, final String msgV3Hash, final String senderName,
                                   final long tsSeconds)
    {
        final PushPreviewPayload p = new PushPreviewPayload(KIND_TEXT);
        p.body = nonEmpty(firstCharacters(text, TEXT_CHARACTERS));
        p.eventKey = textEventKey(msgV3Hash);
        p.senderName = nonEmpty(firstCharacters(senderName, NAME_CHARACTERS));
        p.ts = tsSeconds;
        return p;
    }

    /**
     * A file of the given kind (fileKind): b is its name for a plain file only, h is "f:" + its Tox file id when it
     * has one.
     */
    static PushPreviewPayload file(final String kind, final String fileName, final String fileIdHex,
                                   final String senderName, final long tsSeconds)
    {
        final PushPreviewPayload p = new PushPreviewPayload(isFileKind(kind) ? kind : KIND_FILE);
        p.body = KIND_FILE.equals(p.kind) ? nonEmpty(fileName) : null;
        p.eventKey = fileEventKey(fileIdHex);
        p.senderName = nonEmpty(firstCharacters(senderName, NAME_CHARACTERS));
        p.ts = tsSeconds;
        return p;
    }

    /**
     * A reaction: e, rk (the kind of the reacted message), b (the reacted text, first 80 characters, for a text only)
     * and h = "r:" + the anchor as on the wire + ":" + the emoji.
     */
    static PushPreviewPayload reaction(final String emoji, final String reactedKind, final String reactedText,
                                       final String anchorHex, final String senderName, final long tsSeconds)
    {
        final PushPreviewPayload p = new PushPreviewPayload(KIND_REACTION);
        p.emoji = nonEmpty(emoji);
        p.reactedKind = isMessageKind(reactedKind) ? reactedKind : null;
        p.body = KIND_TEXT.equals(p.reactedKind)
                 ? nonEmpty(firstCharacters(reactedText, REACTED_TEXT_CHARACTERS)) : null;
        p.eventKey = reactionEventKey(anchorHex, emoji);
        p.senderName = nonEmpty(firstCharacters(senderName, NAME_CHARACTERS));
        p.ts = tsSeconds;
        return p;
    }

    /**
     * KHANDAQ (0.2.69, group message wakes): a text to a group, b as for a text; h names it by toxcore's message id
     * and the sender's key in the group (groupTextEventKey), since a group text has no msgV3 hash. g and gid come
     * with inGroup.
     */
    static PushPreviewPayload groupText(final String text, final long messageId, final String senderPeerPubkey,
                                        final String senderName, final long tsSeconds)
    {
        final PushPreviewPayload p = text(text, null, senderName, tsSeconds);
        p.eventKey = groupTextEventKey(messageId, senderPeerPubkey);
        return p;
    }

    /** KHANDAQ (0.2.69, group message wakes): a file to a group, k and b as for a file, h its group file key. */
    static PushPreviewPayload groupFile(final String kind, final String fileName, final String fileHashHex,
                                        final String senderName, final long tsSeconds)
    {
        final PushPreviewPayload p = file(kind, fileName, null, senderName, tsSeconds);
        p.eventKey = groupFileEventKey(fileHashHex);
        return p;
    }

    /** The same event in a group: g (its name as the sender sees it) and gid (its chat id). */
    PushPreviewPayload inGroup(final String name, final String chatIdHex)
    {
        groupName = nonEmpty(firstCharacters(name, NAME_CHARACTERS));
        groupId = (chatIdHex != null && PushPreviewCrypto.unhex(chatIdHex, PushPreviewCrypto.CHAT_ID_BYTES) != null)
                  ? chatIdHex.toUpperCase(Locale.ROOT) : null;
        return this;
    }

    boolean isGroupEvent()
    {
        return groupId != null || groupName != null;
    }

    // ---- event keys ----

    static String textEventKey(final String msgV3Hash)
    {
        return isHex64(msgV3Hash) ? "t:" + msgV3Hash.toUpperCase(Locale.ROOT) : null;
    }

    static String fileEventKey(final String fileIdHex)
    {
        return isHex64(fileIdHex) ? "f:" + fileIdHex.toUpperCase(Locale.ROOT) : null;
    }

    /**
     * KHANDAQ (0.2.69, group message wakes): "gt:" + toxcore's 32-bit message id (8 uppercase hex, big-endian) + ":" +
     * the sender's key in that group. The id alone repeats across members; with the key it names one message.
     */
    static String groupTextEventKey(final long messageId, final String senderPeerPubkey)
    {
        if (messageId < 0L || messageId > 0xFFFFFFFFL || !isHex64(senderPeerPubkey))
        {
            return null;
        }
        return String.format(Locale.ROOT, "gt:%08X:%s", messageId, senderPeerPubkey.toUpperCase(Locale.ROOT));
    }

    /** KHANDAQ (0.2.69, group message wakes): "gf:" + the file's 32-byte hash, the anchor a reaction to it takes. */
    static String groupFileEventKey(final String fileHashHex)
    {
        return isHex64(fileHashHex) ? "gf:" + fileHashHex.toUpperCase(Locale.ROOT) : null;
    }

    static String reactionEventKey(final String anchorHex, final String emoji)
    {
        if (anchorHex == null || anchorHex.isEmpty() || emoji == null || emoji.isEmpty())
        {
            return null;
        }
        for (int i = 0; i < anchorHex.length(); i++)
        {
            if (Character.digit(anchorHex.charAt(i), 16) < 0)
            {
                return null;
            }
        }
        return "r:" + anchorHex.toUpperCase(Locale.ROOT) + ":" + emoji;
    }

    // ---- kinds ----

    /**
     * The kind of a file from its name (sender side, docs/PUSH_PREVIEWS.md "Kinds from a file"): voice for the
     * voice note naming, video for a video extension or a video type the platform reports, photo for a photo
     * extension, otherwise file.
     */
    static String fileKind(final String fileName, final String mimeType)
    {
        final String name = fileName == null ? "" : fileName.trim().toLowerCase(Locale.ROOT);
        if (name.endsWith(VOICE_SUFFIX))
        {
            return KIND_VOICE;
        }
        final int dot = name.lastIndexOf('.');
        final String extension = dot >= 0 ? name.substring(dot + 1) : "";
        if (contains(VIDEO_EXTENSIONS, extension)
            || (mimeType != null && mimeType.toLowerCase(Locale.ROOT).startsWith("video/")))
        {
            return KIND_VIDEO;
        }
        if (contains(PHOTO_EXTENSIONS, extension))
        {
            return KIND_PHOTO;
        }
        return KIND_FILE;
    }

    static boolean isFileKind(final String kind)
    {
        return KIND_VOICE.equals(kind) || KIND_VIDEO.equals(kind) || KIND_PHOTO.equals(kind) || KIND_FILE.equals(kind);
    }

    /** A kind a reacted message can have. */
    static boolean isMessageKind(final String kind)
    {
        return KIND_TEXT.equals(kind) || isFileKind(kind);
    }

    // ---- truncation ----

    /** The first maxCharacters user-perceived characters of text, nothing added; null stays null. */
    static String firstCharacters(final String text, final int maxCharacters)
    {
        if (text == null)
        {
            return null;
        }
        return text.substring(0, MessageSplitter.wholeCharactersEnd(text, maxCharacters));
    }

    // ---- JSON ----

    /**
     * The compact JSON, at most 900 bytes: the text is shortened first, then the group name, then the sender's name,
     * a whole character at a time, and a field with nothing left goes. Null when it still does not fit.
     */
    byte[] toJson()
    {
        byte[] json = serialize();
        if (json.length <= MAX_PLAINTEXT_BYTES)
        {
            return json;
        }
        body = shortenToFit(Field.BODY);
        groupName = shortenToFit(Field.GROUP_NAME);
        senderName = shortenToFit(Field.SENDER_NAME);
        json = serialize();
        return json.length <= MAX_PLAINTEXT_BYTES ? json : null;
    }

    private enum Field
    {
        BODY, GROUP_NAME, SENDER_NAME
    }

    /** The longest whole-character prefix of the field with which the JSON fits, or null when none does. */
    private String shortenToFit(final Field field)
    {
        final String value = get(field);
        if (value == null || serialize().length <= MAX_PLAINTEXT_BYTES)
        {
            return value;
        }
        // The JSON grows with the prefix, so the longest that fits is searched by halves: the most characters known
        // to fit (lo) and the most there can be (hi; a character is at least one char, and the whole value does not
        // fit).
        int lo = 0;
        int hi = value.length() - 1;
        while (lo < hi)
        {
            final int mid = lo + (hi - lo + 1) / 2;
            set(field, value.substring(0, MessageSplitter.wholeCharactersEnd(value, mid)));
            if (serialize().length <= MAX_PLAINTEXT_BYTES)
            {
                lo = mid;
            }
            else
            {
                hi = mid - 1;
            }
        }
        final String kept = lo > 0 ? value.substring(0, MessageSplitter.wholeCharactersEnd(value, lo)) : null;
        set(field, kept);
        return kept;
    }

    private String get(final Field field)
    {
        switch (field)
        {
            case BODY:
                return body;
            case GROUP_NAME:
                return groupName;
            default:
                return senderName;
        }
    }

    private void set(final Field field, final String value)
    {
        switch (field)
        {
            case BODY:
                body = value;
                break;
            case GROUP_NAME:
                groupName = value;
                break;
            default:
                senderName = value;
                break;
        }
    }

    private byte[] serialize()
    {
        final StringBuilder json = new StringBuilder(256);
        json.append("{\"v\":").append(VERSION);
        appendString(json, "k", kind);
        appendString(json, "b", body);
        appendString(json, "rk", reactedKind);
        appendString(json, "e", emoji);
        appendString(json, "n", senderName);
        appendString(json, "g", groupName);
        appendString(json, "gid", groupId);
        json.append(",\"ts\":").append(ts);
        appendString(json, "h", eventKey);
        json.append('}');
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void appendString(final StringBuilder json, final String name, final String value)
    {
        if (value == null)
        {
            return;
        }
        json.append(",\"").append(name).append("\":");
        quote(json, value);
    }

    /**
     * A JSON string as Python writes it with ensure_ascii=false: quote, backslash and the control characters
     * escaped, everything else as it is. A lone surrogate, which UTF-8 cannot carry, becomes U+FFFD.
     */
    static void quote(final StringBuilder out, final String value)
    {
        out.append('"');
        for (int i = 0; i < value.length(); i++)
        {
            final char c = value.charAt(i);
            switch (c)
            {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                case '\b':
                    out.append("\\b");
                    break;
                case '\f':
                    out.append("\\f");
                    break;
                default:
                    if (c < 0x20)
                    {
                        out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    }
                    else if (Character.isHighSurrogate(c) && i + 1 < value.length()
                             && Character.isLowSurrogate(value.charAt(i + 1)))
                    {
                        out.append(c).append(value.charAt(i + 1));
                        i++;
                    }
                    else if (Character.isSurrogate(c))
                    {
                        out.append('\uFFFD');
                    }
                    else
                    {
                        out.append(c);
                    }
                    break;
            }
        }
        out.append('"');
    }

    /**
     * The preview in this plaintext, or null when it is not a version 1 preview of a known kind. Fields this
     * client does not know, and fields of the wrong type, are ignored.
     */
    static PushPreviewPayload parse(final byte[] utf8)
    {
        if (utf8 == null)
        {
            return null;
        }
        final Map<String, Object> fields;
        try
        {
            fields = new JsonObjectReader(new String(utf8, StandardCharsets.UTF_8)).readObject();
        }
        catch (IllegalArgumentException e)
        {
            return null;
        }
        final Object version = fields.get("v");
        final Object kind = fields.get("k");
        if (!(version instanceof Long) || (Long) version != VERSION || !(kind instanceof String))
        {
            return null;
        }
        final String k = (String) kind;
        if (!KIND_REACTION.equals(k) && !isMessageKind(k))
        {
            return null;
        }
        final PushPreviewPayload p = new PushPreviewPayload(k);
        p.body = string(fields, "b");
        p.reactedKind = KIND_REACTION.equals(k) ? string(fields, "rk") : null;
        p.emoji = KIND_REACTION.equals(k) ? string(fields, "e") : null;
        p.senderName = string(fields, "n");
        p.groupName = string(fields, "g");
        final String gid = string(fields, "gid");
        p.groupId = (gid != null && PushPreviewCrypto.unhex(gid, PushPreviewCrypto.CHAT_ID_BYTES) != null)
                    ? gid.toUpperCase(Locale.ROOT) : null;
        final Object ts = fields.get("ts");
        p.ts = (ts instanceof Long) ? (Long) ts : 0L;
        p.eventKey = string(fields, "h");
        return p;
    }

    private static String string(final Map<String, Object> fields, final String name)
    {
        final Object value = fields.get(name);
        return (value instanceof String) ? nonEmpty((String) value) : null;
    }

    private static String nonEmpty(final String value)
    {
        return (value == null || value.isEmpty()) ? null : value;
    }

    private static boolean isHex64(final String hex)
    {
        return PushPreviewCrypto.unhex(hex, 32) != null;
    }

    private static boolean contains(final String[] values, final String value)
    {
        for (final String candidate : values)
        {
            if (candidate.equals(value))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Reads one JSON object: its string and integer members are kept, any other value is read and dropped. Strict
     * about the syntax, since the input authenticated and a malformed one is a bug of its sender.
     */
    private static final class JsonObjectReader
    {
        private static final int MAX_DEPTH = 16;
        private final String text;
        private int pos;

        JsonObjectReader(final String text)
        {
            this.text = text;
        }

        Map<String, Object> readObject()
        {
            skipSpace();
            final Map<String, Object> fields = new HashMap<>();
            expect('{');
            skipSpace();
            if (peek() == '}')
            {
                pos++;
            }
            else
            {
                while (true)
                {
                    skipSpace();
                    final String name = readString();
                    skipSpace();
                    expect(':');
                    fields.put(name, readValue(1));
                    skipSpace();
                    final char c = next();
                    if (c == '}')
                    {
                        break;
                    }
                    if (c != ',')
                    {
                        throw new IllegalArgumentException("expected , or }");
                    }
                }
            }
            skipSpace();
            if (pos != text.length())
            {
                throw new IllegalArgumentException("trailing data");
            }
            return fields;
        }

        /** A string or an integer as itself, anything else read and returned as null. */
        private Object readValue(final int depth)
        {
            if (depth > MAX_DEPTH)
            {
                throw new IllegalArgumentException("too deep");
            }
            skipSpace();
            final char c = peek();
            if (c == '"')
            {
                return readString();
            }
            if (c == '{' || c == '[')
            {
                skipContainer(depth);
                return null;
            }
            if (c == '-' || (c >= '0' && c <= '9'))
            {
                return readNumber();
            }
            if (text.startsWith("true", pos))
            {
                pos += 4;
                return null;
            }
            if (text.startsWith("false", pos))
            {
                pos += 5;
                return null;
            }
            if (text.startsWith("null", pos))
            {
                pos += 4;
                return null;
            }
            throw new IllegalArgumentException("unexpected value");
        }

        private void skipContainer(final int depth)
        {
            final char open = next();
            final char close = (open == '{') ? '}' : ']';
            skipSpace();
            if (peek() == close)
            {
                pos++;
                return;
            }
            while (true)
            {
                if (open == '{')
                {
                    skipSpace();
                    readString();
                    skipSpace();
                    expect(':');
                }
                readValue(depth + 1);
                skipSpace();
                final char c = next();
                if (c == close)
                {
                    return;
                }
                if (c != ',')
                {
                    throw new IllegalArgumentException("expected , or " + close);
                }
            }
        }

        /** An integer as a Long; a number with a fraction or an exponent, or one out of range, as null. */
        private Object readNumber()
        {
            final int start = pos;
            if (peek() == '-')
            {
                pos++;
            }
            boolean integral = true;
            boolean digits = false;
            while (pos < text.length())
            {
                final char c = text.charAt(pos);
                if (c >= '0' && c <= '9')
                {
                    digits = true;
                }
                else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-')
                {
                    integral = false;
                }
                else
                {
                    break;
                }
                pos++;
            }
            if (!digits)
            {
                throw new IllegalArgumentException("bad number");
            }
            if (!integral)
            {
                return null;
            }
            try
            {
                return Long.parseLong(text.substring(start, pos));
            }
            catch (NumberFormatException e)
            {
                return null;
            }
        }

        private String readString()
        {
            expect('"');
            final StringBuilder out = new StringBuilder();
            while (true)
            {
                final char c = next();
                if (c == '"')
                {
                    return out.toString();
                }
                if (c < 0x20)
                {
                    throw new IllegalArgumentException("control character in a string");
                }
                if (c != '\\')
                {
                    out.append(c);
                    continue;
                }
                final char escaped = next();
                switch (escaped)
                {
                    case '"':
                    case '\\':
                    case '/':
                        out.append(escaped);
                        break;
                    case 'b':
                        out.append('\b');
                        break;
                    case 'f':
                        out.append('\f');
                        break;
                    case 'n':
                        out.append('\n');
                        break;
                    case 'r':
                        out.append('\r');
                        break;
                    case 't':
                        out.append('\t');
                        break;
                    case 'u':
                        if (pos + 4 > text.length())
                        {
                            throw new IllegalArgumentException("short unicode escape");
                        }
                        int code = 0;
                        for (int i = 0; i < 4; i++)
                        {
                            final int digit = Character.digit(text.charAt(pos + i), 16);
                            if (digit < 0)
                            {
                                throw new IllegalArgumentException("bad unicode escape");
                            }
                            code = (code << 4) | digit;
                        }
                        pos += 4;
                        out.append((char) code);
                        break;
                    default:
                        throw new IllegalArgumentException("bad escape");
                }
            }
        }

        private void expect(final char wanted)
        {
            if (next() != wanted)
            {
                throw new IllegalArgumentException("expected " + wanted);
            }
        }

        private char next()
        {
            if (pos >= text.length())
            {
                throw new IllegalArgumentException("unexpected end");
            }
            return text.charAt(pos++);
        }

        private char peek()
        {
            if (pos >= text.length())
            {
                throw new IllegalArgumentException("unexpected end");
            }
            return text.charAt(pos);
        }

        private void skipSpace()
        {
            while (pos < text.length())
            {
                final char c = text.charAt(pos);
                if (c != ' ' && c != '\t' && c != '\n' && c != '\r')
                {
                    break;
                }
                pos++;
            }
        }
    }
}
