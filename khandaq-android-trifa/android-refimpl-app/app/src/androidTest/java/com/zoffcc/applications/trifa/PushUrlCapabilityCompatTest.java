package com.zoffcc.applications.trifa;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (re-audit 2026-08-21, R-01) — the assumption the capability rollout is built on.
 *
 * {@code DESIGN-push-per-install-capabilities.md} §3 calls it a "fortunate accident, worth verifying
 * before relying on it": the shipped URL validators check host, path and a non-empty {@code id}, so
 * they would already accept a wake URL carrying {@code cap=} without any client change. That is what
 * satisfies the design's requirement 4 — accept before emit — and it is the difference between a
 * staged rollout and a flag day on two mobile stores.
 *
 * An assumption of that weight should not rest on someone re-reading the validator. It is checked
 * here against the real {@code android.net.Uri}, which is also the parser the property depends on.
 *
 * There is one edge the design does not mention and that this pins: the validator rejects any URL
 * containing '@', a backslash or whitespace ANYWHERE, not only in the authority — the host-confusion
 * defence from audit A33. Base64url capability values contain none of those, so the recommended
 * encoding is safe; a capability encoded some other way could silently become unroutable. Better to
 * find that here than in a rollout.
 */
@RunWith(AndroidJUnit4.class)
public class PushUrlCapabilityCompatTest
{
    private static final String BASE =
            "https://push.khandaq.org/toxfcm/fcm.php?id=fcm-registration-token-value";

    /** 32 random bytes, base64url, unpadded — exactly the encoding §3 of the design specifies. */
    private static final String CAP = "3q2-796tvu_erb7v3q2-796tvu_erb7v3q2-796tvu8";

    @Test
    public void theBaselineUrlIsAccepted()
    {
        assertTrue("the plain wake URL must be allowed, or this test proves nothing about cap",
                   PushUrlValidator.isAllowedPushUrl(BASE));
    }

    @Test
    public void anUnknownCapParameterDoesNotBreakValidation()
    {
        assertTrue("shipped clients must already accept a wake URL carrying cap= — the whole "
                   + "accept-before-emit sequencing depends on it",
                   PushUrlValidator.isAllowedPushUrl(BASE + "&cap=" + CAP));
    }

    @Test
    public void capBeforeIdIsAlsoAccepted()
    {
        assertTrue("parameter order must not matter",
                   PushUrlValidator.isAllowedPushUrl(
                           "https://push.khandaq.org/toxfcm/fcm.php?cap=" + CAP
                           + "&id=fcm-registration-token-value"));
    }

    @Test
    public void ownTokenValidationAcceptsCapToo()
    {
        assertTrue("the same URL travels through isAllowedOwnNotificationToken on the publish side",
                   PushUrlValidator.isAllowedOwnNotificationToken(BASE + "&cap=" + CAP));
    }

    @Test
    public void aCapDoesNotRescueADisallowedHost()
    {
        assertFalse("a capability is not an authorisation to talk to another host",
                    PushUrlValidator.isAllowedPushUrl(
                            "https://evil.example.com/toxfcm/fcm.php?id=fcm-registration-token-value"
                            + "&cap=" + CAP));
    }

    @Test
    public void aCapDoesNotRescueAMissingId()
    {
        assertFalse("cap must not become an accidental substitute for the token id",
                    PushUrlValidator.isAllowedPushUrl(
                            "https://push.khandaq.org/toxfcm/fcm.php?cap=" + CAP));
    }

    /**
     * The constraint the design has to know about: '@' anywhere in the URL is fatal, by design, so a
     * capability encoding that can emit one would produce URLs every shipped client refuses.
     */
    @Test
    public void aCapContainingAnAtSignIsRefusedEverywhere()
    {
        assertFalse("base64url is safe; an encoding that emits '@' is not — the A33 host-confusion "
                    + "check rejects the whole URL, not just the authority",
                    PushUrlValidator.isAllowedPushUrl(BASE + "&cap=abc@def"));
    }
}
