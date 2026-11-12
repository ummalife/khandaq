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

class NotificationCoordinator: NSObject {
    weak var delegate: NotificationCoordinatorDelegate?

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

    // KHANDAQ (#H3): when the app last became active. On foreground Realm flushes the backlog of
    // messages that arrived while away as a single insertion batch; those must not ring on entry
    // (the user was already push-notified). We suppress the new-message sound briefly after this.
    fileprivate var lastBecameActiveDate: Date = .distantPast

    // KHANDAQ: the number last put on the app icon. Every change to an unread chat lands in
    // updateBadges(), and setting the icon to the number it already shows is still a request to
    // SpringBoard each time: a simulator set the same number up to 28 times in a quarter of a second,
    // in bursts every 30 s or so.
    fileprivate var appIconBadgeNumber: Int?

    init(theme: Theme, submanagerObjects: OCTSubmanagerObjects) {
        self.theme = theme
        self.notificationWindow = NotificationWindow()

        self.submanagerObjects = submanagerObjects
        self.avatarManager = AvatarManager(theme: theme)

        // KHANDAQ (#41): a chat is "unread" for the tab/app badge only when its last message is a
        // genuine INCOMING, non-system message newer than lastRead. The old date-only predicate also
        // counted our own outgoing messages (badge stuck after you send) and "X joined" system notes.
        // For groups both incoming & outgoing have senderUniqueIdentifier == nil, so incoming is
        // distinguished by groupSenderPeerId > 0 (mirrors OCTMessageAbstract.isOutgoing).
        let predicate = NSPredicate(format:
            "lastMessage.dateInterval > lastReadDateInterval"
            + " AND lastMessage.groupSystemMessage == NO"
            + " AND (lastMessage.groupSenderPeerId > 0 OR lastMessage.senderUniqueIdentifier != nil)")
        self.chats = submanagerObjects.chats(predicate: predicate)
        self.requests = submanagerObjects.friendRequests()

        super.init()

        addNotificationBlocks()

        NotificationCenter.default.addObserver(self, selector: #selector(NotificationCoordinator.applicationDidBecomeActive), name: NSNotification.Name.UIApplicationDidBecomeActive, object: nil)
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

        showLocalNotificationObject(object)
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
                    if insertions.contains(0) {
                        let message = messages[0]

                        self.playSoundForMessageIfNeeded(message)

                        if self.shouldEnqueueMessage(message) {
                            self.enqueueNotification(.newMessage(message))
                        }
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
            showLocalNotificationObject(object)
        }
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

    func showLocalNotificationObject(_ object: NotificationObject) {
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

        let local = UILocalNotification()
        local.alertBody = body
        local.userInfo = object.action.archive()
        local.soundName = object.soundName

        UIApplication.shared.presentLocalNotificationNow(local)

        // KHANDAQ (#G6): the push-extension already delivered a GENERIC wake banner ("Khandaq" /
        // "New activity" / empty) for this same message; clear those so the contentful one we just
        // posted isn't shadowed by a "New message" duplicate.
        removeGenericPushNotifications()

        showNextNotification()
    }

    /// KHANDAQ (#G6): remove delivered generic wake-push banners from the notification-service
    /// extension, so they don't duplicate the contentful Tox-driven local notification.
    private func removeGenericPushNotifications() {
        let center = UNUserNotificationCenter.current()
        center.getDeliveredNotifications { delivered in
            let genericIds = delivered.filter { n in
                let title = n.request.content.title
                let text = n.request.content.body
                return title == "Khandaq" || text == "New activity" || text == "New message" || text.isEmpty
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
        }
    }

    /// The contact who invites, then what they invite to: "Amina Safina", "Invites you to the group
    /// Book club". The group's name is not a message, so it shows with the previews off too, as a
    /// group's name does in the title of its messages.
    func notificationObjectFromGroupInvite(_ invite: AirGroupInvite) -> NotificationObject {
        let friends = submanagerObjects.friends(predicate: NSPredicate(format: "publicKey ==[c] %@", invite.inviterKey))
        let friend: OCTFriend? = friends.count > 0 ? friends[0] : nil
        let name = friend.map { $0.nickname.isEmpty ? $0.publicKey : $0.nickname } ?? invite.inviterKey
        let body = invite.groupName.isEmpty
            ? String(localized: "notification_group_invite")
            : String(localized: "notification_group_invite_format", invite.groupName)
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
        let body = request.message ?? ""
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
        // The band shows the tile the Air screen shows for this chat (E22).
        let groupTile = AirTileView.Style.group(initials: AirInitials.from(chat?.groupName ?? String(localized: "group_chat_default_title")),
                                                tone: AirTone.index(for: message.chatUniqueIdentifier))

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
            let sender = groupPeerName.isEmpty
                ? (chat?.groupName ?? String(localized: "group_chat_default_title"))
                : groupPeerName
            title = String(localized: "group_private_message_notification_title_format", sender)
            tile = groupTile
        }
        else if isGroup {
            title = chat?.groupName ?? String(localized: "group_chat_default_title")
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
                body = peerPrefix + (messageFile.fileName ?? defaultString)
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

        if chat.groupNotificationsSilent {
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
