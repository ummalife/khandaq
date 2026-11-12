package com.zoffcc.applications.trifa;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * KHANDAQ (redesign, E11): what "New contact" knows about an ID before anything is sent, as
 * AirNewContactModel on iOS (design/DESIGN.md, "New contact and requests on the phone"): the cleaned
 * ID, the real check, the three parts of the ruler, the verdict and whether "Send request" may go.
 * A Tox ID is the 64-character public key, 8 characters of antispam (nospam) and a 4-character check.
 */
final class AirNewContactModel
{
    static final int LENGTH = 76;
    static final int KEY_LENGTH = 64;
    static final int ANTISPAM_LENGTH = 8;
    static final int CHECK_LENGTH = 4;
    /** The command line reads a hex run this long, with a digit in it, as the start of an ID. */
    static final int COMMAND_MINIMUM = 12;
    /** toxcore's limit for the message of a request, in bytes (TOX_MAX_FRIEND_REQUEST_LENGTH). */
    static final int MAX_MESSAGE_BYTES = ToxVars.TOX_MAX_FRIEND_REQUEST_LENGTH;

    private static final String[] PREFIXES = {"TOX://", "KHANDAQ://", "TOX:", "KHANDAQ:", "TOX-ID:", "TOXID:"};
    private static final String INVISIBLE = "​‌‍⁠﻿­";

    /** Answers about a key the screen asks the database or the block list. */
    interface KeyQuery
    {
        boolean test(String publicKey);
    }

    private AirNewContactModel()
    {
    }

    // ---- the ID ----

    /**
     * The hex of an ID in any usual shape: spaces, line breaks, invisible characters, a "tox:" prefix,
     * lower case, or a whole sentence around it. Upper case, at most 76 characters.
     */
    static String clean(final String raw)
    {
        if (raw == null)
        {
            return "";
        }
        final String upper = raw.toUpperCase(Locale.ROOT);
        final StringBuilder kept = new StringBuilder(upper.length());
        for (int i = 0; i < upper.length(); i++)
        {
            final char c = upper.charAt(i);
            if (Character.isWhitespace(c) || Character.isSpaceChar(c) || INVISIBLE.indexOf(c) >= 0)
            {
                continue;
            }
            kept.append(c);
        }
        String text = withoutPrefix(kept.toString());
        // A whole ID somewhere in a sentence ("my ID: ..."): take that run, not the hex letters of the words.
        final String run = longestHexRun(text);
        if (run.length() >= LENGTH)
        {
            return run.substring(0, LENGTH);
        }
        final StringBuilder hex = new StringBuilder();
        for (int i = 0; i < text.length() && hex.length() < LENGTH; i++)
        {
            if (isHex(text.charAt(i)))
            {
                hex.append(text.charAt(i));
            }
        }
        return hex.toString();
    }

    /** Groups of four, the way the ID is read aloud and compared. */
    static String grouped(final String hex)
    {
        final StringBuilder out = new StringBuilder(hex.length() + hex.length() / 4);
        for (int i = 0; i < hex.length(); i++)
        {
            if (i > 0 && i % 4 == 0)
            {
                out.append(' ');
            }
            out.append(hex.charAt(i));
        }
        return out.toString();
    }

    /** toxcore's check: the two bytes are the XOR of the even and of the odd bytes of key and antispam. */
    static boolean checkMatches(final String hex)
    {
        if (hex == null || hex.length() != LENGTH)
        {
            return false;
        }
        final int[] bytes = new int[LENGTH / 2];
        for (int i = 0; i < bytes.length; i++)
        {
            final int high = Character.digit(hex.charAt(2 * i), 16);
            final int low = Character.digit(hex.charAt(2 * i + 1), 16);
            if (high < 0 || low < 0)
            {
                return false;
            }
            bytes[i] = (high << 4) | low;
        }
        final int[] check = {0, 0};
        for (int i = 0; i < bytes.length - 2; i++)
        {
            check[i % 2] ^= bytes[i];
        }
        return check[0] == bytes[bytes.length - 2] && check[1] == bytes[bytes.length - 1];
    }

    /**
     * What the command line holds when it reads as an ID: 12 to 76 hex characters with at least one digit,
     * spaces, line breaks and a "tox:" prefix aside. Null for names and sentences.
     */
    static String commandCandidate(final String raw)
    {
        if (raw == null)
        {
            return null;
        }
        final String upper = raw.toUpperCase(Locale.ROOT);
        final StringBuilder kept = new StringBuilder(upper.length());
        for (int i = 0; i < upper.length(); i++)
        {
            final char c = upper.charAt(i);
            if (!Character.isWhitespace(c) && !Character.isSpaceChar(c))
            {
                kept.append(c);
            }
        }
        final String text = withoutPrefix(kept.toString());
        if (text.length() < COMMAND_MINIMUM || text.length() > LENGTH)
        {
            return null;
        }
        boolean digit = false;
        for (int i = 0; i < text.length(); i++)
        {
            final char c = text.charAt(i);
            if (!isHex(c))
            {
                return null;
            }
            digit |= c >= '0' && c <= '9';
        }
        return digit ? text : null;
    }

    /** The bytes the message takes in a request, spaces at its ends aside. */
    static int messageBytes(final String message)
    {
        return message == null ? 0 : message.trim().getBytes(StandardCharsets.UTF_8).length;
    }

    /** What goes with the request: the typed message, or the greeting when nothing was typed. */
    static String requestMessage(final String typed, final String greeting)
    {
        final String trimmed = typed == null ? "" : typed.trim();
        return trimmed.isEmpty() ? greeting : trimmed;
    }

    private static String withoutPrefix(final String text)
    {
        for (String prefix : PREFIXES)
        {
            if (text.startsWith(prefix))
            {
                return text.substring(prefix.length());
            }
        }
        return text;
    }

    private static String longestHexRun(final String text)
    {
        int bestStart = 0;
        int bestLength = 0;
        int start = 0;
        for (int i = 0; i <= text.length(); i++)
        {
            if (i < text.length() && isHex(text.charAt(i)))
            {
                continue;
            }
            if (i - start > bestLength)
            {
                bestStart = start;
                bestLength = i - start;
            }
            start = i + 1;
        }
        return text.substring(bestStart, bestStart + bestLength);
    }

    private static boolean isHex(final char c)
    {
        return (c >= '0' && c <= '9') || (c >= 'A' && c <= 'F');
    }

    // ---- the verdict ----

    enum RulerState
    {
        FILLING, GOOD, BAD
    }

    /** The verdict under the ID field, in the order it is decided. */
    static final class Verdict
    {
        enum Kind
        {
            EMPTY,
            /** Characters still missing. */
            PARTIAL,
            BAD_CHECK,
            OWN,
            /** Already in contacts (also one whose request is still on its way): "Open chat" instead. */
            CONTACT,
            /** This person already sent you a request: it waits in Requests. */
            THEY_ASKED,
            /** The key is on this device's block list: sending the request lifts the block. */
            BLOCKED,
            OK
        }

        final Kind kind;
        /** For PARTIAL: how many characters are missing. */
        final int left;
        /** For CONTACT, BLOCKED and OK: the public key of the ID. */
        final String publicKey;
        /** For BLOCKED and OK: the whole ID "Send request" sends to. */
        final String address;

        private Verdict(final Kind kind, final int left, final String publicKey, final String address)
        {
            this.kind = kind;
            this.left = left;
            this.publicKey = publicKey;
            this.address = address;
        }

        /** The ID "Send request" sends to, or null while it may not go. */
        String address()
        {
            return kind == Kind.OK || kind == Kind.BLOCKED ? address : null;
        }

        /** The ruler's check part turns green or red only once the ID is whole. */
        RulerState checkState()
        {
            switch (kind)
            {
                case EMPTY:
                case PARTIAL:
                    return RulerState.FILLING;
                case BAD_CHECK:
                    return RulerState.BAD;
                default:
                    return RulerState.GOOD;
            }
        }

        static Verdict of(final String hex, final String ownAddress, final KeyQuery isContact,
                          final KeyQuery hasRequest, final KeyQuery isBlocked)
        {
            if (hex == null || hex.isEmpty())
            {
                return new Verdict(Kind.EMPTY, 0, null, null);
            }
            if (hex.length() < LENGTH)
            {
                return new Verdict(Kind.PARTIAL, LENGTH - hex.length(), null, null);
            }
            if (!checkMatches(hex))
            {
                return new Verdict(Kind.BAD_CHECK, 0, null, null);
            }
            final String key = hex.substring(0, KEY_LENGTH);
            final String own = clean(ownAddress);
            if (own.length() >= KEY_LENGTH && key.equals(own.substring(0, KEY_LENGTH)))
            {
                return new Verdict(Kind.OWN, 0, null, null);
            }
            if (isContact.test(key))
            {
                return new Verdict(Kind.CONTACT, 0, key, null);
            }
            if (hasRequest.test(key))
            {
                return new Verdict(Kind.THEY_ASKED, 0, key, null);
            }
            if (isBlocked.test(key))
            {
                return new Verdict(Kind.BLOCKED, 0, key, hex);
            }
            return new Verdict(Kind.OK, 0, key, hex);
        }
    }

    // ---- the ruler ----

    /** One part of the ruler: how much of it is typed. */
    static final class RulerPart
    {
        final int filled;
        final int size;

        RulerPart(final int filled, final int size)
        {
            this.filled = filled;
            this.size = size;
        }

        /** Key, antispam and check for this many characters typed. */
        static RulerPart[] parts(final int count)
        {
            final int[][] bounds = {{0, KEY_LENGTH}, {KEY_LENGTH, KEY_LENGTH + ANTISPAM_LENGTH},
                                    {KEY_LENGTH + ANTISPAM_LENGTH, LENGTH}};
            final RulerPart[] parts = new RulerPart[bounds.length];
            for (int i = 0; i < bounds.length; i++)
            {
                final int from = bounds[i][0];
                final int to = bounds[i][1];
                parts[i] = new RulerPart(Math.max(0, Math.min(count, to) - from), to - from);
            }
            return parts;
        }
    }

    // ---- the command line ----

    /** What the "New contact with this ID" row says about the start of an ID on the command line. */
    enum CommandNote
    {
        LEFT, BAD, OWN, CONTACT, FINE
    }

    static CommandNote commandNote(final String hex, final String ownAddress, final KeyQuery isContact)
    {
        switch (Verdict.of(hex, ownAddress, isContact, key -> false, key -> false).kind)
        {
            case EMPTY:
            case PARTIAL:
                return CommandNote.LEFT;
            case BAD_CHECK:
                return CommandNote.BAD;
            case OWN:
                return CommandNote.OWN;
            case CONTACT:
                return CommandNote.CONTACT;
            default:
                return CommandNote.FINE;
        }
    }

    // ---- Paste ID on the command line (E3) ----

    /** Where pasted words part: spaces of any kind, punctuation and quotes around an ID in a sentence. */
    private static final String PASTE_SEPARATORS = "[\\s\\p{Z},.;!?()\\[\\]{}<>\"'«»“”]+";

    /**
     * Reads a group link ("khandaq:group:" or "khandaq://group/" and the key) to its 64-character key, or
     * returns null: ShareActivity.parseKhandaqGroupId, the parser of the group dossier's QR.
     */
    interface GroupLinkReader
    {
        String groupKey(String link);
    }

    /**
     * What "Paste ID" puts on the command line from the clipboard's text, which is read only on that tap: a
     * group link as it is written; the text itself when it reads as an ID or its start (a whole ID, a group
     * key, 12 hex characters or more with a digit, spaces and a "tox:" prefix aside); or a whole ID or a
     * 64-character key standing among other words. Null when the text holds none of them: nothing is pasted.
     */
    static String pastedCommand(final CharSequence clip, final GroupLinkReader links)
    {
        if (clip == null)
        {
            return null;
        }
        // Invisible formatting characters (a zero-width space between groups of four) are not part of anything.
        final String text = clip.toString().replaceAll("\\p{Cf}", "").trim();
        if (text.isEmpty())
        {
            return null;
        }
        final String[] words = text.split(PASTE_SEPARATORS);
        if (links != null)
        {
            for (final String word : words)
            {
                if (!word.isEmpty() && links.groupKey(word) != null)
                {
                    return word;
                }
            }
        }
        final String whole = commandCandidate(text);
        if (whole != null)
        {
            return whole;
        }
        for (final String word : words)
        {
            final String hex = withoutPrefix(word.toUpperCase(Locale.ROOT));
            if ((hex.length() == LENGTH || hex.length() == KEY_LENGTH) && allHex(hex))
            {
                return hex;
            }
        }
        return null;
    }

    private static boolean allHex(final String text)
    {
        for (int i = 0; i < text.length(); i++)
        {
            if (!isHex(text.charAt(i)))
            {
                return false;
            }
        }
        return true;
    }
}
