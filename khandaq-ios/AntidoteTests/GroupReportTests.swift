// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import XCTest
@testable import Antidote

/**
 Report on a message of a group (SPEC-community, item 5): the mail to support names the group, the first 8
 characters of the author's key, the time in UTC and the text up to 500 characters, as desktop and Android
 write it. The mailto: link percent-encodes every part and ends the lines with CRLF.
 */
class GroupReportTests: XCTestCase {
    private let time = Date(timeIntervalSince1970: 1_794_832_496)
    private let key = "7f3a91c05d2e8b4416a9c3e07d5f2b8164e0a3c9d7b25f1e8a4c6d0b3f9e2a71"

    func testTheMailNamesTheGroupTheAuthorTheTimeInUTCAndTheText() {
        let report = GroupReport(groupName: "Khandaq Community", authorKey: key, time: time, text: "Peace be upon you")

        XCTAssertEqual(report.subject, "Report: Khandaq Community")
        XCTAssertEqual(report.body, "Group: Khandaq Community\nAuthor: 7F3A91C0\nTime: 2026-11-16T12:34:56Z\nMessage:\nPeace be upon you")
        XCTAssertEqual(GroupReport.address, "support@khandaq.org")
    }

    func testTheTimeIsUTCWhateverTheZoneOfThePhone() {
        let saved = NSTimeZone.default
        defer {
            NSTimeZone.default = saved
        }
        NSTimeZone.default = TimeZone(identifier: "Asia/Tokyo")!
        let report = GroupReport(groupName: "G", authorKey: key, time: time, text: "x")
        XCTAssertTrue(report.body.contains("\nTime: 2026-11-16T12:34:56Z\n"), report.body)
    }

    func testTheLinkEncodesEveryPartAndEndsTheLinesWithCRLF() throws {
        let report = GroupReport(groupName: "A & B = #1 + ?", authorKey: key, time: time, text: "one\ntwo & three")
        let url = try XCTUnwrap(report.mailtoURL)
        let string = url.absoluteString

        XCTAssertTrue(string.hasPrefix("mailto:support@khandaq.org?subject=Report%3A%20A%20%26%20B%20%3D%20%231%20%2B%20%3F&body="), string)
        XCTAssertFalse(string.contains(" "))
        XCTAssertEqual(string.components(separatedBy: "&").count, 2, "the only & is the one between the fields")
        XCTAssertEqual(string.components(separatedBy: "?").count, 2, "the only ? is the one before the fields")
        XCTAssertFalse(string.contains("#"))
        XCTAssertTrue(string.contains("%0D%0A"))
        XCTAssertFalse(string.replacingOccurrences(of: "%0D%0A", with: "").contains("%0A"), "every line ends with CRLF")

        let components = try XCTUnwrap(URLComponents(url: url, resolvingAgainstBaseURL: false))
        XCTAssertEqual(components.scheme, "mailto")
        XCTAssertEqual(components.path, "support@khandaq.org")
        let items = Dictionary(uniqueKeysWithValues: (components.queryItems ?? []).map { ($0.name, $0.value ?? "") })
        XCTAssertEqual(items["subject"], "Report: A & B = #1 + ?")
        XCTAssertEqual(items["body"],
                       "Group: A & B = #1 + ?\r\nAuthor: 7F3A91C0\r\nTime: 2026-11-16T12:34:56Z\r\nMessage:\r\none\r\ntwo & three")
    }

    func testLineEndsOfEveryKindAreLFForTheComposerAndCRLFInTheLink() throws {
        let report = GroupReport(groupName: "G", authorKey: key, time: time, text: "one\r\ntwo\rthree\nfour")

        XCTAssertTrue(report.body.hasSuffix("Message:\none\ntwo\nthree\nfour"), report.body)
        let components = try XCTUnwrap(URLComponents(url: try XCTUnwrap(report.mailtoURL), resolvingAgainstBaseURL: false))
        let body = components.queryItems?.first(where: { $0.name == "body" })?.value
        XCTAssertEqual(body?.hasSuffix("Message:\r\none\r\ntwo\r\nthree\r\nfour"), true)
    }

    func testTheTextStopsAt500CharactersWithoutCuttingOneInHalf() {
        let start = String(repeating: "a", count: 499)

        // A pair of surrogates at the edge stays whole.
        let emoji = GroupReport(groupName: "G", authorKey: key, time: time, text: start + "😀" + "after")
        XCTAssertTrue(emoji.body.hasSuffix("Message:\n" + start + "😀"), String(emoji.body.suffix(8)))

        // So does a flag (two code points) and a letter with its mark.
        let flag = GroupReport(groupName: "G", authorKey: key, time: time, text: start + "🇷🇺" + "after")
        XCTAssertTrue(flag.body.hasSuffix(start + "🇷🇺"))
        let accent = GroupReport(groupName: "G", authorKey: key, time: time, text: start + "e\u{301}" + "after")
        XCTAssertTrue(accent.body.hasSuffix(start + "e\u{301}"))

        // A text of 500 characters or fewer goes whole.
        let whole = String(repeating: "б", count: 500)
        XCTAssertTrue(GroupReport(groupName: "G", authorKey: key, time: time, text: whole).body.hasSuffix("\n" + whole))
        let message = GroupReport(groupName: "G", authorKey: key, time: time, text: whole + "в").body
        XCTAssertEqual(message.components(separatedBy: "Message:\n").last?.count, 500)
    }

    func testAGroupNameOfSeveralLinesIsOneAndAnAuthorWithoutAKeyIsUnknown() {
        let report = GroupReport(groupName: "  Khandaq\n  Community\t", authorKey: "", time: time, text: "")

        XCTAssertEqual(report.subject, "Report: Khandaq Community")
        XCTAssertTrue(report.body.hasPrefix("Group: Khandaq Community\nAuthor: unknown\n"), report.body)
        XCTAssertTrue(report.body.hasSuffix("Message:\n"))
    }

    func testAGroupNameInAnotherAlphabetIsEncodedAsUTF8() throws {
        let report = GroupReport(groupName: "Сообщество", authorKey: key, time: time, text: "Ассаламу алейкум")
        let string = try XCTUnwrap(report.mailtoURL).absoluteString

        // The first letter, Cyrillic capital Es (U+0421), is D0 A1 in UTF-8.
        XCTAssertTrue(string.contains("subject=Report%3A%20%D0%A1"), string)
        let components = try XCTUnwrap(URLComponents(string: string))
        XCTAssertEqual(components.queryItems?.first(where: { $0.name == "subject" })?.value, "Report: Сообщество")
    }
}
