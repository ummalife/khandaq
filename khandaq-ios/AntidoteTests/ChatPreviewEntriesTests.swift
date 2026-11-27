// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import XCTest
@testable import Antidote

/**
 The preview of a chat on a long press in the list (inbox #24, 27.11). A group's preview showed its messages as the
 talk of two, in bubbles without the names of their authors, and letters in a circle instead of the logo of Khandaq
 Community. It now writes the author over the first entry of another member's run and shows their tile beside its
 last, as the conversation does, and it has the tile of the chat's row.
 */
final class ChatPreviewEntriesTests: XCTestCase {
    private let time: TimeInterval = 1_764_000_000

    func testAGroupsPreviewNamesTheAuthorOfEveryRunAndShowsTheirTileAtItsEnd() throws {
        let chat = try group()
        let messages = [
            try entry("Peace be upon you", peerId: 3, name: "Amina", at: 0),
            try entry("Testing the app", peerId: 3, name: "Amina", at: 10),
            try entry("Deleting", at: 20),
            try entry("1", peerId: 5, name: "Bilal", at: 30),
            try entry("1", peerId: 3, name: "Amina", at: 40),
        ]

        let entries = ChatPreviewController.entries(of: messages, in: chat, groups: OCTSubmanagerGroupsMock())

        XCTAssertEqual(entries.map { $0.side }, [.incoming, .incoming, .outgoing, .incoming, .incoming])
        XCTAssertEqual(entries.map { $0.author }, ["Amina", nil, nil, "Bilal", "Amina"])
        XCTAssertEqual(entries.map { $0.showsTile }, [false, true, false, true, true])
        XCTAssertEqual(entries.map { $0.text }, ["Peace be upon you", "Testing the app", "Deleting", "1", "1"])

        let amina = AirTone.index(for: "Amina")
        XCTAssertEqual(entries[0].tile, .person(initials: AirInitials.from("Amina"), tone: amina))
        XCTAssertEqual(entries[1].tile, entries[0].tile, "an entry of a run keeps the column of its tile")
        XCTAssertEqual(entries[0].authorColor, AirTone.color(at: amina))
        XCTAssertNil(entries[2].tile, "your own entries have no tile")
        XCTAssertEqual(entries[3].tile, .person(initials: AirInitials.from("Bilal"), tone: AirTone.index(for: "Bilal")))
    }

    /// Five minutes apart the name stands again, as in the conversation (AirTranscript.runGap).
    func testALongPauseStartsANewRun() throws {
        let messages = [
            try entry("first", peerId: 3, name: "Amina", at: 0),
            try entry("later", peerId: 3, name: "Amina", at: AirTranscript.runGap + 1),
        ]

        let entries = ChatPreviewController.entries(of: messages, in: try group(), groups: OCTSubmanagerGroupsMock())

        XCTAssertEqual(entries.map { $0.author }, ["Amina", "Amina"])
        XCTAssertEqual(entries.map { $0.showsTile }, [true, true])
    }

    func testANoticeOfTheGroupStandsInTheMiddleWithoutAnAuthor() throws {
        let notice = try entry("Bilal joined the group", peerId: 5, name: "Bilal", at: 0)
        notice.groupSystemMessage = true

        let entries = ChatPreviewController.entries(of: [notice], in: try group(), groups: OCTSubmanagerGroupsMock())

        XCTAssertEqual(entries.map { $0.side }, [.notice])
        XCTAssertNil(entries[0].author)
        XCTAssertNil(entries[0].tile)
    }

    /// An author nobody confirmed is said so, with the plain tile, as in the conversation.
    func testAnAuthorNobodyConfirmedIsNotNamed() throws {
        let relayed = try entry("spam", peerId: 0, name: "Amina", at: 0)
        relayed.groupHistorySync = true

        let entries = ChatPreviewController.entries(of: [relayed], in: try group(), groups: UnattributingGroups())

        XCTAssertEqual(entries[0].author, String(localized: "air_group_author_unconfirmed"))
        XCTAssertEqual(entries[0].tile, .unconfirmed)
    }

    func testAPrivateChatsPreviewHasNoAuthorsAndNoTiles() throws {
        let chat = try appClass("OCTChat", as: OCTChat.Type.self).init()
        let theirs = try entry("Hi", at: 0)
        theirs.senderUniqueIdentifier = "friend"
        let messages = [theirs, try entry("Hello", at: 10)]

        let entries = ChatPreviewController.entries(of: messages, in: chat, groups: OCTSubmanagerGroupsMock())

        XCTAssertEqual(entries.map { $0.side }, [.incoming, .outgoing])
        XCTAssertEqual(entries.map { $0.author }, [nil, nil])
        XCTAssertEqual(entries.map { $0.tile }, [nil, nil])
    }

    /// The header has the tile of the chat's row: the logo for Khandaq Community, not letters in a circle.
    func testThePreviewHasTheTileOfTheRow() throws {
        let community = try group()
        community.groupChatIdHex = KhandaqCommunity.chatIdHex
        XCTAssertEqual(ChatListContent.tileStyle(for: community, title: KhandaqCommunity.displayName), .community)

        let club = try group()
        XCTAssertEqual(ChatListContent.tileStyle(for: club, title: "Book club"),
                       .group(initials: AirInitials.from("Book club"), tone: AirTone.index(for: club.uniqueIdentifier)))

        let saved = try appClass("OCTChat", as: OCTChat.Type.self).init()
        saved.isSavedMessages = true
        XCTAssertEqual(ChatListContent.tileStyle(for: saved, title: "Saved Messages"), .saved)
    }

    // MARK: Helpers

    /// The groups of a world where no entry from the history is attributed to its author.
    private final class UnattributingGroups: OCTSubmanagerGroupsMock {
        override func isGroupMessageAttributable(_ message: OCTMessageAbstract?, in chat: OCTChat?) -> Bool {
            return false
        }
    }

    private func group() throws -> OCTChat {
        let chat = try appClass("OCTChat", as: OCTChat.Type.self).init()
        chat.isGroup = true
        chat.groupName = "Book club"
        return chat
    }

    /// A text entry outside any Realm, made from the app's class: objcTox is in the app and in this bundle. With no
    /// peer number and no sender it is yours.
    private func entry(_ text: String, peerId: Int32 = 0, name: String? = nil, at seconds: TimeInterval) throws -> OCTMessageAbstract {
        let message = try appClass("OCTMessageAbstract", as: OCTMessageAbstract.Type.self).init()
        message.groupSenderPeerId = peerId
        message.dateInterval = time + seconds
        let body = try appClass("OCTMessageText", as: OCTMessageText.Type.self).init()
        body.text = text
        body.groupPeerName = name
        message.messageText = body
        return message
    }

    private func appClass<T>(_ name: String, as type: T.Type) throws -> T {
        let cls: AnyClass = try XCTUnwrap(NSClassFromString(name), "no class \(name)")
        return unsafeBitCast(cls, to: type)
    }
}
