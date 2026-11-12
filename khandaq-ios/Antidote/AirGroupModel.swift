// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation

// What the group screens say and allow, apart from any view: who is in the group and in what order,
// who gets a message right now, who may write, and what the founder and the moderators may change.
// The rules mirror NGC (toxcore): the client asks the submanager before acting, these only decide what
// to show.

/// A member of a group as the group screens show them.
struct AirGroupMember: Equatable {
    let peerId: UInt32
    let name: String
    /// The member's key in this group (NGC keys are per group), uppercase hex; empty when unknown.
    let publicKey: String
    let role: OCTToxGroupRole
    let isOnline: Bool
    /// When this device last saw the member online, 0 when it never did.
    let lastSeen: TimeInterval
    let isSelf: Bool

    /// The key the member's tone and initials come from: the group key when known, else the name.
    var toneKey: String {
        return publicKey.isEmpty ? name : publicKey
    }
}

enum AirGroupRoles {
    /// Founder first, observers last.
    static func rank(_ role: OCTToxGroupRole) -> Int {
        switch role {
            case .founder:
                return 0
            case .moderator:
                return 1
            case .user:
                return 2
            case .observer:
                return 3
        }
    }

    /// The role as a member list says it: "founder", "moderator", "member", "observer".
    static func name(_ role: OCTToxGroupRole) -> String {
        switch role {
            case .founder:
                return String(localized: "air_group_role_founder")
            case .moderator:
                return String(localized: "air_group_role_moderator")
            case .user:
                return String(localized: "air_group_role_user")
            case .observer:
                return String(localized: "air_group_role_observer")
        }
    }

    /// "Your role" in the dossier: what this role may do, in one or two sentences.
    static func explanation(_ role: OCTToxGroupRole) -> String {
        switch role {
            case .founder:
                return String(localized: "air_group_you_founder")
            case .moderator:
                return String(localized: "air_group_you_moderator")
            case .user:
                return String(localized: "air_group_you_user")
            case .observer:
                return String(localized: "air_group_you_observer")
        }
    }

    /// Whether `actor` may give `target` the role `role`: the founder gives any role but founder to
    /// anyone else; a moderator moves members and observers between those two roles.
    static func canSet(_ role: OCTToxGroupRole, on target: OCTToxGroupRole, by actor: OCTToxGroupRole) -> Bool {
        guard role != target, role != .founder, target != .founder else {
            return false
        }
        switch actor {
            case .founder:
                return true
            case .moderator:
                return target != .moderator && role != .moderator
            case .user, .observer:
                return false
        }
    }

    /// Whether `actor` may remove a member with the role `target` from the group.
    static func canKick(_ target: OCTToxGroupRole, by actor: OCTToxGroupRole) -> Bool {
        switch actor {
            case .founder:
                return target != .founder
            case .moderator:
                return target == .user || target == .observer
            case .user, .observer:
                return false
        }
    }
}

/// The members in the order of the dossier: by role, then the ones online now, then the most recently
/// seen, then by name.
enum AirGroupRoster {
    /// From this many members on, the dossier offers a search by name.
    static let searchThreshold = 20

    static func sorted(_ members: [AirGroupMember]) -> [AirGroupMember] {
        return members.sorted { left, right in
            let leftRank = AirGroupRoles.rank(left.role)
            let rightRank = AirGroupRoles.rank(right.role)
            if leftRank != rightRank {
                return leftRank < rightRank
            }
            if left.isOnline != right.isOnline {
                return left.isOnline
            }
            if left.lastSeen != right.lastSeen {
                return left.lastSeen > right.lastSeen
            }
            let order = left.name.localizedStandardCompare(right.name)
            if order != .orderedSame {
                return order == .orderedAscending
            }
            return left.peerId < right.peerId
        }
    }

    /// The members whose name contains the query, ignoring case and diacritics.
    static func filtered(_ members: [AirGroupMember], query: String) -> [AirGroupMember] {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            return members
        }
        return members.filter {
            $0.name.range(of: trimmed, options: [.caseInsensitive, .diacriticInsensitive]) != nil
        }
    }

    /// Who gets a message the moment it is sent: everyone online but you, in the order of the strip.
    static func reachableNow(_ members: [AirGroupMember]) -> [AirGroupMember] {
        return sorted(members.filter { $0.isOnline && !$0.isSelf })
    }

    /// The moderators and the founder, in the order of the dossier.
    static func moderators(_ members: [AirGroupMember]) -> [AirGroupMember] {
        return sorted(members.filter { !$0.isSelf && ($0.role == .moderator || $0.role == .founder) })
    }

    /// Whom the button under a read-only bar writes to, privately: the moderators and the founder
    /// where only they write, the founder alone where only the founder writes (as the desktop asks),
    /// and nobody for an observer, whose private messages toxcore refuses.
    static func askable(_ members: [AirGroupMember], right: AirGroupComposeRight) -> [AirGroupMember] {
        switch right {
            case .moderatorsOnly:
                return moderators(members)
            case .founderOnly:
                return moderators(members).filter { $0.role == .founder }
            case .write, .observer:
                return []
        }
    }

    /// The second line of a member row: the role, then "online" or how long ago this device saw them.
    static func statusLine(for member: AirGroupMember, now: TimeInterval) -> String {
        let role = AirGroupRoles.name(member.role)
        let presence: String
        if member.isOnline {
            presence = String(localized: "air_reach_live")
        }
        else if member.lastSeen > 0 {
            presence = AirPresenceLine.awayText(seconds: max(0, now - member.lastSeen))
        }
        else {
            presence = String(localized: "air_group_member_offline")
        }
        return String(localized: "air_group_member_status_format", role, presence)
    }
}

/// How many are in the group and how many get a message now: the line under the header and the hint
/// under the field. The numbers are the ones the chat list shows: peerCount, which counts you too
/// (toxcore counts every confirmed peer, its own included), and the members online besides you.
struct AirGroupReach: Equatable {
    let total: Int
    let online: Int
    let connected: Bool

    init(peerCount: Int, knownMembers: Int, online: Int, connected: Bool) {
        let total = max(peerCount, knownMembers, 1)
        self.total = total
        self.online = connected ? min(max(online, 0), total - 1) : 0
        self.connected = connected
    }

    /// Everyone a message is for: the members but you.
    var recipients: Int {
        return total - 1
    }

    /// "9 members, 4 online now".
    var headerText: String {
        let members = String(localized: "air_group_members_count", total)
        guard connected else {
            return String(localized: "air_group_header_offline_format", members)
        }
        if online == 0 {
            return String(localized: "air_group_header_none_format", members)
        }
        return String(localized: "air_group_header_format", members, online)
    }

    /// "4 of 8 get it now, the rest when they come online".
    var hint: AirComposeHint {
        guard connected else {
            return AirComposeHint(text: String(localized: "air_group_hint_offline"), dot: .transit)
        }
        if recipients == 0 {
            return AirComposeHint(text: String(localized: "air_group_hint_alone"), dot: .none)
        }
        if online == 0 {
            return AirComposeHint(text: String(localized: "air_group_hint_none"), dot: .transit)
        }
        if online == recipients {
            // One other member is a case of its own, not a plural form: Russian takes 21 as "one" too.
            let text = recipients == 1 ? String(localized: "air_group_hint_one_other") : String(localized: "air_group_hint_all_format", online)
            return AirComposeHint(text: text, dot: .live)
        }
        return AirComposeHint(text: String(localized: "air_group_hint_format", online, recipients), dot: .live)
    }
}

/// Whether you may write in the group now. Observers never may; the founder can also leave the
/// writing to the moderators or to themselves alone (the voice state).
enum AirGroupComposeRight: Equatable {
    case write
    case observer
    case moderatorsOnly
    case founderOnly

    static func make(selfRole: OCTToxGroupRole, voiceState: OCTToxGroupVoiceState) -> AirGroupComposeRight {
        if selfRole == .observer {
            return .observer
        }
        switch voiceState {
            case .all:
                return .write
            case .moderator:
                return selfRole == .founder || selfRole == .moderator ? .write : .moderatorsOnly
            case .founder:
                return selfRole == .founder ? .write : .founderOnly
        }
    }

    /// The line in place of the field: "Read only. You are an observer: a moderator can let you write".
    var readOnlyText: String? {
        switch self {
            case .write:
                return nil
            case .observer:
                return String(localized: "air_group_read_only_observer")
            case .moderatorsOnly:
                return String(localized: "air_group_read_only_moderators")
            case .founderOnly:
                return String(localized: "air_group_read_only_founder")
        }
    }

    /// The button under it: whom to write to, in the private messages of the group. None for an
    /// observer: toxcore refuses an observer's private messages too (gc_send_private_message returns -5
    /// while your role is observer), so `privateRefusalText` stands in its place.
    var askTitle: String? {
        switch self {
            case .write, .observer:
                return nil
            case .moderatorsOnly:
                return String(localized: "air_group_ask_moderator")
            case .founderOnly:
                return String(localized: "air_group_ask_founder")
        }
    }

    /// Whether your private messages in this group can leave: everyone's but an observer's. Where only
    /// the moderators or the founder write, a member still writes privately; that is how they ask.
    var canWritePrivately: Bool {
        return self != .observer
    }

    /// Why nothing can be written, not even privately: in place of the button under the bar, and under
    /// the field of a member's private messages. Nil while your private messages can leave.
    var privateRefusalText: String? {
        return canWritePrivately ? nil : String(localized: "air_group_observer_no_private")
    }
}

/// What the founder or a moderator can do to one member, in the order of the sheet.
enum AirGroupMemberAction: Equatable {
    case makeModerator
    case makeUser
    case makeObserver
    case kick

    var role: OCTToxGroupRole? {
        switch self {
            case .makeModerator:
                return .moderator
            case .makeUser:
                return .user
            case .makeObserver:
                return .observer
            case .kick:
                return nil
        }
    }

    /// The actions on `target` that `actor` may take, by the rules above.
    static func available(on target: OCTToxGroupRole, by actor: OCTToxGroupRole) -> [AirGroupMemberAction] {
        var actions: [AirGroupMemberAction] = []
        for action in [AirGroupMemberAction.makeModerator, .makeUser, .makeObserver] {
            if let role = action.role, AirGroupRoles.canSet(role, on: target, by: actor) {
                actions.append(action)
            }
        }
        if AirGroupRoles.canKick(target, by: actor) {
            actions.append(.kick)
        }
        return actions
    }

    /// The verb on the sheet and on the confirmation button.
    var title: String {
        switch self {
            case .makeModerator:
                return String(localized: "air_group_make_moderator")
            case .makeUser:
                return String(localized: "air_group_make_user")
            case .makeObserver:
                return String(localized: "air_group_make_observer")
            case .kick:
                return String(localized: "air_group_kick")
        }
    }

    /// The question and its consequence, in one sentence each.
    func confirmation(name: String) -> (title: String, message: String) {
        switch self {
            case .makeModerator:
                return (String(localized: "air_group_make_moderator_title_format", name),
                        String(localized: "air_group_make_moderator_message"))
            case .makeUser:
                return (String(localized: "air_group_make_user_title_format", name),
                        String(localized: "air_group_make_user_message"))
            case .makeObserver:
                return (String(localized: "air_group_make_observer_title_format", name),
                        String(localized: "air_group_make_observer_message"))
            case .kick:
                return (String(localized: "air_group_kick_title_format", name),
                        String(localized: "air_group_kick_message"))
        }
    }

    /// What the toast says once it is done: "Amina is a moderator now".
    func doneText(name: String) -> String {
        switch self {
            case .makeModerator:
                return String(localized: "air_group_now_moderator_format", name)
            case .makeUser:
                return String(localized: "air_group_now_user_format", name)
            case .makeObserver:
                return String(localized: "air_group_now_observer_format", name)
            case .kick:
                return String(localized: "air_group_kicked_format", name)
        }
    }
}

/// A contact on the invitation lists of "New group" and the group dossier.
struct AirInviteCandidate: Equatable {
    let friendNumber: Int32
    let publicKey: String
    let name: String
    let isOnline: Bool
    /// Already in this group: shown, but not offered again.
    let isMember: Bool

    /// tox_group_invite_friend goes over the friend connection, so only a contact online now can be
    /// invited; the others wait in the list.
    var canInvite: Bool {
        return isOnline && !isMember && friendNumber >= 0
    }
}

enum AirInviteList {
    /// Online first, then by name; a search keeps names containing the query.
    static func arranged(_ candidates: [AirInviteCandidate], query: String) -> [AirInviteCandidate] {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        let kept = trimmed.isEmpty ? candidates : candidates.filter {
            $0.name.range(of: trimmed, options: [.caseInsensitive, .diacriticInsensitive]) != nil
        }
        return kept.sorted { left, right in
            if left.canInvite != right.canInvite {
                return left.canInvite
            }
            if left.isOnline != right.isOnline {
                return left.isOnline
            }
            let order = left.name.localizedStandardCompare(right.name)
            if order != .orderedSame {
                return order == .orderedAscending
            }
            return left.publicKey < right.publicKey
        }
    }

    /// The quiet line of a contact that cannot be invited now.
    static func note(for candidate: AirInviteCandidate) -> String? {
        if candidate.isMember {
            return String(localized: "air_invite_already_member")
        }
        if !candidate.isOnline {
            return String(localized: "air_invite_when_online")
        }
        return nil
    }
}

/// The privacy chosen for a new group, with the consequence said in one sentence.
enum AirGroupPrivacy: Equatable {
    case inviteOnly
    case openById

    var title: String {
        switch self {
            case .inviteOnly:
                return String(localized: "air_group_privacy_invite_only")
            case .openById:
                return String(localized: "air_group_privacy_open")
        }
    }

    var consequence: String {
        switch self {
            case .inviteOnly:
                return String(localized: "air_group_privacy_invite_only_note")
            case .openById:
                return String(localized: "air_group_privacy_open_note")
        }
    }

    init(state: OCTToxGroupPrivacyState) {
        self = state == .private ? .inviteOnly : .openById
    }
}

/// The name field of "New group": NGC takes at most 48 bytes of a name.
enum AirGroupName {
    static let maxBytes = 48

    /// One line: a pasted line break or a run of spaces becomes one space, and the ends are trimmed.
    static func clean(_ text: String) -> String {
        return text.components(separatedBy: .whitespacesAndNewlines).filter { !$0.isEmpty }.joined(separator: " ")
    }

    /// nil while the name fits; otherwise the bytes over the limit.
    static func overflow(_ text: String) -> Int? {
        let bytes = clean(text).utf8.count
        return bytes > maxBytes ? bytes - maxBytes : nil
    }
}

/// The length counter of the group field. NGC carries at most 1372 bytes in one group message
/// (TOX_GROUP_MAX_MESSAGE_LENGTH) and toxcore refuses a longer one instead of cutting it, so the
/// counter shows the room left once a tenth of it remains and the send button waits over the limit.
enum AirGroupComposeLimit {
    static let bytes = 1372

    static func remaining(forBytes count: Int) -> Int? {
        let left = bytes - count
        return Double(left) < Double(bytes) * 0.1 ? left : nil
    }
}

/// How a group conversation labels its entries.
enum AirGroupTranscript {
    /// The author over a run: the name, and the role for a founder or a moderator ("Amina, moderator").
    static func authorLabel(name: String, role: OCTToxGroupRole?) -> String {
        guard let role = role, role == .founder || role == .moderator else {
            return name
        }
        return String(localized: "air_group_author_role_format", name, AirGroupRoles.name(role))
    }

    /// Whether a message names you: by your key in this group, or by the handle the sender typed when
    /// the key is missing (a mention from an older client).
    static func mentionsMe(_ mentions: [GroupMentionHelper.MentionEntry], selfKey: String?, selfName: String?) -> Bool {
        let key = (selfKey ?? "").uppercased()
        let name = (selfName ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let handles = name.isEmpty ? [] : [name, AirTranscript.shortName(name)]
        for mention in mentions {
            if !key.isEmpty && !mention.pubkey.isEmpty {
                if mention.pubkey.uppercased() == key {
                    return true
                }
                continue
            }
            for handle in handles where mention.handle.compare(handle, options: [.caseInsensitive, .diacriticInsensitive]) == .orderedSame {
                return true
            }
        }
        return false
    }

    /// The mark of our own group message: waiting on this device while the group is not connected,
    /// one check once it left, two once a member's device confirmed it.
    static func status(pending: Bool, delivered: Bool, confirmations: Int32) -> AirEntryStatus {
        if pending {
            return .transit
        }
        return delivered || confirmations > 0 ? .delivered : .sent
    }
}

/// A group key as people pass it around (E15): 64 hex characters, bare, after "khandaq:group:" (the QR
/// code of the dossier) or in a khandaq://group/ link, with spaces and line breaks anywhere.
enum AirGroupKey {
    static let length = 64
    private static let prefixes = ["KHANDAQ:GROUP:", "KHANDAQ://GROUP/"]

    /// The characters after a known prefix, upper case, without the spacing; and whether a prefix was there.
    /// Full-width letters and digits (a Chinese or Japanese keyboard) become the ordinary ones.
    static func clean(_ raw: String) -> (text: String, prefixed: Bool) {
        let joined = raw.components(separatedBy: .whitespacesAndNewlines).joined()
        var text = (joined.applyingTransform(.fullwidthToHalfwidth, reverse: false) ?? joined).uppercased()
        for prefix in prefixes where text.hasPrefix(prefix) {
            text = String(text.dropFirst(prefix.count))
            return (text, true)
        }
        return (text, false)
    }

    /// Only 0-9 and A-F: toxcore reads the key byte by byte.
    static func isHex(_ text: String) -> Bool {
        return text.allSatisfy { $0.isASCII && $0.isHexDigit }
    }

    /// A whole key in the command line, only when it says it is a group's: 64 bare characters may also
    /// be the start of a person's ID, and the line offers both then (ChatListController).
    static func command(_ raw: String) -> String? {
        let cleaned = clean(raw)
        guard cleaned.prefixed, cleaned.text.count == length, isHex(cleaned.text) else {
            return nil
        }
        return cleaned.text
    }
}

/// What the key field of "Join a group" holds: nothing yet, the start of a key, a person's ID, something
/// that is not a key, a group already on this device, or a key to join with.
enum AirGroupKeyVerdict: Equatable {
    case empty
    /// Hex characters so far, fewer than a key has.
    case partial(Int)
    case personId
    case notKey
    case member(String)
    case ok(String)

    static func of(_ raw: String, isMember: (String) -> Bool) -> AirGroupKeyVerdict {
        let text = AirGroupKey.clean(raw).text
        guard !text.isEmpty else {
            return .empty
        }
        guard AirGroupKey.isHex(text) else {
            return .notKey
        }
        if text.count == AirToxId.length {
            return .personId
        }
        if text.count < AirGroupKey.length {
            return .partial(text.count)
        }
        guard text.count == AirGroupKey.length else {
            return .notKey
        }
        return isMember(text) ? .member(text) : .ok(text)
    }

    var key: String? {
        if case .ok(let key) = self {
            return key
        }
        return nil
    }

    /// The line under the field; nil keeps the hint.
    var note: String? {
        switch self {
            case .empty, .ok:
                return nil
            case .partial(let typed):
                return String(localized: "air_join_key_partial_format", typed)
            case .personId:
                return String(localized: "air_join_key_person")
            case .notKey:
                return String(localized: "air_join_key_not_key")
            case .member:
                return String(localized: "air_join_key_member")
        }
    }
}

/// The words of a join that toxcore refused at once. A wrong password or a full group shows only
/// later, when the group answers (ActiveSessionCoordinator).
enum AirGroupJoinError {
    static func text(for error: NSError?) -> String {
        switch error.flatMap({ OCTToxErrorGroupJoin(rawValue: $0.code) }) {
            case .some(.badChatId):
                return String(localized: "air_join_key_not_key")
            case .some(.password):
                return String(localized: "group_password_too_long")
            default:
                return String(localized: "group_join_failed")
        }
    }
}
