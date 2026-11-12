// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import XCTest
@testable import Antidote

/**
 "You" (redesign stage 3, E17): the ID in its three parts, a new antispam that gives a new ID, and Log
 out that keeps the profile waiting for its password instead of logging straight back in. The flag is
 checked before anything else, so these tests never read or write the keychain.
 */
class AirYouTests: XCTestCase {
    private var loggedOutBefore = false

    override func setUp() {
        super.setUp()
        loggedOutBefore = SessionAutoLogin.isLoggedOutByUser
    }

    override func tearDown() {
        SessionAutoLogin.isLoggedOutByUser = loggedOutBefore
        super.tearDown()
    }

    func testAWholeIdSplitsIntoKeyAntispamAndCheck() {
        let parts = AirYouId.parts(of: OCTManagerMock.demoOwnAddress)
        XCTAssertEqual(parts?.key, String(OCTManagerMock.demoOwnAddress.prefix(64)))
        XCTAssertEqual(parts?.antispam, "1C0FFEE5")
        XCTAssertEqual(parts?.check, "42E3")
    }

    func testAnIdInLowerCaseOrInGroupsSplitsTheSame() {
        let grouped = AirToxId.grouped(OCTManagerMock.demoOwnAddress.lowercased())
        XCTAssertEqual(AirYouId.parts(of: grouped), AirYouId.parts(of: OCTManagerMock.demoOwnAddress))
    }

    func testAnythingButAWholeIdHasNoParts() {
        XCTAssertNil(AirYouId.parts(of: ""))
        XCTAssertNil(AirYouId.parts(of: "123"))
        XCTAssertNil(AirYouId.parts(of: String(OCTManagerMock.demoOwnAddress.prefix(64))))
    }

    func testANewAntispamIsNeverTheOneInUse() {
        for current in [OCTToxNoSpam.min, OCTManagerMock.demoOwnAntispam, OCTToxNoSpam.max] {
            for _ in 0..<200 {
                XCTAssertNotEqual(AirYouId.newAntispam(replacing: current), current)
            }
        }
    }

    /// The demo world changes its ID as toxcore does, so the walk shows a real new ID: the same key,
    /// the new antispam, and a check that New contact accepts.
    func testTheDemoIdChangesWithItsAntispamAsToxcoreDoes() {
        let user = OCTSubmanagerUserMock()
        user.userAddress = OCTManagerMock.demoOwnAddress
        user.nospam = OCTManagerMock.demoOwnAntispam
        XCTAssertEqual(user.userAddress, OCTManagerMock.demoOwnAddress)
        XCTAssertTrue(AirToxId.checkMatches(user.userAddress))

        user.nospam = 0x0000_0001
        let parts = AirYouId.parts(of: user.userAddress)
        XCTAssertEqual(parts?.key, String(OCTManagerMock.demoOwnAddress.prefix(64)))
        XCTAssertEqual(parts?.antispam, "00000001")
        XCTAssertTrue(AirToxId.checkMatches(user.userAddress))
        XCTAssertNotEqual(user.userAddress, OCTManagerMock.demoOwnAddress)
    }

    /// The opening lays the tile over the card and the card over the tile with transforms alone.
    func testTheOpeningLaysOneFrameOverTheOther() {
        let tile = CGRect(x: 16, y: 58, width: 40, height: 40)
        let card = CGRect(x: 16, y: 116, width: 358, height: 358)
        let grow = AirYouTransition.transform(laying: tile, over: card)
        XCTAssertEqual(grow.a, 358.0 / 40.0, accuracy: 0.0001)
        XCTAssertEqual(grow.d, 358.0 / 40.0, accuracy: 0.0001)
        XCTAssertEqual(grow.tx, card.midX - tile.midX, accuracy: 0.0001)
        XCTAssertEqual(grow.ty, card.midY - tile.midY, accuracy: 0.0001)

        let shrink = AirYouTransition.transform(laying: card, over: tile)
        XCTAssertEqual(shrink.a * grow.a, 1, accuracy: 0.0001)
        XCTAssertEqual(shrink.ty, -grow.ty, accuracy: 0.0001)
    }

    func testLogOutOnYouKeepsTheProfileWaitingForItsPassword() {
        SessionAutoLogin.isLoggedOutByUser = true
        XCTAssertFalse(SessionAutoLogin.canAttemptAutoLogin())
    }

    func testTheWaitOutlivesARestartUntilALoginClearsIt() {
        SessionAutoLogin.isLoggedOutByUser = true
        // A fresh manager reads what the last run left, as the next start of the app does.
        XCTAssertTrue(UserDefaultsManager().loggedOutByUser)

        SessionAutoLogin.isLoggedOutByUser = false
        XCTAssertFalse(UserDefaultsManager().loggedOutByUser)
    }
}
