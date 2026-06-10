package com.zoffcc.applications.trifa;

import android.net.Uri;
import android.text.TextUtils;

import org.khandaq.messenger.KhandaqPush;

import java.util.regex.Pattern;

/**
 * Strict push URL / FCM token validation (security audit fix).
 */
public final class PushUrlValidator
{
    /** Raw FCM registration token (not a full URL). */
    private static final Pattern FCM_RAW_TOKEN = Pattern.compile("^[A-Za-z0-9_\\-:.]{20,4096}$");

    private PushUrlValidator()
    {
    }

    static boolean isAllowedOwnNotificationToken(final String token)
    {
        if (TextUtils.isEmpty(token) || token.length() < 10)
        {
            return false;
        }

        if (token.startsWith("https://"))
        {
            return isAllowedPushUrl(token);
        }

        return FCM_RAW_TOKEN.matcher(token).matches();
    }

    static boolean isAllowedPushUrl(final String pushUrl)
    {
        if (TextUtils.isEmpty(pushUrl) || pushUrl.length() < 12)
        {
            return false;
        }

        if (!pushUrl.startsWith("https://"))
        {
            return false;
        }

        try
        {
            final Uri uri = Uri.parse(pushUrl);
            final String host = uri.getHost();
            final String path = uri.getPath();

            if (host == null || path == null)
            {
                return false;
            }

            // Khandaq relay — exact path only
            if ("push.khandaq.org".equalsIgnoreCase(host))
            {
                return "/toxfcm/fcm.php".equals(path) && hasNonEmptyQueryId(uri);
            }

            // Legacy migration relay
            if ("tox.zoff.xyz".equalsIgnoreCase(host))
            {
                return "/toxfcm/fcm.php".equals(path) && hasNonEmptyQueryId(uri);
            }

            // Old upstream (read-only migration)
            if ("toxcon2020.zoff.cc".equalsIgnoreCase(host))
            {
                return "/toxfcm/fcm.php".equals(path) && hasNonEmptyQueryId(uri);
            }

            // Optional third-party push — only when user explicitly enabled in settings
            if (MainActivity.PREF__allow_push_server_ntfy && "ntfy.sh".equalsIgnoreCase(host))
            {
                final String topic = path.startsWith("/") ? path.substring(1) : path;
                return topic.matches("^[A-Za-z0-9_\\-]{1,64}$");
            }

            if (MainActivity.PREF__allow_push_server_sunup && host.endsWith(".mozilla.com"))
            {
                return path.length() > 1;
            }

            if (MainActivity.PREF__allow_push_server_ntfy && host.endsWith("unifiedpush.org"))
            {
                return path.startsWith("/UP");
            }
        }
        catch (Exception ignored)
        {
        }

        return false;
    }

    static String normalizeOwnToken(final String token)
    {
        if (token == null)
        {
            return null;
        }

        String t = token.trim();
        if (t.endsWith("?up=1"))
        {
            t = t.substring(0, t.length() - "?up=1".length());
        }

        if (t.startsWith("https://"))
        {
            return isAllowedOwnNotificationToken(t) ? t : null;
        }

        if (FCM_RAW_TOKEN.matcher(t).matches())
        {
            return t;
        }

        return null;
    }

    static String toPushUrl(final String token)
    {
        if (token == null)
        {
            return null;
        }

        if (token.startsWith("https://"))
        {
            return isAllowedPushUrl(token) ? token : null;
        }

        if (FCM_RAW_TOKEN.matcher(token).matches())
        {
            return KhandaqPush.FCM_PUSH_URL_PREFIX + token;
        }

        return null;
    }

    private static boolean hasNonEmptyQueryId(final Uri uri)
    {
        final String id = uri.getQueryParameter("id");
        return id != null && id.length() >= 10 && id.length() <= 4096;
    }
}
