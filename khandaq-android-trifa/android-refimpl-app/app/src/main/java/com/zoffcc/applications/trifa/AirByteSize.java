package com.zoffcc.applications.trifa;

import android.content.Context;

import org.khandaq.messenger.R;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Locale;

/**
 * A size in the app's language ("12.6 MB", "154 KB"), counted in thousands as the phone's file manager
 * counts (redesign stage 3, E8; design/DESIGN.md, "Files, voice and calls on the phone"): whole bytes and
 * KB, MB with one decimal, GB with two, and an amount that rounds up to 1000 takes the next unit. The
 * rules of AirByteSize on iOS. The system Formatter wrote "8.43 MB" in the system language, which the
 * language chosen in the app does not change.
 */
final class AirByteSize
{
    /**
     * A group file travels in NGC custom packets, capped at 200 MiB (KHANDAQ_MAX_FILE_TRANSFER_BYTES). The
     * hint and the refusal both say 200 MB, the safe side of that cap, as iOS does.
     */
    static final long GROUP_LIMIT_BYTES = 200L * 1_000_000L;

    private static final long[] UNIT_BYTES = {1L, 1_000L, 1_000_000L, 1_000_000_000L};
    private static final int[] FRACTION_DIGITS = {0, 0, 1, 2};
    private static final int[] FORMATS = {R.string.air_size_bytes_format, R.string.air_size_kb_format,
                                          R.string.air_size_mb_format, R.string.air_size_gb_format};
    private static final BigDecimal NEXT_UNIT = BigDecimal.valueOf(1000L);

    private AirByteSize()
    {
    }

    /** The unit (0 bytes, 1 KB, 2 MB, 3 GB) and the amount rounded to its decimals. */
    static final class Amount
    {
        final int unit;
        final BigDecimal value;

        Amount(final int unit, final BigDecimal value)
        {
            this.unit = unit;
            this.value = value;
        }
    }

    static Amount amount(final long bytes)
    {
        final long size = Math.max(0L, bytes);
        int unit = 0;
        while (unit + 1 < UNIT_BYTES.length && size >= UNIT_BYTES[unit + 1])
        {
            unit++;
        }
        BigDecimal value = rounded(size, unit);
        // "1 MB", not "1000 KB", when the amount rounds up to the next unit.
        if (value.compareTo(NEXT_UNIT) >= 0 && unit + 1 < UNIT_BYTES.length)
        {
            unit++;
            value = rounded(size, unit);
        }
        return new Amount(unit, value);
    }

    /** The amount in the digits and the decimal sign of {@code locale}, without trailing zeros. */
    static String number(final Amount amount, final Locale locale)
    {
        final NumberFormat format = NumberFormat.getNumberInstance(locale);
        format.setGroupingUsed(true);
        format.setMinimumFractionDigits(0);
        format.setMaximumFractionDigits(FRACTION_DIGITS[amount.unit]);
        format.setRoundingMode(RoundingMode.HALF_UP);
        return format.format(amount.value);
    }

    static String text(final Context context, final long bytes)
    {
        final Amount amount = amount(bytes);
        return context.getString(FORMATS[amount.unit], number(amount, AirUi.locale(context)));
    }

    /** "200 MB": the largest file a group takes, in the hint and in the refusal alike. */
    static String groupLimitText(final Context context)
    {
        return text(context, GROUP_LIMIT_BYTES);
    }

    /** "A group takes files up to 200 MB", next to the choice of a file. */
    static String groupLimitHint(final Context context)
    {
        return context.getString(R.string.air_group_file_limit_format, groupLimitText(context));
    }

    private static BigDecimal rounded(final long bytes, final int unit)
    {
        return BigDecimal.valueOf(bytes).divide(BigDecimal.valueOf(UNIT_BYTES[unit]), FRACTION_DIGITS[unit],
                                                RoundingMode.HALF_UP);
    }
}
