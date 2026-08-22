package com.zoffcc.applications.trifa;

import android.util.Log;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;

import static com.zoffcc.applications.trifa.HelperGeneric.del_g_opts;
import static com.zoffcc.applications.trifa.HelperGeneric.get_g_opts;
import static com.zoffcc.applications.trifa.HelperGeneric.set_g_opts;

/**
 * KHANDAQ (re-audit 2026-08-22, K-01) — per-contact push capabilities, device half.
 *
 * <p>The finding: one HMAC secret is baked into every published binary, so anyone who unpacks an APK
 * holds a credential that signs a wake for any device whose targeting token they also have. Rotating
 * it changes nothing — the replacement is baked in just as publicly. What has to change is that the
 * secret authorising a wake becomes specific to one relationship instead of shared by the fleet.
 *
 * <p>The shape is {@code DESIGN-push-per-install-capabilities.md} §3.1. This device mints 32 random
 * bytes per contact, registers their SHA-256 with the relay, and publishes the capability inside the
 * wake URL it already sends that contact over Tox — an authenticated, end-to-end encrypted channel
 * we already have. The relay then wakes this device only for a request that carries a capability it
 * registered. Leaking one capability is attributable to one contact and revocable for that contact
 * alone, without shipping an app.
 *
 * <p><b>Why registration is safe.</b> The design blocked here for a reason: every contact knows this
 * device's FCM token, because it is in the wake URL they were handed, so "prove you know the token"
 * proves nothing and a forged registration would end this device's notifications. The relay
 * therefore mints a nonce and pushes it, data-only, TO THE TOKEN. Only the device that actually
 * holds that FCM registration receives it. A contact can start a challenge and can never finish one.
 *
 * <p><b>Why nothing breaks during the rollout.</b> A contact still running an older build sends the
 * wake URL it was given, verbatim, and appends its own parameters — so a capability published in
 * that URL travels to the relay with no change on the sender's side at all
 * ({@code PushUrlCapabilityCompatTest} pins exactly this). And the relay does not enforce for a
 * device until a fortnight after its first registration, which covers the contact who was offline
 * when the new URL went out.
 */
public final class KhandaqPushCapability
{
    private static final String TAG = "trifa.PushCap";

    /** One capability per contact, keyed by their Tox public key. */
    private static final String CAP_KEY_PREFIX = "khandaq_pushcap_";
    /** The FCM token the stored capabilities were registered against. */
    private static final String CAP_TOKEN_KEY = "khandaq_pushcap_token";
    /** The challenge this device is currently waiting on a push for. */
    private static final String PENDING_CID_KEY = "khandaq_pushcap_pending_cid";
    private static final String PENDING_CAP_KEY = "khandaq_pushcap_pending_cap";

    private static final String CHALLENGE_URL = org.khandaq.messenger.KhandaqPush.RELAY_BASE + "/register/challenge";
    private static final String CONFIRM_URL = org.khandaq.messenger.KhandaqPush.RELAY_BASE + "/register/confirm";
    private static final String REVOKE_URL = org.khandaq.messenger.KhandaqPush.RELAY_BASE + "/register/revoke";

    private static final SecureRandom RNG = new SecureRandom();

    /** When registration for a contact was last attempted. A relay that is down, or a device whose
     *  notifications are switched off at the OS level, must not turn every publish into a 30-second
     *  thread and a failed HTTP round trip. */
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> LAST_ATTEMPT =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final long RETRY_COOLDOWN_MS = 15 * 60 * 1000L;

    private KhandaqPushCapability() {}

    // ------------------------------------------------------------------ minting and storage

    /** 32 bytes of CSPRNG output, base64url without padding — the encoding the shipped URL
     *  validators are known to accept (an '@' or '=' anywhere makes a URL unroutable, see A33). */
    static String mint()
    {
        final byte[] raw = new byte[32];
        RNG.nextBytes(raw);
        return android.util.Base64.encodeToString(
                raw, android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING | android.util.Base64.NO_WRAP);
    }

    private static String capKey(final String friendPubkey)
    {
        return CAP_KEY_PREFIX + friendPubkey.toUpperCase(Locale.ROOT);
    }

    /**
     * The capability this device has issued to one contact, minting one on first use.
     *
     * <p>Returns null when there is nothing to issue against — no FCM token, or the capability set
     * belongs to a token that has since rotated and has not been re-registered yet. Null means
     * "publish the URL without a capability", which is the pre-capability behaviour and is always
     * safe: the relay does not require what it has not been given.
     */
    static String capabilityFor(final String friendPubkey, final String ownFcmToken)
    {
        if (friendPubkey == null || friendPubkey.isEmpty() || ownFcmToken == null || ownFcmToken.isEmpty())
        {
            return null;
        }
        try
        {
            final String registeredFor = get_g_opts(CAP_TOKEN_KEY);
            if (registeredFor == null || !registeredFor.equals(ownFcmToken))
            {
                // The FCM token rotated. Every capability was registered against the old one and can
                // never be used again, so they are dropped rather than published; a fresh set is
                // registered against the new token. Publishing a stale capability would be worse
                // than publishing none — the relay would see a capability it does not know.
                return null;
            }
            final String existing = get_g_opts(capKey(friendPubkey));
            if (existing != null && !existing.isEmpty())
            {
                return existing;
            }
        }
        catch (Exception e)
        {
            Log.i(TAG, "capabilityFor:EE:" + e.getMessage());
        }
        return null;
    }

    /**
     * Issue and register a capability for one contact, blocking on the network.
     *
     * <p>Call from a background thread. Returns the capability on success and null on any failure —
     * and a failure is not an error condition for the caller: the URL is then published without a
     * capability, exactly as before this feature existed.
     */
    static String issueFor(final String friendPubkey, final String ownFcmToken)
    {
        final String existing = capabilityFor(friendPubkey, ownFcmToken);
        if (existing != null)
        {
            return existing;
        }
        final Long last = LAST_ATTEMPT.get(friendPubkey);
        final long now = System.currentTimeMillis();
        if (last != null && (now - last) < RETRY_COOLDOWN_MS)
        {
            return null;
        }
        LAST_ATTEMPT.put(friendPubkey, now);

        final String cap = mint();
        if (!register(ownFcmToken, cap))
        {
            return null;
        }
        LAST_ATTEMPT.remove(friendPubkey);
        try
        {
            set_g_opts(capKey(friendPubkey), cap);
            set_g_opts(CAP_TOKEN_KEY, ownFcmToken);
        }
        catch (Exception e)
        {
            Log.i(TAG, "issueFor:store:EE:" + e.getMessage());
            return null;
        }
        return cap;
    }

    /**
     * Stop honouring one contact's capability.
     *
     * KHANDAQ (re-review follow-up 2026-08-22): this needs the same device proof as registering.
     * Presenting the capability used to be enough — right about ACCESS, wrong about the DEVICE:
     * revoking the LAST capability returns the token to the state where none is required, so a
     * contact holding the only one could hand back its own access and silently return this device to
     * being wakeable by anyone who knows the token. The relay refuses a revoke without a challenge
     * now; this walks the same challenge/confirm the registration does.
     *
     * Blocking on the network. Call from a background thread.
     */
    static boolean revokeFor(final String friendPubkey, final String ownFcmToken)
    {
        try
        {
            final String cap = get_g_opts(capKey(friendPubkey));
            if (cap == null || cap.isEmpty())
            {
                return true;
            }
            final String[] proof = challenge(ownFcmToken);
            if (proof == null)
            {
                // Without the proof the relay will refuse, and dropping the local copy would leave a
                // capability the relay still honours and this device can no longer name.
                Log.i(TAG, "revokeFor: no challenge proof, keeping the capability");
                return false;
            }
            final String body = "{\"cid\":" + org.khandaq.messenger.KhandaqPush.jsonString(proof[0])
                    + ",\"nonce\":" + org.khandaq.messenger.KhandaqPush.jsonString(proof[1])
                    + ",\"cap\":" + org.khandaq.messenger.KhandaqPush.jsonString(cap) + "}";
            final int code = postJson(REVOKE_URL, body);
            if (code == 200)
            {
                del_g_opts(capKey(friendPubkey));
            }
            return code == 200;
        }
        catch (Exception e)
        {
            Log.i(TAG, "revokeFor:EE:" + e.getMessage());
            return false;
        }
    }

    /** Ask for a challenge and wait for the data push carrying its nonce. Returns {cid, nonce}. */
    private static String[] challenge(final String ownFcmToken)
    {
        try
        {
            final String[] response = new String[1];
            final int code = postJson(CHALLENGE_URL,
                    "{\"token\":" + org.khandaq.messenger.KhandaqPush.jsonString(ownFcmToken) + "}",
                    response);
            if (code != 200 || response[0] == null)
            {
                return null;
            }
            final String cid = jsonValue(response[0], "cid");
            if (cid.isEmpty())
            {
                return null;
            }
            set_g_opts(PENDING_CID_KEY, cid);
            for (int waited = 0; waited < 30_000; waited += 250)
            {
                final String nonce = get_g_opts(nonceKey(cid));
                if (nonce != null && !nonce.isEmpty())
                {
                    del_g_opts(nonceKey(cid));
                    del_g_opts(PENDING_CID_KEY);
                    return new String[]{cid, nonce};
                }
                Thread.sleep(250);
            }
            del_g_opts(PENDING_CID_KEY);
            return null;
        }
        catch (Exception e)
        {
            Log.i(TAG, "challenge:EE:" + e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------ the registration handshake

    /**
     * challenge → wait for the relay's data push → confirm.
     *
     * <p>The wait is what makes this safe, and it is why this cannot be a single call: the nonce
     * only ever reaches the device that owns the FCM token.
     */
    private static boolean register(final String ownFcmToken, final String cap)
    {
        try
        {
            final String body = "{\"token\":" + org.khandaq.messenger.KhandaqPush.jsonString(ownFcmToken) + "}";
            final String[] response = new String[1];
            final int code = postJson(CHALLENGE_URL, body, response);
            if (code != 200 || response[0] == null)
            {
                Log.i(TAG, "register:challenge failed http=" + code);
                return false;
            }
            final String cid = jsonValue(response[0], "cid");
            if (cid.isEmpty())
            {
                return false;
            }
            set_g_opts(PENDING_CID_KEY, cid);
            set_g_opts(PENDING_CAP_KEY, cap);

            // The nonce arrives asynchronously through FCM (onRegistrationNonce below). Wait a
            // bounded time for it rather than forever: a device with notifications disabled at the
            // OS level will never receive one, and that must degrade to "no capability published",
            // not to a hung thread.
            for (int waited = 0; waited < 30_000; waited += 250)
            {
                final String nonce = get_g_opts(nonceKey(cid));
                if (nonce != null && !nonce.isEmpty())
                {
                    del_g_opts(nonceKey(cid));
                    del_g_opts(PENDING_CID_KEY);
                    del_g_opts(PENDING_CAP_KEY);
                    final String confirm = "{\"cid\":" + org.khandaq.messenger.KhandaqPush.jsonString(cid)
                                           + ",\"nonce\":" + org.khandaq.messenger.KhandaqPush.jsonString(nonce)
                                           + ",\"cap\":" + org.khandaq.messenger.KhandaqPush.jsonString(cap) + "}";
                    final int ok = postJson(CONFIRM_URL, confirm);
                    Log.i(TAG, "register:confirm http=" + ok);
                    return ok == 200;
                }
                Thread.sleep(250);
            }
            Log.i(TAG, "register: no challenge push arrived within 30s");
            del_g_opts(PENDING_CID_KEY);
            del_g_opts(PENDING_CAP_KEY);
            return false;
        }
        catch (Exception e)
        {
            Log.i(TAG, "register:EE:" + e.getMessage());
            return false;
        }
    }

    private static String nonceKey(final String cid)
    {
        return "khandaq_pushcap_nonce_" + cid;
    }

    /**
     * Called from the FCM service when a registration challenge arrives.
     *
     * <p>Deliberately does nothing but hand the nonce to the waiting thread. The push is data-only
     * and must stay invisible: a device that says "New message" because it registered a capability
     * has turned a security improvement into a bug report.
     */
    public static void onRegistrationNonce(final String cid, final String nonce)
    {
        if (cid == null || nonce == null || cid.isEmpty() || nonce.isEmpty())
        {
            return;
        }
        try
        {
            final String pending = get_g_opts(PENDING_CID_KEY);
            if (pending == null || !pending.equals(cid))
            {
                // A challenge nobody asked for. Ignore it rather than storing it: the only party
                // who can cause one is somebody who knows this token, and there is nothing here for
                // them to gain, but there is no reason to keep their nonce either.
                Log.i(TAG, "onRegistrationNonce: unsolicited challenge ignored");
                return;
            }
            set_g_opts(nonceKey(cid), nonce);
        }
        catch (Exception e)
        {
            Log.i(TAG, "onRegistrationNonce:EE:" + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ plumbing

    static int postJson(final String url, final String body)
    {
        return postJson(url, body, null);
    }

    private static int postJson(final String url, final String body, final String[] out)
    {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream os = conn.getOutputStream())
            {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            final int code = conn.getResponseCode();
            if (out != null && code == 200)
            {
                final java.io.InputStream is = conn.getInputStream();
                final java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                final byte[] chunk = new byte[1024];
                int n;
                // Bounded: the relay's answers are tens of bytes, and an unbounded read of a
                // response we do not control is how a device runs out of memory.
                while ((n = is.read(chunk)) > 0 && buf.size() < 8192)
                {
                    buf.write(chunk, 0, n);
                }
                out[0] = new String(buf.toByteArray(), StandardCharsets.UTF_8);
            }
            return code;
        }
        catch (Exception e)
        {
            Log.i(TAG, "postJson:EE:" + e.getMessage());
            return -1;
        }
        finally
        {
            if (conn != null)
            {
                conn.disconnect();
            }
        }
    }

    /** Pull one string value out of a small, known-shape JSON object. */
    static String jsonValue(final String json, final String key)
    {
        try
        {
            return new org.json.JSONObject(json).optString(key, "");
        }
        catch (Exception ignored)
        {
            return "";
        }
    }
}
