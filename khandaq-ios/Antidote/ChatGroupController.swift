// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit
import SnapKit
import MobileCoreServices
import QuickLook
import ImageIO
import AVFoundation

private struct Constants {
    static let MessagesPortionSize = 50
    static let MaxImageSizeToShowInline: OCTToxFileSize = 20 * 1024 * 1024
    static let MaxInlineImageSide: CGFloat = LoadingImageView.Constants.ImageButtonSize * UIScreen.main.scale
}

protocol ChatGroupControllerDelegate: class {
    func chatGroupControllerWillAppear(_ controller: ChatGroupController)
    func chatGroupControllerWillDisappear(_ controller: ChatGroupController)
    func chatGroupControllerDidRequestGroupInfo(_ controller: ChatGroupController)
    func chatGroupController(_ controller: ChatGroupController, didRequestOpenFriend friend: OCTFriend)
}

class ChatGroupController: PortraitChatController {
    let chat: OCTChat

    fileprivate weak var delegate: ChatGroupControllerDelegate?
    let theme: Theme
    fileprivate weak var submanagerGroups: OCTSubmanagerGroups!
    weak var submanagerObjects: OCTSubmanagerObjects!
    fileprivate weak var submanagerFriends: OCTSubmanagerFriends!
    weak var submanagerChats: OCTSubmanagerChats!

    var messages: Results<OCTMessageAbstract>
    fileprivate var messagesToken: RLMNotificationToken?
    var visibleMessages: Int
    fileprivate var showsSystemMessages: Bool

    fileprivate let timeFormatter: DateFormatter
    fileprivate var inputViewManager: ChatGroupInputViewManager?

    var editMessagesToolbar: UIToolbar!
    var editMessagesToolbarBottomConstraint: Constraint?
    var messageSearchQuery = ""
    var messageSearchController: UISearchController?

    var tableView: UITableView!
    var chatInputView: ChatInputView!
    fileprivate var tableViewToChatInputConstraint: Constraint!
    fileprivate var chatInputViewBottomConstraint: Constraint?
    fileprivate let imageCache = NSCache<AnyObject, AnyObject>()
    // KHANDAQ (#15): cached pixel dimensions (cheap header read) so the preview bubble is sized to the
    // media's aspect ratio synchronously at display time — avoids a height "jump" when the heavy
    // preview finishes loading async.
    fileprivate let mediaPixelSizeCache = NSCache<NSString, NSValue>()
    fileprivate var connectionStatusObserver: NSObjectProtocol?
    fileprivate var voicePlayerObserver: NSObjectProtocol?
    fileprivate var membersDrawerView: GroupMembersDrawerView?
    fileprivate var membersDrawerDimmingView: UIView?
    fileprivate var membersDrawerLeadingConstraint: Constraint?
    fileprivate var isMembersDrawerOpen = false
    fileprivate let ngcVideoOverlay = GroupNgcVideoOverlayView()
    fileprivate var incomingVideoPollTimer: Timer?
    fileprivate var incomingVideoActivityObserver: NSObjectProtocol?
    let replyController = ChatReplyController()

    private let membersDrawerWidth: CGFloat = 300

    init(theme: Theme,
         chat: OCTChat,
         submanagerGroups: OCTSubmanagerGroups,
         submanagerObjects: OCTSubmanagerObjects,
         submanagerFriends: OCTSubmanagerFriends,
         submanagerChats: OCTSubmanagerChats,
         delegate: ChatGroupControllerDelegate) {

        self.theme = theme
        self.chat = chat
        self.submanagerGroups = submanagerGroups
        self.submanagerObjects = submanagerObjects
        self.submanagerFriends = submanagerFriends
        self.submanagerChats = submanagerChats
        self.delegate = delegate

        self.showsSystemMessages = UserDefaultsManager().groupShowSystemMessages
        self.messages = Self.makeMessagesResults(for: chat, submanagerObjects: submanagerObjects, showSystemMessages: self.showsSystemMessages)
        self.visibleMessages = Constants.MessagesPortionSize
        self.timeFormatter = DateFormatter(type: .time)

        super.init()

        edgesForExtendedLayout = UIRectEdge()
        hidesBottomBarWhenPushed = true
        title = chat.groupName ?? String(localized: "group_chat_default_title")
    }

    required convenience init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    deinit {
        messagesToken?.invalidate()
        if let observer = connectionStatusObserver {
            NotificationCenter.default.removeObserver(observer)
        }
        if let observer = voicePlayerObserver {
            NotificationCenter.default.removeObserver(observer)
        }
    }

    override func loadView() {
        loadViewWithBackgroundColor(theme.colorForType(.NormalBackground))

        tableView = UITableView()
        tableView.estimatedRowHeight = 44.0
        tableView.backgroundColor = theme.colorForType(.NormalBackground)
        tableView.separatorStyle = .none
        tableView.delegate = self
        tableView.dataSource = self
        // KHANDAQ (#15): show newest messages at the BOTTOM (standard messenger order). Messages are
        // sorted newest-first; flip the table vertically (like ChatPrivateController) so row 0 sits at
        // the bottom and new messages grow upward from there. Cells are flipped back in willDisplay.
        tableView.transform = CGAffineTransform(a: 1, b: 0, c: 0, d: -1, tx: 0, ty: 0)
        tableView.register(ChatIncomingTextCell.self, forCellReuseIdentifier: ChatIncomingTextCell.staticReuseIdentifier)
        tableView.register(ChatOutgoingTextCell.self, forCellReuseIdentifier: ChatOutgoingTextCell.staticReuseIdentifier)
        tableView.register(ChatIncomingFileCell.self, forCellReuseIdentifier: ChatIncomingFileCell.staticReuseIdentifier)
        tableView.register(ChatOutgoingFileCell.self, forCellReuseIdentifier: ChatOutgoingFileCell.staticReuseIdentifier)
        tableView.register(UITableViewCell.self, forCellReuseIdentifier: "groupSystemCell")
        view.addSubview(tableView)

        chatInputView = ChatInputView(theme: theme)
        view.addSubview(chatInputView)
        view.addSubview(ngcVideoOverlay)

        // KHANDAQ: install the reply preview (which adds previewView into `view`) BEFORE constraining
        // the table's bottom to previewView.top. Doing the constraint first crashed with
        // "Unable to activate constraint ... no common ancestor" because previewView had no superview
        // yet — that crash fired when opening a (just-created) group chat. ChatPrivateController already
        // installs before constraining; this matches that order.
        replyController.install(in: view, above: chatInputView, theme: theme)

        tableView.snp.makeConstraints {
            $0.top.leading.trailing.equalTo(view)
            tableViewToChatInputConstraint = $0.bottom.equalTo(replyController.previewView.snp.top).constraint
        }

        chatInputView.snp.makeConstraints {
            $0.leading.trailing.equalTo(view)
            chatInputViewBottomConstraint = $0.bottom.equalTo(view).constraint
            if #available(iOS 11.0, *) {
                let b = UIApplication.shared.keyWindow?.safeAreaInsets.bottom ?? 20
                chatInputViewBottomConstraint?.update(offset: -b)
            }
        }

        ngcVideoOverlay.snp.makeConstraints {
            $0.leading.trailing.equalTo(view)
            if #available(iOS 11.0, *) {
                $0.top.equalTo(view.safeAreaLayoutGuide.snp.top)
            } else {
                $0.top.equalTo(view)
            }
            $0.bottom.equalTo(chatInputView.snp.top)
        }
    }

    override func viewDidLoad() {
        super.viewDidLoad()

        inputViewManager = ChatGroupInputViewManager(inputView: chatInputView,
                                                     chat: chat,
                                                     submanagerGroups: submanagerGroups,
                                                     submanagerObjects: submanagerObjects,
                                                     presentingViewController: self)
        inputViewManager?.outgoingTextComposer = { [weak self] text in
            guard let self = self else {
                return text
            }
            return self.replyController.composeOutgoingText(text)
        }

        ngcVideoOverlay.onQualityToggle = { [weak self] in
            guard let self = self, self.submanagerGroups.isGroupLiveVideoActive() else {
                return
            }

            let nextQuality = !self.submanagerGroups.isGroupLiveVideoHighQuality()
            self.submanagerGroups.setGroupLiveVideoHighQuality(nextQuality, for: self.chat)
            self.ngcVideoOverlay.setHighQuality(nextQuality)
        }

        updateGroupInfoBarButton()
        updateMembersBarButton()
        installMessageToolsUI()

        messagesToken = messages.addNotificationBlock { [weak self] _ in
            self?.tableView?.reloadData()
        }

        connectionStatusObserver = NotificationCenter.default.addObserver(
                forName: .octGroupConnectionStatusChange,
                object: nil,
                queue: .main) { [weak self] notification in
            guard let self = self,
                  let chatId = notification.userInfo?[kOCTGroupConnectionStatusChangeChatUniqueIdentifierKey] as? String,
                  chatId == self.chat.uniqueIdentifier else {
                return
            }

            self.prepareGroupLiveMediaMonitoringIfNeeded()
            self.updateConnectionBanner()
        }

        voicePlayerObserver = NotificationCenter.default.addObserver(
            forName: .chatVoiceMessagePlayerStateDidChange,
            object: nil,
            queue: .main) { [weak self] notification in
            self?.handleVoicePlayerStateChange(notification)
        }
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        reloadMessagesIfNeeded()
        updateGroupInfoBarButton()
        startIncomingVideoMonitoring()
        prepareGroupLiveMediaMonitoringIfNeeded()
        submanagerGroups.refreshPeers(for: chat)
        updateLastReadDate()
        updateConnectionBanner()
        delegate?.chatGroupControllerWillAppear(self)
    }

    func updateGroupInfoBarButton() {
        let infoItem: UIBarButtonItem
        if #available(iOS 13.0, *) {
            infoItem = UIBarButtonItem(
                    image: UIImage(systemName: "info.circle"),
                    style: .plain,
                    target: self,
                    action: #selector(ChatGroupController.infoButtonPressed))
        }
        else {
            infoItem = UIBarButtonItem(
                    title: String(localized: "group_info_button"),
                    style: .plain,
                    target: self,
                    action: #selector(ChatGroupController.infoButtonPressed))
        }
        infoItem.accessibilityLabel = String(localized: "group_info_title")

        let liveAudioItem: UIBarButtonItem
        if #available(iOS 13.0, *) {
            liveAudioItem = UIBarButtonItem(
                    image: UIImage(systemName: submanagerGroups.isGroupLiveAudioActive() ? "mic.circle.fill" : "mic.circle"),
                    style: .plain,
                    target: self,
                    action: #selector(ChatGroupController.toggleLiveAudio))
        }
        else {
            liveAudioItem = UIBarButtonItem(
                    title: String(localized: "group_live_audio_toggle"),
                    style: .plain,
                    target: self,
                    action: #selector(ChatGroupController.toggleLiveAudio))
        }
        liveAudioItem.accessibilityLabel = String(localized: "group_live_audio_toggle")

        let liveVideoItem: UIBarButtonItem
        let videoIconState = submanagerGroups.groupLiveVideoIconState(for: chat)
        if #available(iOS 13.0, *) {
            liveVideoItem = UIBarButtonItem(
                    image: liveVideoToolbarImage(for: videoIconState),
                    style: .plain,
                    target: self,
                    action: #selector(ChatGroupController.toggleLiveVideo))
        }
        else {
            liveVideoItem = UIBarButtonItem(
                    title: String(localized: "group_live_video_toggle"),
                    style: .plain,
                    target: self,
                    action: #selector(ChatGroupController.toggleLiveVideo))
        }
        liveVideoItem.accessibilityLabel = liveVideoToolbarAccessibilityLabel(for: videoIconState)

        navigationItem.rightBarButtonItems = [infoItem, liveVideoItem, liveAudioItem]
    }

    @available(iOS 13.0, *)
    private func liveVideoToolbarImage(for state: OCTGroupLiveVideoIconState) -> UIImage? {
        let symbolName: String

        switch state {
        case .active:
            symbolName = "video.circle.fill"
        default:
            symbolName = "video.circle"
        }

        guard let image = UIImage(systemName: symbolName) else {
            return nil
        }

        switch state {
        case .active:
            return image.withTintColor(.systemGreen, renderingMode: .alwaysOriginal)
        case .incoming:
            return image.withTintColor(.systemOrange, renderingMode: .alwaysOriginal)
        default:
            return image
        }
    }

    private func liveVideoToolbarAccessibilityLabel(for state: OCTGroupLiveVideoIconState) -> String {
        switch state {
        case .active:
            return String(localized: "group_live_video_toggle_active")
        case .incoming:
            return String(localized: "group_live_video_toggle_incoming")
        default:
            return String(localized: "group_live_video_toggle")
        }
    }

    private func startIncomingVideoMonitoring() {
        incomingVideoPollTimer?.invalidate()
        incomingVideoPollTimer = Timer.scheduledTimer(withTimeInterval: 2.0, repeats: true) { [weak self] _ in
            self?.refreshIncomingVideoUI()
        }

        if let timer = incomingVideoPollTimer {
            RunLoop.main.add(timer, forMode: RunLoop.Mode.commonModes)
        }

        incomingVideoActivityObserver = NotificationCenter.default.addObserver(
                forName: .octGroupLiveVideoActivity,
                object: nil,
                queue: .main) { [weak self] notification in
            guard let self = self else {
                return
            }

            guard let groupNumber = notification.userInfo?[kOCTGroupLiveVideoActivityGroupNumberKey] as? NSNumber else {
                return
            }

            if groupNumber.int32Value == self.chat.groupNumber {
                self.refreshIncomingVideoUI()
            }
        }
    }

    private func stopIncomingVideoMonitoring() {
        incomingVideoPollTimer?.invalidate()
        incomingVideoPollTimer = nil

        if let observer = incomingVideoActivityObserver {
            NotificationCenter.default.removeObserver(observer)
            incomingVideoActivityObserver = nil
        }
    }

    private func refreshIncomingVideoUI() {
        updateGroupInfoBarButton()
        updateNgcVideoStreamInfo()
    }

    private func prepareGroupLiveMediaMonitoringIfNeeded() {
        guard chat.groupNumber >= 0 else {
            return
        }

        submanagerGroups.prepareGroupLiveMediaMonitoring(for: chat)
    }

    private func updateNgcVideoStreamInfo() {
        guard submanagerGroups.isGroupLiveVideoActive() else {
            ngcVideoOverlay.setStreamInfo(nil)
            return
        }

        let streamCount = submanagerGroups.recentIncomingGroupLiveVideoPeerCount(for: chat)

        if streamCount == 0 {
            ngcVideoOverlay.setStreamInfo(String(localized: "group_live_video_streams_empty"))
            return
        }

        if let peerName = submanagerGroups.primaryRecentIncomingGroupLiveVideoPeerName(for: chat),
           !peerName.isEmpty {
            let format = String(localized: "group_live_video_streams_format")
            ngcVideoOverlay.setStreamInfo(String(format: format, streamCount, peerName))
        } else {
            let unknownPeer = String(localized: "group_live_video_stream_unknown_peer")
            let format = String(localized: "group_live_video_streams_format")
            ngcVideoOverlay.setStreamInfo(String(format: format, streamCount, unknownPeer))
        }
    }

    @objc func toggleLiveVideo() {
        if submanagerGroups.isGroupLiveVideoActive() {
            submanagerGroups.stopGroupLiveVideo()
            ngcVideoOverlay.clearFrames()
            ngcVideoOverlay.setVisible(false)
            updateGroupInfoBarButton()
            return
        }

        guard canStartGroupLiveMedia() else {
            return
        }

        let alert = UIAlertController(
                title: String(localized: "group_video_join_title"),
                message: String(localized: "group_video_join_message"),
                preferredStyle: .alert)
        alert.addAction(UIAlertAction(
                title: String(localized: "group_video_join_decline"),
                style: .cancel))
        alert.addAction(UIAlertAction(
                title: String(localized: "group_video_join_confirm"),
                style: .default) { [weak self] _ in
            self?.startGroupLiveVideoAfterConfirm()
        })
        present(alert, animated: true)
    }

    private func startGroupLiveVideoAfterConfirm() {
        if GroupLiveMediaErrorPresenter.isSimulator {
            GroupLiveMediaErrorPresenter.presentSimulatorUnavailable(forAudio: false)
            return
        }

        prepareGroupLiveMediaMonitoringIfNeeded()

        MediaPermission.requestCameraAccess(from: self) { [weak self] granted in
            guard granted, let self = self else {
                return
            }

            MediaPermission.requestMicrophoneAccess(from: self) { [weak self] granted in
                guard granted, let self = self else {
                    return
                }

                do {
                    try self.submanagerGroups.startGroupLiveVideo(for: self.chat,
                                                                  remoteFrameBlock: { [weak self] image in
                                                                      self?.ngcVideoOverlay.updateRemoteFrame(image)
                                                                  },
                                                                  localFrameBlock: { [weak self] image in
                                                                      self?.ngcVideoOverlay.updateLocalFrame(image)
                                                                  })
                    self.ngcVideoOverlay.setHighQuality(self.submanagerGroups.isGroupLiveVideoHighQuality())
                    self.ngcVideoOverlay.setVisible(true)
                    self.ngcVideoOverlay.updateRemoteFrame(nil)
                    self.refreshIncomingVideoUI()
                    self.updateGroupInfoBarButton()
                } catch let error as NSError {
                    GroupLiveMediaErrorPresenter.present(error)
                }
            }
        }
    }

    @objc func toggleLiveAudio() {
        if submanagerGroups.isGroupLiveAudioActive() {
            submanagerGroups.stopGroupLiveAudio()
            updateGroupInfoBarButton()
            return
        }

        guard canStartGroupLiveMedia() else {
            return
        }

        if GroupLiveMediaErrorPresenter.isSimulator {
            GroupLiveMediaErrorPresenter.presentSimulatorUnavailable(forAudio: true)
            return
        }

        prepareGroupLiveMediaMonitoringIfNeeded()

        MediaPermission.requestMicrophoneAccess(from: self) { [weak self] granted in
            guard granted, let self = self else {
                return
            }

            do {
                try self.submanagerGroups.startGroupLiveAudio(for: self.chat)
                self.updateGroupInfoBarButton()
            } catch let error as NSError {
                GroupLiveMediaErrorPresenter.present(error)
            }
        }
    }

    private func canStartGroupLiveMedia() -> Bool {
        guard chat.groupNumber >= 0, submanagerGroups.isGroupConnected(for: chat) else {
            UIAlertController.showWithTitle(
                String(localized: "error_title"),
                message: String(localized: "group_live_media_not_connected"),
                retryBlock: nil)
            return false
        }

        return true
    }

    func updateMembersBarButton() {
        let membersItem: UIBarButtonItem
        if #available(iOS 13.0, *) {
            membersItem = UIBarButtonItem(
                    image: UIImage(systemName: "person.2"),
                    style: .plain,
                    target: self,
                    action: #selector(ChatGroupController.membersButtonPressed))
        }
        else {
            membersItem = UIBarButtonItem(
                    title: String(localized: "group_member_list_title"),
                    style: .plain,
                    target: self,
                    action: #selector(ChatGroupController.membersButtonPressed))
        }
        membersItem.accessibilityLabel = String(localized: "group_member_list_title")
        navigationItem.leftItemsSupplementBackButton = true
        navigationItem.leftBarButtonItem = membersItem
    }

    @objc func membersButtonPressed() {
        toggleMembersDrawer()
    }

    @objc func infoButtonPressed() {
        closeMembersDrawer(animated: true)
        delegate?.chatGroupControllerDidRequestGroupInfo(self)
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        ChatVoiceMessagePlayer.shared.stop()
        stopIncomingVideoMonitoring()
        submanagerGroups.stopGroupLiveAudio()
        submanagerGroups.stopGroupLiveVideo()
        ngcVideoOverlay.clearFrames()
        ngcVideoOverlay.setVisible(false)
        saveEnteredText()
        delegate?.chatGroupControllerWillDisappear(self)
    }

    override func keyboardWillShowAnimated(keyboardFrame frame: CGRect) {
        super.keyboardWillShowAnimated(keyboardFrame: frame)
        chatInputViewBottomConstraint?.update(offset: -frame.size.height)
    }

    override func keyboardWillHideAnimated(keyboardFrame frame: CGRect) {
        super.keyboardWillHideAnimated(keyboardFrame: frame)
        var offset: CGFloat = 0
        if #available(iOS 11.0, *) {
            offset = -(UIApplication.shared.keyWindow?.safeAreaInsets.bottom ?? 20)
        }
        chatInputViewBottomConstraint?.update(offset: offset)
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        let maxHeight = view.bounds.height * 0.4
        chatInputView.maxHeight = maxHeight

        if isMembersDrawerOpen {
            membersDrawerLeadingConstraint?.update(offset: 0)
        }
    }

    func toggleMembersDrawer() {
        if isMembersDrawerOpen {
            closeMembersDrawer(animated: true)
        }
        else {
            openMembersDrawer()
        }
    }

    func openMembersDrawer() {
        guard membersDrawerView == nil else {
            membersDrawerView?.reloadFromTox()
            setMembersDrawer(open: true, animated: true)
            return
        }

        let dimmingView = UIView()
        dimmingView.backgroundColor = UIColor.black.withAlphaComponent(0.35)
        dimmingView.alpha = 0
        dimmingView.addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(ChatGroupController.membersDrawerDimmingTapped)))
        view.addSubview(dimmingView)
        dimmingView.snp.makeConstraints {
            $0.edges.equalTo(view)
        }
        membersDrawerDimmingView = dimmingView

        let drawer = GroupMembersDrawerView(theme: theme, chat: chat, submanagerGroups: submanagerGroups)
        drawer.delegate = self
        view.addSubview(drawer)
        drawer.snp.makeConstraints {
            membersDrawerLeadingConstraint = $0.leading.equalTo(view.snp.leading).offset(-membersDrawerWidth).constraint
            $0.top.bottom.equalTo(view)
            $0.width.equalTo(membersDrawerWidth)
        }
        membersDrawerView = drawer

        drawer.reloadFromTox()
        view.layoutIfNeeded()
        setMembersDrawer(open: true, animated: true)
    }

    func closeMembersDrawer(animated: Bool) {
        guard isMembersDrawerOpen else {
            return
        }

        setMembersDrawer(open: false, animated: animated)
    }

    @objc func membersDrawerDimmingTapped() {
        closeMembersDrawer(animated: true)
    }

    func setMembersDrawer(open: Bool, animated: Bool) {
        isMembersDrawerOpen = open
        membersDrawerLeadingConstraint?.update(offset: open ? 0 : -membersDrawerWidth)

        let animations = {
            self.membersDrawerDimmingView?.alpha = open ? 1 : 0
            self.view.layoutIfNeeded()
        }

        if animated {
            UIView.animate(withDuration: 0.25, animations: animations)
        }
        else {
            animations()
        }
    }

    func presentPeerActions(for peer: OCTGroupPeer, sourceView: UIView, sourceRect: CGRect) {
        GroupPeerActionsPresenter.presentActions(from: self,
                                                 theme: theme,
                                                 chat: chat,
                                                 peer: peer,
                                                 submanagerGroups: submanagerGroups,
                                                 submanagerObjects: submanagerObjects,
                                                 submanagerFriends: submanagerFriends,
                                                 popoverSourceView: sourceView,
                                                 popoverSourceRect: sourceRect,
                                                 onOpenFriend: { [weak self] friend in
            guard let self = self else {
                return
            }

            self.closeMembersDrawer(animated: true)
            self.delegate?.chatGroupController(self, didRequestOpenFriend: friend)
        },
                                                 onPeersChanged: { [weak self] in
            self?.membersDrawerView?.reloadFromTox()
        })
    }
}

extension ChatGroupController: UITableViewDataSource {
    func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        return displayableRowCount()
    }

    func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let entry = messageEntry(atDisplayIndex: indexPath.row)
        let message = entry.message
        let dateText = timeFormatter.string(from: message.date() as Date)
        let highlight = messageSearchQuery.isEmpty ? nil : messageSearchQuery

        if message.groupSystemMessage {
            let cell = tableView.dequeueReusableCell(withIdentifier: "groupSystemCell", for: indexPath)
            cell.backgroundColor = theme.colorForType(.NormalBackground)
            cell.selectionStyle = .none
            cell.textLabel?.numberOfLines = 0
            cell.textLabel?.font = UIFont.italicSystemFont(ofSize: UIFont.preferredFont(forTextStyle: .footnote).pointSize)
            cell.textLabel?.textColor = theme.colorForType(.FriendCellStatus)
            cell.textLabel?.textAlignment = .center
            cell.textLabel?.text = message.messageText?.text ?? ""
            return cell
        }

        if let messageFile = message.messageFile {
            let cell = fileCell(for: message, messageFile: messageFile, dateText: dateText, indexPath: indexPath)
            maybeLoadImageForCellAtPath(cell, indexPath: indexPath)
            return cell
        }

        if message.isOutgoing() {
            let cell = tableView.dequeueReusableCell(withIdentifier: ChatOutgoingTextCell.staticReuseIdentifier, for: indexPath) as! ChatOutgoingTextCell
            let model = ChatOutgoingTextCellModel()
            let parsed = GroupMentionHelper.parse(message.messageText?.text)
            model.message = parsed.bodyText
            model.mentionHandles = parsed.mentions.map { $0.handle }
            model.replyMeta = parsed.reply
            attachReplyQuoteHandler(to: model, messages: messages)
            model.searchHighlight = highlight
            model.dateString = dateText
            model.delivered = (message.messageText?.isDelivered ?? false) || (message.messageText?.groupSyncConfirmations ?? 0) > 0
            cell.delegate = self
            cell.replySwipeDelegate = self
            cell.setupWithTheme(theme, model: model)
            return cell
        }

        let cell = tableView.dequeueReusableCell(withIdentifier: ChatIncomingTextCell.staticReuseIdentifier, for: indexPath) as! ChatIncomingTextCell
        let model = ChatBaseTextCellModel()
        let parsed = GroupMentionHelper.parse(message.messageText?.text)
        let body = parsed.bodyText
        model.mentionHandles = parsed.mentions.map { $0.handle }
        if let header = peerHeader(for: message) {
            model.message = "\(header)\n\(body)"
        }
        else {
            model.message = body
        }
        model.replyMeta = parsed.reply
        attachReplyQuoteHandler(to: model, messages: messages)
        model.searchHighlight = highlight
        model.dateString = dateText
        cell.delegate = self
        cell.replySwipeDelegate = self
        cell.setupWithTheme(theme, model: model)
        return cell
    }
}

extension ChatGroupController: UITableViewDelegate {
    func scrollViewDidScroll(_ scrollView: UIScrollView) {
        let offset = scrollView.contentOffset.y
        if offset > scrollView.contentSize.height - scrollView.frame.size.height * 2 {
            if visibleMessages < messages.count {
                visibleMessages = min(visibleMessages + Constants.MessagesPortionSize, messages.count)
                tableView.reloadData()
            }
        }
    }

    // KHANDAQ (#15): file/media cells don't self-size in height (the movable-content cell hierarchy
    // breaks automatic-dimension for them — the row stays at the estimate and the square preview box
    // is squashed to a strip). Give inline image/video bubbles an explicit aspect-ratio height; let
    // everything else keep automatic sizing.
    func tableView(_ tableView: UITableView, heightForRowAt indexPath: IndexPath) -> CGFloat {
        let message = messageEntry(atDisplayIndex: indexPath.row).message
        guard let messageFile = message.messageFile,
              let file = messageFile.filePath(),
              messageFile.fileSize < Constants.MaxImageSizeToShowInline else {
            return UITableViewAutomaticDimension
        }

        let uti = inferredFileUTI(for: messageFile)
        let isImage = UTTypeConformsTo(uti as CFString? ?? "" as CFString, kUTTypeImage)
        let isVideo = ChatFileMediaLoader.isVideoFile(uti: uti, fileName: messageFile.fileName)
        guard isImage || isVideo else {
            return UITableViewAutomaticDimension
        }

        // Use real pixel dimensions when readable; otherwise a 4:3 fallback so an image/video bubble
        // is never squashed into a strip even if the header can't be read yet.
        let size = mediaPixelSize(forFileAt: file, isVideo: isVideo) ?? CGSize(width: 4, height: 3)
        return LoadingImageView.previewBox(for: size).height + 16.0
    }

    func tableView(_ tableView: UITableView, willDisplay cell: UITableViewCell, forRowAt indexPath: IndexPath) {
        // KHANDAQ (#15): the table is vertically flipped to put newest at the bottom; flip each cell
        // back so its content is upright.
        cell.transform = tableView.transform
        maybeLoadImageForCellAtPath(cell, indexPath: indexPath)
    }

    func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        if tableView.isEditing {
            return
        }

        tableView.deselectRow(at: indexPath, animated: true)
    }

    func tableView(_ tableView: UITableView, shouldShowMenuForRowAt indexPath: IndexPath) -> Bool {
        guard !tableView.isEditing else {
            return false
        }

        let message = messageEntry(atDisplayIndex: indexPath.row).message
        guard !message.groupSystemMessage else {
            return false
        }
        guard message.messageText != nil || message.messageFile != nil else {
            return false
        }

        guard let editable = tableView.cellForRow(at: indexPath) as? ChatEditable else {
            return false
        }

        return editable.shouldShowMenu()
    }

    func tableView(_ tableView: UITableView, canPerformAction action: Selector, forRowAt indexPath: IndexPath, withSender sender: Any?) -> Bool {
        guard let cell = tableView.cellForRow(at: indexPath) as? ChatMovableDateCell else {
            return false
        }

        return cell.isMenuActionSupportedByCell(action)
    }

    func tableView(_ tableView: UITableView, performAction action: Selector, forRowAt indexPath: IndexPath, withSender sender: Any?) {
    }

    @available(iOS 13.0, *)
    func tableView(_ tableView: UITableView, contextMenuConfigurationForRowAt indexPath: IndexPath, point: CGPoint) -> UIContextMenuConfiguration? {
        guard !tableView.isEditing else {
            return nil
        }

        let message = messageEntry(atDisplayIndex: indexPath.row).message
        guard quoteText(for: message) != nil else {
            return nil
        }

        return UIContextMenuConfiguration(identifier: nil, previewProvider: nil) { [weak self] _ in
            guard let self = self else {
                return nil
            }

            let reply = UIAction(title: String(localized: "chat_reply_action")) { _ in
                self.startReply(to: message)
            }
            let select = UIAction(title: String(localized: "group_messages_select_action")) { _ in
                self.toggleTableViewEditing(true, animated: true)
            }
            return UIMenu(children: [reply, select])
        }
    }
}

private extension ChatGroupController {
    func fileCell(for message: OCTMessageAbstract,
                  messageFile: OCTMessageFile,
                  dateText: String,
                  indexPath: IndexPath) -> UITableViewCell {
        let incoming = !message.isOutgoing()
        let cell: ChatGenericFileCell

        if incoming {
            cell = tableView.dequeueReusableCell(withIdentifier: ChatIncomingFileCell.staticReuseIdentifier, for: indexPath) as! ChatIncomingFileCell
        }
        else {
            cell = tableView.dequeueReusableCell(withIdentifier: ChatOutgoingFileCell.staticReuseIdentifier, for: indexPath) as! ChatOutgoingFileCell
        }

        let model: ChatGenericFileCellModel
        if incoming {
            model = ChatIncomingFileCellModel()
        }
        else {
            let outgoingModel = ChatOutgoingFileCellModel()
            outgoingModel.delivered = messageFile.isDelivered || messageFile.groupSyncConfirmations > 0
            model = outgoingModel
        }

        prepareGroupFileCell(cell, andModel: model, withMessage: message)

        if incoming, let peerName = peerName(for: message), !model.isVoiceMessage {
            model.fileName = "\(peerName)\n\(messageFile.fileName ?? "")"
        }

        model.dateString = dateText

        cell.setupWithTheme(theme, model: model)

        if messageFile.fileType == .loading && messageFile.groupTransferProgress > 0 {
            cell.updateProgress(CGFloat(messageFile.groupTransferProgress))
        }

        if messageFile.fileType == .loading {
            let bridge = ChatGroupProgressBridge()
            cell.progressObject = bridge
            bridge.observe(message)
        }

        return cell
    }

    func prepareGroupFileCell(_ cell: ChatGenericFileCell,
                              andModel model: ChatGenericFileCellModel,
                              withMessage message: OCTMessageAbstract) {
        cell.progressObject = nil

        guard let messageFile = message.messageFile else {
            return
        }

        model.fileName = messageFile.fileName
        model.fileSizeBytes = messageFile.fileSize
        model.fileSize = ByteCountFormatter.string(fromByteCount: messageFile.fileSize, countStyle: .file)
        model.fileUTI = inferredFileUTI(for: messageFile)

        let isVoice = VoiceMessageHelper.isVoiceMessage(fileName: messageFile.fileName,
                                                        filePath: messageFile.filePath())
        model.isVoiceMessage = isVoice
        model.voiceMessageId = message.uniqueIdentifier
        model.voiceTransferProgress = messageFile.groupTransferProgress

        if isVoice {
            model.fileName = VoiceMessageHelper.displayFileName(for: messageFile.fileName)
            if messageFile.fileType == .ready, let path = messageFile.filePath() {
                model.voiceDuration = VoiceMessageHelper.audioDuration(at: path)
            }
        }

        switch messageFile.fileType {
            case .loading:
                model.state = .loading
            case .ready:
                model.state = .done
            case .canceled:
                model.state = .cancelled
            default:
                model.state = .loading
        }

        if message.isOutgoing() && messageFile.fileType == .loading {
            model.cancelHandle = { [weak self] in
                guard let self = self else {
                    return
                }

                do {
                    try self.submanagerGroups.cancelGroupFileTransfer(forMessage: message)
                }
                catch let error as NSError {
                    handleErrorWithType(.cancelFileTransfer, error: error)
                }
            }
        }

        if message.isOutgoing() && messageFile.fileType == .canceled {
            model.retryHandle = { [weak self] in
                guard let self = self, let path = messageFile.filePath() else {
                    return
                }

                self.submanagerGroups.sendFile(atPath: path,
                                               to: self.chat,
                                               moveToUploads: false,
                                               successBlock: nil,
                                               failureBlock: { error in
                    handleErrorWithType(.sendFileToFriend, error: error as NSError?)
                })
            }
        }

        model.openHandle = { [weak self] in
            self?.presentFilePreview(for: message)
        }

        if isVoice {
            model.voicePlayToggleHandle = { [weak self] in
                guard let self = self,
                      let path = message.messageFile?.filePath(),
                      message.messageFile?.fileType == .ready else {
                    return
                }

                ChatVoiceMessagePlayer.shared.togglePlayback(messageId: message.uniqueIdentifier, filePath: path)
                self.refreshVisibleVoiceCells(for: message.uniqueIdentifier)
            }
        }
    }

    func presentFilePreview(for message: OCTMessageAbstract) {
        guard let messageFile = message.messageFile,
              !VoiceMessageHelper.isVoiceMessage(fileName: messageFile.fileName, filePath: messageFile.filePath()) else {
            return
        }

        guard let path = messageFile.filePath(),
              FileManager.default.fileExists(atPath: path) else {
            return
        }

        let qlDataSource = FilePreviewControllerDataSource(chat: chat, submanagerObjects: submanagerObjects)
        guard let index = qlDataSource.indexOfMessage(message), index >= 0 else {
            return
        }

        let preview = QuickLookPreviewController()
        preview.dataSource = qlDataSource
        preview.dataSourceStorage = qlDataSource
        preview.currentPreviewItemIndex = index
        present(preview, animated: true, completion: nil)
    }

    func handleVoicePlayerStateChange(_ notification: Notification) {
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
                  let messageFile = message.messageFile else {
                continue
            }

            let model = ChatGenericFileCellModel()
            model.isVoiceMessage = true
            model.voiceMessageId = messageId
            model.state = messageFile.fileType == .ready ? .done : .loading
            model.voiceTransferProgress = messageFile.groupTransferProgress
            fileCell.refreshVoiceMessagePresentation(theme: theme, fileModel: model)
        }
    }

    func peerHeader(for message: OCTMessageAbstract) -> String? {
        guard message.groupSenderPeerId > 0 else {
            return nil
        }

        let name = peerName(for: message) ?? String(localized: "group_peer_fallback_format", message.groupSenderPeerId)
        let role = submanagerGroups.peerRole(withId: UInt32(message.groupSenderPeerId), in: chat)
        return "\(name) · \(role.localizedName)"
    }

    func peerName(for message: OCTMessageAbstract) -> String? {
        guard message.groupSenderPeerId > 0 else {
            if let name = message.messageText?.groupPeerName, !name.isEmpty {
                return name
            }
            return nil
        }

        if let peers = submanagerGroups.peers(for: chat) {
            for i in 0..<peers.count {
                guard let peer = peers[UInt(i)] as? OCTGroupPeer else {
                    continue
                }

                if peer.peerId == message.groupSenderPeerId, let name = peer.peerName, !name.isEmpty {
                    return name
                }
            }
        }

        return String(localized: "group_peer_fallback_format", message.groupSenderPeerId)
    }

    func updateLastReadDate() {
        submanagerObjects.change(chat, lastReadDateInterval: Date().timeIntervalSince1970)
    }

    func reloadMessagesIfNeeded() {
        let showSystemMessages = UserDefaultsManager().groupShowSystemMessages

        guard showSystemMessages != showsSystemMessages else {
            return
        }

        showsSystemMessages = showSystemMessages
        messagesToken?.invalidate()
        messages = ChatGroupController.makeMessagesResults(for: chat,
                                                           submanagerObjects: submanagerObjects,
                                                           showSystemMessages: showSystemMessages)
        messagesToken = messages.addNotificationBlock { [weak self] _ in
            self?.tableView?.reloadData()
        }
        tableView?.reloadData()
    }

    func saveEnteredText() {
        let text = chatInputView.text
        submanagerObjects.change(chat, enteredText: text.isEmpty ? nil : text)
    }

    func updateConnectionBanner() {
        guard !submanagerGroups.isGroupConnected(for: chat) else {
            tableView.tableHeaderView = nil
            return
        }

        let container = UIView(frame: CGRect(x: 0, y: 0, width: tableView.bounds.width, height: 44))
        let label = UILabel()
        label.font = UIFont.preferredFont(forTextStyle: .footnote)
        label.textColor = theme.colorForType(.BusyStatus)
        label.textAlignment = .center
        label.numberOfLines = 2

        let attempt = submanagerGroups.groupJoinAttempt(for: chat)
        if attempt > 0 || submanagerGroups.isGroupJoinRetryRunning(for: chat) {
            let displayAttempt = max(attempt, 1)
            label.text = String(localized: "group_connecting_banner_format", displayAttempt)
        }
        else {
            label.text = String(localized: "group_disconnected_banner")
        }

        container.addSubview(label)
        label.snp.makeConstraints {
            $0.edges.equalToSuperview().inset(UIEdgeInsets(top: 8, left: 12, bottom: 8, right: 12))
        }
        container.layoutIfNeeded()
        let height = label.systemLayoutSizeFitting(UILayoutFittingCompressedSize).height + 16
        container.frame = CGRect(x: 0, y: 0, width: tableView.bounds.width, height: max(height, 36))
        // KHANDAQ (#15): the table is vertically flipped, so flip the header back to keep it upright.
        container.transform = tableView.transform
        tableView.tableHeaderView = container
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

        let uti = inferredFileUTI(for: messageFile)
        let fileName = messageFile.fileName
        let fileCell = (cell as? ChatIncomingFileCell) ?? (cell as? ChatOutgoingFileCell)

        if UTTypeConformsTo(uti as CFString? ?? "" as CFString, kUTTypeImage) {
            // KHANDAQ (#15): size the bubble synchronously from a cheap header read, so the heavy
            // decode (async) just fills the already-correct box instead of resizing the row later.
            if let pixelSize = mediaPixelSize(forFileAt: file, isVideo: false) {
                fileCell?.loadingView.setPreviewSize(pixelSize)
            }

            if let image = imageCache.object(forKey: file as AnyObject) as? UIImage {
                applyInlinePreview(image, durationText: nil, isVideo: false, to: cell)
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

    /// KHANDAQ (#15): cheap pixel-dimensions read (image header / video track) for aspect sizing.
    /// Cached per file path. Returns nil if it cannot be determined (cell keeps the square box).
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

        // Only cache a real measurement; a transient failure (e.g. file still downloading) must not
        // poison the cache permanently.
        guard result.width > 0, result.height > 0 else {
            return nil
        }
        mediaPixelSizeCache.setObject(NSValue(cgSize: result), forKey: path as NSString)
        return result
    }

    func applyInlinePreview(_ image: UIImage, durationText: String?, isVideo: Bool, to cell: UITableViewCell) {
        let fileCell = (cell as? ChatIncomingFileCell) ?? (cell as? ChatOutgoingFileCell)
        fileCell?.setButtonImage(image)

        if isVideo {
            if let incoming = fileCell as? ChatIncomingFileCell {
                incoming.setVideoPlayOverlay()
            }
            if let outgoing = fileCell as? ChatOutgoingFileCell {
                outgoing.setVideoPlayOverlay()
            }
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

    func inferredFileUTI(for messageFile: OCTMessageFile) -> String? {
        if let uti = messageFile.fileUTI, !uti.isEmpty {
            return uti
        }

        guard let fileName = messageFile.fileName else {
            return nil
        }

        let ext = (fileName as NSString).pathExtension
        guard !ext.isEmpty else {
            return nil
        }

        guard !ext.isEmpty,
              let uti = UTTypeCreatePreferredIdentifierForTag(
                kUTTagClassFilenameExtension,
                ext as CFString,
                nil
              )?.takeRetainedValue() as String? else {
            return nil
        }

        return uti
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

            let delta = (size.width > size.height)
                ? (Constants.MaxInlineImageSide / size.width)
                : (Constants.MaxInlineImageSide / size.height)

            size.width *= delta
            size.height *= delta

            image = image.scaleToSize(size)
            self?.imageCache.setObject(image, forKey: fromFile as AnyObject)

            DispatchQueue.main.async {
                let optionalCell = self?.tableView?.cellForRow(at: indexPath)
                guard let cell = optionalCell else {
                    return
                }

                self?.applyInlinePreview(image, durationText: nil, isVideo: false, to: cell)
            }
        }
    }
}

private extension ChatGroupController {
    static func makeMessagesResults(for chat: OCTChat,
                                    submanagerObjects: OCTSubmanagerObjects,
                                    showSystemMessages: Bool) -> Results<OCTMessageAbstract> {
        let predicateFormat = showSystemMessages
            ? "chatUniqueIdentifier == %@ AND groupPrivateMessage == NO"
            : "chatUniqueIdentifier == %@ AND groupPrivateMessage == NO AND groupSystemMessage == NO"
        let predicate = NSPredicate(format: predicateFormat, chat.uniqueIdentifier)
        return submanagerObjects.messages(predicate: predicate).sortedResultsUsingProperty("dateInterval", ascending: false)
    }
}

private extension OCTToxGroupRole {
    var localizedName: String {
        return groupLocalizedName
    }
}

extension ChatGroupController: GroupMembersDrawerViewDelegate {
    func groupMembersDrawerView(_ view: GroupMembersDrawerView, didSelectPeer peer: OCTGroupPeer, sourceRect: CGRect) {
        presentPeerActions(for: peer, sourceView: view, sourceRect: sourceRect)
    }

    func groupMembersDrawerViewDidRequestClose(_ view: GroupMembersDrawerView) {
        closeMembersDrawer(animated: true)
    }
}
