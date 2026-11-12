// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit

/// A file, a photo or a voice note the other person sent, as an entry of the transcript; see
/// ChatGenericFileCell.
class ChatIncomingFileCell: ChatGenericFileCell {
    override var isIncoming: Bool {
        return true
    }
}
