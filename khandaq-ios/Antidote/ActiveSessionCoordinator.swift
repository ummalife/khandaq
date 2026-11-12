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
    /// The screen the phone shows above the Air screen. The raw values are the old tab indexes, so a
    /// theme reload keeps bringing the person back to the same place (RunningCoordinator stores the
    /// value as SelectedTabIndex).
    enum Screen: Int {
        case air = 1
        case settings = 2
        case profile = 3
        /// Settings > Appearance, where the theme and the language change (E18).
        case appearance = 4
    }

    let chatsCoordinator: ChatsTabCoordinator

    /// One stack for the whole phone UI: the Air screen at its root, everything else pushed above it.
    let navigationController: UINavigationController

    var currentScreen: Screen {
        for controller in navigationController.viewControllers.reversed() {
            if controller is AirAppearanceController {
                return .appearance
            }
            if controller is SettingsMainController {
                return .settings
            }
            if controller is ProfileMainController {
                return .profile
            }
        }
        return .air
    }
}

class ActiveSessionCoordinator: NSObject {
    /// An option of `startWithOptions`: the session starts with the command line focused. First launch
    /// ends with it ("Add the first person", stage 4, E19).
    static let focusCommandLineKey = "FocusCommandLine"

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

    /// KHANDAQ (#98): the phone screen to bring back after a theme reload (nil on iPad).
    var currentTabIndex: Int? {
        guard case .iPhone = InterfaceIdiom.current(), let iPhone = iPhone else { return nil }
        return iPhone.currentScreen.rawValue
    }

    /// The phone's single navigation stack, created before the coordinators that push onto it.
    fileprivate let phoneNavigationController: UINavigationController?
    /// "You" growing out of the "you" block (E17); the stack's delegate, which holds it weakly.
    fileprivate let youTransition = AirYouTransition()

    fileprivate var isPresentingSharePicker = false
    fileprivate var pendingSharePayload: ShareInboxPayload?
    fileprivate var pendingOpenInFilePath: String?

    fileprivate let networkReachabilityMonitor = ToxNetworkReachabilityMonitor()
    fileprivate var qualityObserver: NSObjectProtocol?
    fileprivate var degradedNagTimer: Timer?
    // KHANDAQ (#iOS connect-pill): the connecting pill clears via the connectionStatusUpdate delegate,
    // which fires only on a TRANSITION. A fast cold connect (fresh live DHT nodes) can flip 0 to UDP before
    // the delegate is observing, so the transition is lost and the pill hangs even though tox is online.
    // This one-shot reconcile timer polls the LIVE status for the first seconds and clears the pill if we
    // never saw the connected transition. `didReportConnectedPill` makes it idempotent (no green re-flash).
    fileprivate var connectPillReconcileTimer: Timer?
    fileprivate var didReportConnectedPill = false
    fileprivate var presenceRecorder: AirPresenceRecorder?
    fileprivate var blockGuard: AirBlockGuard?
    fileprivate var offlineWatchdog: OfflineReconnectWatchdog?

    init(theme: Theme, window: UIWindow, toxManager: OCTManager) {
        self.theme = theme
        self.window = window
        self.toxManager = toxManager

        // The phone has no tabs: Contacts, Settings and Profile push onto the Air screen's stack.
        let phoneNavigation: UINavigationController?
        switch InterfaceIdiom.current() {
            case .iPhone:
                phoneNavigation = UINavigationController()
            case .iPad:
                phoneNavigation = nil
        }
        self.phoneNavigationController = phoneNavigation

        self.friendsCoordinator = FriendsTabCoordinator(theme: theme, toxManager: toxManager, navigationController: phoneNavigation)
        self.settingsCoordinator = SettingsTabCoordinator(theme: theme, submanagerObjects: toxManager.objects, toxManager: toxManager, navigationController: phoneNavigation)
        self.profileCoordinator = ProfileTabCoordinator(theme: theme, toxManager: toxManager, navigationController: phoneNavigation)
        self.notificationCoordinator = NotificationCoordinator(theme: theme, submanagerObjects: toxManager.objects)
        self.automationCoordinator = AutomationCoordinator(submanagerObjects: toxManager.objects, submanagerFiles: toxManager.files)

        super.init()

        settingsCoordinator.sessionCoordinator = self
        phoneNavigationController?.delegate = youTransition

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

        // KHANDAQ (redesign stage 2): the day strip of the conversation. The hours each contact is seen
        // online are kept next to the profile, outside Realm, and never leave the device (AirLocalStore).
        if let profile = UserDefaultsManager().lastActiveProfile {
            AirLocalStore.shared.attach(directory: URL(fileURLWithPath: ProfileManager().pathForProfileWithName(profile), isDirectory: true))
        }
        let recorder = AirPresenceRecorder(submanagerObjects: toxManager.objects)
        recorder.start()
        presenceRecorder = recorder
        // KHANDAQ (redesign stage 3): requests from a blocked key are dropped as they land.
        let guardian = AirBlockGuard(submanagerObjects: toxManager.objects, submanagerFriends: toxManager.friends)
        guardian.start()
        blockGuard = guardian

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
        let watchdog = OfflineReconnectWatchdog(isOnline: { [weak self] in
            guard let manager = self?.toxManager else {
                return true
            }
            return manager.user.connectionStatus != .none
        }, isReachable: { [weak self] in
            return self?.networkReachabilityMonitor.isReachable ?? false
        })
        watchdog.start()
        offlineWatchdog = watchdog

        // Surface connection-quality changes so the user can watch their link stability: yellow nag while
        // weak, a one-shot green "stable" when it recovers.
        qualityObserver = NotificationCenter.default.addObserver(
            forName: .connectionQualityDidChange, object: nil, queue: .main) { [weak self] note in
            self?.handleConnectionQualityChange(note)
        }
    }

    deinit {
        presenceRecorder?.stop()
        blockGuard?.stop()
        offlineWatchdog?.stop()
        MessageDeliveryWatchdog.shared.stop()
        NotificationCenter.default.removeObserver(self, name: .khandaqManualReconnectRequested, object: nil)
        networkReachabilityMonitor.stop()
        degradedNagTimer?.invalidate()
        connectPillReconcileTimer?.invalidate()
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

    // KHANDAQ (#iOS connect-pill): poll the live tox status about once a second for the first ~12s after launch. If we
    // reach online but never got the connected transition (it flipped before the delegate observed it),
    // clear the pill here. Idempotent via didReportConnectedPill; self-cancels once online or on timeout.
    fileprivate func startConnectPillReconcile() {
        connectPillReconcileTimer?.invalidate()
        var ticks = 0
        connectPillReconcileTimer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] timer in
            guard let self = self else {
                timer.invalidate()
                return
            }
            ticks += 1
            let online = self.toxManager.user.connectionStatus != .none
            if online && !self.didReportConnectedPill {
                self.didReportConnectedPill = true
                SelfConnectionTracker.update(isOnline: true)
                self.notificationCoordinator.setConnectionState(.connected, animated: true)
                self.updatePhoneNetworkState()
            }
            if self.didReportConnectedPill || ticks >= 40 {
                timer.invalidate()
                self.connectPillReconcileTimer = nil
            }
        }
    }

    fileprivate func handleNetworkPathChange() {
        updatePhoneNetworkState()
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
        offlineWatchdog?.stop()
        networkReachabilityMonitor.stop()
        KhandaqPushManager.shared.unbind()
        presenceRecorder?.stop()
        presenceRecorder = nil
        blockGuard?.stop()
        blockGuard = nil
        AirLocalStore.shared.detach()
        ActiveSessionCoordinator.releaseDelegates(user: toxManager?.user, groups: toxManager?.groups, heldBy: self)
        callCoordinator = nil
    }

    func shutdownForThemeReload() {
        // The new session already records for the same profile.
        presenceRecorder?.stop()
        presenceRecorder = nil
        blockGuard?.stop()
        blockGuard = nil
        offlineWatchdog?.stop()
        networkReachabilityMonitor.stop()
        ActiveSessionCoordinator.releaseDelegates(user: toxManager?.user, groups: toxManager?.groups, heldBy: self)
        callCoordinator = nil
    }

    /// Lets go of the manager's user and groups delegates only while they are still this session's.
    /// A theme or language switch builds the new session for the same manager first; clearing them
    /// after that left group invitations and the node's own status with nobody to receive them.
    static func releaseDelegates(user: OCTSubmanagerUser?, groups: OCTSubmanagerGroups?, heldBy owner: AnyObject) {
        if let user = user, user.delegate === owner {
            user.delegate = nil
        }
        if let groups = groups, groups.delegate === owner {
            groups.delegate = nil
        }
    }
}

extension ActiveSessionCoordinator: TopCoordinatorProtocol {
    func startWithOptions(_ options: CoordinatorOptions?) {
        switch InterfaceIdiom.current() {
            case .iPhone:
                window.rootViewController = iPhone.navigationController
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

        switch InterfaceIdiom.current() {
            case .iPhone:
                // Only the Air screen starts: the other coordinators share its stack and push their
                // screens when asked (starting them would replace the stack's root).
                iPhone.chatsCoordinator.startWithOptions(nil)
            case .iPad:
                friendsCoordinator.startWithOptions(nil)
                settingsCoordinator.startWithOptions(settingsOptions)
                profileCoordinator.startWithOptions(nil)
        }
        notificationCoordinator.startWithOptions(nil)
        automationCoordinator.startWithOptions(nil)
        callCoordinator.startWithOptions(nil)

        // Show the connecting pill immediately on launch: connectionStatusUpdate only fires on a
        // *change*, so the initial offline-to-connecting period would otherwise have no indicator.
        SelfConnectionTracker.update(isOnline: toxManager.user.connectionStatus != .none)
        if toxManager.user.connectionStatus != .none {
            // Already online at startup (e.g. warm relaunch): reflect it now; the transition delegate
            // won't fire because there's no change to observe.
            didReportConnectedPill = true
            notificationCoordinator.setConnectionState(.connected, animated: false)
        }
        else {
            notificationCoordinator.setConnectionState(
                networkReachabilityMonitor.isReachable ? .connecting : .offline, animated: false)
            // KHANDAQ (#iOS connect-pill): catch a fast connect whose 0-to-UDP transition lands before the
            // delegate observes it; reconcile the pill against the live status until we go online.
            startConnectPillReconcile()
        }

        if case .iPhone = InterfaceIdiom.current() {
            iPhone.navigationController.view.setNeedsLayout()
            iPhone.navigationController.view.layoutIfNeeded()
        }

        let themeReloadOnly = options?[Options.ThemeReloadOnlyKey] as? Bool ?? false
        if !themeReloadOnly {
            DispatchQueue.main.async { [weak self] in
                guard let self = self else {
                    return
                }
                self.toxManager.bootstrap.addPredefinedNodes()
                // KHANDAQ (#H4): burst the proven TCP relays at cold launch (every app update forces a
                // cold start) so first-connect doesn't wait ~20s for the offline-fallback re-seed.
                self.toxManager.bootstrap.performKhandaqBootstrapBurst()
                self.toxManager.bootstrap.bootstrap()
                self.startNetworkMonitoringAndPushBinding()
            }
        } else {
            startNetworkMonitoringAndPushBinding()
        }

        updateUserAvatar()
        updateUserName()
        updatePhoneNetworkState()

        switch toShow {
            case .none:
                // KHANDAQ (#98): on a theme reload come back to the screen the person was on (the
                // toggle lives in Settings); a fresh session starts on the Air screen.
                let isThemeReload = options?[Options.ThemeReloadOnlyKey] as? Bool ?? false
                if case .iPhone = InterfaceIdiom.current(),
                   isThemeReload,
                   let index = options?[Options.SelectedTabIndexKey] as? Int,
                   let screen = IphoneObjects.Screen(rawValue: index) {
                    showPhoneScreen(screen)
                }
            case .settings:
                showSettings(options: settingsOptions, animated: false)
        }

        // A rebuild for the theme carries the first options again: it must not take the keyboard.
        if !themeReloadOnly, options?[ActiveSessionCoordinator.focusCommandLineKey] as? Bool == true {
            switch InterfaceIdiom.current() {
                case .iPhone:
                    iPhone.chatsCoordinator.listController?.focusesCommandLineOnAppear = true
                case .iPad:
                    iPad.chatsCoordinator.listController?.focusesCommandLineOnAppear = true
            }
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
            case .openGroupInvite:
                notificationCoordinatorShowGroupInvites(notificationCoordinator)
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
            alert.addAction(UIAlertAction(title: String(localized: "air_share_import_action"), style: .default) { [unowned self] _ -> Void in
                self.confirmImport(url)
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
                iPhone.navigationController.present(alert, animated: true, completion: nil)
            case .iPad:
                iPad.splitController.present(alert, animated: true, completion: nil)
        }
    }

    /// Moving a profile in from a file logs out of this one first: said before it happens (E21), in the
    /// words of Settings > Transfer.
    private func confirmImport(_ url: URL) {
        let host: UIViewController
        switch InterfaceIdiom.current() {
            case .iPhone:
                host = iPhone.navigationController
            case .iPad:
                host = iPad.splitController
        }
        let message = ProfileDeviceKey.activeProfileHasKey ? "air_settings_import_message_device" : "air_settings_import_message"
        let sheet = AirConfirm.sheet(title: String(localized: "import_profile"),
                                     message: String(localized: message),
                                     verb: String(localized: "air_share_import_verb"),
                                     style: .default,
                                     source: host.view) { [weak self] in
            self?.logout(importToxProfileFromURL: url)
        }
        host.present(sheet, animated: true, completion: nil)
    }

    /// KHANDAQ (redesign stage 5): what another app shared through the extension waits in the app group's
    /// inbox, and the app asks where it goes as it comes up. The choice is the one of Forward: Saved
    /// Messages, then the people and groups of the latest chats. Before, only groups were offered.
    func processPendingShareIfNeeded() {
        guard !isPresentingSharePicker else {
            return
        }

        guard let payload = ShareInboxStorage.loadPendingShare() else {
            return
        }

        pendingSharePayload = payload
        isPresentingSharePicker = true

        var presenter = rootViewController()
        while let presented = presenter.presentedViewController, !presented.isBeingDismissed {
            presenter = presented
        }
        let sheet = shareTargetsSheet(for: payload, source: presenter.view)
        presenter.present(sheet, animated: true, completion: nil)
        // A screen still coming or going refuses the sheet: the next time the app is active asks again.
        if sheet.presentingViewController == nil {
            pendingSharePayload = nil
            isPresentingSharePicker = false
        }
    }

    /// The chats a share can go to, in the order of Forward, under what is shared.
    private func shareTargetsSheet(for payload: ShareInboxPayload, source: UIView) -> UIAlertController {
        let style: UIAlertControllerStyle = InterfaceIdiom.current() == .iPad ? .alert : .actionSheet
        let sheet = UIAlertController(title: String(localized: "air_share_in_title"),
                                      message: ShareInboxStorage.summary(of: payload),
                                      preferredStyle: style)
        sheet.popoverPresentationController?.sourceView = source
        sheet.popoverPresentationController?.sourceRect = CGRect(x: source.bounds.midX, y: source.bounds.midY, width: 0, height: 0)

        sheet.addAction(UIAlertAction(title: String(localized: "saved_messages_title"), style: .default) { [weak self] _ in
            guard let self = self, let saved = self.toxManager.objects.getOrCreateSavedMessagesChat() else {
                return
            }
            self.deliverPendingShare(to: saved)
        })
        let chats = toxManager.objects.chats().sortedResultsUsingProperty("lastActivityDateInterval", ascending: false)
        var offered = 0
        for index in 0..<chats.count where offered < 25 {
            let chat = chats[index]
            if chat.isSavedMessages {
                continue
            }
            offered += 1
            sheet.addAction(UIAlertAction(title: MessageForwarder.displayName(for: chat), style: .default) { [weak self] _ in
                self?.deliverPendingShare(to: chat)
            })
        }
        sheet.addAction(UIAlertAction(title: String(localized: "alert_cancel"), style: .cancel) { [weak self] _ in
            self?.finishPendingShare()
        })
        return sheet
    }

    private func deliverPendingShare(to chat: OCTChat) {
        if let payload = pendingSharePayload {
            sendSharePayload(payload, to: chat)
        }
        finishPendingShare()
        showChat(chat)
    }

    /// The question is answered: what it was about leaves the inbox, and a share that came in while it
    /// waited gets its own question.
    private func finishPendingShare() {
        if let payload = pendingSharePayload {
            ShareInboxStorage.clearPendingShare(payload)
        }
        pendingSharePayload = nil
        isPresentingSharePicker = false
        DispatchQueue.main.async { [weak self] in
            self?.processPendingShareIfNeeded()
        }
    }

    func presentGroupJoin(chatIdHex: String) {
        switch InterfaceIdiom.current() {
            case .iPhone:
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

            // KHANDAQ (#iOS connect-pill): went offline: allow the reconcile poll to clear the pill again
            // if the next reconnect's transition is also missed.
            didReportConnectedPill = false
            startConnectPillReconcile()

            NetworkDiagnosticsLog.log("self_offline", detail: "rebootstrap")
            ConnectionQualityMonitor.shared.onBootstrapStarted()
            toxManager.bootstrap.rebootstrapOnNetworkChange()
        } else {
            // Connected: flash green "Online", then auto-hide.
            didReportConnectedPill = true
            connectPillReconcileTimer?.invalidate()
            connectPillReconcileTimer = nil
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
        // E12: the invitation waits as a card in Requests until it is answered, and survives a restart.
        // Only one from someone who is not a contact (nothing to join through later) still asks at once.
        guard let inviterKey = friendPublicKey(for: friendNumber) else {
            showGroupInviteAlert(friendNumber: friendNumber, inviteData: inviteData, groupName: groupName)
            return
        }
        let cleanName = (groupName?.isEmpty == false && groupName != "(null)") ? (groupName ?? "") : ""
        let invite = AirGroupInvites.shared.add(inviterKey: inviterKey, groupName: cleanName, data: inviteData)
        // E22: it also comes as a notification of its own kind, so it is not found only by chance.
        notificationCoordinator.notifyGroupInvite(invite)
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

    func notificationCoordinatorShowGroupInvites(_ coordinator: NotificationCoordinator) {
        switch InterfaceIdiom.current() {
            case .iPhone:
                // Invitations are answered right in the Air list, with the requests.
                iPhone.navigationController.popToRootViewController(animated: false)
                iPhone.chatsCoordinator.listController?.showRequests()
            case .iPad:
                primaryIpadControllerShowFriends(iPad.primaryController)
        }
    }

    func notificationCoordinatorAnswerIncomingCall(_ coordinator: NotificationCoordinator, userInfo: String) {
        callCoordinator.answerIncomingCallWithUserInfo(userInfo)
    }

    func notificationCoordinator(_ coordinator: NotificationCoordinator, updateFriendsBadge badge: Int) {
        let text: String? = (badge > 0) ? "\(badge)" : nil

        switch InterfaceIdiom.current() {
            case .iPhone:
                // The Air screen counts requests in its own header; there is no tab to badge.
                break
            case .iPad:
                iPad.primaryController.friendsBadgeText = text
                break
        }
    }

    func notificationCoordinator(_ coordinator: NotificationCoordinator, updateChatsBadge badge: Int) {
        switch InterfaceIdiom.current() {
            case .iPhone, .iPad:
                // The Air screen shows unread counts on its rows; there is no tab to badge.
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

    func friendsTabCoordinatorShowRequests(_ coordinator: FriendsTabCoordinator) {
        switch InterfaceIdiom.current() {
            case .iPhone:
                iPhone.navigationController.popToRootViewController(animated: true)
                iPhone.chatsCoordinator.listController?.showRequests()
            case .iPad:
                primaryIpadControllerShowFriends(iPad.primaryController)
                friendsCoordinator.navigationController.popToRootViewController(animated: true)
        }
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

    func chatsTabCoordinatorShowProfile(_ coordinator: ChatsTabCoordinator) {
        showProfile(animated: true)
    }

    func chatsTabCoordinatorShowSettings(_ coordinator: ChatsTabCoordinator) {
        showSettings(options: nil, animated: true)
    }

    func chatsTabCoordinator(_ coordinator: ChatsTabCoordinator, showAddContactWithAddress address: String?) {
        if case .iPad = InterfaceIdiom.current() {
            primaryIpadControllerShowFriends(iPad.primaryController)
        }
        friendsCoordinator.showAddFriend(prefilledAddress: address, animated: true)
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
        // Log out on "You" asks for the profile password next time (E17); see SessionAutoLogin.
        SessionAutoLogin.isLoggedOutByUser = true
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
        // (PDF, doc...) has no gallery index, so fall through to QuickLook, which previews everything.
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

    // KHANDAQ (user request 17.08): the chat's attachments, split into files / audio / voice.
    func chatPrivateControllerShowAttachments(_ controller: ChatPrivateController, forChat chat: OCTChat) {
        let attachments = ChatAttachmentsController(theme: theme, chat: chat,
                                                    submanagerObjects: toxManager.objects)
        // Push onto whatever stack is showing the chat: on iPad that is the detail column, and in the
        // group-info preview it is that screen's own navigation controller.
        controller.navigationController?.pushViewController(attachments, animated: true)
    }

    func chatPrivateControllerShowFriendProfile(_ controller: ChatPrivateController, forFriend friend: OCTFriend) {
        // iPad detail flow keeps the friend card reachable from the primary column instead.
    }

    // KHANDAQ (redesign stage 2): "Remove" in the dossier. On iPad the conversation is the root of the
    // detail column, so the column shows the contacts instead; the contact goes once it is off screen.
    func chatPrivateControllerRemoveContact(_ controller: ChatPrivateController, friend: OCTFriend) {
        guard let toxManager = toxManager else {
            return
        }
        let navigation = controller.navigationController
        if let navigation = navigation, let index = navigation.viewControllers.firstIndex(of: controller), index > 0 {
            navigation.popToViewController(navigation.viewControllers[index - 1], animated: true)
        }
        else if InterfaceIdiom.current() == .iPad {
            primaryIpadControllerShowFriends(iPad.primaryController)
        }
        AirContactRemoval.afterTransition(of: navigation) {
            AirContactRemoval.remove(friend, submanagerChats: toxManager.chats, submanagerFriends: toxManager.friends)
        }
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
            ShareInboxStorage.clearPendingShare(payload)
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

            if let payload = self.pendingSharePayload {
                ShareInboxStorage.clearPendingShare(payload)
            }
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
                guard let navigationController = phoneNavigationController else {
                    preconditionFailure("The phone stack is created in init")
                }
                let chatsCoordinator = ChatsTabCoordinator(theme: theme, submanagerObjects: toxManager.objects, submanagerChats: toxManager.chats, submanagerGroups: toxManager.groups, submanagerFiles: toxManager.files, submanagerUser: toxManager.user, submanagerFriends: toxManager.friends, navigationController: navigationController)
                chatsCoordinator.delegate = self

                iPhone = IphoneObjects(chatsCoordinator: chatsCoordinator, navigationController: navigationController)

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
                presentingController = iPhone.navigationController
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

    func showFriendRequest(_ request: OCTFriendRequest) {
        switch InterfaceIdiom.current() {
            case .iPhone:
                // Requests are answered right in the Air list.
                iPhone.navigationController.popToRootViewController(animated: false)
                iPhone.chatsCoordinator.listController?.showRequests()
            case .iPad:
                primaryIpadControllerShowFriends(iPad.primaryController)
                friendsCoordinator.showRequest(request, animated: false)
        }
    }

    /**
        Returns active chat controller if it is visible, nil otherwise.
     */
    func activeChatController() -> ChatPrivateController? {
        switch InterfaceIdiom.current() {
            case .iPhone:
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

    /// KHANDAQ (redesign stage 3, E14): the group dossier; the old settings screen opens from its
    /// "Group settings" row.
    func showGroupInfo(_ chat: OCTChat) {
        let infoController = AirGroupDossierController(
                theme: theme,
                chat: chat,
                submanagerGroups: toxManager.groups,
                submanagerObjects: toxManager.objects,
                submanagerChats: toxManager.chats,
                submanagerFiles: toxManager.files,
                submanagerFriends: toxManager.friends)
        infoController.onOpenFriend = { [weak self] friend in
            self?.openChatWithSenderPublicKey(friend.publicKey)
        }

        switch InterfaceIdiom.current() {
            case .iPhone:
                iPhone.navigationController.pushViewController(infoController, animated: true)
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
        // KHANDAQ (#15): the invited group name can arrive nil or as the literal "(null)"; fall
        // back to the default title instead of showing "(null)" in the alert.
        let cleanGroupName = (groupName?.isEmpty == false && groupName != "(null)") ? groupName : nil
        let resolvedGroupName = cleanGroupName ?? String(localized: "group_chat_default_title")
        let title = String(localized: "group_invite_alert_title")
        // KHANDAQ (#15): String(localized:) is a custom initializer that ALREADY runs String(format:)
        // on the localized string with the given arguments. The old code called String(localized:)
        // with NO arguments, so its internal format ran "%@ ... %@" with zero args and gave "(null) ... (null)",
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
                base = iPhone.navigationController
            case .iPad:
                base = iPad.splitController
        }
        var presenter = base
        while let presented = presenter.presentedViewController, !presented.isBeingDismissed {
            presenter = presented
        }
        presenter.present(alert, animated: true, completion: nil)
    }

    func friendPublicKey(for friendNumber: OCTToxFriendNumber) -> String? {
        let friends = toxManager.objects.friends()
        for index in 0..<friends.count {
            if let friend = friends[index] as? OCTFriend, !friend.isInvalidated, friend.friendNumber == friendNumber {
                return friend.publicKey
            }
        }
        return nil
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

    /// `options`: what Settings should open with after a rebuild (the advanced screen, for one).
    func showSettings(options settingsOptions: CoordinatorOptions?, animated: Bool) {
        switch InterfaceIdiom.current() {
            case .iPhone:
                settingsCoordinator.pushSettings(options: settingsOptions, animated: animated)
            case .iPad:
                primaryIpadControllerShowFriends(iPad.primaryController)
        }
    }

    func showProfile(animated: Bool) {
        switch InterfaceIdiom.current() {
            case .iPhone:
                profileCoordinator.pushProfile(animated: animated)
            case .iPad:
                primaryIpadControllerShowProfile(iPad.primaryController)
        }
    }

    /// Brings back a phone screen after a theme reload, without animation.
    func showPhoneScreen(_ screen: IphoneObjects.Screen) {
        switch screen {
            case .air:
                break
            case .settings:
                showSettings(options: nil, animated: false)
            case .profile:
                showProfile(animated: false)
            case .appearance:
                showSettings(options: SettingsTabCoordinator.appearanceOptions, animated: false)
        }
    }

    /// The phone's "you" block says how this device is connected.
    func updatePhoneNetworkState() {
        let state = AirNetworkState(connection: toxManager.user.connectionStatus,
                                    networkReachable: networkReachabilityMonitor.isReachable)
        // Conversations and the Network screen follow it on every device (E4).
        AirSelfNetwork.update(state)
        guard case .iPhone = InterfaceIdiom.current(), let iPhone = iPhone else {
            return
        }
        iPhone.chatsCoordinator.listController?.setNetworkState(state)
    }

    func updateUserStatusView() {
        switch InterfaceIdiom.current() {
            case .iPhone:
                updatePhoneNetworkState()
            case .iPad:
                // Conversations follow our node here too (E4); the iPad keeps its own status view.
                updatePhoneNetworkState()
                iPad.primaryController.userStatus = UserStatus(connectionStatus: toxManager.user.connectionStatus,
                                                               userStatus: toxManager.user.userStatus)
        }
    }

    func updateUserAvatar() {
        switch InterfaceIdiom.current() {
            case .iPhone:
                iPhone.chatsCoordinator.listController?.refreshSelf()
            case .iPad:
                var avatar: UIImage?

                if let avatarData = toxManager.user.userAvatar() {
                    avatar = UIImage(data: avatarData)
                }

                iPad.primaryController.userAvatar = avatar
        }
    }

    func updateUserName() {
        switch InterfaceIdiom.current() {
            case .iPhone:
                iPhone.chatsCoordinator.listController?.refreshSelf()
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
                return iPhone.navigationController
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

    /// Sends what was shared into `chat`: a group and a person through the calls of their composers,
    /// Saved Messages kept on this device, as its composer keeps it.
    func sendSharePayload(_ payload: ShareInboxPayload, to chat: OCTChat) {
        for item in payload.items {
            switch item.type {
                case .text:
                    guard let text = item.text?.trimmingCharacters(in: .whitespacesAndNewlines), !text.isEmpty else {
                        continue
                    }

                    if chat.isSavedMessages {
                        toxManager.objects.addSavedTextMessage(text, to: chat)
                    }
                    else if chat.isGroup {
                        for part in AirComposeLimit.parts(of: text) {
                            toxManager.groups.sendMessage(to: chat, text: part, type: .normal, successBlock: { _ in
                            }, failureBlock: { error in
                                DispatchQueue.main.async {
                                    handleErrorWithType(.sendMessageToFriend, error: error as NSError?)
                                }
                            })
                        }
                    }
                    else {
                        sendSharedText(AirComposeLimit.parts(of: text), toPerson: chat)
                    }

                case .file:
                    let path = ShareInboxStorage.absolutePath(for: item) ?? pendingOpenInFilePath
                    guard let filePath = path else {
                        continue
                    }

                    if chat.isSavedMessages {
                        keepSharedFileInSaved(filePath, fileName: item.fileName ?? (filePath as NSString).lastPathComponent, chat: chat)
                    }
                    else if chat.isGroup {
                        toxManager.groups.sendFile(atPath: filePath, to: chat, moveToUploads: true, successBlock: { _ in
                        }, failureBlock: { error in
                            DispatchQueue.main.async {
                                handleErrorWithType(.sendFileToFriend, error: error as NSError?)
                            }
                        })
                    }
                    else {
                        // objcTox may answer from its own queue; the alert belongs on the main one.
                        toxManager.files.sendFile(atPath: filePath, moveToUploads: true, to: chat, failureBlock: { error in
                            DispatchQueue.main.async {
                                handleErrorWithType(.sendFileToFriend, error: error as NSError)
                            }
                        })
                    }
            }
        }

        pendingOpenInFilePath = nil
    }

    /// A text to a person, as its composer sends one, in the parts that each fit a message: with the
    /// wake-up signal ten seconds after the last part.
    private func sendSharedText(_ parts: [String], toPerson chat: OCTChat) {
        for (index, part) in parts.enumerated() {
            let isLast = index == parts.count - 1
            toxManager.chats.sendMessage(to: chat, text: part, type: .normal, successBlock: { [weak self] _ in
                guard isLast else {
                    return
                }
                DispatchQueue.main.asyncAfter(deadline: .now() + 10) {
                    // The contact (and with it the chat) may have been removed in the meantime.
                    guard let self = self, !chat.isInvalidated else {
                        return
                    }
                    self.toxManager.chats.sendMessagePush(to: chat)
                }
            }, failureBlock: { error in
                DispatchQueue.main.async {
                    handleErrorWithType(.sendMessageToFriend, error: error as NSError?)
                }
            })
        }
    }

    /// A shared file kept in Saved Messages: moved out of the inbox into the folder of Saved files.
    private func keepSharedFileInSaved(_ path: String, fileName: String, chat: OCTChat) {
        guard let directory = ChatInputViewManager.savedFilesDirectory() else {
            return
        }
        let destination = directory.appendingPathComponent("\(UUID().uuidString)_\(fileName)")
        guard (try? FileManager.default.moveItem(atPath: path, toPath: destination.path)) != nil else {
            handleErrorWithType(.sendFileToFriend)
            return
        }
        ChatInputViewManager.recordSavedFile(atPath: destination.path, fileName: fileName, to: chat, objects: toxManager.objects)
    }

    func sendFile(_ filePath: String, toChat chat: OCTChat) {
        showChat(chat)

        toxManager.files.sendFile(atPath: filePath, moveToUploads: true, to: chat, failureBlock: { (error: Error) in
            handleErrorWithType(.sendFileToFriend, error: error as NSError)

        })
    }
}
