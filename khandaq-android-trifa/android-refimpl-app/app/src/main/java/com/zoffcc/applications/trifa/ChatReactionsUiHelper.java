package com.zoffcc.applications.trifa;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.khandaq.messenger.R;

/**
 * KHANDAQ (#192): renders the reaction chips row (R.id.reactions_row, included in the bubble
 * layouts) from the message row's reactions JSON. A chip shows "<emoji> <count>" (count hidden
 * when 1), the chip carrying the user's own reaction gets the highlighted background, tapping a
 * chip toggles the own reaction via the supplied callback.
 */
public class ChatReactionsUiHelper
{
    interface OnChipTap
    {
        void onTap(String emoji);
    }

    static void bind_reactions_row(final View itemView, final String reactionsJson, final OnChipTap cb)
    {
        try
        {
            final LinearLayout row = itemView.findViewById(R.id.reactions_row);
            if (row == null)
            {
                return;
            }
            row.removeAllViews();
            JSONArray arr = null;
            try
            {
                if (reactionsJson != null && !reactionsJson.isEmpty())
                {
                    arr = new JSONArray(reactionsJson);
                }
            }
            catch (Exception ignored)
            {
            }
            if (arr == null || arr.length() == 0)
            {
                row.setVisibility(View.GONE);
                return;
            }

            final float d = row.getResources().getDisplayMetrics().density;
            final int padH = (int) (8 * d);
            final int padV = (int) (3 * d);
            final int margin = (int) (4 * d);
            for (int i = 0; i < arr.length(); i++)
            {
                final JSONObject entry = arr.getJSONObject(i);
                final String emoji = entry.getString("e");
                final JSONArray actors = entry.getJSONArray("p");
                final int count = actors.length();
                if (count < 1)
                {
                    continue;
                }
                boolean own = false;
                for (int j = 0; j < count; j++)
                {
                    if (HelperMessageReaction.OWN_MARKER.equals(actors.getString(j)))
                    {
                        own = true;
                        break;
                    }
                }

                final TextView chip = new TextView(row.getContext());
                chip.setText(count > 1 ? (emoji + " " + count) : emoji);
                chip.setTextSize(13);
                chip.setTextColor(0xFFFFFFFF);
                chip.setPadding(padH, padV, padH, padV);
                chip.setBackgroundResource(own ? R.drawable.reaction_chip_bg_own : R.drawable.reaction_chip_bg);
                final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.setMargins(0, 0, margin, 0);
                chip.setLayoutParams(lp);
                if (cb != null)
                {
                    chip.setOnClickListener(v -> cb.onTap(emoji));
                }
                row.addView(chip);
            }
            row.setVisibility(row.getChildCount() > 0 ? View.VISIBLE : View.GONE);
        }
        catch (Exception ignored)
        {
        }
    }
}
