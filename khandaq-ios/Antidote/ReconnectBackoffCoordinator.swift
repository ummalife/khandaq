// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation

/// Exponential backoff for DHT rebootstrap (Android ReconnectBackoffCoordinator parity).
final class ReconnectBackoffCoordinator {
    static let shared = ReconnectBackoffCoordinator()

    enum Reason: String {
        case networkChange = "NETWORK_CHANGE"
        case offlinePeriodic = "OFFLINE_PERIODIC"
        case manual = "MANUAL"
    }

    private static let baseDelay: TimeInterval = 1
    private static let maxDelay: TimeInterval = 30
    private static let maxAttempts = 12

    private(set) var attempt = 0
    private var pendingWork: DispatchWorkItem?

    /// A reconnect waits for its delay; another request would only push it back.
    var isReconnectPending: Bool {
        return pendingWork != nil
    }

    private init() {}

    func onConnectionRestored() {
        pendingWork?.cancel()
        pendingWork = nil
        if attempt > 0 {
            NetworkDiagnosticsLog.log("reconnect_ok", detail: "attempts_reset=\(attempt)")
        }
        attempt = 0
    }

    func scheduleReconnect(reason: Reason, urgent: Bool) {
        pendingWork?.cancel()

        if urgent {
            attempt = 0
        }

        let currentAttempt = attempt
        let delay = urgent ? 0 : Self.computeDelay(attemptIndex: currentAttempt)

        NetworkDiagnosticsLog.log("reconnect_scheduled",
                                  detail: "reason=\(reason.rawValue) urgent=\(urgent) attempt=\(currentAttempt) delay=\(delay)")

        let work = DispatchWorkItem { [weak self] in
            self?.executeReconnect(reason: reason, attempt: currentAttempt)
        }
        pendingWork = work

        if delay <= 0 {
            DispatchQueue.main.async(execute: work)
        } else {
            DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: work)
        }
    }

    private func executeReconnect(reason: Reason, attempt currentAttempt: Int) {
        pendingWork = nil
        NetworkDiagnosticsLog.log("reconnect_attempt", detail: "\(reason.rawValue) attempt=\(currentAttempt)")

        NotificationCenter.default.post(name: .khandaqManualReconnectRequested, object: reason)

        DispatchQueue.main.asyncAfter(deadline: .now() + 4) { [weak self] in
            guard let self = self else { return }
            guard let toxManager = ActiveSessionCoordinator.sharedToxManagerForReconnect() else { return }

            if toxManager.user.connectionStatus == .none {
                NetworkDiagnosticsLog.log("reconnect_still_offline", detail: reason.rawValue)
                self.scheduleRetryIfStillOffline(reason: reason)
            }
        }
    }

    func scheduleRetryIfStillOffline(reason: Reason) {
        // KHANDAQ: a failed attempt is reported twice, by "rebootstrap completed" (posted 3 s into the
        // rebootstrap) and by the 4 s check in executeReconnect. A reconnect already scheduled answers
        // both. Each report used to count as an attempt and start a retry of its own, never cancelling
        // the one before, so the backoff gave up after half its attempts.
        if pendingWork != nil {
            return
        }

        attempt += 1
        if attempt >= Self.maxAttempts {
            NetworkDiagnosticsLog.log("reconnect_give_up", detail: "max=\(Self.maxAttempts)")
            return
        }

        // As on Android, the retry is the next reconnect, delayed once by the backoff. It used to wait
        // the backoff and then schedule a reconnect that waited it again.
        NetworkDiagnosticsLog.log("reconnect_retry_scheduled", detail: "next=\(attempt)")
        scheduleReconnect(reason: reason, urgent: false)
    }

    private static func computeDelay(attemptIndex: Int) -> TimeInterval {
        let exp = baseDelay * pow(2, Double(min(attemptIndex, 5)))
        let capped = min(exp, maxDelay)
        return capped + Double.random(in: 0...(capped * 0.1))
    }
}

/// The offline watchdog (redesign stage 3, E4; TrifaToxService on Android): while our node stays
/// offline on a phone that has a network, a reconnect is asked for every 15 seconds. The backoff alone
/// only answered a network change or a rebootstrap that had just failed, and gave up after its twelve
/// attempts, so a node that dropped while the network stayed up waited for toxcore's own slow retry.
final class OfflineReconnectWatchdog {
    static let interval: TimeInterval = 15

    private let isOnline: () -> Bool
    private let isReachable: () -> Bool
    private let isReconnectPending: () -> Bool
    private let requestReconnect: () -> Void
    private var timer: Timer?
    private(set) var offlineSince: Date?

    init(isOnline: @escaping () -> Bool,
         isReachable: @escaping () -> Bool,
         isReconnectPending: @escaping () -> Bool = { ReconnectBackoffCoordinator.shared.isReconnectPending },
         requestReconnect: @escaping () -> Void = {
             ReconnectBackoffCoordinator.shared.scheduleReconnect(reason: .offlinePeriodic, urgent: false)
         }) {
        self.isOnline = isOnline
        self.isReachable = isReachable
        self.isReconnectPending = isReconnectPending
        self.requestReconnect = requestReconnect
    }

    deinit {
        timer?.invalidate()
    }

    func start() {
        stop()
        let timer = Timer(timeInterval: OfflineReconnectWatchdog.interval / 3, repeats: true) { [weak self] _ in
            self?.check(now: Date())
        }
        RunLoop.main.add(timer, forMode: .commonModes)
        self.timer = timer
    }

    func stop() {
        timer?.invalidate()
        timer = nil
        offlineSince = nil
    }

    /// One look at the node; true when it asked for a reconnect. The clock starts when the node is
    /// first seen offline with a network, and starts again after each request.
    @discardableResult
    func check(now: Date) -> Bool {
        guard !isOnline(), isReachable() else {
            // Online, or no network at all: the network's return is a path change of its own.
            offlineSince = nil
            return false
        }
        guard let since = offlineSince else {
            offlineSince = now
            return false
        }
        guard now.timeIntervalSince(since) >= OfflineReconnectWatchdog.interval, !isReconnectPending() else {
            return false
        }
        offlineSince = now
        NetworkDiagnosticsLog.log("offline_watchdog", detail: "reconnect")
        requestReconnect()
        return true
    }
}

extension Notification.Name {
    static let khandaqManualReconnectRequested = Notification.Name("KhandaqManualReconnectRequested")
}

extension ActiveSessionCoordinator {
    private static weak var reconnectCoordinator: ActiveSessionCoordinator?

    static func sharedToxManagerForReconnect() -> OCTManager? {
        return reconnectCoordinator?.toxManager
    }

    func registerForReconnectBackoff() {
        ActiveSessionCoordinator.reconnectCoordinator = self

        NotificationCenter.default.addObserver(self,
                                               selector: #selector(handleManualReconnect(_:)),
                                               name: .khandaqManualReconnectRequested,
                                               object: nil)
    }

    @objc private func handleManualReconnect(_ notification: Notification) {
        toxManager.bootstrap.rebootstrapOnNetworkChange()
    }
}
