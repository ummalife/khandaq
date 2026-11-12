// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit
import SnapKit

/// The private messages of a group with one member (E13, "Write privately", "Write to a moderator"),
/// drawn as the conversation with one person: the member's tile and name in the header, a line under
/// it with their role, whether they are online now and who sees these messages, the transcript entries
/// and the composer. NGC private messages carry text only, so the composer has no paperclip and no mic.
/// A private message leaves only while the member is online in the group: the line under the field says
/// so before anything is typed, and the send button waits until they are back instead of failing.
/// An observer reads what a moderator writes to them here but cannot answer: toxcore refuses an
/// observer's private messages (gc_send_private_message returns -5), so the field is off and the line
/// under it says to ask a moderator outside the group.
class GroupPeerPrivateController: PortraitChatController {
    let chat: OCTChat
    let peer: OCTGroupPeer

    fileprivate let theme: Theme
    fileprivate weak var submanagerGroups: OCTSubmanagerGroups!
    fileprivate weak var submanagerObjects: OCTSubmanagerObjects!

    /// The member's key in this group: their peer number can change when they reconnect, the key does
    /// not, so the thread and the recipient follow the key.
    fileprivate let peerKey: String
    fileprivate var peerId: UInt32
    fileprivate var member: AirGroupMember?
    /// Your right to write in the group now, read again with the members: an observer cannot write here.
    fileprivate var groupRight: AirGroupComposeRight = .write

    fileprivate var tableView: UITableView!
    fileprivate var chatInputView: ChatInputView!
    fileprivate var titleView: ChatPrivateTitleView!
    fileprivate let band = AirGroupPrivateBandView()
    /// Who sees these messages, at the start of the thread: it scrolls away with it, so a large text size
    /// keeps room for the messages.
    fileprivate let intro = AirGroupPrivateIntroView()
    fileprivate var observers: [NSObjectProtocol] = []
    /// The words of a send that failed, shown under the field until the text changes.
    fileprivate var sendFailure: String?
    /// The height of the thread at the last layout, and whether its end was in sight before the next:
    /// the keyboard or a taller field shortens the thread, and the newest message stays in sight.
    fileprivate var lastTableHeight: CGFloat = 0
    fileprivate var endWasInSight = true
    /// The height each message's row had when it was last on screen, at `rowHeightsWidth` and the text
    /// size of the time: a reload (a message sent or received) lays the thread out again, and on the
    /// estimate alone the tall rows of the largest text sizes leave the jump to the end short of it.
    fileprivate var rowHeights: [String: CGFloat] = [:]
    fileprivate var rowHeightsWidth: CGFloat = 0

    fileprivate let messages: Results<OCTMessageAbstract>
    fileprivate var messagesToken: RLMNotificationToken?
    fileprivate let timeFormatter = DateFormatter(type: .time)

    init(theme: Theme,
         chat: OCTChat,
         peer: OCTGroupPeer,
         submanagerGroups: OCTSubmanagerGroups,
         submanagerObjects: OCTSubmanagerObjects) {
        self.theme = theme
        self.chat = chat
        self.peer = peer
        self.submanagerGroups = submanagerGroups
        self.submanagerObjects = submanagerObjects
        self.peerKey = (peer.peerPublicKeyHex ?? "").uppercased()
        self.peerId = UInt32(bitPattern: peer.peerId)

        // KHANDAQ (#55): thread by the peer's STABLE pubkey when known, so volatile peer-id reuse can
        // never surface another person's private messages here. Exclusive fallback (pubkey OR peerId,
        // never both): lowercase to match the stored value (Realm == is case-sensitive). Legacy rows
        // with no stored pubkey resolve via the peerId branch.
        let predicate: NSPredicate
        if let pubkey = peer.peerPublicKeyHex, !pubkey.isEmpty {
            predicate = NSPredicate(format: "chatUniqueIdentifier == %@ AND groupPrivateMessage == YES AND groupPrivatePeerPubkey == %@",
                                    chat.uniqueIdentifier, pubkey.lowercased())
        }
        else {
            predicate = NSPredicate(format: "chatUniqueIdentifier == %@ AND groupPrivateMessage == YES AND groupPrivatePeerId == %d",
                                    chat.uniqueIdentifier, peer.peerId)
        }
        self.messages = submanagerObjects.messages(predicate: predicate)
            .sortedResultsUsingProperty("dateInterval", ascending: true)

        super.init()

        title = peer.peerName ?? String(localized: "group_peer_fallback_format", peer.peerId)
        edgesForExtendedLayout = UIRectEdge()
        hidesBottomBarWhenPushed = true
    }

    required convenience init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    deinit {
        messagesToken?.invalidate()
        observers.forEach { NotificationCenter.default.removeObserver($0) }
    }

    override func loadView() {
        loadViewWithBackgroundColor(KQColor.bg)
        view.accessibilityIdentifier = "air.group.private"

        titleView = ChatPrivateTitleView()
        titleView.opensDossier = false
        titleView.onSizeChanged = { [weak self] in
            self?.installTitleItem()
        }
        // The name is a bar item next to the back button; the centre of the bar stays empty.
        navigationItem.titleView = UIView()

        view.addSubview(band)

        tableView = UITableView(frame: .zero, style: .plain)
        tableView.estimatedRowHeight = 44
        tableView.rowHeight = UITableViewAutomaticDimension
        tableView.separatorStyle = .none
        tableView.backgroundColor = KQColor.bg
        tableView.keyboardDismissMode = .interactive
        tableView.dataSource = self
        tableView.delegate = self
        tableView.accessibilityIdentifier = "air.group.private.log"
        tableView.register(AirEntryTextCell.self, forCellReuseIdentifier: AirEntryTextCell.staticReuseIdentifier)
        tableView.tableHeaderView = intro
        view.addSubview(tableView)

        chatInputView = ChatInputView(theme: theme)
        chatInputView.useAirStyle()
        chatInputView.cameraButtonEnabled = false
        chatInputView.voiceButtonEnabled = false
        // KHANDAQ (#113): NGC private messages are text-only: no paperclip, so it isn't a dead button.
        chatInputView.attachmentButtonHidden = true
        chatInputView.delegate = self
        chatInputView.onTextChanged = { [weak self] _ in
            self?.sendFailure = nil
            self?.refreshComposeState(animated: true)
        }
        view.addSubview(chatInputView)

        // Under the field, down to the edge of the screen behind the home indicator.
        let filler = UIView()
        filler.backgroundColor = KQColor.surface
        filler.isUserInteractionEnabled = false
        view.insertSubview(filler, belowSubview: chatInputView)

        band.snp.makeConstraints {
            $0.top.leading.trailing.equalTo(view)
        }
        tableView.snp.makeConstraints {
            $0.top.equalTo(band.snp.bottom)
            $0.leading.trailing.equalTo(view)
            $0.bottom.equalTo(chatInputView.snp.top)
        }
        chatInputView.snp.makeConstraints {
            $0.leading.trailing.equalTo(view)
            $0.bottom.equalTo(view.keyboardLayoutGuide.snp.top)
        }
        filler.snp.makeConstraints {
            $0.leading.trailing.bottom.equalTo(view)
            $0.top.equalTo(chatInputView.snp.bottom)
        }
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        // The screen pushed from here shows only the chevron, as on the other Air screens.
        navigationItem.backButtonDisplayMode = .minimal

        let center = NotificationCenter.default
        let chatId = chat.uniqueIdentifier
        observers.append(center.addObserver(forName: .octGroupPeersUpdated, object: nil, queue: .main) { [weak self] note in
            if let id = note.userInfo?[kOCTGroupPeersUpdatedChatUniqueIdentifierKey] as? String, id != chatId {
                return
            }
            self?.refreshMember(animated: true)
        })
        observers.append(center.addObserver(forName: .octGroupConnectionStatusChange, object: nil, queue: .main) { [weak self] note in
            if let id = note.userInfo?[kOCTGroupConnectionStatusChangeChatUniqueIdentifierKey] as? String, id != chatId {
                return
            }
            self?.refreshMember(animated: true)
        })
        observers.append(center.addObserver(forName: AirSelfNetwork.didChange, object: nil, queue: .main) { [weak self] _ in
            self?.refreshComposeState(animated: true)
        })
        observers.append(center.addObserver(forName: .UIContentSizeCategoryDidChange, object: nil, queue: .main) { [weak self] _ in
            // The time column and the fonts of the entries follow the text size.
            self?.rowHeights.removeAll()
            self?.tableView?.reloadData()
            self?.updateTitleWidth()
            self?.sizeIntro()
        })

        messagesToken = messages.addNotificationBlock { [weak self] _ in
            self?.markPrivateThreadRead()
            self?.tableView?.reloadData()
            self?.scrollToBottom(animated: false)
        }

        installTitleItem()
        refreshMember(animated: false)
    }

    override func viewWillLayoutSubviews() {
        super.viewWillLayoutSubviews()
        if let tableView = tableView {
            let visibleEnd = tableView.contentOffset.y + tableView.bounds.height - tableView.adjustedContentInset.bottom
            endWasInSight = visibleEnd >= tableView.contentSize.height - KQSpace.x8
        }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        updateTitleWidth()
        if let tableView = tableView, tableView.bounds.width != rowHeightsWidth {
            rowHeightsWidth = tableView.bounds.width
            rowHeights.removeAll()
        }
        // A header measured again moves the messages under it as much as a shorter thread does.
        let introChanged = sizeIntro()
        if let tableView = tableView, tableView.bounds.height != lastTableHeight || introChanged {
            lastTableHeight = tableView.bounds.height
            // Not while a finger moves the thread, as when it pulls the keyboard down.
            if endWasInSight, !tableView.isDragging {
                scrollToBottom(animated: false)
            }
        }
    }

    /// A table keeps the frame its header is given: measured at the width of the table, again when the
    /// width, the text size or the words change. True when the header took a new frame.
    @discardableResult
    func sizeIntro() -> Bool {
        guard let tableView = tableView, tableView.bounds.width > 0 else {
            return false
        }
        let width = tableView.bounds.width
        let size = intro.systemLayoutSizeFitting(CGSize(width: width, height: UILayoutFittingCompressedSize.height),
                                                 withHorizontalFittingPriority: .required,
                                                 verticalFittingPriority: .fittingSizeLevel)
        let height = ceil(size.height)
        guard intro.frame.width != width || intro.frame.height != height else {
            return false
        }
        intro.frame = CGRect(x: 0, y: 0, width: width, height: height)
        tableView.tableHeaderView = intro
        return true
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        markPrivateThreadRead()
        scrollToBottom(animated: false)
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        markPrivateThreadRead()
    }

    func markPrivateThreadRead() {
        guard !chat.isInvalidated else {
            return
        }
        submanagerGroups.setPrivateLastReadDateInterval(Date().timeIntervalSince1970,
                                                        peerId: peerId,
                                                        for: chat)
    }

    func scrollToBottom(animated: Bool) {
        guard messages.count > 0, tableView.numberOfRows(inSection: 0) == messages.count else {
            return
        }

        // Rows are measured as they come into sight, so a jump made on the estimated heights stops short
        // of the end when the rows are tall (the largest text sizes): the rows the first jump brings into
        // sight are measured, and a second jump lands on the end.
        let indexPath = IndexPath(row: messages.count - 1, section: 0)
        tableView.layoutIfNeeded()
        tableView.scrollToRow(at: indexPath, at: .bottom, animated: false)
        tableView.layoutIfNeeded()
        tableView.scrollToRow(at: indexPath, at: .bottom, animated: animated)
    }
}

// MARK: - The member

private extension GroupPeerPrivateController {
    /// The tile and the name, left-aligned next to the back button, as in the conversation with one
    /// person.
    func installTitleItem() {
        navigationItem.leftItemsSupplementBackButton = true
        navigationItem.leftBarButtonItems = [UIBarButtonItem(customView: titleView)]
    }

    /// The name runs as wide as the bar leaves it next to the back button.
    func updateTitleWidth() {
        guard view.bounds.width > 0 else {
            return
        }
        let slot = KQSize.targetIos + KQSpace.x8
        let titleChrome = KQSize.tileDesk + KQSpace.x8 + 2 * KQSpace.x4
        titleView.maximumNameWidth = max(2 * KQSize.tilePhone, floor(view.bounds.width - 2 * slot - titleChrome - KQSpace.x16))
    }

    /// The member as the group lists them now: by key, since the peer number may have changed.
    func refreshMember(animated: Bool) {
        guard isViewLoaded, !chat.isInvalidated else {
            return
        }
        let members = AirGroupMembers.load(chat: chat, submanagerGroups: submanagerGroups)
        let found = members.first(where: { !$0.isSelf && !peerKey.isEmpty && $0.publicKey == peerKey })
            ?? members.first(where: { !$0.isSelf && $0.peerId == peerId })
        if let found = found {
            member = found
            peerId = found.peerId
        }

        let name = member?.name ?? (title ?? "")
        title = name
        let toneKey = member?.toneKey ?? (peerKey.isEmpty ? name : peerKey)
        titleView.configure(name: name,
                            style: .person(initials: AirInitials.from(name), tone: AirTone.index(for: toneKey)),
                            photo: nil)

        let groupName = ChatListContent.groupTitle(for: chat)
        let presence = member.map { AirGroupRoster.statusLine(for: $0, now: Date().timeIntervalSince1970) }
            ?? String(localized: "air_group_member_offline")
        band.configure(online: isMemberOnline, presence: presence)
        intro.configure(String(localized: "air_group_private_note_format",
                               groupName.isEmpty ? String(localized: "group_chat_default_title") : groupName,
                               AirTranscript.shortName(name)))
        sizeIntro()
        titleView.spokenPresence = presence

        // A change of your role comes with the members: a moderator may make you an observer while this
        // screen is open, or give you the right to write back.
        var voiceError: NSError?
        let voice = submanagerGroups.groupVoiceState(for: chat, error: &voiceError)
        groupRight = AirGroupComposeRight.make(selfRole: submanagerGroups.selfRole(in: chat), voiceState: voice)
        chatInputView.fieldEnabled = groupRight.canWritePrivately
        refreshComposeState(animated: animated)
    }

    var isMemberOnline: Bool {
        return (member?.isOnline ?? false) && submanagerGroups.isGroupConnected(for: chat)
    }

    /// The line under the field: who gets the message now, or why it would not leave now; for an
    /// observer, that nothing can be written here.
    func refreshComposeState(animated: Bool) {
        guard let hintView = chatInputView?.hintView else {
            return
        }
        let name = AirTranscript.shortName(member?.name ?? (title ?? ""))
        let hint: AirComposeHint
        if let refusal = groupRight.privateRefusalText {
            hint = AirComposeHint(text: refusal, dot: .none)
        }
        else if let failure = sendFailure {
            hint = AirComposeHint(text: failure, dot: .none)
        }
        else if let ownNode = AirComposeHint.ownNode(AirSelfNetwork.state) {
            hint = ownNode
        }
        else if isMemberOnline {
            hint = AirComposeHint(text: String(localized: "air_compose_hint_live_format", name), dot: .live)
        }
        else {
            hint = AirComposeHint(text: String(localized: "air_group_private_hint_away_format", name), dot: .none)
        }
        hintView.setHint(hint, animated: animated)

        let text = chatInputView.text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, groupRight.canWritePrivately else {
            hintView.setCounter(remaining: nil)
            chatInputView.sendBlocked = !groupRight.canWritePrivately
            return
        }
        // An NGC private message carries the whole text in one packet, as a group message does.
        let remaining = AirGroupComposeLimit.remaining(forBytes: text.utf8.count)
        hintView.setCounter(remaining: remaining)
        // Over the limit, or nobody to take it now: the button waits, the line above says why.
        chatInputView.sendBlocked = (remaining ?? 0) < 0 || !isMemberOnline
    }
}

// MARK: - Transcript

extension GroupPeerPrivateController: UITableViewDataSource {
    func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        return messages.count
    }

    func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let message = messages[indexPath.row]
        let outgoing = message.isOutgoing()
        let model = AirEntryTextCellModel()
        model.message = MessageReplyHelper.normalizeForwardedHeader(message.messageText?.text ?? "")
        model.dateString = timeFormatter.string(from: message.date())
        model.timeText = model.dateString
        model.isOutgoing = outgoing
        // toxcore took it, nothing confirms it: one check. A private message that could not leave is
        // never stored, so there is no waiting mark.
        model.status = outgoing ? .sent : .none
        model.transcriptMarks = true
        model.dateSeparator = daySeparator(forRow: indexPath.row)

        let name = member?.name ?? (title ?? "")
        let fullName = outgoing ? String(localized: "air_you") : name
        model.authorColor = outgoing ? KQColor.ink2 : AirTone.color(at: AirTone.index(for: member?.toneKey ?? name))
        model.spokenAuthor = fullName
        let previous = indexPath.row > 0 ? runKey(forRow: indexPath.row - 1) : nil
        if AirTranscript.startsRun(runKey(forRow: indexPath.row), after: previous) || model.dateSeparator != nil {
            model.authorText = outgoing ? fullName : AirTranscript.shortName(fullName)
        }

        let cell = tableView.dequeueReusableCell(withIdentifier: AirEntryTextCell.staticReuseIdentifier, for: indexPath) as! AirEntryTextCell
        cell.setupWithTheme(theme, model: model)
        return cell
    }
}

extension GroupPeerPrivateController: UITableViewDelegate {
    // A row's look depends on the message and the one before it, so a message added at the end leaves
    // the heights of the rows already seen as they were.
    func tableView(_ tableView: UITableView, willDisplay cell: UITableViewCell, forRowAt indexPath: IndexPath) {
        guard indexPath.row < messages.count else {
            return
        }
        rowHeights[messages[indexPath.row].uniqueIdentifier] = cell.bounds.height
    }

    func tableView(_ tableView: UITableView, estimatedHeightForRowAt indexPath: IndexPath) -> CGFloat {
        guard indexPath.row < messages.count, let height = rowHeights[messages[indexPath.row].uniqueIdentifier] else {
            return tableView.estimatedRowHeight
        }
        return height
    }
}

private extension GroupPeerPrivateController {
    /// "Today", "Yesterday" or the date over the first message of each day.
    func daySeparator(forRow row: Int) -> String? {
        let date = messages[row].date()
        let calendar = Calendar.current
        if row > 0, calendar.isDate(messages[row - 1].date(), inSameDayAs: date) {
            return nil
        }
        return AirDayMark.text(for: date, locale: AppLanguage.locale, calendar: calendar)
    }

    func runKey(forRow row: Int) -> AirRunKey {
        let message = messages[row]
        return AirRunKey(author: message.isOutgoing() ? "" : "peer", date: message.date(), isText: true)
    }
}

// MARK: - Composer

extension GroupPeerPrivateController: ChatInputViewDelegate {
    func chatInputViewSendButtonPressed(_ view: ChatInputView) {
        let text = view.text.trimmingCharacters(in: .whitespacesAndNewlines)
        // An observer's private message is never tried: toxcore would refuse it.
        guard !text.isEmpty, groupRight.canWritePrivately, isMemberOnline else {
            refreshComposeState(animated: true)
            return
        }

        submanagerGroups.sendPrivateMessage(text,
                                            toPeerId: peerId,
                                            in: chat,
                                            successBlock: { [weak self] _ in
                                                // Realm notification reloads the table.
                                                self?.chatInputView.text = ""
                                            },
                                            failureBlock: { [weak self] error in
                                                // The text stays in the field; the line under it says
                                                // what failed (E21: where the operation is).
                                                guard let self = self else {
                                                    return
                                                }
                                                let words = errorAlert(for: .sendGroupPrivateMessage, error: error as NSError?)
                                                self.sendFailure = words?.title ?? String(localized: "group_private_message_failed")
                                                self.refreshComposeState(animated: true)
                                                if let failure = self.sendFailure {
                                                    UIAccessibilityPostNotification(UIAccessibilityAnnouncementNotification, failure)
                                                }
                                            })
    }

    func chatInputViewCameraButtonPressed(_ view: ChatInputView, cameraView: UIView) {
    }

    func chatInputViewTextDidChange(_ view: ChatInputView) {
    }
}

// MARK: - The line under the header

/// Under the header of a member's private messages: their role and whether they are online, with the
/// dot of a person online.
final class AirGroupPrivateBandView: UIView {
    private let dot = UIView()
    private let presenceLabel = AirKit.label(.caption, color: KQColor.ink2, lines: 0)
    private let rule = UIView()

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = KQColor.bg
        isAccessibilityElement = true
        accessibilityTraits = UIAccessibilityTraitStaticText
        accessibilityIdentifier = "air.group.private.band"
        dot.layer.cornerRadius = KQSize.dot / 2
        dot.backgroundColor = KQColor.live
        rule.backgroundColor = KQColor.line

        let presenceRow = UIStackView(arrangedSubviews: [dot, presenceLabel])
        presenceRow.axis = .horizontal
        presenceRow.alignment = .center
        presenceRow.spacing = KQSpace.x6
        addSubview(presenceRow)
        addSubview(rule)
        dot.snp.makeConstraints {
            $0.size.equalTo(KQSize.dot)
        }
        presenceRow.snp.makeConstraints {
            $0.top.equalToSuperview().offset(KQSpace.x8)
            $0.bottom.equalToSuperview().offset(-KQSpace.x8)
            $0.leading.equalTo(safeAreaLayoutGuide).offset(KQSpace.x16)
            $0.trailing.equalTo(safeAreaLayoutGuide).offset(-KQSpace.x16)
        }
        rule.snp.makeConstraints {
            $0.leading.trailing.bottom.equalToSuperview()
            $0.height.equalTo(KQStroke.hairline)
        }
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func configure(online: Bool, presence: String) {
        dot.isHidden = !online
        presenceLabel.text = presence
        accessibilityLabel = presence
    }
}

/// The start of a member's private messages: who sees them, in the quiet caption of a note.
final class AirGroupPrivateIntroView: UIView {
    private let label = AirKit.label(.caption, color: KQColor.ink3, lines: 0)

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .clear
        accessibilityIdentifier = "air.group.private.intro"
        label.textAlignment = .center
        addSubview(label)
        label.snp.makeConstraints {
            $0.top.equalToSuperview().offset(KQSpace.x16)
            $0.bottom.equalToSuperview().offset(-KQSpace.x8)
            $0.leading.equalTo(safeAreaLayoutGuide).offset(KQSpace.x24)
            $0.trailing.equalTo(safeAreaLayoutGuide).offset(-KQSpace.x24)
        }
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func configure(_ text: String) {
        label.text = text
    }
}
