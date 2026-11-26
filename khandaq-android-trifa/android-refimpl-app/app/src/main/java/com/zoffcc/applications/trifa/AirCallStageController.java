package com.zoffcc.applications.trifa;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.text.Editable;
import android.text.Layout;
import android.text.TextPaint;
import android.text.TextWatcher;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.AnimationUtils;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;

import org.khandaq.messenger.R;

import java.util.ArrayList;
import java.util.List;

/**
 * KHANDAQ (calls stage, 26.11): the call screen laid out by the call stage spec that iOS and the desktop follow
 * too. The geometry is AirCallStage's; this class puts it on the views of activity_calling.
 *
 * - The other person fills the stage, behind the header and the controls (R1, CustomVideoImageView's stage mode).
 * - Your camera stands in a small box with round corners: the camera's aspect, about a quarter of the stage's
 *   short side, in a corner 16 inside the safe edges and 16 clear of the header and the controls (R2 to R5).
 * - The box can be dragged; let go, it lands in the corner it was thrown at. A tap swaps the two pictures and a
 *   second tap swaps them back. A long press makes it bigger, as #130 did (R6).
 * - The words under the circles stay on one line, smaller where a language needs it, and the row keeps to 384 dp
 *   in the middle of a wide screen (R7).
 * - The header carries a soft shadow over the picture, and the timer is in tabular figures (R8). With no video
 *   either way the header stands in the middle with the person's tile (R9).
 *
 * Main thread only.
 */
final class AirCallStageController
{
    private static final String TAG = "trifa.AirCallStage";
    /** R7: a label shrinks to about 80 % of its size before its end is cut off. */
    private static final float LABEL_MIN_SHARE = 0.8f;
    /** R8: the shadow under the header's words, fixed-black at 50 %, blur 4 and 1 down (dp). */
    private static final float HEADER_SHADOW_ALPHA = 0.5f;
    private static final float HEADER_SHADOW_BLUR_DP = 4f;
    private static final float HEADER_SHADOW_DY_DP = 1f;
    /** R9: the initials in the person's tile take up to this share of its side. */
    private static final float AVATAR_INITIALS_SHARE = 0.36f;

    private final Activity activity;
    private final ViewGroup stage;
    private final CustomVideoImageView remote;
    private final ViewGroup selfBox;
    private final CameraSurfacePreview camera;
    private final ViewGroup chrome;
    private final View headZone;
    private final LinearLayout head;
    private final FrameLayout avatarSlot;
    private final ViewGroup row;
    private final List<TextView> labels = new ArrayList<>();
    private final List<Float> labelSizes = new ArrayList<>();
    private final float density;
    private final int gap;
    private final float tileZ;
    private final float radius;
    private final AirCallStage.Tiles tiles;
    private final Rect safe = new Rect();
    private final Rect scratch = new Rect();
    /** R5: what shows of the header and of the control row, stage px {left, top, right, bottom}; 0s when hidden. */
    private final float[] headerBox = new float[4];
    private final float[] rowBox = new float[4];
    private final float[][] obstacles = {headerBox, rowBox};
    private final ViewOutlineProvider roundTile;
    private final Drawable selfGround;
    private final Drawable selfBorder;
    private final Drawable remoteGround;
    private final Drawable remoteBorder;
    private final Drawable cameraBars;
    private final TileGestures gestures;
    private final Runnable relayoutSoon = this::relayout;
    /** Settling ends when the snap's animation ends or is cancelled (a new touch, a swap). */
    private final Animator.AnimatorListener settled = new AnimatorListenerAdapter()
    {
        @Override
        public void onAnimationCancel(final Animator animation)
        {
            settling = false;
        }

        @Override
        public void onAnimationEnd(final Animator animation)
        {
            settling = false;
        }
    };
    /** False while the expert zoom of the incoming video keeps the old way: then nothing swaps. */
    private final boolean swappable;

    /** Your camera's frame as it shows on the screen, after the preview's turn; 0 while unknown. */
    private int frameW = 0;
    private int frameH = 0;
    /** R5: where the small picture may stand, stage px {left, top, right, bottom}. */
    private float[] area = new float[]{0f, 0f, 0f, 0f};
    private int tileW = 0;
    private int tileH = 0;
    private boolean dragging = false;
    private boolean settling = false;
    private boolean pictureInPicture = false;
    @Nullable
    private AirTileView avatar = null;
    @Nullable
    private String avatarKey = null;
    private int avatarSide = 0;
    /** The view that has the small tile's touch, clicks and TalkBack actions now. */
    @Nullable
    private View wiredSmall = null;

    AirCallStageController(@NonNull final Activity activity)
    {
        this.activity = activity;
        stage = activity.findViewById(R.id.calling_activity_top_viewgroup);
        remote = activity.findViewById(R.id.video_view);
        selfBox = activity.findViewById(R.id.video_box_self_preview_01);
        camera = activity.findViewById(R.id.camera_surfaceview);
        chrome = activity.findViewById(R.id.air_call_chrome);
        headZone = activity.findViewById(R.id.air_call_head_zone);
        head = activity.findViewById(R.id.air_call_head);
        avatarSlot = activity.findViewById(R.id.air_call_avatar_slot);
        row = activity.findViewById(R.id.bottom);

        final Resources res = activity.getResources();
        density = res.getDisplayMetrics().density;
        gap = res.getDimensionPixelSize(R.dimen.kq_space_16);
        radius = res.getDimension(R.dimen.kq_radius_card);
        tileZ = res.getDimension(R.dimen.kq_space_2);
        final boolean rtl = res.getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        tiles = new AirCallStage.Tiles(AirCallStage.defaultCorner(rtl));

        roundTile = new ViewOutlineProvider()
        {
            @Override
            public void getOutline(final View view, final Outline outline)
            {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                // R4: the corners clip the picture itself, and the tile casts no shadow over the other picture.
                outline.setAlpha(0f);
            }
        };
        selfGround = ContextCompat.getDrawable(activity, R.drawable.air_call_tile_ground);
        selfBorder = ContextCompat.getDrawable(activity, R.drawable.air_call_tile_border);
        remoteGround = ContextCompat.getDrawable(activity, R.drawable.air_call_tile_ground);
        remoteBorder = ContextCompat.getDrawable(activity, R.drawable.air_call_tile_border);
        cameraBars = new ColorDrawable(AirUi.color(activity, R.color.kq_fixed_black));

        styleHeader(activity.findViewById(R.id.air_call_name), activity.findViewById(R.id.right_left_text_1));
        collectLabels(row);

        remote.setStageMode(!MainActivity.PREF__X_zoom_incoming_video);
        swappable = remote.isStageMode();
        remote.setOnStagePicture(this::requestRelayout);
        // Your camera showing or not moves the header (R9) and decides whether the pictures may swap.
        camera.setOnShownChanged(this::requestRelayout);

        gestures = new TileGestures();
        wire(selfBox, remote);

        ViewCompat.setOnApplyWindowInsetsListener(stage, (v, insets) -> {
            // R5: the safe edges are the bars as if they showed (the call screen hides them, and one swiped in must
            // not cover the header) and the cutout.
            final Insets bars = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars());
            final Insets cut = insets.getInsets(WindowInsetsCompat.Type.displayCutout());
            final int left = Math.max(bars.left, cut.left);
            final int top = Math.max(bars.top, cut.top);
            final int right = Math.max(bars.right, cut.right);
            final int bottom = Math.max(bars.bottom, cut.bottom);
            if ((left != safe.left) || (top != safe.top) || (right != safe.right) || (bottom != safe.bottom))
            {
                safe.set(left, top, right, bottom);
                chrome.setPadding(left, top, right, bottom);
            }
            return WindowInsetsCompat.CONSUMED;
        });
        stage.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> requestRelayout());
        ViewCompat.requestApplyInsets(stage);
    }

    /** Your camera's frame as it shows, after the preview's turn (CameraWrapper, on every start and turn). */
    void onCameraFrame(final int width, final int height)
    {
        if ((width == frameW) && (height == frameH))
        {
            return;
        }
        frameW = width;
        frameH = height;
        camera.setFrameSize(width, height);
        requestRelayout();
    }

    /** The person of the call, for the tile of the middle (R9): their name and their public key. */
    void setPerson(@Nullable final String title, @Nullable final String key)
    {
        final String name = (title == null) ? "" : title;
        if (avatar == null)
        {
            avatar = new AirTileView(darkContext());
            avatarSlot.addView(avatar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                                    ViewGroup.LayoutParams.MATCH_PARENT));
        }
        avatar.bind(AirTileView.Kind.PERSON, name, AirModel.Tone.index(key));
        requestRelayout();
        if ((key == null) || key.equals(avatarKey))
        {
            return;
        }
        avatarKey = key;
        final AirTileView tile = avatar;
        final Thread loader = new Thread(() -> {
            ChatBubbleUiHelper.AvatarMeta meta = null;
            try
            {
                meta = ChatBubbleUiHelper.peer_avatar_meta(HelperFriend.main_get_friend(key));
            }
            catch (Exception e)
            {
                Log.w(TAG, "setPerson:photo:EE:" + e.getMessage());
            }
            final ChatBubbleUiHelper.AvatarMeta found = meta;
            if ((found == null) || !found.loadable)
            {
                return;
            }
            tile.post(() -> {
                if (activity.isFinishing() || activity.isDestroyed() || (tile != avatar))
                {
                    return;
                }
                ChatBubbleUiHelper.load_peer_photo(activity, found, tile.photoView());
                tile.showPhoto(true);
            });
        }, "air-call-avatar");
        loader.start();
    }

    /** Picture in picture shows the other person alone; the pictures swap back on the way in (R6). */
    void setPictureInPicture(final boolean on)
    {
        pictureInPicture = on;
        if (on)
        {
            tiles.unswap();
        }
        relayout();
    }

    /** Your camera went away: the other person comes back to the stage. */
    void onCameraGone()
    {
        if (tiles.swapped())
        {
            tiles.unswap();
            relayout();
        }
    }

    void requestRelayout()
    {
        stage.removeCallbacks(relayoutSoon);
        stage.post(relayoutSoon);
    }

    /** Puts every view where the rules say. Changes only what differs, so the layout pass it causes settles. */
    void relayout()
    {
        relayout(false);
    }

    private void relayout(final boolean animateSmall)
    {
        final int w = stage.getWidth();
        final int h = stage.getHeight();
        if ((w <= 0) || (h <= 0))
        {
            return;
        }
        final boolean own = ownPictureShown();
        final boolean theirs = remote.hasStagePicture();
        placeHeader(AirCallStage.personInMiddle(theirs, own));
        capRow(w);
        if (!swappable || pictureInPicture || !own || !theirs)
        {
            tiles.unswap();
        }
        area = AirCallStage.ownArea(w, h, safe.left, safe.top, safe.right, safe.bottom, gap);
        measureObstacles();
        final float areaW = area[2] - area[0];
        final float areaH = area[3] - area[1];
        final int[] base = AirCallStage.selfSize(w, h, density, frameW, frameH);
        final int[] plain = AirCallStage.fitInside(base[0], base[1], areaW, areaH);
        // R11: the long-pressed picture grows only as far as its corner stays clear of the header and the row.
        final int[] size = tiles.enlarged() ? AirCallStage.enlargedClear(tiles.corner(), area, plain,
                                                                         AirCallStage.enlarged(base, areaW, areaH),
                                                                         obstacles, gap)
                                            : plain;
        tileW = size[0];
        tileH = size[1];

        final boolean swapped = tiles.swapped();
        final View small = swapped ? remote : selfBox;
        final View big = swapped ? selfBox : remote;
        if (small != wiredSmall)
        {
            wire(small, big);
        }
        makeBig(big);
        makeSmall(small);
        if (!dragging && (animateSmall || !settling))
        {
            final float[] o = AirCallStage.origin(tiles.corner(), area, tileW, tileH, obstacles, gap);
            moveTo(small, o[0], o[1], animateSmall);
        }
        remote.setStageBoxed(swapped);
        camera.setFrameScale(swapped ? AirCallStage.scaleFor(frameW, frameH, w, h) : AirCallStage.Scale.FILL);
        fitLabels();
    }

    /** Your camera shows: its box is up and the preview in it is on (the camera switch turns the preview off). */
    private boolean ownPictureShown()
    {
        return (selfBox.getVisibility() == View.VISIBLE) && (camera.getVisibility() == View.VISIBLE);
    }

    // ---- the header (R8, R9) ----

    private void styleHeader(final TextView name, final TextView state)
    {
        final int shade = ColorUtils.setAlphaComponent(AirUi.color(activity, R.color.kq_fixed_black),
                                                       Math.round(255 * HEADER_SHADOW_ALPHA));
        final float blur = HEADER_SHADOW_BLUR_DP * density;
        final float dy = HEADER_SHADOW_DY_DP * density;
        name.setShadowLayer(blur, 0f, dy, shade);
        state.setShadowLayer(blur, 0f, dy, shade);
        state.setFontFeatureSettings("tnum");
    }

    private void placeHeader(final boolean middle)
    {
        final FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) head.getLayoutParams();
        final int gravity = middle ? Gravity.CENTER_VERTICAL : Gravity.TOP;
        if (lp.gravity != gravity)
        {
            lp.gravity = gravity;
            head.setLayoutParams(lp);
        }
        int side = 0;
        if (middle && (avatar != null))
        {
            final int preferred = Math.round(AirCallStage.avatarDp(
                    activity.getResources().getConfiguration().smallestScreenWidthDp) * density);
            side = AirCallStage.avatarSide(preferred, avatarRoom(), Math.round(AirCallStage.AVATAR_MIN_DP * density));
        }
        if (side > 0)
        {
            final ViewGroup.LayoutParams slot = avatarSlot.getLayoutParams();
            if ((slot.width != side) || (slot.height != side))
            {
                slot.width = side;
                slot.height = side;
                avatarSlot.setLayoutParams(slot);
            }
            if (avatarSide != side)
            {
                avatarSide = side;
                avatar.setLargeInitials(side * AVATAR_INITIALS_SHARE);
            }
        }
        setVisible(avatarSlot, side > 0);
    }

    /**
     * R9, R11: the height the tile may take: the head zone less the words, the chip and the gaps at their own
     * height, so a short window shrinks the tile before anything else is cut off.
     */
    private int avatarRoom()
    {
        final int zone = headZone.getHeight();
        if ((zone <= 0) || (head.getWidth() <= 0))
        {
            return Integer.MAX_VALUE;
        }
        // Measured on their own: inside the zone the last lines would be measured as clipped.
        head.measure(View.MeasureSpec.makeMeasureSpec(head.getWidth(), View.MeasureSpec.EXACTLY),
                     View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        final int tileGap = ((ViewGroup.MarginLayoutParams) avatarSlot.getLayoutParams()).bottomMargin;
        int words = head.getMeasuredHeight();
        if (avatarSlot.getVisibility() != View.GONE)
        {
            words -= avatarSlot.getMeasuredHeight() + tileGap;
        }
        return zone - words - tileGap;
    }

    /** R9 and R12: the tile in the night colours, since the call screen is dark in both themes. */
    private Context darkContext()
    {
        final Configuration night = new Configuration(activity.getResources().getConfiguration());
        night.uiMode = (night.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | Configuration.UI_MODE_NIGHT_YES;
        final ContextThemeWrapper dark = new ContextThemeWrapper(activity, R.style.AppTheme);
        dark.applyOverrideConfiguration(night);
        return dark;
    }

    // ---- the control row (R7) ----

    private void collectLabels(final ViewGroup group)
    {
        for (int i = 0; i < group.getChildCount(); i++)
        {
            final View child = group.getChildAt(i);
            if (child instanceof TextView)
            {
                final TextView label = (TextView) child;
                labels.add(label);
                labelSizes.add(label.getTextSize());
                label.addTextChangedListener(new TextWatcher()
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
                        requestRelayout();
                    }
                });
            }
            else if (child instanceof ViewGroup)
            {
                collectLabels((ViewGroup) child);
            }
        }
    }

    private void fitLabels()
    {
        final TextPaint paint = new TextPaint();
        for (int i = 0; i < labels.size(); i++)
        {
            final TextView label = labels.get(i);
            final int room = label.getWidth() - label.getCompoundPaddingLeft() - label.getCompoundPaddingRight();
            if (room <= 0)
            {
                continue;
            }
            final float size = labelSizes.get(i);
            paint.set(label.getPaint());
            paint.setTextSize(size);
            final CharSequence text = label.getText();
            final float words = paint.measureText(text, 0, text.length());
            final float fitted = AirCallStage.labelSize(size, size * LABEL_MIN_SHARE, words, room);
            if (Math.abs(label.getTextSize() - fitted) > 0.5f)
            {
                label.setTextSize(TypedValue.COMPLEX_UNIT_PX, fitted);
            }
        }
    }

    /** R7: four columns of 96 dp at most, centred, inside the row's own side padding. */
    private void capRow(final int stageW)
    {
        final int cap = Math.round(AirCallStage.ROW_MAX_DP * density) + row.getPaddingLeft() + row.getPaddingRight();
        final int width = ((stageW - safe.left - safe.right) > cap) ? cap : ViewGroup.LayoutParams.MATCH_PARENT;
        final ViewGroup.LayoutParams lp = row.getLayoutParams();
        if (lp.width != width)
        {
            lp.width = width;
            row.setLayoutParams(lp);
        }
    }

    // ---- the two pictures (R2 to R6) ----

    /**
     * R5: what shows of the header (the tile, the words as wide as their longest line, the chip) and of the
     * control row (inside its padding), in stage px, for the small picture to keep clear of.
     */
    private void measureObstacles()
    {
        clear(headerBox);
        clear(rowBox);
        if (shown(head))
        {
            for (int i = 0; i < head.getChildCount(); i++)
            {
                final View child = head.getChildAt(i);
                if ((child.getVisibility() != View.VISIBLE) || (child.getWidth() <= 0) || (child.getHeight() <= 0))
                {
                    continue;
                }
                contentBounds(child, scratch);
                stage.offsetDescendantRectToMyCoords(child, scratch);
                union(headerBox, scratch);
            }
        }
        if (shown(row))
        {
            scratch.set(row.getPaddingLeft(), row.getPaddingTop(), row.getWidth() - row.getPaddingRight(),
                        row.getHeight() - row.getPaddingBottom());
            stage.offsetDescendantRectToMyCoords(row, scratch);
            union(rowBox, scratch);
        }
    }

    /** A child's own bounds; for words only as wide as their longest line, since they stand centred. */
    private static void contentBounds(final View child, final Rect out)
    {
        out.set(0, 0, child.getWidth(), child.getHeight());
        if (!(child instanceof TextView))
        {
            return;
        }
        final TextView text = (TextView) child;
        final Layout layout = text.getLayout();
        if ((layout == null) || (layout.getLineCount() <= 0))
        {
            return;
        }
        float left = Float.MAX_VALUE;
        float right = -Float.MAX_VALUE;
        for (int line = 0; line < layout.getLineCount(); line++)
        {
            left = Math.min(left, layout.getLineLeft(line));
            right = Math.max(right, layout.getLineRight(line));
        }
        final int dx = text.getCompoundPaddingLeft() - text.getScrollX();
        out.left = Math.max(0, (int) Math.floor(left) + dx);
        out.right = Math.max(out.left, Math.min(child.getWidth(), (int) Math.ceil(right) + dx));
    }

    private static void clear(final float[] box)
    {
        box[0] = 0f;
        box[1] = 0f;
        box[2] = 0f;
        box[3] = 0f;
    }

    private static void union(final float[] box, final Rect r)
    {
        if ((box[2] <= box[0]) || (box[3] <= box[1]))
        {
            box[0] = r.left;
            box[1] = r.top;
            box[2] = r.right;
            box[3] = r.bottom;
            return;
        }
        box[0] = Math.min(box[0], r.left);
        box[1] = Math.min(box[1], r.top);
        box[2] = Math.max(box[2], r.right);
        box[3] = Math.max(box[3], r.bottom);
    }

    private boolean shown(final View view)
    {
        return view.isShown() && view.isLaidOut() && (view.getHeight() > 0);
    }

    private void makeBig(final View view)
    {
        setFrame(view, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        view.animate().cancel();
        view.setTranslationX(0f);
        view.setTranslationY(0f);
        view.setTranslationZ(0f);
        view.setClipToOutline(false);
        view.setForeground(null);
        // R1: your camera on the stage stands on fixed-black where it is fitted.
        view.setBackground((view == selfBox) ? cameraBars : null);
    }

    private void makeSmall(final View view)
    {
        setFrame(view, tileW, tileH);
        view.setTranslationZ(tileZ);
        if (view.getOutlineProvider() != roundTile)
        {
            view.setOutlineProvider(roundTile);
        }
        view.setClipToOutline(true);
        view.setBackground((view == selfBox) ? selfGround : remoteGround);
        view.setForeground((view == selfBox) ? selfBorder : remoteBorder);
    }

    /**
     * The small tile takes the drag, the tap and the long press, and TalkBack's names for them; the big picture
     * goes back to what it does on its own: the other person's view to its zoom, your camera to nothing.
     */
    private void wire(final View small, final View big)
    {
        wiredSmall = small;
        small.setOnTouchListener(gestures);
        small.setOnClickListener(v -> swap());
        small.setOnLongClickListener(v -> toggleEnlarged());
        small.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        describeTile(small, small == remote);

        big.setOnTouchListener((big == remote) ? remote : null);
        big.setOnClickListener(null);
        big.setOnLongClickListener(null);
        big.setClickable(false);
        big.setLongClickable(false);
        ViewCompat.replaceAccessibilityAction(big, AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK,
                                              null, null);
        ViewCompat.replaceAccessibilityAction(big,
                                              AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_LONG_CLICK,
                                              null, null);
        big.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    private void setFrame(final View view, final int width, final int height)
    {
        final FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) view.getLayoutParams();
        final int gravity = Gravity.TOP | Gravity.LEFT;
        if ((lp.width != width) || (lp.height != height) || (lp.gravity != gravity) || (lp.leftMargin != 0)
            || (lp.topMargin != 0) || (lp.rightMargin != 0) || (lp.bottomMargin != 0))
        {
            lp.width = width;
            lp.height = height;
            lp.gravity = gravity;
            lp.setMargins(0, 0, 0, 0);
            view.setLayoutParams(lp);
        }
    }

    /** TalkBack names the small picture and says what a double tap and a long press do to it. */
    private void describeTile(final View view, final boolean theirs)
    {
        view.setContentDescription(activity.getString(theirs ? R.string.air_call_their_view : R.string.air_call_self_view));
        ViewCompat.replaceAccessibilityAction(view, AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK,
                                              activity.getString(R.string.air_call_swap_views), null);
        ViewCompat.replaceAccessibilityAction(view,
                                              AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_LONG_CLICK,
                                              activity.getString(tiles.enlarged() ? R.string.air_call_view_smaller
                                                                                  : R.string.air_call_view_bigger),
                                              null);
    }

    private void swap()
    {
        if (!swappable || !remote.hasStagePicture() || !ownPictureShown())
        {
            return;
        }
        tiles.swap();
        relayout();
        final View small = tiles.swapped() ? remote : selfBox;
        small.announceForAccessibility(small.getContentDescription());
    }

    private boolean toggleEnlarged()
    {
        tiles.toggleEnlarged();
        relayout();
        describeTile(tiles.swapped() ? remote : selfBox, tiles.swapped());
        return true;
    }

    private void moveTo(final View view, final float x, final float y, final boolean animate)
    {
        if (animate && !AirUi.reducedMotion())
        {
            settling = true;
            view.animate()
                .translationX(x)
                .translationY(y)
                .setDuration(AirUi.duration(activity, R.integer.kq_dur_layout))
                .setInterpolator(AnimationUtils.loadInterpolator(activity, R.interpolator.kq_ease_out))
                .setListener(settled)
                .start();
            return;
        }
        view.animate().cancel();
        settling = false;
        view.setTranslationX(x);
        view.setTranslationY(y);
    }

    private static void setVisible(final View view, final boolean visible)
    {
        final int next = visible ? View.VISIBLE : View.GONE;
        if (view.getVisibility() != next)
        {
            view.setVisibility(next);
        }
    }

    /**
     * R6: drag the small picture anywhere on the stage; let go, it goes to the corner whose place lies nearest
     * to where it was thrown. A tap is a click (swap) and a long press a long click (bigger), so TalkBack reaches
     * both.
     */
    private final class TileGestures implements View.OnTouchListener
    {
        private final int slop = ViewConfiguration.get(activity).getScaledTouchSlop();
        private final Runnable longPress = this::onLongPress;
        @Nullable
        private VelocityTracker velocity = null;
        @Nullable
        private View target = null;
        private float downX;
        private float downY;
        private float startX;
        private float startY;
        private boolean moved;
        private boolean longPressed;

        @Override
        public boolean onTouch(final View v, final MotionEvent event)
        {
            switch (event.getActionMasked())
            {
                case MotionEvent.ACTION_DOWN:
                    target = v;
                    downX = event.getRawX();
                    downY = event.getRawY();
                    v.animate().cancel();
                    settling = false;
                    startX = v.getTranslationX();
                    startY = v.getTranslationY();
                    moved = false;
                    longPressed = false;
                    if (velocity != null)
                    {
                        velocity.recycle();
                    }
                    velocity = VelocityTracker.obtain();
                    track(event);
                    v.setPressed(true);
                    v.postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                    return true;
                case MotionEvent.ACTION_MOVE:
                {
                    track(event);
                    final float dx = event.getRawX() - downX;
                    final float dy = event.getRawY() - downY;
                    if (!moved && ((dx * dx + dy * dy) > (float) (slop * slop)))
                    {
                        moved = true;
                        dragging = true;
                        v.removeCallbacks(longPress);
                        v.setPressed(false);
                        if (v.getParent() != null)
                        {
                            v.getParent().requestDisallowInterceptTouchEvent(true);
                        }
                    }
                    if (moved)
                    {
                        v.setTranslationX(AirCallStage.clamp(startX + dx, 0f, stage.getWidth() - v.getWidth()));
                        v.setTranslationY(AirCallStage.clamp(startY + dy, 0f, stage.getHeight() - v.getHeight()));
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    track(event);
                    v.removeCallbacks(longPress);
                    v.setPressed(false);
                    if (moved)
                    {
                        float vx = 0f;
                        float vy = 0f;
                        if (velocity != null)
                        {
                            velocity.computeCurrentVelocity(1000);
                            vx = velocity.getXVelocity();
                            vy = velocity.getYVelocity();
                        }
                        settle(v, vx, vy);
                    }
                    else if (!longPressed)
                    {
                        v.performClick();
                    }
                    reset();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    v.removeCallbacks(longPress);
                    v.setPressed(false);
                    if (moved)
                    {
                        settle(v, 0f, 0f);
                    }
                    reset();
                    return true;
                default:
                    return true;
            }
        }

        /** The finger in screen pixels: the box moves under it, so its own coordinates would hardly change. */
        private void track(final MotionEvent event)
        {
            if (velocity == null)
            {
                return;
            }
            final MotionEvent onScreen = MotionEvent.obtain(event);
            onScreen.setLocation(event.getRawX(), event.getRawY());
            velocity.addMovement(onScreen);
            onScreen.recycle();
        }

        private void onLongPress()
        {
            final View v = target;
            if ((v != null) && !moved)
            {
                longPressed = true;
                v.setPressed(false);
                v.performLongClick();
            }
        }

        private void settle(final View v, final float vx, final float vy)
        {
            dragging = false;
            tiles.setCorner(AirCallStage.snapCorner(v.getTranslationX(), v.getTranslationY(), v.getWidth(),
                                                    v.getHeight(), vx, vy, area, obstacles, gap));
            relayout(true);
        }

        private void reset()
        {
            dragging = false;
            target = null;
            if (velocity != null)
            {
                velocity.recycle();
                velocity = null;
            }
        }
    }
}
