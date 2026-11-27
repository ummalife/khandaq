package com.zoffcc.applications.trifa;

/**
 * KHANDAQ (owner, 28.11: "it sends another place, not where I am"): picks the fix a shared place is sent with, as the
 * iPhone does (LocationFixPicker.swift). A fix older than its maximum age, or one without an accuracy, is not where the
 * person is now (the system keeps the last fix it had, which may be from another town), and of the fresh ones the most
 * accurate wins. One within GOOD_ACCURACY_M ends the wait at once, one within FAIR_ACCURACY_M once PATIENCE_MS has
 * passed (indoors the radio seldom does better), and when the wait ends the best fresh fix there is goes.
 * <p>
 * Plain values only, so a JVM test runs it without a device.
 */
final class LocationFixPicker
{
    static final long MAX_AGE_MS = 10_000L;
    static final float GOOD_ACCURACY_M = 20f;
    static final float FAIR_ACCURACY_M = 65f;
    static final long PATIENCE_MS = 2_500L;

    private final long maxAgeMs;
    private boolean hasFix = false;
    private double latitude;
    private double longitude;
    private float accuracyM;

    LocationFixPicker()
    {
        this(MAX_AGE_MS);
    }

    LocationFixPicker(final long maxAgeMs)
    {
        this.maxAgeMs = maxAgeMs;
    }

    /**
     * Takes the fix when it is fresh enough, has an accuracy and is the most accurate so far.
     *
     * @return whether it took it
     */
    boolean consider(final double latitude, final double longitude, final boolean hasAccuracy, final float accuracyM,
                     final long ageMs)
    {
        if (!hasAccuracy || !(accuracyM >= 0f) || ageMs > maxAgeMs)
        {
            return false;
        }
        if (Double.isNaN(latitude) || Double.isNaN(longitude) || Math.abs(latitude) > 90.0
            || Math.abs(longitude) > 180.0)
        {
            return false;
        }
        if (hasFix && this.accuracyM <= accuracyM)
        {
            return false;
        }
        hasFix = true;
        this.latitude = latitude;
        this.longitude = longitude;
        this.accuracyM = accuracyM;
        return true;
    }

    /** Whether the best fix so far is good enough to stop waiting, {@code elapsedMs} after the wait began. */
    boolean isSettled(final long elapsedMs)
    {
        if (!hasFix)
        {
            return false;
        }
        final float enough = elapsedMs < PATIENCE_MS ? GOOD_ACCURACY_M : FAIR_ACCURACY_M;
        return accuracyM <= enough;
    }

    boolean hasFix()
    {
        return hasFix;
    }

    double latitude()
    {
        return latitude;
    }

    double longitude()
    {
        return longitude;
    }

    float accuracyM()
    {
        return accuracyM;
    }
}
