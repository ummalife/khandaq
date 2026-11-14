// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import XCTest
@testable import Antidote

/**
 KHANDAQ (msgV3 receipt shown as "_"): which message objcTox answers with a msgV3 receipt. A message
 from Android or another iPhone carries its 32-byte hash after two zero bytes and gets a receipt that
 names it. A message with no hash, which is every message from the desktop (it sends no msgV3), gets
 none: such a receipt names nothing, went out as a bare "_", and the desktop showed it as a message.
 */
class MsgV3ReceiptTests: XCTestCase {
    /// Stands in for OCTTox: the 1:1 receive path reads its delegate and maps the message type.
    @objc private final class FakeTox: NSObject {
        @objc var delegate: AnyObject?

        @objc(messageTypeFromCMessageType:)
        func messageType(fromCMessageType cType: UInt32) -> OCTToxMessageType {
            return cType == 1 ? .action : .normal
        }
    }

    /// Records what the 1:1 receive path hands over, by the selectors of OCTToxDelegate.
    @objc private final class Sink: NSObject {
        var calls: [String] = []
        var messageArrived: XCTestExpectation?

        @objc(tox:friendMessage:type:friendNumber:msgv3HashHex:sendTimestamp:)
        func message(_ tox: AnyObject, message: String, type: OCTToxMessageType, friendNumber: Int32,
                     hash: String?, sendTimestamp: UInt32) {
            calls.append("message \(friendNumber) \(message) \(hash ?? "no hash")")
            messageArrived?.fulfill()
        }

        @objc(tox:sendFriendHighlevelACK:friendNumber:msgv3HashHex:sendTimestamp:)
        func sendReceipt(_ tox: AnyObject, message: String, friendNumber: Int32, hash: String?,
                         sendTimestamp: UInt32) {
            calls.append("receipt \(friendNumber) \(message) \(hash ?? "no hash") \(sendTimestamp)")
        }
    }

    private let messageHash = [UInt8](0..<32)
    private let messageHashHex = "000102030405060708090A0B0C0D0E0F101112131415161718191A1B1C1D1E1F"
    private let sentAt: UInt32 = 1_700_000_000

    private func bigEndian(_ value: UInt32) -> [UInt8] {
        return [UInt8(value >> 24 & 0xFF), UInt8(value >> 16 & 0xFF), UInt8(value >> 8 & 0xFF), UInt8(value & 0xFF)]
    }

    /// A msgV3 message as Android and iOS send it: the text, two zero bytes, the hash, the send time.
    private func msgV3(_ text: String) -> [UInt8] {
        return Array(text.utf8) + [0, 0] + messageHash + bigEndian(sentAt)
    }

    /// Hands a normal 1:1 message from friend 5 to objcTox and returns what reached the delegate.
    private func deliver(_ message: [UInt8]) -> [String] {
        let sink = Sink()
        sink.messageArrived = expectation(description: "the message reached the delegate")
        let tox = FakeTox()
        tox.delegate = sink
        let userData = Unmanaged.passUnretained(tox).toOpaque()
        message.withUnsafeBufferPointer { bytes in
            friendMessageCallback(nil, 5, 0, bytes.baseAddress, bytes.count, userData)
        }
        // The receive path hops to the main queue, where the receipt follows the message in the same
        // block: once the message is in, a receipt would be too.
        waitForExpectations(timeout: 2)
        return sink.calls
    }

    func testAMessageFromTheDesktopGetsNoReceipt() {
        XCTAssertEqual(deliver(Array("Салам".utf8)), ["message 5 Салам no hash"])

        // Long enough to be read for a hash, and without the two zero bytes in front of one.
        let long = "Салам, это сообщение с компьютера, без msgV3"
        XCTAssertEqual(deliver(Array(long.utf8)), ["message 5 \(long) no hash"])
    }

    func testAMsgV3MessageGetsAReceiptNamingItsHash() {
        XCTAssertEqual(deliver(msgV3("Салам")), [
            "message 5 Салам \(messageHashHex)",
            "receipt 5 _ \(messageHashHex) \(sentAt)",
        ])
    }

    func testNoReceiptGoesOutWithoutAWholeHash() {
        let chats = OCTSubmanagerChatsImpl()
        // Held, so that what was queued can be counted before it runs.
        chats.sendMessageQueue.isSuspended = true
        defer {
            chats.sendMessageQueue.cancelAllOperations()
            chats.sendMessageQueue.isSuspended = false
        }

        chats.tox(nil, sendFriendHighlevelACK: "_", friendNumber: 5, msgv3HashHex: nil, sendTimestamp: sentAt)
        chats.tox(nil, sendFriendHighlevelACK: "_", friendNumber: 5, msgv3HashHex: String(messageHashHex.prefix(63)),
                  sendTimestamp: sentAt)
        XCTAssertEqual(chats.sendMessageQueue.operationCount, 0)

        chats.tox(nil, sendFriendHighlevelACK: "_", friendNumber: 5, msgv3HashHex: messageHashHex, sendTimestamp: sentAt)
        XCTAssertEqual(chats.sendMessageQueue.operationCount, 1)
    }
}
