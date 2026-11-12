// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation
import MobileCoreServices

private enum Gender {
    case male
    case female
}

class OCTManagerMock: NSObject, OCTManager {
    var bootstrap: OCTSubmanagerBootstrap
    var calls: OCTSubmanagerCalls
    var chats: OCTSubmanagerChats
    var files: OCTSubmanagerFiles
    var friends: OCTSubmanagerFriends
    var groups: OCTSubmanagerGroups
    var objects: OCTSubmanagerObjects
    var user: OCTSubmanagerUser

    var realm: RLMRealm

    override init() {
        let configuration = RLMRealmConfiguration.default()
        configuration.inMemoryIdentifier = "test realm"
        realm = try! RLMRealm(configuration: configuration)

        bootstrap = OCTSubmanagerBootstrapMock()
        calls = OCTSubmanagerCallsMock()
        chats = OCTSubmanagerChatsMock(realm: realm)
        files = OCTSubmanagerFilesMock()
        let friendsMock = OCTSubmanagerFriendsMock()
        friendsMock.realm = realm
        friends = friendsMock
        let groupsMock = OCTSubmanagerGroupsMock()
        groupsMock.realm = realm
        groups = groupsMock
        objects = OCTSubmanagerObjectsMock(realm: realm)
        user = OCTSubmanagerUserMock()

        super.init()

        // The store and site captures (stage 5) show the demo people of the redesign only, without the
        // screenshot contacts Antidote's mock came with.
        if !ProcessInfo.processInfo.arguments.contains("DEMO_OURS_ONLY") {
            populateRealm()
        }
        populateStageTwoDemo()
        populateStageThreeDemo()
        populateStageThreeMedia()
        if ProcessInfo.processInfo.arguments.contains("DEMO_STORE") {
            thinForStoreFrames()
        }
    }
    
    func configuration() -> OCTManagerConfiguration {
        return OCTManagerConfiguration()
    }
    
    func exportToxSaveFile() throws -> String {
        return "123"
    }
    
    func changeEncryptPassword(_ newPassword: String, oldPassword: String) -> Bool {
        return true
    }
    
    func isManagerEncrypted(withPassword password: String) -> Bool {
        return true
    }
}

private extension OCTManagerMock {
    func populateRealm() {
        realm.beginWriteTransaction()

        let f1 = addFriend(gender: .female, number: 1, connectionStatus: .TCP, status: .none)
        let f2 = addFriend(gender: .male, number: 1, connectionStatus: .TCP, status: .busy)
        let f3 = addFriend(gender: .female, number: 2, connectionStatus: .none, status: .none)
        let f4 = addFriend(gender: .male, number: 2, connectionStatus: .TCP, status: .away)
        let f5 = addFriend(gender: .male, number: 3, connectionStatus: .TCP, status: .none)
        let f6 = addFriend(gender: .female, number: 3, connectionStatus: .TCP, status: .away)
        let f7 = addFriend(gender: .male, number: 4, connectionStatus: .TCP, status: .away)
        let f8 = addFriend(gender: .female, number: 4, connectionStatus: .none, status: .none)
        let f9 = addFriend(gender: .female, number: 5, connectionStatus: .TCP, status: .none)
        let f10 = addFriend(gender: .male, number: 5, connectionStatus: .none, status: .none)

        let c1 = addChat(friend: f1)
        let c2 = addChat(friend: f2)
        let c3 = addChat(friend: f3)
        let c4 = addChat(friend: f4)
        let c5 = addChat(friend: f5)
        let c6 = addChat(friend: f6)
        let c7 = addChat(friend: f7)
        let c8 = addChat(friend: f8)
        let c9 = addChat(friend: f9)
        let c10 = addChat(friend: f10)

        addDemoConversationToChat(c1)
        addCallMessage(chat: c2, outgoing: false, answered: false, duration: 0.0)
        addTextMessage(chat: c3, outgoing: false, text: OCTManagerMock.storeText("app_store_screenshot_chat_message_1"))
        addCallMessage(chat: c4, outgoing: true, answered: true, duration: 1473.0)
        addTextMessage(chat: c5, outgoing: false, text: OCTManagerMock.storeText("app_store_screenshot_chat_message_2"))
        addFileMessage(chat: c6, outgoing: false, fileName: "party.png")
        addTextMessage(chat: c7, outgoing: true, text: OCTManagerMock.storeText("app_store_screenshot_chat_message_3"))
        addTextMessage(chat: c8, outgoing: true, text: OCTManagerMock.storeText("app_store_screenshot_chat_message_4"))
        addFileMessage(chat: c9, outgoing: true, fileName: "presentation_2016.pdf")
        addTextMessage(chat: c10, outgoing: false, text: OCTManagerMock.storeText("app_store_screenshot_chat_message_5"))

        c1.lastReadDateInterval = Date().timeIntervalSince1970
        // unread message
        // c2.lastReadDateInterval = NSDate().timeIntervalSince1970
        c3.lastReadDateInterval = Date().timeIntervalSince1970
        c4.lastReadDateInterval = Date().timeIntervalSince1970
        c5.lastReadDateInterval = Date().timeIntervalSince1970
        c6.lastReadDateInterval = Date().timeIntervalSince1970
        c7.lastReadDateInterval = Date().timeIntervalSince1970
        c8.lastReadDateInterval = Date().timeIntervalSince1970
        c9.lastReadDateInterval = Date().timeIntervalSince1970
        c10.lastReadDateInterval = Date().timeIntervalSince1970

        try! realm.commitWriteTransaction()
    }

    func addFriend(gender: Gender,
                   number: Int,
                   connectionStatus: OCTToxConnectionStatus,
                   status: OCTToxUserStatus) -> OCTFriend {
        let friend = OCTFriend()
        // A key of its own for each contact: lists that choose contacts go by key, as with real ones.
        friend.publicKey = String(format: "%064lX", (gender == .male ? 0xC100 : 0xC200) + number)
        friend.connectionStatus = connectionStatus
        friend.isConnected = connectionStatus != .none
        friend.status = status

        switch gender {
            case .male:
                friend.nickname = OCTManagerMock.storeText("app_store_screenshot_friend_male_\(number)")
                friend.avatarData = UIImagePNGRepresentation(UIImage(named: "male-\(number)")!)
            case .female:
                friend.nickname = OCTManagerMock.storeText("app_store_screenshot_friend_female_\(number)")
                friend.avatarData = UIImagePNGRepresentation(UIImage(named: "female-\(number)")!)
        }
        
        realm.add(friend)

        return friend
    }

    func addChat(friend: OCTFriend) -> OCTChat {
        let chat = OCTChat()

        realm.add(chat)
        chat.friends.add(friend)

        return chat
    }

    func addDemoConversationToChat(_ chat: OCTChat) {
        addFileMessage(chat: chat, outgoing: false, fileName: "party.png")
        addTextMessage(chat: chat, outgoing: true, text: OCTManagerMock.storeText("app_store_screenshot_conversation_1"))
        addTextMessage(chat: chat, outgoing: false, text: OCTManagerMock.storeText("app_store_screenshot_conversation_2"))
        addTextMessage(chat: chat, outgoing: true, text: OCTManagerMock.storeText("app_store_screenshot_conversation_3"))
        addTextMessage(chat: chat, outgoing: false, text: OCTManagerMock.storeText("app_store_screenshot_conversation_4"))
        addTextMessage(chat: chat, outgoing: false, text: OCTManagerMock.storeText("app_store_screenshot_conversation_5"))
        addTextMessage(chat: chat, outgoing: true, text: OCTManagerMock.storeText("app_store_screenshot_conversation_6"))
        addTextMessage(chat: chat, outgoing: true, text: OCTManagerMock.storeText("app_store_screenshot_conversation_7"))
    }

    func addTextMessage(chat: OCTChat, outgoing: Bool, text: String) {
        let messageText = OCTMessageText()
        messageText.text = text
        messageText.isDelivered = outgoing

        let message = addMessageAbstract(chat: chat, outgoing: outgoing)
        message.messageText = messageText
    }

    func addFileMessage(chat: OCTChat, outgoing: Bool, fileName: String) {
        let messageFile = OCTMessageFile()
        messageFile.fileName = fileName
        messageFile.internalFilePath = Bundle.main.path(forResource: "dummy-photo", ofType: "jpg")
        messageFile.fileType = .ready
        messageFile.fileUTI = kUTTypeImage as String

        let message = addMessageAbstract(chat: chat, outgoing: outgoing)
        message.messageFile = messageFile
    }

    func addCallMessage(chat: OCTChat, outgoing: Bool, answered: Bool, duration: TimeInterval) {
        let messageCall = OCTMessageCall()
        messageCall.callDuration = duration
        messageCall.callEvent = answered ? .answered : .unanswered

        let message = addMessageAbstract(chat: chat, outgoing: outgoing)
        message.messageCall = messageCall
    }

    func addMessageAbstract(chat: OCTChat, outgoing: Bool) -> OCTMessageAbstract {
        let message = OCTMessageAbstract()
        if !outgoing {
            let friend = chat.friends.firstObject() as! OCTFriend
            message.senderUniqueIdentifier = friend.uniqueIdentifier
        }
        message.chatUniqueIdentifier = chat.uniqueIdentifier
        message.dateInterval = Date().timeIntervalSince1970

        realm.add(message)
        chat.lastMessage = message

        return message
    }
}

// MARK: - Stage 2 demo world

/// The demo people of design/redesign/02-plan.md, section 6, for the conversation screen: a contact
/// online directly and one through a relay, one offline with three messages in transit, a very long
/// name, a contact who has not answered the request, a 12 MB file, a group you founded and one you only
/// read, notes "For yourself", and with the DEMO_BULK launch argument 200 more chats. The texts are in
/// AppStoreLocalizable.strings, in the app language.
extension OCTManagerMock {
    static let demoAminaKey = "7F3A91C05D2E8B4416A9C3E07D5F2B8164E0A3C9D7B25F1E8A4C6D0B3F9E2A71"
    static let demoYusufKey = "2C8E5F1A9B3D7046E2A8C5F19D3B7E604A2F8C1D5E9B3A7046C2E8F1A5D9B3E7"
    static let demoBilalKey = "9D1B7E3F5A2C8046B9E1D7F3A5C2084E6B1D9F7A3C5E2048D6B9F1E7A3C5D208"
    static let demoLongKey = "4E6A2C8F0B5D7193E4A6C8F2B0D5E7193A4C6E8F2D0B5A7193E6C4A8F2B0D5E7"
    static let demoLeilaKey = "B5D3F1A7C9E2064B8D5F3A1C7E9B2064D8F5A3C1E7B9D2046F8A5C3E1B7D9F20"
    static let demoOwnAddress = "E5D3A1F7C9B2064D8F5A3C1E7B9D2046F8A5C3E1B7D9F20B5D3F1A7C9E2064B81C0FFEE542E3"
    /// The antispam inside `demoOwnAddress`.
    static let demoOwnAntispam: OCTToxNoSpam = 0x1C0FFEE5
    static let demoFounderGroupNumber: Int32 = 101
    static let demoObserverGroupNumber: Int32 = 102
    static let demoRequestKey = "0142EB6ABBDC1C2A417431B4E940E0F48C7C31073CF8606F628A331619493721"
    static let demoQuietRequestKey = "FA2A8767135B6308EB2300C9AFDBC89DB7E47E9D8A6F11B37CE6F499DD9BB7C6"
    /// Your key in the demo groups (NGC gives you a key per group; one is enough for the demo).
    static let demoSelfGroupKey = "5E1F7A33C0D94B28A6E1F09C3D7B52E4A8C61F0D93B27E5A4C08F16D2B93E7A5"

    /// A demo text in the app language, or in English when that language has no such text.
    static func storeText(_ key: String) -> String {
        let missing = "#missing#"
        let text = Bundle.main.localizedString(forKey: key, value: missing, table: "AppStoreLocalizable")
        if text != missing {
            return text
        }
        if let path = Bundle.main.path(forResource: "en", ofType: "lproj"), let english = Bundle(path: path) {
            let fallback = english.localizedString(forKey: key, value: missing, table: "AppStoreLocalizable")
            if fallback != missing {
                return fallback
            }
        }
        return key
    }

    /// A message id as toxcore's msgV3 has it: 64 hex characters.
    static func demoMessageHash() -> String {
        return (UUID().uuidString + UUID().uuidString).replacingOccurrences(of: "-", with: "")
    }

    fileprivate func populateStageTwoDemo() {
        let now = Date()
        let calendar = Calendar.current
        let today = calendar.startOfDay(for: now)
        let yesterday = calendar.date(byAdding: .day, value: -1, to: today) ?? today.addingTimeInterval(-86400)
        let t = OCTManagerMock.storeText

        func at(_ day: Date, _ hour: Int, _ minute: Int) -> TimeInterval {
            return (calendar.date(bySettingHour: hour, minute: minute, second: 0, of: day) ?? day).timeIntervalSince1970
        }
        func ago(_ seconds: TimeInterval) -> TimeInterval {
            return now.timeIntervalSince1970 - seconds
        }

        // Your own ID in the demo: a real-looking one, so New contact and the profile show its groups.
        if let user = user as? OCTSubmanagerUserMock {
            user.userAddress = OCTManagerMock.demoOwnAddress
            user.publicKey = String(OCTManagerMock.demoOwnAddress.prefix(64))
            user.nospam = OCTManagerMock.demoOwnAntispam
        }

        realm.beginWriteTransaction()

        let amina = addDemoFriend(name: t("demo_s2_amina"), key: OCTManagerMock.demoAminaKey, connection: .UDP, lastSeen: ago(0))
        amina.statusMessage = t("demo_s2_amina_status")
        let aminaChat = addChat(friend: amina)
        addDemoText(chat: aminaChat, sender: amina, text: t("demo_s2_amina_1"), date: at(yesterday, 19, 40))
        addDemoText(chat: aminaChat, sender: nil, text: t("demo_s2_amina_2"), date: at(yesterday, 19, 42))
        addDemoText(chat: aminaChat, sender: amina, text: t("demo_s2_amina_3"), date: ago(125 * 60))
        addDemoText(chat: aminaChat, sender: amina, text: t("demo_s2_amina_4"), date: ago(124 * 60))
        addDemoText(chat: aminaChat, sender: nil, text: t("demo_s2_amina_5"), date: ago(118 * 60))
        addDemoText(chat: aminaChat, sender: nil, text: t("demo_s2_amina_6"), date: ago(117 * 60))
        addDemoText(chat: aminaChat, sender: amina, text: t("demo_s2_amina_7"), date: ago(35 * 60))
        // Sent, its receipt not back yet: one check.
        addDemoText(chat: aminaChat, sender: nil, text: t("demo_s2_amina_8"), date: ago(3 * 60), messageId: 5, delivered: false)
        aminaChat.lastReadDateInterval = now.timeIntervalSince1970

        let yusuf = addDemoFriend(name: t("demo_s2_yusuf"), key: OCTManagerMock.demoYusufKey, connection: .none, lastSeen: ago(25 * 60))
        yusuf.pushToken = "demo"
        let yusufChat = addChat(friend: yusuf)
        addDemoText(chat: yusufChat, sender: yusuf, text: t("demo_s2_yusuf_1"), date: at(yesterday, 21, 10))
        addDemoText(chat: yusufChat, sender: nil, text: t("demo_s2_yusuf_2"), date: at(yesterday, 21, 12))
        // Written while Yusuf was offline: in transit.
        addDemoText(chat: yusufChat, sender: nil, text: t("demo_s2_yusuf_3"), date: ago(12 * 60), messageId: -1, delivered: false)
        addDemoText(chat: yusufChat, sender: nil, text: t("demo_s2_yusuf_4"), date: ago(11 * 60), messageId: -1, delivered: false)
        addDemoText(chat: yusufChat, sender: nil, text: t("demo_s2_yusuf_5"), date: ago(6 * 60), messageId: -1, delivered: false)
        yusufChat.lastReadDateInterval = now.timeIntervalSince1970

        let bilal = addDemoFriend(name: t("demo_s2_bilal"), key: OCTManagerMock.demoBilalKey, connection: .TCP, lastSeen: ago(0))
        let bilalChat = addChat(friend: bilal)
        addDemoText(chat: bilalChat, sender: bilal, text: t("demo_s2_bilal_1"), date: ago(50 * 60))
        addDemoFile(chat: bilalChat, sender: bilal, fileName: "route-plan.pdf", size: 12 * 1024 * 1024, date: ago(49 * 60))
        addDemoText(chat: bilalChat, sender: nil, text: t("demo_s2_bilal_2"), date: ago(45 * 60))
        bilalChat.lastReadDateInterval = now.timeIntervalSince1970

        let long = addDemoFriend(name: t("demo_s2_long"), key: OCTManagerMock.demoLongKey, connection: .none, lastSeen: ago(8 * 86400))
        let longChat = addChat(friend: long)
        addDemoText(chat: longChat, sender: long, text: t("demo_s2_long_1"), date: ago(9 * 86400))
        longChat.lastReadDateInterval = now.timeIntervalSince1970

        // Added by ID, the request not answered yet: never seen online, no conversation.
        _ = addDemoFriend(name: t("demo_s2_leila"), key: OCTManagerMock.demoLeilaKey, connection: .none, lastSeen: 0)

        let founderGroup = addDemoGroup(name: t("demo_s2_group_founder"), number: OCTManagerMock.demoFounderGroupNumber, peers: 9)
        addDemoGroupText(chat: founderGroup, peerId: 3, peerName: t("demo_s2_amina"), text: t("demo_s2_group_1"), date: ago(25 * 60))
        let observerGroup = addDemoGroup(name: t("demo_s2_group_observer"), number: OCTManagerMock.demoObserverGroupNumber, peers: 24)
        addDemoGroupText(chat: observerGroup, peerId: 1, peerName: t("demo_s2_group_peer_imam"), text: t("demo_s2_group_2"), date: ago(3 * 3600))

        let saved = OCTChat()
        saved.isSavedMessages = true
        realm.add(saved)
        addDemoText(chat: saved, sender: nil, text: t("demo_s2_saved_1"), date: at(yesterday, 8, 0))
        addDemoText(chat: saved, sender: nil, text: t("demo_s2_saved_2"), date: ago(20 * 60))
        saved.lastReadDateInterval = now.timeIntervalSince1970

        if ProcessInfo.processInfo.arguments.contains("DEMO_BULK") {
            for index in 1...200 {
                let key = String(format: "%064d", index)
                let friend = addDemoFriend(name: String(format: t("demo_s2_bulk_format"), index), key: key,
                                           connection: index % 3 == 0 ? .UDP : .none, lastSeen: ago(Double(index) * 3600))
                let chat = addChat(friend: friend)
                if index == 1, ProcessInfo.processInfo.arguments.contains("DEMO_LONG_THREAD") {
                    // Thousands of messages in one conversation, for the performance numbers (stage 5):
                    // every third one ours, ten minutes apart, before the last one.
                    let texts = [t("demo_s2_bulk_message"), t("demo_s2_yusuf_3"), t("demo_s2_saved_1"), t("demo_s2_saved_2")]
                    for number in 0..<3000 {
                        addDemoText(chat: chat, sender: number % 3 == 0 ? nil : friend, text: texts[number % texts.count],
                                    date: ago(Double(3000 - number) * 600 + 3700))
                    }
                }
                addDemoText(chat: chat, sender: friend, text: t("demo_s2_bulk_message"), date: ago(Double(index) * 3700))
                chat.lastReadDateInterval = now.timeIntervalSince1970
            }
        }

        try! realm.commitWriteTransaction()

        // What this phone has seen of them: Amina online today since 2 h 10 min ago and in the evenings
        // of the last five days, Yusuf this morning, Bilal for the last 40 minutes.
        var aminaHours: [Date] = []
        for minutes in stride(from: 0, through: 130, by: 60) {
            aminaHours.append(now.addingTimeInterval(-Double(minutes) * 60))
        }
        aminaHours.append(now.addingTimeInterval(-130 * 60))
        for day in 1...5 {
            let base = calendar.date(byAdding: .day, value: -day, to: today) ?? today
            for hour in 19...21 {
                aminaHours.append(Date(timeIntervalSince1970: at(base, hour, 10)))
            }
        }
        var yusufHours: [Date] = [now.addingTimeInterval(-25 * 60 - 3600), now.addingTimeInterval(-25 * 60)]
        if let base = calendar.date(byAdding: .day, value: -1, to: today) {
            yusufHours.append(Date(timeIntervalSince1970: at(base, 21, 5)))
        }
        AirLocalStore.shared.seedDemo(hours: [OCTManagerMock.demoAminaKey: aminaHours,
                                              OCTManagerMock.demoYusufKey: yusufHours,
                                              OCTManagerMock.demoBilalKey: [now, now.addingTimeInterval(-40 * 60)]],
                                      onlineSince: [OCTManagerMock.demoAminaKey: now.addingTimeInterval(-130 * 60),
                                                    OCTManagerMock.demoBilalKey: now.addingTimeInterval(-40 * 60)],
                                      muted: [],
                                      verified: [OCTManagerMock.demoAminaKey: now.addingTimeInterval(-3 * 86400)])
        // One message the network refused, with its "Retry".
        AirFailedSends.shared.add(text: t("demo_s2_bilal_failed"), date: now.addingTimeInterval(-2 * 60), toChat: bilalChat.uniqueIdentifier)
    }

    /// Stage 3: two requests (one with a message, one without) and two group invitations, from Amina
    /// (online) and from Yusuf (offline, so joining has to wait for him).
    fileprivate func populateStageThreeDemo() {
        let now = Date().timeIntervalSince1970
        let t = OCTManagerMock.storeText

        realm.beginWriteTransaction()
        let request = OCTFriendRequest()
        request.publicKey = OCTManagerMock.demoRequestKey
        request.message = t("demo_s3_request_1")
        request.dateInterval = now - 40 * 60
        realm.add(request)
        let quiet = OCTFriendRequest()
        quiet.publicKey = OCTManagerMock.demoQuietRequestKey
        quiet.message = nil
        quiet.dateInterval = now - 5 * 3600
        realm.add(quiet)
        try! realm.commitWriteTransaction()

        AirGroupInvites.shared.add(inviterKey: OCTManagerMock.demoAminaKey, groupName: t("demo_s3_invite_book"),
                                   data: Data(repeating: 1, count: 32), date: Date(timeIntervalSince1970: now - 15 * 60))
        AirGroupInvites.shared.add(inviterKey: OCTManagerMock.demoYusufKey, groupName: t("demo_s3_invite_family"),
                                   data: Data(repeating: 2, count: 32), date: Date(timeIntervalSince1970: now - 3 * 3600))

        populateStageThreeGroups(now: now)

        // "You" (E17): your name and status line as your contacts see them.
        _ = try? user.setUserName(t("demo_s3_self"))
        _ = try? user.setUserStatusMessage(t("demo_s3_self_status"))
    }

    /// The members of the two demo groups with their roles, who of them is online now, and a few more
    /// messages: in the group you founded, 9 members with 4 online and a mention of you; in the one you
    /// only read, 24 members with 7 online.
    private func populateStageThreeGroups(now: TimeInterval) {
        let t = OCTManagerMock.storeText
        guard let groups = groups as? OCTSubmanagerGroupsMock,
              let founder = demoGroup(OCTManagerMock.demoFounderGroupNumber),
              let observer = demoGroup(OCTManagerMock.demoObserverGroupNumber) else {
            return
        }
        let me = t("demo_s3_self")
        groups.selfName = me
        groups.selfRoles[OCTManagerMock.demoFounderGroupNumber] = .founder
        groups.selfRoles[OCTManagerMock.demoObserverGroupNumber] = .observer
        let selfId = Int32(OCTSubmanagerGroupsMock.selfPeerId)

        realm.beginWriteTransaction()

        // The group you founded, by invitation only. Peer 3 is Amina, as in the message of stage 2; Amina,
        // Bilal and Yusuf are your contacts too (the dossier offers their chats), the others are not.
        founder.groupPrivacyState = Int32(OCTToxGroupPrivacyState.private.rawValue)
        addDemoPeer(founder, selfId, me, .founder, lastSeen: now, key: OCTManagerMock.demoSelfGroupKey)
        addDemoPeer(founder, 3, t("demo_s2_amina"), .moderator, lastSeen: now, key: OCTManagerMock.demoAminaKey)
        addDemoPeer(founder, 4, t("demo_s2_bilal"), .user, lastSeen: now, key: OCTManagerMock.demoBilalKey)
        addDemoPeer(founder, 5, t("demo_s3_peer_khadija"), .user, lastSeen: now)
        addDemoPeer(founder, 6, t("demo_s3_peer_omar"), .user, lastSeen: now)
        addDemoPeer(founder, 7, t("demo_s2_yusuf"), .user, lastSeen: now - 25 * 60, key: OCTManagerMock.demoYusufKey)
        addDemoPeer(founder, 8, t("demo_s3_peer_zaynab"), .user, lastSeen: now - 3 * 3600)
        addDemoPeer(founder, 9, t("demo_s3_peer_hamza"), .user, lastSeen: 0)
        addDemoPeer(founder, 10, t("demo_s3_peer_ibrahim"), .observer, lastSeen: now - 2 * 86400)
        groups.onlinePeers[OCTManagerMock.demoFounderGroupNumber] = [selfId, 3, 4, 5, 6]

        addDemoGroupText(chat: founder, peerId: 4, peerName: t("demo_s2_bilal"), text: t("demo_s3_group_bilal"), date: now - 22 * 60)
        addDemoGroupText(chat: founder, peerId: 6, peerName: t("demo_s3_peer_omar"), text: t("demo_s3_group_omar"), date: now - 20 * 60)
        // Khadija asks you by name: the row is marked as a mention of you.
        let handle = AirTranscript.shortName(me)
        let mention = GroupMentionHelper.mentionBlockPrefix + OCTManagerMock.demoSelfGroupKey + ":" + handle
            + GroupMentionHelper.mentionBlockSuffix + String(format: t("demo_s3_group_khadija_format"), handle)
        addDemoGroupText(chat: founder, peerId: 5, peerName: t("demo_s3_peer_khadija"), text: mention, date: now - 12 * 60)
        // Your answer: peer id 0 is how a group row says it is yours.
        addDemoGroupText(chat: founder, peerId: 0, peerName: "", text: t("demo_s3_group_self"), date: now - 10 * 60)
        founder.lastReadDateInterval = now

        // The group where you only read. Peer 1 is the imam, as in the message of stage 2.
        addDemoPeer(observer, selfId, me, .observer, lastSeen: now, key: OCTManagerMock.demoSelfGroupKey)
        addDemoPeer(observer, 1, t("demo_s2_group_peer_imam"), .founder, lastSeen: now)
        addDemoPeer(observer, 2, t("demo_s3_peer_hamza"), .moderator, lastSeen: now)
        addDemoPeer(observer, 3, t("demo_s3_peer_maryam"), .moderator, lastSeen: now - 2 * 3600)
        var online: Set<Int32> = [selfId, 1, 2]
        for index in 4...23 {
            let seen = index <= 8 ? now : now - Double(index) * 3600
            addDemoPeer(observer, Int32(index), String(format: t("demo_s3_listener_format"), index - 3), .user, lastSeen: seen)
            if index <= 8 {
                online.insert(Int32(index))
            }
        }
        groups.onlinePeers[OCTManagerMock.demoObserverGroupNumber] = online
        addDemoGroupText(chat: observer, peerId: 2, peerName: t("demo_s3_peer_hamza"), text: t("demo_s3_group_hamza"), date: now - 90 * 60)
        observer.lastReadDateInterval = now
        // The moderator answers in the private messages of the group what you asked him after the lesson:
        // an observer reads them but cannot write there, toxcore refuses an observer's private messages.
        addDemoPrivateText(chat: observer, peerId: 2, text: t("demo_s5_private_hamza"), outgoing: false, date: now - 46 * 60)

        try! realm.commitWriteTransaction()
    }

    /// A private message of a group with one member, stored as objcTox stores one.
    private func addDemoPrivateText(chat: OCTChat, peerId: Int32, text: String, outgoing: Bool, date: TimeInterval) {
        let peers = OCTGroupPeer.objects(in: realm, with: NSPredicate(format: "chatUniqueIdentifier == %@ AND peerId == %d",
                                                                     chat.uniqueIdentifier, peerId))
        let key = (peers.firstObject() as? OCTGroupPeer)?.peerPublicKeyHex?.lowercased()
        let messageText = OCTMessageText()
        messageText.text = text
        messageText.isDelivered = outgoing
        let message = OCTMessageAbstract()
        message.chatUniqueIdentifier = chat.uniqueIdentifier
        message.dateInterval = date
        message.tssent = date
        message.groupSenderPeerId = outgoing ? 0 : peerId
        message.groupPrivateMessage = true
        message.groupPrivatePeerId = peerId
        message.groupPrivatePeerPubkey = key
        message.messageText = messageText
        realm.add(message)
    }

    private func demoGroup(_ number: Int32) -> OCTChat? {
        let found = OCTChat.objects(in: realm, with: NSPredicate(format: "isGroup == YES AND groupNumber == %d", number))
        return found.count > 0 ? found.firstObject() as? OCTChat : nil
    }

    private func addDemoPeer(_ chat: OCTChat, _ peerId: Int32, _ name: String, _ role: OCTToxGroupRole,
                             lastSeen: TimeInterval, key: String? = nil) {
        let peer = OCTGroupPeer()
        peer.chatUniqueIdentifier = chat.uniqueIdentifier
        peer.peerId = peerId
        peer.peerName = name
        peer.peerRole = Int32(role.rawValue)
        // Per group, as NGC keys are: the same peer number in two groups is two people.
        peer.peerPublicKeyHex = key ?? String(format: "%064lX", 0xA000 + Int(chat.groupNumber) * 0x100 + Int(peerId))
        peer.peerLastSeenDateInterval = lastSeen
        realm.add(peer)
    }

    private func addDemoFriend(name: String, key: String, connection: OCTToxConnectionStatus, lastSeen: TimeInterval) -> OCTFriend {
        let friend = OCTFriend()
        friend.publicKey = key
        friend.nickname = name
        friend.name = name
        friend.connectionStatus = connection
        friend.isConnected = connection != .none
        friend.status = .none
        friend.lastSeenOnlineInterval = lastSeen
        friend.msgv3Capability = true
        realm.add(friend)
        return friend
    }

    @discardableResult
    private func addDemoText(chat: OCTChat,
                             sender: OCTFriend?,
                             text: String,
                             date: TimeInterval,
                             messageId: OCTToxMessageId = 1,
                             delivered: Bool = true) -> OCTMessageAbstract {
        let messageText = OCTMessageText()
        messageText.text = text
        messageText.isDelivered = sender != nil || delivered
        messageText.messageId = sender != nil ? 0 : messageId
        messageText.msgv3HashHex = OCTManagerMock.demoMessageHash()

        let message = addDemoMessage(chat: chat, sender: sender, date: date)
        message.messageText = messageText
        return message
    }

    private func addDemoFile(chat: OCTChat, sender: OCTFriend?, fileName: String, size: OCTToxFileSize, date: TimeInterval) {
        let messageFile = OCTMessageFile()
        messageFile.fileName = fileName
        messageFile.fileSize = size
        messageFile.fileType = .waitingConfirmation
        messageFile.fileUTI = kUTTypePDF as String

        let message = addDemoMessage(chat: chat, sender: sender, date: date)
        message.messageFile = messageFile
    }

    private func addDemoMessage(chat: OCTChat, sender: OCTFriend?, date: TimeInterval) -> OCTMessageAbstract {
        let message = OCTMessageAbstract()
        message.senderUniqueIdentifier = sender?.uniqueIdentifier
        message.chatUniqueIdentifier = chat.uniqueIdentifier
        message.dateInterval = date
        message.sortTimestamp = date
        message.tssent = date
        realm.add(message)
        if date >= chat.lastActivityDateInterval {
            chat.lastMessage = message
            chat.lastActivityDateInterval = date
        }
        return message
    }

    private func addDemoGroup(name: String, number: Int32, peers: Int32) -> OCTChat {
        let chat = OCTChat()
        chat.isGroup = true
        chat.groupNumber = number
        chat.groupName = name
        chat.groupPeerCount = peers
        chat.groupChatIdHex = OCTManagerMock.demoMessageHash()
        realm.add(chat)
        chat.lastReadDateInterval = Date().timeIntervalSince1970
        return chat
    }

    private func addDemoGroupText(chat: OCTChat, peerId: UInt32, peerName: String, text: String, date: TimeInterval) {
        let messageText = OCTMessageText()
        messageText.text = text
        messageText.groupPeerName = peerName
        messageText.isDelivered = true
        if peerId != 0 {
            // The sender's key in the group, as a received row stores it: their tone follows it.
            let peers = OCTGroupPeer.objects(in: realm, with: NSPredicate(format: "chatUniqueIdentifier == %@ AND peerId == %d",
                                                                         chat.uniqueIdentifier, Int32(bitPattern: peerId)))
            if peers.count > 0, let peer = peers.firstObject() as? OCTGroupPeer {
                messageText.groupSenderPubkey = peer.peerPublicKeyHex
            }
        }

        let message = addDemoMessage(chat: chat, sender: nil, date: date)
        message.groupSenderPeerId = Int32(bitPattern: peerId)
        message.groupSyncDedupTimestamp = date
        message.messageText = messageText
    }
}

// MARK: - Stage 3 demo: files, voice and calls

/// Files, a voice note and calls in the conversations of the demo world (design/redesign/02-plan.md,
/// E8 and E9): Amina's answered call yesterday and a voice note from her today; our photo with a
/// caption for Bilal and a ticket on its way from him; for Yusuf, who is offline, our call nobody
/// answered and an archive waiting in "In transit". None of them is newer than the last message of its
/// conversation, so the Air rows read as before.
extension OCTManagerMock {
    fileprivate func populateStageThreeMedia() {
        guard let amina = demoConversation(OCTManagerMock.demoAminaKey),
              let bilal = demoConversation(OCTManagerMock.demoBilalKey),
              let yusuf = demoConversation(OCTManagerMock.demoYusufKey) else {
            return
        }
        let now = Date()
        let calendar = Calendar.current
        let today = calendar.startOfDay(for: now)
        let yesterday = calendar.date(byAdding: .day, value: -1, to: today) ?? today.addingTimeInterval(-86400)

        func yesterdayAt(_ hour: Int, _ minute: Int) -> TimeInterval {
            return (calendar.date(bySettingHour: hour, minute: minute, second: 0, of: yesterday) ?? yesterday).timeIntervalSince1970
        }
        func minutesAgo(_ minutes: Double) -> TimeInterval {
            return now.timeIntervalSince1970 - minutes * 60
        }

        realm.beginWriteTransaction()

        addDemoCall(chat: amina.chat, sender: amina.friend, answered: true, duration: 725, date: yesterdayAt(20, 0))
        // A voice note as Android and iOS name them; the sound is a short tone of the app, so the
        // player has a real length to show.
        let voicePath = Bundle.main.path(forResource: "isotoxin_Calltone", ofType: "aac")
        addDemoFile(chat: amina.chat, sender: amina.friend, fileName: "voice_7d41c2a9.file.m4a",
                    size: demoFileSize(at: voicePath, fallback: 60_757), date: minutesAgo(34),
                    type: .ready, uti: kUTTypeMPEG4Audio as String, path: voicePath)

        // Our photo with its caption: the caption is sent right after the file and flagged as its caption.
        let photoPath = Bundle.main.path(forResource: "dummy-photo", ofType: "jpg")
        addDemoFile(chat: bilal.chat, sender: nil, fileName: "north-exit.jpg",
                    size: demoFileSize(at: photoPath, fallback: 6_266), date: minutesAgo(48),
                    type: .ready, uti: kUTTypeJPEG as String, path: photoPath)
        let caption = addDemoText(chat: bilal.chat, sender: nil, text: OCTManagerMock.storeText("demo_s3_bilal_caption"),
                                  date: minutesAgo(48) + 2)
        caption.isCaption = true
        // On its way to us: OCTSubmanagerFilesMock reports how much of it has come.
        addDemoFile(chat: bilal.chat, sender: bilal.friend, fileName: "tickets.pdf", size: 2_400_000,
                    date: minutesAgo(46), type: .loading, uti: kUTTypePDF as String)

        addDemoCall(chat: yusuf.chat, sender: nil, answered: false, duration: 0, date: yesterdayAt(21, 30))
        // Sent while Yusuf was offline: objcTox keeps such a file with no transfer number until he is back.
        let archive = addDemoFile(chat: yusuf.chat, sender: nil, fileName: "trip-photos.zip", size: 8_400_000,
                                  date: minutesAgo(13), type: .waitingConfirmation, uti: kUTTypeZipArchive as String)
        archive.messageFile?.internalFileNumber = Int32(bitPattern: kOCTToxFileNumberFailure)

        try! realm.commitWriteTransaction()
    }

    /// The conversation with the demo contact whose key is `key`.
    private func demoConversation(_ key: String) -> (chat: OCTChat, friend: OCTFriend)? {
        let friends = OCTFriend.objects(in: realm, with: NSPredicate(format: "publicKey == %@", key))
        guard friends.count > 0, let friend = friends.firstObject() as? OCTFriend else {
            return nil
        }
        let chats = OCTChat.objects(in: realm, with: NSPredicate(format: "ANY friends.uniqueIdentifier == %@", friend.uniqueIdentifier))
        guard chats.count > 0, let chat = chats.firstObject() as? OCTChat else {
            return nil
        }
        return (chat, friend)
    }

    private func demoFileSize(at path: String?, fallback: OCTToxFileSize) -> OCTToxFileSize {
        guard let path = path,
              let size = (try? FileManager.default.attributesOfItem(atPath: path))?[.size] as? NSNumber else {
            return fallback
        }
        return size.int64Value
    }

    private func addDemoCall(chat: OCTChat, sender: OCTFriend?, answered: Bool, duration: TimeInterval, date: TimeInterval) {
        let messageCall = OCTMessageCall()
        messageCall.callDuration = duration
        messageCall.callEvent = answered ? .answered : .unanswered

        let message = addDemoMessage(chat: chat, sender: sender, date: date)
        message.messageCall = messageCall
    }

    @discardableResult
    private func addDemoFile(chat: OCTChat, sender: OCTFriend?, fileName: String, size: OCTToxFileSize, date: TimeInterval,
                             type: OCTMessageFileType, uti: String, path: String? = nil) -> OCTMessageAbstract {
        let messageFile = OCTMessageFile()
        messageFile.fileName = fileName
        messageFile.fileSize = size
        messageFile.fileType = type
        messageFile.fileUTI = uti
        messageFile.internalFilePath = path

        let message = addDemoMessage(chat: chat, sender: sender, date: date)
        message.messageFile = messageFile
        return message
    }
}

// MARK: - The store frames

/// The store frames (stage 5, khandaq-ios/scripts/appstore-shots/captions.json) each show one screen, so
/// the DEMO_STORE launch argument thins the demo world for them: without the very long name, the request
/// nobody answered and the group you only read, Air shows a group and two people online, one person
/// offline and Saved Messages on one screen; and one message waits in transit for Yusuf.
extension OCTManagerMock {
    fileprivate func thinForStoreFrames() {
        realm.beginWriteTransaction()
        for key in [OCTManagerMock.demoLongKey, OCTManagerMock.demoLeilaKey] {
            let friends = OCTFriend.objects(in: realm, with: NSPredicate(format: "publicKey == %@", key))
            guard let friend = friends.firstObject() as? OCTFriend else {
                continue
            }
            let chats = OCTChat.objects(in: realm, with: NSPredicate(format: "ANY friends.uniqueIdentifier == %@", friend.uniqueIdentifier))
            if let chat = chats.firstObject() as? OCTChat {
                deleteDemoMessages(of: chat)
                realm.delete(chat)
            }
            realm.delete(friend)
        }
        if let observer = demoGroup(OCTManagerMock.demoObserverGroupNumber) {
            deleteDemoMessages(of: observer)
            realm.deleteObjects(OCTGroupPeer.objects(in: realm, with: NSPredicate(format: "chatUniqueIdentifier == %@", observer.uniqueIdentifier)))
            realm.delete(observer)
        }
        if let yusuf = demoConversation(OCTManagerMock.demoYusufKey) {
            // Ours and not delivered: the texts and the archive written while he was offline. The newest stays.
            let ours = OCTMessageAbstract.objects(in: realm, with: NSPredicate(format: "chatUniqueIdentifier == %@ AND senderUniqueIdentifier == nil",
                                                                               yusuf.chat.uniqueIdentifier))
            var waiting: [OCTMessageAbstract] = []
            for index in 0..<ours.count {
                guard let message = ours.object(at: index) as? OCTMessageAbstract else {
                    continue
                }
                let waitingText = message.messageText.map { !$0.isDelivered } ?? false
                let waitingFile = message.messageFile?.fileType == .waitingConfirmation
                if waitingText || waitingFile {
                    waiting.append(message)
                }
            }
            waiting.sort { $0.dateInterval < $1.dateInterval }
            for message in waiting.dropLast() {
                deleteDemoMessage(message)
            }
        }
        try! realm.commitWriteTransaction()

        if let groups = groups as? OCTSubmanagerGroupsMock {
            groups.onlinePeers[OCTManagerMock.demoObserverGroupNumber] = nil
            groups.selfRoles[OCTManagerMock.demoObserverGroupNumber] = nil
        }
    }

    private func deleteDemoMessages(of chat: OCTChat) {
        let messages = OCTMessageAbstract.objects(in: realm, with: NSPredicate(format: "chatUniqueIdentifier == %@", chat.uniqueIdentifier))
        var found: [OCTMessageAbstract] = []
        for index in 0..<messages.count {
            if let message = messages.object(at: index) as? OCTMessageAbstract {
                found.append(message)
            }
        }
        found.forEach(deleteDemoMessage)
    }

    private func deleteDemoMessage(_ message: OCTMessageAbstract) {
        if let text = message.messageText {
            realm.delete(text)
        }
        if let file = message.messageFile {
            realm.delete(file)
        }
        if let call = message.messageCall {
            realm.delete(call)
        }
        realm.delete(message)
    }
}
