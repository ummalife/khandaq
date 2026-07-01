// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation

protocol ChatListTableManagerDelegate: class {
    func chatListTableManager(_ manager: ChatListTableManager, didSelectChat chat: OCTChat)
    func chatListTableManager(_ manager: ChatListTableManager, presentAlertController controller: UIAlertController)
    func chatListTableManagerWasUpdated(_ manager: ChatListTableManager)
    func chatListTableManager(_ manager: ChatListTableManager, didRequestGroupInfo chat: OCTChat)
    // KHANDAQ (Figma): multi-select edit mode — row (de)selection changed while editing.
    func chatListTableManagerSelectionDidChange(_ manager: ChatListTableManager)
}

class ChatListTableManager: NSObject {
    weak var delegate: ChatListTableManagerDelegate?

    let tableView: UITableView

    var filterTab: ChatListFilterTab = UserDefaultsManager().chatListFilterTab {
        didSet {
            UserDefaultsManager().chatListFilterTab = filterTab
        }
    }

    var isEmpty: Bool {
        get {
            return visibleRowCount() == 0
        }
    }

    fileprivate let theme: Theme
    fileprivate let avatarManager: AvatarManager
    fileprivate let dateFormatter: DateFormatter
    fileprivate let timeFormatter: DateFormatter

    fileprivate weak var submanagerChats: OCTSubmanagerChats!
    fileprivate weak var submanagerGroups: OCTSubmanagerGroups!
    fileprivate weak var submanagerObjects: OCTSubmanagerObjects!

    fileprivate let chats: Results<OCTChat>
    fileprivate var chatsToken: RLMNotificationToken?
    fileprivate let friends: Results<OCTFriend>
    fileprivate var friendsToken: RLMNotificationToken?
    fileprivate var presenceRefreshTimer: Timer?
    fileprivate var groupConnectionObserver: NSObjectProtocol?
    fileprivate var groupPeersObserver: NSObjectProtocol?

    init(theme: Theme, tableView: UITableView, submanagerChats: OCTSubmanagerChats, submanagerGroups: OCTSubmanagerGroups, submanagerObjects: OCTSubmanagerObjects) {
        self.tableView = tableView

        self.theme = theme
        self.avatarManager = AvatarManager(theme: theme)
        self.dateFormatter = DateFormatter(type: .relativeDate)
        self.timeFormatter = DateFormatter(type: .time)

        self.submanagerChats = submanagerChats
        self.submanagerGroups = submanagerGroups
        self.submanagerObjects = submanagerObjects

        self.chats = submanagerObjects.chats().sortedResultsUsingProperty("lastActivityDateInterval", ascending: false)
        self.friends = submanagerObjects.friends()

        super.init()

        tableView.delegate = self
        tableView.dataSource = self

        addNotificationBlocks()
        startPresenceRefreshTimer()
        startGroupObservers()
    }

    deinit {
        chatsToken?.invalidate()
        friendsToken?.invalidate()
        presenceRefreshTimer?.invalidate()
        if let observer = groupConnectionObserver {
            NotificationCenter.default.removeObserver(observer)
        }
        if let observer = groupPeersObserver {
            NotificationCenter.default.removeObserver(observer)
        }
    }

    func refreshGroupPeerCounts() {
        guard !shouldDeferListUpdates else {
            return
        }

        for index in 0..<chats.count {
            let chat = chats[index]
            if chat.isGroup {
                submanagerGroups.refreshPeers(for: chat)
            }
        }
    }

    func setFilterTab(_ tab: ChatListFilterTab) {
        guard filterTab != tab else {
            return
        }

        filterTab = tab
        tableView.reloadData()
        delegate?.chatListTableManagerWasUpdated(self)
    }

    func unreadCountsForFilterTabs() -> ChatListFilterUnreadCounts {
        var counts = ChatListFilterUnreadCounts()

        for index in 0..<chats.count {
            let chat = chats[index]
            let unread = unreadMessageCount(for: chat)
            guard unread > 0 else {
                continue
            }

            // KHANDAQ (#65): the filter-tab badge counts CHATS with unread (Telegram-style, consistent
            // with the bottom Чаты tab), not the sum of unread messages — so one group with 7 unread
            // shows "1", not "7".
            if !chat.isGroup {
                counts.direct += 1
            }
            else {
                counts.groups += 1
            }

            if ChatFavoritesStore.isFavorite(chat: chat) {
                counts.favorites += 1
            }
        }

        return counts
    }

    func toggleFavorite(at indexPath: IndexPath) {
        let chat = chatAtFilteredRow(indexPath.row)
        ChatFavoritesStore.toggle(chat: chat)
        tableView.reloadData()
        delegate?.chatListTableManagerWasUpdated(self)
    }
}

extension ChatListTableManager: UITableViewDataSource {
    func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        var avatarData: Data?
        var nickname = String(localized: "contact_deleted")
        var connectionStatus = OCTToxConnectionStatus.none
        var userStatus = OCTToxUserStatus.none

        let chat = chatAtFilteredRow(indexPath.row)

        if chat.isGroup {
            // KHANDAQ (#46): prefer the owner-changeable TOPIC (NGC group name is immutable after
            // creation, so a "rename" changes the topic); fall back to the immutable name.
            let nickname = (chat.groupTopic?.isEmpty == false ? chat.groupTopic : chat.groupName)
                ?? String(localized: "group_chat_default_title")
            let model = ChatListCellModel()
            model.avatar = avatarManager.avatarFromString(
                    nickname,
                    diameter: CGFloat(ChatListCell.Constants.AvatarSize))
            model.nickname = nickname
            let preview = lastMessagePreview(in: chat, friend: nil)
            model.message = preview.text
            model.isDraft = preview.isDraft
            if let date = chat.lastActivityDate() {
                model.dateText = dateTextFromDate(date)
            }
            model.isUnread = chatShowsUnreadIndicator(for: chat,
                                                      privateUnreadCount: Int(submanagerGroups.totalUnreadPrivateMessageCount(for: chat)))
            model.unreadCount = unreadMessageCount(for: chat)   // KHANDAQ (#30): numeric badge
            var presenceParts: [String] = []
            if chat.groupPrivacyState == Int32(OCTToxGroupPrivacyState.private.rawValue) {
                presenceParts.append(String(localized: "group_chat_list_private"))
            }
            if !submanagerGroups.isGroupConnected(for: chat) {
                presenceParts.append(String(localized: "group_chat_list_disconnected"))
            }
            let memberCount = groupMemberCount(for: chat)
            if memberCount > 0 {
                let onlineCount = Int(submanagerGroups.onlineGroupPeerCount(for: chat))
                if submanagerGroups.isGroupConnected(for: chat) {
                    presenceParts.append(String(localized: "group_member_online_count_format", memberCount, onlineCount))
                    model.presenceIsOnline = onlineCount > 0
                }
                else {
                    presenceParts.append(String(localized: "group_member_count_format", memberCount))
                    model.presenceIsOnline = false
                }
            }
            else if presenceParts.isEmpty {
                presenceParts.append(String(localized: "group_chat_list_subtitle"))
            }
            model.presenceText = presenceParts.joined(separator: " · ")
            if !submanagerGroups.isGroupConnected(for: chat) {
                model.presenceIsOnline = false
            }
            let privateUnread = submanagerGroups.totalUnreadPrivateMessageCount(for: chat)
            if privateUnread > 0 {
                let privateHint = String(localized: "group_chat_list_private_unread_format", privateUnread)
                if model.presenceText.isEmpty {
                    model.presenceText = privateHint
                }
                else {
                    model.presenceText += " · " + privateHint
                }
            }

            let cell = tableView.dequeueReusableCell(withIdentifier: ChatListCell.staticReuseIdentifier) as! ChatListCell
            cell.setupWithTheme(theme, model: model)
            return cell
        }

        if chat.isSavedMessages {
            let model = ChatListCellModel()
            let title = String(localized: "saved_messages_title")
            // KHANDAQ (#118): render the bookmark on a filled circle at the standard avatar size, so it
            // matches the other (circular) avatars instead of a bare, oversized glyph.
            let diameter = CGFloat(ChatListCell.Constants.AvatarSize)
            if #available(iOS 13.0, *) {
                let renderer = UIGraphicsImageRenderer(size: CGSize(width: diameter, height: diameter))
                model.avatar = renderer.image { ctx in
                    theme.colorForType(.LinkText).setFill()
                    ctx.cgContext.fillEllipse(in: CGRect(x: 0, y: 0, width: diameter, height: diameter))
                    let glyph = diameter * 0.5
                    UIImage(systemName: "bookmark.fill")?
                        .withTintColor(.white, renderingMode: .alwaysOriginal)
                        .draw(in: CGRect(x: (diameter - glyph) / 2, y: (diameter - glyph) / 2, width: glyph, height: glyph))
                }
            }
            else {
                model.avatar = avatarManager.avatarFromString(title, diameter: diameter)
            }
            model.nickname = title
            let preview = lastMessagePreview(in: chat, friend: nil)
            model.message = preview.text
            model.isDraft = preview.isDraft
            if let date = chat.lastActivityDate() {
                model.dateText = dateTextFromDate(date)
            }
            // KHANDAQ (#41): keep the row dot consistent with the numeric badge / tab badge — both
            // ignore outgoing & system messages (the core hasUnreadMessages() is date-only).
            model.unreadCount = unreadMessageCount(for: chat)   // KHANDAQ (#30): numeric badge
            model.isUnread = model.unreadCount > 0
            let cell = tableView.dequeueReusableCell(withIdentifier: ChatListCell.staticReuseIdentifier) as! ChatListCell
            cell.setupWithTheme(theme, model: model)
            return cell
        }

        let friend = chat.friends.lastObject() as? OCTFriend

        if let friend = friend {
            avatarData = friend.avatarData
            nickname = friend.nickname
            connectionStatus = friend.connectionStatus
            userStatus = friend.status
        }

        let model = ChatListCellModel()
        if let data = avatarData {
            model.avatar = UIImage(data: data)
        }
        else {
            model.avatar = avatarManager.avatarFromString(
                    nickname,
                    diameter: CGFloat(ChatListCell.Constants.AvatarSize))
        }

        model.nickname = nickname
        let preview = lastMessagePreview(in: chat, friend: friend)
        model.message = preview.text
        model.isDraft = preview.isDraft
        if let date = chat.lastActivityDate() {
            model.dateText = dateTextFromDate(date)
        }

        model.status = UserStatus(connectionStatus: connectionStatus, userStatus: userStatus)
        model.connectionstatus = ConnectionStatus(connectionStatus: connectionStatus)

        if let friend = friend {
            let presence = FriendPresenceFormatter.presence(for: friend)
            model.presenceText = presence.text
            model.presenceIsOnline = presence.isOnline
        }

        model.isUnread = chatShowsUnreadIndicator(for: chat)
        model.unreadCount = unreadMessageCount(for: chat)   // KHANDAQ (#30): numeric badge

        let cell = tableView.dequeueReusableCell(withIdentifier: ChatListCell.staticReuseIdentifier) as! ChatListCell
        cell.setupWithTheme(theme, model: model)

        return cell
    }

    func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        return visibleRowCount()
    }

    func tableView(_ tableView: UITableView, commit editingStyle: UITableViewCellEditingStyle, forRowAt indexPath: IndexPath) {
        if editingStyle == .delete {
            let chat = chatAtFilteredRow(indexPath.row)

            if chat.isGroup {
                presentGroupDeleteOptions(for: chat)
                return
            }

            let alert = UIAlertController(title: String(localized:"delete_chat_title"), message: nil, preferredStyle: .alert)

            alert.addAction(UIAlertAction(title: String(localized: "alert_cancel"), style: .default, handler: nil))
            alert.addAction(UIAlertAction(title: String(localized: "alert_delete"), style: .destructive) { [unowned self] _ -> Void in
                self.submanagerChats.removeAllMessages(in: chat, removeChat: true)
            })

            delegate?.chatListTableManager(self, presentAlertController: alert)
        }
    }
}

extension ChatListTableManager: UITableViewDelegate {
    func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        // KHANDAQ (Figma): in multi-select edit mode a tap toggles the selection circle instead of
        // opening the chat.
        if tableView.isEditing {
            delegate?.chatListTableManagerSelectionDidChange(self)
            return
        }

        tableView.deselectRow(at: indexPath, animated: true)

        let chat = chatAtFilteredRow(indexPath.row)
        delegate?.chatListTableManager(self, didSelectChat: chat)
    }

    func tableView(_ tableView: UITableView, didDeselectRowAt indexPath: IndexPath) {
        if tableView.isEditing {
            delegate?.chatListTableManagerSelectionDidChange(self)
        }
    }

    @available(iOS 11.0, *)
    func tableView(_ tableView: UITableView, trailingSwipeActionsConfigurationForRowAt indexPath: IndexPath) -> UISwipeActionsConfiguration? {
        let chat = chatAtFilteredRow(indexPath.row)

        let favoriteTitle = ChatFavoritesStore.isFavorite(chat: chat)
            ? String(localized: "chat_filter_remove_favorite")
            : String(localized: "chat_filter_add_favorite")
        let favoriteAction = UIContextualAction(style: .normal, title: favoriteTitle) { [unowned self] _, _, completion in
            self.toggleFavorite(at: indexPath)
            completion(true)
        }
        favoriteAction.backgroundColor = theme.colorForType(.LinkText)

        if chat.isGroup {
            let deleteAction = UIContextualAction(style: .destructive, title: String(localized: "alert_delete")) { [unowned self] _, _, completion in
                self.presentGroupDeleteOptions(for: chat)
                completion(true)
            }

            let infoAction = UIContextualAction(style: .normal, title: String(localized: "group_info_button")) { [unowned self] _, _, completion in
                self.delegate?.chatListTableManager(self, didRequestGroupInfo: chat)
                completion(true)
            }
            infoAction.backgroundColor = theme.colorForType(.LinkText)

            let config = UISwipeActionsConfiguration(actions: [deleteAction, infoAction, favoriteAction])
            config.performsFirstActionWithFullSwipe = false
            return config
        }

        // KHANDAQ (#62): 1:1 chats can be deleted by swipe too (previously only "В избранное"). Removes
        // the chat + its message history but keeps the contact. Confirmed first (irreversible) and no
        // full-swipe, so it can't fire accidentally.
        let deleteAction = UIContextualAction(style: .destructive, title: String(localized: "alert_delete")) { [unowned self] _, _, completion in
            let alert = UIAlertController(title: String(localized: "alert_delete"), message: nil, preferredStyle: .actionSheet)
            alert.addAction(UIAlertAction(title: String(localized: "alert_delete"), style: .destructive) { [unowned self] _ in
                self.submanagerChats.removeAllMessages(in: chat, removeChat: true)
                self.delegate?.chatListTableManagerWasUpdated(self)
            })
            alert.addAction(UIAlertAction(title: String(localized: "alert_cancel"), style: .cancel, handler: nil))
            self.delegate?.chatListTableManager(self, presentAlertController: alert)
            completion(true)
        }

        let config = UISwipeActionsConfiguration(actions: [deleteAction, favoriteAction])
        config.performsFirstActionWithFullSwipe = false
        return config
    }

    @available(iOS 13.0, *)
    func tableView(_ tableView: UITableView, contextMenuConfigurationForRowAt indexPath: IndexPath, point: CGPoint) -> UIContextMenuConfiguration? {
        let chat = chatAtFilteredRow(indexPath.row)
        let isFavorite = ChatFavoritesStore.isFavorite(chat: chat)
        let favoriteTitle = isFavorite
            ? String(localized: "chat_filter_remove_favorite")
            : String(localized: "chat_filter_add_favorite")

        // KHANDAQ (#13): show a read-only preview of the conversation when peeking a chat row.
        // ChatPreviewController is self-contained and has no side effects (does not mark read).
        return UIContextMenuConfiguration(
            identifier: chat.uniqueIdentifier as NSString,
            previewProvider: { [weak self] in
                return self?.makeChatPreviewController(for: chat)
            }
        ) { [weak self] _ in
            let favoriteAction = UIAction(title: favoriteTitle, image: UIImage(systemName: isFavorite ? "star.slash" : "star")) { _ in
                self?.toggleFavorite(at: indexPath)
            }
            return UIMenu(children: [favoriteAction])
        }
    }

    @available(iOS 13.0, *)
    func tableView(_ tableView: UITableView, willPerformPreviewActionForMenuWith configuration: UIContextMenuConfiguration, animator: UIContextMenuInteractionCommitAnimating) {
        guard let identifier = configuration.identifier as? String else {
            return
        }
        // Tapping the peek preview opens the real chat.
        animator.addCompletion { [weak self] in
            guard let self = self else {
                return
            }
            for index in 0..<self.chats.count {
                let chat = self.chats[index]
                if chat.uniqueIdentifier == identifier {
                    self.delegate?.chatListTableManager(self, didSelectChat: chat)
                    break
                }
            }
        }
    }

    @available(iOS 13.0, *)
    private func makeChatPreviewController(for chat: OCTChat) -> ChatPreviewController {
        let title: String
        let avatar: UIImage?

        if chat.isSavedMessages {
            // KHANDAQ (#109/#110): the long-press peek of Saved Messages must show "Избранное" + the
            // bookmark — not the 1:1 fallback "Удалённый контакт" (Saved Messages has no friend).
            title = String(localized: "saved_messages_title")
            var icon: UIImage?
            if #available(iOS 13.0, *) {
                icon = UIImage(systemName: "bookmark.fill")?.withTintColor(theme.colorForType(.LinkText), renderingMode: .alwaysOriginal)
            }
            avatar = icon ?? avatarManager.avatarFromString(title, diameter: CGFloat(ChatListCell.Constants.AvatarSize))
        }
        else if chat.isGroup {
            // KHANDAQ (#46): prefer the owner-changeable TOPIC (see groupTopic note above).
            title = (chat.groupTopic?.isEmpty == false ? chat.groupTopic : chat.groupName)
                ?? String(localized: "group_chat_default_title")
            avatar = avatarManager.avatarFromString(title, diameter: CGFloat(ChatListCell.Constants.AvatarSize))
        }
        else {
            let friend = chat.friends.lastObject() as? OCTFriend
            title = friend?.nickname ?? String(localized: "contact_deleted")
            if let data = friend?.avatarData {
                avatar = UIImage(data: data)
            }
            else {
                avatar = avatarManager.avatarFromString(title, diameter: CGFloat(ChatListCell.Constants.AvatarSize))
            }
        }

        let allMessages = submanagerObjects.messages(predicate: NSPredicate(format: "chatUniqueIdentifier == %@", chat.uniqueIdentifier))
            .sortedResultsUsingProperty("dateInterval", ascending: true)

        var recent = [OCTMessageAbstract]()
        let total = allMessages.count
        var index = max(0, total - 24)
        while index < total {
            recent.append(allMessages[index])
            index += 1
        }

        return ChatPreviewController(theme: theme, title: title, avatar: avatar, messages: recent)
    }
}

extension ChatListTableManager {
    // KHANDAQ (Figma): mass delete from the multi-select edit mode. 1:1 chats are removed with their
    // messages; groups are left (best-effort) and removed — the same semantics as the row-level
    // destructive actions, applied without per-chat prompts (the controller confirms once for all).
    func deleteChats(atVisibleRows rows: [Int]) {
        let chats = rows.sorted().compactMap { $0 < visibleRowCount() ? chatAtFilteredRow($0) : nil }

        for chat in chats {
            if chat.isGroup {
                if chat.groupNumber >= 0 {
                    do {
                        try submanagerGroups.leaveGroup(withNumber: OCTToxGroupNumber(chat.groupNumber), partMessage: nil)
                    }
                    catch {
                        // Still remove the local chat if the tox leave fails (already left, disconnected, …).
                    }
                }
                submanagerGroups.removeAllMessages(in: chat, removeChat: true, leaveGroup: false)
            }
            else {
                submanagerChats.removeAllMessages(in: chat, removeChat: true)
            }
        }
    }
}

private extension ChatListTableManager {
    func addNotificationBlocks() {
        chatsToken = chats.addNotificationBlock { [weak self] change in
            guard let self = self else {
                return
            }

            switch change {
                case .initial:
                    break
                case .update(_, _, _, _):
                    DispatchQueue.main.async { [weak self] in
                        guard let self = self, self.tableView.window != nil, !self.shouldDeferListUpdates else {
                            return
                        }
                        self.tableView.reloadData()
                        self.delegate?.chatListTableManagerWasUpdated(self)
                    }
                case .error(let error):
                    NSLog("ChatListTableManager chats update error: \(error)")
            }
        }

        friendsToken = friends.addNotificationBlock { [weak self] change in
            guard let self = self else {
                return
            }

            switch change {
                case .initial:
                    break
                case .update(_, _, _, let modifications):
                    guard !modifications.isEmpty else {
                        break
                    }

                    self.reloadChatRowsForFriendModifications(modifications)
                case .error(let error):
                    NSLog("ChatListTableManager friends update error: \(error)")
            }
        }
    }

    func reloadChatRowsForFriendModifications(_ friendIndices: [Int]) {
        guard !friendIndices.isEmpty else {
            return
        }

        DispatchQueue.main.async { [weak self] in
            guard let self = self, self.tableView.window != nil, !self.shouldDeferListUpdates else {
                return
            }

            // Friend accept can insert a new chat row while friends update fires — partial reloadRows races with chats.reloadData.
            self.tableView.reloadData()
        }
    }

    func startPresenceRefreshTimer() {
        presenceRefreshTimer?.invalidate()
        presenceRefreshTimer = Timer.scheduledTimer(withTimeInterval: 60, repeats: true) { [weak self] _ in
            guard let self = self, self.tableView.window != nil, !self.shouldDeferListUpdates else {
                return
            }

            self.refreshGroupPeerCounts()
            self.tableView.reloadData()
        }
    }

    var shouldDeferListUpdates: Bool {
        var responder: UIResponder? = tableView

        while let current = responder {
            if let controller = current as? UIViewController, controller.presentedViewController != nil {
                return true
            }

            responder = current.next
        }

        return false
    }

    func startGroupObservers() {
        groupConnectionObserver = NotificationCenter.default.addObserver(
            forName: .octGroupConnectionStatusChange,
            object: nil,
            queue: .main) { [weak self] notification in
            guard let self = self,
                  let chatId = notification.userInfo?[kOCTGroupConnectionStatusChangeChatUniqueIdentifierKey] as? String else {
                return
            }

            self.refreshPeers(forChatUniqueIdentifier: chatId)
        }

        groupPeersObserver = NotificationCenter.default.addObserver(
            forName: .octGroupPeersUpdated,
            object: nil,
            queue: .main) { [weak self] notification in
            guard let self = self,
                  let chatId = notification.userInfo?[kOCTGroupPeersUpdatedChatUniqueIdentifierKey] as? String else {
                return
            }

            self.reloadRow(forChatUniqueIdentifier: chatId)
        }
    }

    func refreshPeers(forChatUniqueIdentifier chatUniqueIdentifier: String) {
        for index in 0..<chats.count {
            let chat = chats[index]
            if chat.isGroup && chat.uniqueIdentifier == chatUniqueIdentifier {
                submanagerGroups.refreshPeers(for: chat)
                tableView.reloadRows(at: [IndexPath(row: index, section: 0)], with: .none)
                return
            }
        }
    }

    func reloadRow(forChatUniqueIdentifier chatUniqueIdentifier: String) {
        for index in 0..<chats.count {
            if chats[index].uniqueIdentifier == chatUniqueIdentifier {
                guard tableView.window != nil else {
                    return
                }

                tableView.reloadRows(at: [IndexPath(row: index, section: 0)], with: .none)
                return
            }
        }
    }

    func presentGroupDeleteOptions(for chat: OCTChat) {
        let alert = UIAlertController(title: String(localized: "group_delete_chat_title"),
                                      message: String(localized: "group_delete_chat_message"),
                                      preferredStyle: .alert)

        alert.addAction(UIAlertAction(title: String(localized: "alert_cancel"), style: .cancel, handler: nil))
        alert.addAction(UIAlertAction(title: String(localized: "group_clear_history_action"), style: .default) { [unowned self] _ in
            self.submanagerGroups.removeAllMessages(in: chat, removeChat: false, leaveGroup: false)
        })
        alert.addAction(UIAlertAction(title: String(localized: "group_leave_action"), style: .destructive) { [unowned self] _ in
            if chat.groupNumber >= 0 {
                do {
                    try self.submanagerGroups.leaveGroup(withNumber: OCTToxGroupNumber(chat.groupNumber), partMessage: nil)
                }
                catch {
                    // Still remove local chat if tox leave fails (already left, disconnected, etc.).
                }
            }
            self.submanagerGroups.removeAllMessages(in: chat, removeChat: true, leaveGroup: false)
        })

        delegate?.chatListTableManager(self, presentAlertController: alert)
    }

    func lastMessage(in chat: OCTChat, friend: OCTFriend?) -> String {
        return lastMessagePreview(in: chat, friend: friend).text
    }

    func lastMessagePreview(in chat: OCTChat, friend: OCTFriend?) -> (text: String, isDraft: Bool) {
        if let friend = friend, friend.isTyping {
            return (String(localized: "chat_is_typing_text"), false)
        }

        if let draft = chat.enteredText?.trimmingCharacters(in: .whitespacesAndNewlines),
           !draft.isEmpty,
           draft != "(null)" {
            var body = draft.replacingOccurrences(of: "\n", with: " ")
            if body.count > 120 {
                let endIndex = body.index(body.startIndex, offsetBy: 117)
                body = String(body[..<endIndex]) + "..."
            }
            return (String(localized: "chat_draft_prefix", body), true)
        }

        guard let message = chat.lastMessage else {
            return ("", false)
        }

        if message.messageText != nil {
            // KHANDAQ (#109): show the clean visible body in the list preview, not the reply/mention wire
            // markup ([KQ|...|Вы] ... [KQ/end]).
            return (MessageReplyHelper.plainBody(for: message) ?? message.messageText?.text ?? "", false)
        }
        else if let file = message.messageFile {
            let fileName = file.fileName ?? ""
            return (String(localized: message.isOutgoing() ? "chat_outgoing_file" : "chat_incoming_file") + " \(fileName)", false)
        }
        else if let call = message.messageCall {
            switch call.callEvent {
                case .answered:
                    let timeString = String(timeInterval: call.callDuration)
                    return (String(localized: "chat_call_finished") + " - \(timeString)", false)
                case .unanswered:
                    return (message.isOutgoing() ?  String(localized: "chat_unanwered_call") : String(localized: "chat_missed_call_message"), false)
            }
        }

        return ("", false)
    }

    func dateTextFromDate(_ date: Date) -> String {
        let isToday = (Calendar.current as NSCalendar).compare(Date(), to: date, toUnitGranularity: .day) == .orderedSame

        return isToday ? timeFormatter.string(from: date) : dateFormatter.string(from: date)
    }

    func groupMemberCount(for chat: OCTChat) -> Int {
        let stored = Int(chat.groupPeerCount)
        let fromApi = Int(submanagerGroups.peerCount(for: chat))
        var count = max(stored, fromApi)
        if count <= 0 && (chat.groupNumber >= 0 || (chat.groupChatIdHex?.count ?? 0) == 64) {
            count = 1
        }
        return count
    }

    func chatShowsUnreadIndicator(for chat: OCTChat, privateUnreadCount: Int = 0) -> Bool {
        if privateUnreadCount > 0 {
            return true
        }

        guard chat.hasUnreadMessages(), let lastMessage = chat.lastMessage else {
            return false
        }

        return !lastMessage.isOutgoing()
    }

    func visibleRowCount() -> Int {
        var count = 0
        for index in 0..<chats.count {
            if chatMatchesFilter(chats[index]) {
                count += 1
            }
        }
        return count
    }

    func chatAtFilteredRow(_ row: Int) -> OCTChat {
        // Saved Messages is always pinned to the top (within whatever filter tab it qualifies for).
        var saved: OCTChat?
        var visible = 0

        for index in 0..<chats.count {
            let chat = chats[index]
            guard chatMatchesFilter(chat) else {
                continue
            }
            if chat.isSavedMessages {
                saved = chat
            }
        }

        if saved != nil {
            if row == 0 {
                return saved!
            }
            for index in 0..<chats.count {
                let chat = chats[index]
                guard chatMatchesFilter(chat), !chat.isSavedMessages else {
                    continue
                }
                visible += 1
                if visible == row {
                    return chat
                }
            }
            return saved!
        }

        for index in 0..<chats.count {
            let chat = chats[index]
            if chatMatchesFilter(chat) {
                if visible == row {
                    return chat
                }
                visible += 1
            }
        }
        return chats.firstObject
    }

    func chatMatchesFilter(_ chat: OCTChat) -> Bool {
        switch filterTab {
            case .direct:
                return !chat.isGroup
            case .groups:
                return chat.isGroup
            case .favorites:
                return ChatFavoritesStore.isFavorite(chat: chat)
        }
    }

    func unreadMessageCount(for chat: OCTChat) -> Int {
        let privateUnread = chat.isGroup ? Int(submanagerGroups.totalUnreadPrivateMessageCount(for: chat)) : 0
        if privateUnread > 0 {
            return privateUnread
        }

        guard chat.hasUnreadMessages() else {
            return 0
        }

        let predicate = NSPredicate(format: "chatUniqueIdentifier == %@ AND dateInterval > %f",
                                    chat.uniqueIdentifier,
                                    chat.lastReadDateInterval)
        guard let results = submanagerObjects.objects(for: .messageAbstract, predicate: predicate) else {
            return 0
        }
        var count = 0
        for index in 0..<results.count {
            guard let message = results.object(at: index) as? OCTMessageAbstract else {
                continue
            }
            // KHANDAQ (#41): only genuine incoming messages count — skip outgoing and "X joined"
            // system notices (isOutgoing() returns NO for system messages, so guard explicitly).
            if !message.isOutgoing() && !message.groupSystemMessage {
                count += 1
            }
        }
        return count
    }
}
