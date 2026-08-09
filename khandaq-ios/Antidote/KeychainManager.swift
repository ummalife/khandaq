// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation

private struct Constants {
    static let ActiveAccountDataService = "org.khandaq.messenger.KeychainManager.ActiveAccountDataService"
    static let FallbackStoragePrefix = "org.khandaq.messenger.KeychainManager.fallback."

    static let toxPasswordForActiveAccount = "toxPasswordForActiveAccount"
    static let failedPinAttemptsNumber = "failedPinAttemptsNumber"
    static let pinLockedUntil = "pinLockedUntil"
}

class KeychainManager {
    /// Tox password used to encrypt/decrypt active account.
    var toxPasswordForActiveAccount: String? {
        get {
            return getStringForKey(Constants.toxPasswordForActiveAccount)
        }
        set {
            setString(newValue, forKey: Constants.toxPasswordForActiveAccount)
        }
    }

    /// Number of failed enters of pin by user.
    var failedPinAttemptsNumber: Int? {
        get {
            return getIntForKey(Constants.failedPinAttemptsNumber)
        }
        set {
            setInt(newValue, forKey: Constants.failedPinAttemptsNumber)
        }
    }

    /// Unix time (in seconds) until which pin entering stays blocked after too many failed attempts.
    ///
    /// KHANDAQ (audit #11): lives in the keychain next to the attempts counter so it survives the
    /// log out that follows a batch of wrong pins — the keychain password logs the user straight back
    /// in, so without a persisted deadline an attacker just repeats batches of ten with no delay.
    var pinLockedUntil: Int? {
        get {
            return getIntForKey(Constants.pinLockedUntil)
        }
        set {
            setInt(newValue, forKey: Constants.pinLockedUntil)
        }
    }

    /// Removes all data related to active account.
    func deleteActiveAccountData() {
        toxPasswordForActiveAccount = nil
        failedPinAttemptsNumber = nil
        pinLockedUntil = nil
    }
}

private extension KeychainManager {
    func getIntForKey(_ key: String) -> Int? {
        guard let data = getDataForKey(key) else {
            return nil
        }

        if data.count == MemoryLayout<Int>.size {
            return data.withUnsafeBytes { rawBuffer in
                rawBuffer.load(as: Int.self)
            }
        }

        if let number = NSKeyedUnarchiver.unarchiveObject(with: data) as? NSNumber {
            return number.intValue
        }

        return nil
    }

    func setInt(_ value: Int?, forKey key: String) {
        guard let value = value else {
            setData(nil, forKey: key)
            return
        }

        var bytes = value
        let data = Data(bytes: &bytes, count: MemoryLayout<Int>.size)
        setData(data, forKey: key)
    }

    func getStringForKey(_ key: String) -> String? {
        guard let data = getDataForKey(key) else {
            return nil
        }

        return NSString(data: data, encoding: String.Encoding.utf8.rawValue) as String?
    }

    func setString(_ string: String?, forKey key: String) {
        let data = string?.data(using: String.Encoding.utf8)
        setData(data, forKey: key)
    }

    func getBoolForKey(_ key: String) -> Bool? {
        guard let data = getDataForKey(key) else {
            return nil
        }

        return (data as NSData).bytes.bindMemory(to: Int.self, capacity: data.count).pointee == 1
    }

    func setBool(_ value: Bool?, forKey key: String) {
        var data: Data? = nil

        if let value = value {
            var bytes = value ? 1 : 0
            withUnsafePointer(to: &bytes) {
                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                    data = Data(bytes: $0, count: MemoryLayout<Int>.size)
                }
            }
        }

        setData(data, forKey: key)
    }

    func fallbackStorageKey(_ key: String) -> String {
        return Constants.FallbackStoragePrefix + key
    }

    func getDataForKey(_ key: String) -> Data? {
        if let data = readKeychainData(forKey: key) {
            return data
        }
        // KHANDAQ (audit A37): migrate any LEGACY plaintext UserDefaults fallback into the keychain, then
        // it is purged (setData → writeFallbackData removes it). New data never touches UserDefaults.
        if let legacy = UserDefaults.standard.data(forKey: fallbackStorageKey(key)) {
            setData(legacy, forKey: key)
            return legacy
        }
        return nil
    }

    func readKeychainData(forKey key: String) -> Data? {
        var query = genericQueryWithKey(key)
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        query[kSecReturnData as String] = kCFBooleanTrue

        var queryResult: AnyObject?
        let status = withUnsafeMutablePointer(to: &queryResult) {
            SecItemCopyMatching(query as CFDictionary, UnsafeMutablePointer($0))
        }

        if status == errSecItemNotFound {
            return nil
        }

        guard status == noErr else {
            log("Error when getting keychain data for key \(key), status \(status)")
            return nil
        }

        guard let data = queryResult as? Data else {
            log("Unexpected data for key \(key)")
            return nil
        }

        return data
    }

    func writeFallbackData(_ newData: Data?, forKey key: String) {
        // KHANDAQ (audit A37): NEVER persist sensitive data (Tox key / profile password) in plaintext
        // UserDefaults — only purge any legacy copy. (A keychain-write failure now means the value isn't
        // persisted this cycle, rather than being leaked in cleartext.)
        _ = newData
        UserDefaults.standard.removeObject(forKey: fallbackStorageKey(key))
    }

    func setData(_ newData: Data?, forKey key: String) {
        let oldKeychainData = readKeychainData(forKey: key)
        var keychainOk = true

        switch (oldKeychainData, newData) {
            case (.some(_), .some(let data)):
                let query = genericQueryWithKey(key)
                var attributesToUpdate = [String : AnyObject]()
                attributesToUpdate[kSecValueData as String] = data as AnyObject?
                let status = SecItemUpdate(query as CFDictionary, attributesToUpdate as CFDictionary)
                if status != noErr {
                    log("Error when updating keychain data for key \(key), status \(status)")
                    keychainOk = false
                }

            case (.some(_), .none):
                let query = genericQueryWithKey(key)
                let status = SecItemDelete(query as CFDictionary)
                if status != noErr {
                    log("Error when deleting keychain data for key \(key), status \(status)")
                    keychainOk = false
                }

            case (.none, .some(let data)):
                var query = genericQueryWithKey(key)
                query[kSecValueData as String] = data as AnyObject?
                // KHANDAQ (audit A37): set ...ThisDeviceOnly ONLY at creation (keeps push/background
                // decrypt working, excludes iCloud sync + encrypted backups).
                query[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
                var status = SecItemAdd(query as CFDictionary, nil)
                if status == errSecDuplicateItem {
                    // KHANDAQ (logout-after-update self-heal): a stale pre-A37 item (stored with a different
                    // accessibility, so our read didn't match it, but the SAME class+service+account primary
                    // key) still occupies the slot → SecItemAdd collides. Delete that orphan by primary key
                    // and re-add with the correct accessibility so the password actually persists this time.
                    SecItemDelete(genericQueryWithKey(key) as CFDictionary)
                    status = SecItemAdd(query as CFDictionary, nil)
                }
                if status != noErr {
                    log("Error when setting keychain data for key \(key), status \(status)")
                    keychainOk = false
                }

            case (.none, .none):
                break
        }

        if keychainOk {
            writeFallbackData(nil, forKey: key)
        } else {
            writeFallbackData(newData, forKey: key)
        }
    }

    func genericQueryWithKey(_ key: String) -> [String : AnyObject] {
        var query = [String : AnyObject]()
        query[kSecClass as String] = kSecClassGenericPassword
        query[kSecAttrService as String] = Constants.ActiveAccountDataService as AnyObject?
        query[kSecAttrAccount as String] = key as AnyObject?
        // KHANDAQ (logout-after-update fix): accessibility is set ONLY at creation time (in setData's add
        // branch), NEVER in this shared query. When kSecAttrAccessible lived here it became a SEARCH
        // predicate on reads/updates/deletes — so after the A37 update to ...ThisDeviceOnly, reads no
        // longer matched a pre-A37 item stored with AfterFirstUnlock → the profile password read back nil
        // → the login screen appeared on every launch. Accessibility is NOT part of a generic-password
        // primary key, so leaving it out here is correct; it is applied when the item is created below.
        return query
    }
}
