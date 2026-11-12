package com.zoffcc.applications.trifa;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.util.Log;

import androidx.preference.PreferenceManager;

import org.khandaq.messenger.R;

/**
 * KHANDAQ (redesign, stage 5): whether Khandaq may keep running in the background, asked once from below
 * in the E21 style (AirQuestion). It was a dialog the main screen opened at its start, over what came
 * next; now it waits until Air is in front by itself: not while "Your ID is ready" is about to open, and
 * not while Add the first person has just put the command line in focus. The answer goes in the same
 * preference as before, so a person who answered the old dialog is not asked again.
 */
final class AirBatteryQuestion
{
    private static final String TAG = "trifa.AirBattery";
    /** The preference of the old dialog, kept so an upgraded profile that answered it is left alone. */
    static final String PREF_ASKED = "asked_whitelist_doze";

    private AirBatteryQuestion()
    {
    }

    /**
     * Asks, unless it was asked before, the system has no such setting, Khandaq is exempt already, or the
     * first launch is not over: {@code idPageNext} when "Your ID is ready" is opening now, and
     * {@code commandWanted} when the command line takes the focus for the first person.
     */
    static void askOnceIfDue(final Activity activity, final boolean idPageNext, final boolean commandWanted)
    {
        if (idPageNext || commandWanted || activity.isFinishing() || activity.isDestroyed())
        {
            return;
        }
        try
        {
            final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
            if (prefs.getBoolean(PREF_ASKED, false))
            {
                return;
            }
            final Intent intent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            if (activity.getPackageManager().resolveActivity(intent, 0) == null
                || PowerSaverHelper.getIfAppIsWhiteListedFromBatteryOptimizations(activity, activity.getPackageName())
                   == PowerSaverHelper.WhiteListedInBatteryOptimizations.WHITE_LISTED)
            {
                // Nothing to open, or nothing left to allow: the question would only be in the way.
                prefs.edit().putBoolean(PREF_ASKED, true).apply();
                return;
            }
            if (AirQuestion.ask(activity, activity.getString(R.string.air_battery_question),
                                activity.getString(R.string.air_battery_consequence),
                                activity.getString(R.string.air_battery_open), false, () -> open(activity, intent))
                != null)
            {
                // Once, whatever the answer, as the old dialog did.
                prefs.edit().putBoolean(PREF_ASKED, true).commit();
            }
        }
        catch (Exception e)
        {
            Log.i(TAG, "askOnceIfDue:EE:" + e.getMessage());
        }
    }

    private static void open(final Activity activity, final Intent intent)
    {
        try
        {
            activity.startActivity(intent);
        }
        catch (Exception e)
        {
            Log.i(TAG, "open:EE:" + e.getMessage());
        }
    }
}
