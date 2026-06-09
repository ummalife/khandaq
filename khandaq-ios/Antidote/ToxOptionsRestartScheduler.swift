// Deferred Tox restart when network options (UDP) change — avoids crash on background/foreground transition.

import UIKit

enum ToxOptionsRestartScheduler {
    private static var pendingDelegate: SettingsAdvancedControllerDelegate?
    private static var pendingController: SettingsAdvancedController?
    private static var activeObserver: NSObjectProtocol?
    private static var restartTriggered = false

    private(set) static var isRestartInProgress = false

    static func requestRestart(from controller: SettingsAdvancedController,
                               delegate: SettingsAdvancedControllerDelegate) {
        pendingDelegate = delegate
        pendingController = controller
        isRestartInProgress = true
        restartTriggered = false

        registerObserversIfNeeded()
        scheduleRestartAttempt()
    }

    static func restartCompleted() {
        isRestartInProgress = false
        restartTriggered = false
        pendingDelegate = nil
        pendingController = nil
        unregisterObservers()
    }

    static func restartFailed() {
        isRestartInProgress = false
        restartTriggered = false
        pendingDelegate = nil
        pendingController = nil
        unregisterObservers()
    }

    private static func registerObserversIfNeeded() {
        guard activeObserver == nil else {
            return
        }
        activeObserver = NotificationCenter.default.addObserver(
            forName: NSNotification.Name.UIApplicationDidBecomeActive,
            object: nil,
            queue: .main
        ) { _ in
            scheduleRestartAttempt()
        }
    }

    private static func unregisterObservers() {
        if let activeObserver = activeObserver {
            NotificationCenter.default.removeObserver(activeObserver)
            self.activeObserver = nil
        }
    }

    private static func scheduleRestartAttempt() {
        DispatchQueue.main.async {
            performRestartIfNeeded()
        }
    }

    private static func performRestartIfNeeded() {
        guard isRestartInProgress, !restartTriggered else {
            return
        }
        guard UIApplication.shared.applicationState == .active else {
            return
        }
        guard let delegate = pendingDelegate, let controller = pendingController else {
            return
        }

        restartTriggered = true
        delegate.settingsAdvancedControllerToxOptionsChanged(controller)
    }
}
