
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
    fileprivate var newMessagesView: UIView!
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
        loadViewWithBackgroundColor(theme.colorForType(.NormalBackground))

        createTableView()
        createTableHeaderViews()
        createNewMessagesView()
        createInputView()
        createEditMessageToolbar()
        installConstraints()
    }

    override func viewDidLoad() {
        super.viewDidLoad()

        addMessagesNotification()

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
                                                     submanagerObjects: self.submanagerObjects)
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
        guard let tableView = tableView else {
            return
        }

        tableView.setContentOffset(CGPoint.zero, animated: true)

        // iOS is broken =\
        // See https://stackoverflow.com/a/30804874
        let delayTime = DispatchTime.now() + Double(Int64(0.2 * Double(NSEC_PER_SEC))) / Double(NSEC_PER_SEC)
        DispatchQueue.main.asyncAfter(deadline: delayTime) { [weak self] in
            self?.tableView?.scrollToRow(at: IndexPath(row: 0, section: 0), at: .top, animated: true)
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
                            // send a text message to trigger PUSH notification, and make friend come online (hopefully)
                            // HINT: call OCTSubmanagerChatsImpl.m -> sendMessageToChat()
                            self.submanagerChats.sendMessage(to: self.chat, text: "calling you", type: .normal, successBlock: nil, failureBlock: nil)
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

        if (incoming_text_message) {
            if (message.tssent == 0) {
                model.dateString = timeFormatter.string(from: message.date()) + "\n" + dateFormatter.string(from: message.date())
            } else {
               let real_datetime = Date(timeIntervalSince1970: TimeInterval(message.tssent))
                model.dateString = timeFormatter.string(from: real_datetime) + "\n" + dateFormatter.string(from: real_datetime)
                // os_log("mmm:%s", timeFormatter.string(from: real_datetime))
            }
        } else {
            model.dateString = timeFormatter.string(from: message.date()) + "\n" + dateFormatter.string(from: message.date())
        }

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
           let caption = captionMessage(forFileAtStorageIndex: entry.storageIndex)?.messageText?.text {
            height += ChatGenericFileCell.captionHeight(for: caption, width: box.width)
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
    func captionMessage(forFileAtStorageIndex storageIndex: Int) -> OCTMessageAbstract? {
        guard storageIndex >= 0, storageIndex < messages.count else {
            return nil
        }
        let file = messages[storageIndex]
        guard file.messageFile != nil else {
            return nil
        }
        let captionIndex = storageIndex - 1   // newer message (DESC order) = sent just after the file
        guard captionIndex >= 0 else {
            return nil
        }
        let caption = messages[captionIndex]
        guard caption.isCaption,
              let text = caption.messageText?.text, !text.isEmpty,
              caption.isOutgoing() == file.isOutgoing() else {
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
        guard message.isCaption, message.messageText?.text?.isEmpty == false else {
            return false
        }
        let fileIndex = storageIndex + 1   // older message (DESC order) = the file it captions
        guard fileIndex < messages.count else {
            return false
        }
        let file = messages[fileIndex]
        return file.messageFile != nil && file.isOutgoing() == message.isOutgoing()
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
        tableView.scrollsToTop = false
        tableView.allowsSelection = false
        tableView.estimatedRowHeight = 44.0
        tableView.backgroundColor = theme.colorForType(.NormalBackground)
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
        label.text = String(localized: "chat_new_messages")
        label.textColor = theme.colorForType(.ConnectingText)
        label.backgroundColor = .clear
        label.font = UIFont.systemFont(ofSize: 12.0)
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

    func createInputView() {
        chatInputView = ChatInputView(theme: theme)
        view.addSubview(chatInputView)

        chatInputViewManager = ChatInputViewManager(inputView: chatInputView,
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
                        tableView.beginUpdates()
                        self.updateTableViewWithDeletions(deletions)
                        self.updateTableViewWithInsertions(insertions)
                        tableView.endUpdates()
                        if !modifications.isEmpty {
                            self.updateTableViewWithModifications(modifications)
                        }
                        self.visibleMessages = self.visibleMessages + insertions.count - deletions.count
                    }
                    else {
                        tableView.reloadData()
                    }

                    self.updateTableHeaderView()

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

    func updateTitlePresence(for friend: OCTFriend) {
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

            if friend.isTyping {
                self.tableViewToChatInputConstraint.deactivate()
                self.typingViewToChatInputConstraint.activate()
                self.typingHeaderView.isHidden = false
                self.typingHeaderView.startAnimation()
            }
            else {
                self.tableViewToChatInputConstraint.activate()
                self.typingViewToChatInputConstraint.deactivate()
                self.typingHeaderView.isHidden = true
                self.typingHeaderView.stopAnimation()
            }

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
            model.caption = (idx >= 0 && idx < messages.count)
                ? captionMessage(forFileAtStorageIndex: idx)?.messageText?.text
                : nil
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

        let uti = messageFile.fileUTI
        let fileName = messageFile.fileName

        if UTTypeConformsTo(uti as CFString? ?? "" as CFString, kUTTypeImage) {
            if let image = imageCache.object(forKey: file as AnyObject) as? UIImage {
                applyInlinePreview(image, durationText: nil, to: cell)
            }
            else {
                loadImageForCellAtIndexPath(indexPath, fromFile: file)
            }
            return
        }

        if ChatFileMediaLoader.isVideoFile(uti: uti, fileName: fileName) {
            if let preview = ChatFileMediaLoader.cachedPreview(for: file) {
                applyInlinePreview(preview.image, durationText: preview.durationText, to: cell)
            }
            else {
                loadVideoPreviewForCellAtIndexPath(indexPath, fromFile: file)
            }
        }
    }

    func applyInlinePreview(_ image: UIImage, durationText: String?, to cell: UITableViewCell) {
        let fileCell = (cell as? ChatIncomingFileCell) ?? (cell as? ChatOutgoingFileCell)
        fileCell?.setButtonImage(image)
        if let durationText = durationText, let outgoing = fileCell as? ChatOutgoingFileCell {
            outgoing.setVideoDurationLabel(durationText)
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
                self?.applyInlinePreview(preview.image, durationText: preview.durationText, to: cell)
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
            navigationItem.rightBarButtonItems = [
                videoButton,
                audioButton,
                locationButton
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
