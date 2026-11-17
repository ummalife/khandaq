// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation
import MobileCoreServices
@testable import Antidote

/// Our own file or photo as an entry of the transcript (redesign stage 3, E8): what it waits for, the bar
/// while it moves, and the status mark at the far end.
class ChatOutgoingFileCellSnapshotTest: CellSnapshotTest {
    func testWaitingState() {
        verify(.waitingConfirmation)
    }

    func testWaitingForSomeoneAway() {
        let model = fileEntry(ChatOutgoingFileCellModel(), state: .waitingConfirmation)
        model.personName = "Yusuf"
        model.personOnline = false
        verifyFileCell(ChatOutgoingFileCell(), model: model)
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
        let model = fileEntry(ChatOutgoingFileCellModel(), state: .done)
        model.delivered = true
        model.takesReadReceipts = true
        verifyFileCell(ChatOutgoingFileCell(), model: model)
    }

    /// KHANDAQ (0.2.57): a group keeps the marks of 0.2.56 (SPEC: groups are unchanged). The file of
    /// testDone as the group controller makes it matches, pixel for pixel, the image testDone had in 0.2.56.
    func testDoneInAGroup() {
        let model = fileEntry(ChatOutgoingFileCellModel(), state: .done)
        model.delivered = true
        verifyFileCell(ChatOutgoingFileCell(), model: model)
    }

    func testWaitingStateWithImage() {
        verifyImage(.waitingConfirmation)
    }

    func testLoadingWithImage() {
        verifyImage(.loading, progress: 0.43)
    }

    func testPausedWithImage() {
        verifyImage(.paused, progress: 0.43)
    }

    func testCancelledWithImage() {
        verifyImage(.cancelled)
    }

    func testDoneWithImage() {
        verifyImage(.done)
    }
}

private extension ChatOutgoingFileCellSnapshotTest {
    func verify(_ state: ChatGenericFileCellModel.State, progress: Float? = nil) {
        let model = fileEntry(ChatOutgoingFileCellModel(), state: state)
        model.personName = "Yusuf"
        verifyFileCell(ChatOutgoingFileCell(), model: model, progress: progress)
    }

    func verifyImage(_ state: ChatGenericFileCellModel.State, progress: Float? = nil) {
        let model = fileEntry(ChatOutgoingFileCellModel(), state: state, name: "icon.png", bytes: 3_140, uti: kUTTypePNG as String)
        model.personName = "Yusuf"
        // Our photo to one person, whose read receipts mute sent and delivered (0.2.57).
        model.takesReadReceipts = true
        model.previewSize = image.size
        verifyFileCell(ChatOutgoingFileCell(), model: model, progress: progress, preview: image)
    }
}
