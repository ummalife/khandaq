// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation
import QuickLook
import UIKit

protocol QuickLookPreviewControllerDataSource: QLPreviewControllerDataSource {
    var previewController: QuickLookPreviewController? { get set }
}

class QuickLookPreviewController: QLPreviewController, QLPreviewControllerDelegate {
    var dataSourceStorage: QuickLookPreviewControllerDataSource?

    override func viewDidLoad() {
        super.viewDidLoad()
        delegate = self
        // KHANDAQ (#10): do NOT add a custom "Done" button. A modally-presented QLPreviewController
        // already provides its own working Done button — adding another produced two "Готово" buttons.
    }

    func previewController(_ controller: QLPreviewController, shouldOpen url: URL, for item: QLPreviewItem) -> Bool {
        return true
    }
}

extension QuickLookPreviewController {
    /// The longest a cover stays up when QuickLook never comes: UIKit refuses some presentations without calling
    /// their completion, and a cover left behind would take every touch.
    static let openingCoverTimeout: TimeInterval = 20

    /// KHANDAQ (0.2.63, O1): QuickLook can take seconds to come up for a big file (a 25 MB PDF took 3-4 s on an
    /// iPhone) and nothing showed meanwhile, so the tap looked lost. A spinner covers the presenter from the tap
    /// until QuickLook is on screen. The cover also takes the touches meanwhile, so a second tap cannot try to
    /// present QuickLook again.
    static func present(_ controller: QuickLookPreviewController, from presenter: UIViewController) {
        // A presenter that already shows something, or is off screen, gets the presentation as before: UIKit
        // refuses it without calling the completion, so a cover would only come down on the timeout.
        guard presenter.presentedViewController == nil, let host = presenter.view, host.window != nil else {
            presenter.present(controller, animated: true, completion: nil)
            return
        }
        let cover = QuickLookOpeningCover(frame: host.bounds)
        host.addSubview(cover)
        UIAccessibilityPostNotification(UIAccessibilityAnnouncementNotification, cover.accessibilityLabel)
        DispatchQueue.main.asyncAfter(deadline: .now() + openingCoverTimeout) { [weak cover] in
            cover?.removeFromSuperview()
        }
        // One turn of the main queue first, so that the spinner is on screen before QuickLook takes its time.
        DispatchQueue.main.async {
            presenter.present(controller, animated: true) {
                cover.removeFromSuperview()
            }
        }
    }
}

/// The spinner over the screen a file is opened from, while QuickLook comes up.
final class QuickLookOpeningCover: UIView {
    static let identifier = "air.quicklook.opening"

    override init(frame: CGRect) {
        super.init(frame: frame)
        autoresizingMask = [.flexibleWidth, .flexibleHeight]
        backgroundColor = .clear

        // As the banner of a video being prepared (VideoSendProgressOverlay): white on a dark rounded plate.
        let plate = UIView()
        plate.backgroundColor = UIColor.black.withAlphaComponent(0.82)
        plate.layer.cornerRadius = 12
        plate.translatesAutoresizingMaskIntoConstraints = false

        let spinner = UIActivityIndicatorView(activityIndicatorStyle: .large)
        spinner.color = .white
        spinner.translatesAutoresizingMaskIntoConstraints = false
        spinner.startAnimating()

        plate.addSubview(spinner)
        addSubview(plate)
        NSLayoutConstraint.activate([
            plate.centerXAnchor.constraint(equalTo: centerXAnchor),
            plate.centerYAnchor.constraint(equalTo: centerYAnchor),
            plate.widthAnchor.constraint(equalToConstant: 88),
            plate.heightAnchor.constraint(equalToConstant: 88),
            spinner.centerXAnchor.constraint(equalTo: plate.centerXAnchor),
            spinner.centerYAnchor.constraint(equalTo: plate.centerYAnchor),
        ])

        isAccessibilityElement = true
        accessibilityLabel = String(localized: "launch_loading")
        accessibilityTraits = UIAccessibilityTraitUpdatesFrequently
        accessibilityIdentifier = QuickLookOpeningCover.identifier
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }
}
