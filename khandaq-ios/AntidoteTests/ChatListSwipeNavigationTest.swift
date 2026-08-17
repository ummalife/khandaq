// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import XCTest
@testable import Antidote

/**
 KHANDAQ (owner request 17.08) — where a horizontal swipe on the chat list lands.

 The three things worth pinning: the order matches what the user sees, the ends do not wrap, and
 Arabic walks the other way. The RTL case is the one that would ship broken unnoticed — the tabs are
 mirrored on screen, so a swipe left has to move backwards through the list, and nobody testing in
 Russian would ever see it.
 */
class ChatListSwipeNavigationTest: XCTestCase {
    func testSwipingLeftMovesForwardInLTR() {
        XCTAssertEqual(ChatListSwipeNavigation.nextTab(from: .direct, swipingLeft: true, isRightToLeft: false), .groups)
        XCTAssertEqual(ChatListSwipeNavigation.nextTab(from: .groups, swipingLeft: true, isRightToLeft: false), .favorites)
    }

    func testSwipingRightMovesBackInLTR() {
        XCTAssertEqual(ChatListSwipeNavigation.nextTab(from: .favorites, swipingLeft: false, isRightToLeft: false), .groups)
        XCTAssertEqual(ChatListSwipeNavigation.nextTab(from: .groups, swipingLeft: false, isRightToLeft: false), .direct)
    }

    func testDirectionIsMirroredInRTL() {
        // Arabic: the tabs read right-to-left, so a swipe LEFT must go backwards.
        XCTAssertEqual(ChatListSwipeNavigation.nextTab(from: .groups, swipingLeft: true, isRightToLeft: true), .direct)
        XCTAssertEqual(ChatListSwipeNavigation.nextTab(from: .groups, swipingLeft: false, isRightToLeft: true), .favorites)
    }

    func testEndsDoNotWrapAround() {
        XCTAssertNil(ChatListSwipeNavigation.nextTab(from: .direct, swipingLeft: false, isRightToLeft: false))
        XCTAssertNil(ChatListSwipeNavigation.nextTab(from: .favorites, swipingLeft: true, isRightToLeft: false))
        // …and the same edges, mirrored.
        XCTAssertNil(ChatListSwipeNavigation.nextTab(from: .direct, swipingLeft: true, isRightToLeft: true))
        XCTAssertNil(ChatListSwipeNavigation.nextTab(from: .favorites, swipingLeft: false, isRightToLeft: true))
    }

    func testOrderMatchesTheVisibleTabs() {
        XCTAssertEqual(ChatListSwipeNavigation.order, [.direct, .groups, .favorites])
    }
}
