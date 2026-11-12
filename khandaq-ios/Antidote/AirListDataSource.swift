// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit

protocol AirListDataSourceDelegate: class {
    func airList(_ list: AirListDataSource, didSelectChat chat: OCTChat)
    func airList(_ list: AirListDataSource, didRequestGroupInfo chat: OCTChat)
    func airList(_ list: AirListDataSource, present controller: UIViewController)
    /// The counts, the empty state or what can be selected changed.
    func airListDidUpdate(_ list: AirListDataSource)
    func airListSelectionDidChange(_ list: AirListDataSource)
    /// "Select" in a row's menu: start choosing chats, with this row already chosen.
    func airList(_ list: AirListDataSource, didRequestSelectionStartingAt indexPath: IndexPath)
}

/**
 Feeds the Air list from the database: chats, contacts without a chat, incoming requests and the
 messages still on their way, turned into AirEntry values and handed to AirListBuilder, so every number
 on the screen comes from one snapshot.

 Database changes are coalesced into one rebuild per run loop turn. A rebuild while the list is on
 screen is applied as a batch update (AirListDiff), so a person who comes online is the one row that
 moves; with Reduce Motion the change cross-fades instead. Nothing is applied while the list is off
 screen, while chats are being selected, while a row's actions are open or its menu is up: the list
 catches up as soon as that ends.
 */
final class AirListDataSource: NSObject {
    weak var delegate: AirListDataSourceDelegate?

    let tableView: UITableView
    private(set) var snapshot = AirSnapshot.empty
    private(set) var filter = AirFilter.current
    private(set) var query = ""

    private let theme: Theme
    private let avatarManager: AvatarManager
    private let timeFormatter = DateFormatter(type: .time)
    private let dateFormatter = DateFormatter(type: .relativeDate)

    private weak var submanagerChats: OCTSubmanagerChats!
    private weak var submanagerGroups: OCTSubmanagerGroups!
    private weak var submanagerObjects: OCTSubmanagerObjects!
    private weak var submanagerFriends: OCTSubmanagerFriends!

    private let chats: Results<OCTChat>
    private let friends: Results<OCTFriend>
    private let requests: Results<OCTFriendRequest>
    private let transitMessages: Results<OCTMessageAbstract>
    private var tokens: [RLMNotificationToken] = []
    private var observers: [NSObjectProtocol] = []
    private var presenceTimer: Timer?

    private var chatsById: [String: OCTChat] = [:]
    private var friendsByKey: [String: OCTFriend] = [:]
    private var requestsById: [String: OCTFriendRequest] = [:]
    /// Cards declined a moment ago: hidden while the toast offers "Undo", removed for good when it goes.
    private var declining = Set<String>()
    private var messageHits: [AirMessageHit] = []
    private var photos: [String: (data: Data, image: UIImage)] = [:]

    private var rebuildScheduled = false
    private var pendingRebuild = false
    private var hasLoaded = false
    private var hasRevealed = false
    private var openMenus = 0
    private var isSwipingRow = false

    init(theme: Theme,
         tableView: UITableView,
         submanagerChats: OCTSubmanagerChats,
         submanagerGroups: OCTSubmanagerGroups,
         submanagerObjects: OCTSubmanagerObjects,
         submanagerFriends: OCTSubmanagerFriends) {
        self.theme = theme
        self.tableView = tableView
        self.avatarManager = AvatarManager(theme: theme)
        self.submanagerChats = submanagerChats
        self.submanagerGroups = submanagerGroups
        self.submanagerObjects = submanagerObjects
        self.submanagerFriends = submanagerFriends

        chats = submanagerObjects.chats().sortedResultsUsingProperty("lastActivityDateInterval", ascending: false)
        friends = submanagerObjects.friends()
        requests = submanagerObjects.friendRequests()
        transitMessages = submanagerObjects.messages(predicate: AirListDataSource.transitPredicate)

        super.init()

        configureTable()
        observeChanges()
        rebuild(animated: false)
    }

    deinit {
        tokens.forEach { $0.invalidate() }
        observers.forEach { NotificationCenter.default.removeObserver($0) }
        presenceTimer?.invalidate()
    }

    // MARK: - What the controller drives

    func setFilter(_ filter: AirFilter) {
        guard self.filter != filter else {
            return
        }
        self.filter = filter
        AirFilter.current = filter
        // The person asked for another list, so it shows now instead of being held like a background
        // change. A sideways swipe that changes the filter also opens the actions of the row under the
        // finger: close them first.
        if isSwipingRow {
            tableView.setEditing(false, animated: false)
            isSwipingRow = false
        }
        rebuild(animated: true, force: openMenus == 0)
    }

    /// The command line's search. Message matches are looked up once per query, not on every change.
    func setQuery(_ query: String) {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed != self.query else {
            return
        }
        self.query = trimmed
        messageHits = trimmed.isEmpty ? [] : findMessages(matching: trimmed)
        // Typing narrows the list at once; sliding rows on every keystroke would only be noise.
        rebuild(animated: false)
    }

    func willAppear() {
        refreshGroupPeers()
        // Not in the window yet at this point, so the usual hold would keep the list as it was.
        if pendingRebuild || !hasLoaded {
            rebuild(animated: false, force: true)
        }
        if !hasRevealed {
            hideRowsForReveal()
        }
    }

    func didAppear() {
        if !hasRevealed {
            hasRevealed = true
            revealRows()
        }
        if pendingRebuild {
            setNeedsRebuild()
        }
    }

    /// Call when choosing chats ends, so the updates held back meanwhile arrive.
    func editingDidEnd() {
        if pendingRebuild {
            setNeedsRebuild()
        }
    }

    func refreshGroupPeers() {
        for index in 0..<chats.count {
            let chat = chats[index]
            if chat.isGroup {
                submanagerGroups.refreshPeers(for: chat)
            }
        }
    }

    /// Chats picked in selection mode, in list order.
    var selectedChats: [OCTChat] {
        let paths = (tableView.indexPathsForSelectedRows ?? []).sorted()
        return paths.compactMap { snapshot.entry(at: $0) }.compactMap { liveChat($0.id) }
    }

    var hasSelectableRows: Bool {
        return snapshot.sections.contains { section in
            section.items.contains { item in
                if case .entry(let entry) = item {
                    return liveChat(entry.id) != nil
                }
                return false
            }
        }
    }

    func deleteSelectedChats() {
        ChatListContent.removeChats(selectedChats, submanagerChats: submanagerChats, submanagerGroups: submanagerGroups)
    }

    func canSelect(at indexPath: IndexPath) -> Bool {
        guard let entry = snapshot.entry(at: indexPath) else {
            return false
        }
        return liveChat(entry.id) != nil
    }
}

// MARK: - Lookups

private extension AirListDataSource {
    // Updates can be held back (selection, an open menu or swipe), so the snapshot may still show an
    // object the database has just deleted. Realm raises on any access to such an object; every action
    // goes through these lookups and simply finds nothing.

    func liveChat(_ id: String) -> OCTChat? {
        guard let chat = chatsById[id], !chat.isInvalidated else {
            return nil
        }
        return chat
    }

    func liveFriend(_ publicKey: String) -> OCTFriend? {
        guard let friend = friendsByKey[publicKey], !friend.isInvalidated else {
            return nil
        }
        return friend
    }

    func liveRequest(_ id: String) -> OCTFriendRequest? {
        guard let request = requestsById[id], !request.isInvalidated else {
            return nil
        }
        return request
    }
}

// MARK: - Building

private extension AirListDataSource {
    /// Our own messages that have not reached anyone yet: 1:1 texts without a receipt, files queued for
    /// an offline friend, group messages queued while the group was disconnected. A 1:1 row counts them
    /// only while the person is away (AirReach.messagesInTransit), as the conversation's tray does. Group
    /// rows match only through groupPendingSend: a sent group text is marked delivered when it is sent.
    static var transitPredicate: NSPredicate {
        return NSPredicate(
            format: "senderUniqueIdentifier == nil AND groupSystemMessage == NO AND groupHistorySync == NO AND groupSenderPeerId == 0 AND (groupPendingSend == YES OR (messageText != nil AND messageText.isDelivered == NO) OR (messageFile != nil AND messageFile.fileType == %d AND messageFile.internalFileNumber == %d))",
            OCTMessageFileType.waitingConfirmation.rawValue,
            Int32(bitPattern: kOCTToxFileNumberFailure))
    }

    func configureTable() {
        tableView.dataSource = self
        tableView.delegate = self
        tableView.backgroundColor = KQColor.surface
        tableView.separatorStyle = .none
        tableView.rowHeight = UITableViewAutomaticDimension
        tableView.estimatedRowHeight = KQSize.rowPhone
        tableView.sectionHeaderHeight = UITableViewAutomaticDimension
        tableView.estimatedSectionHeaderHeight = KQSpace.x32
        tableView.sectionFooterHeight = 0
        tableView.sectionHeaderTopPadding = 0
        tableView.allowsMultipleSelectionDuringEditing = true
        tableView.keyboardDismissMode = .onDrag
        tableView.tintColor = KQColor.ink
        tableView.accessibilityIdentifier = "air.list"

        tableView.register(AirRowCell.self, forCellReuseIdentifier: AirRowCell.reuseIdentifier)
        tableView.register(AirRequestCell.self, forCellReuseIdentifier: AirRequestCell.reuseIdentifier)
        tableView.register(AirMessageCell.self, forCellReuseIdentifier: AirMessageCell.reuseIdentifier)
        tableView.register(AirSectionHeaderView.self, forHeaderFooterViewReuseIdentifier: AirSectionHeaderView.reuseIdentifier)
    }

    func observeChanges() {
        tokens.append(chats.addNotificationBlock { [weak self] change in
            self?.handle(change, source: "chats")
        })
        tokens.append(friends.addNotificationBlock { [weak self] change in
            self?.handle(change, source: "friends")
        })
        tokens.append(requests.addNotificationBlock { [weak self] change in
            self?.handle(change, source: "requests")
        })
        tokens.append(transitMessages.addNotificationBlock { [weak self] change in
            self?.handle(change, source: "transit")
        })

        let center = NotificationCenter.default
        observers.append(center.addObserver(forName: .octGroupConnectionStatusChange, object: nil, queue: .main) { [weak self] notification in
            guard let self = self else {
                return
            }
            if let chatId = notification.userInfo?[kOCTGroupConnectionStatusChangeChatUniqueIdentifierKey] as? String,
               let chat = self.liveChat(chatId) {
                self.submanagerGroups.refreshPeers(for: chat)
            }
            self.setNeedsRebuild()
        })
        observers.append(center.addObserver(forName: .octGroupPeersUpdated, object: nil, queue: .main) { [weak self] _ in
            self?.setNeedsRebuild()
        })
        observers.append(center.addObserver(forName: .airGroupInvitesDidChange, object: nil, queue: .main) { [weak self] _ in
            self?.setNeedsRebuild()
        })

        // "3 min ago" has to age, and group peers only report when asked.
        presenceTimer = Timer.scheduledTimer(withTimeInterval: 60, repeats: true) { [weak self] _ in
            guard let self = self, self.tableView.window != nil else {
                return
            }
            self.refreshGroupPeers()
            self.setNeedsRebuild()
        }
    }

    func handle<T: OCTObject>(_ change: ResultsChange<T>, source: String) {
        switch change {
            case .initial:
                break
            case .update:
                setNeedsRebuild()
            case .error(let error):
                NSLog("AirListDataSource \(source) update error: \(error)")
        }
    }

    func setNeedsRebuild() {
        guard !rebuildScheduled else {
            return
        }
        rebuildScheduled = true
        DispatchQueue.main.async { [weak self] in
            guard let self = self else {
                return
            }
            self.rebuildScheduled = false
            self.rebuild(animated: true)
        }
    }

    var shouldHoldUpdates: Bool {
        return tableView.window == nil || tableView.isEditing || openMenus > 0 || isSwipingRow
    }

    func rebuild(animated: Bool, force: Bool = false) {
        if hasLoaded && !force && shouldHoldUpdates {
            pendingRebuild = true
            return
        }
        pendingRebuild = false

        let (entries, airRequests) = makeEntries()
        let next = AirListBuilder.build(entries: entries,
                                        requests: airRequests,
                                        messageHits: messageHits,
                                        filter: filter,
                                        query: query)
        apply(next, animated: animated)
    }

    func makeEntries() -> ([AirEntry], [AirRequest]) {
        let now = Date().timeIntervalSince1970
        let favoriteKeys = UserDefaultsManager().favoriteChatKeys
        let linkSettling = SelfConnectionTracker.friendLinksMayStillBeConnecting

        var nextChats: [String: OCTChat] = [:]
        var nextFriends: [String: OCTFriend] = [:]
        var friendsWithChat = Set<String>()
        for index in 0..<chats.count {
            let chat = chats[index]
            nextChats[chat.uniqueIdentifier] = chat
            if !chat.isGroup, !chat.isSavedMessages, let friend = chat.friends.lastObject() as? OCTFriend {
                friendsWithChat.insert(friend.publicKey)
            }
        }

        let transit = transitCounts(chats: nextChats)
        var entries: [AirEntry] = []

        for index in 0..<chats.count {
            let chat = chats[index]
            let favorite = favoriteKeys.contains(ChatFavoritesStore.favoriteKey(for: chat))
            if chat.isSavedMessages {
                entries.append(savedEntry(for: chat, isFavorite: favorite))
            }
            else if chat.isGroup {
                entries.append(groupEntry(for: chat, transitCount: transit[chat.uniqueIdentifier] ?? 0, isFavorite: favorite))
            }
            else {
                let friend = chat.friends.lastObject() as? OCTFriend
                if let friend = friend {
                    nextFriends[friend.publicKey] = friend
                }
                entries.append(directEntry(for: chat,
                                           friend: friend,
                                           transitCount: transit[chat.uniqueIdentifier] ?? 0,
                                           isFavorite: favorite,
                                           now: now,
                                           linkSettling: linkSettling))
            }
        }

        // Contacts nobody has written to yet: the row starts the conversation.
        for index in 0..<friends.count {
            let friend = friends[index]
            nextFriends[friend.publicKey] = friend
            guard !friendsWithChat.contains(friend.publicKey) else {
                continue
            }
            let reach = AirReach.friend(connection: friend.connectionStatus,
                                        lastSeenOnlineInterval: friend.lastSeenOnlineInterval,
                                        now: now,
                                        selfLinkSettling: linkSettling)
            entries.append(AirEntry(id: friend.publicKey,
                                    kind: .contact,
                                    title: friend.nickname.isEmpty ? friend.publicKey : friend.nickname,
                                    reach: reach,
                                    preview: String(localized: reach == .pending ? "air_hint_request_sent" : "air_hint_no_messages"),
                                    previewStyle: .hint,
                                    lastDelivery: nil,
                                    timeText: "",
                                    activity: 0,
                                    transitCount: 0,
                                    unreadCount: 0,
                                    isFavorite: false,
                                    avatarData: friend.avatarData,
                                    tone: AirTone.index(for: friend.publicKey)))
        }

        var nextRequests: [String: OCTFriendRequest] = [:]
        var airRequests: [AirRequest] = []
        for index in 0..<requests.count {
            let request = requests[index]
            // A blocked key never shows; AirBlockGuard drops the request a moment later.
            guard !AirLocalStore.shared.isBlocked(publicKey: request.publicKey) else {
                continue
            }
            nextRequests[request.uniqueIdentifier] = request
            guard !declining.contains(request.uniqueIdentifier) else {
                continue
            }
            let date = Date(timeIntervalSince1970: request.dateInterval)
            airRequests.append(AirRequest(id: request.uniqueIdentifier,
                                          publicKey: request.publicKey,
                                          message: (request.message ?? "").trimmingCharacters(in: .whitespacesAndNewlines),
                                          timeText: ChatListContent.timeText(for: date, timeFormatter: timeFormatter, dateFormatter: dateFormatter),
                                          date: request.dateInterval))
        }

        // Invitations to groups wait as cards too, while the contact who sent them is still a contact.
        for invite in AirGroupInvites.shared.items where !declining.contains(invite.id) {
            guard let inviter = nextFriends[invite.inviterKey], !inviter.isInvalidated else {
                continue
            }
            let name = invite.groupName.isEmpty ? String(localized: "group_chat_default_title") : invite.groupName
            airRequests.append(AirRequest(id: invite.id,
                                          publicKey: invite.inviterKey,
                                          message: "",
                                          timeText: ChatListContent.timeText(for: invite.date, timeFormatter: timeFormatter, dateFormatter: dateFormatter),
                                          date: invite.date.timeIntervalSince1970,
                                          kind: .group(name: name,
                                                       inviter: inviter.nickname.isEmpty ? inviter.publicKey : inviter.nickname,
                                                       inviterOnline: inviter.connectionStatus != .none)))
        }

        chatsById = nextChats
        friendsByKey = nextFriends
        requestsById = nextRequests
        return (entries, airRequests)
    }

    func transitCounts(chats: [String: OCTChat]) -> [String: Int] {
        var counts: [String: Int] = [:]
        for index in 0..<transitMessages.count {
            let message = transitMessages[index]
            guard let chat = chats[message.chatUniqueIdentifier], !chat.isSavedMessages else {
                continue
            }
            // A group's queue is groupPendingSend alone (see transitPredicate).
            if chat.isGroup && !message.groupPendingSend {
                continue
            }
            counts[chat.uniqueIdentifier, default: 0] += 1
        }
        return counts
    }

    func directEntry(for chat: OCTChat,
                     friend: OCTFriend?,
                     transitCount: Int,
                     isFavorite: Bool,
                     now: TimeInterval,
                     linkSettling: Bool) -> AirEntry {
        let reach: AirReach
        let title: String
        if let friend = friend {
            reach = AirReach.friend(connection: friend.connectionStatus,
                                    lastSeenOnlineInterval: friend.lastSeenOnlineInterval,
                                    now: now,
                                    selfLinkSettling: linkSettling)
            title = friend.nickname.isEmpty ? friend.publicKey : friend.nickname
        }
        else {
            // The contact was removed and only the conversation is left: nobody to reach.
            reach = .away(secondsAgo: .greatestFiniteMagnitude)
            title = String(localized: "contact_deleted")
        }

        var (preview, style) = previewLine(for: chat, friend: friend)
        if preview.isEmpty && reach == .pending {
            preview = String(localized: "air_hint_request_sent")
            style = .hint
        }

        return AirEntry(id: chat.uniqueIdentifier,
                        kind: .direct,
                        title: title,
                        reach: reach,
                        preview: preview,
                        previewStyle: style,
                        lastDelivery: delivery(of: chat.lastMessage, isGroup: false, personOnline: reach.isLive),
                        timeText: timeText(for: chat),
                        activity: chat.lastActivityDateInterval,
                        transitCount: reach.messagesInTransit(undelivered: transitCount),
                        unreadCount: ChatListContent.unreadCount(for: chat, submanagerGroups: submanagerGroups, submanagerObjects: submanagerObjects),
                        isFavorite: isFavorite,
                        avatarData: friend?.avatarData,
                        tone: AirTone.index(for: friend?.publicKey ?? chat.uniqueIdentifier))
    }

    func groupEntry(for chat: OCTChat, transitCount: Int, isFavorite: Bool) -> AirEntry {
        let online = submanagerGroups.isGroupConnected(for: chat) ? Int(submanagerGroups.onlineGroupPeerCount(for: chat)) : 0
        let (preview, style) = previewLine(for: chat, friend: nil)
        return AirEntry(id: chat.uniqueIdentifier,
                        kind: .group,
                        title: ChatListContent.groupTitle(for: chat),
                        reach: .group(online: max(0, online)),
                        preview: preview,
                        previewStyle: style,
                        lastDelivery: delivery(of: chat.lastMessage, isGroup: true, personOnline: false),
                        timeText: timeText(for: chat),
                        activity: chat.lastActivityDateInterval,
                        transitCount: transitCount,
                        unreadCount: ChatListContent.unreadCount(for: chat, submanagerGroups: submanagerGroups, submanagerObjects: submanagerObjects),
                        isFavorite: isFavorite,
                        avatarData: nil,
                        tone: AirTone.index(for: chat.uniqueIdentifier))
    }

    func savedEntry(for chat: OCTChat, isFavorite: Bool) -> AirEntry {
        var (preview, style) = previewLine(for: chat, friend: nil)
        if preview.isEmpty {
            preview = String(localized: "air_hint_saved")
            style = .hint
        }
        return AirEntry(id: chat.uniqueIdentifier,
                        kind: .saved,
                        title: String(localized: "saved_messages_title"),
                        reach: .local,
                        preview: preview,
                        previewStyle: style,
                        lastDelivery: nil,
                        timeText: timeText(for: chat),
                        activity: chat.lastActivityDateInterval,
                        transitCount: 0,
                        unreadCount: 0,
                        isFavorite: isFavorite,
                        avatarData: nil,
                        tone: 0)
    }

    func previewLine(for chat: OCTChat, friend: OCTFriend?) -> (String, AirEntry.PreviewStyle) {
        let preview = ChatListContent.preview(in: chat, friend: friend)
        let text = preview.text.replacingOccurrences(of: "\n", with: " ")
        switch preview.kind {
            case .message:
                return (text, .message)
            case .draft:
                return (text, .draft)
            case .typing:
                return (text, .typing)
        }
    }

    func timeText(for chat: OCTChat) -> String {
        guard chat.lastActivityDateInterval > 0, let date = chat.lastActivityDate() else {
            return ""
        }
        return ChatListContent.timeText(for: date, timeFormatter: timeFormatter, dateFormatter: dateFormatter)
    }

    /// The mark in front of our own last message: on its way, sent, delivered. The same rules as the
    /// marks in the conversation; `personOnline` matters only for a 1:1 text.
    func delivery(of message: OCTMessageAbstract?, isGroup: Bool, personOnline: Bool) -> AirDelivery? {
        guard let message = message, message.isOutgoing() else {
            return nil
        }

        if isGroup {
            if message.groupPendingSend {
                return .transit
            }
            if let text = message.messageText {
                return text.isDelivered || text.groupSyncConfirmations > 0 ? .delivered : .sent
            }
            if let file = message.messageFile {
                return file.isDelivered || file.groupSyncConfirmations > 0 ? .delivered : .sent
            }
            return nil
        }

        if let text = message.messageText {
            return AirDelivery.ofOutgoingText(isDelivered: text.isDelivered, messageId: text.messageId, personOnline: personOnline)
        }
        if let file = message.messageFile {
            switch file.fileType {
                case .waitingConfirmation where file.internalFileNumber == Int32(bitPattern: kOCTToxFileNumberFailure):
                    return .transit
                case .ready:
                    return .delivered
                case .canceled:
                    return nil
                default:
                    return .sent
            }
        }
        return nil
    }

    func findMessages(matching query: String) -> [AirMessageHit] {
        let outcome = GlobalChatSearch.run(query: query, submanagerObjects: submanagerObjects)
        var seen = Set<String>()
        var hits: [AirMessageHit] = []
        for (index, hit) in outcome.messages.enumerated() {
            var id = hit.messageId.isEmpty ? "\(hit.chat.uniqueIdentifier ?? "")#\(index)" : hit.messageId
            if seen.contains(id) {
                id += "#\(index)"
            }
            seen.insert(id)
            hits.append(AirMessageHit(id: id,
                                      chatId: hit.chat.uniqueIdentifier,
                                      title: hit.title,
                                      snippet: hit.snippet,
                                      timeText: ChatListContent.timeText(for: hit.date, timeFormatter: timeFormatter, dateFormatter: dateFormatter)))
        }
        return hits
    }
}

// MARK: - Applying

private extension AirListDataSource {
    func apply(_ next: AirSnapshot, animated: Bool) {
        let previous = snapshot
        let wasLoaded = hasLoaded
        hasLoaded = true

        guard wasLoaded, animated, tableView.window != nil, !tableView.isEditing else {
            snapshot = next
            tableView.reloadData()
            delegate?.airListDidUpdate(self)
            return
        }

        if UIAccessibilityIsReduceMotionEnabled() {
            snapshot = next
            UIView.transition(with: tableView,
                              duration: KQMotion.duration(KQMotion.state),
                              options: [.transitionCrossDissolve, .allowUserInteraction],
                              animations: { self.tableView.reloadData() },
                              completion: nil)
            delegate?.airListDidUpdate(self)
            return
        }

        guard let changes = AirListDiff.changes(from: previous, to: next) else {
            snapshot = next
            tableView.reloadData()
            delegate?.airListDidUpdate(self)
            return
        }

        snapshot = next
        if !changes.isEmpty {
            tableView.performBatchUpdates({
                tableView.deleteSections(changes.deletedSections, with: .fade)
                tableView.insertSections(changes.insertedSections, with: .fade)
                tableView.deleteRows(at: changes.deletedRows, with: .fade)
                tableView.insertRows(at: changes.insertedRows, with: .fade)
                for move in changes.movedRows {
                    tableView.moveRow(at: move.from, to: move.to)
                }
            }, completion: nil)
        }
        refreshVisibleContent()
        delegate?.airListDidUpdate(self)
    }

    /// Rows that kept or changed their place still show the newest text, marks and counts.
    func refreshVisibleContent() {
        for indexPath in tableView.indexPathsForVisibleRows ?? [] {
            guard let cell = tableView.cellForRow(at: indexPath), let item = snapshot.item(at: indexPath) else {
                continue
            }
            configure(cell, with: item)
        }
        for (index, section) in snapshot.sections.enumerated() where section.kind.hasHeading {
            if let header = tableView.headerView(forSection: index) as? AirSectionHeaderView {
                header.configure(kind: section.kind, title: AirListDataSource.title(of: section.kind), count: section.items.count, animated: true)
            }
        }
    }

    func hideRowsForReveal() {
        guard !UIAccessibilityIsReduceMotionEnabled(), snapshot.itemCount > 0 else {
            return
        }
        tableView.layoutIfNeeded()
        for cell in tableView.visibleCells {
            cell.alpha = 0
            cell.transform = CGAffineTransform(translationX: 0, y: KQSpace.x8)
        }
    }

    /// Only the first time the list appears: rows come in one after another, then never again.
    func revealRows() {
        let cells = tableView.visibleCells.sorted { $0.frame.minY < $1.frame.minY }
        guard !UIAccessibilityIsReduceMotionEnabled() else {
            cells.forEach { $0.alpha = 1; $0.transform = .identity }
            return
        }
        for (index, cell) in cells.enumerated() {
            let animator = UIViewPropertyAnimator(duration: KQMotion.state, timingParameters: KQMotion.easeOut)
            animator.addAnimations {
                cell.alpha = 1
                cell.transform = .identity
            }
            // Past the sixth row everything arrives together, so a long list does not trickle in.
            animator.startAnimation(afterDelay: Double(min(index, 5)) * KQMotion.stagger)
        }
    }

    func configure(_ cell: UITableViewCell, with item: AirItem) {
        switch item {
            case .request(let request):
                guard let cell = cell as? AirRequestCell else {
                    return
                }
                cell.configure(with: request)
                cell.onAccept = { [weak self] in
                    self?.accept(request)
                }
                cell.onDecline = { [weak self] in
                    self?.decline(request)
                }
            case .entry(let entry):
                (cell as? AirRowCell)?.configure(with: entry, photo: photo(for: entry.id, data: entry.avatarData))
            case .message(let hit):
                guard let cell = cell as? AirMessageCell else {
                    return
                }
                let tile = tileStyle(forChatId: hit.chatId, title: hit.title)
                cell.configure(with: hit, query: query, tile: tile.style, photo: tile.photo)
        }
    }

    func tileStyle(forChatId chatId: String, title: String) -> (style: AirTileView.Style, photo: UIImage?) {
        guard let chat = liveChat(chatId) else {
            return (.person(initials: AirInitials.from(title), tone: AirTone.index(for: chatId)), nil)
        }
        if chat.isSavedMessages {
            return (.saved, nil)
        }
        if chat.isGroup {
            return (.group(initials: AirInitials.from(title), tone: AirTone.index(for: chat.uniqueIdentifier)), nil)
        }
        let friend = chat.friends.lastObject() as? OCTFriend
        let key: String = friend?.publicKey ?? chat.uniqueIdentifier ?? ""
        return (.person(initials: AirInitials.from(title), tone: AirTone.index(for: key)), photo(for: chat.uniqueIdentifier, data: friend?.avatarData))
    }

    /// Decoded photos, kept while the bytes stay the same.
    func photo(for id: String, data: Data?) -> UIImage? {
        guard let data = data else {
            photos[id] = nil
            return nil
        }
        if let cached = photos[id], cached.data == data {
            return cached.image
        }
        guard let image = UIImage(data: data) else {
            photos[id] = nil
            return nil
        }
        photos[id] = (data, image)
        return image
    }

    static func title(of kind: AirSectionKind) -> String {
        switch kind {
            case .requests:
                return String(localized: "air_request_tag")
            case .live:
                return String(localized: "air_section_live")
            case .away:
                return String(localized: "air_section_away")
            case .saved:
                return String(localized: "air_section_saved")
            case .messages:
                return String(localized: "air_section_messages")
        }
    }
}

// MARK: - Actions

private extension AirListDataSource {
    func accept(_ card: AirRequest) {
        if case .group = card.kind {
            joinGroup(inviteId: card.id)
            return
        }
        guard let request = liveRequest(card.id) else {
            return
        }
        do {
            try submanagerFriends.approve(request)
        }
        catch let error as NSError {
            handleErrorWithType(.toxAddFriend, error: error)
        }
    }

    /// toxcore joins through the contact who sent the invitation: it fails while they are offline,
    /// and then the card stays for another try.
    func joinGroup(inviteId: String) {
        guard let invite = AirGroupInvites.shared.item(id: inviteId) else {
            return
        }
        guard let inviter = liveFriend(invite.inviterKey) else {
            AirGroupInvites.shared.remove(id: inviteId)
            return
        }
        do {
            let chat = try submanagerGroups.acceptGroupInvite(fromFriendNumber: inviter.friendNumber,
                                                              invite: invite.data,
                                                              groupName: invite.groupName.isEmpty ? nil : invite.groupName,
                                                              password: nil)
            AirGroupInvites.shared.remove(id: inviteId)
            DispatchQueue.main.async { [weak self] in
                guard let self = self, !chat.isInvalidated else {
                    return
                }
                self.delegate?.airList(self, didSelectChat: chat)
            }
        }
        catch let error as NSError {
            handleErrorWithType(.acceptGroupInvite, error: error)
        }
    }

    /// No question first (E12): the card leaves at once and a toast offers "Undo" for five seconds.
    /// The request or invitation is removed only when the toast goes without it.
    func decline(_ card: AirRequest) {
        let isInvite: Bool
        if case .group = card.kind {
            isInvite = true
        }
        else {
            isInvite = false
            guard liveRequest(card.id) != nil else {
                return
            }
        }
        let id = card.id
        declining.insert(id)
        rebuild(animated: true, force: true)
        let host: UIView = tableView.superview ?? tableView
        AirToast.show(String(localized: isInvite ? "air_invite_declined" : "air_request_declined"),
                      actionTitle: String(localized: "air_undo"),
                      seconds: 5,
                      in: host,
                      action: { [weak self] in
                          guard let self = self else {
                              return
                          }
                          self.declining.remove(id)
                          self.rebuild(animated: true, force: true)
                      },
                      expired: { [weak self] in
                          self?.finishDecline(id: id, isInvite: isInvite)
                      })
    }

    func finishDecline(id: String, isInvite: Bool) {
        declining.remove(id)
        if isInvite {
            AirGroupInvites.shared.remove(id: id)
        }
        else if let request = liveRequest(id) {
            submanagerFriends.remove(request)
        }
        setNeedsRebuild()
    }

    func open(_ entry: AirEntry) {
        if let chat = liveChat(entry.id) {
            delegate?.airList(self, didSelectChat: chat)
        }
        else if entry.kind == .contact,
                let friend = liveFriend(entry.id),
                let chat = submanagerChats.getOrCreateChat(with: friend) {
            delegate?.airList(self, didSelectChat: chat)
        }
    }

    func open(_ hit: AirMessageHit) {
        let chat = liveChat(hit.chatId)
            ?? submanagerObjects.object(withUniqueIdentifier: hit.chatId, for: .chat) as? OCTChat
        guard let found = chat, !found.isInvalidated else {
            return
        }
        if !hit.id.contains("#") {
            // KHANDAQ (#64): jump to the matched message once the chat opens.
            GlobalSearchScrollTarget.pending = (found.uniqueIdentifier, hit.id)
        }
        delegate?.airList(self, didSelectChat: found)
    }

    func friend(of entry: AirEntry) -> OCTFriend? {
        if entry.kind == .contact {
            return liveFriend(entry.id)
        }
        guard entry.kind == .direct, let chat = liveChat(entry.id) else {
            return nil
        }
        return chat.friends.lastObject() as? OCTFriend
    }

    func toggleFavorite(_ chat: OCTChat) {
        guard !chat.isInvalidated else {
            return
        }
        ChatFavoritesStore.toggle(chat: chat)
        setNeedsRebuild()
    }

    func confirmDeleteChat(_ chat: OCTChat) {
        guard !chat.isInvalidated else {
            return
        }
        if chat.isGroup {
            delegate?.airList(self, present: ChatListContent.groupDeleteAlert(for: chat, submanagerGroups: submanagerGroups))
            return
        }
        // E21: a sheet from below, what happens in one sentence, the verb on the button.
        let sheet = AirConfirm.sheet(title: String(localized: "air_delete_chat_title"),
                                     message: String(localized: "air_delete_chat_message"),
                                     verb: String(localized: "alert_delete"),
                                     source: nil) { [weak self] in
            guard !chat.isInvalidated else {
                return
            }
            self?.submanagerChats.removeAllMessages(in: chat, removeChat: true)
        }
        delegate?.airList(self, present: sheet)
    }

    /// The contact goes, and with them the conversation (as on the old contacts screen).
    func confirmRemoveContact(_ friend: OCTFriend) {
        guard !friend.isInvalidated else {
            return
        }
        // The same words as "Remove" in the dossier (E21).
        let name = friend.nickname.isEmpty ? friend.publicKey : friend.nickname
        let sheet = AirConfirm.sheet(title: String(localized: "air_dossier_remove_title_format", name),
                                     message: String(localized: "air_dossier_remove_message"),
                                     verb: String(localized: "alert_delete"),
                                     source: nil) { [weak self] in
            guard let self = self else {
                return
            }
            AirContactRemoval.remove(friend, submanagerChats: self.submanagerChats, submanagerFriends: self.submanagerFriends)
        }
        delegate?.airList(self, present: sheet)
    }
}

// MARK: - UITableViewDataSource

extension AirListDataSource: UITableViewDataSource {
    func numberOfSections(in tableView: UITableView) -> Int {
        return snapshot.sections.count
    }

    func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        guard section < snapshot.sections.count else {
            return 0
        }
        return snapshot.sections[section].items.count
    }

    func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        guard let item = snapshot.item(at: indexPath) else {
            return tableView.dequeueReusableCell(withIdentifier: AirRowCell.reuseIdentifier, for: indexPath)
        }

        let identifier: String
        switch item {
            case .request:
                identifier = AirRequestCell.reuseIdentifier
            case .entry:
                identifier = AirRowCell.reuseIdentifier
            case .message:
                identifier = AirMessageCell.reuseIdentifier
        }
        let cell = tableView.dequeueReusableCell(withIdentifier: identifier, for: indexPath)
        // A cell taken back mid-reveal starts from rest.
        cell.alpha = 1
        cell.transform = .identity
        configure(cell, with: item)
        return cell
    }

    func tableView(_ tableView: UITableView, canEditRowAt indexPath: IndexPath) -> Bool {
        guard let entry = snapshot.entry(at: indexPath) else {
            return false
        }
        return liveChat(entry.id) != nil || entry.kind == .contact
    }
}

// MARK: - UITableViewDelegate

extension AirListDataSource: UITableViewDelegate {
    func tableView(_ tableView: UITableView, viewForHeaderInSection section: Int) -> UIView? {
        guard section < snapshot.sections.count else {
            return nil
        }
        let block = snapshot.sections[section]
        guard block.kind.hasHeading,
              let header = tableView.dequeueReusableHeaderFooterView(withIdentifier: AirSectionHeaderView.reuseIdentifier) as? AirSectionHeaderView else {
            return nil
        }
        header.configure(kind: block.kind, title: AirListDataSource.title(of: block.kind), count: block.items.count, animated: false)
        return header
    }

    func tableView(_ tableView: UITableView, heightForHeaderInSection section: Int) -> CGFloat {
        guard section < snapshot.sections.count, snapshot.sections[section].kind.hasHeading else {
            return CGFloat.leastNormalMagnitude
        }
        return UITableViewAutomaticDimension
    }

    func tableView(_ tableView: UITableView, heightForFooterInSection section: Int) -> CGFloat {
        return CGFloat.leastNormalMagnitude
    }

    func tableView(_ tableView: UITableView, viewForFooterInSection section: Int) -> UIView? {
        return nil
    }

    func tableView(_ tableView: UITableView, shouldHighlightRowAt indexPath: IndexPath) -> Bool {
        if case .request? = snapshot.item(at: indexPath) {
            return false
        }
        return !tableView.isEditing || canSelect(at: indexPath)
    }

    func tableView(_ tableView: UITableView, willSelectRowAt indexPath: IndexPath) -> IndexPath? {
        if tableView.isEditing {
            return canSelect(at: indexPath) ? indexPath : nil
        }
        if case .request? = snapshot.item(at: indexPath) {
            return nil
        }
        return indexPath
    }

    func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        if tableView.isEditing {
            delegate?.airListSelectionDidChange(self)
            return
        }
        tableView.deselectRow(at: indexPath, animated: true)

        switch snapshot.item(at: indexPath) {
            case .entry(let entry)?:
                open(entry)
            case .message(let hit)?:
                open(hit)
            case .request?, nil:
                break
        }
    }

    func tableView(_ tableView: UITableView, didDeselectRowAt indexPath: IndexPath) {
        if tableView.isEditing {
            delegate?.airListSelectionDidChange(self)
        }
    }

    func tableView(_ tableView: UITableView, willBeginEditingRowAt indexPath: IndexPath) {
        isSwipingRow = true
    }

    func tableView(_ tableView: UITableView, didEndEditingRowAt indexPath: IndexPath?) {
        isSwipingRow = false
        if pendingRebuild {
            setNeedsRebuild()
        }
    }

    func tableView(_ tableView: UITableView, trailingSwipeActionsConfigurationForRowAt indexPath: IndexPath) -> UISwipeActionsConfiguration? {
        guard let entry = snapshot.entry(at: indexPath) else {
            return nil
        }

        var actions: [UIContextualAction] = []

        if let chat = liveChat(entry.id) {
            let delete = UIContextualAction(style: .destructive, title: String(localized: "alert_delete")) { [weak self] _, _, completion in
                self?.confirmDeleteChat(chat)
                completion(true)
            }
            delete.image = AirIcon.image("trash")
            actions.append(delete)

            if chat.isGroup {
                let info = UIContextualAction(style: .normal, title: String(localized: "group_info_button")) { [weak self] _, _, completion in
                    guard let self = self else {
                        completion(false)
                        return
                    }
                    self.delegate?.airList(self, didRequestGroupInfo: chat)
                    completion(true)
                }
                info.image = AirIcon.image("users")
                info.backgroundColor = KQColor.ink2
                actions.append(info)
            }

            if !chat.isSavedMessages {
                let title = entry.isFavorite ? String(localized: "chat_filter_remove_favorite") : String(localized: "chat_filter_add_favorite")
                let favorite = UIContextualAction(style: .normal, title: title) { [weak self] _, _, completion in
                    self?.toggleFavorite(chat)
                    completion(true)
                }
                favorite.image = AirIcon.image("bookmark")
                favorite.backgroundColor = KQColor.ink
                actions.append(favorite)
            }
        }
        else if let friend = friend(of: entry) {
            let remove = UIContextualAction(style: .destructive, title: String(localized: "air_menu_remove_contact")) { [weak self] _, _, completion in
                self?.confirmRemoveContact(friend)
                completion(true)
            }
            remove.image = AirIcon.image("trash")
            actions.append(remove)
        }

        guard !actions.isEmpty else {
            return nil
        }
        let configuration = UISwipeActionsConfiguration(actions: actions)
        // Irreversible actions are confirmed, and a full swipe never fires one by accident.
        configuration.performsFirstActionWithFullSwipe = false
        return configuration
    }

    func tableView(_ tableView: UITableView, contextMenuConfigurationForRowAt indexPath: IndexPath, point: CGPoint) -> UIContextMenuConfiguration? {
        guard !tableView.isEditing, let entry = snapshot.entry(at: indexPath) else {
            return nil
        }
        let chat = liveChat(entry.id)
        let friend = self.friend(of: entry)
        guard chat != nil || friend != nil else {
            return nil
        }

        var previewProvider: UIContextMenuContentPreviewProvider?
        if let chat = chat {
            previewProvider = { [weak self] in
                guard let self = self, !chat.isInvalidated else {
                    return nil
                }
                return ChatListContent.previewController(for: chat, theme: self.theme, avatarManager: self.avatarManager, submanagerObjects: self.submanagerObjects)
            }
        }

        return UIContextMenuConfiguration(identifier: entry.id as NSString, previewProvider: previewProvider) { [weak self] _ in
            guard let self = self else {
                return nil
            }
            var children: [UIMenuElement] = []

            if let chat = chat {
                if !chat.isSavedMessages {
                    let title = entry.isFavorite ? String(localized: "chat_filter_remove_favorite") : String(localized: "chat_filter_add_favorite")
                    children.append(UIAction(title: title, image: AirIcon.image("bookmark")) { [weak self] _ in
                        self?.toggleFavorite(chat)
                    })
                }
                if chat.isGroup {
                    children.append(UIAction(title: String(localized: "group_info_button"), image: AirIcon.image("users")) { [weak self] _ in
                        guard let self = self else {
                            return
                        }
                        self.delegate?.airList(self, didRequestGroupInfo: chat)
                    })
                }
                children.append(UIAction(title: String(localized: "air_menu_select"), image: AirIcon.image("check")) { [weak self] _ in
                    guard let self = self, let path = self.indexPath(ofEntryId: entry.id) else {
                        return
                    }
                    self.delegate?.airList(self, didRequestSelectionStartingAt: path)
                })
            }

            if let friend = friend {
                children.append(UIAction(title: String(localized: "air_menu_remove_contact"),
                                         image: AirIcon.image("trash"),
                                         attributes: .destructive) { [weak self] _ in
                    self?.confirmRemoveContact(friend)
                })
            }
            return UIMenu(children: children)
        }
    }

    func tableView(_ tableView: UITableView, willPerformPreviewActionForMenuWith configuration: UIContextMenuConfiguration, animator: UIContextMenuInteractionCommitAnimating) {
        guard let id = configuration.identifier as? String else {
            return
        }
        // Tapping the preview opens the real conversation.
        animator.addCompletion { [weak self] in
            guard let self = self, let chat = self.liveChat(id) else {
                return
            }
            self.delegate?.airList(self, didSelectChat: chat)
        }
    }

    func tableView(_ tableView: UITableView, willDisplayContextMenu configuration: UIContextMenuConfiguration, animator: UIContextMenuInteractionAnimating?) {
        openMenus += 1
    }

    func tableView(_ tableView: UITableView, willEndContextMenuInteraction configuration: UIContextMenuConfiguration, animator: UIContextMenuInteractionAnimating?) {
        openMenus = max(0, openMenus - 1)
        let resume = { [weak self] in
            guard let self = self, self.pendingRebuild else {
                return
            }
            self.setNeedsRebuild()
        }
        if let animator = animator {
            animator.addCompletion(resume)
        }
        else {
            resume()
        }
    }

    private func indexPath(ofEntryId id: String) -> IndexPath? {
        for (sectionIndex, section) in snapshot.sections.enumerated() {
            for (row, item) in section.items.enumerated() where item.id == "entry:" + id {
                return IndexPath(row: row, section: sectionIndex)
            }
        }
        return nil
    }
}
