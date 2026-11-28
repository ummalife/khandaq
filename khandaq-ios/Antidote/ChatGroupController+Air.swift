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

        networkBand = AirNetworkBandView()
        networkBand.layoutHost = { [weak self] in
            self?.view.layoutIfNeeded()
        }

        groupBand = AirGroupBandView()
        groupBand.onOpen = { [weak self] in
            self?.infoButtonPressed()
        }

        pinnedStrip = AirPinnedStripView()
        pinnedStrip.isHidden = true
        pinnedStrip.addTarget(self, action: #selector(ChatGroupController.pinnedBannerTapped), for: .touchUpInside)
        pinnedStrip.closeButton.addTarget(self, action: #selector(ChatGroupController.pinnedBannerClosePressed), for: .touchUpInside)

        groupTopStack = UIStackView(arrangedSubviews: [networkBand, groupBand, pinnedStrip])
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
        groupAirObservers.append(center.addObserver(forName: AirNetworkNotice.didChange, object: nil, queue: .main) { [weak self] _ in
            self?.networkBand.follow(AirNetworkNotice.shared, animated: true)
        })
        installGroupTitleItem()
        networkBand.follow(AirNetworkNotice.shared, animated: false)
        refreshGroupAir(animated: false)
    }

    /// The header, the band and the field from the members, the connection and your role.
    func refreshGroupAir(animated: Bool) {
        guard isViewLoaded, !chat.isInvalidated else {
            return
        }
        let members = AirGroupMembers.load(chat: chat, submanagerGroups: submanagerGroups)
        let mentionsBefore = (AirGroupMembers.selfKey(in: groupMembers), selfGroupName)
        let authorsBefore = AirGroupTranscript.authorFacts(of: groupMembers)
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
                                 style: KhandaqCommunity.isCommunity(chat)
                                     ? .community
                                     : .group(initials: AirInitials.from(name), tone: AirTone.index(for: chat.uniqueIdentifier)),
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
        // KHANDAQ (0.2.63): and the role over an entry is its author's among the members now, so the rows are drawn
        // again when who the founders and moderators are changes, the members a group gets once it connects among
        // them.
        if mentionsBefore.0 != AirGroupMembers.selfKey(in: members) || mentionsBefore.1 != selfGroupName
            || authorsBefore != AirGroupTranscript.authorFacts(of: members) {
            tableView?.reloadVisibleRowsKeepingSelection()
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

    /// The counter once a tenth of the room is left, then how many messages a longer text goes as:
    /// ChatGroupInputViewManager sends it in parts. The reply header travels in the first one.
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
        // KHANDAQ (26.11.2026): the block of the members it mentions travels in the first part with the reply's header.
        let head = MessageReplyHelper.encode(replyController.pendingMeta, bodyText: "") + mentionBlock(for: text)
        hintView.setCounter(text: AirComposeLimit.counterText(for: text, head: head, maxBytes: AirGroupComposeLimit.bytes))
        chatInputView.sendBlocked = false
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

    /// KHANDAQ (26.11.2026, DESIGN-group-contacts-private-mentions.md): the other one of the two of a private message
    /// (the author of one we got, the recipient of ours), by the key the row kept, since their peer number changes
    /// when they reconnect; by the peer number on an older row without one. Nil when they are not in the group now.
    func privateAnswerPeer(for message: OCTMessageAbstract) -> OCTGroupPeer? {
        guard message.groupPrivateMessage, !chat.isInvalidated, let peers = submanagerGroups.peers(for: chat) else {
            return nil
        }
        let key = (message.groupPrivatePeerPubkey ?? "").lowercased()
        let selfPeerId = submanagerGroups.groupSelfPeerId(for: chat)
        for index in 0..<peers.count {
            guard let peer = peers.object(at: index) as? OCTGroupPeer, !peer.isInvalidated,
                  !AirGroupMembers.isSelfPeer(UInt32(bitPattern: peer.peerId), selfPeerId: selfPeerId, groupNumber: chat.groupNumber) else {
                continue
            }
            let same = key.isEmpty
                ? peer.peerId == message.groupPrivatePeerId
                : (peer.peerPublicKeyHex ?? "").lowercased() == key
            if same {
                return peer
            }
        }
        return nil
    }

    /// Reply on a private entry: the conversation with the other one of the two, its field ready, where the answer
    /// goes to them alone.
    func openPrivateAnswer(to message: OCTMessageAbstract) {
        guard groupComposeRight.canWritePrivately, let peer = privateAnswerPeer(for: message) else {
            return
        }
        let controller = GroupPeerPrivateController(theme: theme,
                                                    chat: chat,
                                                    peer: peer,
                                                    submanagerGroups: submanagerGroups,
                                                    submanagerObjects: submanagerObjects)
        controller.focusesFieldOnAppear = true
        navigationController?.pushViewController(controller, animated: true)
    }

    // MARK: Transcript

    /// The author over the first entry of a run, in the tone of that member, and who wrote it for
    /// VoiceOver on every entry; beside the entries of another member, the column of their tile, which the
    /// last entry of a run shows.
    func configureGroupEntry(_ entry: ChatMovableDateCellModel, message: OCTMessageAbstract, row: Int) {
        let label: String
        // The name over another member's entry that it names, which opens that member (openAuthor).
        var opensAuthor: String?
        // KHANDAQ (tester 21.11, 8255): the tile of another member's entry, and whether it stands for an author
        // nobody confirmed; none for yours.
        var tile: AirTileView.Style?
        var unconfirmed = false
        if message.isOutgoing() {
            label = String(localized: "air_you")
            entry.authorColor = AirTone.you
            entry.spokenAuthor = label
        }
        else if !submanagerGroups.isGroupMessageAttributable(message, in: chat) {
            // KHANDAQ (re-review 2026-08-22, KQ-03): a relayed row naming an author whose signing key has
            // gone stale is kept but not attributed: the name is replaced rather than annotated.
            label = String(localized: "air_group_author_unconfirmed")
            entry.authorColor = KQColor.ink2
            entry.spokenAuthor = label
            tile = .unconfirmed
            unconfirmed = true
        }
        else {
            // peerName names an unknown peer number itself; nil is a relayed row with neither a number
            // nor a name frozen on it.
            let knownName = peerName(for: message)
            let name = knownName ?? String(localized: "air_group_author_unknown")
            // KHANDAQ (0.2.63): the role of the member who wrote it, by the key it kept, not of whoever has
            // its peer number now.
            let role = AirGroupTranscript.authorRole(key: AirGroupTranscript.authorKey(of: message),
                                                     peerId: message.groupSenderPeerId,
                                                     name: AirGroupTranscript.authorName(of: message),
                                                     among: groupMembers)
            label = AirGroupTranscript.authorLabel(name: name, role: role)
            entry.authorColor = AirTone.color(at: AirTone.index(for: groupToneKey(for: message, name: name)))
            entry.spokenAuthor = label
            // KHANDAQ (0.2.58): an entry that kept neither its author's key nor a peer number names nobody
            // the group could have now, and a key of yours is you.
            let key = AirGroupTranscript.authorKey(of: message).uppercased()
            let named = !key.isEmpty || message.groupSenderPeerId != 0
            if named && (key.isEmpty || key != AirGroupMembers.selfKey(in: groupMembers)?.uppercased()) {
                opensAuthor = name
            }
            // KHANDAQ (tester 21.11, 8255): the member's tile of the band and the dossier, by the name and the key
            // the entry kept; an entry with no name has the plain tile. KHANDAQ (owner, 28.11.2026): so has an entry
            // the history brought without a signature over it, which opens its author as the name over the run does.
            if knownName == nil {
                tile = .unconfirmed
            }
            else {
                tile = .person(initials: AirInitials.from(name), tone: AirTone.index(for: groupToneKey(for: message, name: name)))
            }
        }

        let current = groupRunKey(forDisplayIndex: row)
        let previous = row + 1 < displayableRowCount() ? groupRunKey(forDisplayIndex: row + 1) : nil
        let startsRun = entry.unreadSeparator || AirTranscript.startsRun(current, after: previous)
        entry.authorText = startsRun ? label : nil
        if startsRun, let name = opensAuthor {
            entry.onAuthorTap = { [weak self] source in
                self?.openAuthor(of: message, name: name, from: source)
            }
        }

        if let style = tile {
            // The entry after this one in reading order is the row above it in the flipped log.
            let next = row > 0 ? groupRunKey(forDisplayIndex: row - 1) : nil
            let nextUnderBand = row > 0 && unreadBand.marks(messageEntry(atDisplayIndex: row - 1).message.uniqueIdentifier)
            let onTap: ((UIView) -> Void)?
            if unconfirmed {
                // The tile of an author nobody confirmed opens nobody: the message may not be theirs.
                onTap = { [weak self] _ in
                    self?.sayAuthorUnconfirmed()
                }
            }
            else if let name = opensAuthor {
                // As the name over the first entry of the run.
                onTap = { [weak self] source in
                    self?.openAuthor(of: message, name: name, from: source)
                }
            }
            else {
                onTap = nil
            }
            entry.authorTile = AirAuthorTile(style: style,
                                             shows: AirTranscript.endsRun(current, before: next, nextUnderBand: nextUnderBand),
                                             onTap: onTap)
        }
    }

    /// KHANDAQ (tester 21.11, 8255): a tap on the tile of an author nobody confirmed (KQ-03) says so, instead of opening
    /// a member who may not have written it.
    func sayAuthorUnconfirmed() {
        guard tableView?.isEditing != true else {
            return
        }
        AirToast.show(String(localized: "group_msg_sender_unverified"), in: view, above: groupToastAnchor)
    }

    /// KHANDAQ (0.2.58): the actions on the member who wrote `message`, from the name over the entry: the sheet
    /// of the member's row in the dossier (AirGroupMemberSheet), pointing at the name. The member is the one
    /// the group has now (AirGroupTranscript.author); an author who is not in the group now is said so in a
    /// toast, by `name`, the name over the entry.
    func openAuthor(of message: OCTMessageAbstract, name: String, from source: UIView) {
        guard !message.isInvalidated, !chat.isInvalidated, tableView?.isEditing != true else {
            return
        }
        let members = AirGroupMembers.load(chat: chat, submanagerGroups: submanagerGroups)
        guard let member = AirGroupTranscript.author(key: AirGroupTranscript.authorKey(of: message),
                                                     peerId: message.groupSenderPeerId,
                                                     name: AirGroupTranscript.authorName(of: message),
                                                     among: members) else {
            AirToast.show(String(localized: "air_group_author_not_here_format", name), in: view, above: groupToastAnchor)
            return
        }
        authorSheet.present(for: member, from: source, rect: (source as? AirAuthorLabel)?.nameRect)
    }

    /// What a toast of the conversation stands above: the field, or the bar in its place while you may only
    /// read.
    var groupToastAnchor: UIView? {
        if let bar = readOnlyBar, !bar.isHidden {
            return bar
        }
        return chatInputView
    }

    /// KHANDAQ (26.11.2026, DESIGN-group-contacts-private-mentions.md): who sees a private message of the feed: the
    /// member ours went to, by the key it kept, or you.
    func privateNote(for message: OCTMessageAbstract) -> String {
        guard message.isOutgoing() else {
            return String(localized: "air_group_private_to_you")
        }
        let key = (message.groupPrivatePeerPubkey ?? "").uppercased()
        let name = key.isEmpty ? nil : groupMembers.first(where: { $0.publicKey == key })?.name
        return String(localized: "air_group_private_to_format", name ?? String(localized: "air_group_author_unknown"))
    }

    /// The member a mention in the feed names, from a tap on it: their sheet, or a toast when the group does not have
    /// them now. Nothing for a mention of you.
    func openMentioned(key: String, mentions: [GroupMentionHelper.MentionEntry], from source: UIView) {
        guard !chat.isInvalidated, tableView?.isEditing != true else {
            return
        }
        let wanted = key.uppercased()
        if let member = groupMembers.first(where: { $0.publicKey == wanted }) {
            if !member.isSelf {
                authorSheet.present(for: member, from: source)
            }
            return
        }
        let handle = mentions.first(where: { $0.pubkey.uppercased() == wanted })?.handle ?? ""
        AirToast.show(String(localized: "air_group_author_not_here_format", "@" + handle), in: view, above: groupToastAnchor)
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
        return AirGroupTranscript.toneKey(of: message, name: name)
    }

    private func groupRunKey(forDisplayIndex row: Int) -> AirRunKey {
        let entry = messageEntry(atDisplayIndex: row)
        let message = entry.message
        // A caption drawn inside its photo is not a text entry of its own.
        let merged = messageSearchQuery.isEmpty && isMergedCaptionRow(storageIndex: entry.storageIndex)
        let isText = message.messageText != nil && !message.groupSystemMessage && !merged
        return AirGroupTranscript.runKey(of: message, in: chat, groups: submanagerGroups, isText: isText)
    }
}

extension UITableView {
    /// KHANDAQ (0.2.63): the visible rows drawn again with what they say now, and the ones you had picked still
    /// picked. reloadRows takes the selection off every row it reloads, and the selection is all that Delete and
    /// Copy act on: the messages picked for them came unpicked whenever a verdict on the history, a member or your
    /// key arrived meanwhile.
    func reloadVisibleRowsKeepingSelection() {
        let visible = indexPathsForVisibleRows ?? []
        guard !visible.isEmpty else {
            return
        }
        let picked = Set(indexPathsForSelectedRows ?? []).intersection(visible)
        reloadRows(at: visible, with: .none)
        for indexPath in picked {
            selectRow(at: indexPath, animated: false, scrollPosition: .none)
        }
    }
}
