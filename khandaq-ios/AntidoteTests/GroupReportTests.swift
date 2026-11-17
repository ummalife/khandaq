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

    // MARK: Who wrote it

    /// The author left and toxcore gave their number, 5, to the next member who came: the mail still names the
    /// key the entry kept, never that of whoever has number 5 now.
    func testTheAuthorIsTheKeyTheEntryKeptWhoeverHasItsNumberNow() throws {
        let live = try entry(peerId: 5)
        live.groupPrivatePeerPubkey = key
        live.messageText = try appClass("OCTMessageText", as: OCTMessageText.Type.self).init()
        XCTAssertEqual(GroupReport.authorKey(of: live, authorVerified: false), key, "a live text: the key toxcore gave with the packet")

        let synced = try entry(peerId: 5)
        synced.groupHistorySync = true
        let syncedText = try appClass("OCTMessageText", as: OCTMessageText.Type.self).init()
        syncedText.groupSenderPubkey = "A1B2C3D4" + String(repeating: "0", count: 56)
        synced.messageText = syncedText
        XCTAssertEqual(GroupReport(groupName: "G", authorKey: GroupReport.authorKey(of: synced, authorVerified: true), time: time, text: "").author,
                       "A1B2C3D4", "a text from the group's history whose signature proved its author")

        let file = try entry(peerId: 5)
        let messageFile = try appClass("OCTMessageFile", as: OCTMessageFile.Type.self).init()
        messageFile.groupSenderPubkey = "e5f60718" + String(repeating: "0", count: 56)
        file.messageFile = messageFile
        XCTAssertEqual(GroupReport(groupName: "G", authorKey: GroupReport.authorKey(of: file, authorVerified: false), time: time, text: "").author,
                       "E5F60718", "a live file")

        let keptNone = try entry(peerId: 5)
        keptNone.messageText = try appClass("OCTMessageText", as: OCTMessageText.Type.self).init()
        XCTAssertEqual(GroupReport.authorKey(of: keptNone, authorVerified: false), "")
        XCTAssertEqual(GroupReport(groupName: "G", authorKey: GroupReport.authorKey(of: keptNone, authorVerified: false), time: time, text: "").author,
                       "unknown")

        // Our own private message keeps the key of the member it went to, who is not its author.
        let ours = try entry(peerId: 0)
        ours.groupPrivateMessage = true
        ours.groupPrivatePeerPubkey = key
        XCTAssertEqual(GroupReport.authorKey(of: ours, authorVerified: false), "")
    }

    /// An entry from the group's history names the author the member who relayed it claims, and nobody signed
    /// that claim: a member who forges one could make a report name somebody innocent, and support would then
    /// mute the wrong person. Such an entry names no author unless a signature proved this entry; a live one
    /// keeps the key the transport vouched for.
    func testAnEntryFromTheGroupsHistoryNamesNoAuthorUnlessASignatureProvedIt() throws {
        let chat = try appClass("OCTChat", as: OCTChat.Type.self).init()
        chat.isGroup = true
        chat.groupName = "Book club"
        let claimed = "A1B2C3D4" + String(repeating: "0", count: 56)

        let relayed = try entry(peerId: 5)
        relayed.groupHistorySync = true
        relayed.dateInterval = time.timeIntervalSince1970
        let relayedText = try appClass("OCTMessageText", as: OCTMessageText.Type.self).init()
        relayedText.groupSenderPubkey = claimed
        relayedText.text = "spam"
        relayed.messageText = relayedText

        let unproved = GroupReport(chat: chat, message: relayed, groups: OCTSubmanagerGroupsMock())
        XCTAssertEqual(unproved.author, "unknown")
        XCTAssertEqual(unproved.body, "Group: Book club\nAuthor: unknown\nTime: 2026-11-16T12:34:56Z\nMessage:\nspam")
        XCTAssertEqual(GroupReport(chat: chat, message: relayed, groups: nil).author, "unknown", "no submanager, no proof")
        XCTAssertEqual(GroupReport(chat: chat, message: relayed, groups: VerifyingGroups()).author, "A1B2C3D4",
                       "the signature proved who wrote it")

        // A file from the history can carry no signature at all.
        let relayedFile = try entry(peerId: 5)
        relayedFile.groupHistorySync = true
        relayedFile.dateInterval = time.timeIntervalSince1970
        let file = try appClass("OCTMessageFile", as: OCTMessageFile.Type.self).init()
        file.groupSenderPubkey = claimed.lowercased()
        file.fileName = "photo.jpg"
        relayedFile.messageFile = file
        XCTAssertEqual(GroupReport(chat: chat, message: relayedFile, groups: OCTSubmanagerGroupsMock()).author, "unknown")

        // The same text as it came live: the transport vouched for its sender, whatever the verdict says.
        let live = try entry(peerId: 5)
        live.dateInterval = time.timeIntervalSince1970
        let liveText = try appClass("OCTMessageText", as: OCTMessageText.Type.self).init()
        liveText.groupSenderPubkey = claimed
        liveText.text = "spam"
        live.messageText = liveText
        XCTAssertEqual(GroupReport(chat: chat, message: live, groups: OCTSubmanagerGroupsMock()).author, "A1B2C3D4")
    }

    func testAPrivateMessageInTheGroupIsReportedWithTheMailOfTheGroup() throws {
        let chat = try appClass("OCTChat", as: OCTChat.Type.self).init()
        chat.isGroup = true
        chat.groupName = "Khandaq Community"
        let message = try entry(peerId: 9)
        message.groupPrivateMessage = true
        message.groupPrivatePeerPubkey = key
        message.dateInterval = time.timeIntervalSince1970
        let text = try appClass("OCTMessageText", as: OCTMessageText.Type.self).init()
        text.text = "Peace be upon you"
        message.messageText = text

        let report = GroupReport(chat: chat, message: message, groups: OCTSubmanagerGroupsMock())

        XCTAssertEqual(report.subject, "Report: Khandaq Community")
        XCTAssertEqual(report.body, "Group: Khandaq Community\nAuthor: 7F3A91C0\nTime: 2026-11-16T12:34:56Z\nMessage:\nPeace be upon you")
    }

    func testAGroupNameInAnotherAlphabetIsEncodedAsUTF8() throws {
        let report = GroupReport(groupName: "Сообщество", authorKey: key, time: time, text: "Ассаламу алейкум")
        let string = try XCTUnwrap(report.mailtoURL).absoluteString

        // The first letter, Cyrillic capital Es (U+0421), is D0 A1 in UTF-8.
        XCTAssertTrue(string.contains("subject=Report%3A%20%D0%A1"), string)
        let components = try XCTUnwrap(URLComponents(string: string))
        XCTAssertEqual(components.queryItems?.first(where: { $0.name == "subject" })?.value, "Report: Сообщество")
    }

    // MARK: Helpers

    /// The groups of the demo world, where a signature proved every entry from a group's history.
    private final class VerifyingGroups: OCTSubmanagerGroupsMock {
        override func isGroupMessageAuthorVerified(_ message: OCTMessageAbstract?, in chat: OCTChat?) -> Bool {
            return message?.groupHistorySync == true
        }
    }

    /// An entry of a group outside any Realm, made from the app's class: objcTox is in the app and in this bundle.
    private func entry(peerId: Int32) throws -> OCTMessageAbstract {
        let message = try appClass("OCTMessageAbstract", as: OCTMessageAbstract.Type.self).init()
        message.groupSenderPeerId = peerId
        return message
    }

    private func appClass<T>(_ name: String, as type: T.Type) throws -> T {
        let cls: AnyClass = try XCTUnwrap(NSClassFromString(name), "no class \(name)")
        return unsafeBitCast(cls, to: type)
    }
}
