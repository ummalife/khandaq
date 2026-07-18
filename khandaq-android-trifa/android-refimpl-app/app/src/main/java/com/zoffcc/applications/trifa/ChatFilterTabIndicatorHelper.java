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

        // KHANDAQ: the underline hugs the tab LABEL (centred under the text) instead of spanning the
        // whole 1/3-width tab — shorter & tidier. Falls back to half the tab width if the label
        // hasn't measured yet.
        final TextView labelA = (base < tabLabels.length) ? tabLabels[base] : null;
        final TextView labelB = (Math.min(base + 1, lastTab) < tabLabels.length)
                ? tabLabels[Math.min(base + 1, lastTab)] : null;
        final float indWA = (labelA != null && labelA.getWidth() > 0) ? labelA.getWidth() : tabA.getWidth() * 0.5f;
        final float indWB = (labelB != null && labelB.getWidth() > 0) ? labelB.getWidth() : tabB.getWidth() * 0.5f;

        final float centerA = tabA.getLeft() + tabA.getWidth() / 2f;
        final float centerB = tabB.getLeft() + tabB.getWidth() / 2f;

        final float width = indWA + (indWB - indWA) * fraction;
        final float center = centerA + (centerB - centerA) * fraction;
        final float left = center - width / 2f;

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
