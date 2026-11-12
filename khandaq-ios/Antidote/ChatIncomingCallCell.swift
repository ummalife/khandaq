// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit

/// A call from the other person in the transcript (redesign stage 3, E9); AirCallEntryCell draws it.
class ChatIncomingCallCell: AirCallEntryCell {
    override var isIncoming: Bool {
        return true
    }
}
