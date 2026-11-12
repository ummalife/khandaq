package com.zoffcc.applications.trifa;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;

import org.khandaq.messenger.R;

/**
 * KHANDAQ (redesign, E4): the state of our own node in words, as AirNetworkController on iOS, opened from
 * the quiet line under the name on the Air screen. What the state means for messages, whether the phone
 * has internet and how the link goes, and a way to connect again. No addresses and no probe codes; the
 * technical log is one row further, for support. The screen follows the state while it is open.
 */
public final class AirNetworkActivity extends AirScreenActivity
{
    /** How often the open screen reads the node again: the state has no broadcast of its own. */
    static final long POLL_MS = 1500L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable poll = new Runnable()
    {
        @Override
        public void run()
        {
            refresh(true);
            handler.postDelayed(this, POLL_MS);
        }
    };

    private View dot;
    private TextView title;
    private TextView meaning;
    private AirSettingRow internetRow;
    private AirSettingRow linkRow;
    private View reconnectSection;
    @Nullable
    private AirModel.NetworkState shown;

    static void open(final Context context)
    {
        context.startActivity(new Intent(context, AirNetworkActivity.class));
    }

    @Override
    protected int screenTitle()
    {
        return R.string.air_settings_network;
    }

    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        final Resources res = getResources();

        // The dot keeps to the first line of the title, which may wrap at large text sizes.
        final LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        dot = new View(this);
        dot.setBackgroundResource(R.drawable.air_bg_dot);
        dot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        final int dotSize = res.getDimensionPixelSize(R.dimen.kq_size_dot);
        final LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dotSize, dotSize);
        dotParams.topMargin = res.getDimensionPixelSize(R.dimen.kq_space_10);
        dotParams.setMarginEnd(res.getDimensionPixelSize(R.dimen.kq_space_8));
        titleRow.addView(dot, dotParams);
        title = AirScreenKit.text(this, R.style.TextAppearance_KQ_Heading, R.color.kq_ink);
        ViewCompat.setAccessibilityHeading(title, true);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        meaning = AirScreenKit.body(this, "");
        AirScreenKit.addSection(column, null, null, titleRow, meaning);

        internetRow = new AirSettingRow(this, getString(R.string.air_network_internet), false);
        linkRow = new AirSettingRow(this, getString(R.string.air_network_link), false);
        internetRow.setClickable(false);
        linkRow.setClickable(false);
        AirScreenKit.addSection(column, null, null, AirScreenKit.plateOf(this, internetRow, linkRow));

        final TextView reconnect = AirScreenKit.button(this, getString(R.string.air_network_reconnect), false);
        reconnect.setOnClickListener(v -> {
            TrifaToxService.requestManualReconnect();
            toast(R.string.air_network_reconnect_toast);
        });
        // The whole section goes while the button does not help, so no gap is left in its place.
        reconnectSection = AirScreenKit.addSection(column, null, null, AirScreenKit.leading(reconnect));

        final AirSettingRow log = new AirSettingRow(this, getString(R.string.air_network_log), true);
        log.setOnClickListener(v -> startActivity(new Intent(this, NetworkDiagnosticsActivity.class)));
        AirScreenKit.addSection(column, null, getString(R.string.air_network_log_note), AirScreenKit.plateOf(this, log));
        refresh(false);
    }

    @Override
    protected void onResume()
    {
        super.onResume();
        handler.removeCallbacks(poll);
        handler.postDelayed(poll, POLL_MS);
    }

    @Override
    protected void onPause()
    {
        handler.removeCallbacks(poll);
        super.onPause();
    }

    /** The state now, as this screen and the Connection row of Settings read it. */
    static AirModel.NetworkState stateNow()
    {
        return AirModel.NetworkState.from(TRIFAGlobals.global_self_connection_status,
                                          TRIFAGlobals.HAVE_INTERNET_CONNECTIVITY,
                                          NetworkConnectivityMonitor.defaultNetworkLost());
    }

    /** The state in two or three words: "Online, direct", "Connecting", "No network". */
    static int titleOf(final AirModel.NetworkState state)
    {
        switch (state)
        {
            case DIRECT:
                return R.string.air_network_title_direct;
            case RELAY:
                return R.string.air_network_title_relay;
            case OFFLINE:
                return R.string.air_network_title_offline;
            case CONNECTING:
            default:
                return R.string.air_network_title_connecting;
        }
    }

    /** Shows the state now; a change cross-fades the words while the dot stays where it is. */
    private void refresh(final boolean animated)
    {
        final AirModel.NetworkState next = stateNow();
        if (next == shown)
        {
            return;
        }
        shown = next;
        final int titleRes = titleOf(next);
        final int meaningRes;
        final int linkRes;
        switch (next)
        {
            case DIRECT:
                meaningRes = R.string.air_network_meaning_direct;
                linkRes = R.string.air_network_link_direct;
                break;
            case RELAY:
                meaningRes = R.string.air_network_meaning_relay;
                linkRes = R.string.air_network_link_relay;
                break;
            case OFFLINE:
                meaningRes = R.string.air_network_meaning_offline;
                linkRes = R.string.air_network_link_none;
                break;
            case CONNECTING:
            default:
                meaningRes = R.string.air_network_meaning_connecting;
                linkRes = R.string.air_network_link_none;
                break;
        }
        final boolean alarm = next == AirModel.NetworkState.OFFLINE;
        final boolean live = next == AirModel.NetworkState.DIRECT || next == AirModel.NetworkState.RELAY;
        ViewCompat.setBackgroundTintList(dot, ColorStateList.valueOf(
                AirUi.color(this, alarm ? R.color.kq_danger : (live ? R.color.kq_live : R.color.kq_ink_3))));
        final Runnable apply = () -> {
            title.setText(titleRes);
            title.setTextColor(AirUi.color(this, alarm ? R.color.kq_danger : R.color.kq_ink));
            meaning.setText(meaningRes);
            internetRow.setValue(getString(alarm ? R.string.air_network_internet_no : R.string.air_network_internet_yes));
            linkRow.setValue(getString(linkRes));
            // "Connect again" helps only while the phone has internet and the node has not found the network.
            reconnectSection.setVisibility(next == AirModel.NetworkState.CONNECTING ? View.VISIBLE : View.GONE);
        };
        if (!animated || AirUi.reducedMotion())
        {
            apply.run();
            return;
        }
        final long half = getResources().getInteger(android.R.integer.config_shortAnimTime) / 2;
        column.animate().cancel();
        column.animate().alpha(0.4f).setDuration(half).withEndAction(() -> {
            apply.run();
            column.animate().alpha(1f).setDuration(half).start();
        }).start();
    }
}
