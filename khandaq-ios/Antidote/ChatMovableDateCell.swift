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
}

extension ChatMovableDateCellDelegate {
    func chatMovableDateCellForwardPressed(_ cell: ChatMovableDateCell) {}
}

class ChatMovableDateCell: BaseCell {
    private static var __once: () = {
            var items = UIMenuController.shared.menuItems ?? [UIMenuItem]()
            items += [
                UIMenuItem(title: String(localized: "chat_reply_action"), action: #selector(replyAction)),
                UIMenuItem(title: String(localized: "chat_forward_action"), action: #selector(forwardAction)),
                UIMenuItem(title: String(localized: "chat_more_menu_item"), action: #selector(moreAction))
            ]

            UIMenuController.shared.menuItems = items
        }()
    weak var delegate: ChatMovableDateCellDelegate?
    weak var replySwipeDelegate: ChatReplySwipeDelegate?

    var canBeCopied = false

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

    fileprivate var isShowingMenu: Bool = false
    fileprivate static var setupOnceToken: Int = 0

    override func setupWithTheme(_ theme: Theme, model: BaseCellModel) {
        super.setupWithTheme(theme, model: model)

        guard let movableModel = model as? ChatMovableDateCellModel else {
            assert(false, "Wrong model \(model) passed to cell \(self)")
            return
        }

        _ = ChatMovableDateCell.__once

        dateLabel.text = movableModel.dateString
        dateLabel.numberOfLines = 0 // --> multiline label
        dateLabel.textColor = theme.colorForType(.ChatListCellMessage)
    }

    override func createViews() {
        super.createViews()

        movableContentView = UIView()
        movableContentView.backgroundColor = .clear
        contentView.addSubview(movableContentView)

        dateLabel = UILabel()
        dateLabel.font = UIFont.khandaqFontWithSize(11.0, weight: .medium)
        movableContentView.addSubview(dateLabel)

        let swipeReply = UISwipeGestureRecognizer(target: self, action: #selector(handleReplySwipe))
        swipeReply.direction = .right
        movableContentView.addGestureRecognizer(swipeReply)

        // Using empty view for multiple selection background.
        multipleSelectionBackgroundView = UIView()
    }

    override func installConstraints() {
        super.installConstraints()

        movableContentView.snp.makeConstraints {
            $0.top.equalTo(contentView)
            if (UserDefaultsManager().DateonmessageMode == true) {
                movableContentViewLeftConstraint = $0.leading.equalTo(contentView).constraint.update(offset: -39)
            } else {
                movableContentViewLeftConstraint = $0.leading.equalTo(contentView).constraint
            }
            $0.size.equalTo(contentView)
        }

        dateLabel.snp.makeConstraints {
            $0.centerY.equalTo(movableContentView)
            $0.leading.equalTo(movableContentView.snp.trailing)
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

        let limit = min(chats.count, 25)
        for index in 0..<limit {
            let chat = chats[index]
            alert.addAction(UIAlertAction(title: displayName(for: chat), style: .default) { _ in
                forward(message, to: chat, manager: manager)
            })
        }
        alert.addAction(UIAlertAction(title: String(localized: "alert_cancel"), style: .cancel, handler: nil))
        controller.present(alert, animated: true, completion: nil)
    }

    private static func forward(_ message: OCTMessageAbstract, to chat: OCTChat, manager: OCTManager) {
        if let text = message.messageText?.text, !text.isEmpty {
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
}
