package com.zoffcc.applications.trifa;

import java.security.SecureRandom;

/**
 * KHANDAQ (stage 5): random strings for secrets, from the alphabet MainActivity.getRandomString has always
 * drawn from. The key of a profile without a password is such a secret: it encrypts the database, the file
 * store and the Tox save on this phone, so it comes from SecureRandom. java.util.Random is a 48-bit LCG whose
 * whole future follows from two of its outputs. MainActivity.getRandomString stays for strings that only need
 * to look unique. Keys made before keep working: nothing is re-keyed.
 */
final class SecureRandomString
{
    /** The alphabet of the generated keys. Keys already on phones were drawn from it, so it does not change. */
    static final String ALLOWED_CHARACTERS =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz!§$%&()=?,.;:-_+";

    private static final SecureRandom RANDOM = new SecureRandom();

    private SecureRandomString()
    {
    }

    /** {@code length} characters of {@link #ALLOWED_CHARACTERS}, each drawn uniformly by SecureRandom. */
    static String next(final int length)
    {
        if (length < 0)
        {
            throw new IllegalArgumentException("length < 0: " + length);
        }
        final StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++)
        {
            sb.append(ALLOWED_CHARACTERS.charAt(RANDOM.nextInt(ALLOWED_CHARACTERS.length())));
        }
        return sb.toString();
    }
}
