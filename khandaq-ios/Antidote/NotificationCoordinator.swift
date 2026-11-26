// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation
import os
import UserNotifications

private enum NotificationType {
    case newMessage(OCTMessageAbstract)
    case friendRequest(OCTFriendRequest)
    case groupInvite(AirGroupInvite)
    case reaction(ReactionNotice)
}

/// KHANDAQ (0.2.64): somebody reacted to one of our messages (kOCTMessageReactionNotification).
private struct ReactionNotice {
    let message: OCTMessageAbstract
    let emoji: String
    /// The member who reacted, in a group.
    let reactorName: String?
    /// `r:<anchor>:<emoji>`: the same event a push preview names in `khq_ev`.
    let eventKey: String?
}

private struct Constants {
    static let NotificationVisibleDuration = 3.0
}

protocol NotificationCoordinatorDelegate: class {
    func notificationCoordinator(_ coordinator: NotificationCoordinator, showChat chat: OCTChat)
    func notificationCoordinatorShowFriendRequest(_ coordinator: NotificationCoordinator, showRequest request: OCTFriendRequest)
    /// Requests, where the invitations to groups wait.
    func notificationCoordinatorShowGroupInvites(_ coordinator: NotificationCoordinator)
    func notificationCoordinatorAnswerIncomingCall(_ coordinator: NotificationCoordinator, userInfo: String)

    func notificationCoordinator(_ coordinator: NotificationCoordinator, updateFriendsBadge badge: Int)
    func notificationCoordinator(_ coordinator: NotificationCoordinator, updateChatsBadge badge: Int)
}

/// What was notified while away neither rings nor comes as a band (E22): only a message that arrived
/// just now does.
enum NotificationFreshness {
    /// KHANDAQ (#H3): the backlog Realm flushes right after the app comes to the foreground arrived
    /// while away (already push-notified); it rang "on entry with nothing new visible". A message
    /// arriving while the app is already open, past this short grace window, rings normally.
    static let entryGrace: TimeInterval = 3

    /// KHANDAQ (#H3): the sound is gated only on "inserted at index 0 of the global dateInterval-DESC
    /// list", so a 1:1 message that was offline-QUEUED and flushed on reconnect (old send time, stored
    /// now) inserted at 0 and rang with no visible new message ("звук на входе, а сообщений нет"). A
    /// genuinely-live message has tssent ≈ now; a flushed/backfilled one has an old tssent. 45s absorbs
    /// sender clock skew. Non-msgv3 peers have tssent==0 (can't be offline-queued) and ring normally.
    static let lateDelivery: TimeInterval = 45

    static func isPastEntryGrace(becameActive: Date, now: Date) -> Bool {
        return now.timeIntervalSince(becameActive) >= entryGrace
    }

    /// `tssent` is the sender's send time (0 without msgV3).
    static func arrivedJustNow(sentAt tssent: TimeInterval, becameActive: Date, now: Date) -> Bool {
        if tssent > 0, now.timeIntervalSince1970 - tssent > lateDelivery {
            return false
        }
        return isPastEntryGrace(becameActive: becameActive, now: now)
    }
}

/// KHANDAQ (notification parity, 2026-11-20): the generic banner of #G6, which the notification service extension
/// leaves in the relay's own words ("Khandaq", "New message"; "New activity" for an empty one) when no preview opens,
/// and which a local notification about the same message takes the place of.
enum WakeBanners {
    static func isGeneric(title: String, body: String) -> Bool {
        return title == "Khandaq" || body == "New activity" || body == "New message" || body.isEmpty
    }

    /// Whom a delivered banner is about, uppercased: the group of a preview that opened (`khq_gid`), else the contact
    /// whose wake it was (`sender_pubkey`, or `from` from the legacy FCM API, as ActiveSessionCoordinator reads them),
    /// else its thread, which the extension sets to one of the two; nil for a banner that names nobody.
    static func owner(of content: UNNotificationContent) -> String? {
        for key in ["khq_gid", "sender_pubkey", "from"] {
            if let value = content.userInfo[key] as? String, !value.isEmpty {
                return value.uppercased()
            }
        }
        return content.threadIdentifier.isEmpty ? nil : content.threadIdentifier.uppercased()
    }

    /// KHANDAQ (notification parity, 2026-11-20): every local notification removed every generic banner, so a
    /// Community message took away the only trace of a 1:1 message whose wake had shown one, before that message
    /// itself came in. A generic banner now goes only for a local notification about its own conversation, `owner`
    /// being a contact's public key or a group's chat id. One that names nobody (our own wake, sendOwnPush, or the wake
    /// of a relay that gave no sender) cannot be told apart from any conversation's: any local notification takes its
    /// place, as every one did before, or the banner of our own wake would stay until swiped.
    static func isReplaced(_ content: UNNotificationContent, byNotificationAbout owner: String?) -> Bool {
        guard isGeneric(title: content.title, body: content.body) else {
            return false
        }
        guard let named = self.owner(of: content) else {
            return true
        }
        guard let owner = owner, !owner.isEmpty else {
            return false
        }
        return named == owner.uppercased()
    }
}

/// KHANDAQ (0.2.69, group message wakes): the delivered notifications that already stood for a group text that came
/// over Tox. A push of a text that had to wait knows it by what it says, its mark in `khq_tm`, not by an event key
/// (docs/PUSH_PREVIEWS.md, "Group message wakes"), so a match uses the notification up: the next text that says the
/// same alerts. Kept in memory, on the main queue.
struct PushTextMarkMatches {
    /// At most this many matches are kept; the oldest goes first.
    static let capacity = 128

    private(set) var matchedIdentifiers = [String]()

    /// True when one of `delivered` (its request identifier and its `khq_tm`) carries one of `marks` and has not been
    /// matched before; that one is matched now.
    mutating func match(_ marks: [String], in delivered: [(identifier: String, mark: String?)]) -> Bool {
        guard !marks.isEmpty,
              let hit = delivered.first(where: { entry in
                  entry.mark.map { marks.contains($0) } == true && !matchedIdentifiers.contains(entry.identifier)
              }) else {
            return false
        }
        matchedIdentifiers.append(hit.identifier)
        if matchedIdentifiers.count > PushTextMarkMatches.capacity {
            matchedIdentifiers.removeFirst(matchedIdentifiers.count - PushTextMarkMatches.capacity)
        }
        return true
    }
}

class NotificationCoordinator: NSObject {
    weak var delegate: NotificationCoordinatorDelegate?
    /// KHANDAQ (26.11.2026): your key and your name in a group, to tell the messages that mention you
    /// (ActiveSessionCoordinator reads them from the group's members).
    var groupSelfIdentity: ((OCTChat) -> (key: String?, name: String?))?
    /// KHANDAQ (26.11.2026): the member of a group behind a contact request, when one asked from a group.
    var groupContactOffer: ((OCTFriendRequest) -> OCTGroupContactOffer?)?

    fileprivate let theme: Theme
    fileprivate let userDefaults = UserDefaultsManager()

    fileprivate let notificationWindow: NotificationWindow

    fileprivate weak var submanagerObjects: OCTSubmanagerObjects!

    fileprivate var messagesToken: RLMNotificationToken?
    fileprivate var chats: Results<OCTChat>
    fileprivate var chatsToken: RLMNotificationToken?
    fileprivate var requests: Results<OCTFriendRequest>
    fileprivate var requestsToken: RLMNotificationToken?

    fileprivate let avatarManager: AvatarManager
    fileprivate let audioPlayer = AlertAudioPlayer()

    fileprivate var notificationQueue = [NotificationType]()
    fileprivate var inAppNotificationAppIdsRegistered = [String: Bool]()
    fileprivate var bannedChatIdentifiers = Set<String>()

    // KHANDAQ (#G6): last contentful local notification, to suppress rapid duplicates.
    fileprivate var lastLocalNotificationBody: String = ""
    fileprivate var lastLocalNotificationTime: TimeInterval = 0

    // KHANDAQ (0.2.69, group message wakes): the pushes of group texts that had to wait which a text from Tox matched.
    fileprivate var pushTextMarkMatches = PushTextMarkMatches()

    // KHANDAQ (#H3): when the app last became active. On foreground Realm flushes the backlog of
    // messages that arrived while away as a single insertion batch; those must not ring on entry
    // (the user was already push-notified). We suppress the new-message sound briefly after this.
    fileprivate var lastBecameActiveDate: Date = .distantPast

    // KHANDAQ: the number last put on the app icon. Every change to an unread chat lands in
    // updateBadges(), and setting the icon to the number it already shows is still a request to
    // SpringBoard each time: a simulator set the same number up to 28 times in a quarter of a second,
    // in bursts every 30 s or so.
    fileprivate var appIconBadgeNumber: Int?

    // KHANDAQ (#41): a chat is "unread" for the tab/app badge only when its last message is a
    // genuine INCOMING, non-system message newer than lastRead. The old date-only predicate also
    // counted our own outgoing messages (badge stuck after you send) and "X joined" system notes.
    // For groups both incoming & outgoing have senderUniqueIdentifier == nil, so incoming is
    // distinguished by groupSenderPeerId > 0 (mirrors OCTMessageAbstract.isOutgoing).
    // KHANDAQ (0.2.57): a group whose notifications are off, muted by hand (Khandaq Community too: since 0.2.60
    // its join no longer mutes it), adds nothing to the tab or the app icon; its row in the list keeps its own counter.
    // KHANDAQ (notification parity, 2026-11-20): a row the history sync brought from a member who is not in the group
    // at that moment has peer id 0, and in Khandaq Community, where members come and go all day, so did most of them:
    // new messages added nothing. Such a row names its author by the public key frozen on it at insert, and counts
    // as the message of a member who is there does (ChatListContent.countsAsUnread); a legacy synced row with no key
    // still does not.
    static let unreadChatsPredicate = NSPredicate(format:
        "lastMessage.dateInterval > lastReadDateInterval"
        + " AND lastMessage.groupSystemMessage == NO"
        + " AND (lastMessage.groupSenderPeerId > 0 OR lastMessage.senderUniqueIdentifier != nil"
        + " OR (lastMessage.groupHistorySync == YES"
        + " AND (lastMessage.messageText.groupSenderPubkey != nil OR lastMessage.messageFile.groupSenderPubkey != nil)))"
        + " AND NOT (isGroup == YES AND groupNotificationsSilent == YES)")

    init(theme: Theme, submanagerObjects: OCTSubmanagerObjects) {
        self.theme = theme
        self.notificationWindow = NotificationWindow()

        self.submanagerObjects = submanagerObjects
        self.avatarManager = AvatarManager(theme: theme)

        self.chats = submanagerObjects.chats(predicate: NotificationCoordinator.unreadChatsPredicate)
        self.requests = submanagerObjects.friendRequests()

        super.init()

        addNotificationBlocks()

        NotificationCenter.default.addObserver(self, selector: #selector(NotificationCoordinator.applicationDidBecomeActive), name: NSNotification.Name.UIApplicationDidBecomeActive, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(NotificationCoordinator.messageReactionReceived(_:)), name: .octMessageReaction, object: nil)
    }

    deinit {
        NotificationCenter.default.removeObserver(self)

        messagesToken?.invalidate()
        chatsToken?.invalidate()
        requestsToken?.invalidate()
    }

    /// The connection pill is retired (redesign stage 3, E4): our own node is told by the quiet line
    /// under the name on the Air screen and by the hint under the message field, without colour
    /// flashes. The pill never showed anyway (its window had no scene), so nothing visible goes away.
    func toggleConnectingView(show: Bool, animated: Bool) {
    }

    func setConnectionState(_ state: NotificationWindow.ConnectionPillState, animated: Bool) {
    }

    /**
        Stops showing notifications for given chat.
        Also removes all related to that chat notifications from queue.
     */
    func banNotificationsForChat(_ chat: OCTChat) {
        bannedChatIdentifiers.insert(chat.uniqueIdentifier)

        notificationQueue = notificationQueue.filter {
            switch $0 {
                case .newMessage(let messageAbstract):
                    return messageAbstract.chatUniqueIdentifier != chat.uniqueIdentifier
                case .reaction(let notice):
                    return notice.message.chatUniqueIdentifier != chat.uniqueIdentifier
                case .friendRequest, .groupInvite:
                    return true
            }
        }
        
        LNNotificationCenter.default().clearPendingNotifications(forApplicationIdentifier: chat.uniqueIdentifier);
    }

    /**
        Unban notifications for given chat (if they were banned before).
     */
    func unbanNotificationsForChat(_ chat: OCTChat) {
        bannedChatIdentifiers.remove(chat.uniqueIdentifier)
    }

    func handleLocalNotification(_ notification: UILocalNotification) {
        guard let userInfo = notification.userInfo as? [String: String] else {
            return
        }

        guard let action = NotificationAction(dictionary: userInfo) else {
            return
        }

        performAction(action)
    }

    /// An invitation to a group came from a contact (E22): it rings and comes as a band of its own, or as
    /// a system notification while the app is away; its card waits in Requests either way. The
    /// invitation of a muted contact waits there without a sound or a band.
    func notifyGroupInvite(_ invite: AirGroupInvite) {
        guard !AirLocalStore.shared.isMuted(publicKey: invite.inviterKey),
              !AirLocalStore.shared.isBlocked(publicKey: invite.inviterKey) else {
            return
        }
        // As for a request: what Realm flushes on the way back in was notified while away.
        if isPastEntryGrace {
            audioPlayer.playSound(.NewMessage)
        }
        enqueueNotification(.groupInvite(invite))
    }

    func showCallNotificationWithCaller(_ caller: String, userInfo: String) {
        let object = NotificationObject(
                title: caller,
                body: String(localized: "notification_is_calling"),
                action: .answerIncomingCall(userInfo: userInfo),
                soundName: "isotoxin_Ringtone.aac",
                icon: avatarManager.avatarFromString(caller, diameter: 44, type: .Call),
                tile: nil)

        // KHANDAQ (notification parity, 2026-11-20): it stands in for the wake banner of the caller alone.
        let call = submanagerObjects.object(withUniqueIdentifier: userInfo, for: .call) as? OCTCall
        showLocalNotificationObject(object, owner: call?.caller?.publicKey)
    }

    func registerInAppNotificationAppId(_ appId: String, icon: UIImage? = nil) {
        let iconToUse = icon ?? UIImage(named: "notification-app-icon") ?? UIImage()
        LNNotificationCenter.default().registerApplication(
            withIdentifier: appId,
            name: Bundle.main.infoDictionary?["CFBundleDisplayName"] as? String,
            icon: iconToUse,
            defaultSettings: LNNotificationAppSettings.default())
        inAppNotificationAppIdsRegistered[appId] = true
    }
}

extension NotificationCoordinator: CoordinatorProtocol {
    func startWithOptions(_ options: CoordinatorOptions?) {
        let settings = UIUserNotificationSettings(types: [.alert, .badge, .sound], categories: nil)

        let application = UIApplication.shared
        application.registerUserNotificationSettings(settings)
        application.cancelAllLocalNotifications()

        updateBadges()
    }
}

// MARK: Notifications
extension NotificationCoordinator {
    @objc func applicationDidBecomeActive() {
        lastBecameActiveDate = Date()
        UIApplication.shared.cancelAllLocalNotifications()
    }

    /// KHANDAQ (0.2.64): a reaction to one of our messages notifies as a message does: a band and a sound
    /// in another chat while the app is open, a notification while it is away. Muted chats and members,
    /// and the chat already open, stay quiet; what Realm flushes on the way back in was notified while
    /// away, by the push preview.
    @objc func messageReactionReceived(_ notification: Notification) {
        guard let info = notification.userInfo,
              let messageIdentifier = info[kOCTMessageReactionMessageUniqueIdentifierKey] as? String,
              let emoji = info[kOCTMessageReactionEmojiKey] as? String,
              let message = submanagerObjects.object(withUniqueIdentifier: messageIdentifier, for: .messageAbstract) as? OCTMessageAbstract,
              message.isOutgoing(),
              !areNotificationsMuted(for: message) else {
            return
        }
        if UIApplication.isActive && bannedChatIdentifiers.contains(message.chatUniqueIdentifier) {
            return
        }
        if let chat = submanagerObjects.object(withUniqueIdentifier: message.chatUniqueIdentifier, for: .chat) as? OCTChat,
           !chat.isGroup,
           let friend = chat.friends.lastObject() as? OCTFriend,
           AirLocalStore.shared.isBlocked(publicKey: friend.publicKey) {
            return
        }

        if UIApplication.isActive && isPastEntryGrace {
            audioPlayer.playSound(.NewMessage)
        }
        enqueueNotification(.reaction(ReactionNotice(message: message,
                                                     emoji: emoji,
                                                     reactorName: info[kOCTMessageReactionReactorNameKey] as? String,
                                                     eventKey: info[kOCTMessageReactionEventKeyKey] as? String)))
    }

    /// The name the notifications of a group give it: Khandaq Community by its own name, any other group by
    /// the name it was made with.
    static func groupName(of chat: OCTChat?) -> String {
        return KhandaqCommunity.displayName(for: chat) ?? chat?.groupName ?? String(localized: "group_chat_default_title")
    }

    /// KHANDAQ (notification parity, 2026-11-20): which inserted row of the messages, newest first, a change notifies.
    /// Realm hands the rows written together over in one change, and the newest alone was looked at: a muted
    /// Community message, a synced row, a notice or our own message on top kept the message under it, a 1:1 one too,
    /// from notifying. Now it is the newest that `notifies` passes, of the rows inserted above everything stored
    /// before (an insert below a stored row, a synced one with its old date, is not new, as before); one at most, as
    /// before, so a change never sets off a run of bands.
    static func indexToNotify(insertions: [Int], notifies: (Int) -> Bool) -> Int? {
        var expected = 0
        for index in insertions.sorted() {
            guard index == expected else {
                break
            }
            if notifies(index) {
                return index
            }
            expected += 1
        }
        return nil
    }

    /// KHANDAQ (0.2.69, group message wakes): the event a group message wake names in `khq_ev`: a text by its message
    /// id and its author's key in the group, a file by its 32-byte hash. None for a private message or a row from
    /// history sync, which no wake names, nor for a text whose author key the row did not keep.
    static func groupEventKey(of message: OCTMessageAbstract) -> String? {
        guard !message.groupPrivateMessage, !message.groupHistorySync, !message.groupSystemMessage else {
            return nil
        }
        if let text = message.messageText {
            return OCTPushPreview.groupTextEventKey(withMessageId: UInt32(bitPattern: text.messageId),
                                                    senderPeerKeyHex: AirGroupTranscript.authorKey(of: message))
        }
        return OCTPushPreview.groupFileEventKey(withMsgIdHex: message.messageFile?.groupMsgIdHashHex)
    }

    /// KHANDAQ (0.2.69, group message wakes): how many characters of a text the `b` of a preview keeps
    /// (+[OCTPushPreview plaintextWithFields:]).
    static let previewTextCharacters: UInt = 140

    /// KHANDAQ (0.2.69, group message wakes): the marks a push of a group text that had to wait may carry in `khq_tm`,
    /// by its author's key in the group and its first 140 characters, as the chat shows them and as they came, since
    /// the sender may have put either into its preview; cut as the sender cuts them. None where groupEventKey(of:)
    /// gives none for what it is, nor for a row that kept no author key.
    static func groupTextMarks(of message: OCTMessageAbstract) -> [String] {
        guard !message.groupPrivateMessage, !message.groupHistorySync, !message.groupSystemMessage,
              let raw = message.messageText?.text else {
            return []
        }
        let author = AirGroupTranscript.authorKey(of: message)
        var marks = [String]()
        for said in [OCTPushPreview.previewText(fromMessageText: raw), raw] {
            let shown = OCTPushPreview.prefix(of: said, characters: previewTextCharacters)
            if let mark = PushPreviewCrypto.textMark(senderKey: author, text: shown), !marks.contains(mark) {
                marks.append(mark)
            }
        }
        return marks
    }
}

private extension NotificationCoordinator {
    func addNotificationBlocks() {
        let messages = submanagerObjects.messages().sortedResultsUsingProperty("dateInterval", ascending: false)
        messagesToken = messages.addNotificationBlock { [weak self] change in
            guard let self = self else {
                return
            }

            switch change {
                case .initial:
                    break
                case .update(let messages, _, let insertions, _):
                    guard let messages = messages else {
                        break
                    }
                    // KHANDAQ (notification parity, 2026-11-20): not only the newest row of the change (indexToNotify).
                    let picked = NotificationCoordinator.indexToNotify(insertions: insertions) { row in
                        return messages.object(at: row).map { self.shouldEnqueueMessage($0) } ?? false
                    }
                    if let row = picked, let message = messages.object(at: row) {
                        self.playSoundForMessageIfNeeded(message)
                        self.enqueueNotification(.newMessage(message))
                    }
                case .error(let error):
                    os_log("NotificationCoordinator:messages:realm:error:%{public}@", String(describing: error))
            }
        }

        chatsToken = chats.addNotificationBlock { [weak self] change in
            guard let self = self else {
                return
            }

            switch change {
                case .initial:
                    break
                case .update:
                    self.updateBadges()
                case .error(let error):
                    os_log("NotificationCoordinator:chats:realm:error:%{public}@", String(describing: error))
            }
        }

        requestsToken = requests.addNotificationBlock { [weak self] change in
            guard let self = self else {
                return
            }

            switch change {
                case .initial:
                    break
                case .update(let requests, _, let insertions, _):
                    guard let requests = requests else {
                        break
                    }
                    for index in insertions {
                        let request = requests[index]
                        // KHANDAQ (redesign stage 3): a blocked key neither rings nor shows.
                        guard !AirLocalStore.shared.isBlocked(publicKey: request.publicKey) else {
                            continue
                        }

                        // KHANDAQ (#H3 parity): don't ring for friend-requests flushed from the Realm backlog
                        // during the first 3s after the app became active (was ringing "on entry").
                        if self.isPastEntryGrace {
                            self.audioPlayer.playSound(.NewMessage)
                        }
                        self.enqueueNotification(.friendRequest(request))
                    }
                    self.updateBadges()
                case .error(let error):
                    os_log("NotificationCoordinator:requests:realm:error:%{public}@", String(describing: error))
            }
        }
    }

    func playSoundForMessageIfNeeded(_ message: OCTMessageAbstract) {
        // KHANDAQ: incoming private (in-group direct) messages DO alert now: they are addressed
        // personally to the recipient, so they should be surfaced, not silently filed in the per-peer
        // thread. isOutgoing() still suppresses our own sent private messages.
        if message.isOutgoing() || message.groupHistorySync || message.groupSystemMessage || areNotificationsMuted(for: message) {
            return
        }

        guard arrivedJustNow(message) else {
            return
        }
        // Don't ring for the chat that's already open in the foreground (mirror shouldEnqueueMessage).
        if UIApplication.isActive && bannedChatIdentifiers.contains(message.chatUniqueIdentifier) {
            return
        }

        if message.messageText != nil || message.messageFile != nil {
            audioPlayer.playSound(.NewMessage)
        }
    }

    var isPastEntryGrace: Bool {
        return NotificationFreshness.isPastEntryGrace(becameActive: lastBecameActiveDate, now: Date())
    }

    func arrivedJustNow(_ message: OCTMessageAbstract) -> Bool {
        return NotificationFreshness.arrivedJustNow(sentAt: message.tssent, becameActive: lastBecameActiveDate, now: Date())
    }

    func shouldEnqueueMessage(_ message: OCTMessageAbstract) -> Bool {
        // KHANDAQ: private (in-group direct) messages are notified like any incoming message so the
        // recipient sees they were addressed personally (Android parity). Own sends stay filtered.
        if message.isOutgoing() || message.groupHistorySync || message.groupSystemMessage || areNotificationsMuted(for: message) {
            return false
        }

        if UIApplication.isActive && bannedChatIdentifiers.contains(message.chatUniqueIdentifier) {
            return false
        }

        if message.messageText != nil || message.messageFile != nil {
            return true
        }

        return false
    }

    func enqueueNotification(_ notification: NotificationType) {
        notificationQueue.append(notification)

        showNextNotification()
    }

    func showNextNotification() {
        if notificationQueue.isEmpty {
            return
        }

        let notification = notificationQueue.removeFirst()
        let object = notificationObjectFromNotification(notification)

        if UIApplication.isActive {
            switch notification {
                case .newMessage(let message):
                    // E22: a message from another chat comes as a band under the safe area (sender,
                    // then the text when previews are on); a tap opens the chat. The open chat sends
                    // none (shouldEnqueueMessage), and the sound was played already. The backlog and
                    // late deliveries were notified before and come without a band, as without a sound.
                    if arrivedJustNow(message) {
                        showInAppNotificationObject(object, chatUniqueIdentifier: message.chatUniqueIdentifier)
                    }
                    else {
                        showNextNotification()
                    }
                case .reaction(let notice):
                    if isPastEntryGrace {
                        showInAppNotificationObject(object, chatUniqueIdentifier: notice.message.chatUniqueIdentifier)
                    }
                    else {
                        showNextNotification()
                    }
                case .friendRequest, .groupInvite:
                    if isPastEntryGrace {
                        showInAppNotificationObject(object, chatUniqueIdentifier: nil)
                    }
                    else {
                        showNextNotification()
                    }
            }
        }
        else {
            showLocalNotificationObject(object, eventKey: eventKey(of: notification),
                                        textMarks: textMarks(of: notification), owner: bannerOwner(of: notification))
        }
    }

    /// The event a push preview names in `khq_ev` (docs/PUSH_PREVIEWS.md): a text by its msgV3 hash, a file
    /// by its Tox file id, a reaction by its anchor and emoji, a group message as groupEventKey(of:) says.
    func eventKey(of notification: NotificationType) -> String? {
        switch notification {
            case .newMessage(let message):
                let chat = submanagerObjects.object(withUniqueIdentifier: message.chatUniqueIdentifier, for: .chat) as? OCTChat
                // KHANDAQ (0.2.69, group message wakes): the sender's device woke us with a preview of it.
                if chat?.isGroup == true {
                    return NotificationCoordinator.groupEventKey(of: message)
                }
                guard chat?.isGroup == false else {
                    return nil
                }
                if let hash = message.messageText?.msgv3HashHex, hash.count == 64 {
                    return "t:" + hash.uppercased()
                }
                if let fileId = message.messageFile?.fileIdHex, fileId.count == 64 {
                    return "f:" + fileId.uppercased()
                }
                return nil
            case .reaction(let notice):
                return notice.eventKey
            case .friendRequest, .groupInvite:
                return nil
        }
    }

    /// KHANDAQ (notification parity, 2026-11-20): whom a notification is about, as a delivered banner names it
    /// (WakeBanners.owner): a group by its chat id, a contact by their public key.
    func bannerOwner(of notification: NotificationType) -> String? {
        switch notification {
            case .newMessage(let message):
                return bannerOwner(ofChat: message.chatUniqueIdentifier)
            case .reaction(let notice):
                return bannerOwner(ofChat: notice.message.chatUniqueIdentifier)
            case .friendRequest(let request):
                return request.publicKey
            case .groupInvite(let invite):
                return invite.inviterKey
        }
    }

    func bannerOwner(ofChat chatUniqueIdentifier: String) -> String? {
        guard let chat = submanagerObjects.object(withUniqueIdentifier: chatUniqueIdentifier, for: .chat) as? OCTChat else {
            return nil
        }
        if chat.isGroup {
            return chat.groupChatIdHex
        }
        return (chat.friends.lastObject() as? OCTFriend)?.publicKey
    }

    /// KHANDAQ (0.2.69, group message wakes): the marks a push names a group text by in `khq_tm` when the text had to
    /// wait on its sender's device (groupTextMarks(of:)); none for anything else.
    func textMarks(of notification: NotificationType) -> [String] {
        guard case .newMessage(let message) = notification,
              let chat = submanagerObjects.object(withUniqueIdentifier: message.chatUniqueIdentifier, for: .chat) as? OCTChat,
              chat.isGroup else {
            return []
        }
        return NotificationCoordinator.groupTextMarks(of: message)
    }

    func showInAppNotificationObject(_ object: NotificationObject, chatUniqueIdentifier: String?) {
        // KHANDAQ: custom safe-area banner instead of LNNotificationsUI (which rendered under the
        // Dynamic Island / notch and got clipped). Presented in the existing NotificationWindow.
        notificationWindow.showBanner(title: object.title, body: object.body, tile: object.tile, photo: object.icon,
                                      hint: object.action.bandHint) { [weak self] in
            self?.performAction(object.action)
        }

        showNextNotification()
    }

    /// `owner`: whom it is about (bannerOwner), the generic wake banners of whom it stands in for.
    func showLocalNotificationObject(_ object: NotificationObject, eventKey: String? = nil, textMarks: [String] = [],
                                     owner: String?) {
        let body = "\(object.title): \(object.body)"

        // KHANDAQ (#G6): drop a duplicate of the SAME contentful notification fired within a short
        // window (Realm observer can re-emit) so "ISA: ..." doesn't show twice.
        let now = Date().timeIntervalSince1970
        if body == lastLocalNotificationBody, now - lastLocalNotificationTime < 4.0 {
            showNextNotification()
            return
        }
        lastLocalNotificationBody = body
        lastLocalNotificationTime = now

        guard eventKey != nil || !textMarks.isEmpty else {
            presentLocalNotification(object, body: body, owner: owner)
            showNextNotification()
            return
        }

        // KHANDAQ (0.2.64): the push extension may have shown this very event already, from the preview
        // the sender's wake carried; its notification names the event in `khq_ev`, and this one would only
        // repeat it.
        // KHANDAQ (0.2.69, group message wakes): or, for a group text that had to wait, by what it says in
        // `khq_tm`; that notification then stands for this text and no other (PushTextMarkMatches).
        UNUserNotificationCenter.current().getDeliveredNotifications { [weak self] delivered in
            let shown = eventKey.map { key in
                delivered.contains { ($0.request.content.userInfo["khq_ev"] as? String) == key }
            } ?? false
            let marked: [(identifier: String, mark: String?)] = textMarks.isEmpty ? [] : delivered.map {
                (identifier: $0.request.identifier, mark: $0.request.content.userInfo["khq_tm"] as? String)
            }
            DispatchQueue.main.async {
                guard let self = self else {
                    return
                }
                // On the main queue, so that two texts saying the same never both take one notification.
                if !shown && !self.pushTextMarkMatches.match(textMarks, in: marked) {
                    self.presentLocalNotification(object, body: body, owner: owner)
                }
            }
        }
        showNextNotification()
    }

    func presentLocalNotification(_ object: NotificationObject, body: String, owner: String?) {
        let local = UILocalNotification()
        local.alertBody = body
        local.userInfo = object.action.archive()
        local.soundName = object.soundName

        UIApplication.shared.presentLocalNotificationNow(local)

        // KHANDAQ (#G6): the push-extension already delivered a GENERIC wake banner ("Khandaq" /
        // "New activity" / empty) for this same message; clear those so the contentful one we just
        // posted isn't shadowed by a "New message" duplicate.
        removeGenericPushNotifications(about: owner)
    }

    /// KHANDAQ (#G6): remove delivered generic wake-push banners from the notification-service
    /// extension, so they don't duplicate the contentful Tox-driven local notification.
    /// KHANDAQ (notification parity, 2026-11-20): only the banners of `owner` and those that name nobody
    /// (WakeBanners.isReplaced).
    private func removeGenericPushNotifications(about owner: String?) {
        let center = UNUserNotificationCenter.current()
        center.getDeliveredNotifications { delivered in
            let genericIds = delivered.filter { n in
                return WakeBanners.isReplaced(n.request.content, byNotificationAbout: owner)
            }.map { $0.request.identifier }
            if !genericIds.isEmpty {
                center.removeDeliveredNotifications(withIdentifiers: genericIds)
            }
        }
    }

    func notificationObjectFromNotification(_ notification: NotificationType) -> NotificationObject {
        switch notification {
            case .friendRequest(let request):
                return notificationObjectFromRequest(request)
            case .newMessage(let message):
                return notificationObjectFromMessage(message)
            case .groupInvite(let invite):
                return notificationObjectFromGroupInvite(invite)
            case .reaction(let notice):
                return notificationObjectFromReaction(notice)
        }
    }

    /// The chat's name over who reacted with what to which message: "Amina", "Reacted ❤️ to "See you at
    /// six""; in a group, "Book club", "Amina: Reacted ❤️ to your photo". Worded as the push preview is.
    func notificationObjectFromReaction(_ notice: ReactionNotice) -> NotificationObject {
        let message = notice.message
        let language = AppLanguage.current
        let chat = submanagerObjects.object(withUniqueIdentifier: message.chatUniqueIdentifier, for: .chat) as? OCTChat
        let action = NotificationAction.openChat(chatUniqueIdentifier: message.chatUniqueIdentifier)

        let title: String
        var icon: UIImage?
        let tile: AirTileView.Style?
        if chat?.isGroup == true {
            let groupName = NotificationCoordinator.groupName(of: chat)
            title = groupName
            tile = KhandaqCommunity.isCommunity(chat)
                ? .community
                : .group(initials: AirInitials.from(groupName), tone: AirTone.index(for: message.chatUniqueIdentifier))
        }
        else if let friend = chat?.friends.lastObject() as? OCTFriend {
            title = friend.nickname
            icon = friend.avatarData.flatMap { UIImage(data: $0) }
            tile = .person(initials: AirInitials.from(friend.nickname), tone: AirTone.index(for: friend.publicKey))
        }
        else {
            title = ""
            tile = nil
        }

        let body: String
        if userDefaults.showNotificationPreview {
            var reactedKind: PushPreview.Kind?
            var reactedText: String?
            if let file = message.messageFile {
                reactedKind = PushPreview.Kind(rawValue: OCTPushPreview.kind(forFileName: file.fileName))
            }
            else {
                reactedKind = .text
                let text = GroupMentionHelper.notificationPreviewText(message.messageText?.text)
                reactedText = text.isEmpty ? nil : String(text.prefix(80))
            }
            let line = PushPreviewText.reactionBody(emoji: notice.emoji, reactedKind: reactedKind, text: reactedText, language: language)
            if chat?.isGroup == true, let reactor = notice.reactorName, !reactor.isEmpty {
                body = reactor + ": " + line
            }
            else {
                body = line
            }
        }
        else {
            body = PushPreviewText.strings(language).newReaction
        }

        return NotificationObject(title: title, body: body, action: action, soundName: "isotoxin_NewMessage.aac", icon: icon, tile: tile)
    }

    /// The contact who invites, then what they invite to: "Amina Safina", "Invites you to the group
    /// Book club". The group's name is not a message, so it shows with the previews off too, as a
    /// group's name does in the title of its messages.
    func notificationObjectFromGroupInvite(_ invite: AirGroupInvite) -> NotificationObject {
        let friends = submanagerObjects.friends(predicate: NSPredicate(format: "publicKey ==[c] %@", invite.inviterKey))
        let friend: OCTFriend? = friends.count > 0 ? friends[0] : nil
        let name = friend.map { $0.nickname.isEmpty ? $0.publicKey : $0.nickname } ?? invite.inviterKey
        let groupName = KhandaqCommunity.displayName(forInviteData: invite.data) ?? invite.groupName
        let body = groupName.isEmpty
            ? String(localized: "notification_group_invite")
            : String(localized: "notification_group_invite_format", groupName)
        let tile = AirTileView.Style.person(initials: AirInitials.from(name), tone: AirTone.index(for: friend?.publicKey ?? invite.inviterKey))

        return NotificationObject(title: name,
                                  body: body,
                                  action: .openGroupInvite(inviteIdentifier: invite.id),
                                  soundName: "isotoxin_NewMessage.aac",
                                  icon: friend?.avatarData.flatMap { UIImage(data: $0) },
                                  tile: tile)
    }

    func notificationObjectFromRequest(_ request: OCTFriendRequest) -> NotificationObject {
        let title = String(localized: "notification_incoming_contact_request")
        var body = request.message ?? ""
        // A member who asked from a group: their name there over the group it came from.
        if let offer = groupContactOffer?(request), !offer.memberName.isEmpty {
            body = offer.memberName + "\n" + body
        }
        let action = NotificationAction.openRequest(requestUniqueIdentifier: request.uniqueIdentifier)

        return NotificationObject(title: title, body: body, action: action, soundName: "isotoxin_NewMessage.aac", icon: nil, tile: .pending)
    }

    func notificationObjectFromMessage(_ message: OCTMessageAbstract) -> NotificationObject {
        let title: String
        var icon: UIImage?
        var tile: AirTileView.Style?
        let chat = submanagerObjects.object(withUniqueIdentifier: message.chatUniqueIdentifier, for: .chat) as? OCTChat
        let isGroup = chat?.isGroup ?? false
        let isGroupPrivate = isGroup && message.groupPrivateMessage
        let groupName = NotificationCoordinator.groupName(of: chat)
        // The band shows the tile the Air screen shows for this chat (E22).
        let groupTile: AirTileView.Style = KhandaqCommunity.isCommunity(chat)
            ? .community
            : .group(initials: AirInitials.from(groupName), tone: AirTone.index(for: message.chatUniqueIdentifier))

        // Sender display name for a group peer (used both for the private-message title and the
        // regular in-group peer prefix).
        let groupPeerName: String = {
            if let peerName = message.messageText?.groupPeerName, !peerName.isEmpty {
                return peerName
            }
            if message.groupSenderPeerId > 0 {
                return "Peer \(message.groupSenderPeerId)"
            }
            return ""
        }()

        if isGroupPrivate {
            // A direct (private) message sent to you inside a group: title it so it's unmistakably
            // addressed to you personally, not a normal group post (Android parity).
            let sender = groupPeerName.isEmpty ? groupName : groupPeerName
            title = String(localized: "group_private_message_notification_title_format", sender)
            tile = groupTile
        }
        else if isGroup {
            title = groupName
            tile = groupTile
        }
        else if let friend = submanagerObjects.object(withUniqueIdentifier: message.senderUniqueIdentifier, for: .friend) as? OCTFriend {
            title = friend.nickname
            icon = friend.avatarData.flatMap { UIImage(data: $0) }
            tile = .person(initials: AirInitials.from(friend.nickname), tone: AirTone.index(for: friend.publicKey))
        }
        else {
            title = ""
        }

        var body: String = ""
        let action = NotificationAction.openChat(chatUniqueIdentifier: message.chatUniqueIdentifier)
        let peerPrefix: String = {
            // Private messages already name the sender in the title, so no in-body peer prefix.
            guard isGroup, !isGroupPrivate, !groupPeerName.isEmpty else {
                return ""
            }

            return "\(groupPeerName): "
        }()

        if let messageText = message.messageText {
            let defaultString = String(localized: "notification_new_message")

            if userDefaults.showNotificationPreview {
                let preview = GroupMentionHelper.notificationPreviewText(messageText.text)
                body = peerPrefix + (preview.isEmpty ? defaultString : preview)
            }
            else {
                body = defaultString
            }
        }
        else if let messageFile = message.messageFile {
            let defaultString = String(localized: "notification_incoming_file")

            if userDefaults.showNotificationPreview {
                // KHANDAQ (0.2.64): a voice message, a video and a photo are named for what they are, as the
                // push preview names them; any other file by its name.
                let kindName = OCTPushPreview.kind(forFileName: messageFile.fileName)
                let kind = PushPreview.Kind(rawValue: kindName) ?? .file
                body = peerPrefix + PushPreviewText.messageBody(kind: kind, text: messageFile.fileName, language: AppLanguage.current)
            }
            else {
                body = defaultString
            }
        }

        return NotificationObject(title: title, body: body, action: action, soundName: "isotoxin_NewMessage.aac", icon: icon, tile: tile)
    }

    func performAction(_ action: NotificationAction) {
        switch action {
            case .openChat(let identifier):
                guard let chat = submanagerObjects.object(withUniqueIdentifier: identifier, for: .chat) as? OCTChat else {
                    return
                }

                delegate?.notificationCoordinator(self, showChat: chat)
                banNotificationsForChat(chat)
            case .openRequest(let identifier):
                guard let request = submanagerObjects.object(withUniqueIdentifier: identifier, for: .friendRequest) as? OCTFriendRequest else {
                    return
                }

                delegate?.notificationCoordinatorShowFriendRequest(self, showRequest: request)
            case .openGroupInvite:
                // The invitation may have been answered meanwhile; Requests shows what still waits.
                delegate?.notificationCoordinatorShowGroupInvites(self)
            case .answerIncomingCall(let userInfo):
                delegate?.notificationCoordinatorAnswerIncomingCall(self, userInfo: userInfo)
        }
    }

    func updateBadges() {
        let chatsCount = chats.count
        var requestsCount = 0
        for index in 0..<requests.count where !AirLocalStore.shared.isBlocked(publicKey: requests[index].publicKey) {
            requestsCount += 1
        }

        delegate?.notificationCoordinator(self, updateChatsBadge: chatsCount)
        delegate?.notificationCoordinator(self, updateFriendsBadge: requestsCount)

        let badgeNumber = chatsCount + requestsCount
        if badgeNumber != appIconBadgeNumber {
            appIconBadgeNumber = badgeNumber
            UIApplication.shared.applicationIconBadgeNumber = badgeNumber
        }
    }

    func areNotificationsMuted(for message: OCTMessageAbstract) -> Bool {
        guard let chat = submanagerObjects.object(withUniqueIdentifier: message.chatUniqueIdentifier, for: .chat) as? OCTChat else {
            return false
        }

        // KHANDAQ (redesign stage 2): "Mute" in the contact dossier, kept on this device only.
        guard chat.isGroup else {
            guard let friend = chat.friends.lastObject() as? OCTFriend else {
                return false
            }
            return AirLocalStore.shared.isMuted(publicKey: friend.publicKey)
        }

        // KHANDAQ (26.11.2026, DESIGN-group-contacts-private-mentions.md): a message that mentions you notifies in a
        // group set quiet too, except in Khandaq Community, where everyone can write to everyone.
        if chat.groupNotificationsSilent && !mentionsSelf(message, in: chat) {
            return true
        }

        if message.groupSenderPeerId > 0 {
            let peerKey = "\(chat.uniqueIdentifier):\(message.groupSenderPeerId)"

            if let peer = submanagerObjects.object(withUniqueIdentifier: peerKey, for: .groupPeer) as? OCTGroupPeer,
               peer.peerNotificationsSilent {
                return true
            }
        }

        return false
    }

    func mentionsSelf(_ message: OCTMessageAbstract, in chat: OCTChat) -> Bool {
        guard !message.isOutgoing(), !KhandaqCommunity.isCommunity(chat), let text = message.messageText?.text,
              text.contains(GroupMentionHelper.mentionBlockPrefix) else {
            return false
        }
        let mentions = GroupMentionHelper.parse(text).mentions
        guard !mentions.isEmpty, let identity = groupSelfIdentity?(chat) else {
            return false
        }
        return AirGroupTranscript.mentionsMe(mentions, selfKey: identity.key, selfName: identity.name)
    }

    // func chatsBadge() -> Int {
    //     // TODO update to new Realm and filter unread chats with predicate "lastMessage.dateInterval > lastReadDateInterval"
    //     var badge = 0

    //     for index in 0..<chats.count {
    //         guard let chat = chats[index] as? OCTChat else {
    //             continue
    //         }

    //         if chat.hasUnreadMessages() {
    //             badge += 1
    //         }
    //     }

    //     return badge
    // }
}
