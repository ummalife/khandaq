package com.zoffcc.applications.trifa;

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

        setupDone = true;
        update(activity);
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

        if (connectionDot != null)
        {
            connectionDot.setVisibility(View.VISIBLE);
            if (!connected || quality == ConnectionQualityMonitor.Level.OFFLINE)
            {
                connectionDot.setBackgroundResource(R.drawable.circle_red);
                connectionDot.setContentDescription(activity.getString(R.string.connection_quality_offline));
            }
            else if (quality == ConnectionQualityMonitor.Level.WEAK)
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
            }
        }

        if (connected)
        {
            titleView.setText(R.string.app_name);
            if (connectingSpinner != null)
            {
                connectingSpinner.setVisibility(View.GONE);
            }
        }
        else
        {
            titleView.setText(R.string.main_header_connecting);
            if (connectingSpinner != null)
            {
                connectingSpinner.setVisibility(View.VISIBLE);
            }
        }
    }

    static boolean isNetworkBrandedOnline()
    {
        return is_tox_started && !bootstrapping && !manually_logged_out
                && global_self_connection_status != TOX_CONNECTION_NONE.value;
    }
}
