// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation
import UIKit

protocol ChatListControllerDelegate: class {
    func chatListController(_ controller: ChatListController, didSelectChat chat: OCTChat)
    func chatListController(_ controller: ChatListController, didRequestGroupInfo chat: OCTChat)
    func chatListControllerDidRequestCreateGroup(_ controller: ChatListController)
    func chatListControllerDidRequestCreatePrivateGroup(_ controller: ChatListController)
    func chatListControllerDidRequestJoinGroup(_ controller: ChatListController)
    func chatListControllerDidRequestScanGroupQR(_ controller: ChatListController)
}

class ChatListController: UIViewController {
    weak var delegate: ChatListControllerDelegate?

    fileprivate let theme: Theme
    fileprivate weak var submanagerChats: OCTSubmanagerChats!
    fileprivate weak var submanagerGroups: OCTSubmanagerGroups!
    fileprivate weak var submanagerObjects: OCTSubmanagerObjects!

    fileprivate var placeholderLabel: UILabel!
    fileprivate var tableManager: ChatListTableManager!
    fileprivate var filterBar: ChatListFilterBar!
    // KHANDAQ (#34): Telegram-style global search across every chat/group + all message text.
    fileprivate var searchController: UISearchController!
    fileprivate var searchResultsVC: GlobalSearchResultsController!
    // KHANDAQ (Figma): multi-select edit mode — Отмена + red Удалить replace the nav buttons.
    fileprivate var defaultRightBarItems: [UIBarButtonItem] = []
    fileprivate var cancelEditButton: UIBarButtonItem!
    fileprivate var deleteSelectedButton: UIBarButtonItem!

    init(theme: Theme, submanagerChats: OCTSubmanagerChats, submanagerGroups: OCTSubmanagerGroups, submanagerObjects: OCTSubmanagerObjects) {
        self.theme = theme
        self.submanagerChats = submanagerChats
        self.submanagerGroups = submanagerGroups
        self.submanagerObjects = submanagerObjects

        super.init(nibName: nil, bundle: nil)

        edgesForExtendedLayout = UIRectEdge()
        title = String(localized: "chats_title")
    }

    required convenience init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func loadView() {
        loadViewWithBackgroundColor(theme.colorForType(.NormalBackground))

        createTableView()
        createFilterBar()
        createPlaceholderView()
        installConstraints()

        updateViewsVisibility()
        refreshFilterBadges()

        // KHANDAQ (#iOS nav polish): clean, native-style nav bar — no heavy grey circles/pills that
        // clutter the header. Just tinted glyphs (Apple/Telegram look): «+» in the accent colour (an
        // action), the theme toggle in a subdued neutral (it's a switch, not an action), «Править» as
        // plain accent text.
        let addButton = UIBarButtonItem(
                image: navBarSymbol("plus"),
                style: .plain,
                target: self,
                action: #selector(ChatListController.addButtonPressed))
        addButton.tintColor = theme.colorForType(.LinkText)
        addButton.accessibilityLabel = String(localized: "group_add_button")

        // KHANDAQ (#iOS nav polish): the light/dark theme toggle was removed from the header — theme
        // switching already lives in Settings, so the header stays clean with just «+» (and «Править»).
        defaultRightBarItems = [addButton]
        navigationItem.rightBarButtonItems = defaultRightBarItems

        // «Править»: no grey pill — plain accent text.
        editButtonItem.tintColor = theme.colorForType(.LinkText)
        // KHANDAQ (iPhone 11 Pro report 17.08): the system editButtonItem takes its title from UIKit's
        // own bundle, resolved from the languages the PROCESS launched with — swapping Bundle.main
        // cannot reach it, so it stayed Russian while the rest of the screen turned Arabic. Give it
        // our own title; setEditing keeps it in sync with the Edit/Done state.
        editButtonItem.title = String(localized: "nav_edit_start")

        // KHANDAQ (Figma): edit-mode nav — Отмена (left) + red Удалить (right, enabled on selection).
        cancelEditButton = UIBarButtonItem(
            title: String(localized: "alert_cancel"),
            style: .plain,
            target: self,
            action: #selector(ChatListController.cancelEditingPressed))
        cancelEditButton.tintColor = theme.colorForType(.LinkText)

        deleteSelectedButton = UIBarButtonItem(
            title: String(localized: "alert_delete"),
            style: .done,
            target: self,
            action: #selector(ChatListController.deleteSelectedPressed))
        deleteSelectedButton.tintColor = .systemRed

        setupGlobalSearch()
    }

    func setupGlobalSearch() {
        searchResultsVC = GlobalSearchResultsController(theme: theme)
        searchResultsVC.onSelectChat = { [weak self] chat in
            guard let self = self else {
                return
            }
            self.searchController.isActive = false
            self.delegate?.chatListController(self, didSelectChat: chat)
        }

        searchController = UISearchController(searchResultsController: searchResultsVC)
        searchController.searchResultsUpdater = self
        searchController.obscuresBackgroundDuringPresentation = true
        searchController.searchBar.placeholder = String(localized: "global_search_placeholder")
        searchController.searchBar.autocapitalizationType = .none
        // KHANDAQ (#57): the default prominent search bar rendered a stray opaque box in dark mode.
        // `.minimal` + theming the text field gives a clean full-width field on both themes.
        searchController.searchBar.searchBarStyle = .minimal
        searchController.searchBar.barStyle = ThemeAppearance.isDarkMode ? .black : .default
        searchController.searchBar.tintColor = theme.colorForType(.LinkText)
        if #available(iOS 13.0, *) {
            // KHANDAQ (#57 follow-up): only theme the text color. Painting `field.backgroundColor`
            // under the `.minimal` style draws an opaque rectangle BEHIND the field's own rounded
            // background view; on first appearance (before that subview is laid out) it renders as a
            // torn pill in dark mode and only self-corrects after a theme toggle forces a relayout.
            // The window's overrideUserInterfaceStyle already makes the system field background match
            // the active theme, so the default rounded pill is correct in both modes without a race.
            searchController.searchBar.searchTextField.textColor = theme.colorForType(.NormalText)
        }
        navigationItem.searchController = searchController
        navigationItem.hidesSearchBarWhenScrolling = false
        definesPresentationContext = true
    }

    // KHANDAQ (#iOS nav polish): a clean SF Symbol glyph for a bare (frameless) nav bar button.
    func navBarSymbol(_ name: String) -> UIImage? {
        guard #available(iOS 13.0, *) else {
            return nil
        }
        let config = UIImage.SymbolConfiguration(pointSize: 18.0, weight: .regular)
        return UIImage(systemName: name, withConfiguration: config)
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        filterBar.setSelectedTab(tableManager.filterTab, animated: false)
        refreshFilterBadges()
        scheduleGroupPeersRefreshIfNeeded()
        // KHANDAQ: reload the list whenever it reappears. Realm list updates are deferred while a
        // modal is presented (shouldDeferListUpdates), so a group created via the name dialog could be
        // missing from the list afterwards until something else triggered a reload.
        tableManager.tableView.reloadData()

        // KHANDAQ (#94): force the search field to lay out before it appears. After a theme reload the
        // controller is freshly created; the `.minimal` field could otherwise render as a torn pill in
        // dark mode until a later relayout corrected it.
        if #available(iOS 13.0, *) {
            let field = searchController.searchBar.searchTextField
            field.setNeedsLayout()
            field.layoutIfNeeded()
        }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        view.backgroundColor = theme.colorForType(.NormalBackground)
        tableManager.tableView.backgroundColor = theme.colorForType(.NormalBackground)
        filterBar.applyTheme(theme)
        ThemeChrome.installZeroHeightTableFooter(in: tableManager.tableView, theme: theme)
    }

    override func setEditing(_ editing: Bool, animated: Bool) {
        super.setEditing(editing, animated: animated)

        tableManager.tableView.setEditing(editing, animated: animated)
        // Our own title, for the reason above — super would have reset it to UIKit's.
        editButtonItem.title = String(localized: editing ? "nav_edit_done" : "nav_edit_start")

        // KHANDAQ (Figma): multi-select edit mode — Отмена + red Удалить while editing.
        if editing {
            navigationItem.leftBarButtonItem = cancelEditButton
            navigationItem.rightBarButtonItems = [deleteSelectedButton]
            updateDeleteSelectedButton()
        }
        else {
            navigationItem.rightBarButtonItems = defaultRightBarItems
            updateViewsVisibility()
        }
    }

    @objc func cancelEditingPressed() {
        setEditing(false, animated: true)
    }

    @objc func deleteSelectedPressed() {
        let rows = (tableManager.tableView.indexPathsForSelectedRows ?? []).map { $0.row }

        guard !rows.isEmpty else {
            return
        }

        let alert = UIAlertController(title: String(localized: "delete_selected_chats_title", rows.count),
                                      message: nil,
                                      preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: String(localized: "alert_cancel"), style: .cancel, handler: nil))
        alert.addAction(UIAlertAction(title: String(localized: "alert_delete"), style: .destructive) { [weak self] _ in
            guard let self = self else {
                return
            }
            self.tableManager.deleteChats(atVisibleRows: rows)
            self.setEditing(false, animated: true)
        })
        present(alert, animated: true, completion: nil)
    }

    func updateDeleteSelectedButton() {
        deleteSelectedButton.isEnabled = (tableManager.tableView.indexPathsForSelectedRows?.count ?? 0) > 0
    }
}

extension ChatListController: ChatListTableManagerDelegate {
    func chatListTableManager(_ manager: ChatListTableManager, didSelectChat chat: OCTChat) {
        delegate?.chatListController(self, didSelectChat: chat)
    }

    func chatListTableManager(_ manager: ChatListTableManager, presentAlertController controller: UIAlertController) {
        present(controller, animated: true, completion: nil)
    }

    func chatListTableManagerWasUpdated(_ manager: ChatListTableManager) {
        updateViewsVisibility()
        refreshFilterBadges()
    }

    func chatListTableManager(_ manager: ChatListTableManager, didRequestGroupInfo chat: OCTChat) {
        delegate?.chatListController(self, didRequestGroupInfo: chat)
    }

    func chatListTableManagerSelectionDidChange(_ manager: ChatListTableManager) {
        updateDeleteSelectedButton()
    }
}

private extension ChatListController {
    func updateViewsVisibility() {
        navigationItem.leftBarButtonItem = tableManager.isEmpty ? nil : editButtonItem
        placeholderLabel.isHidden = !tableManager.isEmpty
    }

    func createTableView() {
        let tableView = UITableView()
        tableView.estimatedRowHeight = 44.0
        tableView.backgroundColor = theme.colorForType(.NormalBackground)
        tableView.sectionIndexColor = theme.colorForType(.LinkText)
        // KHANDAQ (Figma): multi-select edit mode with accent selection circles.
        tableView.allowsMultipleSelectionDuringEditing = true
        tableView.tintColor = theme.colorForType(.LinkText)
        ThemeChrome.installZeroHeightTableFooter(in: tableView, theme: theme)

        view.addSubview(tableView)

        tableView.register(ChatListCell.self, forCellReuseIdentifier: ChatListCell.staticReuseIdentifier)

        tableManager = ChatListTableManager(theme: theme, tableView: tableView, submanagerChats: submanagerChats, submanagerGroups: submanagerGroups, submanagerObjects: submanagerObjects)
        tableManager.delegate = self
    }

    func createFilterBar() {
        filterBar = ChatListFilterBar(theme: theme)
        filterBar.setSelectedTab(tableManager.filterTab, animated: false)
        filterBar.onTabSelected = { [weak self] tab in
            guard let self = self else {
                return
            }
            self.tableManager.setFilterTab(tab)
            self.refreshFilterBadges()
        }
        view.addSubview(filterBar)

        installFilterSwipeGestures()
    }

    /**
     KHANDAQ (owner request 17.08): switch Chats / Groups / Favorites by swiping the list.
     Reaching up to the tabs on a large phone is the complaint being fixed.

     Swipe recognisers rather than a pan: the rows already carry trailing swipe actions (delete /
     info / favourite), and those are driven by the table's own pan. A discrete swipe needs speed and
     direction, so a slow drag on a row still opens its actions while a quick flick changes tab.
     Simultaneous recognition is refused for the same reason — one gesture should do one thing.
     */
    private func installFilterSwipeGestures() {
        let left = UISwipeGestureRecognizer(target: self, action: #selector(handleFilterSwipe(_:)))
        left.direction = .left
        left.delegate = self
        left.cancelsTouchesInView = false

        let right = UISwipeGestureRecognizer(target: self, action: #selector(handleFilterSwipe(_:)))
        right.direction = .right
        right.delegate = self
        right.cancelsTouchesInView = false

        tableManager.tableView.addGestureRecognizer(left)
        tableManager.tableView.addGestureRecognizer(right)
    }

    @objc private func handleFilterSwipe(_ recognizer: UISwipeGestureRecognizer) {
        // A swipe while selecting chats would change the filter under the selection and leave the
        // edit-mode nav bar pointing at rows that are no longer there.
        guard !isEditing, !(searchController != nil && searchController.isActive) else {
            return
        }

        let isRTL = view.effectiveUserInterfaceLayoutDirection == .rightToLeft
        guard let tab = ChatListSwipeNavigation.nextTab(from: tableManager.filterTab,
                                                       swipingLeft: recognizer.direction == .left,
                                                       isRightToLeft: isRTL) else {
            return
        }

        filterBar.setSelectedTab(tab, animated: true)
        tableManager.setFilterTab(tab)
        refreshFilterBadges()
    }

    func refreshFilterBadges() {
        filterBar.updateBadges(tableManager.unreadCountsForFilterTabs())
    }

    @objc func addButtonPressed() {
        let alert = UIAlertController(title: nil, message: nil, preferredStyle: .actionSheet)
        alert.addAction(UIAlertAction(title: String(localized: "group_create_public"), style: .default) { [weak self] _ in
            self?.runAfterGroupMenuDismisses { controller in
                controller.delegate?.chatListControllerDidRequestCreateGroup(controller)
            }
        })
        alert.addAction(UIAlertAction(title: String(localized: "group_create_private"), style: .default) { [weak self] _ in
            self?.runAfterGroupMenuDismisses { controller in
                controller.delegate?.chatListControllerDidRequestCreatePrivateGroup(controller)
            }
        })
        // KHANDAQ: "join public group by chat-id" (and its QR variant, which just yields a chat-id)
        // is TEMPORARILY HIDDEN pending the NGC cold-chat-id discovery fix — cold join by id does not
        // connect yet (same as the Android build). Group CREATION stays (private groups work via
        // friend-invite). TO RE-ENABLE, restore the two addAction blocks below.
        // alert.addAction(UIAlertAction(title: String(localized: "group_join_by_id"), style: .default) { [weak self] _ in
        //     self?.runAfterGroupMenuDismisses { controller in
        //         controller.delegate?.chatListControllerDidRequestJoinGroup(controller)
        //     }
        // })
        // alert.addAction(UIAlertAction(title: String(localized: "group_scan_qr"), style: .default) { [weak self] _ in
        //     self?.runAfterGroupMenuDismisses { controller in
        //         controller.delegate?.chatListControllerDidRequestScanGroupQR(controller)
        //     }
        // })
        alert.addAction(UIAlertAction(title: String(localized: "alert_cancel"), style: .cancel, handler: nil))
        if let popover = alert.popoverPresentationController {
            popover.barButtonItem = navigationItem.rightBarButtonItem
        }
        present(alert, animated: true, completion: nil)
    }

    func createPlaceholderView() {
        placeholderLabel = UILabel()
        placeholderLabel.text = String(localized: "chat_no_chats")
        placeholderLabel.textColor = theme.colorForType(.EmptyScreenPlaceholderText)
        // KHANDAQ design (Figma): soft centered multi-line caption (~15pt), not a single big line.
        placeholderLabel.font = UIFont.systemFont(ofSize: 15.0)
        placeholderLabel.numberOfLines = 0
        placeholderLabel.textAlignment = .center
        placeholderLabel.backgroundColor = theme.colorForType(.NormalBackground)
        view.addSubview(placeholderLabel)
    }

    func installConstraints() {
        filterBar.snp.makeConstraints {
            $0.top.leading.trailing.equalTo(view.safeAreaLayoutGuide)
            $0.height.equalTo(44)
        }

        tableManager.tableView.snp.makeConstraints {
            $0.top.equalTo(filterBar.snp.bottom)
            $0.leading.trailing.bottom.equalTo(view)
        }

        placeholderLabel.snp.makeConstraints {
            $0.centerX.equalTo(view)
            $0.centerY.equalTo(view).multipliedBy(0.85)
            $0.leading.equalTo(view).offset(48.0)
            $0.trailing.equalTo(view).offset(-48.0)
        }
    }

    func scheduleGroupPeersRefreshIfNeeded() {
        guard presentedViewController == nil else {
            return
        }

        DispatchQueue.main.async { [weak self] in
            guard let self = self, self.presentedViewController == nil else {
                return
            }

            self.refreshGroupPeersInList()
        }
    }

    func refreshGroupPeersInList() {
        tableManager.refreshGroupPeerCounts()
        tableManager.tableView.reloadData()
    }

    func runAfterGroupMenuDismisses(_ block: @escaping (ChatListController) -> Void) {
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.45) { [weak self] in
            guard let self = self else {
                return
            }

            block(self)
        }
    }
}

private final class ChatListFilterBar: UIView {
    var onTabSelected: ((ChatListFilterTab) -> Void)?

    private let theme: Theme
    private var tabButtons: [ChatListFilterTab: UIButton] = [:]
    private var tabBadges: [ChatListFilterTab: UILabel] = [:]
    private var tabIndicators: [ChatListFilterTab: UIView] = [:]
    private var selectedTab: ChatListFilterTab = UserDefaultsManager().chatListFilterTab

    init(theme: Theme) {
        self.theme = theme
        super.init(frame: .zero)
        backgroundColor = theme.colorForType(.NormalBackground)
        setupTabs()
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func applyTheme(_ theme: Theme) {
        backgroundColor = theme.colorForType(.NormalBackground)
        setSelectedTab(selectedTab, animated: false)
    }

    func setSelectedTab(_ tab: ChatListFilterTab, animated: Bool) {
        selectedTab = tab
        for (filterTab, button) in tabButtons {
            let isSelected = filterTab == tab
            button.setTitleColor(isSelected ? theme.colorForType(.NormalText) : theme.colorForType(.EmptyScreenPlaceholderText), for: .normal)
            tabIndicators[filterTab]?.isHidden = !isSelected
        }
    }

    func updateBadges(_ counts: ChatListFilterUnreadCounts) {
        bindBadge(tabBadges[.direct], count: counts.direct)
        bindBadge(tabBadges[.groups], count: counts.groups)
        bindBadge(tabBadges[.favorites], count: counts.favorites)
    }

    private func setupTabs() {
        let stack = UIStackView()
        stack.axis = .horizontal
        stack.distribution = .fillEqually
        stack.alignment = .fill
        addSubview(stack)
        stack.snp.makeConstraints {
            $0.edges.equalToSuperview()
        }

        addTab(.direct, title: String(localized: "chats_title"), to: stack)
        addTab(.groups, title: String(localized: "chat_filter_groups"), to: stack)
        addTab(.favorites, title: String(localized: "chat_filter_favorites"), to: stack)
    }

    private func addTab(_ tab: ChatListFilterTab, title: String, to stack: UIStackView) {
        let container = UIView()
        stack.addArrangedSubview(container)

        let titleRow = UIStackView()
        titleRow.axis = .horizontal
        titleRow.alignment = .center
        titleRow.spacing = 4
        container.addSubview(titleRow)

        let button = UIButton(type: .system)
        button.setTitle(title, for: .normal)
        button.titleLabel?.font = UIFont.systemFont(ofSize: 15, weight: .medium)
        button.tag = tab.rawValue
        button.addTarget(self, action: #selector(tabPressed(_:)), for: .touchUpInside)
        titleRow.addArrangedSubview(button)

        let badge = UILabel()
        badge.font = UIFont.systemFont(ofSize: 11, weight: .semibold)
        badge.textColor = .white
        badge.backgroundColor = theme.colorForType(.LinkText)
        badge.textAlignment = .center
        badge.layer.cornerRadius = 9
        badge.clipsToBounds = true
        badge.isHidden = true
        badge.setContentHuggingPriority(.required, for: .horizontal)
        titleRow.addArrangedSubview(badge)

        let indicator = UIView()
        indicator.backgroundColor = theme.colorForType(.LinkText)
        container.addSubview(indicator)

        titleRow.snp.makeConstraints {
            $0.centerX.equalToSuperview()
            $0.top.equalToSuperview().offset(8)
        }

        badge.snp.makeConstraints {
            $0.height.equalTo(18)
            $0.width.greaterThanOrEqualTo(18)
        }

        indicator.snp.makeConstraints {
            $0.leading.trailing.bottom.equalToSuperview()
            $0.height.equalTo(2)
        }

        tabButtons[tab] = button
        tabBadges[tab] = badge
        tabIndicators[tab] = indicator
    }

    @objc private func tabPressed(_ sender: UIButton) {
        guard let tab = ChatListFilterTab(rawValue: sender.tag) else {
            return
        }
        setSelectedTab(tab, animated: true)
        onTabSelected?(tab)
    }

    private func bindBadge(_ badge: UILabel?, count: Int) {
        guard let badge = badge else {
            return
        }
        if count > 0 {
            badge.isHidden = false
            badge.text = count > 99 ? "99+" : "\(count)"
        }
        else {
            badge.isHidden = true
            badge.text = nil
        }
    }
}

// MARK: - KHANDAQ (#34) global search

// KHANDAQ (owner request 17.08): keep the tab-switch swipe from competing with the rows' own
// swipe actions — one gesture, one outcome.
extension ChatListController: UIGestureRecognizerDelegate {
    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer,
                           shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer) -> Bool {
        return false
    }

    func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        guard gestureRecognizer is UISwipeGestureRecognizer else {
            return true
        }
        // Not while selecting chats, not while searching, and not while a row already has its
        // actions open (the table is mid-swipe and owns the touch).
        if isEditing || (searchController != nil && searchController.isActive) {
            return false
        }
        return true
    }
}

extension ChatListController: UISearchResultsUpdating {
    func updateSearchResults(for searchController: UISearchController) {
        let query = searchController.searchBar.text ?? ""
        let outcome = GlobalChatSearch.run(query: query, submanagerObjects: submanagerObjects)
        searchResultsVC.apply(outcome: outcome, query: query)
    }
}

/// KHANDAQ (#64): single-shot target so tapping a global-search MESSAGE result scrolls to that
/// message once the chat opens — without threading a parameter through the coordinators. The chat
/// controller reads + clears it on appear.
enum GlobalSearchScrollTarget {
    static var pending: (chatId: String, messageId: String)?

    /// Returns and clears the target if it belongs to `chatId`.
    static func take(forChatId chatId: String) -> String? {
        guard let p = pending, p.chatId == chatId else {
            return nil
        }
        pending = nil
        return p.messageId
    }
}

/// Telegram-style global search: chats/groups by name + every message by text/file name. Pure logic,
/// kept in this file so it needs no separate pbxproj entry (same convention as EmojiKeyboardView).
enum GlobalChatSearch {
    struct ChatHit { let chat: OCTChat; let title: String }
    struct MessageHit { let chat: OCTChat; let title: String; let snippet: String; let messageId: String; let date: Date }
    struct Outcome { let chats: [ChatHit]; let messages: [MessageHit] }

    // Cap message results so a huge history can't stall the UI on every keystroke.
    private static let messageResultLimit = 80

    static func run(query: String, submanagerObjects: OCTSubmanagerObjects) -> Outcome {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            return Outcome(chats: [], messages: [])
        }

        let opts: String.CompareOptions = [.caseInsensitive, .diacriticInsensitive]

        // 1) chats / groups by display name
        var chatHits: [ChatHit] = []
        let allChats = submanagerObjects.chats()
        for index in 0..<allChats.count {
            let chat = allChats[index]
            let title = MessageForwarder.displayName(for: chat)
            if title.range(of: trimmed, options: opts) != nil {
                chatHits.append(ChatHit(chat: chat, title: title))
            }
        }

        // 2) messages by text or file name (skip group system notices). Realm does the filtering;
        //    we sort newest-first and take a bounded slice.
        let predicate = NSPredicate(
            format: "groupSystemMessage == NO AND ((messageText.text CONTAINS[cd] %@) OR (messageFile.fileName CONTAINS[cd] %@))",
            trimmed, trimmed)
        let msgs = submanagerObjects.messages(predicate: predicate)
            .sortedResultsUsingProperty("dateInterval", ascending: false)

        var messageHits: [MessageHit] = []
        var chatCache: [String: OCTChat] = [:]
        let cap = min(msgs.count, messageResultLimit)
        for index in 0..<cap {
            let message = msgs[index]
            let chatId = message.chatUniqueIdentifier
            let chat: OCTChat
            if let cached = chatCache[chatId] {
                chat = cached
            }
            else if let resolved = submanagerObjects.object(withUniqueIdentifier: chatId, for: .chat) as? OCTChat {
                chatCache[chatId] = resolved
                chat = resolved
            }
            else {
                continue
            }

            let snippet = (MessageReplyHelper.plainBody(for: message) ?? "")
                .replacingOccurrences(of: "\n", with: " ")
                .trimmingCharacters(in: .whitespacesAndNewlines)
            // KHANDAQ (#63): the Realm pre-filter matched the RAW text, which embeds a reply's hidden
            // quote preview ([KQ|…|<quoted>…]). Drop hits whose VISIBLE body / file name doesn't
            // actually contain the query, so e.g. a reply "Да" no longer matches "прове".
            let fileName = message.messageFile?.fileName ?? ""
            if snippet.range(of: trimmed, options: opts) == nil,
               fileName.range(of: trimmed, options: opts) == nil {
                continue
            }
            messageHits.append(MessageHit(chat: chat, title: MessageForwarder.displayName(for: chat),
                                          snippet: snippet, messageId: message.uniqueIdentifier ?? "",
                                          date: message.date()))
        }

        return Outcome(chats: chatHits, messages: messageHits)
    }
}

/// Grouped results table behind the search bar, styled per Figma: grey section captions
/// («Контакты и группы:» / «Сообщения:»), avatar rows, teal highlight of the matched substring in
/// the message preview and the time on the right. Selecting a row opens that conversation.
final class GlobalSearchResultsController: UITableViewController {
    var onSelectChat: ((OCTChat) -> Void)?

    fileprivate struct Layout {
        static let avatarSize: CGFloat = 44
        static let horizontalInset: CGFloat = 16
        static let avatarToText: CGFloat = 12
        static let chatRowHeight: CGFloat = 56
        static let messageRowHeight: CGFloat = 68
        static let headerHeight: CGFloat = 32
    }

    private let theme: Theme
    private let avatarManager: AvatarManager
    private let timeFormatter = DateFormatter(type: .time)
    private let dateFormatter = DateFormatter(type: .relativeDate)
    private var chatHits: [GlobalChatSearch.ChatHit] = []
    private var messageHits: [GlobalChatSearch.MessageHit] = []
    private var query = ""

    init(theme: Theme) {
        self.theme = theme
        self.avatarManager = AvatarManager(theme: theme)
        super.init(style: .grouped)
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        tableView.backgroundColor = theme.colorForType(.NormalBackground)
        tableView.keyboardDismissMode = .onDrag
        tableView.separatorColor = theme.colorForType(.SeparatorsAndBorders)
        tableView.separatorInset = UIEdgeInsets(
                top: 0,
                left: Layout.horizontalInset + Layout.avatarSize + Layout.avatarToText,
                bottom: 0,
                right: 0)
        if #available(iOS 15.0, *) {
            tableView.sectionHeaderTopPadding = 0
        }
        tableView.register(GlobalSearchResultCell.self,
                           forCellReuseIdentifier: GlobalSearchResultCell.staticReuseIdentifier)
    }

    func apply(outcome: GlobalChatSearch.Outcome, query: String) {
        chatHits = outcome.chats
        messageHits = outcome.messages
        self.query = query
        updateEmptyState()
        tableView.reloadData()
    }

    private func updateEmptyState() {
        let isEmpty = chatHits.isEmpty && messageHits.isEmpty
        guard isEmpty, !query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            tableView.backgroundView = nil
            return
        }
        let label = UILabel()
        label.text = String(localized: "global_search_no_results")
        label.textColor = theme.colorForType(.EmptyScreenPlaceholderText)
        label.font = UIFont.systemFont(ofSize: 17.0)
        label.textAlignment = .center
        tableView.backgroundView = label
    }

    private enum SectionKind { case chats, messages }

    private var sections: [SectionKind] {
        var result: [SectionKind] = []
        if !chatHits.isEmpty { result.append(.chats) }
        if !messageHits.isEmpty { result.append(.messages) }
        return result
    }

    override func numberOfSections(in tableView: UITableView) -> Int {
        return sections.count
    }

    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        switch sections[section] {
            case .chats: return chatHits.count
            case .messages: return messageHits.count
        }
    }

    override func tableView(_ tableView: UITableView, viewForHeaderInSection section: Int) -> UIView? {
        let container = UIView()
        container.backgroundColor = .clear

        let label = UILabel()
        label.font = UIFont.khandaqFontWithSize(15.0, weight: .light)
        label.textColor = theme.colorForType(.ChatInformationText)
        switch sections[section] {
            case .chats: label.text = String(localized: "global_search_section_chats")
            case .messages: label.text = String(localized: "global_search_section_messages")
        }
        container.addSubview(label)

        label.snp.makeConstraints {
            $0.left.equalTo(container).offset(Layout.horizontalInset)
            $0.right.lessThanOrEqualTo(container).inset(Layout.horizontalInset)
            $0.bottom.equalTo(container).inset(6.0)
        }
        return container
    }

    override func tableView(_ tableView: UITableView, heightForHeaderInSection section: Int) -> CGFloat {
        return Layout.headerHeight
    }

    override func tableView(_ tableView: UITableView, heightForFooterInSection section: Int) -> CGFloat {
        return CGFloat.leastNormalMagnitude
    }

    override func tableView(_ tableView: UITableView, heightForRowAt indexPath: IndexPath) -> CGFloat {
        switch sections[indexPath.section] {
            case .chats: return Layout.chatRowHeight
            case .messages: return Layout.messageRowHeight
        }
    }

    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(
                withIdentifier: GlobalSearchResultCell.staticReuseIdentifier,
                for: indexPath) as! GlobalSearchResultCell
        cell.applyTheme(theme)

        switch sections[indexPath.section] {
            case .chats:
                let hit = chatHits[indexPath.row]
                cell.setup(name: hit.title,
                           snippet: nil,
                           timeText: nil,
                           avatar: avatarImage(for: hit.chat, title: hit.title))
            case .messages:
                let hit = messageHits[indexPath.row]
                cell.setup(name: hit.title,
                           snippet: highlightedSnippet(hit.snippet),
                           timeText: dateText(for: hit.date),
                           avatar: avatarImage(for: hit.chat, title: hit.title))
        }
        return cell
    }

    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        let chat: OCTChat
        switch sections[indexPath.section] {
            case .chats:
                chat = chatHits[indexPath.row].chat
            case .messages:
                let hit = messageHits[indexPath.row]
                chat = hit.chat
                // KHANDAQ (#64): jump to the matched message once the chat opens.
                if !hit.messageId.isEmpty {
                    GlobalSearchScrollTarget.pending = (chat.uniqueIdentifier, hit.messageId)
                }
        }
        onSelectChat?(chat)
    }

    // MARK: styling helpers

    /// Snippet with every occurrence of the query tinted teal (LinkText), rest is secondary grey.
    private func highlightedSnippet(_ snippet: String) -> NSAttributedString {
        let result = NSMutableAttributedString(string: snippet, attributes: [
            NSAttributedStringKey.font: UIFont.khandaqFontWithSize(15.0, weight: .light),
            NSAttributedStringKey.foregroundColor: theme.colorForType(.ChatInformationText),
        ])

        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            return result
        }

        let nsSnippet = snippet as NSString
        let accent = theme.colorForType(.LinkText)
        var searchRange = NSRange(location: 0, length: nsSnippet.length)
        while searchRange.length > 0 {
            let found = nsSnippet.range(of: trimmed,
                                        options: [.caseInsensitive, .diacriticInsensitive],
                                        range: searchRange)
            guard found.location != NSNotFound else {
                break
            }
            result.addAttribute(NSAttributedStringKey.foregroundColor, value: accent, range: found)
            let next = found.location + max(found.length, 1)
            guard next < nsSnippet.length else {
                break
            }
            searchRange = NSRange(location: next, length: nsSnippet.length - next)
        }
        return result
    }

    private func dateText(for date: Date) -> String {
        let isToday = (Calendar.current as NSCalendar).compare(Date(), to: date, toUnitGranularity: .day) == .orderedSame
        return isToday ? timeFormatter.string(from: date) : dateFormatter.string(from: date)
    }

    /// Same avatar sources as the chat list: friend photo → generated initials disc; groups always
    /// use the generated disc; Saved Messages keeps its bookmark badge.
    private func avatarImage(for chat: OCTChat, title: String) -> UIImage? {
        let diameter = Layout.avatarSize

        if chat.isSavedMessages {
            if #available(iOS 13.0, *) {
                let renderer = UIGraphicsImageRenderer(size: CGSize(width: diameter, height: diameter))
                return renderer.image { ctx in
                    theme.colorForType(.LinkText).setFill()
                    ctx.cgContext.fillEllipse(in: CGRect(x: 0, y: 0, width: diameter, height: diameter))
                    let glyph = diameter * 0.5
                    UIImage(systemName: "bookmark.fill")?
                        .withTintColor(.white, renderingMode: .alwaysOriginal)
                        .draw(in: CGRect(x: (diameter - glyph) / 2, y: (diameter - glyph) / 2, width: glyph, height: glyph))
                }
            }
            return avatarManager.avatarFromString(title, diameter: diameter)
        }

        if !chat.isGroup, let friend = chat.friends.lastObject() as? OCTFriend {
            if let data = friend.avatarData, let image = UIImage(data: data) {
                return image
            }
            return avatarManager.avatarFromString(friend.nickname, diameter: diameter)
        }

        return avatarManager.avatarFromString(title, diameter: diameter)
    }
}

/// KHANDAQ (Figma): global-search row — avatar on the left, name + (optional) snippet, time on the
/// right. Lives here to avoid a new file (no pbxproj edit), same convention as the controller above.
private final class GlobalSearchResultCell: UITableViewCell {
    static let staticReuseIdentifier = "GlobalSearchResultCell"

    private let avatarImageView = UIImageView()
    private let nameLabel = UILabel()
    private let snippetLabel = UILabel()
    private let timeLabel = UILabel()
    private let textStack = UIStackView()

    override init(style: UITableViewCellStyle, reuseIdentifier: String?) {
        super.init(style: style, reuseIdentifier: reuseIdentifier)

        avatarImageView.layer.cornerRadius = GlobalSearchResultsController.Layout.avatarSize / 2
        avatarImageView.layer.masksToBounds = true
        avatarImageView.contentMode = .scaleAspectFill
        contentView.addSubview(avatarImageView)

        nameLabel.font = UIFont.khandaqFontWithSize(17.0, weight: .medium)
        snippetLabel.font = UIFont.khandaqFontWithSize(15.0, weight: .light)
        timeLabel.font = UIFont.khandaqFontWithSize(13.0, weight: .light)

        textStack.axis = .vertical
        textStack.alignment = .leading
        textStack.spacing = 2.0
        textStack.addArrangedSubview(nameLabel)
        textStack.addArrangedSubview(snippetLabel)
        contentView.addSubview(textStack)
        contentView.addSubview(timeLabel)

        timeLabel.setContentCompressionResistancePriority(.required, for: .horizontal)
        timeLabel.setContentHuggingPriority(.required, for: .horizontal)

        avatarImageView.snp.makeConstraints {
            $0.left.equalTo(contentView).offset(GlobalSearchResultsController.Layout.horizontalInset)
            $0.centerY.equalTo(contentView)
            $0.size.equalTo(GlobalSearchResultsController.Layout.avatarSize)
        }

        textStack.snp.makeConstraints {
            $0.left.equalTo(avatarImageView.snp.right).offset(GlobalSearchResultsController.Layout.avatarToText)
            $0.centerY.equalTo(contentView)
            $0.right.lessThanOrEqualTo(timeLabel.snp.left).offset(-8.0)
        }

        timeLabel.snp.makeConstraints {
            $0.right.equalTo(contentView).inset(GlobalSearchResultsController.Layout.horizontalInset)
            $0.centerY.equalTo(nameLabel)
        }
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func applyTheme(_ theme: Theme) {
        backgroundColor = theme.colorForType(.NormalBackground)
        nameLabel.textColor = theme.colorForType(.NormalText)
        timeLabel.textColor = theme.colorForType(.ChatInformationText)
    }

    func setup(name: String, snippet: NSAttributedString?, timeText: String?, avatar: UIImage?) {
        nameLabel.text = name
        snippetLabel.attributedText = snippet
        snippetLabel.isHidden = (snippet == nil)
        timeLabel.text = timeText
        avatarImageView.image = avatar
    }
}
