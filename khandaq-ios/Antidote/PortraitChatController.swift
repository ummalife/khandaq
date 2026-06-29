// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit

/// Chat screens use an inverted UITableView (Y-flip) for bottom-anchored scrolling.
/// Landscape rotation corrupts that rendering (blurred / washed-out cells), so chat stays portrait until
/// the message list is refactored without a table-level flip transform.
class PortraitChatController: KeyboardNotificationController {
    override var shouldAutorotate: Bool {
        return false
    }

    override var supportedInterfaceOrientations: UIInterfaceOrientationMask {
        return .portrait
    }
}
