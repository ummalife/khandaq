// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation

class ChatOutgoingFileCellModel: ChatGenericFileCellModel {
    var delivered: Bool = false
    /// KHANDAQ (0.2.57): the person's read receipt reached past this file (1:1 only; groups have no seen).
    var seen: Bool = false
}
