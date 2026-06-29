// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation

private struct Constants {
    static let ActiveAccountDataService = "org.khandaq.messenger.KeychainManager.ActiveAccountDataService"
    static let FallbackStoragePrefix = "org.khandaq.messenger.KeychainManager.fallback."

    static let toxPasswordForActiveAccount = "toxPasswordForActiveAccount"
    static let failedPinAttemptsNumber = "failedPinAttemptsNumber"
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

    /// Removes all data related to active account.
    func deleteActiveAccountData() {
        toxPasswordForActiveAccount = nil
        failedPinAttemptsNumber = nil
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
        return UserDefaults.standard.data(forKey: fallbackStorageKey(key))
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
        let fallbackKey = fallbackStorageKey(key)
        if let newData = newData {
            UserDefaults.standard.set(newData, forKey: fallbackKey)
        } else {
            UserDefaults.standard.removeObject(forKey: fallbackKey)
        }
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
                let status = SecItemAdd(query as CFDictionary, nil)
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
        query[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock

        return query
    }
}
