package org.khandaq.messenger;

import android.util.Log;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import com.zoffcc.applications.trifa.HelperRelay;

import androidx.annotation.NonNull;

/** Receives FCM wake pushes and forwards token updates to TRIfA core. */
public class KhandaqFirebaseMessagingService extends FirebaseMessagingService {
    private static final String TAG = "KhandaqFCM";

    @Override
    public void onNewToken(@NonNull String token) {
        HelperRelay.apply_notification_token_auto(token);
    }

    @Override
    public void onMessageReceived(@NonNull RemoteMessage message) {
        Log.i(TAG, "wake push received");
        android.content.Intent wake = new android.content.Intent("com.zoffcc.applications.trifa.TOXSERVICE_ALARM");
        wake.setPackage(getPackageName());
        sendBroadcast(wake);
    }
}
