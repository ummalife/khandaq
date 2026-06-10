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

    /** Append sender pubkey and optional relay auth to a push wake URL. */
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

        final String auth = relayAuthParam();
        if (!auth.isEmpty() && pushUrl.contains("push.khandaq.org"))
        {
            sb.append(sb.indexOf("?") >= 0 ? '&' : '?');
            sb.append("auth=").append(auth);
        }

        return sb.toString();
    }

    public static String relayAuthParam()
    {
        final String secret = BuildConfig.PUSH_RELAY_AUTH_SECRET;
        if (secret == null || secret.isEmpty())
        {
            return "";
        }

        try
        {
            final Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            final byte[] raw = mac.doFinal("khandaq-push-relay".getBytes(java.nio.charset.StandardCharsets.UTF_8));
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

