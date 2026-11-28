// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation
@testable import Antidote

/// The transcript entry of the conversation with one person (redesign stage 2): the author over the first entry of
/// a run only, the text, and at the end of its last line (owner, 28.11.2026: no time column) the time and the status
/// mark of our own messages.
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

    /// An entry whose author's signing key went stale (KQ-03): not named, with the tile of an author nobody confirmed,
    /// a question mark in a dashed frame (it was an empty square, owner 28.11). KHANDAQ (owner, 28.11.2026): no line
    /// under the text says so any more.
    func testGroupEntryOfAnUnconfirmedAuthorHasTheQuestionTile() {
        let model = groupEntry("The gate is open now.", time: "9:44", showsTile: true, style: .unconfirmed)
        model.authorText = String(localized: "air_group_author_unconfirmed")
        model.authorColor = KQColor.ink2
        verify(model)
    }

    /// KHANDAQ (owner, 28.11.2026): two lines whose last one is short: the time ends it at the far edge.
    func testATwoLineTextEndsItsShortLastLineWithTheTime() {
        verify(entry("We will meet at the old gate of the city, by the fountain, as before.", time: "9:48"))
    }

    /// A last line the words fill: the time takes a line of its own under it, at the far edge.
    func testATextWhoseLastLineIsFullHasTheTimeOnALineOfItsOwn() {
        verify(entry(AirEntryMetaTests.fullLine(), time: "9:49"))
    }

    /// Arabic in a left-to-right row ends at the right edge, so its time stands on a line of its own; in a
    /// right-to-left row (the second image) it ends at the left, beside its time.
    func testArabicTextEndsWithItsTimeOnTheSideItEnds() {
        verify(entry("السلام عليكم، هل ما زلنا على موعد الليلة؟", time: "9:50"))
    }

    func testAReplyQuotesItsMessageOverTheText() {
        let model = entry("Yes, after maghrib.", time: "9:51", status: .delivered)
        var meta = MessageReplyHelper.ReplyMeta()
        meta.senderName = "Amina"
        meta.previewText = "Are we still on for Thursday?"
        model.replyMeta = meta
        verify(model)
    }

    /// The reactions stay under the text, under its time.
    func testTheReactionsStandUnderTheTime() {
        let model = entry("The tickets are booked.", time: "9:52")
        model.reactionChips = [AirReactionChip(emoji: "❤️", count: 2, own: true), AirReactionChip(emoji: "👍", count: 1, own: false)]
        verify(model)
    }

    /// A place another member sent: its map with the pin on the place, and its coordinates under the map in grey
    /// caption type, the map's caption (they stood in body type like a message, owner 28.11).
    func testGroupLocationHasItsPinnedMapAndItsCoordinatesUnderIt() {
        // A place of its own: a map MapKit makes for a place another test shows would stand in for its map there.
        let model = groupEntry("39.92077, 32.85411", time: "9:47", showsTile: true)
        model.authorText = "Bilal"
        model.authorColor = AirTone.color(at: 4)
        model.locationLatitude = 39.92077
        model.locationLongitude = 32.85411
        let key = ChatBaseTextCell.mapSnapshotKey(latitude: 39.92077, longitude: 32.85411)
        verify(model) { cell in
            cell.applyLocationMapSnapshot(self.pinnedMap(), forKey: key)
        }
    }
}

private extension AirEntryTextCellSnapshotTest {
    /// A stand-in for the snapshot of the map (MapKit draws none here): two streets on a plain ground, the pin on
    /// its middle as ChatBaseTextCell.makeMapSnapshot draws it on the place.
    func pinnedMap() -> UIImage {
        let size = CGSize(width: 260, height: 120)
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 3
        let ground = UIGraphicsImageRenderer(size: size, format: format).image { context in
            UIColor(red: 0.90, green: 0.91, blue: 0.87, alpha: 1).setFill()
            context.fill(CGRect(origin: .zero, size: size))
            UIColor.white.setFill()
            context.fill(CGRect(x: 0, y: 72, width: size.width, height: 9))
            context.fill(CGRect(x: 152, y: 0, width: 9, height: size.height))
        }
        return ChatBaseTextCell.drawPin(on: ground, at: CGPoint(x: size.width / 2, y: size.height / 2))
    }

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

    /// The entry in a left-to-right row, then in a right-to-left one, each laid out as the log lays it out. The row
    /// turns before its setup, as an Arabic phone has it (AppLanguage): where the time goes depends on the direction
    /// (BubbleView.showTrailingMeta), so a row turned after its layout (verifyView) would keep the height of the other.
    func verify(_ model: AirEntryTextCellModel, after setup: (AirEntryTextCell) -> Void = { _ in }) {
        for rightToLeft in [false, true] {
            let cell = AirEntryTextCell()
            if rightToLeft {
                cell.forceRightToLeft()
            }
            cell.setupWithTheme(theme, model: model)
            setup(cell)
            layoutAsTheLogDoes(cell)
            FBSnapshotVerifyView(cell, identifier: rightToLeft ? "right-to-left" : "normal")
        }
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

/// KHANDAQ (owner, 28.11.2026): an entry has no time column. Its text starts at the margin (after the column of the
/// tile of another member in a group) and runs to the far margin, and its time, with the mark of our own entries,
/// ends the text's last line at the far edge when that line leaves room for it, or stands on a line of its own under
/// the text, at the same edge. A file or a call has its time beside the bottom of its body.
class AirEntryMetaTests: CellSnapshotTest {
    static let rowWidth: CGFloat = 320
    /// The far margin of the text in a row: its right edge.
    static let farEdge = rowWidth - KQSpace.x16

    /// One line of words, as wide as the text of a row of 320 points takes, that leaves less room after it than the
    /// time ("9:49") and its gap need: the time cannot end it.
    static func fullLine(time: String = "9:49") -> String {
        let column = rowWidth - 2 * KQSpace.x16
        let meta = AirEntryMeta()
        meta.show(time: time, edited: false)
        let room = AirEntryMetrics.metaGap + ceil(meta.systemLayoutSizeFitting(UILayoutFittingCompressedSize).width)
        let font = KQFont.font(.body)
        func width(_ text: String) -> CGFloat {
            return (text as NSString).size(withAttributes: [.font: font]).width
        }
        let words = ["Salam,", "we", "are", "at", "the", "old", "gate", "by", "the", "fountain", "of", "the", "city"]
        var line = ""
        for word in words {
            let longer = line.isEmpty ? word : line + " " + word
            guard width(longer) <= column - 1 else {
                break
            }
            line = longer
        }
        // The last word grows until the room after the line is too small for the time.
        while column - width(line) >= room - 1 && width(line + "s") <= column - 1 {
            line += "s"
        }
        return line
    }

    func testAnEntryHasNoTimeColumn() throws {
        let cell = textCell(entry("Done.", time: "9:46"))
        let text = frame(of: try XCTUnwrap(cell.bubbleView), in: cell)
        XCTAssertEqual(text.minX, AirEntryMetrics.logStart, accuracy: 0.5, "the text starts at the margin")
        XCTAssertEqual(text.maxX, AirEntryMetaTests.rowWidth - KQSpace.x16, accuracy: 0.5, "the text runs to the far margin")
    }

    func testTheTimeEndsTheLastLineWhenTheLineLeavesItRoom() throws {
        let cell = textCell(entry("Done.", time: "9:46", status: .delivered))
        let lines = try lineRects(in: cell)
        XCTAssertEqual(lines.count, 1, "the time took a line of its own beside a short word")
        let meta = frame(of: cell.meta, in: cell)
        XCTAssertEqual(meta.maxX, AirEntryMetaTests.rowWidth - KQSpace.x16, accuracy: 0.5, "the time stands at the far edge")
        XCTAssertEqual(try timeBaseline(in: cell), try lastBaseline(in: cell), accuracy: 1, "the time stands on the line of the text")
        XCTAssertFalse(cell.mark.isHidden)
        XCTAssertGreaterThanOrEqual(frame(of: cell.mark, in: cell).minX, frame(of: cell.meta.timeLabel, in: cell).maxX,
                                    "our mark follows the time")
    }

    func testTheTimeTakesALineOfItsOwnUnderAFullLastLine() throws {
        let cell = textCell(entry(AirEntryMetaTests.fullLine(), time: "9:49"))
        let lines = try lineRects(in: cell)
        XCTAssertEqual(lines.count, 2, "the words of a full line and the time on a line under them")
        let meta = frame(of: cell.meta, in: cell)
        XCTAssertGreaterThanOrEqual(meta.minY, lines[0].maxY - 0.5, "the time stands under the words")
        assertMeta(meta, standsIn: lines[1])
        XCTAssertEqual(meta.maxX, AirEntryMetaTests.rowWidth - KQSpace.x16, accuracy: 0.5)
        XCTAssertLessThanOrEqual(meta.maxY, frame(of: try XCTUnwrap(cell.bubbleView), in: cell).maxY + 0.5,
                                 "the row is measured with the time")
    }

    /// Arabic in a left-to-right row stands at the right edge, where it starts, and ends at its left: the time, at
    /// the right edge, takes the next line.
    func testArabicInALeftToRightRowHasItsTimeOnALineOfItsOwn() throws {
        let cell = textCell(entry("مرحبا", time: "9:50"))
        let lines = try lineRects(in: cell)
        XCTAssertEqual(lines.count, 2)
        XCTAssertEqual(try line(of: "مرحبا", in: cell).maxX, AirEntryMetaTests.farEdge, accuracy: 1,
                       "the Arabic words stand at the right edge")
        let meta = frame(of: cell.meta, in: cell)
        XCTAssertGreaterThanOrEqual(meta.minY, lines[0].maxY - 0.5)
        assertMeta(meta, standsIn: lines[1])
        XCTAssertEqual(meta.maxX, AirEntryMetaTests.farEdge, accuracy: 0.5)
    }

    func testArabicInARightToLeftRowEndsWithItsTimeAtTheLeft() throws {
        let cell = textCell(entry("مرحبا", time: "9:50"), rightToLeft: true)
        XCTAssertEqual(try lineRects(in: cell).count, 1)
        XCTAssertEqual(try line(of: "مرحبا", in: cell).maxX, AirEntryMetaTests.farEdge, accuracy: 1,
                       "the Arabic words start at the right edge")
        XCTAssertEqual(frame(of: cell.meta, in: cell).minX, KQSpace.x16, accuracy: 0.5, "the time stands at the left edge")
        XCTAssertEqual(try timeBaseline(in: cell), try lastBaseline(in: cell), accuracy: 1)
    }

    func testEnglishInARightToLeftRowHasItsTimeOnALineOfItsOwnAtTheLeft() throws {
        let cell = textCell(entry("Done.", time: "9:46"), rightToLeft: true)
        let lines = try lineRects(in: cell)
        XCTAssertEqual(lines.count, 2)
        XCTAssertEqual(try line(of: "Done.", in: cell).minX, KQSpace.x16, accuracy: 1, "English starts at the left edge")
        let meta = frame(of: cell.meta, in: cell)
        XCTAssertEqual(meta.minX, KQSpace.x16, accuracy: 0.5)
        XCTAssertGreaterThanOrEqual(meta.minY, lines[0].maxY - 0.5)
        assertMeta(meta, standsIn: lines[1])
    }

    /// Emoji have no direction of their own: they run as the interface does, and the time ends their line.
    func testEmojiInARightToLeftRowEndWithTheirTimeAtTheLeft() throws {
        let cell = textCell(entry("👍🏽🎉", time: "9:53"), rightToLeft: true)
        XCTAssertEqual(try lineRects(in: cell).count, 1)
        XCTAssertEqual(try line(of: "👍🏽🎉", in: cell).maxX, AirEntryMetaTests.farEdge, accuracy: 1)
        XCTAssertEqual(frame(of: cell.meta, in: cell).minX, KQSpace.x16, accuracy: 0.5)
        XCTAssertEqual(try timeBaseline(in: cell), try lastBaseline(in: cell), accuracy: 1)
    }

    /// Figures are read left to right in every language: the coordinates of a place or a phone number keep their
    /// order in an Arabic interface, start at the left and have the time under them, as English does.
    func testFiguresKeepTheirOrderInARightToLeftRow() throws {
        let coordinates = "39.92077, 32.85411"
        let cell = textCell(entry(coordinates, time: "9:56"), rightToLeft: true)
        XCTAssertEqual(try line(of: coordinates, in: cell).minX, KQSpace.x16, accuracy: 1)
        let view = try textView(in: cell)
        let first = view.layoutManager.location(forGlyphAt: view.layoutManager.glyphIndexForCharacter(at: 0)).x
        let last = view.layoutManager.location(forGlyphAt: view.layoutManager.glyphIndexForCharacter(at: coordinates.utf16.count - 1)).x
        XCTAssertLessThan(first, last, "the first figure stands at the left")
        let lines = try lineRects(in: cell)
        XCTAssertEqual(lines.count, 2)
        assertMeta(frame(of: cell.meta, in: cell), standsIn: lines[1])
        XCTAssertEqual(frame(of: cell.meta, in: cell).minX, KQSpace.x16, accuracy: 0.5)

        let english = textCell(entry("15:30", time: "9:57"))
        XCTAssertEqual(try lineRects(in: english).count, 1, "in a left-to-right row the time ends the line of figures")
    }

    /// Each paragraph of a message stands by its own direction; the time follows the last one.
    func testEachParagraphStandsByItsOwnDirection() throws {
        let cell = textCell(entry("Salam\nمرحبا بكم", time: "9:54"))
        XCTAssertEqual(try line(of: "Salam", in: cell).minX, KQSpace.x16, accuracy: 1)
        XCTAssertEqual(try line(of: "مرحبا بكم", in: cell).maxX, AirEntryMetaTests.farEdge, accuracy: 1)
        let lines = try lineRects(in: cell)
        XCTAssertEqual(lines.count, 3, "the English line, the Arabic line and the time under it")
        assertMeta(frame(of: cell.meta, in: cell), standsIn: lines[2])

        let english = textCell(entry("مرحبا\nSee you", time: "9:55"))
        XCTAssertEqual(try lineRects(in: english).count, 2, "the time ends the English line")
        XCTAssertEqual(try timeBaseline(in: english), try lastBaseline(in: english), accuracy: 1)
    }

    /// "edited" left its row under the text: it stands before the time, as Telegram has it.
    func testEditedStandsBeforeTheTime() throws {
        let model = entry("Done.", time: "9:46")
        model.edited = true
        let cell = textCell(model)
        let edited = String(localized: "message_edited_marker")
        XCTAssertEqual(cell.meta.timeLabel.text, edited + " 9:46")
        let bubble = try XCTUnwrap(cell.bubbleView)
        let rows = labels(in: bubble).filter { !$0.isHidden && $0.text == edited }
        XCTAssertTrue(rows.isEmpty, "a row of its own still says edited")
        XCTAssertEqual(try lineRects(in: cell).count, 1)
    }

    /// The room kept for the time is not part of the text: what is copied, said and set again is the message.
    func testTheTextKeepsNoTraceOfTheRoomForTheTime() throws {
        let message = "Salam, see you soon"
        let cell = textCell(entry(message, time: "9:41"))
        let bubble = try XCTUnwrap(cell.bubbleView)
        XCTAssertEqual(bubble.text, message)
        XCTAssertEqual(bubble.attributedText?.string, message)

        // The log draws the row again: one room, not two.
        cell.setupWithTheme(theme, model: entry(message, time: "9:42"))
        layoutAsTheLogDoes(cell)
        XCTAssertEqual(bubble.text, message)
        let raw = try XCTUnwrap(textView(in: cell).attributedText?.string)
        XCTAssertEqual(raw, message + "\u{200B}\u{FFFC}")
        XCTAssertEqual(try lineRects(in: cell).count, 1)
    }

    /// A row of another member of a group: the text after the column of the tile, the time at the far edge.
    func testAGroupEntryKeepsTheColumnOfTheTileAndTheTimeAtTheFarEdge() throws {
        let model = entry("Salam!", time: "9:41")
        model.authorTile = AirAuthorTile(style: .person(initials: "A", tone: 2), shows: true, onTap: nil)
        let cell = textCell(model)
        let text = frame(of: try XCTUnwrap(cell.bubbleView), in: cell)
        XCTAssertEqual(text.minX, AirEntryMetrics.logStart + KQSize.tileCompact + KQSpace.x8, accuracy: 0.5)
        XCTAssertEqual(frame(of: cell.meta, in: cell).maxX, AirEntryMetaTests.rowWidth - KQSpace.x16, accuracy: 0.5)
        XCTAssertEqual(frame(of: cell.authorTile, in: cell).minX, AirEntryMetrics.logStart, accuracy: 0.5)
    }

    func testAFileHasItsTimeBesideTheBottomOfItsRow() throws {
        let cell = ChatOutgoingFileCell()
        cell.setupWithTheme(theme, model: fileEntry(ChatOutgoingFileCellModel(), state: .done))
        layoutAsTheLogDoes(cell)
        let meta = frame(of: cell.entry.meta, in: cell)
        let row = frame(of: cell.fileRow, in: cell)
        XCTAssertEqual(row.minX, AirEntryMetrics.logStart, accuracy: 0.5, "the file starts at the margin")
        XCTAssertEqual(meta.maxX, AirEntryMetaTests.rowWidth - KQSpace.x16, accuracy: 0.5)
        XCTAssertEqual(meta.maxY, row.maxY, accuracy: 0.5, "the time stands level with the bottom of the file")
        XCTAssertLessThanOrEqual(row.maxX, meta.minX - AirEntryMetrics.metaGap + 0.5, "the file runs under its time")
        XCTAssertEqual(cell.entry.timeLabel.text, "9:41")
    }

    func testTheTimeOfAFileStandsOverItsReactions() throws {
        let model = fileEntry(ChatIncomingFileCellModel(), state: .done)
        model.reactionChips = [AirReactionChip(emoji: "👍", count: 1, own: false)]
        let cell = ChatIncomingFileCell()
        cell.setupWithTheme(theme, model: model)
        layoutAsTheLogDoes(cell)
        let meta = frame(of: cell.entry.meta, in: cell)
        XCTAssertFalse(cell.reactionsView.isHidden)
        XCTAssertEqual(meta.maxY, frame(of: cell.fileRow, in: cell).maxY, accuracy: 0.5)
        XCTAssertLessThanOrEqual(meta.maxY, frame(of: cell.reactionsView, in: cell).minY + 0.5)
    }
}

private extension AirEntryMetaTests {
    func entry(_ text: String, time: String, status: AirEntryStatus = .none) -> AirEntryTextCellModel {
        let model = AirEntryTextCellModel()
        model.message = text
        model.timeText = time
        model.status = status
        model.isOutgoing = status != .none
        return model
    }

    func textCell(_ model: AirEntryTextCellModel, rightToLeft: Bool = false) -> AirEntryTextCell {
        let cell = AirEntryTextCell()
        if rightToLeft {
            cell.forceRightToLeft()
        }
        cell.setupWithTheme(theme, model: model)
        layoutAsTheLogDoes(cell)
        return cell
    }

    func frame(of view: UIView, in cell: UITableViewCell) -> CGRect {
        return view.convert(view.bounds, to: cell.contentView)
    }

    func labels(in view: UIView) -> [UILabel] {
        return view.subviews.flatMap { ($0 as? UILabel).map { [$0] } ?? labels(in: $0) }
    }

    func textView(in cell: AirEntryTextCell) throws -> UITextView {
        let bubble = try XCTUnwrap(cell.bubbleView)
        return try XCTUnwrap(bubble.subviews.compactMap { $0 as? UITextView }.first)
    }

    /// The lines of the text, in the row, as the text system laid them out.
    func lineRects(in cell: AirEntryTextCell) throws -> [CGRect] {
        let view = try textView(in: cell)
        let layout = view.layoutManager
        layout.ensureLayout(for: view.textContainer)
        var lines = [CGRect]()
        layout.enumerateLineFragments(forGlyphRange: NSRange(location: 0, length: layout.numberOfGlyphs)) { rect, _, _, _, _ in
            let inView = rect.offsetBy(dx: view.textContainerInset.left, dy: view.textContainerInset.top)
            lines.append(view.convert(inView, to: cell.contentView))
        }
        return lines
    }

    /// How far the line of the words `part` of the message goes, its words and marks, in the row. (The bounding rect
    /// of Arabic glyphs is far wider than the glyphs.)
    func line(of part: String, in cell: AirEntryTextCell) throws -> CGRect {
        let view = try textView(in: cell)
        let layout = view.layoutManager
        layout.ensureLayout(for: view.textContainer)
        let message = try XCTUnwrap(cell.bubbleView.text) as NSString
        let range = message.range(of: part)
        XCTAssertNotEqual(range.location, NSNotFound)
        let glyph = layout.glyphIndexForCharacter(at: range.location)
        let used = layout.lineFragmentUsedRect(forGlyphAt: glyph, effectiveRange: nil)
        let inView = used.offsetBy(dx: view.textContainerInset.left, dy: view.textContainerInset.top)
        return view.convert(inView, to: cell.contentView)
    }

    /// The time on a line of its own stands within that line, which the text keeps as tall as the time.
    func assertMeta(_ meta: CGRect, standsIn line: CGRect, file: StaticString = #filePath, line number: UInt = #line) {
        XCTAssertGreaterThanOrEqual(meta.minY, line.minY - 0.5, "the time stands in its line", file: file, line: number)
        XCTAssertLessThanOrEqual(meta.maxY, line.maxY + 0.5, "the time stands in its line", file: file, line: number)
    }

    /// The baseline of the text's last line, in the row: of its last character, not of the room kept after it for
    /// the time (whose location TextKit gives at its bottom).
    func lastBaseline(in cell: AirEntryTextCell) throws -> CGFloat {
        let view = try textView(in: cell)
        let layout = view.layoutManager
        layout.ensureLayout(for: view.textContainer)
        let message = try XCTUnwrap(cell.bubbleView.text) as NSString
        XCTAssertGreaterThan(message.length, 0)
        let glyph = layout.glyphIndexForCharacter(at: message.length - 1)
        let line = layout.lineFragmentRect(forGlyphAt: glyph, effectiveRange: nil)
        let y = line.minY + layout.location(forGlyphAt: glyph).y + view.textContainerInset.top
        return view.convert(CGPoint(x: 0, y: y), to: cell.contentView).y
    }

    /// The baseline of the words of the time, in the row.
    func timeBaseline(in cell: AirEntryTextCell) throws -> CGFloat {
        let label = cell.meta.timeLabel
        let font = try XCTUnwrap(label.font)
        let y = (label.bounds.height - font.lineHeight) / 2 + font.ascender
        return label.convert(CGPoint(x: 0, y: y), to: cell.contentView).y
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
        let column = AirEntryMetrics.logStart + KQSize.tileCompact + KQSpace.x8

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
        XCTAssertEqual(frame(of: try XCTUnwrap(cell.bubbleView), in: cell).minX, AirEntryMetrics.logStart, accuracy: 0.5)
        let name = try XCTUnwrap(authorLabel(in: cell))
        XCTAssertEqual(frame(of: name, in: cell).minX, AirEntryMetrics.logStart, accuracy: 0.5)
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
        let column = AirEntryMetrics.logStart + KQSize.tileCompact + KQSpace.x8
        XCTAssertEqual(frame(of: cell.entry.authorLabel, in: cell).minX, column, accuracy: 0.5)

        model.authorTile = nil
        cell.setupWithTheme(theme, model: model)
        layoutAsTheLogDoes(cell)
        XCTAssertTrue(tile.isHidden, "a file of a conversation with one person has no tile")
        XCTAssertEqual(frame(of: cell.entry.authorLabel, in: cell).minX, AirEntryMetrics.logStart, accuracy: 0.5)
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
