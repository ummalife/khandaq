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
