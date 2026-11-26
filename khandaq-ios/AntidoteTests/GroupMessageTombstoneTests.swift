// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import XCTest
@testable import Antidote

/**
 A deleted group message stays deleted (tester 21.11, 8256 and 8261). A location message the tester deleted three
 times came back each time, "often after a name change". A removal left nothing behind, and every member who still
 had the message served it in the group's history sync for 130 minutes, at each join and reconnect, the restart after
 a rename among them; with nothing stored, the dedup took the copy for a new message and stored it again, "Relayed
 from history". What is deleted on the device is now remembered (OCTGroupMessageTombstones) by the marks the receive
 paths tell copies of one message apart by, and a copy that comes again is not stored.
 */
final class GroupMessageTombstoneTests: XCTestCase {
    private let storeKey = "tester-21-11"
    // Keys of members, 64 hex digits each (named so that gitleaks does not take them for secrets).
    private let bobKey = String(repeating: "B0B1", count: 16)
    private let carolKey = String(repeating: "CA01", count: 16)
    private let transferId = String(repeating: "F11E", count: 16)
    private let location = "khandaq-location:41.00820,28.97840"
    /// The member that serves the history (any peer but us: our own peer id is 0).
    private let relayPeer: UInt32 = 7

    private var values = [String: String]()
    private var bed: GroupTestBed?

    override func tearDown() {
        bed?.remove()
        bed = nil
        values = [:]
        super.tearDown()
    }

    // MARK: - The marks a deleted message is known by

    func testADeletedTextIsKnownByItsIdWithItsTextOrItsAuthor() throws {
        let tombstones = try makeTombstones()
        let sent = Date().timeIntervalSince1970 - 600
        tombstones.recordMessages([try incomingText(location, id: 77, author: bobKey, name: "Bob", at: sent)],
                                  storeKey: storeKey, now: sent + 60)

        // A member serves it in the history sync, with the time it was sent.
        XCTAssertTrue(isDeleted(tombstones, id: 77, text: location, author: bobKey, name: "Bob", at: sent))
        // The id goes with its text, whoever relays it and whenever...
        XCTAssertTrue(isDeleted(tombstones, id: 77, text: location, author: nil, name: nil, at: sent + 3600))
        // ...and with its author, who may have edited the text since.
        XCTAssertTrue(isDeleted(tombstones, id: 77, text: "khandaq-location:41.00900,28.97900", author: bobKey, name: "Bob", at: sent))
        // Another member's message under the same id is not it.
        XCTAssertFalse(isDeleted(tombstones, id: 77, text: "Salam", author: carolKey, name: "Carol", at: sent))
    }

    func testADeletedTextIsKnownByItsTextAndAuthorNearItsTime() throws {
        let tombstones = try makeTombstones()
        let sent = Date().timeIntervalSince1970 - 600
        tombstones.recordMessages([try incomingText(location, id: 77, author: bobKey, name: "Bob", at: sent)],
                                  storeKey: storeKey, now: sent + 60)

        // A resend has an id of its own: the same text from the same author, by key or by name, near its time.
        XCTAssertTrue(isDeleted(tombstones, id: 0, text: location, author: bobKey, name: "Bob", at: sent + 60))
        XCTAssertTrue(isDeleted(tombstones, id: 91, text: location, author: nil, name: "Bob", at: sent - 100))
        // The same text from another member is that member's...
        XCTAssertFalse(isDeleted(tombstones, id: 0, text: location, author: carolKey, name: "Carol", at: sent + 60))
        // ...unless within 3 s of it, as stored rows are told apart (#42).
        XCTAssertTrue(isDeleted(tombstones, id: 0, text: location, author: carolKey, name: "Carol", at: sent + 2))
        // The same text sent again later is a new message, and another text is not it.
        XCTAssertFalse(isDeleted(tombstones, id: 0, text: location, author: bobKey, name: "Bob", at: sent + 600))
        XCTAssertFalse(isDeleted(tombstones, id: 0, text: "Salam", author: bobKey, name: "Bob", at: sent))
    }

    /// Our own message, deleted for everyone: a copy served back to us after a rejoin, under another key of ours.
    func testAnOwnTextIsKnownByItsIdAndText() throws {
        let tombstones = try makeTombstones()
        let sent = Date().timeIntervalSince1970 - 600
        let own = try incomingText(location, id: 12, author: nil, name: nil, at: sent)
        own.groupSenderPeerId = 0
        tombstones.recordMessages([own], storeKey: storeKey, now: sent + 60)

        XCTAssertTrue(isDeleted(tombstones, id: 12, text: location, author: carolKey, name: "AZ iOS", at: sent))
        XCTAssertFalse(isDeleted(tombstones, id: 0, text: location, author: carolKey, name: "Carol", at: sent + 60),
                       "our row named no author, and the same text of another member was taken for it")
    }

    func testADeletedFileIsKnownByItsTransferAndByItsContent() throws {
        let tombstones = try makeTombstones()
        let sent = Date().timeIntervalSince1970 - 600
        tombstones.recordMessages([try incomingFile("IMG_0042.jpg", size: 48_213, transfer: transferId, author: bobKey, at: sent)],
                                  storeKey: storeKey, now: sent + 60)

        XCTAssertTrue(tombstones.isDeletedFile(inStoreKey: storeKey, msgIdHashHex: transferId.lowercased(), now: sent + 120))
        XCTAssertFalse(tombstones.isDeletedFile(inStoreKey: storeKey, msgIdHashHex: bobKey, now: sent + 120))
        // A copy sent again comes under a transfer of its own, and a stored copy of a name already taken has a prefix.
        XCTAssertEqual(OCTGroupFileBaseName("1_IMG_0042.jpg"), "IMG_0042.jpg")
        XCTAssertTrue(isDeletedFile(tombstones, name: "1_IMG_0042.jpg", size: 48_213, at: sent + 3600))
        XCTAssertFalse(isDeletedFile(tombstones, name: "IMG_0042.jpg", size: 48_214, at: sent + 3600))
        XCTAssertFalse(isDeletedFile(tombstones, name: "IMG_0042.jpg", size: 48_213, at: sent + 2 * 86_400))
    }

    func testTombstonesOutliveTheAppAndGoAfterAWeek() throws {
        let sent = Date().timeIntervalSince1970 - 600
        try makeTombstones().recordMessages([try incomingText(location, id: 77, author: bobKey, name: "Bob", at: sent)],
                                            storeKey: storeKey, now: sent)

        // Another start reads them from the profile.
        let restarted = try makeTombstones()
        XCTAssertTrue(isDeleted(restarted, id: 77, text: location, author: bobKey, name: "Bob", at: sent, now: sent + 6 * 86_400))
        XCTAssertFalse(isDeleted(restarted, id: 77, text: location, author: bobKey, name: "Bob", at: sent, now: sent + 8 * 86_400))
        XCTAssertEqual(restarted.count(inStoreKey: storeKey, now: sent + 8 * 86_400), 0)
    }

    func testAChatKeepsItsNewestTombstones() throws {
        let tombstones = try makeTombstones()
        let capacity = Int(OCTGroupMessageTombstones.capacityPerChat())
        let sent = Date().timeIntervalSince1970 - 600
        let messages = try (1...(capacity + 8)).map {
            try incomingText("Message \($0)", id: UInt32($0), author: bobKey, name: "Bob", at: sent + Double($0))
        }
        tombstones.recordMessages(messages, storeKey: storeKey, now: sent)

        XCTAssertEqual(Int(tombstones.count(inStoreKey: storeKey, now: sent)), capacity)
        XCTAssertFalse(isDeleted(tombstones, id: 1, text: "Message 1", author: bobKey, name: "Bob", at: sent + 1))
        XCTAssertTrue(isDeleted(tombstones, id: UInt32(capacity + 8), text: "Message \(capacity + 8)", author: bobKey,
                                name: "Bob", at: sent + Double(capacity + 8)))
    }

    /// A system line comes from nobody, and a private message never comes from the history sync.
    func testSystemLinesAndPrivateMessagesLeaveNoTombstone() throws {
        let tombstones = try makeTombstones()
        let sent = Date().timeIntervalSince1970 - 600
        let system = try incomingText("Bob joined", id: 0, author: bobKey, name: "Bob", at: sent)
        system.groupSystemMessage = true
        let privateMessage = try incomingText(location, id: 5, author: bobKey, name: "Bob", at: sent)
        privateMessage.groupPrivateMessage = true
        tombstones.recordMessages([system, privateMessage], storeKey: storeKey, now: sent)

        XCTAssertEqual(tombstones.count(inStoreKey: storeKey, now: sent), 0)
        XCTAssertTrue(values.isEmpty, "something was written")
    }

    /// A group is known by its id, which a chat made again by a rejoin keeps; a chat without one by itself.
    func testAGroupIsKnownByItsId() {
        let chatId = String(repeating: "A1B2C3D4E5F60718", count: 4)
        XCTAssertEqual(OCTGroupMessageTombstones.storeKey(forChatIdHex: chatId, chatUniqueIdentifier: "chat"), chatId.lowercased())
        XCTAssertEqual(OCTGroupMessageTombstones.storeKey(forChatIdHex: nil, chatUniqueIdentifier: "chat"), "chat")
        XCTAssertEqual(OCTGroupMessageTombstones.storeKey(forChatIdHex: "A1B2", chatUniqueIdentifier: "chat"), "chat")
    }

    // MARK: - The history sync, end to end

    func testALocationDeletedHereDoesNotComeBackFromTheHistorySync() throws {
        let bed = try makeBed()
        let sent = UInt32(Date().timeIntervalSince1970 - 600)
        let packet = historyPacket(id: 77, author: bobKey, at: sent, name: "Bob", text: location)

        serve(packet, to: bed)
        let relayed = rows(in: bed)
        XCTAssertEqual(texts(in: bed), [location], "the history sync stored nothing")
        XCTAssertEqual(relayed.first?.groupHistorySync, true)

        // What the report saw: the row went, nothing was left of it, and after a restart the sync stored it again.
        bed.realmManager.removeMessages(relayed)
        try bed.restart()
        serve(packet, to: bed)
        XCTAssertEqual(texts(in: bed), [location], "a removed row did not come back: the test does not show the report")

        // Deleted here, it stays deleted, after a restart too.
        bed.groups.removeGroupMessages(rows(in: bed), in: bed.chat)
        XCTAssertEqual(texts(in: bed), [])
        try bed.restart()
        serve(packet, to: bed)
        XCTAssertEqual(texts(in: bed), [], "the deleted location came back from the history sync")

        // Bob's next message still comes.
        serve(historyPacket(id: 78, author: bobKey, at: sent + 30, name: "Bob", text: "Salam"), to: bed)
        XCTAssertEqual(texts(in: bed), ["Salam"])
    }

    func testAClearedHistoryStaysCleared() throws {
        let bed = try makeBed()
        let sent = UInt32(Date().timeIntervalSince1970 - 600)
        let packets = [historyPacket(id: 77, author: bobKey, at: sent, name: "Bob", text: location),
                       historyPacket(id: 301, author: carolKey, at: sent + 40, name: "Carol", text: "Salam")]
        packets.forEach { serve($0, to: bed) }
        XCTAssertEqual(Set(texts(in: bed)), [location, "Salam"])

        bed.groups.removeAllMessages(in: bed.chat, removeChat: false, leaveGroup: false)
        try bed.restart()
        packets.forEach { serve($0, to: bed) }

        XCTAssertEqual(texts(in: bed), [], "the cleared history came back from the history sync")
    }

    // MARK: - Helpers

    private func makeBed() throws -> GroupTestBed {
        let bed = try GroupTestBed(peerName: "AZ iOS")
        self.bed = bed
        return bed
    }

    /// The tombstones on key-value rows of the test's own, which a later instance reads as a later start of the app.
    private func makeTombstones() throws -> OCTGroupMessageTombstones {
        let tombstonesClass = try GroupTestBed.appClass("OCTGroupMessageTombstones", as: OCTGroupMessageTombstones.Type.self)
        return tombstonesClass.init(getValueBlock: { [unowned self] (key: String?) -> String? in
            return self.values[key ?? ""]
        }, setValueBlock: { [unowned self] (key: String?, value: String?) -> Bool in
            self.values[key ?? ""] = value
            return true
        })
    }

    private func isDeleted(_ tombstones: OCTGroupMessageTombstones, id: UInt32, text: String, author: String?, name: String?,
                           at time: TimeInterval, now: TimeInterval? = nil) -> Bool {
        return tombstones.isDeletedText(inStoreKey: storeKey, messageId: id, text: text, authorKey: author, authorName: name,
                                        dateInterval: time, windowSeconds: 120, now: now ?? time + 3600)
    }

    private func isDeletedFile(_ tombstones: OCTGroupMessageTombstones, name: String, size: UInt64, at time: TimeInterval) -> Bool {
        return tombstones.isDeletedFile(inStoreKey: storeKey, baseName: OCTGroupFileBaseName(name), fileSize: size,
                                        dateInterval: time, now: time)
    }

    /// A text of another member as the live path stores it: the author's key frozen on the row, their name on the text.
    private func incomingText(_ text: String, id: UInt32, author: String?, name: String?, at time: TimeInterval) throws -> OCTMessageAbstract {
        let message = try GroupTestBed.appClass("OCTMessageAbstract", as: OCTMessageAbstract.Type.self).init()
        let body = try GroupTestBed.appClass("OCTMessageText", as: OCTMessageText.Type.self).init()
        body.text = text
        body.messageId = OCTToxMessageId(bitPattern: id)
        body.groupPeerName = name
        message.messageText = body
        message.groupSenderPeerId = 5
        message.groupPrivatePeerPubkey = author?.lowercased()
        message.dateInterval = time
        message.groupSyncDedupTimestamp = time
        return message
    }

    private func incomingFile(_ name: String, size: OCTToxFileSize, transfer: String, author: String, at time: TimeInterval) throws -> OCTMessageAbstract {
        let message = try GroupTestBed.appClass("OCTMessageAbstract", as: OCTMessageAbstract.Type.self).init()
        let file = try GroupTestBed.appClass("OCTMessageFile", as: OCTMessageFile.Type.self).init()
        file.fileName = name
        file.fileSize = size
        file.groupMsgIdHashHex = transfer
        file.groupSenderPubkey = author
        message.messageFile = file
        message.groupSenderPeerId = 5
        message.dateInterval = time
        return message
    }

    /// A message of the history sync (OCTNgcGroupHistSync, type 0x02): magic, layer 1, type 2, the message id, the
    /// author's key, the time it was sent, the author's name in 25 bytes, the text.
    private func historyPacket(id: UInt32, author: String, at time: UInt32, name: String, text: String) -> Data {
        var bytes: [UInt8] = [0x66, 0x77, 0x88, 0x11, 0x34, 0x35, 0x01, 0x02]
        bytes += bigEndian(id)
        bytes += hexBytes(author)
        bytes += bigEndian(time)
        let nameBytes = Array(name.utf8.prefix(25))
        bytes += nameBytes + [UInt8](repeating: 0, count: 25 - nameBytes.count)
        bytes += Array(text.utf8)
        return Data(bytes)
    }

    /// To the history sync the submanager sets up, as a member's private packet (the submanager hands it over from a
    /// member toxcore knows, which a Tox without the network has none of).
    private func serve(_ packet: Data, to bed: GroupTestBed) {
        bed.impl.setupHistSyncIfNeeded()
        bed.impl.histSync.handleIncomingPrivatePacket(withGroupNumber: bed.group, peerId: relayPeer, data: packet)
    }

    /// The messages of the group's chat, system lines left out; objects of the app's classes, read through the
    /// declared interface.
    private func rows(in bed: GroupTestBed) -> [OCTMessageAbstract] {
        guard let messageClass = NSClassFromString("OCTMessageAbstract") else {
            XCTFail("no OCTMessageAbstract")
            return []
        }
        guard let results = bed.realmManager.objects(with: messageClass,
                                                     predicate: NSPredicate(format: "chatUniqueIdentifier == %@ AND groupSystemMessage == NO",
                                                                            bed.chat.uniqueIdentifier)) else {
            XCTFail("no messages to read")
            return []
        }
        return (0..<results.count).map { unsafeBitCast(results.object(at: $0) as AnyObject, to: OCTMessageAbstract.self) }
    }

    private func texts(in bed: GroupTestBed) -> [String] {
        return rows(in: bed).map { ($0 as NSObject).value(forKeyPath: "messageText.text") as? String ?? "?" }
    }

    private func bigEndian(_ value: UInt32) -> [UInt8] {
        return [UInt8(value >> 24 & 0xFF), UInt8(value >> 16 & 0xFF), UInt8(value >> 8 & 0xFF), UInt8(value & 0xFF)]
    }

    private func hexBytes(_ hex: String) -> [UInt8] {
        var bytes = [UInt8]()
        var index = hex.startIndex
        while index < hex.endIndex {
            let next = hex.index(index, offsetBy: 2)
            bytes.append(UInt8(hex[index..<next], radix: 16) ?? 0)
            index = next
        }
        return bytes
    }
}
