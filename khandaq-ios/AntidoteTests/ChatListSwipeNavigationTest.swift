// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import XCTest
@testable import Antidote

/**
 KHANDAQ (owner request 17.08): where a horizontal swipe on the Air list lands.

 The three things worth pinning: the order matches what the user sees, the ends do not wrap, and
 Arabic walks the other way. The RTL case is the one that would ship broken unnoticed: the pills are
 mirrored on screen, so a swipe left has to move backwards through the list, and nobody testing in
 Russian would ever see it.
 */
class ChatListSwipeNavigationTest: XCTestCase {
    func testSwipingLeftMovesForwardInLTR() {
        XCTAssertEqual(ChatListSwipeNavigation.nextFilter(from: .all, swipingLeft: true, isRightToLeft: false), .requests)
        XCTAssertEqual(ChatListSwipeNavigation.nextFilter(from: .requests, swipingLeft: true, isRightToLeft: false), .transit)
        XCTAssertEqual(ChatListSwipeNavigation.nextFilter(from: .transit, swipingLeft: true, isRightToLeft: false), .favorites)
    }

    func testSwipingRightMovesBackInLTR() {
        XCTAssertEqual(ChatListSwipeNavigation.nextFilter(from: .favorites, swipingLeft: false, isRightToLeft: false), .transit)
        XCTAssertEqual(ChatListSwipeNavigation.nextFilter(from: .requests, swipingLeft: false, isRightToLeft: false), .all)
    }

    func testDirectionIsMirroredInRTL() {
        // Arabic: the pills read right to left, so a swipe LEFT must go backwards.
        XCTAssertEqual(ChatListSwipeNavigation.nextFilter(from: .transit, swipingLeft: true, isRightToLeft: true), .requests)
        XCTAssertEqual(ChatListSwipeNavigation.nextFilter(from: .transit, swipingLeft: false, isRightToLeft: true), .favorites)
    }

    func testEndsDoNotWrapAround() {
        XCTAssertNil(ChatListSwipeNavigation.nextFilter(from: .all, swipingLeft: false, isRightToLeft: false))
        XCTAssertNil(ChatListSwipeNavigation.nextFilter(from: .favorites, swipingLeft: true, isRightToLeft: false))
        // The same edges, mirrored.
        XCTAssertNil(ChatListSwipeNavigation.nextFilter(from: .all, swipingLeft: true, isRightToLeft: true))
        XCTAssertNil(ChatListSwipeNavigation.nextFilter(from: .favorites, swipingLeft: false, isRightToLeft: true))
    }

    func testOrderMatchesTheVisiblePills() {
        XCTAssertEqual(ChatListSwipeNavigation.order, [.all, .requests, .transit, .favorites])
        XCTAssertEqual(Array(ChatListSwipeNavigation.order.dropFirst()), AirFilter.pills)
    }
}
