package com.zoffcc.applications.trifa;

import org.junit.Test;

import org.khandaq.messenger.KhandaqPush;

import static com.zoffcc.applications.trifa.PushPreviewCryptoTest.PID;
import static com.zoffcc.applications.trifa.PushPreviewCryptoTest.PREVIEW;
import static com.zoffcc.applications.trifa.PushPreviewCryptoTest.repeat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (0.2.64): a wake to our relay carries "preview" and "pid" (docs/PUSH_PREVIEWS.md, "The wake request"):
 * both or neither, after everything the wake carried before, and only what the relay accepts, so that anything else
 * goes out as the plain wake it was. The signature does not cover them; its pre-image is pinned by
 * scripts/check-push-emission-shape.py.
 */
public class KhandaqWakePreviewTest
{
    private static final String TOKEN = "fcm-registration-token-value";
    private static final String SENDER = "AABBCCDDEEFF00112233445566778899AABBCCDDEEFF00112233445566778899";
    private static final String CAP = "3q2-796tvu_erb7v3q2-796tvu_erb7v3q2-796tvu8";
    private static final String OWN = KhandaqPush.RELAY_BASE + "/toxfcm/fcm.php?id=" + TOKEN + "&type=1";
    private static final String PLAIN_BODY = "{\"token\":\"" + TOKEN + "\",\"sender\":\"" + SENDER + "\"}";

    @Test
    public void the_preview_and_its_id_follow_the_capability()
    {
        final KhandaqPush.WakeRequest r = KhandaqPush.buildWakeRequest(OWN + "&cap=" + CAP, SENDER, PREVIEW, PID);
        assertTrue(r.isJson());
        assertEquals(KhandaqPush.RELAY_BASE + "/wake", r.url);
        assertEquals("{\"token\":\"" + TOKEN + "\",\"sender\":\"" + SENDER + "\",\"cap\":\"" + CAP + "\","
                     + "\"preview\":\"" + PREVIEW + "\",\"pid\":\"" + PID + "\"}", r.jsonBody);
    }

    @Test
    public void a_wake_without_a_capability_carries_the_preview_too()
    {
        final KhandaqPush.WakeRequest r = KhandaqPush.buildWakeRequest(OWN, SENDER, PREVIEW, PID);
        assertEquals("{\"token\":\"" + TOKEN + "\",\"sender\":\"" + SENDER + "\",\"preview\":\"" + PREVIEW + "\","
                     + "\"pid\":\"" + PID + "\"}", r.jsonBody);
        assertFalse("nothing of the preview in the URL", r.url.contains(PREVIEW) || r.url.contains(PID));
    }

    @Test
    public void the_plain_wake_is_what_it_was()
    {
        assertEquals(PLAIN_BODY, KhandaqPush.buildWakeRequest(OWN, SENDER).jsonBody);
        assertEquals(PLAIN_BODY, KhandaqPush.buildWakeRequest(OWN, SENDER, null, null).jsonBody);
    }

    @Test
    public void a_preview_the_relay_would_refuse_goes_as_a_plain_wake()
    {
        for (final String preview : new String[]{null, "", repeat("A", 23), PREVIEW.replace('-', '+'), PREVIEW + "=",
                                                 PREVIEW + " ", repeat("A", 1401)})
        {
            assertEquals(String.valueOf(preview == null ? null : preview.length()), PLAIN_BODY,
                         KhandaqPush.buildWakeRequest(OWN, SENDER, preview, PID).jsonBody);
        }
        // The shortest and the longest the relay keeps.
        assertTrue(KhandaqPush.buildWakeRequest(OWN, SENDER, repeat("A", 24), PID).jsonBody.contains("\"pid\""));
        assertTrue(KhandaqPush.buildWakeRequest(OWN, SENDER, repeat("_", 1400), PID).jsonBody.contains("\"pid\""));
    }

    @Test
    public void an_id_the_relay_would_refuse_goes_as_a_plain_wake()
    {
        for (final String pid : new String[]{null, "", PID.toUpperCase(java.util.Locale.ROOT), PID.substring(1),
                                             PID + "0", PID.substring(1) + "g"})
        {
            assertEquals(String.valueOf(pid), PLAIN_BODY, KhandaqPush.buildWakeRequest(OWN, SENDER, PREVIEW, pid).jsonBody);
        }
    }

    @Test
    public void a_sender_the_recipient_cannot_derive_a_key_from_gets_no_preview()
    {
        final String shortSender = SENDER.substring(2);
        assertEquals("{\"token\":\"" + TOKEN + "\",\"sender\":\"" + shortSender + "\"}",
                     KhandaqPush.buildWakeRequest(OWN, shortSender, PREVIEW, PID).jsonBody);
        assertEquals("{\"token\":\"" + TOKEN + "\",\"sender\":\"\"}",
                     KhandaqPush.buildWakeRequest(OWN, null, PREVIEW, PID).jsonBody);
        final String notHex = "ZZ" + SENDER.substring(2);
        assertFalse(KhandaqPush.buildWakeRequest(OWN, notHex, PREVIEW, PID).jsonBody.contains("preview"));
    }

    @Test
    public void somebody_elses_relay_never_sees_a_preview()
    {
        final String legacy = "https://tox.zoff.xyz/toxfcm/fcm.php?id=" + TOKEN + "&type=1";
        final KhandaqPush.WakeRequest r = KhandaqPush.buildWakeRequest(legacy, SENDER, PREVIEW, PID);
        assertFalse(r.isJson());
        assertNull(r.jsonBody);
        assertEquals(KhandaqPush.withWakeParams(legacy, SENDER), r.url);
        assertFalse(r.url.contains(PREVIEW) || r.url.contains(PID));
    }
}
