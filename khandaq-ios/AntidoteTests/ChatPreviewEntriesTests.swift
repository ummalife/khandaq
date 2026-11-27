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

    // MARK: Quotes, photos, videos and places (inbox #28, 28.11)

    /// A reply quotes the message it answers by the name and the words the conversation quotes it with, and says its
    /// own words without the header the wire carries them under.
    func testAReplyQuotesTheMessageItAnswers() throws {
        let chat = try appClass("OCTChat", as: OCTChat.Type.self).init()
        let reply = try entry(MessageReplyHelper.encode(quote(of: "Amina", saying: "Are we still on for Thursday?"),
                                                        bodyText: "Yes, after maghrib"),
                              at: 10)

        let entries = ChatPreviewController.entries(of: [reply], in: chat, groups: OCTSubmanagerGroupsMock())

        XCTAssertEqual(entries.first?.text, "Yes, after maghrib")
        XCTAssertEqual(entries.first?.reply?.senderName, "Amina")
        XCTAssertEqual(entries.first?.reply?.previewText, "Are we still on for Thursday?")
        XCTAssertNil(entries.first?.media)
    }

    /// A quote of a message deleted here says so in the preview as in the conversation: the preview marks every reply
    /// as the conversation marks it, and a member's reply in a group too.
    func testAQuoteOfADeletedMessageIsMarkedAsInTheConversation() throws {
        let reply = try entry(MessageReplyHelper.encode(quote(of: "Amina", saying: "Gone"), bodyText: "What was it?"),
                              peerId: 3, name: "Bilal", at: 10)
        var marked = [OCTMessageAbstract]()

        let entries = ChatPreviewController.entries(of: [reply], in: try group(), groups: OCTSubmanagerGroupsMock()) { meta, message in
            marked.append(message)
            var deleted = meta
            deleted?.targetDeleted = true
            return deleted
        }

        XCTAssertEqual(marked.count, 1)
        XCTAssertTrue(marked.first === reply)
        XCTAssertEqual(entries.first?.reply?.targetDeleted, true)
        XCTAssertEqual(entries.first?.author, "Bilal")
    }

    /// A photo and a video are shown as the conversation shows them, not by the name of their file; the photo keeps
    /// its shape, and a video whose frame nobody read yet keeps the conversation's 4:3.
    func testAPhotoAndAVideoAreShownAsInTheConversation() throws {
        let chat = try appClass("OCTChat", as: OCTChat.Type.self).init()
        let photoPath = try writePhoto(width: 40, height: 30)
        let videoPath = try writeFile(named: "clip.mov")
        let messages = [
            try fileEntry("Photo_20261101_090000.jpg", path: photoPath, at: 10),
            try fileEntry("C0FFEE00-0000-4000-8000-000000000028.mov", path: videoPath, at: 20),
        ]

        let entries = ChatPreviewController.entries(of: messages, in: chat, groups: OCTSubmanagerGroupsMock())

        XCTAssertEqual(entries.map { $0.media }, [.photo(path: photoPath, pixelSize: CGSize(width: 40, height: 30)),
                                                  .video(path: videoPath, pixelSize: nil)])
        XCTAssertEqual(entries.map { $0.text }, ["", ""])
        XCTAssertEqual(entries.map { $0.side }, [.outgoing, .outgoing])
    }

    /// A file that is no photo or video, a voice note among them (an .m4a is an MPEG-4 to the system), a photo still
    /// arriving and a photo gone from the device keep their names, as in the conversation.
    func testOtherFilesAndPhotosNotHereKeepTheirNames() throws {
        let chat = try appClass("OCTChat", as: OCTChat.Type.self).init()
        let voicePath = try writeFile(named: "voice.m4a")
        let documentPath = try writeFile(named: "notes.pdf")
        let photoPath = try writePhoto(width: 40, height: 30)
        let gonePath = (photoPath as NSString).deletingLastPathComponent + "/gone.jpg"
        let messages = [
            try fileEntry("voice-1764000000.m4a", path: voicePath, at: 10),
            try fileEntry("notes.pdf", path: documentPath, at: 20),
            try fileEntry("IMG_0001.jpg", path: photoPath, incoming: true, ready: false, at: 30),
            try fileEntry("IMG_0002.jpg", path: gonePath, at: 40),
        ]

        let entries = ChatPreviewController.entries(of: messages, in: chat, groups: OCTSubmanagerGroupsMock())

        XCTAssertEqual(entries.map { $0.media }, [nil, nil, nil, nil])
        XCTAssertEqual(entries.map { $0.text }, [String(localized: "chat_outgoing_file") + " voice-1764000000.m4a",
                                                 String(localized: "chat_outgoing_file") + " notes.pdf",
                                                 String(localized: "chat_incoming_file") + " IMG_0001.jpg",
                                                 String(localized: "chat_outgoing_file") + " IMG_0002.jpg"])
    }

    /// A place is shown by its map and written by its coordinates, as the conversation writes it, not as the text it
    /// travels in ("khandaq-location:…" stood in the bubble).
    func testAPlaceIsShownByItsMapAndCoordinates() throws {
        let chat = try appClass("OCTChat", as: OCTChat.Type.self).init()
        let place = try entry(LocationMessage.payload(latitude: -22.90685, longitude: -43.17290), at: 10)

        let entries = ChatPreviewController.entries(of: [place], in: chat, groups: OCTSubmanagerGroupsMock())

        XCTAssertEqual(entries.first?.text, "-22.90685, -43.17290")
        XCTAssertEqual(entries.first?.media, .location(latitude: -22.90685, longitude: -43.17290))
    }

    /// The box of a photo or a video has the shape of its picture, three quarters of its box in the conversation.
    func testAPicturesBoxHasItsShape() {
        XCTAssertEqual(ChatPreviewController.mediaBox(for: CGSize(width: 4000, height: 3000)), CGSize(width: 180, height: 135))
        XCTAssertEqual(ChatPreviewController.mediaBox(for: CGSize(width: 1080, height: 1920)), CGSize(width: 127, height: 225))
        XCTAssertEqual(ChatPreviewController.mediaBox(for: nil), CGSize(width: 180, height: 135))
        XCTAssertEqual(ChatPreviewController.mapBox, CGSize(width: 180, height: 83))
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

    private func quote(of name: String, saying words: String) -> MessageReplyHelper.ReplyMeta {
        var meta = MessageReplyHelper.ReplyMeta()
        meta.localMessageId = 7
        meta.senderPubkeyTail = "A1B2C3D4"
        meta.sortTimestampMs = Int64((time + 5) * 1000)
        meta.senderName = name
        meta.previewText = words
        return meta
    }

    /// A file entry outside any Realm whose file is at `path`: ours unless `incoming`, and all here unless not `ready`.
    private func fileEntry(_ fileName: String,
                           path: String,
                           incoming: Bool = false,
                           ready: Bool = true,
                           at seconds: TimeInterval) throws -> OCTMessageAbstract {
        let message = try appClass("OCTMessageAbstract", as: OCTMessageAbstract.Type.self).init()
        message.dateInterval = time + seconds
        if incoming {
            message.senderUniqueIdentifier = "friend"
        }
        let file = try appClass("OCTMessageFile", as: OCTMessageFile.Type.self).init()
        file.fileName = fileName
        file.fileType = ready ? .ready : .loading
        file.internalFilePath = path
        message.messageFile = file
        return message
    }

    /// A photo of the size given, in pixels, in a file of its own that the test removes.
    private func writePhoto(width: CGFloat, height: CGFloat) throws -> String {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let image = UIGraphicsImageRenderer(size: CGSize(width: width, height: height), format: format).image { context in
            UIColor.gray.setFill()
            context.fill(CGRect(x: 0, y: 0, width: width, height: height))
        }
        let data = try XCTUnwrap(UIImagePNGRepresentation(image))
        return try write(data, named: "photo.png")
    }

    private func writeFile(named name: String) throws -> String {
        return try write(Data([0x00, 0x01, 0x02, 0x03]), named: name)
    }

    private func write(_ data: Data, named name: String) throws -> String {
        let directory = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        addTeardownBlock {
            try? FileManager.default.removeItem(at: directory)
        }
        let url = directory.appendingPathComponent(name)
        try data.write(to: url)
        return url.path
    }

    private func appClass<T>(_ name: String, as type: T.Type) throws -> T {
        let cls: AnyClass = try XCTUnwrap(NSClassFromString(name), "no class \(name)")
        return unsafeBitCast(cls, to: type)
    }
}
