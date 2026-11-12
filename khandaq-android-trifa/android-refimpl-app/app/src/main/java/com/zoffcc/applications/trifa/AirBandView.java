package com.zoffcc.applications.trifa;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.view.AccessibilityDelegateCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.core.widget.TextViewCompat;

import org.khandaq.messenger.R;

/**
 * KHANDAQ (redesign, E22): the band itself, as NotificationBannerView on iOS. On the raised surface with
 * the sheet radius, lifted by the "lift" shadow rather than a frame: the tile the Air screen shows for
 * the chat, the sender in the label type, then up to two lines of text in the second ink. A tap opens
 * what it tells of; a drag moves it up only, and one past {@code air_band_dismiss_distance} puts it
 * away, a shorter one lets it settle back. For TalkBack it is one button that says the sender and the
 * text and what a tap opens, with "Put away" as its dismiss action.
 */
@SuppressLint("ViewConstructor")
final class AirBandView extends LinearLayout
{
    interface Listener
    {
        void onBandTap();

        void onBandDismiss();

        /** A finger is on the band ({@code true}) or left it: the band does not leave under a finger. */
        void onBandHeld(boolean held);
    }

    private final Listener listener;
    private final AirTileView tile;
    private final int touchSlop;
    private final float dismissDistance;
    private float downY;
    private boolean dragging;

    AirBandView(final Context context, final AirBand.Content content, final Listener listener)
    {
        super(context);
        this.listener = listener;
        final Resources res = context.getResources();
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        dismissDistance = res.getDimension(R.dimen.air_band_dismiss_distance);

        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        // The group's back tile peeks out of the tile's own bounds.
        setClipChildren(false);
        setClipToPadding(false);
        final int pad = res.getDimensionPixelSize(R.dimen.kq_space_12);
        setPaddingRelative(pad, pad, res.getDimensionPixelSize(R.dimen.kq_space_16), pad);
        setMinimumHeight(res.getDimensionPixelSize(R.dimen.kq_size_target_android));

        // Raised, sunken while pressed; the focus ring lies over it.
        setBackgroundResource(R.drawable.air_band_bg);
        setElevation(res.getDimension(R.dimen.air_band_elevation));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
        {
            final int shadow = AirUi.color(context, R.color.kq_shadow);
            setOutlineSpotShadowColor(shadow);
            setOutlineAmbientShadowColor(shadow);
        }
        setForeground(context.getDrawable(R.drawable.air_band_fg_focus));

        tile = new AirTileView(context);
        tile.bind(content.tileKind, content.tileTitle, content.tone);
        if (content.photo != null && content.photo.loadable)
        {
            tile.showPhoto(true);
            ChatBubbleUiHelper.load_peer_photo(context, content.photo, tile.photoView());
        }
        final int side = res.getDimensionPixelSize(R.dimen.kq_size_tile_phone);
        addView(tile, new LayoutParams(side, side));

        final LinearLayout words = new LinearLayout(context);
        words.setOrientation(VERTICAL);
        final LayoutParams wordsParams = new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        wordsParams.setMarginStart(pad);
        addView(words, wordsParams);

        final TextView title = new TextView(context);
        TextViewCompat.setTextAppearance(title, R.style.TextAppearance_KQ_Air_LabelStrong);
        title.setTextColor(AirUi.color(context, R.color.kq_ink));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setText(content.title);
        words.addView(title, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (!TextUtils.isEmpty(content.body))
        {
            final TextView body = new TextView(context);
            TextViewCompat.setTextAppearance(body, R.style.TextAppearance_KQ_Air_LabelRegular);
            body.setTextColor(AirUi.color(context, R.color.kq_ink_2));
            body.setMaxLines(2);
            body.setEllipsize(TextUtils.TruncateAt.END);
            body.setText(content.body);
            final LayoutParams bodyParams = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                             ViewGroup.LayoutParams.WRAP_CONTENT);
            bodyParams.topMargin = res.getDimensionPixelSize(R.dimen.kq_space_2);
            words.addView(body, bodyParams);
        }

        // One element for TalkBack: the sender and the text, what a tap opens, and a way to put it away.
        setClickable(true);
        setFocusable(true);
        words.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        setContentDescription(content.spoken());
        final String hint = context.getString(content.hint);
        final String putAway = context.getString(R.string.air_band_dismiss);
        ViewCompat.setAccessibilityDelegate(this, new AccessibilityDelegateCompat()
        {
            @Override
            public void onInitializeAccessibilityNodeInfo(@NonNull final View host,
                                                          @NonNull final AccessibilityNodeInfoCompat info)
            {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(Button.class.getName());
                info.setHintText(hint);
                info.setDismissable(true);
                info.addAction(new AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                        AccessibilityNodeInfoCompat.ACTION_DISMISS, putAway));
            }

            @Override
            public boolean performAccessibilityAction(@NonNull final View host, final int action, final Bundle args)
            {
                if (action == AccessibilityNodeInfoCompat.ACTION_DISMISS)
                {
                    listener.onBandDismiss();
                    return true;
                }
                return super.performAccessibilityAction(host, action, args);
            }
        });
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(final MotionEvent event)
    {
        if (!isEnabled())
        {
            // On its way out: the touch goes through to the screen.
            return false;
        }
        switch (event.getActionMasked())
        {
            case MotionEvent.ACTION_DOWN:
                downY = event.getRawY();
                dragging = false;
                animate().cancel();
                setPressed(true);
                listener.onBandHeld(true);
                return true;
            case MotionEvent.ACTION_MOVE:
            {
                final float dy = event.getRawY() - downY;
                if (!dragging && Math.abs(dy) > touchSlop)
                {
                    dragging = true;
                    setPressed(false);
                }
                if (dragging)
                {
                    // Up only.
                    setTranslationY(Math.min(0f, dy));
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            {
                setPressed(false);
                listener.onBandHeld(false);
                final float dy = event.getRawY() - downY;
                if (!dragging)
                {
                    performClick();
                }
                else if (dy < -dismissDistance)
                {
                    listener.onBandDismiss();
                }
                else
                {
                    settle();
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                setPressed(false);
                listener.onBandHeld(false);
                if (dragging)
                {
                    settle();
                }
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    public boolean performClick()
    {
        super.performClick();
        listener.onBandTap();
        return true;
    }

    @Override
    protected void onConfigurationChanged(final Configuration newConfig)
    {
        super.onConfigurationChanged(newConfig);
        // A screen that turns without being recreated: the width and the safe area the band was placed
        // in are gone, so it goes rather than hang askew.
        listener.onBandDismiss();
    }

    @Override
    protected void onDetachedFromWindow()
    {
        super.onDetachedFromWindow();
        try
        {
            GlideApp.with(getContext()).clear(tile.photoView());
        }
        catch (Throwable ignored)
        {
            // The screen is going away; nothing to clear.
        }
    }

    /** Back in place after a drag too short to put it away. */
    private void settle()
    {
        animate().translationY(0f).setDuration(AirUi.duration(getContext(), R.integer.kq_dur_state)).start();
    }
}
