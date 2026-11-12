// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation
    
class OCTSubmanagerFilesMock: NSObject, OCTSubmanagerFiles {
    /// How much of a demo file on its way has come. It stays put, so the screenshots of the UI walks are
    /// the same on every run.
    static let demoProgress: Float = 0.62

    private struct Subscription {
        weak var subscriber: OCTSubmanagerFilesProgressSubscriber?
        let message: OCTMessageAbstract
    }

    private var subscriptions: [Subscription] = []
    private var progressTimer: Timer?

    deinit {
        progressTimer?.invalidate()
    }

    func send(_ data: Data, withFileName fileName: String, to chat: OCTChat, failureBlock: ((Error) -> Void)? = nil) {
        // nop
    }
    
    func sendFile(atPath filePath: String, moveToUploads: Bool, to chat: OCTChat, failureBlock: ((Error) -> Void)? = nil) {
        // nop
    }
    
    func acceptFileTransfer(_ message: OCTMessageAbstract, failureBlock: ((Error) -> Void)? = nil) {
        // nop
    }
    
    func cancelFileTransfer(_ message: OCTMessageAbstract) throws {
        // nop
    }
    
    func retrySendingFile(_ message: OCTMessageAbstract, failureBlock: ((Error) -> Void)? = nil) {
        // nop
    }

    func resendPendingOutgoingFilesToAllOnlineFriends() {
        // nop
    }

    func pauseFileTransfer(_ pause: Bool, message: OCTMessageAbstract) throws {
        // nop
    }
    
    /// A file of the demo world that is on its way reports its share every second, as objcTox does
    /// while bytes come in; the stall watcher of the conversation hears from it too.
    func add(_ subscriber: OCTSubmanagerFilesProgressSubscriber, forFileTransfer message: OCTMessageAbstract) throws {
        subscriptions.append(Subscription(subscriber: subscriber, message: message))
        reportProgress()
        if progressTimer == nil {
            progressTimer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
                self?.reportProgress()
            }
        }
    }
    
    func remove(_ subscriber: OCTSubmanagerFilesProgressSubscriber, forFileTransfer message: OCTMessageAbstract) throws {
        subscriptions.removeAll { $0.subscriber == nil || $0.subscriber === subscriber }
        stopWhenIdle()
    }

    private func reportProgress() {
        subscriptions.removeAll { $0.subscriber == nil || $0.message.isInvalidated || $0.message.messageFile?.fileType != .loading }
        for subscription in subscriptions {
            subscription.subscriber?.submanagerFiles(onProgressUpdate: OCTSubmanagerFilesMock.demoProgress, message: subscription.message)
        }
        stopWhenIdle()
    }

    private func stopWhenIdle() {
        if subscriptions.isEmpty {
            progressTimer?.invalidate()
            progressTimer = nil
        }
    }
}
