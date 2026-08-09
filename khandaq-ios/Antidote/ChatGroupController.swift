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
    /// KHANDAQ (#192): Telegram-style floating reaction bar shown above a group message on long-press.
    let reactionPopup = ChatReactionPopup()
    weak var submanagerGroups: OCTSubmanagerGroups!
    weak var submanagerObjects: OCTSubmanagerObjects!
    fileprivate weak var submanagerFriends: OCTSubmanagerFriends!
    weak var submanagerChats: OCTSubmanagerChats!

    var messages: Results<OCTMessageAbstract>
    fileprivate var messagesToken: RLMNotificationToken?
    // KHANDAQ (#119): id of the newest own-outgoing message we already auto-scrolled to (so status-only
    // updates don't re-scroll the list).
    fileprivate var lastAutoScrolledOutgoingId: String?
    var visibleMessages: Int
    fileprivate var showsSystemMessages: Bool

    fileprivate let timeFormatter: DateFormatter
    fileprivate var inputViewManager: ChatGroupInputViewManager?

    // KHANDAQ (#99): locale-aware "Сегодня"/"Вчера"/full-date for the day separators (the group
    // controller has no floating-date pill, so it owns its own formatter).
    fileprivate lazy var daySeparatorFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .none
        formatter.doesRelativeDateFormatting = true
        return formatter
    }()

    var editMessagesToolbar: UIToolbar!
    var editMessagesToolbarBottomConstraint: Constraint?
    var messageSearchQuery = ""
    var messageSearchController: UISearchController?

    var tableView: UITableView!
    var chatInputView: ChatInputView!
    // KHANDAQ (#93-task): floating "jump to latest" button, shown when scrolled up.
    fileprivate var scrollDownButton: UIButton!
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
        // KHANDAQ (#46): prefer the owner-changeable TOPIC (NGC group name is immutable; a "rename"
        // updates the topic), falling back to the immutable name.
        title = (chat.groupTopic?.isEmpty == false ? chat.groupTopic : chat.groupName)
            ?? String(localized: "group_chat_default_title")
    }

    required convenience init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    deinit {
        messagesToken?.invalidate()
        // KHANDAQ (leak): stop the 2s incoming-video poll on teardown; without this a mid-poll dealloc
        // left a repeating timer firing into a nil weak self on the main run loop.
        incomingVideoPollTimer?.invalidate()
        if let observer = connectionStatusObserver {
            NotificationCenter.default.removeObserver(observer)
        }
        if let observer = voicePlayerObserver {
            NotificationCenter.default.removeObserver(observer)
        }
    }

    override func loadView() {
        loadViewWithBackgroundColor(theme.colorForType(.ChatBackground))

        // KHANDAQ design (Figma): doodle wallpaper behind the (transparent) message cells.
        let chatBackgroundView = ThemeChrome.makeChatDoodleBackgroundView()
        view.addSubview(chatBackgroundView)
        chatBackgroundView.snp.makeConstraints { $0.edges.equalTo(view) }

        tableView = UITableView()
        tableView.estimatedRowHeight = 44.0
        // Transparent so the doodle background shows through.
        tableView.backgroundColor = .clear
        tableView.separatorStyle = .none
        // KHANDAQ (#37): Telegram-style — drag the message list toward the keyboard to dismiss it
        // (works on the y-flipped table; the interactive dismiss tracks the finger in window coords).
        tableView.keyboardDismissMode = .interactive
        tableView.delegate = self
        tableView.dataSource = self
        // KHANDAQ (#15): show newest messages at the BOTTOM (standard messenger order). Messages are
        // sorted newest-first; flip the table vertically (like ChatPrivateController) so row 0 sits at
        // the bottom and new messages grow upward from there. Cells are flipped back in willDisplay.
        tableView.transform = CGAffineTransform(a: 1, b: 0, c: 0, d: -1, tx: 0, ty: 0)
        // KHANDAQ (#37): y-flipped table → contentInset.top renders at the VISUAL bottom; a small gap
        // so the newest message clears the input bar (parity with the 1:1 controller).
        tableView.contentInset.top = 6.0
        tableView.scrollIndicatorInsets.top = 6.0
        tableView.register(ChatIncomingTextCell.self, forCellReuseIdentifier: ChatIncomingTextCell.staticReuseIdentifier)
        tableView.register(ChatOutgoingTextCell.self, forCellReuseIdentifier: ChatOutgoingTextCell.staticReuseIdentifier)
        tableView.register(ChatIncomingFileCell.self, forCellReuseIdentifier: ChatIncomingFileCell.staticReuseIdentifier)
        tableView.register(ChatOutgoingFileCell.self, forCellReuseIdentifier: ChatOutgoingFileCell.staticReuseIdentifier)
        tableView.register(UITableViewCell.self, forCellReuseIdentifier: "groupSystemCell")
        view.addSubview(tableView)

        // KHANDAQ (#G5 reaction-row): custom Telegram-style long-press popup (reaction bar + action menu
        // together) for groups too — parity with 1:1. Replaces the system context menu (see
        // contextMenuConfigurationForRowAt → nil). minimumPressDuration 0.4 beats UITextView's ~0.5s
        // selection press so it wins over text selection.
        let longPressGR = UILongPressGestureRecognizer(target: self, action: #selector(ChatGroupController.longPressOnGroupTable(_:)))
        longPressGR.minimumPressDuration = 0.4
        tableView.addGestureRecognizer(longPressGR)

        chatInputView = ChatInputView(theme: theme)
        view.addSubview(chatInputView)
        view.addSubview(ngcVideoOverlay)

        // KHANDAQ (#93-task): floating "jump to latest" button (mirrors the 1:1 chat).
        scrollDownButton = UIButton(type: .system)
        scrollDownButton.setTitle("↓", for: .normal)
        scrollDownButton.titleLabel?.font = UIFont.systemFont(ofSize: 20.0, weight: .semibold)
        scrollDownButton.setTitleColor(theme.colorForType(.ConnectingText), for: .normal)
        scrollDownButton.backgroundColor = theme.colorForType(.ConnectingBackground)
        scrollDownButton.layer.cornerRadius = 20.0
        scrollDownButton.layer.masksToBounds = true
        scrollDownButton.alpha = 0.0
        scrollDownButton.addTarget(self, action: #selector(ChatGroupController.scrollDownButtonPressed), for: .touchUpInside)
        view.addSubview(scrollDownButton)

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

        // KHANDAQ (#115): fill the home-indicator gap beneath the input bar with the bar's own
        // background (the bar is pushed up by the safe area, exposing the chat doodle otherwise).
        let inputBottomFiller = UIView()
        inputBottomFiller.backgroundColor = theme.colorForType(.ChatInputBackground)
        inputBottomFiller.isUserInteractionEnabled = false
        view.insertSubview(inputBottomFiller, belowSubview: chatInputView)
        inputBottomFiller.snp.makeConstraints {
            $0.leading.trailing.equalTo(view)
            $0.top.equalTo(chatInputView.snp.bottom)
            $0.bottom.equalTo(view)
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

        scrollDownButton.snp.makeConstraints {
            $0.trailing.equalTo(view).offset(-12.0)
            $0.bottom.equalTo(chatInputView.snp.top).offset(-10.0)
            $0.width.height.equalTo(40.0)
        }
    }

    override func viewDidLoad() {
        super.viewDidLoad()

        replyController.restorePending(forChatId: chat.uniqueIdentifier, theme: theme)
        inputViewManager = ChatGroupInputViewManager(inputView: chatInputView,
                                                     chat: chat,
                                                     submanagerGroups: submanagerGroups,
                                                     submanagerObjects: submanagerObjects,
                                                     presentingViewController: self)
        inputViewManager?.outgoingTextComposer = { [weak self] text in
            guard let self = self else {
                return text
            }
            let composed = self.replyController.composeOutgoingText(text)
            // KHANDAQ (dup fix): purge the PERSISTED reply too — see ChatPrivateController
            self.replyController.savePending(forChatId: self.chat.uniqueIdentifier)
            return composed
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
            self?.handleMessagesChange()
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
        // KHANDAQ (#25): force an immediate reconnect of a stalled group on open instead of waiting
        // up to 90s for the maintenance tick.
        submanagerGroups.foregroundReconnect(for: chat)
        submanagerGroups.refreshPeers(for: chat)
        updateLastReadDate()
        updateConnectionBanner()
        updateGroupTitleView()
        delegate?.chatGroupControllerWillAppear(self)
    }

    /// KHANDAQ (Figma): group nav bar shows the name with a "N участников" subtitle underneath.
    private func updateGroupTitleView() {
        let name = (chat.groupTopic?.isEmpty == false ? chat.groupTopic : chat.groupName)
            ?? String(localized: "group_chat_default_title")
        let count = max(Int(submanagerGroups.peerCount(for: chat)), 1)

        let titleLabel = UILabel()
        titleLabel.text = name
        titleLabel.font = UIFont.systemFont(ofSize: 16.0, weight: .semibold)
        titleLabel.textColor = theme.colorForType(.NormalText)
        titleLabel.textAlignment = .center

        let subtitleLabel = UILabel()
        subtitleLabel.text = String(localized: "group_member_count_format", count)
        subtitleLabel.font = UIFont.systemFont(ofSize: 12.0)
        subtitleLabel.textColor = theme.colorForType(.FriendCellStatus)
        subtitleLabel.textAlignment = .center

        let stack = UIStackView(arrangedSubviews: [titleLabel, subtitleLabel])
        stack.axis = .vertical
        stack.alignment = .center
        stack.spacing = 0
        navigationItem.titleView = stack
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        scrollToGlobalSearchTargetIfNeeded()
        refreshPinnedBanner()   // KHANDAQ (#G5): restore the pinned-message banner if any.
    }

    // KHANDAQ (#G5): pinned-message banner for group chats (mirrors ChatPrivateController).
    private var pinnedBannerView: UIView? {
        get { objc_getAssociatedObject(self, &ChatGroupController.pinBannerKey) as? UIView }
        set { objc_setAssociatedObject(self, &ChatGroupController.pinBannerKey, newValue, .OBJC_ASSOCIATION_RETAIN_NONATOMIC) }
    }
    private var pinnedBannerLabel: UILabel? {
        get { objc_getAssociatedObject(self, &ChatGroupController.pinLabelKey) as? UILabel }
        set { objc_setAssociatedObject(self, &ChatGroupController.pinLabelKey, newValue, .OBJC_ASSOCIATION_RETAIN_NONATOMIC) }
    }
    private static var pinBannerKey: UInt8 = 0
    private static var pinLabelKey: UInt8 = 0
    private static let pinnedBannerHeight: CGFloat = 46.0

    func refreshPinnedBanner() {
        let pinnedId = ChatPinStore.pinnedMessageId(forChat: chat.uniqueIdentifier)
        var preview: String?
        if let pinnedId = pinnedId {
            for i in 0..<messages.count where messages[i].uniqueIdentifier == pinnedId {
                let m = messages[i]
                if let text = m.messageText?.text, !text.isEmpty {
                    preview = MessageReplyHelper.plainBody(for: m) ?? text
                } else if let file = m.messageFile {
                    preview = "📎 " + (file.fileName ?? "")
                } else {
                    preview = "📌"
                }
                break
            }
        }
        guard let text = preview else {
            if pinnedId != nil { ChatPinStore.clearPin(forChat: chat.uniqueIdentifier) }
            pinnedBannerView?.isHidden = true
            tableView?.contentInset.top = 0
            tableView?.verticalScrollIndicatorInsets.top = 0
            return
        }
        ensurePinnedBannerBuilt()
        pinnedBannerLabel?.text = text
        pinnedBannerView?.isHidden = false
        if let banner = pinnedBannerView { view.bringSubview(toFront: banner) }
        tableView?.contentInset.top = ChatGroupController.pinnedBannerHeight
        tableView?.verticalScrollIndicatorInsets.top = ChatGroupController.pinnedBannerHeight
    }

    private func ensurePinnedBannerBuilt() {
        guard pinnedBannerView == nil else { return }
        let banner = UIView()
        banner.backgroundColor = theme.colorForType(.NormalBackground)
        banner.isUserInteractionEnabled = true
        banner.addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(pinnedBannerTapped)))

        let accent = UIView()
        accent.backgroundColor = theme.colorForType(.LinkText)

        let title = UILabel()
        title.text = String(localized: "chat_pin_action")
        title.font = UIFont.systemFont(ofSize: 11.0, weight: .semibold)
        title.textColor = theme.colorForType(.LinkText)

        let label = UILabel()
        label.font = UIFont.systemFont(ofSize: 14.0)
        label.textColor = theme.colorForType(.NormalText)
        label.lineBreakMode = .byTruncatingTail

        let close = UIButton(type: .system)
        if #available(iOS 13.0, *) { close.setImage(UIImage(systemName: "xmark"), for: .normal) } else { close.setTitle("✕", for: .normal) }
        close.tintColor = theme.colorForType(.NormalText)
        close.addTarget(self, action: #selector(pinnedBannerClosePressed), for: .touchUpInside)

        let separator = UIView()
        separator.backgroundColor = theme.colorForType(.SeparatorsAndBorders)

        banner.addSubview(accent); banner.addSubview(title); banner.addSubview(label); banner.addSubview(close); banner.addSubview(separator)
        view.addSubview(banner)

        banner.snp.makeConstraints {
            $0.top.equalTo(view.safeAreaLayoutGuide.snp.top)
            $0.leading.trailing.equalTo(view)
            $0.height.equalTo(ChatGroupController.pinnedBannerHeight)
        }
        accent.snp.makeConstraints { $0.leading.equalTo(banner).offset(12.0); $0.top.equalTo(banner).offset(8.0); $0.bottom.equalTo(banner).offset(-8.0); $0.width.equalTo(3.0) }
        close.snp.makeConstraints { $0.trailing.equalTo(banner).offset(-14.0); $0.centerY.equalTo(banner); $0.width.height.equalTo(24.0) }
        title.snp.makeConstraints { $0.leading.equalTo(accent.snp.trailing).offset(10.0); $0.top.equalTo(banner).offset(6.0); $0.trailing.lessThanOrEqualTo(close.snp.leading).offset(-10.0) }
        label.snp.makeConstraints { $0.leading.equalTo(accent.snp.trailing).offset(10.0); $0.top.equalTo(title.snp.bottom).offset(1.0); $0.trailing.lessThanOrEqualTo(close.snp.leading).offset(-10.0) }
        separator.snp.makeConstraints { $0.leading.trailing.bottom.equalTo(banner); $0.height.equalTo(0.5) }

        pinnedBannerView = banner
        pinnedBannerLabel = label
    }

    @objc private func pinnedBannerTapped() {
        guard let id = ChatPinStore.pinnedMessageId(forChat: chat.uniqueIdentifier), let tableView = tableView else { return }
        var storageIndex: Int?
        for i in 0..<messages.count where messages[i].uniqueIdentifier == id { storageIndex = i; break }
        guard let target = storageIndex else { return }
        if target >= visibleMessages {
            visibleMessages = min(messages.count, target + Constants.MessagesPortionSize)
            tableView.reloadData(); tableView.layoutIfNeeded()
        }
        let rows = tableView.numberOfRows(inSection: 0)
        for row in 0..<rows where messageEntry(atDisplayIndex: row).message.uniqueIdentifier == id {
            let ip = IndexPath(row: row, section: 0)
            DispatchQueue.main.async { tableView.scrollToRow(at: ip, at: .middle, animated: true) }
            break
        }
    }

    @objc private func pinnedBannerClosePressed() {
        ChatPinStore.clearPin(forChat: chat.uniqueIdentifier)
        refreshPinnedBanner()
    }

    /// KHANDAQ (#64): if this group was opened from a global-search message hit, scroll to + briefly
    /// highlight that message.
    func scrollToGlobalSearchTargetIfNeeded() {
        guard let messageId = GlobalSearchScrollTarget.take(forChatId: chat.uniqueIdentifier),
              let tableView = tableView else {
            return
        }

        // KHANDAQ (#7): the target may be older than the loaded window — expand `visibleMessages` to
        // include its storage index before looking up the display row, otherwise the scroll no-ops.
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
            // settles the table's content size (scrolling inline landed short or no-opped).
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
            // KHANDAQ design: brand accent for the active live-video state (was Apple systemGreen).
            return image.withTintColor(UIColor(red: 0.01, green: 0.61, blue: 0.49, alpha: 1.0), renderingMode: .alwaysOriginal)
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
        // KHANDAQ: mark read on EXIT too — viewWillAppear alone misses messages that arrived while the
        // chat was open, leaving the unread badge stuck after the user has actually read everything.
        updateLastReadDate()
        // KHANDAQ (#G7-lock, review): abort an in-progress (esp. slide-up-locked) RECORDING on leave —
        // it survives a finger-lift, so it would otherwise run off-screen leaking the audio session +
        // temp file. (Distinct from voice PLAYBACK below, which is intentionally app-scoped.)
        chatInputView?.abortActiveRecordingIfNeeded()
        // KHANDAQ (Android parity): voice playback keeps going when the user leaves the group —
        // the shared player is app-scoped and must only stop on explicit pause/displacement.
        stopIncomingVideoMonitoring()
        submanagerGroups.stopGroupLiveAudio()
        submanagerGroups.stopGroupLiveVideo()
        ngcVideoOverlay.clearFrames()
        ngcVideoOverlay.setVisible(false)
        saveEnteredText()
        replyController.savePending(forChatId: chat.uniqueIdentifier)
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

        // Telegram-style: a caption text merged into its file bubble has no row of its own.
        if messageSearchQuery.isEmpty, isMergedCaptionRow(storageIndex: entry.storageIndex) {
            let blank = UITableViewCell()
            blank.backgroundColor = .clear
            blank.contentView.backgroundColor = .clear
            blank.selectionStyle = .none
            blank.isUserInteractionEnabled = false
            return blank
        }

        if message.groupSystemMessage {
            let cell = tableView.dequeueReusableCell(withIdentifier: "groupSystemCell", for: indexPath)
            // Transparent so the doodle chat background shows through (matches the message cells).
            cell.backgroundColor = .clear
            cell.contentView.backgroundColor = .clear
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
            // KHANDAQ (#iOS forward): clean stacked/legacy "↪ Переслано от (null)" at display (no-op otherwise).
            model.message = MessageReplyHelper.normalizeForwardedHeader(parsed.bodyText)
            // KHANDAQ: render shared location as a map bubble in groups too (parity with 1:1 +
            // Android — both use the "khandaq-location:lat,lon" wire format).
            if let location = LocationMessage.parse(parsed.bodyText) {
                model.locationLatitude = location.latitude
                model.locationLongitude = location.longitude
            }
            model.mentionHandles = parsed.mentions.map { $0.handle }
            model.replyMeta = parsed.reply
            attachReplyQuoteHandler(to: model, messages: messages)
            model.searchHighlight = highlight
            model.dateString = dateText
            model.delivered = (message.messageText?.isDelivered ?? false) || (message.messageText?.groupSyncConfirmations ?? 0) > 0
            model.dateSeparator = daySeparatorString(forDisplayIndex: indexPath.row)
            model.reactionsDisplay = ChatReactionsFormat.display(from: message.reactionsJSON)
            // KHANDAQ (#208): own group text with a shared message_id is editable (not replies/location).
            let editIdOK = (message.messageText?.messageId ?? 0) != 0
            model.canEdit = editIdOK && model.replyMeta == nil && !model.hasLocation
            // KHANDAQ (#H2): «изменено» rendered as a small dim suffix by the cell, not appended text.
            model.edited = message.edited
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
        if let location = LocationMessage.parse(body) {
            // Map bubble (the cell shows the map + coords; the per-peer header isn't shown inline for
            // location, same as 1:1). Cross-platform: matches the Android "khandaq-location:" format.
            model.locationLatitude = location.latitude
            model.locationLongitude = location.longitude
        }
        else if let header = peerHeader(for: message) {
            model.message = "\(header)\n\(MessageReplyHelper.normalizeForwardedHeader(body))"
            // KHANDAQ (Figma): per-peer colored sender header, keyed by the frozen display name.
            model.senderHeaderLength = header.count
            model.senderColor = GroupPeerColors.color(forName: peerName(for: message) ?? header)
        }
        else {
            model.message = MessageReplyHelper.normalizeForwardedHeader(body)
        }
        model.replyMeta = parsed.reply
        attachReplyQuoteHandler(to: model, messages: messages)
        model.searchHighlight = highlight
        model.dateString = dateText
        model.dateSeparator = daySeparatorString(forDisplayIndex: indexPath.row)
        model.reactionsDisplay = ChatReactionsFormat.display(from: message.reactionsJSON)
        // KHANDAQ (#208/#H2): peer can edit their own group message → cell shows a small dim «изменено».
        model.edited = message.edited
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

        // KHANDAQ (#93-task): show the jump-to-latest button whenever the newest row is off-screen.
        // Treat an empty chat (no rows) as already-at-bottom so the button stays hidden and can't crash.
        let atBottom = tableView.numberOfRows(inSection: 0) == 0
            || (tableView.indexPathsForVisibleRows?.contains(IndexPath(row: 0, section: 0)) ?? true)
        let target: CGFloat = atBottom ? 0.0 : 1.0
        if scrollDownButton.alpha != target {
            UIView.animate(withDuration: 0.2) { self.scrollDownButton.alpha = target }
        }
    }

    // KHANDAQ (#119): reload + jump to the newest message when WE just sent it (Telegram-style).
    func handleMessagesChange() {
        tableView?.reloadData()
        scrollToNewestIfOwnOutgoing()
    }

    func scrollToNewestIfOwnOutgoing() {
        guard messages.count > 0 else {
            return
        }
        let newest = messages[0]
        guard newest.isOutgoing() else {
            return
        }
        let id = newest.uniqueIdentifier
        guard id != lastAutoScrolledOutgoingId else {
            return
        }
        lastAutoScrolledOutgoingId = id
        scrollToNewestMessage()
    }

    func scrollToNewestMessage(animated: Bool = true) {
        guard let tableView = tableView else {
            return
        }
        // KHANDAQ: an empty chat has no row 0 — scrollToRow would crash.
        guard tableView.numberOfRows(inSection: 0) > 0 else {
            return
        }
        tableView.setContentOffset(CGPoint.zero, animated: animated)
        // iOS needs a re-assert after layout settles (see ChatPrivateController).
        let delayTime = DispatchTime.now() + Double(Int64(0.2 * Double(NSEC_PER_SEC))) / Double(NSEC_PER_SEC)
        DispatchQueue.main.asyncAfter(deadline: delayTime) { [weak self] in
            guard let tableView = self?.tableView, tableView.numberOfRows(inSection: 0) > 0 else {
                return
            }
            tableView.scrollToRow(at: IndexPath(row: 0, section: 0), at: .top, animated: animated)
        }
    }

    @objc func scrollDownButtonPressed() {
        scrollToNewestMessage()
    }

    // KHANDAQ (#15): file/media cells don't self-size in height (the movable-content cell hierarchy
    // breaks automatic-dimension for them — the row stays at the estimate and the square preview box
    // is squashed to a strip). Give inline image/video bubbles an explicit aspect-ratio height; let
    // everything else keep automatic sizing.
    /// KHANDAQ (#99): day-separator label for the row that begins a calendar day's block (its oldest
    /// message). nil mid-day or while searching. Mirrors ChatPrivateController.daySeparatorString.
    func daySeparatorString(forDisplayIndex row: Int) -> String? {
        guard messageSearchQuery.isEmpty, row >= 0, row < displayableRowCount() else {
            return nil
        }
        let calendar = Calendar.current
        let message = messageEntry(atDisplayIndex: row).message
        // System messages don't carry a ChatMovableDateCell, so never anchor a separator on them.
        if message.groupSystemMessage {
            return nil
        }
        let day = calendar.startOfDay(for: message.date())
        let nextRow = row + 1
        if nextRow < displayableRowCount() {
            let older = messageEntry(atDisplayIndex: nextRow).message
            if calendar.startOfDay(for: older.date()) == day {
                return nil
            }
        }
        return daySeparatorFormatter.string(from: message.date())
    }

    func tableView(_ tableView: UITableView, heightForRowAt indexPath: IndexPath) -> CGFloat {
        let entry = messageEntry(atDisplayIndex: indexPath.row)
        let message = entry.message

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
        if messageSearchQuery.isEmpty,
           let captionMsg = captionMessage(forFileAtStorageIndex: entry.storageIndex),
           let caption = MessageReplyHelper.plainBody(for: captionMsg), !caption.isEmpty {
            // KHANDAQ (#52): height must match the stripped (markup-free) caption text.
            height += ChatGenericFileCell.captionHeight(for: caption, width: box.width)
        }
        // KHANDAQ (#99): media rows have an explicit height; add the separator band on a day boundary.
        if daySeparatorString(forDisplayIndex: indexPath.row) != nil {
            height += ChatMovableDateCell.daySeparatorReservedHeight
        }
        return height
    }

    /// Pixel size of an inline photo/video, or nil if the file isn't displayable media. Falls back to a
    /// direct header read when the UTI/extension is missing (e.g. a received file with no extension), so a
    /// downloaded video on the RECEIVER side gets the same aspect-ratio bubble as the sender's.
    func inlineMediaSize(forFileAt file: String, messageFile: OCTMessageFile) -> CGSize? {
        let uti = inferredFileUTI(for: messageFile)

        // CRITICAL (real-device crash): voice/audio is never inline pixel media — bail before any probe.
        // heightForRowAt runs on the MAIN thread; the old fallback ran AVURLAsset.tracks per voice (.m4a)
        // cell, which blocks/hangs on iOS 26 → watchdog kill in any chat with voice notes.
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
    /// where isCaption is never set, so the caption used to render as a standalone text bubble under the
    /// media — inferred by adjacency: a plain text immediately newer than the file, same sender &
    /// direction, within 5s, that is not a system or shared-location message.
    func isCaption(_ caption: OCTMessageAbstract, forFile file: OCTMessageAbstract) -> Bool {
        if caption.isCaption {
            return true
        }
        guard caption.messageFile == nil,
              !caption.groupSystemMessage,
              let text = caption.messageText?.text, !text.isEmpty,
              caption.isOutgoing() == file.isOutgoing(),
              caption.groupSenderPeerId == file.groupSenderPeerId,
              LocationMessage.parse(GroupMentionHelper.parse(text).bodyText) == nil else {
            return false
        }
        let delta = caption.dateInterval - file.dateInterval
        return delta >= 0 && delta <= 5.0
    }

    /// KHANDAQ (#122): true only if the file message is actually rendered inline (downloaded/ready,
    /// recognized media, within the inline size limit) — mirrors the heightForRowAt gate. A caption is
    /// merged into its file ONLY when this holds; otherwise it would vanish behind a failed/generic-file
    /// bubble.
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
        let captionIndex = storageIndex - 1
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

    /// True if the message at `storageIndex` is a caption merged into its file (no own row).
    func isMergedCaptionRow(storageIndex: Int) -> Bool {
        guard storageIndex >= 0, storageIndex < messages.count else {
            return false
        }
        let message = messages[storageIndex]
        guard message.messageText?.text?.isEmpty == false else {
            return false
        }
        let fileIndex = storageIndex + 1
        guard fileIndex < messages.count else {
            return false
        }
        let file = messages[fileIndex]
        // KHANDAQ (#122): merge only if the file is actually shown inline; otherwise show the caption as
        // a normal message instead of hiding it behind a failed/generic-file bubble.
        return file.messageFile != nil && isCaption(message, forFile: file) && isFileDisplayedInline(file)
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
        // KHANDAQ (#iOS longpress): on iOS 13+ the unified Telegram popup (reaction bar + action card in
        // ONE presentation, via the long-press recognizer) is the only menu — suppress the legacy
        // horizontal UIMenuController here so reactions never surface separately from the actions.
        if #available(iOS 13.0, *) {
            return false
        }
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

    // KHANDAQ (#G5 reaction-row): groups use the custom unified popup (longPressOnGroupTable →
    // presentGroupContextPopup: reaction bar + action list together), same as 1:1. The system menu
    // can't host a reaction row, so return nil here to avoid a double menu on the same long-press.
    @available(iOS 13.0, *)
    func tableView(_ tableView: UITableView, contextMenuConfigurationForRowAt indexPath: IndexPath, point: CGPoint) -> UIContextMenuConfiguration? {
        return nil
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

        // KHANDAQ (#209): swipe-to-reply on group media/voice bubbles (delegate was text-only).
        cell.replySwipeDelegate = self

        if incoming, let peerName = peerName(for: message), !model.isVoiceMessage {
            model.fileName = "\(peerName)\n\(messageFile.fileName ?? "")"
        }

        model.dateString = dateText
        model.dateSeparator = daySeparatorString(forDisplayIndex: indexPath.row)

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
        model.reactionsDisplay = ChatReactionsFormat.display(from: message.reactionsJSON)

        // Telegram-style merged caption (the text message sent right after this file).
        if messageSearchQuery.isEmpty {
            let idx = messages.indexOfObject(message)
            let captionMsg = (idx >= 0 && idx < messages.count) ? captionMessage(forFileAtStorageIndex: idx) : nil
            // KHANDAQ (#52): strip reply/mention wire markup so a reply sent as a media caption shows
            // its clean body, not the raw "[KQ|…][KQ/end]" header.
            model.caption = captionMsg.flatMap { MessageReplyHelper.plainBody(for: $0) }
        }
        else {
            model.caption = nil
        }

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
            // KHANDAQ (Android parity): scrub the waveform
            model.voiceSeekHandle = { fraction in
                guard let path = message.messageFile?.filePath(),
                      message.messageFile?.fileType == .ready else {
                    return
                }
                ChatVoiceMessagePlayer.shared.seek(
                    messageId: message.uniqueIdentifier, filePath: path, fraction: fraction)
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

        // KHANDAQ (Figma / #204-C): media gallery for image/video; non-media (PDF, doc…) → QuickLook.
        if let start = qlDataSource.galleryStartIndex(forMessageIndex: index) {
            let galleryItems = qlDataSource.galleryItems(myName: "")
            if !galleryItems.isEmpty {
                let gallery = MediaGalleryViewController(items: galleryItems, startIndex: start)
                present(gallery, animated: true, completion: nil)
                return
            }
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
                  message.messageFile != nil else {
                continue
            }

            // KHANDAQ (#36): handle-preserving refresh (see refreshVoicePlaybackState).
            fileCell.refreshVoicePlaybackState(theme: theme)
        }
    }

    func peerHeader(for message: OCTMessageAbstract) -> String? {
        guard message.groupSenderPeerId > 0 else {
            // KHANDAQ (audit F-6): a history-synced row whose author isn't in our roster right now has
            // peer id 0, so there is no role to look up — show the name frozen on the row rather than
            // an anonymous bubble.
            if message.groupHistorySync, let frozen = peerName(for: message), !frozen.isEmpty {
                return frozen
            }

            return nil
        }

        let name = peerName(for: message) ?? String(localized: "group_peer_fallback_format", message.groupSenderPeerId)
        let role = submanagerGroups.peerRole(withId: UInt32(message.groupSenderPeerId), in: chat)
        return "\(name) · \(role.localizedName)"
    }

    func peerName(for message: OCTMessageAbstract) -> String? {
        // KHANDAQ (#42): prefer the name FROZEN on the message at receipt (resolved then by the STABLE
        // sender pubkey). The live peerId lookup below is unreliable after an app restart (the group
        // roster has not re-synced yet → generic "Участник N") and whenever the volatile peerId has
        // since been reused by another peer. That made stored messages change sender/name on relaunch.
        if let stored = message.messageText?.groupPeerName, !stored.isEmpty {
            return stored
        }
        // KHANDAQ (#82): FILE messages freeze their sender name separately (OCTMessageFile.groupPeerName).
        if let storedFile = message.messageFile?.groupPeerName, !storedFile.isEmpty {
            return storedFile
        }

        guard message.groupSenderPeerId > 0 else {
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
        // KHANDAQ (#54): opening the group also clears the separate in-group private-message unread
        // badge ("N личных непрочит."), which a volatile peer id otherwise stranded under an old id.
        submanagerGroups.markAllPrivateThreadsRead(for: chat)
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
            self?.handleMessagesChange()
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

        let bannerText: String
        let attempt = submanagerGroups.groupJoinAttempt(for: chat)
        if attempt > 0 || submanagerGroups.isGroupJoinRetryRunning(for: chat) {
            bannerText = String(localized: "group_connecting_banner_format", max(attempt, 1))
        }
        else {
            bannerText = String(localized: "group_disconnected_banner")
        }

        // KHANDAQ (#iOS group-connecting note): a subtle centered rounded chip (same surface as the 1:1
        // offline note / ChatFauxOfflineHeaderView) instead of raw red text that clipped its 2nd line.
        // Dark translucent pill + light 12pt text, measured at a capped width so the long
        // "…отправятся после соединения." wraps instead of being cut off.
        let maxLabelWidth: CGFloat = 290
        let hInset: CGFloat = 12, vInset: CGFloat = 6, sideMargin: CGFloat = 16, topGap: CGFloat = 10

        let container = UIView()
        let chip = UIView()
        chip.backgroundColor = UIColor(white: 0.0, alpha: 0.28)
        chip.layer.cornerRadius = 13
        chip.layer.masksToBounds = true

        let label = UILabel()
        label.font = UIFont.khandaqFontWithSize(12.0, weight: .light)
        label.textColor = UIColor(white: 1.0, alpha: 0.9)
        label.textAlignment = .center
        label.numberOfLines = 0
        label.preferredMaxLayoutWidth = maxLabelWidth
        label.text = bannerText

        container.addSubview(chip)
        chip.addSubview(label)
        label.snp.makeConstraints {
            $0.edges.equalTo(chip).inset(UIEdgeInsets(top: vInset, left: hInset, bottom: vInset, right: hInset))
            $0.width.lessThanOrEqualTo(maxLabelWidth)
        }
        chip.snp.makeConstraints {
            $0.top.equalTo(container).offset(topGap)
            $0.bottom.equalTo(container).offset(-topGap)
            $0.centerX.equalTo(container)
            $0.leading.greaterThanOrEqualTo(container).offset(sideMargin)
            $0.trailing.lessThanOrEqualTo(container).offset(-sideMargin)
        }

        // Size the header from the label measured at the capped width — never clipped.
        let labelSize = label.sizeThatFits(CGSize(width: maxLabelWidth, height: .greatestFiniteMagnitude))
        let height = ceil(labelSize.height) + vInset * 2 + topGap * 2
        container.frame = CGRect(x: 0, y: 0, width: tableView.bounds.width, height: max(height, 36))
        container.layoutIfNeeded()
        // KHANDAQ (#15): the table is vertically flipped, so flip the header back to keep it upright.
        container.transform = tableView.transform
        tableView.tableHeaderView = container
    }

    func maybeLoadImageForCellAtPath(_ cell: UITableViewCell, indexPath: IndexPath) {
        let message = messageEntry(atDisplayIndex: indexPath.row).message

        guard let messageFile = message.messageFile else {
            return
        }

        // KHANDAQ (#15): only show the inline preview once the file is fully on disk. While an
        // incoming transfer is still arriving, decoding the partial file renders a half image (top
        // strip + grey) — keep the clean loading box until it's Ready. Outgoing files are complete.
        guard message.isOutgoing() || messageFile.fileType == .ready else {
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
        // KHANDAQ (#H5): sort by sortTimestamp (group live == arrival, sync == clamped original) for a
        // stable order that matches displayed times. (Group LIVE cross-device order still depends on
        // per-device arrival — no sender wire ts on the NGC live path — but 1:1 + sync are now stable.)
        return submanagerObjects.messages(predicate: predicate).sortedResultsUsingProperty("sortTimestamp", ascending: false)
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
