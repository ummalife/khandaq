package com.zoffcc.applications.trifa;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Ring buffer of network / reconnect events for debug screen and bug reports.
 */
public final class NetworkDiagnosticsLog
{
    private static final String TAG = "trifa.NetDiagLog";
    private static final int MAX_LINES = 500;

    private static final List<String> lines = new ArrayList<>();
    private static final Object lock = new Object();

    private NetworkDiagnosticsLog()
    {
    }

    static void log(final String event, final String detail)
    {
        final String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
        final String line = ts + " [" + event + "] " + (detail == null ? "" : detail);

        synchronized (lock)
        {
            lines.add(line);
            while (lines.size() > MAX_LINES)
            {
                lines.remove(0);
            }
        }

        Log.i(TAG, line);
    }

    static String snapshot()
    {
        synchronized (lock)
        {
            final StringBuilder sb = new StringBuilder();
            for (final String line : lines)
            {
                sb.append(line).append('\n');
            }
            return sb.toString();
        }
    }

    static void clear()
    {
        synchronized (lock)
        {
            lines.clear();
        }
    }

    static Intent createShareIntent(final Context context)
    {
        try
        {
            final File dir = new File(context.getCacheDir(), "network_diag");
            if (!dir.exists())
            {
                dir.mkdirs();
            }
            final File out = new File(dir, "khandaq-network-diag.txt");
            try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8))
            {
                w.write("Khandaq Network Diagnostics\n");
                w.write(snapshot());
                w.write("\n--- runtime ---\n");
                w.write(NetworkDiagnosticsCollector.collect(context));
            }

            final Uri uri = FileProvider.getUriForFile(context,
                    context.getPackageName() + ".fileprovider", out);
            final Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.putExtra(Intent.EXTRA_SUBJECT, "Khandaq network diagnostics");
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            return share;
        }
        catch (Exception e)
        {
            Log.e(TAG, "createShareIntent:EE:" + e.getMessage());
            return null;
        }
    }
}
