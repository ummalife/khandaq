package com.zoffcc.applications.trifa;

/**
 * KHANDAQ (redesign, stage 5): the values of Settings > About that are built from parts, kept apart from
 * the screen (Aboutpage) so the JVM tests reach them.
 */
final class AirAboutText
{
    /** A zero-width space: a place where a line may break, drawn as nothing. */
    private static final char BREAK_OPPORTUNITY = (char) 0x200B;

    private AirAboutText()
    {
    }

    /**
     * The build string ("1a2b-0c3-9f1-33-arm64-v8a", HelperGeneric.get_trifa_build_str) may break after
     * each of its hyphens, so a narrow line or a large font wraps it there and never inside one of its
     * parts; "x86_64" stays whole. The line breaker alone finds no place: it keeps a hyphen with the digit
     * after it. TalkBack is given the plain string.
     */
    static String breakableAtSeparators(final String value)
    {
        if (value == null)
        {
            return "";
        }
        final StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++)
        {
            final char c = value.charAt(i);
            out.append(c);
            if (c == '-' && i + 1 < value.length())
            {
                out.append(BREAK_OPPORTUNITY);
            }
        }
        return out.toString();
    }

    /** toxcore's version as iOS shows it (OCTTox.version): major.minor.patch, in Latin digits. */
    static String toxcoreVersion(final long major, final long minor, final long patch)
    {
        return major + "." + minor + "." + patch;
    }
}
