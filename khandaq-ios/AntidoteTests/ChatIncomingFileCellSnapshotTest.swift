// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation
import MobileCoreServices
@testable import Antidote

/// A file, a photo or a voice note someone sends us, as an entry of the transcript (redesign stage 3,
/// E8): the name and the size, the bar and what is left while it moves, and the action it waits for.
class ChatIncomingFileCellSnapshotTest: CellSnapshotTest {
    func testWaitingState() {
        verify(.waitingConfirmation)
    }

    func testLoading() {
        verify(.loading, progress: 0.43)
    }

    func testPaused() {
        verify(.paused, progress: 0.43)
    }

    func testCancelled() {
        verify(.cancelled)
    }

    func testDone() {
        verify(.done)
    }

    func testDoneWithImage() {
        let model = fileEntry(ChatIncomingFileCellModel(), state: .done, name: "icon.png", bytes: 3_140, uti: kUTTypePNG as String)
        model.previewSize = image.size
        model.caption = "The gate at the north end"
        verifyFileCell(ChatIncomingFileCell(), model: model, preview: image)
    }

    func testVoiceNote() {
        let model = fileEntry(ChatIncomingFileCellModel(), state: .done, name: "voice.m4a", bytes: 48_000, uti: "com.apple.m4a-audio")
        model.isVoiceMessage = true
        model.voiceDuration = 14
        verifyFileCell(ChatIncomingFileCell(), model: model)
    }

    func testVoiceNoteWaitsForAccept() {
        let model = fileEntry(ChatIncomingFileCellModel(), state: .waitingConfirmation, name: "voice.m4a", bytes: 48_000, uti: "com.apple.m4a-audio")
        model.isVoiceMessage = true
        verifyFileCell(ChatIncomingFileCell(), model: model)
    }

    /// KHANDAQ (tester 21.11, 8255): a file of another member of a group, alone in its run: their name over it and
    /// their tile beside it, level with its bottom, as beside a text.
    func testGroupFileOfAnotherMemberHasTheirTile() {
        let model = fileEntry(ChatIncomingFileCellModel(), state: .done)
        model.authorText = "Bilal"
        model.authorColor = AirTone.color(at: 4)
        model.authorTile = AirAuthorTile(style: .person(initials: "B", tone: 4), shows: true, onTap: nil)
        verifyFileCell(ChatIncomingFileCell(), model: model)
    }

    /// A photo of another member at the end of their run: the preview gives way to the column of the tile.
    func testGroupPhotoOfAnotherMemberHasTheirTile() {
        let model = fileEntry(ChatIncomingFileCellModel(), state: .done, name: "icon.png", bytes: 3_140, uti: kUTTypePNG as String)
        model.previewSize = image.size
        model.authorTile = AirAuthorTile(style: .person(initials: "B", tone: 4), shows: true, onTap: nil)
        verifyFileCell(ChatIncomingFileCell(), model: model, preview: image)
    }
}

private extension ChatIncomingFileCellSnapshotTest {
    func verify(_ state: ChatGenericFileCellModel.State, progress: Float? = nil) {
        verifyFileCell(ChatIncomingFileCell(), model: fileEntry(ChatIncomingFileCellModel(), state: state), progress: progress)
    }
}
