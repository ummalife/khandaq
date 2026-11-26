package com.zoffcc.applications.trifa;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Resources;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.khandaq.messenger.R;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import androidx.test.core.app.ApplicationProvider;

import static org.junit.Assert.assertEquals;

/**
 * KHANDAQ (2026-11-20, notification parity): the system notification of a group message says who wrote it, "Name:
 * text", as the in-app band, iOS and the desktop say it. Only the group's name was there.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class GroupNotificationMemberTest
{
    /**
     * The app's own strings are not in Robolectric's resources here (the unit tests do not include the Android
     * resources), so the format is given as values/strings_air_band.xml has it.
     */
    private static Context context()
    {
        final Context base = ApplicationProvider.getApplicationContext();
        final Resources real = base.getResources();
        final Resources strings = new Resources(real.getAssets(), real.getDisplayMetrics(), real.getConfiguration())
        {
            @Override
            public String getString(final int id, final Object... args)
            {
                if (id == R.string.air_band_sender_text_format)
                {
                    return String.format("%1$s: %2$s", args);
                }
                return super.getString(id, args);
            }
        };
        return new ContextWrapper(base)
        {
            @Override
            public Resources getResources()
            {
                return strings;
            }
        };
    }

    @Test
    public void theMemberWhoWroteLeadsTheText()
    {
        assertEquals("QA A: hello", HelperMsgNotification.with_group_member(context(), "QA A", "hello"));
        assertEquals("QA A: 📷 Photo", HelperMsgNotification.with_group_member(context(), "  QA A ", "📷 Photo"));
    }

    @Test
    public void withoutAMemberTheTextStandsAlone()
    {
        assertEquals("hello", HelperMsgNotification.with_group_member(context(), null, "hello"));
        assertEquals("hello", HelperMsgNotification.with_group_member(context(), "", "hello"));
        assertEquals("hello", HelperMsgNotification.with_group_member(context(), "   ", "hello"));
        assertEquals("hello", HelperMsgNotification.with_group_member(null, "QA A", "hello"));
    }
}
