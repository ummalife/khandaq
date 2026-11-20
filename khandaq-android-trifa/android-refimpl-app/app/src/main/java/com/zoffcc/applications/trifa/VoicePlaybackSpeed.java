package com.zoffcc.applications.trifa;

import android.content.SharedPreferences;

import java.text.NumberFormat;
import java.util.Locale;

/**
 * KHANDAQ (testers' ideas, 0.2.67): how fast voice notes play: 1x, 1.5x or 2x. A tap on the pill of the note
 * that plays goes to the next speed, and from 2x back to 1x. One speed for every note in the app, kept across
 * restarts (MainApplication loads it before any screen); the pitch stays where it is, so a voice played faster
 * does not go high (ChatVoicePlaybackManager sets it with PlaybackParams).
 */
final class VoicePlaybackSpeed
{
    static final String KEY = "pref_voice_playback_speed";
    static final float NORMAL = 1f;
    /** The speeds in the order a tap goes through them. */
    static final float[] STEPS = {1f, 1.5f, 2f};

    /** The speed every voice note plays at. */
    static volatile float current = NORMAL;

    private VoicePlaybackSpeed()
    {
    }

    /** Reads the kept speed; a value that cannot be read, or is not one of the steps, counts as 1x. */
    static void load(final SharedPreferences settings)
    {
        try
        {
            current = known(settings.getFloat(KEY, NORMAL));
        }
        catch (Exception e)
        {
            current = NORMAL;
        }
    }

    /** A tap on the pill: the next speed counts at once and is kept. */
    static float cycle(final SharedPreferences settings)
    {
        final float speed = next(current);
        current = speed;
        try
        {
            settings.edit().putFloat(KEY, speed).apply();
        }
        catch (Exception ignored)
        {
        }
        return speed;
    }

    /** 1x to 1.5x to 2x and back to 1x; from anything else, 1x. */
    static float next(final float speed)
    {
        for (int i = 0; i < STEPS.length; i++)
        {
            if (STEPS[i] == speed)
            {
                return STEPS[(i + 1) % STEPS.length];
            }
        }
        return NORMAL;
    }

    /** The speed when it is one of the steps, 1x otherwise. */
    static float known(final float speed)
    {
        for (final float step : STEPS)
        {
            if (step == speed)
            {
                return speed;
            }
        }
        return NORMAL;
    }

    /** "1", "1.5", "2" in the digits and the decimal sign of {@code locale} (the app's language). */
    static String number(final Locale locale, final float speed)
    {
        final NumberFormat format = NumberFormat.getNumberInstance(locale);
        format.setGroupingUsed(false);
        format.setMinimumFractionDigits(0);
        format.setMaximumFractionDigits(1);
        return format.format(speed);
    }
}
