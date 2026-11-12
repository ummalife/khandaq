package com.zoffcc.applications.trifa;

import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.List;

import org.khandaq.messenger.R;

/**
 * KHANDAQ (redesign, block): Settings > Privacy > Blocked, as AirBlockedKeysController on iOS. Every
 * blocked key in groups of four, oldest first, each with Unblock. Unblocking only lets requests from the
 * key show again; the contact itself comes back with a new request (design/DESIGN.md, "Block, on every
 * client").
 */
public final class AirBlockedActivity extends AirScreenActivity
        implements AirBlockList.Listener, AirBlockList.SnapshotCallback
{
    private boolean destroyed = false;

    @Override
    protected int screenTitle()
    {
        return R.string.air_blocked_title;
    }

    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        AirBlockList.addListener(this);
        AirBlockList.snapshotAsync(this);
    }

    @Override
    protected void onDestroy()
    {
        destroyed = true;
        AirBlockList.removeListener(this);
        super.onDestroy();
    }

    @Override
    public void onAirBlockListChanged()
    {
        AirBlockList.snapshotAsync(this);
    }

    @Override
    public void onAirBlockListSnapshot(final List<AirBlockList.Entry> entries)
    {
        if (destroyed)
        {
            return;
        }
        column.removeAllViews();
        if (entries.isEmpty())
        {
            AirScreenKit.addSection(column, null, null, AirScreenKit.body(this, getString(R.string.air_blocked_empty)));
            return;
        }
        AirScreenKit.addSection(column, null, null, AirScreenKit.body(this, getString(R.string.air_blocked_note)));
        final android.view.View[] cards = new android.view.View[entries.size()];
        for (int i = 0; i < entries.size(); i++)
        {
            cards[i] = card(entries.get(i).publicKey);
        }
        AirScreenKit.addSection(column, null, null, cards);
    }

    /** One key on a plate, the Unblock button under it. */
    private LinearLayout card(final String publicKey)
    {
        final LinearLayout plate = AirScreenKit.plate(this);
        final int inset = getResources().getDimensionPixelSize(R.dimen.kq_space_16);
        plate.setPaddingRelative(inset, inset, inset, inset);
        final TextView key = AirScreenKit.keyText(this, publicKey);
        plate.addView(key, AirScreenKit.matchWidth());
        final TextView unblock = AirScreenKit.button(this, getString(R.string.air_blocked_unblock), false);
        unblock.setOnClickListener(v -> {
            if (AirBlockList.unblock(publicKey))
            {
                toast(R.string.air_blocked_unblocked);
            }
        });
        final LinearLayout.LayoutParams params = AirScreenKit.matchWidth();
        params.topMargin = getResources().getDimensionPixelSize(R.dimen.kq_space_12);
        plate.addView(AirScreenKit.leading(unblock), params);
        return plate;
    }
}
