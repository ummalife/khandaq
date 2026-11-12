// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit

class AppCoordinator {
    fileprivate let window: UIWindow
    var activeCoordinator: TopCoordinatorProtocol? {
        didSet {
            deliverPendingInboxURL()
        }
    }
    /// A file or link handed over while no coordinator is up yet: at launch the profile opens in the
    /// background, so a file opened in Khandaq from another app waits for it instead of being dropped.
    fileprivate var pendingInboxURL: URL?
    fileprivate var theme: Theme
    fileprivate var pendingToxRestartOptions: CoordinatorOptions?
    fileprivate var toxRestartWorkItem: DispatchWorkItem?
    fileprivate var isReloadingAppearance = false
    /// The palette the interface was last built with, so a change of choice that keeps the same
    /// palette (dark to "as the system" on a dark phone) only restyles the window.
    fileprivate var appliedDarkMode: Bool
    fileprivate let systemAppearanceWatcher = ThemeSystemAppearanceWatcher(frame: .zero)

    init(window: UIWindow) {
        self.window = window

        theme = ThemeAppearance.resolvedTheme()
        appliedDarkMode = ThemeAppearance.isDarkMode
        ThemeChrome.applyWindowStyle(window, theme: theme)
        ThemeChrome.applyGlobalAppearance(theme)

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleThemePreferenceChange),
            name: ThemeAppearance.didChangeNotification,
            object: nil
        )

        // Following the system: the phone can switch appearance at any moment (the scheduled dark
        // mode at sunset, Control Centre). While the app is in the background iOS flips the traits
        // back and forth to take snapshots, so the check waits until the app is in front again.
        window.addSubview(systemAppearanceWatcher)
        systemAppearanceWatcher.onChange = { [weak self] in
            guard UIApplication.shared.applicationState != .background else {
                return
            }
            self?.reloadAppearanceIfPaletteChanged()
        }
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleWillEnterForeground),
            name: NSNotification.Name.UIApplicationWillEnterForeground,
            object: nil
        )

        // KHANDAQ (iPhone 11 Pro report, 17 Aug): switching the in-app language only changed PART of
        // the UI: the chat name went Arabic while the tab labels, the bottom bar, the search
        // placeholder and "Править" stayed Russian. The bundle swap works; the problem is that every
        // one of those strings is read ONCE when its view is built (tab bar items in
        // ActiveSessionCoordinator.createDeviceSpecificObjects, the filter tabs in
        // ChatListFilterBar.setupTabs, the placeholder in setupGlobalSearch), so nothing re-reads it.
        // SettingsMainController already posted this notification; it just had no subscriber
        // anywhere in the project. Rebuilding the session is exactly what the theme switch does, and
        // a rebuilt session reads every string again from the swapped bundle.
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleAppearanceChange),
            name: Notification.Name("KhandaqAppLanguageChanged"),
            object: nil
        )

        ToxOptionsRestartScheduler.setContinueRecreateHandler { [weak self] in
            self?.performToxRecreateIfReady()
        }
    }

    @objc func handleAppearanceChange() {
        reloadAppearance()
    }

    @objc func handleThemePreferenceChange() {
        reloadAppearanceIfPaletteChanged()
    }

    @objc func handleWillEnterForeground() {
        guard ThemeAppearance.preference == .system else {
            return
        }
        reloadAppearanceIfPaletteChanged()
    }

    /// Rebuilds the interface only when the palette really changes. Otherwise the window style is
    /// all that differs, and rebuilding the session for it would flash the whole screen for nothing.
    func reloadAppearanceIfPaletteChanged() {
        guard ThemeAppearance.isDarkMode != appliedDarkMode else {
            ThemeChrome.applyWindowStyle(window, theme: theme)
            return
        }
        reloadAppearance()
    }

    func reloadAppearance() {
        guard !isReloadingAppearance else {
            return
        }

        isReloadingAppearance = true

        let newTheme = ThemeAppearance.resolvedTheme()
        theme = newTheme
        appliedDarkMode = ThemeAppearance.isDarkMode
        window.backgroundColor = newTheme.colorForType(.NormalBackground)

        guard let running = activeCoordinator as? RunningCoordinator else {
            // No running session yet (login / create-account / import screens). There is no session
            // UI to rebuild, but the already-instantiated view hierarchy must still be recolored:
            // UIAppearance proxies only affect views created AFTER they are set, so without this the
            // login screen stayed white on toggle. Re-theme the live hierarchy in place, animated with
            // the same cross-dissolve as the running path so the switch is smooth.
            let transitionWindow = window
            UIView.transition(with: transitionWindow,
                              duration: ThemeChrome.transitionDuration,
                              options: [.transitionCrossDissolve, .allowAnimatedContent],
                              animations: {
                // KHANDAQ: flip the window interface style BEFORE re-theming: the search field
                // (.minimal) and tab-bar material are system-drawn from overrideUserInterfaceStyle.
                ThemeChrome.applyGlobalAppearance(newTheme)
                ThemeChrome.applyWindowStyle(transitionWindow, theme: newTheme)
                // The sign-in and first-launch pages (stage 4) draw with design tokens, which follow
                // the window style by themselves; painting them would put a background behind every
                // label on a plate.
                if !(self.activeCoordinator is LoginCoordinator) {
                    ThemeChrome.apply(to: transitionWindow.rootViewController, theme: newTheme)
                }
                transitionWindow.layoutIfNeeded()
            }, completion: { [weak self] _ in
                self?.isReloadingAppearance = false
            })
            return
        }

        let transitionWindow = window
        UIView.transition(with: transitionWindow,
                          duration: ThemeChrome.transitionDuration,
                          options: [.transitionCrossDissolve, .allowAnimatedContent],
                          animations: {
            // KHANDAQ: reloadTheme rebuilds the whole session from scratch, exactly like a fresh
            // launch / app restart, which the user confirms renders the theme correctly everywhere.
            // We must NOT then run ThemeChrome.apply over that freshly-built hierarchy: its generic
            // recursion clobbers system controls' private subviews (blank search pill, dark square box
            // behind UISwitch knobs, mis-tinted cell chevrons): every theme-toggle glitch traced back
            // to this. Set the window style + global appearance proxies first, rebuild, and stop.
            ThemeChrome.applyGlobalAppearance(newTheme)
            ThemeChrome.applyWindowStyle(transitionWindow, theme: newTheme)
            running.reloadTheme(newTheme)
            transitionWindow.layoutIfNeeded()
        }, completion: { [weak self] _ in
            self?.isReloadingAppearance = false
        })
    }
}

// MARK: CoordinatorProtocol
extension AppCoordinator: TopCoordinatorProtocol {
    func startWithOptions(_ options: CoordinatorOptions?) {
        showRestartPlaceholder()
        recreateActiveCoordinator(options: options)
    }

    func handleLocalNotification(_ notification: UILocalNotification) {
        activeCoordinator?.handleLocalNotification(notification)
    }

    func handleNotificationUserInfo(_ userInfo: [AnyHashable: Any]) {
        activeCoordinator?.handleNotificationUserInfo(userInfo)
    }

    func handleInboxURL(_ url: URL) {
        guard let coordinator = activeCoordinator else {
            pendingInboxURL = url
            return
        }
        coordinator.handleInboxURL(url)
    }

    /// Hands a waiting file to the coordinator that has just come up, once its screens are in the
    /// window.
    fileprivate func deliverPendingInboxURL() {
        guard pendingInboxURL != nil, activeCoordinator != nil else {
            return
        }
        DispatchQueue.main.async { [weak self] in
            guard let self = self, let url = self.pendingInboxURL, let coordinator = self.activeCoordinator else {
                return
            }
            self.pendingInboxURL = nil
            coordinator.handleInboxURL(url)
        }
    }

    func processPendingShareIfNeeded() {
        if let runningCoordinator = activeCoordinator as? RunningCoordinator {
            runningCoordinator.processPendingShareIfNeeded()
        }
    }
}

extension AppCoordinator: RunningCoordinatorDelegate {
    func runningCoordinatorDidLogout(_ coordinator: RunningCoordinator, importToxProfileFromURL: URL?) {
        // KHANDAQ (#11 Fix B): keep the keychain on a plain logout so recreateActiveCoordinator
        // auto-logs back into the existing profile instead of onboarding (after Log out on "You" it
        // opens the login form, see SessionAutoLogin). Only wipe for import-on-logout.
        if let url = importToxProfileFromURL {
            KeychainManager().deleteActiveAccountData()
            // The file goes to the sign-in pages straight away. Recreating would sign back in to the
            // profile being left whenever it opens without the stored password: a profile made
            // without a password keeps its key in the keychain (stage 4), and an old unencrypted one
            // needs nothing, so the import was dropped for both.
            let coordinator = createLoginCoordinator(nil)
            activeCoordinator = coordinator
            coordinator.handleInboxURL(url)
            return
        }

        recreateActiveCoordinator()
    }

    func runningCoordinatorDeleteProfile(_ coordinator: RunningCoordinator) {
        let userDefaults = UserDefaultsManager()
        let profileManager = ProfileManager()

        let name = userDefaults.lastActiveProfile!

        do {
            try profileManager.deleteProfileWithName(name)

            KeychainManager().deleteActiveAccountData()
            userDefaults.lastActiveProfile = nil

            recreateActiveCoordinator()
        }
        catch let error as NSError {
            handleErrorWithType(.deleteProfile, error: error)
        }
    }

    func runningCoordinatorRecreateCoordinatorsStack(_ coordinator: RunningCoordinator, options: CoordinatorOptions) {
        pendingToxRestartOptions = options
        beginToxOptionsRestart()
    }
}

extension AppCoordinator: LoginCoordinatorDelegate {
    func loginCoordinatorDidLogin(_ coordinator: LoginCoordinator, manager: OCTManager, password: String, options: CoordinatorOptions?) {
        let keychainManager = KeychainManager()
        keychainManager.toxPasswordForActiveAccount = password
        // KHANDAQ (audit #11): the profile password is strictly stronger than the 4-digit pin, so a
        // successful password login is proof the owner is here, so drop any running pin lockout, else the
        // owner can be refused for up to an hour with no way out.
        keychainManager.failedPinAttemptsNumber = nil
        keychainManager.pinLockedUntil = nil
        // The password was typed: from now on the profile opens by itself again.
        SessionAutoLogin.isLoggedOutByUser = false

        let leaving = window.snapshotView(afterScreenUpdates: false)
        recreateActiveCoordinator(options: options, manager: manager, skipAuthorizationChallenge: true)
        if let leaving = leaving {
            revealSession(from: leaving)
        }
    }

    /// A successful sign-in opens into Air (E20): the page parts towards the person and fades, and
    /// the session is under it. With reduced motion it only fades. The session's own view is not
    /// moved: the window lays it out.
    func revealSession(from leaving: UIView) {
        leaving.frame = window.bounds
        window.addSubview(leaving)
        let opens = !UIAccessibilityIsReduceMotionEnabled()
        let animator = UIViewPropertyAnimator(duration: KQMotion.duration(KQMotion.reveal), timingParameters: KQMotion.easeOut)
        animator.addAnimations {
            leaving.alpha = 0
            if opens {
                leaving.transform = CGAffineTransform(scaleX: 1.04, y: 1.04)
            }
        }
        animator.addCompletion { _ in
            leaving.removeFromSuperview()
        }
        animator.startAnimation()
    }
}

// MARK: Private
private extension AppCoordinator {
    func showRestartPlaceholder() {
        let storyboard = UIStoryboard(name: "LaunchPlaceholderBoard", bundle: Bundle.main)
        window.rootViewController = storyboard.instantiateViewController(withIdentifier: "LaunchPlaceholderController")
    }

    func teardownActiveRunningCoordinator() {
        toxRestartWorkItem?.cancel()
        toxRestartWorkItem = nil

        if let running = activeCoordinator as? RunningCoordinator {
            running.shutdownForToxRestart()
        }
        activeCoordinator = nil
    }

    func beginToxOptionsRestart() {
        toxRestartWorkItem?.cancel()
        showRestartPlaceholder()
        teardownActiveRunningCoordinator()
        scheduleToxRecreateAttempt()
    }

    func scheduleToxRecreateAttempt() {
        guard pendingToxRestartOptions != nil else {
            return
        }

        let work = DispatchWorkItem { [weak self] in
            self?.performToxRecreateIfReady()
        }
        toxRestartWorkItem = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5, execute: work)
    }

    func performToxRecreateIfReady() {
        guard let options = pendingToxRestartOptions else {
            return
        }
        guard ToxOptionsRestartScheduler.isRestartInProgress else {
            return
        }
        guard UIApplication.shared.applicationState == .active else {
            ToxOptionsRestartScheduler.deferRecreate()
            return
        }

        toxRestartWorkItem = nil

        recreateActiveCoordinator(options: options,
                                   skipAuthorizationChallenge: true,
                                   onToxOptionsRestartComplete: { [weak self] success in
            guard let self = self else {
                ToxOptionsRestartScheduler.restartFailed()
                return
            }

            self.pendingToxRestartOptions = nil

            if success {
                ToxOptionsRestartScheduler.restartCompleted()
            } else {
                ToxOptionsRestartScheduler.restartFailed()
                self.presentToxRestartFailedAlert()
            }
        })
    }

    func recreateActiveCoordinator(options: CoordinatorOptions? = nil,
                                   manager: OCTManager? = nil,
                                   skipAuthorizationChallenge: Bool = false,
                                   onToxOptionsRestartComplete: ((Bool) -> Void)? = nil) {
        // Fresh login passes manager directly and must not depend on keychain (unsigned sim builds fail with errSecMissingEntitlement).
        if let manager = manager {
            activeCoordinator = createRunningCoordinatorWithManager(manager,
                                                                    options: options,
                                                                    skipAuthorizationChallenge: skipAuthorizationChallenge)
            onToxOptionsRestartComplete?(true)
            return
        }

        // Restore the last active Tox profile from Documents/saves + keychain password.
        if SessionAutoLogin.canAttemptAutoLogin() {
            let password = SessionAutoLogin.passwordForAutoLogin() ?? ""
            let successBlock: (OCTManager) -> Void = { [weak self] manager -> Void in
                guard let self = self else {
                    onToxOptionsRestartComplete?(false)
                    return
                }
                // KHANDAQ (#15): seed the Tox self-name from the profile name on auto-login too
                // (mirrors the manual-login heal), so existing profiles with an empty self-name stop
                // publishing "Khandaq" as their NGC peer name / showing "(null)" in invites.
                if (manager.user.userName() ?? "").isEmpty,
                   let profileName = UserDefaultsManager().lastActiveProfile, !profileName.isEmpty {
                    _ = try? manager.user.setUserName(profileName)
                }
                self.activeCoordinator = self.createRunningCoordinatorWithManager(manager,
                                                                                  options: options,
                                                                                  skipAuthorizationChallenge: skipAuthorizationChallenge)
                onToxOptionsRestartComplete?(true)
            }

            let deleteActiveAccountAndRetry: () -> Void = { [weak self] in
                    guard let self = self else {
                        onToxOptionsRestartComplete?(false)
                        return
                    }
                    KeychainManager().deleteActiveAccountData()
                    self.recreateActiveCoordinator(options: options,
                                                   manager: manager,
                                                   skipAuthorizationChallenge: skipAuthorizationChallenge,
                                                   onToxOptionsRestartComplete: onToxOptionsRestartComplete)
                }

                guard let profileName = UserDefaultsManager().lastActiveProfile else {
                    if onToxOptionsRestartComplete != nil {
                        onToxOptionsRestartComplete?(false)
                    } else {
                        deleteActiveAccountAndRetry()
                    }
                    return
                }

                let path = ProfileManager().pathForProfileWithName(profileName)

                guard let configuration = OCTManagerConfiguration.configurationWithBaseDirectory(path) else {
                    if onToxOptionsRestartComplete != nil {
                        onToxOptionsRestartComplete?(false)
                    } else {
                        deleteActiveAccountAndRetry()
                    }
                    return
                }

                ToxFactory.createToxWithConfiguration(configuration,
                                                      encryptPassword: password,
                                                      successBlock: successBlock,
                                                      failureBlock: { [weak self] _ in
                    log("Cannot create tox with configuration \(configuration)")
                    if let onToxOptionsRestartComplete = onToxOptionsRestartComplete {
                        onToxOptionsRestartComplete(false)
                    } else {
                        self?.activeCoordinator = self?.createLoginCoordinator(options)
                    }
                })
        }
        else {
            activeCoordinator = createLoginCoordinator(options)
            onToxOptionsRestartComplete?(true)
        }
    }

    func presentToxRestartFailedAlert() {
        DispatchQueue.main.async { [weak self] in
            guard let root = self?.window.rootViewController else {
                return
            }
            let alert = UIAlertController(
                title: String(localized: "settings_udp_restart_title"),
                message: String(localized: "settings_udp_restart_failed_message"),
                preferredStyle: .alert
            )
            alert.addAction(UIAlertAction(title: String(localized: "error_ok_button"), style: .default) { _ in
                self?.recreateActiveCoordinator(skipAuthorizationChallenge: true)
            })
            root.present(alert, animated: true, completion: nil)
        }
    }

    func createRunningCoordinatorWithManager(_ manager: OCTManager,
                                             options: CoordinatorOptions?,
                                             skipAuthorizationChallenge: Bool) -> RunningCoordinator {
        // Both manual login and auto-login end up here.
        KhandaqBranding.clearLegacyDefaultStatus(of: manager.user)

        let coordinator = RunningCoordinator(theme: theme,
                                             window: window,
                                             toxManager: manager,
                                             skipAuthorizationChallenge: skipAuthorizationChallenge)
        coordinator.delegate = self
        coordinator.startWithOptions(options)

        return coordinator
    }

    func createLoginCoordinator(_ options: CoordinatorOptions?) -> LoginCoordinator {
        let coordinator = LoginCoordinator(theme: theme, window: window)
        coordinator.delegate = self
        coordinator.startWithOptions(options)

        return coordinator
    }
}
