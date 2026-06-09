// Khandaq 0.2.0 push relay constants
import Foundation

enum KhandaqPush {
    static let relayBase = "https://push.khandaq.org"
    static let fcmPushURLPrefix = relayBase + "/toxfcm/fcm.php?id="

    static func pushURL(forFcmToken token: String) -> String {
        fcmPushURLPrefix + token + "&type=1"
    }

    static func isAllowedPushURL(_ url: String) -> Bool {
        url.hasPrefix(fcmPushURLPrefix)
            || url.hasPrefix("https://tox.zoff.xyz/toxfcm/fcm.php?id=")
            || url.hasPrefix("https://gotify1.unifiedpush.org/UP?token=")
            || url.hasPrefix("https://ntfy.sh/")
    }
}
