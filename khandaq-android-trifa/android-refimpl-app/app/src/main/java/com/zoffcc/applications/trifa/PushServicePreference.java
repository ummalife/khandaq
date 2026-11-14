package com.zoffcc.applications.trifa;

import android.content.SharedPreferences;

/**
 * KHANDAQ (0.2.56): whether this phone hands its contacts its push address and wakes theirs through the push
 * relay. The setting defaulted to off and its switch went away in 0.2.7, so since then no Android install
 * published its address or sent anybody a wake. It is on now, and it is turned on once for an install that
 * kept the old off; a value stored after that is read as it is.
 */
final class PushServicePreference
{
    static final String KEY = "use_push_service";
    /** Set once {@link #KEY} was turned on for this install. */
    static final String TURNED_ON_KEY = "use_push_service_turned_on_0256";
    static final boolean DEFAULT = true;

    private PushServicePreference()
    {
    }

    /** Reads the setting, turning it on first when that was not done for this install yet. */
    static boolean load(final SharedPreferences settings)
    {
        if (!settings.getBoolean(TURNED_ON_KEY, false))
        {
            settings.edit().putBoolean(KEY, true).putBoolean(TURNED_ON_KEY, true).apply();
            return true;
        }
        return settings.getBoolean(KEY, DEFAULT);
    }
}
