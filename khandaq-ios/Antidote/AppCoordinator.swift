// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit

class AppCoordinator {
    fileprivate let window: UIWindow
    var activeCoordinator: TopCoordinatorProtocol?
    fileprivate var theme: Theme
    fileprivate var pendingToxRestartOptions: CoordinatorOptions?
    fileprivate var toxRestartWorkItem: DispatchWorkItem?

    init(window: UIWindow) {
        self.window = window

        let filepath = Bundle.main.path(forResource: "default-theme", ofType: "yaml")!
        let yamlString = try! NSString(contentsOfFile:filepath, encoding:String.Encoding.utf8.rawValue) as String

        theme = try! Theme(yamlString: yamlString)
        applyTheme(theme)

        ToxOptionsRestartScheduler.setContinueRecreateHandler { [weak self] in
            self?.performToxRecreateIfReady()
        }
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
        activeCoordinator?.handleInboxURL(url)
    }
}

extension AppCoordinator: RunningCoordinatorDelegate {
    func runningCoordinatorDidLogout(_ coordinator: RunningCoordinator, importToxProfileFromURL: URL?) {
        KeychainManager().deleteActiveAccountData()

        recreateActiveCoordinator()

        if let url = importToxProfileFromURL,
           let coordinator = activeCoordinator as? LoginCoordinator {
            coordinator.handleInboxURL(url)
        }
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
    func loginCoordinatorDidLogin(_ coordinator: LoginCoordinator, manager: OCTManager, password: String) {
        KeychainManager().toxPasswordForActiveAccount = password

        recreateActiveCoordinator(manager: manager, skipAuthorizationChallenge: true)
    }
}

// MARK: Private
private extension AppCoordinator {
    func applyTheme(_ theme: Theme) {
        let linkTextColor = theme.colorForType(.LinkText)

        UIButton.appearance().tintColor = linkTextColor
        UISwitch.appearance().onTintColor = linkTextColor
        UINavigationBar.appearance().tintColor = linkTextColor
    }

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
        if let password = KeychainManager().toxPasswordForActiveAccount {
            let successBlock: (OCTManager) -> Void = { [weak self] manager -> Void in
                guard let self = self else {
                    onToxOptionsRestartComplete?(false)
                    return
                }
                self.activeCoordinator = self.createRunningCoordinatorWithManager(manager,
                                                                                  options: options,
                                                                                  skipAuthorizationChallenge: skipAuthorizationChallenge)
                onToxOptionsRestartComplete?(true)
            }

            if let manager = manager {
                successBlock(manager)
            }
            else {
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
                                                      failureBlock: { _ in
                    log("Cannot create tox with configuration \(configuration)")
                    if let onToxOptionsRestartComplete = onToxOptionsRestartComplete {
                        onToxOptionsRestartComplete(false)
                    } else {
                        deleteActiveAccountAndRetry()
                    }
                })
            }
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
