package com.zoffcc.applications.trifa;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.concurrent.TimeUnit;

/**
 * Periodic wake to rebootstrap when the app is backgrounded but process survives.
 */
public final class NetworkKeepAliveWorker extends Worker
{
    private static final String WORK_NAME = "khandaq_network_keepalive";

    public NetworkKeepAliveWorker(@NonNull Context context, @NonNull WorkerParameters params)
    {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork()
    {
        NetworkDiagnosticsLog.log("background_wake", "WorkManager keepalive");
        try
        {
            if (!TrifaToxService.TOX_SERVICE_STARTED)
            {
                NetworkDiagnosticsLog.log("background_wake", "tox service not started, skip");
                return Result.success();
            }
            ReconnectBackoffCoordinator.get().scheduleReconnect(
                    ReconnectBackoffCoordinator.Reason.BACKGROUND_WAKE, true);
            TrifaToxService.wakeup_tox_thread();
        }
        catch (Exception e)
        {
            NetworkDiagnosticsLog.log("background_wake_error", e.getMessage());
            return Result.retry();
        }
        return Result.success();
    }

    static void schedule(final Context context)
    {
        final PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                NetworkKeepAliveWorker.class, 15, TimeUnit.MINUTES)
                .build();
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request);
    }
}
