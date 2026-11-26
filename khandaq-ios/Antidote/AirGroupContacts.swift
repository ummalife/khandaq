// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import SnapKit
import UIKit

// KHANDAQ (26.11.2026, DESIGN-group-contacts-private-mentions.md): a member of a group to contacts. The key a group
// shows for a member is theirs in that group only (toxcore makes a key pair for every group), so it is not the key of
// their ID and no contact can be made of it. "Add to contacts" asks the member over the group instead
// (OCTSubmanagerGroups.sendContactOfferToMember), and they accept it as a contact request.

enum AirGroupContactOffer {
    /// Asks the member with `memberKey` (their key in the group) to exchange IDs; `toast` says what came of it.
    static func send(toMemberKey memberKey: String,
                     name: String,
                     in chat: OCTChat,
                     submanagerGroups: OCTSubmanagerGroups,
                     toast: (String) -> Void) {
        guard !chat.isInvalidated, !memberKey.isEmpty else {
            return
        }
        let result = submanagerGroups.sendContactOfferToMember(withPublicKeyHex: memberKey, in: chat)
        toast(text(for: result, name: name))
    }

    /// What the place the offer was made from says about it.
    static func text(for result: OCTGroupContactOfferResult, name: String) -> String {
        switch result {
            case .sent:
                return String(localized: "air_group_contact_request_sent_format", name)
            case .memberOffline:
                return String(localized: "air_group_contact_offline_format", name)
            case .alreadyContact:
                return String(localized: "air_group_contact_now_format", name)
            case .failed:
                return String(localized: "air_group_contact_failed")
        }
    }

    /// The contact behind a member, once the two of them exchanged their IDs.
    static func contact(ofMemberKey memberKey: String,
                        in chat: OCTChat,
                        submanagerGroups: OCTSubmanagerGroups,
                        submanagerObjects: OCTSubmanagerObjects) -> OCTFriend? {
        guard !memberKey.isEmpty, !chat.isInvalidated,
              let contactKey = submanagerGroups.contactPublicKey(forMemberPublicKeyHex: memberKey, in: chat) else {
            return nil
        }
        let found = submanagerObjects.friends(predicate: NSPredicate(format: "publicKey ==[c] %@", contactKey))
        return found.count > 0 ? found.firstObject : nil
    }
}

/// A member of one of our groups whose key in that group is the 64 characters on the New contact line.
struct AirGroupMemberMatch: Equatable {
    let memberKey: String
    let name: String
    let groupName: String
    let chat: OCTChat

    /// The member of a group of ours with the key `hex` there (64 hex characters), you left out; nil for any other
    /// text.
    static func find(hex: String, submanagerObjects: OCTSubmanagerObjects, submanagerGroups: OCTSubmanagerGroups) -> AirGroupMemberMatch? {
        guard hex.count == AirGroupKey.length, hex.allSatisfy({ $0.isHexDigit }) else {
            return nil
        }
        guard let peers = submanagerObjects.objects(for: .groupPeer, predicate: NSPredicate(format: "peerPublicKeyHex ==[c] %@", hex)) else {
            return nil
        }
        for index in 0..<peers.count {
            guard let peer = peers.object(at: index) as? OCTGroupPeer, !peer.isInvalidated,
                  let chat = submanagerObjects.object(withUniqueIdentifier: peer.chatUniqueIdentifier, for: .chat) as? OCTChat,
                  !chat.isInvalidated, chat.isGroup,
                  !AirGroupMembers.isSelfPeer(UInt32(bitPattern: peer.peerId),
                                              selfPeerId: submanagerGroups.groupSelfPeerId(for: chat),
                                              groupNumber: chat.groupNumber) else {
                continue
            }
            let stored = peer.peerName ?? ""
            let name = stored.isEmpty ? String(localized: "group_peer_fallback_format", peer.peerId) : stored
            let title = ChatListContent.groupTitle(for: chat)
            return AirGroupMemberMatch(memberKey: hex.uppercased(),
                                       name: name,
                                       groupName: title.isEmpty ? String(localized: "group_chat_default_title") : title,
                                       chat: chat)
        }
        return nil
    }

    /// What the line says of the member: "«B» in the group «G»".
    var title: String {
        return String(localized: "air_cmd_member_format", name, groupName)
    }

    static func == (left: AirGroupMemberMatch, right: AirGroupMemberMatch) -> Bool {
        return left.memberKey == right.memberKey && left.name == right.name && left.groupName == right.groupName
            && left.chat.uniqueIdentifier == right.chat.uniqueIdentifier
    }
}

/// On a member's own screen, while they are not a contact: their key in this group with Copy, what it is not, and
/// Add to contacts.
final class AirGroupMemberKeyView: UIView {
    var onCopy: (() -> Void)?
    var onAdd: (() -> Void)?

    private let titleLabel = AirKit.label(.caption, color: KQColor.ink3, lines: 0)
    private let keyLabel = AirKit.label(.mono, color: KQColor.ink2, lines: 0)
    private let noteLabel = AirKit.label(.caption, color: KQColor.ink3, lines: 0)
    private let copyButton = AirButton(style: .normal, title: String(localized: "air_nc_copy"), icon: "copy")
    private let addButton = AirButton(style: .ink, title: String(localized: "air_group_add_contact"), icon: "user-plus")
    private var buttons: UIStackView!

    override init(frame: CGRect) {
        super.init(frame: frame)
        accessibilityIdentifier = "air.group.private.memberKey"
        titleLabel.text = String(localized: "air_group_member_key_title")
        titleLabel.accessibilityTraits = UIAccessibilityTraitHeader
        noteLabel.text = String(localized: "air_group_member_key_note")
        keyLabel.accessibilityIdentifier = "air.group.private.memberKey.value"
        copyButton.accessibilityIdentifier = "air.group.private.memberKey.copy"
        copyButton.addTarget(self, action: #selector(copyTapped), for: .touchUpInside)
        addButton.accessibilityIdentifier = "air.group.private.addContact"
        addButton.addTarget(self, action: #selector(addTapped), for: .touchUpInside)
        buttons = AirKit.buttonRow([copyButton, addButton], traits: traitCollection)

        let column = AirKit.column([titleLabel, keyLabel, buttons, noteLabel], spacing: KQSpace.x8)
        addSubview(column)
        column.snp.makeConstraints {
            $0.edges.equalToSuperview()
        }
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        buttons.axis = traitCollection.preferredContentSizeCategory.isAccessibilityCategory ? .vertical : .horizontal
    }

    /// The key in groups of four, on lines of four groups.
    func configure(key: String) {
        keyLabel.text = AirKeyFormat.lines(key)
        keyLabel.accessibilityLabel = String(localized: "air_group_member_key_title")
        keyLabel.accessibilityValue = AirKeyFormat.groups(key).joined(separator: " ")
    }

    @objc private func copyTapped() {
        onCopy?()
    }

    @objc private func addTapped() {
        onAdd?()
    }
}
