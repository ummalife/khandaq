package com.zoffcc.applications.trifa;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * KHANDAQ (calls stage, 26.11): the geometry of the call screen's stage, the rules one spec gives iOS,
 * Android and the desktop alike (R1 to R6 and R9): how the big picture scales, the size of your own
 * picture, the place it takes clear of the header and the controls and the corner a release snaps it to.
 * Plain numbers, no views, so the unit tests pin every rule (AirCallStageTest); AirCallStageController
 * applies them to the screen.
 */
final class AirCallStage
{
    /** R3: the short side of your own picture is this share of the stage's short side, in dp. */
    static final float SELF_SHARE = 0.26f;
    static final int SELF_MIN_DP = 88;
    static final int SELF_MAX_DP = 168;
    /** KHANDAQ (#130): a long press makes your own picture this much bigger, another one puts it back. */
    static final float SELF_ENLARGE = 2.2f;
    /** R6: a release lands where the picture's centre would be after this long at the speed it left with. */
    static final float SNAP_PROJECTION_S = 0.15f;
    /** R9: the person's tile of an audio call on a phone, and from a tablet's width on. */
    static final int AVATAR_PHONE_DP = 112;
    static final int AVATAR_LARGE_DP = 128;
    static final int LARGE_SCREEN_DP = 600;
    /** R11: in a short window the tile gives way to the room the middle leaves, and under this it stays out. */
    static final int AVATAR_MIN_DP = 64;
    /** R7: four columns of 96 at most, so the circles do not drift apart on a tablet. */
    static final int ROW_MAX_DP = 4 * 96;

    static final int CORNER_TOP_LEFT = 0;
    static final int CORNER_TOP_RIGHT = 1;
    static final int CORNER_BOTTOM_LEFT = 2;
    static final int CORNER_BOTTOM_RIGHT = 3;

    enum Scale
    {
        /** Covers the stage, the overflow cut off evenly. */
        FILL,
        /** Stands whole inside the stage, the rest in fixed-black. */
        FIT
    }

    private AirCallStage()
    {
    }

    /** Taller than wide; a square counts as landscape. */
    static boolean portrait(final float w, final float h)
    {
        return h > w;
    }

    /**
     * R1: a picture turned the way the stage is fills it; one turned the other way fits, so a landscape
     * camera on a portrait phone is never cut down to its middle third. Unknown sizes fill.
     */
    static Scale scaleFor(final float contentW, final float contentH, final float stageW, final float stageH)
    {
        if (!(contentW > 0f) || !(contentH > 0f) || !(stageW > 0f) || !(stageH > 0f))
        {
            return Scale.FILL;
        }
        return portrait(contentW, contentH) == portrait(stageW, stageH) ? Scale.FILL : Scale.FIT;
    }

    /** The one factor that takes the content to the stage's size, the same on both axes. */
    static float factor(final float contentW, final float contentH, final float stageW, final float stageH,
                        final Scale scale)
    {
        if (!(contentW > 0f) || !(contentH > 0f) || !(stageW > 0f) || !(stageH > 0f))
        {
            return 1f;
        }
        final float fx = stageW / contentW;
        final float fy = stageH / contentH;
        return scale == Scale.FILL ? Math.max(fx, fy) : Math.min(fx, fy);
    }

    /** R1: where the content stands, centred with its aspect kept, {left, top, right, bottom}; a fill overflows. */
    static float[] place(final float contentW, final float contentH, final float stageW, final float stageH,
                         final Scale scale)
    {
        if (!(contentW > 0f) || !(contentH > 0f) || !(stageW > 0f) || !(stageH > 0f))
        {
            return new float[]{0f, 0f, Math.max(0f, stageW), Math.max(0f, stageH)};
        }
        final float f = factor(contentW, contentH, stageW, stageH, scale);
        final float w = contentW * f;
        final float h = contentH * f;
        final float left = (stageW - w) / 2f;
        final float top = (stageH - h) / 2f;
        return new float[]{left, top, left + w, top + h};
    }

    /**
     * R2: what a TextureView needs to show a frame with its own aspect, {sx, sy} about the view's centre:
     * the view stretches every frame to its bounds, and this undoes the stretch, filling or fitting.
     */
    static float[] stretchUndo(final float frameW, final float frameH, final float viewW, final float viewH,
                               final Scale scale)
    {
        if (!(frameW > 0f) || !(frameH > 0f) || !(viewW > 0f) || !(viewH > 0f))
        {
            return new float[]{1f, 1f};
        }
        final float f = factor(frameW, frameH, viewW, viewH, scale);
        return new float[]{frameW * f / viewW, frameH * f / viewH};
    }

    /** R3: the short side of your own picture, dp: a quarter of the stage's short side, kept between the bounds. */
    static int selfShortSideDp(final float stageWdp, final float stageHdp)
    {
        final int s = Math.round(SELF_SHARE * Math.min(stageWdp, stageHdp));
        return Math.max(SELF_MIN_DP, Math.min(SELF_MAX_DP, s));
    }

    /**
     * R2, R3: the size of your own picture, px, {width, height}, for a stage in px. The box takes the aspect
     * of the frame as it shows (after the camera's turn), 3:4 portrait while that is not known yet.
     */
    static int[] selfSize(final float stageW, final float stageH, final float density, final float frameW,
                          final float frameH)
    {
        final boolean known = frameW > 0f && frameH > 0f;
        final float fw = known ? frameW : 3f;
        final float fh = known ? frameH : 4f;
        final float d = density > 0f ? density : 1f;
        final int shortDp = selfShortSideDp(stageW / d, stageH / d);
        final int longDp = Math.round(shortDp * (Math.max(fw, fh) / Math.min(fw, fh)));
        final int shortPx = Math.round(shortDp * d);
        final int longPx = Math.round(longDp * d);
        return portrait(fw, fh) ? new int[]{shortPx, longPx} : new int[]{longPx, shortPx};
    }

    /** KHANDAQ (#130): the long-pressed size, the same aspect, never past the area it stands in. */
    static int[] enlarged(final int[] size, final float maxW, final float maxH)
    {
        return fitInside(Math.round(size[0] * SELF_ENLARGE), Math.round(size[1] * SELF_ENLARGE), maxW, maxH);
    }

    /** A box scaled down with its aspect kept until it fits maxW by maxH; never scaled up. */
    static int[] fitInside(final int w, final int h, final float maxW, final float maxH)
    {
        if (w <= 0 || h <= 0 || !(maxW > 0f) || !(maxH > 0f))
        {
            return new int[]{Math.max(0, w), Math.max(0, h)};
        }
        final double f = Math.min(maxW / (double) w, maxH / (double) h);
        if (f >= 1d)
        {
            return new int[]{w, h};
        }
        // The small epsilon keeps a side that lands exactly on the limit from flooring one pixel short.
        return new int[]{(int) Math.floor(w * f + 1e-4), (int) Math.floor(h * f + 1e-4)};
    }

    /**
     * R5: where your own picture may stand, {left, top, right, bottom}: the stage within its safe edges (the
     * cutout, the status bar, the gesture or button bar), the gap in from them.
     */
    static float[] ownArea(final float stageW, final float stageH, final float safeLeft, final float safeTop,
                           final float safeRight, final float safeBottom, final float gap)
    {
        final float left = safeLeft + gap;
        final float top = safeTop + gap;
        return new float[]{left, top, Math.max(left, stageW - safeRight - gap), Math.max(top, stageH - safeBottom - gap)};
    }

    /** R5: bottom-trailing, which is bottom-left in a right-to-left language. */
    static int defaultCorner(final boolean rtl)
    {
        return rtl ? CORNER_BOTTOM_LEFT : CORNER_BOTTOM_RIGHT;
    }

    static boolean rightSide(final int corner)
    {
        return corner == CORNER_TOP_RIGHT || corner == CORNER_BOTTOM_RIGHT;
    }

    static boolean bottomSide(final int corner)
    {
        return corner == CORNER_BOTTOM_LEFT || corner == CORNER_BOTTOM_RIGHT;
    }

    /**
     * R5: the top-left of your own picture in a corner of the area, the gap clear of what it would cross on
     * the stage, {x, y}. Each obstacle is {left, top, right, bottom} (what shows of the header, the control
     * row); an empty one counts for nothing. Crossing one sends a top corner down below it and a bottom corner
     * up above it, so on a wide stage the picture keeps its corner beside the row and the header. With no
     * room left it stays inside the area all the same; on an axis where it is bigger than the area it is
     * centred. The same rule as iOS (AirCallStage.ownRect).
     */
    static float[] origin(final int corner, final float[] area, final float w, final float h, final float[][] obstacles,
                          final float gap)
    {
        final float areaW = area[2] - area[0];
        final float areaH = area[3] - area[1];
        final float x = w > areaW ? area[0] + (areaW - w) / 2f : (rightSide(corner) ? area[2] - w : area[0]);
        if (h > areaH)
        {
            return new float[]{x, area[1] + (areaH - h) / 2f};
        }
        final boolean bottom = bottomSide(corner);
        float y = bottom ? area[3] - h : area[1];
        final float[][] clear = clearances(obstacles, gap, bottom);
        for (final float[] c : clear)
        {
            if (x < c[2] && x + w > c[0] && y < c[3] && y + h > c[1])
            {
                y = bottom ? c[1] - h : c[3];
            }
        }
        return new float[]{x, clamp(y, area[1], area[3] - h)};
    }

    /** The obstacles grown by the gap, the empty ones left out, in the order a corner meets them. */
    private static float[][] clearances(final float[][] obstacles, final float gap, final boolean fromBottom)
    {
        final List<float[]> list = new ArrayList<>();
        if (obstacles != null)
        {
            for (final float[] o : obstacles)
            {
                if (o != null && o[2] > o[0] && o[3] > o[1])
                {
                    list.add(new float[]{o[0] - gap, o[1] - gap, o[2] + gap, o[3] + gap});
                }
            }
        }
        // A top corner meets the highest first, a bottom corner the lowest.
        Collections.sort(list, (a, b) -> fromBottom ? Float.compare(b[3], a[3]) : Float.compare(a[1], b[1]));
        return list.toArray(new float[0][]);
    }

    /**
     * R6: the corner a release lands in. The box's centre goes on at the speed it was let go with (px per
     * second) for SNAP_PROJECTION_S, and the corner whose place (origin) lies nearest to that point wins.
     */
    static int snapCorner(final float left, final float top, final float w, final float h, final float vx,
                          final float vy, final float[] area, final float[][] obstacles, final float gap)
    {
        final float cx = left + w / 2f + vx * SNAP_PROJECTION_S;
        final float cy = top + h / 2f + vy * SNAP_PROJECTION_S;
        int nearest = CORNER_TOP_LEFT;
        double best = Double.MAX_VALUE;
        for (int corner = CORNER_TOP_LEFT; corner <= CORNER_BOTTOM_RIGHT; corner++)
        {
            final float[] o = origin(corner, area, w, h, obstacles, gap);
            final double d = Math.hypot(o[0] + w / 2f - cx, o[1] + h / 2f - cy);
            if (d < best)
            {
                best = d;
                nearest = corner;
            }
        }
        return nearest;
    }

    /** R6: a dragged picture stays where it can be seen and caught again: v between min and max. */
    static float clamp(final float v, final float min, final float max)
    {
        if (max < min)
        {
            return min;
        }
        return Math.max(min, Math.min(max, v));
    }

    /**
     * The turn the other person's picture still needs on screen, clockwise degrees: the phone's own turn
     * (deviceOrientation, the 0, 90, 180 or 270 CallingActivity reads from the accelerometer) less what the
     * display turned by itself. A phone held to portrait turns nothing, so the picture turns all the way; a
     * tablet that turns its display needs nothing more.
     */
    static int remainingRotation(final int deviceOrientation, final int displayRotation)
    {
        final int needed = quarter(360 - quarter(deviceOrientation));
        return quarter(needed - quarter(displayRotation));
    }

    /** Degrees brought to the nearest of 0, 90, 180 and 270. */
    static int quarter(final int degrees)
    {
        int d = degrees % 360;
        if (d < 0)
        {
            d += 360;
        }
        return ((d + 45) / 90 % 4) * 90;
    }

    /**
     * The turn a camera preview needs on a display turned displayRotation degrees (Camera.setDisplayOrientation,
     * as its documentation computes it): the sensor's own mounting, mirrored for the front camera.
     */
    static int cameraDisplayOrientation(final boolean front, final int sensorOrientation, final int displayRotation)
    {
        final int sensor = quarter(sensorOrientation);
        final int display = quarter(displayRotation);
        if (front)
        {
            return quarter(360 - (sensor + display) % 360);
        }
        return quarter(sensor - display + 360);
    }

    /** The frame as it shows after a preview turn, {width, height}: a quarter turn trades the sides. */
    static int[] shownFrame(final int width, final int height, final int orientation)
    {
        return sideways(orientation) ? new int[]{height, width} : new int[]{width, height};
    }

    /** True when the frame shows turned a quarter, so its width and height trade places on screen. */
    static boolean sideways(final int rotation)
    {
        return quarter(rotation) % 180 == 90;
    }

    /**
     * R9: with no video either way (an audio call, a call that still rings, both cameras off) the person
     * stands in the middle of the screen with their tile; as soon as a picture shows, theirs or yours, the
     * header goes to the top and leaves the stage to it. iOS decides the same (CallActiveController).
     */
    static boolean personInMiddle(final boolean remotePictureShown, final boolean ownPictureShown)
    {
        return !remotePictureShown && !ownPictureShown;
    }

    /** R9: the tile of an audio call, dp. */
    static int avatarDp(final int smallestScreenWidthDp)
    {
        return smallestScreenWidthDp >= LARGE_SCREEN_DP ? AVATAR_LARGE_DP : AVATAR_PHONE_DP;
    }

    /**
     * R9, R11: the side the person's tile takes, px: its own size while the middle has room for it, smaller in
     * a short window (landscape, split screen), and none under minSide, so the name and the state never get
     * cut off for it.
     */
    static int avatarSide(final int preferredSide, final int room, final int minSide)
    {
        return room >= minSide ? Math.min(preferredSide, room) : 0;
    }

    /**
     * R7: the size a one-line label takes so its words fit the column: its own size while they fit, else
     * smaller in proportion, but never under minSize (where the end is cut off instead).
     */
    static float labelSize(final float size, final float minSize, final float wordsWidth, final float room)
    {
        if (!(wordsWidth > 0f) || !(room > 0f) || wordsWidth <= room)
        {
            return size;
        }
        return Math.max(minSize, size * room / wordsWidth);
    }

    /** R6: which picture is big. A tap swaps them, another one swaps them back; the corner stays. */
    static final class Tiles
    {
        private boolean swapped;
        private boolean enlarged;
        private int corner;

        Tiles(final int corner)
        {
            this.corner = corner;
        }

        boolean remoteIsBig()
        {
            return !swapped;
        }

        boolean swapped()
        {
            return swapped;
        }

        void swap()
        {
            swapped = !swapped;
        }

        /** Back to the other person big, as when your camera goes away. */
        void unswap()
        {
            swapped = false;
        }

        boolean enlarged()
        {
            return enlarged;
        }

        void toggleEnlarged()
        {
            enlarged = !enlarged;
        }

        void setEnlarged(final boolean next)
        {
            enlarged = next;
        }

        int corner()
        {
            return corner;
        }

        void setCorner(final int next)
        {
            corner = next;
        }
    }
}
