// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import XCTest
@testable import Antidote

/**
 An edited note of Saved Messages (beta 8253). Edit on a note, a new text, Save: the note kept its old text.
 objcTox names the message an edit is for by its msgV3 hash, the one the edit packet carries to the friend,
 and a note of Saved Messages never has one: nobody receives it. editMessage checked for the hash before it
 looked at the chat, so it returned before the note changed.

 The note and the messages to a friend go through objcTox's own chats submanager into a Realm of the
 test's own, with a stand-in for Tox that keeps the packets it is given.
 */
final class SavedNoteEditTests: XCTestCase {
    private var directory: URL?
    private var realmManager: OCTRealmManager?
    private var tox: PacketTox?
    private var dataSource: EditingDataSource?
    private var chats: OCTSubmanagerChats?

    override func setUp() {
        super.setUp()
        do {
            try makeSubmanager()
        }
        catch {
            XCTFail("no chats submanager to edit with: \(error)")
        }
    }

    override func tearDown() {
        chats = nil
        dataSource = nil
        tox = nil
        realmManager = nil
        if let directory = directory {
            try? FileManager.default.removeItem(at: directory)
        }
        super.tearDown()
    }

    func testANoteInSavedMessagesTakesItsEdit() throws {
        let realmManager = try XCTUnwrap(self.realmManager)
        let saved = try XCTUnwrap(realmManager.getOrCreateSavedMessagesChat())
        let note = try XCTUnwrap(realmManager.addSavedTextMessage("Buy dates", to: saved))
        XCTAssertEqual(hash(of: note), "", "a note has a msgV3 hash now; this test no longer covers a note without one")

        try XCTUnwrap(chats).editMessage(note, newText: "  Buy dates and milk\n")

        XCTAssertEqual(text(of: note), "Buy dates and milk", "the note kept its old text")
        XCTAssertEqual(note.value(forKey: "edited") as? Bool, true, "the note has no edited mark")
        XCTAssertGreaterThan(note.value(forKey: "editedTimestamp") as? Double ?? 0, 0)
        XCTAssertEqual(note.value(forKey: "editPending") as? Bool, false, "a note waits to be delivered to nobody")
        XCTAssertEqual(try XCTUnwrap(tox).packets.count, 0, "an edit of a note went out to someone")
    }

    /// The check that moved still holds for a friend: the packet names the message by its hash.
    func testAMessageToAFriendIsStillEditedAndSent() throws {
        let hash = String(repeating: "0123456789ABCDEF", count: 4)
        let message = try storeSentText("See you at six", msgv3HashHex: hash)

        try XCTUnwrap(chats).editMessage(message, newText: "See you at seven")

        XCTAssertEqual(text(of: message), "See you at seven")
        XCTAssertEqual(message.value(forKey: "edited") as? Bool, true)
        XCTAssertEqual(message.value(forKey: "editPending") as? Bool, false, "the edit was sent and still waits")
        let packets = try XCTUnwrap(tox).packets
        XCTAssertEqual(packets.count, 1)
        let packet = [UInt8](try XCTUnwrap(packets.first))
        XCTAssertEqual(Array(packet.prefix(4)), [186, UInt8(ascii: "K"), UInt8(ascii: "Q"), 1])
        XCTAssertEqual(packet.dropFirst(4).prefix(32).map { String(format: "%02X", $0) }.joined(), hash,
                       "the packet does not name the message by its hash")
        XCTAssertEqual(String(decoding: packet.dropFirst(40), as: UTF8.self), "See you at seven")
    }

    /// A message to a friend without its hash cannot be named to the friend, so it is left as it is, as before.
    func testAMessageToAFriendWithoutItsHashIsLeftAsItIs() throws {
        let message = try storeSentText("See you at six", msgv3HashHex: nil)

        try XCTUnwrap(chats).editMessage(message, newText: "See you at seven")

        XCTAssertEqual(text(of: message), "See you at six")
        XCTAssertEqual(message.value(forKey: "edited") as? Bool, false)
        XCTAssertEqual(try XCTUnwrap(tox).packets.count, 0)
    }

    // MARK: - Helpers

    private func makeSubmanager() throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("saved-note-edit-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true, attributes: nil)
        self.directory = directory

        let realmManagerClass = try appClass("OCTRealmManager", as: OCTRealmManager.Type.self)
        let realmManager = try XCTUnwrap(realmManagerClass.init(databaseFileURL: directory.appendingPathComponent("chats.realm"),
                                                                encryptionKey: nil))
        self.realmManager = realmManager

        let tox = PacketTox()
        let dataSource = EditingDataSource(tox: tox, realmManager: realmManager)
        let chats = try appClass("OCTSubmanagerChatsImpl", as: NSObject.Type.self).init()
        chats.setValue(dataSource, forKey: "dataSource")
        self.tox = tox
        self.dataSource = dataSource
        self.chats = try XCTUnwrap(chats as? OCTSubmanagerChats, "OCTSubmanagerChatsImpl edits no messages")
    }

    /// objcTox and Realm are linked into this test bundle as well as into the app, and the two copies do not
    /// mix (OwnCaptionOrderTests): every objcTox object of the test is made from the class of its name.
    private func appClass<T>(_ name: String, as type: T.Type) throws -> T {
        guard let cls = NSClassFromString(name) else {
            throw NSError(domain: "SavedNoteEditTests", code: 1, userInfo: [NSLocalizedDescriptionKey: "no class \(name)"])
        }
        return unsafeBitCast(cls, to: type)
    }

    /// A text this phone sent to a friend, stored as OCTSubmanagerChatsImpl stores it once Tox took it.
    private func storeSentText(_ text: String, msgv3HashHex: String?) throws -> OCTMessageAbstract {
        let realmManager = try XCTUnwrap(self.realmManager)
        let friend = try appClass("OCTFriend", as: OCTFriend.Type.self).init()
        friend.nickname = "QA Mac"
        friend.publicKey = String(repeating: "A", count: 64)
        friend.capabilities2 = ""
        realmManager.add(friend)
        let chat = try XCTUnwrap(realmManager.getOrCreateChat(with: friend))
        return try XCTUnwrap(realmManager.addMessage(withText: text, type: .normal, chat: chat, sender: nil, messageId: 1,
                                                     msgv3HashHex: msgv3HashHex, sentPush: false, tssent: 0, tsrcvd: 0))
    }

    private func text(of message: OCTMessageAbstract) -> String? {
        return message.value(forKeyPath: "messageText.text") as? String
    }

    private func hash(of message: OCTMessageAbstract) -> String {
        return message.value(forKeyPath: "messageText.msgv3HashHex") as? String ?? ""
    }
}

/// Stands in for OCTTox: the friend is online and every packet goes out; the packets are kept.
private final class PacketTox: NSObject {
    private(set) var packets: [Data] = []

    @objc(friendNumberWithPublicKey:error:)
    func friendNumber(publicKey: String, error: NSErrorPointer) -> OCTToxFriendNumber {
        return 0
    }

    @objc(sendLosslessPacketWithFriendNumber:bytes:error:)
    func sendLosslessPacket(friendNumber: OCTToxFriendNumber, bytes: Data, error: NSErrorPointer) -> Bool {
        packets.append(bytes)
        return true
    }
}

/// What OCTSubmanagerChatsImpl asks of OCTManager while it edits a message.
private final class EditingDataSource: NSObject {
    private let tox: PacketTox
    private let realmManager: OCTRealmManager
    private let notificationCenter = NotificationCenter()

    init(tox: PacketTox, realmManager: OCTRealmManager) {
        self.tox = tox
        self.realmManager = realmManager
        super.init()
    }

    @objc func managerGetTox() -> NSObject {
        return tox
    }

    @objc func managerGetRealmManager() -> OCTRealmManager {
        return realmManager
    }

    @objc func managerGetNotificationCenter() -> NotificationCenter {
        return notificationCenter
    }

    @objc func managerUseFauxOfflineMessaging() -> Bool {
        return false
    }
}
