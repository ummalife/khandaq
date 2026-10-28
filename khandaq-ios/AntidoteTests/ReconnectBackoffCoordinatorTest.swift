// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import XCTest
@testable import Antidote

/**
 A failed reconnect attempt is reported twice, by "rebootstrap completed" and by the 4 s check after
 the attempt. Each report used to count as an attempt and schedule a retry of its own, so the backoff
 gave up after half its attempts.
 */
class ReconnectBackoffCoordinatorTest: XCTestCase {
    private let coordinator = ReconnectBackoffCoordinator.shared

    override func setUp() {
        super.setUp()
        coordinator.onConnectionRestored()
    }

    override func tearDown() {
        coordinator.onConnectionRestored()
        super.tearDown()
    }

    func testTwoReportsOfOneFailedAttemptCountOnce() {
        coordinator.scheduleRetryIfStillOffline(reason: .offlinePeriodic)
        coordinator.scheduleRetryIfStillOffline(reason: .offlinePeriodic)

        XCTAssertEqual(coordinator.attempt, 1)
    }

    func testTheNextFailureCountsOnceTheRetryHasRun() {
        coordinator.scheduleRetryIfStillOffline(reason: .offlinePeriodic)

        // The first retry waits about 2 s: the 1 s base doubled, plus up to 10% jitter.
        let ran = expectation(description: "the retry ran")
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.5) {
            ran.fulfill()
        }
        wait(for: [ran], timeout: 5.0)

        coordinator.scheduleRetryIfStillOffline(reason: .offlinePeriodic)

        XCTAssertEqual(coordinator.attempt, 2)
    }
}
