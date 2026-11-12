// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation
@testable import Antidote

/// A call from us as an entry of the transcript (redesign stage 3, E9): the time column, the phone
/// glyph and "Call, 2:17" in tabular figures, or the call nobody answered.
class ChatOutgoingCallCellSnapshotTest: CellSnapshotTest {
    override func setUp() {
        super.setUp()
    }

    func testAnsweredCall() {
        let model = ChatOutgoingCallCellModel()
        model.timeText = "9:41"
        model.callDuration = 137
        model.answered = true

        let cell = ChatOutgoingCallCell()
        cell.setupWithTheme(theme, model: model)

        layoutAsTheLogDoes(cell)
        verifyView(cell)
    }

    func testNonAnsweredCall() {
        let model = ChatOutgoingCallCellModel()
        model.timeText = "9:41"
        model.callDuration = 137
        model.answered = false

        let cell = ChatOutgoingCallCell()
        cell.setupWithTheme(theme, model: model)

        layoutAsTheLogDoes(cell)
        verifyView(cell)
    }
}
