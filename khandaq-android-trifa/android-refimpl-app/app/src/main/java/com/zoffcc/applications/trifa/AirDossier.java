package com.zoffcc.applications.trifa;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Rect;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.widget.SwitchCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;

import org.khandaq.messenger.R;

/**
 * KHANDAQ (redesign, stage 2): the contact dossier (02-plan.md, screen 10), as AirDossierView on iOS. It
 * unfolds from the header over the conversation in 440 ms while the conversation dims under it, and
 * folds back up in 280 ms; with animations off it simply appears and goes. A tap on the dimmed part,
 * the back key, Esc, a pull up on the grip or a swipe up at the end of the content closes it.
 *
 * <p>This class only draws and animates: what the dossier says comes in {@link Content}, and every
 * action goes to the {@link Listener}, which is AirChatPanel.
 */
final class AirDossier
{
    interface Listener
    {
        void onDossierCloseRequested();

        void onDossierMute(boolean muted);

        void onDossierVerify();

        void onDossierUnverify();

        void onDossierCopyKey();

        void onDossierShowQr();

        void onDossierRename();

        void onDossierFiles();

        void onDossierBlock();

        void onDossierRemove();
    }

    /** Everything the dossier shows, in words ready for the screen. */
    static final class Content
    {
        final String name;
        /** Their own name when a nickname hides it, and their status, one per line; empty for none. */
        final String statusLines;
        final int tone;
        final ChatBubbleUiHelper.AvatarMeta photo;
        final AirConversationModel.LinkState link;
        final String initials;
        final String linkText;
        final AirConversationModel.DayStrip strip;
        final boolean nowOnline;
        final String stripSpoken;
        /** Empty when there is no best time yet. */
        final String best;
        final String notifications;
        final boolean muted;
        final String verification;
        final boolean verified;
        final String key;

        Content(final String name, final String statusLines, final int tone, final ChatBubbleUiHelper.AvatarMeta photo,
                final AirConversationModel.LinkState link, final String linkText,
                final AirConversationModel.DayStrip strip, final boolean nowOnline, final String stripSpoken,
                final String best, final String notifications, final boolean muted, final String verification,
                final boolean verified, final String key)
        {
            this.name = name == null ? "" : name;
            this.statusLines = statusLines == null ? "" : statusLines;
            this.tone = tone;
            this.photo = photo;
            this.link = link;
            this.initials = AirModel.Initials.from(this.name);
            this.linkText = linkText;
            this.strip = strip;
            this.nowOnline = nowOnline;
            this.stripSpoken = stripSpoken;
            this.best = best == null ? "" : best;
            this.notifications = notifications;
            this.muted = muted;
            this.verification = verification;
            this.verified = verified;
            this.key = key == null ? "" : key;
        }
    }

    /** A pull on the grip closes the dossier past this distance (dp) or speed (dp per second), as on iOS. */
    private static final float GRIP_CLOSE_DISTANCE_DP = 16f;
    private static final float GRIP_CLOSE_SPEED_DP = 300f;
    /** From this system text size on, button pairs stack and "Rename" goes under the name. */
    private static final float STACK_FONT_SCALE = 1.3f;

    private final Activity activity;
    private final Listener listener;
    private final View body;
    private final View conversation;
    private final View chevron;
    private final View root;
    private final View scrim;
    private final AirCappedScrollView scroll;
    private final AirTileView tile;
    private final TextView name;
    private final TextView status;
    private final TextView rename;
    private final LinearLayout head;
    private final LinearLayout names;
    private final AirPathView path;
    private final TextView linkText;
    private final AirDayStripView strip;
    private final TextView best;
    private final TextView push;
    private final SwitchCompat mute;
    private final TextView verifyText;
    private final View verify;
    private final View unverify;
    private final TextView key;
    private final LinearLayout keyButtons;
    private final View copy;
    private final View qr;
    private final LinearLayout danger;
    private final View block;
    private final View remove;

    private final Rect clip = new Rect();
    private final Interpolator easeOut;
    private ValueAnimator reveal = null;
    private boolean open = false;
    private boolean bindingMute = false;
    private String photoKey = null;
    private String tileTitle = null;
    private boolean stacked = false;
    private VelocityTracker gripTracker = null;
    private float gripDownY = 0f;

    @SuppressLint("ClickableViewAccessibility")
    AirDossier(final Activity activity, final Listener listener)
    {
        this.activity = activity;
        this.listener = listener;
        body = activity.findViewById(R.id.air_chat_body);
        conversation = activity.findViewById(R.id.air_chat_conversation);
        chevron = activity.findViewById(R.id.air_chat_chevron);
        root = activity.findViewById(R.id.air_dossier);
        scrim = activity.findViewById(R.id.air_dossier_scrim);
        scroll = activity.findViewById(R.id.air_dossier_scroll);
        tile = activity.findViewById(R.id.air_dossier_tile);
        head = activity.findViewById(R.id.air_dossier_head);
        names = activity.findViewById(R.id.air_dossier_names);
        name = activity.findViewById(R.id.air_dossier_name);
        status = activity.findViewById(R.id.air_dossier_status);
        rename = activity.findViewById(R.id.air_dossier_rename);
        path = activity.findViewById(R.id.air_dossier_path);
        linkText = activity.findViewById(R.id.air_dossier_link_text);
        strip = activity.findViewById(R.id.air_dossier_strip);
        best = activity.findViewById(R.id.air_dossier_best);
        push = activity.findViewById(R.id.air_dossier_push);
        mute = activity.findViewById(R.id.air_dossier_mute);
        verifyText = activity.findViewById(R.id.air_dossier_verify_text);
        verify = activity.findViewById(R.id.air_dossier_verify);
        unverify = activity.findViewById(R.id.air_dossier_unverify);
        key = activity.findViewById(R.id.air_dossier_key);
        keyButtons = activity.findViewById(R.id.air_dossier_key_buttons);
        copy = activity.findViewById(R.id.air_dossier_copy);
        qr = activity.findViewById(R.id.air_dossier_qr);
        danger = activity.findViewById(R.id.air_dossier_danger);
        block = activity.findViewById(R.id.air_dossier_block);
        remove = activity.findViewById(R.id.air_dossier_remove);
        easeOut = AnimationUtils.loadInterpolator(activity, R.interpolator.kq_ease_out);
        // The "24" of the heading in the digits of the axis under it.
        final TextView dayTitle = activity.findViewById(R.id.air_dossier_day_title);
        dayTitle.setText(AirUi.localDigits(activity, activity.getString(R.string.air_dossier_day_title)));

        for (final View button : new View[]{rename, verify, unverify, copy, qr,
                                            activity.findViewById(R.id.air_dossier_files), block, remove})
        {
            AirUi.markAsButton(button);
        }
        rename.setOnClickListener(v -> listener.onDossierRename());
        verify.setOnClickListener(v -> listener.onDossierVerify());
        unverify.setOnClickListener(v -> listener.onDossierUnverify());
        copy.setOnClickListener(v -> listener.onDossierCopyKey());
        qr.setOnClickListener(v -> listener.onDossierShowQr());
        activity.findViewById(R.id.air_dossier_files).setOnClickListener(v -> listener.onDossierFiles());
        block.setOnClickListener(v -> listener.onDossierBlock());
        remove.setOnClickListener(v -> listener.onDossierRemove());

        mute.setOnCheckedChangeListener((button, checked) -> {
            if (!bindingMute)
            {
                listener.onDossierMute(checked);
            }
        });
        // The row reads as one switch: its label is the switch's name.
        activity.findViewById(R.id.air_dossier_mute_row).setOnClickListener(v -> mute.toggle());

        scrim.setOnClickListener(v -> listener.onDossierCloseRequested());
        scroll.setOnSwipeUpAtEnd(listener::onDossierCloseRequested);
        activity.findViewById(R.id.air_dossier_grip_area).setOnTouchListener(this::onGripTouch);

        // The scroll keeps the conversation 64dp below the dossier, as on iOS.
        body.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (bottom - top != oldBottom - oldTop)
            {
                v.post(this::updateCap);
            }
        });
        applyTextScale();
    }

    boolean isOpen()
    {
        return open;
    }

    // ---- content ----

    void bind(final Content content)
    {
        final Context context = activity;
        if (!TextUtils.equals(tileTitle, content.name) || tile.kind() != AirTileView.Kind.PERSON)
        {
            tileTitle = content.name;
            tile.bind(AirTileView.Kind.PERSON, content.name, content.tone);
            photoKey = null;
        }
        final String nextPhotoKey = content.photo != null && content.photo.loadable
                                    ? content.photo.path + "/" + content.photo.filename + "@" + content.photo.updateTs
                                    : null;
        if (!TextUtils.equals(photoKey, nextPhotoKey))
        {
            photoKey = nextPhotoKey;
            if (nextPhotoKey != null)
            {
                tile.showPhoto(true);
                ChatBubbleUiHelper.load_peer_photo(context, content.photo, tile.photoView());
            }
            else
            {
                tile.showPhoto(false);
                try
                {
                    GlideApp.with(context).clear(tile.photoView());
                }
                catch (Exception ignored)
                {
                }
            }
        }

        name.setText(content.name);
        status.setText(content.statusLines);
        status.setVisibility(content.statusLines.isEmpty() ? View.GONE : View.VISIBLE);

        path.configure(content.link, content.initials, content.name);
        linkText.setText(content.linkText);

        strip.setStrip(content.strip, content.nowOnline);
        strip.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        strip.setContentDescription(context.getString(R.string.air_day_title) + ": " + content.stripSpoken);
        best.setText(content.best);
        best.setVisibility(content.best.isEmpty() ? View.GONE : View.VISIBLE);

        push.setText(content.notifications);
        if (mute.isChecked() != content.muted)
        {
            bindingMute = true;
            mute.setChecked(content.muted);
            bindingMute = false;
        }

        verifyText.setText(content.verification);
        verify.setVisibility(content.verified ? View.GONE : View.VISIBLE);
        unverify.setVisibility(content.verified ? View.VISIBLE : View.GONE);

        key.setText(AirConversationModel.keyLines(content.key, 4));
        key.setContentDescription(context.getString(R.string.air_dossier_key_title) + ": "
                                  + TextUtils.join(" ", AirConversationModel.keyGroups(content.key)));
        applyTextScale();
    }

    /** At large system text sizes the button pairs stack, as iOS does at the accessibility sizes. */
    private void applyTextScale()
    {
        final boolean next = activity.getResources().getConfiguration().fontScale >= STACK_FONT_SCALE;
        if (next == stacked && keyButtons.getOrientation() == (next ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL))
        {
            return;
        }
        stacked = next;
        stack(keyButtons, next, qr);
        stack(danger, next, remove);
        // "Rename" moves under the name, which then keeps the width of the row.
        final ViewGroupSwap swap = new ViewGroupSwap(rename);
        if (next)
        {
            swap.moveTo(names);
            final LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) rename.getLayoutParams();
            lp.gravity = android.view.Gravity.START;
            lp.setMarginStart(-activity.getResources().getDimensionPixelSize(R.dimen.kq_space_8));
            rename.setLayoutParams(lp);
        }
        else
        {
            swap.moveTo(head);
            final LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) rename.getLayoutParams();
            lp.gravity = android.view.Gravity.CENTER_VERTICAL;
            lp.setMarginStart(activity.getResources().getDimensionPixelSize(R.dimen.kq_space_8));
            rename.setLayoutParams(lp);
        }
    }

    private void stack(final LinearLayout row, final boolean vertical, final View second)
    {
        row.setOrientation(vertical ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        final int gap = activity.getResources().getDimensionPixelSize(R.dimen.kq_space_8);
        for (int i = 0; i < row.getChildCount(); i++)
        {
            final View child = row.getChildAt(i);
            final LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) child.getLayoutParams();
            if (vertical)
            {
                lp.width = LinearLayout.LayoutParams.MATCH_PARENT;
                lp.weight = 0f;
            }
            else
            {
                lp.width = 0;
                lp.weight = 1f;
            }
            lp.setMarginStart(child == second && !vertical ? gap : 0);
            lp.topMargin = child == second && vertical ? gap : 0;
            child.setLayoutParams(lp);
        }
    }

    /** Moves one view from its parent to the end of another, keeping its layout parameters' type. */
    private static final class ViewGroupSwap
    {
        private final View view;

        ViewGroupSwap(final View view)
        {
            this.view = view;
        }

        void moveTo(final LinearLayout target)
        {
            if (view.getParent() == target)
            {
                return;
            }
            final android.view.ViewParent parent = view.getParent();
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (parent instanceof android.view.ViewGroup)
            {
                ((android.view.ViewGroup) parent).removeView(view);
            }
            target.addView(view, lp);
        }
    }

    // ---- opening and closing ----

    void open()
    {
        if (open)
        {
            return;
        }
        open = true;
        cancelReveal();
        updateCap();
        root.setVisibility(View.VISIBLE);
        scrim.setVisibility(View.VISIBLE);
        root.setAlpha(1f);
        scrim.setAlpha(0f);
        // The conversation under the dimmed part is out of reach, for touch and for TalkBack.
        conversation.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        rotateChevron(true);
        if (AirUi.reducedMotion())
        {
            root.setClipBounds(null);
            root.setAlpha(0f);
            final long duration = AirUi.duration(activity, R.integer.kq_dur_reveal);
            root.animate().alpha(1f).setDuration(duration).start();
            scrim.animate().alpha(1f).setDuration(duration).start();
            focusHeading();
            return;
        }
        // Hidden until it is measured, then unfolded from the top.
        clip.set(0, 0, Math.max(1, root.getWidth()), 0);
        root.setClipBounds(clip);
        root.post(() -> {
            if (!open)
            {
                return;
            }
            final int height = root.getHeight();
            runReveal(0, height, activity.getResources().getInteger(R.integer.kq_dur_reveal), null);
            scrim.animate().alpha(1f).setInterpolator(easeOut)
                 .setDuration(activity.getResources().getInteger(R.integer.kq_dur_reveal)).start();
            focusHeading();
        });
    }

    /** Folds the dossier back up; {@code animated} false puts it away at once (the screen is leaving). */
    void close(final boolean animated)
    {
        if (!open)
        {
            return;
        }
        open = false;
        cancelReveal();
        rotateChevron(false);
        conversation.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        if (!animated || !root.isLaidOut())
        {
            finishClose();
            return;
        }
        if (AirUi.reducedMotion())
        {
            final long duration = AirUi.duration(activity, R.integer.kq_dur_reveal);
            root.animate().alpha(0f).setDuration(duration).withEndAction(this::finishCloseIfClosed).start();
            scrim.animate().alpha(0f).setDuration(duration).start();
            return;
        }
        final long duration = activity.getResources().getInteger(R.integer.kq_dur_layout);
        final int from = root.getClipBounds() != null ? root.getClipBounds().height() : root.getHeight();
        runReveal(from, 0, duration, this::finishCloseIfClosed);
        scrim.animate().alpha(0f).setInterpolator(easeOut).setDuration(duration).start();
    }

    private void finishCloseIfClosed()
    {
        if (!open)
        {
            finishClose();
        }
    }

    private void finishClose()
    {
        root.animate().cancel();
        scrim.animate().cancel();
        root.setVisibility(View.GONE);
        scrim.setVisibility(View.GONE);
        root.setClipBounds(null);
        root.setAlpha(1f);
        scrim.setAlpha(1f);
        scroll.scrollTo(0, 0);
    }

    private void runReveal(final int from, final int to, final long duration, final Runnable end)
    {
        final ValueAnimator animator = ValueAnimator.ofInt(from, to);
        animator.setDuration(duration);
        animator.setInterpolator(easeOut);
        animator.addUpdateListener(a -> {
            clip.set(0, 0, root.getWidth(), (Integer) a.getAnimatedValue());
            root.setClipBounds(clip);
        });
        animator.addListener(new AnimatorListenerAdapter()
        {
            private boolean cancelled = false;

            @Override
            public void onAnimationCancel(final Animator animation)
            {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(final Animator animation)
            {
                if (reveal == animator)
                {
                    reveal = null;
                }
                if (cancelled)
                {
                    return;
                }
                if (open)
                {
                    root.setClipBounds(null);
                }
                if (end != null)
                {
                    end.run();
                }
            }
        });
        reveal = animator;
        animator.start();
    }

    private void cancelReveal()
    {
        if (reveal != null)
        {
            final ValueAnimator running = reveal;
            reveal = null;
            running.cancel();
        }
        root.animate().cancel();
        scrim.animate().cancel();
    }

    private void rotateChevron(final boolean up)
    {
        if (chevron == null)
        {
            return;
        }
        chevron.animate().cancel();
        chevron.animate().rotation(up ? 180f : 0f).setDuration(AirUi.duration(activity, R.integer.kq_dur_state)).start();
    }

    private void focusHeading()
    {
        name.post(() -> {
            if (open)
            {
                name.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null);
            }
        });
    }

    /** The scroll stops 64dp above the bottom of the conversation, under the grip and the rule. */
    private void updateCap()
    {
        final int bodyHeight = body.getHeight();
        if (bodyHeight <= 0)
        {
            return;
        }
        final android.content.res.Resources res = activity.getResources();
        final int cap = bodyHeight - res.getDimensionPixelSize(R.dimen.air_dossier_gap)
                        - res.getDimensionPixelSize(R.dimen.kq_space_24)
                        - res.getDimensionPixelSize(R.dimen.kq_stroke_hairline);
        scroll.setMaxHeight(Math.max(res.getDimensionPixelSize(R.dimen.kq_size_target_android), cap));
    }

    private boolean onGripTouch(final View view, final MotionEvent event)
    {
        switch (event.getActionMasked())
        {
            case MotionEvent.ACTION_DOWN:
                gripDownY = event.getRawY();
                if (gripTracker == null)
                {
                    gripTracker = VelocityTracker.obtain();
                }
                else
                {
                    gripTracker.clear();
                }
                gripTracker.addMovement(event);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (gripTracker != null)
                {
                    gripTracker.addMovement(event);
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (gripTracker != null)
                {
                    gripTracker.addMovement(event);
                    gripTracker.computeCurrentVelocity(1000);
                    final float density = activity.getResources().getDisplayMetrics().density;
                    final float movement = event.getRawY() - gripDownY;
                    if (movement < -GRIP_CLOSE_DISTANCE_DP * density
                        || gripTracker.getYVelocity() < -GRIP_CLOSE_SPEED_DP * density)
                    {
                        listener.onDossierCloseRequested();
                    }
                    gripTracker.recycle();
                    gripTracker = null;
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (gripTracker != null)
                {
                    gripTracker.recycle();
                    gripTracker = null;
                }
                return true;
            default:
                return false;
        }
    }

    /** The header and the band announce what a tap on them does now. */
    static void describeToggle(final View view, final boolean isOpen)
    {
        ViewCompat.replaceAccessibilityAction(view, AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK,
                                              view.getContext().getString(isOpen ? R.string.air_dossier_close_hint
                                                                                 : R.string.air_chat_dossier_hint),
                                              null);
    }

    void release()
    {
        cancelReveal();
        scroll.setOnSwipeUpAtEnd(null);
        if (gripTracker != null)
        {
            gripTracker.recycle();
            gripTracker = null;
        }
    }
}
