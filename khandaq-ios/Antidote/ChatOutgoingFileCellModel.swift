// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation

class ChatOutgoingFileCellModel: ChatGenericFileCellModel {
    var delivered: Bool = false
    /// KHANDAQ (0.2.57): the person's read receipt reached past this file (1:1 only; groups have no seen).
    var seen: Bool = false
    /// KHANDAQ (0.2.57): a file of a 1:1 conversation with a friend (AirStatusMarkView.takesReadReceipts).
    /// The group controllers and the Saved chat leave it false and keep the marks of 0.2.56.
    var takesReadReceipts: Bool = false
}
