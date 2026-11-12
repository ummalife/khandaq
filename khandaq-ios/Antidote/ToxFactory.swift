// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation

struct ToxFactory {
    static func createToxWithConfiguration(_ configuration: OCTManagerConfiguration,
                                    encryptPassword: String,
                                    successBlock: @escaping (OCTManager) -> Void,
                                    failureBlock: @escaping (Error) -> Void) {
        if ProcessInfo.processInfo.arguments.contains("UI_TESTING") {
            if let error = UITestingSave.open(configuration, password: encryptPassword) {
                DispatchQueue.main.async {
                    failureBlock(error)
                }
                return
            }
            successBlock(OCTManagerMock())
            return
        }

        OCTManagerFactory.manager(with: configuration,
                                                encryptPassword: encryptPassword,
                                                successBlock: { manager in
                                                    ToxNotificationBridge.install(on: manager)
                                                    successBlock(manager)
                                                },
                                                failureBlock: failureBlock)
    }
}

/// objcTox posts the events of a group (its members, its connection, live video) to a notification
/// center of its own, which no screen can reach, while the screens listen on the default center, where the
/// demo world posts them. Without the bridge a group opened on a real profile kept the members and the
/// presence it found on opening: right after joining, the demo group said "1 member, nobody online now"
/// and "Only you are in the group so far" under the founder's welcome. objcTox stays as it is.
enum ToxNotificationBridge {
    /// The events the screens listen to on the default center.
    static let names: [Notification.Name] = [.octGroupPeersUpdated, .octGroupConnectionStatusChange, .octGroupLiveVideoActivity]

    /// Passes the events of the manager's own center on to the default center. That center keeps the
    /// observers and goes away with the manager (logout, another profile). A manager without a center of
    /// its own, as the demo world's, is left alone; the answer says whether the bridge is in place.
    @discardableResult
    static func install(on manager: AnyObject) -> Bool {
        // The center is a private property of objcTox's manager: read only where it exists.
        guard let object = manager as? NSObject,
              object.responds(to: NSSelectorFromString("notificationCenter")),
              let center = object.value(forKey: "notificationCenter") as? NotificationCenter,
              center !== NotificationCenter.default else {
            return false
        }
        for name in names {
            _ = center.addObserver(forName: name, object: nil, queue: nil) { note in
                NotificationCenter.default.post(name: note.name, object: nil, userInfo: note.userInfo)
            }
        }
        return true
    }
}

/// The demo world of the UI tests has no Tox, so nothing would tell a wrong password from the right
/// one. A profile it opens for the first time gets a small save sealed with the password, as a real
/// profile is sealed; the next opening must unseal it, and fails with the error objcTox gives a wrong
/// password (redesign stage 4, E20).
private enum UITestingSave {
    static func open(_ configuration: OCTManagerConfiguration, password: String) -> NSError? {
        let url = URL(fileURLWithPath: configuration.fileStorage.pathForToxSaveFile)
        guard let data = try? Data(contentsOf: url) else {
            let stand = Data("khandaq-ui-testing".utf8)
            if let sealed = try? OCTToxEncryptSave.encryptData(stand, withPassphrase: password) {
                try? sealed.write(to: url, options: .atomic)
            }
            return nil
        }
        guard data.count >= 8, OCTToxEncryptSave.isDataEncrypted(data) else {
            return nil
        }
        if (try? OCTToxEncryptSave.decryptData(data, withPassphrase: password)) != nil {
            return nil
        }
        return NSError(domain: kOCTManagerErrorDomain, code: OCTManagerInitError.databaseKeyDecryptFailed.rawValue, userInfo: nil)
    }
}
