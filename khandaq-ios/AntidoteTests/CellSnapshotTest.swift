// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation
@testable import Antidote

class CellSnapshotTest: SnapshotBaseTest {
    func updateCellLayout(_ cell: UITableViewCell) {
        let size = cell.systemLayoutSizeFitting(CGSize(width: 320, height: 1000))
        cell.frame = CGRect(x: 0, y: 0, width: 320, height: size.height)

        cell.setNeedsLayout()
        cell.layoutIfNeeded()
    }

    /// An entry of the transcript follows the width of the row (redesign stage 2), so its height is
    /// measured at a fixed width, as the table's self-sizing does; updateCellLayout lets the width float
    /// and measures one line.
    func layoutAsTheLogDoes(_ cell: UITableViewCell) {
        let width: CGFloat = 320
        cell.frame = CGRect(x: 0, y: 0, width: width, height: 100)
        cell.layoutIfNeeded()
        let size = cell.systemLayoutSizeFitting(CGSize(width: width, height: UILayoutFittingCompressedSize.height),
                                                withHorizontalFittingPriority: .required,
                                                verticalFittingPriority: .fittingSizeLevel)
        cell.frame = CGRect(x: 0, y: 0, width: width, height: ceil(size.height))
        cell.setNeedsLayout()
        cell.layoutIfNeeded()
    }

    /// A file entry as a conversation fills it (redesign stage 3, E8): the time, the size in English
    /// and every handle the conversation gives, so the entry shows the actions of its state.
    func fileEntry<Model: ChatGenericFileCellModel>(_ model: Model,
                                                    state: ChatGenericFileCellModel.State,
                                                    name: String = "route-plan.pdf",
                                                    bytes: Int64 = 1_260_000,
                                                    uti: String = "com.adobe.pdf") -> Model {
        model.state = state
        model.fileName = name
        model.fileSizeBytes = bytes
        model.fileSize = AirByteSize.text(bytes, locale: Locale(identifier: "en_US"))
        model.fileUTI = uti
        model.timeText = "9:41"
        model.startLoadingHandle = {}
        model.cancelHandle = {}
        model.pauseOrResumeHandle = {}
        model.retryHandle = {}
        model.openHandle = {}
        return model
    }

    /// Lays the file cell out as the log does, after the progress of its transfer and its preview came.
    func verifyFileCell(_ cell: ChatGenericFileCell, model: ChatGenericFileCellModel, progress: Float? = nil, preview: UIImage? = nil) {
        cell.setupWithTheme(theme, model: model)
        let progressObject = MockedChatProgressProtocol()
        if let progress = progress {
            cell.progressObject = progressObject
            progressObject.updateProgress?(progress)
        }
        if let preview = preview {
            cell.setButtonImage(preview)
        }
        layoutAsTheLogDoes(cell)
        verifyView(cell)
    }
}
