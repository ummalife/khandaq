// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import XCTest
@testable import Antidote

/**
 The shared minimum (redesign stage 3, item 10). Replies, edits, deletions and reactions travel as the
 KQ packets Android sends: 186, 187 and 188 between two people, 0x41, 0x42 and 0x43 in a group. Each
 test hands objcTox's receive path the exact bytes Android builds and checks what comes out, and that
 a packet of another shape is left alone. Markup this build cannot read is shown in words, never as a
 service string.
 */
class AirPacketTests: XCTestCase {
    /// Stands in for OCTTox: the 1:1 receive path reads only its delegate.
    @objc private final class FakeTox: NSObject {
        @objc var delegate: AnyObject?
    }

    /// Records what the 1:1 receive path hands over, by the selectors of OCTToxDelegate.
    @objc private final class Sink: NSObject {
        var calls: [String] = []
        var arrived: XCTestExpectation?

        private func record(_ call: String) {
            calls.append(call)
            arrived?.fulfill()
        }

        @objc(tox:friendMessageEditWithMsgv3Hash:newText:editTs:friendNumber:)
        func edit(_ tox: AnyObject, hash: String, newText: String, editTs: UInt32, friendNumber: Int32) {
            record("edit \(hash) \(editTs) \(friendNumber) \(newText)")
        }

        @objc(tox:friendMessageDeleteWithMsgv3Hash:friendNumber:)
        func deleteText(_ tox: AnyObject, hash: String, friendNumber: Int32) {
            record("delete text \(hash) \(friendNumber)")
        }

        @objc(tox:friendMessageDeleteWithFileIdHex:friendNumber:)
        func deleteFile(_ tox: AnyObject, fileId: String, friendNumber: Int32) {
            record("delete file \(fileId) \(friendNumber)")
        }

        @objc(tox:friendMessageReactionWithMsgv3Hash:emoji:add:friendNumber:)
        func reactText(_ tox: AnyObject, hash: String, emoji: String, add: Bool, friendNumber: Int32) {
            record("react text \(hash) \(emoji) \(add) \(friendNumber)")
        }

        @objc(tox:friendFileReactionWithFileIdHex:emoji:add:friendNumber:)
        func reactFile(_ tox: AnyObject, fileId: String, emoji: String, add: Bool, friendNumber: Int32) {
            record("react file \(fileId) \(emoji) \(add) \(friendNumber)")
        }
    }

    private let anchor = [UInt8](0..<32)
    private let anchorHex = "000102030405060708090A0B0C0D0E0F101112131415161718191A1B1C1D1E1F"
    private let thumbsUp = "\u{1F44D}"
    private let groupMagic: [UInt8] = [0x66, 0x77, 0x88, 0x11, 0x34, 0x35, 0x01]

    private func bytes(_ parts: [UInt8]...) -> [UInt8] {
        return parts.flatMap { $0 }
    }

    private func bigEndian(_ value: UInt32) -> [UInt8] {
        return [UInt8(value >> 24 & 0xFF), UInt8(value >> 16 & 0xFF), UInt8(value >> 8 & 0xFF), UInt8(value & 0xFF)]
    }

    /// Hands a 1:1 packet from friend 5 to objcTox and returns what reached the delegate.
    private func deliver(_ packet: [UInt8], expectingCall: Bool = true) -> [String] {
        let sink = Sink()
        let arrived = expectation(description: "the packet reached the delegate")
        arrived.isInverted = !expectingCall
        sink.arrived = arrived
        let tox = FakeTox()
        tox.delegate = sink
        let userData = Unmanaged.passUnretained(tox).toOpaque()
        packet.withUnsafeBufferPointer { bytes in
            friendLosslessPacketCallback(nil, 5, bytes.baseAddress, bytes.count, userData)
        }
        // The receive path hops to the main queue before it calls the delegate.
        wait(for: [arrived], timeout: expectingCall ? 2 : 0.3)
        return sink.calls
    }

    // MARK: 186, 187, 188 between two people

    func testAnEditFromAndroidArrivesWithItsMessageTimeAndText() {
        let text = "Салам, исправил"
        let packet = bytes([186, 0x4B, 0x51, 1], anchor, bigEndian(16_909_060), Array(text.utf8))
        XCTAssertEqual(deliver(packet), ["edit \(anchorHex) 16909060 5 \(text)"])
    }

    func testAPacketOfAnotherVersionOrLengthIsLeftAlone() {
        XCTAssertEqual(deliver(bytes([186, 0x4B, 0x51, 2], anchor, bigEndian(1), [0x78]), expectingCall: false), [])
        XCTAssertEqual(deliver(bytes([187, 0x4B, 0x51, 1], anchor, [0, 0, 1]), expectingCall: false), [])
        XCTAssertEqual(deliver(bytes([188, 0x4B, 0x51, 1, 3], anchor, bigEndian(1), [1, 1, 0x41]), expectingCall: false), [],
                       "an anchor of an unknown kind")
    }

    func testADeleteFromAndroidNamesTheTextOrTheFile() {
        XCTAssertEqual(deliver(bytes([187, 0x4B, 0x51, 1], anchor, bigEndian(1_700_000_000))), ["delete text \(anchorHex) 5"])
        XCTAssertEqual(deliver(bytes([187, 0x4B, 0x51, 1, 2], anchor, bigEndian(1_700_000_000))), ["delete file \(anchorHex) 5"])
    }

    func testAReactionFromAndroidNamesItsEmojiAndWhetherItIsAddedOrTaken() {
        let emoji = Array(thumbsUp.utf8)
        let add = bytes([188, 0x4B, 0x51, 1, 1], anchor, bigEndian(1_700_000_000), [1, UInt8(emoji.count)], emoji)
        XCTAssertEqual(deliver(add), ["react text \(anchorHex) \(thumbsUp) true 5"])

        let remove = bytes([188, 0x4B, 0x51, 1, 2], anchor, bigEndian(1_700_000_000), [0, 1, 0x2A])
        XCTAssertEqual(deliver(remove), ["react file \(anchorHex) * false 5"])

        let noEmoji = bytes([188, 0x4B, 0x51, 1, 1], anchor, bigEndian(1), [1, 0, 0x41])
        XCTAssertEqual(deliver(noEmoji, expectingCall: false), [])
    }

    // MARK: 0x41, 0x42, 0x43 in a group

    /// Without a profile behind it the group path stops after reading the packet: YES means it took the
    /// bytes for its own kind, NO that it left them for another reader.
    private let groups = OCTSubmanagerGroupsImpl()

    func testAGroupEditFromAndroidIsTakenForAnEdit() {
        let edit = bytes(groupMagic, [0x41], bigEndian(7), bigEndian(1_700_000_000), Array("Исправил".utf8))
        XCTAssertTrue(groups.handleIncomingGroupEditPacket(withGroupNumber: 0, peerId: 1, data: Data(edit)))
        XCTAssertFalse(groups.handleIncomingGroupEditPacket(withGroupNumber: 0, peerId: 1, data: Data(Array(edit.prefix(15)))))
        var foreign = edit
        foreign[0] = 0x67
        XCTAssertFalse(groups.handleIncomingGroupEditPacket(withGroupNumber: 0, peerId: 1, data: Data(foreign)))
        XCTAssertFalse(groups.handleIncomingGroupDeletePacket(withGroupNumber: 0, peerId: 1, data: Data(edit)),
                       "an edit was taken for a delete")
    }

    func testAGroupDeleteFromAndroidIsTakenForATextOrAFile() {
        let text = bytes(groupMagic, [0x42], bigEndian(7), bigEndian(1_700_000_000))
        let file = bytes(groupMagic, [0x42, 0x02], anchor, bigEndian(1_700_000_000))
        XCTAssertEqual(text.count, 16)
        XCTAssertEqual(file.count, 45)
        XCTAssertTrue(groups.handleIncomingGroupDeletePacket(withGroupNumber: 0, peerId: 1, data: Data(text)))
        XCTAssertTrue(groups.handleIncomingGroupDeletePacket(withGroupNumber: 0, peerId: 1, data: Data(file)))
        XCTAssertFalse(groups.handleIncomingGroupDeletePacket(withGroupNumber: 0, peerId: 1, data: Data(bytes(text, [0, 0, 0, 0]))))
    }

    func testAGroupReactionFromAndroidIsTakenForAReaction() {
        let emoji = Array(thumbsUp.utf8)
        let author = [UInt8](repeating: 0xAB, count: 32)
        let reaction = bytes(groupMagic, [0x43, 0x01], anchor, author, bigEndian(1_700_000_000), [1, UInt8(emoji.count)], emoji)
        XCTAssertGreaterThanOrEqual(reaction.count, 80)
        XCTAssertTrue(groups.handleIncomingGroupReactionPacket(withGroupNumber: 0, peerId: 1, data: Data(reaction)))
        XCTAssertFalse(groups.handleIncomingGroupReactionPacket(withGroupNumber: 0, peerId: 1, data: Data(Array(reaction.prefix(79)))))
        var foreign = reaction
        foreign[7] = 0x44
        XCTAssertFalse(groups.handleIncomingGroupReactionPacket(withGroupNumber: 0, peerId: 1, data: Data(foreign)))
    }

    // MARK: A readable stand-in

    func testMarkupThisBuildCannotReadIsShownInWords() {
        let newerReply = "[KQ|r2|ABCDEF12|Amina]\nquote\n[KQ/end]\nСалам"
        XCTAssertEqual(MessageReplyHelper.parse(newerReply).bodyText, "Салам")
        XCTAssertNil(MessageReplyHelper.parse(newerReply).reply)

        XCTAssertEqual(MessageReplyHelper.parse("[KQ|poll|1|2|3]").bodyText, String(localized: "air_message_unknown_format"))
        XCTAssertEqual(GroupMentionHelper.notificationPreviewText("[KQ|poll|1|2|3]"), String(localized: "air_message_unknown_format"),
                       "a notification says the same")
    }

    func testKnownMarkupAndPlainTextAreUntouched() {
        var meta = MessageReplyHelper.ReplyMeta()
        meta.localMessageId = 42
        meta.senderPubkeyTail = "ABCDEF12"
        meta.sortTimestampMs = 1_700_000_000_000
        meta.senderName = "Amina"
        meta.previewText = "quote"
        let reply = MessageReplyHelper.parse(MessageReplyHelper.encode(meta, bodyText: "Салам"))
        XCTAssertEqual(reply.reply?.localMessageId, 42)
        XCTAssertEqual(reply.bodyText, "Салам")

        let mention = GroupMentionHelper.parse("[KQ|m|ABCDEF12|Amina][KQ/m]Салам")
        XCTAssertEqual(mention.bodyText, "Салам")

        XCTAssertEqual(MessageReplyHelper.parse("[KQ is a word here]").bodyText, "[KQ is a word here]")
        XCTAssertEqual(MessageReplyHelper.parse("see [KQ|x] inside").bodyText, "see [KQ|x] inside")
    }
}
