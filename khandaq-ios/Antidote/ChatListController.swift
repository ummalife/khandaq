// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation

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

        let addButton = UIBarButtonItem(barButtonSystemItem: .add, target: self, action: #selector(ChatListController.addButtonPressed))
        addButton.accessibilityLabel = String(localized: "group_add_button")

        let themeButton: UIBarButtonItem
        if let image = themeToggleImage() {
            themeButton = UIBarButtonItem(
                image: image,
                style: .plain,
                target: self,
                action: #selector(ChatListController.themeTogglePressed)
            )
        }
        else {
            themeButton = UIBarButtonItem(
                title: ThemeAppearance.isDarkMode ? "☾" : "☀︎",
                style: .plain,
                target: self,
                action: #selector(ChatListController.themeTogglePressed)
            )
        }
        themeButton.accessibilityLabel = String(localized: "theme_toggle_accessibility")
        navigationItem.rightBarButtonItems = [addButton, themeButton]
    }

    @objc func themeTogglePressed() {
        ThemeAppearance.isDarkMode.toggle()
    }

    func themeToggleImage() -> UIImage? {
        guard #available(iOS 13.0, *) else {
            return nil
        }

        let symbolName = ThemeAppearance.isDarkMode ? "moon.fill" : "sun.max.fill"
        let config = UIImage.SymbolConfiguration(pointSize: 17, weight: .medium)
        return UIImage(systemName: symbolName, withConfiguration: config)
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
        placeholderLabel.font = UIFont.khandaqFontWithSize(26.0, weight: .light)
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
            $0.center.equalTo(view)
            $0.size.equalTo(placeholderLabel.sizeThatFits(CGSize(width: CGFloat.greatestFiniteMagnitude, height: CGFloat.greatestFiniteMagnitude)))
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
