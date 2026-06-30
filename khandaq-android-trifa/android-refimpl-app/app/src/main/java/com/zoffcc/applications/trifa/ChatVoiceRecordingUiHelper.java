package com.zoffcc.applications.trifa;

import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import org.khandaq.messenger.R;

/**
 * Telegram-style inline voice recording bar (replaces input bar, not fullscreen overlay).
 */
final class ChatVoiceRecordingUiHelper
{
    interface RecordingActionListener
    {
        void onSend();

        void onCancel();
    }

    private static final float SWIPE_CANCEL_MIN_PX = 120f;

    private final View inputBar;
    private final View recordingBar;
    private final TextView timerView;
    private RecordingActionListener actionListener;
    private float swipeStartX;
    private boolean swipeCancelTriggered;

    private ChatVoiceRecordingUiHelper(final View inputBar,
                                       final View recordingBar,
                                       final TextView timerView,
                                       final TextView cancelView,
                                       final ImageButton sendButton)
    {
        this.inputBar = inputBar;
        this.recordingBar = recordingBar;
        this.timerView = timerView;

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

        if (inputBar == null || recordingBar == null || timerView == null
                || cancelView == null || sendButton == null)
        {
            return null;
        }

        final ChatVoiceRecordingUiHelper helper = new ChatVoiceRecordingUiHelper(
                inputBar, recordingBar, timerView, cancelView, sendButton);
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
            final View dot = recordingBar.findViewById(R.id.voice_recording_dot);
            if (dot != null)
            {
                final android.view.animation.AlphaAnimation blink =
                        new android.view.animation.AlphaAnimation(1f, 0.2f);
                blink.setDuration(600);
                blink.setRepeatMode(android.view.animation.Animation.REVERSE);
                blink.setRepeatCount(android.view.animation.Animation.INFINITE);
                dot.startAnimation(blink);
            }
        }
        setTimerText("0:00");
    }

    void hide()
    {
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
