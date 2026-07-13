// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation
import os

enum LaunchRecovery {
    private static let inProgressKey = "org.khandaq.messenger.launch-in-progress"
    private static let failCountKey = "org.khandaq.messenger.launch-fail-count"
    // KHANDAQ (#5): only wipe the saved session after several consecutive incomplete launches (a real
    // crash LOOP). A single transient crash during launch must NOT log the user out / drop the profile.
    private static let maxConsecutiveFailedLaunches = 3

    static func prepareForLaunch() {
        recoverFromPreviousCrashIfNeeded()
        UserDefaults.standard.set(true, forKey: inProgressKey)
        UserDefaults.standard.synchronize()
    }

    /// Call when login screen or main UI is visible — not before async Tox init completes.
    static func markLaunchCompleted() {
        UserDefaults.standard.set(false, forKey: inProgressKey)
        UserDefaults.standard.set(0, forKey: failCountKey)
        UserDefaults.standard.synchronize()
    }

    // KHANDAQ (#167): the old resetSessionIfBuildChanged() wiped the keychain password on EVERY
    // TestFlight update, forcing a fresh profile login after each build. Removed — the crash-loop
    // guard below already covers a genuinely incompatible build.

    private static func recoverFromPreviousCrashIfNeeded() {
        guard UserDefaults.standard.bool(forKey: inProgressKey) else {
            return
        }

        // Previous launch didn't reach the UI. Count consecutive incomplete launches and only clear
        // the saved session once it looks like a genuine crash LOOP — a single crash must not log the
        // user out (#5: "потеря авторизации после вылета").
        let failures = UserDefaults.standard.integer(forKey: failCountKey) + 1
        UserDefaults.standard.set(failures, forKey: failCountKey)
        UserDefaults.standard.synchronize()

        guard failures >= maxConsecutiveFailedLaunches else {
            os_log("LaunchRecovery: previous launch did not finish (%d/%d), keeping session",
                   failures, maxConsecutiveFailedLaunches)
            return
        }

        os_log("LaunchRecovery: %d consecutive incomplete launches, clearing auto-login session", failures)
        clearStoredSession()
        UserDefaults.standard.set(0, forKey: failCountKey)
        UserDefaults.standard.synchronize()
    }

    private static func clearStoredSession() {
        KeychainManager().deleteActiveAccountData()
        UserDefaultsManager().lastActiveProfile = nil
    }
}
