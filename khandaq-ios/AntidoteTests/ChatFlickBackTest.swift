// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit.UIGestureRecognizerSubclass
import XCTest
@testable import Antidote

/// A pan that reports the drag a test plays through it.
private final class ScriptedPan: UIPanGestureRecognizer {
    var scriptedState: UIGestureRecognizer.State = .possible
    var scriptedTranslation: CGPoint = .zero
    var scriptedVelocity: CGPoint = .zero

    override var state: UIGestureRecognizer.State {
        get { return scriptedState }
        set { scriptedState = newValue }
    }

    override func translation(in view: UIView?) -> CGPoint {
        return scriptedTranslation
    }

    override func velocity(in view: UIView?) -> CGPoint {
        return scriptedVelocity
    }
}

/// Counts the pops instead of guessing whether an animated one has finished.
private final class RecordingNavigation: UINavigationController {
    var pops = 0

    override func popViewController(animated: Bool) -> UIViewController? {
        pops += 1
        return super.popViewController(animated: false)
    }
}

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

    /// A swipe to reply taken back, past the start toward the end edge, only takes the reply back.
    func testADragThatWentTheOtherWayFirstStays() {
        XCTAssertFalse(Geometry.goesBack(translation: CGPoint(x: 90, y: 0), velocity: CGPoint(x: 1200, y: 0),
                                         width: width, isRightToLeft: false, furthestBack: -60))
        XCTAssertFalse(Geometry.goesBack(translation: CGPoint(x: 250, y: 0), velocity: CGPoint(x: 100, y: 0),
                                         width: width, isRightToLeft: false, furthestBack: -60))
        // A finger that wavered a little first still leaves.
        XCTAssertTrue(Geometry.goesBack(translation: CGPoint(x: 90, y: 0), velocity: CGPoint(x: 1200, y: 0),
                                        width: width, isRightToLeft: false, furthestBack: -Geometry.reverseAllowance))
        XCTAssertEqual(Geometry.along(CGPoint(x: -30, y: 5), isRightToLeft: false), -30)
        XCTAssertEqual(Geometry.along(CGPoint(x: -30, y: 5), isRightToLeft: true), 30)
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

    /// The drag as the recognizer reports it, step by step: a flick leaves; a swipe to reply taken back past
    /// the start stays; nothing leaves while the menu of a message is open.
    func testTheDragDecidesOnRelease() {
        func conversation() -> (RecordingNavigation, ChatFlickBack) {
            let navigation = RecordingNavigation(rootViewController: UIViewController())
            let chat = UIViewController()
            navigation.pushViewController(chat, animated: false)
            let table = UITableView()
            chat.view.addSubview(table)
            return (navigation, ChatFlickBack(controller: chat, view: table))
        }
        func play(_ flick: ChatFlickBack, _ steps: [(UIGestureRecognizer.State, CGFloat, CGFloat)]) {
            let pan = ScriptedPan()
            for (state, x, speed) in steps {
                pan.scriptedState = state
                pan.scriptedTranslation = CGPoint(x: x, y: 0)
                pan.scriptedVelocity = CGPoint(x: speed, y: 0)
                flick.panned(pan)
            }
        }
        let flickSteps: [(UIGestureRecognizer.State, CGFloat, CGFloat)] = [(.began, 12, 900), (.changed, 60, 1200),
                                                                            (.ended, 110, 1200)]

        let (flicked, flick) = conversation()
        play(flick, flickSteps)
        XCTAssertEqual(flicked.pops, 1, "a flick leaves")

        let (takenBack, replyTakenBack) = conversation()
        play(replyTakenBack, [(.began, -10, -300), (.changed, -70, -300), (.changed, 40, 1500), (.ended, 90, 1500)])
        XCTAssertEqual(takenBack.pops, 0, "a swipe to reply taken back stays")

        let (covered, underMenu) = conversation()
        underMenu.isCovered = { true }
        play(underMenu, flickSteps)
        XCTAssertEqual(covered.pops, 0, "the menu of a message is open")

        let (cancelled, cancelledFlick) = conversation()
        play(cancelledFlick, [(.began, 12, 900), (.changed, 60, 1200), (.cancelled, 110, 1200)])
        XCTAssertEqual(cancelled.pops, 0, "a cancelled drag stays")
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
            flick.isCovered = { true }
            XCTAssertFalse(flick.gestureRecognizerShouldBegin(pan), "the menu of a message is open")
            flick.isCovered = { false }
            XCTAssertTrue(flick.gestureRecognizerShouldBegin(pan))
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
