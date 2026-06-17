// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation
import os

enum LaunchRecovery {
    private static let inProgressKey = "org.khandaq.messenger.launch-in-progress"
    private static let lastBuildKey = "org.khandaq.messenger.last-known-build"

    static func prepareForLaunch() {
        resetSessionIfBuildChanged()
        recoverFromPreviousCrashIfNeeded()
        UserDefaults.standard.set(true, forKey: inProgressKey)
        UserDefaults.standard.synchronize()
    }

    /// Call when login screen or main UI is visible — not before async Tox init completes.
    static func markLaunchCompleted() {
        UserDefaults.standard.set(false, forKey: inProgressKey)
        UserDefaults.standard.synchronize()
    }

    private static func resetSessionIfBuildChanged() {
        let current = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? ""
        let previous = UserDefaults.standard.string(forKey: lastBuildKey)

        defer {
            UserDefaults.standard.set(current, forKey: lastBuildKey)
            UserDefaults.standard.synchronize()
        }

        guard let previous = previous, previous != current else {
            return
        }

        guard hasStoredSession() else {
            return
        }

        os_log("LaunchRecovery: build changed %{public}@ -> %{public}@, clearing session", previous, current)
        clearStoredSession()
    }

    private static func recoverFromPreviousCrashIfNeeded() {
        guard UserDefaults.standard.bool(forKey: inProgressKey) else {
            return
        }

        os_log("LaunchRecovery: previous launch did not finish, clearing auto-login session")
        clearStoredSession()
    }

    private static func hasStoredSession() -> Bool {
        return KeychainManager().toxPasswordForActiveAccount != nil
            || UserDefaultsManager().lastActiveProfile != nil
    }

    private static func clearStoredSession() {
        KeychainManager().deleteActiveAccountData()
        UserDefaultsManager().lastActiveProfile = nil
    }
}
