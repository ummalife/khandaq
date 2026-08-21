// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation
import MobileCoreServices
import XCTest
@testable import Antidote

/**
 Reported 18 Aug: "on iOS emoji reactions don't stick on media". Running it on the real messenger
 showed the reaction IS stored (the photo cell exposes it to accessibility after a relaunch) but no
 chip is drawn — so the defect is layout, not storage. These tests pin the layout down without a
 simulator run: a file/media cell carrying reactions must be taller than the same cell without them,
 and the chips line must have a real size inside the cell.
 */
class ChatFileCellReactionsLayoutTest: CellSnapshotTest {
    func testOutgoingFileCellGrowsForReactions() {
        let plain = makeOutgoingCell(reactions: nil)
        let withChips = makeOutgoingCell(reactions: "😮")

        XCTAssertGreaterThan(withChips.frame.height, plain.frame.height,
                             "a reacted media bubble must reserve a line for its chips")
        assertChipsAreVisible(in: withChips)
    }

    func testIncomingFileCellGrowsForReactions() {
        let plain = makeIncomingCell(reactions: nil)
        let withChips = makeIncomingCell(reactions: "😮")

        XCTAssertGreaterThan(withChips.frame.height, plain.frame.height,
                             "a reacted incoming media bubble must reserve a line for its chips")
        assertChipsAreVisible(in: withChips)
    }

    /// The same cell with a caption — the caption path deactivates a different bottom constraint.
    func testOutgoingFileCellWithCaptionGrowsForReactions() {
        let plain = makeOutgoingCell(reactions: nil, caption: "hello")
        let withChips = makeOutgoingCell(reactions: "😮", caption: "hello")

        XCTAssertGreaterThan(withChips.frame.height, plain.frame.height,
                             "a reacted captioned media bubble must reserve a line for its chips")
        assertChipsAreVisible(in: withChips)
    }
    /// The explicit-height path in ChatPrivateController/ChatGroupController adds this on top of the
    /// media box; it must be zero when there are no reactions (no layout change for normal media)
    /// and cover the chips line plus its gaps when there are.
    func testReactionsHeightMatchesTheChipsLine() {
        XCTAssertEqual(ChatGenericFileCell.reactionsHeight(for: nil), 0)
        XCTAssertEqual(ChatGenericFileCell.reactionsHeight(for: ""), 0)

        let height = ChatGenericFileCell.reactionsHeight(for: "😮")
        let lineHeight = ChatGenericFileCell.reactionsFont.lineHeight
        XCTAssertGreaterThanOrEqual(height, lineHeight + ChatGenericFileCell.reactionsTopSpacing
                                            + ChatGenericFileCell.reactionsBottomSpacing - 1.0,
                                    "reserved height must cover the chips line and both gaps")

        // Sanity: the reserved band is a single line, not an arbitrary number.
        XCTAssertLessThan(height, 40.0, "one chips line should not reserve more than ~40pt")
    }
}

private extension ChatFileCellReactionsLayoutTest {
    func makeOutgoingCell(reactions: String?, caption: String? = nil) -> ChatOutgoingFileCell {
        let model = ChatOutgoingFileCellModel()
        model.state = .done
        model.fileName = "icon.png"
        model.fileSize = "3.14 KB"
        model.fileUTI = kUTTypePNG as String
        model.caption = caption
        model.reactionsDisplay = reactions

        let cell = ChatOutgoingFileCell()
        cell.setupWithTheme(theme, model: model)
        cell.setButtonImage(image)
        updateCellLayout(cell)
        return cell
    }

    func makeIncomingCell(reactions: String?, caption: String? = nil) -> ChatIncomingFileCell {
        let model = ChatIncomingFileCellModel()
        model.state = .done
        model.fileName = "icon.png"
        model.fileSize = "3.14 KB"
        model.fileUTI = kUTTypePNG as String
        model.caption = caption
        model.reactionsDisplay = reactions

        let cell = ChatIncomingFileCell()
        cell.setupWithTheme(theme, model: model)
        cell.setButtonImage(image)
        updateCellLayout(cell)
        return cell
    }

    func assertChipsAreVisible(in cell: ChatGenericFileCell) {
        let label = cell.reactionsLabel!
        XCTAssertFalse(label.isHidden, "the chips line is hidden even though the message has reactions")
        XCTAssertGreaterThan(label.frame.height, 0, "the chips line has no height")
        XCTAssertGreaterThan(label.frame.width, 0, "the chips line has no width")

        let inCell = label.convert(label.bounds, to: cell.contentView)
        XCTAssertTrue(cell.contentView.bounds.contains(inCell),
                      "the chips line is drawn outside the cell (\(inCell) vs \(cell.contentView.bounds))")
    }
}
