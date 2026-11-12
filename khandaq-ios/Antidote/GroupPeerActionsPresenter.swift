// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit

enum GroupMemberStatusFormatter {
    static func onlineStatusLine(for peer: OCTGroupPeer, chat: OCTChat, submanagerGroups: OCTSubmanagerGroups) -> String {
        if submanagerGroups.isGroupPeerOnline(withId: UInt32(peer.peerId), in: chat) {
            return String(localized: "group_info_member_online")
        }

        let lastSeen = submanagerGroups.groupPeerLastSeenDateInterval(forPeerId: UInt32(peer.peerId), in: chat)
        if lastSeen > 0 {
            return formatLastSeen(lastSeen)
        }

        return String(localized: "group_member_status_offline")
    }

    static func detailSubtitle(for peer: OCTGroupPeer, chat: OCTChat, submanagerGroups: OCTSubmanagerGroups) -> String {
        let role = submanagerGroups.peerRole(withId: UInt32(peer.peerId), in: chat)
        var parts = [role.groupLocalizedName, onlineStatusLine(for: peer, chat: chat, submanagerGroups: submanagerGroups)]

        let unread = submanagerGroups.unreadPrivateMessageCount(forPeerId: UInt32(peer.peerId), in: chat)
        if unread > 0 {
            parts.append(String(localized: "group_peer_private_unread_format", unread))
        }

        if peer.peerNotificationsSilent {
            parts.append(String(localized: "group_peer_notifications_muted"))
        }

        // A comma, not a middle dot: the brief bans pseudo-graphics in the interface (E21 pass).
        return parts.joined(separator: ", ")
    }

    static func formatLastSeen(_ dateInterval: TimeInterval) -> String {
        let delta = Date().timeIntervalSince1970 - dateInterval

        if delta < 60 {
            return String(localized: "group_member_last_seen_just_now")
        }
        if delta < 3600 {
            let minutes = max(1, Int(delta / 60))
            return String(localized: "group_member_last_seen_minutes_format", minutes)
        }
        if delta < 86400 {
            let hours = max(1, Int(delta / 3600))
            return String(localized: "group_member_last_seen_hours_format", hours)
        }
        return String(localized: "group_member_last_seen_long_ago")
    }
}

enum GroupPeerActionsPresenter {
    static func presentActions(from viewController: UIViewController,
                               theme: Theme,
                               chat: OCTChat,
                               peer: OCTGroupPeer,
                               submanagerGroups: OCTSubmanagerGroups,
                               submanagerObjects: OCTSubmanagerObjects,
                               submanagerFriends: OCTSubmanagerFriends?,
                               popoverSourceView: UIView?,
                               popoverSourceRect: CGRect,
                               onOpenFriend: ((OCTFriend) -> Void)?,
                               onPeersChanged: (() -> Void)?) {
        let selfPeerId = submanagerGroups.groupSelfPeerId(for: chat)
        if selfPeerId > 0 && UInt32(peer.peerId) == selfPeerId {
            return
        }

        let peerName = peer.peerName ?? String(localized: "group_peer_fallback_format", peer.peerId)
        let alert = UIAlertController(title: peerName, message: nil, preferredStyle: .actionSheet)

        let unread = submanagerGroups.unreadPrivateMessageCount(forPeerId: UInt32(peer.peerId), in: chat)
        let privateTitle = unread > 0
            ? String(localized: "group_private_message_unread_action_format", unread)
            : String(localized: "group_private_message_action")
        alert.addAction(UIAlertAction(title: privateTitle, style: .default) { _ in
            let controller = GroupPeerPrivateController(theme: theme,
                                                        chat: chat,
                                                        peer: peer,
                                                        submanagerGroups: submanagerGroups,
                                                        submanagerObjects: submanagerObjects)
            viewController.navigationController?.pushViewController(controller, animated: true)
        })

        if let pubkey = peer.peerPublicKeyHex, !pubkey.isEmpty {
            if let friend = friend(withPublicKeyHex: pubkey, submanagerObjects: submanagerObjects),
               let onOpenFriend = onOpenFriend {
                alert.addAction(UIAlertAction(title: String(localized: "group_member_action_open_chat"), style: .default) { _ in
                    onOpenFriend(friend)
                })
            }
            else if let submanagerFriends = submanagerFriends {
                alert.addAction(UIAlertAction(title: String(localized: "group_member_action_add_friend"), style: .default) { _ in
                    do {
                        // KHANDAQ (#15): a group peer exposes only a 64-hex public key, not a full
                        // 76-hex Tox ID, so sendFriendRequest(toAddress:) rejected it ("ошибка на всех").
                        // Add by public key (tox_friend_add_norequest) instead.
                        try submanagerFriends.addFriend(byPublicKey: pubkey.uppercased())
                        // E21: a confirmation is a toast, not a dialog to dismiss.
                        AirToast.show(String(localized: "group_member_action_add_friend_sent"), in: viewController.view)
                    }
                    catch let error as NSError {
                        handleErrorWithType(.toxAddFriend, error: error)
                    }
                })
            }

            alert.addAction(UIAlertAction(title: String(localized: "group_member_action_copy_id"), style: .default) { _ in
                UIPasteboard.general.string = pubkey.uppercased()
                AirToast.show(String(localized: "group_member_action_copy_done"), in: viewController.view)
            })
        }

        if peer.peerNotificationsSilent {
            alert.addAction(UIAlertAction(title: String(localized: "group_peer_notifications_unmute"), style: .default) { _ in
                submanagerGroups.setPeerNotificationsSilent(false, peerId: UInt32(peer.peerId), for: chat)
                onPeersChanged?()
            })
        }
        else {
            alert.addAction(UIAlertAction(title: String(localized: "group_peer_notifications_mute"), style: .default) { _ in
                submanagerGroups.setPeerNotificationsSilent(true, peerId: UInt32(peer.peerId), for: chat)
                onPeersChanged?()
            })
        }

        if submanagerGroups.canKickPeer(withId: UInt32(peer.peerId), in: chat) {
            alert.addAction(UIAlertAction(title: String(localized: "group_peer_kick"), style: .destructive) { _ in
                confirmKick(from: viewController,
                            peer: peer,
                            displayName: peerName,
                            chat: chat,
                            submanagerGroups: submanagerGroups,
                            onPeersChanged: onPeersChanged)
            })
        }

        addRoleChangeActions(to: alert,
                             peer: peer,
                             chat: chat,
                             submanagerGroups: submanagerGroups,
                             viewController: viewController,
                             onPeersChanged: onPeersChanged)

        alert.addAction(UIAlertAction(title: String(localized: "alert_cancel"), style: .cancel, handler: nil))

        if let popover = alert.popoverPresentationController, let sourceView = popoverSourceView {
            popover.sourceView = sourceView
            popover.sourceRect = popoverSourceRect
        }

        viewController.present(alert, animated: true, completion: nil)
    }

    private static func friend(withPublicKeyHex pubkey: String, submanagerObjects: OCTSubmanagerObjects) -> OCTFriend? {
        let normalized = pubkey.uppercased()
        let friends = submanagerObjects.friends()

        for index in 0..<friends.count {
            guard let friend = friends[index] as? OCTFriend else {
                continue
            }
            if friend.publicKey.uppercased() == normalized {
                return friend
            }
        }

        return nil
    }

    private static func addRoleChangeActions(to alert: UIAlertController,
                                             peer: OCTGroupPeer,
                                             chat: OCTChat,
                                             submanagerGroups: OCTSubmanagerGroups,
                                             viewController: UIViewController,
                                             onPeersChanged: (() -> Void)?) {
        let peerId = UInt32(peer.peerId)
        let currentRole = submanagerGroups.peerRole(withId: peerId, in: chat)
        let options: [(OCTToxGroupRole, String)] = [
            (.moderator, String(localized: "group_role_make_moderator")),
            (.user, String(localized: "group_role_make_user")),
            (.observer, String(localized: "group_role_make_observer")),
        ]

        for (role, title) in options {
            guard role != currentRole, submanagerGroups.canSetPeerRole(role, peerId: peerId, in: chat) else {
                continue
            }

            alert.addAction(UIAlertAction(title: title, style: .default) { _ in
                do {
                    try submanagerGroups.setPeerRole(role, peerId: peerId, in: chat)
                    onPeersChanged?()
                }
                catch let error as NSError {
                    handleErrorWithType(.setGroupPeerRole, error: error)
                }
            })
        }
    }

    private static func confirmKick(from viewController: UIViewController,
                                    peer: OCTGroupPeer,
                                    displayName: String,
                                    chat: OCTChat,
                                    submanagerGroups: OCTSubmanagerGroups,
                                    onPeersChanged: (() -> Void)?) {
        // E21: from below, what happens in one sentence, the verb on the button; the words of the
        // group dossier (E14).
        let words = AirGroupMemberAction.kick.confirmation(name: displayName)
        let sheet = AirConfirm.sheet(title: words.title,
                                     message: words.message,
                                     verb: AirGroupMemberAction.kick.title,
                                     source: viewController.view) {
            do {
                try submanagerGroups.kickPeer(withId: UInt32(peer.peerId), in: chat)
                onPeersChanged?()
            }
            catch let error as NSError {
                handleErrorWithType(.kickGroupPeer, error: error)
            }
        }

        viewController.present(sheet, animated: true, completion: nil)
    }
}

extension OCTToxGroupRole {
    var groupLocalizedName: String {
        switch self {
            case .founder:
                return String(localized: "group_role_founder")
            case .moderator:
                return String(localized: "group_role_moderator")
            case .user:
                return String(localized: "group_role_user")
            case .observer:
                return String(localized: "group_role_observer")
        }
    }
}
