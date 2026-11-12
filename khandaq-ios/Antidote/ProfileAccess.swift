// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation

// The profile on this device (redesign stage 4, E19 and E20): the key of a profile made without a
// password, the folder of a new profile, why a profile did not open, and what a profile file holds
// before it is moved in. Nothing here changes a format: the save stays a Tox save sealed with Tox's own
// encryption, the history key stays the file objcTox writes, and the keychain items keep their service
// and protection.

// MARK: - The key of a profile without a password

/// A profile made without a password is still encrypted, as every iOS profile always was: the save and
/// the key of the history are sealed with tox_pass_encrypt. Instead of a password the person types,
/// the seal is a random key kept in this device's keychain, with the protection the password of an
/// encrypted profile has there for automatic sign-in (after the first unlock, this device only, never
/// in a backup). A file in the profile's folder names the keychain item; the key never leaves the
/// keychain. Setting a password replaces the key and removes both.
enum ProfileDeviceKey {
    /// The file next to the save that names the keychain item.
    static let markerFileName = "device-key"

    /// 32 random bytes as 64 hex characters: Tox derives its key from them with scrypt, as from any
    /// passphrase.
    static let keyByteCount = 32

    static func markerURL(profilePath: String) -> URL {
        return URL(fileURLWithPath: profilePath, isDirectory: true).appendingPathComponent(markerFileName)
    }

    /// The name of the keychain item, read from the profile's folder; nil for a profile with a password.
    static func identifier(profilePath: String) -> String? {
        guard let data = try? Data(contentsOf: markerURL(profilePath: profilePath)),
              let text = String(data: data, encoding: .utf8) else {
            return nil
        }
        let identifier = text.trimmingCharacters(in: .whitespacesAndNewlines)
        return identifier.isEmpty ? nil : identifier
    }

    /// The folder of the profile open now; nil before one is.
    static var activeProfilePath: String? {
        return UserDefaultsManager().lastActiveProfile.map { ProfileManager().pathForProfileWithName($0) }
    }

    /// Whether the profile open now was made without a password.
    static var activeProfileHasKey: Bool {
        return activeProfilePath.map { hasKey(profilePath: $0) } ?? false
    }

    static func hasKey(profilePath: String) -> Bool {
        return FileManager.default.fileExists(atPath: markerURL(profilePath: profilePath).path)
    }

    /// The key from the keychain; nil when the profile has a password or the keychain cannot be read
    /// (before the first unlock after a restart).
    static func key(profilePath: String, keychain: KeychainManager = KeychainManager()) -> String? {
        guard let identifier = identifier(profilePath: profilePath) else {
            return nil
        }
        return keychain.deviceKey(forIdentifier: identifier)
    }

    /// How the key of a profile made without a password stands on this iPhone; nil for a profile with
    /// a password. `.missing` is for good: the profile came from another iPhone, or the keychain was
    /// reset, and nothing here opens it (stage 5).
    static func lookup(profilePath: String, keychain: KeychainManager = KeychainManager()) -> DeviceKeyLookup? {
        guard hasKey(profilePath: profilePath) else {
            return nil
        }
        guard let identifier = identifier(profilePath: profilePath) else {
            // The file that names the key cannot be read now, as before the first unlock.
            return .unavailable
        }
        return keychain.lookUpDeviceKey(forIdentifier: identifier)
    }

    /// Makes the key of a new profile and keeps it: in the keychain first, then the file that names it.
    /// nil when the keychain refuses the key or the folder the file: such a profile needs a password.
    static func create(profilePath: String, keychain: KeychainManager = KeychainManager()) -> String? {
        var bytes = [UInt8](repeating: 0, count: keyByteCount)
        guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else {
            return nil
        }
        let key = bytes.map { String(format: "%02x", $0) }.joined()
        let identifier = UUID().uuidString
        guard keychain.setDeviceKey(key, forIdentifier: identifier),
              keychain.deviceKey(forIdentifier: identifier) == key else {
            keychain.setDeviceKey(nil, forIdentifier: identifier)
            return nil
        }
        do {
            try Data(identifier.utf8).write(to: markerURL(profilePath: profilePath), options: .atomic)
        }
        catch {
            log("ProfileDeviceKey: cannot write the marker: \(error)")
            keychain.setDeviceKey(nil, forIdentifier: identifier)
            return nil
        }
        return key
    }

    /// Forgets the key: once a password replaced it, or with the profile.
    static func remove(profilePath: String, keychain: KeychainManager = KeychainManager()) {
        if let identifier = identifier(profilePath: profilePath) {
            keychain.setDeviceKey(nil, forIdentifier: identifier)
        }
        try? FileManager.default.removeItem(at: markerURL(profilePath: profilePath))
    }

    #if DEBUG
    /// With UI_TESTING and DEMO_FORGET_DEVICE_KEY the keychain loses the keys of the profiles made
    /// without a password, and the stored password with them, before anything opens: what a new iPhone
    /// restored from a backup, or a reset keychain, looks like. The profile folders stay as they are.
    static func forgetKeysIfAsked() {
        let arguments = ProcessInfo.processInfo.arguments
        guard arguments.contains("UI_TESTING"), arguments.contains("DEMO_FORGET_DEVICE_KEY") else {
            return
        }
        let keychain = KeychainManager()
        let profiles = ProfileManager()
        for name in profiles.allProfileNames {
            if let identifier = identifier(profilePath: profiles.pathForProfileWithName(name)) {
                keychain.setDeviceKey(nil, forIdentifier: identifier)
            }
        }
        keychain.toxPasswordForActiveAccount = nil
    }
    #endif
}

// MARK: - The folder of a new profile

/// A profile lives in a folder named after it, and the sign-in screen shows that name. First launch
/// asks for one name only, so the folder is the person's name made safe for a file name and unique on
/// this device.
enum ProfileFolderName {
    /// Characters, not bytes: well under the 255 bytes a file name may take in UTF-8.
    static let maxLength = 64

    /// `fallback` names the folder when nothing of `name` is left (only slashes or dots).
    static func make(from name: String, existing: [String], fallback: String) -> String {
        var base = clean(name)
        if base.isEmpty {
            base = clean(fallback)
        }
        if base.isEmpty {
            base = "Profile"
        }
        let taken = Set(existing.map { $0.lowercased() })
        guard taken.contains(base.lowercased()) else {
            return base
        }
        var number = 2
        while taken.contains("\(base) \(number)".lowercased()) {
            number += 1
        }
        return "\(base) \(number)"
    }

    /// One line without the characters a path gives meaning to, without a leading dot (a hidden
    /// folder) and without control characters, at most `maxLength` characters.
    static func clean(_ name: String) -> String {
        let forbidden = CharacterSet(charactersIn: "/:\\").union(.controlCharacters).union(.newlines)
        let spaced = name.unicodeScalars.map { forbidden.contains($0) ? " " : String($0) }.joined()
        var words = spaced.split(whereSeparator: { $0.isWhitespace }).joined(separator: " ")
        while words.hasPrefix(".") {
            words.removeFirst()
            words = words.trimmingCharacters(in: .whitespaces)
        }
        return String(words.prefix(maxLength)).trimmingCharacters(in: .whitespaces)
    }
}

// MARK: - Why a profile did not open

/// What sign-in and import say under the field (E20): a wrong password, a damaged file, or a file the
/// app cannot read, each with what to do; anything else names the operation and keeps its code for
/// support.
enum ProfileOpenFailure: Equatable {
    case wrongPassword
    case damaged(details: String)
    case noAccess(details: String)
    case other(title: String, message: String?, details: String)

    /// The extra length tox_pass_encrypt adds: magic, salt, nonce and MAC. A shorter "encrypted" file
    /// is damaged; toxcore is never handed one.
    static let encryptionExtraLength = 80

    /// Why objcTox refused to open a profile. A wrong password and a damaged history key give the same
    /// code, so the save decides: when `password` opens it, the password is right and the key is damaged.
    /// `historyExisted` tells whether the profile had a history before this attempt: without one a save
    /// that does not open means a wrong password.
    static func from(_ error: NSError, saveData: Data?, password: String, historyExisted: Bool) -> ProfileOpenFailure {
        let details = ErrorAlert.details(of: error)
        guard error.domain == kOCTManagerErrorDomain, let code = OCTManagerInitError(rawValue: error.code) else {
            return .other(title: String(localized: "air_signin_failed_title"),
                          message: String(localized: "error_try_again_support_message"),
                          details: details)
        }
        switch code {
            case .passphraseFailed:
                return .wrongPassword
            case .databaseKeyDecryptFailed:
                return saveOpens(saveData, with: password, details: details) ?? .wrongPassword
            case .toxFileDecryptFailed:
                return historyExisted ? .damaged(details: details) : .wrongPassword
            case .databaseKeyCannotReadKey, .cannotImportToxSave:
                return .noAccess(details: details)
            case .toxFileDecryptNull, .toxFileDecryptBadFormat, .databaseKeyDecryptNull, .databaseKeyDecryptBadFormat,
                 .createToxBadFormat, .createToxEncrypted, .databaseKeyMigrationToEncryptedFailed:
                return .damaged(details: details)
            case .databaseKeyCannotCreateKey, .createToxUnknown, .createToxMemoryError, .createToxPortAlloc,
                 .createToxProxyBadType, .createToxProxyBadHost, .createToxProxyBadPort, .createToxProxyNotFound:
                let words = code.strings()
                let generic = String(localized: "error_title")
                return .other(title: words.title == generic ? String(localized: "air_signin_failed_title") : words.title,
                              message: words.message,
                              details: details)
            @unknown default:
                return .other(title: String(localized: "air_signin_failed_title"),
                              message: String(localized: "error_try_again_support_message"),
                              details: details)
        }
    }

    /// The history key did not open with `password`: the save tells a damaged key (the save opens) from
    /// a wrong password (it does not). nil when the save cannot settle it.
    private static func saveOpens(_ saveData: Data?, with password: String, details: String) -> ProfileOpenFailure? {
        guard let data = saveData, OCTToxEncryptSave.isDataEncrypted(data) else {
            return nil
        }
        guard data.count > encryptionExtraLength else {
            return .damaged(details: details)
        }
        do {
            _ = try OCTToxEncryptSave.decryptData(data, withPassphrase: password)
            return .damaged(details: details)
        }
        catch let error as NSError {
            if error.code == OCTToxEncryptSaveDecryptionError.badFormat.rawValue
                || error.code == OCTToxEncryptSaveDecryptionError.null.rawValue {
                return .damaged(details: details)
            }
            return .wrongPassword
        }
    }

    /// The words under the field: what happened, then what to do.
    var text: String {
        switch self {
            case .wrongPassword:
                return String(localized: "air_signin_wrong_password")
            case .damaged:
                return String(localized: "air_signin_damaged")
            case .noAccess:
                return String(localized: "air_signin_no_access")
            case .other(let title, let message, _):
                return [title, message].compactMap { $0 }.joined(separator: " ")
        }
    }

    /// The code for support, under "Details"; a wrong password has none to give.
    var details: String? {
        switch self {
            case .wrongPassword:
                return nil
            case .damaged(let details), .noAccess(let details):
                return details
            case .other(_, _, let details):
                return details
        }
    }
}

// MARK: - Opening a profile

/// What the app checks before objcTox opens a profile, and what it tidies.
enum ProfileOpening {
    /// The error domain of the checks the app makes itself.
    static let errorDomain = "org.khandaq.messenger.ProfileOpening"

    /// A save or a history key that is there but cannot be read: the phone has not been unlocked since
    /// it started, or the file lost its permissions. objcTox reads an unreadable save as no save and
    /// would make a new profile over it, so the app stops first.
    static func unreadableFile(_ configuration: OCTManagerConfiguration) -> ProfileOpenFailure? {
        let storage = configuration.fileStorage
        for (index, path) in [storage.pathForToxSaveFile, storage.pathForDatabaseEncryptionKey].enumerated() {
            guard FileManager.default.fileExists(atPath: path) else {
                continue
            }
            if (try? Data(contentsOf: URL(fileURLWithPath: path))) == nil {
                return .noAccess(details: "\(errorDomain) \(index + 1)")
            }
        }
        return nil
    }

    /// Whether the profile has a history yet: the database objcTox keeps it in.
    static func historyExists(_ configuration: OCTManagerConfiguration) -> Bool {
        return FileManager.default.fileExists(atPath: configuration.fileStorage.pathForDatabase)
    }

    /// A history key without a history was left by an attempt with a wrong password on a profile that
    /// had none yet (an imported file): objcTox sealed a new key with that password, and the right one
    /// would never open it again. It guards nothing, so it goes, and the next attempt makes a new one.
    static func removeKeyWithoutHistory(_ configuration: OCTManagerConfiguration) {
        let storage = configuration.fileStorage
        let fileManager = FileManager.default
        guard !fileManager.fileExists(atPath: storage.pathForDatabase),
              fileManager.fileExists(atPath: storage.pathForDatabaseEncryptionKey) else {
            return
        }
        try? fileManager.removeItem(atPath: storage.pathForDatabaseEncryptionKey)
    }

    static func saveData(_ configuration: OCTManagerConfiguration) -> Data? {
        return try? Data(contentsOf: URL(fileURLWithPath: configuration.fileStorage.pathForToxSaveFile))
    }

    static func isEncrypted(_ configuration: OCTManagerConfiguration) -> Bool {
        guard let data = saveData(configuration), data.count >= 8 else {
            return false
        }
        return OCTToxEncryptSave.isDataEncrypted(data)
    }
}

// MARK: - What a profile file holds

/// The name and the ID a profile file carries, shown before it is moved in (E20).
struct ProfileFilePreview: Equatable {
    let name: String
    let address: String
}

enum ProfileFileReader {
    enum Outcome: Equatable {
        case preview(ProfileFilePreview)
        case failure(ProfileOpenFailure)
    }

    static func isEncrypted(_ data: Data) -> Bool {
        return data.count >= 8 && OCTToxEncryptSave.isDataEncrypted(data)
    }

    /// Reads the file without the network: the save is decrypted with `password` when it is encrypted,
    /// loaded into a Tox instance that never starts and has neither UDP nor local discovery, and
    /// dropped. Runs scrypt for an encrypted file, so call it off the main thread.
    static func read(_ data: Data, password: String?) -> Outcome {
        var save = data
        if isEncrypted(data) {
            guard let password = password, !password.isEmpty else {
                return .failure(.wrongPassword)
            }
            guard data.count > ProfileOpenFailure.encryptionExtraLength else {
                return .failure(.damaged(details: "\(ProfileOpening.errorDomain) 3"))
            }
            do {
                save = try OCTToxEncryptSave.decryptData(data, withPassphrase: password)
            }
            catch let error as NSError {
                if error.code == OCTToxEncryptSaveDecryptionError.failed.rawValue {
                    return .failure(.wrongPassword)
                }
                return .failure(.damaged(details: ErrorAlert.details(of: error)))
            }
        }
        let options = OCTToxOptions()
        options.udpEnabled = false
        options.ipv6Enabled = false
        options.localDiscoveryEnabled = false
        options.holePunchingEnabled = false
        do {
            let tox = try OCTTox(options: options, savedData: save)
            return .preview(ProfileFilePreview(name: tox.userName() ?? "", address: tox.userAddress))
        }
        catch let error as NSError {
            return .failure(.damaged(details: ErrorAlert.details(of: error)))
        }
    }
}
