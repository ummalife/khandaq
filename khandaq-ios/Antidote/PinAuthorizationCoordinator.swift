// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation
import AudioToolbox
import LocalAuthentication

fileprivate struct Constants {
    static let pinAttemptsNumber = 10

    // KHANDAQ (audit #11): each batch of failed attempts blocks the pin screen for longer. The batch
    // number (1 after 10 wrong pins, 2 after 20, ...) picks the interval, anything past the list gets
    // the max one.
    //
    // The deadline is wall-clock and is deliberately NOT anchored to a monotonic/boot-relative clock:
    // this is brute-force friction for a 4-digit pin, not a security boundary. An attacker who can
    // move the device clock forward already holds an unlocked phone (Settings is right there), and a
    // boot anchor would still fall to a reboot while adding a new way to lock the owner out for real.
    static let pinLockoutIntervals: [TimeInterval] = [60.0, 300.0, 900.0]
    static let pinLockoutMaxInterval: TimeInterval = 3600.0
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

    fileprivate var theme: Theme
    fileprivate let window: UIWindow

    fileprivate weak var submanagerObjects: OCTSubmanagerObjects!

    fileprivate var state: State

    // KHANDAQ (audit #11): ticks the remaining lockout time on the pin screen and re-enables it.
    fileprivate var lockoutTimer: Timer?

    // KHANDAQ (audit #11): the deadline is read from the keychain once, when the pin screen is
    // presented (and written when a new lockout starts) — the 1s tick must not hit the keychain on
    // the main thread.
    fileprivate var lockedUntil: TimeInterval?

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
        // KHANDAQ (Figma): the flat lock screen shows the login-logo wordmark (light/dark asset
        // variants) — this window must carry the app theme's interface style, not the system one.
        ThemeChrome.applyWindowStyle(window, theme: theme)

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
        lockoutTimer?.invalidate()
    }

    /// KHANDAQ (Figma): the lock screen now uses theme backgrounds/keys (no more fixed teal
    /// gradient), so an in-app theme toggle must reach the NEXT EnterPinController too.
    func updateTheme(_ newTheme: Theme) {
        theme = newTheme
        ThemeChrome.applyWindowStyle(window, theme: newTheme)
    }

    @objc func appWillResignActiveNotification() {
        lockIfNeeded(CACurrentMediaTime())
    }

    @objc func appDidBecomeActiveNotification() {
        presentLockChallengeIfApplicationActive()
    }
}

extension PinAuthorizationCoordinator: CoordinatorProtocol {
    func startWithOptions(_ options: CoordinatorOptions?) {
        // Cold start: applicationDidBecomeActive often fires before this coordinator exists.
        presentLockChallengeIfApplicationActive()
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

        guard failedAttempts % Constants.pinAttemptsNumber != 0 else {
            // KHANDAQ (audit #11): keep the counter (it used to be dropped here) and block the pin
            // screen until the deadline — the log out below auto-logs the user back in from the
            // keychain, so without this an attacker just repeats batches of ten, unlimited.
            let deadline = Date().timeIntervalSince1970 + lockoutInterval(failedAttempts)
            lockedUntil = deadline
            keychainManager.pinLockedUntil = Int(deadline)

            // The log out below is asynchronous (profile teardown + recreate), so THIS controller stays
            // on screen for a while — lock it right now or the attacker keeps typing through the window.
            refreshLockoutState(controller)

            handleErrorWithType(.pinLogOut)

            delegate?.pinAuthorizationCoordinatorDidLogout(self)
            return
        }

        controller.resetEnteredPin()
        controller.topText = String(localized: "pin_incorrect")
        controller.descriptionText = failedAttemptsText(failedAttempts)
        AudioServicesPlayAlertSound(SystemSoundID(kSystemSoundID_Vibrate))
    }
}

private extension PinAuthorizationCoordinator {
    func presentLockChallengeIfApplicationActive() {
        guard UIApplication.shared.applicationState == .active else {
            return
        }

        switch state {
            case .unlocked, .validatingPin:
                break
            case .locked(let lockTime):
                isPinDateExpired(lockTime) ? challengeUserToAuthorize(lockTime) : unlock()
        }
    }

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
        let keychainManager = KeychainManager()
        keychainManager.failedPinAttemptsNumber = nil
        // KHANDAQ (audit #11): a successful unlock (pin or Touch ID) means the owner is here, drop the
        // lockout with the counter.
        keychainManager.pinLockedUntil = nil
        lockedUntil = nil

        stopLockoutTimer()
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

        // KHANDAQ (audit #11): single keychain read per presentation, the tick uses the cached value.
        lockedUntil = KeychainManager().pinLockedUntil.map { TimeInterval($0) }

        let controller = EnterPinController(theme: theme, state: .validatePin(validPin: pin))
        // KHANDAQ (Figma): the unlock screen shows the Khandaq wordmark on top.
        controller.showLogo = true
        controller.delegate = self
        window.rootViewController = controller

        // KHANDAQ (audit #11): sets the normal texts, or the locked-out ones if a lockout is running
        // (it must show up on a freshly presented screen too).
        refreshLockoutState(controller)
    }

    /// KHANDAQ (audit #11): the stored counter is cumulative now (it drives the backoff), but the screen
    /// keeps showing the attempts within the current batch — 1...9, exactly like shipped builds — instead
    /// of a lifetime total that would read as broken.
    func failedAttemptsText(_ failedAttempts: Int) -> String? {
        let attemptsInBatch = failedAttempts % Constants.pinAttemptsNumber

        guard attemptsInBatch > 0 else {
            return nil
        }

        return String(localized: "pin_failed_attempts", "\(attemptsInBatch)")
    }

    /// KHANDAQ (audit #11): interval to block the pin screen for after `failedAttempts` wrong pins.
    func lockoutInterval(_ failedAttempts: Int) -> TimeInterval {
        let batch = max(failedAttempts / Constants.pinAttemptsNumber, 1)

        guard batch <= Constants.pinLockoutIntervals.count else {
            return Constants.pinLockoutMaxInterval
        }

        return Constants.pinLockoutIntervals[batch - 1]
    }

    func lockoutRemainingTime() -> TimeInterval {
        guard let lockedUntil = lockedUntil else {
            return 0.0
        }

        let remaining = lockedUntil - Date().timeIntervalSince1970

        // A deadline further out than the longest interval can only come from a clock moved BACKWARDS
        // after it was written — treat it as expired, otherwise the owner never gets the screen back
        // (clamping the remaining time would keep it pinned at the cap forever).
        guard remaining > 0.0 && remaining <= Constants.pinLockoutMaxInterval else {
            return 0.0
        }

        return remaining
    }

    /// KHANDAQ (audit #11): keeps the pin screen in its locked state (keypad ignored, remaining time
    /// shown) until the deadline passes, then hands it back to the user without a relaunch.
    func refreshLockoutState(_ controller: EnterPinController) {
        let remaining = lockoutRemainingTime()

        guard remaining > 0.0 else {
            let keychainManager = KeychainManager()

            if lockedUntil != nil {
                lockedUntil = nil
                keychainManager.pinLockedUntil = nil
            }

            stopLockoutTimer()

            controller.inputEnabled = true
            controller.topText = String(localized: "pin_enter_to_unlock")
            controller.descriptionText = failedAttemptsText(keychainManager.failedPinAttemptsNumber ?? 0)
            return
        }

        controller.inputEnabled = false
        controller.topText = String(localized: "pin_locked_out")
        controller.descriptionText = String(localized: "pin_locked_out_remaining", String(timeInterval: remaining))
        controller.resetEnteredPin()

        startLockoutTimer()
    }

    func startLockoutTimer() {
        guard lockoutTimer == nil else {
            return
        }

        lockoutTimer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] timer in
            guard let self = self else {
                timer.invalidate()
                return
            }

            guard let controller = self.window.rootViewController as? EnterPinController else {
                self.stopLockoutTimer()
                return
            }

            self.refreshLockoutState(controller)
        }
    }

    func stopLockoutTimer() {
        lockoutTimer?.invalidate()
        lockoutTimer = nil
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
