// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation

class StaticTableDefaultCellModel: StaticTableSelectableCellModel {
    enum RightImageType {
        case none
        case arrow
        case checkmark
    }

    var userStatus: UserStatus?
    var connectionStatus: ConnectionStatus?

    var title: String?
    var value: String?

    var rightButton: String?
    var rightButtonHandler: (() -> Void)?

    var rightImageType: RightImageType = .none

    // KHANDAQ (18.08): let a row's caption wrap instead of being clipped by the fixed one-line
    // title (the About screen credits the owner with a whole sentence). Off by default, so every
    // row that existed before keeps its exact single-line layout.
    var multilineTitle: Bool = false

    var userInteractionEnabled: Bool = true

    var canCopyValue: Bool = false
}
