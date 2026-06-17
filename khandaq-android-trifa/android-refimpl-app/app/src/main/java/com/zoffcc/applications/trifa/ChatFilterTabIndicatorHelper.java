package com.zoffcc.applications.trifa;

import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/**
 * Telegram-style sliding underline for chat filter tabs, synced with ViewPager2 scroll position.
 */
final class ChatFilterTabIndicatorHelper
{
    private final View[] tabViews;
    private final TextView[] tabLabels;
    private final View slidingIndicator;
    private final int activeColor;
    private final int inactiveColor;

    private boolean layoutReady = false;
    private float lastIndex = -1f;

    ChatFilterTabIndicatorHelper(final View tabDirect,
                                 final View tabGroups,
                                 final View tabFavorites,
                                 final TextView labelDirect,
                                 final TextView labelGroups,
                                 final TextView labelFavorites,
                                 final View slidingIndicator,
                                 final int activeColor,
                                 final int inactiveColor)
    {
        this.tabViews = new View[]{tabDirect, tabGroups, tabFavorites};
        this.tabLabels = new TextView[]{labelDirect, labelGroups, labelFavorites};
        this.slidingIndicator = slidingIndicator;
        this.activeColor = activeColor;
        this.inactiveColor = inactiveColor;

        if (slidingIndicator != null)
        {
            slidingIndicator.post(this::remeasureTabs);
        }
    }

    void remeasureTabs()
    {
        if (slidingIndicator == null || tabViews[0] == null)
        {
            return;
        }

        boolean ready = true;
        for (final View tab : tabViews)
        {
            if (tab == null || tab.getWidth() <= 0)
            {
                ready = false;
                break;
            }
        }

        layoutReady = ready;
        if (layoutReady && lastIndex >= 0f)
        {
            applyIndex(lastIndex);
        }
    }

    void setScrollPosition(final int position, final float positionOffset)
    {
        lastIndex = position + positionOffset;
        if (!layoutReady)
        {
            if (slidingIndicator != null)
            {
                slidingIndicator.post(this::remeasureTabs);
            }
            return;
        }
        applyIndex(lastIndex);
    }

    void snapToTab(final int tab)
    {
        lastIndex = tab;
        if (!layoutReady)
        {
            if (slidingIndicator != null)
            {
                slidingIndicator.post(this::remeasureTabs);
            }
            return;
        }
        applyIndex(tab);
    }

    private void applyIndex(final float index)
    {
        if (slidingIndicator == null)
        {
            return;
        }

        final int lastTab = tabViews.length - 1;
        int base = (int) Math.floor(index);
        float fraction = index - base;
        if (base >= lastTab)
        {
            base = lastTab;
            fraction = 0f;
        }

        final View tabA = tabViews[base];
        final View tabB = tabViews[Math.min(base + 1, lastTab)];
        if (tabA == null || tabB == null)
        {
            return;
        }

        final float leftA = tabA.getLeft();
        final float leftB = tabB.getLeft();
        final float widthA = tabA.getWidth();
        final float widthB = tabB.getWidth();

        final float left = leftA + (leftB - leftA) * fraction;
        final float width = widthA + (widthB - widthA) * fraction;

        final ViewGroup.LayoutParams params = slidingIndicator.getLayoutParams();
        if (params != null)
        {
            params.width = Math.max(1, Math.round(width));
            slidingIndicator.setLayoutParams(params);
        }

        slidingIndicator.setTranslationX(left);
        slidingIndicator.setVisibility(View.VISIBLE);
        updateLabelColors(index);
    }

    private void updateLabelColors(final float index)
    {
        for (int i = 0; i < tabLabels.length; i++)
        {
            final TextView label = tabLabels[i];
            if (label == null)
            {
                continue;
            }

            final float distance = Math.abs(index - i);
            final float blend = Math.max(0f, 1f - Math.min(1f, distance));
            label.setTextColor(blendColor(inactiveColor, activeColor, blend));
        }
    }

    private static int blendColor(final int from, final int to, final float ratio)
    {
        final float clamped = Math.max(0f, Math.min(1f, ratio));
        final int a = Math.round(Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * clamped);
        final int r = Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * clamped);
        final int g = Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * clamped);
        final int b = Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * clamped);
        return Color.argb(a, r, g, b);
    }
}
