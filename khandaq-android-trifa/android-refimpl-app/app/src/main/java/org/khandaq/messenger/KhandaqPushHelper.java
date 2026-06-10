package org.khandaq.messenger;

import android.content.Context;
import android.util.Log;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;

/** Registers embedded FCM token when google-services.json is configured. */
public final class KhandaqPushHelper {
    private static final String TAG = "KhandaqPushHelper";

    private KhandaqPushHelper() {}

    public static void initIfAvailable(Context context) {
        try {
            if (GoogleApiAvailability.getInstance()
                    .isGooglePlayServicesAvailable(context) != ConnectionResult.SUCCESS) {
                return;
            }
            if (FirebaseApp.getApps(context).isEmpty()) {
                FirebaseApp.initializeApp(context);
            }
            FirebaseMessaging.getInstance().getToken()
                    .addOnCompleteListener(task -> {
                        if (!task.isSuccessful() || task.getResult() == null) {
                            Log.w(TAG, "FCM token fetch failed");
                            return;
                        }
                        broadcastToken(context, task.getResult());
                    });
        } catch (Exception e) {
            Log.w(TAG, "FCM not configured: " + e.getMessage());
        }
    }

    private static void broadcastToken(Context context, String token) {
        com.zoffcc.applications.trifa.HelperRelay.apply_notification_token_auto(token);
    }
}
