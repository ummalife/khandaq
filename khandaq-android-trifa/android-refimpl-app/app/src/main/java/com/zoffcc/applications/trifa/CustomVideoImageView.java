package com.zoffcc.applications.trifa;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Display;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;

import org.khandaq.messenger.R;

import static com.zoffcc.applications.trifa.CallingActivity.VIDEO_ROTATION_LOCK;
import static com.zoffcc.applications.trifa.CallingActivity.device_orientation;
import static com.zoffcc.applications.trifa.MainActivity.PREF__X_zoom_incoming_video;

public class CustomVideoImageView extends androidx.appcompat.widget.AppCompatImageView implements View.OnTouchListener
{
    private static final String TAG = "trifa.CustomVImgVw";
    private static Matrix matrix = new Matrix();
    private Matrix savedMatrix = new Matrix();

    static final int NONE = 0;
    static final int DRAG = 1;
    static final int ZOOM = 2;

    private int mode = NONE;

    private PointF mStartPoint = new PointF();
    private PointF mMiddlePoint = new PointF();
    private Point mBitmapMiddlePoint = new Point();

    private float oldDist = 1f;
    private float matrixValues[] = {0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f};
    private float oldEventX = 0;
    private float oldEventY = 0;
    private float oldStartPointX = 0;
    private float oldStartPointY = 0;
    private int mViewWidth = -1;
    private int mViewHeight = -1;
    private float scaled_mBitmapWidth = 1;
    private float scaled_mBitmapHeight = 1;
    private boolean mDraggable = false;
    private static boolean matrix_was_reset = false;
    private float sum_scale_factor = 1;

    // KHANDAQ (calls stage, 26.11): the call screen's way of showing the other person (rules R1 and R6 of the
    // call stage spec), switched on by AirCallStageController; the group screen keeps the old way. The
    // picture fills the stage when it is turned the stage's way and fits it otherwise, it is cut to its own
    // width (the decoder's stride padding past it came out as the green stripe at the right edge), and it
    // turns with the phone in its own matrix instead of turning and blowing up the whole view.
    private boolean stageMode = false;
    /** In the small box of a swapped stage, where it always fills. */
    private boolean stageBoxed = false;
    private boolean hasPicture = false;
    private int pictureW = 0;
    private int pictureH = 0;
    private int stageRotation = 0;
    private final Matrix stageMatrix = new Matrix();
    private final RectF stageClip = new RectF();
    private final Paint stageBars = new Paint();
    /** Told on the main thread when the first picture of the other person comes (the header leaves the middle). */
    private Runnable onStagePicture = null;


    public CustomVideoImageView(Context context)
    {
        this(context, null, 0);
        if (PREF__X_zoom_incoming_video)
        {
            this.setScaleType(ScaleType.MATRIX);
        }
        else
        {
            // KHANDAQ: fill the screen (crop) instead of letterboxing. FIT_CENTER left black bars on the
            // sides and exposed the encoder's green chroma edge column at the right of the frame; CENTER_CROP
            // scales the remote video to cover the whole view, so it's full-screen and the green edge is
            // cropped off.
            this.setScaleType(ScaleType.CENTER_CROP);
        }
        this.setOnTouchListener(this);
        matrix_was_reset = true;
    }

    public CustomVideoImageView(Context context, AttributeSet attrs)
    {
        this(context, attrs, 0);
        if (PREF__X_zoom_incoming_video)
        {
            this.setScaleType(ScaleType.MATRIX);
        }
        else
        {
            // KHANDAQ: fill the screen (crop) instead of letterboxing. FIT_CENTER left black bars on the
            // sides and exposed the encoder's green chroma edge column at the right of the frame; CENTER_CROP
            // scales the remote video to cover the whole view, so it's full-screen and the green edge is
            // cropped off.
            this.setScaleType(ScaleType.CENTER_CROP);
        }
        this.setOnTouchListener(this);
        matrix_was_reset = true;
    }

    public CustomVideoImageView(Context context, AttributeSet attrs, int defStyle)
    {
        super(context, attrs, defStyle);
        if (PREF__X_zoom_incoming_video)
        {
            this.setScaleType(ScaleType.MATRIX);
        }
        else
        {
            // KHANDAQ: fill the screen (crop) instead of letterboxing. FIT_CENTER left black bars on the
            // sides and exposed the encoder's green chroma edge column at the right of the frame; CENTER_CROP
            // scales the remote video to cover the whole view, so it's full-screen and the green edge is
            // cropped off.
            this.setScaleType(ScaleType.CENTER_CROP);
        }
        this.setOnTouchListener(this);
        matrix_was_reset = true;
    }

    @Override
    public void onSizeChanged(int w, int h, int oldw, int oldh)
    {
        super.onSizeChanged(w, h, oldw, oldh);

        mViewWidth = w;
        mViewHeight = h;

        if (stageMode)
        {
            synchronized (VIDEO_ROTATION_LOCK)
            {
                stageRotation = currentStageRotation();
                updateStageMatrix();
            }
            return;
        }

        if (PREF__X_zoom_incoming_video)
        {
            matrix.reset();
            savedMatrix.reset();
            sum_scale_factor = 1;
            scaled_mBitmapWidth = 1;
            scaled_mBitmapHeight = 1;
        }

        matrix_was_reset = true;
    }

    public static void video_output_orentation_update()
    {
        matrix.reset();
        matrix_was_reset = true;
    }

    /**
     * KHANDAQ (calls stage, 26.11): the call screen's rules on or off (AirCallStageController switches them on
     * unless the expert zoom of the incoming video is on, which keeps its own matrix).
     */
    void setStageMode(final boolean on)
    {
        synchronized (VIDEO_ROTATION_LOCK)
        {
            if (stageMode == on)
            {
                return;
            }
            stageMode = on;
            if (on)
            {
                stageBars.setColor(AirUi.color(getContext(), R.color.kq_fixed_black));
                setRotation(0f);
                setScaleX(1f);
                setScaleY(1f);
                setScaleType(ScaleType.MATRIX);
                stageRotation = currentStageRotation();
                updateStageMatrix();
            }
            else
            {
                setScaleType(PREF__X_zoom_incoming_video ? ScaleType.MATRIX : ScaleType.CENTER_CROP);
                matrix_was_reset = true;
            }
        }
        invalidate();
    }

    boolean isStageMode()
    {
        return stageMode;
    }

    /** R6: in the small box of a swapped stage the other person always fills it. */
    void setStageBoxed(final boolean boxed)
    {
        synchronized (VIDEO_ROTATION_LOCK)
        {
            if (stageBoxed == boxed)
            {
                return;
            }
            stageBoxed = boxed;
            updateStageMatrix();
        }
        invalidate();
    }

    /** True once a picture of the other person has been shown on the stage. */
    boolean hasStagePicture()
    {
        return hasPicture;
    }

    void setOnStagePicture(final Runnable listener)
    {
        onStagePicture = listener;
    }

    /**
     * KHANDAQ (calls stage, 26.11): a frame with the size of its picture. The bitmap can be wider than the
     * picture (MainActivity.allocate_video_buffer_1 sizes it to the decoder's stride), and the columns past
     * the picture's width are no picture. Without the call screen's rules this is the old setBitmap.
     */
    public void setBitmap(final Bitmap bitmap, final int width, final int height)
    {
        if (!stageMode)
        {
            setBitmap(bitmap);
            return;
        }
        synchronized (VIDEO_ROTATION_LOCK)
        {
            if ((bitmap == null) || bitmap.isRecycled())
            {
                return;
            }
            final int w = ((width > 0) && (width <= bitmap.getWidth())) ? width : bitmap.getWidth();
            final int h = ((height > 0) && (height <= bitmap.getHeight())) ? height : bitmap.getHeight();
            final int rotation = currentStageRotation();
            final boolean first = !hasPicture;
            if ((w != pictureW) || (h != pictureH) || (rotation != stageRotation) || first)
            {
                pictureW = w;
                pictureH = h;
                stageRotation = rotation;
                hasPicture = true;
                updateStageMatrix();
            }
            setImageBitmap(bitmap);
            if (first && (onStagePicture != null))
            {
                post(onStagePicture);
            }
        }
    }

    /** The turn the picture still needs: the phone's own turn less what the display turned by itself. */
    private int currentStageRotation()
    {
        int display = 0;
        try
        {
            final Display d = getDisplay();
            if (d != null)
            {
                switch (d.getRotation())
                {
                    case Surface.ROTATION_90:
                        display = 90;
                        break;
                    case Surface.ROTATION_180:
                        display = 180;
                        break;
                    case Surface.ROTATION_270:
                        display = 270;
                        break;
                    default:
                        display = 0;
                        break;
                }
            }
        }
        catch (Exception ignored)
        {
        }
        return AirCallStage.remainingRotation(device_orientation, display);
    }

    /** R1, R6: the picture's matrix (centre, turn, one scale for both axes) and the part of the view it covers. */
    private void updateStageMatrix()
    {
        if (!stageMode)
        {
            return;
        }
        final int vw = getWidth();
        final int vh = getHeight();
        if ((vw <= 0) || (vh <= 0) || (pictureW <= 0) || (pictureH <= 0))
        {
            stageClip.setEmpty();
            return;
        }
        final boolean sideways = AirCallStage.sideways(stageRotation);
        final float shownW = sideways ? pictureH : pictureW;
        final float shownH = sideways ? pictureW : pictureH;
        final AirCallStage.Scale scale = stageBoxed ? AirCallStage.Scale.FILL
                                                    : AirCallStage.scaleFor(shownW, shownH, vw, vh);
        final float f = AirCallStage.factor(shownW, shownH, vw, vh, scale);
        stageMatrix.reset();
        stageMatrix.postTranslate(-pictureW / 2f, -pictureH / 2f);
        stageMatrix.postRotate(stageRotation);
        stageMatrix.postScale(f, f);
        stageMatrix.postTranslate(vw / 2f, vh / 2f);
        final float[] r = AirCallStage.place(shownW, shownH, vw, vh, scale);
        stageClip.set(Math.max(0f, r[0]), Math.max(0f, r[1]), Math.min(vw, r[2]), Math.min(vh, r[3]));
        setImageMatrix(stageMatrix);
        invalidate();
    }

    @Override
    protected void onDraw(final Canvas canvas)
    {
        if (!stageMode)
        {
            super.onDraw(canvas);
            return;
        }
        if (!hasPicture || stageClip.isEmpty())
        {
            // No picture yet: the screen's ground shows through.
            return;
        }
        // R1: a fitted picture stands on fixed-black, and only the picture's own width is drawn.
        canvas.drawRect(0f, 0f, getWidth(), getHeight(), stageBars);
        final int saved = canvas.save();
        canvas.clipRect(stageClip);
        super.onDraw(canvas);
        canvas.restoreToCount(saved);
    }

    public void setBitmap(Bitmap bitmap)
    {
        synchronized(VIDEO_ROTATION_LOCK)
        {
            if (bitmap != null)
            {
                if (!bitmap.isRecycled())
                {
                    if (PREF__X_zoom_incoming_video)
                    {
                        if (matrix_was_reset)
                        {
                            try
                            {
                                int mBitmapWidth = bitmap.getWidth();
                                int mBitmapHeight = bitmap.getHeight();

                                float scale_h = (float) mViewHeight / (float) mBitmapHeight;
                                float scale_w = (float) mViewWidth / (float) mBitmapWidth;

                                // System.out.println("__onTouch__:" + "scale_w=" + scale_w + ",scale_h=" + scale_h);
                                float scale = Math.min(scale_h, scale_w);
                                float MAX_SCALE_BITMAP = 10.0f;
                                float MIN_SCALE_BITMAP = 0.0001f;
                                if (scale < MIN_SCALE_BITMAP)
                                {
                                    scale = MIN_SCALE_BITMAP;
                                }
                                else if (scale > MAX_SCALE_BITMAP)
                                {
                                    scale = MAX_SCALE_BITMAP;
                                }

                                sum_scale_factor = 1;

                                scaled_mBitmapWidth = (float) mBitmapWidth * scale;
                                scaled_mBitmapHeight = (float) mBitmapHeight * scale;

                                mBitmapMiddlePoint.x = (mViewWidth / 2) - ((int) scaled_mBitmapWidth / 2);
                                mBitmapMiddlePoint.y = (mViewHeight / 2) - ((int) scaled_mBitmapHeight / 2);

                                matrix.postTranslate(mBitmapMiddlePoint.x, mBitmapMiddlePoint.y);
                                matrix_was_reset = false;
                            }
                            catch (Exception e)
                            {
                                e.printStackTrace();
                            }
                        }

                        this.setImageMatrix(matrix);
                    }
                    else
                    {
                        if (matrix_was_reset)
                        {
                            try
                            {
                                int rot_needed = 0;

                                if (device_orientation == 90)
                                {
                                    Log.i(TAG, "rot_dev:270");
                                    rot_needed = 270;
                                }
                                else if (device_orientation == 270)
                                {
                                    Log.i(TAG, "rot_dev:90");
                                    rot_needed = 90;
                                }
                                else if (device_orientation == 180)
                                {
                                    Log.i(TAG, "rot_dev:180");
                                    rot_needed = 180;
                                }
                                else
                                {
                                    Log.i(TAG, "rot_dev:0");
                                    rot_needed = 0;
                                }

                                float scale_up = 1.0f;

                                int img_w = bitmap.getWidth();
                                int img_h = bitmap.getHeight();

                                if ((rot_needed == 0) || (rot_needed == 180))
                                {
                                    Log.i(TAG, "scale:1=1.0 " + img_w + " " + img_h + " " + mViewHeight + " " + mViewWidth);
                                    this.setScaleX(1.0f);
                                    this.setScaleY(1.0f);
                                }
                                else
                                {
                                    int tmp = img_w;
                                    img_w = img_h;
                                    img_h = tmp;

                                    // if (img_w < img_h)
                                    if (1+1==2)
                                    {
                                        // TODO: this is NOT correct yet!!
                                        scale_up = (float) (mViewHeight / 2) / (float) (mViewWidth / 2);
                                        Log.i(TAG,
                                              "scale:2=" + scale_up + " " + img_w + " " + img_h + " " + mViewHeight +
                                              " " + mViewWidth);
                                        this.setScaleX(scale_up);
                                        this.setScaleY(scale_up);
                                    }
                                    else
                                    {
                                        Log.i(TAG, "scale:3=1.0 " + img_w + " " + img_h + " " + mViewHeight + " " +
                                                   mViewWidth);
                                        this.setScaleX(1.0f);
                                        this.setScaleY(1.0f);
                                    }
                                }

                                this.setRotation(rot_needed);

                                matrix_was_reset = false;
                            }
                            catch (Exception e)
                            {
                                // e.printStackTrace();
                                // System.out.println("rot_dev:ERR:" + e.getMessage());
                            }
                        }
                    }


                    if (!bitmap.isRecycled())
                    {
                        setImageBitmap(bitmap);
                    }
                }
            }
        }
    }

    @Override
    public boolean onTouch(View v, MotionEvent event)
    {
        // System.out.println("__onTouch__:" + "mask=" + (event.getAction() & MotionEvent.ACTION_MASK));

        // KHANDAQ (call-screen report, 17 Aug): tapping your own preview hides it, and this is the way
        // back — otherwise the preview is gone for the rest of the call with nothing to tap. Checked
        // before the zoom branch so it works whether or not incoming-video zoom is enabled.
        if ((event.getAction() == MotionEvent.ACTION_DOWN) && CallingActivity.self_preview_hidden)
        {
            CallingActivity.set_self_preview_hidden(false);
            if (!PREF__X_zoom_incoming_video)
            {
                return true;
            }
        }

        if (PREF__X_zoom_incoming_video)
        {

            switch (event.getAction() & MotionEvent.ACTION_MASK)
            {
                case MotionEvent.ACTION_DOWN:
                    // System.out.println("__onTouch__:" + "ACTION_DOWN");
                    savedMatrix.set(matrix);
                    mStartPoint.set(event.getX(), event.getY());
                    mode = DRAG;
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    // System.out.println("__onTouch__:" + "ACTION_POINTER_DOWN");
                    oldDist = spacing(event);
                    if (oldDist > 10f)
                    {
                        savedMatrix.set(matrix);
                        midPoint(mMiddlePoint, event);
                        mode = ZOOM;
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_POINTER_UP:
                    mode = NONE;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (mode == DRAG)
                    {
                        drag(event);
                    }
                    else if (mode == ZOOM)
                    {
                        // System.out.println("__onTouch__:" + "ZOOM");
                        zoom(event);
                    }
                    break;
            }


            return true;
        }
        else
        {
            return false;
        }
    }


    public void drag(MotionEvent event)
    {
        matrix.getValues(matrixValues);

        float left = matrixValues[2];
        float top = matrixValues[5];
        float bottom = (top + (matrixValues[0] * scaled_mBitmapHeight)) - mViewHeight;
        float right = (left + (matrixValues[0] * scaled_mBitmapWidth)) - mViewWidth;

        float eventX = event.getX();
        float eventY = event.getY();
        float spacingX = eventX - mStartPoint.x;
        float spacingY = eventY - mStartPoint.y;
        float newPositionLeft = (left < 0 ? spacingX : spacingX * -1) + left;
        float newPositionRight = (spacingX) + right;
        float newPositionTop = (top < 0 ? spacingY : spacingY * -1) + top;
        float newPositionBottom = (spacingY) + bottom;
        boolean x = true;
        boolean y = true;

        if (newPositionRight < 0.0f || newPositionLeft > 0.0f)
        {
            if (newPositionRight < 0.0f && newPositionLeft > 0.0f)
            {
                x = false;
            }
            else
            {
                eventX = oldEventX;
                mStartPoint.x = oldStartPointX;
            }
        }
        if (newPositionBottom < 0.0f || newPositionTop > 0.0f)
        {
            if (newPositionBottom < 0.0f && newPositionTop > 0.0f)
            {
                y = false;
            }
            else
            {
                eventY = oldEventY;
                mStartPoint.y = oldStartPointY;
            }
        }

        if (mDraggable)
        {
            matrix.set(savedMatrix);
            matrix.postTranslate(x ? eventX - mStartPoint.x : 0, y ? eventY - mStartPoint.y : 0);
            this.setImageMatrix(matrix);
            if (x)
            {
                oldEventX = eventX;
            }
            if (y)
            {
                oldEventY = eventY;
            }
            if (x)
            {
                oldStartPointX = mStartPoint.x;
            }
            if (y)
            {
                oldStartPointY = mStartPoint.y;
            }
        }

    }

    public void zoom(MotionEvent event)
    {
        matrix.getValues(matrixValues);

        float newDist = spacing(event);
        float bitmapWidth = matrixValues[0] * scaled_mBitmapWidth;
        float bimtapHeight = matrixValues[0] * scaled_mBitmapHeight;
        // System.out.println("__onTouch__:" + "w=" + bitmapWidth + ",h=" + bimtapHeight);
        boolean in = newDist > oldDist;

        if (!in && matrixValues[0] < 1)
        {
            return;
        }

        mDraggable = bitmapWidth > mViewWidth || bimtapHeight > mViewHeight;

        float midX = (mViewWidth / 2);
        float midY = (mViewHeight / 2);

        float scale = newDist / oldDist;
        // System.out.println("__onTouch__:" + "scf=" + scale);
        boolean do_scale = true;

        //        if ((bitmapWidth < 40) || (mBitmapHeight < 40))
        //        {
        //            do_scale = false;
        //        }
        //        else if ((bitmapWidth > (50 * mViewWidth)) || (mBitmapHeight > (50 * mViewHeight)))
        //        {
        //            do_scale = false;
        //        }

        if (do_scale)
        {
            matrix.set(savedMatrix);
            matrix.postScale(scale, scale, midX, midY);
        }

        this.setImageMatrix(matrix);
    }

    /**
     * Determine the space between the first two fingers
     */
    private float spacing(MotionEvent event)
    {
        float x = event.getX(0) - event.getX(1);
        float y = event.getY(0) - event.getY(1);

        return (float) Math.sqrt(x * x + y * y);
    }

    /**
     * Calculate the mid point of the first two fingers
     */
    private void midPoint(PointF point, MotionEvent event)
    {
        float x = event.getX(0) + event.getX(1);
        float y = event.getY(0) + event.getY(1);
        point.set(x / 2, y / 2);
    }


}

