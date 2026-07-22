package com.zoffcc.applications.trifa;

import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.lang.ref.WeakReference;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * KHANDAQ: global (app-scoped) voice message playback — Telegram-style.
 * <p>
 * Before this, every voice bubble owned its own MediaPlayer, so playback died whenever the
 * RecyclerView recycled the row (scrolling), the chat was left (fragment onPause), or the row was
 * re-bound ({@code refreshPlayer()} reset progress to 0). Testers reported: "аудио останавливается,
 * когда смотришь чат или заходишь в другой чат; и не показывает, где ты остановился".
 * <p>
 * This manager owns ONE MediaPlayer for the whole app:
 * - playback survives row recycling and chat/group switches (nothing view-bound holds it);
 * - a per-file resume position is remembered for the whole session — pausing (or being displaced by
 *   another voice) stores the position, and both the bubble UI and the next play resume from it;
 * - single-playback is inherent (starting one voice pauses the previous one, keeping its position);
 * - bubbles are passive listeners: they attach on bind and render whatever state the manager has.
 * <p>
 * Files can live on the plain filesystem (outgoing voice) or inside the encrypted IOCipher VFS
 * (incoming voice) — {@code vfs} selects the data source.
 */
public final class ChatVoicePlaybackManager
{
    private static final String TAG = "trifa.VoicePlayback";

    public interface Listener
    {
        /** Fired on the UI thread ~2x/sec while playing and on every state change. */
        void onVoicePlaybackTick(String activePath, boolean playing, int positionMs);
    }

    private static MediaPlayer mp = null;
    private static String activePath = null;
    private static boolean activeVfs = false;
    private static boolean prepared = false;

    private static final ConcurrentHashMap<String, Integer> savedPosMs = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Integer> durationsMs = new ConcurrentHashMap<>();
    private static final CopyOnWriteArrayList<WeakReference<Listener>> listeners = new CopyOnWriteArrayList<>();

    private static final Handler ui = new Handler(Looper.getMainLooper());
    private static final Runnable ticker = new Runnable()
    {
        @Override
        public void run()
        {
            notifyTick();
            if (isPlaying())
            {
                ui.postDelayed(this, 400);
            }
        }
    };

    private ChatVoicePlaybackManager()
    {
    }

    // ---------------------------------------------------------------- playback control

    public static synchronized void play(final String path, final boolean vfs)
    {
        if (path == null)
        {
            return;
        }
        try
        {
            if (path.equals(activePath) && mp != null && prepared)
            {
                if (!mp.isPlaying())
                {
                    mp.start();
                }
                startTicker();
                notifyTick();
                return;
            }

            rememberActivePosition();

            if (mp == null)
            {
                mp = new MediaPlayer();
            }
            else
            {
                try
                {
                    mp.reset();
                }
                catch (Exception ignored)
                {
                }
            }
            prepared = false;
            activePath = path;
            activeVfs = vfs;

            setSource(mp, path, vfs);
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener()
            {
                @Override
                public void onCompletion(MediaPlayer player)
                {
                    // finished: forget the resume position so the next play starts from 0
                    synchronized (ChatVoicePlaybackManager.class)
                    {
                        if (activePath != null)
                        {
                            savedPosMs.remove(activePath);
                        }
                        try
                        {
                            player.seekTo(0);
                        }
                        catch (Exception ignored)
                        {
                        }
                    }
                    notifyTickOnUi();
                }
            });
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener()
            {
                @Override
                public boolean onError(MediaPlayer player, int what, int extra)
                {
                    stopAndForget(path);
                    return true;
                }
            });
            mp.prepare();
            prepared = true;
            try
            {
                durationsMs.put(path, mp.getDuration());
            }
            catch (Exception ignored)
            {
            }

            final Integer resume = savedPosMs.get(path);
            if (resume != null && resume > 0)
            {
                try
                {
                    if (resume < mp.getDuration() - 1000)
                    {
                        mp.seekTo(resume);
                    }
                }
                catch (Exception ignored)
                {
                }
            }
            mp.start();
            startTicker();
            notifyTick();
        }
        catch (Exception e)
        {
            HelperGeneric.logI(TAG, "play:EE:" + e.getMessage());
            stopAndForget(path);
        }
    }

    public static synchronized void pause()
    {
        try
        {
            if (mp != null && prepared && mp.isPlaying())
            {
                mp.pause();
                rememberActivePosition();
            }
        }
        catch (Exception ignored)
        {
        }
        notifyTickOnUi();
    }

    /** Seek within the active file, or stage a start position for a non-active one. */
    public static synchronized void seekTo(final String path, final int ms)
    {
        if (path == null)
        {
            return;
        }
        savedPosMs.put(path, Math.max(0, ms));
        if (path.equals(activePath) && mp != null && prepared)
        {
            try
            {
                mp.seekTo(Math.max(0, ms));
            }
            catch (Exception ignored)
            {
            }
        }
        notifyTickOnUi();
    }

    /** Drop playback/state of a file (e.g. it was deleted or failed to play). */
    public static synchronized void stopAndForget(final String path)
    {
        if (path != null && path.equals(activePath))
        {
            try
            {
                if (mp != null)
                {
                    mp.reset();
                }
            }
            catch (Exception ignored)
            {
            }
            activePath = null;
            prepared = false;
        }
        if (path != null)
        {
            savedPosMs.remove(path);
        }
        notifyTickOnUi();
    }

    // KHANDAQ (#2 fix): fully stop and release the global player. Called when the app is swiped
    // from recents (TrifaToxService.onTaskRemoved) so a voice note does not keep playing after the
    // app is fully closed — the foreground service otherwise keeps the process (and this static
    // MediaPlayer) alive. Unlike stopAndForget, this releases native resources and nulls the player.
    public static synchronized void releaseAll()
    {
        try { ui.removeCallbacks(ticker); } catch (Exception ignored) {}
        try
        {
            if (mp != null)
            {
                mp.reset();
                mp.release();
            }
        }
        catch (Exception ignored)
        {
        }
        mp = null;
        activePath = null;
        activeVfs = false;
        prepared = false;
    }

    // ---------------------------------------------------------------- state queries

    public static synchronized boolean isPlaying()
    {
        try
        {
            return mp != null && prepared && mp.isPlaying();
        }
        catch (Exception e)
        {
            return false;
        }
    }

    public static synchronized boolean isActivePath(final String path)
    {
        return path != null && path.equals(activePath);
    }

    public static synchronized int positionMs(final String path)
    {
        if (path == null)
        {
            return 0;
        }
        if (path.equals(activePath) && mp != null && prepared)
        {
            try
            {
                return mp.getCurrentPosition();
            }
            catch (Exception ignored)
            {
            }
        }
        final Integer saved = savedPosMs.get(path);
        return saved != null ? saved : 0;
    }

    /**
     * Duration of a voice file. Cached; on a cache miss a short-lived probe player reads it once
     * (that is what every bubble bind used to do anyway — now it happens once per file per session).
     */
    public static int durationMs(final String path, final boolean vfs)
    {
        if (path == null)
        {
            return 0;
        }
        final Integer cached = durationsMs.get(path);
        if (cached != null)
        {
            return cached;
        }
        MediaPlayer probe = null;
        try
        {
            probe = new MediaPlayer();
            setSource(probe, path, vfs);
            probe.prepare();
            final int dur = probe.getDuration();
            durationsMs.put(path, dur);
            return dur;
        }
        catch (Exception e)
        {
            return 0;
        }
        finally
        {
            if (probe != null)
            {
                try
                {
                    probe.release();
                }
                catch (Exception ignored)
                {
                }
            }
        }
    }

    // ---------------------------------------------------------------- listeners

    public static void attach(final Listener l)
    {
        if (l == null)
        {
            return;
        }
        for (Iterator<WeakReference<Listener>> it = listeners.iterator(); it.hasNext(); )
        {
            final Listener existing = it.next().get();
            if (existing == l)
            {
                return;
            }
        }
        prune();
        listeners.add(new WeakReference<>(l));
    }

    private static void prune()
    {
        for (WeakReference<Listener> ref : listeners)
        {
            if (ref.get() == null)
            {
                listeners.remove(ref);
            }
        }
    }

    // ---------------------------------------------------------------- internals

    private static void setSource(final MediaPlayer player, final String path, final boolean vfs)
            throws java.io.IOException
    {
        if (vfs)
        {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            {
                player.setDataSource(new VFileMediaDataSource(
                        new info.guardianproject.iocipher.RandomAccessFile(path, "r")));
            }
            else
            {
                throw new java.io.IOException("VFS media needs Android M+");
            }
        }
        else
        {
            player.setDataSource(path);
        }
    }

    private static void rememberActivePosition()
    {
        try
        {
            if (activePath != null && mp != null && prepared)
            {
                savedPosMs.put(activePath, mp.getCurrentPosition());
            }
        }
        catch (Exception ignored)
        {
        }
    }

    private static void startTicker()
    {
        ui.removeCallbacks(ticker);
        ui.postDelayed(ticker, 400);
    }

    private static void notifyTickOnUi()
    {
        if (Looper.myLooper() == Looper.getMainLooper())
        {
            notifyTick();
        }
        else
        {
            ui.post(new Runnable()
            {
                @Override
                public void run()
                {
                    notifyTick();
                }
            });
        }
    }

    private static void notifyTick()
    {
        final String path;
        final boolean playing;
        final int pos;
        synchronized (ChatVoicePlaybackManager.class)
        {
            path = activePath;
            playing = isPlaying();
            pos = (path != null) ? positionMs(path) : 0;
        }
        for (WeakReference<Listener> ref : listeners)
        {
            final Listener l = ref.get();
            if (l == null)
            {
                listeners.remove(ref);
                continue;
            }
            try
            {
                l.onVoicePlaybackTick(path, playing, pos);
            }
            catch (Exception ignored)
            {
            }
        }
    }
}
