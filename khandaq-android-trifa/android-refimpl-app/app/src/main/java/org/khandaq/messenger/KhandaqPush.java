package org.khandaq.messenger;

/** Khandaq 0.2.0 push relay constants (privacy-preserving wake URLs). */
public final class KhandaqPush {
    private KhandaqPush() {}

    public static final String RELAY_BASE = "https://push.khandaq.org";
    public static final String FCM_PUSH_URL_PREFIX = RELAY_BASE + "/toxfcm/fcm.php?id=";
    public static final String TOKEN_CHANGED_ACTION = "org.khandaq.messenger.TOKEN_CHANGED";
    /** Heads-up channel for FCM wake notifications (created in MainApplication). */
    public static final String FCM_WAKE_CHANNEL_ID = "khandaq_fcm_wake";
    /** Full-screen incoming audio/video call alerts. */
    public static final String INCOMING_CALL_CHANNEL_ID = "khandaq_incoming_call";
}
