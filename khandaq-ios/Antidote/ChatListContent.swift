// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit

/// What a conversation row says and counts, shared by the iPad chat list (ChatListTableManager) and
/// the phone's Air list (AirListDataSource), so both read a chat the same way and no two counters
/// disagree.
enum ChatListContent {
    struct Preview: Equatable {
        enum Kind: Equatable {
            case message
            case draft
            case typing
        }

        let text: String
        let kind: Kind
    }

    /// The line under a conversation's name: "typing" while the friend types, then an unsent draft,
    /// then the last message as the person sees it (reply and mention markup removed).
    static func preview(in chat: OCTChat, friend: OCTFriend?) -> Preview {
        if let friend = friend, friend.isTyping {
            return Preview(text: String(localized: "chat_is_typing_text"), kind: .typing)
        }

        if let draft = chat.enteredText?.trimmingCharacters(in: .whitespacesAndNewlines),
           !draft.isEmpty,
           draft != "(null)" {
            var body = draft.replacingOccurrences(of: "\n", with: " ")
            if body.count > 120 {
                let endIndex = body.index(body.startIndex, offsetBy: 117)
                body = String(body[..<endIndex]) + "..."
            }
            return Preview(text: String(localized: "chat_draft_prefix", body), kind: .draft)
        }

        guard let message = chat.lastMessage else {
            return Preview(text: "", kind: .message)
        }

        if message.messageText != nil {
            // KHANDAQ (#109): show the clean visible body in the list preview, not the reply and mention
            // wire markup ([KQ|...] ... [KQ/end]).
            return Preview(text: MessageReplyHelper.plainBody(for: message) ?? message.messageText?.text ?? "", kind: .message)
        }
        else if let file = message.messageFile {
            let fileName = file.fileName ?? ""
            return Preview(text: String(localized: message.isOutgoing() ? "chat_outgoing_file" : "chat_incoming_file") + " \(fileName)",
                           kind: .message)
        }
        else if let call = message.messageCall {
            return callPreview(call, outgoing: message.isOutgoing())
        }

        return Preview(text: "", kind: .message)
    }

    /// A call under a conversation's name says what its entry in the transcript says: "Call, 24:33",
    /// "Missed call", "No answer" (the forks' "Call finished - 24:33" stood here, a hyphen for a dash).
    static func callPreview(_ call: OCTMessageCall, outgoing: Bool) -> Preview {
        return Preview(text: AirCallText.entry(answered: call.callEvent == .answered, duration: call.callDuration, incoming: !outgoing),
                       kind: .message)
    }

    /// Time for today, a relative date for anything older.
    static func timeText(for date: Date, timeFormatter: DateFormatter, dateFormatter: DateFormatter) -> String {
        let isToday = (Calendar.current as NSCalendar).compare(Date(), to: date, toUnitGranularity: .day) == .orderedSame

        return isToday ? timeFormatter.string(from: date) : dateFormatter.string(from: date)
    }

    /// KHANDAQ (#46): the owner-changeable topic first (an NGC group's name cannot change after creation,
    /// so a "rename" changes the topic), then the immutable name. Khandaq Community is always "Khandaq
    /// Community" (KhandaqCommunity.displayName), whatever its topic.
    static func groupTitle(for chat: OCTChat) -> String {
        if let fixed = KhandaqCommunity.displayName(for: chat) {
            return fixed
        }
        return (chat.groupTopic?.isEmpty == false ? chat.groupTopic : chat.groupName)
            ?? String(localized: "group_chat_default_title")
    }

    // KHANDAQ (audit F-6): a history-synced row whose author could not be resolved at insert time
    // carries groupSenderPeerId == 0, exactly the shape isOutgoing() used to misread as "ours".
    // Correcting the alignment (it IS someone else's message) would otherwise make those rows start
    // counting, so a long-idle group would jump to a brand-new unread number purely on update. Keep
    // them out of the badge; attributable synced rows (peerId > 0) count exactly as they always did.
    // KHANDAQ (notification parity, 2026-11-20): peer id 0 is what the history sync gives every row whose author is
    // not in the group at that moment (left, or offline), and in Khandaq Community, where members come and go all
    // day, that was most of what it brought: new messages showed no unread. Such a row still names its author by the
    // public key frozen on it at insert, so it counts like the message of a member who is there; only a legacy
    // synced row with no key, which nobody can be named for, stays out. The badge keeps the same rule
    // (NotificationCoordinator.unreadChatsPredicate).
    static func countsAsUnread(_ message: OCTMessageAbstract) -> Bool {
        if message.isOutgoing() || message.groupSystemMessage {
            return false
        }

        guard message.groupHistorySync && message.groupSenderPeerId == 0 else {
            return true
        }
        return message.messageText?.groupSenderPubkey != nil || message.messageFile?.groupSenderPubkey != nil
    }

    static func unreadCount(for chat: OCTChat,
                            submanagerGroups: OCTSubmanagerGroups,
                            submanagerObjects: OCTSubmanagerObjects) -> Int {
        let privateUnread = chat.isGroup ? Int(submanagerGroups.totalUnreadPrivateMessageCount(for: chat)) : 0
        if privateUnread > 0 {
            return privateUnread
        }

        // KHANDAQ (#G8): do NOT gate on chat.hasUnreadMessages(). The core version is date-only and
        // disagrees with the app badge (which counts by lastMessage.dateInterval > lastReadDateInterval),
        // so a chat could show a badge yet no per-row pill. The predicate below already returns 0 for a
        // fully read chat, so the guard only introduced that inconsistency.
        // KHANDAQ (27.11): the moment of the reading as an NSNumber, to the second. With %f NSPredicate takes a float,
        // which holds today's dates only to 128 s: a message read up to a minute before counted unread, or one that
        // came up to a minute after the reading did not.
        let predicate = NSPredicate(format: "chatUniqueIdentifier == %@ AND dateInterval > %@",
                                    chat.uniqueIdentifier,
                                    NSNumber(value: chat.lastReadDateInterval))
        guard let results = submanagerObjects.objects(for: .messageAbstract, predicate: predicate) else {
            return 0
        }
        var count = 0
        for index in 0..<results.count {
            guard let message = results.object(at: index) as? OCTMessageAbstract else {
                continue
            }
            // KHANDAQ (#41): only genuine incoming messages count: skip outgoing messages and "X joined"
            // system notices (isOutgoing() returns NO for system messages, so guard explicitly).
            if countsAsUnread(message) {
                count += 1
            }
        }
        return count
    }

    /// Clear the history, or leave the group and remove it; the caller presents the sheet (E21: from
    /// below on the phone).
    static func groupDeleteAlert(for chat: OCTChat, submanagerGroups: OCTSubmanagerGroups) -> UIAlertController {
        let alert = UIAlertController(title: String(localized: "group_delete_chat_title"),
                                      message: String(localized: "group_delete_chat_message"),
                                      preferredStyle: .actionSheet)

        alert.addAction(UIAlertAction(title: String(localized: "alert_cancel"), style: .cancel, handler: nil))
        alert.addAction(UIAlertAction(title: String(localized: "group_clear_history_action"), style: .default) { [weak submanagerGroups] _ in
            submanagerGroups?.removeAllMessages(in: chat, removeChat: false, leaveGroup: false)
        })
        alert.addAction(UIAlertAction(title: String(localized: "group_leave_action"), style: .destructive) { [weak submanagerGroups] _ in
            guard let submanagerGroups = submanagerGroups else {
                return
            }
            leaveAndRemove(chat, submanagerGroups: submanagerGroups)
        })

        return alert
    }

    // KHANDAQ (Figma): mass delete from the multi-select edit mode. 1:1 chats are removed with their
    // messages; groups are left (best effort) and removed: the same semantics as the row-level
    // destructive actions, applied without per-chat prompts (the caller confirms once for all).
    static func removeChats(_ chats: [OCTChat], submanagerChats: OCTSubmanagerChats, submanagerGroups: OCTSubmanagerGroups) {
        for chat in chats {
            if chat.isGroup {
                leaveAndRemove(chat, submanagerGroups: submanagerGroups)
            }
            else {
                submanagerChats.removeAllMessages(in: chat, removeChat: true)
            }
        }
    }

    /// The tile a chat has in the list: the bookmark of Saved Messages, the logo of the app for Khandaq Community, the
    /// initials of a group or a person in their tone. A person's photo, when there is one, goes over it.
    static func tileStyle(for chat: OCTChat, title: String) -> AirTileView.Style {
        if chat.isSavedMessages {
            return .saved
        }
        if chat.isGroup {
            if KhandaqCommunity.isCommunity(chat) {
                return .community
            }
            return .group(initials: AirInitials.from(title), tone: AirTone.index(for: chat.uniqueIdentifier))
        }
        let friend = chat.friends.lastObject() as? OCTFriend
        let key: String = friend?.publicKey ?? chat.uniqueIdentifier ?? ""
        return .person(initials: AirInitials.from(title), tone: AirTone.index(for: key))
    }

    /// The read-only conversation shown when a row is pressed and held. It has no side effects and
    /// does not mark anything read (KHANDAQ #13).
    /// KHANDAQ (inbox #24, 27.11): with the tile of the row (tileStyle), and in a group with the authors over the
    /// entries; a group's preview had letters in a circle instead of the logo of Khandaq Community, and bubbles
    /// without names, as the talk of two.
    /// KHANDAQ (inbox #28, 28.11): the entries quote the messages they answer and show photos, videos and places as the
    /// conversation does; submanagerChats tells which quoted messages were deleted here.
    static func previewController(for chat: OCTChat,
                                  theme: Theme,
                                  submanagerChats: OCTSubmanagerChats?,
                                  submanagerGroups: OCTSubmanagerGroups,
                                  submanagerObjects: OCTSubmanagerObjects) -> ChatPreviewController {
        let title: String
        var photo: UIImage?

        if chat.isSavedMessages {
            // KHANDAQ (#109/#110): the long-press peek of Saved Messages shows its own title and the
            // bookmark, not the 1:1 fallback "deleted contact" (Saved Messages has no friend).
            title = String(localized: "saved_messages_title")
        }
        else if chat.isGroup {
            title = groupTitle(for: chat)
        }
        else {
            let friend = chat.friends.lastObject() as? OCTFriend
            title = friend?.nickname ?? String(localized: "contact_deleted")
            photo = friend?.avatarData.flatMap { UIImage(data: $0) }
        }

        // KHANDAQ (0.2.56): in the conversation's order (OCTMessageAbstract.chatOrderSortDescriptors), so the
        // preview shows the parts of a long text as the conversation does. KHANDAQ (inbox #28): and the messages the
        // conversation shows: a group's notices only while its settings show them (ChatGroupController.makeMessagesResults).
        let showsNotices = !chat.isGroup || UserDefaultsManager().groupShowSystemMessages
        let format = showsNotices
            ? "chatUniqueIdentifier == %@"
            : "chatUniqueIdentifier == %@ AND groupSystemMessage == NO"
        let allMessages = submanagerObjects.messages(predicate: NSPredicate(format: format, chat.uniqueIdentifier))
            .sortedResultsUsingDescriptors(OCTMessageAbstract.chatOrderSortDescriptors(ascending: true))

        var recent = [OCTMessageAbstract]()
        let total = allMessages.count
        var index = max(0, total - 24)
        while index < total {
            recent.append(allMessages[index])
            index += 1
        }

        // A quote of a message deleted here says so, as in the conversation (inbox #10).
        let deletedReplyTargets = ChatDeletedReplyTargets(chat: chat, submanagerChats: submanagerChats)
        let entries = ChatPreviewController.entries(of: recent, in: chat, groups: submanagerGroups) { reply, message in
            deletedReplyTargets.marked(reply, of: message, in: allMessages)
        }

        return ChatPreviewController(theme: theme,
                                     title: title,
                                     tile: tileStyle(for: chat, title: title),
                                     photo: photo,
                                     entries: entries)
    }

    private static func leaveAndRemove(_ chat: OCTChat, submanagerGroups: OCTSubmanagerGroups) {
        // Read while the chat is still there: removing it below invalidates it.
        KhandaqCommunity.willLeave(chat)
        // KHANDAQ (0.2.60): our invitations to it that wait for their contacts go too.
        AirLocalStore.shared.dropOutgoingInvites(groupKey: chat.groupChatIdHex ?? "")
        if chat.groupNumber >= 0 {
            do {
                try submanagerGroups.leaveGroup(withNumber: OCTToxGroupNumber(chat.groupNumber), partMessage: nil)
            }
            catch {
                // Still remove the local chat if the tox leave fails (already left, disconnected).
            }
        }
        submanagerGroups.removeAllMessages(in: chat, removeChat: true, leaveGroup: false)
    }
}

extension AirUnreadBand {
    /// KHANDAQ (ideas 1): the band over a conversation's messages, newest first, by the rule the unread
    /// count keeps: a message's dateInterval against the read date, and only what countsAsUnread counts
    /// (not your own messages, not the notices of a group, not a synced row nobody can be named for).
    init(messages: Results<OCTMessageAbstract>, lastRead: TimeInterval) {
        self.init(count: messages.count,
                  lastRead: lastRead,
                  arrival: { messages[$0].dateInterval },
                  countsAsUnread: { ChatListContent.countsAsUnread(messages[$0]) },
                  id: { messages[$0].uniqueIdentifier })
    }
}
