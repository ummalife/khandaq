// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation

class ChatMovableDateCellModel: BaseCellModel {
    var dateString: String = ""

    // KHANDAQ (#99): when set, the cell shows a Telegram-style day separator ("Сегодня"/"Вчера"/date)
    // above its bubble. nil (the default) → no separator and the cell is laid out exactly as before.
    var dateSeparator: String?

    // KHANDAQ (Figma): full-width "Непрочитанные сообщения" band above this cell's bubble — set on
    // the first unread incoming message when the chat is opened.
    var unreadSeparator: Bool = false
}
