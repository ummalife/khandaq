// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit
import SnapKit

protocol ChatMovableDateCellDelegate: class {
    func chatMovableDateCellCopyPressed(_ cell: ChatMovableDateCell)
    func chatMovableDateCellDeletePressed(_ cell: ChatMovableDateCell)
    func chatMovableDateCellMorePressed(_ cell: ChatMovableDateCell)
    func chatMovableDateCellReplyPressed(_ cell: ChatMovableDateCell)
    func chatMovableDateCellForwardPressed(_ cell: ChatMovableDateCell)
    func chatMovableDateCellReactPressed(_ cell: ChatMovableDateCell)
}

extension ChatMovableDateCellDelegate {
    func chatMovableDateCellForwardPressed(_ cell: ChatMovableDateCell) {}
    // KHANDAQ (#192): default no-op so delegates without reactions keep compiling
    func chatMovableDateCellReactPressed(_ cell: ChatMovableDateCell) {}
}

class ChatMovableDateCell: BaseCell {
    private static var __once: () = {
            var items = UIMenuController.shared.menuItems ?? [UIMenuItem]()
            items += [
                // KHANDAQ (#192): React first among custom items so it lands on the menu's first page
                UIMenuItem(title: String(localized: "chat_react_action"), action: #selector(reactAction)),
                UIMenuItem(title: String(localized: "chat_reply_action"), action: #selector(replyAction)),
                UIMenuItem(title: String(localized: "chat_forward_action"), action: #selector(forwardAction)),
                UIMenuItem(title: String(localized: "chat_more_menu_item"), action: #selector(moreAction))
            ]

            UIMenuController.shared.menuItems = items
        }()
    weak var delegate: ChatMovableDateCellDelegate?
    weak var replySwipeDelegate: ChatReplySwipeDelegate?

    var canBeCopied = false
    // KHANDAQ (#192): gates the "Реакция" menu item; text cells set it in setupWithTheme
    var canBeReacted = false

    /**
        Superview for content that should move while panning table to the left.
     */
    var movableContentView: UIView!

    var movableOffset: CGFloat = 0 {
        didSet {
            var offset = movableOffset

            if (UserDefaultsManager().DateonmessageMode == true) {
                offset = 39
            }

            if #available(iOS 9.0, *) {
                if UIView.userInterfaceLayoutDirection(for: self.semanticContentAttribute) == .rightToLeft {
                    offset = -offset
                }
            }

            if offset > 0.0 {
                offset = 0.0
            }

            let minOffset = -dateLabel.frame.size.width - 5.0

            if offset < minOffset {
                offset = minOffset
            }

            movableContentViewLeftConstraint.update(offset: offset)
            layoutIfNeeded()
        }
    }

    fileprivate var movableContentViewLeftConstraint: Constraint!
    fileprivate var dateLabel: UILabel!

    // KHANDAQ (#99): Telegram-style day separator pinned at the cell's coordinate-top (= visual top,
    // since the table's y-flip is cancelled by the per-cell y-flip — content is upright). Added to
    // contentView (NOT movableContentView) so it stays put during the swipe-to-reveal-time pan. When
    // the model carries no dateSeparator, the pill is hidden and movableContentView fills the cell as
    // before (top offset 0) — zero layout impact on the vast majority of rows.
    static let daySeparatorReservedHeight: CGFloat = 34.0
    // KHANDAQ (Figma): reserved band height for the "Непрочитанные сообщения" divider.
    static let unreadSeparatorReservedHeight: CGFloat = 32.0
    fileprivate var movableContentViewTopConstraint: Constraint!
    fileprivate var daySeparatorContainer: UIView!
    fileprivate var daySeparatorLabel: UILabel!
    fileprivate var unreadSeparatorContainer: UIView!
    fileprivate var unreadSeparatorLabel: UILabel!
    fileprivate var unreadSeparatorTopConstraint: Constraint!

    fileprivate var isShowingMenu: Bool = false
    fileprivate static var setupOnceToken: Int = 0

    override func setupWithTheme(_ theme: Theme, model: BaseCellModel) {
        super.setupWithTheme(theme, model: model)

        guard let movableModel = model as? ChatMovableDateCellModel else {
            assert(false, "Wrong model \(model) passed to cell \(self)")
            return
        }

        _ = ChatMovableDateCell.__once

        // KHANDAQ design (Figma): chat cells are transparent so the pale-green chat background
        // (tableView background) shows through — otherwise the opaque white cell fill covers it and
        // the white incoming bubble becomes invisible. On iOS 14+ the cell uses a background
        // configuration that overrides backgroundColor, so clear that too.
        backgroundColor = .clear
        contentView.backgroundColor = .clear
        if #available(iOS 14.0, *) {
            automaticallyUpdatesBackgroundConfiguration = false
            backgroundConfiguration = UIBackgroundConfiguration.clear()
        }

        dateLabel.text = movableModel.dateString
        dateLabel.numberOfLines = 0 // --> multiline label
        dateLabel.textColor = theme.colorForType(.ChatListCellMessage)

        // KHANDAQ (#99): show/hide the day separator and reserve top space only when present. The
        // reserved offset is reset on every reuse, so a recycled separator cell lays out cleanly.
        // KHANDAQ (Figma): the "Непрочитанные сообщения" band stacks BELOW the day pill when both
        // land on the same row; offsets are additive and reset on reuse.
        let dayVisible: Bool
        if let separator = movableModel.dateSeparator, !separator.isEmpty {
            daySeparatorLabel.text = separator
            daySeparatorContainer.isHidden = false
            daySeparatorContainer.backgroundColor = theme.colorForType(.ChatIncomingBubble)
            daySeparatorLabel.textColor = theme.colorForType(.ChatListCellMessage)
            dayVisible = true
        } else {
            daySeparatorContainer.isHidden = true
            dayVisible = false
        }

        let unreadVisible = movableModel.unreadSeparator
        if unreadVisible {
            unreadSeparatorLabel.text = String(localized: "unread_messages_separator")
            unreadSeparatorContainer.isHidden = false
            unreadSeparatorContainer.backgroundColor = theme.colorForType(.ChatIncomingBubble).withAlphaComponent(0.92)
            unreadSeparatorLabel.textColor = theme.colorForType(.ChatListCellMessage)
        } else {
            unreadSeparatorContainer.isHidden = true
        }

        let dayOffset = dayVisible ? ChatMovableDateCell.daySeparatorReservedHeight : 0.0
        unreadSeparatorTopConstraint.update(offset: dayOffset + 3.0)
        movableContentViewTopConstraint.update(offset: dayOffset + (unreadVisible ? ChatMovableDateCell.unreadSeparatorReservedHeight : 0.0))
    }

    override func createViews() {
        super.createViews()

        movableContentView = UIView()
        movableContentView.backgroundColor = .clear
        contentView.addSubview(movableContentView)

        dateLabel = UILabel()
        dateLabel.font = UIFont.khandaqFontWithSize(11.0, weight: .medium)
        movableContentView.addSubview(dateLabel)

        // KHANDAQ (#99): day separator pill (hidden unless the model sets dateSeparator).
        daySeparatorContainer = UIView()
        daySeparatorContainer.layer.cornerRadius = 13.0
        daySeparatorContainer.layer.masksToBounds = true
        daySeparatorContainer.isUserInteractionEnabled = false
        daySeparatorContainer.isHidden = true
        contentView.addSubview(daySeparatorContainer)

        daySeparatorLabel = UILabel()
        daySeparatorLabel.font = UIFont.systemFont(ofSize: 12.0, weight: .semibold)
        daySeparatorLabel.textAlignment = .center
        daySeparatorContainer.addSubview(daySeparatorLabel)

        // KHANDAQ (Figma): full-width "Непрочитанные сообщения" band (hidden unless the model sets it).
        unreadSeparatorContainer = UIView()
        unreadSeparatorContainer.isUserInteractionEnabled = false
        unreadSeparatorContainer.isHidden = true
        contentView.addSubview(unreadSeparatorContainer)

        unreadSeparatorLabel = UILabel()
        unreadSeparatorLabel.font = UIFont.systemFont(ofSize: 12.0, weight: .semibold)
        unreadSeparatorLabel.textAlignment = .center
        unreadSeparatorContainer.addSubview(unreadSeparatorLabel)

        let swipeReply = UISwipeGestureRecognizer(target: self, action: #selector(handleReplySwipe))
        swipeReply.direction = .right
        movableContentView.addGestureRecognizer(swipeReply)

        // Using empty view for multiple selection background.
        multipleSelectionBackgroundView = UIView()
    }

    override func installConstraints() {
        super.installConstraints()

        movableContentView.snp.makeConstraints {
            // KHANDAQ (#99): split the old `size.equalTo(contentView)` into width + bottom so the TOP can
            // be pushed down by the day-separator's reserved height (0 by default → identical layout).
            movableContentViewTopConstraint = $0.top.equalTo(contentView).constraint
            if (UserDefaultsManager().DateonmessageMode == true) {
                movableContentViewLeftConstraint = $0.leading.equalTo(contentView).constraint.update(offset: -39)
            } else {
                movableContentViewLeftConstraint = $0.leading.equalTo(contentView).constraint
            }
            $0.width.equalTo(contentView)
            $0.bottom.equalTo(contentView)
        }

        dateLabel.snp.makeConstraints {
            $0.centerY.equalTo(movableContentView)
            $0.leading.equalTo(movableContentView.snp.trailing)
        }

        // Pill centred at the coordinate-top (visual top) of the cell, within the reserved band.
        daySeparatorContainer.snp.makeConstraints {
            $0.top.equalTo(contentView).offset(4.0)
            $0.centerX.equalTo(contentView)
            $0.height.equalTo(26.0)
        }
        daySeparatorLabel.snp.makeConstraints {
            $0.top.bottom.equalTo(daySeparatorContainer)
            $0.leading.equalTo(daySeparatorContainer).offset(12.0)
            $0.trailing.equalTo(daySeparatorContainer).offset(-12.0)
        }

        // Full-width band sits under the day pill's reserved area (offset updated in setup).
        unreadSeparatorContainer.snp.makeConstraints {
            unreadSeparatorTopConstraint = $0.top.equalTo(contentView).offset(3.0).constraint
            $0.leading.trailing.equalTo(contentView)
            $0.height.equalTo(26.0)
        }
        unreadSeparatorLabel.snp.makeConstraints {
            $0.edges.equalTo(unreadSeparatorContainer)
        }
    }

    override func setSelected(_ selected: Bool, animated: Bool) {
        if !isEditing {
            // don't call super in case of editing to avoid background change
            return
        }

        super.setSelected(selected, animated: animated)
    }
}

// Accessibility
extension ChatMovableDateCell {
    override var isAccessibilityElement: Bool {
        get {
            return true
        }
        set {}
    }

    override var accessibilityValue: String? {
        get {
            return dateLabel.text!
        }
        set {}
    }
}

extension ChatMovableDateCell: ChatEditable {
    // Override in subclass to enable menu
    @objc func shouldShowMenu() -> Bool {
        return false
    }

    // Override in subclass to enable menu
    @objc func menuTargetRect() -> CGRect {
        return CGRect.zero
    }

    @objc func willShowMenu() {
        isShowingMenu = true
    }

    @objc func willHideMenu() {
        isShowingMenu = false
    }
}

// Methods to make UIMenuController work.
extension ChatMovableDateCell {
    func isMenuActionSupportedByCell(_ action: Selector) -> Bool {
        switch action {
            case #selector(copy(_:)):
                return canBeCopied
            case #selector(delete(_:)):
                return true
            case #selector(replyAction):
                return true
            case #selector(reactAction):
                return canBeReacted
            case #selector(forwardAction):
                return true
            case #selector(moreAction):
                return true
            default:
                return false
        }
    }

    override func copy(_ sender: Any?) {
        delegate?.chatMovableDateCellCopyPressed(self)
    }

    override func delete(_ sender: Any?) {
        delegate?.chatMovableDateCellDeletePressed(self)
    }

    @objc func moreAction() {
        delegate?.chatMovableDateCellMorePressed(self)
    }

    @objc func replyAction() {
        delegate?.chatMovableDateCellReplyPressed(self)
    }

    @objc func reactAction() {
        delegate?.chatMovableDateCellReactPressed(self)
    }

    @objc func forwardAction() {
        delegate?.chatMovableDateCellForwardPressed(self)
    }

    @objc func handleReplySwipe() {
        replySwipeDelegate?.chatCellDidRequestReply(self)
    }
}

// KHANDAQ: forward a message to any chat (friend or group). Resolves the full OCTManager via the app
// coordinator so it works from both 1:1 and group controllers (which each hold only a subset of
// submanagers). Wire format unchanged, so forwarded content stays cross-platform.
enum MessageForwarder {
    private static var toxManager: OCTManager? {
        guard let appDelegate = UIApplication.shared.delegate as? AppDelegate,
              let running = appDelegate.coordinator?.activeCoordinator as? RunningCoordinator else {
            return nil
        }
        return running.activeSessionCoordinator?.toxManager
    }

    static func displayName(for chat: OCTChat) -> String {
        if chat.isSavedMessages {
            return String(localized: "saved_messages_title")
        }
        if chat.isGroup {
            let name = chat.groupName ?? ""
            return name.isEmpty ? "—" : name
        }
        if let friend = chat.friends.lastObject() as? OCTFriend {
            return friend.nickname.isEmpty ? friend.publicKey : friend.nickname
        }
        return "?"
    }

    static func presentForwardPicker(for message: OCTMessageAbstract, from controller: UIViewController, sourceView: UIView) {
        guard let manager = toxManager else {
            return
        }

        let chats = manager.objects.chats().sortedResultsUsingProperty("lastActivityDateInterval", ascending: false)
        let alert = UIAlertController(title: String(localized: "chat_forward_action"), message: nil, preferredStyle: .actionSheet)
        alert.popoverPresentationController?.sourceView = sourceView
        alert.popoverPresentationController?.sourceRect = sourceView.bounds

        // Saved Messages always available as first option (created on demand).
        alert.addAction(UIAlertAction(title: String(localized: "saved_messages_title"), style: .default) { _ in
            guard let saved = manager.objects.getOrCreateSavedMessagesChat() else { return }
            forward(message, to: saved, manager: manager)
        })

        let limit = min(chats.count, 25)
        for index in 0..<limit {
            let chat = chats[index]
            if chat.isSavedMessages {
                continue
            }
            alert.addAction(UIAlertAction(title: displayName(for: chat), style: .default) { _ in
                forward(message, to: chat, manager: manager)
            })
        }
        alert.addAction(UIAlertAction(title: String(localized: "alert_cancel"), style: .cancel, handler: nil))
        controller.present(alert, animated: true, completion: nil)
    }

    /// KHANDAQ (#39): the original author's display name for a "Forwarded from …" header — own
    /// messages → "You"; group messages → the frozen group peer name; 1:1 → the friend's nickname.
    private static func forwardSourceName(for message: OCTMessageAbstract, manager: OCTManager) -> String {
        if message.isOutgoing() {
            return String(localized: "chat_reply_self_name")
        }
        if let peerName = message.messageText?.groupPeerName, !peerName.isEmpty {
            return peerName
        }
        if let senderId = message.senderUniqueIdentifier,
           let friend = manager.objects.object(withUniqueIdentifier: senderId, for: .friend) as? OCTFriend {
            return friend.nickname.isEmpty ? friend.publicKey : friend.nickname
        }
        return String(localized: "chat_reply_unknown_sender")
    }

    private static func forward(_ message: OCTMessageAbstract, to chat: OCTChat, manager: OCTManager) {
        if chat.isSavedMessages {
            saveLocally(message, to: chat, manager: manager)
            return
        }

        if let raw = message.messageText?.text, !raw.isEmpty {
            // KHANDAQ (#39/#32): forward the clean visible body, NOT the original reply/mention wire
            // markup. Re-sending the raw text made the forward masquerade as a reply (leaked quote) and
            // left a tappable quote that could fuzzy-jump to an unrelated local message on the receiver.
            let body = MessageReplyHelper.plainBody(for: message) ?? raw
            // KHANDAQ (#39): Telegram-style "Forwarded from <name>" attribution line above the body.
            let header = String(format: String(localized: "chat_forwarded_from_format"),
                                forwardSourceName(for: message, manager: manager))
            let text = header + "\n" + body
            if chat.isGroup {
                manager.groups.sendMessage(to: chat, text: text, type: .normal, successBlock: { _ in }, failureBlock: { _ in })
            }
            else {
                manager.chats.sendMessage(to: chat, text: text, type: .normal, successBlock: nil, failureBlock: nil)
            }
            return
        }

        guard let file = message.messageFile,
              let path = file.filePath(),
              FileManager.default.fileExists(atPath: path) else {
            return
        }

        // Copy to a temp path so sendFile(moveToUploads:) doesn't move the source message's file away.
        let tempURL = FileManager.default.temporaryDirectory.appendingPathComponent((path as NSString).lastPathComponent)
        try? FileManager.default.removeItem(at: tempURL)
        guard (try? FileManager.default.copyItem(atPath: path, toPath: tempURL.path)) != nil else {
            return
        }

        if chat.isGroup {
            manager.groups.sendFile(atPath: tempURL.path, to: chat, moveToUploads: true, successBlock: { _ in }, failureBlock: { _ in })
        }
        else {
            manager.files.sendFile(atPath: tempURL.path, moveToUploads: true, to: chat) { _ in }
        }
    }

    /// Persistent directory for files stored in Saved Messages (Tox never transfers these).
    private static func savedFilesDirectory() -> URL? {
        guard let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first else {
            return nil
        }
        let dir = base.appendingPathComponent("KhandaqSaved", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true, attributes: nil)
        return dir
    }

    private static func saveLocally(_ message: OCTMessageAbstract, to chat: OCTChat, manager: OCTManager) {
        if let raw = message.messageText?.text, !raw.isEmpty {
            // KHANDAQ (#39): store the clean body, not the reply/mention wire markup.
            manager.objects.addSavedTextMessage(MessageReplyHelper.plainBody(for: message) ?? raw, to: chat)
            return
        }

        guard let file = message.messageFile,
              let path = file.filePath(),
              FileManager.default.fileExists(atPath: path),
              let dir = savedFilesDirectory() else {
            return
        }

        let originalName = (file.fileName?.isEmpty == false) ? file.fileName! : (path as NSString).lastPathComponent
        let dest = dir.appendingPathComponent("\(UUID().uuidString)_\(originalName)")
        guard (try? FileManager.default.copyItem(atPath: path, toPath: dest.path)) != nil else {
            return
        }

        manager.objects.addSavedFileMessage(withPath: dest.path,
                                            fileName: originalName,
                                            fileSize: file.fileSize,
                                            fileUTI: file.fileUTI,
                                            to: chat)
    }
}
