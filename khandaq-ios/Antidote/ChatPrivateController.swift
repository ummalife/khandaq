
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
    static let NewMessageViewTopOffset: CGFloat = -15.0
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
    fileprivate var visibleMessages: Int
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
    fileprivate var CallWaitingView: UIView!
    fileprivate var callwaiting_running: Bool!
    fileprivate var CallWaitingCancelButton: CallButton?
    fileprivate let linearBar: LinearProgressBar = LinearProgressBar()

    fileprivate var titleView: ChatPrivateTitleView!
    fileprivate var tableView: UITableView?
    fileprivate var typingHeaderView: ChatTypingHeaderView!
    fileprivate var fauxOfflineHeaderView: ChatFauxOfflineHeaderView!
    // KHANDAQ design (Figma): end-to-end encryption notice card shown at the top of an empty chat.
    fileprivate var encryptionBanner: UIView!
    fileprivate var newMessagesView: UIView!
    // KHANDAQ design (Figma): subtle line-art doodle pattern behind the (transparent) message cells.
    fileprivate var chatBackgroundView: UIImageView!
    // KHANDAQ design (Figma): Telegram-style floating day chip ("Сегодня"/"Вчера"/date) that surfaces
    // while scrolling and reflects the date of the topmost visible message, then fades out.
    fileprivate var floatingDateView: UIView!
    fileprivate var floatingDateLabel: UILabel!
    fileprivate var floatingDateHideTimer: Timer?
    fileprivate lazy var floatingDateFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .none
        // Yields locale-aware "Сегодня"/"Вчера" for recent days, full date otherwise — no string keys.
        formatter.doesRelativeDateFormatting = true
        return formatter
    }()
    fileprivate var chatInputView: ChatInputView!
    fileprivate var editMessagesToolbar: UIToolbar!

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
        self.callwaiting_running = false

        let predicate = NSPredicate(format: "chatUniqueIdentifier == %@", chat.uniqueIdentifier)
        self.messages = submanagerObjects.messages(predicate: predicate).sortedResultsUsingProperty("dateInterval", ascending: false)
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
    }

    required convenience init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func loadView() {
        // KHANDAQ design (Figma): chat canvas uses the dedicated chat background, not NormalBackground.
        loadViewWithBackgroundColor(theme.colorForType(.ChatBackground))

        createChatBackgroundView()
        createTableView()
        createTableHeaderViews()
        createNewMessagesView()
        createFloatingDateView()
        createInputView()
        createEditMessageToolbar()
        createEncryptionBanner()
        installConstraints()
    }

    // KHANDAQ design (Figma): encryption-info card pinned to the top of an empty chat.
    private func createEncryptionBanner() {
        let card = UIView()
        card.backgroundColor = theme.colorForType(.ChatIncomingBubble)
        card.layer.cornerRadius = 16.0
        card.layer.masksToBounds = true

        let badge = UIView()
        badge.backgroundColor = theme.colorForType(.TabItemActive).withAlphaComponent(0.14)
        badge.layer.cornerRadius = 10.0
        badge.layer.masksToBounds = true
        card.addSubview(badge)

        let lock = UIImageView()
        if #available(iOS 13.0, *) {
            lock.image = UIImage(systemName: "lock.fill")
        }
        lock.tintColor = theme.colorForType(.LinkText)
        lock.contentMode = .scaleAspectFit
        badge.addSubview(lock)

        let badgeLabel = UILabel()
        badgeLabel.text = String(localized: "chat_encryption_badge")
        badgeLabel.font = UIFont.systemFont(ofSize: 13.0, weight: .semibold)
        badgeLabel.textColor = theme.colorForType(.LinkText)
        badge.addSubview(badgeLabel)

        let info = UILabel()
        info.text = String(localized: "chat_encryption_info")
        info.font = UIFont.systemFont(ofSize: 14.0)
        info.textColor = theme.colorForType(.NormalText)
        info.numberOfLines = 0
        card.addSubview(info)

        view.addSubview(card)
        encryptionBanner = card

        badge.snp.makeConstraints {
            $0.top.leading.equalTo(card).inset(12.0)
            $0.height.equalTo(28.0)
        }
        lock.snp.makeConstraints {
            $0.leading.equalTo(badge).offset(10.0)
            $0.centerY.equalTo(badge)
            $0.width.height.equalTo(13.0)
        }
        badgeLabel.snp.makeConstraints {
            $0.leading.equalTo(lock.snp.trailing).offset(6.0)
            $0.trailing.equalTo(badge).offset(-10.0)
            $0.centerY.equalTo(badge)
        }
        info.snp.makeConstraints {
            $0.top.equalTo(badge.snp.bottom).offset(10.0)
            $0.leading.trailing.equalTo(card).inset(12.0)
            $0.bottom.equalTo(card).offset(-12.0)
        }
        card.snp.makeConstraints {
            $0.top.equalTo(view.safeAreaLayoutGuide).offset(8.0)
            $0.leading.trailing.equalTo(view).inset(16.0)
        }
    }

    private func updateEncryptionBannerVisibility() {
        encryptionBanner?.isHidden = messages.count > 0
    }

    override func viewDidLoad() {
        super.viewDidLoad()

        replyController.restorePending(forChatId: chat.uniqueIdentifier, theme: theme)
        addMessagesNotification()
        updateEncryptionBannerVisibility()

        createNavigationViews()
        addFriendNotification()
        setupMessageSearch()

        self.configureLinearProgressBar()
    }

    fileprivate func setupMessageSearch() {
        let searchController = UISearchController(searchResultsController: nil)
        searchController.obscuresBackgroundDuringPresentation = false
        searchController.searchResultsUpdater = self
        searchController.searchBar.placeholder = String(localized: "group_messages_search_placeholder")
        navigationItem.searchController = searchController
        // The message table is vertically flipped, which inverts the pull-to-reveal gesture. Keep the
        // search bar always visible so reaching search no longer depends on scroll direction.
        navigationItem.hidesSearchBarWhenScrolling = false
        definesPresentationContext = true
        messageSearchController = searchController
    }

    func sendLocationMessage(_ payload: String) {
        guard isActiveLocationSharingChat else {
            return
        }

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
            model.message = body
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
                                                     ensureLoaded: { [weak self] targetIndex in
                                                         guard let self = self else { return }
                                                         self.visibleMessages = min(self.messages.count, targetIndex + 30)
                                                         self.tableView?.reloadData()
                                                     })
        }
    }

    fileprivate func configureLinearProgressBar(){
            linearBar.backgroundColor = UIColor(red:0.68, green:0.81, blue:0.72, alpha:1.0)
            linearBar.progressBarColor = UIColor(red:0.26, green:0.65, blue:0.45, alpha:1.0)
            linearBar.heightForLinearBar = 5
        }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)

        updateLastReadDate()
        delegate?.chatPrivateControllerWillAppear(self)
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)

        // KHANDAQ: mark read on EXIT too — viewWillAppear alone misses messages that arrived while the
        // chat was open, leaving the unread badge stuck after the user has actually read everything.
        updateLastReadDate()
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
    }

    /// KHANDAQ (#64): if this chat was opened from a global-search message hit, scroll to + briefly
    /// highlight that message.
    func scrollToGlobalSearchTargetIfNeeded() {
        guard let messageId = GlobalSearchScrollTarget.take(forChatId: chat.uniqueIdentifier),
              let tableView = tableView else {
            return
        }

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

    @objc
    func buttonCallWaitingCancel() {
            callwaiting_running = false
            CallWaitingView.removeFromSuperview()
            self.linearBar.stopAnimation()
    }

    @objc func audioCallButtonPressed() {

        if let friend = self.friend {
            let connection_status = ConnectionStatus(connectionStatus: friend.connectionStatus)
            if (connection_status != .none)
            {
                // HINT: friend is online, so start the call now
                callwaiting_running = false
                delegate?.chatPrivateControllerCallToChat(self, enableVideo: false)
            }
            else
            {
                // HINT: friend is not online, show call waiting screen
                callwaiting_running = true
                let window = UIApplication.shared.keyWindow!
                CallWaitingView = UIView(frame: window.bounds)
                window.addSubview(CallWaitingView)
                CallWaitingView.backgroundColor = .black

                CallWaitingCancelButton = CallButton(theme: theme, type: .decline, buttonSize: .big)
                CallWaitingCancelButton!.addTarget(self,
                                         action: #selector(buttonCallWaitingCancel),
                                         for: .touchUpInside)
                // CallWaitingCancelButton!.backgroundColor = .white

                let lb1 = UILabel(frame: CGRect(x: 0, y: 0, width: 200, height: 80))
                lb1.text = "Call"
                lb1.textAlignment = .center;
                lb1.font = lb1.font.withSize(35)
                lb1.textColor = .white
                lb1.backgroundColor = .black
                lb1.numberOfLines = 0;
                lb1.sizeToFit()

                let lb2 = UILabel(frame: CGRect(x: 0, y: 0, width: 200, height: 80))
                lb2.text = friend.nickname
                lb2.textAlignment = .center;
                lb2.font = lb1.font.withSize(30)
                lb2.textColor = .white
                lb2.backgroundColor = .black
                lb2.numberOfLines = 0;
                lb2.sizeToFit()

                let lb3 = UILabel(frame: CGRect(x: 0, y: 0, width: 200, height: 80))
                lb3.text = "waiting for friend to come online ..."
                lb3.textAlignment = .center;
                lb3.font = lb1.font.withSize(20)
                lb3.textColor = .white
                lb3.backgroundColor = .black
                lb3.numberOfLines = 0;
                lb3.lineBreakMode = .byWordWrapping
                lb3.sizeToFit()


                CallWaitingView.addSubview(CallWaitingCancelButton!)
                CallWaitingView.addSubview(lb1)
                CallWaitingView.addSubview(lb2)
                CallWaitingView.addSubview(lb3)
                CallWaitingCancelButton!.center = CallWaitingView.center
                CallWaitingView.bringSubview(toFront: lb1)
                CallWaitingView.bringSubview(toFront: lb2)
                CallWaitingView.bringSubview(toFront: lb3)
                CallWaitingView.bringSubview(toFront: CallWaitingCancelButton!)

                let BigButtonOffset = 30.0

                CallWaitingView.snp.makeConstraints { make in
                    make.leading.trailing.bottom.equalToSuperview()
                    // make.top.equalTo(view.safeAreaLayoutGuide) // --> that leave too much see through space at the top. strange
                    make.top.equalToSuperview()
                }

                CallWaitingCancelButton!.snp.makeConstraints { make in
                    make.centerX.equalToSuperview()
                    make.top.greaterThanOrEqualToSuperview().offset(BigButtonOffset)
                    make.bottom.equalToSuperview().offset(-BigButtonOffset)
                }

                lb1.snp.makeConstraints { make in
                    make.centerX.equalToSuperview()
                    make.top.equalToSuperview().offset(60)
                    make.leading.trailing.equalToSuperview()
                }

                lb2.snp.makeConstraints { make in
                    make.centerX.equalToSuperview()
                    make.top.equalTo(lb1.snp.bottom).offset(35)
                    make.leading.trailing.equalToSuperview()
                }

                lb3.snp.makeConstraints { make in
                    make.centerX.equalToSuperview()
                    make.top.equalTo(lb2.snp.bottom).offset(35)
                    make.leading.trailing.equalToSuperview()
                }

                DispatchQueue.global(qos: .userInitiated).async {

                    log("cc:call_waiting_bg_queue")
                    if self.friend != nil {

                        DispatchQueue.main.async {
                            // KHANDAQ (#152): wake an offline friend before the call with a SILENT push.
                            // We used to send a literal "calling you" TEXT message here (to trigger the
                            // push), but it was a visible chat message that spammed both sides on every
                            // call attempt/retry. triggerWakePush pings the friend's push token directly.
                            self.submanagerChats.triggerWakePush(for: self.chat)
                            DispatchQueue.main.asyncAfter(deadline: .now() + 10) {
                                os_log("PUSH:10_seconds")
                                self.submanagerChats.sendMessagePush(to: self.chat)
                            }

                            self.linearBar.startAnimation(viewToAddto: self.CallWaitingView, viewToAlignToBottomOf: lb3, bottom_margin: 10)
                            self.CallWaitingView.bringSubview(toFront: self.linearBar)
                        }

                        var connection_status2: ConnectionStatus = .none

                        while true {

                            DispatchQueue.main.async {
                                connection_status2 = ConnectionStatus(connectionStatus: friend.connectionStatus)
                            }

                            log("cc:while_friend_not_online, \(connection_status2)")
                            if (connection_status2 != .none)
                            {
                                DispatchQueue.main.async {
                                    log("cc:main_queue")
                                    if (self.callwaiting_running)
                                    {
                                        self.callwaiting_running = false
                                        self.CallWaitingView.removeFromSuperview()
                                        self.linearBar.stopAnimation()
                                        self.delegate?.chatPrivateControllerCallToChat(self, enableVideo: false)
                                    }
                                }
                                break
                            }

                            // HINT: sleep for 1 second
                            if (self.callwaiting_running)
                            {
                                sleep(1)
                            }
                            else
                            {
                                DispatchQueue.main.async {
                                    self.CallWaitingView.removeFromSuperview()
                                    self.linearBar.stopAnimation()
                                }
                                break
                            }
                        }
                        log("cc:while_loop_end")
                    }
                }

            }
        } else {
            log("Call_ERROR:no friend?")
        }
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
        delegate?.chatPrivateControllerCallToChat(self, enableVideo: true)
    }

    @objc func locationButtonPressed() {
        displayalert()
    }

    @objc func editMessagesDeleteButtonPressed(_ barButtonItem: UIBarButtonItem) {
        guard let selectedRows = tableView?.indexPathsForSelectedRows else {
            return
        }

        showMessageDeletionConfirmation(messagesCount: selectedRows.count,
                                        showFromItem: barButtonItem,
                                        deleteClosure: { [unowned self] in
            self.toggleTableViewEditing(false, animated: true)

            let toRemove = selectedRows.map {
                return self.messages[$0.row]
            }

            self.submanagerChats.removeMessages(toRemove)
        })
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

extension ChatPrivateController: UITableViewDataSource {
    func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
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
                let outgoingModel = ChatOutgoingTextCellModel()

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

                outgoingModel.delivered = messageText.isDelivered
                outgoingModel.sentpush = messageText.sentPush
                attachReplyQuoteHandler(to: outgoingModel)

                model = outgoingModel

                cell = tableView.dequeueReusableCell(withIdentifier: ChatOutgoingTextCell.staticReuseIdentifier) as! ChatOutgoingTextCell
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
                let incomingModel = ChatBaseTextCellModel()

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

                attachReplyQuoteHandler(to: incomingModel)
                model = incomingModel

                cell = tableView.dequeueReusableCell(withIdentifier: ChatIncomingTextCell.staticReuseIdentifier) as! ChatIncomingTextCell
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

        // KHANDAQ (#99): tag the row with a day separator when it begins a new calendar day.
        model.dateSeparator = daySeparatorString(forDisplayIndex: indexPath.row)

        cell.delegate = self
        cell.replySwipeDelegate = self
        cell.setupWithTheme(theme, model: model)
        cell.transform = tableView.transform;

        return cell
    }

    func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
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
        return floatingDateFormatter.string(from: message.date())
    }

    func tableView(_ tableView: UITableView, heightForRowAt indexPath: IndexPath) -> CGFloat {
        let entry = messageEntry(atDisplayIndex: indexPath.row)
        let message = entry.message

        // Merged caption rows are not shown on their own.
        if messageSearchQuery.isEmpty, isMergedCaptionRow(storageIndex: entry.storageIndex) {
            return 0.0
        }

        guard let messageFile = message.messageFile,
              message.isOutgoing() || messageFile.fileType == .ready,
              let file = messageFile.filePath(),
              messageFile.fileSize < Constants.MaxImageSizeToShowInline else {
            return UITableViewAutomaticDimension
        }

        guard let size = inlineMediaSize(forFileAt: file, messageFile: messageFile) else {
            return UITableViewAutomaticDimension
        }
        let box = LoadingImageView.previewBox(for: size)
        var height = box.height + 16.0

        // Add room for a merged caption rendered inside the media bubble.
        if messageSearchQuery.isEmpty,
           let captionMsg = captionMessage(forFileAtStorageIndex: entry.storageIndex),
           let caption = MessageReplyHelper.plainBody(for: captionMsg), !caption.isEmpty {
            // KHANDAQ (#52): height must match the stripped (markup-free) caption text.
            height += ChatGenericFileCell.captionHeight(for: caption, width: box.width)
        }
        // KHANDAQ (#99): media rows have an explicit height (no auto-sizing), so add the separator band
        // here when this row starts a new day — text/voice rows self-size and need no adjustment.
        if daySeparatorString(forDisplayIndex: indexPath.row) != nil {
            height += ChatMovableDateCell.daySeparatorReservedHeight
        }
        return height
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
    /// recognized media, within the inline size limit) — mirrors the heightForRowAt gate. A caption is
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

        maybeLoadImageForCellAtPath(cell, indexPath: indexPath)
    }

    func tableView(_ tableView: UITableView, shouldShowMenuForRowAt indexPath: IndexPath) -> Bool {
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
                tableView.reloadData()
            }
        }

        // KHANDAQ (#93-task): show the jump-to-latest button whenever the newest row is off-screen.
        // An empty chat has no rows, so indexPathsForVisibleRows is [] (contains == false) — treat
        // "no rows" as already-at-bottom so the button stays hidden (and can't crash on tap).
        let atBottom = tableView.numberOfRows(inSection: 0) == 0
            || (tableView.indexPathsForVisibleRows?.contains(IndexPath(row: 0, section: 0)) ?? true)
        toggleNewMessageView(show: !atBottom)

        updateFloatingDate()
        showFloatingDateMomentarily()
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
            guard UTTypeConformsTo(messageFile.fileUTI as CFString? ?? "" as CFString, kUTTypeImage) else {
                fatalError("Cannot copy non-image file")
            }
            guard let file = messageFile.filePath() else {
                assertionFailure("Tried to copy non-existing file")
                return
            }
            guard let image = UIImage(contentsOfFile: file) else {
                assertionFailure("Cannot create image from file")
                return
            }

            UIPasteboard.general.image = image
        }
    }

    func chatMovableDateCellDeletePressed(_ cell: ChatMovableDateCell) {
        guard let indexPath = tableView?.indexPath(for: cell) else {
            return
        }

        let message = messageEntry(atDisplayIndex: indexPath.row).message

        submanagerChats.removeMessages([message])
    }

    func chatMovableDateCellMorePressed(_ cell: ChatMovableDateCell) {
        toggleTableViewEditing(true, animated: true)

        // TODO select row
        // guard let indexPath = tableView?.indexPathForCell(cell) else {
        //     return
        // }

        // tableView?.selectRowAtIndexPath(indexPath, animated: false, scrollPosition: .None)
    }

    func chatMovableDateCellReplyPressed(_ cell: ChatMovableDateCell) {
        guard let indexPath = tableView?.indexPath(for: cell) else {
            return
        }
        let message = messageEntry(atDisplayIndex: indexPath.row).message
        replyController.startReply(to: message, submanagerObjects: submanagerObjects, theme: theme)
        _ = chatInputView.becomeFirstResponder()
    }

    func chatMovableDateCellForwardPressed(_ cell: ChatMovableDateCell) {
        guard let indexPath = tableView?.indexPath(for: cell) else {
            return
        }
        let message = messageEntry(atDisplayIndex: indexPath.row).message
        MessageForwarder.presentForwardPicker(for: message, from: self, sourceView: cell)
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
        titleView = ChatPrivateTitleView(theme: theme)
        navigationItem.titleView = titleView

        // create correct navigation buttons
        toggleTableViewEditing(false, animated: false)
    }

    func createTableView() {
        let tableView = UITableView()
        self.tableView = tableView
        tableView.dataSource = self
        tableView.delegate = self
        tableView.transform = CGAffineTransform(a: 1, b: 0, c: 0, d: -1, tx: 0, ty: 0)
        // KHANDAQ (#37): the table is y-flipped (d:-1), so contentInset.top renders at the VISUAL
        // bottom — a small gap so the newest message clears the input bar instead of touching it.
        tableView.contentInset.top = 6.0
        tableView.scrollIndicatorInsets.top = 6.0
        tableView.scrollsToTop = false
        tableView.allowsSelection = false
        tableView.estimatedRowHeight = 44.0
        // Transparent so the doodle background view (added behind it) shows through.
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
        newMessagesView = UIView()
        newMessagesView.backgroundColor = theme.colorForType(.ConnectingBackground)
        newMessagesView.layer.cornerRadius = 5.0
        newMessagesView.layer.masksToBounds = true
        newMessagesView.isHidden = true
        view.addSubview(newMessagesView)

        let label = UILabel()
        // KHANDAQ (#93-task): repurposed as a Telegram-style "jump to latest" button — shown whenever
        // the newest message is scrolled off-screen (and on a new incoming message while scrolled up).
        label.text = "↓"
        label.textColor = theme.colorForType(.ConnectingText)
        label.backgroundColor = .clear
        label.font = UIFont.systemFont(ofSize: 18.0, weight: .semibold)
        label.textAlignment = .center
        newMessagesView.addSubview(label)

        let button = UIButton()
        button.addTarget(self, action: #selector(ChatPrivateController.newMessagesViewPressed), for: .touchUpInside)
        newMessagesView.addSubview(button)

        label.snp.makeConstraints {
            $0.leading.equalTo(newMessagesView).offset(Constants.NewMessageViewEdgesOffset)
            $0.trailing.equalTo(newMessagesView).offset(-Constants.NewMessageViewEdgesOffset)
            $0.top.equalTo(newMessagesView).offset(Constants.NewMessageViewEdgesOffset)
            $0.bottom.equalTo(newMessagesView).offset(-Constants.NewMessageViewEdgesOffset)
        }

        button.snp.makeConstraints {
            $0.edges.equalTo(newMessagesView)
        }
    }

    func createChatBackgroundView() {
        // Doodle wallpaper drawn behind the y-flipped table. Added to the controller's view (NOT the
        // table) so the doodles aren't mirrored, and the transparent cells let it show through.
        chatBackgroundView = ThemeChrome.makeChatDoodleBackgroundView()
        view.addSubview(chatBackgroundView)
        chatBackgroundView.snp.makeConstraints {
            $0.edges.equalTo(view)
        }
    }

    func createFloatingDateView() {
        floatingDateView = UIView()
        // Same surface as an incoming bubble so it reads as a chat overlay in both themes.
        floatingDateView.backgroundColor = theme.colorForType(.ChatIncomingBubble)
        floatingDateView.layer.cornerRadius = 13.0
        floatingDateView.layer.masksToBounds = true
        floatingDateView.alpha = 0.0
        floatingDateView.isUserInteractionEnabled = false
        view.addSubview(floatingDateView)

        floatingDateLabel = UILabel()
        floatingDateLabel.textColor = theme.colorForType(.ChatListCellMessage)
        floatingDateLabel.backgroundColor = .clear
        floatingDateLabel.textAlignment = .center
        floatingDateLabel.font = UIFont.systemFont(ofSize: 12.0, weight: .semibold)
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
        floatingDateLabel.text = floatingDateFormatter.string(from: date)
    }

    /// Fades the chip in, then schedules a fade-out so it only shows during/just after scrolling.
    func showFloatingDateMomentarily() {
        guard floatingDateView != nil, let text = floatingDateLabel.text, !text.isEmpty else {
            return
        }

        floatingDateHideTimer?.invalidate()
        UIView.animate(withDuration: 0.15) {
            self.floatingDateView.alpha = 1.0
        }
        floatingDateHideTimer = Timer.scheduledTimer(withTimeInterval: 1.2, repeats: false) { [weak self] _ in
            UIView.animate(withDuration: 0.3) {
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
            return self.replyController.composeOutgoingText(text)
        }
        // KHANDAQ design (Figma): the "+" attachment menu's "Геолокация" shares the current location once.
        chatInputViewManager.onShareLocation = { [weak self] in
            self?.startLocationSharing(sendImmediately: true)
        }
        replyController.install(in: view, above: chatInputView, theme: theme)
    }

    func createEditMessageToolbar() {
        editMessagesToolbar = UIToolbar()
        editMessagesToolbar.isHidden = true
        editMessagesToolbar.tintColor = theme.colorForType(.LinkText)
        editMessagesToolbar.items = [
            UIBarButtonItem(barButtonSystemItem: .trash, target: self, action: #selector(ChatPrivateController.editMessagesDeleteButtonPressed(_:)))
        ]
        view.addSubview(editMessagesToolbar)
    }

    func installConstraints() {
        tableView!.snp.makeConstraints {
            $0.top.leading.trailing.equalTo(view)

            tableViewToChatInputConstraint = $0.bottom.equalTo(replyController.previewView.snp.top).constraint
        }

        typingHeaderView.snp.makeConstraints {
            $0.leading.trailing.equalTo(view)
            $0.top.equalTo(tableView!.snp.bottom)
            typingViewToChatInputConstraint = $0.bottom.equalTo(replyController.previewView.snp.top).constraint
        }

        typingViewToChatInputConstraint.deactivate()

        newMessagesView.snp.makeConstraints {
            $0.centerX.equalTo(tableView!)
            newMessageViewTopConstraint = $0.top.equalTo(chatInputView.snp.top).constraint
        }

        floatingDateView.snp.makeConstraints {
            $0.top.equalTo(view.safeAreaLayoutGuide.snp.top).offset(8.0)
            $0.centerX.equalTo(view)
            $0.height.equalTo(26.0)
        }

        floatingDateLabel.snp.makeConstraints {
            $0.top.bottom.equalTo(floatingDateView)
            $0.leading.equalTo(floatingDateView).offset(12.0)
            $0.trailing.equalTo(floatingDateView).offset(-12.0)
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
        inputBottomFiller.backgroundColor = theme.colorForType(.ChatInputBackground)
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
                    break
                case .update(_, let deletions, let insertions, let modifications):
                    // While searching the table shows a filtered subset, so storage-index granular
                    // updates don't line up — keep the storage window in sync and reload.
                    if !self.messageSearchQuery.isEmpty {
                        self.visibleMessages = max(0, self.visibleMessages + insertions.count - deletions.count)
                        tableView.reloadData()
                        self.updateTableHeaderView()
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

                        if indicesInBounds && countReconciles {
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
                            tableView.beginUpdates()
                            if !deletePaths.isEmpty {
                                tableView.deleteRows(at: deletePaths, with: .top)
                            }
                            if !insertPaths.isEmpty {
                                tableView.insertRows(at: insertPaths, with: .top)
                            }
                            tableView.endUpdates()
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
            let message = messages[index]
            let indexPath = IndexPath(row: index, section: 0)

            if message.messageFile == nil {
                tableView.reloadRows(at: [indexPath], with: .none)
                continue
            }

            guard let cell = tableView.cellForRow(at: indexPath) as? ChatGenericFileCell else {
                continue
            }

            let model = ChatIncomingFileCellModel()
            prepareFileCell(cell, andModel: model, withMessage: message)
            cell.setupWithTheme(theme, model: model)

            maybeLoadImageForCellAtPath(cell, indexPath: indexPath)
        }
    }

    func addFriendNotification() {
        guard let friend = self.friend else {
            if chat.isSavedMessages {
                titleView.name = String(localized: "saved_messages_title")
                titleView.userStatus = UserStatus(connectionStatus: .none, userStatus: .none)
                titleView.connectionStatus = ConnectionStatus(connectionStatus: .none)
                audioButton.isEnabled = false
                videoButton.isEnabled = false
                locationButton.isEnabled = false
                chatInputView.cameraButtonEnabled = true
                return
            }
            titleView.name = String(localized: "contact_deleted")
            titleView.userStatus = UserStatus(connectionStatus: .none, userStatus: .none)
            titleView.connectionStatus = ConnectionStatus(connectionStatus: .none)
            audioButton.isEnabled = true
            videoButton.isEnabled = false
            locationButton.isEnabled = true
            chatInputView.cameraButtonEnabled = false
            return
        }

        titleView.name = friend.nickname
        // KHANDAQ design (Figma): contact avatar in the chat header.
        updateTitleAvatar(for: friend)
        updateTitlePresence(for: friend)

        let predicate = NSPredicate(format: "uniqueIdentifier == %@", friend.uniqueIdentifier)
        let results = submanagerObjects.friends(predicate: predicate)

        friendToken = results.addNotificationBlock { [weak self] change in
            guard let self = self, let friend = self.friend else {
                return
            }

            switch change {
                case .initial:
                    fallthrough
                case .update:
                    self.titleView.name = friend.nickname
                    self.updateTitleAvatar(for: friend)
                    self.updateTitlePresence(for: friend)

                    let isConnected = friend.isConnected

                    self.audioButton.isEnabled = true
                    self.videoButton.isEnabled = isConnected
                    self.locationButton.isEnabled = true
                    self.chatInputView.cameraButtonEnabled = true

                    self.updateTableHeaderView()
                case .error(let error):
                    log("ChatPrivateController friend notification error: \(error)")
            }
        }
    }

    fileprivate func hasPendingOutgoingDelivery() -> Bool {
        let textPredicate = NSPredicate(
            format: "messageText.isDelivered == NO AND senderUniqueIdentifier == nil")
        let filePredicate = NSPredicate(
            format: "messageFile != nil AND messageFile.fileType == %d AND messageFile.internalFileNumber == %u AND senderUniqueIdentifier == nil",
            OCTMessageFileType.waitingConfirmation.rawValue,
            UInt32.max)
        let compound = NSCompoundPredicate(orPredicateWithSubpredicates: [textPredicate, filePredicate])
        return messages.objects(with: compound).count > 0
    }

    fileprivate func updateTitleAvatar(for friend: OCTFriend) {
        // KHANDAQ (#95): show the contact's real avatar in the chat header (mirrors FriendCardController);
        // fall back to initials only when the contact has no avatar set.
        if let data = friend.avatarData, let image = UIImage(data: data) {
            titleView.avatar = image
        } else {
            titleView.avatar = AvatarManager(theme: theme).avatarFromString(friend.nickname, diameter: 30.0)
        }
    }

    func updateTitlePresence(for friend: OCTFriend) {
        // KHANDAQ (#43): Telegram-style typing — surface it in the nav subtitle instead of a bottom
        // bubble that overlapped the last message. Resolved here (not just in the typing callback) so
        // any presence refresh while the peer is typing keeps showing "typing…".
        if friend.isConnected && friend.isTyping {
            titleView.presenceText = String(localized: "chat_is_typing_text")
            titleView.presenceIsOnline = true
            return
        }

        let presence = FriendPresenceFormatter.presence(for: friend)
        titleView.presenceText = presence.text
        titleView.presenceIsOnline = presence.isOnline
    }

    func updateTableHeaderView() {
        guard let tableView = tableView else {
            return
        }

        guard let friend = friend else {
            return
        }

        let hasPendingDelivery = hasPendingOutgoingDelivery()

        UIView.animate(withDuration: Constants.NewMessageViewAnimationDuration, animations: {
        if friend.isConnected {
            if tableView.tableHeaderView === self.fauxOfflineHeaderView {
                tableView.tableHeaderView = nil
            }

            // KHANDAQ (#43): typing now shows in the nav subtitle (updateTitlePresence). Keep the table
            // anchored to the input bar and the old bottom typing bubble permanently hidden.
            self.tableViewToChatInputConstraint.activate()
            self.typingViewToChatInputConstraint.deactivate()
            self.typingHeaderView.isHidden = true
            self.typingHeaderView.stopAnimation()
            self.updateTitlePresence(for: friend)

            self.view.layoutIfNeeded()
        }
        else {
            self.tableViewToChatInputConstraint.activate()
            self.typingViewToChatInputConstraint.deactivate()
            self.typingHeaderView.isHidden = true
            self.typingHeaderView.stopAnimation()

            tableView.tableHeaderView = hasPendingDelivery ? self.fauxOfflineHeaderView : nil
        }
        })

        updateTableHeaderViewLayout()
    }

    func updateTableHeaderViewLayout() {
        guard let headerView = tableView?.tableHeaderView else {
            return
        }
        
        headerView.setNeedsLayout()
        headerView.layoutIfNeeded()
        let height = headerView.systemLayoutSizeFitting(UILayoutFittingCompressedSize).height
        headerView.frame.size.height = height
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

        return (model, cell)
    }

    func prepareFileCell(_ cell: ChatGenericFileCell, andModel model: ChatGenericFileCellModel, withMessage message: OCTMessageAbstract) {
        cell.progressObject = nil

        model.fileName = message.messageFile!.fileName
        model.fileSizeBytes = message.messageFile!.fileSize
        model.fileSize = ByteCountFormatter.string(fromByteCount: message.messageFile!.fileSize, countStyle: .file)
        model.fileUTI = message.messageFile!.fileUTI

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
                    FileTransferStallWatcher.shared.stop(messageId: messageId)
                }
            case .canceled:
                model.state = .cancelled
                if let messageId = message.uniqueIdentifier {
                    FileTransferStallWatcher.shared.stop(messageId: messageId)
                }
            case .ready:
                model.state = .done
                if let messageId = message.uniqueIdentifier {
                    FileTransferStallWatcher.shared.stop(messageId: messageId)
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
            model.voicePlayToggleHandle = {
                guard let path = message.messageFile?.filePath(),
                      message.messageFile?.fileType == .ready,
                      let messageId = message.uniqueIdentifier else {
                    return
                }
                ChatVoiceMessagePlayer.shared.togglePlayback(messageId: messageId, filePath: path)
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

        FileTransferStallWatcher.shared.track(messageId: messageId) { [weak self] in
            UIAlertController.showErrorWithMessage(String(localized: "file_transfer_stalled"), retryBlock: nil)
            _ = try? self?.submanagerFiles.cancelFileTransfer(message)
        }
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
                    barButtonSystemItem: .cancel,
                    target: self,
                    action: #selector(ChatPrivateController.cancelEditingButtonPressed))]
        }
        else {
            let audioImage = UIImage(named: "start-call-medium")!
            let videoImage = UIImage(named: "video-call-medium")!
            let locationImage = UIImage(named: "location-call-medium")!.withRenderingMode(.alwaysOriginal)
            audioButton = UIBarButtonItem(image: audioImage, style: .plain, target: self, action: #selector(ChatPrivateController.audioCallButtonPressed))
            videoButton = UIBarButtonItem(image: videoImage, style: .plain, target: self, action: #selector(ChatPrivateController.videoCallButtonPressed))
            locationButton = UIBarButtonItem(image: locationImage, style: .plain, target: self, action: #selector(ChatPrivateController.locationButtonPressed))

            if (AppDelegate.location_sharing_contact_pubkey != "-1") {
                let locationImage = UIImage(named: "location-call-activated-medium")!.withRenderingMode(.alwaysOriginal)
                locationButton.setBackgroundImage(locationImage, for: .normal, barMetrics: .default)
            }

            navigationItem.leftBarButtonItems = nil
            // KHANDAQ design (Figma): the chat header shows video + call only; location sharing lives in
            // the "+" attachment menu.
            navigationItem.rightBarButtonItems = [
                videoButton,
                audioButton
            ]
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
        let loadedCount = min(visibleMessages, messages.count)
        guard !messageSearchQuery.isEmpty else {
            return loadedCount
        }

        var count = 0
        for index in 0..<loadedCount where MessageSearchHighlighter.messageMatches(messages[index], query: messageSearchQuery) {
            count += 1
        }
        return count
    }

    func messageEntry(atDisplayIndex index: Int) -> (message: OCTMessageAbstract, storageIndex: Int) {
        let loadedCount = min(visibleMessages, messages.count)
        guard !messageSearchQuery.isEmpty else {
            return (messages[index], index)
        }

        var seen = 0
        for storageIndex in 0..<loadedCount where MessageSearchHighlighter.messageMatches(messages[storageIndex], query: messageSearchQuery) {
            if seen == index {
                return (messages[storageIndex], storageIndex)
            }
            seen += 1
        }

        let safeIndex = min(max(0, index), max(0, messages.count - 1))
        return (messages[safeIndex], safeIndex)
    }
}
