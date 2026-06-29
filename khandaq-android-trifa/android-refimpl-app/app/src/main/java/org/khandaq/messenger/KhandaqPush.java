package org.khandaq.messenger;

import org.khandaq.messenger.BuildConfig;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Khandaq 0.2.0 push relay constants (privacy-preserving wake URLs). */
public final class KhandaqPush {
    private KhandaqPush() {}

    public static final String RELAY_BASE = "https://push.khandaq.org";
    public static final String FCM_PUSH_URL_PREFIX = RELAY_BASE + "/toxfcm/fcm.php?id=";
    public static final String TOKEN_CHANGED_ACTION = "org.khandaq.messenger.TOKEN_CHANGED";
    /** Heads-up channel for FCM wake notifications (created in MainApplication). */
    public static final String FCM_WAKE_CHANNEL_ID = "khandaq_fcm_wake";
    /** Full-screen incoming audio/video call alerts. */
    public static final String INCOMING_CALL_CHANNEL_ID = "khandaq_incoming_call";

    /** Append sender pubkey and a replay-resistant relay auth to a push wake URL. */
    public static String withWakeParams(String pushUrl, String senderPubkeyHex)
    {
        if (pushUrl == null || pushUrl.isEmpty())
        {
            return pushUrl;
        }

        final StringBuilder sb = new StringBuilder(pushUrl);

        if (senderPubkeyHex != null && !senderPubkeyHex.isEmpty() && !pushUrl.contains("from="))
        {
            sb.append(pushUrl.contains("?") ? '&' : '?');
            sb.append("from=").append(senderPubkeyHex);
        }

        // KHANDAQ (security NEW-2): request-bound, time-limited auth instead of a constant HMAC.
        // The sender signs the recipient token (id) + sender (from) + a unix timestamp (ts), so the
        // value differs per request and expires — it cannot be replayed or reused for another push.
        // Dormant until a secret is provisioned (BuildConfig.PUSH_RELAY_AUTH_SECRET empty = no-op).
        final String url = sb.toString();
        final String secret = BuildConfig.PUSH_RELAY_AUTH_SECRET;
        if (secret != null && !secret.isEmpty() && url.contains("push.khandaq.org") && !url.contains("auth="))
        {
            final String id = queryValue(url, "id");
            final String from = queryValue(url, "from");
            final String ts = Long.toString(System.currentTimeMillis() / 1000L);
            // must match the server byte-for-byte: msg = id + "\n" + from + "\n" + ts
            final String auth = hmacSha256Hex(secret, id + "\n" + from + "\n" + ts);
            if (!auth.isEmpty())
            {
                sb.append('&').append("ts=").append(ts).append("&auth=").append(auth);
            }
        }

        return sb.toString();
    }

    /** Extract the URL-decoded value of a query parameter (to match the server's request.args view). */
    private static String queryValue(final String url, final String key)
    {
        try
        {
            final int q = url.indexOf('?');
            if (q < 0)
            {
                return "";
            }
            for (final String pair : url.substring(q + 1).split("&"))
            {
                final int eq = pair.indexOf('=');
                if (eq > 0 && pair.substring(0, eq).equals(key))
                {
                    return java.net.URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                }
            }
        }
        catch (Exception ignored)
        {
        }
        return "";
    }

    private static String hmacSha256Hex(final String secret, final String message)
    {
        try
        {
            final Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            final byte[] raw = mac.doFinal(message.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            final StringBuilder hex = new StringBuilder(raw.length * 2);
            for (byte b : raw)
            {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        }
        catch (Exception ignored)
        {
            return "";
        }
    }
}

