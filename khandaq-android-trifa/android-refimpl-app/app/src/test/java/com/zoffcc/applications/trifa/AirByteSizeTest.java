package com.zoffcc.applications.trifa;

import org.junit.Test;

import java.text.DecimalFormatSymbols;
import java.util.Locale;

import static org.junit.Assert.assertEquals;

/**
 * The sizes of files (redesign stage 3, E8): counted in thousands, whole bytes and KB, MB with one decimal,
 * GB with two, an amount that rounds up to 1000 in the next unit; the same cases as AirByteSize on iOS.
 */
public class AirByteSizeTest
{
    private static final int BYTES = 0;
    private static final int KB = 1;
    private static final int MB = 2;
    private static final int GB = 3;

    @Test
    public void bytes_and_kilobytes_are_whole()
    {
        assertAmount(0, BYTES, "0");
        assertAmount(999, BYTES, "999");
        assertAmount(1_000, KB, "1");
        assertAmount(154_499, KB, "154");
        assertAmount(154_500, KB, "155");
    }

    @Test
    public void megabytes_take_one_decimal_and_gigabytes_two()
    {
        assertAmount(12_600_000, MB, "12.6");
        assertAmount(12_649_999, MB, "12.6");
        assertAmount(12_650_000, MB, "12.7");
        assertAmount(8_000_000, MB, "8");
        assertAmount(1_234_567_890L, GB, "1.23");
        assertAmount(2_500_000_000L, GB, "2.5");
    }

    @Test
    public void an_amount_that_rounds_up_to_a_thousand_takes_the_next_unit()
    {
        assertAmount(999_499, KB, "999");
        assertAmount(999_500, MB, "1");
        assertAmount(999_949_999, MB, "999.9");
        assertAmount(999_950_000, GB, "1");
    }

    @Test
    public void a_negative_size_is_none()
    {
        assertAmount(-5, BYTES, "0");
    }

    @Test
    public void the_group_limit_reads_200_megabytes()
    {
        assertAmount(AirByteSize.GROUP_LIMIT_BYTES, MB, "200");
    }

    @Test
    public void the_language_writes_the_digits_and_the_decimal_sign()
    {
        final AirByteSize.Amount amount = AirByteSize.amount(12_600_000);
        assertEquals("12,6", AirByteSize.number(amount, Locale.forLanguageTag("ru")));
        assertEquals("12.6", AirByteSize.number(amount, Locale.CHINA));
        // Whatever digits the runtime gives Arabic, the amount is written in them, with its decimal sign.
        final Locale arabic = Locale.forLanguageTag("ar-EG");
        final DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(arabic);
        final char zero = symbols.getZeroDigit();
        final String expected = "" + (char) (zero + 1) + (char) (zero + 2) + symbols.getDecimalSeparator()
                                + (char) (zero + 6);
        assertEquals(expected, AirByteSize.number(amount, arabic));
    }

    private static void assertAmount(final long bytes, final int unit, final String number)
    {
        final AirByteSize.Amount amount = AirByteSize.amount(bytes);
        assertEquals("unit of " + bytes, unit, amount.unit);
        assertEquals("amount of " + bytes, number, AirByteSize.number(amount, Locale.US));
    }
}
