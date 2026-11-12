// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation

enum SessionAutoLogin {
    /// KHANDAQ (redesign stage 3, E17): set when the person logs out on "You", which promises that the
    /// profile stays on the device and opens again with its password. Until a login with the password
    /// clears it, every start shows the login form instead of logging straight back in. The keychain
    /// keeps the password all the same (#11 Fix B), so the logout after a PIN lockout and the logout
    /// for an import behave as before.
    static var isLoggedOutByUser: Bool {
        get {
            return UserDefaultsManager().loggedOutByUser
        }
        set {
            UserDefaultsManager().loggedOutByUser = newValue
        }
    }

    static func canAttemptAutoLogin() -> Bool {
        guard !isLoggedOutByUser else {
            return false
        }

        guard let profileName = UserDefaultsManager().lastActiveProfile else {
            return false
        }

        let profilePath = ProfileManager().pathForProfileWithName(profileName)
        guard let configuration = OCTManagerConfiguration.configurationWithBaseDirectory(profilePath) else {
            return false
        }

        let savePath = configuration.fileStorage.pathForToxSaveFile
        guard FileManager.default.fileExists(atPath: savePath) else {
            return false
        }

        if isProfileEncrypted(at: savePath) {
            if KeychainManager().toxPasswordForActiveAccount != nil {
                return true
            }
            // A profile made without a password opens by itself only while its key is in the keychain.
            // Without it (another iPhone, a reset keychain, before the first unlock) an attempt cannot
            // succeed, and sign-in says what is going on instead (stage 5).
            if case .found? = ProfileDeviceKey.lookup(profilePath: profilePath) {
                return true
            }
            return false
        }

        return true
    }

    /// The stored password; for a profile made without a password whose stored copy is gone (the
    /// crash-loop guard and an import clear it), the key the profile keeps in the keychain (stage 4).
    static func passwordForAutoLogin() -> String? {
        if let password = KeychainManager().toxPasswordForActiveAccount {
            return password
        }
        guard let profileName = UserDefaultsManager().lastActiveProfile else {
            return nil
        }
        return ProfileDeviceKey.key(profilePath: ProfileManager().pathForProfileWithName(profileName))
    }

    private static func isProfileEncrypted(at savePath: String) -> Bool {
        guard let data = try? Data(contentsOf: URL(fileURLWithPath: savePath)),
              data.count >= 8 else {
            return false
        }

        return OCTToxEncryptSave.isDataEncrypted(data)
    }
}
