package com.zoffcc.applications.trifa;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * KHANDAQ (redesign stage 4, E20): why a profile did not open with the typed password, told apart for
 * the words under the field (design/DESIGN.md, "First launch, sign-in and PIN on the phone"). The
 * password itself is checked by DbSecretKeyStorage as before; this only looks at the two files when
 * that check failed. A file the app cannot read is no access; a database that is not whole pages
 * (SQLCipher writes whole pages) or a savedata with neither Tox header is damaged; anything else is
 * a wrong password.
 * <p>
 * Stage 5: where iOS gives the same failure a code for support, so does this (AirErrorCode), under
 * Details: a save it cannot read is ProfileOpening 1 and a history it cannot read ProfileOpening 2,
 * as iOS checks them (ProfileOpening.unreadableFile, the save first); a save toxcore would not take is
 * objcTox's CreateToxBadFormat. A wrong password has no code on either phone, and nor has a database
 * cut off mid-page, which iOS has no counterpart for.
 */
final class AirProfileCheck
{
    enum Failure
    {
        WRONG_PASSWORD,
        DAMAGED,
        NO_ACCESS,
    }

    /** SQLCipher pages are 1024 or 4096 bytes, so a whole database is always a multiple of this. */
    static final int PAGE_UNIT = 512;

    private static final byte[] ENCRYPTED_MAGIC = {'t', 'o', 'x', 'E', 's', 'a', 'v', 'e'};
    private static final byte[] PLAIN_HEADER = {0, 0, 0, 0, 0x1f, 0x1b, (byte) 0xed, 0x15};

    private AirProfileCheck()
    {
    }

    /** Why the profile did not open, and the code for support that goes with it, null when none does. */
    static final class Verdict
    {
        final Failure failure;
        @Nullable
        final String code;

        Verdict(final Failure failure, @Nullable final String code)
        {
            this.failure = failure;
            this.code = code;
        }
    }

    static Failure classify(final File database, @Nullable final File savedata)
    {
        return check(database, savedata).failure;
    }

    /**
     * A profile file that is there and cannot be read, looked at before anything opens the profile, as iOS
     * does (ProfileOpening.unreadableFile): the database alone opens with the password or the remembered
     * key, and Tox would then start without its save, under an ID that is not this profile's. Null when
     * both files read or are not there yet.
     */
    @Nullable
    static Verdict unreadable(final File database, @Nullable final File savedata)
    {
        if (savedata != null && savedata.exists() && !savedata.canRead())
        {
            return noAccess(AirErrorCode.PROFILE_SAVE_UNREADABLE);
        }
        if (database.exists() && !database.canRead())
        {
            return noAccess(AirErrorCode.PROFILE_HISTORY_UNREADABLE);
        }
        return null;
    }

    static Verdict check(final File database, @Nullable final File savedata)
    {
        final Verdict unreadable = unreadable(database, savedata);
        if (unreadable != null)
        {
            return unreadable;
        }
        if (database.isFile() && (database.length() % PAGE_UNIT) != 0)
        {
            return new Verdict(Failure.DAMAGED, null);
        }
        if (savedata != null && savedata.isFile())
        {
            final byte[] head = new byte[8];
            try (FileInputStream in = new FileInputStream(savedata))
            {
                final boolean header = in.read(head) == head.length
                                       && (startsWith(head, ENCRYPTED_MAGIC) || startsWith(head, PLAIN_HEADER));
                if (!header)
                {
                    // toxcore would not take it as a profile: iOS says CreateToxBadFormat.
                    final String code = AirErrorCode.manager(AirErrorCode.MANAGER_CREATE_TOX_BAD_FORMAT);
                    return new Verdict(Failure.DAMAGED, code);
                }
            }
            catch (IOException e)
            {
                return noAccess(AirErrorCode.PROFILE_SAVE_UNREADABLE);
            }
        }
        return new Verdict(Failure.WRONG_PASSWORD, null);
    }

    private static Verdict noAccess(final int profileOpening)
    {
        return new Verdict(Failure.NO_ACCESS, AirErrorCode.profileOpening(profileOpening));
    }

    private static boolean startsWith(final byte[] head, final byte[] expected)
    {
        for (int i = 0; i < expected.length; i++)
        {
            if (head[i] != expected[i])
            {
                return false;
            }
        }
        return true;
    }
}
