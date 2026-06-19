package com.zoffcc.applications.trifa;

import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import org.khandaq.messenger.R;

import static com.zoffcc.applications.trifa.MainActivity.main_activity_s;

import static com.zoffcc.applications.trifa.TRIFAGlobals.bootstrapping;
import static com.zoffcc.applications.trifa.TRIFAGlobals.global_self_connection_status;
import static com.zoffcc.applications.trifa.TrifaToxService.is_tox_started;
import static com.zoffcc.applications.trifa.TrifaToxService.manually_logged_out;
import static com.zoffcc.applications.trifa.ToxVars.TOX_CONNECTION.TOX_CONNECTION_NONE;

/**
 * Main screen toolbar: connection quality dot + title; tap dot to reconnect.
 */
final class MainHeaderBrandingHelper
{
    private static View headerRoot;
    private static View connectionDot;
    private static ProgressBar connectingSpinner;
    private static TextView titleView;
    private static boolean setupDone;

    // KHANDAQ: once connected, the green dot flashes briefly and then disappears (like iOS) instead of
    // sitting permanently next to the "Khandaq" title.
    private static final long CONNECTED_DOT_VISIBLE_MS = 1600L;
    // How often to re-warn the user while the link stays weak/unstable.
    private static final long WEAK_NAG_INTERVAL_MS = 30000L;
    private static final Handler uiHandler = new Handler(Looper.getMainLooper());
    private static final Runnable hideDotRunnable = () -> {
        if (connectionDot != null)
        {
            connectionDot.setVisibility(View.GONE);
        }
    };
    private static final Runnable weakNagRunnable = new Runnable()
    {
        @Override
        public void run()
        {
            final ConnectionQualityMonitor.Level level = ConnectionQualityMonitor.get().getLevel();
            if (level == ConnectionQualityMonitor.Level.WEAK || level == ConnectionQualityMonitor.Level.MEDIUM)
            {
                showQualityToast(R.string.connection_warn_weak);
                uiHandler.postDelayed(this, WEAK_NAG_INTERVAL_MS);
            }
        }
    };

    private MainHeaderBrandingHelper()
    {
    }

    static void setup(final AppCompatActivity activity, final Toolbar toolbar)
    {
        if (activity == null || toolbar == null || setupDone)
        {
            return;
        }

        final ActionBar actionBar = activity.getSupportActionBar();
        if (actionBar == null)
        {
            return;
        }

        actionBar.setDisplayShowTitleEnabled(false);
        actionBar.setDisplayShowCustomEnabled(true);

        final View custom = activity.getLayoutInflater().inflate(R.layout.main_toolbar_branding, toolbar, false);
        final ActionBar.LayoutParams lp = new ActionBar.LayoutParams(
                ActionBar.LayoutParams.WRAP_CONTENT,
                ActionBar.LayoutParams.MATCH_PARENT,
                Gravity.START | Gravity.CENTER_VERTICAL);
        actionBar.setCustomView(custom, lp);

        headerRoot = custom.findViewById(R.id.main_header_root);
        connectionDot = custom.findViewById(R.id.main_header_connection_dot);
        connectingSpinner = custom.findViewById(R.id.main_header_connecting_spinner);
        titleView = custom.findViewById(R.id.main_header_title);

        if (connectionDot != null)
        {
            connectionDot.setOnClickListener(v -> onConnectionDotClicked(activity));
        }

        // Warn the user about an unstable link (yellow, periodic) and confirm recovery once (green).
        ConnectionQualityMonitor.get().setLevelListener(MainHeaderBrandingHelper::onQualityLevelChanged);

        setupDone = true;
        update(activity);
    }

    // Called (possibly off the UI thread) when the connection-quality level changes.
    private static void onQualityLevelChanged(final ConnectionQualityMonitor.Level oldLevel,
                                              final ConnectionQualityMonitor.Level newLevel)
    {
        uiHandler.post(() -> {
            updateAsync();

            switch (newLevel)
            {
                case WEAK:
                case MEDIUM:
                    showQualityToast(R.string.connection_warn_weak);
                    startWeakNag();
                    break;
                case STRONG:
                    stopWeakNag();
                    if (oldLevel != ConnectionQualityMonitor.Level.STRONG)
                    {
                        showQualityToast(R.string.connection_good);
                    }
                    break;
                case OFFLINE:
                default:
                    stopWeakNag();
                    break;
            }
        });
    }

    private static void showQualityToast(final int textRes)
    {
        final MainActivity activity = main_activity_s;
        if (activity == null || activity.isFinishing())
        {
            return;
        }
        Toast.makeText(activity, textRes, Toast.LENGTH_SHORT).show();
    }

    private static void startWeakNag()
    {
        uiHandler.removeCallbacks(weakNagRunnable);
        uiHandler.postDelayed(weakNagRunnable, WEAK_NAG_INTERVAL_MS);
    }

    private static void stopWeakNag()
    {
        uiHandler.removeCallbacks(weakNagRunnable);
    }

    private static void onConnectionDotClicked(final AppCompatActivity activity)
    {
        if (!is_tox_started || manually_logged_out)
        {
            return;
        }

        Toast.makeText(activity, R.string.connection_reconnect_toast, Toast.LENGTH_SHORT).show();
        TrifaToxService.requestManualReconnect();
        update(activity);
    }

    static void updateAsync()
    {
        final MainActivity activity = main_activity_s;
        if (activity == null || activity.isFinishing())
        {
            return;
        }

        activity.runOnUiThread(() -> update(activity));
    }

    static void update(@Nullable final AppCompatActivity activity)
    {
        if (titleView == null || activity == null)
        {
            return;
        }

        final boolean connected = isNetworkBrandedOnline();
        final ConnectionQualityMonitor.Level quality = ConnectionQualityMonitor.get().getLevel();
        final boolean noNetwork = !connected && quality == ConnectionQualityMonitor.Level.OFFLINE;

        uiHandler.removeCallbacks(hideDotRunnable);

        if (connected)
        {
            // Online: flash a green dot briefly, then hide it (no permanent indicator). Weak/medium
            // signal keeps a steady yellow dot since that's worth surfacing.
            titleView.setText(R.string.app_name);
            if (connectingSpinner != null)
            {
                connectingSpinner.setVisibility(View.GONE);
            }

            if (connectionDot != null)
            {
                connectionDot.setVisibility(View.VISIBLE);
                if (quality == ConnectionQualityMonitor.Level.WEAK)
                {
                    connectionDot.setBackgroundResource(R.drawable.circle_yellow);
                    connectionDot.setContentDescription(activity.getString(R.string.connection_quality_weak));
                }
                else if (quality == ConnectionQualityMonitor.Level.MEDIUM)
                {
                    connectionDot.setBackgroundResource(R.drawable.circle_yellow);
                    connectionDot.setContentDescription(activity.getString(R.string.connection_quality_medium));
                }
                else
                {
                    connectionDot.setBackgroundResource(R.drawable.circle_green);
                    connectionDot.setContentDescription(activity.getString(R.string.connection_quality_strong));
                    uiHandler.postDelayed(hideDotRunnable, CONNECTED_DOT_VISIBLE_MS);
                }
            }
        }
        else if (noNetwork)
        {
            // No network: steady red dot, no spinner.
            titleView.setText(R.string.app_name);
            if (connectingSpinner != null)
            {
                connectingSpinner.setVisibility(View.GONE);
            }
            if (connectionDot != null)
            {
                connectionDot.setVisibility(View.VISIBLE);
                connectionDot.setBackgroundResource(R.drawable.circle_red);
                connectionDot.setContentDescription(activity.getString(R.string.connection_quality_offline));
            }
        }
        else
        {
            // Connecting: spinner + "Connecting…", no dot.
            titleView.setText(R.string.main_header_connecting);
            if (connectingSpinner != null)
            {
                connectingSpinner.setVisibility(View.VISIBLE);
            }
            if (connectionDot != null)
            {
                connectionDot.setVisibility(View.GONE);
            }
        }
    }

    static boolean isNetworkBrandedOnline()
    {
        return is_tox_started && !bootstrapping && !manually_logged_out
                && global_self_connection_status != TOX_CONNECTION_NONE.value;
    }
}
