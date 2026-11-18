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
 chip is drawn, so the defect is layout, not storage. These tests pin the layout down without a
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

    /// The same cell with a caption: the caption line sits between the media and the chips.
    func testOutgoingFileCellWithCaptionGrowsForReactions() {
        let plain = makeOutgoingCell(reactions: nil, caption: "hello")
        let withChips = makeOutgoingCell(reactions: "😮", caption: "hello")

        XCTAssertGreaterThan(withChips.frame.height, plain.frame.height,
                             "a reacted captioned media bubble must reserve a line for its chips")
        assertChipsAreVisible(in: withChips)
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
        model.reactionChips = chips(reactions)

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
        model.reactionChips = chips(reactions)

        let cell = ChatIncomingFileCell()
        cell.setupWithTheme(theme, model: model)
        cell.setButtonImage(image)
        updateCellLayout(cell)
        return cell
    }

    func chips(_ emoji: String?) -> [AirReactionChip] {
        return emoji.map { [AirReactionChip(emoji: $0, count: 1, own: false)] } ?? []
    }

    func assertChipsAreVisible(in cell: ChatGenericFileCell) {
        let chips = cell.reactionsView!
        XCTAssertFalse(chips.isHidden, "the chips line is hidden even though the message has reactions")
        XCTAssertGreaterThan(chips.frame.height, 0, "the chips line has no height")
        XCTAssertGreaterThan(chips.frame.width, 0, "the chips line has no width")
        XCTAssertEqual(chips.shownPlates.count, 1, "the reaction has no chip")

        let inCell = chips.convert(chips.bounds, to: cell.contentView)
        XCTAssertTrue(cell.contentView.bounds.contains(inCell),
                      "the chips line is drawn outside the cell (\(inCell) vs \(cell.contentView.bounds))")
    }
}


/**
 The reactions under an entry as chips (0.2.61), as Android and the desktop draw them: each emoji on the
 raised plate with round ends and the count after it once more than one person chose it, ours on the
 selected plate. They were a line of grey label text, and the desktop drew a reaction from an iPhone as a
 small grey glyph; now the three draw the same chip.
 */
class AirReactionChipsTests: XCTestCase {
    private let json = #"[{"e":"❤️","p":["a1","-OWN-"]},{"e":"✍️","p":["b2"]},{"e":"","p":["c3"]},{"e":"👍","p":[]}]"#

    func testTheChipsAreReadFromTheReactionsOfTheMessage() {
        let chips = ChatReactionsFormat.chips(from: json)
        XCTAssertEqual(chips, [AirReactionChip(emoji: "❤️", count: 2, own: true),
                               AirReactionChip(emoji: "✍️", count: 1, own: false)],
                       "an empty emoji or a reaction nobody holds makes a chip")
        XCTAssertEqual(ChatReactionsFormat.display(of: chips), "❤️ 2  ✍️")
        XCTAssertEqual(ChatReactionsFormat.display(from: json), "❤️ 2  ✍️")
        XCTAssertEqual(ChatReactionsFormat.chips(from: nil), [])
        XCTAssertEqual(ChatReactionsFormat.chips(from: "not json"), [])
        XCTAssertNil(ChatReactionsFormat.display(from: "[]"))
    }

    func testEachReactionIsAChipFromTheStartEdge() {
        let view = AirReactionChipsView()
        view.bind(ChatReactionsFormat.chips(from: json))
        view.frame = CGRect(x: 0, y: 0, width: 300, height: view.intrinsicContentSize.height)
        view.layoutIfNeeded()

        let plates = view.shownPlates
        XCTAssertEqual(plates.count, 2)
        XCTAssertEqual(plates[0].frame.minX, 0)
        XCTAssertGreaterThanOrEqual(plates[0].frame.height, AirReactionChipsView.chipHeight)
        XCTAssertEqual(plates[0].layer.cornerRadius, plates[0].frame.height / 2, "the chip has no round ends")
        XCTAssertEqual(plates[1].frame.minX, plates[0].frame.maxX + AirReactionChipsView.gap, accuracy: 0.5)
        XCTAssertEqual(plates[0].backgroundColor, KQColor.selected, "our own reaction is not on the selected plate")
        XCTAssertEqual(plates[1].backgroundColor, KQColor.raised)
        XCTAssertEqual(plates[0].layer.borderWidth, KQStroke.hairline)
        XCTAssertEqual(view.accessibilityLabel, "❤️ 2  ✍️")
    }

    func testChipsThatDoNotFitAreLeftOut() {
        let many = ["😀", "😁", "😂", "😃", "😄", "😅", "😆", "😇"].map { AirReactionChip(emoji: $0, count: 3, own: false) }
        let view = AirReactionChipsView()
        view.bind(many)
        view.frame = CGRect(x: 0, y: 0, width: 120, height: view.intrinsicContentSize.height)
        view.layoutIfNeeded()

        let plates = view.shownPlates
        XCTAssertFalse(plates.isEmpty)
        XCTAssertLessThan(plates.count, many.count, "the chips run past the end of the line")
        XCTAssertLessThanOrEqual(plates.last?.frame.maxX ?? 0, 120.5)
    }

    func testTheTextOfAnEntryTakesItsChips() {
        let bubble = BubbleView()
        bubble.bindReactions([AirReactionChip(emoji: "✍️", count: 1, own: false)])
        XCTAssertFalse(bubble.reactionsView.isHidden)
        XCTAssertEqual(bubble.reactionsView.chips.count, 1)
        bubble.bindReactions([])
        XCTAssertTrue(bubble.reactionsView.isHidden, "an entry without reactions keeps a line for them")
    }
}
