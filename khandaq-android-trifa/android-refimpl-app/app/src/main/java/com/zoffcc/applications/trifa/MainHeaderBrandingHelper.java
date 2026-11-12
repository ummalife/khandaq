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
 * Main screen toolbar: the title of the screen under it (Settings, You). KHANDAQ (redesign, E4): the
 * connection dot and spinner it carried stay hidden; the state is said in words elsewhere.
 */
final class MainHeaderBrandingHelper
{
    private static View headerRoot;
    private static View connectionDot;
    private static ProgressBar connectingSpinner;
    private static TextView titleView;
    private static boolean setupDone;

    private static final Handler uiHandler = new Handler(Looper.getMainLooper());
    private static final Runnable hideDotRunnable = () -> {
        if (connectionDot != null)
        {
            connectionDot.setVisibility(View.GONE);
        }
    };

    private MainHeaderBrandingHelper()
    {
    }

    static void setup(final AppCompatActivity activity, final Toolbar toolbar)
    {
        // NOTE: must re-bind the custom view on every setup (incl. config-change recreate, e.g. theme
        // toggle): the static view refs otherwise point at the destroyed activity and the new toolbar
        // keeps its XML default title. The quality listener below is idempotent (setLevelListener).
        if (activity == null || toolbar == null)
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

        // KHANDAQ (redesign, E4): a change of quality redraws the header; it no longer raises a toast (neither
        // "Connection stable" nor the warning every 30 s). The line under the name on the Air screen says the
        // state in words and opens the Network screen.
        ConnectionQualityMonitor.get().setLevelListener(MainHeaderBrandingHelper::onQualityLevelChanged);

        setupDone = true;
        update(activity);
    }

    // Called (possibly off the UI thread) when the connection-quality level changes.
    private static void onQualityLevelChanged(final ConnectionQualityMonitor.Level oldLevel,
                                              final ConnectionQualityMonitor.Level newLevel)
    {
        uiHandler.post(MainHeaderBrandingHelper::updateAsync);
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

    // KHANDAQ: the header title shows the current bottom-nav tab name (Chats, Contacts and the rest), matching
    // Figma.
    private static int headerTitleRes = R.string.app_name;

    static void setTitle(@Nullable final AppCompatActivity activity, final int resId)
    {
        headerTitleRes = resId;
        update(activity);
    }

    static void update(@Nullable final AppCompatActivity activity)
    {
        if (titleView == null || activity == null)
        {
            return;
        }

        titleView.setText(headerTitleRes);

        // KHANDAQ (redesign, E4): the header over Settings and You carries no dot and no spinner. The
        // spinner turned without end while connecting, which the design does not allow, and the dot
        // flashed; the line under your name on the Air screen, the Network screen and the Connection row
        // in Settings say the state in words (design/DESIGN.md, "Network state on the phone").
        uiHandler.removeCallbacks(hideDotRunnable);
        if (connectingSpinner != null)
        {
            connectingSpinner.setVisibility(View.GONE);
        }
        if (connectionDot != null)
        {
            connectionDot.setVisibility(View.GONE);
        }
    }

    static boolean isNetworkBrandedOnline()
    {
        return is_tox_started && !bootstrapping && !manually_logged_out
                && global_self_connection_status != TOX_CONNECTION_NONE.value;
    }
}
