package com.zoffcc.applications.trifa;

import android.content.Context;
import android.content.Intent;

/**
 * KHANDAQ (testers' ideas, 0.2.67): a tox: link, tox:ID or tox://ID in any case, opens New contact with the ID
 * filled in, where the field checks it as it checks a pasted one (a typo or a cut ID says so there). Before, the
 * link only started the app. A 64-character key is the key of a group: it goes to Join group, as the share
 * screen always meant it to.
 * <p>
 * A link that comes while the profile is not open (a cold start) waits for the password or the PIN: the main
 * screen opens New contact with it once it is up and nothing is locked ({@link #openPending}).
 */
final class ToxLink
{
    enum Kind
    {
        /** An ID for New contact, whole or not: the screen says what is wrong with one that is not. */
        CONTACT,
        /** A group key, 64 characters. */
        GROUP,
        /** Not a tox: link, or nothing in it that reads as an ID. */
        NONE
    }

    /** What a link holds: its kind and the hex of the ID or the key, upper case. */
    static final class Parsed
    {
        final Kind kind;
        final String hex;

        Parsed(final Kind kind, final String hex)
        {
            this.kind = kind;
            this.hex = hex;
        }

        /** A whole ID whose check matches: New contact can send the request at once. */
        boolean whole()
        {
            return kind == Kind.CONTACT && AirNewContactModel.checkMatches(hex);
        }
    }

    static final Parsed NOTHING = new Parsed(Kind.NONE, null);
    /**
     * How long an ID may wait behind the unlock. Long enough to type a password; a link left that long is not
     * opened out of context on some later unlock.
     */
    static final long PENDING_MAX_AGE_MS = 5 * 60 * 1000L;

    private static final String SCHEME = "tox:";
    private static final Object LOCK = new Object();
    private static String pendingHex = null;
    private static long pendingAt = 0L;

    private ToxLink()
    {
    }

    static boolean isToxLink(final String link)
    {
        return link != null && link.trim().regionMatches(true, 0, SCHEME, 0, SCHEME.length());
    }

    /**
     * Reads a link: "tox:" or "tox://" in any case, then the ID, which ends where a path, a query or a fragment
     * would start. Spaces (also as %20) may stand between its groups. What is left reads as an ID the way the
     * command line reads one (12 to 76 hex characters with a digit among them), and exactly 64 of them as a group
     * key. A run with a stray letter in it (an O typed for a zero) still goes to New contact with what the field
     * keeps of it, when that is as long as the start of an ID: the field then says the ID is not whole.
     */
    static Parsed parse(final String link)
    {
        if (!isToxLink(link))
        {
            return NOTHING;
        }
        String rest = link.trim().substring(SCHEME.length());
        while (rest.startsWith("/"))
        {
            rest = rest.substring(1);
        }
        for (final char end : new char[]{'/', '?', '#', '&'})
        {
            final int at = rest.indexOf(end);
            if (at >= 0)
            {
                rest = rest.substring(0, at);
            }
        }
        final String spaced = rest.replace("%20", " ");
        final String hex = AirNewContactModel.commandCandidate(spaced);
        if (hex != null)
        {
            return new Parsed(hex.length() == AirNewContactModel.KEY_LENGTH ? Kind.GROUP : Kind.CONTACT, hex);
        }
        final String kept = AirNewContactModel.clean(spaced);
        return kept.length() >= AirNewContactModel.COMMAND_MINIMUM ? new Parsed(Kind.CONTACT, kept) : NOTHING;
    }

    /** New contact with this ID filled in. */
    static void openNewContact(final Context context, final String hex)
    {
        final Intent intent = new Intent(context, AirNewContactActivity.class);
        intent.putExtra(AirNewContactActivity.EXTRA_ID, hex);
        context.startActivity(intent);
    }

    /** Keeps the ID of a link for New contact after the unlock; a newer link takes its place. */
    static void storePending(final String hex, final long now)
    {
        synchronized (LOCK)
        {
            pendingHex = hex;
            pendingAt = now;
        }
    }

    static boolean hasPending()
    {
        synchronized (LOCK)
        {
            return pendingHex != null;
        }
    }

    /** The ID waiting for New contact, taken once; null when none waits or it has waited too long. */
    static String takePending(final long now)
    {
        synchronized (LOCK)
        {
            final String hex = pendingHex;
            pendingHex = null;
            if (hex == null || now < pendingAt || now - pendingAt > PENDING_MAX_AGE_MS)
            {
                return null;
            }
            return hex;
        }
    }

    /**
     * From the main screen as it resumes: New contact with the ID a link left, unless the profile is not open yet
     * or an unlock is up or owed. The ID then keeps waiting: the main screen resumes again once the PIN is in.
     */
    static void openPending(final MainActivity activity)
    {
        if (activity == null || !hasPending() || TrifaToxService.orma == null || AppLockHelper.isGateUp(activity))
        {
            return;
        }
        final String hex = takePending(System.currentTimeMillis());
        if (hex != null)
        {
            activity.open_add_friend(hex);
        }
    }
}
