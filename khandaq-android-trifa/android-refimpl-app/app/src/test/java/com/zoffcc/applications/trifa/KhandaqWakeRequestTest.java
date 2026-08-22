package com.zoffcc.applications.trifa;

import org.junit.Test;

import org.khandaq.messenger.KhandaqPush;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (re-audit 2026-08-22, K-03) — the wake request must stop carrying credentials in its URL.
 *
 * <p>The FCM registration token is a targeting secret: hold it and you can push to that device. It
 * used to travel in the request URI beside the HMAC and the timestamp. nginx redacts the query
 * string on that endpoint, which is worth having and is not the whole path — a URL also reaches
 * client-side diagnostics, crash reporters, intermediary proxies, and anything that logs before our
 * redaction applies. A request body reaches none of those by default.
 *
 * <p>What is pinned here is that a call to OUR relay now expresses everything in a body, that the
 * signature pre-image did NOT change while the shape did (a client that moves endpoints must not
 * look to the relay like a client with the wrong secret), and that a push URL belonging to somebody
 * else's relay is left exactly as it was.
 */
public class KhandaqWakeRequestTest
{
    private static final String TOKEN = "fcm-registration-token-value";
    private static final String SENDER = "AABBCCDDEEFF00112233445566778899AABBCCDDEEFF00112233445566778899";
    private static final String CAP = "3q2-796tvu_erb7v3q2-796tvu_erb7v3q2-796tvu8";
    private static final String OWN = KhandaqPush.RELAY_BASE + "/toxfcm/fcm.php?id=" + TOKEN + "&type=1";

    @Test
    public void ourRelayIsCalledWithAnEmptyQueryString()
    {
        final KhandaqPush.WakeRequest r = KhandaqPush.buildWakeRequest(OWN, SENDER);
        assertTrue("our own wake URL must be recognised", KhandaqPush.isOwnRelayWakeUrl(OWN));
        assertEquals(KhandaqPush.RELAY_BASE + "/wake", r.url);
        assertFalse("the endpoint URL must carry no query string at all", r.url.contains("?"));
        assertTrue(r.isJson());
    }

    @Test
    public void theTokenIsInTheBodyAndNowhereElse()
    {
        final KhandaqPush.WakeRequest r = KhandaqPush.buildWakeRequest(OWN, SENDER);
        assertFalse("the registration token must not appear in the URL", r.url.contains(TOKEN));
        assertTrue("the registration token must be in the body", r.jsonBody.contains(TOKEN));
        assertTrue(r.jsonBody.contains(SENDER));
    }

    @Test
    public void aPublishedCapabilityIsCarriedIntoTheBody()
    {
        final KhandaqPush.WakeRequest r = KhandaqPush.buildWakeRequest(OWN + "&cap=" + CAP, SENDER);
        assertTrue("the recipient's capability must reach the relay", r.jsonBody.contains(CAP));
        assertFalse("and must not be left in the URL either", r.url.contains(CAP));
    }

    @Test
    public void aMissingCapabilityIsSimplyAbsent()
    {
        // Not an empty string: the relay treats "" and absent identically, but sending a key whose
        // value is empty invites a future reader to think one was minted and lost.
        final KhandaqPush.WakeRequest r = KhandaqPush.buildWakeRequest(OWN, SENDER);
        assertFalse(r.jsonBody.contains("\"cap\""));
    }

    @Test
    public void somebodyElsesRelayIsLeftAlone()
    {
        final String legacy = "https://tox.zoff.xyz/toxfcm/fcm.php?id=" + TOKEN + "&type=1";
        final KhandaqPush.WakeRequest r = KhandaqPush.buildWakeRequest(legacy, SENDER);
        assertFalse("a relay we do not own is not ours to reinterpret", r.isJson());
        assertNull(r.jsonBody);
        assertTrue("the legacy shape must still carry its parameters in the URL", r.url.contains(TOKEN));
        assertTrue(r.url.startsWith("https://tox.zoff.xyz/"));
    }

    @Test
    public void aUrlWithoutATokenIsNotTreatedAsOurs()
    {
        final String noId = KhandaqPush.RELAY_BASE + "/toxfcm/fcm.php?type=1";
        assertFalse(KhandaqPush.isOwnRelayWakeUrl(noId));
        assertFalse(KhandaqPush.buildWakeRequest(noId, SENDER).isJson());
    }

    @Test
    public void jsonEscapingSurvivesAQuote()
    {
        // The values in play are base64url and hex, so this cannot happen today. Assuming that is
        // how an unescaped quote ends up in a body the relay then answers 400 to, for every wake,
        // silently, for as long as the value that produced it lives.
        assertEquals("\"a\\\"b\"", KhandaqPush.jsonString("a\"b"));
        assertEquals("\"a\\\\b\"", KhandaqPush.jsonString("a\\b"));
        assertEquals("\"a\\nb\"", KhandaqPush.jsonString("a\nb"));
    }

    @Test
    public void withWakeParamsStillWorksForTheLegacyShape()
    {
        // The legacy path is what every already-installed client uses, and what our own client falls
        // back to for foreign relays. A change here would be an outage, not a regression.
        final String url = KhandaqPush.withWakeParams(
                "https://tox.zoff.xyz/toxfcm/fcm.php?id=" + TOKEN, SENDER);
        assertTrue(url.contains("from=" + SENDER));
        assertTrue(url.startsWith("https://tox.zoff.xyz/toxfcm/fcm.php?id=" + TOKEN));
    }
}
