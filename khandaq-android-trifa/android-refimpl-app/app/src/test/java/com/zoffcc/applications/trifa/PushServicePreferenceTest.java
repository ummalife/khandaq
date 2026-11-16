package com.zoffcc.applications.trifa;

import org.junit.Test;

import java.net.InetSocketAddress;
import java.net.Proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (0.2.56): push wake-ups on Android. The setting that lets a phone publish its push address and wake
 * its contacts defaulted to off with no switch left to change it, the FCM token was dropped when it came before
 * the database was open, and a capability registration went past Orbot once push was on.
 */
public class PushServicePreferenceTest
{
    private static final String TOKEN = "fcm-token_0123456789:abcdefghij.KLMNOP";
    private static final String NEWER_TOKEN = "fcm-token_9876543210:abcdefghij.QRSTUV";

    @Test
    public void a_new_install_has_push_on()
    {
        final MemoryPreferences settings = new MemoryPreferences();
        assertTrue(PushServicePreference.load(settings));
        assertEquals(Boolean.TRUE, settings.values.get(PushServicePreference.KEY));
        assertEquals(Boolean.TRUE, settings.values.get(PushServicePreference.TURNED_ON_KEY));
    }

    @Test
    public void an_install_that_kept_the_old_off_is_turned_on_once()
    {
        final MemoryPreferences settings = new MemoryPreferences();
        settings.values.put(PushServicePreference.KEY, Boolean.FALSE);
        assertTrue(PushServicePreference.load(settings));
        assertEquals(Boolean.TRUE, settings.values.get(PushServicePreference.KEY));
        assertEquals(1, settings.commits);
        // Done once: the next start reads what is stored, and writes nothing.
        assertTrue(PushServicePreference.load(settings));
        assertEquals(1, settings.commits);
    }

    @Test
    public void a_value_stored_after_the_turn_on_is_read_as_it_is()
    {
        final MemoryPreferences settings = new MemoryPreferences();
        assertTrue(PushServicePreference.load(settings));
        settings.values.put(PushServicePreference.KEY, Boolean.FALSE);
        assertFalse(PushServicePreference.load(settings));
        assertEquals(Boolean.FALSE, settings.values.get(PushServicePreference.KEY));
    }

    @Test
    public void the_default_is_on()
    {
        assertTrue(PushServicePreference.DEFAULT);
    }

    @Test
    public void a_token_that_comes_before_the_database_is_kept_for_it()
    {
        // No database in a JVM test: orma is null, as it is before MainActivity has opened it.
        assertNull(TrifaToxService.orma);
        HelperRelay.apply_notification_token_auto(TOKEN);
        assertEquals(TOKEN, HelperRelay.notification_token_waiting_for_db());

        // Still not open: applying it keeps it again rather than losing it.
        HelperRelay.apply_notification_token_waiting_for_db();
        assertEquals(TOKEN, HelperRelay.notification_token_waiting_for_db());

        // A newer token replaces it; the newest is the one stored once the database is open.
        HelperRelay.apply_notification_token_auto(" " + NEWER_TOKEN + " ");
        assertEquals(NEWER_TOKEN, HelperRelay.notification_token_waiting_for_db());
    }

    @Test
    public void a_registration_goes_through_orbot_when_the_app_does()
    {
        assertNull("without Orbot the usual way", KhandaqPushCapability.relayProxy(false));

        final Proxy proxy = KhandaqPushCapability.relayProxy(true);
        assertEquals(Proxy.Type.SOCKS, proxy.type());
        final InetSocketAddress address = (InetSocketAddress) proxy.address();
        assertEquals(TRIFAGlobals.ORBOT_PROXY_HOST, address.getHostString());
        assertEquals(TRIFAGlobals.ORBOT_PROXY_PORT, address.getPort());
    }
}
