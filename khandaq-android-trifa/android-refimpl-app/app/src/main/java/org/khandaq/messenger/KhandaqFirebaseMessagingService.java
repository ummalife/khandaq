package org.khandaq.messenger;

import android.content.ComponentName;
import android.content.Intent;
import android.util.Log;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import androidx.annotation.NonNull;

/** Receives FCM wake pushes and forwards token updates to TRIfA core. */
public class KhandaqFirebaseMessagingService extends FirebaseMessagingService {
    private static final String TAG = "KhandaqFCM";

    @Override
    public void onNewToken(@NonNull String token) {
        forwardToken(token);
    }

    @Override
    public void onMessageReceived(@NonNull RemoteMessage message) {
        Log.i(TAG, "wake push received");
        Intent wake = new Intent("com.zoffcc.applications.trifa.TOXSERVICE_ALARM");
        wake.setPackage(getPackageName());
        sendBroadcast(wake);
    }

    private void forwardToken(String token) {
        if (token == null || token.length() < 10) {
            return;
        }
        ComponentName receiver = new ComponentName(
                getPackageName(), "com.zoffcc.applications.trifa.MyTokenReceiver");
        Intent khandaq = new Intent(KhandaqPush.TOKEN_CHANGED_ACTION);
        khandaq.putExtra("token", token);
        khandaq.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        khandaq.setComponent(receiver);
        sendBroadcast(khandaq);
        Intent legacy = new Intent("com.zoffcc.applications.trifa.TOKEN_CHANGED");
        legacy.putExtra("token", token);
        legacy.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        legacy.setComponent(receiver);
        sendBroadcast(legacy);
    }
}
