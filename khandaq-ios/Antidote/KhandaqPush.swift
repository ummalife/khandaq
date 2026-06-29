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

    /// Append sender pubkey and the replay-resistant relay auth (security NEW-2).
    /// Must match the server (infra/push/relay/app.py) and the objcTox push builder
    /// (OCTSubmanagerChatsImpl khandaqAppendRelayAuth) byte-for-byte:
    ///   msg  = id + "\n" + from + "\n" + ts   (raw, URL-decoded values, UTF-8)
    ///   auth = lowercase hex HMAC-SHA256(secret, msg)
    /// NOTE: the live push is actually sent from the objcTox pod (which can't reach this
    /// app-target Swift); this stays in sync as the canonical reference / for any Swift caller.
    static func withWakeParams(_ url: String, senderPubkey: String?) -> String {
        var result = url
        if let pk = senderPubkey, !pk.isEmpty, !result.contains("from=") {
            result += result.contains("?") ? "&from=\(pk)" : "?from=\(pk)"
        }

        guard !relayAuthSecret.isEmpty,
              result.contains("push.khandaq.org"),
              !result.contains("auth=") else {
            return result
        }

        let idValue = queryValue(result, "id")
        let fromValue = queryValue(result, "from")
        let ts = String(Int(Date().timeIntervalSince1970))
        let auth = hmacSha256Hex(relayAuthSecret, "\(idValue)\n\(fromValue)\n\(ts)")
        if !auth.isEmpty {
            result += "&ts=\(ts)&auth=\(auth)"
        }
        return result
    }

    /// URL-decoded value of a query parameter, to match the server's decoded view.
    private static func queryValue(_ url: String, _ key: String) -> String {
        guard let components = URLComponents(string: url) else { return "" }
        return components.queryItems?.first(where: { $0.name == key })?.value ?? ""
    }

    private static func hmacSha256Hex(_ secret: String, _ message: String) -> String {
        guard let keyData = secret.data(using: .utf8) else { return "" }
        let msgData = Data(message.utf8)
        var digest = [UInt8](repeating: 0, count: Int(CC_SHA256_DIGEST_LENGTH))
        keyData.withUnsafeBytes { keyBytes in
            msgData.withUnsafeBytes { msgBytes in
                CCHmac(
                    CCHmacAlgorithm(kCCHmacAlgSHA256),
                    keyBytes.baseAddress, keyData.count,
                    msgBytes.baseAddress, msgData.count,
                    &digest
                )
            }
        }
        return digest.map { String(format: "%02x", $0) }.joined()
    }
}
