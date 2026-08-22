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

    // MARK: - Per-contact capabilities (re-audit 2026-08-22, K-01)
    //
    // The shared push HMAC is baked into every published binary, so unpacking one yields a
    // credential that wakes any device whose FCM token you also hold. The fix is a secret scoped to
    // one relationship: this device mints 32 random bytes per contact, registers their SHA-256 with
    // the relay, and publishes them inside the wake URL that contact already receives over Tox.
    //
    // Registration cannot be authorised by the shared secret — every installation has it — and it
    // cannot be authorised by knowing the FCM token either, because every contact knows that too:
    // it is in the wake URL they were handed. So the relay pushes a nonce TO THE TOKEN, data-only,
    // and only the device that owns that FCM registration can echo it back. A contact can start a
    // challenge and can never finish one.
    //
    // The result is written to UserDefaults because the live push is emitted from the objcTox pod,
    // which cannot reach app-target Swift. `OCTPushUrlValidator.ownWakeURLForToken:friendPublicKey:`
    // reads exactly these keys.

    /// Prefix for one contact's capability. Must match OCTPushUrlValidator.m.
    private static let capPrefix = "khandaq_pushcap_"
    /// The FCM token the stored capabilities were registered against.
    private static let capTokenKey = "khandaq_pushcap_token"

    // MARK: Where capabilities live (re-review 2026-08-22, KQ-08)
    //
    // These are bearer-style per-contact authorization secrets: hold one and you can make the relay
    // wake that device for that relationship until it is revoked or expires. UserDefaults is
    // app-private under normal sandboxing and is still a PREFERENCES store — it lands in backups and
    // in anything that reads the app container. Keychain, device-only, is where a bearer secret
    // belongs.
    //
    // Accessible-after-first-unlock rather than when-unlocked: the push path runs in the background,
    // including right after a reboot before the user has unlocked, and a capability that cannot be
    // read then is a notification that never arrives. ThisDeviceOnly keeps it out of backups and off
    // a restored device, which is the property the review asks for.
    private static let capKeychainService = "org.khandaq.pushcap"

    private static func keychainRead(_ account: String) -> String? {
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: capKeychainService,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        // NOTE: kSecAttrAccessible is deliberately absent here. In a query it acts as a SEARCH
        // predicate, so including it would fail to find items written under any other class — the
        // exact bug that once logged this app out on every launch (see KeychainManager).
        var item: CFTypeRef?
        let status = withUnsafeMutablePointer(to: &item) {
            SecItemCopyMatching(query as CFDictionary, UnsafeMutablePointer($0))
        }
        query.removeAll()
        guard status == errSecSuccess, let data = item as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    @discardableResult
    private static func keychainWrite(_ account: String, _ value: String) -> Bool {
        let base: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: capKeychainService,
            kSecAttrAccount as String: account,
        ]
        SecItemDelete(base as CFDictionary)
        var add = base
        add[kSecValueData as String] = Data(value.utf8)
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        return SecItemAdd(add as CFDictionary, nil) == errSecSuccess
    }

    private static func keychainDelete(_ account: String) {
        let base: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: capKeychainService,
            kSecAttrAccount as String: account,
        ]
        SecItemDelete(base as CFDictionary)
    }

    /// One-time move of a value written by a build that used UserDefaults.
    ///
    /// Runs on read, not at launch: a launch-time sweep would have to enumerate every contact, and
    /// the only capabilities that matter are the ones something actually asks for. The preference is
    /// removed only AFTER the Keychain write succeeds — the reverse order would lose a capability on
    /// a device where the Keychain is briefly unavailable, and a lost capability is a contact whose
    /// pushes stop until the recipient re-registers.
    private static func migrateFromDefaultsIfNeeded(_ account: String) -> String? {
        let defaults = UserDefaults.standard
        guard let legacy = defaults.string(forKey: account), !legacy.isEmpty else { return nil }
        if keychainWrite(account, legacy) {
            defaults.removeObject(forKey: account)
            return legacy
        }
        // Keychain refused: keep the preference so nothing is lost, and try again next time.
        return legacy
    }

    private static func capabilityValue(_ account: String) -> String? {
        if let fromKeychain = keychainRead(account), !fromKeychain.isEmpty {
            return fromKeychain
        }
        return migrateFromDefaultsIfNeeded(account)
    }

    private static let capQueue = DispatchQueue(label: "org.khandaq.pushcap")
    /// Challenge id -> continuation waiting for its nonce.
    private static var pendingChallenges: [String: (String) -> Void] = [:]
    /// When registration for a contact was last attempted, so a relay outage cannot spin.
    private static var lastAttempt: [String: Date] = [:]
    private static let retryCooldown: TimeInterval = 15 * 60

    static func capabilityKey(forFriend publicKey: String) -> String {
        capPrefix + publicKey.uppercased()
    }

    /// The capability already issued to one contact, or nil.
    ///
    /// nil means "publish the URL without one", which is the pre-capability behaviour and is always
    /// safe: the relay never requires a capability it was not given.
    static func capability(forFriend publicKey: String, ownToken: String) -> String? {
        guard let registeredFor = capabilityValue(capTokenKey), registeredFor == ownToken else {
            // The FCM token rotated. Every capability was registered against the old one and can
            // never be used again; publishing one would hand the relay something it does not know.
            return nil
        }
        let cap = capabilityValue(capabilityKey(forFriend: publicKey))
        return (cap?.isEmpty == false) ? cap : nil
    }

    /// Mint, register and store a capability for one contact. Calls back on an arbitrary queue.
    ///
    /// Failure is not an error condition for the caller: the URL is then published without a
    /// capability, exactly as before this existed.
    static func issueCapability(forFriend publicKey: String, ownToken: String,
                                completion: @escaping (String?) -> Void) {
        if let existing = capability(forFriend: publicKey, ownToken: ownToken) {
            completion(existing)
            return
        }
        var proceed = false
        capQueue.sync {
            if let last = lastAttempt[publicKey], Date().timeIntervalSince(last) < retryCooldown {
                proceed = false
            } else {
                lastAttempt[publicKey] = Date()
                proceed = true
            }
        }
        guard proceed else {
            completion(nil)
            return
        }

        var raw = [UInt8](repeating: 0, count: 32)
        guard SecRandomCopyBytes(kSecRandomDefault, raw.count, &raw) == errSecSuccess else {
            completion(nil)
            return
        }
        // base64url, unpadded: '@' and '=' anywhere in a wake URL make it unroutable through the
        // shipped validators (host-confusion defence A33), so the encoding is load-bearing.
        let cap = Data(raw).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")

        postJSON(relayBase + "/register/challenge", ["token": ownToken]) { response in
            guard let cid = response?["cid"] as? String, !cid.isEmpty else {
                completion(nil)
                return
            }
            // Wait for the relay's data push. Bounded: a device with notifications switched off at
            // the OS level will never receive one, and that must degrade to "no capability", not to
            // a callback that never fires.
            var settled = false
            let finish: (String?) -> Void = { nonce in
                guard !settled else { return }
                settled = true
                capQueue.sync { pendingChallenges[cid] = nil }
                guard let nonce = nonce else {
                    completion(nil)
                    return
                }
                postJSON(relayBase + "/register/confirm",
                         ["cid": cid, "nonce": nonce, "cap": cap]) { confirmed in
                    guard confirmed != nil else {
                        completion(nil)
                        return
                    }
                    guard keychainWrite(capabilityKey(forFriend: publicKey), cap),
                          keychainWrite(capTokenKey, ownToken) else {
                        // Registering succeeded on the relay but the secret could not be stored.
                        // Publishing a capability we cannot reproduce would be worse than publishing
                        // none, so this reports failure and the URL goes out without one.
                        completion(nil)
                        return
                    }
                    capQueue.sync { lastAttempt[publicKey] = nil }
                    completion(cap)
                }
            }
            capQueue.sync { pendingChallenges[cid] = { nonce in finish(nonce) } }
            DispatchQueue.global().asyncAfter(deadline: .now() + 30) { finish(nil) }
        }
    }

    /// Called from AppDelegate when the relay's data-only challenge push arrives.
    ///
    /// A challenge nobody asked for is ignored rather than stored: the only party who can cause one
    /// is somebody who knows this token, and there is nothing here for them to gain.
    static func handleRegistrationChallenge(userInfo: [AnyHashable: Any]) -> Bool {
        guard let nonce = userInfo["khandaq_reg_nonce"] as? String,
              let cid = userInfo["khandaq_reg_cid"] as? String,
              !nonce.isEmpty, !cid.isEmpty else {
            return false
        }
        var waiter: ((String) -> Void)?
        capQueue.sync { waiter = pendingChallenges[cid] }
        waiter?(nonce)
        return true
    }

    /// Stop honouring one contact's capability. Presenting it is the proof of the right to drop it.
    static func revokeCapability(forFriend publicKey: String, ownToken: String) {
        let key = capabilityKey(forFriend: publicKey)
        guard let cap = capabilityValue(key), !cap.isEmpty else { return }
        postJSON(relayBase + "/register/revoke", ["token": ownToken, "cap": cap]) { _ in }
        keychainDelete(key)
        // Also clear any pre-KQ-08 copy, so a revoked capability cannot come back from a preference
        // that a migration had not reached yet.
        UserDefaults.standard.removeObject(forKey: key)
    }

    private static func postJSON(_ url: String, _ body: [String: String],
                                 completion: @escaping ([String: Any]?) -> Void) {
        guard let u = URL(string: url),
              let data = try? JSONSerialization.data(withJSONObject: body) else {
            completion(nil)
            return
        }
        var request = URLRequest(url: u)
        request.httpMethod = "POST"
        request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        request.httpBody = data
        request.timeoutInterval = 10
        URLSession.shared.dataTask(with: request) { data, response, _ in
            guard let http = response as? HTTPURLResponse, http.statusCode == 200 else {
                completion(nil)
                return
            }
            let parsed = data.flatMap { try? JSONSerialization.jsonObject(with: $0) } as? [String: Any]
            completion(parsed ?? [:])
        }.resume()
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
