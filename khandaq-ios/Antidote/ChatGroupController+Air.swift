// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import SnapKit
import UIKit

// The group conversation of the redesign (design/redesign/02-plan.md, E13): the group's name in the
// header, the band under it with "9 members, 4 online now" and the tiles of who gets a message right
// now, the field with the line about who gets it and the length counter, and, when you may only read,
// a bar that says why and whom to ask instead of a field that refuses on send. The transcript draws
// the author of each run in the tone of that member and tints the rows where someone named you.

extension ChatGroupController {
    // MARK: Views

    /// The header, the band, the pinned strip and the read-only bar; ChatGroupController.loadView lays
    /// them out.
    func createAirGroupViews() {
        groupTitleView = ChatPrivateTitleView()
        groupTitleView.onSizeChanged = { [weak self] in
            self?.installGroupTitleItem()
        }
        groupTitleView.addTarget(self, action: #selector(ChatGroupController.groupTitleTapped), for: .touchUpInside)
        // The name is a bar item next to the back button; the centre of the bar stays empty.
        navigationItem.titleView = UIView()

        groupBand = AirGroupBandView()
        groupBand.onOpen = { [weak self] in
            self?.infoButtonPressed()
        }

        pinnedStrip = AirPinnedStripView()
        pinnedStrip.isHidden = true
        pinnedStrip.addTarget(self, action: #selector(ChatGroupController.pinnedBannerTapped), for: .touchUpInside)
        pinnedStrip.closeButton.addTarget(self, action: #selector(ChatGroupController.pinnedBannerClosePressed), for: .touchUpInside)

        groupTopStack = UIStackView(arrangedSubviews: [groupBand, pinnedStrip])
        groupTopStack.axis = .vertical
        view.addSubview(groupTopStack)

        readOnlyBar = AirGroupReadOnlyBar()
        readOnlyBar.isHidden = true
        readOnlyBar.onAsk = { [weak self] in
            self?.askModerator()
        }
        view.addSubview(readOnlyBar)
    }

    /// The tile and the name, left-aligned next to the back button, as in the conversation with one
    /// person. Skipped while the selection toolbar owns the bar.
    func installGroupTitleItem() {
        guard tableView?.isEditing != true, let titleView = groupTitleView else {
            return
        }
        navigationItem.leftItemsSupplementBackButton = true
        navigationItem.leftBarButtonItems = [UIBarButtonItem(customView: titleView)]
    }

    /// The name runs as wide as the bar leaves it next to the back button and the buttons.
    func updateGroupTitleWidth() {
        guard let titleView = groupTitleView, view.bounds.width > 0 else {
            return
        }
        let slot = KQSize.targetIos + KQSpace.x8
        let items = CGFloat(navigationItem.rightBarButtonItems?.count ?? 0)
        let titleChrome = KQSize.tileDesk + KQSpace.x8 + KQSpace.x4 + KQSize.iconSmall + 2 * KQSpace.x4
        let available = view.bounds.width - slot - items * slot - titleChrome - KQSpace.x16
        titleView.maximumNameWidth = max(2 * KQSize.tilePhone, floor(available))
    }

    @objc func groupTitleTapped() {
        infoButtonPressed()
    }

    // MARK: State

    func startAirGroup() {
        let center = NotificationCenter.default
        let chatId = chat.uniqueIdentifier
        groupAirObservers.append(center.addObserver(forName: .octGroupPeersUpdated, object: nil, queue: .main) { [weak self] note in
            if let id = note.userInfo?[kOCTGroupPeersUpdatedChatUniqueIdentifierKey] as? String, id != chatId {
                return
            }
            self?.refreshGroupAir(animated: true)
        })
        groupAirObservers.append(center.addObserver(forName: .UIContentSizeCategoryDidChange, object: nil, queue: .main) { [weak self] _ in
            // The time column and the fonts of the entries follow the text size.
            self?.tableView?.reloadData()
            self?.updateGroupTitleWidth()
        })
        groupAirObservers.append(center.addObserver(forName: AirSelfNetwork.didChange, object: nil, queue: .main) { [weak self] _ in
            // Our own node went off or came back (E4): the hint under the field says which.
            self?.refreshGroupAir(animated: true)
        })
        installGroupTitleItem()
        refreshGroupAir(animated: false)
    }

    /// The header, the band and the field from the members, the connection and your role.
    func refreshGroupAir(animated: Bool) {
        guard isViewLoaded, !chat.isInvalidated else {
            return
        }
        let members = AirGroupMembers.load(chat: chat, submanagerGroups: submanagerGroups)
        let mentionsBefore = (AirGroupMembers.selfKey(in: groupMembers), selfGroupName)
        groupMembers = members

        let connected = submanagerGroups.isGroupConnected(for: chat)
        let reach = AirGroupReach(peerCount: Int(submanagerGroups.peerCount(for: chat)),
                                  knownMembers: members.count,
                                  online: Int(submanagerGroups.onlineGroupPeerCount(for: chat)),
                                  connected: connected)
        var voiceError: NSError?
        let voice = submanagerGroups.groupVoiceState(for: chat, error: &voiceError)
        let right = AirGroupComposeRight.make(selfRole: submanagerGroups.selfRole(in: chat), voiceState: voice)
        groupComposeRight = right

        let stored = ChatListContent.groupTitle(for: chat)
        let name = stored.isEmpty ? String(localized: "group_chat_default_title") : stored
        title = name
        groupTitleView.configure(name: name,
                                 style: .group(initials: AirInitials.from(name), tone: AirTone.index(for: chat.uniqueIdentifier)),
                                 photo: nil)
        groupTitleView.spokenPresence = reach.headerText

        groupBand.configure(reach: reach,
                            reachable: connected ? AirGroupRoster.reachableNow(members) : [],
                            showsReach: right == .write,
                            animated: animated)

        if let text = right.readOnlyText {
            readOnlyBar.configure(text: text,
                                  askTitle: right.askTitle,
                                  canAsk: !AirGroupRoster.askable(members, right: right).isEmpty,
                                  note: right.privateRefusalText)
            setGroupReadOnly(true)
        }
        else {
            setGroupReadOnly(false)
            chatInputView.hintView?.setHint(AirComposeHint.ownNode(AirSelfNetwork.state) ?? reach.hint, animated: animated)
            updateGroupComposeLimit()
        }
        updateGroupTitleWidth()

        // Your key or name arrived after the rows were drawn: the mentions of you are tinted now.
        if mentionsBefore.0 != AirGroupMembers.selfKey(in: members) || mentionsBefore.1 != selfGroupName {
            tableView?.reloadRows(at: tableView?.indexPathsForVisibleRows ?? [], with: .none)
        }
    }

    /// Your name in this group, for the mentions of you written by name only.
    var selfGroupName: String? {
        if let own = groupMembers.first(where: { $0.isSelf })?.name, !own.isEmpty {
            return own
        }
        let name = (try? submanagerGroups.groupSelfPeerName(for: chat)) ?? ""
        return name.isEmpty ? nil : name
    }

    /// The counter once a tenth of the room is left; over the limit the send button waits. The reply
    /// header travels in the same message.
    func updateGroupComposeLimit() {
        guard let hintView = chatInputView?.hintView else {
            return
        }
        let text = chatInputView.text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else {
            hintView.setCounter(remaining: nil)
            chatInputView.sendBlocked = false
            return
        }
        let bytes = MessageReplyHelper.encode(replyController.pendingMeta, bodyText: text).utf8.count
        let remaining = AirGroupComposeLimit.remaining(forBytes: bytes)
        hintView.setCounter(remaining: remaining)
        chatInputView.sendBlocked = (remaining ?? 0) < 0
    }

    // MARK: Asking for the right to write

    /// "Write to a moderator" (or "the founder"), offered while only the moderators or the founder write:
    /// the private messages of the group, to the one who can be asked, or a choice among them when there
    /// are several. An observer has no such button: toxcore refuses an observer's private messages.
    func askModerator() {
        let moderators = AirGroupRoster.askable(groupMembers, right: groupComposeRight)
        guard !moderators.isEmpty else {
            return
        }
        if moderators.count == 1, let only = moderators.first {
            openPrivateThread(with: only)
            return
        }
        let sheet = UIAlertController(title: groupComposeRight.askTitle, message: nil, preferredStyle: .actionSheet)
        let now = Date().timeIntervalSince1970
        for member in moderators {
            let title = member.name + ", " + AirGroupRoster.statusLine(for: member, now: now)
            sheet.addAction(UIAlertAction(title: title, style: .default) { [weak self] _ in
                self?.openPrivateThread(with: member)
            })
        }
        sheet.addAction(UIAlertAction(title: String(localized: "alert_cancel"), style: .cancel, handler: nil))
        sheet.popoverPresentationController?.sourceView = readOnlyBar
        sheet.popoverPresentationController?.sourceRect = readOnlyBar.bounds
        present(sheet, animated: true, completion: nil)
    }

    private func openPrivateThread(with member: AirGroupMember) {
        guard let peers = submanagerGroups.peers(for: chat) else {
            return
        }
        for index in 0..<peers.count {
            guard let peer = peers.object(at: index) as? OCTGroupPeer, !peer.isInvalidated,
                  UInt32(bitPattern: peer.peerId) == member.peerId else {
                continue
            }
            let controller = GroupPeerPrivateController(theme: theme,
                                                        chat: chat,
                                                        peer: peer,
                                                        submanagerGroups: submanagerGroups,
                                                        submanagerObjects: submanagerObjects)
            navigationController?.pushViewController(controller, animated: true)
            return
        }
    }

    // MARK: Transcript

    /// The author over the first entry of a run, in the tone of that member, and who wrote it for
    /// VoiceOver on every entry.
    func configureGroupEntry(_ entry: ChatMovableDateCellModel, message: OCTMessageAbstract, row: Int) {
        let label: String
        if message.isOutgoing() {
            label = String(localized: "air_you")
            entry.authorColor = KQColor.ink2
            entry.spokenAuthor = label
        }
        else if !submanagerGroups.isGroupMessageAttributable(message, in: chat) {
            // KHANDAQ (re-review 2026-08-22, KQ-03): a relayed row naming an author whose signing key has
            // gone stale is kept but not attributed: the name is replaced rather than annotated.
            label = String(localized: "air_group_author_unconfirmed")
            entry.authorColor = KQColor.ink2
            entry.spokenAuthor = label
        }
        else {
            // peerName names an unknown peer number itself; nil is a relayed row with neither a number
            // nor a name frozen on it.
            let name = peerName(for: message) ?? String(localized: "air_group_author_unknown")
            let role: OCTToxGroupRole? = message.groupSenderPeerId > 0
                ? submanagerGroups.peerRole(withId: UInt32(bitPattern: message.groupSenderPeerId), in: chat)
                : nil
            label = AirGroupTranscript.authorLabel(name: name, role: role)
            entry.authorColor = AirTone.color(at: AirTone.index(for: groupToneKey(for: message, name: name)))
            entry.spokenAuthor = label
        }

        let previous = row + 1 < displayableRowCount() ? groupRunKey(forDisplayIndex: row + 1) : nil
        let startsRun = entry.unreadSeparator || AirTranscript.startsRun(groupRunKey(forDisplayIndex: row), after: previous)
        entry.authorText = startsRun ? label : nil
    }

    /// Whether a message names you, by your key in this group or by your name.
    func groupMessageMentionsMe(_ mentions: [GroupMentionHelper.MentionEntry]) -> Bool {
        guard !mentions.isEmpty else {
            return false
        }
        return AirGroupTranscript.mentionsMe(mentions, selfKey: AirGroupMembers.selfKey(in: groupMembers), selfName: selfGroupName)
    }

    /// The member's tone comes from their key in the group, as on their tile in the band and the
    /// dossier; from the name when an older row has no key.
    private func groupToneKey(for message: OCTMessageAbstract, name: String) -> String {
        let key = (message.messageText?.groupSenderPubkey ?? "").uppercased()
        return key.isEmpty ? name : key
    }

    private func groupRunKey(forDisplayIndex row: Int) -> AirRunKey {
        let entry = messageEntry(atDisplayIndex: row)
        let message = entry.message
        let author: String
        if message.isOutgoing() {
            author = ""
        }
        else if let key = message.messageText?.groupSenderPubkey, !key.isEmpty {
            author = key.uppercased()
        }
        else {
            author = peerName(for: message) ?? "peer\(message.groupSenderPeerId)"
        }
        // A caption drawn inside its photo is not a text entry of its own.
        let merged = messageSearchQuery.isEmpty && isMergedCaptionRow(storageIndex: entry.storageIndex)
        let isText = message.messageText != nil && !message.groupSystemMessage && !merged
        return AirRunKey(author: author, date: message.date(), isText: isText)
    }
}
