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
                                                     submanagerObjects: self.submanagerObjects)
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
        submanagerChats.removeMessages(toRemove)
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
        if let text = quoteText(for: message) {
            UIPasteboard.general.string = text
        }
    }

    func chatMovableDateCellDeletePressed(_ cell: ChatMovableDateCell) {
        guard let indexPath = tableView.indexPath(for: cell) else {
            return
        }

        let message = messageEntry(atDisplayIndex: indexPath.row).message
        submanagerChats.removeMessages([message])
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
}
