
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit
import AVFoundation
import CoreLocation
import ImageIO
import SnapKit
import MobileCoreServices
import os

private struct Constants {
    static let MessagesPortionSize = 50

    static let InputViewTopOffset: CGFloat = 50.0

    static let NewMessageViewAllowedDelta: CGFloat = 20.0
    static let NewMessageViewEdgesOffset: CGFloat = 5.0
    static let NewMessageViewTopOffset: CGFloat = -KQSpace.x12
    static let NewMessageViewAnimationDuration = 0.2

    static let ResetPanAnimationDuration = 0.3

    static let MaxImageSizeToShowInline: OCTToxFileSize = 20 * 1024 * 1024

    static let MaxInlineImageSide: CGFloat = LoadingImageView.Constants.ImageButtonSize * UIScreen.main.scale
}

protocol ChatPrivateControllerDelegate: class {
    func chatPrivateControllerWillAppear(_ controller: ChatPrivateController)
    func chatPrivateControllerWillDisappear(_ controller: ChatPrivateController)
    func chatPrivateControllerCallToChat(_ controller: ChatPrivateController, enableVideo: Bool)
    func chatPrivateControllerShowQuickLookController(
            _ controller: ChatPrivateController,
            dataSource: QuickLookPreviewControllerDataSource,
            selectedIndex: Int)
    // KHANDAQ (Figma): tapping the chat header opens the friend profile.
    func chatPrivateControllerShowFriendProfile(_ controller: ChatPrivateController, forFriend friend: OCTFriend)
    // KHANDAQ (user request 17.08): everything sent in this chat, split into files / audio / voice.
    func chatPrivateControllerShowAttachments(_ controller: ChatPrivateController, forChat chat: OCTChat)
    // KHANDAQ (redesign stage 2): "Remove" in the dossier, confirmed. The coordinator closes the
    // conversation first and removes the contact once it is off screen (AirContactRemoval).
    func chatPrivateControllerRemoveContact(_ controller: ChatPrivateController, friend: OCTFriend)
}

class ChatPrivateController: PortraitChatController {
    let chat: OCTChat

    fileprivate weak var delegate: ChatPrivateControllerDelegate?

    var isActiveLocationSharingChat: Bool {
        AppDelegate.location_sharing_contact_pubkey == friend?.publicKey
    }
    fileprivate let theme: Theme
    fileprivate weak var submanagerChats: OCTSubmanagerChats!
    fileprivate weak var submanagerObjects: OCTSubmanagerObjects!
    fileprivate weak var submanagerFiles: OCTSubmanagerFiles!

    fileprivate let messages: Results<OCTMessageAbstract>
    fileprivate var messagesToken: RLMNotificationToken?
    fileprivate var visibleMessages: Int {
        didSet {
            displayMapCache = nil
        }
    }
    // KHANDAQ (Figma): first unread incoming message captured on open (before updateLastReadDate
    // marks everything read) — its cell shows the "Непрочитанные сообщения" band and the chat opens
    // scrolled to it.
    fileprivate var unreadSeparatorMessageId: String?
    fileprivate var pendingFirstUnreadScrollIndex: Int?
    fileprivate var didScrollToFirstUnread: Bool = false
    // KHANDAQ: message search in 1:1 chats (parity with groups) — filters the list to matches.
    fileprivate var messageSearchQuery = ""
    fileprivate var messageSearchController: UISearchController?

    fileprivate let friend: OCTFriend?
    fileprivate var friendToken: RLMNotificationToken?

    fileprivate let imageCache = NSCache<AnyObject, AnyObject>()
    fileprivate let mediaPixelSizeCache = NSCache<NSString, NSValue>()
    fileprivate var voicePlayerObserver: NSObjectProtocol?

    fileprivate let timeFormatter: DateFormatter
    fileprivate let dateFormatter: DateFormatter

    fileprivate var audioButton: UIBarButtonItem!
    fileprivate var videoButton: UIBarButtonItem!
    fileprivate var locationButton: UIBarButtonItem!
    // KHANDAQ (redesign stage 3, E9): waiting for an offline person before a call (requestCall).
    fileprivate var callWaiting: AirCallWaitingController?
    fileprivate var callWaitingVideo = false
    fileprivate var callWaitingTimer: Timer?

    fileprivate var titleView: ChatPrivateTitleView!
    fileprivate var tableView: UITableView?
    fileprivate var typingHeaderView: ChatTypingHeaderView!
    fileprivate var fauxOfflineHeaderView: ChatFauxOfflineHeaderView!
    // KHANDAQ design (Figma): end-to-end encryption notice card shown at the top of an empty chat.
    fileprivate var encryptionBanner: UIView!
    fileprivate var newMessagesView: UIView!
    // KHANDAQ design (Figma): Telegram-style floating day chip ("Today", "Yesterday" or the date) that
    // surfaces while scrolling and reflects the date of the topmost visible message, then fades out.
    fileprivate var floatingDateView: UIView!
    fileprivate var floatingDateLabel: UILabel!
    fileprivate var floatingDateHideTimer: Timer?
    // KHANDAQ (#178): while the subtitle shows an offline state ("connecting…"/"last seen…"), no
    // Realm update fires if the friend stays offline — refresh it periodically so the
    // post-reconnect grace window lapses back to "last seen" and relative times stay fresh.
    fileprivate var presenceRefreshTimer: Timer?
    fileprivate var chatInputView: ChatInputView!
    fileprivate var editMessagesToolbar: UIToolbar!

    // KHANDAQ (redesign stage 2): the parts of the conversation in the "Air conversation" extension at
    // the end of this file. Under the header: the presence band and the pinned strip; between the log
    // and the field: the refused messages and the "On its way" tray; over everything: the dossier.
    fileprivate var topStack: UIStackView!
    fileprivate var presenceBand: AirPresenceBandView?
    fileprivate var pinnedStrip: AirPinnedStripView!
    fileprivate var bottomStack: UIStackView!
    fileprivate var failedList: AirFailedListView!
    fileprivate var trayView: AirTrayView!
    /// The room between the strips under the header and the field, shared by the log and the strips under it.
    fileprivate let stripsRoom = UILayoutGuide()
    /// At the accessibility text sizes the strips under the log take at most half of `stripsRoom`.
    fileprivate var stripsHalfConstraint: Constraint?
    fileprivate var isKeyboardUp = false
    /// The presence band's height when it was last on the screen, for the room it would take back.
    fileprivate var shownBandHeight: CGFloat = 0
    /// A fold of the band decided while laying out waits for the next turn of the run loop (keepRoomForLog).
    fileprivate var isBandFoldPending = false
    fileprivate var dossierView: AirDossierView?
    fileprivate var searchButton: UIBarButtonItem!
    fileprivate var filesButton: UIBarButtonItem?
    /// The messages in the tray, oldest first; the log leaves them out.
    fileprivate var trayIds: [String] = []
    fileprivate var isTrayDeparting = false
    fileprivate var needsTrayRefresh = false
    /// Changes of the messages that came while the tray was emptying into the log; applied once it folded.
    fileprivate var needsLogReload = false
    /// A progress listener for each transfer the stall watcher follows, besides the bridge of its row: a
    /// row scrolled out of sight lets its bridge go, and the silence after that is no stall.
    fileprivate var stallListeners: [String: ChatProgressBridge] = [:]
    /// What the open dossier shows, so it is rebuilt only when that changes.
    fileprivate var dossierSignature = ""
    /// Bumped whenever the rows of the log change without the loaded window changing.
    fileprivate var displayGeneration = 0
    /// Storage index of every row of the log; a nil map means each loaded message has its own row.
    fileprivate var displayMapCache: (key: DisplayMapKey, map: [Int]?)?
    /// KHANDAQ (QA 2026-11-13): `messages.count` when the table last read its rows, a row or a height.
    /// The Results are live, so the table can draw a message before Realm delivers the change that adds
    /// it; inserting that row again then draws the message twice. On a phone with TestFlight 1.4.33
    /// (142994) a link shared to a long chat stood twice: a stale copy with one tick above the delivered
    /// one, until the next reload.
    fileprivate var tableReadMessageCount: Int?
    /// What each of our messages showed the last time it was drawn, so a change of state cross-fades.
    fileprivate var statusMemory: [String: AirEntryStatus] = [:]
    /// Messages that rise into the log when their row appears, with the delay of each.
    fileprivate var arrivingIds: [String: TimeInterval] = [:]
    fileprivate var wasFriendOnline: Bool?
    fileprivate var airObservers: [NSObjectProtocol] = []

    fileprivate struct DisplayMapKey: Equatable {
        let loaded: Int
        let total: Int
        let query: String
        let generation: Int
    }

    fileprivate var chatInputViewManager: ChatInputViewManager!
    fileprivate let replyController = ChatReplyController()

    fileprivate var tableViewTapGestureRecognizer: UITapGestureRecognizer!

    fileprivate var tableViewToChatInputConstraint: Constraint!
    fileprivate var typingViewToChatInputConstraint: Constraint!

    fileprivate var newMessageViewTopConstraint: Constraint?
    fileprivate var chatInputViewBottomConstraint: Constraint?

    fileprivate var newMessagesViewVisible = false

    /// Index path for cell with UIMenu presented.
    fileprivate var selectedMenuIndexPath: IndexPath?

    /// KHANDAQ (#192): Telegram-style floating reaction bar shown above the message on long-press.
    fileprivate let reactionPopup = ChatReactionPopup()

    fileprivate let showKeyboardOnAppear: Bool
    fileprivate var disableNextInputViewAnimation = false

    init(theme: Theme, chat: OCTChat, submanagerChats: OCTSubmanagerChats, submanagerObjects: OCTSubmanagerObjects, submanagerFiles: OCTSubmanagerFiles, delegate: ChatPrivateControllerDelegate, showKeyboardOnAppear: Bool = false) {
        self.theme = theme
        self.chat = chat
        self.friend = chat.friends.lastObject() as? OCTFriend
        self.submanagerChats = submanagerChats
        self.submanagerObjects = submanagerObjects
        self.submanagerFiles = submanagerFiles
        self.delegate = delegate
        self.showKeyboardOnAppear = showKeyboardOnAppear

        let predicate = NSPredicate(format: "chatUniqueIdentifier == %@", chat.uniqueIdentifier)
        // KHANDAQ (#H5): sort by sortTimestamp (sender send-time, clamped), NOT dateInterval (local
        // arrival). A late/offline-flushed message then lands in its true chronological slot — matching
        // its displayed time — and the order is identical across devices.
        self.messages = submanagerObjects.messages(predicate: predicate).sortedResultsUsingProperty("sortTimestamp", ascending: false)
        self.visibleMessages = Constants.MessagesPortionSize

        self.timeFormatter = DateFormatter(type: .time)
        self.dateFormatter = DateFormatter()
        dateFormatter.dateFormat = "MMMdd"

        super.init()

        edgesForExtendedLayout = UIRectEdge()
        hidesBottomBarWhenPushed = true

        NotificationCenter.default.addObserver(
                self,
                selector: #selector(ChatPrivateController.applicationDidBecomeActive),
                name: NSNotification.Name.UIApplicationDidBecomeActive,
                object: nil)

        NotificationCenter.default.addObserver(
                self,
                selector: #selector(ChatPrivateController.willShowMenuNotification(_:)),
                name: NSNotification.Name.UIMenuControllerWillShowMenu,
                object: nil)
        NotificationCenter.default.addObserver(
                self,
                selector: #selector(ChatPrivateController.willHideMenuNotification),
                name: NSNotification.Name.UIMenuControllerWillHideMenu,
                object: nil)

        // KHANDAQ: keep the voice player UI (play/pause icon + progress) in sync — without this the
        // 1:1 / Saved Messages voice cells never refreshed, so tapping play looked like it did nothing.
        voicePlayerObserver = NotificationCenter.default.addObserver(
                forName: .chatVoiceMessagePlayerStateDidChange,
                object: nil,
                queue: .main) { [weak self] notification in
            self?.handleVoicePlayerStateChange(notification)
        }
    }

    deinit {
        NotificationCenter.default.removeObserver(self)
        if let voicePlayerObserver = voicePlayerObserver {
            NotificationCenter.default.removeObserver(voicePlayerObserver)
        }
        LocationSharingCoordinator.shared.detach(controller: self)

        messagesToken?.invalidate()
        friendToken?.invalidate()
        presenceRefreshTimer?.invalidate()
        callWaitingTimer?.invalidate()
        // KHANDAQ (leak): also invalidate the one-shot floating-date hide timer for consistency.
        floatingDateHideTimer?.invalidate()
        airObservers.forEach { NotificationCenter.default.removeObserver($0) }
    }

    required convenience init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func loadView() {
        // KHANDAQ (redesign stage 2): the transcript sits on the plain surface: no wallpaper, no bubbles.
        loadViewWithBackgroundColor(KQColor.surface)

        createTableView()
        createTableHeaderViews()
        createNewMessagesView()
        createFloatingDateView()
        createInputView()
        createEditMessageToolbar()
        createAirConversationViews()
        createEncryptionBanner()
        installConstraints()
    }

    // KHANDAQ design (Figma): encryption-info card pinned to the top of an empty chat.
    // KHANDAQ (redesign stage 2): a quiet sunken note under the header, in the tokens of the system.
    private func createEncryptionBanner() {
        let card = UIView()
        card.backgroundColor = KQColor.sunken
        card.layer.cornerRadius = KQRadius.card
        card.layer.masksToBounds = true
        card.isAccessibilityElement = true

        let badgeLabel = UILabel()
        badgeLabel.text = String(localized: "chat_encryption_badge")
        badgeLabel.font = KQFont.font(.label, weight: 600)
        badgeLabel.adjustsFontForContentSizeCategory = true
        badgeLabel.textColor = KQColor.ink
        badgeLabel.numberOfLines = 0
        card.addSubview(badgeLabel)

        let info = UILabel()
        info.text = String(localized: "chat_encryption_info")
        info.font = KQFont.font(.caption)
        info.adjustsFontForContentSizeCategory = true
        info.textColor = KQColor.ink2
        info.numberOfLines = 0
        card.addSubview(info)

        card.accessibilityLabel = [badgeLabel.text, info.text].compactMap { $0 }.joined(separator: ". ")

        view.addSubview(card)
        encryptionBanner = card

        badgeLabel.snp.makeConstraints {
            $0.top.equalTo(card).offset(KQSpace.x12)
            $0.leading.trailing.equalTo(card).inset(KQSpace.x12)
        }
        info.snp.makeConstraints {
            $0.top.equalTo(badgeLabel.snp.bottom).offset(KQSpace.x4)
            $0.leading.trailing.equalTo(card).inset(KQSpace.x12)
            $0.bottom.equalTo(card).offset(-KQSpace.x12)
        }
        card.snp.makeConstraints {
            $0.top.equalTo(topStack.snp.bottom).offset(KQSpace.x8)
            $0.leading.trailing.equalTo(view).inset(KQSpace.x16)
        }
    }

    private func updateEncryptionBannerVisibility() {
        encryptionBanner?.isHidden = messages.count > 0
    }

    override func viewDidLoad() {
        super.viewDidLoad()

        // KHANDAQ (Figma): capture the first unread incoming message BEFORE viewWillAppear's
        // updateLastReadDate marks the chat read. messages are sorted newest-first. The 2s epsilon
        // covers the chat-creation race: a brand-new chat stamps lastReadDateInterval at creation,
        // which lands microseconds AFTER the very first message — a strict compare then skipped it.
        let lastRead = chat.lastReadDateInterval - 2.0
        for i in 0..<messages.count {
            let message = messages[i]
            if message.dateInterval <= lastRead {
                break
            }
            if !message.isOutgoing() {
                unreadSeparatorMessageId = message.uniqueIdentifier
                pendingFirstUnreadScrollIndex = i
            }
        }

        replyController.restorePending(forChatId: chat.uniqueIdentifier, theme: theme)
        addMessagesNotification()
        updateEncryptionBannerVisibility()

        createNavigationViews()
        addFriendNotification()
        // KHANDAQ (redesign stage 2): search waits behind the button in the header (searchButtonPressed).
        startAirConversation()
    }

    fileprivate func setupMessageSearch() {
        let searchController = UISearchController(searchResultsController: nil)
        searchController.obscuresBackgroundDuringPresentation = false
        searchController.searchResultsUpdater = self
        searchController.delegate = self
        searchController.searchBar.placeholder = String(localized: "group_messages_search_placeholder")
        navigationItem.searchController = searchController
        // The message table is vertically flipped, which inverts the pull-to-reveal gesture. Keep the
        // search bar always visible so reaching search no longer depends on scroll direction.
        navigationItem.hidesSearchBarWhenScrolling = false
        definesPresentationContext = true
        messageSearchController = searchController
    }

    func sendLocationMessage(_ payload: String) {
        // KHANDAQ (#T2): don't gate on isActiveLocationSharingChat. The "+" menu shares location ONCE
        // via the coordinator's one-shot path (like groups), which never sets the continuous-sharing
        // pubkey — the old guard silently dropped every one-shot 1:1 location so nothing was sent. The
        // continuous path already checks the pubkey in didUpdateLocations before calling this.
        submanagerChats.sendMessage(to: chat, text: payload, type: .normal, successBlock: nil, failureBlock: nil)
    }

    fileprivate func startLocationSharing(sendImmediately: Bool) {
        guard friend != nil else {
            return
        }

        guard LocationManager.shared.hasUsableAuthorization() else {
            LocationManager.shared.requestAccessForUserInitiatedSharing { [weak self] granted in
                guard granted, let self = self else {
                    return
                }

                LocationSharingCoordinator.shared.start(for: self, sendImmediately: sendImmediately)
            }
            return
        }

        LocationSharingCoordinator.shared.start(for: self, sendImmediately: sendImmediately)
    }

    fileprivate func stopLocationSharing() {
        LocationSharingCoordinator.shared.stop()
    }

    fileprivate func populateTextModel(_ model: ChatBaseTextCellModel, text: String) {
        let parsed = MessageReplyHelper.parse(text)
        model.replyMeta = parsed.reply
        model.searchHighlight = messageSearchQuery.isEmpty ? nil : messageSearchQuery
        let body = parsed.bodyText

        if let location = LocationMessage.parse(body) {
            model.locationLatitude = location.latitude
            model.locationLongitude = location.longitude
            model.message = String(format: "%.5f, %.5f", location.latitude, location.longitude)
        }
        else {
            model.locationLatitude = nil
            model.locationLongitude = nil
            // KHANDAQ (#iOS forward): clean stacked/legacy "↪ Переслано от (null)" headers at display time
            // (incoming cross-platform / pre-fix forwards can't be re-sent). No-op for non-forward bodies.
            model.message = MessageReplyHelper.normalizeForwardedHeader(body)
        }
    }

    fileprivate func attachReplyQuoteHandler(to model: ChatBaseTextCellModel) {
        guard let meta = model.replyMeta else {
            model.onReplyQuoteTap = nil
            return
        }
        model.onReplyQuoteTap = { [weak self] in
            guard let self = self, let tableView = self.tableView else {
                return
            }
            self.replyController.scrollToReplyTarget(meta,
                                                     messages: self.messages,
                                                     tableView: tableView,
                                                     submanagerObjects: self.submanagerObjects,
                                                     displayRow: { [weak self] storageIndex in
                                                         self?.displayIndex(forStorageIndex: storageIndex)
                                                     },
                                                     ensureLoaded: { [weak self] targetIndex in
                                                         guard let self = self else { return }
                                                         self.visibleMessages = min(self.messages.count, targetIndex + 30)
                                                         self.tableView?.reloadData()
                                                     })
        }
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)

        // KHANDAQ (Figma): Telegram-style header — the back button is a bare "‹" chevron (no "Чаты"
        // text), so the left-aligned avatar+name has room. Set on the previous view controller (the
        // one that pushed us) so its title collapses to just the chevron here.
        if #available(iOS 14.0, *) {
            let stack = navigationController?.viewControllers ?? []
            if stack.count >= 2 {
                stack[stack.count - 2].navigationItem.backButtonDisplayMode = .minimal
            }
            // The screens pushed from here (Attachments, the dossier) show only the chevron too: the
            // conversation has no title of its own, so UIKit put the system "Back" there, which does
            // not follow the in-app language.
            navigationItem.backButtonDisplayMode = .minimal
        }

        updateLastReadDate()
        delegate?.chatPrivateControllerWillAppear(self)
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)

        // KHANDAQ: mark read on EXIT too — viewWillAppear alone misses messages that arrived while the
        // chat was open, leaving the unread badge stuck after the user has actually read everything.
        updateLastReadDate()
        // KHANDAQ (#G7-lock, review): a slide-up-locked recording survives a finger-lift, so leaving the
        // chat would otherwise leave it running off-screen (AVAudioSession held + temp file orphaned).
        chatInputView?.abortActiveRecordingIfNeeded()
        chatInputViewManager?.endUserInteraction()
        replyController.savePending(forChatId: chat.uniqueIdentifier)
        delegate?.chatPrivateControllerWillDisappear(self)
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)

        if isActiveLocationSharingChat {
            LocationSharingCoordinator.shared.resume(for: self)
        }

        if showKeyboardOnAppear {
            disableNextInputViewAnimation = true
            _ = chatInputView.becomeFirstResponder()
        }

        scrollToGlobalSearchTargetIfNeeded()
        scrollToFirstUnreadIfNeeded()
        refreshPinnedBanner()   // KHANDAQ (#G5): restore the pinned-message banner if any.
    }

    /// KHANDAQ (Figma): on open, jump to the first unread incoming message (its cell carries the
    /// "Непрочитанные сообщения" band). One-shot per controller; a global-search jump wins.
    func scrollToFirstUnreadIfNeeded() {
        guard !didScrollToFirstUnread, let index = pendingFirstUnreadScrollIndex, let tableView = tableView else {
            return
        }

        didScrollToFirstUnread = true

        if index >= visibleMessages {
            visibleMessages = min(messages.count, index + Constants.MessagesPortionSize)
            tableView.reloadData()
        }
        tableView.layoutIfNeeded()

        // KHANDAQ (redesign stage 2): the index is a storage index; the log can leave messages out.
        guard let row = displayIndex(forStorageIndex: index) else {
            return
        }

        DispatchQueue.main.async {
            tableView.scrollToRow(at: IndexPath(row: row, section: 0), at: .middle, animated: false)
        }
    }

    /// KHANDAQ (#64): if this chat was opened from a global-search message hit, scroll to + briefly
    /// highlight that message.
    func scrollToGlobalSearchTargetIfNeeded() {
        guard let messageId = GlobalSearchScrollTarget.take(forChatId: chat.uniqueIdentifier),
              let tableView = tableView else {
            return
        }

        // KHANDAQ (Figma): the search jump takes priority over the first-unread jump.
        didScrollToFirstUnread = true

        // KHANDAQ (#7): the target may be older than the currently loaded window — the chat paginates
        // and only `visibleMessages` rows are loaded. Find its storage index and expand the window to
        // include it before looking up the display row, otherwise the scroll silently no-ops.
        var storageIndex: Int?
        for i in 0..<messages.count where messages[i].uniqueIdentifier == messageId {
            storageIndex = i
            break
        }
        guard let targetStorageIndex = storageIndex else {
            return
        }
        if targetStorageIndex >= visibleMessages {
            visibleMessages = min(messages.count, targetStorageIndex + Constants.MessagesPortionSize)
            tableView.reloadData()
            tableView.layoutIfNeeded()
        }

        tableView.layoutIfNeeded()

        let rows = tableView.numberOfRows(inSection: 0)
        for row in 0..<rows {
            guard messageEntry(atDisplayIndex: row).message.uniqueIdentifier == messageId else {
                continue
            }
            let indexPath = IndexPath(row: row, section: 0)
            // KHANDAQ (#126): defer the scroll one runloop so it runs AFTER viewDidAppear's layout pass
            // settles the table's content size. Scrolling inline here computed offsets against a
            // not-yet-final layout, so the jump silently landed short or no-opped.
            DispatchQueue.main.async {
                tableView.scrollToRow(at: indexPath, at: .middle, animated: true)
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) {
                    guard let cell = tableView.cellForRow(at: indexPath) else {
                        return
                    }
                    UIView.animate(withDuration: 0.25, animations: {
                        cell.backgroundColor = UIColor.systemYellow.withAlphaComponent(0.25)
                    }, completion: { _ in
                        UIView.animate(withDuration: 0.4) { cell.backgroundColor = .clear }
                    })
                }
            }
            return
        }
    }

    override func keyboardWillShowAnimated(keyboardFrame frame: CGRect) {
        super.keyboardWillShowAnimated(keyboardFrame: frame)
        trayView?.hidesDetail = true
        // At the accessibility text sizes the presence band and the tray fold away while the keyboard is
        // up: the hint under the field says the same, and the log keeps some room (at AX5 it had none).
        isKeyboardUp = true
        keepRoomForLog()

        guard let constraint = chatInputViewBottomConstraint else {
            return
        }

        constraint.update(offset: -frame.size.height)

        if disableNextInputViewAnimation {
            disableNextInputViewAnimation = false
            suppressNextKeyboardAnimation = true
        }
    }

    override func keyboardWillHideAnimated(keyboardFrame frame: CGRect) {
        super.keyboardWillHideAnimated(keyboardFrame: frame)
        trayView?.hidesDetail = false
        isKeyboardUp = false
        keepRoomForLog()

        guard let constraint = chatInputViewBottomConstraint else {
            return
        }

        // TODO: this moves the input view a bit more to the top, because the home button "line" is in the way otherwise
        //       please fix me properly in the future
        constraint.update(offset: 0.0)
        if #available(iOS 11.0, *) {
            let keyWindow = UIApplication.shared.keyWindow
            let b = keyWindow?.safeAreaInsets.bottom
            constraint.update(offset: -(b ?? 20))
        }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()

        updateInputViewMaxHeight()
        updateTitleWidth()
        keepRoomForLog(whileLayingOut: true)
    }
}

// MARK: Actions
extension ChatPrivateController {
    @objc func tapOnTableView() {
        _ = chatInputView.resignFirstResponder()
    }

    @objc func panOnTableView(_ recognizer: UIPanGestureRecognizer) {
        guard let tableView = tableView else {
            return
        }

        if (UserDefaultsManager().DateonmessageMode == true) {
            return
        }

        let translation = recognizer.translation(in: recognizer.view)
        recognizer.setTranslation(CGPoint.zero, in: recognizer.view)

        _ = tableView.visibleCells.filter {
            $0 is ChatMovableDateCell
        }.map {
            $0 as! ChatMovableDateCell
        }.map {
            switch recognizer.state {
                case .possible:
                    fallthrough
                case .began:
                    // nop
                    break
                case .changed:
                    $0.movableOffset += translation.x
                case .ended:
                    fallthrough
                case .cancelled:
                    fallthrough
                case .failed:
                    let cell = $0
                    UIView.animate(withDuration: Constants.ResetPanAnimationDuration, animations: {
                        cell.movableOffset = 0.0
                    }) 
            }
        }
    }

    @objc func newMessagesViewPressed() {
        scrollToNewestMessage()
    }

    // KHANDAQ (#119): jump to the newest message (row 0 in the inverted table).
    func scrollToNewestMessage(animated: Bool = true) {
        guard let tableView = tableView else {
            return
        }
        // KHANDAQ: nothing to scroll to in an empty chat — scrollToRow(row:0) on 0 rows crashes.
        guard tableView.numberOfRows(inSection: 0) > 0 else {
            return
        }

        tableView.setContentOffset(CGPoint.zero, animated: animated)

        // iOS is broken =\
        // See https://stackoverflow.com/a/30804874
        let delayTime = DispatchTime.now() + Double(Int64(0.2 * Double(NSEC_PER_SEC))) / Double(NSEC_PER_SEC)
        DispatchQueue.main.asyncAfter(deadline: delayTime) { [weak self] in
            guard let tableView = self?.tableView, tableView.numberOfRows(inSection: 0) > 0 else {
                return
            }
            tableView.scrollToRow(at: IndexPath(row: 0, section: 0), at: .top, animated: animated)
        }
    }

    func messageBox(messageTitle: String, messageAlert: String, messageBoxStyle: UIAlertControllerStyle, alertActionStyle: UIAlertActionStyle, completionHandler: @escaping () -> Void)
        {
            let alert = UIAlertController(title: messageTitle, message: messageAlert, preferredStyle: messageBoxStyle)

            let okAction = UIAlertAction(title: "Cancel", style: alertActionStyle) { _ in
                completionHandler() // This will only get called after okay is tapped in the alert
            }

            alert.addAction(okAction)
            present(alert, animated: true, completion: nil)
        }

    @objc func attachmentsButtonPressed() {
        delegate?.chatPrivateControllerShowAttachments(self, forChat: chat)
    }

    @objc func audioCallButtonPressed() {
        requestCall(video: false)
    }
    
    @objc func displayalert() {
        var alert = UIAlertController(title: "Location Sharing", message: "Would you like to enable location sharing with this contact?\nYou can disable this feature by clicking on the location icon after it has been enabled.", preferredStyle: UIAlertControllerStyle.alert)
        var action_title = "Enable"

        if (AppDelegate.location_sharing_contact_pubkey != "-1")
        {
            alert = UIAlertController(title: "Location Sharing", message: "Disable sharing with this contact " + AppDelegate.location_sharing_contact_pubkey + " ?" , preferredStyle: UIAlertControllerStyle.alert)
            action_title = "Disable"
        }

        alert.addAction((UIAlertAction(title: action_title, style: .default, handler: { [self] (action) -> Void in
            if (action_title == "Disable")
            {
                AppDelegate.location_sharing_contact_pubkey = "-1"
                self.stopLocationSharing()
                let locationImage = UIImage(named: "location-call-medium")!.withRenderingMode(.alwaysOriginal)
                locationButton.setBackgroundImage(locationImage, for: .normal, barMetrics: .default)
            }
            else
            {
                AppDelegate.location_sharing_contact_pubkey = self.friend?.publicKey ?? "-1"
                let locationImage = UIImage(named: "location-call-activated-medium")!.withRenderingMode(.alwaysOriginal)
                locationButton.setBackgroundImage(locationImage, for: .normal, barMetrics: .default)
                self.startLocationSharing(sendImmediately: true)
            }
            alert.dismiss(animated: true, completion: nil)
        })))

        alert.addAction(
            UIAlertAction(title: "Cancel", style: .cancel, handler: { (action) -> Void in
                alert.dismiss(animated: true, completion: nil)
            }))

        self.present(alert, animated: true, completion: nil)
    }

    @objc func videoCallButtonPressed() {
        requestCall(video: true)
    }

    @objc func locationButtonPressed() {
        displayalert()
    }

    @objc func editMessagesDeleteButtonPressed(_ barButtonItem: UIBarButtonItem) {
        guard let selectedRows = tableView?.indexPathsForSelectedRows else {
            return
        }

        // KHANDAQ (audit #3): $0.row is a DISPLAY index; when a message-search filter is active it
        // differs from the storage index, so map through the display→storage translation (as the group
        // controller does) — otherwise «Удалить у всех»/forward would target an unrelated message.
        let toRemove = selectedRows.map { self.messageEntry(atDisplayIndex: $0.row).message }
        let hasOwn = toRemove.contains { $0.isOutgoing() }

        // KHANDAQ (#205): scope choice for the multi-select delete (mirrors the single-message path).
        showDeleteScopeConfirmation(messagesCount: selectedRows.count,
                                    canDeleteForEveryone: hasOwn,
                                    barButtonItem: barButtonItem,
                                    sourceView: nil,
                                    deleteForMe: { [unowned self] in
            self.toggleTableViewEditing(false, animated: true)
            self.submanagerChats.removeMessages(toRemove)
        }, deleteForEveryone: { [unowned self] in
            self.toggleTableViewEditing(false, animated: true)
            // Own messages are retracted on the peer (KQ delete packet); incoming ones fall back to
            // plain local removal inside deleteMessageForBoth.
            for message in toRemove {
                self.submanagerChats.deleteMessage(forBoth: message)
            }
        })
    }

    // KHANDAQ (#G3): forward every selected message into one chosen chat (chronological order).
    @objc func editMessagesForwardButtonPressed(_ barButtonItem: UIBarButtonItem) {
        guard let selectedRows = tableView?.indexPathsForSelectedRows, !selectedRows.isEmpty else {
            return
        }
        // KHANDAQ (audit #3): display→storage translation (see delete path) so forward sends the
        // actually-selected messages even while a search filter is active.
        let ordered = selectedRows.sorted { $0.row < $1.row }.map { self.messageEntry(atDisplayIndex: $0.row).message }
        let source: UIView = editMessagesToolbar ?? view
        toggleTableViewEditing(false, animated: true)
        MessageForwarder.presentForwardPicker(forMessages: ordered, from: self, sourceView: source)
    }

    @objc func deleteAllMessagesButtonPressed(_ barButtonItem: UIBarButtonItem) {
        toggleTableViewEditing(false, animated: true)

        showMessageDeletionConfirmation(messagesCount: messages.count,
                                        showFromItem: barButtonItem,
                                        deleteClosure: { [unowned self] in
            self.submanagerChats.removeAllMessages(in: self.chat, removeChat: false)
        })
    }

    @objc func cancelEditingButtonPressed() {
        toggleTableViewEditing(false, animated: true)
    }
}

// MARK: Notifications
extension ChatPrivateController {
    @objc func applicationDidBecomeActive() {
        updateLastReadDate()
    }

    @objc func willShowMenuNotification(_ notification: Notification) {
        guard let indexPath = selectedMenuIndexPath else {
            return
        }
        guard let cell = tableView?.cellForRow(at: indexPath) else {
            return
        }
        guard let editable = cell as? ChatEditable else {
            return
        }
        guard let menu = notification.object as? UIMenuController else {
            return
        }

        NotificationCenter.default.removeObserver(self, name: NSNotification.Name.UIMenuControllerWillShowMenu, object: nil)

        menu.setMenuVisible(false, animated: false)

        let rect = cell.convert(editable.menuTargetRect(), to: view)

        menu.setTargetRect(rect, in: view)
        menu.setMenuVisible(true, animated: true)

        NotificationCenter.default.addObserver(
                self,
                selector: #selector(ChatPrivateController.willShowMenuNotification(_:)),
                name: NSNotification.Name.UIMenuControllerWillShowMenu,
                object: nil)
    }

    @objc func willHideMenuNotification() {
        // KHANDAQ (#192): keep the floating reaction bar in sync with the action menu.
        if reactionPopup.isVisible {
            reactionPopup.dismiss(animated: true)
        }
        guard let indexPath = selectedMenuIndexPath else {
            return
        }
        selectedMenuIndexPath = nil

        guard let editable = tableView?.cellForRow(at: indexPath) as? ChatEditable else {
            return
        }

        editable.willHideMenu()
    }
}

// KHANDAQ (#T2): conform to LocationSharingChat so the "+" menu can share once via the coordinator's
// one-shot path (like groups). sendLocationMessage(_:) is implemented in the main class body above.
extension ChatPrivateController: LocationSharingChat {}

extension ChatPrivateController: UITableViewDataSource {
    func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        tableReadMessageCount = messages.count
        let entry = messageEntry(atDisplayIndex: indexPath.row)
        let message = entry.message

        // Telegram-style: a caption text merged into its file bubble has no row of its own.
        if messageSearchQuery.isEmpty, isMergedCaptionRow(storageIndex: entry.storageIndex) {
            let blank = UITableViewCell()
            blank.backgroundColor = .clear
            blank.contentView.backgroundColor = .clear
            blank.selectionStyle = .none
            blank.isUserInteractionEnabled = false
            return blank
        }

        // setting default values to avoid crash
        var model: ChatMovableDateCellModel  = ChatMovableDateCellModel()
        var cell: ChatMovableDateCell  = tableView.dequeueReusableCell(withIdentifier: ChatMovableDateCell.staticReuseIdentifier) as! ChatMovableDateCell

        if message.isOutgoing() {
            if let messageText = message.messageText {
                // KHANDAQ (redesign stage 2): a transcript entry (AirEntryTextCell), not a bubble.
                let outgoingModel = AirEntryTextCellModel()

                if (UserDefaultsManager().DebugMode == false) {
                    populateTextModel(outgoingModel, text: messageText.text ?? "")
                } else {
                    let s1 = (messageText.text ?? "")
                    let s2 = (messageText.msgv3HashHex ?? "")
                    let s3 = (message.senderUniqueIdentifier ?? "")
                    let s4 = (message.chatUniqueIdentifier )
                    let s5 = String(messageText.isDelivered)
                    let s6 = String(messageText.sentPush)
                    let s7 = String(message.tssent)
                    let s8 = String(message.tsrcvd)
                    let s9 = String(message.dateInterval)
                    outgoingModel.message =  s1 + "\n"
                                               + "msgv3HashHex:\n" + s2 + "\n"
                                               + "senderUniqueIdentifier:\n" + s3 + "\n"
                                               + "chatUniqueIdentifier:\n" + s4 + "\n"
                                               + "isDelivered:\n" + s5 + "\n"
                                               + "sentPush:\n" + s6 + "\n"
                                               + "tssent:\n" + s7 + "\n"
                                               + "tsrcvd:\n" + s8 + "\n"
                                               + "dateInterval:\n" + s9 + "\n"
                }

                outgoingModel.isOutgoing = true
                outgoingModel.status = AirEntryStatus.ofOutgoingText(isSaved: chat.isSavedMessages,
                                                                     isDelivered: messageText.isDelivered,
                                                                     messageId: messageText.messageId,
                                                                     personOnline: isFriendOnline)
                if let id = message.uniqueIdentifier {
                    outgoingModel.previousStatus = statusMemory[id]
                    statusMemory[id] = outgoingModel.status
                }
                outgoingModel.reactionsDisplay = ChatReactionsFormat.display(from: message.reactionsJSON)
                attachReplyQuoteHandler(to: outgoingModel)

                // KHANDAQ (#208): own text with a msgV3 hash is editable — but not replies (would
                // corrupt the reply wire header, matching Android) nor location messages.
                // KHANDAQ: Saved Messages rows never get a msgV3 hash (no peer to ACK), so gating Edit on a
                // 64-char hash hid the Edit action there. Saved edits are local-only, so allow them regardless.
                let editHashOK = chat.isSavedMessages || (messageText.msgv3HashHex ?? "").count == 64
                outgoingModel.canEdit = editHashOK && outgoingModel.replyMeta == nil && !outgoingModel.hasLocation
                // KHANDAQ (#H2): the «изменено» marker is rendered as a small dim suffix by the cell
                // (see ChatBaseTextCell), NOT appended to the text — so it reads as a system note, not
                // as if the sender typed it.
                outgoingModel.edited = message.edited

                model = outgoingModel

                cell = tableView.dequeueReusableCell(withIdentifier: AirEntryTextCell.staticReuseIdentifier) as! AirEntryTextCell
            }
            else if let messageCall = message.messageCall {
                let outgoingModel = ChatOutgoingCallCellModel()
                outgoingModel.callDuration = messageCall.callDuration
                outgoingModel.answered = (messageCall.callEvent == .answered)
                model = outgoingModel

                cell = tableView.dequeueReusableCell(withIdentifier: ChatOutgoingCallCell.staticReuseIdentifier) as! ChatOutgoingCallCell
            }
            else if let _ = message.messageFile {
                (model, cell) = imageCellWithMessage(message, incoming: false)
            }
        }
        else {
            if let messageText = message.messageText {
                let incomingModel = AirEntryTextCellModel()

                if (UserDefaultsManager().DebugMode == false) {
                    populateTextModel(incomingModel, text: messageText.text ?? "")
                } else {
                    let s1 = (messageText.text ?? "")
                    let s2 = (messageText.msgv3HashHex ?? "")
                    let s3 = (message.senderUniqueIdentifier ?? "")
                    let s4 = (message.chatUniqueIdentifier)
                    let s5 = String(messageText.isDelivered)
                    let s6 = String(message.tssent)
                    let s7 = String(message.tsrcvd)
                    let s8 = String(message.dateInterval)
                    incomingModel.message = "" + s1 + "\n"
                                               + "msgv3HashHex:\n" + s2 + "\n"
                                               + "senderUniqueIdentifier:\n" + s3 + "\n"
                                               + "chatUniqueIdentifier:\n" + s4 + "\n"
                                               + "isDelivered:\n" + s5 + "\n"
                                               + "tssent:\n" + s6 + "\n"
                                               + "tsrcvd:\n" + s7 + "\n"
                                               + "dateInterval:\n" + s8 + "\n"
                }

                incomingModel.reactionsDisplay = ChatReactionsFormat.display(from: message.reactionsJSON)
                attachReplyQuoteHandler(to: incomingModel)

                // KHANDAQ (#208/#H2): the peer can edit their own message → the cell renders a small dim
                // «изменено» marker (never editable by us; not appended to the text).
                incomingModel.edited = message.edited

                model = incomingModel

                cell = tableView.dequeueReusableCell(withIdentifier: AirEntryTextCell.staticReuseIdentifier) as! AirEntryTextCell
            }
            else if let messageCall = message.messageCall {
                let incomingModel = ChatIncomingCallCellModel()
                incomingModel.callDuration = messageCall.callDuration
                incomingModel.answered = (messageCall.callEvent == .answered)
                model = incomingModel

                cell = tableView.dequeueReusableCell(withIdentifier: ChatIncomingCallCell.staticReuseIdentifier) as! ChatIncomingCallCell
            }
            else if let _ = message.messageFile {
                (model, cell) = imageCellWithMessage(message, incoming: true)
            }
        }

        var incoming_text_message: Bool = false
        if (!message.isOutgoing()) {
            if let messageText = message.messageText {
                incoming_text_message = true
            }
        }

        // KHANDAQ design (Figma): message timestamps show the time only (no per-message date stamp,
        // which rendered as a cramped second line like "июня22").
        if (incoming_text_message) {
            if (message.tssent == 0) {
                model.dateString = timeFormatter.string(from: message.date())
            } else {
               let real_datetime = Date(timeIntervalSince1970: TimeInterval(message.tssent))
                model.dateString = timeFormatter.string(from: real_datetime)
            }
        } else {
            model.dateString = timeFormatter.string(from: message.date())
        }

        markTranscriptEntry(model, message: message, row: indexPath.row)

        cell.delegate = self
        cell.replySwipeDelegate = self
        cell.setupWithTheme(theme, model: model)
        cell.transform = tableView.transform;

        return cell
    }

    func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        tableReadMessageCount = messages.count
        return displayableRowCount()
    }
}

extension ChatPrivateController: UITableViewDelegate {
    /// KHANDAQ (#99): day-separator label for the row that begins a calendar day's block — its OLDEST
    /// message, i.e. the one whose next-older neighbour (display index + 1, newest-first order) falls on
    /// a different day, or the very oldest row. nil mid-day or while searching (filtered subset).
    func daySeparatorString(forDisplayIndex row: Int) -> String? {
        guard messageSearchQuery.isEmpty, row >= 0, row < displayableRowCount() else {
            return nil
        }
        let calendar = Calendar.current
        let message = messageEntry(atDisplayIndex: row).message
        let day = calendar.startOfDay(for: message.date())
        let nextRow = row + 1
        if nextRow < displayableRowCount() {
            let older = messageEntry(atDisplayIndex: nextRow).message
            if calendar.startOfDay(for: older.date()) == day {
                return nil
            }
        }
        // KHANDAQ (redesign stage 2): "Today", "Yesterday" or the date, as the transcript marks a day.
        return AirDayMark.text(for: message.date(), locale: AppLanguage.locale, calendar: calendar)
    }

    func tableView(_ tableView: UITableView, heightForRowAt indexPath: IndexPath) -> CGFloat {
        tableReadMessageCount = messages.count
        let entry = messageEntry(atDisplayIndex: indexPath.row)

        // Merged caption rows are not shown on their own.
        if messageSearchQuery.isEmpty, isMergedCaptionRow(storageIndex: entry.storageIndex) {
            return 0.0
        }

        // KHANDAQ (redesign stage 3, E8): a photo or video entry sizes itself like every other row; its
        // preview box has its size from the file before the image loads (prepareFileCell).
        return UITableViewAutomaticDimension
    }

    @objc func handleVoicePlayerStateChange(_ notification: Notification) {
        guard let state = notification.userInfo?["state"] as? ChatVoiceMessagePlayerState else {
            return
        }
        refreshVisibleVoiceCells(for: state.messageId)
    }

    func refreshVisibleVoiceCells(for messageId: String) {
        guard let tableView = tableView else {
            return
        }

        for cell in tableView.visibleCells {
            guard let fileCell = cell as? ChatGenericFileCell,
                  let indexPath = tableView.indexPath(for: cell) else {
                continue
            }

            let message = messageEntry(atDisplayIndex: indexPath.row).message
            guard message.uniqueIdentifier == messageId,
                  message.messageFile != nil else {
                continue
            }

            // KHANDAQ (#36): handle-preserving refresh (see refreshVoicePlaybackState).
            fileCell.refreshVoicePlaybackState(theme: theme)
        }
    }

    /// The size of the preview of a photo or a video that can be shown inline: ours, or theirs once it
    /// is here, and not larger than an inline preview may be.
    func inlinePreviewSize(for message: OCTMessageAbstract) -> CGSize? {
        guard let messageFile = message.messageFile,
              message.isOutgoing() || messageFile.fileType == .ready,
              let file = messageFile.filePath(),
              messageFile.fileSize < Constants.MaxImageSizeToShowInline else {
            return nil
        }
        return inlineMediaSize(forFileAt: file, messageFile: messageFile)
    }

    /// Pixel size of an inline photo/video, or nil if not displayable media. Probes the file directly
    /// when the UTI/extension is missing so a downloaded video on the receiver gets the right aspect.
    func inlineMediaSize(forFileAt file: String, messageFile: OCTMessageFile) -> CGSize? {
        let uti = inferredFileUTI(for: messageFile)

        // CRITICAL (real-device crash): voice/audio is never inline pixel media. Bail BEFORE any probe.
        // This runs in heightForRowAt on the MAIN thread; the old fallback ran the deprecated synchronous
        // AVURLAsset.tracks for every voice (.m4a) cell, which blocks (and on iOS 26 hangs → watchdog
        // kill) — so any chat containing voice notes crashed on a real device (sims rarely have voice).
        if UTTypeConformsTo(uti as CFString? ?? "" as CFString, kUTTypeAudio)
            || VoiceMessageHelper.isVoiceMessage(fileName: messageFile.fileName, filePath: file) {
            return nil
        }

        let isImage = UTTypeConformsTo(uti as CFString? ?? "" as CFString, kUTTypeImage)
        let isVideo = ChatFileMediaLoader.isVideoFile(uti: uti, fileName: messageFile.fileName)

        if isImage {
            return mediaPixelSize(forFileAt: file, isVideo: false) ?? CGSize(width: 4, height: 3)
        }
        if isVideo {
            return mediaPixelSize(forFileAt: file, isVideo: true) ?? CGSize(width: 4, height: 3)
        }
        // Cheap, safe header read (CGImageSource on a non-image just returns nil).
        if let imageSize = mediaPixelSize(forFileAt: file, isVideo: false) {
            return imageSize
        }
        // Video probe only for a genuinely unknown type (received video with no extension).
        if uti == nil, let videoSize = mediaPixelSize(forFileAt: file, isVideo: true) {
            return videoSize
        }
        return nil
    }

    // MARK: - Telegram-style media captions

    /// The caption text paired with the file at `storageIndex` (the message sent right after it).
    /// KHANDAQ (#45): a caption is either flagged by the sender (isCaption) OR — for a RECEIVED message,
    /// where isCaption is never set, so the caption used to render as a standalone bubble under the
    /// media — inferred by adjacency: a plain text immediately newer than the file, same direction,
    /// within 5s, that is not a system or shared-location message. (Same chat is implicit: both rows
    /// come from this chat's `messages`.)
    func isCaption(_ caption: OCTMessageAbstract, forFile file: OCTMessageAbstract) -> Bool {
        if caption.isCaption {
            return true
        }
        guard caption.messageFile == nil,
              !caption.groupSystemMessage,
              let text = caption.messageText?.text, !text.isEmpty,
              caption.isOutgoing() == file.isOutgoing(),
              LocationMessage.parse(text) == nil else {
            return false
        }
        let delta = caption.dateInterval - file.dateInterval
        return delta >= 0 && delta <= 5.0
    }

    /// KHANDAQ (#122): true only if the file message is actually rendered inline (downloaded/ready,
    /// recognized media, within the inline size limit), as inlinePreviewSize decides. A caption is
    /// merged into its file ONLY when this holds; otherwise the caption would vanish behind a failed /
    /// generic-file bubble.
    func isFileDisplayedInline(_ fileMessage: OCTMessageAbstract) -> Bool {
        guard let messageFile = fileMessage.messageFile,
              fileMessage.isOutgoing() || messageFile.fileType == .ready,
              let path = messageFile.filePath(),
              messageFile.fileSize < Constants.MaxImageSizeToShowInline,
              inlineMediaSize(forFileAt: path, messageFile: messageFile) != nil else {
            return false
        }
        return true
    }

    func captionMessage(forFileAtStorageIndex storageIndex: Int) -> OCTMessageAbstract? {
        guard storageIndex >= 0, storageIndex < messages.count else {
            return nil
        }
        let file = messages[storageIndex]
        guard file.messageFile != nil, isFileDisplayedInline(file) else {
            return nil
        }
        let captionIndex = storageIndex - 1   // newer message (DESC order) = sent just after the file
        guard captionIndex >= 0 else {
            return nil
        }
        let caption = messages[captionIndex]
        guard let text = caption.messageText?.text, !text.isEmpty,
              isCaption(caption, forFile: file) else {
            return nil
        }
        return caption
    }

    /// True if an incremental change touches a media caption (file row whose height depends on a
    /// separate caption message), so the table should reload rather than animate.
    func changeInvolvesCaption(insertions: [Int], modifications: [Int]) -> Bool {
        for index in insertions + modifications {
            guard index >= 0, index < messages.count else {
                continue
            }
            let message = messages[index]
            if message.isCaption {
                return true
            }
            // KHANDAQ (#45): a receiver-inferred caption carries no isCaption flag — detect it so the
            // table reloads (rather than animates) and the neighbouring file row's height updates to
            // embed the caption instead of leaving it stale until the next scroll.
            if isMergedCaptionRow(storageIndex: index) {
                return true
            }
            if message.messageFile != nil, captionMessage(forFileAtStorageIndex: index) != nil {
                return true
            }
        }
        return false
    }

    /// True if the message at `storageIndex` is a caption that is merged into its file (no own row).
    func isMergedCaptionRow(storageIndex: Int) -> Bool {
        guard storageIndex >= 0, storageIndex < messages.count else {
            return false
        }
        let message = messages[storageIndex]
        guard message.messageText?.text?.isEmpty == false else {
            return false
        }
        let fileIndex = storageIndex + 1   // older message (DESC order) = the file it captions
        guard fileIndex < messages.count else {
            return false
        }
        let file = messages[fileIndex]
        // KHANDAQ (#122): merge the caption ONLY if its file is actually shown inline. If the photo
        // failed to download / renders as a generic file, show the caption as a normal message instead.
        return file.messageFile != nil && isCaption(message, forFile: file) && isFileDisplayedInline(file)
    }

    func inferredFileUTI(for messageFile: OCTMessageFile) -> String? {
        if let uti = messageFile.fileUTI, !uti.isEmpty {
            return uti
        }
        guard let fileName = messageFile.fileName else {
            return nil
        }
        let ext = (fileName as NSString).pathExtension
        guard !ext.isEmpty,
              let uti = UTTypeCreatePreferredIdentifierForTag(kUTTagClassFilenameExtension, ext as CFString, nil)?.takeRetainedValue() as String? else {
            return nil
        }
        return uti
    }

    func mediaPixelSize(forFileAt path: String, isVideo: Bool) -> CGSize? {
        if let cached = mediaPixelSizeCache.object(forKey: path as NSString) {
            let size = cached.cgSizeValue
            return size.width > 0 && size.height > 0 ? size : nil
        }

        var result: CGSize = .zero
        let url = URL(fileURLWithPath: path)

        if isVideo {
            let asset = AVURLAsset(url: url)
            if let track = asset.tracks(withMediaType: .video).first {
                let transformed = track.naturalSize.applying(track.preferredTransform)
                result = CGSize(width: abs(transformed.width), height: abs(transformed.height))
            }
        }
        else if let source = CGImageSourceCreateWithURL(url as CFURL, nil),
                let props = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
                let width = props[kCGImagePropertyPixelWidth] as? CGFloat,
                let height = props[kCGImagePropertyPixelHeight] as? CGFloat {
            result = CGSize(width: width, height: height)
        }

        guard result.width > 0, result.height > 0 else {
            return nil
        }
        mediaPixelSizeCache.setObject(NSValue(cgSize: result), forKey: path as NSString)
        return result
    }

    func tableView(_ tableView: UITableView, willDisplay cell: UITableViewCell, forRowAt indexPath: IndexPath) {
        if indexPath.row == 0 {
            toggleNewMessageView(show: false)
        }

        // KHANDAQ (redesign stage 2): a message that just arrived or just left the tray rises into place.
        if !arrivingIds.isEmpty, let entryCell = cell as? AirEntryTextCell,
           let id = messageEntry(atDisplayIndex: indexPath.row).message.uniqueIdentifier,
           let delay = arrivingIds.removeValue(forKey: id) {
            entryCell.animateArrival(delay: delay)
        }

        maybeLoadImageForCellAtPath(cell, indexPath: indexPath)
    }

    func tableView(_ tableView: UITableView, shouldShowMenuForRowAt indexPath: IndexPath) -> Bool {
        // KHANDAQ (#G5): iOS 13+ uses the native vertical context menu (contextMenuConfigurationForRowAt,
        // Telegram-style) — suppress the legacy horizontal UIMenuController there.
        if #available(iOS 13.0, *) {
            return false
        }
        guard !tableView.isEditing else {
            return false
        }
        guard let editable = tableView.cellForRow(at: indexPath) as? ChatEditable else {
            return false
        }

        if !editable.shouldShowMenu() {
            return false
        }

        selectedMenuIndexPath = indexPath
        editable.willShowMenu()

        return true
    }

    func tableView(_ tableView: UITableView, canPerformAction action: Selector, forRowAt indexPath: IndexPath, withSender sender: Any?) -> Bool {
        guard let cell = tableView.cellForRow(at: indexPath) as? ChatMovableDateCell else {
            return false
        }

        return cell.isMenuActionSupportedByCell(action)
    }

    func tableView(_ tableView: UITableView, performAction action: Selector, forRowAt indexPath: IndexPath, withSender sender: Any?) {
        // Dummy method to make tableView:shouldShowMenuForRowAtIndexPath: work.
    }

    // KHANDAQ (#G5 reaction-row): the system context menu can't host a floating reaction row, so 1:1
    // long-press is served by the custom unified popup (longPressOnTableView → presentContextPopup:
    // reaction bar + action list together). Returning nil here disables the system menu so the two
    // don't both fire on the same long-press. iOS 12 still uses the legacy UIMenuController path.
    @available(iOS 13.0, *)
    func tableView(_ tableView: UITableView, contextMenuConfigurationForRowAt indexPath: IndexPath, point: CGPoint) -> UIContextMenuConfiguration? {
        return nil
    }
}

extension ChatPrivateController: UIScrollViewDelegate {
    func scrollViewDidScroll(_ scrollView: UIScrollView) {
        guard let tableView = tableView else {
            return
        }
        guard scrollView === tableView else {
            return
        }

        if tableView.contentOffset.y > (tableView.contentSize.height - tableView.frame.size.height) {
            let previous = visibleMessages

            visibleMessages = visibleMessages + Constants.MessagesPortionSize

            if visibleMessages > messages.count {
                visibleMessages = messages.count
            }

            if visibleMessages != previous {
                // KHANDAQ (overlap fix): never reloadData synchronously from a scroll callback —
                // it can fire reentrantly inside endUpdates' own contentOffset adjustment.
                DispatchQueue.main.async { [weak self] in
                    self?.tableView?.reloadData()
                }
            }
        }

        // KHANDAQ (#93-task): show the jump-to-latest button whenever the newest row is off-screen.
        // An empty chat has no rows, so indexPathsForVisibleRows is [] (contains == false) — treat
        // "no rows" as already-at-bottom so the button stays hidden (and can't crash on tap).
        let atBottom = tableView.numberOfRows(inSection: 0) == 0
            || (tableView.indexPathsForVisibleRows?.contains(IndexPath(row: 0, section: 0)) ?? true)
        toggleNewMessageView(show: !atBottom)

        updateFloatingDate()
        // Only while the person scrolls: opening the conversation, a new message or a layout pass
        // move the log as well, and the chip would flash for nothing.
        if scrollView.isDragging || scrollView.isDecelerating {
            showFloatingDateMomentarily()
        }
    }
}

extension ChatPrivateController: ChatMovableDateCellDelegate {
    func chatMovableDateCellCopyPressed(_ cell: ChatMovableDateCell) {
        guard let indexPath = tableView?.indexPath(for: cell) else {
            return
        }

        let message = messageEntry(atDisplayIndex: indexPath.row).message

        if let messageText = message.messageText {
            // KHANDAQ (#38): copy the visible body, not the raw reply/mention wire markup.
            UIPasteboard.general.string = MessageReplyHelper.plainBody(for: message) ?? messageText.text
        }
        else if let _ = message.messageCall {
            fatalError("Message call cannot be copied")
        }
        else if let messageFile = message.messageFile {
            // KHANDAQ (#154): a media bubble's caption is a separate (merged) message with no row of its
            // own, so "Copy" on the media used to copy only the IMAGE — pasting elsewhere gave the picture,
            // never the caption text. Prefer the caption TEXT (universally pasteable, and what users
            // expect); fall back to the image for a plain photo. Never crash on a non-image / missing file.
            let storageIdx = messages.indexOfObject(message)
            if storageIdx >= 0,
               let captionMsg = captionMessage(forFileAtStorageIndex: storageIdx),
               let captionText = MessageReplyHelper.plainBody(for: captionMsg), !captionText.isEmpty {
                UIPasteboard.general.string = captionText
            }
            else if UTTypeConformsTo(inferredFileUTI(for: messageFile) as CFString? ?? "" as CFString, kUTTypeImage),
                    let file = messageFile.filePath(),
                    let image = UIImage(contentsOfFile: file) {
                UIPasteboard.general.image = image
            }
            // else: a non-image file with no caption — nothing sensible to copy; do nothing (was a crash).
        }
    }

    func chatMovableDateCellDeletePressed(_ cell: ChatMovableDateCell) {
        guard let indexPath = tableView?.indexPath(for: cell) else {
            return
        }

        let message = messageEntry(atDisplayIndex: indexPath.row).message

        // KHANDAQ (#205): let the user pick the scope. Own message → «у меня»/«у всех»; an incoming
        // message can only be removed locally so we skip straight to a single «Удалить».
        showDeleteScopeConfirmation(messagesCount: 1,
                                    canDeleteForEveryone: message.isOutgoing(),
                                    barButtonItem: nil,
                                    sourceView: cell,
                                    deleteForMe: { [unowned self] in
            // KHANDAQ (audit #11): the peer may retract this message (incoming KQ delete invalidates the
            // Realm row) while the scope sheet is open; touching an invalidated object throws an
            // uncatchable RLMException. Bail if it's gone.
            guard !message.isInvalidated else { return }
            self.submanagerChats.removeMessages([message])
        }, deleteForEveryone: { [unowned self] in
            // KHANDAQ (#179): own message is also retracted on the peer (KQ delete packet).
            guard !message.isInvalidated else { return }
            self.submanagerChats.deleteMessage(forBoth: message)
        })
    }

    func chatMovableDateCellMorePressed(_ cell: ChatMovableDateCell) {
        toggleTableViewEditing(true, animated: true)

        // "Select" starts with the message it was picked on already checked.
        if let indexPath = tableView?.indexPath(for: cell) {
            tableView?.selectRow(at: indexPath, animated: false, scrollPosition: .none)
        }
    }

    func chatMovableDateCellReplyPressed(_ cell: ChatMovableDateCell) {
        guard let indexPath = tableView?.indexPath(for: cell) else {
            return
        }
        let message = messageEntry(atDisplayIndex: indexPath.row).message
        replyController.startReply(to: message, submanagerObjects: submanagerObjects, theme: theme)
        // The reply header travels in the same message, so it counts against the length limit.
        updateComposeLimit()
        _ = chatInputView.becomeFirstResponder()
    }

    func chatMovableDateCellForwardPressed(_ cell: ChatMovableDateCell) {
        guard let indexPath = tableView?.indexPath(for: cell) else {
            return
        }
        let message = messageEntry(atDisplayIndex: indexPath.row).message
        MessageForwarder.presentForwardPicker(for: message, from: self, sourceView: cell)
    }

    // KHANDAQ (#G5): pin the message for this chat and show the tap-to-jump banner atop the chat.
    func chatMovableDateCellPinPressed(_ cell: ChatMovableDateCell) {
        guard let indexPath = tableView?.indexPath(for: cell) else {
            return
        }
        let message = messageEntry(atDisplayIndex: indexPath.row).message
        guard let messageId = message.uniqueIdentifier else {
            return
        }
        ChatPinStore.setPinned(messageId: messageId, forChat: chat.uniqueIdentifier)
        refreshPinnedBanner()
    }

    /// Show/refresh (or hide) the pinned-message banner based on the stored pin for this chat.
    func refreshPinnedBanner() {
        let pinnedId = ChatPinStore.pinnedMessageId(forChat: chat.uniqueIdentifier)
        var preview: String?
        if let pinnedId = pinnedId {
            for i in 0..<messages.count where messages[i].uniqueIdentifier == pinnedId {
                preview = ChatPrivateController.pinnedPreview(for: messages[i])
                break
            }
        }

        guard let text = preview else {
            // No pin, or the pinned message was deleted: hide the strip and drop a stale pin.
            if pinnedId != nil { ChatPinStore.clearPin(forChat: chat.uniqueIdentifier) }
            setPinnedStripVisible(false)
            return
        }

        // KHANDAQ (redesign stage 2): the pinned message lies as a quiet strip under the header, in the
        // stack above the log, so the log no longer needs room kept free under a floating card.
        pinnedStrip.configure(text: text)
        setPinnedStripVisible(true)
    }

    private func setPinnedStripVisible(_ visible: Bool) {
        guard pinnedStrip.isHidden == visible else {
            return
        }
        let changes = {
            self.pinnedStrip.isHidden = !visible
            self.view.layoutIfNeeded()
        }
        if view.window != nil {
            UIView.animate(withDuration: KQMotion.duration(KQMotion.layout), delay: 0, options: [.curveEaseOut, .beginFromCurrentState], animations: changes, completion: nil)
        }
        else {
            changes()
        }
    }

    private static func pinnedPreview(for message: OCTMessageAbstract) -> String {
        if let text = message.messageText?.text, !text.isEmpty {
            return MessageReplyHelper.plainBody(for: message) ?? text
        }
        if let file = message.messageFile {
            if VoiceMessageHelper.isVoiceMessage(fileName: file.fileName, filePath: nil) {
                return String(localized: "voice_message_label")
            }
            return file.fileName ?? String(localized: "chat_pinned_banner_title")
        }
        return String(localized: "chat_pinned_banner_title")
    }

    @objc private func pinnedBannerTapped() {
        guard let id = ChatPinStore.pinnedMessageId(forChat: chat.uniqueIdentifier) else {
            return
        }
        jumpToPinnedMessage(id: id)
    }

    @objc private func pinnedBannerClosePressed() {
        ChatPinStore.clearPin(forChat: chat.uniqueIdentifier)
        refreshPinnedBanner()
    }

    /// Scroll to the pinned message (mirrors the global-search jump: expand the paging window first).
    private func jumpToPinnedMessage(id: String) {
        guard let tableView = tableView else {
            return
        }
        var storageIndex: Int?
        for i in 0..<messages.count where messages[i].uniqueIdentifier == id {
            storageIndex = i
            break
        }
        guard let targetStorageIndex = storageIndex else {
            return
        }
        if targetStorageIndex >= visibleMessages {
            visibleMessages = min(messages.count, targetStorageIndex + Constants.MessagesPortionSize)
            tableView.reloadData()
            tableView.layoutIfNeeded()
        }
        let rows = tableView.numberOfRows(inSection: 0)
        for row in 0..<rows where messageEntry(atDisplayIndex: row).message.uniqueIdentifier == id {
            let indexPath = IndexPath(row: row, section: 0)
            DispatchQueue.main.async {
                tableView.scrollToRow(at: indexPath, at: .middle, animated: true)
            }
            break
        }
    }

    // KHANDAQ (#208): edit an own text message via an inline editor, then push the change (packet 186).
    func chatMovableDateCellEditPressed(_ cell: ChatMovableDateCell) {
        guard let indexPath = tableView?.indexPath(for: cell) else {
            return
        }
        presentMessageEditor(for: messageEntry(atDisplayIndex: indexPath.row).message)
    }

    /// The inline editor of an own text, for the log and for a message waiting in the tray.
    fileprivate func presentMessageEditor(for message: OCTMessageAbstract) {
        guard !message.isInvalidated, message.messageText != nil else {
            return
        }
        let current = message.messageText?.text ?? ""

        let alert = UIAlertController(title: String(localized: "chat_edit_action"), message: nil, preferredStyle: .alert)
        alert.addTextField { textField in
            textField.text = current
            textField.autocapitalizationType = .sentences
            textField.clearButtonMode = .whileEditing
        }
        alert.addAction(UIAlertAction(title: String(localized: "alert_cancel"), style: .cancel, handler: nil))
        alert.addAction(UIAlertAction(title: String(localized: "message_edit_save"), style: .default) { [weak self, weak alert] _ in
            guard let self = self, let newText = alert?.textFields?.first?.text else {
                return
            }
            let trimmed = newText.trimmingCharacters(in: .whitespacesAndNewlines)
            if trimmed.isEmpty || trimmed == current {
                return
            }
            // KHANDAQ (audit #11): message may have been retracted by the peer while the edit alert was
            // open — an invalidated Realm object throws an uncatchable RLMException on use.
            guard !message.isInvalidated else { return }
            self.submanagerChats.editMessage(message, newText: trimmed)
        })
        present(alert, animated: true, completion: nil)
    }

    // KHANDAQ (#192): quick reaction bar — one tap toggles the own reaction on the message
    func chatMovableDateCellReactPressed(_ cell: ChatMovableDateCell) {
        guard let indexPath = tableView?.indexPath(for: cell) else {
            return
        }
        let message = messageEntry(atDisplayIndex: indexPath.row).message
        presentReactionPicker(for: message, sourceView: cell)
    }

    func presentReactionPicker(for message: OCTMessageAbstract, sourceView: UIView) {
        showReactionBar(for: message, cell: sourceView)
    }

    /// KHANDAQ (#192): Telegram-style horizontal reaction bar floating above the message bubble.
    func showReactionBar(for message: OCTMessageAbstract, cell: UIView) {
        let rect: CGRect
        if let editable = cell as? ChatEditable {
            rect = cell.convert(editable.menuTargetRect(), to: view)
        } else {
            rect = cell.convert(cell.bounds, to: view)
        }
        reactionPopup.present(in: view, aboveRect: rect,
                              currentEmoji: ChatReactionsFormat.ownEmoji(from: message.reactionsJSON),
                              dark: ThemeAppearance.isDarkMode) { [weak self] emoji in
            // KHANDAQ (audit #11): the message can be retracted by the peer while the reaction bar is open.
            guard let self = self, !message.isInvalidated else { return }
            self.submanagerChats.toggleReaction(onMessage: message, emoji: emoji)
        }
    }
}

extension ChatPrivateController: ChatReplySwipeDelegate {
    func chatCellDidRequestReply(_ cell: ChatMovableDateCell) {
        chatMovableDateCellReplyPressed(cell)
    }
}

extension ChatPrivateController: UIGestureRecognizerDelegate {
    func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        guard let panGR = gestureRecognizer as? UIPanGestureRecognizer else {
            return false
        }

        let translation = panGR.translation(in: panGR.view)

        return fabsf(Float(translation.x)) > fabsf(Float(translation.y))
    }
}

private extension ChatPrivateController {
    func createNavigationViews() {
        titleView = ChatPrivateTitleView()
        // KHANDAQ (Figma): Telegram-style, the tile and the name are LEFT-aligned next to the back
        // button, so the title view is installed as a left bar item (see installTitleLeftItem) and the
        // centered titleView slot is left empty. A nav bar only measures a custom bar-item view once, so
        // when the title's size changes we re-install it to force a re-measure.
        titleView.onSizeChanged = { [weak self] in
            self?.installTitleLeftItem()
        }

        // KHANDAQ (redesign stage 2): the header opens and closes the dossier. "For yourself" and a
        // removed contact have none (opensDossier is false there).
        titleView.addTarget(self, action: #selector(ChatPrivateController.titleViewTapped), for: .touchUpInside)

        // create correct navigation buttons
        toggleTableViewEditing(false, animated: false)
    }

    /// KHANDAQ (Figma): install the Telegram-style avatar+name as a left bar item (supplementing the
    /// back button). Skipped while the multi-select edit toolbar owns the left items.
    func installTitleLeftItem() {
        guard tableView?.isEditing != true else {
            return
        }
        navigationItem.leftItemsSupplementBackButton = true
        navigationItem.leftBarButtonItems = [UIBarButtonItem(customView: titleView)]
    }

    @objc func titleViewTapped() {
        toggleDossier()
    }

    func createTableView() {
        let tableView = UITableView()
        self.tableView = tableView
        tableView.dataSource = self
        tableView.delegate = self
        tableView.transform = CGAffineTransform(a: 1, b: 0, c: 0, d: -1, tx: 0, ty: 0)
        // KHANDAQ (#37): the table is y-flipped (d:-1), so contentInset.top renders at the VISUAL
        // bottom — a small gap so the newest message clears the input bar instead of touching it.
        tableView.contentInset.top = 10.0
        tableView.scrollIndicatorInsets.top = 10.0
        tableView.scrollsToTop = false
        tableView.allowsSelection = false
        tableView.estimatedRowHeight = 44.0
        // Transparent over the surface of the screen.
        tableView.backgroundColor = .clear
        tableView.allowsMultipleSelectionDuringEditing = true
        tableView.separatorStyle = .none
        // KHANDAQ (#37): Telegram-style — drag the message list toward the keyboard to dismiss it
        // (works on the y-flipped table; the interactive dismiss tracks the finger in window coords).
        tableView.keyboardDismissMode = .interactive

        view.addSubview(tableView)

        tableView.register(ChatMovableDateCell.self, forCellReuseIdentifier: ChatMovableDateCell.staticReuseIdentifier)
        tableView.register(ChatIncomingTextCell.self, forCellReuseIdentifier: ChatIncomingTextCell.staticReuseIdentifier)
        tableView.register(ChatOutgoingTextCell.self, forCellReuseIdentifier: ChatOutgoingTextCell.staticReuseIdentifier)
        tableView.register(AirEntryTextCell.self, forCellReuseIdentifier: AirEntryTextCell.staticReuseIdentifier)
        tableView.register(ChatIncomingCallCell.self, forCellReuseIdentifier: ChatIncomingCallCell.staticReuseIdentifier)
        tableView.register(ChatOutgoingCallCell.self, forCellReuseIdentifier: ChatOutgoingCallCell.staticReuseIdentifier)
        tableView.register(ChatIncomingFileCell.self, forCellReuseIdentifier: ChatIncomingFileCell.staticReuseIdentifier)
        tableView.register(ChatOutgoingFileCell.self, forCellReuseIdentifier: ChatOutgoingFileCell.staticReuseIdentifier)

        tableViewTapGestureRecognizer = UITapGestureRecognizer(target: self, action: #selector(ChatPrivateController.tapOnTableView))
        // KHANDAQ: don't cancel touches in the table. This keyboard-dismiss tap defaults to
        // cancelsTouchesInView=true, which swallowed taps on the reply-quote control (a custom
        // UIControl — UIKit doesn't auto-prioritise it the way it does a UIButton), so tapping a
        // reply quote in 1:1 chats never jumped to the original message. Group chats have no such
        // table tap gesture, which is exactly why reply-tap worked there but not in 1:1.
        tableViewTapGestureRecognizer.cancelsTouchesInView = false
        tableView.addGestureRecognizer(tableViewTapGestureRecognizer)

        let panGR = UIPanGestureRecognizer(target: self, action: #selector(ChatPrivateController.panOnTableView(_:)))
        panGR.delegate = self
        tableView.addGestureRecognizer(panGR)

        // KHANDAQ (#G5 reaction-row): custom Telegram-style long-press popup (reaction bar + action menu
        // together). Replaces the system context menu on iOS 13+ (which can't host a reaction row). No
        // delegate — gestureRecognizerShouldBegin returns false for non-pan recognizers, which would
        // block it; the default delegate lets it coexist with scroll (movement cancels the press).
        let longPressGR = UILongPressGestureRecognizer(target: self, action: #selector(ChatPrivateController.longPressOnTableView(_:)))
        longPressGR.minimumPressDuration = 0.4
        tableView.addGestureRecognizer(longPressGR)
    }

    @objc func longPressOnTableView(_ gesture: UILongPressGestureRecognizer) {
        guard gesture.state == .began, let tableView = tableView, !tableView.isEditing else {
            return
        }
        let point = gesture.location(in: tableView)
        guard let indexPath = tableView.indexPathForRow(at: point),
              let cell = tableView.cellForRow(at: indexPath) as? ChatMovableDateCell else {
            return
        }
        if let editable = cell as? ChatEditable, !editable.shouldShowMenu() {
            return
        }
        presentContextPopup(for: cell, indexPath: indexPath)
    }

    // KHANDAQ (#G5 reaction-row): the unified Telegram popup — reaction bar above + action list below,
    // one dimmed backdrop. Reuses the exact per-cell handlers the system menu used, minus "Реакция"
    // (the bar handles it). Reactions and actions are gated by the cell exactly as before.
    fileprivate func presentContextPopup(for cell: ChatMovableDateCell, indexPath: IndexPath) {
        if #available(iOS 10.0, *) {
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
        }
        let message = messageEntry(atDisplayIndex: indexPath.row).message

        func supports(_ sel: Selector) -> Bool { cell.isMenuActionSupportedByCell(sel) }
        var actions: [ChatContextMenuAction] = []
        if supports(#selector(ChatMovableDateCell.replyAction)) {
            actions.append(ChatContextMenuAction(title: String(localized: "chat_reply_action"), systemImageName: "arrowshape.turn.up.left") { [weak self] in self?.chatMovableDateCellReplyPressed(cell) })
        }
        if supports(#selector(copy(_:))) {
            actions.append(ChatContextMenuAction(title: String(localized: "chat_copy_action"), systemImageName: "doc.on.doc") { [weak self] in self?.chatMovableDateCellCopyPressed(cell) })
        }
        if supports(#selector(ChatMovableDateCell.editMessageAction)) {
            actions.append(ChatContextMenuAction(title: String(localized: "chat_edit_action"), systemImageName: "pencil") { [weak self] in self?.chatMovableDateCellEditPressed(cell) })
        }
        actions.append(ChatContextMenuAction(title: String(localized: "chat_pin_action"), systemImageName: "pin") { [weak self] in self?.chatMovableDateCellPinPressed(cell) })
        actions.append(ChatContextMenuAction(title: String(localized: "chat_forward_action"), systemImageName: "arrowshape.turn.up.right") { [weak self] in self?.chatMovableDateCellForwardPressed(cell) })
        actions.append(ChatContextMenuAction(title: String(localized: "chat_select_action"), systemImageName: "checkmark.circle") { [weak self] in self?.chatMovableDateCellMorePressed(cell) })
        actions.append(ChatContextMenuAction(title: String(localized: "alert_delete"), systemImageName: "trash", destructive: true) { [weak self] in self?.chatMovableDateCellDeletePressed(cell) })

        let editable = cell as? ChatEditable
        let bubbleRectInCell = editable?.menuTargetRect() ?? cell.bounds
        let rect = cell.convert(bubbleRectInCell, to: view)
        // KHANDAQ (#G5): lifted-bubble snapshot (Telegram-style) — taken before willShowMenu so it captures
        // the pristine bubble; nil-safe (falls back to the dimmed real bubble).
        let snapshot = cell.resizableSnapshotView(from: bubbleRectInCell, afterScreenUpdates: false, withCapInsets: .zero)
        // KHANDAQ (#G5 reaction-row): suppress the bubble's own text-selection/loupe while the popup is
        // up (mirrors the legacy UIMenuController willShow/Hide), then restore it (keeps links tappable).
        editable?.willShowMenu()
        reactionPopup.presentMenu(in: view, aroundRect: rect,
                                  currentEmoji: ChatReactionsFormat.ownEmoji(from: message.reactionsJSON),
                                  dark: ThemeAppearance.isDarkMode, actions: actions,
                                  bubbleSnapshot: snapshot,
                                  onPick: { [weak self] emoji in
                                      // KHANDAQ (audit #11): guard against a peer-retracted (invalidated) message.
                                      guard let self = self, !message.isInvalidated else { return }
                                      self.submanagerChats.toggleReaction(onMessage: message, emoji: emoji)
                                  },
                                  onDismiss: { editable?.willHideMenu() })
    }

    func createTableHeaderViews() {
        typingHeaderView = ChatTypingHeaderView(theme: theme)
        typingHeaderView.transform = tableView!.transform
        // Hidden until someone is actually typing. Otherwise it lingers as a stray grey pill — notably
        // in friend-less chats (Saved Messages), where updateTableHeaderView() bails out early.
        typingHeaderView.isHidden = true
        view.addSubview(typingHeaderView)

        fauxOfflineHeaderView = ChatFauxOfflineHeaderView(theme: theme)
        fauxOfflineHeaderView.transform = tableView!.transform
        view.addSubview(fauxOfflineHeaderView)
    }

    func createNewMessagesView() {
        // KHANDAQ (#93-task): a Telegram-style "jump to latest" button, shown whenever the newest
        // message is scrolled off-screen (and on a new incoming message while scrolled up).
        // KHANDAQ (redesign stage 2): the plate of the design system with the chevron, at the end edge.
        let button = AirJumpButton()
        button.isHidden = true
        button.addTarget(self, action: #selector(ChatPrivateController.newMessagesViewPressed), for: .touchUpInside)
        view.addSubview(button)
        newMessagesView = button
    }

    func createFloatingDateView() {
        floatingDateView = UIView()
        // KHANDAQ (redesign stage 2): a sunken chip in the tokens of the system, in both themes.
        floatingDateView.backgroundColor = KQColor.sunken
        floatingDateView.layer.cornerRadius = KQRadius.control
        floatingDateView.layer.masksToBounds = true
        floatingDateView.alpha = 0.0
        floatingDateView.isUserInteractionEnabled = false
        view.addSubview(floatingDateView)

        floatingDateLabel = UILabel()
        floatingDateLabel.textColor = KQColor.ink2
        floatingDateLabel.backgroundColor = .clear
        floatingDateLabel.textAlignment = .center
        floatingDateLabel.font = KQFont.font(.caption, weight: 500)
        floatingDateView.addSubview(floatingDateLabel)
    }

    /// Sets the floating chip to the date of the topmost on-screen message (the table is y-flipped,
    /// so "topmost" is the visible cell with the smallest minY once converted to view coordinates).
    func updateFloatingDate() {
        guard let tableView = tableView, floatingDateView != nil,
              messages.count > 0,
              let visible = tableView.indexPathsForVisibleRows, !visible.isEmpty else {
            return
        }

        var topRow: Int?
        var topY = CGFloat.greatestFiniteMagnitude
        for indexPath in visible {
            let rect = tableView.convert(tableView.rectForRow(at: indexPath), to: view)
            if rect.minY < topY {
                topY = rect.minY
                topRow = indexPath.row
            }
        }

        guard let row = topRow else {
            return
        }

        let message = messageEntry(atDisplayIndex: row).message
        let date = (message.tssent != 0)
            ? Date(timeIntervalSince1970: TimeInterval(message.tssent))
            : message.date()
        floatingDateLabel.text = AirDayMark.text(for: date, locale: AppLanguage.locale)
    }

    /// Fades the chip in, then schedules a fade-out so it only shows during/just after scrolling.
    func showFloatingDateMomentarily() {
        guard floatingDateView != nil, let text = floatingDateLabel.text, !text.isEmpty else {
            return
        }

        floatingDateHideTimer?.invalidate()
        UIView.animate(withDuration: KQMotion.duration(KQMotion.tap)) {
            self.floatingDateView.alpha = 1.0
        }
        floatingDateHideTimer = Timer.scheduledTimer(withTimeInterval: 1.2, repeats: false) { [weak self] _ in
            UIView.animate(withDuration: KQMotion.duration(KQMotion.layout)) {
                self?.floatingDateView.alpha = 0.0
            }
        }
    }

    func createInputView() {
        chatInputView = ChatInputView(theme: theme)
        view.addSubview(chatInputView)

        chatInputViewManager = ChatInputViewManager(inputView: chatInputView,
                                                    theme: theme,
                                                    chat: chat,
                                                    submanagerChats: submanagerChats,
                                                    submanagerFiles: submanagerFiles,
                                                    submanagerObjects: submanagerObjects,
                                                    presentingViewController: self)
        chatInputViewManager.outgoingTextComposer = { [weak self] text in
            guard let self = self else {
                return text
            }
            let composed = self.replyController.composeOutgoingText(text)
            // KHANDAQ (dup fix): purge the PERSISTED reply too (savePending with cleared meta
            // removes the key) — a stale UserDefaults copy resurrected the reply bar after a
            // relaunch, leading users to re-send the same reply → duplicate bubbles.
            self.replyController.savePending(forChatId: self.chat.uniqueIdentifier)
            return composed
        }
        // KHANDAQ design (Figma): the "+" attachment menu's "Геолокация" shares the current location once.
        // KHANDAQ (#T2): use the coordinator's one-shot path (same as groups) instead of the continuous
        // toggle — the continuous path required location_sharing_contact_pubkey to be set first, which the
        // menu never did, so the location was never actually sent in 1:1.
        chatInputViewManager.onShareLocation = { [weak self] in
            guard let self = self else { return }
            LocationSharingCoordinator.shared.shareCurrentLocationOnce(to: self)
        }
        replyController.install(in: view, above: chatInputView, theme: theme)

        // KHANDAQ (redesign stage 2): the composer of the design system (one framed field, the send
        // button of the same height, the line about what happens to the message), and refused texts
        // kept on screen with "Retry" instead of an alert. A removed contact keeps the alert: nobody is
        // there to send to again.
        chatInputView.useAirStyle()
        chatInputView.onTextChanged = { [weak self] _ in
            self?.updateComposeLimit()
        }
        chatInputViewManager.onSendFailed = { [weak self] text, _ in
            guard let self = self, self.friend != nil, !self.chat.isSavedMessages else {
                return false
            }
            AirFailedSends.shared.add(text: text, toChat: self.chat.uniqueIdentifier)
            return true
        }
    }

    func createEditMessageToolbar() {
        editMessagesToolbar = UIToolbar()
        editMessagesToolbar.isHidden = true
        // It lies over the composer, edge to edge (installConstraints): it takes the composer's height and
        // never sets it. Hugging its 44 points at the default priority, it pressed the hint under the
        // field to the room it keeps (one line of three at AX5, two of three at AX3).
        editMessagesToolbar.setContentHuggingPriority(.defaultLow, for: .vertical)
        // KHANDAQ (redesign stage 2): buttons are ink.
        editMessagesToolbar.tintColor = KQColor.ink
        // KHANDAQ (#G3): selection mode offered only Delete — add Forward for the selected messages.
        let forwardItem: UIBarButtonItem
        if #available(iOS 13.0, *) {
            forwardItem = UIBarButtonItem(image: UIImage(systemName: "arrowshape.turn.up.right"),
                                          style: .plain, target: self,
                                          action: #selector(ChatPrivateController.editMessagesForwardButtonPressed(_:)))
        } else {
            forwardItem = UIBarButtonItem(title: String(localized: "chat_forward_action"),
                                          style: .plain, target: self,
                                          action: #selector(ChatPrivateController.editMessagesForwardButtonPressed(_:)))
        }
        editMessagesToolbar.items = [
            forwardItem,
            UIBarButtonItem(barButtonSystemItem: .flexibleSpace, target: nil, action: nil),
            UIBarButtonItem(barButtonSystemItem: .trash, target: self, action: #selector(ChatPrivateController.editMessagesDeleteButtonPressed(_:)))
        ]
        view.addSubview(editMessagesToolbar)
    }

    func installConstraints() {
        // KHANDAQ (redesign stage 2): under the header the presence band and the pinned strip, then the
        // log, then the refused messages and the tray, then the reply preview and the field.
        // The strips above and under the log take only the height of their content; the log gets the
        // rest. Weaker than any content hugging, so it only settles what nothing else decides.
        let shrinkToContent = UILayoutPriority.defaultLow.rawValue - 1
        topStack.snp.makeConstraints {
            $0.top.equalTo(view.safeAreaLayoutGuide.snp.top)
            $0.leading.trailing.equalTo(view)
            $0.height.equalTo(0).priority(shrinkToContent)
        }

        tableView!.snp.makeConstraints {
            $0.top.equalTo(topStack.snp.bottom)
            $0.leading.trailing.equalTo(view)

            tableViewToChatInputConstraint = $0.bottom.equalTo(bottomStack.snp.top).constraint
        }

        bottomStack.snp.makeConstraints {
            $0.leading.trailing.equalTo(view)
            $0.bottom.equalTo(replyController.previewView.snp.top)
            $0.height.equalTo(0).priority(shrinkToContent)
        }

        // At the accessibility text sizes the refused messages and the tray take at most half of the room
        // between the band and the field, and their messages scroll in it (keepRoomForLog). Below the
        // compression resistance of words, so a title is never cut to keep the half.
        view.addLayoutGuide(stripsRoom)
        stripsRoom.snp.makeConstraints {
            $0.top.equalTo(topStack.snp.bottom)
            $0.bottom.equalTo(replyController.previewView.snp.top)
            $0.leading.trailing.equalTo(view)
        }
        bottomStack.snp.makeConstraints {
            stripsHalfConstraint = $0.height.lessThanOrEqualTo(stripsRoom.snp.height).multipliedBy(0.5)
                .priority(UILayoutPriority.defaultHigh.rawValue - 10).constraint
        }
        stripsHalfConstraint?.isActive = traitCollection.preferredContentSizeCategory.isAccessibilityCategory

        typingHeaderView.snp.makeConstraints {
            $0.leading.trailing.equalTo(view)
            $0.top.equalTo(tableView!.snp.bottom)
            typingViewToChatInputConstraint = $0.bottom.equalTo(bottomStack.snp.top).constraint
        }

        typingViewToChatInputConstraint.deactivate()

        newMessagesView.snp.makeConstraints {
            $0.trailing.equalTo(view).offset(-KQSpace.x16)
            newMessageViewTopConstraint = $0.top.equalTo(bottomStack.snp.top).constraint
        }

        floatingDateView.snp.makeConstraints {
            $0.top.equalTo(topStack.snp.bottom).offset(KQSpace.x8)
            $0.centerX.equalTo(view)
            $0.height.greaterThanOrEqualTo(KQSize.controlSmall)
        }

        floatingDateLabel.snp.makeConstraints {
            $0.top.equalTo(floatingDateView).offset(KQSpace.x4)
            $0.bottom.equalTo(floatingDateView).offset(-KQSpace.x4)
            $0.leading.equalTo(floatingDateView).offset(KQSpace.x12)
            $0.trailing.equalTo(floatingDateView).offset(-KQSpace.x12)
        }

        chatInputView.snp.makeConstraints {
            $0.leading.trailing.equalTo(view)
            $0.top.greaterThanOrEqualTo(view).offset(Constants.InputViewTopOffset)
            chatInputViewBottomConstraint = $0.bottom.equalTo(view).constraint
            // TODO: this moves the input view a bit more to the top, because the home button "line" is in the way otherwise
            //       please fix me properly in the future
            if #available(iOS 11.0, *) {
                let keyWindow = UIApplication.shared.keyWindow
                let b = keyWindow?.safeAreaInsets.bottom
                chatInputViewBottomConstraint?.update(offset: -(b ?? 20))
            }
        }

        // KHANDAQ (#115): the input bar is pushed up by the home-indicator safe area (see above), which
        // left the chat doodle showing through the gap beneath it. Fill that gap with the bar's own
        // background so the input bar reads as reaching the screen edge. Pinned bar-bottom → view-bottom,
        // so it also covers the hidden area behind the keyboard when it's raised (no visible effect there).
        let inputBottomFiller = UIView()
        inputBottomFiller.backgroundColor = KQColor.surface
        inputBottomFiller.isUserInteractionEnabled = false
        view.insertSubview(inputBottomFiller, belowSubview: chatInputView)
        inputBottomFiller.snp.makeConstraints {
            $0.leading.trailing.equalTo(view)
            $0.top.equalTo(chatInputView.snp.bottom)
            $0.bottom.equalTo(view)
        }

        editMessagesToolbar.snp.makeConstraints {
            $0.edges.equalTo(chatInputView)
        }
    }

    func addMessagesNotification() {
        self.messagesToken = messages.addNotificationBlock { [weak self] change in
            guard let self = self, let tableView = self.tableView else {
                return
            }
            switch change {
                case .initial:
                    // KHANDAQ (QA 2026-11-13): the changes Realm delivers from here on start at this
                    // version; a table that loaded another one is brought to it (see tableReadMessageCount).
                    if let readCount = self.tableReadMessageCount, readCount != self.messages.count {
                        tableView.reloadData()
                    }
                case .update(_, let deletions, let insertions, let modifications):
                    // KHANDAQ (redesign stage 2): the rows of the log change with the messages; the
                    // tray takes what waits for the person, a new message rises into the log.
                    self.displayGeneration += 1
                    let hadTray = !self.trayIds.isEmpty
                    self.refreshTray(animated: true)
                    self.refreshComposeState(animated: true)
                    if insertions.contains(0), let newest = self.messages.object(at: 0),
                       let id = newest.uniqueIdentifier, newest.messageText != nil {
                        self.scheduleArrival(id: id, delay: 0)
                    }
                    // The tray is emptying into the log right now; the log is reloaded once it folded,
                    // so the rising rows are not replaced halfway.
                    if self.isTrayDeparting {
                        self.visibleMessages = max(0, self.visibleMessages + insertions.count - deletions.count)
                        self.needsLogReload = true
                        return
                    }
                    // While searching, or while the tray keeps messages out of the log, the table
                    // shows a subset, so storage-index granular updates don't line up: keep the
                    // storage window in sync and reload.
                    if !self.messageSearchQuery.isEmpty || hadTray || !self.trayIds.isEmpty {
                        self.visibleMessages = max(0, self.visibleMessages + insertions.count - deletions.count)
                        tableView.reloadData()
                        self.updateTableHeaderView()
                        self.updateEncryptionBannerVisibility()
                        if insertions.contains(0) {
                            self.handleNewMessage()
                        }
                        return
                    }
                    // A Telegram-style caption changes the height of a *separate* (file) row, which an
                    // animated incremental update can't express — and sending photo+caption inserts two
                    // rows in quick succession. Fall back to a full reload whenever a caption is involved
                    // to avoid the "invalid number of rows" batch-update assertion.
                    if self.changeInvolvesCaption(insertions: insertions, modifications: modifications) {
                        self.visibleMessages = max(0, self.visibleMessages + insertions.count - deletions.count)
                        tableView.reloadData()
                        self.updateTableHeaderView()
                        if insertions.contains(0) {
                            self.handleNewMessage()
                        }
                        return
                    }
                    if deletions.isEmpty && insertions.isEmpty {
                        self.updateTableViewWithModifications(modifications)
                    }
                    // KHANDAQ (#179): deleted rows leave their neighbours stale — bubble grouping,
                    // corner rounding and the shared timestamp are computed from adjacent messages,
                    // and an animated deleteRows repaints only the removed row (tester video: two
                    // timestamps drawn on top of each other after a delete). Deletions are rare and
                    // user-initiated, so take the artifact-free full reload instead of the batch.
                    else if !deletions.isEmpty {
                        self.visibleMessages = max(0, self.visibleMessages + insertions.count - deletions.count)
                        tableView.reloadData()
                    }
                    else if deletions.count + insertions.count <= 5 {
                        // KHANDAQ (#86/#90): an animated begin/endUpdates batch is only valid if its row
                        // arithmetic exactly matches what the table reports afterwards. A Realm change can
                        // be delivered against an already-advanced collection (coalesced/async delivery, or
                        // an offline-message flood), and the insertion/deletion helpers clamp indices to the
                        // visible window — so the indices in `changes` may not reconcile with the live count.
                        // Applying them then trips UITableView's "invalid batch updates" abort. Reconcile up
                        // front and fall back to a crash-proof reloadData on ANY inconsistency.
                        let oldRows = tableView.numberOfRows(inSection: 0)
                        let newVisible = max(0, self.visibleMessages + insertions.count - deletions.count)
                        let projectedRows = min(newVisible, self.messages.count)
                        let animatedDeletions = deletions.filter { $0 < self.visibleMessages }
                        let animatedInsertions = insertions.filter { $0 < self.visibleMessages }
                        let indicesInBounds = animatedDeletions.allSatisfy { $0 >= 0 && $0 < oldRows }
                            && animatedInsertions.allSatisfy { $0 >= 0 && $0 <= projectedRows }
                        let countReconciles = (oldRows + animatedInsertions.count - animatedDeletions.count) == projectedRows
                        // KHANDAQ (QA 2026-11-13): the table already read the messages after this change
                        // (see tableReadMessageCount), so its rows may show the new messages. On a chat
                        // longer than the loaded window the row count still reconciles, and the batch
                        // would push a row that already shows the new message one place up and draw it
                        // again below. Read after numberOfRows(inSection:), which itself loads the rows
                        // again when a reload is pending.
                        let tableReadThisChange = self.tableReadMessageCount
                            != self.messages.count - insertions.count + deletions.count

                        if indicesInBounds && countReconciles && !tableReadThisChange {
                            // KHANDAQ (#86/#90/crash): apply EXACTLY the reconciled (window-clamped) index
                            // paths — not the clamping helpers, which would re-clamp against the just-
                            // advanced window and insert an extra row at the pagination boundary.
                            let deletePaths = animatedDeletions.map { IndexPath(row: $0, section: 0) }
                            let insertPaths = animatedInsertions.map { IndexPath(row: $0, section: 0) }
                            // Advance the visible window BEFORE endUpdates so the data source reports the
                            // post-update count during the batch. Updating it afterwards left
                            // displayableRowCount() = min(old visibleMessages, new messages.count), which on
                            // a paginated chat is one short of what the table expects → invalid-batch abort.
                            self.visibleMessages = newVisible
                            // KHANDAQ (overlap fix): apply the batch UN-animated. An in-flight .top row
                            // animation collided with the reloadRows/reloadData that routinely land
                            // 50-200ms later (delivered/tssent updates, pagination), leaving ghost
                            // cells drawn over their neighbours and rows stuck at estimated height
                            // (tester: «слиплись сообщения», clipped bubbles).
                            UIView.performWithoutAnimation {
                                tableView.beginUpdates()
                                if !deletePaths.isEmpty {
                                    tableView.deleteRows(at: deletePaths, with: .none)
                                }
                                if !insertPaths.isEmpty {
                                    tableView.insertRows(at: insertPaths, with: .none)
                                }
                                tableView.endUpdates()
                            }
                            if !modifications.isEmpty {
                                self.updateTableViewWithModifications(modifications)
                            }
                        }
                        else {
                            self.visibleMessages = newVisible
                            tableView.reloadData()
                        }
                    }
                    else {
                        self.visibleMessages = max(0, self.visibleMessages + insertions.count - deletions.count)
                        tableView.reloadData()
                    }

                    self.updateTableHeaderView()
                    self.updateEncryptionBannerVisibility()

                    if insertions.contains(0) {
                        self.handleNewMessage()
                    }
                case .error(let error):
                    log("ChatPrivateController messages notification error: \(error)")
            }
        }
    }

    func updateTableViewWithDeletions(_ deletions: [Int]) {
        guard let tableView = tableView else {
            return
        }

        for index in deletions {
            if index >= visibleMessages {
                continue
            }
            let indexPath = IndexPath(row: index, section: 0)
            tableView.deleteRows(at: [indexPath], with: .top)
        }
    }

    func updateTableViewWithInsertions(_ insertions: [Int]) {
        guard let tableView = tableView else {
            return
        }

        for index in insertions {
            if index >= visibleMessages {
                continue
            }
            let indexPath = IndexPath(row: index, section: 0)
            tableView.insertRows(at: [indexPath], with: .top)
        }
    }

    func updateTableViewWithModifications(_ modifications: [Int]) {
        guard let tableView = tableView else {
            return
        }

        for index in modifications {
            if index >= visibleMessages {
                continue
            }
            // KHANDAQ (redesign stage 2): a storage index; the log may leave messages out (the tray).
            guard index < messages.count, let row = displayIndex(forStorageIndex: index) else {
                continue
            }
            let message = messages[index]
            let indexPath = IndexPath(row: row, section: 0)

            if message.messageFile == nil {
                // KHANDAQ (overlap fix): atomic, un-animated — see the insert batch above
                UIView.performWithoutAnimation {
                    tableView.reloadRows(at: [indexPath], with: .none)
                }
                continue
            }

            guard let cell = tableView.cellForRow(at: indexPath) as? ChatGenericFileCell else {
                continue
            }

            // KHANDAQ (#206 H/J): reconfigure in place (no reloadRows) to avoid re-flashing the
            // image/progress — but the row height must be re-measured or a newly-added reaction
            // chip gets zero vertical space and paints as a clipped/garbled fragment. Also pick the
            // correct direction model so an outgoing media cell keeps its right-alignment + checkmark.
            let model: ChatGenericFileCellModel = message.isOutgoing() ? ChatOutgoingFileCellModel()
                                                                       : ChatIncomingFileCellModel()
            prepareFileCell(cell, andModel: model, withMessage: message)
            // The time and the marks of the entry, as tableView(_:cellForRowAt:) gives a file.
            model.dateString = timeFormatter.string(from: message.date())
            markTranscriptEntry(model, message: message, row: row)
            cell.setupWithTheme(theme, model: model)

            maybeLoadImageForCellAtPath(cell, indexPath: indexPath)

            // Empty batch update forces the table to re-query the self-sizing cell's fitting height
            // (so the reaction chips lay out) WITHOUT calling cellForRowAt again (no image reload).
            UIView.performWithoutAnimation {
                tableView.performBatchUpdates(nil)
            }
        }
    }

    func addFriendNotification() {
        guard let friend = self.friend else {
            // KHANDAQ (redesign stage 2): "For yourself" and a removed contact have no dossier and no
            // calls in the header (see toggleTableViewEditing).
            titleView.opensDossier = false
            if chat.isSavedMessages {
                titleView.configure(name: String(localized: "saved_messages_title"), style: .saved, photo: nil)
                setBarItem(audioButton, enabled: false)
                setBarItem(videoButton, enabled: false)
                locationButton.isEnabled = false
                chatInputView.cameraButtonEnabled = true
                return
            }
            titleView.configure(name: String(localized: "contact_deleted"), style: .person(initials: "", tone: 0), photo: nil)
            setBarItem(audioButton, enabled: true)
            setBarItem(videoButton, enabled: false)
            locationButton.isEnabled = true
            chatInputView.cameraButtonEnabled = false
            return
        }

        // KHANDAQ design (Figma): contact avatar in the chat header.
        updateTitleAvatar(for: friend)
        updateTitlePresence(for: friend)

        let predicate = NSPredicate(format: "uniqueIdentifier == %@", friend.uniqueIdentifier)
        let results = submanagerObjects.friends(predicate: predicate)

        friendToken = results.addNotificationBlock { [weak self] change in
            guard let self = self, let friend = self.friend, !friend.isInvalidated else {
                return
            }

            switch change {
                case .initial:
                    fallthrough
                case .update:
                    self.updateTitleAvatar(for: friend)
                    self.updateTitlePresence(for: friend)

                    let isConnected = friend.isConnected

                    // KHANDAQ (redesign stage 3, E9): both stay enabled; for someone offline they say
                    // the call will not get through and offer to wait (requestCall).
                    self.setBarItem(self.audioButton, enabled: true)
                    self.setBarItem(self.videoButton, enabled: true)
                    if isConnected {
                        self.checkCallWaiting()
                    }
                    self.locationButton.isEnabled = true
                    self.chatInputView.cameraButtonEnabled = true

                    self.updateTableHeaderView()
                    self.friendReachabilityChanged()
                case .error(let error):
                    log("ChatPrivateController friend notification error: \(error)")
            }
        }
    }

    fileprivate func hasPendingOutgoingDelivery() -> Bool {
        let textPredicate = NSPredicate(
            format: "messageText.isDelivered == NO AND senderUniqueIdentifier == nil")
        // internalFileNumber is a signed int: a file queued for an offline friend carries
        // kOCTToxFileNumberFailure (UINT32_MAX) stored as -1, so compare with -1 (objcTox #165).
        let filePredicate = NSPredicate(
            format: "messageFile != nil AND messageFile.fileType == %d AND messageFile.internalFileNumber == %d AND senderUniqueIdentifier == nil",
            OCTMessageFileType.waitingConfirmation.rawValue,
            Int32(bitPattern: kOCTToxFileNumberFailure))
        let compound = NSCompoundPredicate(orPredicateWithSubpredicates: [textPredicate, filePredicate])
        return messages.objects(with: compound).count > 0
    }

    fileprivate func updateTitleAvatar(for friend: OCTFriend) {
        // KHANDAQ (#95): show the contact's real avatar in the chat header (mirrors FriendCardController).
        // KHANDAQ (redesign stage 2): in the tile of the design system; initials in the person's tone
        // when the contact has no avatar.
        let name = personName(friend)
        let photo = friend.avatarData.flatMap { UIImage(data: $0) }
        titleView.configure(name: name,
                            style: .person(initials: AirInitials.from(name), tone: AirTone.index(for: friend.publicKey)),
                            photo: photo)
    }

    func updateTitlePresence(for friend: OCTFriend) {
        // KHANDAQ (#43): typing shows in the presence line under the header instead of a bottom bubble
        // that overlapped the last message. Resolved here (not just in the typing callback) so any
        // presence refresh while the peer is typing keeps showing it.
        // KHANDAQ (redesign stage 2): the band under the header carries the link mark, "online since
        // 9:40, direct" or "offline for 25 minutes", and the day strip.
        guard !friend.isInvalidated else {
            return
        }
        updatePresenceBand(for: friend)

        if friend.isConnected {
            presenceRefreshTimer?.invalidate()
            presenceRefreshTimer = nil
        } else if presenceRefreshTimer == nil {
            presenceRefreshTimer = Timer.scheduledTimer(withTimeInterval: 15, repeats: true) { [weak self] _ in
                guard let self = self, let friend = self.friend, !friend.isInvalidated else {
                    return
                }
                self.updateTitlePresence(for: friend)
            }
        }
    }

    func updateTableHeaderView() {
        guard let tableView = tableView else {
            return
        }

        guard let friend = friend else {
            return
        }

        let hasPendingDelivery = hasPendingOutgoingDelivery()

        // KHANDAQ: flush any pending table layout (e.g. the reloadData() a message deletion just
        // issued in the notification block) OUTSIDE the animation. Otherwise the layoutIfNeeded()
        // inside UIView.animate below re-lays-out the freshly reloaded cells within the animation
        // context, animating their frames from scratch — the transparent message list flashed over
        // the doodle background once per delete (tester: «фон подмигивает» при удалении).
        UIView.performWithoutAnimation {
            tableView.layoutIfNeeded()
        }

        guard !friend.isInvalidated else {
            return
        }
        if friend.isConnected {
            // KHANDAQ (#43): typing shows in the presence line under the header (updateTitlePresence),
            // set outside the animation so the band does not animate its text.
            updateTitlePresence(for: friend)
        }

        UIView.animate(withDuration: Constants.NewMessageViewAnimationDuration, animations: {
        if friend.isConnected {
            if tableView.tableHeaderView === self.fauxOfflineHeaderView {
                tableView.tableHeaderView = nil
            }

            // Keep the table anchored to the input bar and the old bottom typing bubble permanently hidden.
            self.tableViewToChatInputConstraint.activate()
            self.typingViewToChatInputConstraint.deactivate()
            self.typingHeaderView.isHidden = true
            self.typingHeaderView.stopAnimation()

            self.view.layoutIfNeeded()
        }
        else {
            self.tableViewToChatInputConstraint.activate()
            self.typingViewToChatInputConstraint.deactivate()
            self.typingHeaderView.isHidden = true
            self.typingHeaderView.stopAnimation()

            // KHANDAQ (redesign stage 2): texts waiting for the person are in the tray, which says the
            // same; the note stays for files waiting for them (they are not in the tray).
            tableView.tableHeaderView = (hasPendingDelivery && self.trayIds.isEmpty) ? self.fauxOfflineHeaderView : nil
        }
        })

        updateTableHeaderViewLayout()
    }

    func updateTableHeaderViewLayout() {
        guard let headerView = tableView?.tableHeaderView else {
            return
        }
        
        // KHANDAQ (#iOS offline-note): measure the wrapped label at the TRUE table width and ceil the
        // fractional height, otherwise the multi-line note lost its last line ("…Ему" truncation).
        let width = tableView?.bounds.width ?? headerView.frame.width
        headerView.setNeedsLayout()
        headerView.layoutIfNeeded()
        let target = CGSize(width: width, height: 0)
        let height = ceil(headerView.systemLayoutSizeFitting(target,
                                                             withHorizontalFittingPriority: .required,
                                                             verticalFittingPriority: .fittingSizeLevel).height)
        headerView.frame = CGRect(x: 0, y: 0, width: width, height: height)
        tableView?.tableHeaderView = headerView
    }

    func updateInputViewMaxHeight() {
        chatInputView.maxHeight = chatInputView.frame.maxY - Constants.InputViewTopOffset
    }

    func handleNewMessage() {
        if UIApplication.isActive {
            updateLastReadDate()
        }

        guard let tableView = tableView else {
            return
        }

        // KHANDAQ (#119): when WE just sent the newest message, always jump straight to it
        // (Telegram-style) instead of surfacing the "new messages" pill.
        if messages.count > 0, messages[0].isOutgoing() {
            toggleNewMessageView(show: false)
            scrollToNewestMessage()
            return
        }

        guard let visible = tableView.indexPathsForVisibleRows else {
            return
        }

        let first = IndexPath(row: 0, section: 0)

        if !visible.contains(first) {
            toggleNewMessageView(show: true)
        }
    }

    func toggleNewMessageView(show: Bool) {
        guard show != newMessagesViewVisible else {
            return
        }
        newMessagesViewVisible = show

        if show {
            newMessagesView.isHidden = false
        }

        UIView.animate(withDuration: Constants.NewMessageViewAnimationDuration, animations: {
            if show {
                self.newMessageViewTopConstraint?.update(offset: Constants.NewMessageViewTopOffset - self.newMessagesView.frame.size.height)
            }
            else {
                self.newMessageViewTopConstraint?.update(offset: 0.0)
            }

            self.view.layoutIfNeeded()

        }, completion: { finished in
            if !show {
                self.newMessagesView.isHidden = true
            }
        })
    }

    func updateLastReadDate() {
        submanagerObjects.change(chat, lastReadDateInterval: Date().timeIntervalSince1970)
    }

    func imageCellWithMessage(_ message: OCTMessageAbstract, incoming: Bool) -> (ChatMovableDateCellModel, ChatMovableDateCell) {
        let cell: ChatGenericFileCell

        if incoming {
            cell = tableView!.dequeueReusableCell(withIdentifier: ChatIncomingFileCell.staticReuseIdentifier) as! ChatIncomingFileCell
        }
        else {
            cell = tableView!.dequeueReusableCell(withIdentifier: ChatOutgoingFileCell.staticReuseIdentifier) as! ChatOutgoingFileCell
        }
        // KHANDAQ (#23): outgoing files must use the outgoing model so the delivery checkmark
        // renders — previously even outgoing file cells got a ChatIncomingFileCellModel, so the
        // status indicator was always hidden (ChatOutgoingFileCell's `as? ChatOutgoingFileCellModel`
        // cast silently failed).
        let model: ChatGenericFileCellModel = incoming ? ChatIncomingFileCellModel() : ChatOutgoingFileCellModel()

        prepareFileCell(cell, andModel: model, withMessage: message)

        // KHANDAQ (#209): swipe-to-reply is inherited from ChatMovableDateCell but only worked on
        // text/call cells because the delegate was never assigned on media/voice cells.
        cell.replySwipeDelegate = self

        return (model, cell)
    }

    func prepareFileCell(_ cell: ChatGenericFileCell, andModel model: ChatGenericFileCellModel, withMessage message: OCTMessageAbstract) {
        cell.progressObject = nil

        model.fileName = message.messageFile!.fileName
        model.fileSizeBytes = message.messageFile!.fileSize
        // KHANDAQ (redesign stage 3, E8): in the language chosen in the app, which ByteCountFormatter ignores.
        model.fileSize = AirByteSize.text(message.messageFile!.fileSize)
        model.fileUTI = message.messageFile!.fileUTI
        model.reactionsDisplay = ChatReactionsFormat.display(from: message.reactionsJSON)
        // KHANDAQ (redesign stage 3, E8): the preview box takes its size before the image loads, so the
        // row sizes itself once; the person's name and presence say what a file of ours waits for.
        model.previewSize = inlinePreviewSize(for: message)
        if let friend = friend, !friend.isInvalidated {
            model.personName = AirTranscript.shortName(personName(friend))
        }
        model.personOnline = isFriendOnline

        // Telegram-style merged caption (the text message sent right after this file).
        if messageSearchQuery.isEmpty {
            let idx = messages.indexOfObject(message)
            let captionMsg = (idx >= 0 && idx < messages.count) ? captionMessage(forFileAtStorageIndex: idx) : nil
            // KHANDAQ (#52): strip reply/mention wire markup from a media caption (parity with groups).
            model.caption = captionMsg.flatMap { MessageReplyHelper.plainBody(for: $0) }
        }
        else {
            model.caption = nil
        }

        // KHANDAQ (#15): render 1:1 voice notes as a player (parity with groups).
        let isVoice = VoiceMessageHelper.isVoiceMessage(fileName: message.messageFile?.fileName,
                                                        filePath: message.messageFile?.filePath())
        model.isVoiceMessage = isVoice
        model.voiceMessageId = message.uniqueIdentifier

        // KHANDAQ (#23): outgoing delivery checkmark (parity with text + group files). A finished
        // outgoing transfer means the peer pulled the whole file, so treat it as delivered.
        if let outgoingModel = model as? ChatOutgoingFileCellModel {
            outgoingModel.delivered = (message.messageFile?.isDelivered ?? false)
                || (message.messageFile?.fileType == .ready)
        }
        if isVoice {
            model.fileName = VoiceMessageHelper.displayFileName(for: message.messageFile?.fileName)
            if message.messageFile?.fileType == .ready, let path = message.messageFile?.filePath() {
                model.voiceDuration = VoiceMessageHelper.audioDuration(at: path)
            }
        }

        switch message.messageFile!.fileType {
            case .waitingConfirmation:
                model.state = .waitingConfirmation
            case .loading:
                model.state = .loading

                let bridge = ChatProgressBridge()
                cell.progressObject = bridge
                _ = try? self.submanagerFiles.add(bridge, forFileTransfer: message)
                self.trackFileTransferStall(for: message)
            case .paused:
                model.state = .paused
                if let messageId = message.uniqueIdentifier {
                    self.stopWatchingStall(of: messageId)
                }
            case .canceled:
                model.state = .cancelled
                if let messageId = message.uniqueIdentifier {
                    self.stopWatchingStall(of: messageId)
                }
            case .ready:
                model.state = .done
                if let messageId = message.uniqueIdentifier {
                    self.stopWatchingStall(of: messageId)
                }
        }

        model.cancelHandle = { [weak self] in
            do {
                try self?.submanagerFiles.cancelFileTransfer(message)
            }
            catch let error as NSError {
                handleErrorWithType(.cancelFileTransfer, error: error)
            }
        }

        model.pauseOrResumeHandle = { [weak self] in
            let isPaused = (message.messageFile!.pausedBy.rawValue & OCTMessageFilePausedBy.user.rawValue) != 0

            do {
                try self?.submanagerFiles.pauseFileTransfer(!isPaused, message: message)
            }
            catch let error as NSError {
                handleErrorWithType(.cancelFileTransfer, error: error)
            }
        }

        if !message.isOutgoing() {
            model.startLoadingHandle = { [weak self] in
                self?.submanagerFiles.acceptFileTransfer(message) { (error: Error) -> Void in
                    handleErrorWithType(.acceptIncomingFile, error: error as NSError)
                }
            }

            model.retryHandle = { [weak self] in
                self?.submanagerFiles.acceptFileTransfer(message) { (error: Error) -> Void in
                    handleErrorWithType(.acceptIncomingFile, error: error as NSError)
                }
            }
        }
        else {
            model.retryHandle = { [weak self] in
                self?.submanagerFiles.retrySendingFile(message) { (error: Error) in
                    handleErrorWithType(.sendFileToFriend, error: error as NSError)
                }
            }
        }

        model.openHandle = { [weak self] in
            guard let sself = self else {
                return
            }
            let qlDataSource = FilePreviewControllerDataSource(chat: sself.chat, submanagerObjects: sself.submanagerObjects)
            guard let index = qlDataSource.indexOfMessage(message) else {
                return
            }

            sself.delegate?.chatPrivateControllerShowQuickLookController(sself, dataSource: qlDataSource, selectedIndex: index)
        }

        if isVoice {
            model.voicePlayToggleHandle = { [weak self] in
                let file = message.messageFile

                if file?.fileType == .ready,
                   let path = file?.filePath(),
                   let messageId = message.uniqueIdentifier {
                    ChatVoiceMessagePlayer.shared.togglePlayback(messageId: messageId, filePath: path)
                    return
                }

                // KHANDAQ (#156): autodownload defaults to Never and the voice bubble hides the
                // file-box download/retry buttons, so an incoming voice note could never be
                // fetched at all. Tapping play on a not-yet-downloaded note starts the transfer.
                if !message.isOutgoing(),
                   (file?.fileType == .waitingConfirmation || file?.fileType == .paused) {
                    self?.submanagerFiles.acceptFileTransfer(message) { (error: Error) -> Void in
                        handleErrorWithType(.acceptIncomingFile, error: error as NSError)
                    }
                }
            }
            // KHANDAQ (Android parity): scrub the waveform
            model.voiceSeekHandle = { fraction in
                guard message.messageFile?.fileType == .ready,
                      let path = message.messageFile?.filePath(),
                      let messageId = message.uniqueIdentifier else {
                    return
                }
                ChatVoiceMessagePlayer.shared.seek(
                    messageId: messageId, filePath: path, fraction: fraction)
            }
        }
    }

    func maybeLoadImageForCellAtPath(_ cell: UITableViewCell, indexPath: IndexPath) {
        let message = messageEntry(atDisplayIndex: indexPath.row).message

        guard let messageFile = message.messageFile else {
            return
        }

        guard let file = messageFile.filePath() else {
            return
        }

        if messageFile.fileSize >= Constants.MaxImageSizeToShowInline {
            return
        }

        // KHANDAQ (#120): infer the UTI from the extension — outgoing files often have no stored fileUTI,
        // so a sent video (especially .mpg) fell through to the generic-file bubble. Matches the group.
        let uti = inferredFileUTI(for: messageFile)
        let fileName = messageFile.fileName
        let fileCell = (cell as? ChatIncomingFileCell) ?? (cell as? ChatOutgoingFileCell)

        if UTTypeConformsTo(uti as CFString? ?? "" as CFString, kUTTypeImage) {
            if let pixelSize = mediaPixelSize(forFileAt: file, isVideo: false) {
                fileCell?.loadingView.setPreviewSize(pixelSize)
            }
            if let image = imageCache.object(forKey: file as AnyObject) as? UIImage {
                applyInlinePreview(image, durationText: nil, to: cell)
            }
            else {
                loadImageForCellAtIndexPath(indexPath, fromFile: file)
            }
            return
        }

        if ChatFileMediaLoader.isVideoFile(uti: uti, fileName: fileName) {
            if let pixelSize = mediaPixelSize(forFileAt: file, isVideo: true) {
                fileCell?.loadingView.setPreviewSize(pixelSize)
            }
            if let preview = ChatFileMediaLoader.cachedPreview(for: file) {
                applyInlinePreview(preview.image, durationText: preview.durationText, isVideo: true, to: cell)
            }
            else {
                loadVideoPreviewForCellAtIndexPath(indexPath, fromFile: file)
            }
        }
    }

    func applyInlinePreview(_ image: UIImage, durationText: String?, isVideo: Bool = false, to cell: UITableViewCell) {
        let fileCell = (cell as? ChatIncomingFileCell) ?? (cell as? ChatOutgoingFileCell)
        fileCell?.setButtonImage(image)

        if isVideo {
            // KHANDAQ (#120): video gets a play overlay + duration on both incoming and outgoing cells.
            // The 1:1 path previously set neither, so a sent video rendered as a plain file bubble.
            (fileCell as? ChatIncomingFileCell)?.setVideoPlayOverlay()
            (fileCell as? ChatOutgoingFileCell)?.setVideoPlayOverlay()
            if let durationText = durationText {
                (fileCell as? ChatIncomingFileCell)?.setVideoDurationLabel(durationText)
                (fileCell as? ChatOutgoingFileCell)?.setVideoDurationLabel(durationText)
            }
        }
    }

    func loadVideoPreviewForCellAtIndexPath(_ indexPath: IndexPath, fromFile: String) {
        ChatFileMediaLoader.loadPreview(at: fromFile) { [weak self] preview in
            guard let preview = preview else {
                return
            }

            self?.imageCache.setObject(preview.image, forKey: fromFile as AnyObject)

            DispatchQueue.main.async {
                let optionalCell = self?.tableView?.cellForRow(at: indexPath)
                guard let cell = optionalCell else {
                    return
                }
                self?.applyInlinePreview(preview.image, durationText: preview.durationText, isVideo: true, to: cell)
            }
        }
    }

    func loadImageForCellAtIndexPath(_ indexPath: IndexPath, fromFile: String) {
        DispatchQueue.global(qos: .default).async { [weak self] in
            guard var image = UIImage(contentsOfFile: fromFile) else {
                return
            }

            var size = image.size
            guard size.width > 0 || size.height > 0 else {
                return
            }

            let delta = (size.width > size.height) ? (Constants.MaxInlineImageSide / size.width) : (Constants.MaxInlineImageSide / size.height)

            size.width *= delta
            size.height *= delta

            image = image.scaleToSize(size)
            self?.imageCache.setObject(image, forKey: fromFile as AnyObject)

            DispatchQueue.main.async {
                let optionalCell = self?.tableView?.cellForRow(at: indexPath)
                guard let cell = optionalCell else {
                    return
                }

                self?.applyInlinePreview(image, durationText: nil, to: cell)
            }
        }
    }

    func trackFileTransferStall(for message: OCTMessageAbstract) {
        guard let messageId = message.uniqueIdentifier else {
            return
        }

        // The watcher hears the transfer through a listener of its own as well, not only through the
        // bridge of a row on screen.
        let listener = ChatProgressBridge()
        stallListeners[messageId] = listener
        _ = try? submanagerFiles?.add(listener, forFileTransfer: message)

        FileTransferStallWatcher.shared.track(messageId: messageId) { [weak self] in
            guard let self = self else {
                return
            }
            self.stallListeners.removeValue(forKey: messageId)
            // Only a transfer still running in the conversation on screen: one that ended meanwhile, or a
            // conversation left or covered by another screen, is no stall to tell about (and to cancel).
            guard self.isViewLoaded, self.view.window != nil, !message.isInvalidated,
                  message.messageFile?.fileType == .loading else {
                return
            }
            UIAlertController.showErrorWithMessage(String(localized: "file_transfer_stalled"), retryBlock: nil)
            _ = try? self.submanagerFiles?.cancelFileTransfer(message)
        }
    }

    func stopWatchingStall(of messageId: String) {
        FileTransferStallWatcher.shared.stop(messageId: messageId)
        stallListeners.removeValue(forKey: messageId)
    }

    func toggleTableViewEditing(_ editing: Bool, animated: Bool) {
        tableView?.setEditing(editing, animated: animated)

        tableViewTapGestureRecognizer.isEnabled = !editing
        editMessagesToolbar.isHidden = !editing

        if editing {
            _ = chatInputView.resignFirstResponder()

            navigationItem.leftBarButtonItems = [UIBarButtonItem(
                title: String(localized: "delete_all_messages"),
                style: .plain,
                target: self,
                action: #selector(ChatPrivateController.deleteAllMessagesButtonPressed(_:)))]

            navigationItem.rightBarButtonItems = [UIBarButtonItem(
                    title: String(localized: "alert_cancel"),
                    style: .plain,
                    target: self,
                    action: #selector(ChatPrivateController.cancelEditingButtonPressed))]
        }
        else {
            // Location sharing lives in the "+" attachment menu; the item is kept for displayalert(),
            // which redraws it, but it is not in the header.
            let locationImage = UIImage(named: "location-call-medium")!.withRenderingMode(.alwaysOriginal)
            locationButton = UIBarButtonItem(image: locationImage, style: .plain, target: self, action: #selector(ChatPrivateController.locationButtonPressed))

            if (AppDelegate.location_sharing_contact_pubkey != "-1") {
                let locationImage = UIImage(named: "location-call-activated-medium")!.withRenderingMode(.alwaysOriginal)
                locationButton.setBackgroundImage(locationImage, for: .normal, barMetrics: .default)
            }

            // KHANDAQ (redesign stage 2): the icon buttons of the design system (a 20 point glyph in a
            // 44 point target, grey until pressed). A person: call, video call and the search behind its
            // button; their files are in the dossier. "For yourself" and a removed contact: files and
            // search. rightBarButtonItems[0] is the slot at the trailing edge.
            audioButton = airBarButton(icon: "phone", label: String(localized: "air_chat_call"),
                                       identifier: "air.chat.call", action: #selector(ChatPrivateController.audioCallButtonPressed))
            videoButton = airBarButton(icon: "video", label: String(localized: "air_chat_video"),
                                       identifier: "air.chat.video", action: #selector(ChatPrivateController.videoCallButtonPressed))
            searchButton = airBarButton(icon: "search", label: String(localized: "air_chat_search"),
                                        identifier: "air.chat.search", action: #selector(ChatPrivateController.searchButtonPressed))

            // KHANDAQ (Figma): the tile and the name, left-aligned next to the back button.
            installTitleLeftItem()

            if let friend = friend {
                filesButton = nil
                navigationItem.rightBarButtonItems = [audioButton, videoButton, searchButton]
                if !friend.isInvalidated {
                    setBarItem(videoButton, enabled: friend.isConnected)
                }
            }
            else {
                let files = airBarButton(icon: "file", label: String(localized: "attachments_title"),
                                         identifier: "air.chat.files", action: #selector(ChatPrivateController.attachmentsButtonPressed))
                filesButton = files
                navigationItem.rightBarButtonItems = [files, searchButton]
            }
            updateTitleWidth()
        }
    }

    func showMessageDeletionConfirmation(messagesCount: Int,
                                         showFromItem barButtonItem: UIBarButtonItem,
                                         deleteClosure: @escaping () -> Void) {
        let deleteButtonText = messagesCount > 1 ?
            String(localized: "delete_multiple_messages") + " (\(messagesCount))" :
            String(localized: "delete_single_message")

        let alert = UIAlertController(title: nil, message: nil, preferredStyle: .actionSheet)
        alert.popoverPresentationController?.barButtonItem = barButtonItem

        alert.addAction(UIAlertAction(title: deleteButtonText, style: .destructive) { _ -> Void in
            deleteClosure()
        })

        alert.addAction(UIAlertAction(title: String(localized: "alert_cancel"), style: .cancel, handler: nil))

        present(alert, animated: true, completion: nil)
    }

    // KHANDAQ (#205): Telegram-style delete scope. When the selection contains at least one OWN
    // (outgoing) message in a real 1:1 chat, offer «Удалить у всех» (retract on the peer) vs
    // «Удалить у меня» (local only) so it's clear where the message is removed. Otherwise (only
    // incoming, or Saved Messages self-chat) just a single local «Удалить».
    func showDeleteScopeConfirmation(messagesCount: Int,
                                     canDeleteForEveryone: Bool,
                                     barButtonItem: UIBarButtonItem?,
                                     sourceView: UIView?,
                                     deleteForMe: @escaping () -> Void,
                                     deleteForEveryone: @escaping () -> Void) {
        let alert = UIAlertController(title: nil, message: nil, preferredStyle: .actionSheet)
        if let barButtonItem = barButtonItem {
            alert.popoverPresentationController?.barButtonItem = barButtonItem
        } else if let sourceView = sourceView {
            alert.popoverPresentationController?.sourceView = sourceView
            alert.popoverPresentationController?.sourceRect = sourceView.bounds
        }

        if canDeleteForEveryone && !chat.isSavedMessages {
            alert.addAction(UIAlertAction(title: String(localized: "delete_for_everyone"), style: .destructive) { _ in
                deleteForEveryone()
            })
            alert.addAction(UIAlertAction(title: String(localized: "delete_for_me"), style: .destructive) { _ in
                deleteForMe()
            })
        } else {
            let title = messagesCount > 1 ?
                String(localized: "delete_multiple_messages") + " (\(messagesCount))" :
                String(localized: "delete_single_message")
            alert.addAction(UIAlertAction(title: title, style: .destructive) { _ in
                deleteForMe()
            })
        }

        alert.addAction(UIAlertAction(title: String(localized: "alert_cancel"), style: .cancel, handler: nil))
        present(alert, animated: true, completion: nil)
    }
}

// KHANDAQ: 1:1 message search (mirrors the group implementation — filter the list to matches).
extension ChatPrivateController: UISearchResultsUpdating {
    func updateSearchResults(for searchController: UISearchController) {
        let newQuery = searchController.searchBar.text ?? ""
        guard newQuery != messageSearchQuery else {
            return
        }
        messageSearchQuery = newQuery
        tableView?.reloadData()
    }

    func displayableRowCount() -> Int {
        return displayMap()?.count ?? min(visibleMessages, messages.count)
    }

    func messageEntry(atDisplayIndex index: Int) -> (message: OCTMessageAbstract, storageIndex: Int) {
        if let map = displayMap() {
            if index >= 0, index < map.count, map[index] < messages.count {
                return (messages[map[index]], map[index])
            }
        }
        else if index >= 0, index < messages.count {
            return (messages[index], index)
        }

        // A row the table still holds after its message went (the chat was just emptied): an empty
        // message draws an empty row instead of reading past the end.
        guard messages.count > 0 else {
            return (OCTMessageAbstract(), 0)
        }
        let safeIndex = min(max(0, index), messages.count - 1)
        return (messages[safeIndex], safeIndex)
    }

    /// KHANDAQ (redesign stage 2): the storage index of every row of the log, newest first. The log
    /// leaves out the messages in the tray and, while searching, what does not match; nil when it shows
    /// every loaded message, so a row is its storage index.
    fileprivate func displayMap() -> [Int]? {
        let loaded = min(visibleMessages, messages.count)
        let key = DisplayMapKey(loaded: loaded, total: messages.count, query: messageSearchQuery, generation: displayGeneration)
        if let cache = displayMapCache, cache.key == key {
            return cache.map
        }

        var map: [Int]?
        if !messageSearchQuery.isEmpty || !trayIds.isEmpty {
            let hidden = Set(trayIds)
            var rows: [Int] = []
            rows.reserveCapacity(loaded)
            for index in 0..<loaded {
                let message = messages[index]
                if !hidden.isEmpty, let id = message.uniqueIdentifier, hidden.contains(id) {
                    continue
                }
                if !messageSearchQuery.isEmpty, !MessageSearchHighlighter.messageMatches(message, query: messageSearchQuery) {
                    continue
                }
                rows.append(index)
            }
            map = rows
        }
        displayMapCache = (key: key, map: map)
        return map
    }

    /// The row of the log that shows the message at `storageIndex`; nil when it is not loaded or left out.
    func displayIndex(forStorageIndex storageIndex: Int) -> Int? {
        guard storageIndex >= 0, storageIndex < min(visibleMessages, messages.count) else {
            return nil
        }
        guard let map = displayMap() else {
            return storageIndex
        }
        var low = 0
        var high = map.count - 1
        while low <= high {
            let middle = (low + high) / 2
            if map[middle] == storageIndex {
                return middle
            }
            if map[middle] < storageIndex {
                low = middle + 1
            }
            else {
                high = middle - 1
            }
        }
        return nil
    }
}

// MARK: - Air conversation (redesign stage 2)

// The conversation as design/redesign/02-plan.md draws it (screens 5, 6, 7, 10 and 16): the band under
// the header with the link and the day strip, the transcript, the "On its way" tray and the refused
// messages between the log and the field, the hint under the field, and the dossier unfolded from the
// header. The words come from AirConversationModel, the views from AirConversationViews.
extension ChatPrivateController {
    /// The person's name as the header, the tray and the hints show it.
    fileprivate func personName(_ friend: OCTFriend) -> String {
        return friend.nickname.isEmpty ? friend.publicKey : friend.nickname
    }

    fileprivate func currentReach(for friend: OCTFriend) -> AirReach {
        return AirReach.friend(connection: friend.connectionStatus,
                               lastSeenOnlineInterval: friend.lastSeenOnlineInterval,
                               now: Date().timeIntervalSince1970,
                               selfLinkSettling: SelfConnectionTracker.friendLinksMayStillBeConnecting)
    }

    /// A message sent now would arrive now.
    fileprivate var isFriendOnline: Bool {
        guard let friend = friend, !friend.isInvalidated else {
            return false
        }
        return currentReach(for: friend).isLive
    }

    // MARK: Building

    fileprivate func createAirConversationViews() {
        pinnedStrip = AirPinnedStripView()
        pinnedStrip.isHidden = true
        pinnedStrip.addTarget(self, action: #selector(ChatPrivateController.pinnedBannerTapped), for: .touchUpInside)
        pinnedStrip.closeButton.addTarget(self, action: #selector(ChatPrivateController.pinnedBannerClosePressed), for: .touchUpInside)

        var topViews: [UIView] = []
        if friend != nil {
            let band = AirPresenceBandView()
            band.addTarget(self, action: #selector(ChatPrivateController.presenceBandTapped), for: .touchUpInside)
            presenceBand = band
            topViews.append(band)
        }
        topViews.append(pinnedStrip)
        topStack = UIStackView(arrangedSubviews: topViews)
        topStack.axis = .vertical
        view.addSubview(topStack)

        failedList = AirFailedListView()
        failedList.onRetry = { [weak self] id in
            self?.retryFailedSend(id: id)
        }
        failedList.onItemTap = { [weak self] id, source in
            self?.presentFailedSendActions(id: id, source: source)
        }

        trayView = AirTrayView()
        trayView.isHidden = true
        trayView.layoutHost = { [weak self] in
            self?.view.layoutIfNeeded()
        }
        trayView.onItemTap = { [weak self] id, source in
            self?.presentTrayActions(id: id, source: source)
        }
        trayView.onItemAction = { [weak self] id, action in
            self?.performTrayAction(action, id: id)
        }

        bottomStack = UIStackView(arrangedSubviews: [failedList, trayView])
        bottomStack.axis = .vertical
        view.addSubview(bottomStack)
    }

    fileprivate func startAirConversation() {
        let center = NotificationCenter.default
        airObservers.append(center.addObserver(forName: .airLocalStoreDidChange, object: nil, queue: .main) { [weak self] _ in
            self?.localMarksChanged()
        })
        airObservers.append(center.addObserver(forName: .airFailedSendsDidChange, object: nil, queue: .main) { [weak self] note in
            guard let self = self, (note.userInfo?["chatId"] as? String) == self.chat.uniqueIdentifier else {
                return
            }
            self.refreshFailedSends(animated: true)
        })
        airObservers.append(center.addObserver(forName: AirSelfNetwork.didChange, object: nil, queue: .main) { [weak self] _ in
            // Our own node went off or came back (E4): the hint under the field says which.
            self?.refreshComposeState(animated: true)
        })
        airObservers.append(center.addObserver(forName: .UIContentSizeCategoryDidChange, object: nil, queue: .main) { [weak self] _ in
            // The time column and the fonts of the entries follow the text size; the refused messages
            // are laid out again so "Retry" stays at the text column.
            self?.tableView?.reloadData()
            self?.refreshFailedSends(animated: false)
            self?.updateTitleWidth()
        })

        wasFriendOnline = isFriendOnline
        refreshTray(animated: false)
        refreshFailedSends(animated: false)
        refreshComposeState(animated: false)
        updateComposeLimit()
        tableView?.reloadData()
    }

    /// The header buttons: the icon button of the design system as a bar item.
    fileprivate func airBarButton(icon: String, label: String, identifier: String, action: Selector) -> UIBarButtonItem {
        let button = AirIconButton(icon: icon, label: label)
        button.accessibilityIdentifier = identifier
        button.addTarget(self, action: action, for: .touchUpInside)
        let item = UIBarButtonItem(customView: button)
        item.accessibilityLabel = label
        return item
    }

    /// A bar item with a custom view is not disabled by its own isEnabled, so both are set.
    fileprivate func setBarItem(_ item: UIBarButtonItem?, enabled: Bool) {
        guard let item = item else {
            return
        }
        item.isEnabled = enabled
        (item.customView as? UIControl)?.isEnabled = enabled
    }

    /// At the accessibility text sizes the log keeps its room (AirLogRoom): the presence band folds away
    /// while the keyboard is up and where the log would have less than its share with it, the tray folds
    /// away while the keyboard is up, and the refused messages and the tray keep to half of the room
    /// between the band and the field. The hint under the field says what the band and the tray said,
    /// and the title opens the dossier the band opened.
    ///
    /// The band is arranged in a stack. Hidden in the middle of a layout pass (the tray opens inside one),
    /// the stack gave its room back to the constraints while the screen kept the frames it had: at AX5 the
    /// words of the band went, its 136 points stayed above a log of 145, and the open tray stood over the
    /// field. So a fold decided while laying out is made on the next turn of the run loop, with a layout of
    /// the whole screen.
    fileprivate func keepRoomForLog(whileLayingOut: Bool = false) {
        let accessible = traitCollection.preferredContentSizeCategory.isAccessibilityCategory
        if let half = stripsHalfConstraint, half.isActive != accessible {
            half.isActive = accessible
        }
        trayView?.foldsAway = accessible && isKeyboardUp
        guard let band = presenceBand, let tableView = tableView else {
            return
        }
        if !band.isHidden, band.bounds.height > 0 {
            shownBandHeight = band.bounds.height
        }
        var bandHeight = band.bounds.height
        if band.isHidden {
            let fitting = band.systemLayoutSizeFitting(CGSize(width: view.bounds.width, height: UILayoutFittingCompressedSize.height),
                                                       withHorizontalFittingPriority: .required,
                                                       verticalFittingPriority: .fittingSizeLevel)
            bandHeight = max(shownBandHeight, fitting.height)
        }
        let folds = AirLogRoom.bandFolds(accessible: accessible, keyboardUp: isKeyboardUp, log: tableView.bounds.height,
                                         band: bandHeight, bandShown: !band.isHidden, screen: view.bounds.height)
        guard band.isHidden != folds else {
            return
        }
        guard whileLayingOut else {
            band.isHidden = folds
            return
        }
        guard !isBandFoldPending else {
            return
        }
        isBandFoldPending = true
        DispatchQueue.main.async { [weak self] in
            guard let self = self else {
                return
            }
            self.isBandFoldPending = false
            let changes = {
                self.keepRoomForLog()
                self.view.setNeedsLayout()
                self.view.layoutIfNeeded()
            }
            guard self.view.window != nil else {
                changes()
                return
            }
            UIView.animate(withDuration: KQMotion.duration(KQMotion.layout), delay: 0, options: [.beginFromCurrentState, .curveEaseOut],
                           animations: changes, completion: nil)
        }
    }

    /// The name in the header runs as wide as the bar leaves it next to the back button and the buttons.
    fileprivate func updateTitleWidth() {
        guard titleView != nil else {
            return
        }
        let slot = KQSize.targetIos + KQSpace.x8
        let items = CGFloat(navigationItem.rightBarButtonItems?.count ?? 0)
        let titleChrome = KQSize.tileDesk + KQSpace.x8 + KQSpace.x4 + KQSize.iconSmall + 2 * KQSpace.x4
        let available = view.bounds.width - slot - items * slot - titleChrome - KQSpace.x16
        titleView.maximumNameWidth = max(2 * KQSize.tilePhone, floor(available))
    }

    // MARK: Presence

    fileprivate func updatePresenceBand(for friend: OCTFriend) {
        guard !friend.isInvalidated else {
            return
        }
        let reach = currentReach(for: friend)
        let now = Date()
        let presence = AirPresenceLine.text(reach: reach,
                                            onlineSince: AirLocalStore.shared.onlineSince(publicKey: friend.publicKey),
                                            isTyping: friend.isTyping,
                                            time: timeFormatter.string(from:))
        titleView.spokenPresence = presence

        guard let band = presenceBand else {
            return
        }
        let hours = AirLocalStore.shared.hours(publicKey: friend.publicKey)
        let strip = AirDayStrip.make(hours: hours, now: now)
        band.configure(link: AirLinkState(reach: reach),
                       presence: presence,
                       strip: strip,
                       nowOnline: reach.isLive,
                       stripHint: dayStripHint(hours: hours, strip: strip, now: now),
                       stripSpoken: strip.spokenText(locale: AppLanguage.locale, nowOnline: reach.isLive))
    }

    /// Beside the strip: when the person is usually online, or the hours of the last day, or why it is empty.
    fileprivate func dayStripHint(hours: Set<Int>, strip: AirDayStrip, now: Date) -> String {
        if let best = AirBestHours.find(hours: hours, now: now) {
            return String(localized: "air_day_best_format", best.from, best.to)
        }
        if strip.hasMarks {
            let ranges = strip.ranges.map { String(localized: "air_day_range_format", $0.from, $0.to) }
            return String(localized: "air_day_hint_ranges_format", ranges.joined(separator: ", "))
        }
        return String(localized: "air_day_empty")
    }

    @objc fileprivate func presenceBandTapped() {
        toggleDossier()
    }

    /// The person came online or went away, or something about them changed: the hint, the tray, the
    /// marks of our messages and the open dossier follow.
    fileprivate func friendReachabilityChanged() {
        let online = isFriendOnline
        let onlineChanged = wasFriendOnline != nil && wasFriendOnline != online
        wasFriendOnline = online
        refreshComposeState(animated: true)
        let trayChanged = refreshTray(animated: true)
        if (trayChanged || onlineChanged) && !isTrayDeparting {
            tableView?.reloadData()
        }
        refreshDossierIfNeeded()
    }

    fileprivate func localMarksChanged() {
        guard let friend = friend, !friend.isInvalidated else {
            return
        }
        updatePresenceBand(for: friend)
        refreshDossierIfNeeded()
    }

    // MARK: Transcript

    /// The marks of an entry in the transcript: its day, the unread band, the time column and the author.
    /// A file drawn again in place takes them too: a fresh model without them hung a file that had just
    /// been delivered under the run before it, with no time and no day, and VoiceOver read it without who
    /// sent it and when (redesign stage 5).
    fileprivate func markTranscriptEntry(_ model: ChatMovableDateCellModel, message: OCTMessageAbstract, row: Int) {
        // KHANDAQ (#99): tag the row with a day separator when it begins a new calendar day.
        model.dateSeparator = daySeparatorString(forDisplayIndex: row)
        // KHANDAQ (Figma): the "Unread messages" band on the first unread incoming message.
        model.unreadSeparator = (unreadSeparatorMessageId != nil && message.uniqueIdentifier == unreadSeparatorMessageId)
        // KHANDAQ (redesign stage 2): day and unread marks as transcript marks, the author over the
        // first entry of each run.
        model.transcriptMarks = true
        // Texts, files and calls alike (redesign stage 3, E8 and E9).
        configureTranscriptEntry(model, message: message, row: row)
    }

    /// The time column, the author over the first entry of a run, and who wrote it for VoiceOver.
    fileprivate func configureTranscriptEntry(_ entry: ChatMovableDateCellModel, message: OCTMessageAbstract, row: Int) {
        entry.timeText = entry.dateString
        guard !chat.isSavedMessages else {
            // Notes to yourself: no author and no marks.
            entry.authorText = nil
            entry.spokenAuthor = ""
            return
        }

        let outgoing = message.isOutgoing()
        let fullName: String
        if outgoing {
            fullName = String(localized: "air_you")
            entry.authorColor = KQColor.ink2
        }
        else if let friend = friend, !friend.isInvalidated {
            fullName = personName(friend)
            entry.authorColor = AirTone.color(at: AirTone.index(for: friend.publicKey))
        }
        else {
            fullName = String(localized: "contact_deleted")
            entry.authorColor = KQColor.ink2
        }
        entry.spokenAuthor = fullName

        let previous = row + 1 < displayableRowCount() ? runKey(forDisplayIndex: row + 1) : nil
        let startsRun = entry.unreadSeparator || AirTranscript.startsRun(runKey(forDisplayIndex: row), after: previous)
        entry.authorText = startsRun ? (outgoing ? fullName : AirTranscript.shortName(fullName)) : nil
    }

    fileprivate func runKey(forDisplayIndex row: Int) -> AirRunKey {
        let entry = messageEntry(atDisplayIndex: row)
        let message = entry.message
        let outgoing = message.isOutgoing()
        let date = (!outgoing && message.tssent != 0) ? Date(timeIntervalSince1970: TimeInterval(message.tssent)) : message.date()
        // A caption drawn inside its photo is not a text entry of its own.
        let merged = messageSearchQuery.isEmpty && isMergedCaptionRow(storageIndex: entry.storageIndex)
        return AirRunKey(author: outgoing ? "" : "peer", date: date, isText: message.messageText != nil && !merged)
    }

    /// The row of `id` rises into the log when it next appears; forgotten after a moment, so a row
    /// scrolled to later does not rise.
    fileprivate func scheduleArrival(id: String, delay: TimeInterval) {
        arrivingIds[id] = delay
        DispatchQueue.main.asyncAfter(deadline: .now() + 1 + delay) { [weak self] in
            self?.arrivingIds.removeValue(forKey: id)
        }
    }

    /// The words of a text without the reply header, as one line of the tray shows them.
    fileprivate func plainEntryText(_ raw: String) -> String {
        let body = MessageReplyHelper.parse(raw).bodyText
        if let location = LocationMessage.parse(body) {
            return String(format: "%.5f, %.5f", location.latitude, location.longitude)
        }
        return MessageReplyHelper.normalizeForwardedHeader(body)
    }

    // MARK: Tray

    /// Our texts waiting for the person, oldest first. Only while they are not online: once they are,
    /// objcTox sends every undelivered text again and the messages belong to the log.
    fileprivate func trayItems() -> [AirQueueItem] {
        guard let friend = friend, !friend.isInvalidated, !chat.isSavedMessages, !isFriendOnline else {
            return []
        }
        // KHANDAQ (redesign stage 3, E8): a file that could not be offered while the person is away waits
        // in the tray too, as the list's "In transit" counts it (AirListDataSource.transitPredicate).
        let waiting = messages.objects(with: NSPredicate(
            format: "senderUniqueIdentifier == nil AND ((messageText != nil AND messageText.isDelivered == NO) OR (messageFile != nil AND messageFile.fileType == %d AND messageFile.internalFileNumber == %d))",
            OCTMessageFileType.waitingConfirmation.rawValue,
            Int32(bitPattern: kOCTToxFileNumberFailure)))
        var items: [AirQueueItem] = []
        for index in 0..<waiting.count {
            guard let message = waiting.object(at: index), !message.isInvalidated, !message.isCaption,
                  let id = message.uniqueIdentifier else {
                continue
            }
            let text: String
            if let file = message.messageFile {
                let isVoice = VoiceMessageHelper.isVoiceMessage(fileName: file.fileName, filePath: file.filePath())
                let name = isVoice ? String(localized: "air_voice_spoken") : (file.fileName ?? "")
                text = String(localized: "air_tray_file_format", name, AirByteSize.text(file.fileSize))
            }
            else if let raw = message.messageText?.text, !raw.isEmpty {
                text = plainEntryText(raw)
            }
            else {
                continue
            }
            items.append(AirQueueItem(id: id, text: text, time: timeFormatter.string(from: message.date()), status: .transit))
        }
        return items.reversed()
    }

    /// Brings the tray in line with the messages. Returns true when the log has to show other rows.
    @discardableResult
    fileprivate func refreshTray(animated: Bool) -> Bool {
        guard trayView != nil else {
            return false
        }
        if isTrayDeparting {
            needsTrayRefresh = true
            return false
        }
        let items = trayItems()
        let ids = items.map { $0.id }
        if animated, ids.isEmpty, !trayIds.isEmpty, isFriendOnline, !trayView.isHidden, view.window != nil {
            departTray()
            return false
        }

        let changed = ids != trayIds
        if changed {
            // A message that left the tray without the person coming online (a late receipt) rises too.
            for id in trayIds where !ids.contains(id) {
                scheduleArrival(id: id, delay: 0)
            }
            trayIds = ids
            displayGeneration += 1
        }

        var detail = ""
        if let friend = friend, !friend.isInvalidated {
            let name = AirTranscript.shortName(personName(friend))
            let hasPush = !(friend.pushToken ?? "").isEmpty
            detail = String(localized: hasPush ? "air_tray_wait_push_format" : "air_tray_wait_format", name)
        }
        let wasHidden = trayView.isHidden
        trayView.configure(items: items,
                           collapsed: String(localized: "air_transit_format", items.count),
                           expanded: String(localized: "air_tray_head_format", items.count),
                           detail: detail)
        if items.isEmpty {
            trayView.setExpanded(false, animated: false)
        }
        if animated, wasHidden != trayView.isHidden, view.window != nil {
            UIView.animate(withDuration: KQMotion.duration(KQMotion.layout), delay: 0, options: [.curveEaseOut, .beginFromCurrentState], animations: {
                self.view.layoutIfNeeded()
            }, completion: nil)
        }
        updateTableHeaderView()
        return changed
    }

    /// The person came online: the messages leave the tray one by one, 90 ms apart, and rise into the
    /// log in the same order; then the tray folds. With Reduce Motion they fade together.
    fileprivate func departTray() {
        let leaving = trayIds
        isTrayDeparting = true
        trayIds = []
        displayGeneration += 1

        let step = UIAccessibilityIsReduceMotionEnabled() ? 0 : KQMotion.stagger
        for (index, id) in leaving.enumerated() {
            // The clock they had in the tray turns into the mark of the log.
            statusMemory[id] = .transit
            scheduleArrival(id: id, delay: step * Double(index))
        }
        tableView?.reloadData()

        trayView.depart(ids: leaving) { [weak self] in
            self?.trayView.fold { [weak self] in
                guard let self = self else {
                    return
                }
                self.isTrayDeparting = false
                self.trayView.setExpanded(false, animated: false)
                var reload = self.needsLogReload
                self.needsLogReload = false
                if self.needsTrayRefresh {
                    self.needsTrayRefresh = false
                    reload = self.refreshTray(animated: true) || reload
                }
                if reload {
                    self.tableView?.reloadData()
                    self.updateEncryptionBannerVisibility()
                }
                self.updateTableHeaderView()
            }
        }
        UIAccessibilityPostNotification(UIAccessibilityAnnouncementNotification, String(localized: "air_tray_departed"))
    }

    fileprivate func trayMessage(id: String) -> OCTMessageAbstract? {
        guard trayIds.contains(id),
              let message = submanagerObjects.object(withUniqueIdentifier: id, for: .messageAbstract) as? OCTMessageAbstract,
              !message.isInvalidated,
              message.messageText?.isDelivered == false || message.messageFile?.fileType == .waitingConfirmation else {
            return nil
        }
        return message
    }

    /// Same rule as in the log: a text with a msgV3 hash, not a reply and not a location.
    fileprivate func canEditTrayMessage(_ message: OCTMessageAbstract) -> Bool {
        guard let text = message.messageText?.text, (message.messageText?.msgv3HashHex ?? "").count == 64 else {
            return false
        }
        let parsed = MessageReplyHelper.parse(text)
        return parsed.reply == nil && LocationMessage.parse(parsed.bodyText) == nil
    }

    fileprivate func presentTrayActions(id: String, source: UIView) {
        guard let message = trayMessage(id: id) else {
            return
        }
        let sheet = UIAlertController(title: nil, message: nil, preferredStyle: .actionSheet)
        sheet.popoverPresentationController?.sourceView = source
        sheet.popoverPresentationController?.sourceRect = source.bounds
        if canEditTrayMessage(message) {
            sheet.addAction(UIAlertAction(title: String(localized: "chat_edit_action"), style: .default) { [weak self] _ in
                self?.performTrayAction(.edit, id: id)
            })
        }
        sheet.addAction(UIAlertAction(title: String(localized: "air_tray_cancel"), style: .destructive) { [weak self] _ in
            self?.performTrayAction(.cancel, id: id)
        })
        sheet.addAction(UIAlertAction(title: String(localized: "alert_cancel"), style: .cancel, handler: nil))
        present(sheet, animated: true, completion: nil)
    }

    fileprivate func performTrayAction(_ action: AirTrayAction, id: String) {
        guard let message = trayMessage(id: id) else {
            return
        }
        switch action {
            case .edit:
                guard canEditTrayMessage(message) else {
                    UIAccessibilityPostNotification(UIAccessibilityAnnouncementNotification, String(localized: "air_tray_edit_unavailable"))
                    return
                }
                presentMessageEditor(for: message)
            case .cancel:
                if message.messageFile != nil {
                    // A file never left: the transfer is called off and the entry says "Not sent" with
                    // "Send again" in the log.
                    do {
                        try submanagerFiles.cancelFileTransfer(message)
                    }
                    catch let error as NSError {
                        handleErrorWithType(.cancelFileTransfer, error: error)
                    }
                    return
                }
                // Gone here, and retracted on the person's device if a copy ever reached it.
                submanagerChats.deleteMessage(forBoth: message)
        }
    }

    // MARK: Refused messages

    fileprivate func refreshFailedSends(animated: Bool) {
        guard failedList != nil else {
            return
        }
        let items = AirFailedSends.shared.items(forChat: chat.uniqueIdentifier).map {
            AirQueueItem(id: $0.id, text: plainEntryText($0.text), time: timeFormatter.string(from: $0.date), status: .failed)
        }
        let wasHidden = failedList.isHidden
        failedList.configure(items: items)
        if animated, wasHidden != failedList.isHidden, view.window != nil {
            UIView.animate(withDuration: KQMotion.duration(KQMotion.layout), delay: 0, options: [.curveEaseOut, .beginFromCurrentState], animations: {
                self.view.layoutIfNeeded()
            }, completion: nil)
        }
    }

    /// "Retry": the same text through the same call as the send button.
    fileprivate func retryFailedSend(id: String) {
        let chatId: String = chat.uniqueIdentifier
        guard let item = AirFailedSends.shared.items(forChat: chatId).first(where: { $0.id == id }) else {
            return
        }
        AirFailedSends.shared.remove(id: id, fromChat: chatId)
        chatInputViewManager.resend(item.text) { sent in
            guard !sent else {
                return
            }
            AirFailedSends.shared.add(text: item.text, date: item.date, toChat: chatId)
            UIAccessibilityPostNotification(UIAccessibilityAnnouncementNotification, String(localized: "air_delivery_failed"))
        }
    }

    fileprivate func presentFailedSendActions(id: String, source: UIView) {
        let chatId: String = chat.uniqueIdentifier
        guard let item = AirFailedSends.shared.items(forChat: chatId).first(where: { $0.id == id }) else {
            return
        }
        let parsed = MessageReplyHelper.parse(item.text)
        let sheet = UIAlertController(title: nil, message: nil, preferredStyle: .actionSheet)
        sheet.popoverPresentationController?.sourceView = source
        sheet.popoverPresentationController?.sourceRect = source.bounds
        sheet.addAction(UIAlertAction(title: String(localized: "air_retry"), style: .default) { [weak self] _ in
            self?.retryFailedSend(id: id)
        })
        // A reply goes back to the field only with its quote, which the field cannot take back; it can
        // be sent again or dropped.
        if parsed.reply == nil {
            sheet.addAction(UIAlertAction(title: String(localized: "chat_edit_action"), style: .default) { [weak self] _ in
                guard let self = self else {
                    return
                }
                AirFailedSends.shared.remove(id: id, fromChat: chatId)
                self.chatInputView.text = parsed.bodyText
                _ = self.chatInputView.becomeFirstResponder()
            })
        }
        sheet.addAction(UIAlertAction(title: String(localized: "air_tray_cancel"), style: .destructive) { _ in
            AirFailedSends.shared.remove(id: id, fromChat: chatId)
        })
        sheet.addAction(UIAlertAction(title: String(localized: "alert_cancel"), style: .cancel, handler: nil))
        present(sheet, animated: true, completion: nil)
    }

    // MARK: Composer

    /// The line under the field: "Will go now: Amina is online" or "Will wait on its way: Yusuf is offline".
    fileprivate func refreshComposeState(animated: Bool) {
        guard let hintView = chatInputView?.hintView else {
            return
        }
        let hint: AirComposeHint
        if let friend = friend, !friend.isInvalidated {
            hint = AirComposeHint.make(isSaved: false,
                                       contactExists: true,
                                       name: AirTranscript.shortName(personName(friend)),
                                       reach: currentReach(for: friend))
        }
        else {
            hint = AirComposeHint.make(isSaved: chat.isSavedMessages, contactExists: false, name: "", reach: .local)
        }
        // Saved messages never leave the phone, so our own node has nothing to say there.
        let ownNode = chat.isSavedMessages ? nil : AirComposeHint.ownNode(AirSelfNetwork.state)
        hintView.setHint(ownNode ?? hint, animated: animated)
    }

    /// The length counter once a tenth of the room is left; over the limit the send button waits.
    fileprivate func updateComposeLimit() {
        guard let hintView = chatInputView?.hintView else {
            return
        }
        let text = chatInputView.text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard friend != nil, !chat.isSavedMessages, !text.isEmpty else {
            hintView.setCounter(remaining: nil)
            chatInputView.sendBlocked = false
            return
        }
        // The reply header travels in the same message.
        let bytes = MessageReplyHelper.encode(replyController.pendingMeta, bodyText: text).utf8.count
        let remaining = AirComposeLimit.remaining(forBytes: bytes)
        hintView.setCounter(remaining: remaining)
        chatInputView.sendBlocked = (remaining ?? 0) < 0
    }

    // MARK: Search

    @objc fileprivate func searchButtonPressed() {
        if dossierView != nil {
            closeDossier()
        }
        if messageSearchController == nil {
            setupMessageSearch()
            navigationController?.navigationBar.setNeedsLayout()
            navigationController?.view.layoutIfNeeded()
        }
        // The bar has to be in the navigation bar before it can take the keyboard.
        DispatchQueue.main.async { [weak self] in
            guard let controller = self?.messageSearchController else {
                return
            }
            controller.isActive = true
            DispatchQueue.main.async {
                controller.searchBar.becomeFirstResponder()
            }
        }
    }

    // MARK: Dossier

    fileprivate func toggleDossier() {
        if dossierView != nil {
            closeDossier()
        }
        else {
            openDossier()
        }
    }

    fileprivate func openDossier() {
        guard dossierView == nil, let friend = friend, !friend.isInvalidated else {
            return
        }
        _ = chatInputView.resignFirstResponder()
        if reactionPopup.isVisible {
            reactionPopup.dismiss(animated: false)
        }
        if let search = messageSearchController, search.isActive {
            search.isActive = false
        }

        let dossier = AirDossierView()
        let content = dossierContent(for: friend)
        dossierSignature = signature(of: content)
        dossier.configure(content)
        wireDossier(dossier)
        view.addSubview(dossier)
        dossier.snp.makeConstraints {
            $0.top.equalTo(view.safeAreaLayoutGuide.snp.top)
            $0.leading.trailing.bottom.equalTo(view)
        }
        view.layoutIfNeeded()
        dossierView = dossier
        dossier.open()
        titleView.setExpanded(true, animated: true)
    }

    fileprivate func closeDossier(completion: (() -> Void)? = nil) {
        guard let dossier = dossierView else {
            completion?()
            return
        }
        dossierView = nil
        titleView.setExpanded(false, animated: true)
        dossier.close { [weak dossier] in
            dossier?.removeFromSuperview()
            completion?()
        }
        UIAccessibilityPostNotification(UIAccessibilityScreenChangedNotification, titleView)
    }

    fileprivate func refreshDossierIfNeeded() {
        guard let dossier = dossierView, let friend = friend, !friend.isInvalidated else {
            return
        }
        let content = dossierContent(for: friend)
        let next = signature(of: content)
        guard next != dossierSignature else {
            return
        }
        dossierSignature = next
        dossier.update(content)
    }

    /// Everything the dossier shows that can change while it is open. The mute switch is left out: it
    /// already shows what was just chosen.
    fileprivate func signature(of content: AirDossierContent) -> String {
        let strip = content.strip.online.map { $0 ? "1" : "0" }.joined()
        return [content.name, content.statusMessage, content.linkText, strip, String(content.strip.nowHour),
                String(content.nowOnline), content.bestHours, content.notifications, content.verification,
                String(content.photo != nil)].joined(separator: "|")
    }

    fileprivate func dossierContent(for friend: OCTFriend) -> AirDossierContent {
        let name = personName(friend)
        let shortName = AirTranscript.shortName(name)
        let reach = currentReach(for: friend)
        let link = AirLinkState(reach: reach)
        let now = Date()
        let hours = AirLocalStore.shared.hours(publicKey: friend.publicKey)
        let strip = AirDayStrip.make(hours: hours, now: now)

        let linkText: String
        switch link {
            case .direct:
                linkText = String(localized: "air_dossier_link_direct")
            case .relay:
                linkText = String(localized: "air_dossier_link_relay")
            case .none:
                linkText = String(localized: "air_dossier_link_none")
        }

        var statusLines: [String] = []
        if let ownName = friend.name, !ownName.isEmpty, ownName != name {
            statusLines.append(ownName)
        }
        if let status = friend.statusMessage, !status.isEmpty {
            statusLines.append(status)
        }

        let verifiedAt = AirLocalStore.shared.verifiedAt(publicKey: friend.publicKey)
        let verification: String
        if let date = verifiedAt {
            // The month in words, as the day marks write it: a numeric date reads month-first or
            // day-first depending on the region, and "11/8/26" is ambiguous.
            let formatter = DateFormatter()
            formatter.locale = AppLanguage.locale
            formatter.setLocalizedDateFormatFromTemplate("dMMMMy")
            verification = String(localized: "air_dossier_verified_format", formatter.string(from: date))
        }
        else {
            verification = String(localized: "air_dossier_not_verified")
        }

        let hasPush = !(friend.pushToken ?? "").isEmpty
        let initials = AirInitials.from(name)
        return AirDossierContent(
                name: name,
                statusMessage: statusLines.joined(separator: "\n"),
                tile: .person(initials: initials, tone: AirTone.index(for: friend.publicKey)),
                photo: friend.avatarData.flatMap { UIImage(data: $0) },
                initials: initials,
                link: link,
                linkText: linkText,
                strip: strip,
                nowOnline: reach.isLive,
                stripSpoken: strip.spokenText(locale: AppLanguage.locale, nowOnline: reach.isLive),
                bestHours: AirBestHours.find(hours: hours, now: now).map { String(localized: "air_dossier_best_format", $0.from, $0.to) } ?? "",
                notifications: hasPush ? String(localized: "air_dossier_push_on_format", shortName) : String(localized: "air_dossier_push_off"),
                muted: AirLocalStore.shared.isMuted(publicKey: friend.publicKey),
                verification: verification,
                isVerified: verifiedAt != nil,
                key: friend.publicKey)
    }

    fileprivate func wireDossier(_ dossier: AirDossierView) {
        dossier.onClose = { [weak self] in
            self?.closeDossier()
        }
        dossier.onMuteChange = { [weak self] muted in
            guard let friend = self?.friend, !friend.isInvalidated else {
                return
            }
            AirLocalStore.shared.setMuted(muted, publicKey: friend.publicKey)
        }
        dossier.onVerify = { [weak self] in
            self?.presentKeyCheck()
        }
        dossier.onUnverify = { [weak self] in
            guard let friend = self?.friend, !friend.isInvalidated else {
                return
            }
            AirLocalStore.shared.setVerified(nil, publicKey: friend.publicKey)
        }
        dossier.onCopyKey = { [weak self] in
            guard let friend = self?.friend, !friend.isInvalidated else {
                return
            }
            UIPasteboard.general.string = friend.publicKey
            UINotificationFeedbackGenerator().notificationOccurred(.success)
            UIAccessibilityPostNotification(UIAccessibilityAnnouncementNotification, String(localized: "air_dossier_key_copied"))
        }
        dossier.onShowQR = { [weak self] in
            self?.showKeyQR()
        }
        dossier.onRename = { [weak self] in
            self?.presentRename()
        }
        dossier.onAttachments = { [weak self] in
            guard let self = self else {
                return
            }
            self.delegate?.chatPrivateControllerShowAttachments(self, forChat: self.chat)
        }
        dossier.onRemove = { [weak self] in
            self?.confirmContactRemoval()
        }
        dossier.onBlock = { [weak self] in
            self?.confirmBlock()
        }
    }

    /// "Check key": the person reads the first 64 characters of their ID, which is the key in the dossier.
    fileprivate func presentKeyCheck() {
        guard let friend = friend, !friend.isInvalidated else {
            return
        }
        let message = String(localized: "air_verify_message") + "\n\n" + AirKeyFormat.lines(friend.publicKey)
        let alert = UIAlertController(title: String(localized: "air_verify_title"), message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: String(localized: "alert_cancel"), style: .cancel, handler: nil))
        alert.addAction(UIAlertAction(title: String(localized: "air_verify_match"), style: .default) { [weak self] _ in
            guard let friend = self?.friend, !friend.isInvalidated else {
                return
            }
            AirLocalStore.shared.setVerified(Date(), publicKey: friend.publicKey)
        })
        present(alert, animated: true, completion: nil)
    }

    fileprivate func showKeyQR() {
        guard let friend = friend, !friend.isInvalidated else {
            return
        }
        let controller = QRViewerController(theme: theme,
                                            text: friend.publicKey,
                                            heading: String(localized: "air_qr_key_heading_format", AirTranscript.shortName(personName(friend))),
                                            subtitle: String(localized: "air_qr_key_subtitle"))
        navigationController?.pushViewController(controller, animated: true)
    }

    fileprivate func presentRename() {
        guard let friend = friend, !friend.isInvalidated else {
            return
        }
        let controller = TextEditController(theme: theme, title: String(localized: "nickname"), defaultValue: friend.nickname, changeTextHandler: { [weak self] newValue in
            guard let self = self, let friend = self.friend, !friend.isInvalidated else {
                return
            }
            self.submanagerObjects.change(friend, nickname: newValue)
        }, userFinishedEditing: { [weak self] in
            _ = self?.navigationController?.popViewController(animated: true)
        })
        navigationController?.pushViewController(controller, animated: true)
    }

    fileprivate func confirmContactRemoval() {
        guard let friend = friend, !friend.isInvalidated else {
            return
        }
        // E21: from below, as "Block" next to it.
        let sheet = AirConfirm.sheet(title: String(localized: "air_dossier_remove_title_format", personName(friend)),
                                     message: String(localized: "air_dossier_remove_message"),
                                     verb: String(localized: "alert_delete"),
                                     source: view) { [weak self] in
            guard let self = self, let friend = self.friend, !friend.isInvalidated else {
                return
            }
            self.prepareForContactRemoval()
            self.delegate?.chatPrivateControllerRemoveContact(self, friend: friend)
        }
        present(sheet, animated: true, completion: nil)
    }

    /// "Block": one sentence says what happens, the button is the verb. The key goes into this device's
    /// block list first, then the contact leaves through the same path as "Remove".
    fileprivate func confirmBlock() {
        guard let friend = friend, !friend.isInvalidated else {
            return
        }
        let sheet = UIAlertController(title: String(localized: "air_dossier_block_title_format", personName(friend)),
                                      message: String(localized: "air_dossier_block_message"),
                                      preferredStyle: .actionSheet)
        sheet.popoverPresentationController?.sourceView = view
        sheet.popoverPresentationController?.sourceRect = CGRect(x: view.bounds.midX, y: view.bounds.maxY, width: 0, height: 0)
        sheet.addAction(UIAlertAction(title: String(localized: "air_dossier_block"), style: .destructive) { [weak self] _ in
            guard let self = self, let friend = self.friend, !friend.isInvalidated else {
                return
            }
            AirLocalStore.shared.block(publicKey: friend.publicKey)
            self.prepareForContactRemoval()
            self.delegate?.chatPrivateControllerRemoveContact(self, friend: friend)
        })
        sheet.addAction(UIAlertAction(title: String(localized: "alert_cancel"), style: .cancel, handler: nil))
        present(sheet, animated: true, completion: nil)
    }

    /// The conversation stops reading Realm before the coordinator removes the contact and the chat:
    /// nothing here may touch them once they are gone.
    fileprivate func prepareForContactRemoval() {
        messagesToken?.invalidate()
        messagesToken = nil
        friendToken?.invalidate()
        friendToken = nil
        presenceRefreshTimer?.invalidate()
        presenceRefreshTimer = nil
        airObservers.forEach { NotificationCenter.default.removeObserver($0) }
        airObservers = []
        NotificationCenter.default.removeObserver(self, name: NSNotification.Name.UIApplicationDidBecomeActive, object: nil)
        if isActiveLocationSharingChat {
            AppDelegate.location_sharing_contact_pubkey = "-1"
            stopLocationSharing()
        }
        chatInputViewManager.endUserInteraction()
        if let dossier = dossierView {
            dossierView = nil
            dossier.removeFromSuperview()
        }
    }
}

extension ChatPrivateController: UISearchControllerDelegate {
    /// Search goes back behind its button once it is closed.
    func didDismissSearchController(_ searchController: UISearchController) {
        navigationItem.searchController = nil
        messageSearchController = nil
        guard !messageSearchQuery.isEmpty else {
            return
        }
        messageSearchQuery = ""
        tableView?.reloadData()
    }
}

/// KHANDAQ (#G5): per-chat pinned-message storage. Local (UserDefaults) for now — a single pinned
/// message id per chat; no Realm migration, no wire sync yet (that's a follow-up).
enum ChatPinStore {
    private static func key(_ chatId: String) -> String { "khandaq_pinned_msg_\(chatId)" }

    static func pinnedMessageId(forChat chatId: String) -> String? {
        UserDefaults.standard.string(forKey: key(chatId))
    }

    static func setPinned(messageId: String, forChat chatId: String) {
        UserDefaults.standard.set(messageId, forKey: key(chatId))
    }

    static func clearPin(forChat chatId: String) {
        UserDefaults.standard.removeObject(forKey: key(chatId))
    }
}

// MARK: - Calls (redesign stage 3, E9)

private extension ChatPrivateController {
    /// A call to someone online starts at once. For someone offline the button says the call will not
    /// get through and offers to wait for them: their phone gets a signal without text (KHANDAQ #152),
    /// and the call starts by itself once they are online.
    func requestCall(video: Bool) {
        guard let friend = friend, !friend.isInvalidated else {
            log("Call_ERROR:no friend?")
            return
        }
        if friend.isConnected {
            stopCallWaiting(dismiss: true)
            delegate?.chatPrivateControllerCallToChat(self, enableVideo: video)
            return
        }
        let name = AirTranscript.shortName(personName(friend))
        let hasPush = !(friend.pushToken ?? "").isEmpty
        let alert = UIAlertController(title: AirCallText.offlineTitle(name: name),
                                      message: AirCallText.offlineDetail(hasPush: hasPush),
                                      preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: String(localized: "air_call_wait_action"), style: .default) { [weak self] _ in
            self?.startCallWaiting(video: video)
        })
        alert.addAction(UIAlertAction(title: String(localized: "alert_cancel"), style: .cancel, handler: nil))
        present(alert, animated: true, completion: nil)
    }

    func startCallWaiting(video: Bool) {
        guard callWaiting == nil, let friend = friend, !friend.isInvalidated else {
            return
        }
        let hasPush = !(friend.pushToken ?? "").isEmpty
        let waiting = AirCallWaitingController(name: personName(friend),
                                               shortName: AirTranscript.shortName(personName(friend)),
                                               hasPush: hasPush)
        waiting.onCancel = { [weak self] in
            self?.stopCallWaiting(dismiss: true)
        }
        callWaiting = waiting
        callWaitingVideo = video
        present(waiting, animated: true, completion: nil)

        // KHANDAQ (#152): wake an offline friend before the call with a SILENT push, then the message
        // push 10 seconds later, as before. Never a visible "calling you" text.
        submanagerChats.triggerWakePush(for: chat)
        if let chats = submanagerChats {
            let chat = self.chat
            DispatchQueue.main.asyncAfter(deadline: .now() + 10) {
                os_log("PUSH:10_seconds")
                chats.sendMessagePush(to: chat)
            }
        }
        // The friend notification calls checkCallWaiting too; the timer is the safety net it was before.
        callWaitingTimer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            self?.checkCallWaiting()
        }
    }

    /// The person came online while we wait: the waiting screen goes, then the call starts.
    func checkCallWaiting() {
        guard let waiting = callWaiting else {
            return
        }
        guard let friend = friend, !friend.isInvalidated else {
            stopCallWaiting(dismiss: true)
            return
        }
        guard friend.isConnected else {
            return
        }
        let video = callWaitingVideo
        stopCallWaiting(dismiss: false)
        waiting.dismiss(animated: true) { [weak self] in
            guard let self = self else {
                return
            }
            self.delegate?.chatPrivateControllerCallToChat(self, enableVideo: video)
        }
    }

    func stopCallWaiting(dismiss: Bool) {
        callWaitingTimer?.invalidate()
        callWaitingTimer = nil
        if dismiss {
            callWaiting?.dismiss(animated: true, completion: nil)
        }
        callWaiting = nil
    }
}
