// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation

class OCTSubmanagerGroupsMock: NSObject, OCTSubmanagerGroups {
    weak var delegate: OCTSubmanagerGroupsDelegate?
    /// The demo world's store: with it, accepting an invitation adds the group as a real profile would.
    var realm: RLMRealm?
    /// Your peer id in every demo group (the members of a group are seeded in OCTManagerMock).
    static let selfPeerId: UInt32 = 900
    /// The members online now, by group number.
    var onlinePeers: [Int32: Set<Int32>] = [:]
    /// Your role in each group, by group number; a group without one has you as a member.
    var selfRoles: [Int32: OCTToxGroupRole] = [:]
    /// Your name in the demo groups.
    var selfName = ""
    /// Groups joined by key on this phone that no member has answered yet: they stay "connecting".
    var connectingGroups: Set<Int32> = []
    private var reconnectObserver: NSObjectProtocol?

    /// With DEMO_CONNECTING, Bilal invites you to a group 16 seconds after "Connect again" (ten after
    /// his message), so a walk sees the band of an invitation (E22).
    override init() {
        super.init()

        if ProcessInfo.processInfo.arguments.contains("DEMO_CONNECTING") {
            reconnectObserver = NotificationCenter.default.addObserver(forName: .khandaqManualReconnectRequested, object: nil, queue: .main) { [weak self] note in
                guard (note.object as? ReconnectBackoffCoordinator.Reason) == .manual else {
                    return
                }
                DispatchQueue.main.asyncAfter(deadline: .now() + 16) { [weak self] in
                    self?.receiveDemoInvite()
                }
            }
        }
    }

    deinit {
        if let observer = reconnectObserver {
            NotificationCenter.default.removeObserver(observer)
        }
    }

    /// An invitation from Bilal arriving now, handed over by friend number as toxcore does.
    private func receiveDemoInvite() {
        guard let realm = realm,
              let friend = OCTFriend.objects(in: realm, with: NSPredicate(format: "publicKey == %@", OCTManagerMock.demoBilalKey)).firstObject() as? OCTFriend else {
            return
        }
        // The demo contacts carry no friend numbers of their own; this one gets a number nobody else has.
        let number: OCTToxFriendNumber = 77
        realm.beginWriteTransaction()
        friend.friendNumber = number
        try? realm.commitWriteTransaction()
        delegate?.submanagerGroups(self, didReceiveInviteFromFriendNumber: number,
                                   invite: Data(repeating: 3, count: 32),
                                   groupName: OCTManagerMock.storeText("demo_s3_band_invite_group"))
    }

    func flushAllPendingGroupMessagesIfNeeded() {
        // nop
    }

    func toggleReaction(onGroupMessage message: OCTMessageAbstract!, emoji: String!, in chat: OCTChat!) {
        // nop
    }

    func deleteGroupMessage(forBoth message: OCTMessageAbstract!, in chat: OCTChat!) {
        // nop
    }

    func editGroupMessage(_ message: OCTMessageAbstract!, in chat: OCTChat!, newText: String!) {
        // nop
    }

    func createPublicGroup(withName groupName: String!, peerName: String!, error: NSErrorPointer) -> OCTToxGroupNumber {
        return 0
    }

    func createPublicGroup(withName groupName: String!, peerName: String!, completion: ((OCTToxGroupNumber, Error?) -> Void)!) {
        completion(addCreatedGroup(name: groupName ?? "", privacy: .public), nil)
    }

    func createPrivateGroup(withName groupName: String!, peerName: String!, error: NSErrorPointer) -> OCTToxGroupNumber {
        return 0
    }

    func createPrivateGroup(withName groupName: String!, peerName: String!, completion: ((OCTToxGroupNumber, Error?) -> Void)!) {
        completion(addCreatedGroup(name: groupName ?? "", privacy: .private), nil)
    }

    /// A group joined by its key: it waits for a member to answer, as a cold join does on a real
    /// network, so the demo shows the group connecting.
    func joinGroup(withChatIdHex chatIdHex: String!, peerName: String!, password: String?, error: NSErrorPointer) -> OCTToxGroupNumber {
        guard let realm = realm else {
            return 0
        }
        let number = addDemoGroup(name: "", privacy: .public)
        let found = OCTChat.objects(in: realm, with: NSPredicate(format: "isGroup == YES AND groupNumber == %d", number))
        if let chat = found.firstObject() as? OCTChat {
            realm.beginWriteTransaction()
            chat.groupName = nil
            chat.groupPeerCount = 0
            chat.groupChatIdHex = (chatIdHex ?? "").uppercased()
            try? realm.commitWriteTransaction()
        }
        connectingGroups.insert(number)
        return OCTToxGroupNumber(number)
    }

    func leaveGroup(withNumber groupNumber: OCTToxGroupNumber, partMessage: String?) throws {
        // nop
    }

    func leavePendingGroupsNow() {
        // nop
    }

    func sendMessage(_ message: String!, type: OCTToxMessageType, groupNumber: OCTToxGroupNumber, messageId: UnsafeMutablePointer<UInt32>?) throws {
        // nop
    }

    func chatIdHex(forGroupNumber groupNumber: OCTToxGroupNumber) throws -> String {
        return ""
    }

    func chatIdHex(for chat: OCTChat!) throws -> String {
        return chat?.groupChatIdHex ?? ""
    }

    func chat(forGroupNumber groupNumber: OCTToxGroupNumber) -> OCTChat? {
        guard let realm = realm else {
            return nil
        }
        let chats = OCTChat.objects(in: realm, with: NSPredicate(format: "isGroup == YES AND groupNumber == %d", Int32(groupNumber)))
        return chats.count > 0 ? chats.firstObject() as? OCTChat : nil
    }

    func peerCount(for chat: OCTChat!) -> Int32 {
        return chat?.groupPeerCount ?? 0
    }

    func offlinePeerCount(for chat: OCTChat!) -> Int32 {
        return 0
    }

    func peers(for chat: OCTChat!) -> RLMResults<AnyObject>! {
        let predicate = NSPredicate(format: "chatUniqueIdentifier == %@", chat?.uniqueIdentifier ?? "")
        // The demo store, not the default realm: the demo world lives in memory.
        guard let realm = realm else {
            return OCTGroupPeer.objects(with: predicate).sortedResults(usingKeyPath: "peerName", ascending: true)
        }
        return OCTGroupPeer.objects(in: realm, with: predicate).sortedResults(usingKeyPath: "peerName", ascending: true)
    }

    func refreshPeers(for chat: OCTChat!) {
        // nop
    }

    func foregroundReconnect(for chat: OCTChat!) {
        // nop
    }

    func prepareGroupLiveMediaMonitoring(for chat: OCTChat!) {
        // nop
    }

    func syncGroupsWithTox() {
        // nop
    }

    func setGroupBackgroundWorkPaused(_ paused: Bool) {
        // nop
    }

    func acceptGroupInvite(fromFriendNumber friendNumber: OCTToxFriendNumber, invite inviteData: Data!, groupName: String!, password: String?) throws -> OCTChat {
        guard realm != nil else {
            return OCTChat()
        }
        let number = addDemoGroup(name: groupName ?? "", privacy: .private)
        return chat(forGroupNumber: OCTToxGroupNumber(number)) ?? OCTChat()
    }

    func inviteFriend(withNumber friendNumber: OCTToxFriendNumber, toGroupNumber groupNumber: OCTToxGroupNumber) throws {
        // nop
    }

    func sendMessage(to chat: OCTChat!, text: String!, type: OCTToxMessageType, successBlock: ((OCTMessageAbstract?) -> Void)!, failureBlock: ((Error?) -> Void)!) {
        // nop
    }

    func sendFile(atPath filePath: String!, to chat: OCTChat!, moveToUploads: Bool, successBlock: ((OCTMessageAbstract?) -> Void)!, failureBlock: ((Error?) -> Void)!) {
        // nop
    }

    func removeAllMessages(in chat: OCTChat!, removeChat: Bool, leaveGroup: Bool) {
        // nop
    }

    func removeGroupSystemMessages(in chat: OCTChat!) -> UInt {
        return 0
    }

    func groupSystemMessageCount(for chat: OCTChat!) -> UInt {
        return 0
    }

    func isGroupPeerOnline(withId peerId: UInt32, in chat: OCTChat!) -> Bool {
        guard let number = chat?.groupNumber else {
            return false
        }
        return onlinePeers[number]?.contains(Int32(bitPattern: peerId)) ?? false
    }

    /// KHANDAQ (audit#2 finding 1): the mock never has a verdict store, and "unproved" is the honest
    /// answer for it: the same one the real thing gives for every row until both sides sign.
    func isGroupMessageAuthorVerified(_ message: OCTMessageAbstract?, in chat: OCTChat?) -> Bool {
        return false
    }

    /// KHANDAQ (re-review 2026-08-22, KQ-03). true, not false: the mock has no HSK directory, and
    /// the question is "must this row's author be hidden". Answering false here would strip the
    /// sender name from every message in every preview and test that uses the mock.
    func isGroupMessageAttributable(_ message: OCTMessageAbstract?, in chat: OCTChat?) -> Bool {
        return true
    }

    func groupPeerLastSeenDateInterval(forPeerId peerId: UInt32, in chat: OCTChat!) -> TimeInterval {
        return peer(peerId, in: chat)?.peerLastSeenDateInterval ?? 0
    }

    /// The members online now besides you, as toxcore counts them.
    func onlineGroupPeerCount(for chat: OCTChat!) -> Int32 {
        guard let number = chat?.groupNumber else {
            return 0
        }
        return Int32(onlinePeers[number]?.filter { $0 != Int32(OCTSubmanagerGroupsMock.selfPeerId) }.count ?? 0)
    }

    func groupSelfPeerName(for chat: OCTChat!) throws -> String {
        return selfName
    }

    func groupSelfPeerId(for chat: OCTChat!) -> UInt32 {
        return OCTSubmanagerGroupsMock.selfPeerId
    }

    func setGroupSelfPeerName(_ name: String!, for chat: OCTChat!) throws {
        selfName = name ?? ""
        guard let realm = realm, let chat = chat,
              let own = peer(OCTSubmanagerGroupsMock.selfPeerId, in: chat) else {
            return
        }
        realm.beginWriteTransaction()
        own.peerName = selfName
        try? realm.commitWriteTransaction()
        postPeersUpdated(chat)
    }

    func cancelGroupFileTransfer(forMessage message: OCTMessageAbstract!) throws {
        // nop
    }

    func setGroupNotificationsSilent(_ silent: Bool, for chat: OCTChat!) {
        // nop
    }

    func setPeerNotificationsSilent(_ silent: Bool, peerId: UInt32, for chat: OCTChat!) {
        // nop
    }

    func canKickPeer(withId peerId: UInt32, in chat: OCTChat!) -> Bool {
        guard peerId != OCTSubmanagerGroupsMock.selfPeerId, peer(peerId, in: chat) != nil else {
            return false
        }
        return AirGroupRoles.canKick(peerRole(withId: peerId, in: chat), by: selfRole(in: chat))
    }

    /// The demo removes the member from the group's list, as toxcore does once the kick goes out.
    func kickPeer(withId peerId: UInt32, in chat: OCTChat!) throws {
        guard let realm = realm, let chat = chat, let member = peer(peerId, in: chat) else {
            return
        }
        realm.beginWriteTransaction()
        realm.delete(member)
        chat.groupPeerCount = max(1, chat.groupPeerCount - 1)
        try realm.commitWriteTransaction()
        onlinePeers[chat.groupNumber]?.remove(Int32(bitPattern: peerId))
        postPeersUpdated(chat)
    }

    func peerRole(withId peerId: UInt32, in chat: OCTChat!) -> OCTToxGroupRole {
        if peerId == OCTSubmanagerGroupsMock.selfPeerId {
            return selfRole(in: chat)
        }
        guard let member = peer(peerId, in: chat) else {
            return .user
        }
        return OCTToxGroupRole(rawValue: Int(member.peerRole)) ?? .user
    }

    func canSetPeerRole(_ role: OCTToxGroupRole, peerId: UInt32, in chat: OCTChat!) -> Bool {
        guard peerId != OCTSubmanagerGroupsMock.selfPeerId, peer(peerId, in: chat) != nil else {
            return false
        }
        return AirGroupRoles.canSet(role, on: peerRole(withId: peerId, in: chat), by: selfRole(in: chat))
    }

    func setPeerRole(_ role: OCTToxGroupRole, peerId: UInt32, in chat: OCTChat!) throws {
        guard let realm = realm, let chat = chat, let member = peer(peerId, in: chat) else {
            return
        }
        realm.beginWriteTransaction()
        member.peerRole = Int32(role.rawValue)
        try realm.commitWriteTransaction()
        postPeersUpdated(chat)
    }

    func refreshGroupMetadata(for chat: OCTChat!) {
        // nop
    }

    /// The demo groups (OCTManagerMock): one you founded, one where you only read, and the ones you
    /// create or join.
    func selfRole(in chat: OCTChat!) -> OCTToxGroupRole {
        guard let number = chat?.groupNumber else {
            return .user
        }
        return selfRoles[number] ?? .user
    }

    func groupTopic(for chat: OCTChat!) throws -> String {
        return chat?.groupTopic ?? ""
    }

    func setGroupTopic(_ topic: String!, for chat: OCTChat!) throws {
        store(chat) { chat in
            chat.groupTopic = topic
        }
    }

    func groupPassword(for chat: OCTChat!) throws -> String {
        return chat?.groupPassword ?? ""
    }

    func setGroupPassword(_ password: String!, for chat: OCTChat!) throws {
        store(chat) { chat in
            chat.groupPassword = password
        }
    }

    func groupTopicLock(for chat: OCTChat!, error: NSErrorPointer) -> OCTToxGroupTopicLock {
        return chat?.groupTopicLockEnabled == true ? .enabled : .disabled
    }

    func setGroupTopicLock(_ topicLock: OCTToxGroupTopicLock, for chat: OCTChat!) throws {
        store(chat) { chat in
            chat.groupTopicLockEnabled = topicLock == .enabled
        }
    }

    func groupPeerLimit(for chat: OCTChat!, error: NSErrorPointer) -> Int32 {
        return chat?.groupPeerLimit ?? kOCTDefaultGroupPeerLimit
    }

    func setGroupPeerLimit(_ peerLimit: Int32, for chat: OCTChat!) throws {
        store(chat) { chat in
            chat.groupPeerLimit = peerLimit
        }
    }

    /// As objcTox decides it: never an observer, always the founder and the moderators, and the others
    /// while the topic is not locked.
    func canEditGroupTopic(in chat: OCTChat!) -> Bool {
        switch selfRole(in: chat) {
            case .observer:
                return false
            case .founder, .moderator:
                return true
            default:
                return chat?.groupTopicLockEnabled != true
        }
    }

    func setPrivateLastReadDateInterval(_ interval: TimeInterval, peerId: UInt32, for chat: OCTChat!) {
        // nop
    }

    func unreadPrivateMessageCount(forPeerId peerId: UInt32, in chat: OCTChat!) -> Int32 {
        return 0
    }

    func totalUnreadPrivateMessageCount(for chat: OCTChat!) -> Int32 {
        return 0
    }

    func markAllPrivateThreadsRead(for chat: OCTChat!) {
    }

    func isGroupAtPeerCapacity(for chat: OCTChat!) -> Bool {
        return false
    }

    func isGroupConnected(for chat: OCTChat!) -> Bool {
        guard let number = chat?.groupNumber, number >= 0 else {
            return false
        }
        return !connectingGroups.contains(number)
    }

    func groupJoinAttempt(for chat: OCTChat!) -> Int {
        return 0
    }

    func isGroupJoinRetryRunning(for chat: OCTChat!) -> Bool {
        return false
    }

    func groupPrivacyState(for chat: OCTChat!, error: NSErrorPointer) -> OCTToxGroupPrivacyState {
        if chat?.groupPrivacyState == Int32(OCTToxGroupPrivacyState.private.rawValue) {
            return .private
        }
        return .public
    }

    func setGroupPrivacyState(_ privacyState: OCTToxGroupPrivacyState, for chat: OCTChat!) throws {
        store(chat) { chat in
            chat.groupPrivacyState = Int32(privacyState.rawValue)
        }
    }

    func groupVoiceState(for chat: OCTChat!, error: NSErrorPointer) -> OCTToxGroupVoiceState {
        return OCTToxGroupVoiceState(rawValue: Int(chat?.groupVoiceState ?? 0)) ?? .all
    }

    func setGroupVoiceState(_ voiceState: OCTToxGroupVoiceState, for chat: OCTChat!) throws {
        store(chat) { chat in
            chat.groupVoiceState = Int32(voiceState.rawValue)
        }
    }

    func startGroupLiveAudio(for chat: OCTChat!) throws {
        // nop
    }

    func stopGroupLiveAudio() {}

    func isGroupLiveAudioActive() -> Bool {
        return false
    }

    func startGroupLiveVideo(for chat: OCTChat!,
                             remoteFrameBlock: ((UIImage?) -> Void)!,
                             localFrameBlock: ((UIImage?) -> Void)!) throws {
        // nop
    }

    func stopGroupLiveVideo() {}

    func isGroupLiveVideoActive() -> Bool {
        return false
    }

    func setGroupLiveVideoHighQuality(_ highQuality: Bool, for chat: OCTChat!) {
        // nop
    }

    func isGroupLiveVideoHighQuality() -> Bool {
        return false
    }

    func groupLiveVideoIconState(for chat: OCTChat!) -> OCTGroupLiveVideoIconState {
        return .inactive
    }

    func recentIncomingGroupLiveVideoPeerCount(for chat: OCTChat!) -> UInt {
        return 0
    }

    func primaryRecentIncomingGroupLiveVideoPeerName(for chat: OCTChat!) -> String? {
        return nil
    }

    /// Kept as objcTox keeps a private message toxcore took: outgoing, threaded by the member's key.
    /// Refused as toxcore refuses it: to someone offline, and from an observer.
    func sendPrivateMessage(_ text: String!, toPeerId peerId: UInt32, in chat: OCTChat!, successBlock: ((OCTMessageAbstract?) -> Void)!, failureBlock: ((Error?) -> Void)!) {
        guard let realm = realm, let chat = chat, selfRole(in: chat) != .observer,
              let member = peer(peerId, in: chat), isGroupPeerOnline(withId: peerId, in: chat) else {
            failureBlock?(NSError(domain: "OCTSubmanagerGroups", code: 0, userInfo: nil))
            return
        }
        let messageText = OCTMessageText()
        messageText.text = text
        messageText.isDelivered = true
        let message = OCTMessageAbstract()
        message.chatUniqueIdentifier = chat.uniqueIdentifier
        message.dateInterval = Date().timeIntervalSince1970
        message.groupSenderPeerId = 0
        message.groupPrivateMessage = true
        message.groupPrivatePeerId = Int32(bitPattern: peerId)
        message.groupPrivatePeerPubkey = member.peerPublicKeyHex?.lowercased()
        message.messageText = messageText
        realm.beginWriteTransaction()
        realm.add(message)
        try? realm.commitWriteTransaction()
        successBlock?(message)
    }

    // MARK: - The demo world

    private func peer(_ peerId: UInt32, in chat: OCTChat?) -> OCTGroupPeer? {
        guard let realm = realm, let chat = chat else {
            return nil
        }
        let predicate = NSPredicate(format: "chatUniqueIdentifier == %@ AND peerId == %d", chat.uniqueIdentifier, Int32(bitPattern: peerId))
        let found = OCTGroupPeer.objects(in: realm, with: predicate)
        return found.count > 0 ? found.firstObject() as? OCTGroupPeer : nil
    }

    /// A change to a demo group, written to the demo store so the screens read it back.
    private func store(_ chat: OCTChat?, _ change: (OCTChat) -> Void) {
        guard let realm = realm, let chat = chat, !chat.isInvalidated else {
            return
        }
        realm.beginWriteTransaction()
        change(chat)
        try? realm.commitWriteTransaction()
    }

    private func postPeersUpdated(_ chat: OCTChat) {
        NotificationCenter.default.post(name: .octGroupPeersUpdated, object: nil,
                                        userInfo: [kOCTGroupPeersUpdatedChatUniqueIdentifierKey: chat.uniqueIdentifier])
    }

    /// A group created on this phone: you are its founder and its only member.
    private func addCreatedGroup(name: String, privacy: OCTToxGroupPrivacyState) -> OCTToxGroupNumber {
        guard realm != nil else {
            return 0
        }
        let number = addDemoGroup(name: name, privacy: privacy)
        selfRoles[number] = .founder
        return OCTToxGroupNumber(number)
    }

    /// A new group in the demo store, numbered after the others; returns its number.
    private func addDemoGroup(name: String, privacy: OCTToxGroupPrivacyState) -> Int32 {
        guard let realm = realm else {
            return 0
        }
        let groups = OCTChat.objects(in: realm, with: NSPredicate(format: "isGroup == YES"))
        var number: Int32 = 100
        for index in 0..<groups.count {
            if let group = groups.object(at: index) as? OCTChat {
                number = max(number, group.groupNumber)
            }
        }
        number += 1
        let chat = OCTChat()
        realm.beginWriteTransaction()
        chat.isGroup = true
        chat.groupNumber = number
        chat.groupName = name
        chat.groupPeerCount = 1
        chat.groupPrivacyState = Int32(privacy.rawValue)
        chat.groupChatIdHex = OCTManagerMock.demoMessageHash()
        chat.lastActivityDateInterval = Date().timeIntervalSince1970
        chat.lastReadDateInterval = chat.lastActivityDateInterval
        realm.add(chat)
        try? realm.commitWriteTransaction()
        return number
    }
}
