package com.zoffcc.applications.trifa;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.IntegerRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.core.widget.ImageViewCompat;

import org.khandaq.messenger.R;

import java.util.ArrayList;
import java.util.List;

import static com.zoffcc.applications.trifa.NoNetworkNotice.Phase.BACK_ONLINE;
import static com.zoffcc.applications.trifa.NoNetworkNotice.Phase.CONNECTING;
import static com.zoffcc.applications.trifa.NoNetworkNotice.Phase.HIDDEN;
import static com.zoffcc.applications.trifa.NoNetworkNotice.Phase.NO_NETWORK;

/**
 * KHANDAQ (0.2.57, SPEC-network part 2): the "no network" band under the header of the chat list and of a chat,
 * as AirNetworkBand on the desktop. One {@link NoNetworkNotice} decides for the whole app: the system's network
 * comes from NetworkConnectivityMonitor, our node from the self connection callback, and every band attached to
 * a window draws the same phase.
 * <ul>
 * <li>No network: the no-network icon, "No network" and what the messages do. Connecting: the clock and
 * "Connecting". Back online: the check in the live colour and "Back online". Once the network is back the title
 * says it all, as on the desktop.</li>
 * <li>It is a row of the screen, so it pushes what is under it down and never covers the message field. It opens
 * in the layout time with its words coming down from under the header and closes in the state time; with reduced
 * motion it is there or gone at once and its words fade, 120 ms at most.</li>
 * <li>No sound and no vibration. TalkBack hears the title once, when the band comes.</li>
 * </ul>
 */
public final class AirNoNetworkBand extends ViewGroup
{
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** The notice, the phase and the bands are touched on the main thread only. */
    private static final NoNetworkNotice NOTICE = new NoNetworkNotice();
    private static final List<AirNoNetworkBand> BANDS = new ArrayList<>();
    private static final Runnable STEP = AirNoNetworkBand::step;
    private static NoNetworkNotice.Phase phase = HIDDEN;
    private static long phaseSince = SystemClock.elapsedRealtime();

    private final Interpolator easeOut;
    private final Paint rule = new Paint();
    private final int hairline;
    @Nullable
    private View content = null;
    @Nullable
    private ImageView icon = null;
    @Nullable
    private TextView title = null;
    @Nullable
    private TextView note = null;
    /** Not private: the motion's listener ends it without a synthetic accessor. */
    @Nullable
    ValueAnimator motion = null;
    /** The words the band shows, or HIDDEN before it showed any. */
    private NoNetworkNotice.Phase words = HIDDEN;
    /** The motion: the height it starts from, whether the height goes to nothing, and how far it is. */
    private int fromHeight = 0;
    private boolean toNothing = false;
    private float travelled = 1f;
    /** True while the band goes away: it is gone once the motion ends. */
    private boolean leaving = false;
    private int contentHeight = 0;

    public AirNoNetworkBand(@NonNull final Context context)
    {
        this(context, null);
    }

    public AirNoNetworkBand(@NonNull final Context context, @Nullable final AttributeSet attrs)
    {
        super(context, attrs);
        easeOut = AnimationUtils.loadInterpolator(context, R.interpolator.kq_ease_out);
        hairline = getResources().getDimensionPixelSize(R.dimen.kq_stroke_hairline);
        rule.setColor(AirUi.color(context, R.color.kq_line));
    }

    // ---- the one notice of the app ----

    /** NetworkConnectivityMonitor, on any thread: whether the system has a usable network now. */
    static void networkChanged(final boolean usable)
    {
        MAIN.post(() -> {
            final long now = SystemClock.elapsedRealtime();
            NOTICE.node(nodeOnline(), now);
            publish(NOTICE.network(usable, now), now);
        });
    }

    /** The self connection callback, on the Tox thread: our node went online or offline. */
    static void nodeChanged()
    {
        MAIN.post(STEP);
    }

    private static void step()
    {
        final long now = SystemClock.elapsedRealtime();
        publish(NOTICE.node(nodeOnline(), now), now);
    }

    private static boolean nodeOnline()
    {
        return TrifaToxService.is_tox_started
               && (TRIFAGlobals.global_self_connection_status != ToxVars.TOX_CONNECTION.TOX_CONNECTION_NONE.value);
    }

    private static void publish(final NoNetworkNotice.Phase next, final long now)
    {
        MAIN.removeCallbacks(STEP);
        final long at = NOTICE.nextAt();
        if (at >= 0)
        {
            MAIN.postDelayed(STEP, Math.max(0L, at - now));
        }
        if (next == phase)
        {
            return;
        }
        // For the support log: when the phone lost its network and how long it took to get online again.
        NetworkDiagnosticsLog.log("no_network_band", phase + " -> " + next + " after " + (now - phaseSince) + " ms");
        final NoNetworkNotice.Phase before = phase;
        phase = next;
        phaseSince = now;
        for (final AirNoNetworkBand band : new ArrayList<>(BANDS))
        {
            band.present(before, next, true);
        }
    }

    // ---- the band on a screen ----

    @Override
    protected void onFinishInflate()
    {
        super.onFinishInflate();
        content = findViewById(R.id.air_no_network_content);
        icon = findViewById(R.id.air_no_network_icon);
        title = findViewById(R.id.air_no_network_title);
        note = findViewById(R.id.air_no_network_note);
        if (content != null)
        {
            // TalkBack reads the band as one: the title, then what the messages do.
            ViewCompat.setScreenReaderFocusable(content, true);
        }
    }

    @Override
    protected void onAttachedToWindow()
    {
        super.onAttachedToWindow();
        if (isInEditMode())
        {
            return;
        }
        if (!BANDS.contains(this))
        {
            BANDS.add(this);
        }
        // A screen that opens while the band is up shows it at once, with no word to TalkBack.
        present(HIDDEN, phase, false);
    }

    @Override
    protected void onDetachedFromWindow()
    {
        BANDS.remove(this);
        stopMotion();
        super.onDetachedFromWindow();
    }

    private void present(final NoNetworkNotice.Phase before, final NoNetworkNotice.Phase next, final boolean event)
    {
        if ((content == null) || (icon == null) || (title == null) || (note == null))
        {
            return;
        }
        final boolean moving = event && personSeesIt();
        if (next == HIDDEN)
        {
            if ((getVisibility() == VISIBLE) && !leaving)
            {
                move(true, false, moving);
            }
            return;
        }
        final boolean coming = (getVisibility() != VISIBLE) || leaving;
        final boolean newWords = next != words;
        bind(next);
        if (coming || newWords)
        {
            setVisibility(VISIBLE);
            move(false, coming, moving);
        }
        if (event && (before == HIDDEN) && coming)
        {
            // The title once, as the band comes; the rest is there for whoever moves to it.
            announceForAccessibility(title.getText());
        }
    }

    private void bind(final NoNetworkNotice.Phase next)
    {
        final int image;
        final int tint;
        final int text;
        if (next == CONNECTING)
        {
            image = R.drawable.kq_ic_clock;
            tint = R.color.kq_ink_2;
            text = R.string.air_network_title_connecting;
        }
        else if (next == BACK_ONLINE)
        {
            image = R.drawable.kq_ic_check;
            tint = R.color.kq_live;
            text = R.string.air_no_network_back;
        }
        else
        {
            image = R.drawable.kq_ic_wifi_off;
            tint = R.color.kq_ink_2;
            text = R.string.air_network_title_offline;
        }
        icon.setImageResource(image);
        ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(AirUi.color(getContext(), tint)));
        title.setText(text);
        // What the messages do is said while there is no network; after that the title says it all.
        note.setVisibility((next == NO_NETWORK) ? VISIBLE : GONE);
        words = next;
    }

    /** Whether a motion would be seen: the band's screen is in front and laid out. */
    private boolean personSeesIt()
    {
        if (!isAttachedToWindow() || (getWindowVisibility() != VISIBLE) || !(getParent() instanceof View))
        {
            return false;
        }
        final View parent = (View) getParent();
        return parent.isShown() && (parent.getWidth() > 0);
    }

    /**
     * Opens the band, gives it new words, or ({@code leave}) takes it away: the height travels in the layout time
     * for a band that comes and in the state time otherwise, and new words fade in. With reduced motion nothing
     * travels: only the words fade, 120 ms at most (AirUi.duration), and a band that leaves goes once they did.
     */
    private void move(final boolean leave, final boolean coming, final boolean moving)
    {
        stopMotion();
        leaving = leave;
        if (!moving)
        {
            settle();
            return;
        }
        final boolean reduced = AirUi.reducedMotion();
        final float alphaFrom;
        final float alphaTo;
        if (leave)
        {
            alphaFrom = content.getAlpha();
            alphaTo = reduced ? 0f : alphaFrom;
        }
        else
        {
            alphaFrom = (reduced || !coming) ? 0f : content.getAlpha();
            alphaTo = 1f;
        }
        fromHeight = (getVisibility() == VISIBLE) ? getHeight() : 0;
        toNothing = leave && !reduced;
        travelled = reduced ? 1f : 0f;
        content.setAlpha(alphaFrom);
        requestLayout();

        @IntegerRes final int duration = (coming && !leave) ? R.integer.kq_dur_layout : R.integer.kq_dur_state;
        final ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(AirUi.duration(getContext(), duration));
        animator.setInterpolator(easeOut);
        animator.addUpdateListener(animation -> {
            final float t = (float) animation.getAnimatedValue();
            if (!reduced)
            {
                travelled = t;
                requestLayout();
            }
            content.setAlpha(alphaFrom + (alphaTo - alphaFrom) * t);
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
                if (cancelled)
                {
                    return;
                }
                motion = null;
                settle();
            }
        });
        motion = animator;
        animator.start();
    }

    /** The end of a motion, or a change no one sees: the band at its height, or gone. */
    void settle()
    {
        fromHeight = 0;
        toNothing = false;
        travelled = 1f;
        if (content != null)
        {
            content.setAlpha(1f);
        }
        if (leaving)
        {
            leaving = false;
            setVisibility(GONE);
        }
        requestLayout();
    }

    private void stopMotion()
    {
        final ValueAnimator running = motion;
        motion = null;
        if (running != null)
        {
            running.cancel();
        }
    }

    // ---- measure, layout and the rule under the band ----

    @Override
    protected void onMeasure(final int widthMeasureSpec, final int heightMeasureSpec)
    {
        final int width = MeasureSpec.getSize(widthMeasureSpec);
        if (content == null)
        {
            setMeasuredDimension(width, 0);
            return;
        }
        content.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        contentHeight = content.getMeasuredHeight();
        final int full = contentHeight + hairline;
        final int target = toNothing ? 0 : full;
        setMeasuredDimension(width, Math.round(fromHeight + (target - fromHeight) * travelled));
    }

    @Override
    protected void onLayout(final boolean changed, final int left, final int top, final int right, final int bottom)
    {
        if (content == null)
        {
            return;
        }
        // The words stand on the rule: as the band opens they come down from under the header.
        final int contentTop = (bottom - top) - hairline - contentHeight;
        content.layout(0, contentTop, right - left, contentTop + contentHeight);
    }

    @Override
    protected void dispatchDraw(@NonNull final Canvas canvas)
    {
        super.dispatchDraw(canvas);
        canvas.drawRect(0f, getHeight() - hairline, getWidth(), getHeight(), rule);
    }

    @Override
    public boolean shouldDelayChildPressedState()
    {
        return false;
    }
}
