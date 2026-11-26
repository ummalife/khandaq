package com.zoffcc.applications.trifa;

import android.os.Build;
import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.widget.LinearLayout;

public class ViewUtil
{
    public static void setY(final @NonNull View v, final int y)
    {
        if (Build.VERSION.SDK_INT >= 11)
        {
            ViewCompat.setY(v, y);
        }
        else
        {
            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
            params.topMargin = y;
            v.setLayoutParams(params);
        }
    }

    public static float getY(final @NonNull View v)
    {
        if (Build.VERSION.SDK_INT >= 11)
        {
            return ViewCompat.getY(v);
        }
        else
        {
            return ((ViewGroup.MarginLayoutParams) v.getLayoutParams()).topMargin;
        }
    }

    public static float getX(final @NonNull View v)
    {
        if (Build.VERSION.SDK_INT >= 11)
        {
            return ViewCompat.getX(v);
        }
        else
        {
            return ((LinearLayout.LayoutParams) v.getLayoutParams()).leftMargin;
        }
    }


    @SuppressWarnings("unchecked")
    public static <T extends View> T findById(@NonNull View parent, @IdRes int resId)
    {
        return (T) parent.findViewById(resId);
    }

    public static void animateIn(final @NonNull View view, final @NonNull Animation animation)
    {
        if (view.getVisibility() == View.VISIBLE)
        {
            return;
        }

        view.clearAnimation();
        animation.reset();
        animation.setStartTime(0);
        view.setVisibility(View.VISIBLE);
        view.startAnimation(animation);
    }


    public static void animateOut(final @NonNull View view, final @NonNull Animation animation, final int visibility)
    {
        if (view.getVisibility() == visibility)
        {
            // future.set(true);
        }
        else
        {
            view.clearAnimation();
            animation.reset();
            animation.setStartTime(0);
            animation.setAnimationListener(new Animation.AnimationListener()
            {
                @Override
                public void onAnimationStart(Animation animation)
                {
                }

                @Override
                public void onAnimationRepeat(Animation animation)
                {
                }

                @Override
                public void onAnimationEnd(Animation animation)
                {
                    view.setVisibility(visibility);
                    // future.set(true);
                }
            });
            view.startAnimation(animation);
        }
    }

    // KHANDAQ (#1 + #3 fix): on edge-to-edge (targetSdk 35/36) the decor draws under the status bar
    // AND the keyboard, and adjustResize no longer lifts the layout automatically. Without handling
    // insets by hand the header ends up under the status bar (avatar/name overlap the clock — #3)
    // and the input field ends up hidden behind the keyboard (#1). Apply the status-bar inset as top
    // padding and max(IME, nav-bar) as bottom padding on the content root — reproducing fit-system-
    // windows + adjustResize by hand.
    public static void applyImeBottomInsets(final android.app.Activity activity)
    {
        if (activity == null) { return; }
        final View content = activity.findViewById(android.R.id.content);
        if (content == null) { return; }
        try
        {
            ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
                final int top = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()).top;
                final int ime = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom;
                final int nav = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()).bottom;
                v.setPadding(v.getPaddingLeft(), top, v.getPaddingRight(), Math.max(ime, nav));
                // consume: group/conference roots still carry fitsSystemWindows="true" — letting the
                // insets pass through would apply the top offset a second time there.
                return androidx.core.view.WindowInsetsCompat.CONSUMED;
            });
        }
        catch (Throwable ignored)
        {
        }
    }

    /**
     * KHANDAQ (inbox #3, 27.11): keep a screen that never asked for the insets out from under the
     * system bars.
     *
     * Android 16 lays every window of an app that targets it edge to edge, and
     * windowOptOutEdgeToEdgeEnforcement no longer counts there. Such a screen has its toolbar under the
     * status bar: on Network diagnostics the title and the back arrow sat over the clock (reported on
     * an Android 16 phone with 0.2.69).
     *
     * The root view of the screen takes the following as padding on top of its own:
     * - the status bar and the navigation bar;
     * - the display cutout (on any side, also in landscape);
     * - the keyboard at the bottom.
     *
     * The root's background then fills the strip under the status bar, so the toolbar's colour runs up
     * to the top edge in both themes instead of the window's. The insets stop at the root.
     *
     * On older Android the decor has already taken the bars, so the padding stays as it was. A screen
     * whose content root is not built yet (a ListActivity before its adapter) pads the content frame.
     */
    public static void fitRootToSystemBars(final android.app.Activity activity)
    {
        if (activity == null)
        {
            return;
        }
        final View content = activity.findViewById(android.R.id.content);
        if (content == null)
        {
            return;
        }
        final View root = ((content instanceof ViewGroup) && (((ViewGroup) content).getChildCount() > 0))
                          ? ((ViewGroup) content).getChildAt(0) : content;
        final int baseLeft = root.getPaddingLeft();
        final int baseTop = root.getPaddingTop();
        final int baseRight = root.getPaddingRight();
        final int baseBottom = root.getPaddingBottom();
        try
        {
            ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
                final androidx.core.graphics.Insets bars = insets.getInsets(
                        androidx.core.view.WindowInsetsCompat.Type.systemBars()
                        | androidx.core.view.WindowInsetsCompat.Type.displayCutout());
                final int ime = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom;
                v.setPadding(baseLeft + bars.left, baseTop + bars.top, baseRight + bars.right,
                             baseBottom + Math.max(bars.bottom, ime));
                return androidx.core.view.WindowInsetsCompat.CONSUMED;
            });
            ViewCompat.requestApplyInsets(root);
        }
        catch (Throwable ignored)
        {
        }
    }

    /**
     * Keep a form's bottom button bar above the on-screen keyboard.
     *
     * With targetSdk 35+ the window is edge-to-edge and windowSoftInputMode="adjustResize" no longer
     * resizes it, so a bar pinned to the bottom of the layout stays where it is and the keyboard
     * covers it. On a 720x1600 phone (Samsung SM-A075F) that hid "Create group" completely: you
     * typed a name and there was nothing left to press, which is exactly how it was reported.
     */
    public static void keep_content_above_keyboard(final android.view.View root)
    {
        if (root == null)
        {
            return;
        }
        final int base_bottom = root.getPaddingBottom();
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root, (v, wi) ->
        {
            final androidx.core.graphics.Insets ime =
                    wi.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime());
            final androidx.core.graphics.Insets bars =
                    wi.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(),
                         base_bottom + Math.max(ime.bottom, bars.bottom));
            return wi;
        });
        androidx.core.view.ViewCompat.requestApplyInsets(root);
    }

}
