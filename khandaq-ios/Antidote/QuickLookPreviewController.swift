// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation
import QuickLook

protocol QuickLookPreviewControllerDataSource: QLPreviewControllerDataSource {
    var previewController: QuickLookPreviewController? { get set }
}

class QuickLookPreviewController: QLPreviewController, QLPreviewControllerDelegate {
    var dataSourceStorage: QuickLookPreviewControllerDataSource?

    override func viewDidLoad() {
        super.viewDidLoad()
        delegate = self
        navigationItem.rightBarButtonItem = UIBarButtonItem(
            barButtonSystemItem: .done,
            target: self,
            action: #selector(dismissPreview)
        )
    }

    @objc private func dismissPreview() {
        dismiss(animated: true, completion: nil)
    }

    func previewController(_ controller: QLPreviewController, shouldOpen url: URL, for item: QLPreviewItem) -> Bool {
        return true
    }
}
