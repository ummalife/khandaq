// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation
import AudioToolbox
import LocalAuthentication

fileprivate struct Constants {
    static let pinAttemptsNumber = 10
}

protocol PinAuthorizationCoordinatorDelegate: class {
    func pinAuthorizationCoordinatorDidLogout(_ coordinator: PinAuthorizationCoordinator)
}

class PinAuthorizationCoordinator: NSObject {
    weak var delegate: PinAuthorizationCoordinatorDelegate?

    fileprivate enum State {
        case unlocked
        case locked(lockTime: CFTimeInterval)
        case validatingPin
    }

    fileprivate let theme: Theme
    fileprivate let window: UIWindow

    fileprivate weak var submanagerObjects: OCTSubmanagerObjects!

    fileprivate var state: State

    var preventFromLocking: Bool = false {
        didSet {
            if !preventFromLocking && UIApplication.shared.applicationState != .active {
                // In case if locking option change in background we want to lock app when user comes back.
                lockIfNeeded(CACurrentMediaTime())
            }
        }
    }

    init(theme: Theme, submanagerObjects: OCTSubmanagerObjects, lockOnStartup: Bool) {
        self.theme = theme
        self.window = UIWindow(frame: UIScreen.main.bounds)
        self.submanagerObjects = submanagerObjects
        self.state = .unlocked

        super.init()

        // Showing window on top of all other windows.
        window.windowLevel = CGFloat(2000 + 1000)

        if lockOnStartup {
            lockIfNeeded(0)
        }

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(PinAuthorizationCoordinator.appWillResignActiveNotification),
            name: NSNotification.Name.UIApplicationWillResignActive,
            object: nil
        )

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(PinAuthorizationCoordinator.appDidBecomeActiveNotification),
            name: NSNotification.Name.UIApplicationDidBecomeActive,
            object: nil
        )
    }

    deinit {
        NotificationCenter.default.removeObserver(self)
    }

    @objc func appWillResignActiveNotification() {
        lockIfNeeded(CACurrentMediaTime())
    }

    @objc func appDidBecomeActiveNotification() {
        switch state {
            case .unlocked:
                // unlocked, nothing to do here
                break
            case .locked(let lockTime):
                isPinDateExpired(lockTime) ? challengeUserToAuthorize(lockTime) : unlock()
            case .validatingPin:
                // checking pin, no action required
                break
        }
    }
}

extension PinAuthorizationCoordinator: CoordinatorProtocol {
    func startWithOptions(_ options: CoordinatorOptions?) {
        // Biometric challenge runs from applicationDidBecomeActive when UI is ready.
    }
}

extension PinAuthorizationCoordinator: EnterPinControllerDelegate {
    func enterPinController(_ controller: EnterPinController, successWithPin pin: String) {
        unlock()
    }

    func enterPinControllerFailure(_ controller: EnterPinController) {
        let keychainManager = KeychainManager()

        var failedAttempts = keychainManager.failedPinAttemptsNumber ?? 0
        failedAttempts += 1

        keychainManager.failedPinAttemptsNumber = failedAttempts

        guard failedAttempts < Constants.pinAttemptsNumber else {
            keychainManager.failedPinAttemptsNumber = nil
            handleErrorWithType(.pinLogOut)

            delegate?.pinAuthorizationCoordinatorDidLogout(self)
            return
        }

        controller.resetEnteredPin()
        controller.topText = String(localized: "pin_incorrect")
        controller.descriptionText = String(localized: "pin_failed_attempts", "\(failedAttempts)")
        AudioServicesPlayAlertSound(SystemSoundID(kSystemSoundID_Vibrate))
    }
}

private extension PinAuthorizationCoordinator {
    func lockIfNeeded(_ lockTime: CFTimeInterval) {
        guard submanagerObjects.getProfileSettings().unlockPinCode != nil else {
            return
        }

        if preventFromLocking {
            return
        }

        for window in UIApplication.shared.windows {
            window.endEditing(true)
        }

        let storyboard = UIStoryboard(name: "LaunchPlaceholderBoard", bundle: Bundle.main)
        window.rootViewController = storyboard.instantiateViewController(withIdentifier: "LaunchPlaceholderController")
        window.isHidden = false

        switch state {
            case .unlocked:
                state = .locked(lockTime: lockTime)
            case .locked:
                // In case of Locked state don't want to update lockTime.
                break
            case .validatingPin:
                // In case of ValidatingPin state we also don't want to do anything.
                break
        }
    }

    func unlock() {
        KeychainManager().failedPinAttemptsNumber = nil

        state = .unlocked
        window.isHidden = true
    }

    func challengeUserToAuthorize(_ lockTime: CFTimeInterval) {
        if window.rootViewController is EnterPinController {
            // already showing pin controller
            return
        }

        if shouldUseTouchID() {
            let context = LAContext()
            var error: NSError?
            guard context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &error) else {
                showValidatePinController()
                return
            }

            state = .validatingPin
            context.evaluatePolicy(
                .deviceOwnerAuthenticationWithBiometrics,
                localizedReason: String(localized: "pin_touch_id_description")
            ) { [weak self] success, _ in
                DispatchQueue.main.async {
                    guard let self = self else { return }
                    self.state = .locked(lockTime: lockTime)
                    success ? self.unlock() : self.showValidatePinController()
                }
            }
        }
        else {
            showValidatePinController()
        }
    }

    func showValidatePinController() {
        let settings = submanagerObjects.getProfileSettings()
        guard let pin = settings.unlockPinCode else {
            if settings.useTouchID {
                settings.useTouchID = false
                submanagerObjects.saveProfileSettings(settings)
            }
            unlock()
            return
        }

        let failedAttempts = KeychainManager().failedPinAttemptsNumber ?? 0

        let controller = EnterPinController(theme: theme, state: .validatePin(validPin: pin))
        controller.topText = String(localized: "pin_enter_to_unlock")
        controller.descriptionText =
          failedAttempts > 0 ?
          String(localized: "pin_failed_attempts", "\(failedAttempts)") :
          nil
        controller.delegate = self
        window.rootViewController = controller
    }

    func isPinDateExpired(_ lockTime: CFTimeInterval) -> Bool {
        let settings = submanagerObjects.getProfileSettings()
        let delta = CACurrentMediaTime() - lockTime

        switch settings.lockTimeout {
            case .Immediately:
                return true
            case .Seconds30:
                return delta > 30
            case .Minute1:
                return delta > 60
            case .Minute2:
                return delta > (60 * 2)
            case .Minute5:
                return delta > (60 * 5)
        }
    }

    func shouldUseTouchID() -> Bool {
        guard submanagerObjects.getProfileSettings().useTouchID else {
            return false
        }

        guard LAContext().canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: nil) else {
            return false
        }

        return true
    }
}
