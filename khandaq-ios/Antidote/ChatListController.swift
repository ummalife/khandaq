// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import SnapKit
import UIKit

protocol ChatListControllerDelegate: class {
    func chatListController(_ controller: ChatListController, didSelectChat chat: OCTChat)
    func chatListController(_ controller: ChatListController, didRequestGroupInfo chat: OCTChat)
    func chatListControllerDidRequestCreateGroup(_ controller: ChatListController)
    func chatListControllerDidRequestCreatePrivateGroup(_ controller: ChatListController)
    func chatListControllerDidRequestJoinGroup(_ controller: ChatListController)
    /// "Join a group" with the key the command line holds.
    func chatListController(_ controller: ChatListController, didRequestJoinGroupWithKey key: String)
    func chatListControllerDidRequestScanGroupQR(_ controller: ChatListController)
    /// The "you" block: your profile.
    func chatListControllerDidRequestProfile(_ controller: ChatListController)
    func chatListControllerDidRequestSettings(_ controller: ChatListController)
    /// "New contact", with the ID already parsed when one was pasted into the command line.
    func chatListController(_ controller: ChatListController, didRequestAddContactWithAddress address: String?)
}

/**
 The phone's main screen, "Air" (design/redesign/02-plan.md, screens 1 to 4): you and your node, the
 command line, the filters, and one list of everyone you can write to, split by whether a message sent
 now would arrive now. It replaces the four tabs: profile and settings open from the "you" block, a new
 contact or group from "+", and contacts live in the same list as conversations.
 */
class ChatListController: UIViewController {
    weak var delegate: ChatListControllerDelegate?

    fileprivate let theme: Theme
    fileprivate weak var submanagerUser: OCTSubmanagerUser!

    fileprivate let header = AirHeaderView()

    /// Your tile in the "you" block, where "You" grows from (AirYouTransition).
    var youTile: UIView {
        return header.selfView.tileView
    }
    fileprivate let tableView = UITableView(frame: .zero, style: .plain)
    /// "New contact with this ID" over the list while the command line holds the start of an ID.
    fileprivate let commandIdRow = AirCommandIdRow()
    /// "Join the group with this key" over it while the line holds exactly a group key's 64 characters.
    fileprivate let commandGroupRow = AirCommandIdRow(kind: .group)
    fileprivate lazy var commandHeader: UIView = {
        let header = UIView()
        let stack = UIStackView(arrangedSubviews: [commandGroupRow, commandIdRow])
        stack.axis = .vertical
        header.addSubview(stack)
        stack.snp.makeConstraints {
            $0.edges.equalToSuperview()
        }
        return header
    }()
    fileprivate var commandIdHex: String?
    fileprivate weak var submanagerObjectsForCommand: OCTSubmanagerObjects?
    fileprivate var dataSource: AirListDataSource!
    fileprivate var emptyView: AirEmptyView?
    fileprivate var emptyState: AirEmptyState?
    fileprivate var emptyViewWidth: CGFloat = 0
    fileprivate var network = AirNetworkState.connecting
    /// Set by the end of first launch ("Add the first person", E19): the command line takes the
    /// keyboard the first time this screen appears, ready for an ID or a name.
    var focusesCommandLineOnAppear = false

    fileprivate var cancelEditButton: UIBarButtonItem!
    fileprivate var deleteSelectedButton: UIBarButtonItem!

    init(theme: Theme,
         submanagerChats: OCTSubmanagerChats,
         submanagerGroups: OCTSubmanagerGroups,
         submanagerObjects: OCTSubmanagerObjects,
         submanagerFriends: OCTSubmanagerFriends,
         submanagerUser: OCTSubmanagerUser) {
        self.theme = theme
        self.submanagerUser = submanagerUser
        self.submanagerObjectsForCommand = submanagerObjects

        super.init(nibName: nil, bundle: nil)

        dataSource = AirListDataSource(theme: theme,
                                       tableView: tableView,
                                       submanagerChats: submanagerChats,
                                       submanagerGroups: submanagerGroups,
                                       submanagerObjects: submanagerObjects,
                                       submanagerFriends: submanagerFriends)
        dataSource.delegate = self

        title = String(localized: "chats_title")
    }

    required convenience init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    deinit {
        NotificationCenter.default.removeObserver(self)
    }

    override func loadView() {
        let root = UIView()
        root.backgroundColor = KQColor.surface
        root.accessibilityIdentifier = "air.root"
        view = root

        view.addSubview(tableView)
        view.addSubview(header)

        header.snp.makeConstraints {
            $0.top.equalTo(view.safeAreaLayoutGuide)
            $0.leading.trailing.equalToSuperview()
        }
        tableView.snp.makeConstraints {
            $0.top.equalTo(header.snp.bottom)
            $0.leading.trailing.bottom.equalToSuperview()
        }

        configureHeader()
        configureEditing()
        installFilterSwipeGesture()

        NotificationCenter.default.addObserver(self,
                                               selector: #selector(keyboardWillChangeFrame(_:)),
                                               name: NSNotification.Name.UIKeyboardWillChangeFrame,
                                               object: nil)
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        // The screens pushed from here (You, Settings, a conversation) show only the chevron: where
        // "Chats" does not fit, at the large text sizes, UIKit put the system "Back" in its place,
        // which does not follow the in-app language. VoiceOver still reads "Chats".
        navigationItem.backButtonDisplayMode = .minimal
        refreshSelf()
        header.selfView.setNetwork(network, animated: false)
        header.setFilter(dataSource.filter)
        header.setCounts(dataSource.snapshot.counts, animated: false)
        updateEmptyState()
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        // The header is the top of this screen; the bar returns for selection and for every screen
        // pushed from here.
        navigationController?.setNavigationBarHidden(!isEditing, animated: animated)
        refreshSelf()
        view.layoutIfNeeded()
        dataSource.willAppear()
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        dataSource.didAppear()
        if focusesCommandLineOnAppear {
            focusesCommandLineOnAppear = false
            header.commandField.textField.becomeFirstResponder()
        }
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        header.commandField.textField.resignFirstResponder()
        if navigationController?.topViewController !== self {
            navigationController?.setNavigationBarHidden(false, animated: animated)
        }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        if emptyView != nil, tableView.bounds.width != emptyViewWidth {
            sizeEmptyFooter()
        }
    }

    override func setEditing(_ editing: Bool, animated: Bool) {
        guard editing != isEditing else {
            return
        }
        super.setEditing(editing, animated: animated)

        if editing {
            header.commandField.textField.resignFirstResponder()
        }
        tableView.setEditing(editing, animated: animated)
        navigationController?.setNavigationBarHidden(!editing, animated: animated)
        header.isUserInteractionEnabled = !editing
        UIView.animate(withDuration: KQMotion.duration(KQMotion.state)) {
            self.header.alpha = editing ? 0.4 : 1
        }
        updateDeleteSelectedButton()

        if !editing {
            dataSource.editingDidEnd()
        }
    }

    // MARK: - Driven by the session

    /// The quiet line under your name.
    func setNetworkState(_ state: AirNetworkState) {
        network = state
        guard isViewLoaded else {
            return
        }
        header.selfView.setNetwork(state, animated: view.window != nil)
    }

    /// Your name and photo, after a change in the profile.
    func refreshSelf() {
        guard isViewLoaded, let user = submanagerUser else {
            return
        }
        let photo = user.userAvatar().flatMap { UIImage(data: $0) }
        header.selfView.configure(name: user.userName() ?? "", photo: photo)
    }

    /// A notification about a request: the list, showing requests.
    func showRequests() {
        loadViewIfNeeded()
        if isEditing {
            setEditing(false, animated: false)
        }
        cancelSearch()
        applyFilter(.requests)
    }
}

// MARK: - Header and command line

private extension ChatListController {
    func configureHeader() {
        header.selfView.addTarget(self, action: #selector(selfPressed), for: .touchUpInside)
        header.selfView.onNetworkTap = { [weak self] in
            self?.networkPressed()
        }
        header.settingsButton.addTarget(self, action: #selector(settingsPressed), for: .touchUpInside)
        header.addButton.addTarget(self, action: #selector(addPressed), for: .touchUpInside)
        header.cancelButton.addTarget(self, action: #selector(cancelSearchPressed), for: .touchUpInside)
        for pill in header.pills.values {
            pill.addTarget(self, action: #selector(pillPressed(_:)), for: .touchUpInside)
        }

        header.commandField.onTextChange = { [weak self] text in
            self?.commandChanged(text)
        }
        header.commandField.onReturn = { [weak self] _ in
            self?.header.commandField.textField.resignFirstResponder()
        }
        header.commandField.onFocusChange = { [weak self] _ in
            self?.updateSearchingState()
        }
        commandIdRow.addTarget(self, action: #selector(commandIdRowPressed), for: .touchUpInside)
        commandGroupRow.addTarget(self, action: #selector(commandGroupRowPressed), for: .touchUpInside)
    }

    @objc func commandGroupRowPressed() {
        guard let hex = commandIdHex else {
            return
        }
        cancelSearch()
        delegate?.chatListController(self, didRequestJoinGroupWithKey: hex)
    }

    @objc func commandIdRowPressed() {
        let hex = commandIdHex
        cancelSearch()
        delegate?.chatListController(self, didRequestAddContactWithAddress: hex)
    }

    /// Shows or hides the row "New contact with this ID" as the head of the list, with "Join the group
    /// with this key" over it when the line holds as many characters as a group key has.
    func setCommandIdRow(hex: String?) {
        commandIdHex = hex
        defer {
            updateEmptyState()
        }
        guard let hex = hex else {
            if tableView.tableHeaderView === commandHeader {
                tableView.tableHeaderView = nil
            }
            return
        }
        let own = submanagerUser?.userAddress ?? ""
        commandIdRow.configure(note: AirCommandIdRow.note(hex: hex, ownAddress: own, isContact: { [weak self] key in
            (self?.submanagerObjectsForCommand?.friends(predicate: NSPredicate(format: "publicKey ==[c] %@", key)).count ?? 0) > 0
        }))
        commandGroupRow.isHidden = hex.count != AirGroupKey.length
        commandGroupRow.configure(note: String(localized: "air_cmd_group_note"))
        let width = tableView.bounds.width > 0 ? tableView.bounds.width : view.bounds.width
        let size = commandHeader.systemLayoutSizeFitting(CGSize(width: width, height: UILayoutFittingCompressedSize.height),
                                                         withHorizontalFittingPriority: .required,
                                                         verticalFittingPriority: .fittingSizeLevel)
        let rows: CGFloat = commandGroupRow.isHidden ? 1 : 2
        commandHeader.frame = CGRect(x: 0, y: 0, width: width, height: max(rows * KQSize.rowPhone, size.height))
        tableView.tableHeaderView = commandHeader
    }

    @objc func selfPressed() {
        delegate?.chatListControllerDidRequestProfile(self)
    }

    /// The line under your name: the state of your node in words (E4).
    func networkPressed() {
        navigationController?.pushViewController(AirNetworkController(theme: theme), animated: true)
    }

    @objc func settingsPressed() {
        delegate?.chatListControllerDidRequestSettings(self)
    }

    @objc func addPressed() {
        let actions = [
            AirCreateSheetController.Action(title: String(localized: "air_new_contact"),
                                            icon: "user-plus",
                                            identifier: "air.newContact") { [weak self] in
                guard let self = self else {
                    return
                }
                self.delegate?.chatListController(self, didRequestAddContactWithAddress: nil)
            },
            // KHANDAQ (redesign stage 3, E15): one "New group"; who can join is chosen on its sheet, where
            // "by invitation only" is the answer it opens with.
            AirCreateSheetController.Action(title: String(localized: "air_new_group_title"),
                                            icon: "users",
                                            identifier: "air.newGroup") { [weak self] in
                guard let self = self else {
                    return
                }
                self.delegate?.chatListControllerDidRequestCreatePrivateGroup(self)
            },
            // KHANDAQ (redesign stage 5, E15): joining by the group key. A cold join by key alone may
            // wait for a member to come online; objcTox also asks the contacts among the members for an
            // invitation (#51), and the sheet says how it works before anything is sent.
            AirCreateSheetController.Action(title: String(localized: "air_join_title"),
                                            icon: "scan",
                                            identifier: "air.joinGroup") { [weak self] in
                guard let self = self else {
                    return
                }
                self.delegate?.chatListControllerDidRequestJoinGroup(self)
            },
        ]
        present(AirCreateSheetController(actions: actions), animated: true, completion: nil)
    }

    @objc func pillPressed(_ sender: AirPillButton) {
        // The active pill again returns to everything.
        applyFilter(sender.filter == dataSource.filter ? .all : sender.filter)
    }

    @objc func cancelSearchPressed() {
        cancelSearch()
    }

    func applyFilter(_ filter: AirFilter) {
        dataSource.setFilter(filter)
        header.setFilter(filter)
        // Another filter opens at its top. The rows there start at the estimated row height, and a card
        // measured taller than that moved the list by the difference once laid out, so a band that opened
        // Requests left the new card half under the header. The second pass sets the top again with the
        // rows measured.
        let top = CGPoint(x: 0, y: -tableView.adjustedContentInset.top)
        for _ in 0..<2 {
            tableView.layoutIfNeeded()
            tableView.setContentOffset(top, animated: false)
        }
    }

    func commandChanged(_ text: String) {
        switch AirCommand.parse(text) {
            case .groupKey(let key):
                // A group's key with its prefix (a link or the dossier's QR text): straight to "Join a group".
                header.commandField.text = ""
                header.commandField.textField.resignFirstResponder()
                setCommandIdRow(hex: nil)
                dataSource.setQuery("")
                updateSearchingState()
                delegate?.chatListController(self, didRequestJoinGroupWithKey: key)
            case .contactAddress(let address):
                // A whole Tox ID: straight to "New contact" with it, and the line is free again.
                header.commandField.text = ""
                header.commandField.textField.resignFirstResponder()
                setCommandIdRow(hex: nil)
                dataSource.setQuery("")
                updateSearchingState()
                delegate?.chatListController(self, didRequestAddContactWithAddress: address)
            case .partialAddress(let hex):
                setCommandIdRow(hex: hex)
                dataSource.setQuery(hex)
                updateSearchingState()
            case .query(let query):
                setCommandIdRow(hex: nil)
                dataSource.setQuery(query)
                updateSearchingState()
            case .empty:
                setCommandIdRow(hex: nil)
                dataSource.setQuery("")
                updateSearchingState()
        }
    }

    func cancelSearch() {
        header.commandField.text = ""
        header.commandField.textField.resignFirstResponder()
        setCommandIdRow(hex: nil)
        dataSource.setQuery("")
        updateSearchingState()
    }

    var isSearching: Bool {
        return header.commandField.textField.isFirstResponder || !dataSource.query.isEmpty
    }

    func updateSearchingState() {
        header.setSearching(isSearching, animated: view.window != nil)
    }
}

// MARK: - Selection

private extension ChatListController {
    func configureEditing() {
        cancelEditButton = UIBarButtonItem(title: String(localized: "alert_cancel"),
                                           style: .plain,
                                           target: self,
                                           action: #selector(cancelEditingPressed))
        cancelEditButton.tintColor = KQColor.ink

        deleteSelectedButton = UIBarButtonItem(title: String(localized: "alert_delete"),
                                               style: .done,
                                               target: self,
                                               action: #selector(deleteSelectedPressed))
        deleteSelectedButton.tintColor = KQColor.danger

        navigationItem.leftBarButtonItem = cancelEditButton
        navigationItem.rightBarButtonItem = deleteSelectedButton
    }

    @objc func cancelEditingPressed() {
        setEditing(false, animated: true)
    }

    @objc func deleteSelectedPressed() {
        let chats = dataSource.selectedChats
        guard !chats.isEmpty else {
            return
        }

        // E21: a sheet from below, what happens in one sentence, the verb on the button.
        let sheet = AirConfirm.sheet(title: String(localized: "delete_selected_chats_title", chats.count),
                                     message: String(localized: "air_delete_chats_message"),
                                     verb: String(localized: "alert_delete"),
                                     source: view) { [weak self] in
            guard let self = self else {
                return
            }
            self.dataSource.deleteSelectedChats()
            self.setEditing(false, animated: true)
        }
        present(sheet, animated: true, completion: nil)
    }

    func updateDeleteSelectedButton() {
        let count = dataSource.selectedChats.count
        deleteSelectedButton.isEnabled = count > 0
        navigationItem.title = isEditing && count > 0 ? String(localized: "air_selected_format", count) : nil
    }
}

// MARK: - Empty state

private extension ChatListController {
    func updateEmptyState() {
        // While the row "New contact with this ID" stands over the list, "nothing found" says nothing new.
        let found = dataSource.snapshot.emptyState
        let state = commandIdHex != nil && found == .noMatch ? nil : found
        guard state != emptyState || (state != nil && emptyView == nil) else {
            return
        }
        emptyState = state

        guard let current = state else {
            emptyView = nil
            tableView.tableFooterView = UIView(frame: CGRect(x: 0, y: 0, width: 0, height: CGFloat.leastNormalMagnitude))
            return
        }

        let copy = ChatListController.emptyCopy(for: current)
        let view = AirEmptyView(title: copy.title, body: copy.body, action: copy.action)
        view.accessibilityIdentifier = "air.empty"
        view.onAction = { [weak self] in
            self?.emptyActionPressed(for: current)
        }
        emptyView = view
        sizeEmptyFooter()
    }

    func sizeEmptyFooter() {
        guard let view = emptyView else {
            return
        }
        let width = tableView.bounds.width > 0 ? tableView.bounds.width : UIScreen.main.bounds.width
        emptyViewWidth = tableView.bounds.width
        let size = view.systemLayoutSizeFitting(CGSize(width: width, height: UILayoutFittingCompressedSize.height),
                                                withHorizontalFittingPriority: .required,
                                                verticalFittingPriority: .fittingSizeLevel)
        view.frame = CGRect(x: 0, y: 0, width: width, height: ceil(size.height))
        tableView.tableFooterView = view
    }

    func emptyActionPressed(for state: AirEmptyState) {
        switch state {
            case .nothingYet:
                delegate?.chatListController(self, didRequestAddContactWithAddress: nil)
            case .noMatch:
                cancelSearch()
            case .noRequests, .noTransit, .noFavorites:
                applyFilter(.all)
        }
    }

    static func emptyCopy(for state: AirEmptyState) -> (title: String, body: String, action: String) {
        switch state {
            case .nothingYet:
                return (String(localized: "air_empty_nothing_title"),
                        String(localized: "air_empty_nothing_body"),
                        String(localized: "air_new_contact"))
            case .noMatch:
                return (String(localized: "air_empty_no_match_title"),
                        String(localized: "air_empty_no_match_body"),
                        String(localized: "air_empty_show_all"))
            case .noRequests:
                return (String(localized: "air_empty_no_requests_title"),
                        String(localized: "air_empty_no_requests_body"),
                        String(localized: "air_empty_show_all"))
            case .noTransit:
                return (String(localized: "air_empty_no_transit_title"),
                        String(localized: "air_empty_no_transit_body"),
                        String(localized: "air_empty_show_all"))
            case .noFavorites:
                return (String(localized: "air_empty_no_favorites_title"),
                        String(localized: "air_empty_no_favorites_body"),
                        String(localized: "air_empty_show_all"))
        }
    }

    @objc func keyboardWillChangeFrame(_ notification: Notification) {
        guard let value = notification.userInfo?[UIKeyboardFrameEndUserInfoKey] as? NSValue else {
            return
        }
        let frame = view.convert(value.cgRectValue, from: nil)
        let overlap = max(0, view.bounds.maxY - frame.minY - view.safeAreaInsets.bottom)
        tableView.contentInset.bottom = overlap
        tableView.verticalScrollIndicatorInsets.bottom = overlap
    }
}

// MARK: - The list

extension ChatListController: AirListDataSourceDelegate {
    func airList(_ list: AirListDataSource, didSelectChat chat: OCTChat) {
        header.commandField.textField.resignFirstResponder()
        delegate?.chatListController(self, didSelectChat: chat)
    }

    func airList(_ list: AirListDataSource, didRequestGroupInfo chat: OCTChat) {
        delegate?.chatListController(self, didRequestGroupInfo: chat)
    }

    func airList(_ list: AirListDataSource, present controller: UIViewController) {
        present(controller, animated: true, completion: nil)
    }

    func airListDidUpdate(_ list: AirListDataSource) {
        guard isViewLoaded else {
            return
        }
        header.setCounts(list.snapshot.counts, animated: view.window != nil)
        updateEmptyState()
        if isEditing && !list.hasSelectableRows {
            setEditing(false, animated: true)
        }
    }

    func airListSelectionDidChange(_ list: AirListDataSource) {
        updateDeleteSelectedButton()
    }

    func airList(_ list: AirListDataSource, didRequestSelectionStartingAt indexPath: IndexPath) {
        setEditing(true, animated: true)
        tableView.selectRow(at: indexPath, animated: false, scrollPosition: .none)
        updateDeleteSelectedButton()
    }
}

// MARK: - Swiping between filters

/**
 KHANDAQ (owner request 17.08): switch filters by swiping the list sideways; reaching up to the pills on
 a large phone is the complaint being fixed.

 A pan recogniser, not a swipe one. UISwipeGestureRecognizer never fired here: the table and its rows
 own the touches (rows carry trailing actions, the table has its own pan), and a discrete swipe
 recogniser added above them was simply never given the gesture. A pan runs alongside the table's own
 scrolling (simultaneous recognition allowed) and decides at the end whether the movement was a
 horizontal flick: far enough sideways, and clearly more sideways than vertical, so scrolling the list
 and opening a row's actions keep working untouched.
 */
extension ChatListController: UIGestureRecognizerDelegate {
    fileprivate func installFilterSwipeGesture() {
        let pan = UIPanGestureRecognizer(target: self, action: #selector(handleFilterPan(_:)))
        pan.delegate = self
        pan.cancelsTouchesInView = false
        pan.maximumNumberOfTouches = 1
        tableView.addGestureRecognizer(pan)
    }

    @objc fileprivate func handleFilterPan(_ recognizer: UIPanGestureRecognizer) {
        guard recognizer.state == .ended, !isEditing, !isSearching else {
            return
        }

        let translation = recognizer.translation(in: view)
        let velocity = recognizer.velocity(in: view)
        let horizontalEnough = abs(translation.x) >= 60.0 || abs(velocity.x) >= 400.0
        guard horizontalEnough, abs(translation.x) > abs(translation.y) * 1.5 else {
            return
        }

        let isRightToLeft = view.effectiveUserInterfaceLayoutDirection == .rightToLeft
        guard let filter = ChatListSwipeNavigation.nextFilter(from: dataSource.filter,
                                                              swipingLeft: translation.x < 0,
                                                              isRightToLeft: isRightToLeft) else {
            return
        }
        applyFilter(filter)
    }

    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer,
                           shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer) -> Bool {
        // The filter pan has to run alongside the table's scrolling and the rows' swipe actions:
        // refusing simultaneous recognition is what kept it from ever being recognised.
        return true
    }

    func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        guard gestureRecognizer is UIPanGestureRecognizer else {
            return true
        }
        // Not while choosing chats and not while searching.
        return !isEditing && !isSearching
    }
}

// MARK: - Global search
/// KHANDAQ (#64): single-shot target so tapping a message found by the command line scrolls to that
/// message once the chat opens, without threading a parameter through the coordinators. The chat
/// controller reads and clears it on appear.
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

/// Global search: chats and groups by name, and every message by its text or file name. The Air list
/// filters rows by name itself and uses the message matches.
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
            // quote preview ([KQ|...|<quoted>...]). Drop hits whose VISIBLE body or file name does not
            // contain the query, so a short reply no longer matches words from the message it quotes.
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
