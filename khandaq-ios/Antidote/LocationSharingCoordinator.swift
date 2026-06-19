// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import CoreLocation
import Foundation

/// KHANDAQ: anything that can receive a one-shot "share my location" message (currently group chats;
/// 1:1 uses the continuous activeChatController path).
protocol LocationSharingChat: AnyObject {
    func sendLocationMessage(_ payload: String)
}

final class LocationSharingCoordinator: NSObject, CLLocationManagerDelegate {
    static let shared = LocationSharingCoordinator()

    /// Collapse duplicate CLLocation callbacks for a single requestLocation().
    private let minSendInterval: TimeInterval = 5

    private let locationManager = CLLocationManager()
    private var sendPending = false
    private var lastSentAt: Date?
    private weak var activeChatController: ChatPrivateController?
    /// One-shot target (e.g. a group): send the current location once, no continuous toggle.
    private weak var oneShotChat: LocationSharingChat?

    private override init() {
        super.init()
        locationManager.delegate = self
        if #available(iOS 14.0, *) {
            locationManager.desiredAccuracy = kCLLocationAccuracyReduced
        } else {
            locationManager.desiredAccuracy = kCLLocationAccuracyBest
        }
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
            locationManager.requestLocation()
            return
        }

        LocationManager.shared.requestAccessForUserInitiatedSharing { [weak self] granted in
            guard granted, let self = self, self.oneShotChat != nil else {
                self?.oneShotChat = nil
                return
            }
            self.locationManager.requestLocation()
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
        locationManager.requestLocation()
    }

    private func shouldSendNow() -> Bool {
        guard let lastSentAt else {
            return true
        }
        return Date().timeIntervalSince(lastSentAt) >= minSendInterval
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        // One-shot (group) send takes priority and is independent of the continuous 1:1 toggle.
        if let chat = oneShotChat, let location = locations.last {
            oneShotChat = nil
            chat.sendLocationMessage(LocationMessage.payload(
                latitude: location.coordinate.latitude,
                longitude: location.coordinate.longitude))
            return
        }

        guard sendPending,
              let location = locations.last,
              AppDelegate.location_sharing_contact_pubkey != "-1" else {
            sendPending = false
            return
        }

        sendPending = false

        guard shouldSendNow() else {
            return
        }

        lastSentAt = Date()

        let payload = LocationMessage.payload(
            latitude: location.coordinate.latitude,
            longitude: location.coordinate.longitude)

        guard let controller = activeChatController,
              controller.isActiveLocationSharingChat else {
            return
        }

        controller.sendLocationMessage(payload)
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        sendPending = false
    }
}
