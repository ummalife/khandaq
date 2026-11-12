// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit

/// A file, a photo or a voice note we sent, as an entry of the transcript with its status mark; see
/// ChatGenericFileCell.
class ChatOutgoingFileCell: ChatGenericFileCell {
    override var isIncoming: Bool {
        return false
    }
}
