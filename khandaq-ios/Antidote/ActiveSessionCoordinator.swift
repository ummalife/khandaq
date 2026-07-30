// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit

protocol ActiveSessionCoordinatorDelegate: class {
    func activeSessionCoordinatorDidLogout(_ coordinator: ActiveSessionCoordinator, importToxProfileFromURL: URL?)
    func activeSessionCoordinatorDeleteProfile(_ coordinator: ActiveSessionCoordinator)
    func activeSessionCoordinatorRecreateCoordinatorsStack(_ coordinator: ActiveSessionCoordinator, options: CoordinatorOptions)
    func activeSessionCoordinatorDidStartCall(_ coordinator: ActiveSessionCoordinator)
    func activeSessionCoordinatorDidFinishCall(_ coordinator: ActiveSessionCoordinator)
}

private struct Options {
    static let ToShowKey = "ToShowKey"
    static let StoredOptions = "StoredOptions"
    static let ThemeReloadOnlyKey = "ThemeReloadOnly"
    static let SelectedTabIndexKey = "SelectedTabIndex"

    enum Coordinator {
        case none
        case settings
    }
}

private struct IpadObjects {
    let splitController: UISplitViewController

    let primaryController: PrimaryIpadController
    let chatsCoordinator: ChatsTabCoordinator

    let keyboardObserver = KeyboardObserver()
}

private struct IphoneObjects {
    enum TabCoordinator: Int {
        case friends = 0
        case chats = 1
        case settings = 2
        case profile = 3

        static func allValues() -> [TabCoordinator]{
            return [friends, chats, settings, profile]
        }
    }

    let chatsCoordinator: ChatsTabCoordinator

    let tabBarController: TabBarController

    let friendsTabBarItem: UITabBarItem
    let chatsTabBarItem: UITabBarItem
    let profileTabBarItem: UITabBarItem
}

class ActiveSessionCoordinator: NSObject {
    weak var delegate: ActiveSessionCoordinatorDelegate?

    fileprivate let theme: Theme
    fileprivate let window: UIWindow

    // Tox manager is stored here
    var toxManager: OCTManager!

    fileprivate let friendsCoordinator: FriendsTabCoordinator
    fileprivate let settingsCoordinator: SettingsTabCoordinator
    fileprivate let profileCoordinator: ProfileTabCoordinator

    fileprivate let notificationCoordinator: NotificationCoordinator
    fileprivate let automationCoordinator: AutomationCoordinator
    var callCoordinator: CallCoordinator!

    /**
        One of following properties will be non-empty, depending on running device.
     */
    fileprivate var iPhone: IphoneObjects!
    fileprivate var iPad: IpadObjects!

    /// KHANDAQ (#98): currently selected iPhone tab (nil on iPad) — preserved across a theme reload.
    var currentTabIndex: Int? {
        guard case .iPhone = InterfaceIdiom.current() else { return nil }
        return iPhone?.tabBarController.selectedIndex
    }

    fileprivate var isPresentingSharePicker = false
    fileprivate var pendingSharePayload: ShareInboxPayload?
    fileprivate var pendingOpenInFilePath: String?

    fileprivate let networkReachabilityMonitor = ToxNetworkReachabilityMonitor()
    fileprivate var qualityObserver: NSObjectProtocol?
    fileprivate var degradedNagTimer: Timer?

    init(theme: Theme, window: UIWindow, toxManager: OCTManager) {
        self.theme = theme
        self.window = window
        self.toxManager = toxManager

        self.friendsCoordinator = FriendsTabCoordinator(theme: theme, toxManager: toxManager)
        self.settingsCoordinator = SettingsTabCoordinator(theme: theme, submanagerObjects: toxManager.objects, toxManager: toxManager)
        self.profileCoordinator = ProfileTabCoordinator(theme: theme, toxManager: toxManager)
        self.notificationCoordinator = NotificationCoordinator(theme: theme, submanagerObjects: toxManager.objects)
        self.automationCoordinator = AutomationCoordinator(submanagerObjects: toxManager.objects, submanagerFiles: toxManager.files)

        super.init()

        settingsCoordinator.sessionCoordinator = self

        QaCommandHandler.consumePendingCommands(coordinator: self)
        QaCommandHandler.schedulePendingCommandRetry(coordinator: self)

        // order matters
        createDeviceSpecificObjects()
        createCallCoordinator()

        toxManager.user.delegate = self
        toxManager.groups.delegate = self

        toxManager.objects.setGroupShowSystemMessages(UserDefaultsManager().groupShowSystemMessages)

        // KHANDAQ: ensure the local-only "Saved Messages" chat exists so it's always reachable.
        _ = toxManager.objects.getOrCreateSavedMessagesChat()

        friendsCoordinator.delegate = self
        settingsCoordinator.delegate = self
        profileCoordinator.delegate = self
        notificationCoordinator.delegate = self

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(ActiveSessionCoordinator.networkRebootstrapCompleted),
            name: NSNotification.Name("kOCTNetworkRebootstrapCompletedNotification"),
            object: nil)

        NotificationCenter.default.addObserver(self, selector: #selector(ActiveSessionCoordinator.applicationWillTerminate), name: NSNotification.Name.UIApplicationWillTerminate, object: nil)

        MessageDeliveryWatchdog.shared.bind(toxManager: toxManager)
        MessageDeliveryWatchdog.shared.start()
        registerForReconnectBackoff()

        // Surface connection-quality changes so the user can watch their link stability: yellow nag while
        // weak, a one-shot green "stable" when it recovers.
        qualityObserver = NotificationCenter.default.addObserver(
            forName: .connectionQualityDidChange, object: nil, queue: .main) { [weak self] note in
            self?.handleConnectionQualityChange(note)
        }
    }

    deinit {
        MessageDeliveryWatchdog.shared.stop()
        NotificationCenter.default.removeObserver(self, name: .khandaqManualReconnectRequested, object: nil)
        networkReachabilityMonitor.stop()
        degradedNagTimer?.invalidate()
        if let qualityObserver = qualityObserver {
            NotificationCenter.default.removeObserver(qualityObserver)
        }
        NotificationCenter.default.removeObserver(self)
    }

    fileprivate func handleConnectionQualityChange(_ note: Notification) {
        guard let newRaw = note.userInfo?["level"] as? String,
              let new = ConnectionQualityLevel(rawValue: newRaw) else {
            return
        }
        let old = (note.userInfo?["old"] as? String).flatMap(ConnectionQualityLevel.init)

        switch new {
            case .weak, .medium:
                // Show the yellow warning now and keep nagging periodically while it stays weak.
                notificationCoordinator.setConnectionState(.degraded, animated: true)
                startDegradedNag()

            case .strong:
                stopDegradedNag()
                // Only announce recovery if we were actually degraded/offline before.
                if let old = old, old != .strong {
                    notificationCoordinator.setConnectionState(.stable, animated: true)
                }

            case .offline:
                // Red offline pill is driven by the tox connection-status path; just stop nagging.
                stopDegradedNag()
        }
    }

    fileprivate func startDegradedNag() {
        degradedNagTimer?.invalidate()
        degradedNagTimer = Timer.scheduledTimer(withTimeInterval: 30.0, repeats: true) { [weak self] _ in
            guard let self = self else {
                return
            }
            let level = ConnectionQualityMonitor.shared.level
            if level == .weak || level == .medium {
                self.notificationCoordinator.setConnectionState(.degraded, animated: true)
            } else {
                self.stopDegradedNag()
            }
        }
    }

    fileprivate func stopDegradedNag() {
        degradedNagTimer?.invalidate()
        degradedNagTimer = nil
    }

    fileprivate func handleNetworkPathChange() {
        NetworkDiagnosticsLog.log("network_path_change", detail: "reachability")
        ConnectionQualityMonitor.shared.onBootstrapStarted()
        ReconnectBackoffCoordinator.shared.scheduleReconnect(reason: .networkChange, urgent: true)
        toxManager.chats.broadcastOwnPushURLToConnectedFriends()
    }

    fileprivate func startNetworkMonitoringAndPushBinding() {
        KhandaqPushManager.shared.bind(toxManager: toxManager)

        networkReachabilityMonitor.start { [weak self] in
            self?.handleNetworkPathChange()
        }
    }

    @objc func networkRebootstrapCompleted() {
        let connected = toxManager.user.connectionStatus != .none
        ConnectionQualityMonitor.shared.onBootstrapFinished(connected: connected)
        NetworkDiagnosticsLog.log("rebootstrap_completed", detail: "connected=\(connected)")
        if connected {
            ReconnectBackoffCoordinator.shared.onConnectionRestored()
            toxManager.chats.tickDeliveryWatchdog()
            toxManager.groups.flushAllPendingGroupMessagesIfNeeded()
        } else {
            ReconnectBackoffCoordinator.shared.scheduleRetryIfStillOffline(reason: .offlinePeriodic)
        }
    }

    @objc func applicationWillTerminate() {
        shutdownForToxRestart()
    }

    func shutdownForToxRestart() {
        MessageDeliveryWatchdog.shared.stop()
        networkReachabilityMonitor.stop()
        KhandaqPushManager.shared.unbind()
        toxManager?.user.delegate = nil
        toxManager?.groups.delegate = nil
        callCoordinator = nil
    }

    func shutdownForThemeReload() {
        networkReachabilityMonitor.stop()
        toxManager?.user.delegate = nil
        toxManager?.groups.delegate = nil
        callCoordinator = nil
    }
}

extension ActiveSessionCoordinator: TopCoordinatorProtocol {
    func startWithOptions(_ options: CoordinatorOptions?) {
        switch InterfaceIdiom.current() {
            case .iPhone:
                window.rootViewController = iPhone.tabBarController
            case .iPad:
                primaryIpadControllerShowFriends(iPad.primaryController)
                window.rootViewController = iPad.splitController
        }

        var settingsOptions: CoordinatorOptions?

        let toShow = options?[Options.ToShowKey] as? Options.Coordinator ?? .none
        switch toShow {
            case .none:
                break
            case .settings:
                settingsOptions = options?[Options.StoredOptions] as? CoordinatorOptions
        }

        friendsCoordinator.startWithOptions(nil)
        switch InterfaceIdiom.current() {
            case .iPhone:
                iPhone.chatsCoordinator.startWithOptions(nil)
            case .iPad:
                break
        }
        settingsCoordinator.startWithOptions(settingsOptions)
        profileCoordinator.startWithOptions(nil)
        notificationCoordinator.startWithOptions(nil)
        automationCoordinator.startWithOptions(nil)
        callCoordinator.startWithOptions(nil)

        // Show the connecting pill immediately on launch — connectionStatusUpdate only fires on a
        // *change*, so the initial offline→connecting period would otherwise have no indicator.
        SelfConnectionTracker.update(isOnline: toxManager.user.connectionStatus != .none)
        if toxManager.user.connectionStatus == .none {
            notificationCoordinator.setConnectionState(
                networkReachabilityMonitor.isReachable ? .connecting : .offline, animated: false)
        }

        if case .iPhone = InterfaceIdiom.current() {
            // KHANDAQ (#98): on a theme reload keep whatever tab the user was on (the toggle lives in
            // Settings); only default to Chats on a genuine fresh session start.
            let isThemeReload = options?[Options.ThemeReloadOnlyKey] as? Bool ?? false
            let tabIndex = (isThemeReload ? options?[Options.SelectedTabIndexKey] as? Int : nil)
                ?? IphoneObjects.TabCoordinator.chats.rawValue
            iPhone.tabBarController.selectTab(at: tabIndex)
            iPhone.tabBarController.preloadChildTabViews()
            iPhone.tabBarController.view.setNeedsLayout()
            iPhone.tabBarController.view.layoutIfNeeded()
        }

        let themeReloadOnly = options?[Options.ThemeReloadOnlyKey] as? Bool ?? false
        if !themeReloadOnly {
            DispatchQueue.main.async { [weak self] in
                guard let self = self else {
                    return
                }
                self.toxManager.bootstrap.addPredefinedNodes()
                self.toxManager.bootstrap.bootstrap()
                self.startNetworkMonitoringAndPushBinding()
            }
        } else {
            startNetworkMonitoringAndPushBinding()
        }

        updateUserAvatar()
        updateUserName()

        switch toShow {
            case .none:
                break
            case .settings:
                showSettings()
        }

        if !themeReloadOnly {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) { [weak self] in
                self?.processPendingShareIfNeeded()
            }
        }

        LaunchRecovery.markLaunchCompleted()
    }
    func handleLocalNotification(_ notification: UILocalNotification) {
        notificationCoordinator.handleLocalNotification(notification)
    }

    func handleNotificationUserInfo(_ userInfo: [AnyHashable: Any]) {
        if let stringUserInfo = userInfo as? [String: String],
           let action = NotificationAction(dictionary: stringUserInfo) {
            switch action {
            case .openChat(let identifier):
                openChatWithUniqueIdentifier(identifier)
            case .openRequest(let identifier):
                if let request = toxManager.objects.object(withUniqueIdentifier: identifier, for: .friendRequest) as? OCTFriendRequest {
                    showFriendRequest(request)
                }
            case .answerIncomingCall(let userInfo):
                callCoordinator.answerIncomingCallWithUserInfo(userInfo)
            }
            return
        }

        if let sender = (userInfo["sender_pubkey"] as? String) ?? (userInfo["from"] as? String) {
            openChatWithSenderPublicKey(sender)
            return
        }

        if let chatId = userInfo["chat_id"] as? String {
            openChatWithUniqueIdentifier(chatId)
        }
    }

    func handleInboxURL(_ url: URL) {
        guard url.scheme?.lowercased() == "khandaq" else {
            handleNonKhandaqInboxURL(url)
            return
        }

        if url.host?.lowercased() == "share" {
            processPendingShareIfNeeded()
            return
        }

        if QaCommandHandler.handle(url: url, coordinator: self) {
            return
        }

        if let chatIdHex = GroupJoinHelper.normalizedGroupChatIdHex(from: url.absoluteString) {
            presentGroupJoin(chatIdHex: chatIdHex)
            return
        }

        NSLog("Khandaq: ignored deep link %@", url.absoluteString)
    }

    private func handleNonKhandaqInboxURL(_ url: URL) {
        let fileName = url.lastPathComponent
        let filePath = url.path
        let isToxFile = url.isToxURL()

        let style: UIAlertControllerStyle

        switch InterfaceIdiom.current() {
            case .iPhone:
                style = .actionSheet
            case .iPad:
                style = .alert
        }

        let alert = UIAlertController(title: nil, message: fileName, preferredStyle: style)

        if isToxFile {
            alert.addAction(UIAlertAction(title: String(localized: "create_profile"), style: .default) { [unowned self] _ -> Void in
                self.logout(importToxProfileFromURL: url)
            })
        }

        alert.addAction(UIAlertAction(title: String(localized: "file_send_to_contact"), style: .default) { [unowned self] _ -> Void in
            self.sendFileToChats(filePath, fileName: fileName)
        })

        alert.addAction(UIAlertAction(title: String(localized: "file_send_to_group"), style: .default) { [unowned self] _ -> Void in
            self.sendFileToGroup(filePath, fileName: fileName)
        })

        alert.addAction(UIAlertAction(title: String(localized: "alert_cancel"), style: .cancel, handler: nil))

        switch InterfaceIdiom.current() {
            case .iPhone:
                iPhone.tabBarController.present(alert, animated: true, completion: nil)
            case .iPad:
                iPad.splitController.present(alert, animated: true, completion: nil)
        }
    }

    func processPendingShareIfNeeded() {
        guard !isPresentingSharePicker else {
            return
        }

        guard let payload = ShareInboxStorage.loadPendingShare() else {
            return
        }

        pendingSharePayload = payload
        isPresentingSharePicker = true

        let controller = GroupSelectController(theme: theme, submanagerObjects: toxManager.objects)
        controller.delegate = self
        controller.title = String(localized: "file_send_to_group")

        let navigation = UINavigationController(rootViewController: controller)
        rootViewController().present(navigation, animated: true, completion: nil)
    }

    func presentGroupJoin(chatIdHex: String) {
        switch InterfaceIdiom.current() {
            case .iPhone:
                iPhone.tabBarController.selectedIndex = IphoneObjects.TabCoordinator.chats.rawValue
                iPhone.chatsCoordinator.joinGroupFromExternal(chatIdHex: chatIdHex)
            case .iPad:
                iPad.chatsCoordinator.joinGroupFromExternal(chatIdHex: chatIdHex)
        }
    }
}

extension ActiveSessionCoordinator: OCTSubmanagerUserDelegate {
    func submanagerUser(_ submanager: OCTSubmanagerUser, connectionStatusUpdate connectionStatus: OCTToxConnectionStatus) {
        SelfConnectionTracker.update(isOnline: connectionStatus != .none)
        updateUserStatusView()

        if connectionStatus == .none {
            // Distinguish "no network" (red) from "still connecting" (blue).
            let state: NotificationWindow.ConnectionPillState =
                networkReachabilityMonitor.isReachable ? .connecting : .offline
            notificationCoordinator.setConnectionState(state, animated: true)

            NetworkDiagnosticsLog.log("self_offline", detail: "rebootstrap")
            ConnectionQualityMonitor.shared.onBootstrapStarted()
            toxManager.bootstrap.rebootstrapOnNetworkChange()
        } else {
            // Connected → flash green "Online", then auto-hide.
            notificationCoordinator.setConnectionState(.connected, animated: true)
            ConnectionQualityMonitor.shared.onBootstrapFinished(connected: true)
        }
    }
}

extension ActiveSessionCoordinator: OCTSubmanagerGroupsDelegate {
    func submanagerGroups(_ submanager: OCTSubmanagerGroups,
                          didReceiveInviteFromFriendNumber friendNumber: OCTToxFriendNumber,
                          invite inviteData: Data,
                          groupName: String?) {
        showGroupInviteAlert(friendNumber: friendNumber, inviteData: inviteData, groupName: groupName)
    }

    func submanagerGroups(_ submanager: OCTSubmanagerGroups,
                          groupJoinDidFail failType: OCTToxGroupJoinFail,
                          for chat: OCTChat) {
        let message: String
        switch failType {
            case .peerLimit:
                message = String(localized: "group_join_fail_peer_limit")
            case .invalidPassword:
                message = String(localized: "group_join_fail_invalid_password")
            case .unknown:
                message = String(localized: "group_join_failed")
        }

        UIAlertController.showErrorWithMessage(message, retryBlock: nil)
    }
}

extension ActiveSessionCoordinator: NotificationCoordinatorDelegate {
    func notificationCoordinator(_ coordinator: NotificationCoordinator, showChat chat: OCTChat) {
        showChat(chat)
    }

    func notificationCoordinatorShowFriendRequest(_ coordinator: NotificationCoordinator, showRequest request: OCTFriendRequest) {
        showFriendRequest(request)
    }

    func notificationCoordinatorAnswerIncomingCall(_ coordinator: NotificationCoordinator, userInfo: String) {
        callCoordinator.answerIncomingCallWithUserInfo(userInfo)
    }

    func notificationCoordinator(_ coordinator: NotificationCoordinator, updateFriendsBadge badge: Int) {
        let text: String? = (badge > 0) ? "\(badge)" : nil

        switch InterfaceIdiom.current() {
            case .iPhone:
                iPhone.friendsTabBarItem.badgeValue = text
            case .iPad:
                iPad.primaryController.friendsBadgeText = text
                break
        }
    }

    func notificationCoordinator(_ coordinator: NotificationCoordinator, updateChatsBadge badge: Int) {
        switch InterfaceIdiom.current() {
            case .iPhone:
                iPhone.chatsTabBarItem.badgeValue = (badge > 0) ? "\(badge)" : nil
            case .iPad:
                // none
                break
        }
    }
}

extension ActiveSessionCoordinator: CallCoordinatorDelegate {
    func callCoordinator(_ coordinator: CallCoordinator, notifyAboutBackgroundCallFrom caller: String, userInfo: String) {
        notificationCoordinator.showCallNotificationWithCaller(caller, userInfo: userInfo)
    }

    func callCoordinatorDidStartCall(_ coordinator: CallCoordinator) {
        delegate?.activeSessionCoordinatorDidStartCall(self)
    }

    func callCoordinatorDidFinishCall(_ coordinator: CallCoordinator) {
        delegate?.activeSessionCoordinatorDidFinishCall(self)
    }
}

extension ActiveSessionCoordinator: FriendsTabCoordinatorDelegate {
    func friendsTabCoordinatorOpenChat(_ coordinator: FriendsTabCoordinator, forFriend friend: OCTFriend) {
        let chat = toxManager.chats.getOrCreateChat(with: friend)

        showChat(chat!)
    }

    func friendsTabCoordinatorCall(_ coordinator: FriendsTabCoordinator, toFriend friend: OCTFriend) {
        let chat = toxManager.chats.getOrCreateChat(with: friend)!

        callCoordinator.callToChat(chat, enableVideo: false)
    }

    func friendsTabCoordinatorVideoCall(_ coordinator: FriendsTabCoordinator, toFriend friend: OCTFriend) {
        let chat = toxManager.chats.getOrCreateChat(with: friend)!

        callCoordinator.callToChat(chat, enableVideo: true)
    }
}

extension ActiveSessionCoordinator: ChatsTabCoordinatorDelegate {
    func chatsTabCoordinator(_ coordinator: ChatsTabCoordinator, chatWillAppear chat: OCTChat) {
        notificationCoordinator.banNotificationsForChat(chat)
    }

    func chatsTabCoordinator(_ coordinator: ChatsTabCoordinator, chatWillDisapper chat: OCTChat) {
        notificationCoordinator.unbanNotificationsForChat(chat)
    }

    func chatsTabCoordinator(_ coordinator: ChatsTabCoordinator, callToChat chat: OCTChat, enableVideo: Bool) {
        callCoordinator.callToChat(chat, enableVideo: enableVideo)
    }
}

extension ActiveSessionCoordinator: ChatGroupControllerDelegate {
    func chatGroupControllerWillAppear(_ controller: ChatGroupController) {
        notificationCoordinator.banNotificationsForChat(controller.chat)
    }

    func chatGroupControllerWillDisappear(_ controller: ChatGroupController) {
        notificationCoordinator.unbanNotificationsForChat(controller.chat)
    }

    func chatGroupControllerDidRequestGroupInfo(_ controller: ChatGroupController) {
        showGroupInfo(controller.chat)
    }

    func chatGroupController(_ controller: ChatGroupController, didRequestOpenFriend friend: OCTFriend) {
        openChatWithSenderPublicKey(friend.publicKey)
    }
}

extension ActiveSessionCoordinator: SettingsTabCoordinatorDelegate {
    func settingsTabCoordinatorRecreateCoordinatorsStack(_ coordinator: SettingsTabCoordinator, options settingsOptions: CoordinatorOptions) {
        delegate?.activeSessionCoordinatorRecreateCoordinatorsStack(self, options: [
            Options.ToShowKey: Options.Coordinator.settings,
            Options.StoredOptions: settingsOptions,
        ])
    }

    func logout(importToxProfileFromURL profileURL: URL? = nil) {
        delegate?.activeSessionCoordinatorDidLogout(self, importToxProfileFromURL: profileURL)
    }
}

extension ActiveSessionCoordinator: ProfileTabCoordinatorDelegate {
    func profileTabCoordinatorDelegateLogout(_ coordinator: ProfileTabCoordinator) {
        logout()
    }

    func profileTabCoordinatorDelegateDeleteProfile(_ coordinator: ProfileTabCoordinator) {
        delegate?.activeSessionCoordinatorDeleteProfile(self)
    }

    func profileTabCoordinatorDelegateDidChangeUserStatus(_ coordinator: ProfileTabCoordinator) {
        updateUserStatusView()
    }

    func profileTabCoordinatorDelegateDidChangeAvatar(_ coordinator: ProfileTabCoordinator) {
        updateUserAvatar()
    }

    func profileTabCoordinatorDelegateDidChangeUserName(_ coordinator: ProfileTabCoordinator) {
        updateUserName()
    }
}

extension ActiveSessionCoordinator: PrimaryIpadControllerDelegate {
    func primaryIpadController(_ controller: PrimaryIpadController, didSelectChat chat: OCTChat) {
        showChat(chat)
    }

    func primaryIpadController(_ controller: PrimaryIpadController, didRequestGroupInfo chat: OCTChat) {
        showGroupInfo(chat)
    }

    func primaryIpadControllerShowFriends(_ controller: PrimaryIpadController) {
        iPad.splitController.showDetailViewController(friendsCoordinator.navigationController, sender: nil)
    }

    func primaryIpadControllerShowSettings(_ controller: PrimaryIpadController) {
        iPad.splitController.showDetailViewController(settingsCoordinator.navigationController, sender: nil)
    }

    func primaryIpadControllerShowProfile(_ controller: PrimaryIpadController) {
        iPad.splitController.showDetailViewController(profileCoordinator.navigationController, sender: nil)
    }
}

extension ActiveSessionCoordinator: ChatPrivateControllerDelegate {
    func chatPrivateControllerWillAppear(_ controller: ChatPrivateController) {
        notificationCoordinator.banNotificationsForChat(controller.chat)
    }

    func chatPrivateControllerWillDisappear(_ controller: ChatPrivateController) {
        notificationCoordinator.unbanNotificationsForChat(controller.chat)
    }

    func chatPrivateControllerCallToChat(_ controller: ChatPrivateController, enableVideo: Bool) {
        callCoordinator.callToChat(controller.chat, enableVideo: enableVideo)
    }

    func chatPrivateControllerShowQuickLookController(
            _ controller: ChatPrivateController,
            dataSource: QuickLookPreviewControllerDataSource,
            selectedIndex: Int)
    {
        // KHANDAQ (Figma / #204-C): custom media gallery for image/video; a non-media tap
        // (PDF, doc…) has no gallery index → fall through to QuickLook, which previews everything.
        if let fp = dataSource as? FilePreviewControllerDataSource,
           let start = fp.galleryStartIndex(forMessageIndex: selectedIndex) {
            let items = fp.galleryItems(myName: toxManager?.user.userName() ?? "")
            if !items.isEmpty {
                let gallery = MediaGalleryViewController(items: items, startIndex: start)
                iPad.splitController.present(gallery, animated: true, completion: nil)
                return
            }
        }

        let controller = QuickLookPreviewController()
        controller.dataSource = dataSource
        controller.dataSourceStorage = dataSource
        controller.currentPreviewItemIndex = selectedIndex

        iPad.splitController.present(controller, animated: true, completion: nil)
    }

    func chatPrivateControllerShowFriendProfile(_ controller: ChatPrivateController, forFriend friend: OCTFriend) {
        // iPad detail flow keeps the friend card reachable from the primary column instead.
    }
}

extension ActiveSessionCoordinator: GroupSelectControllerDelegate {
    func groupSelectController(_ controller: GroupSelectController, didSelectGroup chat: OCTChat) {
        rootViewController().dismiss(animated: true) { [unowned self] in
            guard let payload = self.pendingSharePayload else {
                self.isPresentingSharePicker = false
                return
            }

            self.sendSharePayload(payload, to: chat)
            ShareInboxStorage.clearPendingShare()
            self.pendingSharePayload = nil
            self.pendingOpenInFilePath = nil
            self.isPresentingSharePicker = false
            self.showChat(chat)
        }
    }

    func groupSelectControllerCancel(_ controller: GroupSelectController) {
        rootViewController().dismiss(animated: true) { [unowned self] in
            if let filePath = self.pendingOpenInFilePath {
                _ = try? FileManager.default.removeItem(atPath: filePath)
            }

            ShareInboxStorage.clearPendingShare()
            self.pendingSharePayload = nil
            self.pendingOpenInFilePath = nil
            self.isPresentingSharePicker = false
        }
    }
}

extension ActiveSessionCoordinator: FriendSelectControllerDelegate {
    func friendSelectController(_ controller: FriendSelectController, didSelectFriend friend: OCTFriend) {
        rootViewController().dismiss(animated: true) { [unowned self] in
            guard let filePath = controller.userInfo as? String else {
                return
            }

            let chat = self.toxManager.chats.getOrCreateChat(with: friend)
            self.sendFile(filePath, toChat: chat!)
        }
    }

    func friendSelectControllerCancel(_ controller: FriendSelectController) {
        rootViewController().dismiss(animated: true, completion: nil)

        guard let filePath = controller.userInfo as? String else {
            return
        }
        _ = try? FileManager.default.removeItem(atPath: filePath)
    }
}

private extension ActiveSessionCoordinator {
    func createDeviceSpecificObjects() {
        switch InterfaceIdiom.current() {
            case .iPhone:
                let chatsCoordinator = ChatsTabCoordinator(theme: theme, submanagerObjects: toxManager.objects, submanagerChats: toxManager.chats, submanagerGroups: toxManager.groups, submanagerFiles: toxManager.files, submanagerUser: toxManager.user, submanagerFriends: toxManager.friends)
                chatsCoordinator.delegate = self

                let tabBarControllers = IphoneObjects.TabCoordinator.allValues().map { object -> UINavigationController in
                    switch object {
                        case .friends:
                            return friendsCoordinator.navigationController
                        case .chats:
                            return chatsCoordinator.navigationController
                        case .settings:
                            return settingsCoordinator.navigationController
                        case .profile:
                            return profileCoordinator.navigationController
                    }
                }

                let tabBarItems = configureNativeTabBarItems(for: tabBarControllers)

                let tabBarController = TabBarController(theme: theme, controllers: tabBarControllers)

                iPhone = IphoneObjects(
                        chatsCoordinator: chatsCoordinator,
                        tabBarController: tabBarController,
                        friendsTabBarItem: tabBarItems.friends,
                        chatsTabBarItem: tabBarItems.chats,
                        profileTabBarItem: tabBarItems.profile)

            case .iPad:
                let splitController = UISplitViewController()
                splitController.preferredDisplayMode = .allVisible

                let chatsCoordinator = ChatsTabCoordinator(theme: theme, submanagerObjects: toxManager.objects, submanagerChats: toxManager.chats, submanagerGroups: toxManager.groups, submanagerFiles: toxManager.files, submanagerUser: toxManager.user, submanagerFriends: toxManager.friends)
                chatsCoordinator.delegate = self
                chatsCoordinator.externalPresentingController = splitController
                chatsCoordinator.chatDisplayHandler = { [weak self] chat, _ in
                    self?.showChat(chat)
                }

                let primaryController = PrimaryIpadController(theme: theme, submanagerChats: toxManager.chats, submanagerGroups: toxManager.groups, submanagerObjects: toxManager.objects)
                primaryController.delegate = self
                splitController.viewControllers = [UINavigationController(rootViewController: primaryController)]

                iPad = IpadObjects(splitController: splitController, primaryController: primaryController, chatsCoordinator: chatsCoordinator)
        }
    }

    func createCallCoordinator() {
        let presentingController: UIViewController

        switch InterfaceIdiom.current() {
            case .iPhone:
                presentingController = iPhone.tabBarController
            case .iPad:
                presentingController = iPad.splitController
        }

        self.callCoordinator = CallCoordinator(
                theme: theme,
                presentingController: presentingController,
                submanagerCalls: toxManager.calls,
                submanagerObjects: toxManager.objects)
        callCoordinator.delegate = self
    }

    func configureNativeTabBarItems(for controllers: [UINavigationController]) -> (friends: UITabBarItem, chats: UITabBarItem, profile: UITabBarItem) {
        func templateImage(_ name: String) -> UIImage? {
            UIImage(named: name)?.withRenderingMode(.alwaysTemplate)
        }

        // KHANDAQ design (Figma): tab icons are clean SF-Symbol outlines — a person-in-circle for
        // Contacts, twin bubbles for Chats, a gear for Settings. Fall back to the bundled assets on
        // iOS < 13. (Profile keeps the live user avatar via makeProfileTabBarImage.)
        func tabImage(systemName: String, fallback: String) -> UIImage? {
            if #available(iOS 13.0, *), let symbol = UIImage(systemName: systemName) {
                return symbol
            }
            return templateImage(fallback)
        }

        let friendsItem = UITabBarItem(
                title: String(localized: "contacts_title"),
                image: tabImage(systemName: "person.crop.circle", fallback: "tab-bar-friends"),
                tag: IphoneObjects.TabCoordinator.friends.rawValue)
        friendsItem.badgeColor = theme.colorForType(.TabBadgeBackground)
        configureCompactTabBarItem(friendsItem, accessibilityTitle: String(localized: "contacts_title"))
        controllers[IphoneObjects.TabCoordinator.friends.rawValue].tabBarItem = friendsItem

        let chatsItem = UITabBarItem(
                title: String(localized: "chats_title"),
                image: tabImage(systemName: "bubble.left.and.bubble.right", fallback: "tab-bar-chats"),
                tag: IphoneObjects.TabCoordinator.chats.rawValue)
        chatsItem.badgeColor = theme.colorForType(.TabBadgeBackground)
        configureCompactTabBarItem(chatsItem, accessibilityTitle: String(localized: "chats_title"))
        controllers[IphoneObjects.TabCoordinator.chats.rawValue].tabBarItem = chatsItem

        let settingsItem = UITabBarItem(
                title: String(localized: "settings_title"),
                image: tabImage(systemName: "gearshape", fallback: "tab-bar-settings"),
                tag: IphoneObjects.TabCoordinator.settings.rawValue)
        configureCompactTabBarItem(settingsItem, accessibilityTitle: String(localized: "settings_title"))
        controllers[IphoneObjects.TabCoordinator.settings.rawValue].tabBarItem = settingsItem

        let profileItem = UITabBarItem(
                title: String(localized: "profile_title"),
                image: TabBarController.makeProfileTabBarImage(
                        theme: theme,
                        userImage: nil,
                        userStatus: .offline,
                        connectionStatus: .none),
                tag: IphoneObjects.TabCoordinator.profile.rawValue)
        configureCompactTabBarItem(profileItem, accessibilityTitle: String(localized: "profile_title"))
        controllers[IphoneObjects.TabCoordinator.profile.rawValue].tabBarItem = profileItem

        return (friendsItem, chatsItem, profileItem)
    }

    func configureCompactTabBarItem(_ item: UITabBarItem, accessibilityTitle: String) {
        item.accessibilityLabel = accessibilityTitle
    }

    func showFriendRequest(_ request: OCTFriendRequest) {
        switch InterfaceIdiom.current() {
            case .iPhone:
                iPhone.tabBarController.selectedIndex = IphoneObjects.TabCoordinator.friends.rawValue
            case .iPad:
                primaryIpadControllerShowFriends(iPad.primaryController)
        }

        friendsCoordinator.showRequest(request, animated: false)
    }

    /**
        Returns active chat controller if it is visible, nil otherwise.
     */
    func activeChatController() -> ChatPrivateController? {
        switch InterfaceIdiom.current() {
            case .iPhone:
                if iPhone.tabBarController.selectedIndex != IphoneObjects.TabCoordinator.chats.rawValue {
                    return nil
                }

                return iPhone.chatsCoordinator.activeChatController()
            case .iPad:
                return iPadDetailController() as? ChatPrivateController
        }
    }

    func openChatWithUniqueIdentifier(_ identifier: String) {
        guard let chat = toxManager.objects.object(withUniqueIdentifier: identifier, for: .chat) as? OCTChat else {
            return
        }
        showChat(chat)
    }

    func openChatWithSenderPublicKey(_ publicKey: String) {
        let normalized = publicKey.uppercased()
        let friends = toxManager.objects.friends()
        for index in 0..<friends.count {
            guard let friend = friends[index] as? OCTFriend else {
                continue
            }
            if friend.publicKey.uppercased() == normalized,
               let chat = toxManager.chats.getOrCreateChat(with: friend) {
                showChat(chat)
                return
            }
        }
    }

    func showChat(_ chat: OCTChat) {
        switch InterfaceIdiom.current() {
            case .iPhone:
                if iPhone.tabBarController.selectedIndex != IphoneObjects.TabCoordinator.chats.rawValue {
                    iPhone.tabBarController.selectedIndex = IphoneObjects.TabCoordinator.chats.rawValue
                }

                iPhone.chatsCoordinator.showChat(chat, animated: false)
            case .iPad:
                if chat.isGroup {
                    if let groupVC = iPadDetailController() as? ChatGroupController, groupVC.chat == chat {
                        return
                    }
                }
                else if let chatVC = iPadDetailController() as? ChatPrivateController, chatVC.chat == chat {
                    return
                }

                let rootController: UIViewController
                if chat.isGroup {
                    rootController = ChatGroupController(
                            theme: theme,
                            chat: chat,
                            submanagerGroups: toxManager.groups,
                            submanagerObjects: toxManager.objects,
                            submanagerFriends: toxManager.friends,
                            submanagerChats: toxManager.chats,
                            delegate: self)
                }
                else {
                    rootController = ChatPrivateController(
                            theme: theme,
                            chat: chat,
                            submanagerChats: toxManager.chats,
                            submanagerObjects: toxManager.objects,
                            submanagerFiles: toxManager.files,
                            delegate: self,
                            showKeyboardOnAppear: iPad.keyboardObserver.keyboardVisible)
                }
                let navigation = UINavigationController(rootViewController: rootController)

                iPad.splitController.showDetailViewController(navigation, sender: nil)
        }
    }

    func showGroupInfo(_ chat: OCTChat) {
        let infoController = GroupInfoController(
                theme: theme,
                chat: chat,
                submanagerGroups: toxManager.groups,
                submanagerObjects: toxManager.objects,
                submanagerChats: toxManager.chats,
                submanagerFiles: toxManager.files,
                submanagerFriends: toxManager.friends)

        switch InterfaceIdiom.current() {
            case .iPhone:
                if iPhone.tabBarController.selectedIndex != IphoneObjects.TabCoordinator.chats.rawValue {
                    iPhone.tabBarController.selectedIndex = IphoneObjects.TabCoordinator.chats.rawValue
                }
                iPhone.chatsCoordinator.navigationController.pushViewController(infoController, animated: true)
            case .iPad:
                if let navigation = iPadDetailController()?.navigationController {
                    navigation.pushViewController(infoController, animated: true)
                }
                else {
                    let navigation = UINavigationController(rootViewController: infoController)
                    iPad.splitController.showDetailViewController(navigation, sender: nil)
                }
        }
    }

    func showGroupInviteAlert(friendNumber: OCTToxFriendNumber, inviteData: Data, groupName: String?) {
        let friendDisplayName = friendDisplayName(for: friendNumber)
        // KHANDAQ (#15): the invited group name can arrive nil or as the literal "(null)" — fall
        // back to the default title instead of showing "(null)" in the alert.
        let cleanGroupName = (groupName?.isEmpty == false && groupName != "(null)") ? groupName : nil
        let resolvedGroupName = cleanGroupName ?? String(localized: "group_chat_default_title")
        let title = String(localized: "group_invite_alert_title")
        // KHANDAQ (#15): String(localized:) is a custom initializer that ALREADY runs String(format:)
        // on the localized string with the given arguments. The old code called String(localized:)
        // with NO arguments, so its internal format ran "%@ … %@" with zero args → "(null) … (null)",
        // and the outer String(format:) then had nothing left to fill. Pass the args straight to
        // String(localized:) instead.
        let message = String(localized: "group_invite_alert_message_format", friendDisplayName as NSString, resolvedGroupName as NSString)

        let alert = UIAlertController(title: title, message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: String(localized: "group_invite_accept"), style: .default) { [unowned self] _ in
            do {
                let chat = try self.toxManager.groups.acceptGroupInvite(fromFriendNumber: friendNumber,
                                                                        invite: inviteData,
                                                                        groupName: groupName,
                                                                        password: nil)
                DispatchQueue.main.async {
                    self.showChat(chat)
                }
            }
            catch let error as NSError {
                handleErrorWithType(.acceptGroupInvite, error: error)
            }
        })
        alert.addAction(UIAlertAction(title: String(localized: "alert_cancel"), style: .cancel, handler: nil))

        // KHANDAQ (#15): present on the TOP-MOST controller. Presenting directly on the tab bar /
        // split controller silently fails when something is already presented (e.g. an open chat or
        // another sheet), which made invites arrive but show no alert ("просто тишина").
        let base: UIViewController
        switch InterfaceIdiom.current() {
            case .iPhone:
                base = iPhone.tabBarController
            case .iPad:
                base = iPad.splitController
        }
        var presenter = base
        while let presented = presenter.presentedViewController, !presented.isBeingDismissed {
            presenter = presented
        }
        presenter.present(alert, animated: true, completion: nil)
    }

    func friendDisplayName(for friendNumber: OCTToxFriendNumber) -> String {
        let friends = toxManager.objects.friends()
        for index in 0..<friends.count {
            guard let friend = friends[index] as? OCTFriend else {
                continue
            }
            if friend.friendNumber == friendNumber {
                if let name = friend.name, !name.isEmpty, name != "(null)" {
                    return name
                }
                return friend.publicKey
            }
        }
        return String(localized: "group_invite_unknown_friend")
    }

    func showSettings() {
        switch InterfaceIdiom.current() {
            case .iPhone:
                iPhone.tabBarController.selectedIndex = IphoneObjects.TabCoordinator.settings.rawValue
            case .iPad:
                primaryIpadControllerShowFriends(iPad.primaryController)
        }
    }

    func updateUserStatusView() {
        let status = UserStatus(connectionStatus: toxManager.user.connectionStatus, userStatus: toxManager.user.userStatus)
        let connectionstatus = ConnectionStatus(connectionStatus: toxManager.user.connectionStatus)

        switch InterfaceIdiom.current() {
            case .iPhone:
                refreshProfileTabBarItem(userStatus: status, connectionStatus: connectionstatus)
            case .iPad:
                iPad.primaryController.userStatus = status
        }
    }

    func updateUserAvatar() {
        switch InterfaceIdiom.current() {
            case .iPhone:
                let status = UserStatus(connectionStatus: toxManager.user.connectionStatus, userStatus: toxManager.user.userStatus)
                let connectionstatus = ConnectionStatus(connectionStatus: toxManager.user.connectionStatus)
                refreshProfileTabBarItem(userStatus: status, connectionStatus: connectionstatus)
            case .iPad:
                var avatar: UIImage?

                if let avatarData = toxManager.user.userAvatar() {
                    avatar = UIImage(data: avatarData)
                }

                iPad.primaryController.userAvatar = avatar
        }
    }

    func refreshProfileTabBarItem(userStatus: UserStatus, connectionStatus: ConnectionStatus) {
        var avatar: UIImage?

        if let avatarData = toxManager.user.userAvatar() {
            avatar = UIImage(data: avatarData)
        }

        iPhone.profileTabBarItem.image = TabBarController.makeProfileTabBarImage(
                theme: theme,
                userImage: avatar,
                userStatus: userStatus,
                connectionStatus: connectionStatus)
        iPhone.profileTabBarItem.accessibilityValue = userStatus.toString()
    }

    func updateUserName() {
        switch InterfaceIdiom.current() {
            case .iPhone:
                // nop
                break
            case .iPad:
                iPad.primaryController.userName = toxManager.user.userName()
        }
    }

    func iPadDetailController() -> UIViewController? {
        guard iPad.splitController.viewControllers.count == 2 else {
            return nil
        }

        let controller = iPad.splitController.viewControllers[1]

        if let navigation = controller as? UINavigationController {
            return navigation.topViewController
        }

        return controller
    }

    func rootViewController() -> UIViewController {
        switch InterfaceIdiom.current() {
            case .iPhone:
                return iPhone.tabBarController
            case .iPad:
                return iPad.splitController
        }
    }

    func sendFileToChats(_ filePath: String, fileName: String) {
        let controller = FriendSelectController(theme: theme, submanagerObjects: toxManager.objects)
        controller.delegate = self
        controller.title = String(localized: "file_send_to_contact")
        controller.userInfo = filePath as AnyObject?

        let navigation = UINavigationController(rootViewController: controller)

        rootViewController().present(navigation, animated: true, completion: nil)
    }

    func sendFileToGroup(_ filePath: String, fileName: String) {
        pendingOpenInFilePath = filePath
        pendingSharePayload = ShareInboxPayload(
            version: 1,
            createdAt: Date().timeIntervalSince1970,
            items: [ShareInboxItem(type: .file, text: nil, fileName: fileName, relativePath: nil)]
        )
        isPresentingSharePicker = true

        let controller = GroupSelectController(theme: theme, submanagerObjects: toxManager.objects)
        controller.delegate = self
        controller.title = String(localized: "file_send_to_group")

        let navigation = UINavigationController(rootViewController: controller)
        rootViewController().present(navigation, animated: true, completion: nil)
    }

    func sendSharePayload(_ payload: ShareInboxPayload, to chat: OCTChat) {
        for item in payload.items {
            switch item.type {
                case .text:
                    guard let text = item.text?.trimmingCharacters(in: .whitespacesAndNewlines), !text.isEmpty else {
                        continue
                    }

                    toxManager.groups.sendMessage(to: chat, text: text, type: .normal, successBlock: { _ in
                    }, failureBlock: { error in
                        handleErrorWithType(.sendMessageToFriend, error: error as NSError?)
                    })

                case .file:
                    let path = ShareInboxStorage.absolutePath(for: item) ?? pendingOpenInFilePath
                    guard let filePath = path else {
                        continue
                    }

                    toxManager.groups.sendFile(atPath: filePath, to: chat, moveToUploads: true, successBlock: { _ in
                    }, failureBlock: { error in
                        handleErrorWithType(.sendFileToFriend, error: error as NSError?)
                    })
            }
        }

        pendingOpenInFilePath = nil
    }

    func sendFile(_ filePath: String, toChat chat: OCTChat) {
        showChat(chat)

        toxManager.files.sendFile(atPath: filePath, moveToUploads: true, to: chat, failureBlock: { (error: Error) in
            handleErrorWithType(.sendFileToFriend, error: error as NSError)

        })
    }
}
