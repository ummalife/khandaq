// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import XCTest
@testable import Antidote

/**
 KHANDAQ (0.2.65, tester feedback): a flick toward the end edge from anywhere on the messages leaves the
 conversation, as in Telegram; mirrored in a right-to-left layout, where the other way answers a message.
 */
class ChatFlickBackTest: XCTestCase {
    private typealias Geometry = ChatFlickBackGeometry
    private let width: CGFloat = 390

    func testAQuickFlickToTheRightGoesBack() {
        XCTAssertTrue(Geometry.goesBack(translation: CGPoint(x: 90, y: 10), velocity: CGPoint(x: 1200, y: 0),
                                        width: width, isRightToLeft: false))
        XCTAssertTrue(Geometry.goesBack(translation: CGPoint(x: Geometry.minimumFlick, y: 0),
                                        velocity: CGPoint(x: Geometry.minimumVelocity, y: 0),
                                        width: width, isRightToLeft: false))
    }

    func testASlowDragAcrossHalfTheWidthGoesBack() {
        XCTAssertTrue(Geometry.goesBack(translation: CGPoint(x: 200, y: 20), velocity: CGPoint(x: 50, y: 0),
                                        width: width, isRightToLeft: false))
        // Unless the finger is on its way back when it lets go.
        XCTAssertFalse(Geometry.goesBack(translation: CGPoint(x: 250, y: 0), velocity: CGPoint(x: -800, y: 0),
                                         width: width, isRightToLeft: false))
    }

    /// The releases measured on the simulator (iPhone 17 Pro, 402 points wide): XCUITest's quick drag of 140
    /// points was tracked for 118 and ended at 405 points a second, its slow one ended at 164.
    func testTheMeasuredReleasesOfTheUITest() {
        XCTAssertTrue(Geometry.goesBack(translation: CGPoint(x: 117.7, y: 0), velocity: CGPoint(x: 405.7, y: 0),
                                        width: 402, isRightToLeft: false))
        XCTAssertFalse(Geometry.goesBack(translation: CGPoint(x: 110, y: 0), velocity: CGPoint(x: 164, y: 0),
                                         width: 402, isRightToLeft: false))
    }

    /// The UI test of the swipe to reply drags a row 160 points away from the start edge, slowly, and expects
    /// nothing to happen (SwipeAndLanguageUITests.checkReplySwipe).
    func testASlowShortDragStays() {
        XCTAssertFalse(Geometry.goesBack(translation: CGPoint(x: 160, y: 0), velocity: CGPoint(x: 120, y: 0),
                                         width: width, isRightToLeft: false))
        XCTAssertFalse(Geometry.goesBack(translation: CGPoint(x: 40, y: 0), velocity: CGPoint(x: 2000, y: 0),
                                         width: width, isRightToLeft: false))
    }

    func testTheOtherWayIsTheSwipeToReplyNotBack() {
        XCTAssertFalse(Geometry.goesBack(translation: CGPoint(x: -250, y: 0), velocity: CGPoint(x: -2000, y: 0),
                                         width: width, isRightToLeft: false))
        // A flick that went right and came back left at speed does not leave either.
        XCTAssertFalse(Geometry.goesBack(translation: CGPoint(x: 90, y: 0), velocity: CGPoint(x: -1500, y: 0),
                                         width: width, isRightToLeft: false))
    }

    func testScrollingIsNotLeaving() {
        XCTAssertFalse(Geometry.goesBack(translation: CGPoint(x: 100, y: 90), velocity: CGPoint(x: 1500, y: 900),
                                         width: width, isRightToLeft: false))
        XCTAssertFalse(Geometry.goesBack(translation: CGPoint(x: 0, y: 300), velocity: CGPoint(x: 0, y: 3000),
                                         width: width, isRightToLeft: false))
    }

    func testRightToLeftItIsMirrored() {
        XCTAssertTrue(Geometry.goesBack(translation: CGPoint(x: -90, y: 0), velocity: CGPoint(x: -1200, y: 0),
                                        width: width, isRightToLeft: true))
        XCTAssertTrue(Geometry.goesBack(translation: CGPoint(x: -200, y: 0), velocity: .zero,
                                        width: width, isRightToLeft: true))
        XCTAssertFalse(Geometry.goesBack(translation: CGPoint(x: 250, y: 0), velocity: CGPoint(x: 2000, y: 0),
                                         width: width, isRightToLeft: true))
    }

    /// The flick leaves only the screen on top of a stack, and never a conversation that is the root (iPad).
    func testTheFlickBeginsOnlyOnAPushedConversation() {
        let list = UIViewController()
        let chat = UIViewController()
        let navigation = UINavigationController(rootViewController: list)
        navigation.pushViewController(chat, animated: false)
        let table = UITableView()
        chat.view.addSubview(table)

        let flick = ChatFlickBack(controller: chat, view: table)
        let pan = table.gestureRecognizers?.compactMap { $0 as? UIPanGestureRecognizer }
            .first { $0.delegate === flick }
        XCTAssertNotNil(pan)
        if let pan = pan {
            XCTAssertTrue(flick.gestureRecognizerShouldBegin(pan))
            table.isEditing = true
            XCTAssertFalse(flick.gestureRecognizerShouldBegin(pan), "choosing messages is not leaving")
            table.isEditing = false
        }

        let rootChat = UIViewController()
        let rootTable = UITableView()
        rootChat.view.addSubview(rootTable)
        _ = UINavigationController(rootViewController: rootChat)
        let rootFlick = ChatFlickBack(controller: rootChat, view: rootTable)
        let rootPan = rootTable.gestureRecognizers?.compactMap { $0 as? UIPanGestureRecognizer }
            .first { $0.delegate === rootFlick }
        XCTAssertNotNil(rootPan)
        if let rootPan = rootPan {
            XCTAssertFalse(rootFlick.gestureRecognizerShouldBegin(rootPan))
        }
    }
}
