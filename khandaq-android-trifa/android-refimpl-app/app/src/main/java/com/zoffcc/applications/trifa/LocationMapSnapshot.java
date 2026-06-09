package com.zoffcc.applications.trifa;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public final class LocationMapSnapshot
{
    private static final int ZOOM = 15;
    private static final int TILE_SIZE = 256;
    private static final int GRID = 3;
    private static final int OUTPUT_WIDTH = 260;
    private static final int OUTPUT_HEIGHT = 120;
    private static final String TILE_URL = "https://tile.openstreetmap.org/%d/%d/%d.png";
    private static final String USER_AGENT = "KhandaqMessenger/1.0";

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final OkHttpClient CLIENT = new OkHttpClient();
    private static final LruCache<String, Bitmap> CACHE = new LruCache<>(24);

    private LocationMapSnapshot()
    {
    }

    public static void loadInto(ImageView imageView, double latitude, double longitude)
    {
        final String cacheKey = String.format(Locale.US, "%.5f,%.5f", latitude, longitude);
        final Bitmap cached = CACHE.get(cacheKey);
        if (cached != null && !cached.isRecycled())
        {
            imageView.setImageBitmap(cached);
            return;
        }

        EXECUTOR.execute(() -> {
            final Bitmap bitmap = render(latitude, longitude);
            if (bitmap == null)
            {
                return;
            }

            CACHE.put(cacheKey, bitmap);
            MAIN.post(() -> {
                if (imageView.getTag() != null && !cacheKey.equals(imageView.getTag()))
                {
                    return;
                }
                imageView.setImageBitmap(bitmap);
            });
        });
    }

    private static Bitmap render(double latitude, double longitude)
    {
        final int centerTileX = lonToTileX(longitude, ZOOM);
        final int centerTileY = latToTileY(latitude, ZOOM);
        final int offset = GRID / 2;
        final int canvasSize = TILE_SIZE * GRID;
        final Bitmap canvasBitmap = Bitmap.createBitmap(canvasSize, canvasSize, Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(canvasBitmap);

        for (int gridY = 0; gridY < GRID; gridY++)
        {
            for (int gridX = 0; gridX < GRID; gridX++)
            {
                final int tileX = centerTileX - offset + gridX;
                final int tileY = centerTileY - offset + gridY;
                final Bitmap tile = fetchTile(tileX, tileY);
                if (tile == null)
                {
                    continue;
                }

                canvas.drawBitmap(tile, gridX * TILE_SIZE, gridY * TILE_SIZE, null);
                tile.recycle();
            }
        }

        final double pixelX = lonToPixelX(longitude, ZOOM) - (centerTileX - offset) * TILE_SIZE;
        final double pixelY = latToPixelY(latitude, ZOOM) - (centerTileY - offset) * TILE_SIZE;
        drawMarker(canvas, (float) pixelX, (float) pixelY);

        final int cropLeft = Math.max(0, (int) pixelX - OUTPUT_WIDTH / 2);
        final int cropTop = Math.max(0, (int) pixelY - OUTPUT_HEIGHT / 2);
        final int cropRight = Math.min(canvasSize, cropLeft + OUTPUT_WIDTH);
        final int cropBottom = Math.min(canvasSize, cropTop + OUTPUT_HEIGHT);
        final Bitmap cropped = Bitmap.createBitmap(
                canvasBitmap,
                cropLeft,
                cropTop,
                cropRight - cropLeft,
                cropBottom - cropTop);
        canvasBitmap.recycle();
        return cropped;
    }

    private static Bitmap fetchTile(int tileX, int tileY)
    {
        final Request request = new Request.Builder()
                .url(String.format(Locale.US, TILE_URL, ZOOM, tileX, tileY))
                .header("User-Agent", USER_AGENT)
                .build();

        try (Response response = CLIENT.newCall(request).execute())
        {
            if (!response.isSuccessful() || response.body() == null)
            {
                return null;
            }

            final byte[] bytes = response.body().bytes();
            return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        }
        catch (IOException ignored)
        {
            return null;
        }
    }

    private static void drawMarker(Canvas canvas, float x, float y)
    {
        final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        shadow.setColor(Color.argb(90, 0, 0, 0));
        canvas.drawCircle(x, y + 4f, 8f, shadow);

        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(Color.rgb(220, 53, 69));
        canvas.drawCircle(x, y, 7f, fill);

        final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(2f);
        border.setColor(Color.WHITE);
        canvas.drawCircle(x, y, 7f, border);
    }

    private static int lonToTileX(double lon, int zoom)
    {
        return (int) Math.floor((lon + 180.0) / 360.0 * (1 << zoom));
    }

    private static int latToTileY(double lat, int zoom)
    {
        final double latRad = Math.toRadians(lat);
        return (int) Math.floor(
                (1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0 * (1 << zoom));
    }

    private static double lonToPixelX(double lon, int zoom)
    {
        return (lon + 180.0) / 360.0 * (1 << zoom) * TILE_SIZE;
    }

    private static double latToPixelY(double lat, int zoom)
    {
        final double latRad = Math.toRadians(lat);
        return (1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0 * (1 << zoom) * TILE_SIZE;
    }
}
