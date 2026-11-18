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
        model.authorColor = AirTone.you
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

    /// KHANDAQ (0.2.57): a group keeps the marks of 0.2.56 (SPEC: groups are unchanged). The entries of
    /// testOutgoingSent and testOutgoingDelivered as the group controllers make them match, pixel for
    /// pixel, the images those two tests had in 0.2.56.
    func testOutgoingSentInAGroup() {
        let model = entry("I will bring tea.", time: "9:45", status: .sent)
        model.takesReadReceipts = false
        verify(model)
    }

    func testOutgoingDeliveredInAGroup() {
        let model = entry("Done.", time: "9:46", status: .delivered)
        model.takesReadReceipts = false
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
        // The conversation with one person, whose read receipts mute sent and delivered (0.2.57).
        model.takesReadReceipts = true
        return model
    }

    func verify(_ model: AirEntryTextCellModel) {
        let cell = AirEntryTextCell()
        cell.setupWithTheme(theme, model: model)
        layoutAsTheLogDoes(cell)
        verifyView(cell)
    }
}

/// KHANDAQ (0.2.58): the name over an entry of another member of a group opens that member, as the
/// member's row in the dossier does. The touch takes the name, a margin to each side and the room of the
/// entry above it, never the message under it; VoiceOver has the name as a button after its entry.
class AirAuthorTapTests: CellSnapshotTest {
    func testTheNameOfAnotherMemberTakesATapAndLeavesTheMessageItsOwn() throws {
        var opened: [UIView] = []
        let cell = textCell(author: "Amina") { opened.append($0) }
        let label = try XCTUnwrap(authorLabel(in: cell))
        let name = label.nameRect

        XCTAssertTrue(hit(cell, label, CGPoint(x: name.midX, y: name.midY)) === label, "the name")
        XCTAssertTrue(hit(cell, label, CGPoint(x: name.midX, y: 1 - AirEntryMetrics.entryInset)) === label, "the room above the name")
        XCTAssertTrue(hit(cell, label, CGPoint(x: name.maxX + AirAuthorLabel.touchMargin - 1, y: name.midY)) === label,
                      "the margin after the name")
        XCTAssertFalse(hit(cell, label, CGPoint(x: name.midX, y: label.bounds.maxY + 1)) === label, "the message under the name")
        XCTAssertFalse(hit(cell, label, CGPoint(x: label.touchRect.maxX + 4, y: name.midY)) === label, "the line after the name")
        XCTAssertGreaterThanOrEqual(label.touchRect.width, KQSize.targetIos)

        XCTAssertTrue(label.accessibilityActivate(), "a double tap on the name does nothing")
        XCTAssertEqual(opened.count, 1)
        XCTAssertTrue(opened.first === label, "the actions point at the name")
    }

    func testVoiceOverHasTheEntryThenTheNameAsAButton() throws {
        let cell = textCell(author: "Amina, moderator") { _ in }
        let label = try XCTUnwrap(authorLabel(in: cell))

        XCTAssertFalse(cell.isAccessibilityElement)
        let elements = try XCTUnwrap(cell.accessibilityElements)
        XCTAssertEqual(elements.count, 2)
        let entry = try XCTUnwrap(elements.first as? UIAccessibilityElement)
        XCTAssertEqual(entry.accessibilityLabel, cell.accessibilityLabel, "the entry says what the row said")
        XCTAssertEqual(entry.accessibilityValue, cell.accessibilityValue)
        XCTAssertTrue(elements.last as? AirAuthorLabel === label)
        XCTAssertTrue(label.isAccessibilityElement)
        XCTAssertEqual(label.accessibilityLabel, "Amina, moderator")
        XCTAssertEqual(label.accessibilityHint, String(localized: "air_group_member_hint"))
        XCTAssertTrue(label.accessibilityTraits & UIAccessibilityTraitButton != 0, "the name is not read as a button")
    }

    func testWithoutATapTheNameIsSaidWithItsEntryAndTakesNoTouch() throws {
        let cell = textCell(author: "Amina", tap: nil)
        let label = try XCTUnwrap(authorLabel(in: cell))
        let name = label.nameRect

        XCTAssertFalse(hit(cell, label, CGPoint(x: name.midX, y: name.midY)) === label)
        XCTAssertTrue(cell.isAccessibilityElement)
        XCTAssertNil(cell.accessibilityElements)
        XCTAssertFalse(label.isAccessibilityElement)
        XCTAssertFalse(label.accessibilityActivate())
    }

    /// A row drawn again for another entry (the log reuses its rows) loses the tap of the one before.
    func testAReusedRowForgetsTheTapOfItsLastEntry() throws {
        var opened = 0
        let cell = textCell(author: "Amina") { _ in opened += 1 }
        let ours = entry(author: String(localized: "air_you"), tap: nil)
        ours.isOutgoing = true
        ours.status = .sent
        cell.setupWithTheme(theme, model: ours)
        layoutAsTheLogDoes(cell)
        let label = try XCTUnwrap(authorLabel(in: cell))
        let name = label.nameRect

        XCTAssertFalse(hit(cell, label, CGPoint(x: name.midX, y: name.midY)) === label)
        XCTAssertFalse(label.accessibilityActivate())
        XCTAssertEqual(opened, 0)
        XCTAssertTrue(cell.isAccessibilityElement)
    }

    /// While the log selects messages a tap on the name selects its row, as on any other part of it.
    func testWhileTheLogSelectsMessagesTheNameLeavesTheTapToTheRow() throws {
        var opened = 0
        let cell = textCell(author: "Amina") { _ in opened += 1 }
        let label = try XCTUnwrap(authorLabel(in: cell))

        cell.setEditing(true, animated: false)
        cell.layoutIfNeeded()
        let name = label.nameRect
        XCTAssertFalse(hit(cell, label, CGPoint(x: name.midX, y: name.midY)) === label)
        XCTAssertTrue(cell.isAccessibilityElement)
        XCTAssertFalse(label.accessibilityActivate())
        XCTAssertEqual(opened, 0)

        cell.setEditing(false, animated: false)
        cell.layoutIfNeeded()
        XCTAssertTrue(hit(cell, label, CGPoint(x: label.nameRect.midX, y: label.nameRect.midY)) === label)
    }

    func testTheNameOverAFileOfAnotherMemberIsAButtonToo() throws {
        var opened = 0
        let model = fileEntry(ChatIncomingFileCellModel(), state: .done)
        model.authorText = "Bilal"
        model.onAuthorTap = { _ in
            opened += 1
        }
        let cell = ChatIncomingFileCell()
        cell.setupWithTheme(theme, model: model)
        layoutAsTheLogDoes(cell)
        let label = cell.entry.authorLabel
        let name = label.nameRect

        XCTAssertTrue(hit(cell, label, CGPoint(x: name.midX, y: name.midY)) === label)
        XCTAssertFalse(hit(cell, label, CGPoint(x: name.midX, y: label.bounds.maxY + 1)) === label, "the file under the name")
        let elements = try XCTUnwrap(cell.accessibilityElements)
        XCTAssertGreaterThanOrEqual(elements.count, 2)
        XCTAssertTrue(elements[1] as? AirAuthorLabel === label, "the name follows its entry")
        XCTAssertTrue(label.accessibilityActivate())
        XCTAssertEqual(opened, 1)
    }

    /// The touch is on the words wherever the label draws them: at the start of a left-to-right row and of a
    /// right-to-left one (Arabic), a name in either script.
    func testTheTouchCoversTheNameInBothDirections() throws {
        for (rightToLeft, name) in [(false, "Amina"), (false, "أمينة"), (true, "أمينة"), (true, "Amina")] {
            let cell = AirEntryTextCell()
            cell.setupWithTheme(theme, model: entry(author: name) { _ in })
            if rightToLeft {
                mirror(cell)
            }
            layoutAsTheLogDoes(cell)
            let label = try XCTUnwrap(authorLabel(in: cell))
            let ink = try XCTUnwrap(inkColumns(of: label), "\(name) draws nothing")
            let touch = label.touchRect
            let context = "\(name), right to left: \(rightToLeft), words at \(ink), touch at \(touch.minX)...\(touch.maxX)"
            XCTAssertLessThanOrEqual(touch.minX, ink.lowerBound, context)
            XCTAssertGreaterThanOrEqual(touch.maxX, ink.upperBound, context)
            XCTAssertLessThan(touch.width, label.bounds.width / 2, "the touch is the name's, not the row's: " + context)
        }
    }
}

private extension AirAuthorTapTests {
    func entry(author: String, text: String = "Salam! Are we still on for tonight?", tap: ((UIView) -> Void)?) -> AirEntryTextCellModel {
        let model = AirEntryTextCellModel()
        model.message = text
        model.timeText = "9:41"
        model.authorText = author
        model.spokenAuthor = author
        model.authorColor = AirTone.color(at: 2)
        model.onAuthorTap = tap
        return model
    }

    func textCell(author: String, tap: ((UIView) -> Void)?) -> AirEntryTextCell {
        let cell = AirEntryTextCell()
        cell.setupWithTheme(theme, model: entry(author: author, tap: tap))
        layoutAsTheLogDoes(cell)
        return cell
    }

    func authorLabel(in view: UIView) -> AirAuthorLabel? {
        for subview in view.subviews {
            if let label = subview as? AirAuthorLabel {
                return label
            }
            if let label = authorLabel(in: subview) {
                return label
            }
        }
        return nil
    }

    /// What a finger at `point` of `label` touches in `cell`.
    func hit(_ cell: UITableViewCell, _ label: UIView, _ point: CGPoint) -> UIView? {
        return cell.hitTest(label.convert(point, to: cell), with: nil)
    }

    /// The row as an Arabic phone lays it out (AppLanguage turns every view right to left).
    func mirror(_ view: UIView) {
        view.semanticContentAttribute = .forceRightToLeft
        view.subviews.forEach { mirror($0) }
    }

    /// The columns where the label draws its words, in its own coordinates.
    func inkColumns(of label: UILabel) -> ClosedRange<CGFloat>? {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = false
        let image = UIGraphicsImageRenderer(bounds: label.bounds, format: format).image { _ in
            label.drawText(in: label.bounds)
        }
        guard let picture = image.cgImage, let data = picture.dataProvider?.data, let bytes = CFDataGetBytePtr(data) else {
            return nil
        }
        let bytesPerPixel = picture.bitsPerPixel / 8
        let columns = (0..<picture.width).filter { x in
            (0..<picture.height).contains { y in
                let pixel = bytes + y * picture.bytesPerRow + x * bytesPerPixel
                return (0..<bytesPerPixel).contains { pixel[$0] != 0 }
            }
        }
        guard let first = columns.first, let last = columns.last else {
            return nil
        }
        return CGFloat(first)...CGFloat(last + 1)
    }
}
