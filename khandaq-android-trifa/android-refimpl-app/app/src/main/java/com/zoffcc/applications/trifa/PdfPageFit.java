package com.zoffcc.applications.trifa;

/**
 * KHANDAQ (Khandaq Testers 8263): the size in pixels a page of a PDF is drawn at in the app's viewer. A page
 * is drawn as wide as asked (twice the screen, so that it stays sharp when zoomed), but never over a number
 * of pixels, which bounds the memory of one page, nor over a length of side, past which many phones cannot
 * draw a bitmap at all (their largest texture is 4096 px). The shape of the page is kept.
 */
final class PdfPageFit
{
    private PdfPageFit()
    {
    }

    /**
     * Width and height, at least 1 by 1, for a page of {@code pageWidthPt} by {@code pageHeightPt} points.
     * A limit of 0 or less is no limit.
     */
    static int[] bitmapSize(final int pageWidthPt, final int pageHeightPt, final int targetWidthPx,
                            final long maxPixels, final int maxSidePx)
    {
        final double width = Math.max(1, pageWidthPt);
        final double height = Math.max(1, pageHeightPt);
        double scale = Math.max(1, targetWidthPx) / width;
        if (maxPixels > 0)
        {
            scale = Math.min(scale, Math.sqrt(maxPixels / (width * height)));
        }
        if (maxSidePx > 0)
        {
            scale = Math.min(scale, maxSidePx / Math.max(width, height));
        }
        return new int[]{pixels(width * scale), pixels(height * scale)};
    }

    private static int pixels(final double length)
    {
        // The small margin keeps a length such as 999.9999999999999 (1000 after a division and a product) at 1000.
        return (int) Math.max(1L, Math.min(Integer.MAX_VALUE, (long) Math.floor(length + 1e-6)));
    }
}
