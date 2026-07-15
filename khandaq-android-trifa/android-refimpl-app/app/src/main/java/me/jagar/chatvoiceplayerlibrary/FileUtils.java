package me.jagar.chatvoiceplayerlibrary;

import android.app.Activity;
import android.content.Context;
import android.os.AsyncTask;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;

public class FileUtils {

    public static final int MAX_FILE_SIZE_BYTES = 100000; // ~100 kByte

    public static void updateVisualizer(final Context context, final File file, final PlayerVisualizerSeekbar playerVisualizerSeekbar){
        new AsyncTask<Void, Void, byte[]>()
        {
            @Override
            protected byte[] doInBackground(Void... voids) {
                return fileToBytes(file);
            }

            @Override
            protected void onPostExecute(final byte[] bytes) {
                super.onPostExecute(bytes);
                ((Activity) context).runOnUiThread(new Runnable() {
                    @Override
                    public void run()
                    {
                        playerVisualizerSeekbar.setBytes(bytes);
                        playerVisualizerSeekbar.invalidate();
                    }
                });
            }
        }.execute();
    }

    public static void vupdateVisualizer(final Context context, final info.guardianproject.iocipher.File vfile, final PlayerVisualizerSeekbar playerVisualizerSeekbar){
        new AsyncTask<Void, Void, byte[]>()
        {
            @Override
            protected byte[] doInBackground(Void... voids) {
                return vfileToBytes(vfile);
            }

            @Override
            protected void onPostExecute(final byte[] bytes) {
                super.onPostExecute(bytes);
                ((Activity) context).runOnUiThread(new Runnable() {
                    @Override
                    public void run()
                    {
                        playerVisualizerSeekbar.setBytes(bytes);
                        playerVisualizerSeekbar.invalidate();
                    }
                });
            }
        }.execute();
    }

    // KHANDAQ (tester feedback): voice notes longer than ~30-60s exceed MAX_FILE_SIZE_BYTES, so this
    // used to return null and the seekbar drew a flat line instead of a waveform (some notes waveform,
    // some flat, depending on length). Also a single read() rarely filled the whole array, leaving a
    // trailing flat (zero) section. Now: never bail on big files — read the FIRST MAX_FILE_SIZE_BYTES
    // (that many raw bytes already yield far more samples than there are bars) and read fully via a
    // loop so the bar pattern is consistent for every voice message.
    public static byte[] fileToBytes(File file)
    {
        try
        {
            final long len = file.length();
            if (len <= 0)
            {
                return null;
            }
            final int size = (int) Math.min(len, MAX_FILE_SIZE_BYTES);
            final byte[] bytes = new byte[size];
            BufferedInputStream buf = new BufferedInputStream(new FileInputStream(file));
            final int read = readFully(buf, bytes, size);
            buf.close();
            return trimTo(bytes, read);
        }
        catch (Exception e)
        {
            e.printStackTrace();
            return null;
        }
    }

    public static byte[] vfileToBytes(info.guardianproject.iocipher.File vfile)
    {
        try
        {
            final long len = vfile.length();
            if (len <= 0)
            {
                return null;
            }
            final int size = (int) Math.min(len, MAX_FILE_SIZE_BYTES);
            final byte[] bytes = new byte[size];
            BufferedInputStream buf = new BufferedInputStream(new info.guardianproject.iocipher.FileInputStream(vfile));
            final int read = readFully(buf, bytes, size);
            buf.close();
            return trimTo(bytes, read);
        }
        catch (Exception e)
        {
            e.printStackTrace();
            return null;
        }
    }

    private static int readFully(BufferedInputStream buf, byte[] bytes, int size) throws java.io.IOException
    {
        int off = 0;
        int r;
        while (off < size && (r = buf.read(bytes, off, size - off)) != -1)
        {
            off += r;
        }
        return off;
    }

    private static byte[] trimTo(byte[] bytes, int read)
    {
        if (read <= 0)
        {
            return null;
        }
        if (read == bytes.length)
        {
            return bytes;
        }
        final byte[] trimmed = new byte[read];
        System.arraycopy(bytes, 0, trimmed, 0, read);
        return trimmed;
    }
}
