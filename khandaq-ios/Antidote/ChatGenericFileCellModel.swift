// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit

class ChatGenericFileCellModel: ChatMovableDateCellModel {
    enum State {
        case waitingConfirmation
        /// KHANDAQ (0.2.57): the transfer was under way when the connection went; it goes on by itself from
        /// `transferProgress` once the person is back (OCTMessageFilePausedByConnection).
        case waitingConnection
        case loading
        case paused
        case cancelled
        case done
    }

    /// KHANDAQ (0.2.57): the state of a file that waits for an accept. One the lost connection cut
    /// (`cutByConnection`) waits for the connection with its bar until it is offered again: ours, offered again
    /// while the person is online (`offered`: it has a transfer again), waits for them to accept it.
    static func waitingState(cutByConnection: Bool, outgoing: Bool, offered: Bool) -> State {
        return (cutByConnection && !(outgoing && offered)) ? .waitingConnection : .waitingConfirmation
    }

    var state: State = .waitingConfirmation
    var fileName: String?
    var fileSize: String?
    var fileSizeBytes: Int64 = 0
    var fileUTI: String?
    /// KHANDAQ: Telegram-style caption rendered under the media in the same cell.
    var caption: String?
    /// KHANDAQ (#192, 0.2.61): the reactions under the file, photo or voice note, as chips; none for none.
    var reactionChips: [AirReactionChip] = []
    /// The reactions in words ("❤️ 2  👍"), nil for none: what VoiceOver says of the chips.
    var reactionsDisplay: String? {
        return ChatReactionsFormat.display(of: reactionChips)
    }

    var startLoadingHandle: (() -> Void)?
    var cancelHandle: (() -> Void)?
    var retryHandle: (() -> Void)?
    var pauseOrResumeHandle: (() -> Void)?
    var openHandle: (() -> Void)?

    // KHANDAQ (redesign stage 3, E8): the entry in the transcript.
    /// The size of the photo or video preview, known before the image is loaded so the row has its
    /// height at once; nil for a file shown by its name.
    var previewSize: CGSize?
    /// The other person of a conversation with one person, for "Waits for Yusuf to come online"; nil
    /// in a group.
    var personName: String?
    var personOnline = true
    /// The share already moved, for the bar before the first progress report (a group keeps it).
    var transferProgress: Float = 0

    var isVoiceMessage = false
    var voiceMessageId: String?
    var voiceTransferProgress: Float = 0
    var voiceDuration: TimeInterval = 0
    var voicePlayToggleHandle: (() -> Void)?
    // KHANDAQ (Android parity): scrub the voice note (the share 0 to 1 along the wave)
    var voiceSeekHandle: ((Float) -> Void)?

    /// KHANDAQ (tester 8263): an audio file that is here (VoiceMessageHelper.isInlineAudioFile), not a voice
    /// note: it plays in its entry with the player of the voice notes and the voice handles, at its own
    /// speed, and keeps its name row, a tap on which opens it in QuickLook (share, save, another app).
    var isAudioFile = false

    /// The entry has the player: a voice note, or an audio file.
    var playsInline: Bool {
        return isVoiceMessage || isAudioFile
    }
}
