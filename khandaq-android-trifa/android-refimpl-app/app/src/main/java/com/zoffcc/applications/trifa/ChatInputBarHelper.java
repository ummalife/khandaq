package com.zoffcc.applications.trifa;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.OvalShape;
import android.util.TypedValue;
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

    /**
     * Send button glyph: a white paper plane on a brand-green circle.
     *
     * KHANDAQ (18.08): iOS draws "paperplane.circle.fill" in #029B7D (Figma). Android used a bare
     * Material send arrow tinted grey, so the same button looked different on the two platforms.
     * Building it as a LayerDrawable keeps the circle tied to the icon, so swapping mic <-> send
     * does not leave a green circle behind the microphone.
     */
    static Drawable makeCircularSendIcon(final Context context)
    {
        final int sizePx = dpToPx(context, CHAT_INPUT_ICON_DP);
        final ShapeDrawable circle = new ShapeDrawable(new OvalShape());
        circle.getPaint().setColor(context.getResources().getColor(R.color.khandaq_teal));
        circle.setIntrinsicWidth(sizePx);
        circle.setIntrinsicHeight(sizePx);

        final Drawable glyph = new com.mikepenz.iconics.IconicsDrawable(context).
                icon(com.mikepenz.google_material_typeface_library.GoogleMaterial.Icon.gmd_send).
                color(Color.WHITE).
                sizeDp(CHAT_INPUT_ICON_DP - 12);

        final LayerDrawable layered = new LayerDrawable(new Drawable[]{circle, glyph});
        final int inset = dpToPx(context, 6);
        layered.setLayerInset(1, inset, inset, inset, inset);
        return layered;
    }

    private static int dpToPx(final Context context, final int dp)
    {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                                               context.getResources().getDisplayMetrics());
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
        applyIconTint(micSendButton, false);
        micSendButton.setImageDrawable(micIcon);
        micSendButton.setColorFilter(null);
    }

    /// Send mode paints itself; mic mode keeps the input-bar icon tint.
    private static void applyIconTint(final ImageButton button, final boolean sendMode)
    {
        if (button == null)
        {
            return;
        }

        if (sendMode)
        {
            androidx.core.widget.ImageViewCompat.setImageTintList(button, null);
        }
        else
        {
            androidx.core.widget.ImageViewCompat.setImageTintList(button,
                    android.content.res.ColorStateList.valueOf(
                            button.getResources().getColor(R.color.tg_chat_input_icon)));
        }
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
                    // The XML puts app:tint on this button for the grey microphone. The send icon
                    // brings its own colours (white plane on a green circle), and leaving the tint on
                    // flattened it into a plain grey disc — clear it in send mode, restore for the mic.
                    applyIconTint(button, showSend);
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
