package org.khandaq.messenger;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.zoffcc.applications.trifa.CallAudioService;

/** Decline action on incoming-call notification. */
public class IncomingCallReceiver extends BroadcastReceiver {
    private static final String TAG = "KhandaqIncomingCall";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !HelperCallNotification.ACTION_DECLINE.equals(intent.getAction())) {
            return;
        }
        Log.i(TAG, "decline from notification");
        HelperCallNotification.cancel(context);
        CallAudioService.stop_me(true);
    }
}
