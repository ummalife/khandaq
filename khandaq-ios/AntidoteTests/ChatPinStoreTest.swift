// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import XCTest
@testable import Antidote

/// KHANDAQ (0.2.65, tester feedback): the menu of the pinned message says "Unpin", any other one "Pin".
class ChatPinStoreTest: XCTestCase {
    func testOnlyThePinnedMessageIsPinned() {
        let chatId = "flick-back-test-\(UUID().uuidString)"
        defer { ChatPinStore.clearPin(forChat: chatId) }
        XCTAssertFalse(ChatPinStore.isPinned(messageId: "a", inChat: chatId))
        ChatPinStore.setPinned(messageId: "a", forChat: chatId)
        XCTAssertTrue(ChatPinStore.isPinned(messageId: "a", inChat: chatId))
        XCTAssertFalse(ChatPinStore.isPinned(messageId: "b", inChat: chatId))
        XCTAssertFalse(ChatPinStore.isPinned(messageId: nil, inChat: chatId))
        XCTAssertFalse(ChatPinStore.isPinned(messageId: "a", inChat: chatId + "-other"))
        ChatPinStore.clearPin(forChat: chatId)
        XCTAssertFalse(ChatPinStore.isPinned(messageId: "a", inChat: chatId))
    }
}
