package com.zoffcc.applications.trifa;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.khandaq.messenger.R;

/**
 * KHANDAQ (calls stage, 26.11): the real call screen (activity_calling, AirCallStageController and the two
 * picture views) on made-up pictures, for the screenshots the call stage spec asks for. Debug builds only,
 * started with adb. It opens no camera and no microphone and starts no call: the other person's picture is a
 * test card bitmap, with a green strip past its width as a decoder's row padding leaves it (it must never
 * show), and your camera's box is painted with a test card through its TextureView.
 *
 * The window is set up as CallingActivity sets up its own (the night resources, the ground, the cutout, the
 * bars hidden), and the controls show as they do at that moment of a call.
 *
 * Extras:
 * - scene: video (default), wide (the other person's picture is landscape), swapped, bigger, audio, ringing;
 * - own: portrait or landscape, the frame of your camera (default: the way the display stands);
 * - turn: what the accelerometer would say, 0 upright or 270 for a display at ROTATION_90.
 * Logcat tag trifa.AirCallHarness says "ready" once the scene is on.
 *
 * It handles the same configuration changes as CallingActivity, so a display turned while it is open goes
 * through onConfigurationChanged as on a call: the camera's card turns with it ("turned" in the log).
 */
public final class AirCallStageHarnessActivity extends AppCompatActivity
{
    private static final String TAG = "trifa.AirCallHarness";
    /** A decoder's row padding: 16 pixels past the picture's width, painted green so a leak would show. */
    private static final int STRIDE_PAD = 16;
    private static final int THEIR_GROUND = 0xFF35506B;
    private static final int OWN_GROUND = 0xFF6B4F3A;
    private static final int MARK = 0xFFFFD54F;
    private static final int PAD_GREEN = 0xFF00FF00;
    private static final String NAME = "Amina Yusupova";

    private AirCallStageController stageController;
    private CameraSurfacePreview camera;
    private boolean video;

    /** As CallingActivity: the night resources whatever the app's theme is (R12). */
    @Override
    protected void attachBaseContext(final Context newBase)
    {
        getDelegate().setLocalNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        super.attachBaseContext(newBase);
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_calling);
        styleWindow();
        hideLegacy();

        final String scene = valueOr(getIntent().getStringExtra("scene"), "video");
        final boolean ringing = "ringing".equals(scene);
        final boolean audio = "audio".equals(scene);
        video = !ringing && !audio;
        CallingActivity.device_orientation = getIntent().getIntExtra("turn", 0);

        showHeader(ringing);
        showControls(ringing, audio);

        final View selfBox = findViewById(R.id.video_box_self_preview_01);
        camera = findViewById(R.id.camera_surfaceview);
        selfBox.setVisibility(video ? View.VISIBLE : View.GONE);
        camera.setVisibility(View.VISIBLE);

        stageController = new AirCallStageController(this);
        stageController.setPerson(NAME, null);
        if (!video)
        {
            ready(scene);
            return;
        }

        final boolean displayWide = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        final boolean ownWide = "landscape".equals(valueOr(getIntent().getStringExtra("own"), displayWide ? "landscape"
                                                                                                             : "portrait"));
        final int ownW = ownWide ? 1280 : 720;
        final int ownH = ownWide ? 720 : 1280;
        final boolean theirWide = "wide".equals(scene);
        final int theirW = theirWide ? 1280 : 720;
        final int theirH = theirWide ? 720 : 1280;
        final CustomVideoImageView remote = findViewById(R.id.video_view);
        remote.post(() -> remote.setBitmap(theirCard(theirW, theirH), theirW, theirH));
        paintOwnCard(camera, ownW, ownH, 0, () -> {
            stageController.onCameraFrame(ownW, ownH);
            selfBox.postDelayed(() -> {
                if ("swapped".equals(scene))
                {
                    selfBox.performClick();
                }
                else if ("bigger".equals(scene))
                {
                    selfBox.performLongClick();
                }
                ready(scene);
            }, 600);
        });
    }

    /**
     * As CallingActivity.onConfigurationChanged: the camera's picture turns with the display (CameraWrapper
     * refreshDisplayOrientation; here the card is painted again the new way round, and the accelerometer is
     * taken to agree with the display) and the stage lays out again.
     */
    @Override
    public void onConfigurationChanged(@NonNull final Configuration newConfig)
    {
        super.onConfigurationChanged(newConfig);
        CallingActivity.device_orientation = (360 - displayDegrees()) % 360;
        if (video)
        {
            final boolean wide = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE;
            final int w = wide ? 1280 : 720;
            final int h = wide ? 720 : 1280;
            paintOwnCard(camera, w, h, 0, () -> stageController.onCameraFrame(w, h));
        }
        stageController.requestRelayout();
        getWindow().getDecorView().postDelayed(() -> Log.i(TAG, "turned " + displayDegrees()), 1200);
    }

    private int displayDegrees()
    {
        switch (getWindowManager().getDefaultDisplay().getRotation())
        {
            case Surface.ROTATION_90:
                return 90;
            case Surface.ROTATION_180:
                return 180;
            case Surface.ROTATION_270:
                return 270;
            default:
                return 0;
        }
    }

    @Override
    public void onWindowFocusChanged(final boolean hasFocus)
    {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus)
        {
            hideBars();
        }
    }

    /** CallingActivity.hideSystemUI, with the night resources (no light bars). */
    private void hideBars()
    {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN);
    }

    private static String valueOr(final String value, final String fallback)
    {
        return (value == null) ? fallback : value;
    }

    /** "ready" once the scene has settled and the bars are out of sight, as they are on a call. */
    private void ready(final String scene)
    {
        getWindow().getDecorView().postDelayed(() -> readyWhenBarsHidden(scene, 0), 700);
    }

    private void readyWhenBarsHidden(final String scene, final int tries)
    {
        final View decor = getWindow().getDecorView();
        final WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(decor);
        final boolean barsShown = (insets != null) && (insets.isVisible(WindowInsetsCompat.Type.statusBars())
                                                       || insets.isVisible(WindowInsetsCompat.Type.navigationBars()));
        if (barsShown && (tries < 40))
        {
            hideBars();
            decor.postDelayed(() -> readyWhenBarsHidden(scene, tries + 1), 150);
            return;
        }
        // The bars slide away; give that a moment to end before the picture is taken.
        decor.postDelayed(() -> Log.i(TAG, "ready " + scene + (barsShown ? " (bars still shown)" : "")), 500);
    }

    /** CallingActivity.style_call_window. */
    private void styleWindow()
    {
        final int ground = AirUi.color(this, R.color.kq_lock_top);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(ground));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
        {
            final WindowManager.LayoutParams attrs = getWindow().getAttributes();
            attrs.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(attrs);
        }
        getWindow().setStatusBarColor(ground);
        getWindow().setNavigationBarColor(ground);
    }

    /** CallingActivity.hide_legacy_call_debug_ui, and the debug word it keeps invisible. */
    private void hideLegacy()
    {
        final int[] legacy = {R.id.quality_slider_container, R.id.video_box_right_top_01,
                              R.id.video_box_right_volumeslider_01, R.id.video_box_right_video_add_delay_slider_01,
                              R.id.video_box_aec, R.id.audio_bar_in_v, R.id.audio_bar_out_v, R.id.video_speaker_aec};
        for (final int id : legacy)
        {
            final View v = findViewById(id);
            if (v != null)
            {
                v.setVisibility(View.GONE);
            }
        }
        findViewById(R.id.debug001_text).setVisibility(View.INVISIBLE);
    }

    /** The name, the state (the timer in the heading size with tabular figures, as show_call_state) and the link. */
    private void showHeader(final boolean ringing)
    {
        final TextView name = findViewById(R.id.air_call_name);
        name.setText(NAME);
        final TextView state = findViewById(R.id.right_left_text_1);
        if (ringing)
        {
            state.setText(R.string.air_call_incoming);
        }
        else
        {
            state.setTextAppearance(R.style.TextAppearance_KQ_Heading);
            state.setTextColor(AirUi.color(this, R.color.kq_fixed_white));
            state.setFontFeatureSettings("tnum");
            state.setText(AirUi.localDigits(this, HelperCall.callDuration(252)));
        }
        final AirLinkMarkView mark = findViewById(R.id.air_call_link_mark);
        mark.setState(AirConversationModel.LinkState.DIRECT);
        ((TextView) findViewById(R.id.air_call_link_text)).setText(R.string.air_call_line_direct);
    }

    /** CallingActivity.apply_call_controls for the moment: ringing (a video call) or on a call. */
    private void showControls(final boolean ringing, final boolean audio)
    {
        show(R.id.air_call_answer_column, ringing);
        show(R.id.air_call_video_column, ringing);
        show(R.id.air_call_mic_column, !ringing);
        show(R.id.air_call_speaker_column, !ringing);
        show(R.id.air_call_camera_column, !ringing && !audio);
        show(R.id.air_call_misc_column, false);
        final ViewGroup row = findViewById(R.id.bottom);
        final View decline = findViewById(R.id.air_call_decline_column);
        row.removeView(decline);
        row.addView(decline, ringing ? 0 : row.getChildCount());
        ((TextView) findViewById(R.id.air_call_decline_label)).setText(ringing ? R.string.air_call_decline
                                                                                : R.string.air_call_end);
        final ImageButton mic = findViewById(R.id.mute_button);
        mic.setImageResource(R.drawable.kq_ic_mic);
        mic.setActivated(false);
        findViewById(R.id.audio_speaker_button).setActivated(!audio);
    }

    private void show(final int id, final boolean shown)
    {
        findViewById(id).setVisibility(shown ? View.VISIBLE : View.GONE);
    }

    /** Your camera's test card, painted into the preview's texture at the frame's size, once it is there. */
    private void paintOwnCard(final CameraSurfacePreview camera, final int w, final int h, final int tries,
                              final Runnable then)
    {
        final SurfaceTexture texture = camera.isAvailable() ? camera.getSurfaceTexture() : null;
        if (texture == null)
        {
            if (tries < 200)
            {
                camera.postDelayed(() -> paintOwnCard(camera, w, h, tries + 1, then), 50);
            }
            else
            {
                Log.w(TAG, "own card: the texture never came");
            }
            return;
        }
        texture.setDefaultBufferSize(w, h);
        final Canvas canvas = camera.lockCanvas();
        if (canvas == null)
        {
            camera.postDelayed(() -> paintOwnCard(camera, w, h, tries + 1, then), 50);
            return;
        }
        drawCard(canvas, w, h, OWN_GROUND, "YOU", w + " x " + h);
        camera.unlockCanvasAndPost(canvas);
        then.run();
    }

    /** The other person's test card, in a bitmap wider than the picture by the decoder's row padding. */
    private static Bitmap theirCard(final int w, final int h)
    {
        final Bitmap bitmap = Bitmap.createBitmap(w + STRIDE_PAD, h, Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(PAD_GREEN);
        canvas.save();
        canvas.clipRect(0, 0, w, h);
        drawCard(canvas, w, h, THEIR_GROUND, "THEM", w + " x " + h);
        canvas.restore();
        return bitmap;
    }

    /** A plain card: the thirds, a circle in the middle, marks in the corners (what a fill cuts) and the size. */
    private static void drawCard(final Canvas canvas, final int w, final int h, final int ground, final String title,
                                 final String size)
    {
        canvas.drawColor(ground);
        final float unit = Math.min(w, h);
        final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        line.setColor(0x66FFFFFF);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(Math.max(2f, unit / 240f));
        for (int i = 1; i < 3; i++)
        {
            canvas.drawLine(w * i / 3f, 0, w * i / 3f, h, line);
            canvas.drawLine(0, h * i / 3f, w, h * i / 3f, line);
        }
        canvas.drawCircle(w / 2f, h / 2f, unit * 0.2f, line);

        final Paint mark = new Paint(Paint.ANTI_ALIAS_FLAG);
        mark.setColor(MARK);
        mark.setStrokeWidth(Math.max(6f, unit / 60f));
        final float m = unit * 0.12f;
        final float e = mark.getStrokeWidth() / 2f;
        canvas.drawLine(e, e, m, e, mark);
        canvas.drawLine(e, e, e, m, mark);
        canvas.drawLine(w - e, e, w - m, e, mark);
        canvas.drawLine(w - e, e, w - e, m, mark);
        canvas.drawLine(e, h - e, m, h - e, mark);
        canvas.drawLine(e, h - e, e, h - m, mark);
        canvas.drawLine(w - e, h - e, w - m, h - e, mark);
        canvas.drawLine(w - e, h - e, w - e, h - m, mark);

        final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(Color.WHITE);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(unit * 0.12f);
        canvas.drawText(title, w / 2f, h / 2f - unit * 0.26f, text);
        text.setTextSize(unit * 0.06f);
        text.setTypeface(Typeface.DEFAULT);
        canvas.drawText(size, w / 2f, h / 2f + unit * 0.32f, text);
    }
}
