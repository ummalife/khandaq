package com.zoffcc.applications.trifa;

import android.graphics.drawable.Drawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import org.khandaq.messenger.R;

/**
 * Telegram-style chat input: attach | field | emoji | mic/send (animated).
 */
final class ChatInputBarHelper
{
    /** Attach / emoji / send icons in the chat input bar (matches mic ~24dp). */
    static final int CHAT_INPUT_ICON_DP = 26;

    private static final int TAG_MIC_SEND_MODE = R.id.khandaq_mic_send_mode;
    private static final long MIC_SEND_ANIM_MS = 180L;

    private ChatInputBarHelper()
    {
    }

    // The chat activities open with SOFT_INPUT_STATE_HIDDEN while the input field is
    // pre-focused, so a tap does not change focus and some IMEs (Yandex keyboard on
    // Android 16) never show up. Ask the IME explicitly on every tap.
    static void ensureImeOpensOnTap(final TextView inputField)
    {
        if (inputField == null)
        {
            return;
        }

        // KHANDAQ (paste fix): use a NON-consuming touch listener instead of an OnClickListener.
        // setOnClickListener makes the field clickable and swallows the tap as a click, so the
        // framework's insertion/paste floating toolbar never appears (tap-to-paste was dead).
        // A touch listener that returns false still lets the Editor show the caret + Paste bubble,
        // while we opportunistically ask the IME to open (Yandex-on-A16 workaround preserved).
        inputField.setOnTouchListener(new View.OnTouchListener()
        {
            @Override
            public boolean onTouch(View v, android.view.MotionEvent event)
            {
                if (event.getActionMasked() == android.view.MotionEvent.ACTION_UP)
                {
                    try
                    {
                        final android.view.inputmethod.InputMethodManager imm =
                                (android.view.inputmethod.InputMethodManager) v.getContext().
                                        getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
                        if (imm != null)
                        {
                            imm.showSoftInput(v, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
                        }
                    }
                    catch (Exception ignored)
                    {
                    }
                }
                return false; // never consume — let the Editor handle selection/caret/paste
            }
        });
    }

    static void setupAttachButton(final ImageButton attachButton,
                                  final Drawable attachIcon,
                                  final View.OnClickListener attachClickListener)
    {
        if (attachButton == null)
        {
            return;
        }

        attachButton.setImageDrawable(attachIcon);
        attachButton.setOnClickListener(attachClickListener);
    }

    static void bindMicSendTextWatcher(final TextView inputField,
                                       final ImageButton micSendButton,
                                       final Drawable micIcon,
                                       final Drawable sendIcon)
    {
        if (inputField == null || micSendButton == null)
        {
            return;
        }

        final TextWatcher watcher = new TextWatcher()
        {
            @Override
            public void beforeTextChanged(final CharSequence s, final int start, final int count, final int after)
            {
            }

            @Override
            public void onTextChanged(final CharSequence s, final int start, final int before, final int count)
            {
            }

            @Override
            public void afterTextChanged(final Editable s)
            {
                updateMicSendIcon(micSendButton, micIcon, sendIcon, HelperGeneric.has_sendable_chat_text(s));
            }
        };

        inputField.addTextChangedListener(watcher);
        updateMicSendIcon(micSendButton, micIcon, sendIcon,
                HelperGeneric.has_sendable_chat_text(inputField.getText()));
    }

    static boolean isSendMode(final ImageButton micSendButton)
    {
        return Boolean.TRUE.equals(micSendButton.getTag(TAG_MIC_SEND_MODE));
    }

    static void resetMicSendIcon(final ImageButton micSendButton, final Drawable micIcon)
    {
        if (micSendButton == null)
        {
            return;
        }

        micSendButton.animate().cancel();
        micSendButton.setScaleX(1f);
        micSendButton.setScaleY(1f);
        micSendButton.setAlpha(1f);
        micSendButton.setTag(TAG_MIC_SEND_MODE, Boolean.FALSE);
        micSendButton.setImageDrawable(micIcon);
        micSendButton.setColorFilter(null);
    }

    static void updateMicSendIcon(final ImageButton button,
                                  final Drawable micDrawable,
                                  final Drawable sendDrawable,
                                  final boolean showSend)
    {
        if (button == null)
        {
            return;
        }

        final boolean currentSend = Boolean.TRUE.equals(button.getTag(TAG_MIC_SEND_MODE));
        if (currentSend == showSend)
        {
            return;
        }

        button.setTag(TAG_MIC_SEND_MODE, showSend);
        button.animate().cancel();

        button.animate()
                .scaleX(0.35f)
                .scaleY(0.35f)
                .alpha(0f)
                .setDuration(MIC_SEND_ANIM_MS / 2)
                .withEndAction(() -> {
                    button.setImageDrawable(showSend ? sendDrawable : micDrawable);
                    button.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .alpha(1f)
                            .setDuration(MIC_SEND_ANIM_MS / 2)
                            .start();
                })
                .start();
    }
}
