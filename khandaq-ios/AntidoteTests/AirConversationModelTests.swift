// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import XCTest
@testable import Antidote

/**
 The conversation screen's rules as plain values (redesign stage 2): the status of our messages, the
 runs of the transcript and the day marks, the presence line, the hint and the length counter under
 the field, the key in groups, the day strip and the best hours, and the two stores kept outside
 Realm (the hourly marks and the refused messages).
 */
class AirConversationModelTests: XCTestCase {
    private var savedLanguage: Any?
    private var savedAppleLanguages: Any?
    private var calendar: Calendar!
    private var temporaryDirectory: URL?

    override func setUp() {
        super.setUp()
        savedLanguage = UserDefaults.standard.object(forKey: AppLanguage.storageKey)
        savedAppleLanguages = UserDefaults.standard.object(forKey: "AppleLanguages")
        calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Europe/Istanbul")!
        use("en")
    }

    override func tearDown() {
        UserDefaults.standard.set(savedLanguage, forKey: AppLanguage.storageKey)
        UserDefaults.standard.set(savedAppleLanguages, forKey: "AppleLanguages")
        AppLanguage.applyAtLaunch()
        if let directory = temporaryDirectory {
            try? FileManager.default.removeItem(at: directory)
        }
        super.tearDown()
    }

    // MARK: - Status of our messages

    func testStatusFollowsTheMessageAndThePerson() {
        XCTAssertEqual(AirEntryStatus.ofOutgoingText(isSaved: false, isDelivered: false, messageId: -1, personOnline: false), .transit)
        XCTAssertEqual(AirEntryStatus.ofOutgoingText(isSaved: false, isDelivered: false, messageId: -1, personOnline: true), .sending)
        XCTAssertEqual(AirEntryStatus.ofOutgoingText(isSaved: false, isDelivered: false, messageId: 7, personOnline: true), .sent)
        XCTAssertEqual(AirEntryStatus.ofOutgoingText(isSaved: false, isDelivered: true, messageId: 7, personOnline: false), .delivered)
        // Notes for yourself go nowhere and carry no mark.
        XCTAssertEqual(AirEntryStatus.ofOutgoingText(isSaved: true, isDelivered: true, messageId: 0, personOnline: false), .none)
    }

    func testEveryStatusHasItsIconAndWords() {
        XCTAssertEqual(AirEntryStatus.transit.iconName, "clock")
        XCTAssertEqual(AirEntryStatus.sent.iconName, "check")
        XCTAssertEqual(AirEntryStatus.delivered.iconName, "check-double")
        XCTAssertEqual(AirEntryStatus.failed.iconName, "alert")
        XCTAssertNil(AirEntryStatus.none.iconName)
        XCTAssertEqual(AirEntryStatus.failed.spokenText, "not sent")
        XCTAssertEqual(AirEntryStatus.transit.spokenText, "in transit")
    }

    // MARK: - Seen (0.2.57)

    func testOnlyAMessageThatHasLeftTurnsSeen() {
        XCTAssertEqual(AirEntryStatus.sent.applyingSeen(true), .seen)
        XCTAssertEqual(AirEntryStatus.delivered.applyingSeen(true), .seen)
        // What waits, is leaving or was refused keeps its mark, whatever the person has seen.
        XCTAssertEqual(AirEntryStatus.transit.applyingSeen(true), .transit)
        XCTAssertEqual(AirEntryStatus.sending.applyingSeen(true), .sending)
        XCTAssertEqual(AirEntryStatus.failed.applyingSeen(true), .failed)
        XCTAssertEqual(AirEntryStatus.none.applyingSeen(true), .none)
        // Not seen yet, or a person on an old client, which sends no read receipts: the marks stay.
        XCTAssertEqual(AirEntryStatus.sent.applyingSeen(false), .sent)
        XCTAssertEqual(AirEntryStatus.delivered.applyingSeen(false), .delivered)
        // A file goes the same way.
        XCTAssertEqual(AirFileEntryText.status(state: .done, delivered: true, personOnline: false).applyingSeen(true), .seen)
        XCTAssertEqual(AirFileEntryText.status(state: .waitingConfirmation, delivered: false, personOnline: false).applyingSeen(true), .transit)
        XCTAssertEqual(AirFileEntryText.status(state: .cancelled, delivered: false, personOnline: true).applyingSeen(true), .failed)
    }

    func testSeenIsTwoTealChecksAndSentAndDeliveredTakeTheInkOfTheTime() {
        XCTAssertEqual(AirEntryStatus.seen.iconName, "check-double")
        // In a 1:1 conversation with a friend, the one that takes read receipts.
        XCTAssertTrue(AirStatusMarkView.color(for: .sent, takesReadReceipts: true) === KQColor.ink3)
        XCTAssertTrue(AirStatusMarkView.color(for: .delivered, takesReadReceipts: true) === KQColor.ink3)
        XCTAssertTrue(AirStatusMarkView.color(for: .seen, takesReadReceipts: true) === KQColor.seen)
        XCTAssertTrue(AirStatusMarkView.color(for: .transit, takesReadReceipts: true) === KQColor.transit)
        XCTAssertTrue(AirStatusMarkView.color(for: .failed, takesReadReceipts: true) === KQColor.danger)
        // Android's khandaq_teal, light and dark, so the three clients show seen alike.
        XCTAssertEqual(rgb(KQColor.seen, .light), 0x029B7D)
        XCTAssertEqual(rgb(KQColor.seen, .dark), 0x10B98F)
    }

    func testAGroupAndTheSavedChatKeepTheInkOfTheirMarks() {
        // SPEC: groups are unchanged. The group controllers and the Saved chat leave takesReadReceipts
        // false, and sent and delivered keep the ink of 0.2.56 there, light and dark.
        XCTAssertFalse(AirEntryTextCellModel().takesReadReceipts)
        XCTAssertFalse(ChatOutgoingFileCellModel().takesReadReceipts)
        XCTAssertTrue(AirStatusMarkView.color(for: .sent, takesReadReceipts: false) === KQColor.ink)
        XCTAssertTrue(AirStatusMarkView.color(for: .delivered, takesReadReceipts: false) === KQColor.ink)
        XCTAssertEqual(rgb(KQColor.ink, .light), 0x0F1218)
        XCTAssertEqual(rgb(KQColor.ink, .dark), 0xE7EAEF)
        XCTAssertTrue(AirStatusMarkView.color(for: .sending, takesReadReceipts: false) === KQColor.ink3)
        XCTAssertTrue(AirStatusMarkView.color(for: .transit, takesReadReceipts: false) === KQColor.transit)
        XCTAssertTrue(AirStatusMarkView.color(for: .failed, takesReadReceipts: false) === KQColor.danger)

        // The mark as drawn: delivered in ink for a group, muted for the same entry of a 1:1 chat.
        let mark = AirStatusMarkView()
        mark.set(.delivered, animated: false)
        XCTAssertTrue(tint(of: mark) === KQColor.ink)
        mark.takesReadReceipts = true
        XCTAssertTrue(tint(of: mark) === KQColor.ink3)
        mark.takesReadReceipts = false
        mark.set(.sent, animated: false)
        XCTAssertTrue(tint(of: mark) === KQColor.ink)
    }

    func testSeenIsSaidInEveryLanguageThatSaysDelivered() {
        XCTAssertEqual(AirEntryStatus.seen.spokenText, "seen")
        use("ru")
        XCTAssertEqual(AirEntryStatus.seen.spokenText, "прочитано")
        use("ar")
        XCTAssertEqual(AirEntryStatus.seen.spokenText, "قُرئت")
        use("zh")
        XCTAssertEqual(AirEntryStatus.seen.spokenText, "已读")
    }

    func testAReceiptLeavesOnlyFromAConversationInFrontOfThePerson() {
        XCTAssertTrue(AirSeenGate.allowsReport(onScreen: true, appActive: true, sceneActive: true, lockScreenShown: false, isSaved: false, receiptsOn: true))
        // Another screen, the background or a locked device, the app's own lock over it, or the Saved chat.
        XCTAssertFalse(AirSeenGate.allowsReport(onScreen: false, appActive: true, sceneActive: true, lockScreenShown: false, isSaved: false, receiptsOn: true))
        XCTAssertFalse(AirSeenGate.allowsReport(onScreen: true, appActive: false, sceneActive: true, lockScreenShown: false, isSaved: false, receiptsOn: true))
        XCTAssertFalse(AirSeenGate.allowsReport(onScreen: true, appActive: true, sceneActive: false, lockScreenShown: false, isSaved: false, receiptsOn: true))
        XCTAssertFalse(AirSeenGate.allowsReport(onScreen: true, appActive: true, sceneActive: true, lockScreenShown: true, isSaved: false, receiptsOn: true))
        XCTAssertFalse(AirSeenGate.allowsReport(onScreen: true, appActive: true, sceneActive: true, lockScreenShown: false, isSaved: true, receiptsOn: true))
    }

    /// KHANDAQ (ideas 1): Settings > Privacy > Send read receipts off: not even a conversation in front of
    /// the person reports what they have seen.
    func testNoReceiptLeavesWhileReadReceiptsAreOff() {
        XCTAssertFalse(AirSeenGate.allowsReport(onScreen: true, appActive: true, sceneActive: true, lockScreenShown: false, isSaved: false,
                                                receiptsOn: false))
    }

    /// KHANDAQ (ideas 1): typing is announced only while the switch is on, and never from the Saved chat,
    /// which has no friend to tell (objcTox took friend number 0 for it).
    func testTypingIsAnnouncedOnlyWhileTheSwitchIsOnAndNeverFromSaved() {
        XCTAssertTrue(AirTypingGate.mayAnnounce(typingOn: true, isSaved: false))
        XCTAssertFalse(AirTypingGate.mayAnnounce(typingOn: false, isSaved: false))
        XCTAssertFalse(AirTypingGate.mayAnnounce(typingOn: true, isSaved: true))
        XCTAssertFalse(AirTypingGate.mayAnnounce(typingOn: false, isSaved: true))
    }

    // MARK: - Transcript

    func testARunGoesOnForTheSameAuthorWithinFiveMinutesOfTheSameDay() {
        let start = date(2026, 11, 11, 12, 0)
        let first = AirRunKey(author: "peer", date: start, isText: true)
        let soon = AirRunKey(author: "peer", date: start.addingTimeInterval(4 * 60), isText: true)
        let late = AirRunKey(author: "peer", date: start.addingTimeInterval(6 * 60), isText: true)
        let other = AirRunKey(author: "", date: start.addingTimeInterval(60), isText: true)
        let file = AirRunKey(author: "peer", date: start.addingTimeInterval(60), isText: false)

        XCTAssertTrue(AirTranscript.startsRun(first, after: nil, calendar: calendar))
        XCTAssertFalse(AirTranscript.startsRun(soon, after: first, calendar: calendar))
        XCTAssertTrue(AirTranscript.startsRun(late, after: first, calendar: calendar))
        XCTAssertTrue(AirTranscript.startsRun(other, after: first, calendar: calendar))
        XCTAssertTrue(AirTranscript.startsRun(soon, after: file, calendar: calendar))

        let beforeMidnight = AirRunKey(author: "peer", date: date(2026, 11, 10, 23, 58), isText: true)
        let afterMidnight = AirRunKey(author: "peer", date: date(2026, 11, 11, 0, 1), isText: true)
        XCTAssertTrue(AirTranscript.startsRun(afterMidnight, after: beforeMidnight, calendar: calendar))
    }

    func testShortNameIsTheFirstWord() {
        XCTAssertEqual(AirTranscript.shortName("  Yusuf Karimov "), "Yusuf")
        XCTAssertEqual(AirTranscript.shortName("Amina"), "Amina")
        XCTAssertEqual(AirTranscript.shortName(""), "")
    }

    func testDayMarksSayTodayAndYesterdayThenTheDate() {
        let now = date(2026, 11, 11, 15, 0)
        let english = Locale(identifier: "en_US")
        XCTAssertEqual(AirDayMark.text(for: date(2026, 11, 11, 9, 0), now: now, locale: english, calendar: calendar), "Today")
        XCTAssertEqual(AirDayMark.text(for: date(2026, 11, 10, 23, 0), now: now, locale: english, calendar: calendar), "Yesterday")
        let older = AirDayMark.text(for: date(2026, 11, 2, 9, 0), now: now, locale: english, calendar: calendar)
        XCTAssertTrue(older.contains("2"), older)
        XCTAssertFalse(older.contains("2026"), "the year is left out within the same year: \(older)")
        XCTAssertTrue(AirDayMark.text(for: date(2025, 3, 2, 9, 0), now: now, locale: english, calendar: calendar).contains("2025"))

        use("ru")
        XCTAssertEqual(AirDayMark.text(for: date(2026, 11, 11, 9, 0), now: now, locale: AppLanguage.locale, calendar: calendar), "Сегодня")
    }

    // MARK: - Presence line

    func testPresenceLineNamesTheLinkAndWhenThePersonCameOnline() {
        let since = date(2026, 11, 11, 9, 40)
        let time: (Date) -> String = { _ in "9:40" }
        XCTAssertEqual(AirPresenceLine.text(reach: .direct, onlineSince: since, isTyping: false, time: time), "online since 9:40, direct link")
        XCTAssertEqual(AirPresenceLine.text(reach: .relay, onlineSince: nil, isTyping: false, time: time), "online, via a relay")
        XCTAssertEqual(AirPresenceLine.text(reach: .direct, onlineSince: since, isTyping: true, time: time), "typing")
        XCTAssertEqual(AirPresenceLine.text(reach: .pending, onlineSince: nil, isTyping: false, time: time), "waiting for a reply to the request")

        use("ru")
        XCTAssertEqual(AirPresenceLine.text(reach: .direct, onlineSince: since, isTyping: false, time: time), "в сети с 9:40, связь напрямую")
    }

    /// QA live 0.2.61: the band read the link after a presence line that had already named it.
    func testTheBandSaysTheLinkOnlyWhenThePresenceLineDoesNot() {
        XCTAssertTrue(AirPresenceLine.namesLink(reach: .direct, isTyping: false))
        XCTAssertTrue(AirPresenceLine.namesLink(reach: .relay, isTyping: false))
        XCTAssertTrue(AirPresenceLine.namesLink(reach: .away(secondsAgo: 25 * 60), isTyping: false))
        XCTAssertTrue(AirPresenceLine.namesLink(reach: .pending, isTyping: false))
        XCTAssertFalse(AirPresenceLine.namesLink(reach: .direct, isTyping: true), "the typing line names no link")
        XCTAssertTrue(AirPresenceLine.namesLink(reach: .away(secondsAgo: 60), isTyping: true), "typing counts only while online")

        let strip = AirDayStrip.make(hours: [], now: Date())
        let band = AirPresenceBandView()
        band.configure(link: .direct, presence: "online since 9:40, direct link", strip: strip, nowOnline: true,
                       stripHint: "", stripSpoken: "no marks",
                       speaksLink: !AirPresenceLine.namesLink(reach: .direct, isTyping: false))
        XCTAssertEqual(band.accessibilityLabel, "online since 9:40, direct link. Last 24 hours: no marks")
        band.configure(link: .relay, presence: "typing", strip: strip, nowOnline: true, stripHint: "", stripSpoken: "no marks",
                       speaksLink: !AirPresenceLine.namesLink(reach: .relay, isTyping: true))
        XCTAssertEqual(band.accessibilityLabel, "typing. link via a relay. Last 24 hours: no marks")
    }

    func testTheQuoteOverAReplyIsSpokenAsItIsShown() {
        var meta = MessageReplyHelper.ReplyMeta()
        meta.senderName = "QA A"
        meta.previewText = "hello\nfrom Windows "
        XCTAssertEqual(MessageReplyHelper.spokenQuote(meta), "In reply to QA A, hello from Windows")
        meta.senderName = ""
        XCTAssertEqual(MessageReplyHelper.spokenQuote(meta), "In reply to Unknown, hello from Windows")
        meta.previewText = " \n "
        XCTAssertNil(MessageReplyHelper.spokenQuote(meta), "no quote is shown, none is spoken")
        XCTAssertNil(MessageReplyHelper.spokenQuote(nil))

        // The words on each side of a line break stay apart in the preview a reply sends, NUL still goes.
        var multiline = MessageReplyHelper.ReplyMeta()
        multiline.senderName = "QA Droid B"
        multiline.previewText = "Are we still on\nfor tonight?\u{0}"
        let sent = MessageReplyHelper.parse(MessageReplyHelper.encode(multiline, bodyText: "Yes")).reply
        XCTAssertEqual(sent?.previewText, "Are we still on for tonight?")

        use("ru")
        meta.senderName = "QA A"
        meta.previewText = "привет"
        XCTAssertEqual(MessageReplyHelper.spokenQuote(meta), "В ответ QA A, привет")
    }

    func testAwayTextCountsInWordsThatAgreeWithTheNumber() {
        XCTAssertEqual(AirPresenceLine.awayText(seconds: 30), "offline for under a minute")
        XCTAssertEqual(AirPresenceLine.awayText(seconds: 60), "offline for 1 minute")
        XCTAssertEqual(AirPresenceLine.awayText(seconds: 25 * 60), "offline for 25 minutes")
        XCTAssertEqual(AirPresenceLine.awayText(seconds: 3 * 3600), "offline for 3 hours")
        XCTAssertEqual(AirPresenceLine.awayText(seconds: 2 * 86400), "offline for 2 days")
        XCTAssertEqual(AirPresenceLine.awayText(seconds: 9 * 86400), "offline for over a week")

        use("ru")
        XCTAssertEqual(AirPresenceLine.awayText(seconds: 21 * 60), "не в сети 21 минуту")
        XCTAssertEqual(AirPresenceLine.awayText(seconds: 25 * 60), "не в сети 25 минут")
        XCTAssertEqual(AirPresenceLine.awayText(seconds: 2 * 3600), "не в сети 2 часа")
        XCTAssertEqual(AirPresenceLine.awayText(seconds: 5 * 86400), "не в сети 5 дней")

        use("ar")
        XCTAssertEqual(AirPresenceLine.awayText(seconds: 2 * 60), "غير متصل منذ 2 دقيقتين")
    }

    // MARK: - Under the field

    func testHintSaysWhatHappensToAMessageSentNow() {
        let live = AirComposeHint.make(isSaved: false, contactExists: true, name: "Amina", reach: .direct)
        XCTAssertEqual(live.text, "Goes right away: Amina is online")
        XCTAssertEqual(live.dot, .live)

        let waiting = AirComposeHint.make(isSaved: false, contactExists: true, name: "Yusuf", reach: .away(secondsAgo: 1500))
        XCTAssertEqual(waiting.text, "Waits in transit: Yusuf is offline. It will go on its own")
        XCTAssertEqual(waiting.dot, .transit)

        let saved = AirComposeHint.make(isSaved: true, contactExists: false, name: "", reach: .local)
        XCTAssertEqual(saved.text, "The note stays only on this device")
        XCTAssertEqual(saved.dot, .none)

        XCTAssertEqual(AirComposeHint.make(isSaved: false, contactExists: false, name: "", reach: .local).text, "")

        use("ru")
        XCTAssertEqual(AirComposeHint.make(isSaved: false, contactExists: true, name: "Амина", reach: .relay).text, "Уйдёт сразу: Амина в сети")
        XCTAssertEqual(AirComposeHint.make(isSaved: false, contactExists: true, name: "Юсуф", reach: .pending).text,
                       "Подождёт в пути: Юсуф не в сети. Уйдёт само")
    }

    func testCounterAppearsInTheLastTenthOfTheRoom() {
        XCTAssertEqual(AirComposeLimit.bytes, 1334)
        XCTAssertNil(AirComposeLimit.remaining(forBytes: 0))
        XCTAssertNil(AirComposeLimit.remaining(forBytes: 1200))
        XCTAssertEqual(AirComposeLimit.remaining(forBytes: 1201), 133)
        XCTAssertEqual(AirComposeLimit.remaining(forBytes: 1334), 0)
        XCTAssertEqual(AirComposeLimit.remaining(forBytes: 1340), -6)

        XCTAssertEqual(AirComposeLimit.counterText(remaining: 1), "1 byte left")
        XCTAssertEqual(AirComposeLimit.counterText(remaining: 120), "120 bytes left")
        XCTAssertEqual(AirComposeLimit.counterText(remaining: -6), "6 bytes over the limit")

        use("ru")
        XCTAssertEqual(AirComposeLimit.counterText(remaining: 1), "остался 1 байт")
        XCTAssertEqual(AirComposeLimit.counterText(remaining: 3), "осталось 3 байта")
        XCTAssertEqual(AirComposeLimit.counterText(remaining: 120), "осталось 120 байт")
        XCTAssertEqual(AirComposeLimit.counterText(remaining: -6), "на 6 байт больше предела")
    }

    /// Past the room of one message the counter says how many messages the text goes as: the field no
    /// longer stops at 1000 characters, a long text goes in parts.
    func testCounterSaysHowManyMessagesALongTextGoesAs() {
        XCTAssertNil(AirComposeLimit.counterText(for: String(repeating: "a", count: 1200)))
        XCTAssertEqual(AirComposeLimit.counterText(for: String(repeating: "a", count: 1201)), "133 bytes left")
        XCTAssertEqual(AirComposeLimit.counterText(for: String(repeating: "a", count: 1335)), "Goes as 2 messages")
        // The header of a reply counts too.
        XCTAssertEqual(AirComposeLimit.counterText(for: String(repeating: "a", count: 1330), head: "HEAD\n"), "Goes as 2 messages")
        // A group message has room for 1372 bytes.
        XCTAssertNil(AirComposeLimit.counterText(for: String(repeating: "a", count: 1234), maxBytes: AirGroupComposeLimit.bytes))
        XCTAssertEqual(AirComposeLimit.counterText(for: String(repeating: "a", count: 1373), maxBytes: AirGroupComposeLimit.bytes),
                       "Goes as 2 messages")

        use("ru")
        XCTAssertEqual(AirComposeLimit.counterText(for: String(repeating: "ж", count: 668)), "Уйдёт 2 сообщениями")
        XCTAssertEqual(AirComposeLimit.counterText(for: String(repeating: "a", count: AirComposeLimit.bytes * 20 + 1)),
                       "Уйдёт 21 сообщением")
    }

    /// The field hands its text over as an NSString, whose length in UTF-8 is counted anew from its start
    /// each time: the split counted what was left of a long text once a part, half a second at every key
    /// for a megabyte (review 0.2.56). It says what it says of the text as Swift keeps it, at once.
    func testTheCounterOfMegabytesFromTheFieldIsCheapAndSaysTheSame() throws {
        let text = Array(repeating: "салам alaikum", count: 2 * 1_048_576 / 20).joined(separator: " ")
        let fromField = try XCTUnwrap(NSMutableString(string: text).copy() as? NSString) as String
        XCTAssertNil(fromField.utf8.withContiguousStorageIfAvailable { $0.count }, "not a text as a field hands it over")

        let start = CFAbsoluteTimeGetCurrent()
        let counter = AirComposeLimit.counterText(for: fromField)
        let elapsed = CFAbsoluteTimeGetCurrent() - start

        XCTAssertEqual(counter, AirComposeLimit.counterText(for: text))
        XCTAssertEqual(AirComposeLimit.parts(of: fromField), AirComposeLimit.parts(of: text))
        XCTAssertLessThan(elapsed, 0.5, "the counter of two megabytes from the field took \(elapsed) s")
    }

    // MARK: - Long texts

    /// Cyrillic takes two bytes a letter, and a text without a space is cut after the last whole letter
    /// that fits: no part is longer than a message, and none ends inside a letter.
    func testALongTextGoesInPartsOfWholeLetters() {
        let cyrillic = String(repeating: "ж", count: 1500)
        let parts = AirComposeLimit.parts(of: cyrillic)
        XCTAssertEqual(parts.map { $0.count }, [667, 667, 166])
        XCTAssertEqual(parts.joined(), cyrillic)

        let ascii = String(repeating: "a", count: 3000)
        XCTAssertEqual(AirComposeLimit.parts(of: ascii).map { $0.utf8.count }, [1334, 1334, 332])

        // A two-byte letter that would end a byte past the room goes whole into the next message.
        let edge = String(repeating: "a", count: 1333) + "ж"
        XCTAssertEqual(AirComposeLimit.parts(of: edge), [String(repeating: "a", count: 1333), "ж"])
    }

    func testAnEmojiIsNeverCutInTwo() {
        // A thumb with a skin tone: two scalars of four bytes, one character. 166 of them fit a message.
        let thumb = "\u{1F44D}\u{1F3FD}"
        let thumbs = String(repeating: thumb, count: 400)
        let thumbParts = AirComposeLimit.parts(of: thumbs)
        XCTAssertEqual(thumbParts.map { $0.utf8.count }, [1328, 1328, 544])
        XCTAssertTrue(thumbParts.allSatisfy { part in part.allSatisfy { String($0) == thumb } })
        XCTAssertEqual(thumbParts.joined(), thumbs)

        // A family: four people joined by three zero-width joiners, one character of 25 bytes.
        let family = "\u{1F468}\u{200D}\u{1F469}\u{200D}\u{1F467}\u{200D}\u{1F466}"
        XCTAssertEqual(family.count, 1)
        XCTAssertEqual(family.utf8.count, 25)
        let families = String(repeating: family, count: 120)
        let familyParts = AirComposeLimit.parts(of: families)
        XCTAssertEqual(familyParts.map { $0.utf8.count }, [1325, 1325, 350])
        XCTAssertTrue(familyParts.allSatisfy { part in part.allSatisfy { String($0) == family } })
        XCTAssertEqual(familyParts.joined(), families)
    }

    func testAPartEndsAtALineBreakOrASpaceInItsSecondHalf() {
        // Words: a part ends after the last whole word that fits.
        let words = Array(repeating: "salam", count: 400).joined(separator: " ")
        let wordParts = AirComposeLimit.parts(of: words)
        XCTAssertEqual(wordParts.map { $0.utf8.count }, [1331, 1067])
        XCTAssertEqual(wordParts.joined(separator: " "), words)

        // A line break in the second half wins over a later space.
        let lines = String(repeating: "a", count: 900) + "\n" + String(repeating: "b ", count: 400)
        let lineParts = AirComposeLimit.parts(of: lines)
        XCTAssertEqual(lineParts.count, 2)
        XCTAssertEqual(lineParts.first, String(repeating: "a", count: 900))

        // One in the first half would leave a short part: the part takes all of its room instead.
        let early = String(repeating: "a", count: 100) + "\n" + String(repeating: "c", count: 1500)
        XCTAssertEqual(AirComposeLimit.parts(of: early).map { $0.utf8.count }, [1334, 267])
    }

    func testATextOfExactlyOneMessageGoesAsOne() {
        let full = String(repeating: "a", count: AirComposeLimit.bytes)
        XCTAssertEqual(AirComposeLimit.parts(of: full), [full])
        XCTAssertEqual(AirComposeLimit.parts(of: full + "b"), [full, "b"])

        let group = String(repeating: "a", count: AirGroupComposeLimit.bytes)
        XCTAssertEqual(AirComposeLimit.parts(of: group, maxBytes: AirGroupComposeLimit.bytes), [group])
        XCTAssertEqual(AirComposeLimit.parts(of: group + "b", maxBytes: AirGroupComposeLimit.bytes), [group, "b"])
        XCTAssertEqual(AirComposeLimit.parts(of: group).count, 2, "a private message has less room than a group one")
    }

    /// The header of a reply rides whole on the first part, so the first part still reads as the reply
    /// and the rest of the text follows as plain messages.
    func testTheHeaderOfAReplyRidesOnTheFirstPart() {
        var meta = MessageReplyHelper.ReplyMeta()
        meta.localMessageId = 42
        meta.senderPubkeyTail = "ab12"
        meta.sortTimestampMs = 1_800_000_000_000
        meta.senderName = "Amina"
        meta.previewText = "See you at the station"
        let body = String(repeating: "ж", count: 1000)
        let parts = AirComposeLimit.parts(ofComposed: MessageReplyHelper.encode(meta, bodyText: body), body: body)

        XCTAssertEqual(parts.count, 2)
        XCTAssertTrue(parts.allSatisfy { $0.utf8.count <= AirComposeLimit.bytes })
        let first = MessageReplyHelper.parse(parts[0])
        XCTAssertEqual(first.reply?.localMessageId, 42)
        XCTAssertEqual(first.reply?.previewText, "See you at the station")
        XCTAssertEqual(first.bodyText + parts[1], body)
        XCTAssertNil(MessageReplyHelper.parse(parts[1]).reply, "only the first part is the reply")

        // Not one character fits beside a header this long: the header goes alone, the text after it.
        let head = String(repeating: "h", count: 1329) + "\n"
        let thumbs = String(repeating: "\u{1F44D}\u{1F3FD}", count: 2)
        XCTAssertEqual(AirComposeLimit.parts(of: thumbs, head: head), [head, thumbs])
    }

    /// Only a character longer than a whole message (a letter under a thousand marks) is cut, between two
    /// of its scalars: no part is ever longer than a message.
    func testOnlyACharacterLongerThanAMessageIsCut() {
        let monster = "a" + String(repeating: "\u{0301}", count: 1000)
        XCTAssertEqual(monster.count, 1)
        let parts = AirComposeLimit.parts(of: monster)
        XCTAssertEqual(parts.map { $0.utf8.count }, [1333, 668])
        XCTAssertEqual(parts.joined(), monster)
    }

    func testTrayHeadCountsTheMessages() {
        XCTAssertEqual(String(localized: "air_tray_head_format", 1), "In transit: 1 message")
        XCTAssertEqual(String(localized: "air_tray_head_format", 3), "In transit: 3 messages")
        use("ru")
        XCTAssertEqual(String(localized: "air_tray_head_format", 3), "В пути: 3 сообщения")
        XCTAssertEqual(String(localized: "air_tray_head_format", 5), "В пути: 5 сообщений")
        XCTAssertEqual(String(localized: "air_transit_format", 3), "3 в пути")
    }

    // MARK: - Replies to your own message

    /// A reply to your own message (QA-Live 15.11): its header named you "You", and every client shows the
    /// name a header carries, so the friend read the quote as their own words. The header names you as the
    /// friend knows you: your name and your key. Only the bar above your field still says "You".
    func testAReplyToYourOwnMessageNamesYouAsYourFriendKnowsYou() throws {
        let key = "7f3a91c05d2e8b4416a9c3e07d5f2b8164e0a3c9d7b25f1e8a4c6d0b3f9e2a71"
        let meta = try XCTUnwrap(ChatReplyController.replyMeta(for: try ownMessage("Bring the blue folder"),
                                                               own: ChatReplyController.OwnAuthor(name: " Lucy QA ", publicKey: key),
                                                               friend: { _ in nil }))

        let read = try XCTUnwrap(MessageReplyHelper.parse(MessageReplyHelper.encode(meta, bodyText: "Done")).reply,
                                 "the friend reads no reply")
        XCTAssertEqual(read.senderName, "Lucy QA", "the friend is not told who wrote the quoted message")
        XCTAssertEqual(read.senderPubkeyTail, "3F9E2A71", "the friend is not told your key")
        XCTAssertEqual(read.previewText, "Bring the blue folder")
        XCTAssertEqual(meta.localSenderName, "You", "the bar above the field does not say You")

        use("ru")
        let russian = try XCTUnwrap(ChatReplyController.replyMeta(for: try ownMessage("Bring the blue folder"),
                                                                  own: ChatReplyController.OwnAuthor(name: "Lucy QA", publicKey: key),
                                                                  friend: { _ in nil }))
        let readInRussian = try XCTUnwrap(MessageReplyHelper.parse(MessageReplyHelper.encode(russian, bodyText: "Done")).reply)
        XCTAssertEqual(readInRussian.senderName, "Lucy QA", "the header names you by a word of your own language")
        XCTAssertEqual(russian.localSenderName, String(localized: "chat_reply_self_name"))
    }

    /// In a group the header names you by the name you have there, with no key: iOS names nobody in a group
    /// reply by key, so a receiver matches the quote by its time, as for every member.
    func testAReplyToYourOwnMessageInAGroupCarriesTheNameYouHaveThere() throws {
        let meta = try XCTUnwrap(ChatReplyController.replyMeta(for: try ownMessage("Who brings the projector?"),
                                                               own: ChatReplyController.OwnAuthor(name: "Lucy at fix-ios-1", publicKey: nil),
                                                               friend: { _ in nil }))

        let read = try XCTUnwrap(MessageReplyHelper.parse(MessageReplyHelper.encode(meta, bodyText: "I do")).reply)
        XCTAssertEqual(read.senderName, "Lucy at fix-ios-1")
        XCTAssertEqual(read.senderPubkeyTail, "")
    }

    /// A reply chosen and left unsent is kept per chat. One kept by the build before, without the bar's
    /// name, still comes back.
    func testAReplyKeptByTheBuildBeforeStillComesBack() throws {
        let kept = #"{"localMessageId":42,"senderPubkeyTail":"AB12","sortTimestampMs":1800000000000,"senderName":"Amina","previewText":"See you","legacyQuote":false}"#
        let meta = try JSONDecoder().decode(MessageReplyHelper.ReplyMeta.self, from: Data(kept.utf8))
        XCTAssertEqual(meta.senderName, "Amina")
        XCTAssertNil(meta.localSenderName)
    }

    // MARK: - Key

    func testKeyIsReadInGroupsOfFour() {
        let key = "7f3a91c05d2e8b4416a9c3e07d5f2b8164e0a3c9d7b25f1e8a4c6d0b3f9e2a71"
        let groups = AirKeyFormat.groups(key)
        XCTAssertEqual(groups.count, 16)
        XCTAssertEqual(groups.first, "7F3A")
        XCTAssertEqual(groups.last, "2A71")
        let lines = AirKeyFormat.lines(key).components(separatedBy: "\n")
        XCTAssertEqual(lines.count, 4)
        XCTAssertEqual(lines[0], "7F3A 91C0 5D2E 8B44")
        XCTAssertEqual(AirKeyFormat.groups(""), [])
    }

    // MARK: - Day strip and best hours

    func testDayStripCoversTheLastTwentyFourHours() {
        let now = date(2026, 11, 11, 10, 30)
        let hours: Set<Int> = [
            hourStart(2026, 11, 11, 9), hourStart(2026, 11, 11, 10),
            // Yesterday 22:00 is still within the last day; yesterday 9:00 is not (today's 9:00 took its cell).
            hourStart(2026, 11, 10, 22), hourStart(2026, 11, 10, 8),
        ]
        let strip = AirDayStrip.make(hours: hours, now: now, calendar: calendar)
        XCTAssertEqual(strip.nowHour, 10)
        XCTAssertEqual(strip.online.count, 24)
        XCTAssertTrue(strip.online[9])
        XCTAssertTrue(strip.online[10])
        XCTAssertTrue(strip.online[22])
        XCTAssertFalse(strip.online[8])
        XCTAssertTrue(strip.hasMarks)
        XCTAssertEqual(strip.ranges.map { [$0.from, $0.to] }, [[9, 11], [22, 23]])

        let empty = AirDayStrip.make(hours: [], now: now, calendar: calendar)
        XCTAssertFalse(empty.hasMarks)
        XCTAssertEqual(empty.spokenText(locale: Locale(identifier: "en_US"), nowOnline: false), "no marks")
        XCTAssertEqual(strip.spokenText(locale: Locale(identifier: "en_US"), nowOnline: true),
                       "online 9:00 to 11:00 and 22:00 to 23:00. Online now")
    }

    func testBestHoursNeedTwoDaysAndPickTheLongestRunSeenMostOften() {
        let now = date(2026, 11, 11, 12, 0)
        XCTAssertNil(AirBestHours.find(hours: [hourStart(2026, 11, 11, 9)], now: now, calendar: calendar))

        var hours = Set<Int>()
        for day in 6...10 {
            for hour in 19...21 {
                hours.insert(hourStart(2026, 11, day, hour))
            }
        }
        hours.insert(hourStart(2026, 11, 10, 7))
        let best = AirBestHours.find(hours: hours, now: now, calendar: calendar)
        XCTAssertEqual(best?.from, 19)
        XCTAssertEqual(best?.to, 22)

        // Marks older than a week do not count.
        let old: Set<Int> = [hourStart(2026, 10, 1, 19), hourStart(2026, 10, 2, 19)]
        XCTAssertNil(AirBestHours.find(hours: old, now: now, calendar: calendar))

        // Online around the clock: no "best time".
        var allDay = Set<Int>()
        for day in 9...10 {
            for hour in 0..<24 {
                allDay.insert(hourStart(2026, 11, day, hour))
            }
        }
        XCTAssertNil(AirBestHours.find(hours: allDay, now: now, calendar: calendar))
    }

    // MARK: - Stores outside Realm

    func testLocalStoreFilesContactsUnderAHashAndKeepsMarksAcrossLaunches() {
        let key = "7F3A91C05D2E8B4416A9C3E07D5F2B8164E0A3C9D7B25F1E8A4C6D0B3F9E2A71"
        let hashed = AirLocalStore.hashedKey(key)
        XCTAssertEqual(hashed.count, 32)
        XCTAssertEqual(hashed, AirLocalStore.hashedKey(key.lowercased()))
        XCTAssertFalse(hashed.uppercased().contains(String(key.prefix(16))))

        let directory = makeTemporaryDirectory()
        let store = AirLocalStore()
        store.attach(directory: directory)
        let now = Date()
        store.markOnline(publicKey: key, at: now)
        store.setMuted(true, publicKey: key)
        store.setVerified(now, publicKey: key)
        store.noteConnected(publicKey: key, at: now)
        XCTAssertEqual(store.hours(publicKey: key).count, 1)
        XCTAssertNotNil(store.onlineSince(publicKey: key))

        let file = directory.appendingPathComponent(AirLocalStore.fileName)
        let written = expectation(description: "the marks reach the file")
        pollUntil({ (try? Data(contentsOf: file)).map { String(decoding: $0, as: UTF8.self).contains("verifiedAt") } ?? false }) {
            written.fulfill()
        }
        wait(for: [written], timeout: 5)
        let json = String(decoding: (try? Data(contentsOf: file)) ?? Data(), as: UTF8.self)
        XCTAssertFalse(json.contains(key), "the file never names a contact")

        let again = AirLocalStore()
        again.attach(directory: directory)
        XCTAssertEqual(again.hours(publicKey: key).count, 1)
        XCTAssertTrue(again.isMuted(publicKey: key))
        XCTAssertNotNil(again.verifiedAt(publicKey: key))
        // When someone came online is memory only.
        XCTAssertNil(again.onlineSince(publicKey: key))

        again.forget(publicKey: key)
        XCTAssertTrue(again.hours(publicKey: key).isEmpty)
        XCTAssertFalse(again.isMuted(publicKey: key))
    }

    func testRefusedMessagesStayPerChatUntilSentOrDropped() {
        let sends = AirFailedSends()
        let changed = expectation(forNotification: .airFailedSendsDidChange, object: sends) { note in
            (note.userInfo?["chatId"] as? String) == "chat-a"
        }
        changed.assertForOverFulfill = false
        let first = sends.add(text: "one", toChat: "chat-a")
        wait(for: [changed], timeout: 1)
        sends.add(text: "two", toChat: "chat-a")
        sends.add(text: "elsewhere", toChat: "chat-b")
        XCTAssertEqual(sends.items(forChat: "chat-a").map { $0.text }, ["one", "two"])

        sends.remove(id: first.id, fromChat: "chat-a")
        XCTAssertEqual(sends.items(forChat: "chat-a").map { $0.text }, ["two"])
        sends.removeAll(fromChat: "chat-a")
        XCTAssertTrue(sends.items(forChat: "chat-a").isEmpty)
        XCTAssertEqual(sends.items(forChat: "chat-b").count, 1)
    }

    func testEveryConversationStringHasItsPluralsInRussianAndArabic() {
        for key in ["air_tray_head_format", "air_presence_away_minutes", "air_presence_away_hours", "air_presence_away_days"] {
            for language in ["en", "ru", "ar"] {
                use(language)
                let format = NSLocalizedString(key, comment: "")
                XCTAssertTrue(format.contains("#@"), "\(language) \(key) has no plural rule")
            }
        }
    }
}

// MARK: - Fixtures

private extension AirConversationModelTests {
    func use(_ language: String) {
        AppLanguage.set(language)
        XCTAssertEqual(AppLanguage.current, language)
    }

    /// The colour a status mark draws its icon in.
    func tint(of mark: AirStatusMarkView) -> UIColor? {
        return mark.subviews.compactMap { $0 as? UIImageView }.first?.tintColor
    }

    /// The 0xRRGGBB of a colour in a theme.
    func rgb(_ color: UIColor, _ style: UIUserInterfaceStyle) -> UInt32 {
        var red: CGFloat = 0
        var green: CGFloat = 0
        var blue: CGFloat = 0
        var alpha: CGFloat = 0
        XCTAssertTrue(color.resolvedColor(with: UITraitCollection(userInterfaceStyle: style)).getRed(&red, green: &green, blue: &blue, alpha: &alpha))
        return UInt32((red * 255).rounded()) << 16 | UInt32((green * 255).rounded()) << 8 | UInt32((blue * 255).rounded())
    }

    /// A text of yours as objcTox keeps it: no sender. Made from the app's classes, looked up by name:
    /// objcTox is linked into this bundle as well, and the two copies do not mix (OwnCaptionOrderTests).
    func ownMessage(_ text: String) throws -> OCTMessageAbstract {
        let messageText = try appClass("OCTMessageText", as: OCTMessageText.Type.self).init()
        messageText.text = text
        let message = try appClass("OCTMessageAbstract", as: OCTMessageAbstract.Type.self).init()
        message.dateInterval = 1_800_000_000.25
        message.sortTimestamp = message.dateInterval
        message.messageText = messageText
        XCTAssertTrue(message.isOutgoing())
        return message
    }

    func appClass<T>(_ name: String, as type: T.Type) throws -> T {
        let cls = try XCTUnwrap(NSClassFromString(name), "no class \(name)")
        return unsafeBitCast(cls, to: type)
    }

    func date(_ year: Int, _ month: Int, _ day: Int, _ hour: Int, _ minute: Int) -> Date {
        return calendar.date(from: DateComponents(year: year, month: month, day: day, hour: hour, minute: minute))!
    }

    func hourStart(_ year: Int, _ month: Int, _ day: Int, _ hour: Int) -> Int {
        return Int(date(year, month, day, hour, 0).timeIntervalSince1970)
    }

    func makeTemporaryDirectory() -> URL {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("air-store-\(UUID().uuidString)", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        temporaryDirectory = directory
        return directory
    }

    func pollUntil(_ condition: @escaping () -> Bool, then done: @escaping () -> Void) {
        if condition() {
            done()
            return
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) { [weak self] in
            self?.pollUntil(condition, then: done)
        }
    }
}

/**
 KHANDAQ (ideas 1): the "Unread messages" band and the jump to it when a conversation opens (AirUnreadBand),
 kept by the conversation with one person and now by a group too.
 */
final class UnreadBandTests: XCTestCase, GroupScreenTesting {
    fileprivate let lastRead: TimeInterval = 1_800_000_000
    fileprivate var directories: [URL] = []
    /// The group screen and its window, let go before what the screen holds weakly.
    fileprivate var window: UIWindow?
    fileprivate var screen: ChatGroupController?
    /// What the screen holds weakly: the profile, the submanagers and its delegate.
    fileprivate var kept: [AnyObject] = []

    override func tearDown() {
        GlobalSearchScrollTarget.pending = nil
        closeGroupScreen()
        super.tearDown()
    }

    // MARK: - Where the band goes

    func testTheBandGoesOnTheOldestNewMessageOfSomeoneElse() {
        // Newest first: yours, theirs, a notice, theirs (the band), then two read before.
        XCTAssertEqual(bandIndex([(lastRead + 40, false), (lastRead + 30, true), (lastRead + 20, false),
                                  (lastRead + 10, true), (lastRead - 60, true), (lastRead - 120, true)]), 3)
    }

    func testNothingNewFromSomeoneElseHasNoBand() {
        XCTAssertNil(bandIndex([]), "no messages")
        XCTAssertNil(bandIndex([(lastRead - 10, true), (lastRead - 20, true)]), "all read")
        XCTAssertNil(bandIndex([(lastRead + 10, false), (lastRead + 5, false), (lastRead - 20, true)]), "only yours is new")
    }

    /// Under the first message read already nothing is new, not even a row sorted there with a later date.
    func testTheBandNeverGoesPastAMessageReadAlready() {
        XCTAssertEqual(bandIndex([(lastRead + 10, true), (lastRead - 10, true), (lastRead + 20, true)]), 0)
    }

    /// A new chat stamps its read date as it is made, a moment after the message that made it.
    func testTheMessageThatMadeTheChatIsNew() {
        XCTAssertEqual(bandIndex([(lastRead - 1, true)]), 0)
        XCTAssertNil(bandIndex([(lastRead - AirUnreadBand.creationLead - 1, true)]))
    }

    func testTheJumpIsTakenOnceAndTheBandStays() {
        let start = lastRead + 10
        var band = AirUnreadBand(count: 3,
                                 lastRead: lastRead,
                                 arrival: { start - TimeInterval($0) },
                                 countsAsUnread: { _ in true },
                                 id: { "m\($0)" })
        XCTAssertEqual(band.messageId, "m2")
        XCTAssertEqual(band.takeJump(), 2)
        XCTAssertNil(band.takeJump(), "the jump came back")
        XCTAssertTrue(band.marks("m2"))
        XCTAssertFalse(band.marks("m1"))
        XCTAssertFalse(band.marks(nil))

        var none = AirUnreadBand.none
        XCTAssertFalse(none.marks(nil))
        XCTAssertNil(none.takeJump())
    }

    /// In a group the band follows the unread count: a notice and a synced row nobody can be named for are
    /// not unread, so it is never on them.
    func testInAGroupTheBandSkipsNoticesAndUnnamedSyncedRows() throws {
        let (chat, realmManager) = try group()
        try store("On my way", at: lastRead + 50, in: chat, realmManager)
        let theirs = try store("See you at six", at: lastRead + 40, in: chat, realmManager, peer: 7)
        try store("Amina joined", at: lastRead + 30, in: chat, realmManager, notice: true)
        try store("An old line", at: lastRead + 20, in: chat, realmManager, synced: true)
        try store("Read before", at: lastRead - 60, in: chat, realmManager, peer: 7)
        let objects = OCTSubmanagerObjectsMock(realm: realmManager.realm)
        kept.append(objects)
        // Newest first, as the group's log lists them (the dates differ, so sortTimestamp alone orders them).
        let messages = objects.messages(predicate: NSPredicate(format: "chatUniqueIdentifier == %@", chat.uniqueIdentifier))
            .sortedResultsUsingProperty("sortTimestamp", ascending: false)

        let band = AirUnreadBand(messages: messages, lastRead: lastRead)

        XCTAssertEqual(band.messageId, theirs.uniqueIdentifier)
        XCTAssertEqual(band.jumpIndex, 1)
    }

    // MARK: - The group screen

    /// A group opens at its first unread message, under the band: past the 50 rows the log loads first, and
    /// with your own message the newest, which the first notification of the list used to scroll to.
    func testAGroupOpensAtItsFirstUnreadMessageUnderTheBand() throws {
        let (chat, realmManager) = try group()
        try inOneWrite(realmManager) {
            for minute in 0..<5 {
                try store("Read \(minute)", at: lastRead - 600 + TimeInterval(minute * 60), in: chat, realmManager, peer: 7)
            }
            for minute in 0..<54 {
                try store("New \(minute)", at: lastRead + 60 + TimeInterval(minute * 60), in: chat, realmManager, peer: 7)
            }
            try store("Coming", at: lastRead + 3_600, in: chat, realmManager)
        }
        // Newest first: yours at 0, the new ones at 1 to 54, the ones read before at 55 to 59.
        let bandRow = IndexPath(row: 54, section: 0)

        let screen = try open(chat, realmManager)
        // As while the screen slides in: the layout settles and the list is first heard from.
        settle(for: 0.6)
        screen.viewDidAppear(false)
        settle(for: 1.0)

        let table = try XCTUnwrap(screen.tableView)
        XCTAssertEqual(table.numberOfRows(inSection: 0), 60, "the log did not load past its first 50 rows")
        XCTAssertTrue(table.indexPathsForVisibleRows?.contains(bandRow) == true,
                      "the log does not stand at the first unread message")
        let unread = String(localized: "unread_messages_separator")
        let banded = table.indexPathsForVisibleRows?.filter { indexPath in
            (table.cellForRow(at: indexPath) as? ChatMovableDateCell)?.transcriptMarksSpoken?.contains(unread) == true
        }
        XCTAssertEqual(banded, [bandRow], "the band is not over the first unread message alone")
    }

    /// A jump to a search hit goes first, and the log does not jump on to the band after it.
    func testASearchHitWinsOverTheFirstUnreadMessage() throws {
        let (chat, realmManager) = try group()
        var stored: [OCTMessageAbstract] = []
        try inOneWrite(realmManager) {
            for minute in 0..<40 {
                stored.append(try store("New \(minute)", at: lastRead + 60 + TimeInterval(minute * 60), in: chat, realmManager, peer: 7))
            }
        }
        // Newest first: the hit at row 20, the band at row 39.
        GlobalSearchScrollTarget.pending = (chatId: chat.uniqueIdentifier, messageId: stored[19].uniqueIdentifier)

        let screen = try open(chat, realmManager)
        settle(for: 0.6)
        screen.viewDidAppear(false)
        settle(for: 1.0)

        let table = try XCTUnwrap(screen.tableView)
        XCTAssertTrue(table.indexPathsForVisibleRows?.contains(IndexPath(row: 20, section: 0)) == true,
                      "the search hit is not on screen")
        XCTAssertFalse(table.indexPathsForVisibleRows?.contains(IndexPath(row: 39, section: 0)) == true,
                       "the log went on to the first unread message")
    }
}

// MARK: - Fixtures

private extension UnreadBandTests {
    /// The band's index over messages given newest first as (the date they came, whether they count).
    func bandIndex(_ rows: [(TimeInterval, Bool)]) -> Int? {
        return AirUnreadBand.index(count: rows.count,
                                   lastRead: lastRead,
                                   arrival: { rows[$0].0 },
                                   countsAsUnread: { rows[$0].1 })
    }
}

/// What a test of the group screen keeps, for the fixtures below (the unread band, and Edit in the menu of
/// a group): the profiles it made, the screen and its window, and what the screen holds weakly.
private protocol GroupScreenTesting: AnyObject {
    var lastRead: TimeInterval { get }
    var directories: [URL] { get set }
    var window: UIWindow? { get set }
    var screen: ChatGroupController? { get set }
    var kept: [AnyObject] { get set }
}

extension GroupScreenTesting {
    /// A group read up to `lastRead`, in a profile of the test's own, made from the app's classes: this
    /// bundle has copies of its own, which do not mix with them (OwnCaptionOrderTests).
    func group() throws -> (OCTChat, OCTRealmManager) {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("unread-band-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true, attributes: nil)
        directories.append(directory)
        let realmManagerClass = try appClass("OCTRealmManager", as: OCTRealmManager.Type.self)
        let realmManager = try XCTUnwrap(realmManagerClass.init(databaseFileURL: directory.appendingPathComponent("profile.realm"),
                                                                encryptionKey: nil))
        let chat = try appClass("OCTChat", as: OCTChat.Type.self).init()
        chat.isGroup = true
        chat.groupNumber = 12
        chat.groupName = "Book club"
        chat.groupPeerCount = 2
        chat.lastReadDateInterval = lastRead
        realmManager.add(chat)
        kept.append(realmManager)
        return (chat, realmManager)
    }

    /// A text of the group as objcTox stores it: yours with no peer, someone else's with `peer`, a notice
    /// of the group, or a row a member synced whose author was not known; `messageId` is the group's ID of
    /// a text that went out (0 while it waits).
    @discardableResult
    func store(_ text: String,
               at date: TimeInterval,
               in chat: OCTChat,
               _ realmManager: OCTRealmManager,
               peer: Int32 = 0,
               notice: Bool = false,
               synced: Bool = false,
               messageId: OCTToxMessageId = 0) throws -> OCTMessageAbstract {
        let messageText = try appClass("OCTMessageText", as: OCTMessageText.Type.self).init()
        messageText.text = text
        messageText.isDelivered = true
        messageText.messageId = messageId
        let message = try appClass("OCTMessageAbstract", as: OCTMessageAbstract.Type.self).init()
        message.chatUniqueIdentifier = chat.uniqueIdentifier
        message.dateInterval = date
        message.sortTimestamp = date
        message.groupSenderPeerId = peer
        message.groupSystemMessage = notice
        message.groupHistorySync = synced
        message.messageText = messageText
        let realm: RLMRealm = realmManager.realm
        if realm.inWriteTransaction {
            realm.add(message)
        }
        else {
            realmManager.add(message)
        }
        return message
    }

    /// Runs `body` in one write of the profile: a write of its own for each of 60 messages takes seconds.
    func inOneWrite(_ realmManager: OCTRealmManager, _ body: () throws -> Void) throws {
        let realm: RLMRealm = realmManager.realm
        realm.beginWriteTransaction()
        do {
            try body()
        }
        catch {
            realm.cancelWriteTransaction()
            throw error
        }
        try realm.commitWriteTransaction()
    }

    /// The group's screen in a navigation stack, as the app pushes it, in a window of its own, loaded and
    /// laid out. UIKit says viewDidAppear once such a window is up, but not always in a whole run, after the
    /// windows other tests leave behind, so the tests say it themselves.
    func open(_ chat: OCTChat, _ realmManager: OCTRealmManager) throws -> ChatGroupController {
        let realm: RLMRealm = realmManager.realm
        let groups = OCTSubmanagerGroupsMock()
        groups.realm = realm
        let objects = OCTSubmanagerObjectsMock(realm: realm)
        let friends = OCTSubmanagerFriendsMock()
        let chats = OCTSubmanagerChatsMock(realm: realm)
        let user = OCTSubmanagerUserMock()
        let delegate = QuietGroupScreenDelegate()
        kept += [groups, objects, friends, chats, user, delegate]
        let screen = ChatGroupController(theme: ThemeAppearance.resolvedTheme(),
                                         chat: chat,
                                         submanagerGroups: groups,
                                         submanagerObjects: objects,
                                         submanagerFriends: friends,
                                         submanagerChats: chats,
                                         submanagerUser: user,
                                         delegate: delegate)
        // In the app's scene: a window of no scene never shows, and its screens never appear.
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first,
                                  "the app has no scene")
        let window = UIWindow(windowScene: scene)
        window.frame = CGRect(x: 0, y: 0, width: 390, height: 844)
        window.rootViewController = UINavigationController(rootViewController: screen)
        window.isHidden = false
        window.layoutIfNeeded()
        self.window = window
        self.screen = screen
        return screen
    }

    func appClass<T>(_ name: String, as type: T.Type) throws -> T {
        let cls: AnyClass = try XCTUnwrap(NSClassFromString(name), "no class \(name)")
        return unsafeBitCast(cls, to: type)
    }

    /// Lets everything go: the screen and its window before what the screen holds weakly, then the profiles.
    func closeGroupScreen() {
        // The screen leaves its window first, as it does in the app when it is closed: its field stands on the
        // keyboard layout guide (beta 8251), and UIKit keeps a window dropped with such a view still in it, and
        // the screen with it. Its 2-second video poll then ran after the profile had gone.
        window?.rootViewController = nil
        window?.isHidden = true
        window = nil
        screen = nil
        kept = []
        directories.forEach { try? FileManager.default.removeItem(at: $0) }
        directories = []
    }
}

extension GroupScreenTesting where Self: XCTestCase {
    /// Lets the main loop run for `seconds`: the screen appears, jumps, and hears from its list.
    func settle(for seconds: TimeInterval) {
        let settled = expectation(description: "the main loop ran")
        DispatchQueue.main.asyncAfter(deadline: .now() + seconds) {
            settled.fulfill()
        }
        wait(for: [settled], timeout: seconds + 5)
    }
}

/**
 KHANDAQ (ideas 1): Edit in the long-press menu of a group, on the terms of a chat with one person: a text
 of your own that went out with the group's message ID, not one still waiting, someone else's, a reply or
 a location; it opens the editor with the text of the message the menu was opened on.
 */
final class GroupEditMenuTests: XCTestCase, GroupScreenTesting {
    fileprivate let lastRead: TimeInterval = 1_800_000_000
    fileprivate var directories: [URL] = []
    fileprivate var window: UIWindow?
    fileprivate var screen: ChatGroupController?
    fileprivate var kept: [AnyObject] = []

    override func tearDown() {
        closeGroupScreen()
        super.tearDown()
    }

    func testEditIsInTheMenuOfYourOwnSentTextAlone() throws {
        let (chat, realmManager) = try group()
        var quoted = MessageReplyHelper.ReplyMeta()
        quoted.localMessageId = 3
        quoted.senderName = "Amina"
        quoted.previewText = "Where?"
        let location = LocationMessage.payload(latitude: 41.0082, longitude: 28.9784)
        try inOneWrite(realmManager) {
            try store("See you at six", at: lastRead - 10, in: chat, realmManager, messageId: 41)
            try store("On my way", at: lastRead - 20, in: chat, realmManager)
            try store("Fine by me", at: lastRead - 30, in: chat, realmManager, peer: 7, messageId: 42)
            try store(MessageReplyHelper.encode(quoted, bodyText: "At the library"), at: lastRead - 40, in: chat, realmManager, messageId: 43)
            try store(location, at: lastRead - 50, in: chat, realmManager, messageId: 44)
        }
        let screen = try open(chat, realmManager)
        settle(for: 0.6)

        let edit = String(localized: "chat_edit_action")
        var offered: [String: Bool] = [:]
        for (text, actions) in try menus(of: screen) {
            offered[text] = actions.contains { $0.title == edit }
        }
        XCTAssertEqual(offered.count, 5, "not every message has its menu: \(offered)")
        XCTAssertEqual(offered["See you at six"], true, "no Edit on your own text")
        XCTAssertEqual(offered["On my way"], false, "Edit on a text still waiting for its ID")
        XCTAssertEqual(offered["Fine by me"], false, "Edit on someone else's text")
        XCTAssertEqual(offered["At the library"], false, "Edit on a reply")
        XCTAssertEqual(offered[location], false, "Edit on a location")
    }

    func testEditOpensTheEditorWithTheText() throws {
        let (chat, realmManager) = try group()
        try store("See you at six", at: lastRead - 10, in: chat, realmManager, messageId: 41)
        let screen = try open(chat, realmManager)
        settle(for: 0.6)

        let edit = try XCTUnwrap(try menus(of: screen).first?.actions.first { $0.title == String(localized: "chat_edit_action") })
        edit.handler()

        let editor = try XCTUnwrap(screen.navigationController?.presentedViewController as? UIAlertController, "Edit opened nothing")
        XCTAssertEqual(editor.title, String(localized: "chat_edit_action"))
        XCTAssertEqual(editor.textFields?.first?.text, "See you at six")
        XCTAssertEqual(editor.actions.map { $0.title ?? "" }, [String(localized: "alert_cancel"), String(localized: "message_edit_save")])

        // The editor comes up and goes within the test, while the screen still has what it holds weakly: a
        // dismissal cut into the presentation was dropped, and the screen, left behind, read its released
        // submanagers in the next test.
        settle(for: 0.5)
        let gone = expectation(description: "the editor went away")
        editor.dismiss(animated: false) {
            gone.fulfill()
        }
        wait(for: [gone], timeout: 5)
    }

    /// A message gone while its menu was open opens no editor, and nothing reads the gone message.
    func testAMessageGoneWhileTheMenuWasOpenIsLeftAlone() throws {
        let (chat, realmManager) = try group()
        let mine = try store("See you at six", at: lastRead - 10, in: chat, realmManager, messageId: 41)
        let screen = try open(chat, realmManager)
        settle(for: 0.6)
        let edit = try XCTUnwrap(try menus(of: screen).first?.actions.first { $0.title == String(localized: "chat_edit_action") })

        let realm: RLMRealm = realmManager.realm
        realm.beginWriteTransaction()
        realm.delete(mine)
        try realm.commitWriteTransaction()
        XCTAssertTrue(mine.isInvalidated)
        edit.handler()

        XCTAssertNil(screen.navigationController?.presentedViewController, "an editor opened for a message that is gone")
    }

    /// The menu of each message on the screen, by its words.
    private func menus(of screen: ChatGroupController) throws -> [(text: String, actions: [ChatContextMenuAction])] {
        let table = try XCTUnwrap(screen.tableView)
        return try (0..<table.numberOfRows(inSection: 0)).map { row in
            let indexPath = IndexPath(row: row, section: 0)
            let cell = try XCTUnwrap(screen.tableView(table, cellForRowAt: indexPath) as? ChatMovableDateCell, "row \(row) is no message")
            let message = screen.messageEntry(atDisplayIndex: row).message
            let text = MessageReplyHelper.plainBody(for: message) ?? ""
            return (text, screen.groupContextActions(for: cell, message: message, indexPath: indexPath))
        }
    }
}

/// The group screen's delegate in a test: nothing to open.
private final class QuietGroupScreenDelegate: ChatGroupControllerDelegate {
    func chatGroupControllerWillAppear(_ controller: ChatGroupController) {}
    func chatGroupControllerWillDisappear(_ controller: ChatGroupController) {}
    func chatGroupControllerDidRequestGroupInfo(_ controller: ChatGroupController) {}
    func chatGroupController(_ controller: ChatGroupController, didRequestOpenFriend friend: OCTFriend) {}
}
