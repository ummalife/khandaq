/**
 * [TRIfA], Java part of Tox Reference Implementation for Android
 * Copyright (C) 2017 Zoff <zoff@zoff.cc>
 * <p>
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * version 2 as published by the Free Software Foundation.
 * <p>
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the
 * Free Software Foundation, Inc., 51 Franklin Street, Fifth Floor,
 * Boston, MA  02110-1301, USA.
 */

package com.zoffcc.applications.trifa;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.TextureView;
import android.view.View;

import androidx.annotation.NonNull;

import java.util.List;

/**
 * Your own camera on the call screen.
 *
 * KHANDAQ (calls stage, 26.11): a TextureView, no longer a SurfaceView forced to 120x160 dp. That fixed box
 * was 3:4 while the camera shows 9:16 in portrait, so the face came out squashed, and a SurfaceView cannot
 * be clipped to round corners, turned or stacked over the other person's picture. The view now takes the
 * box AirCallStageController gives it (the camera's own aspect, R2 of the call stage spec) and undoes the
 * stretch a TextureView puts on every frame (setFrameSize), so the picture keeps its aspect, filling the
 * box or the whole stage when the pictures are swapped. The camera still starts when the surface is there
 * and stops when it goes, as it did with the SurfaceView; CallingActivity.onStop stops it too, since a
 * TextureView keeps its surface while the screen is away.
 */
public class CameraSurfacePreview extends TextureView implements TextureView.SurfaceTextureListener
{

    public static final String TAG = "trifa.CameraSurfacePrv";
    Context mContext;
    static List<Camera.Size> mSupportedPreviewSizes = null;
    static Camera.Size mPreviewSize = null;

    CameraWrapper mCameraWrapper;

    /** The frame as it shows after the camera's turn, 0 while it is not known. */
    private int frameW = 0;
    private int frameH = 0;
    private AirCallStage.Scale frameScale = AirCallStage.Scale.FILL;
    private final Matrix frameTransform = new Matrix();
    private Runnable onShownChanged = null;

    public CameraSurfacePreview(Context context, AttributeSet attrs)
    {
        super(context, attrs);
        this.mContext = getContext();
        // Until the first frame the box's own ground (lock-bottom) shows through.
        setOpaque(false);
        setSurfaceTextureListener(this);
    }

    private Camera.Size getOptimalPreviewSize_2(List<Camera.Size> sizes, int w, int h)
    {
        final double ASPECT_TOLERANCE = 0.1;
        double targetRatio = (double) h / w;

        if (sizes == null)
        {
            return null;
        }

        Camera.Size optimalSize = null;
        double minDiff = Double.MAX_VALUE;

        int targetHeight = h;

        for (Camera.Size size : sizes)
        {
            double ratio = (double) size.height / size.width;
            if (Math.abs(ratio - targetRatio) > ASPECT_TOLERANCE)
            {
                continue;
            }

            if (Math.abs(size.height - targetHeight) < minDiff)
            {
                optimalSize = size;
                minDiff = Math.abs(size.height - targetHeight);
            }
        }

        if (optimalSize == null)
        {
            minDiff = Double.MAX_VALUE;
            for (Camera.Size size : sizes)
            {
                if (Math.abs(size.height - targetHeight) < minDiff)
                {
                    optimalSize = size;
                    minDiff = Math.abs(size.height - targetHeight);
                }
            }
        }

        return optimalSize;
    }

    private Camera.Size getOptimalPreviewSize_1(List<Camera.Size> sizes, int w, int h)
    {
        final double ASPECT_TOLERANCE = 0.1;
        double targetRatio = (double) h / w;

        if (sizes == null)
        {
            return null;
        }

        Camera.Size optimalSize = null;
        double minDiff = Double.MAX_VALUE;

        int targetHeight = h;

        for (Camera.Size size : sizes)
        {
            double ratio = (double) size.width / size.height;
            if (Math.abs(ratio - targetRatio) > ASPECT_TOLERANCE)
            {
                continue;
            }
            if (Math.abs(size.height - targetHeight) < minDiff)
            {
                optimalSize = size;
                minDiff = Math.abs(size.height - targetHeight);
            }
        }

        if (optimalSize == null)
        {
            minDiff = Double.MAX_VALUE;
            for (Camera.Size size : sizes)
            {
                if (Math.abs(size.height - targetHeight) < minDiff)
                {
                    optimalSize = size;
                    minDiff = Math.abs(size.height - targetHeight);
                }
            }
        }
        return optimalSize;
    }

    public static int convertDpToPixels(float dp, Context context)
    {
        int px = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                                                 context.getResources().getDisplayMetrics());
        return px;
    }

    public static int convertSpToPixels(float sp, Context context)
    {
        int px = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp,
                                                 context.getResources().getDisplayMetrics());
        return px;
    }

    /** KHANDAQ (calls stage, 26.11): told when this preview or its box shows or hides; the stage lays out again. */
    void setOnShownChanged(final Runnable listener)
    {
        onShownChanged = listener;
    }

    @Override
    protected void onVisibilityChanged(@NonNull final View changedView, final int visibility)
    {
        super.onVisibilityChanged(changedView, visibility);
        if (onShownChanged != null)
        {
            onShownChanged.run();
        }
    }

    /** R2: the camera frame as it shows; the view keeps its aspect in whatever box it stands in. */
    void setFrameSize(final int width, final int height)
    {
        if ((width == frameW) && (height == frameH))
        {
            return;
        }
        frameW = width;
        frameH = height;
        applyFrameTransform();
    }

    /** R1, R6: fill in its box; on the whole stage, fill or fit as the stage's turn decides. */
    void setFrameScale(final AirCallStage.Scale scale)
    {
        if (scale == frameScale)
        {
            return;
        }
        frameScale = scale;
        applyFrameTransform();
    }

    int frameWidth()
    {
        return frameW;
    }

    int frameHeight()
    {
        return frameH;
    }

    private void applyFrameTransform()
    {
        final int vw = getWidth();
        final int vh = getHeight();
        if ((vw <= 0) || (vh <= 0))
        {
            return;
        }
        final float[] scale = AirCallStage.stretchUndo(frameW, frameH, vw, vh, frameScale);
        frameTransform.setScale(scale[0], scale[1], vw / 2f, vh / 2f);
        setTransform(frameTransform);
    }

    @Override
    protected void onSizeChanged(final int w, final int h, final int oldw, final int oldh)
    {
        super.onSizeChanged(w, h, oldw, oldh);
        applyFrameTransform();
    }

    @Override
    public void onSurfaceTextureAvailable(final SurfaceTexture surface, final int width, final int height)
    {
        Log.i(TAG, "onSurfaceTextureAvailable...");
        applyFrameTransform();
        try
        {
            if (CallingActivity.ca != null)
            {
                CallingActivity.ca.onCameraSurfaceReady();
            }
        }
        catch (Exception e)
        {
            Log.i(TAG, "onSurfaceTextureAvailable:EE1:" + e.getMessage());
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(final SurfaceTexture surface, final int width, final int height)
    {
        applyFrameTransform();
    }

    @Override
    public boolean onSurfaceTextureDestroyed(final SurfaceTexture surface)
    {
        Log.i(TAG, "onSurfaceTextureDestroyed...");
        try
        {
            CameraWrapper.getInstance().doStopCamera();
        }
        catch (Exception e)
        {
            Log.i(TAG, "onSurfaceTextureDestroyed:EE01:" + e.getMessage());
        }
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(final SurfaceTexture surface)
    {
    }

    /** The surface the camera previews into; null until the view is on screen. */
    public SurfaceTexture getPreviewTexture()
    {
        return isAvailable() ? getSurfaceTexture() : null;
    }
}
