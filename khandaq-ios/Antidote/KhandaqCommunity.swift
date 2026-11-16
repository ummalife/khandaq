// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation

/// The marks a profile keeps about Khandaq Community (AirLocalStore, in air-local.json next to the profile).
/// They only ever become true.
protocol KhandaqCommunityMarks: AnyObject {
    var communityAutoJoinDone: Bool { get }
    var communityLeft: Bool { get }
    func setCommunityAutoJoinDone()
    /// True when the mark was not set before.
    @discardableResult
    func setCommunityLeft() -> Bool
}

/// KHANDAQ (0.2.57): Khandaq Community, the official group of the app (SPEC-community, part B). Every profile
/// joins it once by itself, muted, and never again once the person has left it. Wherever a group's name shows
/// it is "Khandaq Community" (owner, 2026-11-16); otherwise it is a group like any other: the list, the
/// dossier, the mute switch, leaving and moderation are those of every group.
/// Its founder is our bot on khandaq.org, whose state keeps the chat id, so the id is compiled in. The join
/// needs the toxcore that looks a group up by its chat id (e24bb519): without it the group stays in "joining".
enum KhandaqCommunity {
    /// The chat id of the group, 64 hexadecimal characters in upper case.
    static let chatIdHex = "CFF1995F235303B88C4EF1CE903DCEDC77CEAC82DD1323DAB2CACD2EF840646F"

    /// The group's name, the same in every language: a name, not a phrase to translate.
    static let displayName = "Khandaq Community"

    static func isCommunity(chatIdHex id: String?) -> Bool {
        return id?.uppercased() == chatIdHex
    }

    static func isCommunity(_ chat: OCTChat?) -> Bool {
        guard let chat = chat, !chat.isInvalidated, chat.isGroup else {
            return false
        }
        return isCommunity(chatIdHex: chat.groupChatIdHex)
    }

    /// `displayName` when `chat` is the group, else nil, and the caller names any other group as before. The
    /// name the group's state gives is not used: its topic said "Official Khandaq community · Be kind, no spam
    /// or ads · …", the list showed the topic, and the profile stores the topic as the name when the group
    /// gave none (OCTRealmManager updateGroupTopic).
    static func displayName(for chat: OCTChat?) -> String? {
        return isCommunity(chat) ? displayName : nil
    }

    /// `displayName` when a group invitation (toxcore's invite data, which starts with the group's chat id) is
    /// to the group, else nil.
    static func displayName(forInviteData data: Data) -> String? {
        guard data.count >= 32 else {
            return nil
        }
        let id = data.prefix(32).map { String(format: "%02X", $0) }.joined()
        return isCommunity(chatIdHex: id) ? displayName : nil
    }

    /// Whether to join it now (item 1): this profile's node is connected, the profile never joined it by
    /// itself before (autoJoinDone) nor left it (left), and it is not in it already, by hand or from an
    /// earlier join.
    static func shouldAutoJoin(connected: Bool, autoJoinDone: Bool, left: Bool, alreadyInGroup: Bool) -> Bool {
        return connected && !autoJoinDone && !left && !alreadyInGroup
    }

    /// Joins it through `join` when shouldAutoJoin says so. `join` gives the group once it exists here,
    /// toxcore having taken the join, and nil when it did not. Only then is the group muted through `mute`
    /// (item 2: the person was added without being asked, and hears nothing of it until they turn it on) and
    /// the join marked done; a join that failed marks nothing, so the next start or connection tries again.
    /// Returns the group it joined.
    @discardableResult
    static func autoJoin<Group>(marks: KhandaqCommunityMarks,
                                connected: Bool,
                                alreadyInGroup: Bool,
                                join: () -> Group?,
                                mute: (Group) -> Void) -> Group? {
        guard shouldAutoJoin(connected: connected,
                             autoJoinDone: marks.communityAutoJoinDone,
                             left: marks.communityLeft,
                             alreadyInGroup: alreadyInGroup) else {
            return nil
        }
        guard let group = join() else {
            return nil
        }
        mute(group)
        marks.setCommunityAutoJoinDone()
        return group
    }

    /// A group was left (item 3): when it is Khandaq Community it is never joined by itself again, however it
    /// was joined, across restarts. True when that changed the marks.
    @discardableResult
    static func noteGroupLeft(chatIdHex id: String?, marks: KhandaqCommunityMarks) -> Bool {
        return isCommunity(chatIdHex: id) && marks.setCommunityLeft()
    }

    /// The person leaves `chat` and it is removed: called before it goes, while the chat can still be read.
    static func willLeave(_ chat: OCTChat, marks: KhandaqCommunityMarks = AirLocalStore.shared) {
        guard !chat.isInvalidated, chat.isGroup else {
            return
        }
        if noteGroupLeft(chatIdHex: chat.groupChatIdHex, marks: marks) {
            log("Community: left; it is not joined by itself again")
        }
    }

    /// The chat of the group when this profile is in it: in the list, with the number toxcore gave it.
    static func joinedChat(in objects: OCTSubmanagerObjects) -> OCTChat? {
        let chats = objects.chats(predicate: NSPredicate(format: "isGroup == YES AND groupChatIdHex ==[c] %@", chatIdHex))
        for index in 0..<chats.count {
            let chat = chats[index]
            if !chat.isInvalidated && chat.groupNumber >= 0 {
                return chat
            }
        }
        return nil
    }

    /// One try of the join, from the session: at its start and whenever its node comes online. The name in
    /// the group is the profile's own, as for a join by hand; there is no password.
    @discardableResult
    static func autoJoinIfDue(manager: OCTManager, marks: KhandaqCommunityMarks = AirLocalStore.shared) -> OCTChat? {
        let groups = manager.groups
        let peerName = GroupJoinHelper.peerName(userName: manager.user.userName(),
                                                profileName: UserDefaultsManager().lastActiveProfile)
        let joined = autoJoin(marks: marks,
                              connected: manager.user.connectionStatus != .none,
                              alreadyInGroup: joinedChat(in: manager.objects) != nil,
                              join: { () -> OCTChat? in
                                  var error: NSError?
                                  let number = groups.joinGroup(withChatIdHex: chatIdHex, peerName: peerName, password: nil, error: &error)
                                  guard error == nil, number != kOCTToxGroupNumberFailure else {
                                      log("Community: toxcore did not take the join (code \(error?.code ?? -1)); the next start or connection tries again")
                                      return nil
                                  }
                                  groups.syncGroupsWithTox()
                                  guard let chat = groups.chat(forGroupNumber: number) else {
                                      return nil
                                  }
                                  groups.refreshPeers(for: chat)
                                  return chat
                              },
                              mute: { chat in
                                  groups.setGroupNotificationsSilent(true, for: chat)
                              })
        if joined != nil {
            log("Community: joined by itself, muted")
        }
        return joined
    }

    /// Whether a failed join of `chat` is the person's to hear of. A join of Khandaq Community the app made
    /// by itself says nothing: the person did not ask for it, and toxcore tries a full group again later
    /// (SPEC-community v1). After the person left it, a join by hand tells as for any group.
    static func tellsJoinFailure(of chat: OCTChat, marks: KhandaqCommunityMarks = AirLocalStore.shared) -> Bool {
        return !(isCommunity(chat) && marks.communityAutoJoinDone && !marks.communityLeft)
    }
}
