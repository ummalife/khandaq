// Khandaq 0.2.0 push relay constants
import Foundation
import CommonCrypto

enum KhandaqPush {
    static let relayBase = "https://push.khandaq.org"
    static let fcmPushURLPrefix = relayBase + "/toxfcm/fcm.php?id="

    /// Set via Info.plist KhandaqPushRelayAuthSecret (optional).
    private static let relayAuthSecret: String = {
        Bundle.main.object(forInfoDictionaryKey: "KhandaqPushRelayAuthSecret") as? String ?? ""
    }()

    static func pushURL(forFcmToken token: String) -> String {
        fcmPushURLPrefix + token + "&type=1"
    }

    static func isAllowedPushURL(_ url: String) -> Bool {
        guard let components = URLComponents(string: url),
              let host = components.host?.lowercased() else {
            return false
        }
        let path = components.path

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
        let message = Data("khandaq-push-relay".utf8)
        var digest = [UInt8](repeating: 0, count: Int(CC_SHA256_DIGEST_LENGTH))
        keyData.withUnsafeBytes { keyBytes in
            message.withUnsafeBytes { msgBytes in
                CCHmac(
                    CCHmacAlgorithm(kCCHmacAlgSHA256),
                    keyBytes.baseAddress, keyData.count,
                    msgBytes.baseAddress, message.count,
                    &digest
                )
            }
        }
        return digest.map { String(format: "%02x", $0) }.joined()
    }
}
