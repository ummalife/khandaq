// Deferred Tox restart when network options (UDP) change — avoids crash on background/foreground transition.

import UIKit

enum ToxOptionsRestartScheduler {
    private static var pendingDelegate: SettingsAdvancedControllerDelegate?
    private static var pendingController: SettingsAdvancedController?
    private static var activeObservers: [NSObjectProtocol] = []
    private static var restartTriggered = false
    private static var recreateDeferred = false
    private static var continueRecreateHandler: (() -> Void)?

    private(set) static var isRestartInProgress = false

    static func setContinueRecreateHandler(_ handler: @escaping () -> Void) {
        continueRecreateHandler = handler
    }

    static func requestRestart(from controller: SettingsAdvancedController,
                               delegate: SettingsAdvancedControllerDelegate) {
        pendingDelegate = delegate
        pendingController = controller
        isRestartInProgress = true
        restartTriggered = false
        recreateDeferred = false

        registerObserversIfNeeded()
        scheduleRestartAttempt()
    }

    static func restartCompleted() {
        isRestartInProgress = false
        restartTriggered = false
        recreateDeferred = false
        pendingDelegate = nil
        pendingController = nil
        unregisterObservers()
    }

    static func restartFailed() {
        isRestartInProgress = false
        restartTriggered = false
        recreateDeferred = false
        pendingDelegate = nil
        pendingController = nil
        unregisterObservers()
    }

    static func deferRecreate() {
        recreateDeferred = true
        restartTriggered = false
    }

    static func scheduleRestartAttempt() {
        DispatchQueue.main.async {
            performRestartIfNeeded()
            if recreateDeferred {
                continueRecreateHandler?()
            }
        }
    }

    private static func registerObserversIfNeeded() {
        guard activeObservers.isEmpty else {
            return
        }

        activeObservers.append(NotificationCenter.default.addObserver(
            forName: NSNotification.Name.UIApplicationDidBecomeActive,
            object: nil,
            queue: .main
        ) { _ in
            scheduleRestartAttempt()
        })

        activeObservers.append(NotificationCenter.default.addObserver(
            forName: NSNotification.Name.UIApplicationWillResignActive,
            object: nil,
            queue: .main
        ) { _ in
            // Only defer after teardown has started; otherwise wait for next active cycle.
            guard isRestartInProgress, restartTriggered else {
                return
            }
            deferRecreate()
        })
    }

    private static func unregisterObservers() {
        for observer in activeObservers {
            NotificationCenter.default.removeObserver(observer)
        }
        activeObservers.removeAll()
    }

    private static func performRestartIfNeeded() {
        guard isRestartInProgress else {
            return
        }
        guard UIApplication.shared.applicationState == .active else {
            return
        }

        if restartTriggered && !recreateDeferred {
            return
        }

        if recreateDeferred {
            continueRecreateHandler?()
            return
        }

        guard let delegate = pendingDelegate, let controller = pendingController else {
            continueRecreateHandler?()
            return
        }

        restartTriggered = true
        recreateDeferred = false
        delegate.settingsAdvancedControllerToxOptionsChanged(controller)
    }
}
