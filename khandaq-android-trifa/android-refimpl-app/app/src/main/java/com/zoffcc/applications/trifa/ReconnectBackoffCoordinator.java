package com.zoffcc.applications.trifa;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static com.zoffcc.applications.trifa.NetworkDiagnosticsLog.log;
import static com.zoffcc.applications.trifa.TrifaToxService.on_network_reconnect_attempt;

/**
 * Exponential backoff with jitter for DHT rebootstrap (1s, 2s, 4s … max 30s).
 * Urgent network-change events fire immediately (debounce handled upstream).
 */
final class ReconnectBackoffCoordinator
{
    private static final String TAG = "trifa.ReconnectBackoff";
    private static final long BASE_DELAY_MS = 1000L;
    private static final long MAX_DELAY_MS = 30_000L;
    private static final int MAX_ATTEMPTS = 12;

    enum Reason
    {
        NETWORK_CHANGE,
        OFFLINE_PERIODIC,
        BACKGROUND_WAKE,
        MANUAL
    }

    private static ReconnectBackoffCoordinator instance;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random jitter = new Random();
    private final AtomicInteger attempt = new AtomicInteger(0);
    private Runnable pending;
    private long lastSuccessMs = 0L;

    static ReconnectBackoffCoordinator get()
    {
        if (instance == null)
        {
            instance = new ReconnectBackoffCoordinator();
        }
        return instance;
    }

    void onConnectionRestored()
    {
        handler.post(() -> {
            if (attempt.get() > 0)
            {
                log("reconnect_ok", "attempts_reset=" + attempt.get());
            }
            attempt.set(0);
            lastSuccessMs = SystemClock.elapsedRealtime();
            cancelPending();
        });
    }

    void scheduleReconnect(final Reason reason, final boolean urgent)
    {
        handler.post(() -> scheduleReconnectOnHandler(reason, urgent));
    }

    private void scheduleReconnectOnHandler(final Reason reason, final boolean urgent)
    {
        cancelPending();

        if (urgent)
        {
            attempt.set(0);
        }

        final int currentAttempt = attempt.get();
        final long delayMs = urgent ? 0L : computeDelayMs(currentAttempt);

        log("reconnect_scheduled",
                "reason=" + reason + " urgent=" + urgent + " attempt=" + currentAttempt + " delayMs=" + delayMs);

        pending = () -> executeReconnect(reason, currentAttempt);
        if (delayMs <= 0L)
        {
            handler.post(pending);
        }
        else
        {
            handler.postDelayed(pending, delayMs);
        }
    }

    private void executeReconnect(final Reason reason, final int currentAttempt)
    {
        pending = null;

        log("reconnect_attempt", "reason=" + reason + " attempt=" + currentAttempt);
        try
        {
            on_network_reconnect_attempt(reason, currentAttempt);
        }
        catch (Exception e)
        {
            log("reconnect_error", e.getMessage());
        }
    }

    void scheduleRetryIfStillOffline(final Reason reason)
    {
        handler.post(() -> {
            final int next = attempt.incrementAndGet();
            if (next >= MAX_ATTEMPTS)
            {
                log("reconnect_give_up", "max_attempts=" + MAX_ATTEMPTS);
                return;
            }
            final long delayMs = computeDelayMs(next);
            log("reconnect_retry_scheduled", "nextAttempt=" + next + " delayMs=" + delayMs);
            scheduleReconnectOnHandler(reason, false);
        });
    }

    private void cancelPending()
    {
        if (pending != null)
        {
            handler.removeCallbacks(pending);
            pending = null;
        }
    }

    static long computeDelayMs(final int attemptIndex)
    {
        final long exp = BASE_DELAY_MS * (1L << Math.min(attemptIndex, 5));
        final long capped = Math.min(exp, MAX_DELAY_MS);
        final long jitterMs = (long) (capped * 0.1 * (new Random().nextDouble()));
        return capped + jitterMs;
    }

    int getAttempt()
    {
        return attempt.get();
    }

    long getLastSuccessMs()
    {
        return lastSuccessMs;
    }
}
