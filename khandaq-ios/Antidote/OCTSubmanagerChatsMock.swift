// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation

/// The chats of the UI tests' mock profile. Sending stores the message as objcTox does: with the person
/// online it is sent at once and delivered a moment later; offline it waits in transit (message id -1).
/// With the DEMO_RETURN launch argument, a message to the offline demo contact brings them online a few
/// seconds later, so the tray can be seen emptying into the log. With DEMO_CONNECTING, Bilal writes a
/// few seconds after "Connect again" has found the network, so a walk sees the band of a message (E22).
class OCTSubmanagerChatsMock: NSObject, OCTSubmanagerChats {
    private let realm: RLMRealm?
    private let demoReturn = ProcessInfo.processInfo.arguments.contains("DEMO_RETURN")
    private var nextMessageId: OCTToxMessageId = 100
    private var returnScheduled = false
    private var reconnectObserver: NSObjectProtocol?

    init(realm: RLMRealm? = nil) {
        self.realm = realm
        super.init()

        if ProcessInfo.processInfo.arguments.contains("DEMO_CONNECTING") {
            reconnectObserver = NotificationCenter.default.addObserver(forName: .khandaqManualReconnectRequested, object: nil, queue: .main) { [weak self] note in
                guard (note.object as? ReconnectBackoffCoordinator.Reason) == .manual else {
                    return
                }
                DispatchQueue.main.asyncAfter(deadline: .now() + 6) { [weak self] in
                    self?.receiveDemoMessage(from: OCTManagerMock.demoBilalKey, text: OCTManagerMock.storeText("demo_s3_band_message"))
                }
            }
        }
    }

    deinit {
        if let observer = reconnectObserver {
            NotificationCenter.default.removeObserver(observer)
        }
    }

    func tickDeliveryWatchdog() {
        // nop
    }

    func getOrCreateChat(with friend: OCTFriend!) -> OCTChat! {
        if let realm = realm, let friend = friend,
           let chat = OCTChat.objects(in: realm, with: NSPredicate(format: "ANY friends == %@", friend)).firstObject() as? OCTChat {
            return chat
        }
        return OCTChat()
    }

    func sendOwnPush() {
        // nop
    }

    func broadcastOwnPushURLToConnectedFriends() {
        // nop
    }

    func deleteMessage(forBoth message: OCTMessageAbstract!) {
        guard let realm = realm, let message = message, !message.isInvalidated else {
            return
        }
        realm.beginWriteTransaction()
        if let text = message.messageText {
            realm.delete(text)
        }
        realm.delete(message)
        try? realm.commitWriteTransaction()
    }

    func toggleReaction(onMessage message: OCTMessageAbstract!, emoji: String!) {
        // nop
    }

    func removeMessages(_ messages: [OCTMessageAbstract]!) {
        // nop
    }

    func editMessage(_ message: OCTMessageAbstract!, newText: String!) {
        guard let realm = realm, let message = message, !message.isInvalidated, let text = message.messageText else {
            return
        }
        realm.beginWriteTransaction()
        text.text = newText
        message.edited = true
        message.editedTimestamp = Date().timeIntervalSince1970
        try? realm.commitWriteTransaction()
    }

    func removeAllMessages(in chat: OCTChat!, removeChat: Bool) {
        // The demo world forgets a removed contact's conversation, as the real store does.
        guard let realm = realm, let chat = chat, !chat.isInvalidated else {
            return
        }
        let messages = OCTMessageAbstract.objects(in: realm, with: NSPredicate(format: "chatUniqueIdentifier == %@", chat.uniqueIdentifier))
        realm.beginWriteTransaction()
        realm.deleteObjects(messages)
        if removeChat {
            realm.delete(chat)
        }
        try? realm.commitWriteTransaction()
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
        guard let realm = realm, let chat = chat, !chat.isInvalidated, let text = text else {
            return
        }
        let friend = chat.friends.firstObject() as? OCTFriend
        let online = friend?.isConnected ?? false
        let now = Date().timeIntervalSince1970

        let messageText = OCTMessageText()
        messageText.text = text
        messageText.type = type
        messageText.isDelivered = false
        messageText.messageId = online ? takeMessageId() : -1
        messageText.msgv3HashHex = OCTManagerMock.demoMessageHash()

        let message = OCTMessageAbstract()
        message.chatUniqueIdentifier = chat.uniqueIdentifier
        message.dateInterval = now
        message.sortTimestamp = now
        message.tssent = now
        message.messageText = messageText

        realm.beginWriteTransaction()
        realm.add(message)
        chat.lastMessage = message
        chat.lastActivityDateInterval = now
        try? realm.commitWriteTransaction()

        userSuccessBlock?(message)

        let messageId: String = message.uniqueIdentifier
        if online {
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) { [weak self] in
                self?.markDelivered([messageId])
            }
        }
        else if demoReturn, let friend = friend, friend.publicKey == OCTManagerMock.demoYusufKey {
            scheduleReturn(of: friend, chat: chat)
        }
    }

    func setIsTyping(_ isTyping: Bool, in chat: OCTChat!) throws {
        // nop
    }

    /// A message from the demo contact with `key`, arriving now as objcTox stores one.
    private func receiveDemoMessage(from key: String, text: String) {
        guard let realm = realm,
              let friend = OCTFriend.objects(in: realm, with: NSPredicate(format: "publicKey == %@", key)).firstObject() as? OCTFriend,
              let chat = OCTChat.objects(in: realm, with: NSPredicate(format: "ANY friends == %@", friend)).firstObject() as? OCTChat else {
            return
        }
        let now = Date().timeIntervalSince1970

        let messageText = OCTMessageText()
        messageText.text = text
        messageText.isDelivered = true
        messageText.msgv3HashHex = OCTManagerMock.demoMessageHash()

        let message = OCTMessageAbstract()
        message.senderUniqueIdentifier = friend.uniqueIdentifier
        message.chatUniqueIdentifier = chat.uniqueIdentifier
        message.dateInterval = now
        message.sortTimestamp = now
        message.tssent = now
        message.messageText = messageText

        realm.beginWriteTransaction()
        realm.add(message)
        chat.lastMessage = message
        chat.lastActivityDateInterval = now
        try? realm.commitWriteTransaction()
    }

    private func takeMessageId() -> OCTToxMessageId {
        nextMessageId += 1
        return nextMessageId
    }

    private func markDelivered(_ ids: [String]) {
        guard let realm = realm else {
            return
        }
        realm.beginWriteTransaction()
        for id in ids {
            guard let message = OCTMessageAbstract.object(in: realm, forPrimaryKey: id), let text = message.messageText else {
                continue
            }
            if text.messageId < 0 {
                text.messageId = takeMessageId()
            }
            text.isDelivered = true
        }
        try? realm.commitWriteTransaction()
    }

    private func markFilesSent(_ ids: [String]) {
        guard let realm = realm, !ids.isEmpty else {
            return
        }
        realm.beginWriteTransaction()
        for id in ids {
            if let file = OCTMessageAbstract.object(in: realm, forPrimaryKey: id)?.messageFile {
                file.fileType = .ready
                file.isDelivered = true
            }
        }
        try? realm.commitWriteTransaction()
    }

    /// The person comes online four seconds later; objcTox then sends everything that waited (one
    /// check each), and the receipts follow (two checks). A file that waited goes through by then too.
    private func scheduleReturn(of friend: OCTFriend, chat: OCTChat) {
        guard !returnScheduled, let realm = realm else {
            return
        }
        returnScheduled = true
        let chatId: String = chat.uniqueIdentifier
        let friendId: String = friend.uniqueIdentifier
        DispatchQueue.main.asyncAfter(deadline: .now() + 4) { [weak self] in
            guard let self = self, let friend = OCTFriend.object(in: realm, forPrimaryKey: friendId) else {
                return
            }
            realm.beginWriteTransaction()
            friend.connectionStatus = .UDP
            friend.isConnected = true
            friend.lastSeenOnlineInterval = Date().timeIntervalSince1970
            try? realm.commitWriteTransaction()

            let waiting = OCTMessageAbstract.objects(in: realm, with: NSPredicate(
                    format: "chatUniqueIdentifier == %@ AND senderUniqueIdentifier == nil AND messageText != nil AND messageText.isDelivered == NO",
                    chatId))
            var ids: [String] = []
            for index in 0..<waiting.count {
                if let message = waiting.object(at: index) as? OCTMessageAbstract {
                    ids.append(message.uniqueIdentifier)
                }
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) { [weak self] in
                guard let self = self else {
                    return
                }
                realm.beginWriteTransaction()
                for id in ids {
                    if let text = OCTMessageAbstract.object(in: realm, forPrimaryKey: id)?.messageText, text.messageId < 0 {
                        text.messageId = self.takeMessageId()
                    }
                }
                try? realm.commitWriteTransaction()
            }
            let files = OCTMessageAbstract.objects(in: realm, with: NSPredicate(
                    format: "chatUniqueIdentifier == %@ AND senderUniqueIdentifier == nil AND messageFile != nil AND messageFile.fileType == %d",
                    chatId, OCTMessageFileType.waitingConfirmation.rawValue))
            var fileIds: [String] = []
            for index in 0..<files.count {
                if let message = files.object(at: index) as? OCTMessageAbstract {
                    fileIds.append(message.uniqueIdentifier)
                }
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + 2.0) { [weak self] in
                self?.markDelivered(ids)
                self?.markFilesSent(fileIds)
            }
        }
    }
}
