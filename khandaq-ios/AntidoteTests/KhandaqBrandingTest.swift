// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import XCTest
@testable import Antidote

/**
 Older builds wrote "Khandaq is Tox" into every new profile's status, and friends saw it under the
 name. Login clears it, but only while it is still exactly that text — a status the user wrote stays.
 */
class KhandaqBrandingTest: XCTestCase {
    private final class StatusUser: OCTSubmanagerUserMock {
        var status: String?

        override func setUserStatusMessage(_ statusMessage: String?) throws {
            status = statusMessage
        }

        override func userStatusMessage() -> String? {
            return status
        }
    }

    func testUntouchedLegacyStatusIsCleared() {
        let user = StatusUser()
        user.status = KhandaqBranding.legacyDefaultStatusMessage
        KhandaqBranding.clearLegacyDefaultStatus(of: user)
        XCTAssertEqual(user.status, "")
    }

    func testStatusTheUserWroteIsKept() {
        let user = StatusUser()
        user.status = "Khandaq is Tox, and so am I"
        KhandaqBranding.clearLegacyDefaultStatus(of: user)
        XCTAssertEqual(user.status, "Khandaq is Tox, and so am I")
    }
}
