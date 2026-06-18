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
        else if let peerName = message.messageText?.groupPeerName, !peerName.isEmpty {
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
                             submanagerObjects: OCTSubmanagerObjects) {
        let loaded = min(tableView.numberOfRows(inSection: 0), messages.count)
        var bestIndex: Int?
        var bestDelta = Int64.max

        for index in 0..<loaded {
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
