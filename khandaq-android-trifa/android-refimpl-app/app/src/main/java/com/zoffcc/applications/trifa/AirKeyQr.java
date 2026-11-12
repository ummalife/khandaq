package com.zoffcc.applications.trifa;

import android.app.Activity;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import org.khandaq.messenger.R;

/**
 * KHANDAQ (redesign, stage 3b): the QR code of a key in a dialog, under its heading and over the key in
 * groups of four: a person's public key in the contact dossier, a group key in the group dossier.
 */
final class AirKeyQr
{
    private static final String TAG = "trifa.AirKeyQr";

    private AirKeyQr()
    {
    }

    /**
     * Draws the code of {@code payload} off the main thread, then shows it over {@code activity} with
     * {@code key} under it. A group's payload is its link, "khandaq:group:" and the key, as on iOS, so
     * a camera that reads it knows a group from a person.
     */
    static void show(final Activity activity, final String payload, final String key, final String heading,
                     final String subtitle)
    {
        new Thread(() -> {
            Bitmap bitmap = null;
            try
            {
                bitmap = ProfileContentFragment.encodeAsBitmap(payload);
            }
            catch (Exception e)
            {
                Log.i(TAG, "qr:EE:" + e.getMessage());
            }
            final Bitmap qr = bitmap;
            if (qr != null)
            {
                activity.runOnUiThread(() -> showBitmap(activity, qr, heading, subtitle, key));
            }
        }, "air-key-qr").start();
    }

    static void showBitmap(final Activity activity, final Bitmap qr, final String heading, final String subtitle,
                           final String key)
    {
        if (activity.isFinishing() || activity.isDestroyed())
        {
            return;
        }
        final Resources res = activity.getResources();
        final LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.addView(text(activity, subtitle, R.style.TextAppearance_KQ_Caption));
        // Square modules: the code is drawn without smoothing, on white, so a camera reads it in both themes.
        final BitmapDrawable drawable = new BitmapDrawable(res, qr);
        drawable.setFilterBitmap(false);
        drawable.setAntiAlias(false);
        final ImageView image = new ImageView(activity);
        image.setImageDrawable(drawable);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        final GradientDrawable plate = new GradientDrawable();
        plate.setColor(AirUi.color(activity, R.color.kq_fixed_white));
        plate.setCornerRadius(res.getDimension(R.dimen.kq_radius_control));
        image.setBackground(plate);
        final int pad = res.getDimensionPixelSize(R.dimen.kq_space_12);
        image.setPadding(pad, pad, pad, pad);
        image.setContentDescription(heading);
        final int side = Math.round(240f * res.getDisplayMetrics().density);
        final LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(side, side);
        imageParams.topMargin = res.getDimensionPixelSize(R.dimen.kq_space_16);
        content.addView(image, imageParams);
        final TextView keyText = text(activity, AirConversationModel.keyLines(key, 4), R.style.TextAppearance_KQ_Mono);
        keyText.setTextDirection(View.TEXT_DIRECTION_LTR);
        // Under the code, centred with it (the text alignment, not the gravity, decides here).
        keyText.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        keyText.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        final LinearLayout.LayoutParams keyParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        keyParams.topMargin = res.getDimensionPixelSize(R.dimen.kq_space_12);
        content.addView(keyText, keyParams);

        // The dialog's own side margins around the content.
        final FrameLayout box = new FrameLayout(activity);
        final int boxSide = res.getDimensionPixelSize(R.dimen.kq_space_24);
        box.setPaddingRelative(boxSide, res.getDimensionPixelSize(R.dimen.kq_space_8), boxSide, 0);
        box.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                          ViewGroup.LayoutParams.WRAP_CONTENT));
        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(heading)
                .setView(box)
                .setPositiveButton(R.string.MainActivity_button_ok, null)
                .create();
        AirUi.showDialog(activity, dialog, false);
    }

    private static TextView text(final Activity activity, final String words, final int appearance)
    {
        final TextView view = AirScreenKit.text(activity, appearance, R.color.kq_ink_2);
        view.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        view.setText(words);
        return view;
    }
}
