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

    /**
     * How to emit one wake, decided from the recipient's push URL.
     *
     * KHANDAQ (re-audit 2026-08-22, K-03). The FCM registration token is a targeting secret — hold
     * it and you can push to that device — and it used to travel in the request URI, next to the
     * HMAC and its timestamp. nginx redacts the query string, but a URL leaks through more than one
     * log: client diagnostics, crash reporters, an intermediary proxy, a copied link, a screenshot.
     * A request BODY leaks through none of those by default.
     *
     * So for our own relay the token, the sender and the capability move into a JSON body and the
     * authentication into headers. Anything else — the legacy tox.zoff.xyz relay, a URL we do not
     * recognise — keeps the old form exactly, because a push URL we do not own is not ours to
     * reinterpret.
     */
    public static final class WakeRequest
    {
        /** Absolute URL to call. */
        public final String url;
        /** JSON body, or null when this is the legacy form-post shape. */
        public final String jsonBody;
        /** Bearer value for Authorization, or null when no secret is provisioned. */
        public final String auth;
        /** Value for X-Khandaq-Ts, or null. */
        public final String ts;

        WakeRequest(String url, String jsonBody, String auth, String ts)
        {
            this.url = url;
            this.jsonBody = jsonBody;
            this.auth = auth;
            this.ts = ts;
        }

        public boolean isJson()
        {
            return jsonBody != null;
        }
    }

    /** True when this URL is our own relay's wake endpoint, and therefore ours to modernise. */
    public static boolean isOwnRelayWakeUrl(final String pushUrl)
    {
        return pushUrl != null
               && pushUrl.startsWith(RELAY_BASE + "/toxfcm/fcm.php")
               && !queryValue(pushUrl, "id").isEmpty();
    }

    public static WakeRequest buildWakeRequest(final String pushUrl, final String senderPubkeyHex)
    {
        if (!isOwnRelayWakeUrl(pushUrl))
        {
            // Unchanged behaviour for every URL that is not ours.
            return new WakeRequest(withWakeParams(pushUrl, senderPubkeyHex), null, null, null);
        }

        final String id = queryValue(pushUrl, "id");
        final String from = (senderPubkeyHex == null) ? "" : senderPubkeyHex;
        // The capability the RECIPIENT minted for us and published inside its wake URL. We carry it
        // across verbatim; we never mint or guess one for somebody else's device.
        final String cap = queryValue(pushUrl, "cap");

        final StringBuilder body = new StringBuilder(128);
        body.append("{\"token\":").append(jsonString(id));
        body.append(",\"sender\":").append(jsonString(from));
        if (!cap.isEmpty())
        {
            body.append(",\"cap\":").append(jsonString(cap));
        }
        body.append('}');

        String auth = null;
        String ts = null;
        final String secret = BuildConfig.PUSH_RELAY_AUTH_SECRET;
        if (secret != null && !secret.isEmpty())
        {
            ts = Long.toString(System.currentTimeMillis() / 1000L);
            // Byte-for-byte the pre-image the legacy path signs: id + "\n" + from + "\n" + ts.
            // Moving the request shape must not move the signature, or a client that switches
            // endpoints looks to the relay like a client with the wrong secret.
            final String mac = hmacSha256Hex(secret, id + "\n" + from + "\n" + ts);
            if (!mac.isEmpty())
            {
                auth = "Bearer " + mac;
            }
            else
            {
                ts = null;
            }
        }
        return new WakeRequest(RELAY_BASE + "/wake", body.toString(), auth, ts);
    }

    /** Minimal JSON string escaping. The values here are base64url/hex, but assuming that is how a
     *  quote ends up unescaped in a body the relay then fails to parse. */
    public static String jsonString(final String s)
    {
        final StringBuilder out = new StringBuilder(s.length() + 2);
        out.append('"');
        for (int i = 0; i < s.length(); i++)
        {
            final char c = s.charAt(i);
            switch (c)
            {
                case '"':  out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20)
                    {
                        out.append(String.format("\\u%04x", (int) c));
                    }
                    else
                    {
                        out.append(c);
                    }
            }
        }
        out.append('"');
        return out.toString();
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

