package com.zoffcc.applications.trifa;

import android.content.res.ColorStateList;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import org.khandaq.messenger.R;

import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;

/**
 * Telegram-style inline voice recording bar (replaces input bar, not fullscreen overlay).
 */
final class ChatVoiceRecordingUiHelper
{
    interface RecordingActionListener
    {
        void onSend();

        void onCancel();

        /**
         * KHANDAQ (Khandaq Testers 8249): the pause button was tapped. Pauses a recording that runs or goes on
         * with a paused one, and says whether it is paused now.
         */
        default boolean onTogglePause()
        {
            return false;
        }
    }

    private static final float SWIPE_CANCEL_MIN_PX = 120f;

    private final View inputBar;
    private final View recordingBar;
    private final TextView timerView;
    private final ImageButton sendButton;
    /** KHANDAQ (Khandaq Testers 8249): pause and resume, while the recording runs hands-free. */
    private final ImageButton pauseButton;
    private RecordingActionListener actionListener;
    /** A group records by a tap, hands-free from the start; a 1:1 chat once the recording is locked. */
    private boolean pauseWhileRecording = false;
    private boolean locked = false;
    /**
     * KHANDAQ (redesign, stage 2): the one-to-one chat draws this bar with the design system. Its
     * recording dot stays still, as the design system's dots never pulse (the timer running beside it
     * already says that it records), and the send button takes the design-system glyphs.
     */
    boolean airStyle = false;
    private float swipeStartX;
    private boolean swipeCancelTriggered;

    private ChatVoiceRecordingUiHelper(final View inputBar,
                                       final View recordingBar,
                                       final TextView timerView,
                                       final TextView cancelView,
                                       final ImageButton sendButton,
                                       final ImageButton pauseButton)
    {
        this.inputBar = inputBar;
        this.recordingBar = recordingBar;
        this.timerView = timerView;
        this.sendButton = sendButton;
        this.pauseButton = pauseButton;

        if (pauseButton != null)
        {
            pauseButton.setOnClickListener(v -> {
                if (actionListener != null)
                {
                    setPausedUi(actionListener.onTogglePause());
                }
            });
        }

        cancelView.setOnClickListener(v -> {
            if (actionListener != null)
            {
                actionListener.onCancel();
            }
        });

        sendButton.setOnClickListener(v -> {
            if (actionListener != null)
            {
                actionListener.onSend();
            }
        });

        recordingBar.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked())
            {
                case MotionEvent.ACTION_DOWN:
                    swipeStartX = event.getX();
                    swipeCancelTriggered = false;
                    return true;

                case MotionEvent.ACTION_MOVE:
                    if (!swipeCancelTriggered && (event.getX() - swipeStartX) <= -SWIPE_CANCEL_MIN_PX)
                    {
                        swipeCancelTriggered = true;
                        if (actionListener != null)
                        {
                            actionListener.onCancel();
                        }
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    return true;

                default:
                    return false;
            }
        });
    }

    static ChatVoiceRecordingUiHelper bind(final View emojiBarRoot, final RecordingActionListener listener)
    {
        if (emojiBarRoot == null)
        {
            return null;
        }

        final View inputBar = emojiBarRoot.findViewById(R.id.emoji_bar_input);
        final View recordingBar = emojiBarRoot.findViewById(R.id.voice_recording_bar);
        final TextView timerView = emojiBarRoot.findViewById(R.id.voice_recording_timer);
        final TextView cancelView = emojiBarRoot.findViewById(R.id.voice_recording_cancel);
        final ImageButton sendButton = emojiBarRoot.findViewById(R.id.voice_recording_send);
        final ImageButton pauseButton = emojiBarRoot.findViewById(R.id.voice_recording_pause);

        if (inputBar == null || recordingBar == null || timerView == null
                || cancelView == null || sendButton == null)
        {
            return null;
        }

        final ChatVoiceRecordingUiHelper helper = new ChatVoiceRecordingUiHelper(
                inputBar, recordingBar, timerView, cancelView, sendButton, pauseButton);
        helper.actionListener = listener;
        return helper;
    }

    void show()
    {
        // KHANDAQ (iOS hold-to-record): the recording bar is OPAQUE and overlays the input row on
        // top — we do NOT hide the input bar, so the mic button stays alive UNDER the overlay and
        // keeps the touch gesture it captured on ACTION_DOWN (release = send, slide = cancel).
        if (recordingBar != null)
        {
            recordingBar.setVisibility(View.VISIBLE);
        }
        setPausedUi(false);
        updatePauseButton();
        setTimerText("0:00");
    }

    /**
     * KHANDAQ (Khandaq Testers 8249): a group records by a tap, so its recording can pause from the start; a 1:1
     * chat holds the mic and can pause once the recording is locked.
     */
    void setPauseWhileRecording(final boolean always)
    {
        pauseWhileRecording = always;
        updatePauseButton();
    }

    /**
     * KHANDAQ (Khandaq Testers 8249): a paused recording shows the mic, which goes on with it, and a still grey
     * dot; a running one the two bars and the red dot. TalkBack names what the button does.
     */
    void setPausedUi(final boolean paused)
    {
        if (pauseButton != null)
        {
            pauseButton.setImageResource(paused ? R.drawable.kq_ic_mic : R.drawable.air_voice_ic_pause);
            pauseButton.setContentDescription(pauseButton.getContext().getString(
                    paused ? R.string.voice_recording_resume : R.string.voice_recording_pause));
        }
        final View dot = (recordingBar == null) ? null : recordingBar.findViewById(R.id.voice_recording_dot);
        if (dot == null)
        {
            return;
        }
        dot.clearAnimation();
        if (paused)
        {
            ViewCompat.setBackgroundTintList(dot, ColorStateList.valueOf(
                    ContextCompat.getColor(dot.getContext(), R.color.kq_ink_3)));
        }
        else if (airStyle)
        {
            ViewCompat.setBackgroundTintList(dot, ColorStateList.valueOf(
                    ContextCompat.getColor(dot.getContext(), R.color.kq_danger)));
        }
        else
        {
            ViewCompat.setBackgroundTintList(dot, null);
            final android.view.animation.AlphaAnimation blink =
                    new android.view.animation.AlphaAnimation(1f, 0.2f);
            blink.setDuration(600);
            blink.setRepeatMode(android.view.animation.Animation.REVERSE);
            blink.setRepeatCount(android.view.animation.Animation.INFINITE);
            dot.startAnimation(blink);
        }
    }

    private void updatePauseButton()
    {
        if (pauseButton != null)
        {
            pauseButton.setVisibility((pauseWhileRecording || locked) ? View.VISIBLE : View.GONE);
        }
    }

    /**
     * KHANDAQ (voice lock): while locked the big bar button acts as SEND — swap the mic glyph for
     * an up-arrow so it's obvious the recording keeps running and a tap sends it.
     */
    void setLockedUi(final boolean locked)
    {
        this.locked = locked;
        updatePauseButton();
        if (sendButton != null)
        {
            if (airStyle)
            {
                sendButton.setImageResource(locked ? R.drawable.kq_ic_arrow_up : R.drawable.kq_ic_mic);
            }
            else
            {
                sendButton.setImageResource(locked ? R.drawable.baseline_arrow_upward_24
                                                   : R.drawable.baseline_keyboard_voice_24);
            }
        }
    }

    void hide()
    {
        setLockedUi(false);
        if (recordingBar != null)
        {
            final View dot = recordingBar.findViewById(R.id.voice_recording_dot);
            if (dot != null)
            {
                dot.clearAnimation();
            }
            recordingBar.setVisibility(View.GONE);
        }
        if (inputBar != null)
        {
            inputBar.setVisibility(View.VISIBLE);
        }
    }

    void setTimerText(final CharSequence text)
    {
        if (timerView != null && text != null)
        {
            timerView.setText(text);
        }
    }
}
