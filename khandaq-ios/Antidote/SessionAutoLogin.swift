// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation

enum SessionAutoLogin {
    static func canAttemptAutoLogin() -> Bool {
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
            return KeychainManager().toxPasswordForActiveAccount != nil
        }

        return true
    }

    static func passwordForAutoLogin() -> String? {
        KeychainManager().toxPasswordForActiveAccount
    }

    private static func isProfileEncrypted(at savePath: String) -> Bool {
        guard let data = try? Data(contentsOf: URL(fileURLWithPath: savePath)),
              data.count >= 8 else {
            return false
        }

        return OCTToxEncryptSave.isDataEncrypted(data)
    }
}
