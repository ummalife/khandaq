package com.zoffcc.applications.trifa;

import java.util.regex.Pattern;

/** Validates bootstrap node host/IP strings before DB insert (#12 partial). */
public final class BootstrapHostValidator
{
    private static final Pattern HOST = Pattern.compile(
            "^(?:[0-9]{1,3}(?:\\.[0-9]{1,3}){3}|"
                    + "(?:[0-9a-fA-F]{0,4}:){2,7}[0-9a-fA-F]{0,4}|"
                    + "[a-zA-Z0-9](?:[a-zA-Z0-9\\-]{0,61}[a-zA-Z0-9])?(?:\\.[a-zA-Z0-9](?:[a-zA-Z0-9\\-]{0,61}[a-zA-Z0-9])?)*)$");

    private BootstrapHostValidator()
    {
    }

    static boolean isValidHost(final String host)
    {
        return host != null && !host.isEmpty() && !host.startsWith("-") && HOST.matcher(host).matches();
    }

    static boolean isValidBootstrapEntry(final String ip, final long port, final String keyHex)
    {
        return isValidHost(ip) && port > 0 && port <= 65535 && isValidPublicKeyHex(keyHex);
    }

    static boolean isValidPublicKeyHex(final String key)
    {
        return key != null && key.length() == 64 && key.matches("[0-9A-Fa-f]{64}");
    }
}
