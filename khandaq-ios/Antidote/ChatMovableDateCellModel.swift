// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit

class ChatMovableDateCellModel: BaseCellModel {
    var dateString: String = ""

    // KHANDAQ (#99): when set, the cell shows a Telegram-style day separator ("Сегодня"/"Вчера"/date)
    // above its bubble. nil (the default) means no separator, and the cell is laid out exactly as before.
    var dateSeparator: String?

    // KHANDAQ (Figma): full-width "Непрочитанные сообщения" band above this cell's bubble, set on
    // the first unread incoming message when the chat is opened.
    var unreadSeparator: Bool = false

    // KHANDAQ (redesign stage 2): draw the day and unread marks the way the transcript of a conversation
    // with one person does (words where the text column starts, no plate and no rules) instead of the
    // centred pill and the full-width band. Group chats leave it off and keep their look.
    var transcriptMarks: Bool = false

    // KHANDAQ (redesign stage 3, E8 and E9): every entry of the transcript, a text, a file or a call,
    // shows its time in the time column, the author over the first entry of a run, and says who wrote
    // it to VoiceOver.
    var timeText = ""
    /// The author over the first entry of a run; nil for the entries that follow it.
    var authorText: String?
    var authorColor: UIColor = KQColor.ink2
    /// Who wrote it, for VoiceOver, on every entry of the run.
    var spokenAuthor = ""
}
