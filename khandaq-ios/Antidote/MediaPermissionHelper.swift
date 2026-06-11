// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import AVFoundation
import UIKit

enum MediaPermission {
    static func requestCameraAccess(from presenter: UIViewController, completion: @escaping (Bool) -> Void) {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            completion(true)
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { granted in
                DispatchQueue.main.async {
                    if granted {
                        completion(true)
                    } else {
                        showCameraDenied(from: presenter)
                        completion(false)
                    }
                }
            }
        case .denied, .restricted:
            showCameraDenied(from: presenter)
            completion(false)
        @unknown default:
            showCameraDenied(from: presenter)
            completion(false)
        }
    }

    static func isCameraAuthorized() -> Bool {
        AVCaptureDevice.authorizationStatus(for: .video) == .authorized
    }

    static func showCameraDenied(from presenter: UIViewController) {
        showPermissionDeniedAlert(
            from: presenter,
            message: String(localized: "camera_access_denied_message")
        )
    }

    static func showPermissionDeniedAlert(from presenter: UIViewController, message: String) {
        let alert = UIAlertController(
            title: String(localized: "permission_denied_title"),
            message: message,
            preferredStyle: .alert
        )

        alert.addAction(UIAlertAction(title: String(localized: "open_settings"), style: .default) { _ in
            guard let url = URL(string: UIApplicationOpenSettingsURLString) else {
                return
            }
            UIApplication.shared.open(url)
        })

        alert.addAction(UIAlertAction(title: String(localized: "alert_cancel"), style: .cancel, handler: nil))
        presenter.present(alert, animated: true, completion: nil)
    }
}
