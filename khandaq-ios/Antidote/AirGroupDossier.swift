// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import SnapKit
import UIKit

/// The group dossier (E14): who gets a message now with their roles, your role and what it allows, the
/// group key with Copy, Invite and the QR code (or, in a group by invitation only, how members join,
/// with Invite), notifications, the history, the group settings and "Leave the group". The founder and
/// the moderators change roles and remove members from a member's sheet; every change is confirmed, and
/// a role change can be taken back from the toast.
final class AirGroupDossierController: AirScrollController {
    var onOpenFriend: ((OCTFriend) -> Void)?

    private let theme: Theme
    private let chat: OCTChat
    private let submanagerGroups: OCTSubmanagerGroups
    private let submanagerObjects: OCTSubmanagerObjects
    private let submanagerChats: OCTSubmanagerChats
    private let submanagerFiles: OCTSubmanagerFiles
    private let submanagerFriends: OCTSubmanagerFriends

    private var members: [AirGroupMember] = []
    private var query = ""
    private var observers: [NSObjectProtocol] = []
    private let membersHolder = UIStackView()
    private let membersNote = AirKit.note("")
    private var search: AirSearchField?
    private let muteSwitch = UISwitch()
    private let muteNote = AirKit.note("")
    private var memberRows: [AirGroupMemberRow] = []
    /// Who can join as the key section was last built for, and the watch on the stored privacy that
    /// rebuilds it once that changes.
    private var shownPrivacy: AirGroupPrivacy?
    private var privacyToken: RLMNotificationToken?
    /// The actions on a member, from the member's row.
    private lazy var memberSheet: AirGroupMemberSheet = {
        let sheet = AirGroupMemberSheet(host: self,
                                        theme: theme,
                                        chat: chat,
                                        submanagerGroups: submanagerGroups,
                                        submanagerObjects: submanagerObjects,
                                        submanagerFriends: submanagerFriends)
        sheet.onMembersChanged = { [weak self] in
            self?.reloadMembers(animated: true)
        }
        return sheet
    }()

    init(theme: Theme,
         chat: OCTChat,
         submanagerGroups: OCTSubmanagerGroups,
         submanagerObjects: OCTSubmanagerObjects,
         submanagerChats: OCTSubmanagerChats,
         submanagerFiles: OCTSubmanagerFiles,
         submanagerFriends: OCTSubmanagerFriends) {
        self.theme = theme
        self.chat = chat
        self.submanagerGroups = submanagerGroups
        self.submanagerObjects = submanagerObjects
        self.submanagerChats = submanagerChats
        self.submanagerFiles = submanagerFiles
        self.submanagerFriends = submanagerFriends
        super.init(nibName: nil, bundle: nil)
        hidesBottomBarWhenPushed = true
        title = String(localized: "air_group_dossier_title")
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    deinit {
        observers.forEach { NotificationCenter.default.removeObserver($0) }
        privacyToken?.invalidate()
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.accessibilityIdentifier = "air.group.dossier"
        membersHolder.axis = .vertical
        membersHolder.spacing = KQSpace.x8
        muteSwitch.onTintColor = KQColor.switchOn
        muteSwitch.accessibilityIdentifier = "air.group.dossier.mute"
        muteSwitch.accessibilityLabel = String(localized: "air_dossier_mute")
        muteSwitch.addTarget(self, action: #selector(muteChanged), for: .valueChanged)

        let center = NotificationCenter.default
        let chatId = chat.uniqueIdentifier
        observers.append(center.addObserver(forName: .octGroupPeersUpdated, object: nil, queue: .main) { [weak self] note in
            if let id = note.userInfo?[kOCTGroupPeersUpdatedChatUniqueIdentifierKey] as? String, id != chatId {
                return
            }
            self?.reloadMembers(animated: true)
        })
        observers.append(center.addObserver(forName: .octGroupConnectionStatusChange, object: nil, queue: .main) { [weak self] note in
            if let id = note.userInfo?[kOCTGroupConnectionStatusChangeChatUniqueIdentifierKey] as? String, id != chatId {
                return
            }
            self?.reloadMembers(animated: false)
        })
        build()
        observePrivacy()
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        guard !chat.isInvalidated else {
            navigationController?.popToRootViewController(animated: false)
            return
        }
        submanagerGroups.refreshPeers(for: chat)
        reloadMembers(animated: false)
        privacyMayHaveChanged()
    }

    // MARK: Building

    private var selfRole: OCTToxGroupRole {
        return submanagerGroups.selfRole(in: chat)
    }

    private var groupName: String {
        let name = ChatListContent.groupTitle(for: chat)
        return name.isEmpty ? String(localized: "group_chat_default_title") : name
    }

    private var chatIdHex: String {
        return ((try? submanagerGroups.chatIdHex(for: chat)) ?? chat.groupChatIdHex ?? "").uppercased()
    }

    private var privacy: AirGroupPrivacy {
        var error: NSError?
        return AirGroupPrivacy(state: submanagerGroups.groupPrivacyState(for: chat, error: &error))
    }

    private var composeRight: AirGroupComposeRight {
        var error: NSError?
        let voice = submanagerGroups.groupVoiceState(for: chat, error: &error)
        return AirGroupComposeRight.make(selfRole: selfRole, voiceState: voice)
    }

    private func build() {
        column.arrangedSubviews.forEach { $0.removeFromSuperview() }
        let role = selfRole

        // The group, and how many are here.
        let name = AirKit.label(.display, color: KQColor.ink, lines: 0)
        name.text = groupName
        name.accessibilityTraits = UIAccessibilityTraitHeader
        name.accessibilityIdentifier = "air.group.dossier.name"
        var head: [UIView] = [name]
        // The name the group was made with, under the topic it shows by; Khandaq Community has one name only.
        if !KhandaqCommunity.isCommunity(chat),
           let topic = chat.groupTopic, !topic.isEmpty, topic != chat.groupName, let original = chat.groupName, !original.isEmpty {
            head.append(AirKit.body(original))
        }
        let counts = AirKit.label(.label, weight: 400, color: KQColor.ink2, lines: 0)
        counts.font = KQFont.tabular(KQFont.font(.label, weight: 400))
        counts.accessibilityIdentifier = "air.group.dossier.counts"
        head.append(counts)
        column.addArrangedSubview(AirKit.column(head, spacing: KQSpace.x4))

        // Your role.
        let roleText = AirKit.body(AirGroupRoles.explanation(role))
        roleText.accessibilityIdentifier = "air.group.dossier.role"
        addSection(String(localized: "air_group_your_role"), views: [roleText])

        // The members.
        let heading = composeRight == .write
            ? String(localized: "air_group_who_gets_now")
            : String(localized: "air_group_members")
        var memberViews: [UIView] = [membersNote]
        let field = AirSearchField(placeholder: String(localized: "air_group_search_members"))
        field.accessibilityIdentifier = "air.group.dossier.search"
        field.onChange = { [weak self] text in
            self?.query = text
            self?.reloadMembers(animated: false)
        }
        search = field
        memberViews.append(field)
        memberViews.append(membersHolder)
        addSection(heading, views: memberViews)

        // The key. It is shown, copied and put in a QR code only while anyone with it can join: a group
        // by invitation only cannot be joined by its key, so instead it says how members join, with
        // Invite, and its founder can open it to anyone with the key from here.
        let key = chatIdHex
        let privacy = self.privacy
        shownPrivacy = privacy
        if !key.isEmpty {
            let invite = AirButton(style: .normal, title: String(localized: "air_group_invite"), icon: "user-plus")
            invite.accessibilityIdentifier = "air.group.dossier.invite"
            invite.addTarget(self, action: #selector(inviteTapped), for: .touchUpInside)
            if privacy == .inviteOnly {
                let line = AirKit.body(privacy.consequence)
                line.accessibilityIdentifier = "air.group.dossier.inviteOnly"
                var rows: [UIView] = []
                if role == .founder {
                    let open = AirGroupPrivacy.openById
                    rows.append(AirSettingRow.toggle(title: open.title,
                                                     detail: open.consequence,
                                                     isOn: false,
                                                     identifier: "air.group.dossier.openByKey") { [weak self] on in
                        self?.openByKeyChanged(on)
                    })
                }
                addSection(String(localized: "air_group_settings_join"), views: [line, AirKit.leading(invite)], rows: rows)
            }
            else {
                let keyLabel = AirKit.label(.mono, color: KQColor.ink, lines: 0)
                keyLabel.text = AirKeyFormat.lines(key)
                keyLabel.accessibilityLabel = String(localized: "air_group_key")
                keyLabel.accessibilityValue = AirKeyFormat.groups(key).joined(separator: " ")
                keyLabel.accessibilityIdentifier = "air.group.dossier.key"
                let copy = AirButton(style: .normal, title: String(localized: "air_group_copy_key"), icon: "copy")
                copy.accessibilityIdentifier = "air.group.dossier.copy"
                copy.addTarget(self, action: #selector(copyKey), for: .touchUpInside)
                let qr = AirButton(style: .normal, title: String(localized: "air_dossier_qr"), icon: "scan")
                qr.accessibilityIdentifier = "air.group.dossier.qr"
                qr.addTarget(self, action: #selector(qrTapped), for: .touchUpInside)
                let buttons = AirKit.buttonRow([copy, invite], traits: traitCollection)
                addSection(String(localized: "air_group_key"),
                           views: [keyLabel, AirKit.note(privacy.consequence), buttons, AirKit.leading(qr)])
            }
        }

        // Notifications.
        muteSwitch.isOn = chat.groupNotificationsSilent
        let mute = AirSettingRow(title: String(localized: "air_dossier_mute"), opens: false)
        mute.setAccessory(muteSwitch)
        let notifications = addSection(String(localized: "air_dossier_notifications_title"), rows: [mute])
        updateMuteNote()
        notifications.addArrangedSubview(muteNote)

        // The history.
        let files = AirSettingRow(title: String(localized: "air_dossier_files_title"))
        files.accessibilityIdentifier = "air.group.dossier.files"
        files.addTarget(self, action: #selector(filesTapped), for: .touchUpInside)
        var historyRows: [UIView] = [files]
        let systemCount = Int(submanagerGroups.groupSystemMessageCount(for: chat))
        if systemCount > 0 {
            let system = AirSettingRow(title: String(localized: "air_group_remove_system_format", systemCount), opens: false)
            system.addTarget(self, action: #selector(removeSystemTapped), for: .touchUpInside)
            historyRows.append(system)
        }
        let clear = AirSettingRow(title: String(localized: "air_group_clear_history"), opens: false, danger: true)
        clear.accessibilityIdentifier = "air.group.dossier.clear"
        clear.addTarget(self, action: #selector(clearTapped), for: .touchUpInside)
        historyRows.append(clear)
        addSection(String(localized: "air_group_history"), rows: historyRows, note: String(localized: "air_group_history_note"))

        // Everything else the group has: name, topic, password, limits, who may write.
        let settings = AirSettingRow(title: String(localized: "air_group_settings"),
                                     detail: String(localized: "air_group_settings_detail"))
        settings.accessibilityIdentifier = "air.group.dossier.settings"
        settings.addTarget(self, action: #selector(settingsTapped), for: .touchUpInside)
        addSection(nil, rows: [settings])

        let leave = AirButton(style: .danger, title: String(localized: "air_group_leave"))
        leave.accessibilityIdentifier = "air.group.dossier.leave"
        leave.addTarget(self, action: #selector(leaveTapped), for: .touchUpInside)
        addSection(nil, views: [leave], note: String(localized: "air_group_leave_note"))

        reloadMembers(animated: false)
        counts.text = reach.headerText
        countsLabel = counts
    }

    private weak var countsLabel: UILabel?

    private var reach: AirGroupReach {
        let connected = submanagerGroups.isGroupConnected(for: chat)
        return AirGroupReach(peerCount: Int(submanagerGroups.peerCount(for: chat)),
                             knownMembers: members.count,
                             online: Int(submanagerGroups.onlineGroupPeerCount(for: chat)),
                             connected: connected)
    }

    // MARK: Members

    private func loadMembers() -> [AirGroupMember] {
        return AirGroupMembers.load(chat: chat, submanagerGroups: submanagerGroups)
    }

    private func reloadMembers(animated: Bool) {
        guard isViewLoaded, !chat.isInvalidated else {
            return
        }
        members = loadMembers()
        countsLabel?.text = reach.headerText
        search?.isHidden = members.count < AirGroupRoster.searchThreshold
        let shown = AirGroupRoster.filtered(members, query: query)
        let now = Date().timeIntervalSince1970
        let reachNow = reach

        // FLIP: where each row stood, so after a change of role it moves to the place of its new role.
        let moves = animated && !UIAccessibilityIsReduceMotionEnabled() && view.window != nil
        var before: [UInt32: CGFloat] = [:]
        if moves {
            view.layoutIfNeeded()
            for row in memberRows {
                before[row.member.peerId] = row.convert(row.bounds, to: membersHolder).minY
            }
        }

        let rebuild = {
            self.membersHolder.arrangedSubviews.forEach { $0.removeFromSuperview() }
            self.memberRows = shown.map { self.makeMemberRow($0, now: now) }
            if !self.memberRows.isEmpty {
                let list = AirKit.column(self.memberRows, spacing: 0)
                let plate = UIView()
                plate.backgroundColor = KQColor.surface
                plate.layer.cornerRadius = KQRadius.card
                plate.clipsToBounds = true
                plate.addSubview(list)
                list.snp.makeConstraints {
                    $0.edges.equalToSuperview()
                }
                self.membersHolder.addArrangedSubview(plate)
            }
        }

        if moves && !before.isEmpty {
            rebuild()
            view.layoutIfNeeded()
            for row in memberRows {
                if let old = before[row.member.peerId] {
                    let offset = old - row.convert(row.bounds, to: membersHolder).minY
                    if abs(offset) > 0.5 {
                        row.superview?.bringSubview(toFront: row)
                        row.transform = CGAffineTransform(translationX: 0, y: offset)
                    }
                }
                else {
                    row.alpha = 0
                }
            }
            let rows = memberRows
            let animator = UIViewPropertyAnimator(duration: KQMotion.layout, timingParameters: KQMotion.easeStandard)
            animator.addAnimations {
                rows.forEach {
                    $0.transform = .identity
                    $0.alpha = 1
                }
            }
            animator.startAnimation()
        }
        else if animated && view.window != nil {
            // Reduce Motion, or nothing to move from: no travel, a short cross-fade.
            UIView.transition(with: membersHolder, duration: KQMotion.duration(KQMotion.state), options: [.transitionCrossDissolve, .allowUserInteraction], animations: rebuild, completion: nil)
        }
        else {
            rebuild()
        }

        if shown.isEmpty {
            membersNote.text = String(localized: "air_group_nobody_found")
        }
        else if composeRight == .write {
            membersNote.text = reachNow.connected && reachNow.recipients > 0
                ? String(localized: "air_group_who_gets_now_note_format", reachNow.online, reachNow.recipients)
                : reachNow.hint.text
        }
        else {
            membersNote.text = nil
        }
        membersNote.isHidden = (membersNote.text ?? "").isEmpty
    }

    /// A member row: yours only says who you are, any other opens the actions on a tap or a long press.
    private func makeMemberRow(_ member: AirGroupMember, now: TimeInterval) -> AirGroupMemberRow {
        let opens = !member.isSelf
        let row = AirGroupMemberRow(member: member, status: AirGroupRoster.statusLine(for: member, now: now), opens: opens)
        guard opens else {
            return row
        }
        row.addTarget(self, action: #selector(memberTapped(_:)), for: .touchUpInside)
        row.onLongPress = { [weak self, weak row] in
            guard let row = row else {
                return
            }
            self?.presentActions(for: row.member, from: row)
        }
        return row
    }

    @objc private func memberTapped(_ row: AirGroupMemberRow) {
        presentActions(for: row.member, from: row)
    }

    // MARK: Member actions

    private func presentActions(for member: AirGroupMember, from source: UIView) {
        memberSheet.onOpenFriend = onOpenFriend
        memberSheet.present(for: member, from: source)
    }

    private func toast(_ text: String) {
        AirToast.show(text, in: navigationController?.view ?? view)
    }

    // MARK: Actions

    @objc private func muteChanged() {
        submanagerGroups.setGroupNotificationsSilent(muteSwitch.isOn, for: chat)
        updateMuteNote()
    }

    private func updateMuteNote() {
        muteNote.text = String(localized: muteSwitch.isOn ? "air_group_muted_note" : "air_group_unmuted_note")
    }

    @objc private func copyKey() {
        // The founder may have closed the group a moment ago: its key is not handed out then.
        guard privacy == .openById else {
            privacyMayHaveChanged()
            return
        }
        UIPasteboard.general.string = chatIdHex
        toast(String(localized: "air_group_key_copied"))
    }

    @objc private func qrTapped() {
        let key = chatIdHex
        guard !key.isEmpty, privacy == .openById else {
            privacyMayHaveChanged()
            return
        }
        let controller = QRViewerController(theme: theme,
                                            text: "khandaq:group:\(key)",
                                            heading: String(localized: "air_group_qr_heading_format", groupName),
                                            subtitle: String(localized: "air_group_qr_subtitle"))
        navigationController?.pushViewController(controller, animated: true)
    }

    /// The founder's switch in a group by invitation only: on, anyone with the group key can join, and
    /// the key section shows the key with Copy and the QR code. A refusal puts the switch back.
    private func openByKeyChanged(_ on: Bool) {
        guard on, !chat.isInvalidated else {
            return
        }
        do {
            try submanagerGroups.setGroupPrivacyState(.public, for: chat)
            build()
            toast(String(localized: "air_group_settings_saved"))
        }
        catch let error as NSError {
            build()
            handleErrorWithType(.setGroupPrivacy, error: error)
        }
    }

    /// Watches who can join as the profile keeps it: objcTox writes it there when the founder changes it,
    /// here or in the group settings, and when the group's state arrives from its members (toxcore's
    /// privacy callback). Only a managed chat can be watched.
    ///
    /// The answer waits for the next turn of the main loop: Realm may call this block inside objcTox's next
    /// write (the privacy callback stores the state, then the system line that says so), on the queue of
    /// the realm manager, and the rebuild reads through that queue (the count of system lines), which
    /// would be a dispatch_sync onto the queue the thread already holds.
    private func observePrivacy() {
        guard privacyToken == nil, !chat.isInvalidated, chat.realm != nil else {
            return
        }
        privacyToken = chat.addNotificationBlock({ [weak self] deleted, _, _ in
            guard !deleted else {
                return
            }
            DispatchQueue.main.async {
                self?.privacyMayHaveChanged()
            }
        }, keyPaths: ["groupPrivacyState"])
    }

    /// Rebuilds the screen once who can join is not what the key section shows. A write of the same
    /// value notifies too, so nothing is rebuilt then.
    private func privacyMayHaveChanged() {
        guard isViewLoaded, !chat.isInvalidated, let shown = shownPrivacy, shown != privacy else {
            return
        }
        build()
    }

    @objc private func inviteTapped() {
        let controller = AirGroupInviteController(chat: chat,
                                                  memberKeys: Set(members.map { $0.publicKey }.filter { !$0.isEmpty }),
                                                  submanagerGroups: submanagerGroups,
                                                  submanagerObjects: submanagerObjects)
        controller.onInvited = { [weak self] count in
            guard let self = self else {
                return
            }
            self.navigationController?.popToViewController(self, animated: true)
            self.toast(String(localized: "air_invite_sent", count))
        }
        navigationController?.pushViewController(controller, animated: true)
    }

    @objc private func filesTapped() {
        let attachments = ChatAttachmentsController(theme: theme, chat: chat, submanagerObjects: submanagerObjects)
        navigationController?.pushViewController(attachments, animated: true)
    }

    @objc private func removeSystemTapped() {
        let sheet = UIAlertController(title: String(localized: "group_delete_system_msgs_title"),
                                      message: String(localized: "air_group_remove_system_message"),
                                      preferredStyle: .actionSheet)
        sheet.addAction(UIAlertAction(title: String(localized: "air_group_remove_system_action"), style: .destructive) { [weak self] _ in
            guard let self = self else {
                return
            }
            _ = self.submanagerGroups.removeGroupSystemMessages(in: self.chat)
            self.build()
        })
        sheet.addAction(UIAlertAction(title: String(localized: "alert_cancel"), style: .cancel, handler: nil))
        sheet.popoverPresentationController?.sourceView = view
        present(sheet, animated: true, completion: nil)
    }

    @objc private func clearTapped() {
        let sheet = UIAlertController(title: String(localized: "air_group_clear_history_title"),
                                      message: String(localized: "air_group_clear_history_message"),
                                      preferredStyle: .actionSheet)
        sheet.addAction(UIAlertAction(title: String(localized: "air_group_clear_history"), style: .destructive) { [weak self] _ in
            guard let self = self else {
                return
            }
            self.submanagerGroups.removeAllMessages(in: self.chat, removeChat: false, leaveGroup: false)
            self.navigationController?.popViewController(animated: true)
        })
        sheet.addAction(UIAlertAction(title: String(localized: "alert_cancel"), style: .cancel, handler: nil))
        sheet.popoverPresentationController?.sourceView = view
        present(sheet, animated: true, completion: nil)
    }

    @objc private func settingsTapped() {
        let controller = GroupInfoController(chat: chat, submanagerGroups: submanagerGroups)
        navigationController?.pushViewController(controller, animated: true)
    }

    @objc private func leaveTapped() {
        let sheet = UIAlertController(title: String(localized: "air_group_leave_title_format", groupName),
                                      message: String(localized: "air_group_leave_message"),
                                      preferredStyle: .actionSheet)
        sheet.addAction(UIAlertAction(title: String(localized: "air_group_leave"), style: .destructive) { [weak self] _ in
            self?.leave()
        })
        sheet.addAction(UIAlertAction(title: String(localized: "alert_cancel"), style: .cancel, handler: nil))
        sheet.popoverPresentationController?.sourceView = view
        present(sheet, animated: true, completion: nil)
    }

    private func leave() {
        guard chat.groupNumber >= 0 else {
            return
        }
        do {
            try submanagerGroups.leaveGroup(withNumber: OCTToxGroupNumber(chat.groupNumber), partMessage: nil)
            KhandaqCommunity.willLeave(chat)
            submanagerGroups.removeAllMessages(in: chat, removeChat: true, leaveGroup: false)
            navigationController?.popToRootViewController(animated: true)
        }
        catch let error as NSError {
            handleErrorWithType(.leaveGroup, error: error)
        }
    }
}

/// The actions on another member of a group: write privately, open the conversation or add the contact,
/// copy the key, mute, and the roles and removal the founder and the moderators may give, each confirmed,
/// a role change taken back from its toast. KHANDAQ (0.2.58): one sheet for the member's row in the dossier
/// and for the name over the member's entry in the conversation (ChatGroupController.openAuthor).
final class AirGroupMemberSheet {
    /// Opens the conversation with a member who is a contact; without it the sheet does not offer one.
    var onOpenFriend: ((OCTFriend) -> Void)?
    /// After a role changed or a member was removed here.
    var onMembersChanged: (() -> Void)?
    /// The view the toasts stand above, inside the host's view: the field of a conversation. Without one they
    /// go over the host's navigation, where they stay over a screen the sheet opened.
    var toastAnchor: (() -> UIView?)?

    private weak var host: UIViewController?
    private let theme: Theme
    private let chat: OCTChat
    private let submanagerGroups: OCTSubmanagerGroups
    private let submanagerObjects: OCTSubmanagerObjects
    private let submanagerFriends: OCTSubmanagerFriends

    init(host: UIViewController,
         theme: Theme,
         chat: OCTChat,
         submanagerGroups: OCTSubmanagerGroups,
         submanagerObjects: OCTSubmanagerObjects,
         submanagerFriends: OCTSubmanagerFriends) {
        self.host = host
        self.theme = theme
        self.chat = chat
        self.submanagerGroups = submanagerGroups
        self.submanagerObjects = submanagerObjects
        self.submanagerFriends = submanagerFriends
    }

    /// The sheet on `member`; where it shows as a popover it points at `rect` of `source`, its bounds unless
    /// given. Nothing for you, or for a member toxcore no longer lists.
    func present(for member: AirGroupMember, from source: UIView, rect: CGRect? = nil) {
        guard let host = host, !chat.isInvalidated, !member.isSelf, let peer = groupPeer(member.peerId) else {
            return
        }
        let sheet = UIAlertController(title: member.name,
                                      message: AirGroupRoster.statusLine(for: member, now: Date().timeIntervalSince1970),
                                      preferredStyle: .actionSheet)
        sheet.addAction(UIAlertAction(title: String(localized: "air_group_write_privately"), style: .default) { [weak self] _ in
            self?.openPrivate(with: peer)
        })
        if let friend = friend(withKey: member.publicKey), let open = onOpenFriend {
            sheet.addAction(UIAlertAction(title: String(localized: "air_group_open_chat"), style: .default) { _ in
                open(friend)
            })
        }
        else if !member.publicKey.isEmpty {
            sheet.addAction(UIAlertAction(title: String(localized: "air_group_add_contact"), style: .default) { [weak self] _ in
                self?.addContact(member)
            })
        }
        if !member.publicKey.isEmpty {
            sheet.addAction(UIAlertAction(title: String(localized: "air_group_copy_member_key"), style: .default) { [weak self] _ in
                UIPasteboard.general.string = member.publicKey
                self?.toast(String(localized: "air_group_member_key_copied"))
            })
        }
        let silent = peer.peerNotificationsSilent
        sheet.addAction(UIAlertAction(title: String(localized: silent ? "air_group_member_unmute" : "air_group_member_mute"), style: .default) { [weak self] _ in
            guard let self = self, !self.chat.isInvalidated else {
                return
            }
            self.submanagerGroups.setPeerNotificationsSilent(!silent, peerId: member.peerId, for: self.chat)
            self.toast(String(localized: silent ? "air_group_member_unmuted_format" : "air_group_member_muted_format", member.name))
        })
        let selfRole = submanagerGroups.selfRole(in: chat)
        for action in AirGroupMemberAction.available(on: member.role, by: selfRole) where allowed(action, on: member) {
            sheet.addAction(UIAlertAction(title: action.title, style: action == .kick ? .destructive : .default) { [weak self] _ in
                self?.confirm(action, on: member, from: source, rect: rect)
            })
        }
        sheet.addAction(UIAlertAction(title: String(localized: "alert_cancel"), style: .cancel, handler: nil))
        point(sheet, at: source, rect: rect)
        host.present(sheet, animated: true, completion: nil)
    }

    /// A toast of the sheet: over the host's navigation, or above the anchor in the host's view.
    func toast(_ text: String, actionTitle: String? = nil, seconds: TimeInterval = 3, action: (() -> Void)? = nil) {
        guard let host = host else {
            return
        }
        let anchor = toastAnchor?()
        let place: UIView = anchor != nil ? host.view : (host.navigationController?.view ?? host.view)
        AirToast.show(text, actionTitle: actionTitle, seconds: seconds, in: place, above: anchor, action: action)
    }

    private func groupPeer(_ peerId: UInt32) -> OCTGroupPeer? {
        guard let peers = submanagerGroups.peers(for: chat) else {
            return nil
        }
        for index in 0..<peers.count {
            if let peer = peers.object(at: index) as? OCTGroupPeer, !peer.isInvalidated, UInt32(bitPattern: peer.peerId) == peerId {
                return peer
            }
        }
        return nil
    }

    private func friend(withKey key: String) -> OCTFriend? {
        guard !key.isEmpty else {
            return nil
        }
        let found = submanagerObjects.friends(predicate: NSPredicate(format: "publicKey ==[c] %@", key))
        return found.count > 0 ? found.firstObject : nil
    }

    /// Where a sheet that shows as a popover points: at the source while it is on the screen; else, as the
    /// row or the entry has gone since, at the middle of the host's view.
    private func point(_ sheet: UIAlertController, at source: UIView, rect: CGRect?) {
        guard let popover = sheet.popoverPresentationController else {
            return
        }
        if source.window == nil, let view = host?.view {
            popover.sourceView = view
            popover.sourceRect = CGRect(x: view.bounds.midX, y: view.bounds.midY, width: 0, height: 0)
            popover.permittedArrowDirections = []
        }
        else {
            popover.sourceView = source
            popover.sourceRect = rect ?? source.bounds
        }
    }

    /// The rules say what may be offered; toxcore has the last word.
    private func allowed(_ action: AirGroupMemberAction, on member: AirGroupMember) -> Bool {
        if let role = action.role {
            return submanagerGroups.canSetPeerRole(role, peerId: member.peerId, in: chat)
        }
        return submanagerGroups.canKickPeer(withId: member.peerId, in: chat)
    }

    private func confirm(_ action: AirGroupMemberAction, on member: AirGroupMember, from source: UIView, rect: CGRect?) {
        guard let host = host else {
            return
        }
        let words = action.confirmation(name: member.name)
        let sheet = UIAlertController(title: words.title, message: words.message, preferredStyle: .actionSheet)
        sheet.addAction(UIAlertAction(title: action.title, style: action == .kick ? .destructive : .default) { [weak self] _ in
            self?.perform(action, on: member)
        })
        sheet.addAction(UIAlertAction(title: String(localized: "alert_cancel"), style: .cancel, handler: nil))
        point(sheet, at: source, rect: rect)
        host.present(sheet, animated: true, completion: nil)
    }

    private func perform(_ action: AirGroupMemberAction, on member: AirGroupMember) {
        guard !chat.isInvalidated else {
            return
        }
        do {
            if let role = action.role {
                let previous = member.role
                try submanagerGroups.setPeerRole(role, peerId: member.peerId, in: chat)
                onMembersChanged?()
                toast(action.doneText(name: member.name), actionTitle: String(localized: "air_undo"), seconds: 5) { [weak self] in
                    self?.undoRole(previous, of: member)
                }
            }
            else {
                try submanagerGroups.kickPeer(withId: member.peerId, in: chat)
                onMembersChanged?()
                toast(action.doneText(name: member.name))
            }
        }
        catch let error as NSError {
            handleErrorWithType(action == .kick ? .kickGroupPeer : .setGroupPeerRole, error: error)
        }
    }

    private func undoRole(_ role: OCTToxGroupRole, of member: AirGroupMember) {
        guard !chat.isInvalidated else {
            return
        }
        do {
            try submanagerGroups.setPeerRole(role, peerId: member.peerId, in: chat)
            onMembersChanged?()
        }
        catch let error as NSError {
            handleErrorWithType(.setGroupPeerRole, error: error)
        }
    }

    private func openPrivate(with peer: OCTGroupPeer) {
        guard !chat.isInvalidated, !peer.isInvalidated else {
            return
        }
        let controller = GroupPeerPrivateController(theme: theme,
                                                    chat: chat,
                                                    peer: peer,
                                                    submanagerGroups: submanagerGroups,
                                                    submanagerObjects: submanagerObjects)
        host?.navigationController?.pushViewController(controller, animated: true)
    }

    private func addContact(_ member: AirGroupMember) {
        do {
            // A group gives only the 64-character key, not a whole ID: the contact is added by key.
            try submanagerFriends.addFriend(byPublicKey: member.publicKey)
            toast(String(localized: "air_group_contact_added_format", member.name))
        }
        catch let error as NSError {
            handleErrorWithType(.toxAddFriend, error: error)
        }
    }
}

/// "Invite" from the group dossier: the contacts, online first, and the button on a bar above the
/// keyboard. Only a contact online now can be invited (the invitation goes over their connection).
final class AirGroupInviteController: AirScrollController {
    var onInvited: ((Int) -> Void)?

    private let chat: OCTChat
    private let memberKeys: Set<String>
    private let submanagerGroups: OCTSubmanagerGroups
    private let submanagerObjects: OCTSubmanagerObjects
    private let listView = AirInviteListView()
    private let bar = UIView()
    private let inviteButton = AirButton(style: .ink, title: String(localized: "air_group_invite"))
    private let whyLabel = AirKit.note("")

    init(chat: OCTChat, memberKeys: Set<String>, submanagerGroups: OCTSubmanagerGroups, submanagerObjects: OCTSubmanagerObjects) {
        self.chat = chat
        self.memberKeys = memberKeys
        self.submanagerGroups = submanagerGroups
        self.submanagerObjects = submanagerObjects
        super.init(nibName: nil, bundle: nil)
        hidesBottomBarWhenPushed = true
        title = String(localized: "air_group_invite_title")
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewDidLoad() {
        adjustsForKeyboard = false
        super.viewDidLoad()
        view.accessibilityIdentifier = "air.group.invite"
        listView.configure(candidates: AirInviteCandidates.load(from: submanagerObjects, memberKeys: memberKeys))
        listView.onChange = { [weak self] in
            self?.updateButton()
        }
        addSection(nil, views: [listView], note: String(localized: "air_invite_online_note"))

        bar.backgroundColor = KQColor.surface
        let rule = UIView()
        rule.backgroundColor = KQColor.line
        bar.addSubview(rule)
        inviteButton.accessibilityIdentifier = "air.group.invite.send"
        inviteButton.addTarget(self, action: #selector(inviteTapped), for: .touchUpInside)
        let barColumn = AirKit.column([inviteButton, whyLabel], spacing: KQSpace.x6)
        bar.addSubview(barColumn)
        view.addSubview(bar)
        rule.snp.makeConstraints {
            $0.leading.trailing.top.equalToSuperview()
            $0.height.equalTo(KQStroke.hairline)
        }
        barColumn.snp.makeConstraints {
            $0.top.equalToSuperview().offset(KQSpace.x8)
            $0.bottom.equalToSuperview().offset(-KQSpace.x8)
            $0.leading.equalTo(view.safeAreaLayoutGuide).offset(KQSpace.x16)
            $0.trailing.equalTo(view.safeAreaLayoutGuide).offset(-KQSpace.x16)
        }
        inviteButton.snp.makeConstraints {
            $0.height.equalTo(KQSize.targetIos).priority(.medium)
        }
        bar.snp.makeConstraints {
            $0.leading.trailing.equalToSuperview()
            $0.bottom.equalTo(view.keyboardLayoutGuide.snp.top)
        }
        scroll.snp.remakeConstraints {
            $0.top.leading.trailing.equalToSuperview()
            $0.bottom.equalTo(bar.snp.top)
        }
        updateButton()
    }

    private func updateButton() {
        let count = listView.selected.count
        inviteButton.isEnabled = count > 0
        inviteButton.setTitle(count > 0 ? String(localized: "air_invite_send_format", count) : String(localized: "air_group_invite"))
        whyLabel.text = count > 0 ? nil : String(localized: "air_invite_choose_first")
        whyLabel.isHidden = count > 0
    }

    @objc private func inviteTapped() {
        guard chat.groupNumber >= 0 else {
            return
        }
        var sent = 0
        var firstError: NSError?
        for candidate in listView.chosen where candidate.canInvite {
            do {
                try submanagerGroups.inviteFriend(withNumber: OCTToxFriendNumber(candidate.friendNumber),
                                                  toGroupNumber: OCTToxGroupNumber(chat.groupNumber))
                sent += 1
            }
            catch let error as NSError {
                firstError = firstError ?? error
            }
        }
        if let error = firstError {
            // One alert for the lot: the ones that went out are still reported by the toast.
            handleErrorWithType(.inviteFriendToGroup, error: error)
        }
        if sent > 0 {
            onInvited?(sent)
        }
    }
}

/// The contacts as invitation candidates: whether each is online now and whether they are already a
/// member (their key among the group's).
enum AirInviteCandidates {
    static func load(from submanagerObjects: OCTSubmanagerObjects, memberKeys: Set<String>) -> [AirInviteCandidate] {
        let friends = submanagerObjects.friends()
        let members = Set(memberKeys.map { $0.uppercased() })
        var list: [AirInviteCandidate] = []
        for index in 0..<friends.count {
            let friend = friends[index]
            guard !friend.isInvalidated else {
                continue
            }
            let nick = friend.nickname.trimmingCharacters(in: .whitespacesAndNewlines)
            let name = nick.isEmpty ? (friend.name ?? "") : nick
            list.append(AirInviteCandidate(friendNumber: friend.friendNumber,
                                           publicKey: friend.publicKey,
                                           name: name.isEmpty ? AirKeyFormat.groups(friend.publicKey).prefix(2).joined(separator: " ") : name,
                                           isOnline: friend.connectionStatus != .none,
                                           isMember: members.contains(friend.publicKey.uppercased())))
        }
        return list
    }
}

/// The members of a group as the group screens show them, read from the submanager: the peers it lists,
/// with their roles and presence, and your own row whether or not toxcore lists you among them.
enum AirGroupMembers {
    static func load(chat: OCTChat, submanagerGroups: OCTSubmanagerGroups) -> [AirGroupMember] {
        guard !chat.isInvalidated else {
            return []
        }
        let selfId = submanagerGroups.groupSelfPeerId(for: chat)
        let selfRole = submanagerGroups.selfRole(in: chat)
        let connected = submanagerGroups.isGroupConnected(for: chat)
        var list: [AirGroupMember] = []
        var sawSelf = false
        if let peers = submanagerGroups.peers(for: chat) {
            for index in 0..<peers.count {
                guard let peer = peers.object(at: index) as? OCTGroupPeer, !peer.isInvalidated else {
                    continue
                }
                let id = UInt32(bitPattern: peer.peerId)
                let isSelf = isSelfPeer(id, selfPeerId: selfId, groupNumber: chat.groupNumber)
                sawSelf = sawSelf || isSelf
                let stored = peer.peerName ?? ""
                let name = stored.isEmpty ? String(localized: "group_peer_fallback_format", peer.peerId) : stored
                list.append(AirGroupMember(peerId: id,
                                           name: name,
                                           publicKey: (peer.peerPublicKeyHex ?? "").uppercased(),
                                           role: isSelf ? selfRole : submanagerGroups.peerRole(withId: id, in: chat),
                                           isOnline: isSelf ? connected : submanagerGroups.isGroupPeerOnline(withId: id, in: chat),
                                           lastSeen: submanagerGroups.groupPeerLastSeenDateInterval(forPeerId: id, in: chat),
                                           isSelf: isSelf))
            }
        }
        if !sawSelf {
            let own = (try? submanagerGroups.groupSelfPeerName(for: chat)) ?? ""
            list.append(AirGroupMember(peerId: selfId,
                                       name: own.isEmpty ? String(localized: "air_you") : own,
                                       publicKey: "",
                                       role: selfRole,
                                       isOnline: connected,
                                       lastSeen: 0,
                                       isSelf: true))
        }
        return AirGroupRoster.sorted(list)
    }

    /// Whether a peer of the group is you. toxcore numbers you first in every group, so your peer id is
    /// 0 there (900 in the demo world); the submanager answers 0 as well when it cannot tell, for a chat
    /// without a group number, and then nobody is taken for you.
    static func isSelfPeer(_ peerId: UInt32, selfPeerId: UInt32, groupNumber: Int32) -> Bool {
        return groupNumber >= 0 && peerId == selfPeerId
    }

    /// Your own key in this group, for telling the messages that name you; nil while toxcore has not
    /// listed you among the peers.
    static func selfKey(in members: [AirGroupMember]) -> String? {
        let key = members.first(where: { $0.isSelf })?.publicKey ?? ""
        return key.isEmpty ? nil : key
    }
}
