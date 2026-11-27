// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import CoreLocation
import Foundation

/// KHANDAQ: anything that can receive a one-shot "share my location" message (currently group chats;
/// 1:1 uses the continuous activeChatController path).
protocol LocationSharingChat: AnyObject {
    func sendLocationMessage(_ payload: String)
    /// KHANDAQ (owner, 28.11): no fix of where you are came in time (Location Services off, no signal): nothing was
    /// sent, and the chat says so instead of leaving the tap without an answer.
    func locationSharingFailed()
}

extension LocationSharingChat {
    func locationSharingFailed() {}
}

/// KHANDAQ (owner, 28.11: "it sends another place, not where I am"): picks the fix a shared place is sent with. A fix
/// older than `maxAge` or without a valid accuracy is not where the person is now (the system hands out the last
/// fix it had, which may be from another town), and of the fresh ones the most accurate wins. One within
/// `goodAccuracy` ends the wait at once, one within `fairAccuracy` once `patience` has passed (indoors the radio
/// seldom does better), and at `maxWait` the best fresh fix there is goes.
struct LocationFixPicker {
    static let maxAge: TimeInterval = 10
    static let goodAccuracy: CLLocationAccuracy = 20
    static let fairAccuracy: CLLocationAccuracy = 65
    static let patience: TimeInterval = 2.5
    static let maxWait: TimeInterval = 8

    private(set) var best: CLLocation?

    mutating func consider(_ location: CLLocation, now: Date) {
        guard location.horizontalAccuracy >= 0,
              now.timeIntervalSince(location.timestamp) <= LocationFixPicker.maxAge else {
            return
        }
        if let best = best, best.horizontalAccuracy <= location.horizontalAccuracy {
            return
        }
        best = location
    }

    /// Whether the best fix so far is good enough to stop waiting, `elapsed` seconds after the wait began.
    func isSettled(elapsed: TimeInterval) -> Bool {
        guard let best = best else {
            return false
        }
        let enough = elapsed < LocationFixPicker.patience ? LocationFixPicker.goodAccuracy : LocationFixPicker.fairAccuracy
        return best.horizontalAccuracy <= enough
    }
}

final class LocationSharingCoordinator: NSObject, CLLocationManagerDelegate {
    static let shared = LocationSharingCoordinator()

    /// The purpose of NSLocationTemporaryUsageDescriptionDictionary in Antidote-Info.plist: sending where you are
    /// while Precise Location is off for the app.
    static let fullAccuracyPurposeKey = "ShareLocation"

    /// Collapse duplicate sends of the continuous 1:1 path.
    private let minSendInterval: TimeInterval = 5

    private let locationManager = CLLocationManager()
    private var sendPending = false
    private var lastSentAt: Date?
    private weak var activeChatController: ChatPrivateController?
    /// One-shot target (e.g. a group): send the current location once, no continuous toggle.
    private weak var oneShotChat: LocationSharingChat?

    /// A fix is being picked (LocationFixPicker): from the request of precise location, if any, to its delivery.
    private var fixRunning = false
    /// When the updates of the fix being picked began; nil while precise location is still being asked for.
    private var fixStartedAt: Date?
    private var picker = LocationFixPicker()
    private var fixTimers: [Timer] = []

    private override init() {
        super.init()
        locationManager.delegate = self
        // KHANDAQ (owner, 28.11): the place you send is where you are. kCLLocationAccuracyReduced stood here, and from
        // iOS 14 it makes the system give a place kilometres off even with Precise Location on.
        locationManager.desiredAccuracy = kCLLocationAccuracyBest
    }

    /// Begin sharing with this chat. Sends at most one location message when `sendImmediately` is true.
    func start(for controller: ChatPrivateController, sendImmediately: Bool) {
        activeChatController = controller
        if sendImmediately {
            requestSend()
        }
    }

    /// Re-attach UI after returning to an already-enabled sharing chat (no automatic resend).
    func resume(for controller: ChatPrivateController) {
        activeChatController = controller
    }

    /// KHANDAQ: send the current location ONCE to `chat` (used by group chats). Requests permission
    /// if needed; no continuous sharing/toggle.
    func shareCurrentLocationOnce(to chat: LocationSharingChat) {
        oneShotChat = chat

        if LocationManager.shared.hasUsableAuthorization() {
            startFix()
            return
        }

        LocationManager.shared.requestAccessForUserInitiatedSharing { [weak self] granted in
            guard granted, let self = self, self.oneShotChat != nil else {
                self?.oneShotChat = nil
                return
            }
            self.startFix()
        }
    }

    func stop() {
        sendPending = false
        lastSentAt = nil
        activeChatController = nil
    }

    func detach(controller: ChatPrivateController) {
        if activeChatController === controller {
            activeChatController = nil
        }
    }

    private func requestSend() {
        guard AppDelegate.location_sharing_contact_pubkey != "-1",
              !sendPending else {
            return
        }

        guard LocationManager.shared.hasUsableAuthorization() else {
            return
        }

        sendPending = true
        startFix()
    }

    private func shouldSendNow() -> Bool {
        guard let lastSentAt else {
            return true
        }
        return Date().timeIntervalSince(lastSentAt) >= minSendInterval
    }

    // MARK: - The fix

    /// Picks a fix of where you are now, asking once for the exact place first where the app may only know the
    /// approximate one (Precise Location off): the person asked to send where they are. A fix already being picked
    /// serves whoever waits when it is delivered.
    private func startFix() {
        guard !fixRunning else {
            return
        }
        fixRunning = true

        if #available(iOS 14.0, *), locationManager.accuracyAuthorization == .reducedAccuracy {
            locationManager.requestTemporaryFullAccuracyAuthorization(
                withPurposeKey: LocationSharingCoordinator.fullAccuracyPurposeKey) { [weak self] _ in
                // Whatever the answer: with Precise Location still off the fix is the approximate one, as allowed.
                DispatchQueue.main.async {
                    self?.beginUpdates()
                }
            }
            return
        }
        beginUpdates()
    }

    private func beginUpdates() {
        guard fixRunning, fixStartedAt == nil else {
            return
        }
        picker = LocationFixPicker()
        fixStartedAt = Date()
        locationManager.startUpdatingLocation()
        fixTimers = [
            Timer.scheduledTimer(withTimeInterval: LocationFixPicker.patience, repeats: false) { [weak self] _ in
                self?.finishFixIfSettled()
            },
            Timer.scheduledTimer(withTimeInterval: LocationFixPicker.maxWait, repeats: false) { [weak self] _ in
                self?.finishFix()
            },
        ]
    }

    private func finishFixIfSettled() {
        guard let started = fixStartedAt, picker.isSettled(elapsed: Date().timeIntervalSince(started)) else {
            return
        }
        finishFix()
    }

    private func finishFix() {
        guard fixRunning else {
            return
        }
        fixRunning = false
        fixStartedAt = nil
        fixTimers.forEach { $0.invalidate() }
        fixTimers = []
        locationManager.stopUpdatingLocation()
        let fix = picker.best
        picker = LocationFixPicker()
        deliver(fix)
    }

    /// The fix goes to everyone who waits for one: a one-shot chat, and the continuous 1:1 send.
    private func deliver(_ fix: CLLocation?) {
        let payload = fix.map {
            LocationMessage.payload(latitude: $0.coordinate.latitude, longitude: $0.coordinate.longitude)
        }

        if let chat = oneShotChat {
            oneShotChat = nil
            if let payload = payload {
                chat.sendLocationMessage(payload)
            }
            else {
                chat.locationSharingFailed()
            }
        }

        guard sendPending else {
            return
        }
        sendPending = false

        guard let payload = payload,
              AppDelegate.location_sharing_contact_pubkey != "-1",
              shouldSendNow() else {
            return
        }

        lastSentAt = Date()

        guard let controller = activeChatController,
              controller.isActiveLocationSharingChat else {
            return
        }

        controller.sendLocationMessage(payload)
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard fixRunning, fixStartedAt != nil else {
            return
        }
        let now = Date()
        for location in locations {
            picker.consider(location, now: now)
        }
        finishFixIfSettled()
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        // No fix yet is not the end: the radio keeps trying, and the timers end the wait.
        if let error = error as? CLError, error.code == .locationUnknown {
            return
        }
        // Denied or failed: the best fix so far goes, or the chat says none came.
        finishFix()
    }
}
