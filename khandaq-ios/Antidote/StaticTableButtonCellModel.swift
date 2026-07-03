// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation

class StaticTableButtonCellModel: StaticTableSelectableCellModel {
    var title: String?
    // KHANDAQ design (Figma): destructive actions (e.g. "Выйти") render in red instead of accent green.
    var destructive: Bool = false
    // KHANDAQ design (Figma): optional leading SF Symbol; when set, the icon + label render as a
    // centered group (the MyID "Копировать"/"Показать QR-код" pill buttons). nil = plain label button.
    var iconName: String?
}
