// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation

/// Periodic retry for stale outgoing direct/group messages (Android MessageDeliveryWatchdog parity).
final class MessageDeliveryWatchdog {
    static let shared = MessageDeliveryWatchdog()

    private static let watchInterval: TimeInterval = 5

    private weak var toxManager: OCTManager?
    private var timer: Timer?

    private init() {}

    func bind(toxManager: OCTManager) {
        self.toxManager = toxManager
    }

    func start() {
        stop()
        timer = Timer.scheduledTimer(withTimeInterval: MessageDeliveryWatchdog.watchInterval, repeats: true) { [weak self] _ in
            self?.tick()
        }
        if let timer = timer {
            RunLoop.main.add(timer, forMode: .commonModes)
        }
    }

    func stop() {
        timer?.invalidate()
        timer = nil
        toxManager = nil
    }

    private func tick() {
        guard let toxManager = toxManager else {
            return
        }

        toxManager.chats.tickDeliveryWatchdog()
        toxManager.groups.flushAllPendingGroupMessagesIfNeeded()
    }
}
