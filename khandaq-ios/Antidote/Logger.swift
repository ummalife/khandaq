// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import CryptoKit
import Foundation

func log(_ string: String, filename: NSString = #file) {
    #if DEBUG
    NSLog("\(filename.lastPathComponent): \(string)")
    #endif
}

/// KHANDAQ (0.2.56): the lines that say a push token arrived. They name it by a short fingerprint, never
/// by the token: whoever holds the FCM token can wake this phone through the relay, and a log leaves the
/// phone (the console, a sysdiagnose, the output of a QA run). The fingerprint still tells one token from
/// another between runs.
enum PushTokenLog {
    static func fcmTokenReceived(_ token: String?) -> String {
        return "Firebase registration token: \(fingerprint(token))"
    }

    static func apnsTokenReceived(_ token: Data) -> String {
        return "APNs token retrieved: \(fingerprint(token))"
    }

    /// The first four bytes of the token's SHA-256 in hex, after "sha256:"; "none" for no token.
    static func fingerprint(_ token: String?) -> String {
        guard let token = token, !token.isEmpty else {
            return "none"
        }
        return fingerprint(Data(token.utf8))
    }

    static func fingerprint(_ token: Data) -> String {
        guard !token.isEmpty else {
            return "none"
        }
        return "sha256:" + SHA256.hash(data: token).prefix(4).map { String(format: "%02x", $0) }.joined()
    }
}
