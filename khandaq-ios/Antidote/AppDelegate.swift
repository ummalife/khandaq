// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit
import Firebase
import os

@UIApplicationMain
class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?
    let gcmMessageIDKey = "gcm.message_id"
    var coordinator: AppCoordinator!
    private var pendingOpenURL: URL?
    let callManager = CallManager()
    lazy var providerDelegate: ProviderDelegate = ProviderDelegate(callManager: self.callManager)
    var backgroundTask: UIBackgroundTaskIdentifier = UIBackgroundTaskInvalid
    // KHANDAQ (#164): when the app entered background, to decide if the DHT is stale on return.
    private var enteredBackgroundAt: Date?
    var gps_was_stopped_by_forground: Bool = false
    static var lastStartGpsTS: Int64 = 0
    static var location_sharing_contact_pubkey: String = "-1"

    class var shared: AppDelegate {
      return UIApplication.shared.delegate as! AppDelegate
    }
    
    func displayIncomingCall(uuid: UUID, handle: String, hasVideo: Bool = false, completion: ((NSError?) -> Void)?) {
      providerDelegate.reportIncomingCall(uuid: uuid, handle: handle, hasVideo: hasVideo, completion: completion)
    }
    
    func endIncomingCalls() {
        providerDelegate.endIncomingCalls()
    }

    func applicationWillEnterForeground(_ application: UIApplication) {
        os_log("AppDelegate:applicationWillEnterForeground")
        UIApplication.shared.endBackgroundTask(self.backgroundTask)
        self.backgroundTask = UIBackgroundTaskInvalid

        if ToxOptionsRestartScheduler.isRestartInProgress {
            ToxOptionsRestartScheduler.scheduleRestartAttempt()
            return
        }

        if !ToxOptionsRestartScheduler.isRestartInProgress,
           let runcoord = coordinator?.activeCoordinator as? RunningCoordinator,
           let session = runcoord.activeSessionCoordinator,
           let toxManager = session.toxManager {
            // KHANDAQ (#164): after a real suspension the DHT connections are dead but toxcore only
            // notices via slow timeouts (~2 min before the peer sees us online again). An urgent
            // re-bootstrap (debounced, same call the background-fetch path uses) reconnects in
            // seconds. Skip for quick app switches where the connection is still alive.
            if let backgroundedAt = enteredBackgroundAt, Date().timeIntervalSince(backgroundedAt) > 30 {
                toxManager.bootstrap.rebootstrapOnNetworkChange()
            }
            toxManager.friends.refreshConnectionStatuses()
            toxManager.chats.sendOwnPush()
            // KHANDAQ (re-audit 2026-08-22, K-01): make sure every connected contact has a
            // capability of its own before the URLs go out, then broadcast.
            //
            // Registration is a network round trip and a wait for the relay's challenge push, so it
            // cannot block coming to the foreground. The broadcast below therefore goes out with
            // whatever is ready — nothing at all on the very first run, which is exactly the
            // behaviour that existed before capabilities — and re-broadcasts once registration
            // lands. A contact never waits on the relay to hold a working push URL.
            ensurePushCapabilities(for: session, toxManager: toxManager)
            toxManager.chats.broadcastOwnPushURLToConnectedFriends()
            QaCommandHandler.consumePendingCommands(coordinator: session)
        }
        enteredBackgroundAt = nil

        coordinator?.processPendingShareIfNeeded()

        gps_was_stopped_by_forground = true
        let gps = LocationManager.shared
        if !gps.isHasAccess() {
            os_log("AppDelegate:applicationWillEnterForeground:gps:no_access")
        } else if gps.state == .Monitoring {
            os_log("AppDelegate:applicationWillEnterForeground:gps:STOP")
            gps.stopMonitoring()
        }

        os_log("AppDelegate:applicationWillEnterForeground:DidEnterBackground:2:END")
    }

    func applicationDidBecomeActive(_ application: UIApplication) {
        coordinator?.processPendingShareIfNeeded()
    }

    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplicationLaunchOptionsKey: Any]?) -> Bool {
        // KHANDAQ (#248): resolve the app language before a single view exists, so the first frame
        // is already in the right language and layout direction.
        AppLanguage.applyAtLaunch()

        window = UIWindow(frame: UIScreen.main.bounds)
        window?.backgroundColor = ThemeAppearance.placeholderBackgroundColor

        let launchPlaceholder = UIStoryboard(name: "LaunchPlaceholderBoard", bundle: Bundle.main)
            .instantiateViewController(withIdentifier: "LaunchPlaceholderController")
        window?.rootViewController = launchPlaceholder
        window?.makeKeyAndVisible()

        // iOS keeps the launch screen until this method returns — defer heavy init
        // so the placeholder (logo + spinner) appears immediately.
        DispatchQueue.main.async { [weak self] in
            self?.finishApplicationLaunch(application, launchOptions: launchOptions)
        }

        return true
    }

    private func finishApplicationLaunch(_ application: UIApplication,
                                         launchOptions: [UIApplicationLaunchOptionsKey: Any]?) {
        LaunchRecovery.prepareForLaunch()
        log("didFinishLaunchingWithOptions")
        os_log("AppDelegate:didFinishLaunchingWithOptions:start")

        configureLoggingStuff()

        if ProcessInfo.processInfo.arguments.contains("UI_TESTING") {
            // Speeding up animations for UI tests.
            window?.layer.speed = 1000
        }

        coordinator = AppCoordinator(window: window!)
        coordinator.startWithOptions(nil)

        if let url = pendingOpenURL {
            pendingOpenURL = nil
            coordinator.handleInboxURL(url)
        }

        if let notification = launchOptions?[UIApplicationLaunchOptionsKey.localNotification] as? UILocalNotification {
            coordinator.handleLocalNotification(notification)
        }

        if let remoteNotification = launchOptions?[UIApplicationLaunchOptionsKey.remoteNotification] as? [AnyHashable: Any] {
            coordinator.handleNotificationUserInfo(remoteNotification)
        }

        // Firebase + push registration block the main thread; defer until login UI is up.
        DispatchQueue.main.async { [weak self] in
            self?.configureFirebaseAndNotifications(application)
        }
        // HINT: try to go online every 47 minutes
        let bgfetchInterval: TimeInterval = 47 * 60
        application.setMinimumBackgroundFetchInterval(bgfetchInterval)
        os_log("AppDelegate:didFinishLaunchingWithOptions:end")
    }

    func applicationWillTerminate(_ application: UIApplication) {
        log("WillTerminate")
        os_log("AppDelegate:applicationWillTerminate")
    }

    func applicationDidReceiveMemoryWarning(_ application: UIApplication) {
        log("DidReceiveMemoryWarning")
        os_log("AppDelegate:applicationDidReceiveMemoryWarning")
    }

    func applicationDidEnterBackground(_ application: UIApplication) {
        log("DidEnterBackground")
        os_log("AppDelegate:applicationDidEnterBackground:start")
        enteredBackgroundAt = Date()

        if ToxOptionsRestartScheduler.isRestartInProgress {
            os_log("AppDelegate:applicationDidEnterBackground:skip:tox_restart")
            backgroundTask = UIApplication.shared.beginBackgroundTask(expirationHandler: { [weak self] in
                guard let self = self else { return }
                UIApplication.shared.endBackgroundTask(self.backgroundTask)
                self.backgroundTask = UIBackgroundTaskInvalid
            })
            DispatchQueue.main.asyncAfter(wallDeadline: DispatchWallTime.now() + 25) {
                UIApplication.shared.endBackgroundTask(self.backgroundTask)
                self.backgroundTask = UIBackgroundTaskInvalid
            }
            return
        }

        backgroundTask = UIApplication.shared.beginBackgroundTask (expirationHandler: { [weak self] in
            guard let self = self else { return }
            UIApplication.shared.endBackgroundTask(self.backgroundTask)
            self.backgroundTask = UIBackgroundTaskInvalid
            os_log("AppDelegate:applicationDidEnterBackground:3:expirationHandler:END")
        })

        DispatchQueue.main.asyncAfter(wallDeadline: DispatchWallTime.now() + 15) {
            if ToxOptionsRestartScheduler.isRestartInProgress {
                os_log("AppDelegate:applicationDidEnterBackground:PushSelf:skip:tox_restart")
                return
            }
            if (UserDefaultsManager().LongerbgMode == true) {
                guard let runcoord = self.coordinator?.activeCoordinator as? RunningCoordinator else {
                    os_log("AppDelegate:applicationDidEnterBackground:PushSelf:skip:not_running")
                    return
                }
                guard let toxManager = runcoord.activeSessionCoordinator?.toxManager else {
                    os_log("AppDelegate:applicationDidEnterBackground:PushSelf:skip:no_tox")
                    return
                }
                toxManager.chats.sendOwnPush()
            } else {
                os_log("AppDelegate:applicationDidEnterBackground:PushSelf:longer-bg-mode not active in settings")
            }
        }

        gps_was_stopped_by_forground = false
        let gps = LocationManager.shared
        // KHANDAQ (#14): the background location keepalive must only run when the user explicitly
        // enabled "Longer Background Mode" (same gate as sendOwnPush above). Otherwise location started
        // on every background once permission was ever granted and the status-bar location indicator
        // stayed on / never turned off.
        if UserDefaultsManager().LongerbgMode == true && gps.isHasAccess() {
            AppDelegate.lastStartGpsTS = Date().millisecondsSince1970
            gps.startMonitoring()
            os_log("AppDelegate:applicationDidEnterBackground:gps:START")
            DispatchQueue.main.asyncAfter(wallDeadline: DispatchWallTime.now() + (3 * 60)) {
                os_log("AppDelegate:applicationDidEnterBackground:4:gps:finishing")
                let gps = LocationManager.shared
                if !gps.isHasAccess() {
                    os_log("AppDelegate:applicationDidEnterBackground:4:gps:no_access")
                } else if gps.state == .Monitoring {
                    if (self.gps_was_stopped_by_forground == false) {

                        let diffTime = Date().millisecondsSince1970 - AppDelegate.lastStartGpsTS
                        os_log("AppDelegate:applicationDidEnterBackground:4:gps:Tlast=%ld", AppDelegate.lastStartGpsTS)
                        os_log("AppDelegate:applicationDidEnterBackground:4:gps:Tnow=%ld", Date().millisecondsSince1970)
                        os_log("AppDelegate:applicationDidEnterBackground:4:gps:Tdiff=%ld", diffTime)

                        if (diffTime > (((3 * 60) - 4) * 1000))
                        {
                            os_log("AppDelegate:applicationDidEnterBackground:4:gps:STOP")
                            gps.stopMonitoring()
                        } else {
                            os_log("AppDelegate:applicationDidEnterBackground:4:gps:STOP skipped, must be an old timer")
                        }
                    } else {
                        os_log("AppDelegate:applicationDidEnterBackground:4:gps:was stopped by forground, skipping")
                    }
                } else {
                    os_log("AppDelegate:applicationDidEnterBackground:4:gps:STOP skipped, gps was stopped already")
                }
            }
        } else {
            os_log("AppDelegate:applicationDidEnterBackground:gps:no_access")
        }

        DispatchQueue.main.asyncAfter(wallDeadline: DispatchWallTime.now() + 25) {
            UIApplication.shared.endBackgroundTask(self.backgroundTask)
            self.backgroundTask = UIBackgroundTaskInvalid
            os_log("AppDelegate:applicationDidEnterBackground:1:END")
        }
    }

    func application(_ application: UIApplication, performFetchWithCompletionHandler completionHandler: @escaping (UIBackgroundFetchResult) -> Void) {

        log("performFetchWithCompletionHandler:start")
        os_log("AppDelegate:performFetchWithCompletionHandler:start")

        if let runcoord = coordinator?.activeCoordinator as? RunningCoordinator,
           let session = runcoord.activeSessionCoordinator {
            NetworkDiagnosticsLog.log("background_fetch", detail: "rebootstrap")
            ConnectionQualityMonitor.shared.onBootstrapStarted()
            session.toxManager.bootstrap.rebootstrapOnNetworkChange()
            DispatchQueue.main.asyncAfter(deadline: .now() + 5) {
                completionHandler(.newData)
                log("performFetchWithCompletionHandler:end")
                os_log("AppDelegate:performFetchWithCompletionHandler:end")
            }
            return
        }

        completionHandler(.noData)
    }

    func application(_ application: UIApplication, didReceive notification: UILocalNotification) {
        coordinator.handleLocalNotification(notification)
    }

    func application(_ app: UIApplication, open url: URL, options: [UIApplicationOpenURLOptionsKey : Any]) -> Bool {
        handleIncomingURL(url)
        return true
    }

    func application(_ application: UIApplication, open url: URL, sourceApplication: String?, annotation: Any) -> Bool {
        handleIncomingURL(url)
        return true
    }

    private func handleIncomingURL(_ url: URL) {
        guard let coordinator = coordinator else {
            pendingOpenURL = url
            return
        }
        coordinator.handleInboxURL(url)
    }

  // Device received notification (legacy callback)
  //
  func application(_ application: UIApplication,
                   didReceiveRemoteNotification userInfo: [AnyHashable: Any]) {
    if let messageID = userInfo[gcmMessageIDKey] {
      log("Message ID: \(messageID)")
    }
  }

  // tells the app that a remote notification arrived that indicates there is data to be fetched.
  // ios 7+
  //
  func application(_ application: UIApplication,
                   didReceiveRemoteNotification userInfo: [AnyHashable: Any],
                   fetchCompletionHandler completionHandler: @escaping (UIBackgroundFetchResult)
                     -> Void) {
    // KHANDAQ (re-audit 2026-08-22, K-01): a push-capability registration challenge, not a wake.
    //
    // This is the proof that makes per-contact capabilities registrable at all: the relay pushes a
    // nonce to the FCM token, and only the device that owns that registration receives it. It is
    // data-only and must stay invisible — returning immediately keeps it from waking the Tox
    // service or spending the 25-second background budget below on a message that does not exist.
    if KhandaqPush.handleRegistrationChallenge(userInfo: userInfo) {
      os_log("AppDelegate:push capability challenge received")
      completionHandler(.noData)
      return
    }

    if let messageID = userInfo[gcmMessageIDKey] {
      log("Message ID: \(messageID)")
    }

    os_log("AppDelegate:didReceiveRemoteNotification:start")
    // HINT: we have 30 seconds here. use 25 of those 30 seconds to be on the safe side
    DispatchQueue.main.asyncAfter(deadline: .now() + 25) { [weak self] in
        completionHandler(UIBackgroundFetchResult.newData)
        os_log("AppDelegate:didReceiveRemoteNotification:start")
    }
  }

  // APNs failed to register the device for push notifications
  //
  func application(_ application: UIApplication,
                   didFailToRegisterForRemoteNotificationsWithError error: Error) {
    log("Unable to register for remote notifications: \(error.localizedDescription)")
  }

  func application(_ application: UIApplication,
                   didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
    log("APNs token retrieved: \(deviceToken)")
    os_log("AppDelegate:didRegisterForRemoteNotificationsWithDeviceToken")
  }
}

@available(iOS 10, *)
extension AppDelegate: UNUserNotificationCenterDelegate {

  // determine what to do if app is in foreground when a notification is coming
  // ios 10+ UNUserNotificationCenterDelegate method
  //
  func userNotificationCenter(_ center: UNUserNotificationCenter,
                              willPresent notification: UNNotification,
                              withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions)
                                -> Void) {
    let userInfo = notification.request.content.userInfo

    if let messageID = userInfo[gcmMessageIDKey] {
      log("Message ID: \(messageID)")
    }
    if UIApplication.shared.applicationState == .active {
      completionHandler([.sound])
    } else if #available(iOS 14.0, *) {
      completionHandler([.banner, .sound, .badge])
    } else {
      completionHandler([.alert, .sound, .badge])
    }
  }

  // Process and handle the user's response to a delivered notification.
  // ios 10+ UNUserNotificationCenterDelegate method
  //
  func userNotificationCenter(_ center: UNUserNotificationCenter,
                              didReceive response: UNNotificationResponse,
                              withCompletionHandler completionHandler: @escaping () -> Void) {
    let userInfo = response.notification.request.content.userInfo

    if let messageID = userInfo[gcmMessageIDKey] {
      log("Message ID: \(messageID)")
    }
    coordinator.handleNotificationUserInfo(userInfo)
    completionHandler()
  }

}

private extension AppDelegate {
    func configureLoggingStuff() {
        DDLog.add(DDASLLogger.sharedInstance())
        // DDLog.add(DDTTYLogger.sharedInstance())
    }

    func configureFirebaseAndNotifications(_ application: UIApplication) {
        guard FirebaseApp.app() == nil else {
            return
        }

        guard Bundle.main.path(forResource: "GoogleService-Info", ofType: "plist") != nil else {
            log("Firebase: GoogleService-Info.plist missing, skipping")
            return
        }

        FirebaseApp.configure()
        Messaging.messaging().delegate = self

        if #available(iOS 10.0, *) {
            UNUserNotificationCenter.current().delegate = self

            let authOptions: UNAuthorizationOptions = [.alert, .badge, .sound]
            UNUserNotificationCenter.current().requestAuthorization(
                options: authOptions,
                completionHandler: { _, _ in }
            )
        } else {
            let settings: UIUserNotificationSettings =
                UIUserNotificationSettings(types: [.alert, .badge, .sound], categories: nil)
            application.registerUserNotificationSettings(settings)
        }

        application.registerForRemoteNotifications()
    }
}

extension AppDelegate: MessagingDelegate {
  func messaging(_ messaging: Messaging, didReceiveRegistrationToken fcmToken: String?) {
    log("Firebase registration token: \(String(describing: fcmToken))")

    let dataDict: [String: String] = ["token": fcmToken ?? ""]
    NotificationCenter.default.post(
      name: Notification.Name("FCMToken"),
      object: nil,
      userInfo: dataDict
    )
  }
}

extension AppDelegate {
    /// Issue and register one capability per connected contact, then re-publish.
    ///
    /// Deliberately limited to CONNECTED contacts: a capability is only useful once the contact can
    /// receive the URL carrying it, and registering for a contact who has been offline for months
    /// would put a row in the relay for a relationship nothing is using. Offline contacts pick one
    /// up the next time both sides are up.
    func ensurePushCapabilities(for session: ActiveSessionCoordinator, toxManager: OCTManager) {
        guard let ownToken = Messaging.messaging().fcmToken, !ownToken.isEmpty else {
            return
        }
        // Indexed, not filter/map: Results is this app's own thin wrapper over RLMResults and is
        // not a Swift Sequence, so the functional forms do not exist on it. The predicate does the
        // filtering in Realm, which is also where it belongs.
        guard let objects = session.toxManager?.objects else { return }
        let results = objects.friends(predicate: NSPredicate(format: "isConnected == YES"))
        var connected: [String] = []
        for i in 0..<results.count {
            connected.append(results[i].publicKey)
        }
        guard !connected.isEmpty else { return }

        let group = DispatchGroup()
        var issuedAny = false
        for publicKey in connected {
            if KhandaqPush.capability(forFriend: publicKey, ownToken: ownToken) != nil {
                continue
            }
            group.enter()
            KhandaqPush.issueCapability(forFriend: publicKey, ownToken: ownToken) { cap in
                if cap != nil { issuedAny = true }
                group.leave()
            }
        }
        group.notify(queue: .main) {
            guard issuedAny else { return }
            // Now that the URLs would carry a capability, send them again.
            toxManager.chats.broadcastOwnPushURLToConnectedFriends()
        }
    }
}

// Convenience AppWide Simple Alert
extension AppDelegate {
    func alert(_ title: String, _ msg: String? = nil) {
        os_log("AppDelegate:alert")
        let cnt = UIAlertController(title: title, message: msg, preferredStyle: .alert)
        cnt.addAction(UIAlertAction(title: "Ok", style: .default, handler: { [weak cnt] act in
            cnt?.dismiss(animated: true, completion: nil)
        }))

        // guard let vc = AppDelegate.topViewController() else { return }
        // vc.present(cnt, animated: true, completion: nil)
    }
}

extension Date {
    var millisecondsSince1970: Int64 {
        Int64((self.timeIntervalSince1970 * 1000.0).rounded())
    }

    init(milliseconds: Int64) {
        self = Date(timeIntervalSince1970: TimeInterval(milliseconds) / 1000)
    }
}
