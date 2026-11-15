// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import XCTest
@testable import Antidote

/**
 The lines AppDelegate logs when a push token arrives (QA-Live 15.11). The FCM line printed the whole
 token, and whoever holds it can wake the phone through the relay. The lines name a token by a short
 fingerprint of its SHA-256 now. The tokens here are made up for the test, in the shape FCM and APNs give.
 */
final class PushTokenLogTests: XCTestCase {
    private let fcmToken = "fQm3Zr7XkTq:APA91b" + String(repeating: "Hv2Lw9Np4Ds6Kc8Jx1", count: 8)

    private var apnsToken: Data {
        return Data((0..<32).map { UInt8(($0 * 37 + 11) % 256) })
    }

    func testTheFCMLineNamesTheTokenByAFingerprintOnly() {
        let line = PushTokenLog.fcmTokenReceived(fcmToken)

        XCTAssertFalse(line.contains(fcmToken), "the line carries the whole token")
        XCTAssertFalse(line.contains(String(fcmToken.prefix(8))), "the line carries the start of the token")
        XCTAssertFalse(line.contains(String(fcmToken.suffix(8))), "the line carries the end of the token")
        XCTAssertEqual(line, "Firebase registration token: " + PushTokenLog.fingerprint(fcmToken))
    }

    func testTheAPNsLineNamesTheTokenByAFingerprintOnly() {
        let token = apnsToken
        let hex = token.map { String(format: "%02x", $0) }.joined()
        let line = PushTokenLog.apnsTokenReceived(token)

        XCTAssertFalse(line.lowercased().contains(String(hex.prefix(8))), "the line carries the start of the token")
        XCTAssertEqual(line, "APNs token retrieved: " + PushTokenLog.fingerprint(token))
    }

    func testAFingerprintIsShortStableAndTellsTokensApart() {
        let fingerprint = PushTokenLog.fingerprint(fcmToken)

        XCTAssertTrue(fingerprint.hasPrefix("sha256:"))
        XCTAssertEqual(fingerprint.count, "sha256:".count + 8, "four bytes of the hash, in hex")
        XCTAssertEqual(PushTokenLog.fingerprint(fcmToken), fingerprint, "the same token, another fingerprint")
        XCTAssertNotEqual(PushTokenLog.fingerprint(fcmToken + "x"), fingerprint, "another token, the same fingerprint")
        XCTAssertEqual(PushTokenLog.fingerprint(Data(fcmToken.utf8)), fingerprint, "the text and its bytes differ")
    }

    func testNoTokenIsSaidInWords() {
        XCTAssertEqual(PushTokenLog.fcmTokenReceived(nil), "Firebase registration token: none")
        XCTAssertEqual(PushTokenLog.fingerprint(""), "none")
        XCTAssertEqual(PushTokenLog.fingerprint(Data()), "none")
    }
}
