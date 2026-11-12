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
}

private extension ChatIncomingFileCellSnapshotTest {
    func verify(_ state: ChatGenericFileCellModel.State, progress: Float? = nil) {
        verifyFileCell(ChatIncomingFileCell(), model: fileEntry(ChatIncomingFileCellModel(), state: state), progress: progress)
    }
}
