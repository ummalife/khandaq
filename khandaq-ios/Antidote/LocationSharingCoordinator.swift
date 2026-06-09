// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import CoreLocation
import Foundation

final class LocationSharingCoordinator: NSObject, CLLocationManagerDelegate {
    static let shared = LocationSharingCoordinator()

    /// Collapse duplicate CLLocation callbacks for a single requestLocation().
    private let minSendInterval: TimeInterval = 5

    private let locationManager = CLLocationManager()
    private var sendPending = false
    private var lastSentAt: Date?
    private weak var activeChatController: ChatPrivateController?

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
