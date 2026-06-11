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

    enum Coordinator {
        case none
        case settings
    }
}

private struct IpadObjects {
    let splitController: UISplitViewController

    let primaryController: PrimaryIpadController

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

    fileprivate let networkReachabilityMonitor = ToxNetworkReachabilityMonitor()

    init(theme: Theme, window: UIWindow, toxManager: OCTManager) {
        self.theme = theme
        self.window = window
        self.toxManager = toxManager

        self.friendsCoordinator = FriendsTabCoordinator(theme: theme, toxManager: toxManager)
        self.settingsCoordinator = SettingsTabCoordinator(theme: theme)
        self.profileCoordinator = ProfileTabCoordinator(theme: theme, toxManager: toxManager)
        self.notificationCoordinator = NotificationCoordinator(theme: theme, submanagerObjects: toxManager.objects)
        self.automationCoordinator = AutomationCoordinator(submanagerObjects: toxManager.objects, submanagerFiles: toxManager.files)

        super.init()

        // order matters
        createDeviceSpecificObjects()
        createCallCoordinator()

        toxManager.user.delegate = self

        friendsCoordinator.delegate = self
        settingsCoordinator.delegate = self
        profileCoordinator.delegate = self
        notificationCoordinator.delegate = self

        KhandaqPushManager.shared.bind(toxManager: toxManager)

        networkReachabilityMonitor.start { [weak self] in
            self?.handleNetworkPathChange()
        }

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(ActiveSessionCoordinator.networkRebootstrapCompleted),
            name: NSNotification.Name("kOCTNetworkRebootstrapCompletedNotification"),
            object: nil)

        NotificationCenter.default.addObserver(self, selector: #selector(ActiveSessionCoordinator.applicationWillTerminate), name: NSNotification.Name.UIApplicationWillTerminate, object: nil)
    }

    deinit {
        networkReachabilityMonitor.stop()
        NotificationCenter.default.removeObserver(self)
    }

    fileprivate func handleNetworkPathChange() {
        NetworkDiagnosticsLog.log("network_path_change", detail: "reachability")
        ConnectionQualityMonitor.shared.onBootstrapStarted()
        toxManager.bootstrap.rebootstrapOnNetworkChange()
        toxManager.chats.broadcastOwnPushURLToConnectedFriends()
    }

    @objc func networkRebootstrapCompleted() {
        let connected = toxManager.user.connectionStatus != .none
        ConnectionQualityMonitor.shared.onBootstrapFinished(connected: connected)
        NetworkDiagnosticsLog.log("rebootstrap_completed", detail: "connected=\(connected)")
    }

    @objc func applicationWillTerminate() {
        shutdownForToxRestart()
    }

    func shutdownForToxRestart() {
        networkReachabilityMonitor.stop()
        KhandaqPushManager.shared.unbind()
        toxManager?.user.delegate = nil
        callCoordinator = nil
        toxManager = nil
    }
}

extension ActiveSessionCoordinator: TopCoordinatorProtocol {
    func startWithOptions(_ options: CoordinatorOptions?) {
        switch InterfaceIdiom.current() {
            case .iPhone:
                iPhone.tabBarController.selectedIndex = IphoneObjects.TabCoordinator.chats.rawValue
                iPhone.chatsCoordinator.startWithOptions(nil)

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
        settingsCoordinator.startWithOptions(settingsOptions)
        profileCoordinator.startWithOptions(nil)
        notificationCoordinator.startWithOptions(nil)
        automationCoordinator.startWithOptions(nil)
        callCoordinator.startWithOptions(nil)

        DispatchQueue.main.async { [weak self] in
            guard let self = self else {
                return
            }
            self.toxManager.bootstrap.addPredefinedNodes()
            self.toxManager.bootstrap.bootstrap()
        }

        updateUserAvatar()
        updateUserName()

        switch toShow {
            case .none:
                break
            case .settings:
                showSettings()
        }
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

        alert.addAction(UIAlertAction(title: String(localized: "alert_cancel"), style: .cancel, handler: nil))

        switch InterfaceIdiom.current() {
            case .iPhone:
                iPhone.tabBarController.present(alert, animated: true, completion: nil)
            case .iPad:
                iPad.splitController.present(alert, animated: true, completion: nil)
        }
    }
}

extension ActiveSessionCoordinator: OCTSubmanagerUserDelegate {
    func submanagerUser(_ submanager: OCTSubmanagerUser, connectionStatusUpdate connectionStatus: OCTToxConnectionStatus) {
        updateUserStatusView()

        let show = (connectionStatus == .none)
        notificationCoordinator.toggleConnectingView(show: show, animated: true)

        if connectionStatus == .none {
            NetworkDiagnosticsLog.log("self_offline", detail: "rebootstrap")
            ConnectionQualityMonitor.shared.onBootstrapStarted()
            toxManager.bootstrap.rebootstrapOnNetworkChange()
        } else {
            ConnectionQualityMonitor.shared.onBootstrapFinished(connected: true)
        }
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

extension ActiveSessionCoordinator: SettingsTabCoordinatorDelegate {
    func settingsTabCoordinatorRecreateCoordinatorsStack(_ coordinator: SettingsTabCoordinator, options settingsOptions: CoordinatorOptions) {
        delegate?.activeSessionCoordinatorRecreateCoordinatorsStack(self, options: [
            Options.ToShowKey: Options.Coordinator.settings,
            Options.StoredOptions: settingsOptions,
        ])
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
        let controller = QuickLookPreviewController()
        controller.dataSource = dataSource
        controller.dataSourceStorage = dataSource
        controller.currentPreviewItemIndex = selectedIndex

        iPad.splitController.present(controller, animated: true, completion: nil)
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
                let chatsCoordinator = ChatsTabCoordinator(theme: theme, submanagerObjects: toxManager.objects, submanagerChats: toxManager.chats, submanagerFiles: toxManager.files)
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

                let primaryController = PrimaryIpadController(theme: theme, submanagerChats: toxManager.chats, submanagerObjects: toxManager.objects)
                primaryController.delegate = self
                splitController.viewControllers = [UINavigationController(rootViewController: primaryController)]

                iPad = IpadObjects(splitController: splitController, primaryController: primaryController)
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

        let friendsItem = UITabBarItem(
                title: String(localized: "contacts_title"),
                image: templateImage("tab-bar-friends"),
                tag: IphoneObjects.TabCoordinator.friends.rawValue)
        friendsItem.badgeColor = theme.colorForType(.TabBadgeBackground)
        controllers[IphoneObjects.TabCoordinator.friends.rawValue].tabBarItem = friendsItem

        let chatsItem = UITabBarItem(
                title: String(localized: "chats_title"),
                image: templateImage("tab-bar-chats"),
                tag: IphoneObjects.TabCoordinator.chats.rawValue)
        chatsItem.badgeColor = theme.colorForType(.TabBadgeBackground)
        controllers[IphoneObjects.TabCoordinator.chats.rawValue].tabBarItem = chatsItem

        let settingsItem = UITabBarItem(
                title: String(localized: "settings_title"),
                image: templateImage("tab-bar-settings"),
                tag: IphoneObjects.TabCoordinator.settings.rawValue)
        controllers[IphoneObjects.TabCoordinator.settings.rawValue].tabBarItem = settingsItem

        let profileItem = UITabBarItem(
                title: String(localized: "profile_title"),
                image: TabBarController.makeProfileTabBarImage(
                        theme: theme,
                        userImage: nil,
                        userStatus: .offline,
                        connectionStatus: .none),
                tag: IphoneObjects.TabCoordinator.profile.rawValue)
        controllers[IphoneObjects.TabCoordinator.profile.rawValue].tabBarItem = profileItem

        return (friendsItem, chatsItem, profileItem)
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
                if let chatVC = iPadDetailController() as? ChatPrivateController {
                    if chatVC.chat == chat {
                        // controller is already visible
                        return
                    }
                }

                let controller = ChatPrivateController(
                        theme: theme,
                        chat: chat,
                        submanagerChats: toxManager.chats,
                        submanagerObjects: toxManager.objects,
                        submanagerFiles: toxManager.files,
                        delegate: self,
                        showKeyboardOnAppear: iPad.keyboardObserver.keyboardVisible)
                let navigation = UINavigationController(rootViewController: controller)

                iPad.splitController.showDetailViewController(navigation, sender: nil)
        }
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

    func logout(importToxProfileFromURL profileURL: URL? = nil) {
        delegate?.activeSessionCoordinatorDidLogout(self, importToxProfileFromURL: profileURL)
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

    func sendFile(_ filePath: String, toChat chat: OCTChat) {
        showChat(chat)

        toxManager.files.sendFile(atPath: filePath, moveToUploads: true, to: chat, failureBlock: { (error: Error) in
            handleErrorWithType(.sendFileToFriend, error: error as NSError)

        })
    }
}
