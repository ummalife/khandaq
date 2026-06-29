package com.zoffcc.applications.trifa;

import android.os.SystemClock;

/**
 * Tracks connection quality from reconnect timing and bootstrap outcomes.
 */
public final class ConnectionQualityMonitor
{
    public enum Level
    {
        STRONG,
        MEDIUM,
        WEAK,
        OFFLINE
    }

    private static final ConnectionQualityMonitor INSTANCE = new ConnectionQualityMonitor();

    private volatile Level level = Level.STRONG;
    private volatile long estimatedRttMs = 80L;
    private long lastBootstrapStartMs = 0L;

    /** Notified (possibly off the UI thread) on every level transition, so the UI can warn the user. */
    public interface LevelListener
    {
        void onLevelChanged(Level oldLevel, Level newLevel);
    }

    private volatile LevelListener levelListener;

    public void setLevelListener(final LevelListener listener)
    {
        this.levelListener = listener;
    }

    private void setLevel(final Level newLevel)
    {
        final Level old = this.level;
        if (old == newLevel)
        {
            return;
        }
        this.level = newLevel;
        final LevelListener l = this.levelListener;
        if (l != null)
        {
            l.onLevelChanged(old, newLevel);
        }
    }

    public static ConnectionQualityMonitor get()
    {
        return INSTANCE;
    }

    public void onBootstrapStarted()
    {
        lastBootstrapStartMs = SystemClock.elapsedRealtime();
    }

    public void onBootstrapFinished(final boolean connected)
    {
        if (lastBootstrapStartMs > 0L)
        {
            final long elapsed = SystemClock.elapsedRealtime() - lastBootstrapStartMs;
            estimatedRttMs = (estimatedRttMs * 3 + elapsed) / 4;
            lastBootstrapStartMs = 0L;
        }

        if (!connected)
        {
            setLevel(Level.OFFLINE);
            return;
        }

        if (estimatedRttMs < 300L)
        {
            setLevel(Level.STRONG);
        }
        else if (estimatedRttMs < 1500L)
        {
            setLevel(Level.MEDIUM);
        }
        else
        {
            setLevel(Level.WEAK);
        }

        NetworkDiagnosticsLog.log("connection_quality", level.name() + " rtt_ms=" + estimatedRttMs);
    }

    public void onInternetLost()
    {
        setLevel(Level.OFFLINE);
    }

    /** Tox core reports a live connection — clear stale OFFLINE until RTT samples say otherwise. */
    public void onToxConnected()
    {
        if (level == Level.OFFLINE)
        {
            setLevel(Level.STRONG);
        }
    }

    public void onToxDisconnected()
    {
        setLevel(Level.OFFLINE);
    }

    public Level getLevel()
    {
        return level;
    }

    public String getLabel()
    {
        switch (level)
        {
            case STRONG:
                return "strong";
            case MEDIUM:
                return "medium";
            case WEAK:
                return "weak";
            default:
                return "offline";
        }
    }

    public long getEstimatedRttMs()
    {
        return estimatedRttMs;
    }

    /** Adaptive payload size for chunked sends on weak networks. */
    public int getAdaptiveChunkPayloadBytes()
    {
        switch (level)
        {
            case WEAK:
                return 256;
            case MEDIUM:
                return 512;
            case STRONG:
            default:
                return 1024;
        }
    }
}
