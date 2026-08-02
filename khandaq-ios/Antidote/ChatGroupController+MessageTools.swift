// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit
import SnapKit

extension ChatGroupController {
    func installMessageToolsUI() {
        tableView.allowsMultipleSelectionDuringEditing = true

        editMessagesToolbar = UIToolbar()
        editMessagesToolbar.isHidden = true
        editMessagesToolbar.tintColor = theme.colorForType(.LinkText)
        editMessagesToolbar.items = [
            UIBarButtonItem(barButtonSystemItem: .trash, target: self, action: #selector(ChatGroupController.editMessagesDeleteButtonPressed)),
            UIBarButtonItem(barButtonSystemItem: .flexibleSpace, target: nil, action: nil),
            UIBarButtonItem(title: String(localized: "group_messages_copy_action"), style: .plain, target: self, action: #selector(ChatGroupController.editMessagesCopyButtonPressed)),
        ]
        view.addSubview(editMessagesToolbar)

        editMessagesToolbar.snp.makeConstraints {
            $0.leading.trailing.equalTo(view)
            editMessagesToolbarBottomConstraint = $0.bottom.equalTo(chatInputView.snp.top).constraint
        }

        if #available(iOS 11.0, *) {
            let searchController = UISearchController(searchResultsController: nil)
            searchController.obscuresBackgroundDuringPresentation = false
            searchController.searchResultsUpdater = self
            searchController.searchBar.placeholder = String(localized: "group_messages_search_placeholder")
            navigationItem.searchController = searchController
            navigationItem.hidesSearchBarWhenScrolling = true
            definesPresentationContext = true
            messageSearchController = searchController
        }
    }

    func displayableRowCount() -> Int {
        let loadedCount = min(visibleMessages, messages.count)
        guard !messageSearchQuery.isEmpty else {
            return loadedCount
        }

        var count = 0
        for index in 0..<loadedCount {
            if MessageSearchHighlighter.messageMatches(messages[index], query: messageSearchQuery) {
                count += 1
            }
        }
        return count
    }

    func messageEntry(atDisplayIndex index: Int) -> (message: OCTMessageAbstract, storageIndex: Int) {
        let loadedCount = min(visibleMessages, messages.count)
        guard !messageSearchQuery.isEmpty else {
            return (messages[index], index)
        }

        var seen = 0
        for storageIndex in 0..<loadedCount {
            if MessageSearchHighlighter.messageMatches(messages[storageIndex], query: messageSearchQuery) {
                if seen == index {
                    return (messages[storageIndex], storageIndex)
                }
                seen += 1
            }
        }

        return (messages[index], index)
    }

    func quoteText(for message: OCTMessageAbstract) -> String? {
        MessageReplyHelper.previewText(for: message)
    }

    func startReply(to message: OCTMessageAbstract) {
        replyController.startReply(to: message, submanagerObjects: submanagerObjects, theme: theme)
        _ = chatInputView.becomeFirstResponder()
    }

    func attachReplyQuoteHandler(to model: ChatBaseTextCellModel, messages: Results<OCTMessageAbstract>) {
        guard let meta = model.replyMeta else {
            model.onReplyQuoteTap = nil
            return
        }
        model.onReplyQuoteTap = { [weak self] in
            guard let self = self else {
                return
            }
            self.replyController.scrollToReplyTarget(meta,
                                                     messages: messages,
                                                     tableView: self.tableView,
                                                     submanagerObjects: self.submanagerObjects,
                                                     ensureLoaded: { [weak self] targetIndex in
                                                         guard let self = self else { return }
                                                         self.visibleMessages = min(messages.count, targetIndex + 30)
                                                         self.tableView?.reloadData()
                                                     })
        }
    }

    func appendQuote(from message: OCTMessageAbstract) {
        startReply(to: message)
    }

    func toggleTableViewEditing(_ editing: Bool, animated: Bool) {
        tableView.setEditing(editing, animated: animated)
        editMessagesToolbar.isHidden = !editing

        if editing {
            closeMembersDrawer(animated: false)
            _ = chatInputView.resignFirstResponder()
            navigationItem.rightBarButtonItem = UIBarButtonItem(
                    barButtonSystemItem: .cancel,
                    target: self,
                    action: #selector(ChatGroupController.cancelEditingButtonPressed))
        }
        else {
            updateGroupInfoBarButton()
        }
    }

    @objc func editMessagesDeleteButtonPressed() {
        guard let selectedRows = tableView.indexPathsForSelectedRows, !selectedRows.isEmpty else {
            return
        }

        let toRemove = selectedRows.map { messageEntry(atDisplayIndex: $0.row).message }
        // KHANDAQ (#193): own group TEXT messages retract for everyone; the rest delete locally.
        for message in toRemove {
            if message.messageText != nil && message.groupSenderPeerId == 0 {
                submanagerGroups.deleteGroupMessage(forBoth: message, in: chat)
            } else {
                submanagerChats.removeMessages([message])
            }
        }
        toggleTableViewEditing(false, animated: true)
    }

    @objc func editMessagesCopyButtonPressed() {
        guard let selectedRows = tableView.indexPathsForSelectedRows, !selectedRows.isEmpty else {
            return
        }

        let parts = selectedRows.compactMap { indexPath -> String? in
            let message = messageEntry(atDisplayIndex: indexPath.row).message
            return quoteText(for: message)
        }

        UIPasteboard.general.string = parts.joined(separator: "\n")
        toggleTableViewEditing(false, animated: true)
    }

    @objc func cancelEditingButtonPressed() {
        toggleTableViewEditing(false, animated: true)
    }

    func presentMessageActions(for message: OCTMessageAbstract, sourceView: UIView, sourceRect: CGRect) {
        guard !message.groupSystemMessage else {
            return
        }

        let alert = UIAlertController(title: nil, message: nil, preferredStyle: .actionSheet)

        if quoteText(for: message) != nil {
            alert.addAction(UIAlertAction(title: String(localized: "chat_reply_action"), style: .default) { [weak self] _ in
                self?.startReply(to: message)
            })
        }

        alert.addAction(UIAlertAction(title: String(localized: "chat_forward_action"), style: .default) { [weak self] _ in
            guard let self = self else { return }
            MessageForwarder.presentForwardPicker(for: message, from: self, sourceView: sourceView)
        })

        alert.addAction(UIAlertAction(title: String(localized: "group_messages_select_action"), style: .default) { [weak self] _ in
            self?.toggleTableViewEditing(true, animated: true)
        })

        alert.addAction(UIAlertAction(title: String(localized: "alert_cancel"), style: .cancel, handler: nil))

        if let popover = alert.popoverPresentationController {
            popover.sourceView = sourceView
            popover.sourceRect = sourceRect
        }

        present(alert, animated: true, completion: nil)
    }
}

extension ChatGroupController: ChatReplySwipeDelegate {
    func chatCellDidRequestReply(_ cell: ChatMovableDateCell) {
        chatMovableDateCellReplyPressed(cell)
    }
}

extension ChatGroupController: UISearchResultsUpdating {
    func updateSearchResults(for searchController: UISearchController) {
        messageSearchQuery = searchController.searchBar.text ?? ""
        tableView.reloadData()
    }
}

extension ChatGroupController: ChatMovableDateCellDelegate {
    func chatMovableDateCellCopyPressed(_ cell: ChatMovableDateCell) {
        guard let indexPath = tableView.indexPath(for: cell) else {
            return
        }

        let message = messageEntry(atDisplayIndex: indexPath.row).message
        // KHANDAQ (#38): copy the visible body, not the raw reply/mention wire markup. quoteText()
        // only stripped mentions, so a reply's [KQ|…][KQ/end] header leaked into the clipboard.
        if let text = MessageReplyHelper.plainBody(for: message) {
            UIPasteboard.general.string = text
        }
    }

    func chatMovableDateCellDeletePressed(_ cell: ChatMovableDateCell) {
        guard let indexPath = tableView.indexPath(for: cell) else {
            return
        }

        let message = messageEntry(atDisplayIndex: indexPath.row).message
        // KHANDAQ (#193): own group TEXT retracts for everyone; the rest delete locally.
        if message.messageText != nil && message.groupSenderPeerId == 0 {
            submanagerGroups.deleteGroupMessage(forBoth: message, in: chat)
        } else {
            submanagerChats.removeMessages([message])
        }
    }

    func chatMovableDateCellMorePressed(_ cell: ChatMovableDateCell) {
        toggleTableViewEditing(true, animated: true)
    }

    func chatMovableDateCellReplyPressed(_ cell: ChatMovableDateCell) {
        guard let indexPath = tableView.indexPath(for: cell) else {
            return
        }
        let message = messageEntry(atDisplayIndex: indexPath.row).message
        startReply(to: message)
    }

    func chatMovableDateCellForwardPressed(_ cell: ChatMovableDateCell) {
        guard let indexPath = tableView.indexPath(for: cell) else {
            return
        }
        let message = messageEntry(atDisplayIndex: indexPath.row).message
        MessageForwarder.presentForwardPicker(for: message, from: self, sourceView: cell)
    }

    // KHANDAQ (#192): quick reaction bar for group messages (broadcasts KQ NGC packet 0x43).
    // Reached from the reaction-chip tap (legacy delegate) — the long-press context menu calls
    // presentGroupReactionPicker directly.
    func chatMovableDateCellReactPressed(_ cell: ChatMovableDateCell) {
        guard let indexPath = tableView.indexPath(for: cell) else {
            return
        }
        let message = messageEntry(atDisplayIndex: indexPath.row).message
        presentGroupReactionPicker(for: message, sourceView: cell)
    }

    // KHANDAQ (#208): edit an own group text message via an inline editor, then broadcast (NGC 0x41).
    func chatMovableDateCellEditPressed(_ cell: ChatMovableDateCell) {
        guard let indexPath = tableView.indexPath(for: cell) else {
            return
        }
        let message = messageEntry(atDisplayIndex: indexPath.row).message
        guard message.messageText != nil, message.groupSenderPeerId == 0 else {
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
            self.submanagerGroups.editGroupMessage(message, in: self.chat, newText: trimmed)
        })
        present(alert, animated: true, completion: nil)
    }

    // KHANDAQ (#192): Telegram-style horizontal reaction bar above the group message (chip-tap path).
    func presentGroupReactionPicker(for message: OCTMessageAbstract, sourceView: UIView) {
        let rect = sourceView.convert(sourceView.bounds, to: view)
        reactionPopup.present(in: view, aboveRect: rect,
                              currentEmoji: ChatReactionsFormat.ownEmoji(from: message.reactionsJSON),
                              dark: ThemeAppearance.isDarkMode) { [weak self] emoji in
            guard let self = self else { return }
            self.submanagerGroups.toggleReaction(onGroupMessage: message, emoji: emoji, in: self.chat)
        }
    }

    // KHANDAQ (#G5 reaction-row): custom Telegram long-press popup for GROUPS (parity with 1:1) —
    // reaction bar above + action list below, one dimmed backdrop. Same gating as the old group system
    // menu (text/file only, no system messages); reuses the group per-message handlers, minus «Реакция»
    // (the bar handles it via submanagerGroups.toggleReaction).
    @objc func longPressOnGroupTable(_ gesture: UILongPressGestureRecognizer) {
        guard gesture.state == .began, !tableView.isEditing else {
            return
        }
        let point = gesture.location(in: tableView)
        guard let indexPath = tableView.indexPathForRow(at: point),
              let cell = tableView.cellForRow(at: indexPath) as? ChatMovableDateCell else {
            return
        }
        let message = messageEntry(atDisplayIndex: indexPath.row).message
        guard !message.groupSystemMessage else {
            return
        }
        guard message.messageText != nil || message.messageFile != nil else {
            return
        }
        if let editable = cell as? ChatEditable, !editable.shouldShowMenu() {
            return
        }
        presentGroupContextPopup(for: cell, message: message, indexPath: indexPath)
    }

    fileprivate func presentGroupContextPopup(for cell: ChatMovableDateCell, message: OCTMessageAbstract, indexPath: IndexPath) {
        if #available(iOS 10.0, *) {
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
        }
        var actions: [ChatContextMenuAction] = []
        if quoteText(for: message) != nil {
            actions.append(ChatContextMenuAction(title: String(localized: "chat_reply_action"), systemImageName: "arrowshape.turn.up.left") { [weak self] in self?.startReply(to: message) })
        }
        actions.append(ChatContextMenuAction(title: String(localized: "chat_forward_action"), systemImageName: "arrowshape.turn.up.right") { [weak self] in
            guard let self = self else { return }
            let source: UIView = self.tableView.cellForRow(at: indexPath) ?? self.tableView
            MessageForwarder.presentForwardPicker(for: message, from: self, sourceView: source)
        })
        actions.append(ChatContextMenuAction(title: String(localized: "chat_pin_action"), systemImageName: "pin") { [weak self] in
            guard let self = self, let id = message.uniqueIdentifier else { return }
            ChatPinStore.setPinned(messageId: id, forChat: self.chat.uniqueIdentifier)
            self.refreshPinnedBanner()
        })
        actions.append(ChatContextMenuAction(title: String(localized: "group_messages_select_action"), systemImageName: "checkmark.circle") { [weak self] in self?.toggleTableViewEditing(true, animated: true) })

        let editable = cell as? ChatEditable
        let rect: CGRect
        if let editable = editable {
            rect = cell.convert(editable.menuTargetRect(), to: view)
        } else {
            rect = cell.convert(cell.bounds, to: view)
        }
        editable?.willShowMenu()
        reactionPopup.presentMenu(in: view, aroundRect: rect,
                                  currentEmoji: ChatReactionsFormat.ownEmoji(from: message.reactionsJSON),
                                  dark: ThemeAppearance.isDarkMode, actions: actions,
                                  onPick: { [weak self] emoji in
                                      guard let self = self else { return }
                                      self.submanagerGroups.toggleReaction(onGroupMessage: message, emoji: emoji, in: self.chat)
                                  },
                                  onDismiss: { editable?.willHideMenu() })
    }
}
