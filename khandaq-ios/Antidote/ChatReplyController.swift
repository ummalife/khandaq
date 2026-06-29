// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit
import SnapKit

protocol ChatReplySwipeDelegate: class {
    func chatCellDidRequestReply(_ cell: ChatMovableDateCell)
}

/// Shared reply state + preview bar for chat screens.
final class ChatReplyController {
    private(set) var pendingMeta: MessageReplyHelper.ReplyMeta?
    let previewView = ChatReplyPreviewView()

    func install(in hostView: UIView, above inputView: UIView, theme: Theme) {
        hostView.addSubview(previewView)
        previewView.apply(theme: theme)
        previewView.snp.makeConstraints {
            $0.leading.trailing.equalTo(hostView)
            $0.bottom.equalTo(inputView.snp.top)
        }
        previewView.onCancel = { [weak self] in
            self?.clear()
        }
        previewView.hidePreview()
    }

    func clear() {
        pendingMeta = nil
        previewView.hidePreview()
    }

    // KHANDAQ (#121): persist the pending reply per-chat so a reply selected, then abandoned by leaving
    // the chat without sending, survives re-entry (the controller — and its in-memory pendingMeta — is
    // gone on exit). Mirrors how the draft text is persisted on the chat.
    private static func persistKey(forChatId chatId: String) -> String {
        return "khandaqPendingReply_\(chatId)"
    }

    func savePending(forChatId chatId: String) {
        let key = ChatReplyController.persistKey(forChatId: chatId)
        guard let meta = pendingMeta, let data = try? JSONEncoder().encode(meta) else {
            UserDefaults.standard.removeObject(forKey: key)
            return
        }
        UserDefaults.standard.set(data, forKey: key)
    }

    func restorePending(forChatId chatId: String, theme: Theme) {
        guard pendingMeta == nil else {
            return
        }
        let key = ChatReplyController.persistKey(forChatId: chatId)
        guard let data = UserDefaults.standard.data(forKey: key),
              let meta = try? JSONDecoder().decode(MessageReplyHelper.ReplyMeta.self, from: data) else {
            return
        }
        pendingMeta = meta
        previewView.show(meta: meta, theme: theme)
    }

    func composeOutgoingText(_ userText: String) -> String {
        let encoded = MessageReplyHelper.encode(pendingMeta, bodyText: userText)
        clear()
        return encoded
    }

    func startReply(to message: OCTMessageAbstract, submanagerObjects: OCTSubmanagerObjects, theme: Theme) {
        guard let preview = MessageReplyHelper.previewText(for: message) else {
            return
        }

        var meta = MessageReplyHelper.ReplyMeta()
        meta.localMessageId = MessageReplyHelper.stableLocalId(message.uniqueIdentifier)
        meta.sortTimestampMs = Int64(message.dateInterval * 1000)
        meta.previewText = preview

        if message.isOutgoing() {
            meta.senderName = String(localized: "chat_reply_self_name")
        }
        else if let senderId = message.senderUniqueIdentifier,
                let friend = submanagerObjects.object(withUniqueIdentifier: senderId, for: .friend) as? OCTFriend {
            meta.senderPubkeyTail = MessageReplyHelper.pubkeyTail(friend.publicKey)
            meta.senderName = friendDisplayName(friend)
        }
        else if let peerName = message.messageText?.groupPeerName ?? message.messageFile?.groupPeerName,
                !peerName.isEmpty {
            // KHANDAQ (#83): group FILE messages freeze the sender on messageFile, not messageText —
            // without this branch replying to a received file showed the author as "Неизвестно".
            meta.senderName = peerName
        }
        else {
            meta.senderName = String(localized: "chat_reply_unknown_sender")
        }

        pendingMeta = meta
        previewView.show(meta: meta, theme: theme)
    }

    func scrollToReplyTarget(_ meta: MessageReplyHelper.ReplyMeta,
                             messages: Results<OCTMessageAbstract>,
                             tableView: UITableView,
                             submanagerObjects: OCTSubmanagerObjects,
                             ensureLoaded: (Int) -> Void) {
        // KHANDAQ (#100): search the FULL history, not just the currently loaded window — replying to
        // an older (paginated-out) message must still jump to it.
        var bestIndex: Int?
        var bestDelta = Int64.max

        for index in 0..<messages.count {
            let message = messages[index]
            if meta.localMessageId != 0,
               MessageReplyHelper.stableLocalId(message.uniqueIdentifier) == meta.localMessageId {
                bestIndex = index
                break
            }

            let ts = Int64(message.dateInterval * 1000)
            let delta = abs(ts - meta.sortTimestampMs)
            if delta > 5000 {
                continue
            }

            if !meta.senderPubkeyTail.isEmpty {
                if message.isOutgoing() {
                    continue
                }
                guard let senderId = message.senderUniqueIdentifier,
                      let friend = submanagerObjects.object(withUniqueIdentifier: senderId, for: .friend) as? OCTFriend,
                      MessageReplyHelper.pubkeyTail(friend.publicKey) == meta.senderPubkeyTail else {
                    continue
                }
            }

            if delta < bestDelta {
                bestDelta = delta
                bestIndex = index
            }
        }

        guard let index = bestIndex else {
            return
        }

        // KHANDAQ (#100): expand the loaded window if the target is paginated out, then scroll.
        // The guard keeps us crash-safe if storage index still maps past the display rows.
        if index >= tableView.numberOfRows(inSection: 0) {
            ensureLoaded(index)
            tableView.layoutIfNeeded()
        }
        guard index < tableView.numberOfRows(inSection: 0) else {
            return
        }

        let indexPath = IndexPath(row: index, section: 0)
        tableView.scrollToRow(at: indexPath, at: .middle, animated: true)

        DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) {
            if let cell = tableView.cellForRow(at: indexPath) {
                UIView.animate(withDuration: 0.25, animations: {
                    cell.backgroundColor = UIColor.systemYellow.withAlphaComponent(0.25)
                }, completion: { _ in
                    UIView.animate(withDuration: 0.4) {
                        cell.backgroundColor = .clear
                    }
                })
            }
        }
    }

    private func friendDisplayName(_ friend: OCTFriend) -> String {
        if let name = friend.name, !name.isEmpty {
            return name
        }
        if !friend.nickname.isEmpty, friend.nickname != friend.publicKey {
            return friend.nickname
        }
        return friend.publicKey
    }
}
