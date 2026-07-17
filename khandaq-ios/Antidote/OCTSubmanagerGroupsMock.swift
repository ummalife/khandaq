// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation

class OCTSubmanagerGroupsMock: NSObject, OCTSubmanagerGroups {
    weak var delegate: OCTSubmanagerGroupsDelegate?

    func flushAllPendingGroupMessagesIfNeeded() {
        // nop
    }

    func toggleReaction(onGroupMessage message: OCTMessageAbstract!, emoji: String!, in chat: OCTChat!) {
        // nop
    }

    func createPublicGroup(withName groupName: String!, peerName: String!, error: NSErrorPointer) -> OCTToxGroupNumber {
        return 0
    }

    func createPublicGroup(withName groupName: String!, peerName: String!, completion: ((OCTToxGroupNumber, Error?) -> Void)!) {
        completion(0, nil)
    }

    func createPrivateGroup(withName groupName: String!, peerName: String!, error: NSErrorPointer) -> OCTToxGroupNumber {
        return 0
    }

    func createPrivateGroup(withName groupName: String!, peerName: String!, completion: ((OCTToxGroupNumber, Error?) -> Void)!) {
        completion(0, nil)
    }

    func joinGroup(withChatIdHex chatIdHex: String!, peerName: String!, password: String?, error: NSErrorPointer) -> OCTToxGroupNumber {
        return 0
    }

    func leaveGroup(withNumber groupNumber: OCTToxGroupNumber, partMessage: String?) throws {
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
        return nil
    }

    func peerCount(for chat: OCTChat!) -> Int32 {
        return chat?.groupPeerCount ?? 0
    }

    func offlinePeerCount(for chat: OCTChat!) -> Int32 {
        return 0
    }

    func peers(for chat: OCTChat!) -> RLMResults<AnyObject>! {
        let predicate = NSPredicate(format: "chatUniqueIdentifier == %@", chat?.uniqueIdentifier ?? "")
        return OCTGroupPeer.objects(with: predicate).sortedResults(usingKeyPath: "peerName", ascending: true)
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
        return OCTChat()
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
        return false
    }

    func groupPeerLastSeenDateInterval(forPeerId peerId: UInt32, in chat: OCTChat!) -> TimeInterval {
        return 0
    }

    func onlineGroupPeerCount(for chat: OCTChat!) -> Int32 {
        return 0
    }

    func groupSelfPeerName(for chat: OCTChat!) throws -> String {
        return ""
    }

    func groupSelfPeerId(for chat: OCTChat!) -> UInt32 {
        return 0
    }

    func setGroupSelfPeerName(_ name: String!, for chat: OCTChat!) throws {
        // nop
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
        return false
    }

    func kickPeer(withId peerId: UInt32, in chat: OCTChat!) throws {
        // nop
    }

    func peerRole(withId peerId: UInt32, in chat: OCTChat!) -> OCTToxGroupRole {
        return .user
    }

    func canSetPeerRole(_ role: OCTToxGroupRole, peerId: UInt32, in chat: OCTChat!) -> Bool {
        return false
    }

    func setPeerRole(_ role: OCTToxGroupRole, peerId: UInt32, in chat: OCTChat!) throws {
        // nop
    }

    func refreshGroupMetadata(for chat: OCTChat!) {
        // nop
    }

    func selfRole(in chat: OCTChat!) -> OCTToxGroupRole {
        return .user
    }

    func groupTopic(for chat: OCTChat!) throws -> String {
        return chat?.groupTopic ?? ""
    }

    func setGroupTopic(_ topic: String!, for chat: OCTChat!) throws {
        // nop
    }

    func groupPassword(for chat: OCTChat!) throws -> String {
        return chat?.groupPassword ?? ""
    }

    func setGroupPassword(_ password: String!, for chat: OCTChat!) throws {
        // nop
    }

    func groupTopicLock(for chat: OCTChat!, error: NSErrorPointer) -> OCTToxGroupTopicLock {
        return chat?.groupTopicLockEnabled == true ? .enabled : .disabled
    }

    func setGroupTopicLock(_ topicLock: OCTToxGroupTopicLock, for chat: OCTChat!) throws {
        // nop
    }

    func groupPeerLimit(for chat: OCTChat!, error: NSErrorPointer) -> Int32 {
        return chat?.groupPeerLimit ?? kOCTDefaultGroupPeerLimit
    }

    func setGroupPeerLimit(_ peerLimit: Int32, for chat: OCTChat!) throws {
        // nop
    }

    func canEditGroupTopic(in chat: OCTChat!) -> Bool {
        return true
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
        return (chat?.groupNumber ?? -1) >= 0
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
        // nop
    }

    func groupVoiceState(for chat: OCTChat!, error: NSErrorPointer) -> OCTToxGroupVoiceState {
        return .all
    }

    func setGroupVoiceState(_ voiceState: OCTToxGroupVoiceState, for chat: OCTChat!) throws {
        // nop
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

    func sendPrivateMessage(_ text: String!, toPeerId peerId: UInt32, in chat: OCTChat!, successBlock: ((OCTMessageAbstract?) -> Void)!, failureBlock: ((Error?) -> Void)!) {
        // nop
    }
}
