// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation
@testable import Antidote

/// The transcript entry of the conversation with one person (redesign stage 2): the time column, the
/// author over the first entry of a run only, the text, and the status mark of our own messages.
class AirEntryTextCellSnapshotTest: CellSnapshotTest {
    func testIncomingRunStartUnderTheDayMark() {
        let model = entry("Salam! Are we still on for tonight?", time: "9:41")
        model.dateSeparator = String(localized: "air_day_today")
        model.transcriptMarks = true
        model.authorText = "Amina"
        model.authorColor = AirTone.color(at: 2)
        verify(model)
    }

    func testIncomingContinuationHasNoAuthor() {
        verify(entry("I can bring the map of the route.", time: "9:42"))
    }

    func testOutgoingInTransit() {
        let model = entry("See you at the gate at 19:30.", time: "9:44", status: .transit)
        model.authorText = String(localized: "air_you")
        model.authorColor = KQColor.ink2
        verify(model)
    }

    func testOutgoingSent() {
        verify(entry("I will bring tea.", time: "9:45", status: .sent))
    }

    func testOutgoingDelivered() {
        verify(entry("Done.", time: "9:46", status: .delivered))
    }

    func testLongEditedMessageWrapsInItsColumn() {
        let text = "Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris."
        let model = entry(text, time: "12:22", status: .delivered)
        model.edited = true
        verify(model)
    }
}

private extension AirEntryTextCellSnapshotTest {
    func entry(_ text: String, time: String, status: AirEntryStatus = .none) -> AirEntryTextCellModel {
        let model = AirEntryTextCellModel()
        model.message = text
        model.timeText = time
        model.status = status
        model.isOutgoing = status != .none
        return model
    }

    func verify(_ model: AirEntryTextCellModel) {
        let cell = AirEntryTextCell()
        cell.setupWithTheme(theme, model: model)
        layoutAsTheLogDoes(cell)
        verifyView(cell)
    }
}
