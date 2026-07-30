// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation
    
class OCTSubmanagerChatsMock: NSObject, OCTSubmanagerChats {
    func tickDeliveryWatchdog() {
        // nop
    }

    func getOrCreateChat(with friend: OCTFriend!) -> OCTChat! {
        return OCTChat()
    }

    func sendOwnPush() {
        // nop
    }

    func broadcastOwnPushURLToConnectedFriends() {
        // nop
    }

    func deleteMessage(forBoth message: OCTMessageAbstract!) {
        // nop
    }

    func toggleReaction(onMessage message: OCTMessageAbstract!, emoji: String!) {
        // nop
    }

    func removeMessages(_ messages: [OCTMessageAbstract]!) {
        // nop
    }

    func editMessage(_ message: OCTMessageAbstract!, newText: String!) {
        // nop
    }
    
    func removeAllMessages(in chat: OCTChat!, removeChat: Bool) {
        // nop
    }

    public func sendMessagePush(to chat: OCTChat!)
    {
       // nop
    }

    func triggerWakePush(for chat: OCTChat!) {
        // nop
    }

    public func sendMessage(to chat: OCTChat!,
            text: String!,
            type: OCTToxMessageType,
            successBlock userSuccessBlock: ((OCTMessageAbstract?) -> Void)!,
            failureBlock userFailureBlock: ((Error?) -> Void)!) {
        // nop
    }
    
    func setIsTyping(_ isTyping: Bool, in chat: OCTChat!) throws {
        // nop
    }
}
