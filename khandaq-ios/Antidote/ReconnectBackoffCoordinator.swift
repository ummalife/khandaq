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

    private var attempt = 0
    private var pendingWork: DispatchWorkItem?

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
        attempt += 1
        if attempt >= Self.maxAttempts {
            NetworkDiagnosticsLog.log("reconnect_give_up", detail: "max=\(Self.maxAttempts)")
            return
        }

        let delay = Self.computeDelay(attemptIndex: attempt)
        NetworkDiagnosticsLog.log("reconnect_retry_scheduled", detail: "next=\(attempt) delay=\(delay)")

        let work = DispatchWorkItem { [weak self] in
            self?.scheduleReconnect(reason: reason, urgent: false)
        }
        pendingWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: work)
    }

    private static func computeDelay(attemptIndex: Int) -> TimeInterval {
        let exp = baseDelay * pow(2, Double(min(attemptIndex, 5)))
        let capped = min(exp, maxDelay)
        return capped + Double.random(in: 0...(capped * 0.1))
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
