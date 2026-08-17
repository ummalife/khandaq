// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit
import SnapKit

private enum GroupInfoSection: Int {
    case topic = 0
    case password = 1
    case topicLock = 2
    case privacy = 3
    case voice = 4
    case peerLimit = 5
    case chatId = 6
    case notifications = 7
    case invite = 8
    case members = 9
    case danger = 10
}

class GroupInfoController: UIViewController {
    let chat: OCTChat

    fileprivate let theme: Theme
    fileprivate weak var submanagerGroups: OCTSubmanagerGroups!
    fileprivate weak var submanagerObjects: OCTSubmanagerObjects!
    fileprivate weak var submanagerChats: OCTSubmanagerChats!
    fileprivate weak var submanagerFiles: OCTSubmanagerFiles!
    fileprivate weak var submanagerFriends: OCTSubmanagerFriends!

    fileprivate var tableView: UITableView!
    fileprivate var peers: Results<OCTGroupPeer>
    fileprivate var peersToken: RLMNotificationToken?
    fileprivate var chatIdHex: String = ""
    fileprivate var topicText: String = ""
    fileprivate var passwordText: String = ""
    fileprivate var topicLockEnabled: Bool = false
    fileprivate var privacyIsPrivate: Bool = false
    fileprivate var voiceState: OCTToxGroupVoiceState = .all
    fileprivate var peerLimitValue: Int32 = kOCTDefaultGroupPeerLimit
    fileprivate var canEditTopic: Bool = true
    fileprivate var selfRole: OCTToxGroupRole = .user
    fileprivate var isFounder: Bool = false
    fileprivate var systemMessageCount: Int = 0
    fileprivate var myPeerNameText: String = ""

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
        self.peers = Results<OCTGroupPeer>(results: submanagerGroups.peers(for: chat))

        super.init(nibName: nil, bundle: nil)

        title = chat.groupName ?? String(localized: "group_info_title")
        edgesForExtendedLayout = UIRectEdge()
    }

    required convenience init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    deinit {
        peersToken?.invalidate()
    }

    override func loadView() {
        loadViewWithBackgroundColor(theme.colorForType(.NormalBackground))

        tableView = UITableView(frame: .zero, style: .grouped)
        tableView.backgroundColor = theme.colorForType(.NormalBackground)
        tableView.delegate = self
        tableView.dataSource = self
        tableView.register(UITableViewCell.self, forCellReuseIdentifier: "cell")
        view.addSubview(tableView)

        tableView.snp.makeConstraints {
            $0.edges.equalTo(view)
        }
    }

    override func viewDidLoad() {
        super.viewDidLoad()

        peersToken = peers.addNotificationBlock { [weak self] _ in
            self?.tableView?.reloadData()
        }
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        reloadGroupInfo()
    }
}

extension GroupInfoController: UITableViewDataSource {
    func numberOfSections(in tableView: UITableView) -> Int {
        return 11
    }

    func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        switch GroupInfoSection(rawValue: section)! {
            case .topic:
                return chat.groupNumber >= 0 ? 2 : 1
            case .password:
                return isFounder ? 1 : 0
            case .topicLock:
                return isFounder ? 1 : 0
            case .privacy:
                return isFounder ? 1 : 0
            case .voice:
                return isFounder ? 1 : 0
            case .peerLimit:
                return isFounder ? 1 : 0
            case .chatId:
                // KHANDAQ: hide the group chat-id section. Joining by chat-id needs UDP peer
                // discovery (blocked on target networks); members are only added manually via
                // friend-invite, so a non-functional ID just confuses users.
                return 0
            case .notifications:
                return 1
            case .invite:
                return chat.groupNumber >= 0 ? 1 : 0
            case .members:
                return max(peers.count, 1)
            case .danger:
                return chat.groupNumber >= 0 ? 3 : 0
        }
    }

    func tableView(_ tableView: UITableView, titleForHeaderInSection section: Int) -> String? {
        switch GroupInfoSection(rawValue: section)! {
            case .topic:
                return String(localized: "group_topic_header")
            case .password:
                return isFounder ? String(localized: "group_password_header") : nil
            case .topicLock:
                return isFounder ? String(localized: "group_topic_lock_header") : nil
            case .privacy:
                return isFounder ? String(localized: "group_privacy_header") : nil
            case .voice:
                return isFounder ? String(localized: "group_voice_header") : nil
            case .peerLimit:
                return isFounder ? String(localized: "group_peer_limit_header") : nil
            case .chatId:
                return nil // KHANDAQ: chat-id section hidden (see numberOfRows)
            case .notifications:
                return String(localized: "group_notifications_header")
            case .invite:
                return nil
            case .members:
                // KHANDAQ (#49): online from onlineGroupPeerCount so it matches the drawer + list row.
                let online = Int(submanagerGroups.onlineGroupPeerCount(for: chat))
                let total = displayMemberCount()
                if total > online && online > 0 {
                    // KHANDAQ (#9): show total · online from toxcore's NGC-synced counts so every
                    // client agrees (parity with Android).
                    return String(localized: "group_members_header_online_format", total, online)
                }
                if total > 0 {
                    return String(localized: "group_members_header_format", total)
                }
                return String(localized: "group_members_header")
            case .danger:
                return String(localized: "group_danger_header")
        }
    }

    func tableView(_ tableView: UITableView, titleForFooterInSection section: Int) -> String? {
        switch GroupInfoSection(rawValue: section)! {
            case .topic:
                if topicLockEnabled {
                    if canEditTopic && selfRole == .moderator {
                        return String(localized: "group_topic_footer_locked_moderator")
                    }
                    if !canEditTopic {
                        return String(localized: "group_topic_footer_no_permission")
                    }
                    return String(localized: "group_topic_footer_locked")
                }
                return String(localized: "group_topic_footer")
            case .password:
                return String(localized: "group_password_footer")
            case .topicLock:
                return String(localized: "group_topic_lock_footer")
            case .privacy:
                return String(localized: "group_privacy_footer")
            case .voice:
                return String(localized: "group_voice_footer")
            case .peerLimit:
                if submanagerGroups.isGroupAtPeerCapacity(for: chat) {
                    return String(localized: "group_peer_limit_at_capacity_footer")
                }
                return String(localized: "group_peer_limit_footer")
            case .chatId:
                return nil // KHANDAQ: chat-id section hidden (see numberOfRows)
            case .danger:
                if systemMessageCount > 0 {
                    return String(localized: "group_danger_footer_with_system_format", systemMessageCount)
                }
                return String(localized: "group_danger_footer")
            default:
                return nil
        }
    }

    func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: "cell", for: indexPath)
        cell.backgroundColor = theme.colorForType(.SettingsBackground)
        cell.textLabel?.textColor = theme.colorForType(.NormalText)
        cell.detailTextLabel?.textColor = theme.colorForType(.FriendCellStatus)
        cell.selectionStyle = .default
        cell.accessoryView = nil
        cell.accessoryType = .none
        cell.textLabel?.text = nil
        cell.detailTextLabel?.text = nil

        switch GroupInfoSection(rawValue: indexPath.section)! {
            case .topic:
                cell.textLabel?.font = UIFont.preferredFont(forTextStyle: .body)
                cell.textLabel?.numberOfLines = 2
                if indexPath.row == 0 {
                    cell.textLabel?.text = topicText.isEmpty ? String(localized: "group_topic_placeholder") : topicText
                    cell.accessoryType = canEditTopic ? .disclosureIndicator : .none
                    cell.selectionStyle = canEditTopic ? .default : .none
                    cell.textLabel?.textColor = canEditTopic ? theme.colorForType(.NormalText) : theme.colorForType(.FriendCellStatus)
                }
                else {
                    cell.textLabel?.numberOfLines = 1
                    cell.textLabel?.text = myPeerNameText.isEmpty ? String(localized: "group_my_peer_name_placeholder") : myPeerNameText
                    cell.accessoryType = .disclosureIndicator
                    cell.selectionStyle = .default
                    cell.textLabel?.textColor = theme.colorForType(.NormalText)
                }
            case .password:
                cell.textLabel?.font = UIFont.preferredFont(forTextStyle: .body)
                cell.textLabel?.text = passwordText.isEmpty ? String(localized: "group_password_placeholder") : String(repeating: "•", count: min(passwordText.count, 12))
                cell.accessoryType = .disclosureIndicator
            case .topicLock:
                cell.textLabel?.font = UIFont.preferredFont(forTextStyle: .body)
                cell.textLabel?.text = String(localized: "group_topic_lock_label")
                cell.selectionStyle = .none
                let toggle = UISwitch()
                toggle.isOn = topicLockEnabled
                toggle.addTarget(self, action: #selector(GroupInfoController.groupTopicLockToggled(_:)), for: .valueChanged)
                cell.accessoryView = toggle
            case .privacy:
                cell.textLabel?.font = UIFont.preferredFont(forTextStyle: .body)
                cell.textLabel?.text = String(localized: "group_privacy_private_label")
                cell.selectionStyle = .none
                let privacyToggle = UISwitch()
                privacyToggle.isOn = privacyIsPrivate
                privacyToggle.addTarget(self, action: #selector(GroupInfoController.groupPrivacyToggled(_:)), for: .valueChanged)
                cell.accessoryView = privacyToggle
            case .voice:
                cell.textLabel?.font = UIFont.preferredFont(forTextStyle: .body)
                cell.textLabel?.text = String(localized: "group_voice_header")
                cell.detailTextLabel?.text = voiceState.localizedName
                cell.accessoryType = .disclosureIndicator
            case .peerLimit:
                cell.textLabel?.font = UIFont.preferredFont(forTextStyle: .body)
                cell.textLabel?.text = String(localized: "group_peer_limit_value_format", Int(peerLimitValue))
                cell.accessoryType = .disclosureIndicator
            case .chatId:
                cell.textLabel?.font = UIFont.preferredFont(forTextStyle: .footnote)
                cell.textLabel?.numberOfLines = 0
                if indexPath.row == 0 {
                    cell.textLabel?.text = chatIdHex.isEmpty ? String(localized: "group_chat_id_loading") : chatIdHex
                    cell.accessoryType = chatIdHex.isEmpty ? .none : .disclosureIndicator
                }
                else {
                    cell.textLabel?.font = UIFont.preferredFont(forTextStyle: .body)
                    cell.textLabel?.numberOfLines = 1
                    cell.textLabel?.text = String(localized: "group_show_invite_qr")
                    cell.textLabel?.textColor = theme.colorForType(.LinkText)
                    cell.accessoryType = .disclosureIndicator
                }
            case .notifications:
                cell.textLabel?.font = UIFont.preferredFont(forTextStyle: .body)
                cell.textLabel?.numberOfLines = 1
                cell.textLabel?.text = String(localized: "group_notifications_mute")
                cell.selectionStyle = .none
                let toggle = UISwitch()
                toggle.isOn = chat.groupNotificationsSilent
                toggle.addTarget(self, action: #selector(GroupInfoController.groupNotificationsToggled(_:)), for: .valueChanged)
                cell.accessoryView = toggle
            case .invite:
                cell.textLabel?.font = UIFont.preferredFont(forTextStyle: .body)
                cell.textLabel?.numberOfLines = 1
                cell.textLabel?.text = String(localized: "group_invite_friend_action")
                cell.textLabel?.textColor = theme.colorForType(.LinkText)
                cell.accessoryType = .disclosureIndicator
            case .members:
                // KHANDAQ (#40/#61): bounds-checked — the live peer list can shrink under the table.
                if let peer = peers.object(at: indexPath.row) {
                    cell.textLabel?.text = peer.peerName ?? String(localized: "group_peer_fallback_format", peer.peerId)
                    cell.detailTextLabel?.text = peerDetailSubtitle(for: peer)
                    cell.accessoryType = .disclosureIndicator
                }
                else {
                    cell.textLabel?.text = String(localized: "group_members_empty")
                    cell.selectionStyle = .none
                }
            case .danger:
                cell.textLabel?.font = UIFont.preferredFont(forTextStyle: .body)
                switch indexPath.row {
                    case 0:
                        cell.textLabel?.text = String(localized: "group_clear_history_action")
                        cell.textLabel?.textColor = theme.colorForType(.NormalText)
                    case 1:
                        cell.textLabel?.text = String(localized: "group_delete_system_msgs_action")
                        if systemMessageCount > 0 {
                            cell.detailTextLabel?.text = String(localized: "group_delete_system_msgs_count_format", systemMessageCount)
                            cell.textLabel?.textColor = theme.colorForType(.NormalText)
                        }
                        else {
                            cell.textLabel?.textColor = theme.colorForType(.FriendCellStatus)
                        }
                    default:
                        cell.textLabel?.text = String(localized: "group_leave_action")
                        cell.textLabel?.textColor = .systemRed
                }
                cell.selectionStyle = .default
        }

        return cell
    }
}

extension GroupInfoController: UITableViewDelegate {
    func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)

        switch GroupInfoSection(rawValue: indexPath.section)! {
            case .topic:
                if indexPath.row == 0 {
                    if canEditTopic {
                        presentTopicEditor()
                    }
                }
                else {
                    presentMyPeerNameEditor()
                }
            case .password:
                presentPasswordEditor()
            case .topicLock:
                break
            case .privacy:
                break
            case .voice:
                presentVoiceStatePicker()
            case .peerLimit:
                presentPeerLimitEditor()
            case .chatId:
                if indexPath.row == 1 {
                    presentInviteQR()
                    return
                }

                guard !chatIdHex.isEmpty else {
                    return
                }

                let alert = UIAlertController(title: String(localized: "group_chat_id_header"), message: chatIdHex, preferredStyle: .alert)
                alert.addAction(UIAlertAction(title: String(localized: "copy"), style: .default) { _ in
                    UIPasteboard.general.string = self.chatIdHex
                })
                alert.addAction(UIAlertAction(title: String(localized: "alert_cancel"), style: .cancel, handler: nil))
                present(alert, animated: true, completion: nil)
            case .notifications:
                break
            case .invite:
                presentInviteFriendPicker()
            case .members:
                // KHANDAQ (#40/#61): bounds-checked — avoid trapping if the row index is stale.
                guard let peer = peers.object(at: indexPath.row) else {
                    return
                }

                presentPeerActions(for: peer, at: indexPath)
            case .danger:
                switch indexPath.row {
                    case 0:
                        confirmClearHistory()
                    case 1:
                        confirmDeleteSystemMessages()
                    default:
                        confirmLeaveGroup()
                }
        }
    }
}

private extension GroupInfoController {
    @objc func groupNotificationsToggled(_ sender: UISwitch) {
        submanagerGroups.setGroupNotificationsSilent(sender.isOn, for: chat)
    }

    @objc func groupTopicLockToggled(_ sender: UISwitch) {
        let lock: OCTToxGroupTopicLock = sender.isOn ? .enabled : .disabled

        do {
            try submanagerGroups.setGroupTopicLock(lock, for: chat)
            topicLockEnabled = sender.isOn
            canEditTopic = submanagerGroups.canEditGroupTopic(in: chat)
            tableView.reloadSections(IndexSet(integer: GroupInfoSection.topic.rawValue), with: .none)
        }
        catch let error as NSError {
            sender.isOn = topicLockEnabled
            handleErrorWithType(.setGroupTopicLock, error: error)
        }
    }

    @objc func groupPrivacyToggled(_ sender: UISwitch) {
        let state: OCTToxGroupPrivacyState = sender.isOn ? .private : .public

        do {
            try submanagerGroups.setGroupPrivacyState(state, for: chat)
            privacyIsPrivate = sender.isOn
        }
        catch let error as NSError {
            sender.isOn = privacyIsPrivate
            handleErrorWithType(.setGroupPrivacy, error: error)
        }
    }

    func presentVoiceStatePicker() {
        let alert = UIAlertController(title: String(localized: "group_voice_header"), message: nil, preferredStyle: .actionSheet)

        let states: [OCTToxGroupVoiceState] = [.all, .moderator, .founder]
        for state in states {
            alert.addAction(UIAlertAction(title: state.localizedName, style: .default) { [unowned self] _ in
                do {
                    try self.submanagerGroups.setGroupVoiceState(state, for: self.chat)
                    self.voiceState = state
                    self.tableView.reloadSections(IndexSet(integer: GroupInfoSection.voice.rawValue), with: .none)
                }
                catch let error as NSError {
                    handleErrorWithType(.setGroupVoice, error: error)
                }
            })
        }

        alert.addAction(UIAlertAction(title: String(localized: "alert_cancel"), style: .cancel, handler: nil))

        if let popover = alert.popoverPresentationController {
            popover.sourceView = tableView
            popover.sourceRect = tableView.rectForRow(at: IndexPath(row: 0, section: GroupInfoSection.voice.rawValue))
        }

        present(alert, animated: true, completion: nil)
    }

    func presentInviteQR() {
        guard !chatIdHex.isEmpty else {
            return
        }

        let payload = "khandaq:group:\(chatIdHex)"
        let controller = QRViewerController(theme: theme, text: payload)
        navigationController?.pushViewController(controller, animated: true)
    }

    @objc func topicFieldChanged(_ sender: UITextField) {
        topicText = sender.text ?? ""
    }

    @objc func passwordFieldChanged(_ sender: UITextField) {
        passwordText = sender.text ?? ""
    }

    func presentTopicEditor() {
        let alert = UIAlertController(title: String(localized: "group_topic_header"), message: nil, preferredStyle: .alert)
        alert.addTextField { field in
            field.text = self.topicText
            field.placeholder = String(localized: "group_topic_placeholder")
        }
        alert.addAction(UIAlertAction(title: String(localized: "alert_ok"), style: .default) { [unowned self] _ in
            self.topicText = alert.textFields?.first?.text ?? ""
            self.saveTopicIfNeeded()
            self.tableView.reloadData()
        })
        alert.addAction(UIAlertAction(title: String(localized: "alert_cancel"), style: .cancel, handler: nil))
        present(alert, animated: true, completion: nil)
    }

    func presentMyPeerNameEditor() {
        let alert = UIAlertController(title: String(localized: "group_my_peer_name_header"), message: nil, preferredStyle: .alert)
        alert.addTextField { field in
            field.text = self.myPeerNameText
            field.placeholder = String(localized: "group_my_peer_name_placeholder")
        }
        alert.addAction(UIAlertAction(title: String(localized: "alert_ok"), style: .default) { [unowned self] _ in
            self.myPeerNameText = alert.textFields?.first?.text?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            self.saveMyPeerNameIfNeeded()
            self.tableView.reloadSections(IndexSet(integer: GroupInfoSection.topic.rawValue), with: .none)
        })
        alert.addAction(UIAlertAction(title: String(localized: "alert_cancel"), style: .cancel, handler: nil))
        present(alert, animated: true, completion: nil)
    }

    func saveMyPeerNameIfNeeded() {
        guard chat.groupNumber >= 0 else {
            return
        }

        do {
            try submanagerGroups.setGroupSelfPeerName(myPeerNameText, for: chat)
            submanagerGroups.refreshPeers(for: chat)
        }
        catch let error as NSError {
            handleErrorWithType(.setGroupSelfPeerName, error: error)
        }
    }

    func presentPasswordEditor() {
        let alert = UIAlertController(title: String(localized: "group_password_header"), message: nil, preferredStyle: .alert)
        alert.addTextField { field in
            field.text = self.passwordText
            field.placeholder = String(localized: "group_password_placeholder")
            field.isSecureTextEntry = true
        }
        alert.addAction(UIAlertAction(title: String(localized: "alert_ok"), style: .default) { [unowned self] _ in
            self.passwordText = alert.textFields?.first?.text ?? ""
            self.savePasswordIfNeeded()
            self.tableView.reloadData()
        })
        alert.addAction(UIAlertAction(title: String(localized: "alert_cancel"), style: .cancel, handler: nil))
        present(alert, animated: true, completion: nil)
    }

    func presentPeerLimitEditor() {
        let alert = UIAlertController(title: String(localized: "group_peer_limit_header"), message: nil, preferredStyle: .alert)
        alert.addTextField { field in
            field.text = String(self.peerLimitValue)
            field.placeholder = String(localized: "group_peer_limit_placeholder")
            field.keyboardType = .numberPad
        }
        alert.addAction(UIAlertAction(title: String(localized: "alert_ok"), style: .default) { [unowned self] _ in
            if let text = alert.textFields?.first?.text, let parsed = Int32(text), parsed > 0 {
                // KHANDAQ (#iOS group member limit): clamp to the Tox NGC ceiling (uint16 maxpeers).
                // Values > 65535 would silently wrap in the (uint16_t) cast on the way to toxcore.
                self.peerLimitValue = min(parsed, 65535)
            }
            self.savePeerLimitIfNeeded()
            self.tableView.reloadData()
        })
        alert.addAction(UIAlertAction(title: String(localized: "alert_cancel"), style: .cancel, handler: nil))
        present(alert, animated: true, completion: nil)
    }

    func saveTopicIfNeeded() {
        let current = chat.groupTopic ?? chat.groupName ?? ""
        guard topicText != current else {
            return
        }

        do {
            try submanagerGroups.setGroupTopic(topicText, for: chat)
        }
        catch let error as NSError {
            handleErrorWithType(.setGroupTopic, error: error)
        }
    }

    func savePasswordIfNeeded() {
        guard isFounder else {
            return
        }

        let current = chat.groupPassword ?? ""
        guard passwordText != current else {
            return
        }

        do {
            try submanagerGroups.setGroupPassword(passwordText.isEmpty ? nil : passwordText, for: chat)
        }
        catch let error as NSError {
            handleErrorWithType(.setGroupPassword, error: error)
        }
    }

    func savePeerLimitIfNeeded() {
        guard isFounder else {
            return
        }

        guard peerLimitValue > 0 else {
            handleErrorWithType(.setGroupPeerLimit, error: nil)
            return
        }

        let current = submanagerGroups.groupPeerLimit(for: chat, error: nil)
        guard peerLimitValue != current else {
            return
        }

        do {
            try submanagerGroups.setGroupPeerLimit(peerLimitValue, for: chat)
        }
        catch let error as NSError {
            handleErrorWithType(.setGroupPeerLimit, error: error)
        }
    }

    func presentPeerActions(for peer: OCTGroupPeer, at indexPath: IndexPath) {
        GroupPeerActionsPresenter.presentActions(from: self,
                                                 theme: theme,
                                                 chat: chat,
                                                 peer: peer,
                                                 submanagerGroups: submanagerGroups,
                                                 submanagerObjects: submanagerObjects,
                                                 submanagerFriends: submanagerFriends,
                                                 popoverSourceView: tableView,
                                                 popoverSourceRect: tableView.rectForRow(at: indexPath),
                                                 onOpenFriend: { [unowned self] friend in
            guard let friendChat = self.submanagerChats.getOrCreateChat(with: friend) else {
                return
            }

            let controller = ChatPrivateController(
                    theme: self.theme,
                    chat: friendChat,
                    submanagerChats: self.submanagerChats,
                    submanagerObjects: self.submanagerObjects,
                    submanagerFiles: self.submanagerFiles,
                    delegate: self)
            self.navigationController?.pushViewController(controller, animated: true)
        },
                                                 onPeersChanged: { [unowned self] in
            self.reloadGroupInfo()
        })
    }

    func peerDetailSubtitle(for peer: OCTGroupPeer) -> String {
        return GroupMemberStatusFormatter.detailSubtitle(for: peer, chat: chat, submanagerGroups: submanagerGroups)
    }

    func confirmDeleteSystemMessages() {
        guard systemMessageCount > 0 else {
            return
        }

        let alert = UIAlertController(title: String(localized: "group_delete_system_msgs_title"),
                                      message: String(localized: "group_delete_system_msgs_message"),
                                      preferredStyle: .alert)

        alert.addAction(UIAlertAction(title: String(localized: "group_delete_system_msgs_confirm"), style: .destructive) { [unowned self] _ in
            _ = self.submanagerGroups.removeGroupSystemMessages(in: self.chat)
            self.systemMessageCount = Int(self.submanagerGroups.groupSystemMessageCount(for: self.chat))
            self.tableView.reloadSections(IndexSet(integer: GroupInfoSection.danger.rawValue), with: .none)
        })
        alert.addAction(UIAlertAction(title: String(localized: "alert_cancel"), style: .cancel, handler: nil))

        present(alert, animated: true, completion: nil)
    }

    func confirmClearHistory() {
        let alert = UIAlertController(title: String(localized: "group_clear_history_action"),
                                      message: String(localized: "group_clear_history_confirm_message"),
                                      preferredStyle: .alert)

        alert.addAction(UIAlertAction(title: String(localized: "group_clear_history_action"), style: .destructive) { [unowned self] _ in
            self.submanagerGroups.removeAllMessages(in: self.chat, removeChat: false, leaveGroup: false)
            self.navigationController?.popViewController(animated: true)
        })
        alert.addAction(UIAlertAction(title: String(localized: "alert_cancel"), style: .cancel, handler: nil))

        present(alert, animated: true, completion: nil)
    }

    func confirmLeaveGroup() {
        let alert = UIAlertController(title: String(localized: "group_leave_action"),
                                      message: String(localized: "group_leave_confirm_message"),
                                      preferredStyle: .alert)
        alert.addTextField { field in
            field.placeholder = String(localized: "group_leave_part_message_placeholder")
        }

        alert.addAction(UIAlertAction(title: String(localized: "group_leave_action"), style: .destructive) { [unowned self] _ in
            let partMessage = alert.textFields?.first?.text?.trimmingCharacters(in: .whitespacesAndNewlines)
            self.leaveGroup(partMessage: partMessage?.isEmpty == false ? partMessage : nil)
        })
        alert.addAction(UIAlertAction(title: String(localized: "alert_cancel"), style: .cancel, handler: nil))

        present(alert, animated: true, completion: nil)
    }

    func leaveGroup(partMessage: String?) {
        guard chat.groupNumber >= 0 else {
            return
        }

        do {
            try submanagerGroups.leaveGroup(withNumber: OCTToxGroupNumber(chat.groupNumber), partMessage: partMessage)
            submanagerGroups.removeAllMessages(in: chat, removeChat: true, leaveGroup: false)
            navigationController?.popToRootViewController(animated: true)
        }
        catch let error as NSError {
            handleErrorWithType(.leaveGroup, error: error)
        }
    }

    func presentInviteFriendPicker() {
        guard chat.groupNumber >= 0 else {
            return
        }

        let friends = submanagerObjects.friends()
        var connectedFriends: [OCTFriend] = []

        for index in 0..<friends.count {
            guard let friend = friends[index] as? OCTFriend else {
                continue
            }
            if friend.connectionStatus != .none && friend.friendNumber >= 0 {
                connectedFriends.append(friend)
            }
        }

        if connectedFriends.isEmpty {
            let alert = UIAlertController(title: nil, message: String(localized: "group_invite_friends_empty"), preferredStyle: .alert)
            alert.addAction(UIAlertAction(title: String(localized: "alert_ok"), style: .default, handler: nil))
            present(alert, animated: true, completion: nil)
            return
        }

        // KHANDAQ (#iOS group multi-invite): pick SEVERAL connected contacts at once instead of a
        // one-at-a-time action sheet. Uses the reliable friend-connection invite path
        // (tox_group_invite_friend) — no UDP peer discovery needed.
        let picker = GroupInviteFriendsController(theme: theme, friends: connectedFriends)
        picker.onConfirm = { [weak self] chosen in
            self?.navigationController?.popViewController(animated: true)
            self?.inviteFriends(chosen)
        }
        navigationController?.pushViewController(picker, animated: true)
    }

    func inviteFriends(_ friends: [OCTFriend]) {
        guard chat.groupNumber >= 0, !friends.isEmpty else {
            return
        }

        var sent = 0
        for friend in friends {
            do {
                try submanagerGroups.inviteFriend(withNumber: friend.friendNumber,
                                                  toGroupNumber: OCTToxGroupNumber(chat.groupNumber))
                sent += 1
            }
            catch let error as NSError {
                handleErrorWithType(.inviteFriendToGroup, error: error)
            }
        }

        guard sent > 0 else {
            return
        }

        let alert = UIAlertController(title: nil,
                                      message: String(localized: "group_invite_multi_success_format", sent),
                                      preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: String(localized: "alert_ok"), style: .default, handler: nil))
        presentSuccessAlert(alert)
    }

    func presentSuccessAlert(_ alert: UIAlertController) {
        if let presented = presentedViewController {
            presented.dismiss(animated: true) { [weak self] in
                self?.present(alert, animated: true, completion: nil)
            }
            return
        }
        present(alert, animated: true, completion: nil)
    }

    func reloadGroupInfo() {
        submanagerGroups.refreshPeers(for: chat)
        submanagerGroups.refreshGroupMetadata(for: chat)
        isFounder = submanagerGroups.selfRole(in: chat) == .founder
        selfRole = submanagerGroups.selfRole(in: chat)
        canEditTopic = submanagerGroups.canEditGroupTopic(in: chat)

        if let topic = try? submanagerGroups.groupTopic(for: chat) {
            topicText = topic
        }
        else {
            topicText = chat.groupTopic ?? chat.groupName ?? ""
        }

        passwordText = (try? submanagerGroups.groupPassword(for: chat)) ?? chat.groupPassword ?? ""

        let lock = submanagerGroups.groupTopicLock(for: chat, error: nil)
        topicLockEnabled = lock == .enabled

        let privacy = submanagerGroups.groupPrivacyState(for: chat, error: nil)
        privacyIsPrivate = privacy == .private

        voiceState = submanagerGroups.groupVoiceState(for: chat, error: nil)

        peerLimitValue = submanagerGroups.groupPeerLimit(for: chat, error: nil)
        if peerLimitValue < 1 {
            peerLimitValue = kOCTDefaultGroupPeerLimit
        }

        do {
            chatIdHex = try submanagerGroups.chatIdHex(for: chat)
        }
        catch {
            if let stored = chat.groupChatIdHex, !stored.isEmpty {
                chatIdHex = stored
            }
            else {
                chatIdHex = ""
            }
        }

        systemMessageCount = Int(submanagerGroups.groupSystemMessageCount(for: chat))

        if chat.groupNumber >= 0 {
            myPeerNameText = (try? submanagerGroups.groupSelfPeerName(for: chat)) ?? ""
        }
        else {
            myPeerNameText = ""
        }

        tableView?.reloadData()
    }

    func displayMemberCount() -> Int {
        // KHANDAQ (#9): total real members = online (peer count, incl. self) + saved offline members,
        // both from toxcore's NGC-synced state — consistent across clients.
        // KHANDAQ (#49): no offline term — keep the total equal to peerCount / listed rows so the info
        // header, the drawer and the chat-list row all agree.
        let fromTox = Int(submanagerGroups.peerCount(for: chat))
        let fromRealm = peers.count
        return max(fromTox, fromRealm, chat.groupNumber >= 0 ? 1 : 0)
    }
}

private extension OCTToxGroupRole {
    var localizedName: String {
        return groupLocalizedName
    }
}

extension GroupInfoController: ChatPrivateControllerDelegate {
    func chatPrivateControllerWillAppear(_ controller: ChatPrivateController) {
    }

    func chatPrivateControllerWillDisappear(_ controller: ChatPrivateController) {
    }

    func chatPrivateControllerCallToChat(_ controller: ChatPrivateController, enableVideo: Bool) {
    }

    func chatPrivateControllerShowQuickLookController(_ controller: ChatPrivateController,
                                                      dataSource: QuickLookPreviewControllerDataSource,
                                                      selectedIndex: Int) {
        // KHANDAQ (Figma / #204-C): media gallery for image/video; non-media (PDF, doc…) → QuickLook.
        if let fp = dataSource as? FilePreviewControllerDataSource,
           let start = fp.galleryStartIndex(forMessageIndex: selectedIndex) {
            let items = fp.galleryItems(myName: "")
            if !items.isEmpty {
                let gallery = MediaGalleryViewController(items: items, startIndex: start)
                navigationController?.present(gallery, animated: true, completion: nil)
                return
            }
        }

        let previewController = QuickLookPreviewController()
        previewController.dataSource = dataSource
        previewController.dataSourceStorage = dataSource
        previewController.currentPreviewItemIndex = selectedIndex
        navigationController?.present(previewController, animated: true, completion: nil)
    }

    // KHANDAQ (user request 17.08): the chat's attachments, split into files / audio / voice.
    func chatPrivateControllerShowAttachments(_ controller: ChatPrivateController, forChat chat: OCTChat) {
        let attachments = ChatAttachmentsController(theme: theme, chat: chat,
                                                    submanagerObjects: submanagerObjects)
        // Push onto whatever stack is showing the chat: on iPad that is the detail column, and in the
        // group-info preview it is that screen's own navigation controller.
        controller.navigationController?.pushViewController(attachments, animated: true)
    }

    func chatPrivateControllerShowFriendProfile(_ controller: ChatPrivateController, forFriend friend: OCTFriend) {
        // Not applicable in the group-info hosted preview.
    }
}

private extension OCTToxGroupVoiceState {
    var localizedName: String {
        switch self {
            case .all:
                return String(localized: "group_voice_all")
            case .moderator:
                return String(localized: "group_voice_moderator")
            case .founder:
                return String(localized: "group_voice_founder")
        }
    }
}

/// KHANDAQ (#iOS group multi-invite): a lightweight multi-select friend picker for inviting several
/// connected contacts to a group at once. Defined here (not a standalone file) so it joins the app
/// target without a project-file edit, mirroring the inline-helper pattern used elsewhere.
private final class GroupInviteFriendsController: UIViewController, UITableViewDataSource, UITableViewDelegate {
    var onConfirm: (([OCTFriend]) -> Void)?

    private let theme: Theme
    private let friends: [OCTFriend]
    private var selected = Set<Int>()
    private var tableView: UITableView!
    private let cellReuseIdentifier = "GroupInviteFriendCell"

    init(theme: Theme, friends: [OCTFriend]) {
        self.theme = theme
        self.friends = friends
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewDidLoad() {
        super.viewDidLoad()

        title = String(localized: "group_invite_picker_title")
        view.backgroundColor = theme.colorForType(.NormalBackground)

        tableView = UITableView(frame: .zero, style: .plain)
        tableView.dataSource = self
        tableView.delegate = self
        tableView.backgroundColor = theme.colorForType(.NormalBackground)
        tableView.separatorColor = theme.colorForType(.SeparatorsAndBorders)
        tableView.tableFooterView = UIView()
        tableView.register(UITableViewCell.self, forCellReuseIdentifier: cellReuseIdentifier)
        view.addSubview(tableView)
        tableView.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            tableView.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            tableView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            tableView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            tableView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
        ])

        navigationItem.rightBarButtonItem = UIBarButtonItem(title: confirmTitle(),
                                                            style: .done,
                                                            target: self,
                                                            action: #selector(confirmTapped))
        updateConfirmButton()
    }

    private func confirmTitle() -> String {
        return String(localized: "group_invite_confirm_format", selected.count)
    }

    private func updateConfirmButton() {
        navigationItem.rightBarButtonItem?.title = confirmTitle()
        navigationItem.rightBarButtonItem?.isEnabled = !selected.isEmpty
    }

    @objc private func confirmTapped() {
        guard !selected.isEmpty else {
            return
        }
        let chosen = selected.sorted().map { friends[$0] }
        onConfirm?(chosen)
    }

    // MARK: UITableViewDataSource

    func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        return friends.count
    }

    func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: cellReuseIdentifier, for: indexPath)
        let friend = friends[indexPath.row]
        cell.textLabel?.text = friend.name?.isEmpty == false ? friend.name! : friend.publicKey
        cell.textLabel?.textColor = theme.colorForType(.NormalText)
        cell.backgroundColor = theme.colorForType(.NormalBackground)
        cell.tintColor = theme.colorForType(.LinkText)
        cell.accessoryType = selected.contains(indexPath.row) ? .checkmark : .none
        cell.selectionStyle = .none
        return cell
    }

    // MARK: UITableViewDelegate

    func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: false)
        if selected.contains(indexPath.row) {
            selected.remove(indexPath.row)
        } else {
            selected.insert(indexPath.row)
        }
        tableView.reloadRows(at: [indexPath], with: .none)
        updateConfirmButton()
    }
}
