// Khandaq 0.2.0 push relay constants
import Foundation
import CryptoKit

enum KhandaqPush {
    static let relayBase = "https://push.khandaq.org"
    static let fcmPushURLPrefix = relayBase + "/toxfcm/fcm.php?id="

    /// Set via build setting KHANDAQ_PUSH_AUTH_SECRET (optional).
    private static let relayAuthSecret: String = {
        Bundle.main.object(forInfoDictionaryKey: "KhandaqPushRelayAuthSecret") as? String ?? ""
    }()

    static func pushURL(forFcmToken token: String) -> String {
        fcmPushURLPrefix + token + "&type=1"
    }

    static func isAllowedPushURL(_ url: String) -> Bool {
        guard let components = URLComponents(string: url),
              let host = components.host?.lowercased(),
              let path = components.path else {
            return false
        }

        if host == "push.khandaq.org" {
            return path == "/toxfcm/fcm.php" && (components.queryItems?.first(where: { $0.name == "id" })?.value?.count ?? 0) >= 10
        }
        if host == "tox.zoff.xyz" {
            return path == "/toxfcm/fcm.php" && (components.queryItems?.first(where: { $0.name == "id" })?.value?.count ?? 0) >= 10
        }
        return false
    }

    static func withWakeParams(_ url: String, senderPubkey: String?) -> String {
        var result = url
        if let pk = senderPubkey, !pk.isEmpty, !result.contains("from=") {
            result += result.contains("?") ? "&from=\(pk)" : "?from=\(pk)"
        }
        let auth = relayAuthParam()
        if !auth.isEmpty, result.contains("push.khandaq.org") {
            result += result.contains("?") ? "&auth=\(auth)" : "?auth=\(auth)"
        }
        return result
    }

    static func relayAuthParam() -> String {
        guard !relayAuthSecret.isEmpty,
              let keyData = relayAuthSecret.data(using: .utf8) else {
            return ""
        }
        let key = SymmetricKey(data: keyData)
        let mac = HMAC<SHA256>.authenticationCode(for: Data("khandaq-push-relay".utf8), using: key)
        return mac.map { String(format: "%02x", $0) }.joined()
    }
}
