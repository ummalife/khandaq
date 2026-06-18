// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation

/**
    Bridge between objcTox subscriber and chat progress protocol.
 */
class ChatProgressBridge: NSObject, ChatProgressProtocol {
    var updateProgress: ((_ progress: Float) -> Void)?
    var updateEta: ((_ eta: CFTimeInterval, _ bytesPerSecond: OCTToxFileSize) -> Void)?
}

extension ChatProgressBridge: OCTSubmanagerFilesProgressSubscriber {
    func submanagerFiles(onProgressUpdate progress: Float, message: OCTMessageAbstract) {
        if let messageId = message.uniqueIdentifier {
            FileTransferStallWatcher.shared.progressReceived(for: messageId)
        }
        DispatchQueue.main.async { [weak self] in
            self?.updateProgress?(progress)
        }
    }

    func submanagerFiles(onEtaUpdate eta: CFTimeInterval, bytesPerSecond: OCTToxFileSize, message: OCTMessageAbstract) {
        DispatchQueue.main.async { [weak self] in
            self?.updateEta?(eta, bytesPerSecond)
        }
    }
}

/** Live group file transfer progress for visible cells. */
final class ChatGroupProgressBridge: NSObject, ChatProgressProtocol {
    var updateProgress: ((_ progress: Float) -> Void)?
    var updateEta: ((_ eta: CFTimeInterval, _ bytesPerSecond: OCTToxFileSize) -> Void)?

    private var timer: Timer?
    private weak var message: OCTMessageAbstract?
    private var lastBytes: OCTToxFileSize = 0
    private var lastTs: CFTimeInterval = 0

    func observe(_ message: OCTMessageAbstract) {
        self.message = message
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: 0.25, repeats: true) { [weak self] _ in
            self?.poll()
        }
        poll()
    }

    func stop() {
        timer?.invalidate()
        timer = nil
        message = nil
    }

    deinit {
        stop()
    }

    private func poll() {
        guard let message = message, let file = message.messageFile else {
            stop()
            return
        }

        guard file.fileType == .loading else {
            stop()
            return
        }

        let progress = file.groupTransferProgress
        updateProgress?(progress)

        if progress > 0, file.fileSize > 0 {
            let done = OCTToxFileSize(Double(file.fileSize) * Double(progress))
            let speed = estimateSpeed(bytesDone: done)
            if speed > 0 {
                updateEta?(0, speed)
            }
        }
    }

    private func estimateSpeed(bytesDone: OCTToxFileSize) -> OCTToxFileSize {
        let now = CFAbsoluteTimeGetCurrent()
        defer {
            lastBytes = bytesDone
            lastTs = now
        }

        guard lastTs > 0, bytesDone > lastBytes else {
            return 0
        }

        let delta = max(now - lastTs, 0.001)
        return OCTToxFileSize(Double(bytesDone - lastBytes) / delta)
    }
}
