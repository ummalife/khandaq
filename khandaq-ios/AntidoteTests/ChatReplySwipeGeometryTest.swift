// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import XCTest
@testable import Antidote

/**
 KHANDAQ (0.2.56, owner decision 3): swipe a message toward the start edge to answer it, as in Telegram.

 The same cases as ReplySwipeCallbackTest on Android: the row moves toward the start edge only, at most
 72 points, mirrored in a right-to-left layout; the reply point can be reached under that cap; the badge
 grows until the reply point. Then what the cell is built with: no swipe to the right any more, and the
 wave of a voice note keeps its drags.
 */
class ChatReplySwipeGeometryTest: XCTestCase {
    private typealias Geometry = ChatReplySwipeGeometry

    func testLeftToRightTheRowMovesOnlyToTheLeft() {
        XCTAssertEqual(Geometry.offset(forTranslation: -30, isRightToLeft: false), -30)
        XCTAssertEqual(Geometry.offset(forTranslation: -500, isRightToLeft: false), -Geometry.maxOffset)
        XCTAssertEqual(Geometry.offset(forTranslation: 40, isRightToLeft: false), 0)
        XCTAssertEqual(Geometry.offset(forTranslation: 0, isRightToLeft: false), 0)
    }

    func testRightToLeftItIsMirrored() {
        XCTAssertEqual(Geometry.offset(forTranslation: 30, isRightToLeft: true), 30)
        XCTAssertEqual(Geometry.offset(forTranslation: 500, isRightToLeft: true), Geometry.maxOffset)
        XCTAssertEqual(Geometry.offset(forTranslation: -40, isRightToLeft: true), 0)
    }

    func testTheCapIsTheOneOfAndroid() {
        XCTAssertEqual(Geometry.maxOffset, 72)
        XCTAssertEqual(Geometry.triggerOffset, 56)
        XCTAssertEqual(Geometry.offset(forTranslation: -100, isRightToLeft: false, maxOffset: 40), -40)
        XCTAssertEqual(Geometry.offset(forTranslation: 100, isRightToLeft: true, maxOffset: 40), 40)
    }

    func testTheReplyPointIsReachable() {
        XCTAssertLessThan(Geometry.triggerOffset, Geometry.maxOffset)
        XCTAssertTrue(Geometry.isPastTrigger(Geometry.offset(forTranslation: -500, isRightToLeft: false)))
        XCTAssertTrue(Geometry.isPastTrigger(Geometry.offset(forTranslation: 500, isRightToLeft: true)))
        XCTAssertTrue(Geometry.isPastTrigger(-Geometry.triggerOffset))
        XCTAssertFalse(Geometry.isPastTrigger(-(Geometry.triggerOffset - 1)))
        // A drag the other way never gets there, however far it goes.
        XCTAssertFalse(Geometry.isPastTrigger(Geometry.offset(forTranslation: 500, isRightToLeft: false)))
        XCTAssertFalse(Geometry.isPastTrigger(Geometry.offset(forTranslation: -500, isRightToLeft: true)))
    }

    func testTheBadgeGrowsUntilTheReplyPoint() {
        XCTAssertEqual(Geometry.badgeProgress(forOffset: 0), 0)
        XCTAssertEqual(Geometry.badgeProgress(forOffset: -28), 0.5, accuracy: 0.0001)
        XCTAssertEqual(Geometry.badgeProgress(forOffset: 28), 0.5, accuracy: 0.0001)
        XCTAssertEqual(Geometry.badgeProgress(forOffset: -56), 1)
        XCTAssertEqual(Geometry.badgeProgress(forOffset: -72), 1)
        XCTAssertEqual(Geometry.badgeProgress(forOffset: -10, triggerOffset: 0), 1)
    }

    func testOnlyASidewaysDragTowardTheStartEdgeBegins() {
        XCTAssertTrue(Geometry.beginsSwipe(translation: CGPoint(x: -12, y: 3), isRightToLeft: false))
        XCTAssertFalse(Geometry.beginsSwipe(translation: CGPoint(x: 12, y: 3), isRightToLeft: false))
        XCTAssertFalse(Geometry.beginsSwipe(translation: CGPoint(x: -4, y: 9), isRightToLeft: false))
        XCTAssertFalse(Geometry.beginsSwipe(translation: CGPoint(x: -6, y: -6), isRightToLeft: false))
        XCTAssertFalse(Geometry.beginsSwipe(translation: .zero, isRightToLeft: false))
        XCTAssertTrue(Geometry.beginsSwipe(translation: CGPoint(x: 12, y: -3), isRightToLeft: true))
        XCTAssertFalse(Geometry.beginsSwipe(translation: CGPoint(x: -12, y: 3), isRightToLeft: true))
    }

    func testTheBadgeSitsHalfwayIntoTheStripTheRowLeaves() {
        XCTAssertEqual(Geometry.badgeCenterX(rowWidth: 402, offset: -56, isRightToLeft: false), 374)
        XCTAssertEqual(Geometry.badgeCenterX(rowWidth: 402, offset: 0, isRightToLeft: false), 402)
        XCTAssertEqual(Geometry.badgeCenterX(rowWidth: 402, offset: 56, isRightToLeft: true), 28)
        XCTAssertEqual(Geometry.badgeCenterX(rowWidth: 402, offset: 0, isRightToLeft: true), 0)
        // At full size the badge fits in the strip at the reply point.
        XCTAssertLessThanOrEqual(Geometry.badgeDiameter, Geometry.triggerOffset)
    }

    func testTheSwipeToTheRightIsGone() {
        let cell = ChatMovableDateCell(style: .default, reuseIdentifier: nil)
        let views: [UIView] = [cell, cell.contentView, cell.movableContentView]
        let recognizers = views.flatMap { $0.gestureRecognizers ?? [] }
        XCTAssertFalse(recognizers.contains { $0 is UISwipeGestureRecognizer },
                       "the discrete swipe to the right answered from the other edge")
        XCTAssertEqual((cell.contentView.gestureRecognizers ?? []).filter { $0 is UIPanGestureRecognizer }.count, 1)
    }

    func testTheWaveOfAVoiceNoteKeepsItsDrags() {
        XCTAssertTrue(ChatMovableDateCell(style: .default, reuseIdentifier: nil).replySwipeExcludedViews.isEmpty)

        let cell = ChatIncomingFileCell(style: .default, reuseIdentifier: nil)
        XCTAssertEqual(cell.replySwipeExcludedViews.count, 1)
        XCTAssertTrue(cell.replySwipeExcludedViews.allSatisfy { $0.isDescendant(of: cell.voiceMessageView) })
    }
}
