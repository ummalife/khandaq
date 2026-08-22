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
        // KHANDAQ (re-audit 2026-08-22, K-01): a registration challenge, not a wake.
        //
        // This is the proof that makes per-contact capabilities safe to register: the relay pushes a
        // nonce to the FCM token and only the device that owns that token receives it. It is
        // data-only and must stay invisible — no broadcast, no service wake-up, nothing on screen.
        // A device that lit up "New message" because it registered a capability would have turned a
        // security fix into a support ticket.
        final java.util.Map<String, String> data = message.getData();
        if (data != null && data.containsKey("khandaq_reg_nonce")) {
            Log.i(TAG, "push capability challenge received");
            com.zoffcc.applications.trifa.KhandaqPushCapability.onRegistrationNonce(
                    data.get("khandaq_reg_cid"), data.get("khandaq_reg_nonce"));
            return;
        }

        Log.i(TAG, "wake push received");
        android.content.Intent wake = new android.content.Intent("com.zoffcc.applications.trifa.TOXSERVICE_ALARM");
        wake.setPackage(getPackageName());
        sendBroadcast(wake);
    }
}
