package com.zoffcc.applications.trifa;

import android.content.SharedPreferences;

/**
 * KHANDAQ (testers' ideas, 0.2.67): two switches in Settings > Privacy, both on by default.
 *
 * Send read receipts: turned off, the seen packet (189, HelperMessageSeen) never leaves this phone, nor
 * does an anchor that waited for the person to come online. The receipts of others still show: only
 * what this phone sends changes.
 *
 * Show that I'm typing: turned off, toxcore is never told that we type (tox_self_set_typing), so the
 * person never reads "typing". Groups send no typing state at all (NGC has none).
 *
 * Not the older key pref_send_read_receipts: that one (pref_notification.xml, a screen nothing opens)
 * gates the msgV2 receipt, which says delivered, never seen, and without which the sender resends the
 * message. Its value stays what it was.
 *
 * Read wherever the app runs: MainApplication loads both before any activity or service, and follows
 * every change, so a process woken in the background by a push reads the person's choice too.
 */
final class PrivacyPreference
{
    static final String KEY_SEEN_RECEIPTS = "pref_send_seen_receipts";
    static final String KEY_TYPING = "pref_send_typing";
    static final boolean DEFAULT = true;

    /** Whether this phone tells people that it has seen their messages. */
    static volatile boolean sendsSeenReceipts = DEFAULT;
    /** Whether this phone tells people that we are typing. */
    static volatile boolean sendsTyping = DEFAULT;

    private PrivacyPreference()
    {
    }

    /** Reads both switches; a value that cannot be read counts as the default. */
    static void load(final SharedPreferences settings)
    {
        sendsSeenReceipts = read(settings, KEY_SEEN_RECEIPTS);
        sendsTyping = read(settings, KEY_TYPING);
    }

    /** True for a key this class reads, and for null: the preferences were cleared (Android 11 and later). */
    static boolean concerns(final String key)
    {
        return key == null || KEY_SEEN_RECEIPTS.equals(key) || KEY_TYPING.equals(key);
    }

    /** The receipts switch was turned: it counts at once and is kept. */
    static void setSeenReceipts(final SharedPreferences settings, final boolean on)
    {
        sendsSeenReceipts = on;
        settings.edit().putBoolean(KEY_SEEN_RECEIPTS, on).apply();
    }

    /** The typing switch was turned: it counts at once and is kept. */
    static void setTyping(final SharedPreferences settings, final boolean on)
    {
        sendsTyping = on;
        settings.edit().putBoolean(KEY_TYPING, on).apply();
    }

    private static boolean read(final SharedPreferences settings, final String key)
    {
        try
        {
            return settings.getBoolean(key, DEFAULT);
        }
        catch (Exception e)
        {
            return DEFAULT;
        }
    }
}
