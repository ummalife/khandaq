package com.zoffcc.applications.trifa;

/**
 * KHANDAQ (Khandaq Testers 8264): the geometry of drawing and writing on a photo of a send. The marks are kept
 * in the photo's own pixels, so the copy saved has them where they were seen on any screen; the line and the
 * words are sized by the photo, so that they look the same on a small photo and on a large one.
 */
final class PhotoMarkupMath
{
    private PhotoMarkupMath()
    {
    }

    /** Scale, then x and y offset, that fit a photo inside a view, centred; nothing to fit is scale 1. */
    static float[] fit(final int photoWidth, final int photoHeight, final int viewWidth, final int viewHeight)
    {
        if ((photoWidth <= 0) || (photoHeight <= 0) || (viewWidth <= 0) || (viewHeight <= 0))
        {
            return new float[]{1f, 0f, 0f};
        }
        final float scale = Math.min((float) viewWidth / photoWidth, (float) viewHeight / photoHeight);
        return new float[]{scale, (viewWidth - (photoWidth * scale)) / 2f, (viewHeight - (photoHeight * scale)) / 2f};
    }

    /** The width of a line in the photo's pixels: a 150th of its long side, at least 2 px. */
    static float brushWidth(final int photoWidth, final int photoHeight)
    {
        return Math.max(2f, Math.max(photoWidth, photoHeight) / 150f);
    }

    /** The size of words in the photo's pixels: a 16th of its long side, at least 12 px. */
    static float textSize(final int photoWidth, final int photoHeight)
    {
        return Math.max(12f, Math.max(photoWidth, photoHeight) / 16f);
    }

    static float clamp(final float value, final float min, final float max)
    {
        return Math.max(min, Math.min(max, value));
    }
}
