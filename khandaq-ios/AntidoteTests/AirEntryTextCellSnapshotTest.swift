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

    /// KHANDAQ (tester 21.11, 8255): an entry of another member of a group, alone in its run: the name over it and
    /// the member's tile beside it, level with its bottom, as in Telegram. A line of text grows to the tile.
    func testGroupEntryOfAnotherMemberAloneInItsRun() {
        let model = groupEntry("Salam! Are we still on?", time: "9:41", showsTile: true)
        model.authorText = "Amina"
        model.authorColor = AirTone.color(at: 2)
        verify(model)
    }

    /// The entries of a run before its last keep the column of the tile empty: the text of the run stands in one line.
    func testGroupEntryInsideARunKeepsTheColumnOfTheTile() {
        verify(groupEntry("I can bring the map of the route.", time: "9:42", showsTile: false))
    }

    /// The last entry of a run of several: the tile without the name, level with its last line.
    func testGroupEntryClosingARunHasTheTileBesideItsLastLine() {
        let text = "Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore."
        verify(groupEntry(text, time: "9:43", showsTile: true))
    }

    /// An entry the history brought without a signature over it: the plain tile, and the note under the text.
    func testGroupEntryOfAnUnconfirmedAuthorHasThePlainTileAndItsNote() {
        let model = groupEntry("The gate is open now.", time: "9:44", showsTile: true, style: .unconfirmed)
        model.authorText = "Bilal"
        model.authorColor = AirTone.color(at: 4)
        model.senderUnverified = true
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

    /// An entry of another member as the group controllers make it (ChatGroupController.configureGroupEntry).
    func groupEntry(_ text: String, time: String, showsTile: Bool,
                    style: AirTileView.Style = .person(initials: "A", tone: 2)) -> AirEntryTextCellModel {
        let model = entry(text, time: time)
        model.takesReadReceipts = false
        model.authorTile = AirAuthorTile(style: style, shows: showsTile, onTap: nil)
        return model
    }
}

/// KHANDAQ (tester 21.11, 8255): the tile of another member beside their entries in a group, Telegram-like. It
/// opens the member as their name does, takes a finger around it to a touch target, never the text beside it, and
/// keeps the text of a run in one column whether it shows or not. Your own entries and the conversation with one
/// person have no column for it.
class AirAuthorTileTests: CellSnapshotTest {
    func testTheTileOfAnotherMemberTakesATapAndLeavesTheTextItsOwn() throws {
        var opened: [UIView] = []
        let cell = textCell(entry(showsTile: true) { opened.append($0) })
        let tile = cell.authorTile
        let bounds = tile.bounds

        XCTAssertFalse(tile.isHidden)
        XCTAssertEqual(bounds.size, CGSize(width: KQSize.tileCompact, height: KQSize.tileCompact))
        XCTAssertTrue(hit(cell, tile, CGPoint(x: bounds.midX, y: bounds.midY)) === tile, "the tile")
        XCTAssertTrue(hit(cell, tile, CGPoint(x: bounds.maxX + 3, y: bounds.midY)) === tile, "the room after the tile")
        let text = try XCTUnwrap(cell.bubbleView)
        let textStart = text.convert(text.bounds, to: tile).minX
        XCTAssertFalse(hit(cell, tile, CGPoint(x: textStart + 1, y: bounds.midY)) === tile, "the text beside the tile")
        XCTAssertGreaterThanOrEqual(tile.touchRect.width, KQSize.targetIos)

        tile.perform(Selector(("tapped")))
        XCTAssertEqual(opened.count, 1, "a tap opens the member")
        XCTAssertTrue(opened.first === tile, "the actions point at the tile")
    }

    func testATileWithoutATapIsAPicture() {
        let cell = textCell(entry(showsTile: true, tap: nil))
        let tile = cell.authorTile
        XCTAssertFalse(tile.isHidden)
        XCTAssertFalse(tile.acts)
        XCTAssertFalse(hit(cell, tile, CGPoint(x: tile.bounds.midX, y: tile.bounds.midY)) === tile)
    }

    /// While the log selects messages a tap on the tile selects its row, as on any other part of it.
    func testWhileTheLogSelectsMessagesTheTileLeavesTheTapToTheRow() {
        var opened = 0
        let cell = textCell(entry(showsTile: true) { _ in opened += 1 })
        let tile = cell.authorTile

        cell.setEditing(true, animated: false)
        cell.layoutIfNeeded()
        XCTAssertFalse(tile.acts)
        XCTAssertFalse(hit(cell, tile, CGPoint(x: tile.bounds.midX, y: tile.bounds.midY)) === tile)
        tile.perform(Selector(("tapped")))
        XCTAssertEqual(opened, 0)

        cell.setEditing(false, animated: false)
        cell.layoutIfNeeded()
        XCTAssertTrue(hit(cell, tile, CGPoint(x: tile.bounds.midX, y: tile.bounds.midY)) === tile)
    }

    /// VoiceOver passes the tile by: the entry says who wrote it, and the name over the first entry is the button.
    func testVoiceOverPassesTheTileBy() throws {
        let cell = textCell(entry(showsTile: true) { _ in })
        XCTAssertFalse(cell.authorTile.isAccessibilityElement)
        XCTAssertTrue(cell.isAccessibilityElement, "an entry without its name is one element, as before")
        XCTAssertEqual(cell.accessibilityLabel, "Amina, 9:41")
    }

    /// The text of a run stands in one column, whether its entry shows the tile or not; the name over the first entry
    /// starts where the text does.
    func testTheTextOfARunStandsInOneColumnBesideTheTile() throws {
        let first = textCell(entry(showsTile: false, author: "Amina"))
        let last = textCell(entry(showsTile: true, text: "Salam!"))
        let firstText = frame(of: try XCTUnwrap(first.bubbleView), in: first)
        let lastText = frame(of: try XCTUnwrap(last.bubbleView), in: last)
        let column = AirEntryMetrics.textStart() + KQSize.tileCompact + KQSpace.x8

        XCTAssertEqual(firstText.minX, column, accuracy: 0.5)
        XCTAssertEqual(lastText.minX, column, accuracy: 0.5)
        XCTAssertTrue(first.authorTile.isHidden, "the first entry of a run of several keeps the column empty")
        let name = try XCTUnwrap(authorLabel(in: first))
        XCTAssertEqual(frame(of: name, in: first).minX, column, accuracy: 0.5)
        let tile = frame(of: last.authorTile, in: last)
        XCTAssertLessThanOrEqual(tile.maxX, lastText.minX - KQSpace.x8 + 0.5, "the tile stands before the text")
        XCTAssertEqual(tile.maxY, lastText.maxY, accuracy: 0.5, "the tile stands level with the bottom of the entry")
        XCTAssertGreaterThanOrEqual(tile.minY, AirEntryMetrics.entryInset - 0.5, "a line of text grows to its tile")
        XCTAssertLessThanOrEqual(tile.maxY, last.bounds.maxY - AirEntryMetrics.entryInset + 0.5)
    }

    /// Your own entries in a group and the entries of a conversation with one person have no tile and no column for
    /// it, and a row drawn again for one of them (the log reuses its rows) loses the tile of the entry before.
    func testOwnAndOneToOneEntriesKeepTheirColumnAndAReusedRowForgetsTheTile() throws {
        var opened = 0
        let cell = textCell(entry(showsTile: true) { _ in opened += 1 })

        let ours = AirEntryTextCellModel()
        ours.message = "See you at the gate at 19:30."
        ours.timeText = "9:44"
        ours.isOutgoing = true
        ours.status = .sent
        ours.authorText = String(localized: "air_you")
        cell.setupWithTheme(theme, model: ours)
        layoutAsTheLogDoes(cell)

        let tile = cell.authorTile
        XCTAssertTrue(tile.isHidden)
        XCTAssertFalse(tile.acts)
        XCTAssertFalse(hit(cell, tile, CGPoint(x: tile.bounds.midX, y: tile.bounds.midY)) === tile)
        tile.perform(Selector(("tapped")))
        XCTAssertEqual(opened, 0)
        XCTAssertEqual(frame(of: try XCTUnwrap(cell.bubbleView), in: cell).minX, AirEntryMetrics.textStart(), accuracy: 0.5)
        let name = try XCTUnwrap(authorLabel(in: cell))
        XCTAssertEqual(frame(of: name, in: cell).minX, AirEntryMetrics.textStart(), accuracy: 0.5)
    }

    /// A file of another member keeps the same column and shows the same tile, which opens the member.
    func testAFileOfAnotherMemberHasTheTileToo() throws {
        var opened = 0
        let model = fileEntry(ChatIncomingFileCellModel(), state: .done)
        model.authorText = "Bilal"
        model.authorTile = AirAuthorTile(style: .person(initials: "B", tone: 4), shows: true) { _ in
            opened += 1
        }
        let cell = ChatIncomingFileCell()
        cell.setupWithTheme(theme, model: model)
        layoutAsTheLogDoes(cell)
        let tile = cell.entry.authorTile

        XCTAssertFalse(tile.isHidden)
        XCTAssertEqual(tile.style, .person(initials: "B", tone: 4))
        XCTAssertTrue(hit(cell, tile, CGPoint(x: tile.bounds.midX, y: tile.bounds.midY)) === tile)
        tile.perform(Selector(("tapped")))
        XCTAssertEqual(opened, 1)
        let column = AirEntryMetrics.textStart() + KQSize.tileCompact + KQSpace.x8
        XCTAssertEqual(frame(of: cell.entry.authorLabel, in: cell).minX, column, accuracy: 0.5)

        model.authorTile = nil
        cell.setupWithTheme(theme, model: model)
        layoutAsTheLogDoes(cell)
        XCTAssertTrue(tile.isHidden, "a file of a conversation with one person has no tile")
        XCTAssertEqual(frame(of: cell.entry.authorLabel, in: cell).minX, AirEntryMetrics.textStart(), accuracy: 0.5)
    }

    /// A location of another member keeps its map in the column beside the tile, inside its row, on a narrow phone.
    func testALocationOfAnotherMemberKeepsItsMapBesideTheTile() throws {
        let model = entry(showsTile: true) { _ in }
        model.message = "21.42250, 39.82620"
        model.locationLatitude = 21.4225
        model.locationLongitude = 39.8262
        let cell = textCell(model)
        let bubble = try XCTUnwrap(cell.bubbleView)
        // The map tile is the only image view the bubble holds directly (ChatLocationRowLayoutTest).
        let map = try XCTUnwrap(bubble.subviews.compactMap { $0 as? UIImageView }.first { !$0.isHidden },
                                "a location shows the place of its map")
        let mapFrame = frame(of: map, in: cell)

        XCTAssertGreaterThan(mapFrame.width, 0)
        XCTAssertGreaterThanOrEqual(mapFrame.minX, frame(of: cell.authorTile, in: cell).maxX + KQSpace.x8 - 0.5,
                                    "the map stands after the tile")
        XCTAssertTrue(cell.bounds.contains(mapFrame), "the map stays inside its row: \(mapFrame) in \(cell.bounds)")
    }
}

private extension AirAuthorTileTests {
    func entry(showsTile: Bool, author: String? = nil, text: String = "Salam! Are we still on for tonight?",
               tap: ((UIView) -> Void)?) -> AirEntryTextCellModel {
        let model = AirEntryTextCellModel()
        model.message = text
        model.timeText = "9:41"
        model.authorText = author
        model.spokenAuthor = "Amina"
        model.authorColor = AirTone.color(at: 2)
        model.onAuthorTap = author == nil ? nil : tap
        model.authorTile = AirAuthorTile(style: .person(initials: "A", tone: 2), shows: showsTile, onTap: tap)
        return model
    }

    func entry(showsTile: Bool, author: String? = nil, text: String = "Salam! Are we still on for tonight?") -> AirEntryTextCellModel {
        return entry(showsTile: showsTile, author: author, text: text, tap: { _ in })
    }

    func textCell(_ model: AirEntryTextCellModel) -> AirEntryTextCell {
        let cell = AirEntryTextCell()
        cell.setupWithTheme(theme, model: model)
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

    /// Where `view` stands in the row.
    func frame(of view: UIView, in cell: UITableViewCell) -> CGRect {
        return view.convert(view.bounds, to: cell.contentView)
    }

    /// What a finger at `point` of `view` touches in `cell`.
    func hit(_ cell: UITableViewCell, _ view: UIView, _ point: CGPoint) -> UIView? {
        return cell.hitTest(view.convert(point, to: cell), with: nil)
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

/// QA live 0.2.61: VoiceOver reads a transcript entry as one element, so the quote over a reply has to be
/// in what the entry says, or a reply is heard as a message of its own.
class AirEntrySpokenReplyTests: CellSnapshotTest {
    func testAReplySaysWhomAndWhatItAnswersBeforeItsOwnWords() {
        let model = AirEntryTextCellModel()
        model.message = "See you at the gate"
        model.timeText = "9:41"
        model.spokenAuthor = "Amina"
        var meta = MessageReplyHelper.ReplyMeta()
        meta.senderName = "Yusuf"
        meta.previewText = "Are we still on\nfor tonight?"
        model.replyMeta = meta
        let cell = AirEntryTextCell()
        cell.setupWithTheme(theme, model: model)
        XCTAssertEqual(cell.accessibilityLabel, "Amina, 9:41")
        XCTAssertEqual(cell.accessibilityValue, "In reply to Yusuf, Are we still on for tonight?, See you at the gate")

        model.replyMeta = nil
        cell.setupWithTheme(theme, model: model)
        XCTAssertEqual(cell.accessibilityValue, "See you at the gate", "a message that answers nothing names no quote")
    }
}
